package net.quber.afactorymode;

import android.os.Process;
import android.system.Os;
import android.system.OsConstants;
import android.system.StructStat;
import java.io.File;
import java.io.FileDescriptor;
import java.nio.charset.Charset;
import java.security.MessageDigest;
import java.util.Map;

public final class SpiIdentityProbe {
    private static final Charset UTF8 = Charset.forName("UTF-8");
    private static boolean started;
    private static Result completed;
    private static native String runIdentity();

    public static final class Result {
        public final String status, reason, evidence;
        public final byte[] data;
        Result(String status, String reason, byte[] data, String evidence) {
            this.status = status; this.reason = reason; this.data = data.clone(); this.evidence = evidence;
        }
    }

    public static synchronized Result readSynchronousForHelper() {
        if (completed != null) return completed;
        if (started) return failed("identity_already_started", "stage=once_guard\n");
        started = true;
        String stage = "root_guard";
        try {
            if (Process.myUid() != 0) return completed = failed("helper_uid_invalid", "stage=" + stage + "\n");
            stage = "controller_preflight";
            SpiControllerProbe.Result controller = SpiControllerProbe.readSynchronousForHelper();
            if (!"ok".equals(controller.status) || !"register_snapshots_read".equals(controller.reason)) {
                return completed = failed("identity_preflight_failed", "stage=" + stage + "\npreflight_reason=" + controller.reason + "\n");
            }
            SpiIdentityPolicy.requirePreflight(new String(controller.data, UTF8), controller.evidence);
            stage = "native_library";
            String path = verifiedLibrary();
            System.load(path);
            stage = "native_identity";
            String text = runIdentity();
            Map<String, String> report = SpiIdentityPolicy.nativeReport(text);
            if ("ok".equals(report.get("status"))) {
                return completed = new Result("ok", report.get("reason"), text.getBytes(UTF8),
                        "caller_uid=0\nstage=complete\nnative_sha256=" + SpiIdentityBuildConfig.SHA256 + "\n");
            }
            return completed = failed(report.get("reason"), "stage=native_identity\n" + text);
        } catch (Exception error) {
            String detail = "stage=" + stage + "\n";
            String message = error.getMessage();
            if (message != null && message.matches("[a-z0-9_]{1,120}")) detail += "failure_detail=" + message + "\n";
            return completed = failed("identity_failed_no_retry", detail);
        } catch (LinkageError error) {
            return completed = failed("native_library_unavailable", "stage=" + stage + "\n");
        }
    }

    static String verifiedLibrary() throws Exception {
        String apk = System.getenv("CLASSPATH");
        SystemBackupProtocol.requirePanelApk(apk);
        String parent = apk.substring(0, apk.lastIndexOf('/'));
        for (String directory : new String[]{parent, parent + "/lib", parent + "/lib/arm"}) {
            StructStat info = Os.lstat(directory);
            if (!OsConstants.S_ISDIR(info.st_mode) || (info.st_uid != 0 && info.st_uid != 1000)
                    || (info.st_mode & 0022) != 0 || !directory.equals(new File(directory).getCanonicalPath())) {
                throw new IllegalStateException("native_directory_invalid");
            }
        }
        String path = parent + "/lib/arm/libhome_panel_spi.so";
        StructStat before = Os.lstat(path);
        if (!OsConstants.S_ISREG(before.st_mode) || (before.st_uid != 0 && before.st_uid != 1000)
                || (before.st_mode & 0022) != 0 || before.st_size < 1 || before.st_size > 4194304) {
            throw new IllegalStateException("native_file_invalid");
        }
        long deadline = System.nanoTime() + 2000000000L;
        FileDescriptor fd = Os.open(path, OsConstants.O_RDONLY | OsConstants.O_NOFOLLOW | OsConstants.O_NONBLOCK | 02000000, 0);
        try {
            if (!same(before, Os.fstat(fd))) throw new IllegalStateException("native_file_changed");
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] buffer = new byte[16384];
            long size = 0;
            while (true) {
                if (System.nanoTime() >= deadline) throw new IllegalStateException("native_hash_timeout");
                int n = Os.read(fd, buffer, 0, buffer.length);
                if (n == 0) break;
                if (n < 0 || size + n > before.st_size) throw new IllegalStateException("native_file_changed");
                digest.update(buffer, 0, n); size += n;
            }
            if (size != before.st_size || !same(before, Os.fstat(fd)) || !same(before, Os.lstat(path))) {
                throw new IllegalStateException("native_file_changed");
            }
            StringBuilder actual = new StringBuilder();
            for (byte value : digest.digest()) {
                actual.append("0123456789abcdef".charAt((value >>> 4) & 15));
                actual.append("0123456789abcdef".charAt(value & 15));
            }
            if (!SpiIdentityBuildConfig.SHA256.equals(actual.toString())) throw new IllegalStateException("native_hash_mismatch");
        } finally { Os.close(fd); }
        return path;
    }

    private static boolean same(StructStat a, StructStat b) {
        return a.st_dev == b.st_dev && a.st_ino == b.st_ino && a.st_mode == b.st_mode && a.st_uid == b.st_uid
                && a.st_gid == b.st_gid && a.st_size == b.st_size && a.st_mtime == b.st_mtime && a.st_ctime == b.st_ctime;
    }

    private static Result failed(String reason, String detail) {
        return new Result("error", reason, new byte[0], "caller_uid=" + Process.myUid() + "\n" + detail);
    }
}
