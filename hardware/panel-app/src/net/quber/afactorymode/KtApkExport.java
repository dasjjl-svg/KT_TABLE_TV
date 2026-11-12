package net.quber.afactorymode;

import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Looper;
import android.os.Process;
import android.os.SystemClock;
import android.system.ErrnoException;
import android.system.Os;
import android.system.OsConstants;
import android.system.StructStat;
import java.io.ByteArrayOutputStream;
import java.io.Closeable;
import java.io.File;
import java.io.FileDescriptor;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.Proxy;
import java.net.URL;
import java.nio.charset.Charset;
import java.security.MessageDigest;
import java.util.LinkedHashSet;
import java.util.Timer;
import java.util.TimerTask;
import java.util.concurrent.atomic.AtomicBoolean;
import org.json.JSONArray;
import org.json.JSONObject;

public final class KtApkExport {
    private static final long MAX_FILE = 96L * 1024L * 1024L;
    private static final long MAX_TOTAL = 192L * 1024L * 1024L;
    private static final int MAX_FILES = 4;
    private static final long MAX_LIBRARY = 16L * 1024L * 1024L;
    private static final String[] LIBRARY_NAMES = {"libsysmanagerservice_jni.so", "libhisysmanagerclient.so", "libhisysmanagerservice.so"};
    private static final int MAX_SUMMARY = 32768;
    private static final long BUDGET_MS = 150000;
    private static final Charset UTF8 = Charset.forName("UTF-8");
    private static final AtomicBoolean RUNNING = new AtomicBoolean(false);

    private enum Target {
        MIDDLEWARE("com.kt.gigagenie.ollehtv", PanelBuildConfig.COLLECTOR_ORIGIN,
                PanelBuildConfig.COLLECTOR_TOKEN),
        HOME_APP("com.kt.gigagenie.launcher", PanelBuildConfig.HOME_COLLECTOR_ORIGIN,
                PanelBuildConfig.HOME_COLLECTOR_TOKEN),
        SETTINGS_APP("com.android.settings", PanelBuildConfig.SETTINGS_COLLECTOR_ORIGIN,
                PanelBuildConfig.SETTINGS_COLLECTOR_TOKEN);

        final String packageName;
        final String collectorOrigin;
        final String collectorToken;

        Target(String packageName, String collectorOrigin, String collectorToken) {
            this.packageName = packageName;
            this.collectorOrigin = collectorOrigin;
            this.collectorToken = collectorToken;
        }
    }

    private KtApkExport() {}

    public interface Callback { void complete(Result result); }

    public static final class Result {
        public final String message;
        public final String diagnostics;
        Result(String message, String diagnostics) {
            this.message = message;
            this.diagnostics = diagnostics;
        }
    }

    public static boolean start(Context context, final Callback callback) {
        return start(context, callback, Target.MIDDLEWARE);
    }

    public static boolean startHomeApp(Context context, final Callback callback) {
        return start(context, callback, Target.HOME_APP);
    }

    public static boolean startSettingsApp(Context context, final Callback callback) {
        return start(context, callback, Target.SETTINGS_APP);
    }

    public static boolean startNetworkAdb(Context context, final Callback callback) {
        return start(context, callback, Target.SETTINGS_APP, Operation.ADB_SETUP);
    }

    public static boolean startServiceInspection(Context context, final Callback callback) {
        return start(context, callback, Target.SETTINGS_APP, Operation.SERVICE_INSPECTION);
    }

    public static boolean startSystemBackup(Context context, final Callback callback) {
        return startSystemBackup(context, callback, BackupMode.SYSTEM);
    }

    public static boolean startSpiIdentity(Context context, final Callback callback) {
        return startSystemBackup(context, callback, BackupMode.SPI_IDENTITY);
    }

    public static boolean startSpiBackup(Context context, final Callback callback) {
        return startSystemBackup(context, callback, BackupMode.SPI_BACKUP);
    }

    public static boolean startSpiSamples(Context context, final Callback callback) {
        return startSystemBackup(context, callback, BackupMode.SPI_READ);
    }

    private enum BackupMode { SYSTEM, SPI_IDENTITY, SPI_BACKUP, SPI_READ }

    private static boolean startSystemBackup(Context context, final Callback callback, final BackupMode mode) {
        if (context == null || callback == null || !RUNNING.compareAndSet(false, true)) return false;
        final Context application = context.getApplicationContext();
        try {
            new Thread(new Runnable() {
                public void run() {
                    Result result;
                    try { result = mode == BackupMode.SPI_READ ? SystemBackupExport.runSpiSamples(application)
                            : mode == BackupMode.SPI_IDENTITY ? SystemBackupExport.runSpiIdentity(application)
                            : mode == BackupMode.SPI_BACKUP ? SystemBackupExport.runSpiBackup(application) : SystemBackupExport.run(application); }
                    catch (Exception error) { result = new Result("백업을 완료하지 못했습니다.", error.getClass().getSimpleName()); }
                    finally { RUNNING.set(false); }
                    callback.complete(result);
                }
            }, "system-backup-export").start();
            return true;
        } catch (RuntimeException error) { RUNNING.set(false); return false; }
    }

    private enum Operation { COLLECT, ADB_SETUP, SERVICE_INSPECTION }

    private static boolean start(Context context, final Callback callback, final Target target) {
        return start(context, callback, target, Operation.COLLECT);
    }

    private static boolean start(Context context, final Callback callback, final Target target, final Operation operation) {
        if (context == null || callback == null || !RUNNING.compareAndSet(false, true)) return false;
        final Context application = context.getApplicationContext();
        try {
            new Thread(new Runnable() {
                @Override public void run() {
                    Result result;
                    try { result = new Collection(application, target, operation).run(); }
                    catch (RuntimeException error) {
                        result = new Result("분석 파일 수집을 완료하지 못했습니다.", "collection.error=" + error.getClass().getSimpleName());
                    } finally { RUNNING.set(false); }
                    callback.complete(result);
                }
            }, "kt-apk-export").start();
            return true;
        } catch (RuntimeException error) {
            RUNNING.set(false);
            return false;
        }
    }

    private static final class Collection {
        private final Context context;
        private final Target target;
        private final Operation operation;
        private final Diagnostics diagnostics = new Diagnostics();
        private final JSONArray artifacts = new JSONArray();
        private final long deadline = SystemClock.elapsedRealtime() + BUDGET_MS;
        private final AtomicBoolean expired = new AtomicBoolean(false);
        private volatile HttpURLConnection activeConnection;
        private volatile FileInputStream activeFile;
        private URL origin;
        private boolean targetFound;
        private String packageVersion = "";
        private String nativeLibraryDir = "";
        private int failures;
        private int uploadFailures;
        private long attemptedBytes;

        Collection(Context context, Target target, Operation operation) {
            this.context = context;
            this.target = target;
            this.operation = operation;
        }

        Result run() {
            if (Looper.myLooper() == Looper.getMainLooper()) {
                return new Result("백그라운드에서 실행해야 합니다.", "collection.error=main_thread");
            }
            Timer timer = new Timer("kt-apk-export-deadline", true);
            timer.schedule(new TimerTask() {
                @Override public void run() {
                    expired.set(true);
                    HttpURLConnection connection = activeConnection;
                    try { if (connection != null) connection.disconnect(); }
                    finally { close(activeFile); }
                }
            }, BUDGET_MS);
            boolean reportSent = false;
            try {
                origin = collectorOrigin();
                if (operation != Operation.COLLECT) {
                    if (target != Target.SETTINGS_APP) throw new IOException("invalid_adb_target");
                    diagnostics.add(operation == Operation.ADB_SETUP ? "adb.phase" : "inspection.phase", "preflight");
                    postSummary(summary().toString().getBytes(UTF8));
                    checkDeadline();
                    if (operation == Operation.ADB_SETUP) diagnostics.add("adb.setup", AdbNetworkSetup.run(context));
                    else diagnostics.add("inspection", AdbNetworkSetup.inspectRuntime(context));
                }
                diagnostics.add("debug", DeviceDebugInfo.collect(context));
                if (target == Target.SETTINGS_APP) diagnostics.add("usb", DeviceUsbInfo.collect());
                diagnostics.add("runtime", DeviceRuntimeInfo.collect());
                checkDeadline();
                inspectAndSend();
                checkDeadline();
                JSONObject report = summary();
                byte[] summaryBytes = report.toString().getBytes(UTF8);
                String reportDiagnostics = diagnostics.text();
                while (summaryBytes.length > MAX_SUMMARY && reportDiagnostics.length() > 0) {
                    reportDiagnostics = reportDiagnostics.substring(0, reportDiagnostics.length() / 2);
                    report.put("diagnostics", reportDiagnostics);
                    summaryBytes = report.toString().getBytes(UTF8);
                }
                if (summaryBytes.length > MAX_SUMMARY) throw new IOException("summary_limit");
                postSummary(summaryBytes);
                reportSent = true;
            } catch (Exception error) {
                diagnostics.error("collection", error);
            } finally {
                timer.cancel();
                HttpURLConnection connection = activeConnection;
                activeConnection = null;
                if (connection != null) connection.disconnect();
                close(activeFile);
                activeFile = null;
            }
            String message;
            if (!reportSent) {
                message = "진단 결과 전송에 실패했습니다. 확인된 APK 전송: " + artifacts.length() + "개.\n"
                        + (expired.get() || SystemClock.elapsedRealtime() >= deadline
                            ? "150초 제한에 도달했습니다." : "Mac 수집기와 네트워크 연결 상태를 확인해 주세요.");
            } else if (operation == Operation.ADB_SETUP) {
                message = "네트워크 ADB 시험 결과를 Mac으로 전송했습니다.\n실제 ADB 연결 성공 여부는 Mac에서 확인합니다.";
            } else if (operation == Operation.SERVICE_INSPECTION) {
                message = "ADB 실행 환경 진단을 Mac으로 전송했습니다.\n전송 완료는 ADB 연결이나 관리자 권한 확보를 뜻하지 않습니다.";
            } else if (!targetFound) {
                message = "진단 결과를 Mac으로 전송했습니다. 대상 KT 앱을 확인하지 못했습니다.\n확인된 파일: 0개";
            } else if (artifacts.length() == 0) {
                message = uploadFailures > 0
                        ? "진단 결과는 Mac으로 전송했지만 APK 파일 전송은 실패했습니다.\n확인된 파일: 0개"
                        : "진단 결과를 Mac으로 전송했습니다. 읽을 수 있는 APK를 확보하지 못했습니다.\n확인된 파일: 0개";
            } else {
                message = "진단 결과를 Mac으로 전송했습니다. 확인된 분석 파일: " + artifacts.length() + "개."
                        + (failures > 0 ? "\n읽기 또는 전송에 실패한 파일도 있습니다. 아래 진단을 확인해 주세요." : "");
            }
            return new Result(message, diagnostics.text());
        }

        private URL collectorOrigin() throws IOException {
            URL value = new URL(target.collectorOrigin);
            String path = value.getPath();
            if (!("http".equals(value.getProtocol()) || "https".equals(value.getProtocol()))
                    || value.getHost().length() == 0 || value.getUserInfo() != null
                    || value.getQuery() != null || value.getRef() != null
                    || !(path.length() == 0 || "/".equals(path))
                    || !target.collectorToken.matches("[0-9a-fA-F]{64}")) {
                throw new IOException("collector_configuration");
            }
            return value;
        }

        private void inspectAndSend() throws Exception {
            PackageInfo info;
            try { info = context.getPackageManager().getPackageInfo(target.packageName, 0); }
            catch (PackageManager.NameNotFoundException error) {
                diagnostics.add("package", "not_found");
                return;
            } catch (SecurityException error) {
                diagnostics.error("package", error);
                return;
            }
            ApplicationInfo application = info.applicationInfo;
            if (application == null || !target.packageName.equals(application.packageName)) {
                diagnostics.add("package", "invalid_application_info");
                return;
            }
            targetFound = true;
            diagnostics.add("package.uid", String.valueOf(application.uid));
            packageVersion = bounded(info.versionName, 128) + " (" + info.versionCode + ")";
            nativeLibraryDir = bounded(application.nativeLibraryDir, 1024);
            diagnostics.add("package", "found");
            if (operation != Operation.COLLECT) return;
            LinkedHashSet<String> paths = new LinkedHashSet<String>();
            addPath(paths, application.sourceDir);
            String[] splits = application.splitSourceDirs;
            if (splits != null) {
                int limit = Math.min(splits.length, MAX_FILES);
                for (int i = 0; i < limit; i++) addPath(paths, splits[i]);
                if (splits.length > limit) diagnostics.add("package.splits", "limit_exceeded");
            }
            int slot = 0;
            for (String path : paths) {
                checkDeadline();
                if (slot >= MAX_FILES) { diagnostics.add("package.files", "limit_exceeded"); break; }
                String kind = "apk-" + slot++;
                String stage = "read";
                diagnostics.add(kind + ".source", path);
                try (OpenApk apk = openApk(path)) {
                    if (apk.size > MAX_TOTAL - attemptedBytes) throw new IOException("total_size_limit");
                    attemptedBytes += apk.size;
                    String splitName = path.equals(application.sourceDir) ? "base" : bounded(new File(path).getName(), 128);
                    stage = "upload";
                    JSONObject artifact = upload(kind, apk, splitName);
                    artifacts.put(artifact);
                    diagnostics.add(kind, "verified");
                } catch (Exception error) {
                    failures++;
                    if ("upload".equals(stage)) uploadFailures++;
                    diagnostics.error(kind + "." + stage, error);
                }
            }
            if (target == Target.SETTINGS_APP) inspectLibraries();
            diagnostics.add("artifacts.count", String.valueOf(artifacts.length()));
        }

        private void inspectLibraries() throws Exception {
            LinkedHashSet<String> directories = new LinkedHashSet<String>();
            addLibraryDirectory(directories, nativeLibraryDir);
            String search = System.getProperty("java.library.path", "");
            if (search.length() <= 4096) {
                String[] entries = search.split(":");
                for (int i = 0; i < Math.min(entries.length, 16); i++) addLibraryDirectory(directories, entries[i]);
            }
            diagnostics.add("library.search_dirs", String.valueOf(directories.size()));
            for (int index = 0; index < LIBRARY_NAMES.length && artifacts.length() < MAX_FILES; index++) {
                String name = LIBRARY_NAMES[index];
                String kind = "elf-" + index;
                boolean found = false;
                for (String directory : directories) {
                    checkDeadline();
                    String path = new File(directory, name).getPath();
                    try (OpenApk library = openCodeFile(path, true)) {
                        if (library.size > MAX_TOTAL - attemptedBytes) throw new IOException("total_size_limit");
                        attemptedBytes += library.size;
                        diagnostics.add(kind + ".source", path);
                        artifacts.put(upload(kind, library, name));
                        diagnostics.add(kind, "verified");
                        found = true;
                        break;
                    } catch (Exception error) {
                        diagnostics.error(kind + ".candidate", error);
                    }
                }
                if (!found) diagnostics.add(kind, "not_acquired");
            }
        }

        private void addLibraryDirectory(LinkedHashSet<String> directories, String path) {
            if (path == null || path.length() == 0 || !clean(path, 1024) || directories.size() >= 12) return;
            if (!(path.startsWith("/system/") || path.startsWith("/vendor/")) || path.contains("..")) return;
            try {
                String canonical = new File(path).getCanonicalPath();
                if (!(canonical.startsWith("/system/") || canonical.startsWith("/vendor/"))) return;
                directories.add(canonical);
            } catch (IOException error) { diagnostics.error("library.directory", error); }
        }

        private void addPath(LinkedHashSet<String> paths, String path) {
            if (path == null || path.length() == 0) { diagnostics.add("package.path", "missing"); return; }
            if (paths.size() >= MAX_FILES) { diagnostics.add("package.files", "limit_exceeded"); return; }
            if (!clean(path, 1024)) { failures++; diagnostics.add("package.path", "invalid"); return; }
            paths.add(path);
        }

        private OpenApk openApk(String path) throws IOException, ErrnoException {
            return openCodeFile(path, false);
        }

        private OpenApk openCodeFile(String path, boolean library) throws IOException, ErrnoException {
            checkDeadline();
            File requested = new File(path);
            if (!requested.isAbsolute() || !path.endsWith(library ? ".so" : ".apk")) throw new IOException("invalid_code_path");
            if (library && !(path.startsWith("/system/") || path.startsWith("/vendor/"))) throw new IOException("invalid_library_path");
            File canonical = requested.getCanonicalFile();
            if (!path.equals(canonical.getPath())) throw new IOException("symlink_or_noncanonical_path");
            if (blockedPath(path)) throw new IOException("not_code_path");
            checkParents(canonical.getParentFile());
            StructStat before = Os.lstat(path);
            if (!OsConstants.S_ISREG(before.st_mode)
                    || before.st_size < (library ? 52 : 4) || before.st_size > (library ? MAX_LIBRARY : MAX_FILE)) throw new IOException("invalid_code_file");
            if ((before.st_mode & 0002) != 0 || (before.st_uid != 0 && before.st_uid != 1000)) {
                diagnostics.add("file.permissions", "uid=" + before.st_uid + " mode=" + Integer.toOctalString(before.st_mode));
            }
            FileDescriptor descriptor = Os.open(path, OsConstants.O_RDONLY | OsConstants.O_NOFOLLOW | OsConstants.O_NONBLOCK, 0);
            FileInputStream stream = new FileInputStream(descriptor);
            boolean retained = false;
            try {
                StructStat after = Os.fstat(descriptor);
                if (!sameFile(before, after) || !sameFile(after, Os.lstat(path))
                        || !path.equals(requested.getCanonicalPath())) throw new IOException("apk_changed");
                checkParents(canonical.getParentFile());
                byte[] magic = new byte[library ? 20 : 4];
                int offset = 0;
                while (offset < magic.length) {
                    int count = stream.read(magic, offset, magic.length - offset);
                    if (count < 0) throw new IOException("short_apk");
                    offset += count;
                }
                if (library) {
                    if (magic[0] != 127 || magic[1] != 'E' || magic[2] != 'L' || magic[3] != 'F'
                            || magic[4] != 1 || magic[5] != 1 || magic[6] != 1
                            || magic[16] != 3 || magic[17] != 0 || magic[18] != 40 || magic[19] != 0) {
                        throw new IOException("not_arm_shared_library");
                    }
                } else if (magic[0] != 'P' || magic[1] != 'K' || magic[2] != 3 || magic[3] != 4) throw new IOException("not_apk_zip");
                retained = true;
                return new OpenApk(path, descriptor, stream, after, magic);
            } finally { if (!retained) stream.close(); }
        }

        private void checkParents(File parent) throws IOException, ErrnoException {
            int count = 0;
            while (parent != null) {
                if (++count > 32) throw new IOException("path_depth");
                StructStat stat = Os.lstat(parent.getPath());
                if (!OsConstants.S_ISDIR(stat.st_mode)) throw new IOException("symlink_or_invalid_directory");
                if ((stat.st_mode & 0002) != 0 || (stat.st_uid != 0 && stat.st_uid != 1000)) {
                    diagnostics.add("directory.permissions", parent.getPath() + " uid=" + stat.st_uid
                            + " mode=" + Integer.toOctalString(stat.st_mode));
                }
                parent = parent.getParentFile();
            }
        }

        private JSONObject upload(String kind, OpenApk apk, String splitName) throws Exception {
            HttpURLConnection connection = connection("/artifact/" + kind, "application/octet-stream", apk.size);
            activeFile = apk.stream;
            try {
                MessageDigest digest = MessageDigest.getInstance("SHA-256");
                long sent = apk.magic.length;
                try (OutputStream output = connection.getOutputStream()) {
                    output.write(apk.magic);
                    digest.update(apk.magic);
                    byte[] buffer = new byte[32768];
                    while (sent < apk.size) {
                        checkDeadline();
                        int count = apk.stream.read(buffer, 0, (int) Math.min(buffer.length, apk.size - sent));
                        if (count < 0) throw new IOException("apk_truncated");
                        if (count == 0) continue;
                        output.write(buffer, 0, count);
                        digest.update(buffer, 0, count);
                        sent += count;
                    }
                    if (apk.stream.read() != -1 || !sameFile(apk.stat, Os.fstat(apk.descriptor))) throw new IOException("apk_changed");
                }
                String hash = hex(digest.digest());
                JSONObject response = response(connection);
                if (!response.optBoolean("ok", false) || response.optLong("size", -1) != apk.size
                        || !hash.equalsIgnoreCase(response.optString("sha256", ""))) throw new IOException("receipt_mismatch");
                return new JSONObject().put("kind", kind).put("path", apk.path).put("splitName", splitName)
                        .put("size", apk.size).put("sha256", hash);
            } finally {
                activeFile = null;
                activeConnection = null;
                connection.disconnect();
            }
        }

        private JSONObject summary() throws Exception {
            return new JSONObject().put("version", 1).put("model", bounded(Build.MODEL, 128))
                    .put("appUid", Process.myUid()).put("packageName", target.packageName).put("targetFound", targetFound)
                    .put("packageVersion", packageVersion).put("diagnostics", diagnostics.text())
                    .put("artifacts", artifacts).put("nativeLibraryDir", nativeLibraryDir);
        }

        private void postSummary(byte[] bytes) throws Exception {
            HttpURLConnection connection = connection("/collection", "application/json; charset=utf-8", bytes.length);
            try {
                try (OutputStream output = connection.getOutputStream()) { checkDeadline(); output.write(bytes); }
                if (!response(connection).optBoolean("ok", false)) throw new IOException("report_rejected");
            } finally { activeConnection = null; connection.disconnect(); }
        }

        private HttpURLConnection connection(String path, String contentType, long size) throws IOException {
            checkDeadline();
            URL target = new URL(origin.getProtocol(), origin.getHost(), origin.getPort(), path);
            HttpURLConnection connection = (HttpURLConnection) target.openConnection(Proxy.NO_PROXY);
            activeConnection = connection;
            connection.setInstanceFollowRedirects(false);
            connection.setUseCaches(false);
            connection.setAllowUserInteraction(false);
            connection.setConnectTimeout(timeout(10000));
            connection.setReadTimeout(timeout(15000));
            connection.setRequestMethod("POST");
            connection.setDoOutput(true);
            connection.setFixedLengthStreamingMode(size);
            connection.setRequestProperty("Content-Type", contentType);
            connection.setRequestProperty("Authorization", "Bearer " + this.target.collectorToken);
            connection.setRequestProperty("Connection", "close");
            return connection;
        }

        private JSONObject response(HttpURLConnection connection) throws Exception {
            checkDeadline();
            int status = connection.getResponseCode();
            if (status < 200 || status >= 300) throw new IOException("http_" + status);
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            try (InputStream input = connection.getInputStream()) {
                byte[] buffer = new byte[1024];
                int count;
                while ((count = input.read(buffer)) != -1) {
                    checkDeadline();
                    if (bytes.size() + count > 4096) throw new IOException("receipt_limit");
                    bytes.write(buffer, 0, count);
                }
            }
            return new JSONObject(new String(bytes.toByteArray(), UTF8));
        }

        private int timeout(int maximum) throws IOException {
            checkDeadline();
            return (int) Math.max(1L, Math.min(maximum, deadline - SystemClock.elapsedRealtime()));
        }

        private void checkDeadline() throws IOException {
            if (expired.get() || Thread.currentThread().isInterrupted() || SystemClock.elapsedRealtime() >= deadline) {
                expired.set(true);
                throw new IOException("deadline");
            }
        }
    }

    private static final class OpenApk implements Closeable {
        final String path;
        final FileDescriptor descriptor;
        final FileInputStream stream;
        final StructStat stat;
        final byte[] magic;
        final long size;
        OpenApk(String path, FileDescriptor descriptor, FileInputStream stream, StructStat stat, byte[] magic) {
            this.path = path; this.descriptor = descriptor; this.stream = stream; this.stat = stat;
            this.magic = magic; this.size = stat.st_size;
        }
        @Override public void close() throws IOException { stream.close(); }
    }

    private static boolean sameFile(StructStat first, StructStat second) {
        return OsConstants.S_ISREG(second.st_mode) && first.st_dev == second.st_dev && first.st_ino == second.st_ino
                && first.st_size == second.st_size && first.st_uid == second.st_uid
                && first.st_mode == second.st_mode && first.st_mtime == second.st_mtime;
    }

    private static boolean blockedPath(String path) {
        return path.startsWith("/proc/") || path.startsWith("/sys/") || path.startsWith("/dev/")
                || path.startsWith("/sdcard/") || path.startsWith("/storage/") || path.startsWith("/mnt/")
                || path.startsWith("/data/data/") || path.startsWith("/data/user/") || path.startsWith("/data/user_de/");
    }

    private static boolean clean(String value, int limit) {
        if (value.length() > limit) return false;
        for (int i = 0; i < value.length(); i++) if (Character.isISOControl(value.charAt(i))) return false;
        return true;
    }

    private static String bounded(String value, int limit) {
        if (value == null) return "";
        StringBuilder result = new StringBuilder(Math.min(value.length(), limit));
        for (int i = 0; i < value.length() && result.length() < limit; i++) {
            char c = value.charAt(i);
            if (!Character.isISOControl(c)) result.append(c);
        }
        return result.toString();
    }

    private static String hex(byte[] value) {
        char[] alphabet = "0123456789abcdef".toCharArray();
        char[] result = new char[value.length * 2];
        for (int i = 0; i < value.length; i++) {
            result[i * 2] = alphabet[(value[i] & 255) >>> 4];
            result[i * 2 + 1] = alphabet[value[i] & 15];
        }
        return new String(result);
    }

    private static void close(Closeable value) {
        if (value == null) return;
        try { value.close(); } catch (IOException ignored) {}
    }

    private static final class Diagnostics {
        private final StringBuilder value = new StringBuilder();
        void add(String key, String detail) {
            String line = key + "=" + detail + "\n";
            int remaining = 8192 - value.length();
            if (remaining > 0) value.append(line.substring(0, Math.min(line.length(), remaining)));
        }
        void error(String stage, Exception error) {
            String detail = error.getClass().getSimpleName();
            if (error instanceof ErrnoException) detail += " errno=" + ((ErrnoException) error).errno;
            else if (error instanceof IOException) {
                String code = error.getMessage();
                if (code != null && code.matches("[a-z][a-z0-9_]{0,63}")) detail += " " + code;
            }
            add(stage + ".error", detail);
        }
        String text() { return value.toString(); }
    }
}
