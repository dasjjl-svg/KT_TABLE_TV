package net.quber.afactorymode;

import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.provider.Settings;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;

public final class DeviceDebugInfo {
    private static final int MAX_RESULT = 1200;
    private static final String[] PROPERTY_KEYS = {
        "ro.debuggable", "ro.secure", "ro.adb.secure",
        "service.adb.tcp.port", "persist.adb.tcp.port",
        "sys.usb.config", "sys.usb.state", "init.svc.adbd"
    };

    private DeviceDebugInfo() {}

    public static String collect(Context context) {
        StringBuilder result = new StringBuilder();
        if (context == null) return "context.error=missing\n";
        readSetting(result, context, "development_settings_enabled");
        readSetting(result, context, "adb_enabled");
        readProperties(result);
        try {
            Intent intent = new Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS);
            ResolveInfo resolved = context.getPackageManager().resolveActivity(intent, PackageManager.MATCH_DEFAULT_ONLY);
            add(result, "development.intent.resolved=" + (resolved != null && resolved.activityInfo != null));
        } catch (RuntimeException error) {
            error(result, "development.intent", error);
        } catch (LinkageError error) {
            error(result, "development.intent", error);
        }
        try {
            PackageInfo info = context.getPackageManager().getPackageInfo("com.android.settings", 0);
            add(result, "settings.package.found=true");
            if (info.applicationInfo != null) add(result, "settings.package.uid=" + info.applicationInfo.uid);
            add(result, "settings.package.versionCode=" + info.versionCode);
            String version = info.versionName;
            add(result, "settings.package.versionName=" + (version == null || version.length() == 0 ? "unset"
                    : version.length() <= 40 && version.matches("[A-Za-z0-9._+\\-]{1,40}") ? version : "unsupported_value"));
        } catch (PackageManager.NameNotFoundException error) {
            add(result, "settings.package.found=false");
        } catch (RuntimeException error) {
            error(result, "settings.package", error);
        } catch (LinkageError error) {
            error(result, "settings.package", error);
        }
        return result.toString();
    }

    private static void readSetting(StringBuilder result, Context context, String key) {
        try {
            String value = Settings.Global.getString(context.getContentResolver(), key);
            add(result, "global." + key + "=" + flagValue(value));
        } catch (RuntimeException error) {
            error(result, "global." + key, error);
        } catch (LinkageError error) {
            error(result, "global." + key, error);
        }
    }

    private static void readProperties(StringBuilder result) {
        Method get;
        try {
            get = Class.forName("android.os.SystemProperties").getMethod("get", String.class);
        } catch (Exception error) {
            error(result, "properties", error);
            return;
        } catch (LinkageError error) {
            error(result, "properties", error);
            return;
        }
        for (String key : PROPERTY_KEYS) {
            try {
                Object value = get.invoke(null, key);
                add(result, key + "=" + (value instanceof String ? propertyValue(key, (String) value) : "unsupported_type"));
            } catch (Exception error) {
                error(result, key, error);
            } catch (LinkageError error) {
                error(result, key, error);
            }
        }
    }

    private static String propertyValue(String key, String value) {
        if (value.length() == 0) return "unset";
        if (value.length() > 64) return "unsupported_value";
        if (key.startsWith("ro.")) return flagValue(value);
        if (key.endsWith(".tcp.port")) {
            if (!value.matches("-?[0-9]{1,5}")) return "unsupported_value";
            int port = Integer.parseInt(value);
            return port >= -1 && port <= 65535 ? value : "unsupported_value";
        }
        if (key.equals("init.svc.adbd")) {
            return value.matches("running|stopped|stopping|restarting") ? value : "unsupported_value";
        }
        return value.matches("(?:none|adb|mtp|ptp|rndis|mass_storage|accessory|audio_source|midi|ncm|acm|diag|serial|rmnet|qdss)(?:,(?:none|adb|mtp|ptp|rndis|mass_storage|accessory|audio_source|midi|ncm|acm|diag|serial|rmnet|qdss))*")
                ? value : "unsupported_value";
    }

    private static String flagValue(String value) {
        if (value == null || value.length() == 0) return "unset";
        return value.equals("0") || value.equals("1") ? value : "unsupported_value";
    }

    private static void error(StringBuilder result, String key, Throwable error) {
        Throwable cause = error instanceof InvocationTargetException && error.getCause() != null ? error.getCause() : error;
        String name = cause.getClass().getSimpleName();
        add(result, key + ".error=" + (name.length() <= 64 && name.matches("[A-Za-z][A-Za-z0-9_$]{0,63}") ? name : "unavailable"));
    }

    private static void add(StringBuilder result, String line) {
        if (result.length() + line.length() + 1 <= MAX_RESULT) result.append(line).append('\n');
    }
}
