package net.quber.afactorymode;

final class MaintenanceHomePolicy {
    static final class State {
        final int enabled;
        final String phase;

        State(int enabled, String phase) {
            if ((enabled != 0 && enabled != 1) || phase == null
                    || !phase.matches("prepared|active|restored|recovery_required")) {
                throw new IllegalArgumentException("home_state_invalid");
            }
            this.enabled = enabled;
            this.phase = phase;
        }

        State phase(String next) { return new State(enabled, next); }
    }

    interface Access {
        State load() throws Exception;
        State capture() throws Exception;
        void save(State state) throws Exception;
        void requireOriginal(State state) throws Exception;
        void disableGreeting(State state) throws Exception;
        void addPanelPreference() throws Exception;
        boolean isPanelHome() throws Exception;
        void restoreGreeting(State state) throws Exception;
        void removePanelPreference() throws Exception;
        boolean isOriginalHome(State state) throws Exception;
        void requireRepairable(State state) throws Exception;
        void addRepairPreference(State state) throws Exception;
        void verifyRepair(State state) throws Exception;
        void rollbackRepair(State state) throws Exception;
    }

    static final class Result {
        final String status, reason;
        Result(String status, String reason) { this.status = status; this.reason = reason; }
    }

    static Result run(Access access, boolean configure) {
        State state;
        try { state = access.load(); }
        catch (Exception error) { return failure(error); }
        if (!configure) {
            if (state == null) return new Result("ok", "home_not_configured");
            return restore(access, state) ? new Result("ok", "home_restored")
                    : new Result("error", "home_restore_incomplete");
        }
        if (state != null && "active".equals(state.phase)) {
            return repairActive(access, state);
        }
        if (state != null && !"restored".equals(state.phase)) {
            return restore(access, state) ? new Result("error", "home_interrupted_restored")
                    : new Result("error", "home_restore_incomplete");
        }
        try {
            if (state == null) state = access.capture();
            access.requireOriginal(state);
            state = state.phase("prepared");
            access.save(state);
        } catch (Exception error) { return failure(error); }
        try {
            access.disableGreeting(state);
            access.addPanelPreference();
            if (!access.isPanelHome()) throw new IllegalStateException("home_resolve_failed");
            access.save(state.phase("active"));
            return new Result("ok", "home_configured");
        } catch (Exception error) {
            return restore(access, state) ? failure(error) : new Result("error", "home_rollback_incomplete");
        }
    }

    static Result runRepairOnly(Access access) {
        State state;
        try { state = access.load(); }
        catch (Exception error) { return failure(error); }
        if (state == null || !"active".equals(state.phase)) {
            return new Result("error", "home_repair_requires_active");
        }
        return repairActive(access, state);
    }

    private static Result repairActive(Access access, State state) {
        try {
            if (access.isPanelHome()) return new Result("ok", "home_already_configured");
            access.requireRepairable(state);
        } catch (Exception error) { return failure(error); }
        try {
            access.addRepairPreference(state);
            access.verifyRepair(state);
            return new Result("ok", "home_preference_repaired");
        } catch (Exception error) {
            try { access.rollbackRepair(state); }
            catch (Exception incomplete) { return new Result("error", "home_preference_repair_incomplete"); }
            return failure(error);
        }
    }

    static void requireRepairState(State state, int enabled, int homeCount, int ownCount,
                                   boolean resolver, boolean candidatesUnchanged) {
        if (state == null || !"active".equals(state.phase)) throw new IllegalStateException("home_repair_state");
        if (enabled != 2) throw new IllegalStateException("home_repair_component_changed");
        if (homeCount != 0 || ownCount != 0) throw new IllegalStateException("home_repair_preferences_changed");
        if (!resolver) throw new IllegalStateException("home_repair_not_resolver");
        if (!candidatesUnchanged) throw new IllegalStateException("home_repair_candidates_changed");
    }

    private static boolean restore(Access access, State state) {
        boolean restored = true;
        try { access.restoreGreeting(state); }
        catch (Exception error) { restored = false; }
        if (restored) {
            try { access.removePanelPreference(); }
            catch (Exception error) { restored = false; }
        }
        try { restored = access.isOriginalHome(state) && restored; }
        catch (Exception error) { restored = false; }
        try { access.save(state.phase(restored ? "restored" : "recovery_required")); }
        catch (Exception error) { return false; }
        return restored;
    }

    private static Result failure(Exception error) {
        String reason = error.getMessage();
        return new Result("error", reason != null && reason.matches("[a-z][a-z0-9_]{0,79}")
                ? reason : "home_operation_failed");
    }
}
