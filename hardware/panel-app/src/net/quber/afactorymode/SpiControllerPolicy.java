package net.quber.afactorymode;

import java.nio.charset.Charset;
import java.util.concurrent.TimeUnit;

final class SpiControllerPolicy {
    static final long CLOCK_BASE = 0xf8a22000L;
    static final long FMC_BASE = 0xf9950000L;
    static final int CLOCK_OFFSET = 0x380;
    static final int VERSION = 0xbc;
    private static final int[] OFFSETS = {VERSION, 0x00, 0x04, 0x08, 0x18, 0x1c,
            0x24, 0x28, 0x2c, 0x30, 0x38, 0x3c, 0x40, 0x68, 0x70};
    private static final String[] NAMES = {"version", "cfg", "global_cfg", "timing_spi_cfg",
            "interrupt", "interrupt_enable", "cmd", "addrh", "addrl", "op_cfg", "data_num",
            "op", "dma_len", "op_ctrl", "op_para"};
    private static final long RESAMPLE_MILLIS = 100L;
    private static final int MAX_BYTES = 2048;
    private static final Charset UTF8 = Charset.forName("UTF-8");

    interface Access {
        void prepare(Deadline deadline) throws Failure;
        int readClock() throws Failure;
        int readRegister(int offset) throws Failure;
        void close() throws Failure;
    }

    static final class Failure extends Exception {
        final String status;
        final String reason;
        Failure(String status, String reason) { super(reason); this.status = status; this.reason = reason; }
    }

    static final class Result {
        final String status;
        final byte[] data;
        final String reason;
        Result(String status, byte[] data, String reason) {
            this.status = status; this.data = data.clone(); this.reason = reason;
        }
    }

    static final class Deadline {
        private final long end;
        Deadline(long millis) { end = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(millis); }
        long remaining() { return Math.max(0L, end - System.nanoTime()); }
        void check() throws Failure { if (remaining() == 0) throw new Failure("error", "timeout"); }
    }

    static final class SynchronousOnce {
        private final long millis;
        private boolean started;
        private Result saved;
        SynchronousOnce(long millis) {
            if (millis < 1 || millis > 5000) throw new IllegalArgumentException("timeout_range");
            this.millis = millis;
        }
        synchronized Result read(Access access) {
            if (saved != null) return saved;
            if (started) return failure("error", "already_started");
            started = true;
            saved = evaluate(access, new Deadline(millis));
            return saved;
        }
    }

    static int[] registerOffsets() { return OFFSETS.clone(); }

    static void requireOffset(int offset) throws Failure {
        for (int allowed : OFFSETS) if (offset == allowed) return;
        throw new Failure("error", "register_not_allowed");
    }

    private static int clock(Access access, Deadline deadline) throws Failure {
        deadline.check();
        int value = access.readClock();
        deadline.check();
        return value;
    }

    private static int register(Access access, Deadline deadline, int offset) throws Failure {
        deadline.check(); requireOffset(offset);
        int value = access.readRegister(offset);
        deadline.check();
        return value;
    }

    private static boolean clockAccessible(int value) {
        return (value & 1) != 0 && (value & 0x10) == 0;
    }

    private static int[] snapshot(Access access, Deadline deadline, int version) throws Failure {
        int[] values = new int[OFFSETS.length];
        values[0] = version;
        for (int i = 1; i < OFFSETS.length; i++) values[i] = register(access, deadline, OFFSETS[i]);
        return values;
    }

    private static void appendSnapshot(StringBuilder text, String prefix, int[] values) {
        for (int i = 0; i < OFFSETS.length; i++) {
            text.append(prefix).append(NAMES[i]).append('=').append(hex(values[i])).append('\n');
        }
        text.append(prefix).append("op_start_raw=").append(values[11] & 1).append('\n');
        text.append(prefix).append("op_ctrl_ready_raw=").append(values[13] & 1).append('\n');
        text.append(prefix).append("interrupts_enabled_any=").append((values[5] & 0x1ff) != 0 ? 1 : 0).append('\n');
        text.append(prefix).append("cs_raw=").append((values[9] >>> 11) & 1).append('\n');
        text.append(prefix).append("force_cs_raw=").append((values[9] >>> 10) & 1).append('\n');
    }

    private static void pause(Deadline deadline) throws Failure {
        deadline.check();
        if (deadline.remaining() < TimeUnit.MILLISECONDS.toNanos(RESAMPLE_MILLIS)) {
            throw new Failure("error", "timeout");
        }
        try { Thread.sleep(RESAMPLE_MILLIS); }
        catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            throw new Failure("error", "interrupted");
        }
        deadline.check();
    }

    private static Result observed(StringBuilder text, int count, String unchanged,
            String clockUnchanged, String access, String reason) {
        text.append("snapshot_count=").append(count).append('\n');
        text.append("registers_unchanged=").append(unchanged).append('\n');
        text.append("clock_unchanged=").append(clockUnchanged).append('\n');
        text.append("controller_access=").append(access).append('\n');
        text.append("sampling=non_atomic\nidle=not_established\nownership=not_established\n");
        text.append("flash_read=not_performed\n");
        return success(text, reason);
    }

    private static Result snapshots(Access access, Deadline deadline, StringBuilder text,
            int before, int version) throws Failure {
        int[] first = snapshot(access, deadline, version);
        appendSnapshot(text, "fmc_", first);
        int afterFirst = clock(access, deadline);
        text.append("crg224_after_first=").append(hex(afterFirst)).append('\n');
        if (!clockAccessible(afterFirst) || afterFirst != before) {
            return observed(text, 1, "not_compared", "0", "first_snapshot_only", "clock_changed_during_snapshot");
        }
        pause(deadline);
        text.append("resample_min_delay_ms=").append(RESAMPLE_MILLIS).append('\n');
        int beforeSecond = clock(access, deadline);
        text.append("crg224_before_second=").append(hex(beforeSecond)).append('\n');
        if (!clockAccessible(beforeSecond) || beforeSecond != before) {
            return observed(text, 1, "not_compared", "0", "first_snapshot_only", "clock_changed_before_resample");
        }
        int secondVersion = register(access, deadline, VERSION);
        if (secondVersion != 0x100) {
            text.append("fmc2_version=").append(hex(secondVersion)).append('\n');
            return observed(text, 1, "not_compared", "not_compared", "second_version_only", "controller_version_changed");
        }
        int[] second = snapshot(access, deadline, secondVersion);
        appendSnapshot(text, "fmc2_", second);
        int after = clock(access, deadline);
        text.append("crg224_after=").append(hex(after)).append('\n');
        boolean unchanged = java.util.Arrays.equals(first, second);
        String reason = before != after ? "clock_changed_during_snapshot"
                : unchanged ? "register_snapshots_read" : "registers_changed_between_snapshots";
        return observed(text, 2, unchanged ? "1" : "0", before == after ? "1" : "0", "sampled_twice", reason);
    }

    private static Result evaluate(Access access, Deadline deadline) {
        Result result = failure("error", "snapshot_failed");
        try {
            deadline.check(); access.prepare(deadline);
            int before = clock(access, deadline);
            StringBuilder text = new StringBuilder("version=2\nsource=spi_controller_state\n");
            text.append("crg224_before=").append(hex(before)).append('\n');
            text.append("clock_enabled=").append(before & 1).append('\n');
            text.append("reset_requested=").append((before >>> 4) & 1).append('\n');
            text.append("clock_select_raw=").append((before >>> 5) & 7).append('\n');
            if (!clockAccessible(before)) {
                result = observed(text, 0, "not_compared", "not_compared", "skipped", "clock_off_or_reset_no_controller_read");
            } else {
                int version = register(access, deadline, VERSION);
                if (version != 0x100) {
                    text.append("fmc_version=").append(hex(version)).append('\n');
                    result = observed(text, 0, "not_compared", "not_compared", "version_only", "controller_version_mismatch");
                } else {
                    result = snapshots(access, deadline, text, before, version);
                }
            }
        } catch (Failure error) {
            result = failure(error.status, error.reason);
        } catch (RuntimeException error) {
            result = failure("error", "snapshot_failed");
        } finally {
            try { access.close(); }
            catch (Failure error) { result = failure("error", "close_failed"); }
            catch (RuntimeException error) { result = failure("error", "close_failed"); }
        }
        if ("ok".equals(result.status)) {
            try { deadline.check(); }
            catch (Failure error) { return failure(error.status, error.reason); }
        }
        return result;
    }

    private static Result success(StringBuilder text, String reason) {
        byte[] data = text.toString().getBytes(UTF8);
        if (data.length > MAX_BYTES) return failure("error", "snapshot_too_large");
        return new Result("ok", data, reason);
    }

    private static Result failure(String status, String reason) {
        return new Result(status, new byte[0], reason);
    }

    private static String hex(int value) {
        String digits = Integer.toHexString(value);
        StringBuilder out = new StringBuilder("0x");
        for (int n = digits.length(); n < 8; n++) out.append('0');
        return out.append(digits).toString();
    }
}
