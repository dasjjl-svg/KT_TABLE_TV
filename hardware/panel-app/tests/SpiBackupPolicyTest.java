package net.quber.afactorymode;

import java.util.Arrays;
import java.util.Map;

public final class SpiBackupPolicyTest {
    private static int checks;
    private interface Action { void run(); }
    private static void check(boolean value) { checks++; if (!value) throw new AssertionError(); }
    private static void reject(Action action) {
        checks++;
        try { action.run(); } catch (IllegalArgumentException expected) { return; }
        throw new AssertionError("accepted invalid backup evidence");
    }
    private static String good() {
        return "version=2\nstatus=ok\nreason=array_read_verified\nid1=c29e16c29e16c29e\nid2=c29e16c29e16c29e\n"
                + "sr_before=00\nsr_after=00\ncommands_started=32809\nconfig_restored=1\nrestoration_incomplete=0\n"
                + "flash_array_read=verified_two_pass\nflash_program_erase=not_performed\nownership=not_established\n"
                + "interrupt_buffer_preserved=not_guaranteed\nop_flags_left=not_restored\nflash_capacity=4194304\n"
                + "payload_bytes=4194304\nprefix_verified=1\npasses_completed=2\narray_commands=32770\n"
                + "array_bytes_read=8389120\nstatus_checks=35\nid_checks=4\naddress_diversity=1\nread_chunk_bytes=256\n";
    }
    private static String set(String text, String key, String value) {
        return text.replaceAll("(?m)^" + key + "=[^\n]*", key + "=" + value);
    }
    private static String preflightError() {
        String text = good();
        for (String key : new String[]{"commands_started", "restoration_incomplete", "payload_bytes", "prefix_verified",
                "passes_completed", "array_commands", "array_bytes_read", "status_checks", "id_checks", "address_diversity"}) {
            text = set(text, key, "0");
        }
        for (String key : new String[]{"id1", "id2", "sr_before", "sr_after"}) text = set(text, key, "unknown");
        text = set(text, "status", "error"); text = set(text, "reason", "preflight_mismatch");
        text = set(text, "config_restored", "not_changed");
        return set(text, "flash_array_read", "not_performed");
    }
    public static void main(String[] args) {
        final byte[] payload = new byte[4194304];
        payload[0] = 0x55; payload[256] = (byte)0xaa;
        Map<String, String> parsed = SpiBackupPolicy.nativeReport(good(), payload);
        check("ok".equals(parsed.get("status")) && parsed.size() == 25);
        for (final String replacement : new String[]{"commands_started=32808", "array_commands=32769", "array_bytes_read=8388864",
                "status_checks=34", "id_checks=3", "prefix_verified=0", "passes_completed=1", "address_diversity=0",
                "config_restored=0", "restoration_incomplete=1", "id2=c29e15c29e15c29e", "sr_after=01", "sr_before=unknown",
                "flash_capacity=2097152", "payload_bytes=0", "flash_array_read=incomplete", "version=1", "read_chunk_bytes=4096",
                "flash_program_erase=performed", "ownership=established", "op_flags_left=restored",
                "interrupt_buffer_preserved=guaranteed", "reason=identity_read"}) {
            final int split = replacement.indexOf('=');
            reject(new Action() { public void run() {
                SpiBackupPolicy.nativeReport(set(good(), replacement.substring(0, split), replacement.substring(split + 1)), payload);
            }});
        }
        for (final String line : good().split("\n")) {
            reject(new Action() { public void run() { SpiBackupPolicy.nativeReport(good().replace(line + "\n", ""), payload); }});
            reject(new Action() { public void run() { SpiBackupPolicy.nativeReport(good() + line + "\n", payload); }});
        }
        for (final String key : new String[]{"commands_started", "array_commands", "array_bytes_read", "status_checks", "id_checks",
                "prefix_verified", "passes_completed", "address_diversity", "restoration_incomplete", "payload_bytes", "read_chunk_bytes"}) {
            for (final String value : new String[]{"-1", "+1", "01", " 1", "1 ", "0x1", "999999999999999999", "2147483648"}) {
                reject(new Action() { public void run() { SpiBackupPolicy.nativeReport(set(good(), key, value), payload); }});
            }
        }
        for (final String tail : new String[]{"\n", "unknown=1\n", "bad\n", "x=\u0001\n", "x=a=b\n"}) {
            reject(new Action() { public void run() { SpiBackupPolicy.nativeReport(good() + tail, payload); }});
        }
        reject(new Action() { public void run() { SpiBackupPolicy.nativeReport(null, payload); }});
        reject(new Action() { public void run() { SpiBackupPolicy.nativeReport(good(), null); }});
        reject(new Action() { public void run() { SpiBackupPolicy.nativeReport(new String(new char[1501]).replace('\0', 'x'), payload); }});
        reject(new Action() { public void run() { SpiBackupPolicy.nativeReport(good(), new byte[0]); }});
        reject(new Action() { public void run() { SpiBackupPolicy.nativeReport(good(), new byte[4194303]); }});
        reject(new Action() { public void run() { SpiBackupPolicy.nativeReport(good(), new byte[4194305]); }});
        check("preflight_mismatch".equals(SpiBackupPolicy.nativeReport(preflightError(), new byte[0]).get("reason")));
        final String completedError = set(set(set(set(good(), "status", "error"), "reason", "cleanup_failed"),
                "payload_bytes", "0"), "flash_array_read", "incomplete");
        check("error".equals(SpiBackupPolicy.nativeReport(completedError, new byte[0]).get("status")));
        reject(new Action() { public void run() { SpiBackupPolicy.nativeReport(completedError, payload); }});
        reject(new Action() { public void run() { SpiBackupPolicy.nativeReport(set(completedError, "payload_bytes", "4194304"), payload); }});
        String busy = set(set(set(set(set(preflightError(), "reason", "flash_status_busy"), "commands_started", "1"),
                "status_checks", "1"), "sr_before", "02"), "config_restored", "1");
        check("error".equals(SpiBackupPolicy.nativeReport(busy, new byte[0]).get("status")));
        final String timeout = set(set(set(set(preflightError(), "reason", "timeout"), "commands_started", "1"),
                "config_restored", "0"), "restoration_incomplete", "1");
        check("error".equals(SpiBackupPolicy.nativeReport(timeout, new byte[0]).get("status")));
        reject(new Action() { public void run() { SpiBackupPolicy.nativeReport(set(timeout, "restoration_incomplete", "0"), new byte[0]); }});
        reject(new Action() { public void run() { SpiBackupPolicy.nativeReport(set(preflightError(), "array_commands", "1"), new byte[0]); }});
        reject(new Action() { public void run() { SpiBackupPolicy.nativeReport(set(preflightError(), "status_checks", "1"), new byte[0]); }});
        reject(new Action() { public void run() { SpiBackupPolicy.nativeReport(set(preflightError(), "id1", "c29e16c29e16c29e"), new byte[0]); }});

        String partialPass = completedError;
        for (String[] change : new String[][]{{"commands_started", "16406"}, {"array_commands", "16386"},
                {"array_bytes_read", "4194816"}, {"status_checks", "18"}, {"id_checks", "2"},
                {"sr_after", "unknown"}, {"passes_completed", "1"}}) partialPass = set(partialPass, change[0], change[1]);
        final String onePass = partialPass;
        check("error".equals(SpiBackupPolicy.nativeReport(onePass, new byte[0]).get("status")));
        final String inFlight = set(set(set(set(onePass, "commands_started", "16407"), "array_commands", "16387"),
                "config_restored", "0"), "restoration_incomplete", "1");
        check("1".equals(SpiBackupPolicy.nativeReport(inFlight, new byte[0]).get("restoration_incomplete")));
        reject(new Action() { public void run() { SpiBackupPolicy.nativeReport(set(inFlight, "array_bytes_read", "4194817"), new byte[0]); }});
        reject(new Action() { public void run() { SpiBackupPolicy.nativeReport(set(inFlight, "array_commands", "16388"), new byte[0]); }});
        reject(new Action() { public void run() { SpiBackupPolicy.nativeReport(set(onePass, "passes_completed", "2"), new byte[0]); }});
        final String prematurePass = set(set(set(onePass, "commands_started", "16405"), "array_commands", "16385"),
                "array_bytes_read", "4194560");
        reject(new Action() { public void run() { SpiBackupPolicy.nativeReport(prematurePass, new byte[0]); }});
        for (final String replacement : new String[]{"commands_started=32810", "array_commands=32771", "array_bytes_read=8389376"}) {
            final int split = replacement.indexOf('=');
            reject(new Action() { public void run() { SpiBackupPolicy.nativeReport(
                    set(completedError, replacement.substring(0, split), replacement.substring(split + 1)), new byte[0]); }});
        }

        Arrays.fill(payload, (byte)0);
        reject(new Action() { public void run() { SpiBackupPolicy.nativeReport(good(), payload); }});
        payload[256] = 0x55;
        reject(new Action() { public void run() { SpiBackupPolicy.nativeReport(good(), payload); }});
        Arrays.fill(payload, (byte)0xff);
        reject(new Action() { public void run() { SpiBackupPolicy.nativeReport(good(), payload); }});
        payload[256] = 0x55;
        reject(new Action() { public void run() { SpiBackupPolicy.nativeReport(good(), payload); }});
        payload[0] = 0x55;
        check("ok".equals(SpiBackupPolicy.nativeReport(good(), payload).get("status")));
        Arrays.fill(payload, (byte)0x55);
        reject(new Action() { public void run() { SpiBackupPolicy.nativeReport(good(), payload); }});
        for (int i = 0; i < payload.length; i++) payload[i] = (byte)(i % 256);
        reject(new Action() { public void run() { SpiBackupPolicy.nativeReport(good(), payload); }});
        payload[256] ^= 1;
        check("ok".equals(SpiBackupPolicy.nativeReport(good(), payload).get("status")));
        final byte[] id = {(byte)0xc2, (byte)0x9e, 0x16, (byte)0xc2, (byte)0x9e, 0x16, (byte)0xc2, (byte)0x9e};
        for (int period : new int[]{3, 8}) {
            for (int i = 0; i < 256; i++) payload[i] = id[i % period];
            reject(new Action() { public void run() { SpiBackupPolicy.nativeReport(good(), payload); }});
        }
        System.out.println("SpiBackupPolicyTest passed: " + checks + " checks");
    }
}
