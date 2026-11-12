package net.quber.afactorymode;

import java.util.Map;

final class SpiBackupPolicy {
    private static final int CAPACITY = 4194304;
    private static final int READ_CHUNK = 256;
    private static final String ID = "c29e16c29e16c29e";
    private static final String[] KEYS = {"version", "status", "reason", "id1", "id2", "sr_before", "sr_after",
            "commands_started", "config_restored", "restoration_incomplete", "flash_array_read",
            "flash_program_erase", "ownership", "interrupt_buffer_preserved", "op_flags_left", "flash_capacity",
            "payload_bytes", "prefix_verified", "passes_completed", "array_commands", "array_bytes_read",
            "status_checks", "id_checks", "address_diversity", "read_chunk_bytes"};

    static Map<String, String> nativeReport(String text, byte[] payload) {
        if (payload == null || text == null || text.endsWith("\n\n")) throw new IllegalArgumentException("native_null_or_blank");
        Map<String, String> values = SpiIdentityPolicy.fields(text, 1500);
        if (values.size() != KEYS.length) throw new IllegalArgumentException("native_field_count");
        for (String key : KEYS) if (!values.containsKey(key)) throw new IllegalArgumentException("native_missing_field");
        require(values, "version", "2");
        require(values, "read_chunk_bytes", "256");
        require(values, "flash_capacity", "4194304");
        require(values, "flash_program_erase", "not_performed");
        require(values, "ownership", "not_established");
        require(values, "interrupt_buffer_preserved", "not_guaranteed");
        require(values, "op_flags_left", "not_restored");
        if (!values.get("status").matches("ok|error")
                || !values.get("reason").matches("[a-z][a-z0-9_]{0,79}")
                || !values.get("config_restored").matches("0|1|not_changed")
                || !values.get("flash_array_read").matches("not_performed|incomplete|verified_two_pass")) {
            throw new IllegalArgumentException("native_field_value");
        }
        for (String key : new String[]{"id1", "id2"}) {
            if (!values.get(key).matches("unknown|[0-9a-f]{16}")) throw new IllegalArgumentException("native_id_value");
        }
        for (String key : new String[]{"sr_before", "sr_after"}) {
            if (!values.get(key).matches("unknown|[0-9a-f]{2}")) throw new IllegalArgumentException("native_sr_value");
        }
        int commands = number(values, "commands_started", 32809);
        int arrays = number(values, "array_commands", 32770);
        int bytes = number(values, "array_bytes_read", 8389120);
        int statusChecks = number(values, "status_checks", 35);
        int idChecks = number(values, "id_checks", 4);
        int prefix = number(values, "prefix_verified", 1);
        int passes = number(values, "passes_completed", 2);
        int diversity = number(values, "address_diversity", 1);
        int incomplete = number(values, "restoration_incomplete", 1);
        int payloadBytes = number(values, "payload_bytes", CAPACITY);
        int completedArrays = bytes / READ_CHUNK;
        if ((payloadBytes != 0 && payloadBytes != CAPACITY) || payloadBytes != payload.length
                || bytes % READ_CHUNK != 0 || completedArrays > arrays || arrays - completedArrays > 1
                || commands < arrays + statusChecks + idChecks || commands > arrays + statusChecks + idChecks + 1
                || (prefix == 1 && completedArrays < 2) || (completedArrays > 2 && prefix != 1)
                || (diversity == 1 && (prefix != 1 || completedArrays < 4))
                || (passes >= 1 && completedArrays < 16386) || (passes == 2 && completedArrays != 32770)
                || (passes > 0 && prefix != 1)) throw new IllegalArgumentException("native_counts_inconsistent");
        if ((idChecks >= 1) == "unknown".equals(values.get("id1"))
                || (idChecks >= 2) == "unknown".equals(values.get("id2"))
                || (statusChecks >= 1) == "unknown".equals(values.get("sr_before"))
                || (statusChecks == 35) == "unknown".equals(values.get("sr_after"))) {
            throw new IllegalArgumentException("native_observation_inconsistent");
        }
        String restored = values.get("config_restored");
        if (("0".equals(restored) && incomplete != 1)
                || (!"0".equals(restored) && incomplete != 0)
                || ("not_changed".equals(restored) && commands != 0)) {
            throw new IllegalArgumentException("native_restore_inconsistent");
        }
        if ("ok".equals(values.get("status"))) {
            require(values, "reason", "array_read_verified");
            require(values, "id1", ID); require(values, "id2", ID);
            require(values, "sr_before", "00"); require(values, "sr_after", "00");
            require(values, "config_restored", "1"); require(values, "restoration_incomplete", "0");
            require(values, "flash_array_read", "verified_two_pass");
            if (commands != 32809 || arrays != 32770 || bytes != 8389120 || statusChecks != 35 || idChecks != 4
                    || prefix != 1 || passes != 2 || diversity != 1 || payloadBytes != CAPACITY) {
                throw new IllegalArgumentException("native_success_inconsistent");
            }
            verifyPayload(payload);
        } else {
            require(values, "payload_bytes", "0");
            require(values, "flash_array_read", arrays == 0 ? "not_performed" : "incomplete");
        }
        return values;
    }

    private static int number(Map<String, String> values, String key, int maximum) {
        String value = values.get(key);
        if (!value.matches("0|[1-9][0-9]{0,8}")) throw new IllegalArgumentException("native_number_" + key);
        int number = Integer.parseInt(value);
        if (number > maximum) throw new IllegalArgumentException("native_number_" + key);
        return number;
    }

    private static void verifyPayload(byte[] payload) {
        byte[] id = {(byte)0xc2, (byte)0x9e, 0x16, (byte)0xc2, (byte)0x9e, 0x16, (byte)0xc2, (byte)0x9e};
        boolean zero = true, erased = true, id3 = true, id8 = true;
        for (int i = 0; i < READ_CHUNK; i++) {
            zero &= payload[i] == 0;
            erased &= payload[i] == (byte)0xff;
            id3 &= payload[i] == id[i % 3];
            id8 &= payload[i] == id[i % 8];
        }
        if (zero || erased || id3 || id8) throw new IllegalArgumentException("native_suspicious_prefix");
        for (int i = READ_CHUNK; i < payload.length; i++) {
            if (payload[i] != payload[i % READ_CHUNK]) return;
        }
        throw new IllegalArgumentException("native_suspicious_repetition");
    }

    private static void require(Map<String, String> values, String key, String expected) {
        if (!expected.equals(values.get(key))) throw new IllegalArgumentException("native_required_" + key);
    }
}
