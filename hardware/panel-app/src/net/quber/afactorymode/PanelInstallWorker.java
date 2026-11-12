package net.quber.afactorymode;

import android.content.Context;
import android.content.pm.PackageInfo;
import android.os.SystemClock;
import android.system.Os;
import android.system.OsConstants;
import android.system.StructStat;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileDescriptor;
import java.io.InputStream;
import org.json.JSONObject;

public final class PanelInstallWorker {
    private PanelInstallWorker() {}

    public static void main(String[] args) {
        String releaseId = "", nonce = "";
        int installed = 0;
        boolean installStarted = false, commandInFlight = false, createAttempted = false, released = false;
        int session = 0;
        try {
            MaintenanceRoot.initialize(240000L);
            if (args == null || args.length != 0) throw new IllegalArgumentException("worker_arguments");
            if (!(MaintenanceRoot.DIRECTORY + "/helper.apk").equals(System.getenv("CLASSPATH"))) throw new IllegalArgumentException("worker_classpath");
            Os.setsid();
            FileDescriptor sink = Os.open("/dev/null", OsConstants.O_RDWR | OsConstants.O_NOFOLLOW | MaintenanceRoot.closeOnExec(), 0);
            try {
                StructStat node = Os.fstat(sink);
                if (!OsConstants.S_ISCHR(node.st_mode) || node.st_uid != 0) throw new IllegalStateException("worker_stdio");
                Os.dup2(sink, 0); Os.dup2(sink, 1); Os.dup2(sink, 2);
            } finally { Os.close(sink); }
            MaintenanceRoot.ensureRootDirectory();
            Context context = MaintenanceRoot.systemContext();
            JSONObject job = new JSONObject(new String(MaintenanceRoot.readRoot("job.json", 8192), MaintenancePolicy.UTF8));
            if (job.length() != 6) throw new IllegalArgumentException("worker_job");
            releaseId = job.getString("releaseId"); nonce = job.getString("transportNonce");
            String caller = job.getString("caller");
            if (!releaseId.matches("[0-9a-f]{32}") || !nonce.matches("[0-9a-f]{8}")
                    || (!MaintenancePolicy.PACKAGE.equals(caller) && !MaintenancePolicy.BOOTSTRAP_PACKAGE.equals(caller))) {
                throw new IllegalArgumentException("worker_job");
            }
            if (!releaseId.equals(new String(MaintenanceRoot.readRoot("update.lock", 64), MaintenancePolicy.UTF8))) {
                throw new IllegalStateException("worker_lock");
            }
            byte[] helper = MaintenanceRoot.readRoot("helper.apk", 2 * 1024 * 1024);
            if (!job.getString("helperSha256").equals(MaintenanceTrust.sha256(helper))) throw new IllegalStateException("worker_helper_hash");
            MaintenanceRoot.verifySignedArchive(context, MaintenanceRoot.rootFile("helper.apk"), caller, job.getInt("callerVersion"));
            MaintenancePolicy.Release release = MaintenancePolicy.verify(MaintenanceRoot.readRoot("release.json", 8192), System.currentTimeMillis() / 1000L);
            if (!releaseId.equals(release.id)) throw new IllegalArgumentException("worker_release");
            MaintenancePolicy.verifyApk(context, MaintenanceRoot.rootFile("candidate.apk"), release);
            installed = MaintenancePolicy.installedVersion(context);
            if (installed != job.getInt("previousVersion") || release.versionCode <= installed) throw new IllegalStateException("worker_version");
            verifyFramework("/system/framework/pm.jar"); verifyFramework("/system/framework/am.jar");
            write("accepted", "worker_ready", "installing", releaseId, installed);
            Thread.sleep(1500L);
            MaintenanceRoot.checkTime();
            if (release.expiresAt <= System.currentTimeMillis() / 1000L) throw new IllegalStateException("worker_release_expired");
            write("accepted", "package_installing", "installing", releaseId, installed);
            java.lang.Process command = pm(MaintenanceInstallProtocol.create(release.size));
            createAttempted = true; commandInFlight = true;
            CommandResult created = await(command, 15000L);
            if (!created.finished) throw new IllegalStateException("install_session_completion_unknown");
            commandInFlight = false;
            if (created.exitCode != 0 || created.overflow) throw new IllegalStateException("install_session_failed");
            session = MaintenanceInstallProtocol.createdSession(created.output);
            JSONObject ownSession = new JSONObject();
            ownSession.put("releaseId", releaseId); ownSession.put("sessionId", session);
            MaintenanceRoot.writeRoot("install-session.json", ownSession.toString().getBytes(MaintenancePolicy.UTF8));
            command = pm(MaintenanceInstallProtocol.write(session, release.size));
            commandInFlight = true;
            CommandResult written = await(command, 30000L);
            if (!written.finished) throw new IllegalStateException("install_stream_completion_unknown");
            commandInFlight = false;
            if (written.exitCode != 0 || written.overflow || !MaintenanceInstallProtocol.written(written.output, release.size)) {
                throw new IllegalStateException("install_stream_failed");
            }
            MaintenancePolicy.verifyApk(context, MaintenanceRoot.rootFile("candidate.apk"), release);
            command = pm(MaintenanceInstallProtocol.commit(session));
            installStarted = true;
            commandInFlight = true;
            CommandResult outcome = await(command, 120000L);
            if (!outcome.finished) {
                write("error", "install_completion_unknown", "indeterminate", releaseId, installed);
                MaintenanceRoot.clearTransport(nonce);
                return;
            }
            commandInFlight = false;
            int current = MaintenancePolicy.installedVersion(context);
            if (outcome.exitCode != 0 || outcome.overflow || !MaintenanceInstallProtocol.succeeded(outcome.output)) {
                if (current != installed) throw new IllegalStateException("install_state_indeterminate");
                write("error", "package_install_failed", "error", releaseId, current);
                MaintenanceRoot.clearTransport(nonce);
                MaintenanceRoot.removeRootIfPresent("update.lock");
                return;
            }
            if (current != release.versionCode) throw new IllegalStateException("installed_version_mismatch");
            PackageInfo actual = context.getPackageManager().getPackageInfo(MaintenancePolicy.PACKAGE, 0);
            SystemBackupProtocol.requirePanelApk(actual.applicationInfo.sourceDir);
            MaintenancePolicy.verifyApk(context, new File(actual.applicationInfo.sourceDir), release);
            installed = current;
            MaintenanceRoot.clearTransport(nonce);
            write("ok", "installed_restart_requested", "installed", releaseId, installed);
            MaintenanceRoot.removeRootIfPresent("update.lock");
            released = true;
            java.lang.Process restart = MaintenanceRoot.launch("/system/framework/am.jar", "com.android.commands.am.Am",
                    "start", "--user", "0", "-n", MaintenancePolicy.PACKAGE + "/" + MaintenancePolicy.ACTIVITY,
                    "-a", "android.intent.action.MAIN", "-c", "android.intent.category.LAUNCHER");
            await(restart, 15000L);
        } catch (Throwable error) {
            try {
                if (!released) {
                    boolean uncertain = installStarted || commandInFlight || (createAttempted && session == 0);
                    if (!uncertain && session > 0) {
                        try {
                            CommandResult abandoned = await(pm(MaintenanceInstallProtocol.abandon(session)), 10000L);
                            uncertain = !abandoned.finished || abandoned.exitCode != 0 || abandoned.overflow
                                    || !MaintenanceInstallProtocol.succeeded(abandoned.output);
                        } catch (Exception abandonFailed) { uncertain = true; }
                    }
                    write("error", MaintenanceRoot.reason(error), uncertain ? "indeterminate" : "error", releaseId, installed);
                    if (nonce.matches("[0-9a-f]{8}")) MaintenanceRoot.clearTransport(nonce);
                    if (!uncertain && !releaseId.isEmpty()) MaintenanceRoot.removeRootIfPresent("update.lock");
                }
            } catch (Throwable ignored) { }
        }
    }

    private static java.lang.Process pm(String[] arguments) throws Exception {
        return MaintenanceRoot.launch("/system/framework/pm.jar", "com.android.commands.pm.Pm", arguments);
    }

    private static void write(String status, String reason, String worker, String release, int installed) throws Exception {
        MaintenanceRoot.writeRoot("result.json", MaintenanceRoot.result(status, reason, worker, release, installed)
                .toString().getBytes(MaintenancePolicy.UTF8));
    }

    private static void verifyFramework(String path) throws Exception {
        StructStat node = Os.lstat(path);
        if (!OsConstants.S_ISREG(node.st_mode) || node.st_uid != 0 || (node.st_mode & 0022) != 0
                || node.st_size < 1 || node.st_size > 1024 * 1024 || !path.equals(new File(path).getCanonicalPath())) {
            throw new IllegalStateException("framework_command");
        }
    }

    private static CommandResult await(java.lang.Process process, long milliseconds) throws Exception {
        process.getOutputStream().close();
        final Collector collector = new Collector(process.getInputStream());
        Thread reader = new Thread(collector, "panel-install-output"); reader.setDaemon(true); reader.start();
        long end = SystemClock.elapsedRealtime() + milliseconds;
        while (SystemClock.elapsedRealtime() < end) {
            MaintenanceRoot.checkTime();
            try {
                int code = process.exitValue();
                reader.join(1000L);
                return new CommandResult(true, code, collector.text(), collector.overflow || reader.isAlive());
            } catch (IllegalThreadStateException running) { Thread.sleep(100L); }
        }
        return new CommandResult(false, -1, "", false);
    }

    private static final class Collector implements Runnable {
        private final InputStream input;
        private final ByteArrayOutputStream output = new ByteArrayOutputStream();
        volatile boolean overflow;
        Collector(InputStream input) { this.input = input; }
        public void run() {
            byte[] bytes = new byte[1024];
            try {
                int count;
                while ((count = input.read(bytes)) != -1) {
                    synchronized (output) {
                        if (count <= 8192 - output.size()) output.write(bytes, 0, count); else overflow = true;
                    }
                }
            } catch (Exception error) { overflow = true; }
            finally { try { input.close(); } catch (Exception ignored) { } }
        }
        String text() { synchronized (output) { return new String(output.toByteArray(), MaintenancePolicy.UTF8); } }
    }

    private static final class CommandResult {
        final boolean finished, overflow;
        final int exitCode;
        final String output;
        CommandResult(boolean finished, int exitCode, String output, boolean overflow) {
            this.finished = finished; this.exitCode = exitCode; this.output = output; this.overflow = overflow;
        }
    }
}
