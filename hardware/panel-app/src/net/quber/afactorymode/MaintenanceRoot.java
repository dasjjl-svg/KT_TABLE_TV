package net.quber.afactorymode;

import android.content.Context;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.os.Process;
import android.os.SystemClock;
import android.system.ErrnoException;
import android.system.Os;
import android.system.OsConstants;
import android.system.StructStat;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileDescriptor;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.lang.reflect.Method;
import java.nio.channels.FileLock;
import java.util.Arrays;
import org.json.JSONObject;

public final class MaintenanceRoot {
    static final String DIRECTORY = "/data/local/tmp/home-panel-maintenance";
    private static StructStat directoryIdentity;
    private static long deadline;
    private static final int CLOSE_ON_EXEC = 0x80000, DIRECTORY_OPEN = 0x4000;

    private MaintenanceRoot() {}

    public static void main(String[] args) {
        JSONObject result;
        try {
            initialize(35000L);
            if (args == null || args.length != 1 || !args[0].matches("status|startUpdate|configureHome|restoreHome|repairHome")) {
                throw new IllegalArgumentException("maintenance_mode");
            }
            Context context = systemContext();
            String caller = verifyCaller(context);
            ensureRootDirectory();
            if ("status".equals(args[0])) result = status(context);
            else {
                try (MutationLock mutation = new MutationLock()) {
                    if ("startUpdate".equals(args[0])) result = startUpdate(context, caller);
                    else {
                        if (existsRoot("update.lock")) throw new IllegalStateException("update_in_progress");
                        result = MaintenanceHome.run(context, args[0]);
                    }
                }
            }
        } catch (Throwable error) {
            result = failure(reason(error));
        }
        System.out.println(result.toString());
    }

    static void initialize(long duration) throws Exception {
        if (Process.myUid() != 0 || Os.getuid() != 0 || Os.geteuid() != 0) throw new IllegalStateException("root_identity");
        if (!"CT1105_SB".equals(android.os.Build.MODEL) || android.os.Build.VERSION.SDK_INT != 22
                || !"armeabi-v7a".equals(android.os.Build.CPU_ABI)) {
            throw new IllegalStateException("root_platform");
        }
        Os.umask(0077);
        deadline = SystemClock.elapsedRealtime() + duration;
        directoryIdentity = null;
        StructStat launcher = Os.lstat("/system/bin/app_process32");
        if (!OsConstants.S_ISREG(launcher.st_mode) || launcher.st_uid != 0 || (launcher.st_mode & 0022) != 0
                || (launcher.st_mode & 0100) == 0) throw new IllegalStateException("root_launcher");
    }

    static Context systemContext() throws Exception {
        if (android.os.Looper.myLooper() == null) {
            if (android.os.Looper.getMainLooper() == null) android.os.Looper.prepareMainLooper();
            else android.os.Looper.prepare();
        }
        Class<?> thread = Class.forName("android.app.ActivityThread");
        Object current = thread.getMethod("systemMain").invoke(null);
        Object context = thread.getMethod("getSystemContext").invoke(current);
        if (!(context instanceof Context)) throw new IllegalStateException("root_context");
        return (Context) context;
    }

    private static String verifyCaller(Context context) throws Exception {
        String apk = System.getenv("CLASSPATH");
        SystemBackupProtocol.requirePanelApk(apk);
        String caller = apk.startsWith("/data/app/" + MaintenancePolicy.PACKAGE + "-")
                ? MaintenancePolicy.PACKAGE : MaintenancePolicy.BOOTSTRAP_PACKAGE;
        PackageInfo installed = context.getPackageManager().getPackageInfo(caller, PackageManager.GET_SIGNATURES);
        if (!apk.equals(installed.applicationInfo.sourceDir)) throw new IllegalStateException("caller_path");
        verifySignedArchive(context, new File(apk), caller, installed.versionCode);
        checkedFile(apk, -1, 2 * 1024 * 1024);
        return caller;
    }

    static void verifySignedArchive(Context context, File apk, String expectedPackage, int expectedVersion) throws Exception {
        PackageInfo archive = context.getPackageManager().getPackageArchiveInfo(apk.getAbsolutePath(), PackageManager.GET_SIGNATURES);
        if (archive == null || !expectedPackage.equals(archive.packageName) || archive.versionCode != expectedVersion
                || archive.signatures == null || archive.signatures.length != 1
                || !MaintenanceSignerPolicy.accepts(expectedPackage, expectedVersion,
                        MaintenanceTrust.sha256(archive.signatures[0].toByteArray()))) {
            throw new IllegalStateException("helper_signature");
        }
    }

    static void ensureRootDirectory() throws Exception {
        checkTime();
        try {
            Os.mkdir(DIRECTORY, 0700);
            FileDescriptor created = Os.open(DIRECTORY, OsConstants.O_RDONLY | DIRECTORY_OPEN | OsConstants.O_NOFOLLOW | CLOSE_ON_EXEC, 0);
            try {
                StructStat node = Os.fstat(created);
                if (!OsConstants.S_ISDIR(node.st_mode) || node.st_uid != 0 || (node.st_mode & 0777) != 0700) {
                    throw new IllegalStateException("root_directory_created");
                }
                Os.fchown(created, 0, 0);
            } finally { Os.close(created); }
        }
        catch (ErrnoException error) { if (error.errno != OsConstants.EEXIST) throw error; }
        StructStat now = Os.lstat(DIRECTORY);
        if (!OsConstants.S_ISDIR(now.st_mode) || now.st_uid != 0 || now.st_gid != 0 || (now.st_mode & 0777) != 0700
                || !DIRECTORY.equals(new File(DIRECTORY).getCanonicalPath())) throw new IllegalStateException("root_directory");
        if (directoryIdentity != null && !sameNode(directoryIdentity, now)) throw new IllegalStateException("root_directory_changed");
        directoryIdentity = now;
    }

    static File rootFile(String name) throws Exception {
        MaintenancePathPolicy.basename(name);
        ensureRootDirectory();
        return new File(DIRECTORY, name);
    }

    static boolean existsRoot(String name) throws Exception {
        File path = rootFile(name);
        try { checkedFile(path.getAbsolutePath(), 0, 2 * 1024 * 1024); return true; }
        catch (ErrnoException absent) { if (absent.errno == OsConstants.ENOENT) return false; throw absent; }
    }

    static byte[] readRoot(String name, int limit) throws Exception {
        return readChecked(rootFile(name).getAbsolutePath(), 0, limit);
    }

    static void writeRoot(String name, byte[] bytes) throws Exception {
        if (bytes == null || bytes.length > 8192) throw new IllegalArgumentException("root_record_size");
        String temporaryName = MaintenancePathPolicy.temporary(name);
        File temporary = rootFile(temporaryName);
        removeRootIfPresent(temporaryName);
        writeNew(temporary.getAbsolutePath(), bytes);
        File destination = rootFile(name);
        if (existsRoot(name)) checkedFile(destination.getAbsolutePath(), 0, 8192);
        Os.rename(temporary.getAbsolutePath(), destination.getAbsolutePath());
        syncDirectory();
    }

    static void removeRootIfPresent(String name) throws Exception {
        File file = rootFile(name);
        if (existsRoot(name)) { Os.remove(file.getAbsolutePath()); syncDirectory(); }
    }

    static void checkTime() {
        if (SystemClock.elapsedRealtime() >= deadline) throw new IllegalStateException("maintenance_timeout");
    }

    private static StructStat checkedFile(String path, int owner, int maximum) throws Exception {
        checkTime();
        StructStat node = Os.lstat(path);
        if (!OsConstants.S_ISREG(node.st_mode) || node.st_nlink != 1 || node.st_size < 0 || node.st_size > maximum
                || (owner >= 0 ? node.st_uid != owner : node.st_uid != 0 && node.st_uid != 1000)
                || (node.st_mode & 0022) != 0 || !path.equals(new File(path).getCanonicalPath())) {
            throw new IllegalStateException("checked_file");
        }
        if (owner == 0 && path.startsWith(DIRECTORY + "/") && (node.st_mode & 0777) != 0600) {
            throw new IllegalStateException("root_file_mode");
        }
        return node;
    }

    private static byte[] readChecked(String path, int owner, int maximum) throws Exception {
        StructStat before = checkedFile(path, owner, maximum);
        FileDescriptor descriptor = Os.open(path, OsConstants.O_RDONLY | OsConstants.O_NOFOLLOW | OsConstants.O_NONBLOCK | CLOSE_ON_EXEC, 0);
        try (FileInputStream input = new FileInputStream(descriptor)) {
            if (!sameFile(before, Os.fstat(descriptor))) throw new IllegalStateException("file_changed");
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            byte[] buffer = new byte[8192];
            int count;
            while ((count = input.read(buffer)) != -1) {
                checkTime();
                if (count > maximum - output.size()) throw new IllegalStateException("file_size");
                output.write(buffer, 0, count);
            }
            if (!sameFile(before, Os.fstat(descriptor)) || !sameFile(before, Os.lstat(path))
                    || output.size() != before.st_size) throw new IllegalStateException("file_changed");
            return output.toByteArray();
        }
    }

    private static void writeNew(String path, byte[] bytes) throws Exception {
        ensureRootDirectory();
        FileDescriptor descriptor = Os.open(path, OsConstants.O_WRONLY | OsConstants.O_CREAT | OsConstants.O_EXCL
                | OsConstants.O_NOFOLLOW | CLOSE_ON_EXEC, 0600);
        try (FileOutputStream output = new FileOutputStream(descriptor)) {
            Os.fchown(descriptor, 0, 0);
            Os.fchmod(descriptor, 0600);
            checkTime();
            output.write(bytes);
            Os.fsync(descriptor);
        }
        checkedFile(path, 0, 2 * 1024 * 1024);
        syncDirectory();
    }

    private static void syncDirectory() throws Exception {
        ensureRootDirectory();
        FileDescriptor directory = Os.open(DIRECTORY, OsConstants.O_RDONLY | DIRECTORY_OPEN | OsConstants.O_NOFOLLOW | CLOSE_ON_EXEC, 0);
        try {
            if (!sameNode(directoryIdentity, Os.fstat(directory))) throw new IllegalStateException("root_directory_changed");
            Os.fsync(directory);
        } finally { Os.close(directory); }
    }

    private static boolean sameNode(StructStat first, StructStat second) {
        return first.st_dev == second.st_dev && first.st_ino == second.st_ino && first.st_uid == second.st_uid
                && first.st_gid == second.st_gid && first.st_mode == second.st_mode;
    }

    private static boolean sameFile(StructStat first, StructStat second) {
        return sameNode(first, second) && first.st_nlink == second.st_nlink && first.st_size == second.st_size
                && first.st_mtime == second.st_mtime && first.st_ctime == second.st_ctime;
    }

    private static JSONObject startUpdate(Context context, String caller) throws Exception {
        if (existsRoot("update.lock")) return result("error", "update_in_progress", "indeterminate", "", MaintenancePolicy.installedVersion(context));
        PackageInfo callerInfo = context.getPackageManager().getPackageInfo(caller, PackageManager.GET_SIGNATURES);
        MaintenanceDownload download = new MaintenanceDownload(PanelBuildConfig.MAINTENANCE_ORIGIN,
                PanelBuildConfig.MAINTENANCE_TOKEN, new MaintenanceDownload.Clock() {
                    @Override public long now() { return SystemClock.elapsedRealtime(); }
                }, Math.min(SystemClock.elapsedRealtime() + 18000L, deadline - 12000L));
        byte[] envelope;
        try { envelope = download.release(); }
        catch (MaintenanceDownload.RetryableException unavailable) {
            return result("error", reason(unavailable), "idle", "", MaintenancePolicy.installedVersion(context));
        }
        if (envelope == null) return result("ok", "no_release", "idle", "", MaintenancePolicy.installedVersion(context));
        MaintenancePolicy.Release release = MaintenancePolicy.verify(envelope, System.currentTimeMillis() / 1000L);
        if (release.versionCode < 40) throw new IllegalArgumentException("release_legacy_version");
        int previous = MaintenancePolicy.installedVersion(context);
        if (release.versionCode <= previous) return result("ok", "installed_version_verified", "installed", release.id, previous);
        byte[] candidate;
        try { candidate = download.apk(release.sha256, release.size); }
        catch (MaintenanceDownload.RetryableException unavailable) {
            return result("error", reason(unavailable), "idle", release.id, previous);
        }
        if (candidate.length != release.size || !release.sha256.equals(MaintenanceTrust.sha256(candidate))) throw new IllegalStateException("candidate_hash");
        checkTime();
        byte[] helper = readChecked(callerInfo.applicationInfo.sourceDir, -1, 2 * 1024 * 1024);
        String nonce = System.getenv("PANEL_REQUEST_NONCE");
        if (nonce == null || !nonce.matches("[0-9a-f]{8}")) throw new IllegalStateException("request_nonce");
        writeNew(rootFile("update.lock").getAbsolutePath(), release.id.getBytes(MaintenancePolicy.UTF8));
        boolean launched = false;
        try {
            for (String name : new String[]{"helper.apk", "candidate.apk", "release.json", "job.json"}) removeRootIfPresent(name);
            writeNew(rootFile("helper.apk").getAbsolutePath(), helper);
            writeNew(rootFile("candidate.apk").getAbsolutePath(), candidate);
            writeNew(rootFile("release.json").getAbsolutePath(), envelope);
            verifySignedArchive(context, rootFile("helper.apk"), caller, callerInfo.versionCode);
            MaintenancePolicy.verifyApk(context, rootFile("candidate.apk"), release);
            JSONObject job = new JSONObject();
            job.put("releaseId", release.id); job.put("caller", caller); job.put("callerVersion", callerInfo.versionCode);
            job.put("previousVersion", previous); job.put("transportNonce", nonce); job.put("helperSha256", MaintenanceTrust.sha256(helper));
            writeRoot("job.json", job.toString().getBytes(MaintenancePolicy.UTF8));
            JSONObject accepted = result("accepted", "worker_queued", "installing", release.id, previous);
            writeRoot("result.json", accepted.toString().getBytes(MaintenancePolicy.UTF8));
            java.lang.Process worker = launch("/data/local/tmp/home-panel-maintenance/helper.apk",
                    "net.quber.afactorymode.PanelInstallWorker");
            launched = true;
            worker.getOutputStream().close();
            long readyUntil = SystemClock.elapsedRealtime() + 5000L;
            boolean ready = false;
            while (SystemClock.elapsedRealtime() < readyUntil) {
                JSONObject observed = new JSONObject(new String(readRoot("result.json", 8192), MaintenancePolicy.UTF8));
                if (release.id.equals(observed.optString("releaseId")) && "worker_ready".equals(observed.optString("reason"))) {
                    ready = true; break;
                }
                try { worker.exitValue(); throw new IllegalStateException("worker_ended_before_ready"); }
                catch (IllegalThreadStateException running) { }
                Thread.sleep(50);
            }
            if (!ready) throw new IllegalStateException("worker_ready_timeout");
            worker.getInputStream().close();
            worker.getErrorStream().close();
            return accepted;
        } catch (Exception failure) {
            writeRoot("result.json", result("error", launched ? "worker_start_indeterminate" : "worker_start_failed",
                    launched ? "indeterminate" : "error", release.id, previous).toString().getBytes(MaintenancePolicy.UTF8));
            if (!launched) removeRootIfPresent("update.lock");
            throw failure;
        } finally { Arrays.fill(candidate, (byte) 0); Arrays.fill(helper, (byte) 0); }
    }

    static java.lang.Process launch(String classPath, String main, String... args) throws Exception {
        checkTime();
        if (!(DIRECTORY + "/helper.apk").equals(classPath) && !"/system/framework/pm.jar".equals(classPath)
                && !"/system/framework/am.jar".equals(classPath)) throw new IllegalArgumentException("worker_classpath");
        String[] command = new String[3 + args.length];
        command[0] = "/system/bin/app_process32"; command[1] = "/system/bin"; command[2] = main;
        System.arraycopy(args, 0, command, 3, args.length);
        ProcessBuilder builder = new ProcessBuilder(command);
        builder.environment().put("CLASSPATH", classPath);
        builder.environment().put("ANDROID_ROOT", "/system");
        builder.environment().put("ANDROID_DATA", "/data");
        builder.environment().remove("LD_PRELOAD");
        builder.environment().remove("LD_LIBRARY_PATH");
        builder.directory(new File("/system/bin"));
        builder.redirectErrorStream(true);
        return builder.start();
    }

    private static final class MutationLock implements AutoCloseable {
        private final FileOutputStream output;
        private final FileLock lock;
        MutationLock() throws Exception {
            File file = rootFile("maintenance.lock");
            if (!existsRoot("maintenance.lock")) {
                try { writeNew(file.getAbsolutePath(), new byte[0]); }
                catch (ErrnoException existing) { if (existing.errno != OsConstants.EEXIST) throw existing; }
            }
            StructStat before = checkedFile(file.getAbsolutePath(), 0, 0);
            FileDescriptor descriptor = Os.open(file.getAbsolutePath(), OsConstants.O_RDWR | OsConstants.O_NOFOLLOW | CLOSE_ON_EXEC, 0);
            output = new FileOutputStream(descriptor);
            FileLock acquired = null;
            try {
                if (!sameFile(before, Os.fstat(descriptor))) throw new IllegalStateException("maintenance_lock_changed");
                acquired = output.getChannel().tryLock();
                if (acquired == null) throw new IllegalStateException("maintenance_busy");
                lock = acquired;
            } catch (Exception error) {
                if (acquired != null) acquired.release();
                output.close();
                throw error;
            }
        }
        public void close() throws Exception { try { lock.release(); } finally { output.close(); } }
    }

    private static JSONObject status(Context context) throws Exception {
        int installed = MaintenancePolicy.installedVersion(context);
        JSONObject saved;
        if (!existsRoot("result.json")) {
            saved = result("ok", "status_ready", "idle", "", installed);
        } else saved = new JSONObject(new String(readRoot("result.json", 8192), MaintenancePolicy.UTF8));
        saved.put("status", "ok"); saved.put("installedVersion", installed);
        saved.put("home", MaintenanceHome.home(context));
        saved.remove("homeEvidence"); saved.remove("homeEvidenceError");
        try {
            saved.put("homeEvidence", MaintenanceHome.evidence(context));
            if (saved.toString().getBytes(MaintenancePolicy.UTF8).length > 8191) throw new IllegalStateException("home_evidence_size");
        } catch (Exception error) {
            saved.remove("homeEvidence");
            saved.put("homeEvidenceError", reason(error));
        }
        saved.remove("startupEvidence"); saved.remove("startupEvidenceError");
        try {
            saved.put("startupEvidence", StartupEvidence.collect(context));
            if (saved.toString().getBytes(MaintenancePolicy.UTF8).length > 8191) throw new IllegalStateException("startup_evidence_size");
        } catch (Exception error) {
            saved.remove("startupEvidence");
            saved.put("startupEvidenceError", reason(error));
        }
        if (saved.toString().getBytes(MaintenancePolicy.UTF8).length > 8191) saved.remove("startupEvidenceError");
        saved.remove("crashEvidence");
        try {
            saved.put("crashEvidence", StartupCrashEvidence.collect(context));
            if (saved.toString().getBytes(MaintenancePolicy.UTF8).length > 8191) saved.remove("crashEvidence");
        } catch (Exception unavailable) { saved.remove("crashEvidence"); }
        if (saved.toString().getBytes(MaintenancePolicy.UTF8).length > 8191) throw new IllegalStateException("maintenance_status_size");
        return saved;
    }

    static int closeOnExec() { return CLOSE_ON_EXEC; }

    static JSONObject result(String status, String reason, String worker, String release, int installed) throws Exception {
        JSONObject result = new JSONObject();
        result.put("status", status); result.put("reason", reason); result.put("workerStatus", worker);
        result.put("releaseId", release); result.put("installedVersion", installed); result.put("home", "unknown");
        return result;
    }

    static JSONObject failure(String reason) {
        try { return result("error", reason, "error", "", 0); }
        catch (Exception impossible) { throw new IllegalStateException(impossible); }
    }

    static String reason(Throwable error) {
        String message = error.getMessage();
        return message != null && message.matches("[a-z][a-z0-9_]{0,79}") ? message : "maintenance_failed";
    }

    static void clearTransport(String nonce) throws Exception {
        if (nonce == null || !nonce.matches("[0-9a-f]{8}")) throw new IllegalArgumentException("transport_nonce");
        Class<?> properties = Class.forName("android.os.SystemProperties");
        Method get = properties.getMethod("get", String.class);
        String value = (String) get.invoke(null, SystemBackupProtocol.KEY);
        if (value.length() == 0) return;
        if (!value.matches(nonce + ":(ok|error)")) return;
        if (!value.equals(get.invoke(null, SystemBackupProtocol.KEY))) return;
        properties.getMethod("set", String.class, String.class).invoke(null, SystemBackupProtocol.KEY, "");
        if (value.equals(get.invoke(null, SystemBackupProtocol.KEY))) throw new IllegalStateException("transport_clear");
    }
}
