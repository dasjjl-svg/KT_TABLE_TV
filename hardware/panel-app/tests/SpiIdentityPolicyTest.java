package net.quber.afactorymode;

public final class SpiIdentityPolicyTest {
    private static int checks;
    private interface Action { void run(); }
    private static void check(boolean value) { checks++; if (!value) throw new AssertionError(); }
    private static void reject(Action action) {
        checks++;
        try { action.run(); } catch (IllegalArgumentException expected) { return; }
        throw new AssertionError("accepted invalid identity evidence");
    }
    private static String good() {
        return "version=1\nstatus=ok\nreason=identity_sampled\nid1=ef40150000000000\nid2=ef40150000000000\n"
                + "sr_before=1c\nsr_after=1c\ncommands_started=4\nconfig_restored=1\nrestoration_incomplete=0\n"
                + "flash_array_read=not_performed\nflash_program_erase=not_performed\nownership=not_established\n";
    }
    private static String preflight() {
        String text = "version=2\nsnapshot_count=2\nregisters_unchanged=1\nclock_unchanged=1\n"
                + "crg224_before=0x00000081\ncrg224_after=0x00000081\n";
        for (String p : new String[]{"fmc_", "fmc2_"}) {
            text += p + "version=0x00000100\n" + p + "cfg=0x00000021\n" + p + "global_cfg=0x000000c4\n"
                    + p + "timing_spi_cfg=0x0000006f\n" + p + "interrupt=0x00000001\n"
                    + p + "interrupt_enable=0x00000000\n" + p + "op_start_raw=0\n" + p + "op_ctrl_ready_raw=0\n"
                    + p + "cs_raw=0\n" + p + "force_cs_raw=0\n";
        }
        return text;
    }
    private static String evidence() {
        return "caller_uid=0\nplatform_driver_before=not_bound\nplatform_driver_after=not_bound\n"
                + "fmc_irq_line_matches_before=0\nfmc_irq_line_matches_after=0\n";
    }
    public static void main(String[] args) {
        check("ok".equals(SpiIdentityPolicy.nativeReport(good()).get("status")));
        for (final String replacement : new String[]{"commands_started=3", "config_restored=0", "restoration_incomplete=1",
                "id2=c840150000000000", "sr_after=00", "sr_before=unknown", "version=2"}) {
            final String key = replacement.substring(0, replacement.indexOf('='));
            reject(new Action() { public void run() { SpiIdentityPolicy.nativeReport(good().replaceAll(key + "=[^\\n]*", replacement)); }});
        }
        for (final String status : new String[]{"01", "02", "03", "ff"}) {
            reject(new Action() { public void run() { SpiIdentityPolicy.nativeReport(good().replace("=1c", "=" + status)); }});
        }
        for (final String id : new String[]{"0000000000000000", "ffffffffffffffff", "0040150000000000", "ff40150000000000", "7f40150000000000", "unknown"}) {
            reject(new Action() { public void run() { SpiIdentityPolicy.nativeReport(good().replace("ef40150000000000", id)); }});
        }
        for (final String tail : new String[]{"status=error\n", "extra=1\n", "bad\n", "x=\u0001\n", "x=a=b\n"}) {
            reject(new Action() { public void run() { SpiIdentityPolicy.nativeReport(good() + tail); }});
        }
        reject(new Action() { public void run() { SpiIdentityPolicy.nativeReport(good().replace("sr_before=1c\n", "")); }});
        reject(new Action() { public void run() { SpiIdentityPolicy.nativeReport(new String(new char[1501]).replace('\0', 'x')); }});
        String failed = good().replace("status=ok", "status=error").replace("reason=identity_sampled", "reason=operation_timeout")
                .replace("config_restored=1", "config_restored=0").replace("restoration_incomplete=0", "restoration_incomplete=1")
                .replace("commands_started=4", "commands_started=1").replace("ef40150000000000", "unknown").replace("=1c", "=unknown");
        check("operation_timeout".equals(SpiIdentityPolicy.nativeReport(failed).get("reason")));
        SpiIdentityPolicy.requirePreflight(preflight(), evidence()); checks++;
        for (final String field : new String[]{"registers_unchanged=1", "clock_unchanged=1", "snapshot_count=2",
                "crg224_before=0x00000081", "fmc_cfg=0x00000021", "fmc2_cfg=0x00000021",
                "fmc_interrupt_enable=0x00000000", "fmc2_interrupt_enable=0x00000000", "fmc_op_start_raw=0",
                "fmc2_op_ctrl_ready_raw=0", "fmc_cs_raw=0", "fmc2_force_cs_raw=0"}) {
            reject(new Action() { public void run() { SpiIdentityPolicy.requirePreflight(preflight().replace(field, field + "0"), evidence()); }});
        }
        for (final String field : new String[]{"caller_uid=0", "platform_driver_before=not_bound",
                "platform_driver_after=not_bound", "fmc_irq_line_matches_before=0", "fmc_irq_line_matches_after=0"}) {
            reject(new Action() { public void run() { SpiIdentityPolicy.requirePreflight(preflight(), evidence().replace(field, field + "0")); }});
        }
        System.out.println("SpiIdentityPolicyTest passed: " + checks + " checks");
    }
}
