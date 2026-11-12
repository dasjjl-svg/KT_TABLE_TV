package net.quber.afactorymode;

import android.os.SystemClock;
import android.system.ErrnoException;
import android.system.Os;
import android.system.OsConstants;
import android.system.StructStat;
import java.io.ByteArrayOutputStream;
import java.io.FileDescriptor;
import java.io.FileInputStream;
import java.io.IOException;
import java.nio.charset.Charset;

public final class DeviceRuntimeInfo {
    private static final String[] INIT_FILES = {"/init.rc", "/init.bigfish.rc"};
    private static final String[] STAT_FILES = {
        "/system/alticast/app/alticaptor/lib/libalticaptor.so",
        "/system/alticast/app/alticaptor/su.bin",
        "/system/alticast/app/alticaptor/run_altibrowser.sh",
        "/sbin/fork.bin",
        "/sbin/adbd",
        "/system/bin/adbd"
    };
    private static final int MAX_FILE = 65536;
    private static final int MAX_TOTAL = 131072;
    private static final int MAX_RESULT = 3000;
    private static final long BUDGET_MS = 2000;
    private static final Charset UTF8 = Charset.forName("UTF-8");

    private DeviceRuntimeInfo() {}

    public static String collect() {
        StringBuilder result = new StringBuilder();
        long deadline = SystemClock.elapsedRealtime() + BUDGET_MS;
        int imports = 0;
        int consumed = 0;
        for (String path : INIT_FILES) {
            try {
                checkDeadline(deadline);
                StructStat before = Os.lstat(path);
                if (!OsConstants.S_ISREG(before.st_mode)) throw new IOException("not_regular_or_symlink");
                if (before.st_size < 0 || before.st_size > Math.min(MAX_FILE, MAX_TOTAL - consumed)) {
                    throw new IOException("size_limit");
                }
                consumed += (int) before.st_size;
                FileDescriptor descriptor = Os.open(path, OsConstants.O_RDONLY | OsConstants.O_NOFOLLOW | OsConstants.O_NONBLOCK, 0);
                try (FileInputStream input = new FileInputStream(descriptor)) {
                    if (!sameFile(before, Os.fstat(descriptor)) || !sameFile(before, Os.lstat(path))) {
                        throw new IOException("file_changed");
                    }
                    ByteArrayOutputStream bytes = new ByteArrayOutputStream((int) before.st_size);
                    byte[] buffer = new byte[4096];
                    int remaining = (int) before.st_size;
                    while (remaining > 0) {
                        checkDeadline(deadline);
                        int count = input.read(buffer, 0, Math.min(buffer.length, remaining));
                        if (count < 0) throw new IOException("file_truncated");
                        if (count == 0) continue;
                        bytes.write(buffer, 0, count);
                        remaining -= count;
                    }
                    checkDeadline(deadline);
                    if (!sameFile(before, Os.fstat(descriptor)) || !sameFile(before, Os.lstat(path))) {
                        throw new IOException("file_changed");
                    }
                    DeviceInitParser.Result parsed = DeviceInitParser.parse(new String(bytes.toByteArray(), UTF8), 10 - imports);
                    imports += parsed.imports;
                    add(result, path + ".read=ok bytes=" + before.st_size);
                    for (String line : parsed.lines) add(result, path + "." + line);
                }
            } catch (ErrnoException error) {
                error(result, path, error.errno);
            } catch (IOException error) {
                String code = error.getMessage();
                add(result, path + ".error=" + (code != null && code.matches("[a-z_]{1,40}") ? code : "read_failed"));
            } catch (SecurityException error) {
                add(result, path + ".error=permission_denied");
            } catch (RuntimeException error) {
                add(result, path + ".error=runtime_failure");
            }
        }
        for (String path : STAT_FILES) {
            try {
                checkDeadline(deadline);
                StructStat stat = Os.lstat(path);
                add(result, path + ".lstat=uid=" + stat.st_uid + " gid=" + stat.st_gid
                        + " mode=" + Integer.toOctalString(stat.st_mode) + " size=" + stat.st_size);
            } catch (ErrnoException error) {
                error(result, path, error.errno);
            } catch (IOException error) {
                add(result, path + ".error=deadline");
            } catch (SecurityException error) {
                add(result, path + ".error=permission_denied");
            } catch (RuntimeException error) {
                add(result, path + ".error=runtime_failure");
            }
        }
        return result.toString();
    }

    private static boolean sameFile(StructStat first, StructStat second) {
        return OsConstants.S_ISREG(second.st_mode) && first.st_dev == second.st_dev && first.st_ino == second.st_ino
                && first.st_size == second.st_size && first.st_mode == second.st_mode && first.st_uid == second.st_uid
                && first.st_gid == second.st_gid && first.st_mtime == second.st_mtime;
    }

    private static void checkDeadline(long deadline) throws IOException {
        if (Thread.currentThread().isInterrupted() || SystemClock.elapsedRealtime() >= deadline) throw new IOException("deadline");
    }

    private static void error(StringBuilder result, String path, int errno) {
        String kind = errno == OsConstants.EACCES || errno == OsConstants.EPERM ? "permission_denied"
                : errno == OsConstants.ENOENT ? "not_found" : errno == OsConstants.ELOOP ? "symlink_refused" : "io_error";
        add(result, path + ".error=" + kind + " errno=" + errno);
    }

    private static void add(StringBuilder result, String line) {
        int limit = line.startsWith("/init.") ? MAX_RESULT - 768 : MAX_RESULT;
        if (result.length() + line.length() + 1 <= limit) result.append(line).append('\n');
    }
}
