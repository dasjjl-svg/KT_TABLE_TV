package net.quber.afactorymode;

public final class MaintenanceSignerPolicyTest {
    private static int checks;
    private static final String ORIGINAL = RecipientSigningConfig.BOOTSTRAP_SHA256;
    private static final String APPROVED = "c8a2e9bccf597c2fb6dc66bee293fc13f2fc47ec77bc6b2b0d52c11f51192ab8";

    public static void main(String[] args) {
        for (int version : new int[]{1, 38, 39, 40, 41, 46, 47, 48, Integer.MAX_VALUE}) {
            String bootstrapExpected = version <= 46 ? ORIGINAL : APPROVED;
            String bootstrapRejected = version <= 46 ? APPROVED : ORIGINAL;
            check(bootstrapExpected.equals(MaintenanceSignerPolicy.expectedCertificate("net.quber.afactorymode", version)), "bootstrap_boundary");
            check(MaintenanceSignerPolicy.accepts("net.quber.afactorymode", version, bootstrapExpected), "bootstrap_accepts_expected");
            check(!MaintenanceSignerPolicy.accepts("net.quber.afactorymode", version, bootstrapRejected), "bootstrap_rejects_other");
            String expected = version <= 39 ? ORIGINAL : APPROVED;
            String rejected = version <= 39 ? APPROVED : ORIGINAL;
            check(expected.equals(MaintenanceSignerPolicy.expectedCertificate("dev.homepanel.community", version)), "managed_boundary");
            check(MaintenanceSignerPolicy.accepts("dev.homepanel.community", version, expected), "managed_accepts_expected");
            check(!MaintenanceSignerPolicy.accepts("dev.homepanel.community", version, rejected), "managed_rejects_other");
        }
        for (String name : new String[]{"dev.homepanel.community", "net.quber.afactorymode"}) {
            for (int version : new int[]{Integer.MIN_VALUE, -1, 0}) {
                reject(name, version);
                check(!MaintenanceSignerPolicy.accepts(name, version, ORIGINAL), "invalid_version_original");
                check(!MaintenanceSignerPolicy.accepts(name, version, APPROVED), "invalid_version_approved");
            }
        }
        for (String name : new String[]{null, "", "dev.homepanel.community.stable", "dev.homepanel.community ",
                "net.quber.afactorymode.remote", "com.kt.gigagenie.launcher"}) {
            for (int version : new int[]{39, 40, 46, 47}) {
                reject(name, version);
                check(!MaintenanceSignerPolicy.accepts(name, version, ORIGINAL), "foreign_original");
                check(!MaintenanceSignerPolicy.accepts(name, version, APPROVED), "foreign_approved");
            }
        }
        for (String invalid : new String[]{null, "", ORIGINAL.toUpperCase(java.util.Locale.ROOT),
                APPROVED.toUpperCase(java.util.Locale.ROOT), ORIGINAL + "\n", APPROVED + " ", "00"}) {
            check(!MaintenanceSignerPolicy.accepts("dev.homepanel.community", 39, invalid), "invalid_hash_original");
            check(!MaintenanceSignerPolicy.accepts("dev.homepanel.community", 40, invalid), "invalid_hash_approved");
            check(!MaintenanceSignerPolicy.accepts("net.quber.afactorymode", 46, invalid), "invalid_hash_bootstrap_original");
            check(!MaintenanceSignerPolicy.accepts("net.quber.afactorymode", 47, invalid), "invalid_hash_bootstrap_approved");
        }
        System.out.println("MaintenanceSignerPolicyTest passed: " + checks + " checks");
    }

    private static void reject(String name, int version) {
        try { MaintenanceSignerPolicy.expectedCertificate(name, version); throw new AssertionError("invalid_identity"); }
        catch (IllegalArgumentException expected) { checks++; }
    }

    private static void check(boolean result, String label) {
        checks++; if (!result) throw new AssertionError(label);
    }
}
