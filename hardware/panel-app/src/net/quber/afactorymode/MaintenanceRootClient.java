package net.quber.afactorymode;

import android.content.Context;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.system.Os;
import android.system.OsConstants;
import android.system.StructStat;
import java.io.File;
import org.json.JSONObject;

final class MaintenanceRootClient {
    enum Mode { STATUS, START_UPDATE, CONFIGURE_HOME, RESTORE_HOME, REPAIR_HOME }

    static JSONObject call(Context context, Mode mode) throws Exception {
        if (context == null || mode == null) throw new IllegalArgumentException("maintenance_arguments");
        String apk = context.getApplicationInfo().sourceDir;
        SystemBackupProtocol.requirePanelApk(apk);
        if (!apk.equals(new File(apk).getCanonicalPath())) throw new IllegalArgumentException("helper_path");
        StructStat node = Os.lstat(apk);
        if (!OsConstants.S_ISREG(node.st_mode) || (node.st_uid != 0 && node.st_uid != 1000)
                || (node.st_mode & 0022) != 0 || node.st_size < 1 || node.st_size > 2 * 1024 * 1024) {
            throw new IllegalArgumentException("helper_file");
        }
        String own = context.getPackageName();
        if (!MaintenancePolicy.PACKAGE.equals(own) && !MaintenancePolicy.BOOTSTRAP_PACKAGE.equals(own)) {
            throw new IllegalArgumentException("helper_package");
        }
        PackageInfo installed = context.getPackageManager().getPackageInfo(own, PackageManager.GET_SIGNATURES);
        if (installed.signatures == null || installed.signatures.length != 1
                || !MaintenanceSignerPolicy.accepts(own, installed.versionCode,
                        MaintenanceTrust.sha256(installed.signatures[0].toByteArray()))) {
            throw new IllegalArgumentException("helper_signature");
        }
        SystemBackupProtocol.Helper helper = mode == Mode.STATUS ? SystemBackupProtocol.Helper.MAINTENANCE_STATUS
                : mode == Mode.START_UPDATE ? SystemBackupProtocol.Helper.MAINTENANCE_START_UPDATE
                : mode == Mode.CONFIGURE_HOME ? SystemBackupProtocol.Helper.MAINTENANCE_CONFIGURE_HOME
                : mode == Mode.REPAIR_HOME ? SystemBackupProtocol.Helper.MAINTENANCE_REPAIR_HOME
                : SystemBackupProtocol.Helper.MAINTENANCE_RESTORE_HOME;
        try (AdbNetworkSetup.BackupAccess access = AdbNetworkSetup.beginBackup(context)) {
            SystemBackupSource.Result transferred = access.maintenance(apk, helper);
            if (!transferred.propertyCleared || !"ok".equals(transferred.status)
                    || transferred.data.length < 2 || transferred.data.length > 8192) {
                throw new IllegalStateException("maintenance_transfer");
            }
            JSONObject result = new JSONObject(new String(transferred.data, MaintenancePolicy.UTF8));
            String status = result.getString("status"), reason = result.getString("reason");
            if (!status.matches("ok|accepted|error") || !reason.matches("[a-z][a-z0-9_]{0,79}")) {
                throw new IllegalArgumentException("maintenance_result");
            }
            if (result.has("installedVersion") && result.getInt("installedVersion") < 0) throw new IllegalArgumentException("maintenance_version");
            if (result.has("home") && !result.getString("home").matches("panel|other|unknown")) throw new IllegalArgumentException("maintenance_home");
            if (result.has("workerStatus") && !result.getString("workerStatus").matches("idle|installing|installed|error|indeterminate")) throw new IllegalArgumentException("maintenance_worker");
            if (result.has("releaseId") && !result.getString("releaseId").matches("|[0-9a-f]{32}")) throw new IllegalArgumentException("maintenance_release");
            return result;
        }
    }
}
