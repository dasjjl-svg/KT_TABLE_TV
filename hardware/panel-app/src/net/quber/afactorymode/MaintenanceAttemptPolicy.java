package net.quber.afactorymode;

final class MaintenanceAttemptPolicy {
    private MaintenanceAttemptPolicy() {}

    static boolean retrySafe(String status, String worker, String reason) {
        return "error".equals(status) && "idle".equals(worker)
                && ("download_timeout".equals(reason) || "download_unavailable".equals(reason));
    }

    static boolean completedWithoutWorker(String status, String worker, String reason) {
        return "ok".equals(status) && ("idle".equals(worker) && "no_release".equals(reason)
                || "installed".equals(worker) && "installed_version_verified".equals(reason));
    }
}
