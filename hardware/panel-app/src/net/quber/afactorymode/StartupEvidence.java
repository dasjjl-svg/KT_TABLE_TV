package net.quber.afactorymode;

import android.app.ActivityManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.os.SystemClock;
import android.system.ErrnoException;
import android.system.Os;
import android.system.OsConstants;
import android.system.StructStat;
import java.io.FileDescriptor;
import java.io.FileInputStream;
import java.lang.reflect.Method;
import java.util.List;
import org.json.JSONArray;
import org.json.JSONObject;

final class StartupEvidence {
    private static final String KT = "com.kt.gigagenie.launcher";
    private static final String[] PACKAGES = {MaintenancePolicy.PACKAGE, MaintenancePolicy.BOOTSTRAP_PACKAGE, KT};
    private static final String[] PROPERTIES = {"sys.boot_completed", "persist.sys.qb.enable", "persist.sys.qb.flag",
            "persist.sys.firstboot.flag", "ro.boot.bootreason"};
    private final StringBuilder errors = new StringBuilder();

    static JSONObject collect(Context context) throws Exception {
        return new StartupEvidence().read(context);
    }

    private JSONObject read(Context context) throws Exception {
        MaintenanceRoot.checkTime();
        JSONObject result = new JSONObject();
        result.put("uptimeMs", SystemClock.elapsedRealtime());
        result.put("bootId", bootId());
        result.put("properties", properties());
        PackageManager manager = context.getPackageManager();
        JSONArray packages = new JSONArray();
        for (int index = 0; index < PACKAGES.length; index++) packages.put(packageInfo(manager, index));
        result.put("packages", packages);
        JSONArray components = new JSONArray();
        for (int index = 0; index < 4; index++) components.put(componentInfo(manager, index));
        result.put("components", components);
        ActivityManager activities = (ActivityManager) context.getSystemService(Context.ACTIVITY_SERVICE);
        result.put("topActivities", topActivities(activities));
        result.put("factoryMarker", factoryMarker());
        result.put("serviceComponents", serviceComponents(activities));
        result.put("error", errors.toString());
        if (result.toString().getBytes(MaintenancePolicy.UTF8).length > 4096) throw new IllegalStateException("startup_evidence_size");
        MaintenanceRoot.checkTime();
        return result;
    }

    private String bootId() {
        try {
            FileDescriptor descriptor = Os.open("/proc/sys/kernel/random/boot_id",
                    OsConstants.O_RDONLY | OsConstants.O_NOFOLLOW | MaintenanceRoot.closeOnExec(), 0);
            try (FileInputStream input = new FileInputStream(descriptor)) {
                String value = new String(MaintenancePolicy.read(input, 64), MaintenancePolicy.UTF8).trim();
                if (validBootId(value)) return value;
            }
        } catch (Exception unavailable) { }
        error("boot_id_unavailable");
        return "unknown";
    }

    static boolean validBootId(String value) {
        return value != null && value.matches("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}");
    }

    private JSONObject properties() throws Exception {
        JSONObject result = new JSONObject();
        Method getter = null;
        try { getter = Class.forName("android.os.SystemProperties").getMethod("get", String.class, String.class); }
        catch (Exception unavailable) { error("properties_unavailable"); }
        for (String key : PROPERTIES) {
            String value = "unknown";
            if (getter != null) {
                try { value = text((String) getter.invoke(null, key, "unknown"), 128, "property_value"); }
                catch (Exception unavailable) { error("property_unavailable"); }
            }
            result.put(key, value);
        }
        return result;
    }

    private JSONObject packageInfo(PackageManager manager, int index) throws Exception {
        String name = PACKAGES[index];
        JSONObject result = new JSONObject();
        result.put("name", name); result.put("installed", false); result.put("versionCode", -1);
        result.put("firstInstallTime", -1L); result.put("lastUpdateTime", -1L);
        result.put("enabledState", -1); result.put("sourceDir", "unknown");
        result.put("flags", -1); result.put("bootPermissionGranted", false);
        try {
            PackageInfo info = manager.getPackageInfo(name, 0);
            result.put("installed", true);
            result.put("versionCode", info.versionCode >= 0 ? info.versionCode : -1);
            result.put("firstInstallTime", Math.max(-1L, info.firstInstallTime));
            result.put("lastUpdateTime", Math.max(-1L, info.lastUpdateTime));
            result.put("sourceDir", text(info.applicationInfo == null ? null : info.applicationInfo.sourceDir, 256, "package_path"));
            result.put("flags", info.applicationInfo == null ? -1 : info.applicationInfo.flags);
            result.put("bootPermissionGranted", manager.checkPermission("android.permission.RECEIVE_BOOT_COMPLETED", name)
                    == PackageManager.PERMISSION_GRANTED);
            try { result.put("enabledState", state(manager.getApplicationEnabledSetting(name))); }
            catch (Exception unavailable) { error("package_state_unavailable"); }
        } catch (PackageManager.NameNotFoundException absent) { }
        catch (Exception unavailable) { error("package_" + index + "_unavailable"); }
        return result;
    }

    private JSONObject componentInfo(PackageManager manager, int index) throws Exception {
        ComponentName component = index == 3 ? new ComponentName(KT, KT + ".ui.GreetingActivity")
                : new ComponentName(MaintenancePolicy.PACKAGE, index == 0 ? MaintenancePolicy.ACTIVITY
                        : "net.quber.afactorymode." + (index == 1 ? "MaintenanceService" : "PanelBootReceiver"));
        int enabled = -1;
        try {
            if (index == 1) manager.getServiceInfo(component, PackageManager.GET_DISABLED_COMPONENTS);
            else if (index == 2) manager.getReceiverInfo(component, PackageManager.GET_DISABLED_COMPONENTS);
            else manager.getActivityInfo(component, PackageManager.GET_DISABLED_COMPONENTS);
            enabled = state(manager.getComponentEnabledSetting(component));
        } catch (PackageManager.NameNotFoundException absent) { }
        catch (Exception unavailable) { error("component_" + index + "_unavailable"); }
        JSONObject result = new JSONObject();
        result.put("name", component.flattenToString()); result.put("state", enabled);
        return result;
    }

    private JSONArray topActivities(ActivityManager manager) {
        JSONArray result = new JSONArray();
        try {
            if (manager == null) throw new IllegalStateException();
            List<ActivityManager.RunningTaskInfo> tasks = manager.getRunningTasks(5);
            if (tasks == null) throw new IllegalStateException();
            for (ActivityManager.RunningTaskInfo task : tasks) {
                if (result.length() >= 5) { error("tasks_truncated"); break; }
                if (task == null || task.topActivity == null) continue;
                String name = text(task.topActivity.flattenToString(), 256, "task_name");
                if (!"unknown".equals(name)) result.put(name);
            }
        } catch (Exception unavailable) { error("tasks_unavailable"); }
        return result;
    }

    private JSONObject factoryMarker() throws Exception {
        JSONObject result = new JSONObject();
        result.put("exists", false); result.put("uid", -1); result.put("mode", -1); result.put("size", -1L);
        try {
            StructStat stat = Os.lstat("/data/_pref/user_factory");
            result.put("exists", true); result.put("uid", stat.st_uid);
            result.put("mode", stat.st_mode & 0xffff); result.put("size", Math.max(-1L, stat.st_size));
        } catch (ErrnoException unavailable) {
            if (unavailable.errno != OsConstants.ENOENT) error("factory_marker_unavailable");
        } catch (Exception unavailable) { error("factory_marker_unavailable"); }
        return result;
    }

    private JSONArray serviceComponents(ActivityManager manager) {
        JSONArray result = new JSONArray();
        try {
            if (manager == null) throw new IllegalStateException();
            List<ActivityManager.RunningServiceInfo> services = manager.getRunningServices(64);
            if (services == null) throw new IllegalStateException();
            for (ActivityManager.RunningServiceInfo service : services) {
                if (service == null || service.service == null || !allowedPackage(service.service.getPackageName())) continue;
                if (result.length() >= 16) { error("services_truncated"); break; }
                String name = text(service.service.flattenToString(), 256, "service_name");
                if (!"unknown".equals(name)) result.put(name);
            }
        } catch (Exception unavailable) { error("services_unavailable"); }
        return result;
    }

    private static boolean allowedPackage(String value) {
        for (String name : PACKAGES) if (name.equals(value)) return true;
        return "com.kt.gigagenie.upgrade".equals(value);
    }

    private int state(int value) {
        if (value >= 0 && value <= 4) return value;
        error("enabled_state_invalid"); return -1;
    }

    private String text(String value, int maximum, String reason) {
        boolean valid = value != null && value.length() <= maximum;
        if (valid) {
            for (int index = 0; index < value.length(); index++) {
                char character = value.charAt(index);
                if (character < 32 || character == 127) { valid = false; break; }
            }
        }
        if (valid) return value;
        error(reason); return "unknown";
    }

    private void error(String reason) {
        int extra = reason.length() + (errors.length() == 0 ? 0 : 1);
        if (errors.length() + extra > 120) return;
        if (errors.length() > 0) errors.append(',');
        errors.append(reason);
    }
}
