package net.quber.afactorymode;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.Charset;
import java.util.concurrent.TimeoutException;

public final class ServiceInspectionRunnerTest {
    private static int checks;

    public static void main(String[] args) {
        Fake complete = new Fake();
        String result = ServiceInspectionRunner.run(complete);
        expect(result, "result=inspection_complete");
        expect(result, "identity.scope=service_spawned_shell");
        expect(result, "uid=0,0,0,0");
        expect(result, "diagnostic.clear_verified=true");
        calls(complete, 6, 6);

        Fake busy = new Fake();
        busy.value = "someone_else";
        expect(ServiceInspectionRunner.run(busy), "result=blocked_existing_diagnostic_value");
        calls(busy, 0, 0);

        Fake timeout = new Fake();
        timeout.queryTimeout = true;
        result = ServiceInspectionRunner.run(timeout);
        expect(result, "result=indeterminate_binder_timeout_no_further_calls");
        expect(result, "diagnostic.clear_verified=false");
        calls(timeout, 1, 0);

        Fake foreign = new Fake();
        foreign.foreign = true;
        result = ServiceInspectionRunner.run(foreign);
        expect(result, "result=indeterminate_foreign_result_no_clear");
        if (!"other_nonce:0,0,0,0".equals(foreign.value)) throw new AssertionError("foreign_value_modified");
        calls(foreign, 1, 0);

        Fake race = new Fake();
        race.changeBeforeClear = true;
        expect(ServiceInspectionRunner.run(race), "result=indeterminate_value_changed_no_clear");
        calls(race, 1, 0);

        Fake invalid = new Fake();
        invalid.invalid = true;
        result = ServiceInspectionRunner.run(invalid);
        expect(result, "result=failed_invalid_result_cleared");
        expect(result, "diagnostic.clear_verified=true");
        if (result.contains("untrusted")) throw new AssertionError("invalid_value_logged");
        calls(invalid, 1, 1);

        Fake empty = new Fake();
        empty.emptyPayload = true;
        result = ServiceInspectionRunner.run(empty);
        expect(result, "result=failed_invalid_result_cleared");
        expect(result, "uid.payload_chars=0");
        expect(result, "uid.payload_empty=true");
        expect(result, "diagnostic.clear_verified=true");
        calls(empty, 1, 1);

        Fake refused = new Fake();
        refused.noResult = true;
        result = ServiceInspectionRunner.run(refused);
        expect(result, "result=failed_query_no_result");
        expect(result, "diagnostic.clear_verified=true");
        calls(refused, 1, 0);

        Fake unclear = new Fake();
        unclear.queryStatus = 256;
        expect(ServiceInspectionRunner.run(unclear), "result=indeterminate_query_status_no_further_calls");
        calls(unclear, 1, 1);

        Fake clearFailure = new Fake();
        clearFailure.clearFails = true;
        result = ServiceInspectionRunner.run(clearFailure);
        expect(result, "result=failed_clear_no_further_calls");
        expect(result, "diagnostic.clear_verified=false");
        calls(clearFailure, 1, 1);

        Fake clearTimeout = new Fake();
        clearTimeout.clearTimeout = true;
        result = ServiceInspectionRunner.run(clearTimeout);
        expect(result, "result=indeterminate_binder_timeout_no_further_calls");
        expect(result, "phase=uid.clear");
        calls(clearTimeout, 1, 1);

        Fake clearStatus = new Fake();
        clearStatus.clearStatus = 256;
        result = ServiceInspectionRunner.run(clearStatus);
        expect(result, "result=indeterminate_clear_status_no_further_calls");
        expect(result, "diagnostic.clear_verified=true");
        calls(clearStatus, 1, 1);

        Fake interrupted = new Fake();
        interrupted.interrupt = true;
        expect(ServiceInspectionRunner.run(interrupted), "result=indeterminate_interrupted_no_further_calls");
        if (!Thread.interrupted()) throw new AssertionError("interrupt_lost");
        calls(interrupted, 1, 0);
        System.out.println("ServiceInspectionRunnerTest passed: " + checks + " checks");
    }

    private static final class Fake implements ServiceInspectionRunner.Channel {
        String value = "";
        int queries, clears, readsAfterWrite;
        int queryStatus, clearStatus;
        boolean queryTimeout, clearTimeout, foreign, changeBeforeClear, invalid, noResult, clearFails, interrupt, emptyPayload;

        public String read() {
            if (queries > 0 && ++readsAfterWrite == 2 && changeBeforeClear) value = "other_nonce:changed";
            return value;
        }

        public int write(byte[] request) throws Exception {
            ByteBuffer buffer = ByteBuffer.wrap(request).order(ByteOrder.LITTLE_ENDIAN);
            if (!ServiceInspectionProtocol.KEY.equals(readString(buffer))) throw new AssertionError("unexpected_key");
            String wireValue = readString(buffer);
            if (buffer.hasRemaining()) throw new AssertionError("extra_data");
            if ("\"\"".equals(wireValue)) {
                clears++;
                if (clearTimeout) throw new TimeoutException();
                if (!clearFails) value = "";
                return clearStatus;
            }
            queries++;
            readsAfterWrite = 0;
            if (queryTimeout) throw new TimeoutException();
            if (interrupt) throw new InterruptedException();
            String nonce = wireValue.substring(1, 9);
            if (!nonce.matches("[0-9a-f]{8}")) throw new AssertionError("bad_nonce");
            String[] values = {"0,0,0,0", "0000003fffffffff", "ok,adbd,/sbin/adbd", "unreadable", "no_entry", "p11,absent,absent"};
            value = noResult ? "" : foreign ? "other_nonce:0,0,0,0"
                    : nonce + ":" + (emptyPayload ? "" : invalid ? "untrusted\nvalue" : values[queries - 1]);
            return queryStatus;
        }
    }

    private static String readString(ByteBuffer input) {
        int length = input.getInt();
        byte[] value = new byte[length];
        input.get(value);
        if (length > 0) {
            if (input.get() != 0) throw new AssertionError("missing_nul");
            while ((input.position() & 3) != 0) if (input.get() != 0) throw new AssertionError("bad_padding");
        }
        return new String(value, Charset.forName("UTF-8"));
    }

    private static void calls(Fake fake, int queries, int clears) {
        checks++;
        if (fake.queries != queries || fake.clears != clears) throw new AssertionError("extra_or_missing_calls");
    }

    private static void expect(String output, String line) {
        checks++;
        if (!output.contains(line + "\n")) throw new AssertionError("missing: " + line + "\n" + output);
    }
}
