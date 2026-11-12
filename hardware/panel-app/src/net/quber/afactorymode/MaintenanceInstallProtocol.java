package net.quber.afactorymode;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

final class MaintenanceInstallProtocol {
    private MaintenanceInstallProtocol() {}

    static String[] create(int size) {
        requireSize(size);
        return new String[]{"install-create", "-r", "--user", "0", "-S", Integer.toString(size)};
    }

    static String[] write(int session, int size) {
        requireSession(session); requireSize(size);
        return new String[]{"install-write", "-S", Integer.toString(size), Integer.toString(session),
                "base.apk", "/data/local/tmp/home-panel-maintenance/candidate.apk"};
    }

    static String[] commit(int session) { requireSession(session); return new String[]{"install-commit", Integer.toString(session)}; }
    static String[] abandon(int session) { requireSession(session); return new String[]{"install-abandon", Integer.toString(session)}; }

    static int createdSession(String output) {
        if (output == null || output.length() > 128) throw new IllegalArgumentException("install_session_output");
        Matcher match = Pattern.compile("Success: created install session \\[([1-9][0-9]{0,9})\\]\\r?\\n?").matcher(output);
        if (!match.matches()) throw new IllegalArgumentException("install_session_output");
        long value = Long.parseLong(match.group(1));
        if (value > Integer.MAX_VALUE) throw new IllegalArgumentException("install_session_range");
        return (int) value;
    }

    static boolean written(String output, int size) {
        requireSize(size);
        return terminal(output, "Success: streamed " + size + " bytes");
    }

    static boolean succeeded(String output) { return terminal(output, "Success"); }

    private static boolean terminal(String output, String expected) {
        return expected.equals(output) || (expected + "\n").equals(output) || (expected + "\r\n").equals(output);
    }

    private static void requireSession(int session) { if (session <= 0) throw new IllegalArgumentException("install_session_range"); }
    private static void requireSize(int size) { if (size <= 0 || size > 2 * 1024 * 1024) throw new IllegalArgumentException("install_size"); }
}
