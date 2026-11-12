package net.quber.afactorymode;

import java.util.Map;

final class SpiReadPolicy {
    private static final int PAYLOAD_SIZE = 2048;
    private static final String ID = "c29e16c29e16c29e";
    private static final String[] COMPARISONS = {"short_repeat_equal", "long_repeat_equal", "overlap_0_equal",
            "overlap_100_equal", "overlap_200_equal", "long_period_256", "long_period_512"};
    private static final String[] LONG_HEX = {"long_head32_hex", "long_mid32_hex", "long_tail32_hex"};
    private static final String[] KEYS = {"version", "status", "reason", "id1", "id2", "sr_before", "sr_after",
            "commands_started", "config_restored", "restoration_incomplete", "flash_array_read",
            "flash_program_erase", "ownership", "interrupt_buffer_preserved", "op_flags_left", "payload_bytes",
            "sample_count", "sample_bytes", "array_commands", "array_bytes_read", "status_checks", "id_checks",
            "short_repeat_equal", "long_repeat_equal", "overlap_0_equal", "overlap_100_equal", "overlap_200_equal",
            "long_period_256", "long_period_512", "long_head32_hex", "long_mid32_hex", "long_tail32_hex",
            "address_space_mode"};

    private SpiReadPolicy() {}

    static Map<String, String> nativeReport(String text, byte[] payload) {
        if (text == null || payload == null || text.endsWith("\n\n")) {
            throw new IllegalArgumentException("native_null_or_blank");
        }
        Map<String, String> values = SpiIdentityPolicy.fields(text, 1500);
        if (values.size() != KEYS.length) throw new IllegalArgumentException("native_field_count");
        for (String key : KEYS) if (!values.containsKey(key)) throw new IllegalArgumentException("native_missing_field");
        require(values, "version", "1");
        require(values, "flash_program_erase", "not_performed");
        require(values, "ownership", "not_established");
        require(values, "interrupt_buffer_preserved", "not_guaranteed");
        require(values, "op_flags_left", "not_restored");
        require(values, "address_space_mode", "unverified");
        if (!values.get("status").matches("ok|error") || !values.get("reason").matches("[a-z][a-z0-9_]{0,79}")
                || !values.get("config_restored").matches("0|1|not_changed")) {
            throw new IllegalArgumentException("native_field_value");
        }
        for (String key : new String[]{"id1", "id2"}) {
            if (!values.get(key).matches("unknown|[0-9a-f]{16}")) throw new IllegalArgumentException("native_id_value");
        }
        for (String key : new String[]{"sr_before", "sr_after"}) {
            if (!values.get(key).matches("unknown|[0-9a-f]{2}")) throw new IllegalArgumentException("native_sr_value");
        }
        boolean success = "ok".equals(values.get("status"));
        for (String key : COMPARISONS) {
            if (!values.get(key).matches(success ? "0|1" : "0|1|unknown")) {
                throw new IllegalArgumentException("native_comparison_value");
            }
        }
        for (String key : LONG_HEX) {
            if (!values.get(key).matches(success ? "[0-9a-f]{64}" : "unknown")) {
                throw new IllegalArgumentException("native_long_sample_value");
            }
        }
        int commands = number(values, "commands_started", 18);
        int arrays = number(values, "array_commands", 10);
        int bytes = number(values, "array_bytes_read", 10240);
        int statusChecks = number(values, "status_checks", 4);
        int idChecks = number(values, "id_checks", 4);
        int samples = number(values, "sample_count", 8);
        int sampleBytes = number(values, "sample_bytes", PAYLOAD_SIZE);
        int payloadBytes = number(values, "payload_bytes", PAYLOAD_SIZE);
        int incomplete = number(values, "restoration_incomplete", 1);
        if (payloadBytes != payload.length || (payloadBytes != 0 && payloadBytes != PAYLOAD_SIZE)
                || sampleBytes != samples * 256 || bytes < sampleBytes || (bytes - sampleBytes) % 4096 != 0
                || (bytes - sampleBytes) / 4096 > 2) {
            throw new IllegalArgumentException("native_counts_inconsistent");
        }
        int completedArrays = samples + (bytes - sampleBytes) / 4096;
        if (arrays < completedArrays || arrays > completedArrays + 1
                || commands < arrays + statusChecks + idChecks || commands > arrays + statusChecks + idChecks + 1) {
            throw new IllegalArgumentException("native_counts_inconsistent");
        }
        if ((idChecks >= 1) == "unknown".equals(values.get("id1"))
                || (idChecks >= 2) == "unknown".equals(values.get("id2"))
                || (statusChecks >= 1) == "unknown".equals(values.get("sr_before"))
                || (statusChecks == 4) == "unknown".equals(values.get("sr_after"))) {
            throw new IllegalArgumentException("native_observation_inconsistent");
        }
        String restored = values.get("config_restored");
        if (("0".equals(restored) && incomplete != 1) || (!"0".equals(restored) && incomplete != 0)
                || ("not_changed".equals(restored) && commands != 0)) {
            throw new IllegalArgumentException("native_restore_inconsistent");
        }
        if (success) {
            require(values, "reason", "read_probe_complete");
            require(values, "id1", ID); require(values, "id2", ID);
            require(values, "sr_before", "00"); require(values, "sr_after", "00");
            require(values, "config_restored", "1"); require(values, "restoration_incomplete", "0");
            require(values, "flash_array_read", "sampled_addresses");
            if (commands != 18 || arrays != 10 || bytes != 10240 || statusChecks != 4 || idChecks != 4
                    || samples != 8 || sampleBytes != PAYLOAD_SIZE || payloadBytes != PAYLOAD_SIZE) {
                throw new IllegalArgumentException("native_success_inconsistent");
            }
            verifySamples(values, payload);
        } else {
            require(values, "payload_bytes", "0");
            require(values, "flash_array_read", arrays == 0 ? "not_performed" : "incomplete");
        }
        return values;
    }

    private static void verifySamples(Map<String, String> values, byte[] payload) {
        boolean repeat = true;
        for (int i = 0; i < 256; i++) repeat &= payload[i] == payload[7 * 256 + i];
        if (repeat != "1".equals(values.get("short_repeat_equal"))) {
            throw new IllegalArgumentException("native_short_repeat_inconsistent");
        }
        if ("1".equals(values.get("overlap_0_equal"))) {
            String head = values.get("long_head32_hex");
            for (int i = 0; i < 32; i++) {
                int value = Integer.parseInt(head.substring(i * 2, i * 2 + 2), 16);
                if ((payload[i] & 255) != value) throw new IllegalArgumentException("native_overlap_inconsistent");
            }
        }
    }

    private static int number(Map<String, String> values, String key, int maximum) {
        String value = values.get(key);
        if (!value.matches("0|[1-9][0-9]{0,8}")) throw new IllegalArgumentException("native_number_" + key);
        int result = Integer.parseInt(value);
        if (result > maximum) throw new IllegalArgumentException("native_number_" + key);
        return result;
    }

    private static void require(Map<String, String> values, String key, String expected) {
        if (!expected.equals(values.get(key))) throw new IllegalArgumentException("native_required_" + key);
    }
}
