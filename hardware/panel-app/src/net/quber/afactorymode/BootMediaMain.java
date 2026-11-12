package net.quber.afactorymode;

import android.os.Process;
import android.util.Base64;
import org.json.JSONObject;

public final class BootMediaMain {
    private BootMediaMain() { }

    public static void main(String[] args) {
        String output;
        int callerUid = Process.myUid();
        try {
            String status = "error";
            String reason;
            byte[] data = new byte[0];
            BootMediaPolicy.Evidence initial = new BootMediaPolicy.Evidence(callerUid);
            String evidence;
            if (args == null || args.length != 0) {
                reason = "helper_args_invalid";
                initial.stage(BootMediaPolicy.Stage.ARGUMENT_GUARD);
                evidence = initial.snapshot();
            } else if (callerUid != 0) {
                reason = "helper_uid_invalid";
                initial.stage(BootMediaPolicy.Stage.ROOT_GUARD);
                evidence = initial.snapshot();
            } else {
                BootMediaProbe.Result result = BootMediaProbe.readSynchronousForHelper();
                status = result.status;
                reason = result.reason;
                data = result.data;
                evidence = result.evidence;
            }
            JSONObject report = new JSONObject();
            report.put("version", 1);
            report.put("status", status);
            report.put("reason", reason);
            report.put("data", Base64.encodeToString(data, Base64.NO_WRAP));
            report.put("evidence", evidence);
            output = report.toString();
        } catch (Exception error) {
            output = failure(callerUid);
        } catch (LinkageError error) {
            output = failure(callerUid);
        }
        System.out.println(output);
    }

    private static String failure(int callerUid) {
        return "{\"version\":1,\"status\":\"error\",\"reason\":\"helper_failed\",\"data\":\"\","
                + "\"evidence\":\"caller_uid=" + callerUid + "\\nstage=helper_failed\\n\"}";
    }
}
