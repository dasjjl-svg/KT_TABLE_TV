package net.quber.afactorymode;

import java.io.ByteArrayOutputStream;
import java.io.UnsupportedEncodingException;

final class AdbNetworkProtocol {
    private AdbNetworkProtocol() {}

    static boolean allowedPort(String value) {
        return "".equals(value) || "-1".equals(value) || "0".equals(value) || "5555".equals(value);
    }

    static byte[] setPort(String value) {
        if (!allowedPort(value)) throw new IllegalArgumentException("unsupported_port");
        return pair("service.adb.tcp.port", value.length() == 0 ? "\"\"" : value);
    }

    static byte[] startDaemon() {
        return pair("ctl.start", "adbd");
    }

    private static byte[] pair(String key, String value) {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        string8(output, key);
        string8(output, value);
        return output.toByteArray();
    }

    private static void string8(ByteArrayOutputStream output, String value) {
        byte[] bytes;
        try {
            bytes = value.getBytes("UTF-8");
        } catch (UnsupportedEncodingException impossible) {
            throw new AssertionError(impossible);
        }
        int size = bytes.length;
        for (int shift = 0; shift < 32; shift += 8) output.write((size >>> shift) & 255);
        if (size == 0) return;
        output.write(bytes, 0, size);
        output.write(0);
        for (int padding = (4 - ((size + 1) & 3)) & 3; padding > 0; padding--) output.write(0);
    }
}
