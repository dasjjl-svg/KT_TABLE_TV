package net.quber.afactorymode;

import android.os.Process;
import android.os.SystemClock;
import java.nio.charset.Charset;
import java.util.Map;

public final class SpiBackupProbe {
    private static final Charset UTF8 = Charset.forName("UTF-8");
    private static boolean started;
    private static Result completed;
    private static native byte[][] runBackup();

    public static final class Result {
        public final String status, reason, evidence;
        public final byte[] data;
        Result(String status, String reason, byte[] data, String evidence) {
            this.status = status; this.reason = reason; this.data = data; this.evidence = evidence;
        }
    }

    public static synchronized Result readSynchronousForHelper() {
        if (completed != null) return completed;
        if (started) return failed("backup_already_started", "stage=once_guard\n");
        started = true;
        String stage = "root_guard";
        try {
            if (Process.myUid() != 0) return completed = failed("helper_uid_invalid", "stage=" + stage + "\n");
            stage = "controller_preflight";
            SpiControllerProbe.Result controller = SpiControllerProbe.readSynchronousForHelper();
            if (!"ok".equals(controller.status) || !"register_snapshots_read".equals(controller.reason)) {
                return completed = failed("backup_preflight_failed", "stage=" + stage + "\npreflight_reason=" + controller.reason + "\n");
            }
            SpiIdentityPolicy.requirePreflight(new String(controller.data, UTF8), controller.evidence);
            stage = "native_library";
            System.load(SpiIdentityProbe.verifiedLibrary());
            stage = "native_backup";
            long start = SystemClock.elapsedRealtime();
            byte[][] reply = runBackup();
            long duration = SystemClock.elapsedRealtime() - start;
            if (reply == null || reply.length != 2 || reply[0] == null || reply[1] == null
                    || reply[0].length < 1 || reply[0].length > 1500
                    || (reply[1].length != 0 && reply[1].length != SpiBackupFrame.PAYLOAD_SIZE)) {
                throw new IllegalStateException("native_reply_shape");
            }
            String text = new String(reply[0], UTF8);
            Map<String, String> report = SpiBackupPolicy.nativeReport(text, reply[1]);
            String evidence = "caller_uid=0\nstage=complete\nnative_sha256=" + SpiIdentityBuildConfig.SHA256
                    + "\nnative_duration_ms=" + duration + "\n" + text;
            if ("ok".equals(report.get("status"))) {
                return completed = new Result("ok", report.get("reason"), reply[1], evidence);
            }
            return completed = failed(report.get("reason"), "stage=native_backup\nnative_duration_ms=" + duration + "\n" + text);
        } catch (Exception error) {
            String detail = "stage=" + stage + "\n";
            String message = error.getMessage();
            if (message != null && message.matches("[a-z0-9_]{1,120}")) detail += "failure_detail=" + message + "\n";
            return completed = failed("backup_failed_no_retry", detail);
        } catch (LinkageError error) {
            return completed = failed("native_library_unavailable", "stage=" + stage + "\n");
        }
    }

    private static Result failed(String reason, String detail) {
        return new Result("error", reason, new byte[0], "caller_uid=" + Process.myUid() + "\n" + detail);
    }
}
