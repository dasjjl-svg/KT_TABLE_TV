package net.quber.afactorymode;

import android.os.Build;
import android.os.Process;
import android.system.ErrnoException;
import android.system.Os;
import android.system.OsConstants;
import android.system.StructStat;
import java.io.ByteArrayOutputStream;
import java.io.FileDescriptor;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.nio.charset.Charset;
import java.security.MessageDigest;

public final class BootMediaProbe {
    private static final BootMediaPolicy.Once ONCE = new BootMediaPolicy.Once(5000L);
    private static final BootMediaPolicy.SynchronousOnce SYNCHRONOUS_ONCE = new BootMediaPolicy.SynchronousOnce(5000L);
    private static boolean started;
    private static Result completed;
    private static final Charset UTF8 = Charset.forName("UTF-8");
    private static final String DEVICE = "/dev/hi_ca";
    private static final String LIBRARY = "/system/lib/libhi_msp.so";
    private static final int O_CLOEXEC = 02000000;
    private static final int FLAGS = OsConstants.O_RDONLY | OsConstants.O_NOFOLLOW
            | OsConstants.O_NONBLOCK | O_CLOEXEC;

    public static final class Result {
        public final String status;
        public final byte[] data;
        public final String reason;
        public final String evidence;

        private Result(BootMediaPolicy.Result result, String evidence) {
            status = result.status;
            data = result.data.clone();
            reason = result.reason;
            this.evidence = evidence;
        }
    }

    public static Result read() { return read(false); }

    public static Result readSynchronousForHelper() { return read(true); }

    private static Result read(boolean helper) {
        AndroidAccess access = new AndroidAccess();
        synchronized (BootMediaProbe.class) {
            if (completed != null) return completed;
            if (started) return new Result(new BootMediaPolicy.Result("error", new byte[0], "already_started"),
                    access.evidence.snapshot());
            started = true;
        }
        BootMediaPolicy.Result result;
        if (helper && Process.myUid() != 0) {
            access.evidence.stage(BootMediaPolicy.Stage.ROOT_GUARD);
            result = new BootMediaPolicy.Result("error", new byte[0], "helper_uid_invalid");
        } else {
            result = helper ? SYNCHRONOUS_ONCE.read(access) : ONCE.read(access);
        }
        if ("ok".equals(result.status)) access.evidence.stage(BootMediaPolicy.Stage.COMPLETE);
        Result output = new Result(result, access.evidence.snapshot());
        synchronized (BootMediaProbe.class) { completed = output; }
        return output;
    }

    private BootMediaProbe() { }

    private static final class AndroidAccess implements BootMediaPolicy.Access {
        private FileDescriptor device;
        private Method ioctl;
        private Constructor<?> mutableConstructor;
        private Field mutableValue;
        private final BootMediaPolicy.Evidence evidence = new BootMediaPolicy.Evidence(Process.myUid());

        public void prepare(BootMediaPolicy.Deadline deadline) throws BootMediaPolicy.Failure {
            evidence.stage(BootMediaPolicy.Stage.PLATFORM);
            deadline.check();
            BootMediaPolicy.verifyPlatform(Build.MODEL, Build.HARDWARE, Build.VERSION.SDK_INT, Build.CPU_ABI);
            deadline.check();
            evidence.stage(BootMediaPolicy.Stage.KERNEL);
            BootMediaPolicy.verifyKernel(readKernel(deadline));
            deadline.check();
            evidence.stage(BootMediaPolicy.Stage.LIBRARY);
            BootMediaPolicy.verifyLibrary(libraryHash(deadline));
            deadline.check();
            evidence.stage(BootMediaPolicy.Stage.IOCTL_API);
            try {
                Class<?> mutable = Class.forName("android.util.MutableInt");
                mutableConstructor = mutable.getConstructor(Integer.TYPE);
                mutableValue = mutable.getField("value");
                ioctl = Os.class.getMethod("ioctlInt", FileDescriptor.class, Integer.TYPE, mutable);
                if (mutableValue.getType() != Integer.TYPE || ioctl.getReturnType() != Integer.TYPE) {
                    throw new NoSuchMethodException();
                }
            } catch (ReflectiveOperationException error) {
                throw new BootMediaPolicy.Failure("error", "ioctl_api_unavailable");
            } catch (LinkageError error) {
                throw new BootMediaPolicy.Failure("error", "ioctl_api_unavailable");
            } catch (SecurityException error) {
                throw new BootMediaPolicy.Failure("error", "ioctl_api_unavailable");
            }
            deadline.check();
            try {
                evidence.stage(BootMediaPolicy.Stage.NODE_LSTAT);
                StructStat before = Os.lstat(DEVICE);
                evidence.lstat(node(before));
                deadline.check();
                requireDevice(before);
                deadline.check();
                evidence.stage(BootMediaPolicy.Stage.NODE_OPEN);
                device = Os.open(DEVICE, FLAGS, 0);
                deadline.check();
                evidence.stage(BootMediaPolicy.Stage.NODE_FSTAT);
                StructStat opened = Os.fstat(device);
                evidence.fstat(node(opened));
                deadline.check();
                requireDevice(opened);
                if (!sameNode(before, opened)) {
                    throw new BootMediaPolicy.Failure("error", "hi_ca_changed");
                }
                evidence.stage(BootMediaPolicy.Stage.NODE_PATH);
                deadline.check();
                StructStat after = Os.lstat(DEVICE);
                evidence.path(node(after));
                deadline.check();
                requireDevice(after);
                if (!sameNode(opened, after)) {
                    throw new BootMediaPolicy.Failure("error", "hi_ca_changed");
                }
            } catch (ErrnoException error) {
                throw failureFor(error, "hi_ca");
            }
            deadline.check();
        }

        public int get(int request) throws BootMediaPolicy.Failure {
            BootMediaPolicy.requireGetter(request);
            if (device == null || ioctl == null) throw new BootMediaPolicy.Failure("error", "device_not_prepared");
            evidence.stage(request == BootMediaPolicy.GET_SCS ? BootMediaPolicy.Stage.QUERY_SCS
                    : request == BootMediaPolicy.GET_CONTROL ? BootMediaPolicy.Stage.QUERY_CONTROL
                    : BootMediaPolicy.Stage.QUERY_MODE);
            try {
                Object mutable = mutableConstructor.newInstance(Integer.valueOf(Integer.MIN_VALUE));
                Object returned = ioctl.invoke(null, device, Integer.valueOf(request), mutable);
                if (!(returned instanceof Integer) || ((Integer) returned).intValue() != 0) {
                    throw new BootMediaPolicy.Failure("error", "ioctl_failed");
                }
                return mutableValue.getInt(mutable);
            } catch (InvocationTargetException error) {
                Throwable cause = error.getCause();
                if (cause instanceof ErrnoException) {
                    int errno = ((ErrnoException) cause).errno;
                    if (errno == OsConstants.EACCES || errno == OsConstants.EPERM) {
                        throw new BootMediaPolicy.Failure("unreadable", "ioctl_unreadable");
                    }
                }
                throw new BootMediaPolicy.Failure("error", "ioctl_failed");
            } catch (ReflectiveOperationException error) {
                throw new BootMediaPolicy.Failure("error", "ioctl_api_unavailable");
            }
        }

        public void close() throws BootMediaPolicy.Failure {
            FileDescriptor owned = device;
            device = null;
            if (owned != null) {
                try { Os.close(owned); }
                catch (ErrnoException error) {
                    evidence.stage(BootMediaPolicy.Stage.CLOSE);
                    throw new BootMediaPolicy.Failure("error", "close_failed");
                }
            }
        }
    }

    private static void requireDevice(StructStat stat) throws BootMediaPolicy.Failure {
        BootMediaPolicy.verifyNode(node(stat));
    }

    private static BootMediaPolicy.Node node(StructStat stat) {
        long major = ((stat.st_rdev >>> 8) & 0xfffL) | ((stat.st_rdev >>> 32) & 0xfffff000L);
        long minor = (stat.st_rdev & 0xffL) | ((stat.st_rdev >>> 12) & 0xffffff00L);
        return new BootMediaPolicy.Node(stat.st_uid, stat.st_gid, stat.st_mode, major, minor);
    }

    private static boolean sameNode(StructStat first, StructStat second) {
        return first.st_dev == second.st_dev && first.st_ino == second.st_ino
                && first.st_rdev == second.st_rdev && first.st_mode == second.st_mode
                && first.st_uid == second.st_uid && first.st_gid == second.st_gid;
    }

    private static boolean sameFile(StructStat first, StructStat second) {
        return sameNode(first, second) && first.st_size == second.st_size
                && first.st_mtime == second.st_mtime && first.st_ctime == second.st_ctime;
    }

    private static String readKernel(BootMediaPolicy.Deadline deadline) throws BootMediaPolicy.Failure {
        FileDescriptor fd = null;
        try {
            deadline.check();
            fd = Os.open("/proc/sys/kernel/osrelease", FLAGS, 0);
            deadline.check();
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            byte[] buffer = new byte[257];
            while (true) {
                deadline.check();
                int length = Os.read(fd, buffer, 0, buffer.length);
                deadline.check();
                if (length == 0) break;
                if (length < 0 || output.size() + length > 256) {
                    throw new BootMediaPolicy.Failure("error", "kernel_invalid");
                }
                output.write(buffer, 0, length);
            }
            return new String(output.toByteArray(), UTF8).trim();
        } catch (Exception error) {
            if (error instanceof BootMediaPolicy.Failure) throw (BootMediaPolicy.Failure) error;
            throw new BootMediaPolicy.Failure("unreadable", "kernel_unreadable");
        } finally {
            closeOwned(fd);
        }
    }

    private static String libraryHash(BootMediaPolicy.Deadline deadline) throws BootMediaPolicy.Failure {
        FileDescriptor fd = null;
        try {
            deadline.check();
            StructStat before = Os.lstat(LIBRARY);
            deadline.check();
            if (!OsConstants.S_ISREG(before.st_mode) || before.st_uid != 0
                    || before.st_size <= 0 || before.st_size > 8L * 1024L * 1024L) {
                throw new BootMediaPolicy.Failure("error", "library_invalid");
            }
            fd = Os.open(LIBRARY, FLAGS, 0);
            deadline.check();
            StructStat opened = Os.fstat(fd);
            deadline.check();
            if (!sameFile(before, opened)) throw new BootMediaPolicy.Failure("error", "library_changed");
            MessageDigest sha = MessageDigest.getInstance("SHA-256");
            byte[] buffer = new byte[16384];
            long size = 0;
            while (true) {
                deadline.check();
                int length = Os.read(fd, buffer, 0, buffer.length);
                deadline.check();
                if (length == 0) break;
                size += length;
                if (length < 0 || size > before.st_size) throw new BootMediaPolicy.Failure("error", "library_changed");
                sha.update(buffer, 0, length);
            }
            StructStat finalOpened = Os.fstat(fd);
            deadline.check();
            StructStat finalPath = Os.lstat(LIBRARY);
            deadline.check();
            if (size != before.st_size || !sameFile(opened, finalOpened) || !sameFile(opened, finalPath)) {
                throw new BootMediaPolicy.Failure("error", "library_changed");
            }
            StringBuilder hex = new StringBuilder();
            for (byte value : sha.digest()) {
                hex.append("0123456789abcdef".charAt((value >>> 4) & 15));
                hex.append("0123456789abcdef".charAt(value & 15));
            }
            return hex.toString();
        } catch (BootMediaPolicy.Failure error) {
            throw error;
        } catch (ErrnoException error) {
            throw failureFor(error, "library");
        } catch (Exception error) {
            throw new BootMediaPolicy.Failure("error", "library_hash_failed");
        } finally {
            closeOwned(fd);
        }
    }

    private static void closeOwned(FileDescriptor fd) throws BootMediaPolicy.Failure {
        if (fd == null) return;
        try { Os.close(fd); }
        catch (ErrnoException error) { throw new BootMediaPolicy.Failure("error", "close_failed"); }
    }

    private static BootMediaPolicy.Failure failureFor(ErrnoException error, String scope) {
        if (error.errno == OsConstants.ENOENT) return new BootMediaPolicy.Failure("absent", scope + "_absent");
        if (error.errno == OsConstants.EACCES || error.errno == OsConstants.EPERM) {
            return new BootMediaPolicy.Failure("unreadable", scope + "_unreadable");
        }
        return new BootMediaPolicy.Failure("error", scope + "_io_error");
    }
}
