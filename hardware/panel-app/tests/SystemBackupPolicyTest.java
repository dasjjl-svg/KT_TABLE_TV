package net.quber.afactorymode;

import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

public final class SystemBackupPolicyTest {
    private static int checks;
    private static final String MAP = "mmcblk0|179:0|33554432|-1|0\n"
            + "mmcblk0p1|179:1|2048|2048|0\n"
            + "mmcblk0p2|179:2|4096|4096|0\n"
            + "mmcblk0boot0|179:8|8192|-1|1\n";
    private static final String MOUNTS = "20 1 0:1 / / rw,relatime - rootfs rootfs rw\n"
            + "21 20 179:1 / /system ro,relatime shared:1 - ext4 /dev/block/by-name/system ro,errors=panic\n"
            + "22 20 179:2 / /data rw,relatime - ext4 /dev/block/platform/controller/by-name/userdata rw\n";

    public static void main(String[] args) {
        final Map<String, SystemBackupPolicy.Block> map = SystemBackupPolicy.blocks(MAP);
        expect(map.size() == 4, "map_size");
        expect(map.get("mmcblk0p1").sizeBytes() == 1048576L, "sector_conversion");
        expect(!map.get("mmcblk0p1").readOnly, "ro_zero");
        expect(map.get("mmcblk0boot0").readOnly, "ro_one");
        expect(map.get("mmcblk0boot0").start == -1L, "boot_start");
        final Set<String> rw = SystemBackupPolicy.writableMounts(MOUNTS);
        expect(rw.contains("179:2") && !rw.contains("179:1"), "rw_by_device_number");
        SystemBackupPolicy.validatePartition("mmcblk0p1", 1048576L, 1048576, map, rw);
        SystemBackupPolicy.validatePartition("mmcblk0boot0", 4194304L, 1048576, map, rw);
        rejected(new Runnable() { public void run() { SystemBackupPolicy.validatePartition("mmcblk0p2", 2097152L, 1048576, map, rw); }});
        rejected(new Runnable() { public void run() { SystemBackupPolicy.validatePartition("mmcblk0", 17179869184L, 1048576, map, rw); }});
        rejected(new Runnable() { public void run() { SystemBackupPolicy.validatePartition("mmcblk0p3", 1048576L, 1048576, map, rw); }});
        rejected(new Runnable() { public void run() { SystemBackupPolicy.validatePartition("mmcblk0p1", 512L, 1048576, map, rw); }});
        rejected(new Runnable() { public void run() { SystemBackupPolicy.validatePartition("mmcblk0p1", 1048576L, 65536, map, rw); }});
        rejected(new Runnable() { public void run() { SystemBackupPolicy.validatePartition("mmcblk0p1;id", 1048576L, 1048576, map, rw); }});
        String[] badMaps = {null, "", "\n", "mmcblk0p0|179:1|1|0|0", "mmcblk0p01|179:1|1|0|0",
                "mmcblk0p1|179:1|-1|0|0", "mmcblk0p1|179:1|9223372036854775808|0|0",
                "mmcblk0p1|179:1|18014398509481984|0|0", "mmcblk0p1|179:1|1|18014398509481983|0",
                "mmcblk0p1|179:1|1|-2|0", "mmcblk0p1|4294967296:1|1|0|0", "mmcblk0p1|179:1|1|0|2",
                "mmcblk0p1|179:1|1|0|0|extra", MAP + "mmcblk0p1|179:4|1|0|0\n",
                MAP + "mmcblk0p3|0179:002|1|0|0\n"};
        for (final String bad : badMaps) rejected(new Runnable() { public void run() { SystemBackupPolicy.blocks(bad); }});
        String[] badMounts = {null, "", "\n", "20 1 179:1 / / rw - ext4 /dev/block/x",
                "20 1 179:1 / / rw ext4 /dev/block/x rw", "20 1 invalid / / rw - ext4 /dev/block/x rw",
                "20 1 179:1 / / relatime - ext4 /dev/block/x rw", "20 1 179:1 / / ro,rw - ext4 /dev/block/x rw",
                "20 1 179:1 / / rw - ext4 /dev/block/x rw extra", "20 1 179:1 / / rw - ext4 /dev/block/x rw\n20 1 179:2 / / rw - ext4 /dev/block/y rw"};
        for (final String bad : badMounts) rejected(new Runnable() { public void run() { SystemBackupPolicy.writableMounts(bad); }});
        Set<String> bindRo = SystemBackupPolicy.writableMounts("20 1 179:1 / /system ro - ext4 /dev/block/x rw\n");
        expect(bindRo.contains("179:1"), "superblock_writable");
        Set<String> escaped = SystemBackupPolicy.writableMounts("20 1 179:1 / /mnt/a\\040b rw - ext4 /dev/block/x rw\n");
        expect(escaped.contains("179:1"), "escaped_path_ignored");
        Map<String, SystemBackupPolicy.Block> tooLarge = SystemBackupPolicy.blocks("mmcblk0p1|179:1|67108865|0|0\n");
        final Map<String, SystemBackupPolicy.Block> large = tooLarge;
        rejected(new Runnable() { public void run() { SystemBackupPolicy.validatePartition("mmcblk0p1", 34359738880L, 1048576, large, new LinkedHashSet<String>()); }});
        expect(SystemBackupPolicy.rootIdentity("Name:\tsh\nUid:\t0\t0\t0\t0\nCapEff:\t0000003fffffffff\n"), "root_four_uids");
        String[] notRoot = {null, "", "Uid: 0", "Uid: 0 0 0", "Uid: 0 0 0 0 0", "Uid: 0 1000 0 0", "Uid: -1 0 0 0",
                "Uid: 0 0 0 0\nUid: 0 0 0 0\n", "Uid: 0 0 0 0\n Uid: 1000 1000 1000 1000", "Gid: 0 0 0 0", "Uid: 00 0 0 0"};
        for (String invalid : notRoot) expect(!SystemBackupPolicy.rootIdentity(invalid), "reject_non_root_identity");
        System.out.println("SystemBackupPolicyTest passed: " + checks + " checks");
    }

    private static void rejected(Runnable action) {
        checks++;
        try { action.run(); } catch (IllegalArgumentException expected) { return; }
        throw new AssertionError("accepted_invalid_input");
    }

    private static void expect(boolean value, String label) {
        checks++;
        if (!value) throw new AssertionError(label);
    }
}
