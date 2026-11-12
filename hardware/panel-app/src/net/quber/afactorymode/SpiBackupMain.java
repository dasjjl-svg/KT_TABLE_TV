package net.quber.afactorymode;

import android.os.Process;
import java.io.FileDescriptor;
import java.io.FileOutputStream;
import java.nio.charset.Charset;
import org.json.JSONObject;

public final class SpiBackupMain {
    private static final Charset UTF8 = Charset.forName("UTF-8");
    private SpiBackupMain() { }

    public static void main(String[] args) {
        int callerUid = Process.myUid();
        byte[] report, payload = new byte[0];
        try {
            String status = "error", reason, evidence;
            if (args == null || args.length != 0) {
                reason = "helper_args_invalid";
                evidence = "caller_uid=" + callerUid + "\nstage=argument_guard\n";
            } else if (callerUid != 0) {
                reason = "helper_uid_invalid";
                evidence = "caller_uid=" + callerUid + "\nstage=root_guard\n";
            } else {
                SpiBackupProbe.Result result = SpiBackupProbe.readSynchronousForHelper();
                status = result.status; reason = result.reason;
                payload = result.data; evidence = result.evidence;
            }
            report = new JSONObject().put("version", 1).put("status", status)
                    .put("reason", reason).put("evidence", evidence).toString().getBytes(UTF8);
        } catch (Exception error) { report = failure(callerUid); payload = new byte[0]; }
        catch (LinkageError error) { report = failure(callerUid); payload = new byte[0]; }
        try { SpiBackupFrame.write(new FileOutputStream(FileDescriptor.out), report, payload); }
        catch (Exception error) { }
    }

    private static byte[] failure(int uid) {
        return ("{\"version\":1,\"status\":\"error\",\"reason\":\"helper_failed\","
                + "\"evidence\":\"caller_uid=" + uid + "\\nstage=helper_failed\\n\"}").getBytes(UTF8);
    }
}
