package net.quber.afactorymode;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.SystemClock;
import android.provider.Settings;

final class PanelReturnLauncher {
    private static final long LAUNCH_COOLDOWN_MS = 1500L;
    private static final Object GATE = new Object();
    private static volatile boolean panelForeground;
    private static volatile Runnable backgroundListener;
    private static long lastLaunchAt;

    private PanelReturnLauncher() {}

    static void setForeground(boolean foreground) {
        boolean changedToBackground = panelForeground && !foreground;
        panelForeground = foreground;
        if (changedToBackground) {
            Runnable listener = backgroundListener;
            if (listener != null) {
                try { listener.run(); } catch (RuntimeException ignored) { }
            }
        }
    }

    static boolean isForeground() {
        return panelForeground;
    }

    static void setBackgroundListener(Runnable listener) {
        backgroundListener = listener;
    }

    static boolean isAccessibilityServiceEnabled(Context context) {
        try {
            String enabled = Settings.Secure.getString(context.getContentResolver(),
                    Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
            int global = Settings.Secure.getInt(context.getContentResolver(),
                    Settings.Secure.ACCESSIBILITY_ENABLED, 0);
            return global == 1 && ReturnTriggerPolicy.containsAccessibilityService(enabled,
                    ReturnTriggerPolicy.RETURN_SERVICE_COMPONENT);
        } catch (RuntimeException unavailable) {
            return false;
        }
    }

    static boolean bringToFront(Context context, String reason) {
        return bringToFront(context, reason, false);
    }

    static boolean bringToFront(Context context, String reason, boolean forceWhenForeground) {
        if (context == null || !MaintenancePolicy.PACKAGE.equals(context.getPackageName())) return false;
        SharedPreferences prefs = context.getSharedPreferences("maintenance", Context.MODE_PRIVATE);
        if (!prefs.getBoolean("enabled", true)) return false;
        if (!forceWhenForeground && panelForeground) return false;
        long now = SystemClock.elapsedRealtime();
        synchronized (GATE) {
            if (lastLaunchAt > 0L && now >= lastLaunchAt && now - lastLaunchAt < LAUNCH_COOLDOWN_MS) {
                return false;
            }
            lastLaunchAt = now;
        }
        try {
            Intent panel = new Intent(Intent.ACTION_MAIN);
            panel.addCategory(Intent.CATEGORY_LAUNCHER);
            panel.setComponent(new ComponentName(MaintenancePolicy.PACKAGE, MaintenancePolicy.ACTIVITY));
            panel.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                    | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED
                    | Intent.FLAG_ACTIVITY_SINGLE_TOP
                    | Intent.FLAG_ACTIVITY_CLEAR_TOP
                    | Intent.FLAG_ACTIVITY_REORDER_TO_FRONT);
            context.startActivity(panel);
            prefs.edit()
                    .putString("lastReturnTrigger", ReturnTriggerPolicy.safeTriggerLabel(reason))
                    .putLong("lastReturnAt", System.currentTimeMillis())
                    .apply();
            return true;
        } catch (RuntimeException unavailable) {
            return false;
        }
    }
}
