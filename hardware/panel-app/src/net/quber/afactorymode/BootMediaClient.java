package net.quber.afactorymode;

import android.content.Context;
import android.os.Process;
import android.system.Os;
import android.system.OsConstants;
import android.system.StructStat;
import android.util.Base64;
import java.io.File;
import java.nio.charset.Charset;
import java.util.concurrent.TimeoutException;
import org.json.JSONObject;

final class BootMediaClient {
    private static final Charset UTF8 = Charset.forName("UTF-8");
    private static final Attempt BOOT = new Attempt(SystemBackupProtocol.Helper.BOOT_MEDIA);
    private static final Attempt SPI = new Attempt(SystemBackupProtocol.Helper.SPI_CONTROLLER);
    private static final Attempt IDENTITY = new Attempt(SystemBackupProtocol.Helper.SPI_IDENTITY);
    private static final Attempt READ_PROBE = new Attempt(SystemBackupProtocol.Helper.SPI_READ);
    private static final Attempt SPI_BACKUP = new Attempt(SystemBackupProtocol.Helper.SPI_BACKUP);

    static final class Result {
        final String status;
        final byte[] data;
        final String reason;
        final String evidence;

        Result(String status, byte[] data, String reason, String evidence) {
            this.status = status;
            this.data = data.clone();
            this.reason = reason;
            this.evidence = evidence;
        }
    }

    static Result read(Context context, AdbNetworkSetup.BackupAccess access) { return BOOT.read(context, access); }

    static Result readSpiController(Context context, AdbNetworkSetup.BackupAccess access) { return SPI.read(context, access); }

    static Result readSpiIdentity(Context context, AdbNetworkSetup.BackupAccess access) { return IDENTITY.read(context, access); }

    static Result readSpiSamples(Context context, AdbNetworkSetup.BackupAccess access) { return READ_PROBE.read(context, access); }

    static Result readSpiBackup(Context context, AdbNetworkSetup.BackupAccess access) { return SPI_BACKUP.read(context, access); }

    private static final class Attempt {
        private final SystemBackupProtocol.Helper helper;
        private boolean attempted;
        private Result saved;

        Attempt(SystemBackupProtocol.Helper helper) { this.helper = helper; }

        synchronized Result read(Context context, AdbNetworkSetup.BackupAccess access) {
            if (saved != null) return saved;
            if (attempted) return failed("helper_already_started", "");
            attempted = true;
            String evidence = "app_uid=" + Process.myUid() + "\n";
            try {
                String apk = context.getApplicationInfo().sourceDir;
                SystemBackupProtocol.requirePanelApk(apk);
                if (!apk.equals(new File(apk).getCanonicalPath())) {
                    return saved = failed("apk_path_changed", evidence);
                }
                StructStat file = Os.lstat(apk);
                evidence += "apk_uid=" + file.st_uid + "\napk_gid=" + file.st_gid
                        + "\napk_mode=" + Integer.toOctalString(file.st_mode) + "\n";
                if (!OsConstants.S_ISREG(file.st_mode) || (file.st_uid != 0 && file.st_uid != 1000)
                        || (file.st_mode & 0022) != 0 || file.st_size < 1 || file.st_size > 2L * 1024L * 1024L) {
                    return saved = failed("apk_identity_invalid", evidence);
                }
                StructStat launcher = Os.lstat("/system/bin/app_process32");
                if (!OsConstants.S_ISREG(launcher.st_mode) || launcher.st_uid != 0
                        || (launcher.st_mode & 0022) != 0 || (launcher.st_mode & 0100) == 0) {
                    return saved = failed("helper_launcher_invalid", evidence);
                }
                SystemBackupSource.Result transfer = helper == SystemBackupProtocol.Helper.SPI_READ ? access.readSpiSamples(apk)
                        : helper == SystemBackupProtocol.Helper.SPI_BACKUP ? access.readSpiBackup(apk)
                        : helper == SystemBackupProtocol.Helper.SPI_IDENTITY ? access.readSpiIdentity(apk)
                        : helper == SystemBackupProtocol.Helper.SPI_CONTROLLER ? access.readSpiController(apk) : access.readBootMedia(apk);
                if (!transfer.propertyCleared || !"ok".equals(transfer.status)) {
                    return saved = failed("helper_transfer_failed", evidence);
                }
                if (helper == SystemBackupProtocol.Helper.SPI_BACKUP) {
                    return saved = parseSpiBackup(transfer.data, evidence);
                }
                if (transfer.data.length < 2 || transfer.data.length > 8192) {
                    return saved = failed("helper_output_size", evidence);
                }
                JSONObject reply = new JSONObject(new String(transfer.data, UTF8));
                if (reply.length() != 5 || !(reply.get("version") instanceof Integer) || reply.getInt("version") != 1) {
                    return saved = failed("helper_output_shape", evidence);
                }
                Object statusValue = reply.get("status");
                Object reasonValue = reply.get("reason");
                Object dataValue = reply.get("data");
                Object evidenceValue = reply.get("evidence");
                if (!(statusValue instanceof String) || !(reasonValue instanceof String)
                        || !(dataValue instanceof String) || !(evidenceValue instanceof String)) {
                    return saved = failed("helper_output_shape", evidence);
                }
                String status = (String) statusValue;
                String reason = (String) reasonValue;
                String encoded = (String) dataValue;
                String detail = (String) evidenceValue;
                if (!status.matches("ok|absent|unreadable|error") || !reason.matches("[a-z][a-z0-9_]{0,79}")
                        || encoded.length() > 4096 || detail.length() > 2048 || !plain(detail)) {
                    return saved = failed("helper_output_fields", evidence);
                }
                byte[] data = Base64.decode(encoded, Base64.NO_WRAP);
                if (!encoded.equals(Base64.encodeToString(data, Base64.NO_WRAP))
                        || data.length > 2048 || (!"ok".equals(status) && data.length != 0)) {
                    return saved = failed("helper_output_data", evidence);
                }
                if (helper == SystemBackupProtocol.Helper.SPI_READ && "ok".equals(status) && data.length != 2048) {
                    return saved = failed("helper_output_data", evidence);
                }
                saved = new Result(status, data, reason, evidence + detail);
            } catch (TimeoutException timeout) {
                saved = failed("helper_timeout_no_retry", evidence);
            } catch (Exception error) {
                saved = failed("helper_failed_no_retry", evidence);
            }
            return saved;
        }
    }

    private static Result parseSpiBackup(byte[] wire, String evidence) throws Exception {
        if (wire.length < SpiBackupFrame.HEADER_SIZE + 1 || wire.length > SpiBackupFrame.MAX_WIRE) {
            return failed("helper_output_size", evidence);
        }
        SpiBackupFrame.Frame frame;
        try {
            frame = SpiBackupFrame.decode(wire);
        } catch (IllegalArgumentException invalid) {
            return failed("helper_output_data", evidence);
        }
        JSONObject reply = new JSONObject(new String(frame.report, UTF8));
        if (reply.length() != 4 || !(reply.get("version") instanceof Integer) || reply.getInt("version") != 1) {
            return failed("helper_output_shape", evidence);
        }
        Object statusValue = reply.get("status");
        Object reasonValue = reply.get("reason");
        Object evidenceValue = reply.get("evidence");
        if (!(statusValue instanceof String) || !(reasonValue instanceof String) || !(evidenceValue instanceof String)) {
            return failed("helper_output_shape", evidence);
        }
        String status = (String) statusValue;
        String reason = (String) reasonValue;
        String detail = (String) evidenceValue;
        if (!status.matches("ok|absent|unreadable|error") || !reason.matches("[a-z][a-z0-9_]{0,79}")
                || detail.length() > 2048 || !plain(detail)) {
            return failed("helper_output_fields", evidence);
        }
        if ("ok".equals(status) ? frame.payload.length != SpiBackupFrame.PAYLOAD_SIZE : frame.payload.length != 0) {
            return failed("helper_output_data", evidence);
        }
        return new Result(status, frame.payload, reason, evidence + detail);
    }

    private static boolean plain(String value) {
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c != '\n' && (c < 32 || c > 126)) return false;
        }
        return true;
    }

    private static Result failed(String reason, String evidence) {
        return new Result("error", new byte[0], reason, evidence);
    }
}
