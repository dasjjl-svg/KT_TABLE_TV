package net.quber.afactorymode;

import android.content.Context;
import android.os.DropBoxManager;
import android.os.SystemClock;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.json.JSONArray;
import org.json.JSONObject;

final class StartupCrashEvidence {
    private static final String[] TAGS = {"data_app_crash", "data_app_anr", "data_app_native_crash"};
    private static final long WINDOW_MS = 7L * 24 * 60 * 60 * 1000;
    private static final int TEXT_BYTES = 16384;
    private static final String IDENTIFIER = "[A-Za-z_$][A-Za-z0-9_$]*";
    private static final String CLASS = IDENTIFIER + "(?:\\." + IDENTIFIER + ")+";
    private static final Pattern EXCEPTION = Pattern.compile("^(?:Caused by: )?(" + CLASS + ")(?::.*)?$");
    private static final Pattern FRAME = Pattern.compile("^\\s*at (" + CLASS + ")\\.(" + IDENTIFIER
            + "|<init>|<clinit>)\\(([^()]*)\\)\\s*$");
    private static final Pattern SOURCE = Pattern.compile("^([A-Za-z_$][A-Za-z0-9_$-]*\\.(?:java|kt|scala)|SourceFile)(?::([0-9]{1,10}))?$");
    private final List<Parsed> entries = new ArrayList<Parsed>();
    private final StringBuilder errors = new StringBuilder();
    private long deadline;

    static JSONObject collect(Context context) throws Exception {
        return new StartupCrashEvidence().read(context);
    }

    private JSONObject read(Context context) throws Exception {
        deadline = SystemClock.elapsedRealtime() + 2500;
        long now = System.currentTimeMillis();
        if (now <= 0) { error("clock_unavailable"); return output(); }
        DropBoxManager manager;
        try { manager = (DropBoxManager) context.getSystemService(Context.DROPBOX_SERVICE); }
        catch (Exception unavailable) { error("dropbox_unavailable"); return output(); }
        if (manager == null) { error("dropbox_unavailable"); return output(); }
        long since = Math.max(0L, now - WINDOW_MS);
        for (int window = 0; window < 2; window++) {
            if (window == 1 && since == 0) break;
            long maximum = window == 0 ? now : since;
            int limit = window == 0 ? 32 : 16;
            for (String tag : TAGS) {
                long cursor = window == 0 ? since : 0;
                for (int index = 0; index < limit; index++) {
                    DropBoxManager.Entry entry = null;
                    try {
                        checkTime();
                        entry = manager.getNextEntry(tag, cursor);
                        checkTime();
                        if (entry == null) break;
                        long time = entry.getTimeMillis();
                        if (time <= cursor || time > now || !tag.equals(entry.getTag())) {
                            error("entry_metadata_invalid"); break;
                        }
                        if (time > maximum) break;
                        cursor = time;
                        if ((entry.getFlags() & DropBoxManager.IS_TEXT) != 0) {
                            String text = entry.getText(TEXT_BYTES);
                            checkTime();
                            if (text == null) error("entry_text_unavailable");
                            else {
                                Parsed parsed = parse(tag, time, text);
                                if (parsed != null) {
                                    add(parsed);
                                    if (window == 1) error("early_timestamp_observed");
                                }
                            }
                        }
                        if (index == limit - 1) error(window == 0 ? "scan_limit" : "early_scan_limit");
                    } catch (SecurityException denied) {
                        error("dropbox_permission_denied"); return output();
                    } catch (IllegalStateException unavailable) {
                        error("collection_interrupted"); return output();
                    } catch (Exception unavailable) {
                        error("entry_unavailable"); break;
                    } finally {
                        if (entry != null) entry.close();
                    }
                }
            }
        }
        return output();
    }

    private void checkTime() {
        MaintenanceRoot.checkTime();
        if (SystemClock.elapsedRealtime() >= deadline) throw new IllegalStateException("crash_timeout");
    }

    private void add(Parsed parsed) {
        entries.add(parsed);
        Collections.sort(entries, new Comparator<Parsed>() {
            public int compare(Parsed left, Parsed right) {
                return left.time < right.time ? 1 : left.time == right.time ? 0 : -1;
            }
        });
        if (entries.size() > 4) { entries.remove(entries.size() - 1); error("entries_truncated"); }
    }

    static Parsed parse(String tag, long time, String text) throws Exception {
        if (!knownTag(tag) || time < 0 || text == null || text.length() > TEXT_BYTES
                || text.getBytes(MaintenancePolicy.UTF8).length > TEXT_BYTES) return null;
        String[] lines = text.split("\\r?\\n", -1);
        if (lines.length == 0 || !lines[0].startsWith("Process: ")) return null;
        String name = lines[0].substring(9);
        if (!MaintenancePolicy.PACKAGE.equals(name) && !MaintenancePolicy.BOOTSTRAP_PACKAGE.equals(name)) return null;
        for (int index = 1; index < lines.length; index++) if (lines[index].startsWith("Process:")) return null;
        Parsed result = new Parsed(tag, time, name);
        boolean allowedSection = !"data_app_anr".equals(tag);
        for (int index = 1; index < lines.length; index++) {
            String line = lines[index];
            if (line.length() > 2048) continue;
            if (line.startsWith("Cmd line: ")) {
                allowedSection = name.equals(line.substring(10));
                continue;
            }
            if (line.startsWith("----- end ")) { allowedSection = false; continue; }
            if (!allowedSection) continue;
            Matcher exception = EXCEPTION.matcher(line);
            if (exception.matches()) {
                String type = exception.group(1);
                if (type.length() <= 160 && (type.endsWith("Exception") || type.endsWith("Error") || type.endsWith("Throwable"))) {
                    if ("unknown".equals(result.exception) || line.startsWith("Caused by: ")) {
                        result.exception = type;
                        result.frames.clear();
                    }
                }
                continue;
            }
            if (result.frames.size() >= 12) continue;
            Matcher frame = FRAME.matcher(line);
            if (!frame.matches() || frame.group(1).length() > 160 || frame.group(2).length() > 160) continue;
            String source = frame.group(3);
            String file = "unknown";
            int number = -1;
            if ("Native Method".equals(source)) number = -2;
            else if (!"Unknown Source".equals(source)) {
                Matcher location = SOURCE.matcher(source);
                if (!location.matches() || location.group(1).length() > 160) continue;
                file = location.group(1);
                if (location.group(2) != null) {
                    try { number = Integer.parseInt(location.group(2)); }
                    catch (NumberFormatException invalid) { continue; }
                }
            }
            JSONObject value = new JSONObject();
            value.put("className", frame.group(1)); value.put("methodName", frame.group(2));
            value.put("fileName", file); value.put("lineNumber", number);
            result.frames.add(value);
        }
        return result;
    }

    private static boolean knownTag(String value) {
        for (String tag : TAGS) if (tag.equals(value)) return true;
        return false;
    }

    private JSONObject output() throws Exception {
        for (;;) {
            JSONObject result = new JSONObject();
            JSONArray values = new JSONArray();
            for (Parsed entry : entries) values.put(entry.json());
            result.put("entries", values); result.put("error", errors.toString());
            if (result.toString().getBytes(MaintenancePolicy.UTF8).length <= 4096) return result;
            error("output_truncated");
            boolean reduced = false;
            for (int index = entries.size() - 1; index >= 0; index--) {
                List<JSONObject> frames = entries.get(index).frames;
                if (!frames.isEmpty()) { frames.remove(frames.size() - 1); reduced = true; break; }
            }
            if (!reduced && !entries.isEmpty()) entries.remove(entries.size() - 1);
        }
    }

    private void error(String value) {
        if (errors.indexOf(value) >= 0 || errors.length() + value.length() + (errors.length() == 0 ? 0 : 1) > 120) return;
        if (errors.length() > 0) errors.append(',');
        errors.append(value);
    }

    static final class Parsed {
        final String tag;
        final long time;
        final String name;
        final List<JSONObject> frames = new ArrayList<JSONObject>();
        String exception = "unknown";
        Parsed(String tag, long time, String name) { this.tag = tag; this.time = time; this.name = name; }
        JSONObject json() throws Exception {
            JSONObject result = new JSONObject();
            result.put("timeMs", time); result.put("package", name); result.put("tag", tag);
            result.put("exception", exception); result.put("frames", new JSONArray(frames));
            return result;
        }
    }
}
