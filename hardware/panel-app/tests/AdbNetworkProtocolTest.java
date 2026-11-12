package net.quber.afactorymode;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.Charset;
import java.util.Arrays;

public final class AdbNetworkProtocolTest {
    public static void main(String[] args) {
        expectHex(AdbNetworkProtocol.startDaemon(), "0900000063746c2e7374617274000000040000006164626400000000");
        expectHex(AdbNetworkProtocol.setPort("5555"), "14000000736572766963652e6164622e7463702e706f727400000000040000003535353500000000");
        String[] allowed = {"", "-1", "0", "5555"};
        for (String port : allowed) {
            ByteBuffer input = ByteBuffer.wrap(AdbNetworkProtocol.setPort(port)).order(ByteOrder.LITTLE_ENDIAN);
            expect("service.adb.tcp.port", readNativeString8(input));
            expect(port.length() == 0 ? "\"\"" : port, readNativeString8(input));
            if (input.hasRemaining()) throw new AssertionError("trailing_payload");
        }
        String[] rejected = {null, "5556", "05555", "-2", "65536", "5555 ", "5555;id", "$(id)", "\n", "\""};
        for (String value : rejected) {
            if (AdbNetworkProtocol.allowedPort(value)) throw new AssertionError("accepted_port");
            try {
                AdbNetworkProtocol.setPort(value);
                throw new AssertionError("accepted_payload");
            } catch (IllegalArgumentException expected) {}
        }
        System.out.println("AdbNetworkProtocolTest passed");
    }

    private static String readNativeString8(ByteBuffer input) {
        int count = input.getInt();
        byte[] bytes = new byte[count];
        input.get(bytes);
        if (count > 0) {
            if (input.get() != 0) throw new AssertionError("missing_nul");
            while ((input.position() & 3) != 0) if (input.get() != 0) throw new AssertionError("nonzero_padding");
        }
        return new String(bytes, Charset.forName("UTF-8"));
    }

    private static void expectHex(byte[] actual, String hex) {
        byte[] expected = new byte[hex.length() / 2];
        for (int i = 0; i < expected.length; i++) expected[i] = (byte) Integer.parseInt(hex.substring(i * 2, i * 2 + 2), 16);
        if (!Arrays.equals(actual, expected)) throw new AssertionError("wire_fixture_mismatch");
    }

    private static void expect(String expected, String actual) {
        if (!expected.equals(actual)) throw new AssertionError("unexpected_field");
    }
}
