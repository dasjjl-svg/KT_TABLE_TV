package net.quber.afactorymode;

import android.os.Build;
import android.os.Process;
import android.system.ErrnoException;
import android.system.Os;
import android.system.OsConstants;
import android.system.StructStat;
import java.io.ByteArrayOutputStream;
import java.io.FileDescriptor;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.nio.charset.Charset;
import java.security.MessageDigest;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class SpiControllerProbe {
    private static final String DEVICE = "/dev/hi_mem";
    private static final String PLATFORM_NODE = "/sys/bus/platform/devices/f9950000.hifmc100.NAND";
    private static final String LIBRARY = "/system/lib/libhi_common.so";
    private static final String LIBRARY_SHA256 = "169cd2890ee73fb7bc131cf1725325b006e6e474d390d5bf1792252c9604104a";
    private static final String JAVA_LIBRARY = "/system/lib/libjavacore.so";
    private static final String JAVA_LIBRARY_SHA256 = "cd57abab1dd625f9d9eaa6028a85151739f5525f2b8f70d8cab3e73875b4e94c";
    private static final String C_LIBRARY = "/system/lib/libc.so";
    private static final String C_LIBRARY_SHA256 = "5514002dcfaa1227ff4db45f4aa0df43761272f518bd45279c32bdc6b9078764";
    private static final Charset UTF8 = Charset.forName("UTF-8");
    private static final int FLAGS = OsConstants.O_RDONLY | OsConstants.O_NOFOLLOW
            | OsConstants.O_NONBLOCK | 02000000;
    private static final long PAGE_SIZE = 4096L;
    private static final SpiControllerPolicy.SynchronousOnce ONCE = new SpiControllerPolicy.SynchronousOnce(5000L);
    private static boolean started;
    private static Result completed;

    public static final class Result {
        public final String status, reason, evidence;
        public final byte[] data;

        private Result(SpiControllerPolicy.Result result, String evidence) {
            status = result.status;
            reason = result.reason;
            data = result.data.clone();
            this.evidence = evidence;
        }
    }

    private SpiControllerProbe() { }

    public static Result readSynchronousForHelper() {
        AndroidAccess access = new AndroidAccess();
        synchronized (SpiControllerProbe.class) {
            if (completed != null) return completed;
            if (started) return new Result(new SpiControllerPolicy.Result("error", new byte[0], "already_started"),
                    access.evidence.snapshot());
            started = true;
        }
        SpiControllerPolicy.Result result;
        if (Process.myUid() != 0) {
            access.evidence.stage = "root_guard";
            result = new SpiControllerPolicy.Result("error", new byte[0], "helper_uid_invalid");
        } else {
            result = ONCE.read(access);
        }
        if ("ok".equals(result.status)) access.evidence.stage = "complete";
        Result output = new Result(result, access.evidence.snapshot());
        synchronized (SpiControllerProbe.class) { completed = output; }
        return output;
    }

    private static final class Evidence {
        final int callerUid = Process.myUid();
        String stage = "start";
        int miscMinor = -1, sysfsMinor = -1;
        StructStat before, opened, after;
        String ownershipBefore, ownershipAfter;

        String snapshot() {
            StringBuilder out = new StringBuilder("caller_uid=").append(callerUid)
                    .append("\nstage=").append(stage).append('\n');
            if (miscMinor >= 0) out.append("misc_minor=").append(miscMinor).append('\n');
            if (sysfsMinor >= 0) out.append("sysfs_minor=").append(sysfsMinor).append('\n');
            append(out, "lstat", before);
            append(out, "fstat", opened);
            append(out, "path", after);
            if (ownershipBefore != null) out.append(ownershipBefore);
            if (ownershipAfter != null) out.append(ownershipAfter);
            out.append("exclusive_access_verified=0\n");
            return out.toString();
        }

        private static void append(StringBuilder out, String prefix, StructStat stat) {
            if (stat == null) return;
            out.append(prefix).append("_type=").append(stat.st_mode & 0170000).append('\n');
            out.append(prefix).append("_uid=").append(stat.st_uid).append('\n');
            out.append(prefix).append("_gid=").append(stat.st_gid).append('\n');
            out.append(prefix).append("_mode=").append(stat.st_mode & 07777).append('\n');
            out.append(prefix).append("_major=").append(major(stat.st_rdev)).append('\n');
            out.append(prefix).append("_minor=").append(minor(stat.st_rdev)).append('\n');
        }
    }

    private static final class AndroidAccess implements SpiControllerPolicy.Access {
        final Evidence evidence = new Evidence();
        SpiControllerPolicy.Deadline deadline;
        FileDescriptor device;
        Method peek;
        long clockAddress, fmcAddress;
        boolean clockMapped, fmcMapped, fmcAllowed;

        public void prepare(SpiControllerPolicy.Deadline deadline) throws SpiControllerPolicy.Failure {
            this.deadline = deadline;
            evidence.stage = "platform";
            deadline.check();
            try { BootMediaPolicy.verifyPlatform(Build.MODEL, Build.HARDWARE, Build.VERSION.SDK_INT, Build.CPU_ABI); }
            catch (BootMediaPolicy.Failure error) { throw fail(error.status, error.reason); }
            deadline.check();
            evidence.stage = "kernel";
            String kernel = new String(readBounded("/proc/sys/kernel/osrelease", 256, deadline, "kernel"), UTF8).trim();
            try { BootMediaPolicy.verifyKernel(kernel); }
            catch (BootMediaPolicy.Failure error) { throw fail(error.status, error.reason); }
            evidence.stage = "library";
            if (!LIBRARY_SHA256.equals(libraryHash(LIBRARY, deadline))) throw fail("error", "library_hash_mismatch");
            evidence.stage = "java_library";
            if (!JAVA_LIBRARY_SHA256.equals(libraryHash(JAVA_LIBRARY, deadline))) throw fail("error", "java_library_hash_mismatch");
            evidence.stage = "c_library";
            if (!C_LIBRARY_SHA256.equals(libraryHash(C_LIBRARY, deadline))) throw fail("error", "c_library_hash_mismatch");
            evidence.stage = "page_size";
            if (Os.sysconf(OsConstants._SC_PAGESIZE) != PAGE_SIZE) throw fail("error", "page_size_mismatch");
            deadline.check();
            evidence.stage = "peek_api";
            try {
                peek = Class.forName("libcore.io.Memory").getMethod("peekInt", Long.TYPE, Boolean.TYPE);
                if (peek.getReturnType() != Integer.TYPE) throw new NoSuchMethodException();
            } catch (ReflectiveOperationException error) { throw fail("error", "peek_api_unavailable"); }
            catch (LinkageError error) { throw fail("error", "peek_api_unavailable"); }
            catch (SecurityException error) { throw fail("error", "peek_api_unavailable"); }
            deadline.check();
            evidence.stage = "misc_registry";
            evidence.miscMinor = miscMinor(new String(readBounded("/proc/misc", 65536, deadline, "misc_registry"), UTF8));
            evidence.stage = "sysfs_registry";
            evidence.sysfsMinor = sysfsMinor(new String(readBounded("/sys/class/misc/hi_mem/dev", 64,
                    deadline, "sysfs_registry"), UTF8));
            if (evidence.miscMinor != evidence.sysfsMinor) throw fail("error", "hi_mem_registry_mismatch");
            try {
                evidence.stage = "node_lstat";
                deadline.check();
                evidence.before = Os.lstat(DEVICE);
                deadline.check();
                requireDevice(evidence.before, evidence.miscMinor);
                evidence.stage = "node_open";
                deadline.check();
                device = Os.open(DEVICE, FLAGS, 0);
                deadline.check();
                evidence.stage = "node_fstat";
                evidence.opened = Os.fstat(device);
                deadline.check();
                requireDevice(evidence.opened, evidence.miscMinor);
                if (!sameNode(evidence.before, evidence.opened)) throw fail("error", "hi_mem_changed");
                evidence.stage = "node_path_check";
                evidence.after = Os.lstat(DEVICE);
                deadline.check();
                requireDevice(evidence.after, evidence.miscMinor);
                if (!sameNode(evidence.opened, evidence.after)) throw fail("error", "hi_mem_changed");
                evidence.stage = "ownership_before";
                evidence.ownershipBefore = ownershipSnapshot("before", deadline);
                evidence.stage = "clock_map";
                deadline.check();
                clockAddress = Os.mmap(0L, PAGE_SIZE, OsConstants.PROT_READ, OsConstants.MAP_SHARED,
                        device, SpiControllerPolicy.CLOCK_BASE);
                clockMapped = true;
                deadline.check();
            } catch (ErrnoException error) { throw errno(error, "hi_mem"); }
        }

        public int readClock() throws SpiControllerPolicy.Failure {
            if (!clockMapped) throw fail("error", "clock_not_prepared");
            evidence.stage = "clock_read";
            int value = peek(clockAddress + SpiControllerPolicy.CLOCK_OFFSET);
            fmcAllowed = (value & 1) != 0 && (value & 16) == 0;
            return value;
        }

        public int readRegister(int offset) throws SpiControllerPolicy.Failure {
            SpiControllerPolicy.requireOffset(offset);
            if (!fmcAllowed || !clockMapped || device == null) throw fail("error", "fmc_not_permitted");
            if (!fmcMapped) {
                evidence.stage = "fmc_map";
                deadline.check();
                try {
                    fmcAddress = Os.mmap(0L, PAGE_SIZE, OsConstants.PROT_READ, OsConstants.MAP_SHARED,
                            device, SpiControllerPolicy.FMC_BASE);
                    fmcMapped = true;
                } catch (ErrnoException error) { throw errno(error, "fmc_map"); }
                deadline.check();
            }
            evidence.stage = "fmc_read";
            return peek(fmcAddress + offset);
        }

        private int peek(long address) throws SpiControllerPolicy.Failure {
            deadline.check();
            try {
                Object result = peek.invoke(null, Long.valueOf(address), Boolean.FALSE);
                deadline.check();
                if (!(result instanceof Integer)) throw fail("error", "register_result_invalid");
                return ((Integer) result).intValue();
            } catch (InvocationTargetException error) { throw fail("error", "register_read_failed"); }
            catch (ReflectiveOperationException error) { throw fail("error", "peek_api_unavailable"); }
        }

        public void close() throws SpiControllerPolicy.Failure {
            boolean failed = false;
            if (evidence.ownershipBefore != null) {
                if (deadline.remaining() > 0) {
                    evidence.ownershipAfter = ownershipSnapshot("after", deadline);
                } else {
                    evidence.ownershipAfter = "ownership_after=not_sampled_deadline\n";
                }
            }
            if (fmcMapped) {
                fmcMapped = false;
                try { Os.munmap(fmcAddress, PAGE_SIZE); }
                catch (ErrnoException error) { failed = true; }
            }
            if (clockMapped) {
                clockMapped = false;
                try { Os.munmap(clockAddress, PAGE_SIZE); }
                catch (ErrnoException error) { failed = true; }
            }
            FileDescriptor owned = device;
            device = null;
            if (owned != null) {
                try { Os.close(owned); }
                catch (ErrnoException error) { failed = true; }
            }
            if (failed) { evidence.stage = "close"; throw fail("error", "close_failed"); }
        }
    }

    private static String ownershipSnapshot(String suffix, SpiControllerPolicy.Deadline deadline) {
        String driver = "unknown";
        try {
            deadline.check();
            StructStat platform = Os.stat(PLATFORM_NODE);
            deadline.check();
            if (OsConstants.S_ISDIR(platform.st_mode)) {
                try {
                    StructStat link = Os.lstat(PLATFORM_NODE + "/driver");
                    deadline.check();
                    driver = OsConstants.S_ISLNK(link.st_mode) ? "bound" : "unexpected_node";
                } catch (ErrnoException error) {
                    driver = error.errno == OsConstants.ENOENT ? "not_bound" : "unknown";
                }
            }
        } catch (Exception ignored) { driver = "unknown"; }
        StringBuilder out = new StringBuilder("platform_driver_").append(suffix).append('=').append(driver).append('\n');
        try {
            String text = new String(readBounded("/proc/interrupts", 65536, deadline, "interrupts"), UTF8);
            int matches = 0;
            StringBuilder lines = new StringBuilder();
            for (String line : text.split("\n")) {
                if (line.contains("hifmc") || line.contains("hisfc") || line.contains("hi_sfc")) {
                    matches++;
                    if (matches <= 2) {
                        String value = line.trim().replaceAll("\\s+", " ");
                        if (value.length() > 160) value = value.substring(0, 160);
                        if (!value.matches("[ -~]*")) value = "non_ascii";
                        lines.append("fmc_irq_line_").append(suffix).append('_').append(matches)
                                .append('=').append(value).append('\n');
                    }
                }
            }
            out.append("fmc_irq_line_matches_").append(suffix).append('=').append(matches).append('\n');
            out.append(lines);
        } catch (Exception ignored) {
            out.append("fmc_irq_line_matches_").append(suffix).append("=unknown\n");
        }
        return out.toString();
    }

    private static int miscMinor(String value) throws SpiControllerPolicy.Failure {
        int found = -1;
        Pattern entry = Pattern.compile("([0-9]{1,3})\\s+(\\S+)");
        for (String line : value.split("\n")) {
            if (line.trim().length() == 0) continue;
            Matcher match = entry.matcher(line.trim());
            if (!match.matches()) throw fail("error", "misc_registry_invalid");
            if ("hi_mem".equals(match.group(2))) {
                if (found >= 0) throw fail("error", "misc_registry_duplicate");
                found = Integer.parseInt(match.group(1));
                if (found > 255) throw fail("error", "misc_registry_invalid");
            }
        }
        if (found < 0) throw fail("absent", "hi_mem_unregistered");
        return found;
    }

    private static int sysfsMinor(String value) throws SpiControllerPolicy.Failure {
        Matcher match = Pattern.compile("10:([0-9]{1,3})").matcher(value.trim());
        if (!match.matches()) throw fail("error", "sysfs_registry_invalid");
        int minor = Integer.parseInt(match.group(1));
        if (minor > 255) throw fail("error", "sysfs_registry_invalid");
        return minor;
    }

    private static void requireDevice(StructStat stat, int expectedMinor) throws SpiControllerPolicy.Failure {
        if (!OsConstants.S_ISCHR(stat.st_mode) || (stat.st_uid != 0 && stat.st_uid != 1000)
                || major(stat.st_rdev) != 10L || minor(stat.st_rdev) != expectedMinor) {
            throw fail("error", "hi_mem_invalid");
        }
    }

    private static long major(long dev) { return ((dev >>> 8) & 0xfffL) | ((dev >>> 32) & 0xfffff000L); }
    private static long minor(long dev) { return (dev & 0xffL) | ((dev >>> 12) & 0xffffff00L); }

    private static boolean sameNode(StructStat a, StructStat b) {
        return a.st_dev == b.st_dev && a.st_ino == b.st_ino && a.st_rdev == b.st_rdev
                && a.st_mode == b.st_mode && a.st_uid == b.st_uid && a.st_gid == b.st_gid;
    }

    private static boolean sameFile(StructStat a, StructStat b) {
        return sameNode(a, b) && a.st_size == b.st_size && a.st_mtime == b.st_mtime && a.st_ctime == b.st_ctime;
    }

    private static byte[] readBounded(String path, int maximum, SpiControllerPolicy.Deadline deadline,
            String scope) throws SpiControllerPolicy.Failure {
        FileDescriptor fd = null;
        try {
            deadline.check();
            fd = Os.open(path, FLAGS, 0);
            deadline.check();
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            byte[] buffer = new byte[Math.min(4096, maximum + 1)];
            while (true) {
                deadline.check();
                int count = Os.read(fd, buffer, 0, buffer.length);
                deadline.check();
                if (count == 0) return bytes.toByteArray();
                if (count < 0 || bytes.size() + count > maximum) throw fail("error", scope + "_too_large");
                bytes.write(buffer, 0, count);
            }
        } catch (ErrnoException error) { throw errno(error, scope); }
        catch (java.io.InterruptedIOException error) { throw fail("error", scope + "_read_interrupted"); }
        finally { closeOwned(fd); }
    }

    private static String libraryHash(String path, SpiControllerPolicy.Deadline deadline) throws SpiControllerPolicy.Failure {
        FileDescriptor fd = null;
        try {
            deadline.check();
            StructStat before = Os.lstat(path);
            deadline.check();
            if (!OsConstants.S_ISREG(before.st_mode) || before.st_uid != 0
                    || before.st_size <= 0 || before.st_size > 8L * 1024L * 1024L) throw fail("error", "library_invalid");
            fd = Os.open(path, FLAGS, 0);
            deadline.check();
            StructStat opened = Os.fstat(fd);
            deadline.check();
            if (!sameFile(before, opened)) throw fail("error", "library_changed");
            MessageDigest sha = MessageDigest.getInstance("SHA-256");
            byte[] buffer = new byte[16384];
            long size = 0;
            while (true) {
                deadline.check();
                int count = Os.read(fd, buffer, 0, buffer.length);
                deadline.check();
                if (count == 0) break;
                size += count;
                if (count < 0 || size > before.st_size) throw fail("error", "library_changed");
                sha.update(buffer, 0, count);
            }
            StructStat finalOpened = Os.fstat(fd);
            deadline.check();
            StructStat finalPath = Os.lstat(path);
            deadline.check();
            if (size != before.st_size || !sameFile(opened, finalOpened) || !sameFile(opened, finalPath)) {
                throw fail("error", "library_changed");
            }
            StringBuilder hex = new StringBuilder();
            for (byte value : sha.digest()) {
                hex.append("0123456789abcdef".charAt((value >>> 4) & 15));
                hex.append("0123456789abcdef".charAt(value & 15));
            }
            return hex.toString();
        } catch (SpiControllerPolicy.Failure error) { throw error; }
        catch (ErrnoException error) { throw errno(error, "library"); }
        catch (Exception error) { throw fail("error", "library_hash_failed"); }
        finally { closeOwned(fd); }
    }

    private static void closeOwned(FileDescriptor fd) throws SpiControllerPolicy.Failure {
        if (fd == null) return;
        try { Os.close(fd); }
        catch (ErrnoException error) { throw fail("error", "close_failed"); }
    }

    private static SpiControllerPolicy.Failure errno(ErrnoException error, String scope) {
        if (error.errno == OsConstants.ENOENT) return fail("absent", scope + "_absent");
        if (error.errno == OsConstants.EACCES || error.errno == OsConstants.EPERM) return fail("unreadable", scope + "_unreadable");
        return fail("error", scope + "_io_error");
    }

    private static SpiControllerPolicy.Failure fail(String status, String reason) {
        return new SpiControllerPolicy.Failure(status, reason);
    }
}
