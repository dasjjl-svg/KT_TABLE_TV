package net.quber.afactorymode;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.Charset;
import java.util.Arrays;

public final class SpiBackupFrameTest {
    private static int checks;
    private static final Charset UTF8 = Charset.forName("UTF-8");

    private static final class Parts extends OutputStream {
        final byte[][] values = new byte[3][];
        int count;
        boolean closed;
        boolean flushed;

        public void write(int value) { throw new AssertionError("unexpected_byte_write"); }
        public void write(byte[] value) {
            if (count == values.length) throw new AssertionError("too_many_parts");
            values[count++] = value;
        }
        public void close() { closed = true; }
        public void flush() { flushed = true; }
    }

    public static void main(String[] args) throws Exception {
        byte[] report = "{\"version\":1,\"status\":\"ok\",\"label\":\"백업\"}".getBytes(UTF8);
        byte[] payload = new byte[SpiBackupFrame.PAYLOAD_SIZE];
        for (int i = 0; i < payload.length; i++) payload[i] = (byte) (i * 31 + i / 256);
        Parts parts = new Parts();
        SpiBackupFrame.write(parts, report, payload);
        expect(parts.count == 3, "three_parts");
        expect(parts.values[0].length == 16, "header_only_allocation");
        expect(parts.values[1] == report && parts.values[2] == payload, "payload_not_copied_by_writer");
        expect(!parts.closed && !parts.flushed, "caller_owns_stream");
        byte[] header = parts.values[0];
        expect(Arrays.equals(Arrays.copyOf(header, 8), "HPSPIB01".getBytes(UTF8)), "fixed_magic");
        expect(unsignedLength(header, 8) == report.length && unsignedLength(header, 12) == 4194304L,
                "big_endian_lengths");
        byte[] wire = encode(report, payload);
        expect(wire.length == 16 + report.length + 4194304, "wire_length");
        SpiBackupFrame.Frame decoded = SpiBackupFrame.decode(wire);
        expect(Arrays.equals(decoded.report, report), "report_roundtrip");
        expect(Arrays.equals(decoded.payload, payload), "four_mib_roundtrip");
        int payloadStart = 16 + report.length;
        decoded.payload[0] ^= 1;
        expect(wire[payloadStart] == payload[0], "decoded_payload_does_not_alias_wire");
        wire[payloadStart + 1] ^= 1;
        expect(decoded.payload[1] == payload[1], "wire_does_not_alias_decoded_payload");
        decoded.report[0] ^= 1;
        expect(wire[16] == report[0], "decoded_report_does_not_alias_wire");

        byte[] empty = encode("{}".getBytes(UTF8), new byte[0]);
        expect(SpiBackupFrame.decode(empty).payload.length == 0, "failure_without_payload");
        byte[] largestReport = new byte[SpiBackupFrame.MAX_REPORT];
        Arrays.fill(largestReport, (byte) ' ');
        largestReport[0] = '{';
        largestReport[1] = '}';
        byte[] largest = encode(largestReport, payload);
        expect(largest.length == SpiBackupFrame.MAX_WIRE, "maximum_wire_bound");
        expect(SpiBackupFrame.decode(largest).report.length == 4096, "maximum_report");
        expect(SpiBackupFrame.decode(encode(new byte[] {'0'}, new byte[0])).report.length == 1, "minimum_report");

        reject(null);
        for (int length = 0; length < 16; length++) reject(new byte[length]);
        for (int i = 0; i < 8; i++) {
            byte[] bad = empty.clone();
            bad[i] ^= 1;
            reject(bad);
        }
        for (long length : new long[] {0, 4097, 0x7fffffffL, 0x80000000L, 0xffffffffL}) {
            byte[] bad = empty.clone();
            putLength(bad, 8, length);
            reject(bad);
        }
        for (long length : new long[] {1, 4194303, 4194305, 0x7fffffffL, 0x80000000L, 0xffffffffL}) {
            byte[] bad = empty.clone();
            putLength(bad, 12, length);
            reject(bad);
        }
        reject(Arrays.copyOf(empty, 16));
        reject(Arrays.copyOf(empty, empty.length - 1));
        reject(Arrays.copyOf(empty, empty.length + 1));
        reject(Arrays.copyOf(wire, wire.length - 1));
        reject(Arrays.copyOf(wire, wire.length + 1));
        reject(Arrays.copyOf(largest, largest.length + 1));
        byte[] truncatedHeader = Arrays.copyOf(wire, 16);
        reject(truncatedHeader);
        byte[] falseEmpty = empty.clone();
        putLength(falseEmpty, 12, 4194304);
        reject(falseEmpty);

        rejectWrite(null, new byte[0]);
        rejectWrite(new byte[0], new byte[0]);
        rejectWrite(new byte[4097], new byte[0]);
        rejectWrite(report, null);
        rejectWrite(report, new byte[1]);
        rejectWrite(report, new byte[4194303]);
        rejectWrite(report, new byte[4194305]);
        try {
            SpiBackupFrame.write(null, report, payload);
            throw new AssertionError("null_stream_accepted");
        } catch (IllegalArgumentException expected) { checks++; }
        final IOException failure = new IOException("fixture_write_failure");
        final int[] calls = {0};
        OutputStream failing = new OutputStream() {
            public void write(int value) { throw new AssertionError("unexpected_byte_write"); }
            public void write(byte[] value) throws IOException {
                calls[0]++;
                throw failure;
            }
        };
        try {
            SpiBackupFrame.write(failing, report, payload);
            throw new AssertionError("stream_error_not_propagated");
        } catch (IOException expected) {
            expect(expected == failure && calls[0] == 1, "failed_write_stops_immediately");
        }
        System.out.println("SpiBackupFrameTest: " + checks + " checks passed");
    }

    private static byte[] encode(byte[] report, byte[] payload) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream(16 + report.length + payload.length);
        SpiBackupFrame.write(output, report, payload);
        return output.toByteArray();
    }

    private static void reject(byte[] wire) {
        try {
            SpiBackupFrame.decode(wire);
            throw new AssertionError("invalid_frame_accepted");
        } catch (IllegalArgumentException expected) { checks++; }
    }

    private static void rejectWrite(byte[] report, byte[] payload) throws IOException {
        Parts parts = new Parts();
        try {
            SpiBackupFrame.write(parts, report, payload);
            throw new AssertionError("invalid_write_accepted");
        } catch (IllegalArgumentException expected) {
            expect(parts.count == 0, "invalid_write_sends_nothing");
        }
    }

    private static long unsignedLength(byte[] value, int offset) {
        long result = 0;
        for (int i = 0; i < 4; i++) result = result * 256 + (value[offset + i] & 255L);
        return result;
    }

    private static void putLength(byte[] value, int offset, long length) {
        for (int i = 3; i >= 0; i--) {
            value[offset + i] = (byte) length;
            length >>>= 8;
        }
    }

    private static void expect(boolean value, String label) {
        checks++;
        if (!value) throw new AssertionError(label);
    }
}
