package net.quber.afactorymode;

public final class MaintenanceAttemptPolicyTest {
    private static int checks;
    private static void check(boolean value) { checks++; if (!value) throw new AssertionError("check " + checks); }

    public static void main(String[] args) {
        for (String reason : new String[]{"download_timeout", "download_unavailable"}) {
            check(MaintenanceAttemptPolicy.retrySafe("error", "idle", reason));
            for (String worker : new String[]{null, "", "error", "installing", "installed", "indeterminate"}) {
                check(!MaintenanceAttemptPolicy.retrySafe("error", worker, reason));
            }
            for (String status : new String[]{null, "", "ok", "accepted"}) {
                check(!MaintenanceAttemptPolicy.retrySafe(status, "idle", reason));
            }
        }
        for (String reason : new String[]{null, "", "maintenance_unavailable", "maintenance_timeout",
                "worker_start_failed", "worker_start_indeterminate", "candidate_hash", "apk_identity",
                "release_target", "download_size", "download_header", "download_http_status", "update_in_progress"}) {
            check(!MaintenanceAttemptPolicy.retrySafe("error", "idle", reason));
        }
        check(MaintenanceAttemptPolicy.completedWithoutWorker("ok", "idle", "no_release"));
        check(MaintenanceAttemptPolicy.completedWithoutWorker("ok", "installed", "installed_version_verified"));
        check(!MaintenanceAttemptPolicy.completedWithoutWorker("accepted", "installed", "installed_version_verified"));
        check(!MaintenanceAttemptPolicy.completedWithoutWorker("error", "idle", "no_release"));
        check(!MaintenanceAttemptPolicy.completedWithoutWorker("ok", "installing", "no_release"));
        check(!MaintenanceAttemptPolicy.completedWithoutWorker("ok", "indeterminate", "no_release"));
        check(!MaintenanceAttemptPolicy.completedWithoutWorker("ok", "idle", "worker_ready"));
        check(!MaintenanceAttemptPolicy.completedWithoutWorker("ok", "installed", "download_unavailable"));
        check(!MaintenanceAttemptPolicy.completedWithoutWorker(null, null, null));
        System.out.println("Unstarted update retry policy checks passed: " + checks);
    }
}
