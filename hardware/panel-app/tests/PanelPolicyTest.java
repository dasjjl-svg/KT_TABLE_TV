package net.quber.afactorymode;

public final class PanelPolicyTest {
    private static int checks;
    private static void check(boolean value, String message) {
        checks++;
        if (!value) throw new AssertionError(message);
    }
    public static void main(String[] args) {
        PanelPolicy policy = new PanelPolicy("http://192.168.10.20:4181");
        for (String value : new String[]{"http://192.168.10.20:4181/", "http://192.168.10.20:4181/api/state", "http://192.168.10.20:4181/#room"}) {
            check(policy.sameOrigin(value), "local page rejected");
        }
        for (String value : new String[]{null, "", "file:///sdcard/secret", "content://provider", "javascript:alert(1)",
                "https://192.168.10.20:4181/", "http://192.168.10.20/", "http://192.168.10.20.evil:4181/",
                "http://user@192.168.10.20:4181/", "http://192.168.10.20:4181@evil/", "http://192.168.10.20:4181\\@evil/"}) {
            check(!policy.sameOrigin(value), "external or malformed origin accepted");
        }
        check(policy.reconnect("http://192.168.10.20:4181/device/reconnect"), "reconnect route rejected");
        for (String value : new String[]{"http://192.168.10.20:4181/device/reconnect?next=evil", "http://evil/device/reconnect",
                "http://192.168.10.20:4181/device/%72econnect", "http://192.168.10.20:4181/device/reconnect#x"}) {
            check(!policy.reconnect(value), "unexpected reconnect route accepted");
        }
        String credential = new String(new char[64]).replace('\0', 'a');
        String cookie = "genie_legacy=" + credential + "; Path=/; HttpOnly; SameSite=Strict; Max-Age=86400";
        check(cookie.equals(PanelPolicy.sessionCookie(cookie)), "valid session cookie rejected");
        for (String value : new String[]{null, cookie.replace("HttpOnly;", ""), cookie.replace("SameSite=Strict", "SameSite=None"),
                cookie.replace("Path=/", "Path=/api"), cookie + "; Domain=evil", cookie + "\r\nInjected: yes",
                cookie.replace(credential, "short"), cookie.replace("genie_legacy", "other")}) {
            check(PanelPolicy.sessionCookie(value) == null, "invalid cookie accepted");
        }
        check(PanelPolicy.retryDelay(0) == 5000, "first retry");
        check(PanelPolicy.retryDelay(1) == 10000, "second retry");
        check(PanelPolicy.retryDelay(2) == 20000, "third retry");
        check(PanelPolicy.retryDelay(3) == 30000, "fourth retry");
        check(PanelPolicy.retryDelay(Integer.MAX_VALUE) == 30000, "retry cap");
        System.out.println("Panel URL, session cookie and retry policy checks passed: " + checks);
    }
}
