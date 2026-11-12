package net.quber.afactorymode;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.ActivityInfo;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.pm.PackageInfo;
import android.content.pm.ResolveInfo;
import android.os.Process;
import android.util.Xml;
import java.io.StringWriter;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import org.json.JSONArray;
import org.json.JSONObject;
import org.xmlpull.v1.XmlSerializer;

final class MaintenanceHome {
    private static final String KT = "com.kt.gigagenie.launcher";
    private static final ComponentName GREETING = new ComponentName(KT, KT + ".ui.GreetingActivity");
    private static final ComponentName PANEL = new ComponentName(MaintenancePolicy.PACKAGE, MaintenancePolicy.ACTIVITY);
    private static final ComponentName RESOLVER = new ComponentName("android", "com.android.internal.app.ResolverActivity");
    private static final String STATE_FILE = "home-state.json";

    static JSONObject run(Context context, String mode) throws Exception {
        if (Process.myUid() != 0 || context == null
                || !"configureHome".equals(mode) && !"restoreHome".equals(mode) && !"repairHome".equals(mode)) {
            throw new IllegalArgumentException("home_arguments");
        }
        MaintenanceRoot.ensureRootDirectory();
        Adapter adapter = new Adapter(context);
        MaintenanceHomePolicy.Result outcome = "repairHome".equals(mode) ? MaintenanceHomePolicy.runRepairOnly(adapter)
                : MaintenanceHomePolicy.run(adapter, "configureHome".equals(mode));
        JSONObject result = new JSONObject();
        result.put("status", outcome.status);
        result.put("reason", outcome.reason);
        result.put("home", home(context));
        return result;
    }

    static String home(Context context) {
        try {
            ComponentName resolved = resolve(context.getPackageManager());
            return resolved == null ? "unknown" : PANEL.equals(resolved) ? "panel" : "other";
        } catch (Exception error) { return "unknown"; }
    }

    static JSONObject evidence(Context context) throws Exception {
        MaintenanceRoot.checkTime();
        PackageManager manager = context.getPackageManager();
        ComponentName current = resolve(manager);
        int enabled;
        try {
            manager.getActivityInfo(GREETING, PackageManager.GET_DISABLED_COMPONENTS);
            enabled = manager.getComponentEnabledSetting(GREETING);
        } catch (PackageManager.NameNotFoundException absent) { enabled = -1; }
        List<ResolveInfo> found = manager.queryIntentActivities(homeIntent(), PackageManager.MATCH_DEFAULT_ONLY);
        if (found == null || found.size() > 16) throw new IllegalStateException("home_evidence_candidates");
        ArrayList<String> names = new ArrayList<String>();
        for (ResolveInfo item : found) {
            if (item == null || item.activityInfo == null) throw new IllegalStateException("home_evidence_candidate");
            names.add(new ComponentName(item.activityInfo.packageName, item.activityInfo.name).flattenToString());
        }
        Collections.sort(names);
        JSONObject result = new JSONObject();
        result.put("resolved", current == null ? "" : current.flattenToString());
        result.put("greetingEnabled", enabled);
        result.put("candidates", new JSONArray(names));
        result.put("preferences", new Preferences(manager).homeJson(16, 2048));
        validateEvidence(result);
        MaintenanceRoot.checkTime();
        return result;
    }

    static void validateEvidence(JSONObject value) throws Exception {
        if (value == null || value.length() != 4) throw new IllegalArgumentException("home_evidence_fields");
        evidenceString(value.get("resolved"), 256);
        Object enabled = value.get("greetingEnabled");
        if (!(enabled instanceof Integer) || ((Integer) enabled) < -1 || ((Integer) enabled) > 4) {
            throw new IllegalArgumentException("home_evidence_enabled");
        }
        JSONArray candidates = value.getJSONArray("candidates"), preferences = value.getJSONArray("preferences");
        if (candidates.length() > 16 || preferences.length() > 16) throw new IllegalArgumentException("home_evidence_count");
        for (int index = 0; index < candidates.length(); index++) evidenceString(candidates.get(index), 256);
        for (int index = 0; index < preferences.length(); index++) {
            JSONObject preference = preferences.getJSONObject(index);
            if (preference.length() != 2) throw new IllegalArgumentException("home_evidence_preference");
            evidenceString(preference.get("component"), 256);
            evidenceString(preference.get("filter"), 2048);
        }
        if (value.toString().getBytes(MaintenancePolicy.UTF8).length > 7168) throw new IllegalArgumentException("home_evidence_size");
    }

    private static void evidenceString(Object value, int maximum) {
        if (!(value instanceof String) || ((String) value).length() > maximum) {
            throw new IllegalArgumentException("home_evidence_string");
        }
    }

    private static Intent homeIntent() { return new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME); }

    private static IntentFilter homeFilter() {
        IntentFilter filter = new IntentFilter(Intent.ACTION_MAIN);
        filter.addCategory(Intent.CATEGORY_HOME);
        filter.addCategory(Intent.CATEGORY_DEFAULT);
        return filter;
    }

    private static ComponentName resolve(PackageManager manager) {
        ResolveInfo resolved = manager.resolveActivity(homeIntent(), PackageManager.MATCH_DEFAULT_ONLY);
        return resolved == null || resolved.activityInfo == null ? null
                : new ComponentName(resolved.activityInfo.packageName, resolved.activityInfo.name);
    }

    private static boolean isHomeFilter(IntentFilter filter) {
        return filter.hasAction(Intent.ACTION_MAIN) && filter.hasCategory(Intent.CATEGORY_HOME);
    }

    private static boolean isOurFilter(IntentFilter filter) {
        return isHomeFilter(filter) && filter.countActions() == 1 && filter.countCategories() == 2
                && filter.hasCategory(Intent.CATEGORY_DEFAULT) && filter.countDataSchemes() == 0
                && filter.countDataTypes() == 0 && filter.countDataAuthorities() == 0 && filter.countDataPaths() == 0
                && filter.countDataSchemeSpecificParts() == 0
                && filter.getPriority() == 0;
    }

    private static final class Preferences {
        final List<IntentFilter> filters = new ArrayList<IntentFilter>();
        final List<ComponentName> activities = new ArrayList<ComponentName>();
        Preferences(PackageManager manager) {
            manager.getPreferredActivities(filters, activities, null);
            if (filters.size() != activities.size() || filters.size() > 256) throw new IllegalStateException("home_preferences_invalid");
            for (int index = 0; index < filters.size(); index++) {
                if (filters.get(index) == null || activities.get(index) == null) throw new IllegalStateException("home_preferences_invalid");
            }
        }
        int homeCount() {
            int count = 0;
            for (IntentFilter filter : filters) if (isHomeFilter(filter)) count++;
            return count;
        }
        int requireOnlyOurPanelPreferences() {
            int count = 0;
            for (int index = 0; index < activities.size(); index++) {
                ComponentName activity = activities.get(index);
                if (activity == null) throw new IllegalStateException("home_preferences_invalid");
                if (MaintenancePolicy.PACKAGE.equals(activity.getPackageName())) {
                    if (!PANEL.equals(activity) || !isOurFilter(filters.get(index)) || ++count > 1) {
                        throw new IllegalStateException("panel_preferences_changed");
                    }
                }
            }
            return count;
        }

        int removableLegacyCount() {
            List<MaintenanceLegacyHomePolicy.Entry> entries = new ArrayList<MaintenanceLegacyHomePolicy.Entry>();
            for (int index = 0; index < filters.size(); index++) {
                ComponentName component = activities.get(index);
                entries.add(new MaintenanceLegacyHomePolicy.Entry(component.getPackageName(), component.getClassName(),
                        isHomeFilter(filters.get(index)), isOurFilter(filters.get(index))));
            }
            return MaintenanceLegacyHomePolicy.removableCount(MaintenancePolicy.BOOTSTRAP_PACKAGE, MaintenancePolicy.ACTIVITY, entries);
        }

        boolean hasBootstrap() {
            for (ComponentName component : activities) if (MaintenancePolicy.BOOTSTRAP_PACKAGE.equals(component.getPackageName())) return true;
            return false;
        }

        List<String> snapshot(boolean excludeBootstrap) throws Exception {
            List<String> items = new ArrayList<String>();
            for (int index = 0; index < filters.size(); index++) {
                if (!excludeBootstrap || !MaintenancePolicy.BOOTSTRAP_PACKAGE.equals(activities.get(index).getPackageName())) {
                    items.add(itemJson(index, 2048).toString());
                }
            }
            Collections.sort(items);
            return items;
        }

        List<String> withoutPanelSnapshot() throws Exception {
            requireOnlyOurPanelPreferences();
            List<String> items = new ArrayList<String>();
            for (int index = 0; index < filters.size(); index++) {
                if (!MaintenancePolicy.PACKAGE.equals(activities.get(index).getPackageName())) {
                    items.add(itemJson(index, 2048).toString());
                }
            }
            Collections.sort(items);
            return items;
        }

        private JSONObject itemJson(int index, int maximumFilterLength) throws Exception {
            StringWriter text = new StringWriter();
            XmlSerializer xml = Xml.newSerializer();
            xml.setOutput(text);
            xml.startTag(null, "filter");
            filters.get(index).writeToXml(xml);
            xml.endTag(null, "filter");
            xml.flush();
            if (text.getBuffer().length() > maximumFilterLength) throw new IllegalStateException("home_evidence_filter");
            JSONObject item = new JSONObject();
            item.put("component", activities.get(index).flattenToString());
            item.put("filter", text.toString());
            return item;
        }
        JSONArray homeJson() throws Exception { return homeJson(256, Integer.MAX_VALUE); }

        JSONArray homeJson(int maximumEntries, int maximumFilterLength) throws Exception {
            JSONArray saved = new JSONArray();
            for (int index = 0; index < filters.size(); index++) {
                IntentFilter filter = filters.get(index);
                if (!isHomeFilter(filter)) continue;
                if (saved.length() >= maximumEntries || activities.get(index) == null) {
                    throw new IllegalStateException("home_evidence_preferences");
                }
                saved.put(itemJson(index, maximumFilterLength));
            }
            return saved;
        }
    }

    private static final class Adapter implements MaintenanceHomePolicy.Access {
        final Context context;
        final PackageManager manager;
        JSONObject baseline;
        List<String> repairPreferences;
        List<String> repairCandidates;
        boolean repairAttempted;

        Adapter(Context context) { this.context = context; this.manager = context.getPackageManager(); }

        public MaintenanceHomePolicy.State load() throws Exception {
            if (!MaintenanceRoot.existsRoot(STATE_FILE)) return null;
            JSONObject data = new JSONObject(new String(MaintenanceRoot.readRoot(STATE_FILE, 8192), MaintenancePolicy.UTF8));
            if (data.length() != 7 || data.getInt("schema") != 1
                    || !GREETING.flattenToString().equals(data.getString("component"))
                    || !GREETING.flattenToString().equals(data.getString("previousHome"))
                    || !"greeting_component_only".equals(data.getString("scope"))
                    || data.getJSONArray("homePreferences").length() != 0) throw new IllegalStateException("home_state_invalid");
            JSONArray candidates = data.getJSONArray("candidates");
            if (candidates.length() < 2 || candidates.length() > 16) throw new IllegalStateException("home_state_invalid");
            boolean hasPanel = false, hasGreeting = false;
            for (int index = 0; index < candidates.length(); index++) {
                ComponentName candidate = ComponentName.unflattenFromString(candidates.getString(index));
                if (candidate == null) throw new IllegalStateException("home_state_invalid");
                hasPanel |= PANEL.equals(candidate); hasGreeting |= GREETING.equals(candidate);
            }
            if (!hasPanel || !hasGreeting) throw new IllegalStateException("home_state_invalid");
            JSONObject operation = data.getJSONObject("operation");
            if (operation.length() != 2) throw new IllegalStateException("home_state_invalid");
            MaintenanceHomePolicy.State state = new MaintenanceHomePolicy.State(operation.getInt("enabled"), operation.getString("phase"));
            baseline = data;
            return state;
        }

        public MaintenanceHomePolicy.State capture() throws Exception {
            requireInstalled();
            Preferences preferences = new Preferences(manager);
            int enabled = manager.getComponentEnabledSetting(GREETING);
            ComponentName current = resolve(manager);
            if ((enabled != 0 && enabled != 1) || !GREETING.equals(current)) throw new IllegalStateException("home_original_unexpected");
            if (preferences.homeCount() != 0) preferences = clearLegacyPreferences(preferences, enabled);
            JSONObject evidence = new JSONObject();
            evidence.put("schema", 1);
            evidence.put("component", GREETING.flattenToString());
            evidence.put("enabled", enabled);
            evidence.put("resolved", current == null ? "" : current.flattenToString());
            evidence.put("homePreferences", preferences.homeJson());
            MaintenanceRoot.writeRoot("home-operation.json", evidence.toString().getBytes(MaintenancePolicy.UTF8));
            if (preferences.homeCount() != 0) throw new IllegalStateException("prior_home_preference");
            if (preferences.requireOnlyOurPanelPreferences() != 0) throw new IllegalStateException("prior_panel_preference");
            if ((enabled != 0 && enabled != 1) || !GREETING.equals(current)) throw new IllegalStateException("home_original_unexpected");
            baseline = new JSONObject();
            baseline.put("schema", 1);
            baseline.put("component", GREETING.flattenToString());
            baseline.put("previousHome", current.flattenToString());
            baseline.put("homePreferences", new JSONArray());
            baseline.put("candidates", candidateNames(true));
            baseline.put("operation", new JSONObject());
            baseline.put("scope", "greeting_component_only");
            return new MaintenanceHomePolicy.State(enabled, "prepared");
        }

        private Preferences clearLegacyPreferences(Preferences original, int enabled) throws Exception {
            int count = original.removableLegacyCount();
            if (count < 1) throw new IllegalStateException("prior_home_preference");
            requireBootstrapSigner();
            candidateNames(true);
            List<String> before = original.snapshot(false), preserved = original.snapshot(true);
            JSONObject evidence = new JSONObject();
            evidence.put("schema", 1); evidence.put("scope", "bootstrap_home_only");
            evidence.put("package", MaintenancePolicy.BOOTSTRAP_PACKAGE);
            evidence.put("component", new ComponentName(MaintenancePolicy.BOOTSTRAP_PACKAGE, MaintenancePolicy.ACTIVITY).flattenToString());
            evidence.put("greetingEnabled", enabled); evidence.put("resolved", GREETING.flattenToString());
            evidence.put("preferences", original.homeJson(16, 2048));
            byte[] bytes = evidence.toString().getBytes(MaintenancePolicy.UTF8);
            if (!MaintenanceRoot.existsRoot("legacy-home-preferences.json")) MaintenanceRoot.writeRoot("legacy-home-preferences.json", bytes);
            requireSavedLegacyEvidence(new JSONObject(new String(MaintenanceRoot.readRoot("legacy-home-preferences.json", 8192),
                    MaintenancePolicy.UTF8)), evidence);
            requireInstalled(); requireBootstrapSigner();
            if (manager.getComponentEnabledSetting(GREETING) != enabled || !GREETING.equals(resolve(manager))) {
                throw new IllegalStateException("home_original_changed");
            }
            Preferences confirmed = new Preferences(manager);
            if (confirmed.removableLegacyCount() != count || !before.equals(confirmed.snapshot(false))) {
                throw new IllegalStateException("legacy_home_preferences_changed");
            }
            MaintenanceRoot.checkTime();
            ComponentName legacy = new ComponentName(MaintenancePolicy.BOOTSTRAP_PACKAGE, MaintenancePolicy.ACTIVITY);
            Method replace = manager.getClass().getMethod("replacePreferredActivity", IntentFilter.class,
                    int.class, ComponentName[].class, ComponentName.class);
            replace.setAccessible(true);
            replace.invoke(manager, homeFilter(), IntentFilter.MATCH_CATEGORY_EMPTY, new ComponentName[]{legacy}, legacy);
            Preferences normalized = new Preferences(manager);
            if (normalized.removableLegacyCount() != 1 || !preserved.equals(normalized.snapshot(true))) {
                throw new IllegalStateException("legacy_home_normalize_incomplete");
            }
            MaintenanceRoot.checkTime();
            manager.clearPackagePreferredActivities(MaintenancePolicy.BOOTSTRAP_PACKAGE);
            Preferences after = new Preferences(manager);
            if (after.homeCount() != 0 || after.hasBootstrap() || !preserved.equals(after.snapshot(false))) {
                throw new IllegalStateException("legacy_home_cleanup_incomplete");
            }
            if (manager.getComponentEnabledSetting(GREETING) != enabled || !GREETING.equals(resolve(manager))) {
                throw new IllegalStateException("home_original_changed");
            }
            MaintenanceRoot.checkTime();
            return after;
        }

        private void requireSavedLegacyEvidence(JSONObject saved, JSONObject current) throws Exception {
            if (saved.length() != 7 || !(saved.get("schema") instanceof Integer) || saved.getInt("schema") != 1
                    || !(saved.get("greetingEnabled") instanceof Integer)
                    || saved.getInt("greetingEnabled") != current.getInt("greetingEnabled")) {
                throw new IllegalStateException("legacy_home_evidence_changed");
            }
            for (String field : new String[]{"scope", "package", "component", "resolved"}) {
                if (!(saved.get(field) instanceof String) || !current.getString(field).equals(saved.getString(field))) {
                    throw new IllegalStateException("legacy_home_evidence_changed");
                }
            }
            MaintenanceLegacyHomePolicy.requireSubset(legacyEvidenceEntries(saved.getJSONArray("preferences")),
                    legacyEvidenceEntries(current.getJSONArray("preferences")));
        }

        private List<String> legacyEvidenceEntries(JSONArray entries) throws Exception {
            if (entries.length() < 1 || entries.length() > 16) throw new IllegalStateException("legacy_home_evidence_changed");
            String expected = new ComponentName(MaintenancePolicy.BOOTSTRAP_PACKAGE, MaintenancePolicy.ACTIVITY).flattenToString();
            List<String> result = new ArrayList<String>();
            for (int index = 0; index < entries.length(); index++) {
                JSONObject item = entries.getJSONObject(index);
                if (item.length() != 2 || !(item.get("component") instanceof String) || !(item.get("filter") instanceof String)
                        || !expected.equals(item.getString("component")) || item.getString("filter").length() < 1
                        || item.getString("filter").length() > 2048) throw new IllegalStateException("legacy_home_evidence_changed");
                result.add(item.getString("filter"));
            }
            return result;
        }

        private void requireBootstrapSigner() throws Exception {
            PackageInfo installed;
            try { installed = manager.getPackageInfo(MaintenancePolicy.BOOTSTRAP_PACKAGE, PackageManager.GET_SIGNATURES); }
            catch (PackageManager.NameNotFoundException absent) { throw new IllegalStateException("legacy_home_package_missing"); }
            if (!MaintenancePolicy.BOOTSTRAP_PACKAGE.equals(installed.packageName) || installed.versionCode < 1
                    || installed.signatures == null || installed.signatures.length != 1
                    || !MaintenanceSignerPolicy.accepts(MaintenancePolicy.BOOTSTRAP_PACKAGE, installed.versionCode,
                            MaintenanceTrust.sha256(installed.signatures[0].toByteArray()))) {
                throw new IllegalStateException("legacy_home_signature");
            }
        }

        public void save(MaintenanceHomePolicy.State state) throws Exception {
            if (baseline == null) throw new IllegalStateException("home_state_missing");
            JSONObject operation = new JSONObject();
            operation.put("enabled", state.enabled); operation.put("phase", state.phase);
            baseline.put("operation", operation);
            byte[] bytes = baseline.toString().getBytes(MaintenancePolicy.UTF8);
            MaintenanceRoot.writeRoot(STATE_FILE, bytes);
            if (!java.util.Arrays.equals(bytes, MaintenanceRoot.readRoot(STATE_FILE, 8192))) throw new IllegalStateException("home_state_verify");
        }

        public void requireOriginal(MaintenanceHomePolicy.State state) throws Exception {
            MaintenanceRoot.checkTime();
            requireInstalled();
            if (manager.getComponentEnabledSetting(GREETING) != state.enabled || !GREETING.equals(resolve(manager))) {
                throw new IllegalStateException("home_original_changed");
            }
            Preferences preferences = new Preferences(manager);
            if (preferences.homeCount() != 0 || preferences.requireOnlyOurPanelPreferences() != 0) {
                throw new IllegalStateException("prior_home_preference");
            }
            candidateNames(true);
        }

        public void disableGreeting(MaintenanceHomePolicy.State state) throws Exception {
            requireOriginal(state);
            manager.setComponentEnabledSetting(GREETING, PackageManager.COMPONENT_ENABLED_STATE_DISABLED, PackageManager.DONT_KILL_APP);
            if (manager.getComponentEnabledSetting(GREETING) != PackageManager.COMPONENT_ENABLED_STATE_DISABLED) {
                throw new IllegalStateException("home_disable_failed");
            }
        }

        public void addPanelPreference() throws Exception {
            MaintenanceRoot.checkTime();
            if (new Preferences(manager).homeCount() != 0) throw new IllegalStateException("home_preferences_changed");
            List<ResolveInfo> candidates = candidates(false);
            ComponentName[] components = new ComponentName[candidates.size()];
            int match = 0;
            for (int index = 0; index < candidates.size(); index++) {
                ResolveInfo item = candidates.get(index);
                components[index] = new ComponentName(item.activityInfo.packageName, item.activityInfo.name);
                match = Math.max(match, item.match);
            }
            manager.addPreferredActivity(homeFilter(), match, components, PANEL);
        }

        public boolean isPanelHome() throws Exception {
            requireInstalled();
            Preferences preferences = new Preferences(manager);
            int ours = preferences.requireOnlyOurPanelPreferences();
            if (preferences.homeCount() != ours) return false;
            candidates(false);
            return manager.getComponentEnabledSetting(GREETING) == PackageManager.COMPONENT_ENABLED_STATE_DISABLED
                    && PANEL.equals(resolve(manager));
        }

        public void requireRepairable(MaintenanceHomePolicy.State state) throws Exception {
            Preferences preferences = repairPrecondition(state);
            repairPreferences = preferences.snapshot(false);
            repairCandidates = repairCandidateNames();
        }

        public void addRepairPreference(MaintenanceHomePolicy.State state) throws Exception {
            if (repairPreferences == null || repairCandidates == null || repairAttempted) {
                throw new IllegalStateException("home_repair_not_prepared");
            }
            List<ResolveInfo> found = candidates(false);
            ComponentName[] components = new ComponentName[found.size()];
            int match = 0;
            List<String> names = new ArrayList<String>();
            for (int index = 0; index < found.size(); index++) {
                ResolveInfo item = found.get(index);
                components[index] = new ComponentName(item.activityInfo.packageName, item.activityInfo.name);
                names.add(components[index].flattenToString());
                match = Math.max(match, item.match);
            }
            Collections.sort(names);
            Preferences preferences = repairPrecondition(state);
            if (!repairPreferences.equals(preferences.snapshot(false))
                    || !repairCandidates.equals(names) || !repairCandidates.equals(repairCandidateNames())) {
                throw new IllegalStateException("home_repair_snapshot_changed");
            }
            MaintenanceRoot.checkTime();
            repairAttempted = true;
            manager.addPreferredActivity(homeFilter(), match, components, PANEL);
        }

        public void verifyRepair(MaintenanceHomePolicy.State state) throws Exception {
            MaintenanceRoot.checkTime();
            requireInstalled();
            if (!repairAttempted || state == null || !"active".equals(state.phase)
                    || manager.getComponentEnabledSetting(GREETING) != PackageManager.COMPONENT_ENABLED_STATE_DISABLED
                    || !repairCandidates.equals(repairCandidateNames())) {
                throw new IllegalStateException("home_repair_state_changed");
            }
            Preferences preferences = new Preferences(manager);
            if (preferences.homeCount() != 1 || preferences.requireOnlyOurPanelPreferences() != 1
                    || !repairPreferences.equals(preferences.withoutPanelSnapshot())
                    || !PANEL.equals(resolve(manager))) {
                throw new IllegalStateException("home_repair_verify_failed");
            }
            MaintenanceRoot.checkTime();
        }

        public void rollbackRepair(MaintenanceHomePolicy.State state) throws Exception {
            if (!repairAttempted) return;
            MaintenanceRoot.checkTime();
            Preferences preferences = new Preferences(manager);
            if (preferences.requireOnlyOurPanelPreferences() == 0) return;
            List<String> foreign = preferences.withoutPanelSnapshot();
            manager.clearPackagePreferredActivities(MaintenancePolicy.PACKAGE);
            Preferences after = new Preferences(manager);
            if (after.requireOnlyOurPanelPreferences() != 0 || !foreign.equals(after.snapshot(false))) {
                throw new IllegalStateException("home_repair_rollback_failed");
            }
            MaintenanceRoot.checkTime();
        }

        private Preferences repairPrecondition(MaintenanceHomePolicy.State state) throws Exception {
            MaintenanceRoot.checkTime();
            requireInstalled();
            Preferences preferences = new Preferences(manager);
            List<String> expected = new ArrayList<String>();
            if (baseline == null) throw new IllegalStateException("home_repair_state");
            JSONArray saved = baseline.getJSONArray("candidates");
            boolean greeting = false;
            for (int index = 0; index < saved.length(); index++) {
                String name = saved.getString(index);
                if (GREETING.flattenToString().equals(name)) {
                    if (greeting) throw new IllegalStateException("home_repair_candidates_changed");
                    greeting = true;
                } else {
                    if (expected.contains(name)) throw new IllegalStateException("home_repair_candidates_changed");
                    expected.add(name);
                }
            }
            Collections.sort(expected);
            MaintenanceHomePolicy.requireRepairState(state, manager.getComponentEnabledSetting(GREETING),
                    preferences.homeCount(), preferences.requireOnlyOurPanelPreferences(), RESOLVER.equals(resolve(manager)),
                    greeting && expected.equals(repairCandidateNames()));
            MaintenanceRoot.checkTime();
            return preferences;
        }

        private List<String> repairCandidateNames() {
            List<String> names = new ArrayList<String>();
            for (ResolveInfo candidate : candidates(false)) {
                names.add(new ComponentName(candidate.activityInfo.packageName, candidate.activityInfo.name).flattenToString());
            }
            Collections.sort(names);
            return names;
        }

        public void restoreGreeting(MaintenanceHomePolicy.State state) throws Exception {
            int current = manager.getComponentEnabledSetting(GREETING);
            if (current != state.enabled && current != PackageManager.COMPONENT_ENABLED_STATE_DISABLED) {
                throw new IllegalStateException("home_component_changed");
            }
            if (current != state.enabled) manager.setComponentEnabledSetting(GREETING, state.enabled, PackageManager.DONT_KILL_APP);
            if (manager.getComponentEnabledSetting(GREETING) != state.enabled) throw new IllegalStateException("home_restore_failed");
        }

        public void removePanelPreference() {
            Preferences preferences = new Preferences(manager);
            if (preferences.requireOnlyOurPanelPreferences() == 0) return;
            manager.clearPackagePreferredActivities(MaintenancePolicy.PACKAGE);
            if (new Preferences(manager).requireOnlyOurPanelPreferences() != 0) throw new IllegalStateException("home_preference_restore_failed");
        }

        public boolean isOriginalHome(MaintenanceHomePolicy.State state) {
            return manager.getComponentEnabledSetting(GREETING) == state.enabled && GREETING.equals(resolve(manager))
                    && new Preferences(manager).homeCount() == 0;
        }

        private void requireInstalled() throws Exception {
            if (MaintenancePolicy.installedVersion(context) < 1) throw new IllegalStateException("panel_not_installed");
            ActivityInfo panel = manager.getActivityInfo(PANEL, 0);
            ActivityInfo greeting = manager.getActivityInfo(GREETING, PackageManager.GET_DISABLED_COMPONENTS);
            ApplicationInfo kt = greeting.applicationInfo;
            if (!panel.enabled || !panel.exported || !panel.applicationInfo.enabled
                    || !kt.enabled || !greeting.exported) {
                throw new IllegalStateException("home_activity_invalid");
            }
        }

        private List<ResolveInfo> candidates(boolean requireGreeting) {
            List<ResolveInfo> list = manager.queryIntentActivities(homeIntent(), PackageManager.MATCH_DEFAULT_ONLY);
            if (list == null || list.size() < 1 || list.size() > 16) throw new IllegalStateException("home_candidates_invalid");
            boolean panel = false, greeting = false;
            ArrayList<String> seen = new ArrayList<String>();
            for (ResolveInfo item : list) {
                if (item == null || item.activityInfo == null || !item.activityInfo.enabled || !item.activityInfo.applicationInfo.enabled) {
                    throw new IllegalStateException("home_candidates_invalid");
                }
                ComponentName component = new ComponentName(item.activityInfo.packageName, item.activityInfo.name);
                if (seen.contains(component.flattenToString())) throw new IllegalStateException("home_candidates_invalid");
                seen.add(component.flattenToString());
                panel |= PANEL.equals(component); greeting |= GREETING.equals(component);
                if (KT.equals(component.getPackageName()) && !GREETING.equals(component)) throw new IllegalStateException("home_kt_candidate_changed");
            }
            if (!panel || greeting != requireGreeting) throw new IllegalStateException("home_candidates_changed");
            return list;
        }

        private JSONArray candidateNames(boolean requireGreeting) {
            ArrayList<String> names = new ArrayList<String>();
            for (ResolveInfo item : candidates(requireGreeting)) names.add(new ComponentName(item.activityInfo.packageName, item.activityInfo.name).flattenToString());
            Collections.sort(names);
            return new JSONArray(names);
        }
    }
}
