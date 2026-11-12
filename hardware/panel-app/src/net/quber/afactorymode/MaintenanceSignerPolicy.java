package net.quber.afactorymode;

final class MaintenanceSignerPolicy {
    static final String ORIGINAL_SHA256 = RecipientSigningConfig.BOOTSTRAP_SHA256;
    static final String APPROVED_SHA256 = "c8a2e9bccf597c2fb6dc66bee293fc13f2fc47ec77bc6b2b0d52c11f51192ab8";

    private MaintenanceSignerPolicy() {}

    static String expectedCertificate(String packageName, int versionCode) {
        if (versionCode <= 0) throw new IllegalArgumentException("signer_version");
        if ("net.quber.afactorymode".equals(packageName)) return ORIGINAL_SHA256;
        if ("dev.homepanel.community".equals(packageName)) return versionCode <= 39 ? ORIGINAL_SHA256 : APPROVED_SHA256;
        throw new IllegalArgumentException("signer_package");
    }

    static boolean accepts(String packageName, int versionCode, String certificateSha256) {
        try { return expectedCertificate(packageName, versionCode).equals(certificateSha256); }
        catch (IllegalArgumentException invalid) { return false; }
    }
}
