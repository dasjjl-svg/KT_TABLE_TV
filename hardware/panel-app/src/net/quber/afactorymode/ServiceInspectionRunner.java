package net.quber.afactorymode;

import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.TimeoutException;

final class ServiceInspectionRunner {
    interface Channel {
        String read() throws Exception;
        int write(byte[] request) throws Exception;
    }

    private ServiceInspectionRunner() {}

    static String run(Channel channel) {
        StringBuilder result = new StringBuilder();
        String phase = "preflight";
        boolean clearVerified = false;
        try {
            if (!"".equals(channel.read())) return finish(result, "blocked_existing_diagnostic_value", clearVerified);
            clearVerified = true;
            line(result, "identity.scope=service_spawned_shell");
            for (ServiceInspectionProtocol.Query query : ServiceInspectionProtocol.Query.values()) {
                String label = query.name().toLowerCase(Locale.US);
                phase = label + ".preflight";
                if (!"".equals(channel.read())) return finish(result, "blocked_diagnostic_value_changed", false);
                String nonce = UUID.randomUUID().toString().replace("-", "").substring(0, 8);
                phase = label + ".query";
                clearVerified = false;
                int status = channel.write(ServiceInspectionProtocol.query(query, nonce));
                line(result, label + ".raw_status=" + status);
                phase = label + ".readback";
                String observed = channel.read();
                if ("".equals(observed)) {
                    clearVerified = true;
                    return finish(result, "failed_query_no_result", clearVerified);
                }
                if (observed == null || !observed.startsWith(nonce + ":")) {
                    return finish(result, "indeterminate_foreign_result_no_clear", clearVerified);
                }
                String parsed = null;
                try { parsed = ServiceInspectionProtocol.parse(query, nonce, observed); }
                catch (IllegalArgumentException error) {
                    line(result, label + ".payload=invalid");
                    line(result, label + ".payload_chars=" + (observed.length() - 9));
                    line(result, label + ".payload_empty=" + (observed.length() == 9));
                }
                if (parsed != null) line(result, label + "=" + parsed);
                phase = label + ".clear";
                if (!observed.equals(channel.read())) return finish(result, "indeterminate_value_changed_no_clear", clearVerified);
                int clearStatus = channel.write(ServiceInspectionProtocol.clear());
                clearVerified = "".equals(channel.read());
                line(result, label + ".clear_verified=" + clearVerified);
                if (clearStatus != 0) {
                    line(result, label + ".clear_raw_status=" + clearStatus);
                    return finish(result, "indeterminate_clear_status_no_further_calls", clearVerified);
                }
                if (!clearVerified) return finish(result, "failed_clear_no_further_calls", clearVerified);
                if (status != 0) return finish(result, "indeterminate_query_status_no_further_calls", clearVerified);
                if (parsed == null) return finish(result, "failed_invalid_result_cleared", clearVerified);
            }
            return finish(result, "inspection_complete", clearVerified);
        } catch (TimeoutException error) {
            line(result, "phase=" + phase);
            line(result, "binder.call_may_still_complete=true");
            return finish(result, "indeterminate_binder_timeout_no_further_calls", clearVerified);
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            line(result, "phase=" + phase);
            return finish(result, "indeterminate_interrupted_no_further_calls", clearVerified);
        } catch (Exception error) {
            line(result, "phase=" + phase);
            line(result, "error=" + error.getClass().getSimpleName());
            return finish(result, "indeterminate_error_no_further_calls", clearVerified);
        }
    }

    private static void line(StringBuilder result, String value) {
        if (result.length() + value.length() + 1 < 2000) result.append(value).append('\n');
    }

    private static String finish(StringBuilder result, String state, boolean clearVerified) {
        line(result, "diagnostic.clear_verified=" + clearVerified);
        line(result, "result=" + state);
        return result.toString();
    }
}
