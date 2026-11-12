package net.quber.afactorymode;

import java.net.URI;
import java.util.Locale;

final class PanelPolicy {
    private final URI origin;

    PanelPolicy(String value) {
        try {
            origin = new URI(value);
            if (!"http".equals(origin.getScheme()) || origin.getHost() == null
                    || origin.getRawUserInfo() != null || origin.getRawQuery() != null
                    || origin.getRawFragment() != null || !"".equals(origin.getRawPath())) {
                throw new IllegalArgumentException("Invalid panel origin");
            }
        } catch (Exception error) {
            throw new IllegalArgumentException("Invalid panel origin");
        }
    }

    boolean sameOrigin(String value) {
        try {
            URI candidate = new URI(value);
            return origin.getScheme().equals(candidate.getScheme())
                    && origin.getHost().equals(candidate.getHost())
                    && port(origin) == port(candidate) && candidate.getRawUserInfo() == null;
        } catch (Exception error) {
            return false;
        }
    }

    boolean reconnect(String value) {
        if (!sameOrigin(value)) return false;
        try {
            URI candidate = new URI(value);
            return "/device/reconnect".equals(candidate.getRawPath())
                    && candidate.getRawQuery() == null && candidate.getRawFragment() == null;
        } catch (Exception error) {
            return false;
        }
    }

    static long retryDelay(int attempt) {
        if (attempt <= 0) return 5000L;
        if (attempt == 1) return 10000L;
        if (attempt == 2) return 20000L;
        return 30000L;
    }

    static String sessionCookie(String value) {
        if (value == null || value.length() > 512 || value.indexOf('\r') >= 0 || value.indexOf('\n') >= 0) return null;
        String[] fields = value.split(";");
        if (!fields[0].trim().matches("genie_legacy=[0-9a-f]{64}")) return null;
        boolean path = false, httpOnly = false, sameSite = false;
        for (int i = 1; i < fields.length; i++) {
            String field = fields[i].trim().toLowerCase(Locale.US);
            if (field.startsWith("domain=")) return null;
            if (field.equals("path=/")) path = true;
            if (field.equals("httponly")) httpOnly = true;
            if (field.equals("samesite=strict")) sameSite = true;
        }
        return path && httpOnly && sameSite
                ? fields[0].trim() + "; Path=/; HttpOnly; SameSite=Strict; Max-Age=86400" : null;
    }

    private static int port(URI uri) {
        return uri.getPort() == -1 ? 80 : uri.getPort();
    }
}
