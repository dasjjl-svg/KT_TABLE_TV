package net.quber.afactorymode;

import java.nio.charset.Charset;
import java.util.LinkedHashMap;
import java.util.Map;

final class SpiIdentityPolicy {
    private static final Charset UTF8 = Charset.forName("UTF-8");
    private static final String[] REQUIRED = {"version", "status", "reason", "id1", "id2",
            "sr_before", "sr_after", "commands_started", "config_restored", "restoration_incomplete",
            "flash_array_read", "flash_program_erase", "ownership"};

    static Map<String, String> fields(String text, int maximum) {
        if (text == null || text.length() == 0 || text.getBytes(UTF8).length > maximum) {
            throw new IllegalArgumentException("output_size");
        }
        Map<String, String> result = new LinkedHashMap<String, String>();
        for (String line : text.split("\n")) {
            int split = line.indexOf('=');
            if (split < 1 || split != line.lastIndexOf('=')) throw new IllegalArgumentException("output_line");
            String key = line.substring(0, split), value = line.substring(split + 1);
            if (!key.matches("[a-z][a-z0-9_]{0,59}") || !value.matches("[ -~]{1,160}")
                    || result.put(key, value) != null) throw new IllegalArgumentException("output_field");
        }
        return result;
    }

    static void requirePreflight(String text, String evidence) {
        Map<String, String> values = fields(text, 2048);
        require(values, "version", "2");
        require(values, "snapshot_count", "2");
        require(values, "registers_unchanged", "1");
        require(values, "clock_unchanged", "1");
        require(values, "crg224_before", "0x00000081");
        require(values, "crg224_after", "0x00000081");
        for (String prefix : new String[]{"fmc_", "fmc2_"}) {
            require(values, prefix + "version", "0x00000100");
            require(values, prefix + "cfg", "0x00000021");
            require(values, prefix + "global_cfg", "0x000000c4");
            require(values, prefix + "timing_spi_cfg", "0x0000006f");
            require(values, prefix + "interrupt", "0x00000001");
            require(values, prefix + "interrupt_enable", "0x00000000");
            require(values, prefix + "op_start_raw", "0");
            require(values, prefix + "op_ctrl_ready_raw", "0");
            require(values, prefix + "cs_raw", "0");
            require(values, prefix + "force_cs_raw", "0");
        }
        Map<String, String> detail = fields(evidence, 2048);
        require(detail, "caller_uid", "0");
        require(detail, "platform_driver_before", "not_bound");
        require(detail, "platform_driver_after", "not_bound");
        require(detail, "fmc_irq_line_matches_before", "0");
        require(detail, "fmc_irq_line_matches_after", "0");
    }

    static Map<String, String> nativeReport(String text) {
        Map<String, String> values = fields(text, 1500);
        for (String key : REQUIRED) if (!values.containsKey(key)) throw new IllegalArgumentException("native_missing_field");
        for (String key : values.keySet()) {
            boolean allowed = false;
            for (String required : REQUIRED) if (required.equals(key)) allowed = true;
            if (!allowed && !key.matches("op_flags_left|interrupt_buffer_preserved|stage|errno")) {
                throw new IllegalArgumentException("native_unknown_field");
            }
        }
        require(values, "version", "1");
        require(values, "flash_array_read", "not_performed");
        require(values, "flash_program_erase", "not_performed");
        require(values, "ownership", "not_established");
        if (!values.get("status").matches("ok|error")
                || !values.get("reason").matches("[a-z][a-z0-9_]{0,79}")
                || !values.get("commands_started").matches("[0-4]")
                || !values.get("config_restored").matches("0|1|not_changed")
                || !values.get("restoration_incomplete").matches("0|1")) {
            throw new IllegalArgumentException("native_field_value");
        }
        for (String key : new String[]{"id1", "id2"}) {
            if (!values.get(key).matches("unknown|[0-9a-f]{16}")) throw new IllegalArgumentException("native_id_value");
        }
        for (String key : new String[]{"sr_before", "sr_after"}) {
            if (!values.get(key).matches("unknown|[0-9a-f]{2}")) throw new IllegalArgumentException("native_status_value");
        }
        if ("ok".equals(values.get("status"))) {
            require(values, "commands_started", "4");
            require(values, "config_restored", "1");
            require(values, "restoration_incomplete", "0");
            String id = values.get("id1"), sr = values.get("sr_before");
            if (id.equals("unknown") || id.startsWith("00") || id.startsWith("ff") || id.startsWith("7f")
                    || !id.equals(values.get("id2")) || sr.equals("unknown")
                    || !sr.equals(values.get("sr_after")) || (Integer.parseInt(sr, 16) & 3) != 0) {
                throw new IllegalArgumentException("native_success_inconsistent");
            }
        }
        return values;
    }

    private static void require(Map<String, String> values, String key, String expected) {
        if (!expected.equals(values.get(key))) throw new IllegalArgumentException("required_value_" + key);
    }
}
