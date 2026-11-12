package net.quber.afactorymode;

public final class HomeSetupPolicyTest {
    private static final String MANAGED = "dev.homepanel.community", BOOTSTRAP = "net.quber.afactorymode";
    private static int checks;
    private static void check(boolean value) { checks++; if (!value) throw new AssertionError("home setup check " + checks); }

    public static void main(String[] args) {
        for (String action : new String[]{null, "", "android.intent.action.BOOT_COMPLETED", "home.panel.UNKNOWN"}) {
            check(!HomeSetupPolicy.requestedAfterAction(MANAGED, action, false, false));
            check(!HomeSetupPolicy.shouldConfigure(MANAGED, false, true, false));
        }
        check(!HomeSetupPolicy.requestedAfterAction(BOOTSTRAP, "home.panel.SETUP", false, false));
        check(!HomeSetupPolicy.shouldConfigure(BOOTSTRAP, true, true, false));
        boolean requested = HomeSetupPolicy.requestedAfterAction(MANAGED, "home.panel.SETUP", false, false);
        check(requested);
        check(HomeSetupPolicy.shouldConfigure(MANAGED, requested, true, false));
        requested = false;
        check(!HomeSetupPolicy.shouldConfigure(MANAGED, requested, true, false));
        check(!HomeSetupPolicy.requestedAfterAction(MANAGED, null, requested, false));
        check(HomeSetupPolicy.requestedAfterAction(MANAGED, "home.panel.SETUP", requested, false));
        check(!HomeSetupPolicy.requestedAfterAction(MANAGED, "home.panel.SETUP", false, true));
        check(!HomeSetupPolicy.shouldConfigure(MANAGED, true, true, true));
        check(!HomeSetupPolicy.shouldConfigure(MANAGED, true, false, false));
        check(!HomeSetupPolicy.requestedAfterAction(MANAGED, "home.panel.RESTORE", true, false));
        check(!HomeSetupPolicy.requestedAfterAction(MANAGED, "home.panel.RESTORE", true, true));
        System.out.println("Explicit HOME setup policy checks passed: " + checks);
    }
}
