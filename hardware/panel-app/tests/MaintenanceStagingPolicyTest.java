package net.quber.afactorymode;

public final class MaintenanceStagingPolicyTest {
    private static int checks;
    private static final int UID = 10044;

    public static void main(String[] args) {
        for (MaintenanceStagingPolicy.Role role : new MaintenanceStagingPolicy.Role[]{MaintenanceStagingPolicy.Role.APP, MaintenanceStagingPolicy.Role.FILES}) {
            for (int mode : new int[]{0700, 0751, 0755, 0770, 0771}) accept(role, UID, UID, 0040000 | mode, true);
            accept(role, UID, 1000, 0040751, true);
            reject(role, UID, 1000, 0040771, true, "gid_1000_expected_10044");
            reject(role, UID, 0, 0040770, true, "gid_0_expected_10044");
            reject(role, UID, -1, 0040700, true, "gid_invalid_expected_10044");
            for (int mode : new int[]{0702, 0753, 0777, 01700, 02771, 04700, 0500, 0600}) {
                rejectPrefix(role, UID, UID, 0040000 | mode, true, "mode_");
            }
            reject(role, UID + 1, UID, 0040771, true, "uid_10045_expected_10044");
            reject(role, UID, UID, 0100771, true, "type");
            reject(role, UID, UID, 0120777, true, "type");
            reject(role, UID, UID, 0040771, false, "canonical");
        }
        MaintenanceStagingPolicy.Role role = MaintenanceStagingPolicy.Role.PRIVATE;
        accept(role, UID, UID, 0040700, true);
        accept(role, UID, 1000, 0040700, true);
        for (int mode : new int[]{0701, 0710, 0751, 0755, 0770, 0771, 0777, 01700, 0500}) {
            rejectPrefix(role, UID, UID, 0040000 | mode, true, "mode_");
        }
        reject(role, UID, UID, 0040771, true, "mode_0771");
        reject(role, 0, 0, 0040700, true, "uid_0_expected_10044");
        reject(role, UID, UID, 0120700, true, "type");
        reject(role, UID, UID, 0040700, false, "canonical");
        for (MaintenanceStagingPolicy.Role selected : MaintenanceStagingPolicy.Role.values()) {
            check(MaintenanceStagingPolicy.statFailure(selected, 2).equals("staging_" + selected.label + "_stat_2"));
            for (int mode = 0; mode <= 07777; mode++) {
                try {
                    MaintenanceStagingPolicy.validate(selected, UID, UID, UID, 0040000 | mode, true);
                    check((mode & 0002) == 0 && (mode & 07000) == 0 && (mode & 0700) == 0700);
                    check(selected != MaintenanceStagingPolicy.Role.PRIVATE || mode == 0700);
                } catch (IllegalArgumentException expected) {
                    check(expected.getMessage().matches("staging_[a-z0-9_]{1,70}"));
                }
            }
        }
        System.out.println("MaintenanceStagingPolicyTest passed: " + checks + " checks");
    }

    private static void accept(MaintenanceStagingPolicy.Role role, int uid, int gid, int mode, boolean canonical) {
        MaintenanceStagingPolicy.validate(role, UID, uid, gid, mode, canonical); checks++;
    }

    private static void reject(MaintenanceStagingPolicy.Role role, int uid, int gid, int mode, boolean canonical, String reason) {
        try { MaintenanceStagingPolicy.validate(role, UID, uid, gid, mode, canonical); throw new AssertionError("accepted"); }
        catch (IllegalArgumentException expected) { check(expected.getMessage().equals("staging_" + role.label + "_" + reason)); }
    }

    private static void rejectPrefix(MaintenanceStagingPolicy.Role role, int uid, int gid, int mode, boolean canonical, String reason) {
        try { MaintenanceStagingPolicy.validate(role, UID, uid, gid, mode, canonical); throw new AssertionError("accepted"); }
        catch (IllegalArgumentException expected) { check(expected.getMessage().startsWith("staging_" + role.label + "_" + reason)); }
    }

    private static void check(boolean value) { checks++; if (!value) throw new AssertionError("check " + checks); }
}
