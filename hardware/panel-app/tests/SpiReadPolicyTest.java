package net.quber.afactorymode;

import java.util.Arrays;
import java.util.Map;

public final class SpiReadPolicyTest {
    private static int checks;
    private static final String[] COMPARISONS = {"short_repeat_equal", "long_repeat_equal", "overlap_0_equal",
            "overlap_100_equal", "overlap_200_equal", "long_period_256", "long_period_512"};
    private static final String[] LONG_HEX = {"long_head32_hex", "long_mid32_hex", "long_tail32_hex"};
    private interface Action { void run(); }

    private static String good(byte[] payload) {
        boolean repeat = true;
        for (int i = 0; i < 256; i++) repeat &= payload[i] == payload[1792 + i];
        return "version=1\nstatus=ok\nreason=read_probe_complete\nid1=c29e16c29e16c29e\nid2=c29e16c29e16c29e\n"
                + "sr_before=00\nsr_after=00\ncommands_started=18\nconfig_restored=1\nrestoration_incomplete=0\n"
                + "flash_array_read=sampled_addresses\nflash_program_erase=not_performed\nownership=not_established\n"
                + "interrupt_buffer_preserved=not_guaranteed\nop_flags_left=not_restored\npayload_bytes=2048\n"
                + "sample_count=8\nsample_bytes=2048\narray_commands=10\narray_bytes_read=10240\nstatus_checks=4\nid_checks=4\n"
                + "short_repeat_equal=" + (repeat ? "1" : "0") + "\nlong_repeat_equal=1\noverlap_0_equal=1\n"
                + "overlap_100_equal=1\noverlap_200_equal=1\nlong_period_256=1\nlong_period_512=1\n"
                + "long_head32_hex=" + hex(payload, 0) + "\nlong_mid32_hex=" + zeros() + "\nlong_tail32_hex=" + zeros()
                + "\naddress_space_mode=unverified\n";
    }

    private static String earlyFailure() {
        String text = good(new byte[2048]);
        for (String key : new String[]{"commands_started", "restoration_incomplete", "payload_bytes", "sample_count",
                "sample_bytes", "array_commands", "array_bytes_read", "status_checks", "id_checks"}) text = set(text, key, "0");
        for (String key : new String[]{"id1", "id2", "sr_before", "sr_after"}) text = set(text, key, "unknown");
        for (String key : COMPARISONS) text = set(text, key, "unknown");
        for (String key : LONG_HEX) text = set(text, key, "unknown");
        text = set(text, "status", "error"); text = set(text, "reason", "preflight_mismatch");
        text = set(text, "config_restored", "not_changed");
        return set(text, "flash_array_read", "not_performed");
    }

    public static void main(String[] args) {
        final byte[] same = new byte[2048];
        Map<String, String> parsed = SpiReadPolicy.nativeReport(good(same), same);
        check(parsed.size() == 33 && "ok".equals(parsed.get("status")), "identical_zero_samples_are_valid_diagnostic");
        Arrays.fill(same, (byte)0xff);
        check("ok".equals(SpiReadPolicy.nativeReport(good(same), same).get("status")), "identical_ff_samples_allowed");
        Arrays.fill(same, (byte)0x55);
        check("ok".equals(SpiReadPolicy.nativeReport(good(same), same).get("status")), "repeated_sample_pattern_allowed");
        final byte[] different = same.clone();
        different[2047] ^= 1;
        check("0".equals(SpiReadPolicy.nativeReport(good(different), different).get("short_repeat_equal")), "unequal_short_repeat_allowed");
        String allDifferent = good(different);
        for (String key : COMPARISONS) allDifferent = set(allDifferent, key, "0");
        check("ok".equals(SpiReadPolicy.nativeReport(allDifferent, different).get("status")), "all_observed_mismatches_allowed");
        check("ok".equals(SpiReadPolicy.nativeReport(set(good(same), "overlap_0_equal", "0"), same).get("status")),
                "matching_32_bytes_do_not_prove_256_byte_overlap");
        reject(new Action() { public void run() { SpiReadPolicy.nativeReport(set(good(same), "short_repeat_equal", "0"), same); }});
        reject(new Action() { public void run() { SpiReadPolicy.nativeReport(set(good(different), "short_repeat_equal", "1"), different); }});
        reject(new Action() { public void run() { SpiReadPolicy.nativeReport(set(good(same), "long_head32_hex", zeros()), same); }});

        for (final String replacement : new String[]{"version=2", "reason=identity_read", "commands_started=17", "array_commands=9",
                "array_bytes_read=9984", "status_checks=3", "id_checks=3", "sample_count=7", "sample_bytes=1792", "payload_bytes=0",
                "config_restored=0", "restoration_incomplete=1", "id1=c29e15c29e15c29e", "id2=unknown", "sr_before=01", "sr_after=unknown",
                "flash_array_read=incomplete", "flash_program_erase=performed", "ownership=established", "op_flags_left=restored",
                "interrupt_buffer_preserved=guaranteed", "address_space_mode=main_array"}) {
            final int split = replacement.indexOf('=');
            reject(new Action() { public void run() {
                SpiReadPolicy.nativeReport(set(good(same), replacement.substring(0, split), replacement.substring(split + 1)), same);
            }});
        }
        for (final String key : COMPARISONS) {
            for (final String value : new String[]{"unknown", "2", "true", "-1", "01"}) {
                reject(new Action() { public void run() { SpiReadPolicy.nativeReport(set(good(same), key, value), same); }});
            }
        }
        for (final String key : LONG_HEX) {
            for (final String value : new String[]{"unknown", "00", zeros() + "00", zeros().substring(2), "G" + zeros().substring(1)}) {
                reject(new Action() { public void run() { SpiReadPolicy.nativeReport(set(good(same), key, value), same); }});
            }
        }
        for (final String key : new String[]{"commands_started", "array_commands", "array_bytes_read", "status_checks", "id_checks",
                "sample_count", "sample_bytes", "payload_bytes", "restoration_incomplete"}) {
            for (final String value : new String[]{"-1", "+1", "01", " 1", "1 ", "0x1", "999999999999999999", "2147483648"}) {
                reject(new Action() { public void run() { SpiReadPolicy.nativeReport(set(good(same), key, value), same); }});
            }
        }
        for (final String line : good(same).split("\n")) {
            reject(new Action() { public void run() { SpiReadPolicy.nativeReport(good(same).replace(line + "\n", ""), same); }});
            reject(new Action() { public void run() { SpiReadPolicy.nativeReport(good(same) + line + "\n", same); }});
        }
        for (final String tail : new String[]{"\n", "unknown=1\n", "bad\n", "x=\u0001\n", "x=a=b\n"}) {
            reject(new Action() { public void run() { SpiReadPolicy.nativeReport(good(same) + tail, same); }});
        }
        reject(new Action() { public void run() { SpiReadPolicy.nativeReport(null, same); }});
        reject(new Action() { public void run() { SpiReadPolicy.nativeReport(good(same), null); }});
        reject(new Action() { public void run() { SpiReadPolicy.nativeReport(new String(new char[1501]).replace('\0', 'x'), same); }});
        for (final int length : new int[]{0, 1, 2047, 2049, 4096}) {
            reject(new Action() { public void run() { SpiReadPolicy.nativeReport(good(same), new byte[length]); }});
        }

        check("error".equals(SpiReadPolicy.nativeReport(earlyFailure(), new byte[0]).get("status")), "early_failure_unknown_observations");
        for (final String key : LONG_HEX) {
            reject(new Action() { public void run() { SpiReadPolicy.nativeReport(set(earlyFailure(), key, zeros()), new byte[0]); }});
        }
        String completedFailure = set(set(set(set(good(same), "status", "error"), "reason", "cleanup_failed"),
                "payload_bytes", "0"), "flash_array_read", "incomplete");
        for (String key : LONG_HEX) completedFailure = set(completedFailure, key, "unknown");
        final String completeError = completedFailure;
        check("error".equals(SpiReadPolicy.nativeReport(completeError, new byte[0]).get("status")), "completed_read_failure_no_payload");
        reject(new Action() { public void run() { SpiReadPolicy.nativeReport(completeError, same); }});
        reject(new Action() { public void run() { SpiReadPolicy.nativeReport(set(completeError, "payload_bytes", "2048"), same); }});
        String partial = earlyFailure();
        for (String[] change : new String[][]{{"reason", "operation_failed"}, {"commands_started", "5"}, {"status_checks", "2"},
                {"id_checks", "2"}, {"id1", "c29e16c29e16c29e"}, {"id2", "c29e16c29e16c29e"}, {"sr_before", "00"},
                {"sample_count", "1"}, {"sample_bytes", "256"}, {"array_commands", "1"}, {"array_bytes_read", "256"},
                {"config_restored", "1"}, {"flash_array_read", "incomplete"}}) partial = set(partial, change[0], change[1]);
        check("error".equals(SpiReadPolicy.nativeReport(partial, new byte[0]).get("status")), "partial_short_read_failure");
        final String interrupted = set(set(set(set(partial, "commands_started", "6"), "array_commands", "2"),
                "config_restored", "0"), "restoration_incomplete", "1");
        check("1".equals(SpiReadPolicy.nativeReport(interrupted, new byte[0]).get("restoration_incomplete")), "inflight_failure_evidence_retained");
        reject(new Action() { public void run() { SpiReadPolicy.nativeReport(set(interrupted, "restoration_incomplete", "0"), new byte[0]); }});
        reject(new Action() { public void run() { SpiReadPolicy.nativeReport(set(interrupted, "sample_bytes", "257"), new byte[0]); }});
        reject(new Action() { public void run() { SpiReadPolicy.nativeReport(set(interrupted, "array_bytes_read", "512"), new byte[0]); }});
        reject(new Action() { public void run() { SpiReadPolicy.nativeReport(set(earlyFailure(), "id1", "c29e16c29e16c29e"), new byte[0]); }});
        System.out.println("SpiReadPolicyTest: " + checks + " checks passed");
    }

    private static String set(String text, String key, String value) {
        return text.replaceAll("(?m)^" + key + "=[^\n]*", key + "=" + value);
    }

    private static String zeros() { return new String(new char[64]).replace('\0', '0'); }

    private static String hex(byte[] bytes, int offset) {
        char[] result = new char[64];
        char[] alphabet = "0123456789abcdef".toCharArray();
        for (int i = 0; i < 32; i++) {
            int value = bytes[offset + i] & 255;
            result[i * 2] = alphabet[value >>> 4]; result[i * 2 + 1] = alphabet[value & 15];
        }
        return new String(result);
    }

    private static void reject(Action action) {
        checks++;
        try { action.run(); } catch (IllegalArgumentException expected) { return; }
        throw new AssertionError("accepted_invalid_read_evidence");
    }

    private static void check(boolean value, String label) {
        checks++;
        if (!value) throw new AssertionError(label);
    }
}
