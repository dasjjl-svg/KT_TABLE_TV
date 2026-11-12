package net.quber.afactorymode;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

final class SystemBackupPolicy {
    private static final long MAX_SECTORS = Long.MAX_VALUE / 512L;
    private static final long MAX_PARTITION = 32L * 1024L * 1024L * 1024L;
    private static final int MAX_TEXT = 65536;

    static final class Block {
        final String name;
        final String deviceNumber;
        final long sectors;
        final long start;
        final boolean readOnly;

        private Block(String name, String deviceNumber, long sectors, long start, boolean readOnly) {
            this.name = name;
            this.deviceNumber = deviceNumber;
            this.sectors = sectors;
            this.start = start;
            this.readOnly = readOnly;
        }

        long sizeBytes() { return sectors * 512L; }
    }

    private SystemBackupPolicy() {}

    static Map<String, Block> blocks(String raw) {
        requireText(raw);
        Map<String, Block> result = new LinkedHashMap<String, Block>();
        Set<String> devices = new LinkedHashSet<String>();
        for (String line : raw.split("\n", -1)) {
            if (line.length() == 0) continue;
            String[] fields = line.split("\\|", -1);
            if (fields.length != 5 || !blockName(fields[0])) fail("invalid_block_map");
            String device = deviceNumber(fields[1]);
            long sectors = number(fields[2], MAX_SECTORS);
            long start = "-1".equals(fields[3]) ? -1L : number(fields[3], MAX_SECTORS);
            if (start >= 0 && start > MAX_SECTORS - sectors) fail("block_range_overflow");
            if (!("0".equals(fields[4]) || "1".equals(fields[4]))) fail("invalid_read_only_flag");
            if (result.containsKey(fields[0]) || !devices.add(device)) fail("duplicate_block");
            result.put(fields[0], new Block(fields[0], device, sectors, start, "1".equals(fields[4])));
            if (result.size() > 1002) fail("too_many_blocks");
        }
        if (result.isEmpty()) fail("empty_block_map");
        return Collections.unmodifiableMap(result);
    }

    static Set<String> writableMounts(String raw) {
        requireText(raw);
        Set<String> result = new LinkedHashSet<String>();
        Set<Long> mountIds = new LinkedHashSet<Long>();
        int lines = 0;
        for (String line : raw.split("\n", -1)) {
            if (line.length() == 0) continue;
            if (!line.equals(line.trim()) || line.indexOf('\r') >= 0 || line.indexOf('\0') >= 0) fail("invalid_mountinfo");
            String[] fields = line.split(" +", -1);
            if (fields.length < 10) fail("invalid_mountinfo");
            long mountId = number(fields[0], 4294967295L);
            number(fields[1], 4294967295L);
            if (mountId == 0 || !mountIds.add(mountId)) fail("duplicate_mount_id");
            String device = deviceNumber(fields[2]);
            if (!fields[3].startsWith("/") || !fields[4].startsWith("/")) fail("invalid_mount_path");
            int separator = -1;
            for (int i = 6; i < fields.length; i++) {
                if ("-".equals(fields[i])) {
                    if (separator != -1) fail("invalid_mount_separator");
                    separator = i;
                }
            }
            if (separator < 6 || separator != fields.length - 4
                    || fields[separator + 1].length() == 0 || fields[separator + 2].length() == 0) fail("invalid_mount_separator");
            boolean mountWritable = writableOptions(fields[5]);
            boolean superWritable = writableOptions(fields[separator + 3]);
            if (mountWritable || superWritable) result.add(device);
            if (++lines > 4096) fail("too_many_mounts");
        }
        if (lines == 0) fail("empty_mountinfo");
        return Collections.unmodifiableSet(result);
    }

    static void validatePartition(String name, long size, int chunkSize,
                                  Map<String, Block> blocks, Set<String> writableDevices) {
        if (!partitionName(name) || blocks == null || writableDevices == null) fail("invalid_partition");
        Block block = blocks.get(name);
        if (block == null || !name.equals(block.name)) fail("unknown_partition");
        if (size <= 0 || size > MAX_PARTITION || (size & 511L) != 0
                || size != block.sizeBytes() || chunkSize != 1048576) fail("invalid_partition_size");
        if (writableDevices.contains(block.deviceNumber)) fail("partition_mounted_writable");
    }

    static boolean rootIdentity(String raw) {
        if (raw == null || raw.length() == 0 || raw.length() > MAX_TEXT || raw.indexOf('\0') >= 0) return false;
        boolean found = false;
        for (String line : raw.split("\n", -1)) {
            String candidate = line.trim();
            if (!candidate.startsWith("Uid:")) continue;
            if (found) return false;
            found = true;
            String[] values = candidate.substring(4).trim().split("\\s+", -1);
            if (values.length != 4) return false;
            for (String value : values) if (!"0".equals(value)) return false;
        }
        return found;
    }

    private static boolean blockName(String value) {
        return "mmcblk0".equals(value) || partitionName(value);
    }

    private static boolean partitionName(String value) {
        return value != null && value.matches("mmcblk0(?:p[1-9][0-9]{0,2}|boot[01])");
    }

    private static String deviceNumber(String value) {
        if (value == null) { fail("invalid_device_number"); return null; }
        String[] fields = value.split(":", -1);
        if (fields.length != 2) { fail("invalid_device_number"); return null; }
        return number(fields[0], 4294967295L) + ":" + number(fields[1], 4294967295L);
    }

    private static long number(String value, long maximum) {
        if (value == null || !value.matches("[0-9]{1,19}")) fail("invalid_unsigned_number");
        long result;
        try { result = Long.parseLong(value); }
        catch (NumberFormatException error) { throw new IllegalArgumentException("number_overflow"); }
        if (result > maximum) fail("number_overflow");
        return result;
    }

    private static boolean writableOptions(String value) {
        if (value == null || value.length() == 0) { fail("invalid_mount_options"); return false; }
        boolean readOnly = false, writable = false;
        for (String option : value.split(",", -1)) {
            if (option.length() == 0 || option.indexOf('\t') >= 0) fail("invalid_mount_options");
            if ("ro".equals(option)) readOnly = true;
            if ("rw".equals(option)) writable = true;
        }
        if (readOnly == writable) fail("ambiguous_mount_options");
        return writable;
    }

    private static void requireText(String raw) {
        if (raw == null || raw.length() == 0 || raw.length() > MAX_TEXT || raw.indexOf('\0') >= 0) fail("invalid_text");
    }

    private static void fail(String reason) { throw new IllegalArgumentException(reason); }
}
