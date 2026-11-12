package net.quber.afactorymode;

final class MaintenanceStagingPolicy {
    enum Role {
        APP("app"), FILES("files"), PRIVATE("private");
        final String label;
        Role(String label) { this.label = label; }
    }

    private MaintenanceStagingPolicy() {}

    static void validate(Role role, int expectedUid, int uid, int gid, int mode, boolean canonical) {
        if (role == null || expectedUid <= 0) throw new IllegalArgumentException("staging_policy_arguments");
        if ((mode & 0170000) != 0040000) fail(role, "type");
        if (uid != expectedUid) fail(role, "uid_" + number(uid) + "_expected_" + expectedUid);
        if (!canonical) fail(role, "canonical");
        int permissions = mode & 07777;
        if ((mode & ~0177777) != 0 || (permissions & 07000) != 0 || (permissions & 0002) != 0
                || (permissions & 0700) != 0700 || (role == Role.PRIVATE && permissions != 0700)) {
            fail(role, "mode_" + octal(permissions));
        }
        if (gid < 0 || ((permissions & 0020) != 0 && gid != expectedUid)) {
            fail(role, "gid_" + number(gid) + "_expected_" + expectedUid);
        }
    }

    static String statFailure(Role role, int errno) {
        if (role == null || errno <= 0) throw new IllegalArgumentException("staging_policy_arguments");
        return "staging_" + role.label + "_stat_" + errno;
    }

    private static String octal(int permissions) {
        String text = Integer.toOctalString(permissions);
        return "0000".substring(text.length()) + text;
    }

    private static String number(int value) { return value < 0 ? "invalid" : Integer.toString(value); }
    private static void fail(Role role, String reason) { throw new IllegalArgumentException("staging_" + role.label + "_" + reason); }
}
