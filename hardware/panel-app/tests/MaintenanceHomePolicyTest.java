package net.quber.afactorymode;

import java.util.ArrayList;
import java.util.List;

public final class MaintenanceHomePolicyTest {
    private static int checks;

    private static final class Mock implements MaintenanceHomePolicy.Access {
        MaintenanceHomePolicy.State saved;
        int enabled;
        boolean panelPreference;
        boolean externalPreference;
        boolean resolveFailure;
        boolean trusted = true;
        boolean resolver = true;
        boolean candidatesUnchanged = true;
        boolean foreignAfterPrepare;
        boolean foreignAfterWrite;
        boolean repairAttempted;
        String failure = "";
        boolean failAfter;
        final List<String> events = new ArrayList<String>();

        void event(String name, boolean after) throws Exception {
            if (!after) events.add(name);
            if (failure.equals(name) && failAfter == after) throw new Exception(name + "_failed");
        }
        public MaintenanceHomePolicy.State load() throws Exception { event("load", false); return saved; }
        public MaintenanceHomePolicy.State capture() throws Exception {
            event("capture", false);
            if (externalPreference) throw new Exception("prior_home_preference");
            return new MaintenanceHomePolicy.State(enabled, "prepared");
        }
        public void save(MaintenanceHomePolicy.State state) throws Exception {
            event("save_" + state.phase, false);
            saved = state;
            event("save_" + state.phase, true);
        }
        public void requireOriginal(MaintenanceHomePolicy.State state) throws Exception {
            event("original", false);
            if (enabled != state.enabled || panelPreference || externalPreference) throw new Exception("home_original_changed");
        }
        public void disableGreeting(MaintenanceHomePolicy.State state) throws Exception {
            event("disable", false);
            expect(saved != null && saved.phase.equals("prepared"), "baseline_before_disable");
            enabled = 2;
            event("disable", true);
        }
        public void addPanelPreference() throws Exception {
            event("prefer", false);
            expect(enabled == 2, "disable_before_prefer");
            panelPreference = true;
            event("prefer", true);
        }
        public boolean isPanelHome() throws Exception {
            event("panel", false);
            return enabled == 2 && panelPreference && !resolveFailure && !externalPreference;
        }
        public void restoreGreeting(MaintenanceHomePolicy.State state) throws Exception {
            event("restore", false);
            enabled = state.enabled;
            event("restore", true);
        }
        public void removePanelPreference() throws Exception {
            event("clear", false);
            expect(enabled == saved.enabled, "fallback_before_clear");
            if (externalPreference) throw new Exception("panel_preferences_changed");
            panelPreference = false;
            event("clear", true);
        }
        public boolean isOriginalHome(MaintenanceHomePolicy.State state) throws Exception {
            event("restored", false);
            return enabled == state.enabled && !panelPreference && !externalPreference;
        }
        public void requireRepairable(MaintenanceHomePolicy.State state) throws Exception {
            event("repair_gate", false);
            if (!trusted) throw new Exception("panel_not_installed");
            MaintenanceHomePolicy.requireRepairState(state, enabled,
                    (panelPreference ? 1 : 0) + (externalPreference ? 1 : 0), panelPreference ? 1 : 0,
                    resolver, candidatesUnchanged);
        }
        public void addRepairPreference(MaintenanceHomePolicy.State state) throws Exception {
            event("repair_prefer", false);
            if (foreignAfterPrepare) externalPreference = true;
            requireRepairable(state);
            repairAttempted = true;
            panelPreference = true;
            event("repair_prefer", true);
        }
        public void verifyRepair(MaintenanceHomePolicy.State state) throws Exception {
            event("repair_verify", false);
            if (foreignAfterWrite) externalPreference = true;
            if (!isPanelHome()) throw new Exception("home_repair_verify_failed");
        }
        public void rollbackRepair(MaintenanceHomePolicy.State state) throws Exception {
            event("repair_clear", false);
            if (repairAttempted) panelPreference = false;
            event("repair_clear", true);
        }
    }

    public static void main(String[] args) throws Exception {
        for (int original : new int[]{0, 1}) {
            Mock mock = new Mock(); mock.enabled = original;
            result(MaintenanceHomePolicy.run(mock, true), "ok", "home_configured");
            expect(mock.saved.enabled == original && "active".equals(mock.saved.phase), "original_state_preserved");
            expect(mock.enabled == 2 && mock.panelPreference, "configured");
            int mutations = count(mock.events, "disable");
            result(MaintenanceHomePolicy.run(mock, true), "ok", "home_already_configured");
            expect(count(mock.events, "disable") == mutations, "repeat_no_mutation");
            result(MaintenanceHomePolicy.run(mock, false), "ok", "home_restored");
            expect(mock.enabled == original && !mock.panelPreference && mock.saved.phase.equals("restored"), "restored_exact");
            result(MaintenanceHomePolicy.run(mock, true), "ok", "home_configured");
            expect(mock.saved.enabled == original, "reconfigure_preserves_baseline");
        }
        Mock absent = new Mock();
        result(MaintenanceHomePolicy.run(absent, false), "ok", "home_not_configured");
        expect(absent.events.size() == 1, "absent_restore_no_mutation");

        for (String step : new String[]{"load", "capture", "original", "save_prepared"}) {
            for (boolean after : new boolean[]{false, true}) {
                if (after && !step.equals("save_prepared")) continue;
                Mock mock = new Mock(); mock.failure = step; mock.failAfter = after;
                expect(MaintenanceHomePolicy.run(mock, true).status.equals("error"), "preparation_failure");
                expect(mock.enabled == 0 && !mock.panelPreference && count(mock.events, "disable") == 0, "preparation_no_mutation");
            }
        }
        Mock prior = new Mock(); prior.externalPreference = true;
        result(MaintenanceHomePolicy.run(prior, true), "error", "prior_home_preference");
        expect(prior.saved == null && prior.enabled == 0 && !prior.panelPreference, "prior_preserved");

        for (String step : new String[]{"disable", "prefer", "panel", "save_active"}) {
            for (boolean after : new boolean[]{false, true}) {
                if (after && step.equals("panel")) continue;
                Mock mock = new Mock(); mock.enabled = 1; mock.failure = step; mock.failAfter = after;
                expect(MaintenanceHomePolicy.run(mock, true).status.equals("error"), "mutation_failure");
                expect(mock.enabled == 1 && !mock.panelPreference && mock.saved.phase.equals("restored"), "mutation_rollback");
            }
        }
        Mock resolver = new Mock(); resolver.resolveFailure = true;
        result(MaintenanceHomePolicy.run(resolver, true), "error", "home_resolve_failed");
        expect(resolver.enabled == 0 && !resolver.panelPreference, "resolve_failure_rollback");

        for (String phase : new String[]{"prepared", "recovery_required"}) {
            Mock mock = new Mock(); mock.saved = new MaintenanceHomePolicy.State(0, phase); mock.enabled = 2; mock.panelPreference = true;
            result(MaintenanceHomePolicy.run(mock, true), "error", "home_interrupted_restored");
            expect(mock.enabled == 0 && !mock.panelPreference && count(mock.events, "disable") == 0, "interruption_recover_only");
        }
        Mock drift = new Mock(); drift.saved = new MaintenanceHomePolicy.State(1, "active"); drift.enabled = 2;
        MaintenanceHomePolicy.State original = drift.saved;
        result(MaintenanceHomePolicy.run(drift, true), "ok", "home_preference_repaired");
        expect(drift.enabled == 2 && drift.panelPreference && drift.saved == original, "repair_preserves_original_baseline");
        expect(count(drift.events, "disable") == 0 && count(drift.events, "restore") == 0
                && count(drift.events, "save_active") == 0, "repair_only_preference_mutation");

        for (String guard : new String[]{"untrusted", "component", "foreign", "own", "resolver", "candidates", "query"}) {
            Mock mock = repairMock(); MaintenanceHomePolicy.State baseline = mock.saved;
            if (guard.equals("untrusted")) mock.trusted = false;
            if (guard.equals("component")) mock.enabled = 1;
            if (guard.equals("foreign")) mock.externalPreference = true;
            if (guard.equals("own")) { mock.panelPreference = true; mock.resolveFailure = true; }
            if (guard.equals("resolver")) mock.resolver = false;
            if (guard.equals("candidates")) mock.candidatesUnchanged = false;
            if (guard.equals("query")) mock.failure = "panel";
            int enabled = mock.enabled; boolean own = mock.panelPreference, foreign = mock.externalPreference;
            expect(MaintenanceHomePolicy.run(mock, true).status.equals("error"), "repair_guard_" + guard);
            expect(mock.enabled == enabled && mock.panelPreference == own && mock.externalPreference == foreign
                    && mock.saved == baseline, "repair_guard_preserves_state_" + guard);
            expect(count(mock.events, "repair_prefer") == 0 && count(mock.events, "restore") == 0,
                    "repair_guard_no_mutation_" + guard);
        }
        for (String step : new String[]{"repair_prefer", "repair_verify"}) {
            for (boolean after : new boolean[]{false, true}) {
                if (after && step.equals("repair_verify")) continue;
                Mock mock = repairMock(); MaintenanceHomePolicy.State baseline = mock.saved;
                mock.failure = step; mock.failAfter = after;
                expect(MaintenanceHomePolicy.run(mock, true).status.equals("error"), "repair_failure");
                expect(mock.enabled == 2 && !mock.panelPreference && mock.saved == baseline,
                        "repair_failure_rolls_back_only_own");
                expect(count(mock.events, "restore") == 0 && count(mock.events, "save_restored") == 0,
                        "repair_failure_no_fallback_or_baseline_write");
            }
        }
        Mock raceBefore = repairMock(); raceBefore.foreignAfterPrepare = true;
        result(MaintenanceHomePolicy.run(raceBefore, true), "error", "home_repair_preferences_changed");
        expect(raceBefore.enabled == 2 && raceBefore.externalPreference && !raceBefore.panelPreference,
                "repair_rechecks_before_write");
        Mock raceAfter = repairMock(); raceAfter.foreignAfterWrite = true;
        result(MaintenanceHomePolicy.run(raceAfter, true), "error", "home_repair_verify_failed");
        expect(raceAfter.enabled == 2 && raceAfter.externalPreference && !raceAfter.panelPreference,
                "repair_preserves_new_foreign_preference");
        Mock repairIncomplete = repairMock(); repairIncomplete.resolveFailure = true; repairIncomplete.failure = "repair_clear";
        result(MaintenanceHomePolicy.run(repairIncomplete, true), "error", "home_preference_repair_incomplete");
        expect(repairIncomplete.enabled == 2 && repairIncomplete.panelPreference && repairIncomplete.saved.phase.equals("active"),
                "repair_incomplete_does_not_restore_kt");
        for (String phase : new String[]{"prepared", "restored", "recovery_required"}) {
            try {
                MaintenanceHomePolicy.requireRepairState(new MaintenanceHomePolicy.State(0, phase), 2, 0, 0, true, true);
                throw new AssertionError("repair_requires_active");
            } catch (IllegalStateException expected) { checks++; }
        }
        try { MaintenanceHomePolicy.requireRepairState(null, 2, 0, 0, true, true); throw new AssertionError("repair_requires_state"); }
        catch (IllegalStateException expected) { checks++; }

        for (String phase : new String[]{null, "prepared", "restored", "recovery_required"}) {
            Mock mock = new Mock(); mock.enabled = 2;
            if (phase != null) mock.saved = new MaintenanceHomePolicy.State(1, phase);
            MaintenanceHomePolicy.State baseline = mock.saved;
            result(MaintenanceHomePolicy.runRepairOnly(mock), "error", "home_repair_requires_active");
            expect(mock.events.size() == 1 && mock.events.get(0).equals("load"), "repair_only_rejects_before_queries");
            expect(mock.saved == baseline && mock.enabled == 2 && !mock.panelPreference,
                    "repair_only_never_configures_or_restores_nonactive");
        }
        Mock only = repairMock(); MaintenanceHomePolicy.State onlyBaseline = only.saved;
        result(MaintenanceHomePolicy.runRepairOnly(only), "ok", "home_preference_repaired");
        expect(only.saved == onlyBaseline && only.enabled == 2 && only.panelPreference, "repair_only_preserves_baseline");
        expect(count(only.events, "load") == 1 && count(only.events, "capture") == 0
                && count(only.events, "restore") == 0 && count(only.events, "disable") == 0,
                "repair_only_single_state_load");
        result(MaintenanceHomePolicy.runRepairOnly(only), "ok", "home_already_configured");
        expect(count(only.events, "repair_prefer") == 1, "repair_only_idempotent");
        Mock onlyForeign = repairMock(); onlyForeign.externalPreference = true;
        result(MaintenanceHomePolicy.runRepairOnly(onlyForeign), "error", "home_repair_preferences_changed");
        expect(onlyForeign.externalPreference && onlyForeign.enabled == 2 && !onlyForeign.panelPreference,
                "repair_only_foreign_unchanged");
        Mock onlyLoadFailure = repairMock(); onlyLoadFailure.failure = "load";
        result(MaintenanceHomePolicy.runRepairOnly(onlyLoadFailure), "error", "load_failed");
        expect(onlyLoadFailure.events.size() == 1, "repair_only_load_failure_stops");
        Mock onlyVerifyFailure = repairMock(); onlyVerifyFailure.resolveFailure = true;
        result(MaintenanceHomePolicy.runRepairOnly(onlyVerifyFailure), "error", "home_repair_verify_failed");
        expect(!onlyVerifyFailure.panelPreference && onlyVerifyFailure.enabled == 2
                && onlyVerifyFailure.saved.phase.equals("active"), "repair_only_failed_attempt_cleans_only_own");

        for (String step : new String[]{"restore", "clear", "restored", "save_restored"}) {
            Mock mock = new Mock(); mock.saved = new MaintenanceHomePolicy.State(0, "active"); mock.enabled = 2; mock.panelPreference = true; mock.failure = step;
            result(MaintenanceHomePolicy.run(mock, false), "error", "home_restore_incomplete");
            if (step.equals("restore")) expect(mock.panelPreference && count(mock.events, "clear") == 0, "keep_panel_when_fallback_fails");
            if (!step.equals("save_restored")) expect(mock.saved.phase.equals("recovery_required"), "incomplete_durable");
        }
        Mock externallyChanged = new Mock(); externallyChanged.saved = new MaintenanceHomePolicy.State(0, "active");
        externallyChanged.enabled = 2; externallyChanged.panelPreference = true; externallyChanged.externalPreference = true;
        result(MaintenanceHomePolicy.run(externallyChanged, false), "error", "home_restore_incomplete");
        expect(externallyChanged.enabled == 0 && externallyChanged.panelPreference && externallyChanged.externalPreference, "external_preferences_preserved");

        for (int state : new int[]{-1, 2, 3, 4, Integer.MAX_VALUE}) {
            try { new MaintenanceHomePolicy.State(state, "prepared"); throw new AssertionError("invalid_state"); }
            catch (IllegalArgumentException expected) { checks++; }
        }
        for (String phase : new String[]{"", "disabled", "complete", "ACTIVE", null}) {
            try { new MaintenanceHomePolicy.State(0, phase); throw new AssertionError("invalid_phase"); }
            catch (IllegalArgumentException expected) { checks++; }
        }
        System.out.println("MaintenanceHomePolicyTest passed: " + checks + " checks");
    }

    private static Mock repairMock() {
        Mock mock = new Mock(); mock.saved = new MaintenanceHomePolicy.State(0, "active"); mock.enabled = 2; return mock;
    }

    private static int count(List<String> values, String expected) {
        int result = 0; for (String value : values) if (value.equals(expected)) result++; return result;
    }
    private static void result(MaintenanceHomePolicy.Result result, String status, String reason) {
        expect(status.equals(result.status) && reason.equals(result.reason), "result_" + reason);
    }
    private static void expect(boolean condition, String name) {
        checks++; if (!condition) throw new AssertionError(name);
    }
}
