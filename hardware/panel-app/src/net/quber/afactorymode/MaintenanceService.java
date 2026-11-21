package net.quber.afactorymode;

import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.net.ConnectivityManager;
import android.net.NetworkInfo;
import android.os.IBinder;
import android.os.SystemClock;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import org.json.JSONObject;

public final class MaintenanceService extends Service {
    private static final String SETUP = "home.panel.SETUP";
    private static final String RESTORE = "home.panel.RESTORE";
    private static final int HOME_CONFIGURATION_VERSION = 2;
    private static final int HOME_REPAIR_VERSION = 3;
    private static final int RETURN_CONFIG_VERSION = 1;
    private static final long RETURN_POLL_BACKGROUND_MS = 2000L;
    private static final long RETURN_POLL_FOREGROUND_MS = 10000L;
    private final ScheduledExecutorService work = Executors.newSingleThreadScheduledExecutor();
    private final ScheduledExecutorService returnPoller = Executors.newSingleThreadScheduledExecutor();
    private final PanelReturnVoiceReceiver voiceReceiver = new PanelReturnVoiceReceiver();
    private final ReconnectScheduler reconnect = new ReconnectScheduler(work, new Runnable() {
        @Override public void run() { tick(); }
    });
    private final BroadcastReceiver network = new BroadcastReceiver() {
        @Override public void onReceive(Context context, Intent intent) {
            boolean connected = networkAvailable();
            reconnect.connectionChanged(connected);
            if (connected) scheduleReturnPoll(500L);
        }
    };
    private final Object operationGate = new Object();
    private final Object returnPollGate = new Object();
    private boolean scheduled;
    private boolean voiceRegistered;
    private ScheduledFuture<?> nextReturnPoll;
    private boolean startupHeartbeatAttempted;
    private String startupReportInProcess = "";
    private volatile long installWaitUntil;
    private volatile String installWaitRelease = "";
    private volatile boolean bootstrapRepairRequested;
    private volatile String lastHandledReturnId = "";
    private SharedPreferences prefs;
    private String rootStatus = "unknown", home = "unknown";

    static void start(Context context, boolean setup) {
        SharedPreferences preferences = context.getSharedPreferences("maintenance", MODE_PRIVATE);
        if (setup || (MaintenancePolicy.PACKAGE.equals(context.getPackageName()) && preferences.getBoolean("enabled", true))) {
            preferences.edit().putBoolean("enabled", true).commit();
            Intent intent = new Intent(context, MaintenanceService.class);
            if (setup) intent.setAction(SETUP);
            context.startService(intent);
        }
    }

    static void restore(Context context) {
        context.startService(new Intent(context, MaintenanceService.class).setAction(RESTORE));
    }

    @Override public void onCreate() {
        super.onCreate(); prefs = getSharedPreferences("maintenance", MODE_PRIVATE);
        registerReceiver(network, new IntentFilter(ConnectivityManager.CONNECTIVITY_ACTION));
        if (MaintenancePolicy.PACKAGE.equals(getPackageName())) {
            registerReceiver(voiceReceiver, PanelReturnVoiceReceiver.intentFilter());
            voiceRegistered = true;
            PanelReturnLauncher.setBackgroundListener(new Runnable() {
                @Override public void run() { scheduleReturnPoll(800L); }
            });
        }
    }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        String action = intent == null ? "" : intent.getAction();
        if (RESTORE.equals(action)) {
            synchronized (operationGate) {
                prefs.edit().putBoolean("homeSetupRequested", HomeSetupPolicy.requestedAfterAction(
                        getPackageName(), action, prefs.getBoolean("homeSetupRequested", false),
                        installWaitUntil > SystemClock.elapsedRealtime())).commit();
                if (installWaitUntil > SystemClock.elapsedRealtime()) {
                    record("accepted", "restore_waits_for_install");
                    return START_STICKY;
                }
                prefs.edit().putBoolean("enabled", false).putBoolean("homeAttempted", true)
                        .remove("returnConfigVersion").commit();
                cancelReturnPoll();
            }
            work.execute(new Runnable() { public void run() {
                try {
                    if (installWaitUntil > SystemClock.elapsedRealtime()) {
                        record("accepted", "restore_waits_for_install"); return;
                    }
                    JSONObject result = MaintenanceRootClient.call(MaintenanceService.this, MaintenanceRootClient.Mode.RESTORE_HOME);
                    observe(result);
                    report("ok".equals(result.optString("status")) ? "idle" : "error", reason(result, "restore_failed"), "");
                } catch (Exception error) { record("error", "restore_unavailable"); }
            }});
            return START_NOT_STICKY;
        }
        if (SETUP.equals(action)) {
            SharedPreferences.Editor editor = prefs.edit().putBoolean("enabled", true);
            editor.putBoolean("homeSetupRequested", HomeSetupPolicy.requestedAfterAction(getPackageName(), action,
                    prefs.getBoolean("homeSetupRequested", false), installWaitUntil > SystemClock.elapsedRealtime()));
            if (installWaitUntil <= SystemClock.elapsedRealtime()) {
                editor.putBoolean("homeAttempted", false);
                if (MaintenancePolicy.BOOTSTRAP_PACKAGE.equals(getPackageName())) bootstrapRepairRequested = true;
            }
            editor.commit();
        }
        if (prefs.getBoolean("enabled", false) && !scheduled) {
            scheduled = true;
            work.scheduleWithFixedDelay(new Runnable() { public void run() { tick(); } }, 0, 30, TimeUnit.SECONDS);
            scheduleReturnPoll(1500L);
        } else if (SETUP.equals(action)) {
            work.execute(new Runnable() { public void run() { tick(); } });
            scheduleReturnPoll(1500L);
        }
        return START_STICKY;
    }

    @Override public IBinder onBind(Intent intent) { return null; }
    @Override public void onDestroy() {
        PanelReturnLauncher.setBackgroundListener(null);
        cancelReturnPoll();
        reconnect.close();
        unregisterReceiver(network);
        if (voiceRegistered) {
            unregisterReceiver(voiceReceiver);
            voiceRegistered = false;
        }
        returnPoller.shutdownNow();
        work.shutdown();
        super.onDestroy();
    }

    private boolean networkAvailable() {
        ConnectivityManager manager = (ConnectivityManager) getSystemService(CONNECTIVITY_SERVICE);
        NetworkInfo active = manager == null ? null : manager.getActiveNetworkInfo();
        return active != null && active.isConnected();
    }

    private void tick() {
        if (reconnect.isClosed() || !prefs.getBoolean("enabled", false)) return;
        if (!networkAvailable()) { record("idle", "network_unavailable"); return; }
        if (!startupHeartbeatAttempted) {
            try { report("checking", "service_started", ""); startupHeartbeatAttempted = true; }
            catch (Exception unavailable) { }
        }
        try {
            if (MaintenancePolicy.BOOTSTRAP_PACKAGE.equals(getPackageName())
                    && !bootstrapRepairRequested && MaintenancePolicy.installedVersion(this) >= 39) {
                handoffManaged();
                return;
            }
            if (installWaitUntil > SystemClock.elapsedRealtime()) {
                report("accepted", "awaiting_install_result", installWaitRelease);
                return;
            }
            JSONObject state = MaintenanceRootClient.call(this, MaintenanceRootClient.Mode.STATUS);
            observe(state);
            if (!"ok".equals(state.optString("status"))) {
                rootStatus = "unavailable";
                report("error", reason(state, "root_unavailable"), ""); return;
            }
            reportStartupEvidence(state);
            reportCrashEvidence(state);
            reportHomeEvidence(state);
            String worker = state.optString("workerStatus");
            if ("installing".equals(worker) || "indeterminate".equals(worker)) {
                report("installing".equals(worker) ? "accepted" : "error",
                        reason(state, "awaiting_install_result"), state.optString("releaseId", ""));
                return;
            }
            if (MaintenancePolicy.PACKAGE.equals(getPackageName())
                    && prefs.getBoolean("homeAttempted", false)
                    && prefs.getInt("homeConfigurationVersion", 0) == HOME_CONFIGURATION_VERSION
                    && state.optInt("installedVersion", 0) >= 40) {
                if (!prefs.edit().putInt("homeConfigurationVersion", HOME_REPAIR_VERSION)
                        .remove("homeEvidenceVersion").commit()) throw new IllegalStateException("home_repair_record");
                JSONObject repaired = MaintenanceRootClient.call(this, MaintenanceRootClient.Mode.REPAIR_HOME);
                observe(repaired);
                if (!"ok".equals(repaired.optString("status"))) {
                    report("error", reason(repaired, "home_repair_failed"), ""); return;
                }
                report("idle", reason(repaired, "home_repaired"), "");
            }
            if (HomeSetupPolicy.shouldConfigure(getPackageName(), prefs.getBoolean("homeSetupRequested", false),
                    prefs.getBoolean("enabled", false), installWaitUntil > SystemClock.elapsedRealtime())) {
                synchronized (operationGate) {
                    if (!HomeSetupPolicy.shouldConfigure(getPackageName(), prefs.getBoolean("homeSetupRequested", false),
                            prefs.getBoolean("enabled", false), installWaitUntil > SystemClock.elapsedRealtime())) return;
                    if (!prefs.edit().remove("homeSetupRequested").remove("homeConfigurationVersion")
                            .putBoolean("homeAttempted", true).commit()) {
                        throw new IllegalStateException("home_attempt_record");
                    }
                }
                JSONObject configured = MaintenanceRootClient.call(this, MaintenanceRootClient.Mode.CONFIGURE_HOME);
                observe(configured);
                prefs.edit().remove("homeEvidenceVersion").commit();
                if (!"ok".equals(configured.optString("status"))) {
                    report("error", reason(configured, "home_setup_failed"), ""); return;
                }
                if (!"panel".equals(configured.optString("home"))) {
                    report("error", "home_setup_unverified", ""); return;
                }
                if (!prefs.edit().putInt("homeConfigurationVersion", HOME_CONFIGURATION_VERSION).commit()) {
                    throw new IllegalStateException("home_setup_record");
                }
                report("idle", "home_setup_verified", "");
            }
            ensureReturnHelperConfigured();
            byte[] envelope = request("GET", "/v1/release", null, 8192, true);
            if (envelope == null) {
                report("idle", "no_release", "");
                finishBootstrapRepair();
                return;
            }
            MaintenancePolicy.Release release = MaintenancePolicy.verify(envelope, System.currentTimeMillis() / 1000L);
            int installed = MaintenancePolicy.installedVersion(this);
            if (installed >= release.versionCode) {
                report("installed", "installed_version_verified", release.id);
                finishBootstrapRepair();
                return;
            }
            if (release.id.equals(prefs.getString("attemptedRelease", ""))) {
                if (release.id.equals(prefs.getString("failedRelease", ""))) {
                    report("error", safeReason(prefs.getString("failedReason", ""), "install_not_started"), release.id);
                    return;
                }
                report("error".equals(worker) ? "error" : "accepted",
                        "error".equals(worker) ? reason(state, "install_failed") : "awaiting_install_result", release.id); return;
            }
            report("downloading", "release_verified", release.id);
            synchronized (operationGate) {
                if (!prefs.getBoolean("enabled", false)) return;
                if (!prefs.edit().putString("attemptedRelease", release.id).commit()) throw new IllegalStateException("attempt_record");
                installWaitRelease = release.id;
                installWaitUntil = SystemClock.elapsedRealtime() + 240000L;
            }
            JSONObject accepted = MaintenanceRootClient.call(this, MaintenanceRootClient.Mode.START_UPDATE);
            observe(accepted);
            String acceptedId = accepted.optString("releaseId", "");
            String acceptedStatus = accepted.optString("status");
            if (MaintenanceAttemptPolicy.completedWithoutWorker(acceptedStatus,
                    accepted.optString("workerStatus"), accepted.optString("reason"))) {
                clearUnstartedAttempt();
                report("installed".equals(accepted.optString("workerStatus")) ? "installed" : "idle",
                        reason(accepted, "no_release"), acceptedId);
                finishBootstrapRepair();
                return;
            }
            if (MaintenanceAttemptPolicy.retrySafe(acceptedStatus,
                    accepted.optString("workerStatus"), accepted.optString("reason"))) {
                clearUnstartedAttempt();
                report("idle", reason(accepted, "download_unavailable"), acceptedId);
                return;
            }
            boolean installing = "accepted".equals(acceptedStatus);
            try {
                if (installing) {
                    if (!acceptedId.matches("[0-9a-f]{32}")) throw new IllegalStateException("accepted_release_missing");
                    installWaitRelease = acceptedId;
                    if (!prefs.edit().putString("attemptedRelease", acceptedId).commit()) throw new IllegalStateException("attempt_record");
                } else {
                    prefs.edit().putString("failedRelease", acceptedId.isEmpty() ? release.id : acceptedId)
                            .putString("failedReason", reason(accepted, "install_not_started")).commit();
                }
                report(installing ? "accepted" : "error", reason(accepted, "install_not_started"), acceptedId);
            } finally {
                if (installing && MaintenancePolicy.BOOTSTRAP_PACKAGE.equals(getPackageName())) {
                    bootstrapRepairRequested = false;
                    stopBootstrapMaintenance();
                }
            }
        } catch (Exception error) {
            String why = error.getMessage();
            try { report("error", safeReason(why, "maintenance_unavailable"), ""); }
            catch (Exception offline) { record("error", "server_unavailable"); }
        }
    }

    private void clearUnstartedAttempt() {
        synchronized (operationGate) {
            if (!prefs.edit().remove("attemptedRelease").remove("failedRelease").remove("failedReason").commit()) {
                throw new IllegalStateException("attempt_clear");
            }
            installWaitUntil = 0;
            installWaitRelease = "";
        }
    }

    private void handoffManaged() throws Exception {
        Intent panel = new Intent(Intent.ACTION_MAIN);
        panel.setClassName(MaintenancePolicy.PACKAGE, MaintenancePolicy.ACTIVITY);
        panel.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        startActivity(panel);
        try { report("idle", "managed_handoff", ""); }
        finally { stopBootstrapMaintenance(); }
    }

    private void finishBootstrapRepair() throws Exception {
        if (MaintenancePolicy.BOOTSTRAP_PACKAGE.equals(getPackageName()) && MaintenancePolicy.installedVersion(this) >= 39) {
            bootstrapRepairRequested = false;
            handoffManaged();
        }
    }

    private void stopBootstrapMaintenance() {
        if (!MaintenancePolicy.BOOTSTRAP_PACKAGE.equals(getPackageName())) return;
        prefs.edit().putBoolean("enabled", false).commit();
        stopSelf();
    }

    private void observe(JSONObject result) {
        if ("ok".equals(result.optString("status")) || "accepted".equals(result.optString("status"))) rootStatus = "ok";
        String found = result.optString("home");
        if ("panel".equals(found) || "other".equals(found)) home = found;
    }

    private void reportHomeEvidence(JSONObject state) {
        if (!MaintenancePolicy.PACKAGE.equals(getPackageName())) return;
        try {
            int version = getPackageManager().getPackageInfo(getPackageName(), 0).versionCode;
            if (prefs.getInt("homeEvidenceVersion", -1) == version) return;
            JSONObject evidence = state.optJSONObject("homeEvidence");
            if (evidence == null) {
                prefs.edit().putString("homeEvidenceError", safeReason(state.optString("homeEvidenceError"), "home_evidence_unavailable")).commit();
                return;
            }
            MaintenanceHome.validateEvidence(evidence);
            JSONObject body = new JSONObject();
            body.put("schema", 1); body.put("package", getPackageName());
            body.put("versionCode", version); body.put("home", evidence);
            byte[] bytes = body.toString().getBytes(MaintenancePolicy.UTF8);
            if (bytes.length > 8192) throw new IllegalArgumentException("home_evidence_size");
            byte[] response = request("POST", "/v1/home-report", bytes, 1024, false);
            if (response == null || !new JSONObject(new String(response, MaintenancePolicy.UTF8)).optBoolean("ok")) {
                throw new IllegalStateException("home_evidence_ack");
            }
            prefs.edit().putInt("homeEvidenceVersion", version).remove("homeEvidenceError").commit();
        } catch (Exception error) {
            try { prefs.edit().putString("homeEvidenceError", safeReason(error.getMessage(), "home_evidence_unavailable")).commit(); }
            catch (RuntimeException unavailable) { }
        }
    }

    private void reportStartupEvidence(JSONObject state) {
        if (!MaintenancePolicy.PACKAGE.equals(getPackageName())) return;
        try {
            JSONObject evidence = state.optJSONObject("startupEvidence");
            if (evidence == null) return;
            int version = getPackageManager().getPackageInfo(getPackageName(), 0).versionCode;
            String bootId = evidence.getString("bootId");
            boolean knownBoot = StartupEvidence.validBootId(bootId);
            String key = version + ":" + bootId;
            if (key.equals(startupReportInProcess) || knownBoot && key.equals(prefs.getString("startupEvidenceKey", ""))) return;
            if (evidence.toString().getBytes(MaintenancePolicy.UTF8).length > 4096) throw new IllegalArgumentException("startup_evidence_size");
            JSONObject currentPrefs = new JSONObject();
            currentPrefs.put("enabled", prefs.getBoolean("enabled", false));
            currentPrefs.put("homeAttempted", prefs.getBoolean("homeAttempted", false));
            currentPrefs.put("homeConfigurationVersion", prefs.getInt("homeConfigurationVersion", 0));
            currentPrefs.put("status", boundedPreference("status")); currentPrefs.put("reason", boundedPreference("reason"));
            JSONObject body = new JSONObject();
            body.put("schema", 1); body.put("package", getPackageName()); body.put("versionCode", version);
            body.put("currentPrefs", currentPrefs); body.put("startup", evidence);
            byte[] bytes = body.toString().getBytes(MaintenancePolicy.UTF8);
            if (bytes.length > 8192) throw new IllegalArgumentException("startup_report_size");
            byte[] response = request("POST", "/v1/startup-report", bytes, 1024, false);
            if (response == null || !new JSONObject(new String(response, MaintenancePolicy.UTF8)).optBoolean("ok")) {
                throw new IllegalStateException("startup_evidence_ack");
            }
            startupReportInProcess = key;
            if (knownBoot) prefs.edit().putString("startupEvidenceKey", key).commit();
        } catch (Exception unavailable) { }
    }

    private String boundedPreference(String key) {
        String value = prefs.getString(key, "unknown");
        return value != null && value.length() <= 120 ? value : "unknown";
    }

    private void reportCrashEvidence(JSONObject state) {
        if (!MaintenancePolicy.PACKAGE.equals(getPackageName())) return;
        try {
            int version = getPackageManager().getPackageInfo(getPackageName(), 0).versionCode;
            if (prefs.getInt("crashEvidenceVersion", -1) == version) return;
            JSONObject evidence = state.optJSONObject("crashEvidence");
            if (evidence == null || evidence.toString().getBytes(MaintenancePolicy.UTF8).length > 4096) return;
            JSONObject body = new JSONObject();
            body.put("schema", 1); body.put("package", getPackageName());
            body.put("versionCode", version); body.put("crash", evidence);
            byte[] bytes = body.toString().getBytes(MaintenancePolicy.UTF8);
            if (bytes.length > 8192) return;
            byte[] response = request("POST", "/v1/crash-report", bytes, 1024, false);
            if (response != null && new JSONObject(new String(response, MaintenancePolicy.UTF8)).optBoolean("ok")) {
                prefs.edit().putInt("crashEvidenceVersion", version).commit();
            }
        } catch (Exception unavailable) { }
    }

    private String reason(JSONObject result, String fallback) { return safeReason(result.optString("reason"), fallback); }
    private static String safeReason(String value, String fallback) {
        return value != null && value.matches("[a-z0-9_]{1,120}") ? value : fallback;
    }

    private void report(String status, String reason, String releaseId) throws Exception {
        record(status, reason);
        JSONObject body = new JSONObject();
        body.put("schema", 1); body.put("package", getPackageName());
        body.put("versionCode", getPackageManager().getPackageInfo(getPackageName(), 0).versionCode);
        body.put("status", status); body.put("reason", reason); body.put("releaseId", releaseId);
        body.put("home", home); body.put("rootStatus", rootStatus);
        byte[] response = request("POST", "/v1/report", body.toString().getBytes(MaintenancePolicy.UTF8), 1024, false);
        if (response == null || !new JSONObject(new String(response, MaintenancePolicy.UTF8)).optBoolean("ok")) {
            throw new IllegalStateException("report_ack");
        }
    }

    private void record(String status, String reason) {
        prefs.edit().putString("status", status).putString("reason", reason).putString("home", home)
                .putLong("lastAt", System.currentTimeMillis()).commit();
    }

    private void ensureReturnHelperConfigured() {
        if (!MaintenancePolicy.PACKAGE.equals(getPackageName())
                || !prefs.getBoolean("enabled", false)
                || (prefs.getInt("returnConfigVersion", 0) >= RETURN_CONFIG_VERSION
                        && PanelReturnLauncher.isAccessibilityServiceEnabled(this))) {
            return;
        }
        try {
            JSONObject configured = MaintenanceRootClient.call(this, MaintenanceRootClient.Mode.CONFIGURE_RETURN);
            observe(configured);
            if ("ok".equals(configured.optString("status"))
                    && "return_service_configured".equals(configured.optString("reason"))) {
                prefs.edit().putInt("returnConfigVersion", RETURN_CONFIG_VERSION).commit();
            }
        } catch (Exception unavailable) { }
    }

    private void cancelReturnPoll() {
        synchronized (returnPollGate) {
            if (nextReturnPoll != null) {
                nextReturnPoll.cancel(false);
                nextReturnPoll = null;
            }
        }
    }

    private void scheduleReturnPoll(long delayMs) {
        if (!MaintenancePolicy.PACKAGE.equals(getPackageName()) || reconnect.isClosed()
                || prefs == null || !prefs.getBoolean("enabled", false)) {
            return;
        }
        synchronized (returnPollGate) {
            if (returnPoller.isShutdown()) return;
            if (nextReturnPoll != null) nextReturnPoll.cancel(false);
            try {
                nextReturnPoll = returnPoller.schedule(new Runnable() {
                    @Override public void run() { pollReturnHome(); }
                }, Math.max(250L, delayMs), TimeUnit.MILLISECONDS);
            } catch (RuntimeException rejected) {
                nextReturnPoll = null;
            }
        }
    }

    private void pollReturnHome() {
        if (!MaintenancePolicy.PACKAGE.equals(getPackageName()) || reconnect.isClosed()
                || prefs == null || !prefs.getBoolean("enabled", false)) {
            return;
        }
        try {
            if (networkAvailable()) {
                byte[] raw = request("GET", "/v1/return-home", null, 1024, true, 2500, 2500);
                if (raw != null) {
                    JSONObject command = new JSONObject(new String(raw, MaintenancePolicy.UTF8));
                    int schema = command.optInt("schema", -1);
                    String action = command.optString("action", "");
                    String id = command.optString("id", "");
                    String source = command.optString("source", "");
                    long expiresAt = command.optLong("expiresAt", -1L);
                    if (ReturnTriggerPolicy.validReturnRequest(schema, action, id, source, expiresAt)) {
                        if (!id.equals(lastHandledReturnId)) {
                            lastHandledReturnId = id;
                            PanelReturnLauncher.bringToFront(this, "remote_" + source, true);
                        }
                        JSONObject ack = new JSONObject();
                        ack.put("schema", 1);
                        ack.put("id", id);
                        try {
                            request("POST", "/v1/return-home/ack", ack.toString().getBytes(MaintenancePolicy.UTF8),
                                    512, false, 2500, 2500);
                        } catch (Exception ignored) { }
                    }
                }
            }
        } catch (Exception unavailable) {
        } finally {
            long delay = PanelReturnLauncher.isForeground() ? RETURN_POLL_FOREGROUND_MS : RETURN_POLL_BACKGROUND_MS;
            scheduleReturnPoll(delay);
        }
    }

    private byte[] request(String method, String path, byte[] body, int maximum, boolean allowEmpty) throws Exception {
        return request(method, path, body, maximum, allowEmpty, 5000, 10000);
    }

    private byte[] request(String method, String path, byte[] body, int maximum, boolean allowEmpty,
            int connectTimeout, int readTimeout) throws Exception {
        HttpURLConnection connection = (HttpURLConnection) new URL(PanelBuildConfig.MAINTENANCE_ORIGIN + path).openConnection(java.net.Proxy.NO_PROXY);
        connection.setInstanceFollowRedirects(false);
        connection.setConnectTimeout(connectTimeout); connection.setReadTimeout(readTimeout); connection.setUseCaches(false);
        connection.setRequestMethod(method);
        connection.setRequestProperty("Authorization", "Bearer " + PanelBuildConfig.MAINTENANCE_TOKEN);
        connection.setRequestProperty("Accept-Encoding", "identity");
        try {
            if (body != null) {
                connection.setDoOutput(true); connection.setFixedLengthStreamingMode(body.length);
                connection.setRequestProperty("Content-Type", "application/json");
                try (java.io.OutputStream out = connection.getOutputStream()) { out.write(body); }
            }
            int status = connection.getResponseCode();
            if (status == 204 && allowEmpty) return null;
            if (status != 200) throw new IllegalStateException("http_status");
            if (connection.getContentLength() > maximum) throw new IllegalStateException("response_size");
            try (InputStream input = connection.getInputStream()) { return MaintenancePolicy.read(input, maximum); }
        } finally { connection.disconnect(); }
    }

}
