package net.quber.afactorymode;

import java.nio.charset.Charset;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

final class BootMediaPolicy {
    static final int GET_SCS = 0x8004613d;
    static final int GET_CONTROL = 0x8004614d;
    static final int GET_MODE = 0x80046137;
    static final String LIBRARY_SHA256 = "738eabc869c3e756a533fe60a06b1e3c2c8e95116acde36aa3c9090148ba4d2c";
    static final String KERNEL = "3.18.24_hi3798cv2x";
    private static final Charset UTF8 = Charset.forName("UTF-8");
    private static final String[] MEDIA = {"spi", "nand", "nor", "emmc", "spi_nand", "sd"};

    enum Stage {
        START("start"), ARGUMENT_GUARD("argument_guard"), ROOT_GUARD("root_guard"),
        PLATFORM("platform"), KERNEL("kernel"), LIBRARY("library"), IOCTL_API("ioctl_api"),
        NODE_LSTAT("node_lstat"), NODE_OPEN("node_open"), NODE_FSTAT("node_fstat"),
        NODE_PATH("node_path_check"), QUERY_SCS("query_scs"), QUERY_CONTROL("query_control"),
        QUERY_MODE("query_mode"), CLOSE("close"), COMPLETE("complete");

        final String value;
        Stage(String value) { this.value = value; }
    }

    static final class Node {
        final int uid, gid, mode;
        final long major, minor;

        Node(int uid, int gid, int mode, long major, long minor) {
            this.uid = uid;
            this.gid = gid;
            this.mode = mode;
            this.major = major;
            this.minor = minor;
        }
    }

    static final class Evidence {
        private final int callerUid;
        private Stage stage = Stage.START;
        private Node lstat, fstat, path;

        Evidence(int callerUid) { this.callerUid = callerUid; }
        synchronized void stage(Stage value) { stage = value; }
        synchronized void lstat(Node value) { lstat = value; }
        synchronized void fstat(Node value) { fstat = value; }
        synchronized void path(Node value) { path = value; }

        synchronized String snapshot() {
            StringBuilder text = new StringBuilder("caller_uid=").append(callerUid)
                    .append("\nstage=").append(stage.value).append('\n');
            append(text, "lstat", lstat);
            append(text, "fstat", fstat);
            append(text, "path", path);
            return text.toString();
        }

        private static void append(StringBuilder text, String prefix, Node node) {
            if (node == null) return;
            text.append(prefix).append("_type=").append(node.mode & 0170000).append('\n');
            text.append(prefix).append("_uid=").append(node.uid).append('\n');
            text.append(prefix).append("_gid=").append(node.gid).append('\n');
            text.append(prefix).append("_mode=").append(node.mode & 07777).append('\n');
            text.append(prefix).append("_major=").append(node.major).append('\n');
            text.append(prefix).append("_minor=").append(node.minor).append('\n');
        }
    }

    interface Access {
        void prepare(Deadline deadline) throws Failure;
        int get(int request) throws Failure;
        void close() throws Failure;
    }

    static final class Failure extends Exception {
        final String status;
        final String reason;

        Failure(String status, String reason) {
            super(reason);
            this.status = status;
            this.reason = reason;
        }
    }

    static final class Result {
        final String status;
        final byte[] data;
        final String reason;

        Result(String status, byte[] data, String reason) {
            this.status = status;
            this.data = data.clone();
            this.reason = reason;
        }
    }

    static final class Deadline {
        private final long end;
        private volatile boolean expired;

        Deadline(long milliseconds) {
            end = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(milliseconds);
        }

        long remaining() {
            return expired ? 0L : Math.max(0L, end - System.nanoTime());
        }

        void check() throws Failure {
            if (remaining() == 0L) throw new Failure("error", "timeout");
        }

        void expire() { expired = true; }
    }

    static final class Once {
        private final long timeoutMillis;
        private Result result;
        private FutureTask<Result> task;

        Once(long timeoutMillis) {
            if (timeoutMillis < 1L || timeoutMillis > 5000L) throw new IllegalArgumentException("timeout_range");
            this.timeoutMillis = timeoutMillis;
        }

        synchronized Result read(final Access access) {
            if (result != null) return result;
            if (task != null) return failed("error", "already_started");
            final Deadline deadline = new Deadline(timeoutMillis);
            task = new FutureTask<Result>(new Callable<Result>() {
                public Result call() { return evaluate(access, deadline); }
            });
            Thread worker = new Thread(task, "boot-media-query");
            worker.setDaemon(true);
            try {
                worker.start();
                result = task.get(deadline.remaining(), TimeUnit.NANOSECONDS);
            } catch (TimeoutException error) {
                deadline.expire();
                result = failed("error", "timeout");
            } catch (InterruptedException error) {
                deadline.expire();
                Thread.currentThread().interrupt();
                result = failed("error", "interrupted");
            } catch (ExecutionException error) {
                deadline.expire();
                result = failed("error", "probe_failed");
            } catch (RuntimeException error) {
                deadline.expire();
                result = failed("error", "probe_failed");
            }
            return result;
        }
    }

    static final class SynchronousOnce {
        private final long timeoutMillis;
        private boolean started;
        private Result result;

        SynchronousOnce(long timeoutMillis) {
            if (timeoutMillis < 1L || timeoutMillis > 5000L) throw new IllegalArgumentException("timeout_range");
            this.timeoutMillis = timeoutMillis;
        }

        synchronized Result read(Access access) {
            if (result != null) return result;
            if (started) return failed("error", "already_started");
            started = true;
            result = evaluate(access, new Deadline(timeoutMillis));
            return result;
        }
    }

    static void verifyPlatform(String model, String hardware, int sdk, String abi) throws Failure {
        if (!"CT1105_SB".equals(model) || !"bigfish".equals(hardware)
                || sdk != 22 || !"armeabi-v7a".equals(abi)) {
            throw new Failure("error", "platform_mismatch");
        }
    }

    static void verifyKernel(String kernel) throws Failure {
        if (!KERNEL.equals(kernel)) throw new Failure("error", "kernel_mismatch");
    }

    static void verifyLibrary(String digest) throws Failure {
        if (!LIBRARY_SHA256.equals(digest)) throw new Failure("error", "library_hash_mismatch");
    }

    static void verifyNode(Node node) throws Failure {
        if ((node.mode & 0170000) != 0020000 || (node.mode & 07777) != 0777
                || node.uid != 1000 || node.gid != 1005 || node.major != 218L || node.minor != 49L) {
            throw new Failure("error", "hi_ca_invalid");
        }
    }

    static void requireGetter(int request) throws Failure {
        if (request != GET_SCS && request != GET_CONTROL && request != GET_MODE) {
            throw new Failure("error", "ioctl_not_allowed");
        }
    }

    private static int get(Access access, Deadline deadline, int request) throws Failure {
        deadline.check();
        requireGetter(request);
        int value = access.get(request);
        deadline.check();
        return value;
    }

    private static Result evaluate(Access access, Deadline deadline) {
        Result result = failed("error", "probe_failed");
        try {
            deadline.check();
            access.prepare(deadline);
            int scs = get(access, deadline, GET_SCS);
            if (scs != 0 && scs != 1) throw new Failure("error", "scs_invalid");
            int control = get(access, deadline, GET_CONTROL);
            if (control != 0 && control != 1) throw new Failure("error", "boot_control_invalid");
            StringBuilder data = new StringBuilder("version=1\nsource=hi_ca_boot_getters\n");
            data.append("scs_active_raw=").append(scs).append('\n');
            data.append("boot_select_control_raw=").append(control).append('\n');
            if (control == 0) {
                data.append("boot_selection=chipset_pin\nboot_mode_raw=not_queried\n")
                        .append("configured_medium=unknown\nactual_boot_medium=unverified\n");
                result = new Result("ok", data.toString().getBytes(UTF8), "pin_controlled_medium_unknown");
            } else {
                int mode = get(access, deadline, GET_MODE);
                if (mode < 0 || mode >= MEDIA.length) throw new Failure("error", "boot_mode_invalid");
                data.append("boot_selection=otp\nboot_mode_raw=").append(mode).append('\n')
                        .append("configured_medium=").append(MEDIA[mode]).append('\n')
                        .append("actual_boot_medium=unverified\n");
                result = new Result("ok", data.toString().getBytes(UTF8), "otp_configuration_read");
            }
        } catch (Failure error) {
            result = failed(error.status, error.reason);
        } catch (RuntimeException error) {
            result = failed("error", "probe_failed");
        } finally {
            try {
                access.close();
            } catch (Failure error) {
                result = failed("error", "close_failed");
            } catch (RuntimeException error) {
                result = failed("error", "close_failed");
            }
        }
        if ("ok".equals(result.status)) {
            try { deadline.check(); }
            catch (Failure error) { result = failed(error.status, error.reason); }
        }
        return result;
    }

    private static Result failed(String status, String reason) {
        return new Result(status, new byte[0], reason);
    }
}
