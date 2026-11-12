package net.quber.afactorymode;

import java.util.Arrays;

public final class MaintenanceInstallProtocolTest {
    private static int checks;
    public static void main(String[] args) {
        check(Arrays.equals(MaintenanceInstallProtocol.create(1234), new String[]{"install-create", "-r", "--user", "0", "-S", "1234"}));
        check(Arrays.equals(MaintenanceInstallProtocol.write(87, 1234), new String[]{"install-write", "-S", "1234", "87", "base.apk", "/data/local/tmp/home-panel-maintenance/candidate.apk"}));
        check(Arrays.equals(MaintenanceInstallProtocol.commit(87), new String[]{"install-commit", "87"}));
        check(Arrays.equals(MaintenanceInstallProtocol.abandon(87), new String[]{"install-abandon", "87"}));
        check(MaintenanceInstallProtocol.createdSession("Success: created install session [1]\n") == 1);
        check(MaintenanceInstallProtocol.createdSession("Success: created install session [2147483647]\r\n") == Integer.MAX_VALUE);
        for (final String text : new String[]{null, "", "Success: created install session [0]", "Success: created install session [01]",
                "Success: created install session [-1]", "Success: created install session [2147483648]", "Success: created install session [99999999999]",
                "Success: created install session [1];id", "Success: created install session [1]\nFailure [x]", "Warning\nSuccess: created install session [1]"}) {
            reject(new Action() { public void run() { MaintenanceInstallProtocol.createdSession(text); }});
        }
        for (final int value : new int[]{0, -1, Integer.MIN_VALUE}) {
            reject(new Action() { public void run() { MaintenanceInstallProtocol.write(value, 1); }});
            reject(new Action() { public void run() { MaintenanceInstallProtocol.commit(value); }});
            reject(new Action() { public void run() { MaintenanceInstallProtocol.abandon(value); }});
        }
        for (final int value : new int[]{0, -1, 2097153, Integer.MAX_VALUE}) {
            reject(new Action() { public void run() { MaintenanceInstallProtocol.create(value); }});
            reject(new Action() { public void run() { MaintenanceInstallProtocol.write(1, value); }});
        }
        check(MaintenanceInstallProtocol.written("Success: streamed 1234 bytes\n", 1234));
        check(!MaintenanceInstallProtocol.written("Success: streamed 1233 bytes\n", 1234));
        check(!MaintenanceInstallProtocol.written("Success: streamed 1234 bytes\nFailure", 1234));
        check(MaintenanceInstallProtocol.succeeded("Success\n"));
        check(!MaintenanceInstallProtocol.succeeded("Failure [Success]"));
        check(!MaintenanceInstallProtocol.succeeded("prefix Success\n"));
        check(!MaintenanceInstallProtocol.succeeded(null));
        System.out.println("MaintenanceInstallProtocolTest passed: " + checks + " checks");
    }
    interface Action { void run(); }
    private static void reject(Action action) { try { action.run(); throw new AssertionError("accepted invalid input"); } catch (IllegalArgumentException expected) { checks++; } }
    private static void check(boolean value) { checks++; if (!value) throw new AssertionError("check " + checks); }
}
