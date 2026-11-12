package net.quber.afactorymode;

public final class DeviceInitParserTest {
    public static void main(String[] args) {
        DeviceInitParser.Result mixed = DeviceInitParser.parse(
                "import /init.environ.rc\nimport /init.${ro.hardware}.rc\n"
                + "service unrelated /system/bin/other --password DO_NOT_REPORT\n user system\n group system\n"
                + "service forker /sbin/fork.bin --secret DO_NOT_REPORT\n user root\n group root system inet\n"
                + " setenv SECRET DO_NOT_REPORT\n socket private stream 660 root system\n"
                + "on boot\n user DO_NOT_REPORT\nservice next /system/bin/notfork.bin\n group DO_NOT_REPORT\n", 10);
        String report = join(mixed);
        require(mixed.imports == 2, "valid imports were lost");
        require(report.contains("service=forker executable=/sbin/fork.bin"), "fork service missing");
        require(report.contains("service.forker.user=root"), "fork user missing");
        require(report.contains("service.forker.group=root system inet"), "fork groups missing");
        require(!report.contains("DO_NOT_REPORT") && !report.contains("unrelated") && !report.contains("--secret"), "unrelated values leaked");

        DeviceInitParser.Result falseMatches = DeviceInitParser.parse(
                "service a /sbin/fork.bin.backup\n user root\n"
                + "service b /system/bin/sh /sbin/fork.bin\n group root\n"
                + "service c /sbin/../private/fork.bin\n user root\n"
                + "import /init.rc;bad\nimport /../private.rc\nimport /init.rc extra\n", 10);
        require(falseMatches.lines.isEmpty(), "unsafe paths or argument matches accepted");

        DeviceInitParser.Result bounded = DeviceInitParser.parse(
                "import /a.rc\nimport /b.rc\nservice f /custom/bin/fork.bin\n user system # trailing comment\n"
                + " group system inet\nimport /c.rc\n group DO_NOT_REPORT\n", 1);
        require(bounded.imports == 1, "global import budget exceeded");
        require(join(bounded).contains("service.f.user=system"), "comment parsing failed");
        require(!join(bounded).contains("DO_NOT_REPORT"), "import did not end the service section");

        DeviceInitParser.Result zero = DeviceInitParser.parse("import /a.rc\nservice f /sbin/fork.bin\n user 1000\n", 0);
        require(zero.imports == 0 && join(zero).contains("service.f.user=1000"), "zero import budget suppressed service evidence");

        StringBuilder large = new StringBuilder();
        for (int i = 0; i < 100; i++) large.append("import /i").append(i).append(".rc\n");
        DeviceInitParser.Result capped = DeviceInitParser.parse(large.toString(), 1000);
        require(capped.imports == 10, "hard import cap missing");
        require(DeviceInitParser.parse("service f /sbin/fork.bin\n user root\u0000secret\n group root;bad\n", 10).lines.size() == 1,
                "control characters or shell punctuation leaked");
        System.out.println("Init parser verified: target-only evidence, secret omission, section boundaries, safe paths and bounds.");
    }

    private static String join(DeviceInitParser.Result value) { return value.lines.toString(); }
    private static void require(boolean value, String message) {
        if (!value) throw new AssertionError(message);
    }
}
