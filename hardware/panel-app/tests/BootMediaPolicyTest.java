package net.quber.afactorymode;

import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

public final class BootMediaPolicyTest {
    private static int checks;
    private static final Charset UTF8 = Charset.forName("UTF-8");

    private static class Mock implements BootMediaPolicy.Access {
        final int[] values;
        final List<Integer> calls = new ArrayList<Integer>();
        int prepared;
        int closed;
        int failAt = -1;
        boolean prepareFailure;
        boolean closeFailure;
        Thread closer;

        Mock(int... values) { this.values = values; }

        public void prepare(BootMediaPolicy.Deadline deadline) throws BootMediaPolicy.Failure {
            prepared++;
            if (prepareFailure) throw new BootMediaPolicy.Failure("unreadable", "hi_ca_unreadable");
        }

        public int get(int request) throws BootMediaPolicy.Failure {
            BootMediaPolicy.requireGetter(request);
            int index = calls.size();
            calls.add(Integer.valueOf(request));
            if (index == failAt) throw new BootMediaPolicy.Failure("error", "ioctl_failed");
            if (index >= values.length) throw new AssertionError("unexpected_query");
            return values[index];
        }

        public void close() throws BootMediaPolicy.Failure {
            closed++;
            closer = Thread.currentThread();
            if (closeFailure) throw new BootMediaPolicy.Failure("error", "close_failed");
        }
    }

    public static void main(String[] args) throws Exception {
        Mock pin = new Mock(1, 0);
        BootMediaPolicy.Once once = new BootMediaPolicy.Once(5000L);
        BootMediaPolicy.Result pinResult = once.read(pin);
        expect("ok".equals(pinResult.status), "pin_ok");
        String pinText = new String(pinResult.data, UTF8);
        expect(pinText.contains("boot_selection=chipset_pin\n"), "pin_selection");
        expect(pinText.contains("configured_medium=unknown\n"), "pin_unknown");
        expect(pinText.contains("boot_mode_raw=not_queried\n"), "no_fabricated_mode");
        expect(pin.calls.equals(Arrays.asList(BootMediaPolicy.GET_SCS, BootMediaPolicy.GET_CONTROL)), "pin_only_two_getters");
        expect(pin.closed == 1 && pin.closer != Thread.currentThread(), "worker_closes");
        Mock notRun = new Mock();
        expect(once.read(notRun) == pinResult && notRun.prepared == 0 && notRun.closed == 0, "successful_once");

        String[] names = {"spi", "nand", "nor", "emmc", "spi_nand", "sd"};
        for (int mode = 0; mode < names.length; mode++) {
            Mock otp = new Mock(0, 1, mode);
            BootMediaPolicy.Result result = new BootMediaPolicy.Once(5000L).read(otp);
            String text = new String(result.data, UTF8);
            expect("ok".equals(result.status) && text.contains("boot_mode_raw=" + mode + "\n"), "valid_mode_raw");
            expect(text.contains("boot_selection=otp\n"), "otp_selection");
            expect(text.contains("configured_medium=" + names[mode] + "\n"), "mode_mapping");
            expect(text.contains("actual_boot_medium=unverified\n"), "not_actual_boot_proof");
            expect(otp.calls.equals(Arrays.asList(BootMediaPolicy.GET_SCS, BootMediaPolicy.GET_CONTROL,
                    BootMediaPolicy.GET_MODE)) && otp.closed == 1, "otp_three_getters");
        }

        for (int invalid : new int[] {-1, 2, Integer.MIN_VALUE, Integer.MAX_VALUE}) {
            Mock scs = new Mock(invalid);
            failed(new BootMediaPolicy.Once(5000L).read(scs), "scs_invalid");
            expect(scs.calls.size() == 1 && scs.closed == 1, "invalid_scs_stops");
            Mock control = new Mock(1, invalid);
            failed(new BootMediaPolicy.Once(5000L).read(control), "boot_control_invalid");
            expect(control.calls.size() == 2 && control.closed == 1, "invalid_control_stops");
        }
        for (int invalid : new int[] {-1, 6, 7, Integer.MIN_VALUE, Integer.MAX_VALUE}) {
            Mock mode = new Mock(1, 1, invalid);
            failed(new BootMediaPolicy.Once(5000L).read(mode), "boot_mode_invalid");
            expect(mode.closed == 1, "invalid_mode_closed");
        }
        for (int failAt = 0; failAt < 3; failAt++) {
            Mock failing = new Mock(1, 1, 3);
            failing.failAt = failAt;
            BootMediaPolicy.Once failureOnce = new BootMediaPolicy.Once(5000L);
            BootMediaPolicy.Result result = failureOnce.read(failing);
            failed(result, "ioctl_failed");
            expect(failing.calls.size() == failAt + 1 && failing.closed == 1, "failure_stops_sequence");
            Mock ignored = new Mock();
            expect(failureOnce.read(ignored) == result && ignored.prepared == 0, "failure_no_retry");
        }
        Mock denied = new Mock();
        denied.prepareFailure = true;
        BootMediaPolicy.Result deniedResult = new BootMediaPolicy.Once(5000L).read(denied);
        expect("unreadable".equals(deniedResult.status) && deniedResult.data.length == 0, "prepare_status");
        expect(denied.calls.size() == 0 && denied.closed == 1, "prepare_failure_no_ioctl");
        Mock closeFailure = new Mock(1, 0);
        closeFailure.closeFailure = true;
        failed(new BootMediaPolicy.Once(5000L).read(closeFailure), "close_failed");

        for (int request : new int[] {0, 0x40046137, 0x80086137, 0x80046138, 0x8004613e, -1}) {
            try {
                BootMediaPolicy.requireGetter(request);
                throw new AssertionError("unlisted_ioctl_allowed");
            } catch (BootMediaPolicy.Failure expected) {
                expect("ioctl_not_allowed".equals(expected.reason), "allowlist");
            }
        }
        BootMediaPolicy.verifyPlatform("CT1105_SB", "bigfish", 22, "armeabi-v7a");
        for (int field = 0; field < 4; field++) {
            try {
                BootMediaPolicy.verifyPlatform(field == 0 ? "CT1105" : "CT1105_SB", field == 1 ? "other" : "bigfish",
                        field == 2 ? 23 : 22, field == 3 ? "arm64-v8a" : "armeabi-v7a");
                throw new AssertionError("platform_mismatch_allowed");
            } catch (BootMediaPolicy.Failure expected) {
                expect("platform_mismatch".equals(expected.reason), "platform_guard");
            }
        }
        BootMediaPolicy.verifyKernel("3.18.24_hi3798cv2x");
        BootMediaPolicy.verifyLibrary(BootMediaPolicy.LIBRARY_SHA256);
        try {
            BootMediaPolicy.verifyKernel("3.18.24");
            throw new AssertionError("kernel_mismatch_allowed");
        } catch (BootMediaPolicy.Failure expected) { expect("kernel_mismatch".equals(expected.reason), "kernel_guard"); }
        try {
            BootMediaPolicy.verifyLibrary("0000000000000000000000000000000000000000000000000000000000000000");
            throw new AssertionError("hash_mismatch_allowed");
        } catch (BootMediaPolicy.Failure expected) { expect("library_hash_mismatch".equals(expected.reason), "hash_guard"); }

        timeoutTest();
        nodeTest();
        synchronousTest();
        evidenceTest();
        System.out.println("BootMediaPolicyTest passed: " + checks + " checks");
    }

    private static void nodeTest() throws Exception {
        BootMediaPolicy.verifyNode(new BootMediaPolicy.Node(1000, 1005, 0020777, 218L, 49L));
        BootMediaPolicy.Node[] invalid = {
                new BootMediaPolicy.Node(0, 1005, 0020777, 218L, 49L),
                new BootMediaPolicy.Node(1000, 1041, 0020777, 218L, 49L),
                new BootMediaPolicy.Node(1000, 1005, 0100777, 218L, 49L),
                new BootMediaPolicy.Node(1000, 1005, 0120777, 218L, 49L),
                new BootMediaPolicy.Node(1000, 1005, 0020660, 218L, 49L),
                new BootMediaPolicy.Node(1000, 1005, 0020666, 218L, 49L),
                new BootMediaPolicy.Node(1000, 1005, 0020677, 218L, 49L),
                new BootMediaPolicy.Node(1000, 1005, 0020770, 218L, 49L),
                new BootMediaPolicy.Node(1000, 1005, 0022777, 218L, 49L),
                new BootMediaPolicy.Node(1000, 1005, 0024777, 218L, 49L),
                new BootMediaPolicy.Node(1000, 1005, 0020600, 218L, 49L),
                new BootMediaPolicy.Node(1000, 1005, 0020777, 219L, 49L),
                new BootMediaPolicy.Node(1000, 1005, 0020777, 218L, 97L)
        };
        for (BootMediaPolicy.Node node : invalid) {
            try {
                BootMediaPolicy.verifyNode(node);
                throw new AssertionError("unsafe_node_allowed");
            } catch (BootMediaPolicy.Failure expected) {
                expect("hi_ca_invalid".equals(expected.reason), "node_guard");
            }
        }
    }

    private static void synchronousTest() throws Exception {
        final Thread caller = Thread.currentThread();
        Mock direct = new Mock(1, 0) {
            public void prepare(BootMediaPolicy.Deadline deadline) throws BootMediaPolicy.Failure {
                expect(Thread.currentThread() == caller, "synchronous_prepare_thread");
                super.prepare(deadline);
            }
            public int get(int request) throws BootMediaPolicy.Failure {
                expect(Thread.currentThread() == caller, "synchronous_get_thread");
                return super.get(request);
            }
        };
        BootMediaPolicy.SynchronousOnce sync = new BootMediaPolicy.SynchronousOnce(5000L);
        BootMediaPolicy.Result result = sync.read(direct);
        expect("ok".equals(result.status) && direct.closer == caller, "synchronous_close_thread");
        expect(direct.calls.equals(Arrays.asList(BootMediaPolicy.GET_SCS, BootMediaPolicy.GET_CONTROL)),
                "synchronous_pin_skips_mode");
        Mock retry = new Mock();
        expect(sync.read(retry) == result && retry.prepared == 0 && retry.closed == 0, "synchronous_no_retry");

        Mock elapsed = new Mock(1, 1, 3) {
            public int get(int request) throws BootMediaPolicy.Failure {
                int value = super.get(request);
                try { Thread.sleep(40L); }
                catch (InterruptedException error) { throw new AssertionError("unexpected_interrupt"); }
                return value;
            }
        };
        BootMediaPolicy.SynchronousOnce bounded = new BootMediaPolicy.SynchronousOnce(20L);
        BootMediaPolicy.Result timeout = bounded.read(elapsed);
        failed(timeout, "timeout");
        expect(elapsed.calls.size() == 1 && elapsed.closed == 1 && elapsed.closer == caller,
                "synchronous_late_return_stops_sequence_and_closes");
        Mock ignored = new Mock();
        expect(bounded.read(ignored) == timeout && ignored.prepared == 0, "synchronous_timeout_no_retry");

        Mock prepareElapsed = new Mock() {
            public void prepare(BootMediaPolicy.Deadline deadline) throws BootMediaPolicy.Failure {
                super.prepare(deadline);
                deadline.expire();
            }
        };
        failed(new BootMediaPolicy.SynchronousOnce(5000L).read(prepareElapsed), "timeout");
        expect(prepareElapsed.calls.isEmpty() && prepareElapsed.closed == 1, "deadline_after_prepare_blocks_ioctl");
        Mock failedOpen = new Mock();
        failedOpen.prepareFailure = true;
        BootMediaPolicy.SynchronousOnce deniedOnce = new BootMediaPolicy.SynchronousOnce(5000L);
        BootMediaPolicy.Result denied = deniedOnce.read(failedOpen);
        expect("unreadable".equals(denied.status), "synchronous_prepare_denied");
        Mock deniedRetry = new Mock();
        expect(deniedOnce.read(deniedRetry) == denied && deniedRetry.prepared == 0,
                "synchronous_open_failure_not_retried");
    }

    private static void evidenceTest() {
        BootMediaPolicy.Evidence evidence = new BootMediaPolicy.Evidence(0);
        evidence.stage(BootMediaPolicy.Stage.NODE_LSTAT);
        evidence.lstat(new BootMediaPolicy.Node(1000, 1005, 0020777, 218L, 49L));
        String text = evidence.snapshot();
        expect(text.contains("stage=node_lstat\n") && text.contains("lstat_gid=1005\n"),
                "failure_metadata_available");
        expect(text.contains("lstat_type=8192\n") && text.contains("lstat_mode=511\n"),
                "metadata_type_and_permissions_distinct");
        expect(!text.contains("fstat_") && !text.contains("/dev/"), "no_invented_or_content_metadata");
        BootMediaPolicy.Node extremes = new BootMediaPolicy.Node(Integer.MIN_VALUE, Integer.MAX_VALUE,
                Integer.MAX_VALUE, Long.MIN_VALUE, Long.MAX_VALUE);
        evidence.lstat(extremes);
        evidence.fstat(extremes);
        evidence.path(extremes);
        evidence.stage(BootMediaPolicy.Stage.QUERY_CONTROL);
        expect(evidence.snapshot().getBytes(UTF8).length <= 2048, "metadata_bounded");
    }

    private static void timeoutTest() throws Exception {
        final CountDownLatch started = new CountDownLatch(1);
        final CountDownLatch release = new CountDownLatch(1);
        final CountDownLatch closeDone = new CountDownLatch(1);
        Mock blocked = new Mock(1, 1, 3) {
            public int get(int request) throws BootMediaPolicy.Failure {
                int result = super.get(request);
                started.countDown();
                try {
                    if (!release.await(3, TimeUnit.SECONDS)) throw new AssertionError("test_release_missing");
                } catch (InterruptedException error) {
                    throw new AssertionError("worker_interrupted");
                }
                return result;
            }
            public void close() throws BootMediaPolicy.Failure {
                super.close();
                closeDone.countDown();
            }
        };
        BootMediaPolicy.Once once = new BootMediaPolicy.Once(250L);
        BootMediaPolicy.Result result;
        try {
            result = once.read(blocked);
            expect(started.await(1, TimeUnit.SECONDS), "query_started");
            failed(result, "timeout");
            expect(closeDone.getCount() == 1L, "timeout_does_not_close_worker_fd");
            Mock retry = new Mock();
            expect(once.read(retry) == result && retry.prepared == 0, "timeout_no_retry");
        } finally {
            release.countDown();
        }
        expect(closeDone.await(1, TimeUnit.SECONDS), "late_worker_closes");
        expect(blocked.calls.size() == 1 && blocked.closed == 1, "timeout_stops_remaining_getters");
        expect(blocked.closer != Thread.currentThread(), "fd_owned_by_worker");
        Mock afterClose = new Mock();
        expect(once.read(afterClose) == result && afterClose.prepared == 0, "timeout_result_stable_after_close");
    }

    private static void failed(BootMediaPolicy.Result result, String reason) {
        expect("error".equals(result.status) && reason.equals(result.reason) && result.data.length == 0, reason);
    }

    private static void expect(boolean value, String label) {
        checks++;
        if (!value) throw new AssertionError(label);
    }
}
