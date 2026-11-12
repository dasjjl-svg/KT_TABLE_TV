package net.quber.afactorymode;

import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public final class SpiControllerPolicyTest {
    private static int checks;
    private static final Charset UTF8 = Charset.forName("UTF-8");
    private static final int[] REVIEWED = {0xbc, 0x00, 0x04, 0x08, 0x18, 0x1c,
            0x24, 0x28, 0x2c, 0x30, 0x38, 0x3c, 0x40, 0x68, 0x70};

    private static class Mock implements SpiControllerPolicy.Access {
        final List<Integer> reads = new ArrayList<Integer>();
        final Map<Integer, Integer> overrides = new HashMap<Integer, Integer>();
        final int[] clocks = {0x81, 0x81, 0x81, 0x81};
        final int[] versions = {0x100, 0x100};
        int clockReads, versionReads, prepared, closed;
        int failOffset = -1, failSample;
        int changedOffset = -1, changedValue;
        boolean prepareFailure;
        Thread closer;

        public void prepare(SpiControllerPolicy.Deadline deadline) throws SpiControllerPolicy.Failure {
            prepared++;
            if (prepareFailure) throw new SpiControllerPolicy.Failure("unreadable", "mem_unreadable");
        }
        public int readClock() { return clocks[Math.min(clockReads++, clocks.length - 1)]; }
        public int readRegister(int offset) throws SpiControllerPolicy.Failure {
            SpiControllerPolicy.requireOffset(offset);
            reads.add(Integer.valueOf(offset));
            int sample = offset == SpiControllerPolicy.VERSION ? versionReads++ : versionReads - 1;
            if (offset == failOffset && sample == failSample) throw new SpiControllerPolicy.Failure("error", "read_failed");
            if (offset == SpiControllerPolicy.VERSION) return versions[Math.min(sample, 1)];
            if (sample == 1 && offset == changedOffset) return changedValue;
            if (overrides.containsKey(Integer.valueOf(offset))) return overrides.get(Integer.valueOf(offset)).intValue();
            switch (offset) {
                case 0x00: return 0x21;
                case 0x04: return 0xc4;
                case 0x08: return 0x6f;
                case 0x18: return 1;
                case 0x3c: return 0x1c4;
                default: return 0;
            }
        }
        public void close() throws SpiControllerPolicy.Failure { closed++; closer = Thread.currentThread(); }
    }

    public static void main(String[] args) throws Exception {
        Mock valid = new Mock();
        SpiControllerPolicy.SynchronousOnce once = new SpiControllerPolicy.SynchronousOnce(5000);
        SpiControllerPolicy.Result result = once.read(valid);
        String data = text(result);
        expect("ok".equals(result.status) && "register_snapshots_read".equals(result.reason), "snapshot_ok");
        expect(valid.reads.equals(expectedReads(2)), "two_complete_reviewed_snapshots");
        expect(valid.clockReads == 4 && valid.closed == 1 && valid.closer == Thread.currentThread(), "synchronous_cleanup");
        expect(data.contains("crg224_before=0x00000081\n") && data.contains("clock_select_raw=4\n"), "clock_format");
        expect(data.contains("resample_min_delay_ms=100\n") && data.contains("snapshot_count=2\n"), "resample_explicit");
        expect(data.contains("registers_unchanged=1\n") && data.contains("clock_unchanged=1\n"), "stable_reported");
        expect(data.contains("sampling=non_atomic\nidle=not_established\nownership=not_established\n"), "observation_not_ownership");
        expect(data.contains("flash_read=not_performed\n") && result.data.length <= 2048, "scope_and_size_bound");
        Mock retry = new Mock();
        expect(once.read(retry) == result && retry.prepared == 0, "success_not_repeated");

        for (int clock : new int[]{0, 0x80, 0x10, 0x11, 0x91, -1}) {
            Mock blocked = new Mock(); blocked.clocks[0] = clock;
            SpiControllerPolicy.Result r = run(blocked);
            expect("ok".equals(r.status) && "clock_off_or_reset_no_controller_read".equals(r.reason), "gated_clock");
            expect(blocked.reads.isEmpty() && blocked.clockReads == 1 && blocked.closed == 1, "clock_guard_no_fmc_access");
        }
        for (int version : new int[]{0, -1, 0x101, 0x200}) {
            Mock wrong = new Mock(); wrong.versions[0] = version;
            expect("controller_version_mismatch".equals(run(wrong).reason)
                    && wrong.reads.equals(Arrays.asList(0xbc)), "wrong_version_stops");
            Mock secondWrong = new Mock(); secondWrong.versions[1] = version;
            SpiControllerPolicy.Result r = run(secondWrong);
            List<Integer> expected = expectedReads(1); expected.add(Integer.valueOf(0xbc));
            expect("controller_version_changed".equals(r.reason) && secondWrong.reads.equals(expected), "second_wrong_version_stops");
            expect(text(r).contains("snapshot_count=1\n") && text(r).contains("registers_unchanged=not_compared\n"), "incomplete_not_equal");
        }
        for (int stage = 1; stage <= 3; stage++) {
            for (int value : new int[]{0, 0x91, 0x41}) {
                Mock changed = new Mock(); changed.clocks[stage] = value;
                SpiControllerPolicy.Result r = run(changed);
                expect(r.reason.startsWith("clock_changed_") && text(r).contains("clock_unchanged=0\n"), "changed_clock_reported");
                expect(changed.reads.equals(expectedReads(stage == 3 ? 2 : 1)) && changed.closed == 1, "clock_change_stops_next_snapshot");
                expect(changed.clockReads == stage + 1, "no_extra_access_after_clock_change");
            }
        }
        for (int offset : REVIEWED) {
            if (offset == SpiControllerPolicy.VERSION) continue;
            Mock changed = new Mock(); changed.changedOffset = offset; changed.changedValue = 0x87654321;
            SpiControllerPolicy.Result r = run(changed);
            expect("registers_changed_between_snapshots".equals(r.reason)
                    && text(r).contains("registers_unchanged=0\n"), "every_register_compared");
        }
        Mock flags = new Mock();
        flags.overrides.put(Integer.valueOf(0x30), Integer.valueOf(0xc00));
        flags.overrides.put(Integer.valueOf(0x3c), Integer.valueOf(0x1c5));
        flags.overrides.put(Integer.valueOf(0x68), Integer.valueOf(1));
        flags.overrides.put(Integer.valueOf(0x1c), Integer.valueOf(0x100));
        String flagText = text(run(flags));
        for (String prefix : new String[]{"fmc_", "fmc2_"}) {
            expect(flagText.contains(prefix + "op_start_raw=1\n"), "pio_start_reported");
            expect(flagText.contains(prefix + "op_ctrl_ready_raw=1\n"), "dma_ready_reported_without_idle_claim");
            expect(flagText.contains(prefix + "interrupt_enable=0x00000100\n")
                    && flagText.contains(prefix + "interrupts_enabled_any=1\n"), "interrupt_enable_reported");
            expect(flagText.contains(prefix + "cs_raw=1\n") && flagText.contains(prefix + "force_cs_raw=1\n"), "chipselect_reported");
        }
        expect(flagText.contains("idle=not_established\n") && flagText.contains("ownership=not_established\n"), "busy_flags_do_not_change_scope");
        Mock highBits = new Mock();
        for (int offset : REVIEWED) highBits.overrides.put(Integer.valueOf(offset), Integer.valueOf(-1));
        SpiControllerPolicy.Result highResult = run(highBits);
        expect("ok".equals(highResult.status) && highResult.data.length <= 2048
                && text(highResult).contains("fmc2_op_para=0xffffffff\n"), "unsigned_values_stay_bounded");

        for (int sample = 0; sample <= 1; sample++) {
            for (int offset : REVIEWED) {
                Mock failing = new Mock(); failing.failOffset = offset; failing.failSample = sample;
                SpiControllerPolicy.SynchronousOnce failedOnce = new SpiControllerPolicy.SynchronousOnce(5000);
                SpiControllerPolicy.Result failed = failedOnce.read(failing);
                expect("error".equals(failed.status) && failed.data.length == 0 && "read_failed".equals(failed.reason), "read_failure_empty");
                expect(failing.reads.get(failing.reads.size() - 1).intValue() == offset && failing.closed == 1, "read_failure_stops");
                Mock ignored = new Mock();
                expect(failedOnce.read(ignored) == failed && ignored.prepared == 0, "read_failure_not_repeated");
            }
        }
        Mock denied = new Mock(); denied.prepareFailure = true;
        SpiControllerPolicy.Result absent = run(denied);
        expect("unreadable".equals(absent.status) && absent.data.length == 0
                && denied.clockReads == 0 && denied.reads.isEmpty() && denied.closed == 1, "no_access_after_prepare_failure");
        expect(Arrays.equals(SpiControllerPolicy.registerOffsets(), REVIEWED), "exact_allowlist");
        for (int offset : new int[]{-1, 1, 0x0c, 0x10, 0x14, 0x20, 0x34, 0x44, 0x48, 0x4c,
                0x50, 0x54, 0x58, 0x5c, 0x6c, 0x74, 0xac, 0x100, Integer.MAX_VALUE}) {
            try { SpiControllerPolicy.requireOffset(offset); throw new AssertionError("unexpected_register_allowed"); }
            catch (SpiControllerPolicy.Failure expected) { expect("register_not_allowed".equals(expected.reason), "register_allowlist"); }
        }
        int[] altered = SpiControllerPolicy.registerOffsets(); altered[0] = 0x20;
        expect(SpiControllerPolicy.registerOffsets()[0] == 0xbc, "allowlist_cannot_be_mutated");
        Mock elapsed = new Mock() {
            public int readClock() { sleep(40); return super.readClock(); }
        };
        SpiControllerPolicy.SynchronousOnce timed = new SpiControllerPolicy.SynchronousOnce(20);
        SpiControllerPolicy.Result timeout = timed.read(elapsed);
        expect("timeout".equals(timeout.reason) && timeout.data.length == 0
                && elapsed.reads.isEmpty() && elapsed.closed == 1, "late_clock_no_followup");
        Mock ignored = new Mock();
        expect(timed.read(ignored) == timeout && ignored.prepared == 0, "timeout_not_repeated");
        Mock shortBudget = new Mock();
        SpiControllerPolicy.Result insufficient = new SpiControllerPolicy.SynchronousOnce(50).read(shortBudget);
        expect("timeout".equals(insufficient.reason) && insufficient.data.length == 0
                && shortBudget.reads.equals(expectedReads(1)) && shortBudget.clockReads == 2, "insufficient_resample_budget_stops");
        Mock interrupted = new Mock();
        try {
            Thread.currentThread().interrupt();
            SpiControllerPolicy.Result r = run(interrupted);
            expect("interrupted".equals(r.reason) && r.data.length == 0 && Thread.currentThread().isInterrupted(), "interrupt_restored");
            expect(interrupted.reads.equals(expectedReads(1)) && interrupted.clockReads == 2 && interrupted.closed == 1, "interruption_closes_without_resample");
        } finally { Thread.interrupted(); }
        Mock slowClose = new Mock() {
            public void close() throws SpiControllerPolicy.Failure { super.close(); sleep(600); }
        };
        expect("timeout".equals(new SpiControllerPolicy.SynchronousOnce(500).read(slowClose).reason), "close_time_accounted");
        Mock brokenClose = new Mock() {
            public void close() throws SpiControllerPolicy.Failure {
                super.close(); throw new SpiControllerPolicy.Failure("error", "close_error");
            }
        };
        SpiControllerPolicy.Result closeError = run(brokenClose);
        expect("close_failed".equals(closeError.reason) && closeError.data.length == 0 && brokenClose.closed == 1, "cleanup_failure_discards_data");
        System.out.println("SpiControllerPolicyTest passed: " + checks + " checks; snapshot bytes=" + result.data.length);
    }

    private static SpiControllerPolicy.Result run(Mock mock) {
        return new SpiControllerPolicy.SynchronousOnce(5000).read(mock);
    }

    private static List<Integer> expectedReads(int count) {
        List<Integer> values = new ArrayList<Integer>();
        for (int n = 0; n < count; n++) for (int offset : REVIEWED) values.add(Integer.valueOf(offset));
        return values;
    }

    private static String text(SpiControllerPolicy.Result result) { return new String(result.data, UTF8); }

    private static void sleep(long millis) {
        try { Thread.sleep(millis); }
        catch (InterruptedException error) { Thread.currentThread().interrupt(); throw new AssertionError(error); }
    }

    private static void expect(boolean value, String name) { checks++; if (!value) throw new AssertionError(name); }
}
