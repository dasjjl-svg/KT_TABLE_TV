package net.quber.afactorymode;

import java.io.IOException;
import java.io.OutputStream;

public final class SpiBackupFrame {
    public static final int HEADER_SIZE = 16;
    public static final int MAX_REPORT = 4096;
    public static final int PAYLOAD_SIZE = 4194304;
    public static final int MAX_WIRE = HEADER_SIZE + MAX_REPORT + PAYLOAD_SIZE;
    private static final byte[] MAGIC = {'H', 'P', 'S', 'P', 'I', 'B', '0', '1'};

    private SpiBackupFrame() {}

    public static final class Frame {
        public final byte[] report;
        public final byte[] payload;

        private Frame(byte[] report, byte[] payload) {
            this.report = report;
            this.payload = payload;
        }
    }

    public static void write(OutputStream output, byte[] report, byte[] payload) throws IOException {
        if (output == null || report == null || payload == null) {
            throw new IllegalArgumentException("frame_null");
        }
        requireLengths(report.length, payload.length);
        byte[] header = new byte[HEADER_SIZE];
        System.arraycopy(MAGIC, 0, header, 0, MAGIC.length);
        putLength(header, 8, report.length);
        putLength(header, 12, payload.length);
        output.write(header);
        output.write(report);
        output.write(payload);
    }

    public static Frame decode(byte[] frame) {
        if (frame == null || frame.length < HEADER_SIZE || frame.length > MAX_WIRE) {
            throw new IllegalArgumentException("frame_size");
        }
        for (int i = 0; i < MAGIC.length; i++) {
            if (frame[i] != MAGIC[i]) throw new IllegalArgumentException("frame_magic");
        }
        long reportLength = lengthAt(frame, 8);
        long payloadLength = lengthAt(frame, 12);
        requireLengths(reportLength, payloadLength);
        if (HEADER_SIZE + reportLength + payloadLength != frame.length) {
            throw new IllegalArgumentException("frame_length_mismatch");
        }
        byte[] report = new byte[(int) reportLength];
        byte[] payload = new byte[(int) payloadLength];
        System.arraycopy(frame, HEADER_SIZE, report, 0, report.length);
        System.arraycopy(frame, HEADER_SIZE + report.length, payload, 0, payload.length);
        return new Frame(report, payload);
    }

    private static void requireLengths(long report, long payload) {
        if (report < 1 || report > MAX_REPORT || (payload != 0 && payload != PAYLOAD_SIZE)) {
            throw new IllegalArgumentException("frame_lengths");
        }
    }

    private static long lengthAt(byte[] frame, int offset) {
        return ((frame[offset] & 255L) << 24) | ((frame[offset + 1] & 255L) << 16)
                | ((frame[offset + 2] & 255L) << 8) | (frame[offset + 3] & 255L);
    }

    private static void putLength(byte[] header, int offset, int length) {
        header[offset] = (byte) (length >>> 24);
        header[offset + 1] = (byte) (length >>> 16);
        header[offset + 2] = (byte) (length >>> 8);
        header[offset + 3] = (byte) length;
    }
}
