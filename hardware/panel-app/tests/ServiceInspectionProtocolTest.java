package net.quber.afactorymode;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.Charset;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.concurrent.TimeUnit;

public final class ServiceInspectionProtocolTest {
    private static final String NONCE = "a1b2c3d4";
    private static final Charset UTF8 = Charset.forName("UTF-8");
    private static final String LEGACY_UID_VALUE = "\"a1b2c3d4:$("
            + "[ -r /proc/self/status ]||{ echo unreadable;exit;};n=0;"
            + "while read -r k a b c d e;do n=$((n+1));[ $n -le 256 ]||break;"
            + "[ \"$k\" = Uid: ]||continue;[ -z \"$e\" ]||break;"
            + "for v in \"$a\" \"$b\" \"$c\" \"$d\";do "
            + "case $v in (''|*[!0-9]*) echo invalid;exit;;esac;"
            + "[ ${#v} -le 10 ]||{ echo invalid;exit;};done;"
            + "printf '%s,%s,%s,%s' \"$a\" \"$b\" \"$c\" \"$d\";exit;"
            + "done </proc/self/status 2>/dev/null;echo invalid)\"";

    public static void main(String[] args) throws Exception {
        wireChecks();
        resultChecks();
        fixtureChecks();
        System.out.println("ServiceInspectionProtocolTest passed");
    }

    private static void wireChecks() {
        byte[] expectedClear = hex("1700000064656275672e686f6d6570616e656c2e696e7370656374000200000022220000");
        if (!Arrays.equals(expectedClear, ServiceInspectionProtocol.clear())) throw new AssertionError("clear_wire");
        for (ServiceInspectionProtocol.Query query : ServiceInspectionProtocol.Query.values()) {
            String value = requestValue(ServiceInspectionProtocol.query(query, NONCE));
            int size = ("setprop " + ServiceInspectionProtocol.KEY + " " + value).getBytes(UTF8).length + 1;
            if (size > 900) throw new AssertionError("service_buffer_bound");
            if (!value.startsWith("\"" + NONCE + ":$(") || !value.endsWith(")\"")) throw new AssertionError("nonce_bound");
        }
        String[] bad = {null, "", "a1b2c3d", "a1b2c3d40", "A1B2C3D4", "a1b2c3d;", "$(true)0", "a1b2c3d\n"};
        for (final String nonce : bad) {
            rejected(new Runnable() { public void run() { ServiceInspectionProtocol.query(ServiceInspectionProtocol.Query.UID, nonce); }});
        }
    }

    private static void resultChecks() {
        expect("0,0,0,0", parse(ServiceInspectionProtocol.Query.UID, "0,0,0,0"));
        expect("10025,10025,10025,10025", parse(ServiceInspectionProtocol.Query.UID, "10025,10025,10025,10025"));
        expect("0000000000000000", parse(ServiceInspectionProtocol.Query.CAPS, "0000000000000000"));
        expect("ok,adbd,/sbin/adbd", parse(ServiceInspectionProtocol.Query.INIT_MAIN, "ok,adbd,/sbin/adbd"));
        expect("ok,debugd,/system/bin/adbd", parse(ServiceInspectionProtocol.Query.INIT_MAIN, "ok,debugd,/system/bin/adbd"));
        expect("p11,absent,unreadable", parse(ServiceInspectionProtocol.Query.ADB_FILES, "p11,absent,unreadable"));
        for (String status : new String[]{"absent", "unreadable", "no_entry", "truncated", "invalid"}) {
            expect(status, parse(ServiceInspectionProtocol.Query.INIT_USB, status));
        }
        for (final String payload : new String[]{"0,0,0", "0,0,0,0,0", "-1,0,0,0", "4294967296,0,0,0", "0,0,0,0\n", "0,0,0,0;id"}) {
            rejected(new Runnable() { public void run() { parse(ServiceInspectionProtocol.Query.UID, payload); }});
        }
        for (final String payload : new String[]{"ok,other,/system/bin/sh", "ok,adbd,/../adbd", "ok,adbd,/system//bin/adbd", "ok,adbd,/sbin/adbd,--root", "ok,adbd,/sbin/adbd\n"}) {
            rejected(new Runnable() { public void run() { parse(ServiceInspectionProtocol.Query.INIT_BOARD, payload); }});
        }
        rejected(new Runnable() { public void run() { ServiceInspectionProtocol.parse(ServiceInspectionProtocol.Query.UID, NONCE, "00000000:0,0,0,0"); }});
        rejected(new Runnable() { public void run() { ServiceInspectionProtocol.parse(ServiceInspectionProtocol.Query.CAPS, NONCE, NONCE + ":" + repeat('0', 83)); }});
    }

    private static void fixtureChecks() throws Exception {
        Path directory = Files.createTempDirectory("panel-inspection-protocol-");
        try {
            write(directory.resolve("fixture-status"), "Name:\ttest\nUid:\t10025\t10025\t10025\t10025\nCapEff:\t0000000000000000\n");
            final String legacy = runValue(directory, ServiceInspectionProtocol.Query.UID, LEGACY_UID_VALUE, "");
            expect(NONCE + ":", legacy);
            rejected(new Runnable() { public void run() { ServiceInspectionProtocol.parse(ServiceInspectionProtocol.Query.UID, NONCE, legacy); }});
            expect("10025,10025,10025,10025", run(directory, ServiceInspectionProtocol.Query.UID, ""));
            expect("0000000000000000", run(directory, ServiceInspectionProtocol.Query.CAPS, ""));
            write(directory.resolve("fixture-status"), "Uid:\t0\t0\t0\t0 extra\n");
            expect("invalid", run(directory, ServiceInspectionProtocol.Query.UID, ""));
            write(directory.resolve("fixture-init"), "# service adbd /bad\nservice unrelated /system/bin/other secret\nservice adbd /sbin/adbd --private-value\n    user root\n");
            expect("ok,adbd,/sbin/adbd", run(directory, ServiceInspectionProtocol.Query.INIT_MAIN, ""));
            write(directory.resolve("fixture-init"), "service debugd /system/bin/adbd");
            expect("ok,debugd,/system/bin/adbd", run(directory, ServiceInspectionProtocol.Query.INIT_BOARD, ""));
            write(directory.resolve("fixture-init"), "service adbd /sbin/adbd $(touch SHOULD_NOT_EXIST)\n");
            expect("ok,adbd,/sbin/adbd", run(directory, ServiceInspectionProtocol.Query.INIT_USB, ""));
            if (Files.exists(directory.resolve("SHOULD_NOT_EXIST"))) throw new AssertionError("fixture_executed");
            write(directory.resolve("fixture-init"), "service adbd $(touch SHOULD_NOT_EXIST)\n");
            expect("invalid", run(directory, ServiceInspectionProtocol.Query.INIT_USB, ""));
            if (Files.exists(directory.resolve("SHOULD_NOT_EXIST"))) throw new AssertionError("fixture_executed");
            write(directory.resolve("fixture-init"), "# -n \\c\\nservice adbd /wrong\\n\nservice adbd /sbin/adbd\n");
            expect("ok,adbd,/sbin/adbd", run(directory, ServiceInspectionProtocol.Query.INIT_MAIN, ""));
            write(directory.resolve("fixture-init"), repeat('\n', 1024));
            expect("no_entry", run(directory, ServiceInspectionProtocol.Query.INIT_MAIN, ""));
            write(directory.resolve("fixture-init"), repeat('\n', 1025));
            expect("truncated", run(directory, ServiceInspectionProtocol.Query.INIT_MAIN, ""));
            write(directory.resolve("fixture-init"), repeat('\n', 65536) + "service adbd /sbin/adbd\n");
            expect("truncated", run(directory, ServiceInspectionProtocol.Query.INIT_MAIN, ""));
            write(directory.resolve("fixture-init"), "service adbd /sbin/adbd\n");
            expect("unreadable", run(directory, ServiceInspectionProtocol.Query.INIT_MAIN, "dd(){ print -rn -- partial;return 1;};"));
            Files.delete(directory.resolve("fixture-init"));
            expect("absent", run(directory, ServiceInspectionProtocol.Query.INIT_MAIN, ""));
            Files.createDirectories(directory.resolve("sbin"));
            Files.createDirectories(directory.resolve("system/bin"));
            Path executable = directory.resolve("sbin/adbd");
            write(executable, "fixture only\n");
            if (!executable.toFile().setExecutable(true, true)) throw new AssertionError("fixture_permissions");
            expect("p11,absent,unreadable", run(directory, ServiceInspectionProtocol.Query.ADB_FILES, ""));
        } finally {
            deleteTree(directory);
        }
    }

    private static String run(Path directory, ServiceInspectionProtocol.Query query, String prefix) throws Exception {
        String value = requestValue(ServiceInspectionProtocol.query(query, NONCE));
        return ServiceInspectionProtocol.parse(query, NONCE, runValue(directory, query, value, prefix));
    }

    private static String runValue(Path directory, ServiceInspectionProtocol.Query query, String value, String prefix) throws Exception {
        value = value.replace("/proc/self/status", "./fixture-status");
        value = value.replace("p=/init.bigfish.rc;", "p=./fixture-init;")
                .replace("p=/init.usb.rc;", "p=./fixture-init;").replace("p=/init.rc;", "p=./fixture-init;");
        if (query == ServiceInspectionProtocol.Query.ADB_FILES) {
            value = value.replace("/sbin/adbd", "./sbin/adbd").replace("/system/bin/adbd", "./system/bin/adbd")
                    .replace("/system/xbin/adbd", "./system/xbin/adbd");
        }
        ProcessBuilder builder = new ProcessBuilder("/bin/ksh", "-c", "printf(){ return 127; };" + prefix + "print -rn -- " + value);
        builder.directory(directory.toFile());
        builder.environment().put("LC_ALL", "C");
        Process process = builder.start();
        if (!process.waitFor(5, TimeUnit.SECONDS)) {
            process.destroyForcibly();
            throw new AssertionError("fixture_timeout");
        }
        String output = read(process.getInputStream());
        String error = read(process.getErrorStream());
        if (process.exitValue() != 0 || !error.isEmpty()) throw new AssertionError("fixture_shell_failed " + query + ": " + error);
        return output;
    }

    private static String requestValue(byte[] bytes) {
        ByteBuffer input = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);
        expect(ServiceInspectionProtocol.KEY, string8(input));
        String value = string8(input);
        if (input.hasRemaining()) throw new AssertionError("trailing_wire_bytes");
        return value;
    }

    private static String string8(ByteBuffer input) {
        int count = input.getInt();
        byte[] data = new byte[count];
        input.get(data);
        if (count > 0) {
            if (input.get() != 0) throw new AssertionError("missing_nul");
            while ((input.position() & 3) != 0) if (input.get() != 0) throw new AssertionError("bad_padding");
        }
        return new String(data, UTF8);
    }

    private static String parse(ServiceInspectionProtocol.Query query, String value) {
        return ServiceInspectionProtocol.parse(query, NONCE, NONCE + ":" + value);
    }

    private static void write(Path path, String content) throws Exception {
        Files.write(path, content.getBytes(UTF8));
    }

    private static String read(InputStream input) throws Exception {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        byte[] buffer = new byte[256];
        int size;
        while ((size = input.read(buffer)) != -1) {
            if (output.size() + size > 2048) throw new AssertionError("unbounded_fixture_output");
            output.write(buffer, 0, size);
        }
        return new String(output.toByteArray(), UTF8);
    }

    private static String repeat(char value, int count) {
        char[] data = new char[count];
        Arrays.fill(data, value);
        return new String(data);
    }

    private static byte[] hex(String value) {
        byte[] bytes = new byte[value.length() / 2];
        for (int i = 0; i < bytes.length; i++) bytes[i] = (byte) Integer.parseInt(value.substring(i * 2, i * 2 + 2), 16);
        return bytes;
    }

    private static void deleteTree(Path path) throws Exception {
        if (Files.isDirectory(path)) {
            java.nio.file.DirectoryStream<Path> children = Files.newDirectoryStream(path);
            try { for (Path child : children) deleteTree(child); } finally { children.close(); }
        }
        Files.deleteIfExists(path);
    }

    private static void rejected(Runnable action) {
        try { action.run(); } catch (IllegalArgumentException expected) { return; }
        throw new AssertionError("invalid_value_accepted");
    }

    private static void expect(String expected, String actual) {
        if (!expected.equals(actual)) throw new AssertionError("expected " + expected + " got " + actual);
    }
}
