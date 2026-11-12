package net.quber.afactorymode;

final class HomeSetupPolicy {
    private static final String MANAGED = "dev.homepanel.community";
    private HomeSetupPolicy() {}

    static boolean requestedAfterAction(String packageName, String action, boolean pending, boolean installing) {
        if ("home.panel.RESTORE".equals(action)) return false;
        if ("home.panel.SETUP".equals(action) && !installing) return MANAGED.equals(packageName);
        return pending;
    }

    static boolean shouldConfigure(String packageName, boolean pending, boolean enabled, boolean installing) {
        return MANAGED.equals(packageName) && pending && enabled && !installing;
    }
}
