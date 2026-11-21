package net.quber.afactorymode;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

final class ReturnTriggerPolicy {
    static final String RETURN_SERVICE_CLASS = "net.quber.afactorymode.PanelReturnAccessibilityService";
    static final String RETURN_SERVICE_COMPONENT = "dev.homepanel.community/" + RETURN_SERVICE_CLASS;
    static final String RETURN_CODE = "9999";
    static final int CODE_LENGTH = 4;
    static final long DIGIT_MAX_GAP_MS = 2500L;
    static final long DIGIT_WINDOW_MS = 4000L;
    static final long TRIGGER_COOLDOWN_MS = 3000L;
    static final int MAX_VOICE_RAW_LENGTH = 96;
    static final int MAX_VOICE_NORMALIZED_LENGTH = 24;
    static final int ACTION_DOWN = 0;
    static final int KEYCODE_0 = 7;
    static final int KEYCODE_9 = 16;
    static final int KEYCODE_NUMPAD_0 = 144;
    static final int KEYCODE_NUMPAD_9 = 153;

    private static final String VOICE_CORE = "홈패널로복귀";
    private static final String[] WAKE_PREFIXES = {
        "기가지니야", "기가지니", "지니야", "헤이구글", "오케이구글", "시리야"
    };
    private static final Set<String> VOICE_SUFFIXES = Collections.unmodifiableSet(new HashSet<String>(Arrays.asList(
        "", "해", "해줘", "해요", "해주세요", "하자", "해라", "실행", "실행해", "실행해줘", "이동"
    )));
    private static final Set<String> VOICE_SOURCE_PACKAGES = Collections.unmodifiableSet(new HashSet<String>(Arrays.asList(
        "com.kt.gigagenie.launcher",
        "com.kt.gigagenie.voicerecognition",
        "com.kt.gigagenie.mc",
        "com.kt.gigagenie.ollehtv",
        "com.kt.gigagenie.standbyscreen",
        "com.kt.gigagenie.voice",
        "com.google.android.googlequicksearchbox",
        "com.google.android.katniss"
    )));

    private ReturnTriggerPolicy() {}

    static int digitFromKeyCode(int keyCode) {
        if (keyCode >= KEYCODE_0 && keyCode <= KEYCODE_9) return '0' + (keyCode - KEYCODE_0);
        if (keyCode >= KEYCODE_NUMPAD_0 && keyCode <= KEYCODE_NUMPAD_9) return '0' + (keyCode - KEYCODE_NUMPAD_0);
        return -1;
    }

    static boolean matchesVoiceCommand(String raw) {
        if (raw == null || raw.isEmpty() || raw.length() > MAX_VOICE_RAW_LENGTH) return false;
        StringBuilder normalized = new StringBuilder(raw.length());
        for (int i = 0; i < raw.length(); i++) {
            char c = raw.charAt(i);
            if ((c < 32 && c != '\n' && c != '\r' && c != '\t') || c == 127) return false;
            if (Character.isWhitespace(c) || isIgnoredPunctuation(c)) continue;
            normalized.append(Character.toLowerCase(c));
        }
        String text = normalized.toString();
        if (text.isEmpty() || text.length() > MAX_VOICE_NORMALIZED_LENGTH) return false;
        for (String prefix : WAKE_PREFIXES) {
            if (text.startsWith(prefix)) {
                text = text.substring(prefix.length());
                break;
            }
        }
        if (!text.startsWith(VOICE_CORE)) return false;
        return VOICE_SUFFIXES.contains(text.substring(VOICE_CORE.length()));
    }

    private static boolean isIgnoredPunctuation(char c) {
        return c == '.' || c == ',' || c == '!' || c == '?' || c == '~' || c == '·'
                || c == '\'' || c == '"' || c == '(' || c == ')' || c == '[' || c == ']'
                || c == '<' || c == '>' || c == '-' || c == '_' || c == '/' || c == '\\'
                || c == ':' || c == ';' || c == '“' || c == '”' || c == '‘' || c == '’';
    }

    static boolean allowedVoiceSourcePackage(String packageName) {
        if (packageName == null || packageName.isEmpty()) return false;
        return VOICE_SOURCE_PACKAGES.contains(packageName);
    }

    static boolean validReturnRequest(int schema, String action, String id, String source, long expiresAt) {
        return schema == 1
                && "return_home".equals(action)
                && id != null && id.matches("[0-9a-f]{32}")
                && source != null && source.matches("web|ha|voice|cli")
                && expiresAt >= 1L && expiresAt <= 4102444800L;
    }

    static boolean containsAccessibilityService(String current, String component) {
        if (current == null || current.isEmpty() || component == null || component.isEmpty()) return false;
        for (String entry : splitServices(current)) {
            if (component.equals(entry)) return true;
        }
        return false;
    }

    static String addAccessibilityService(String current, String component) {
        if (component == null || !component.matches("[A-Za-z0-9_.]+/[A-Za-z0-9_.]+")) {
            throw new IllegalArgumentException("accessibility_component");
        }
        List<String> entries = splitServices(current);
        if (!entries.contains(component)) entries.add(component);
        return joinServices(entries);
    }

    static String removeAccessibilityService(String current, String component) {
        if (component == null || !component.matches("[A-Za-z0-9_.]+/[A-Za-z0-9_.]+")) {
            throw new IllegalArgumentException("accessibility_component");
        }
        List<String> entries = splitServices(current);
        List<String> kept = new ArrayList<String>(entries.size());
        for (String entry : entries) {
            if (!component.equals(entry)) kept.add(entry);
        }
        return joinServices(kept);
    }

    private static List<String> splitServices(String current) {
        List<String> result = new ArrayList<String>();
        if (current == null || current.isEmpty()) return result;
        for (String raw : current.split(":")) {
            String item = raw.trim();
            if (!item.isEmpty() && !result.contains(item)) result.add(item);
        }
        return result;
    }

    private static String joinServices(List<String> entries) {
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < entries.size(); i++) {
            if (i > 0) out.append(':');
            out.append(entries.get(i));
        }
        return out.toString();
    }

    static final class DigitDetector {
        private final long[] timestamps = new long[CODE_LENGTH];
        private int count;
        private long lastTriggerAt;

        synchronized void reset() {
            count = 0;
        }

        synchronized boolean onKeyEvent(int action, int keyCode, int repeatCount, long nowMs, boolean panelForeground) {
            if (panelForeground) {
                count = 0;
                return false;
            }
            if (action != ACTION_DOWN || repeatCount != 0) return false;
            int digit = digitFromKeyCode(keyCode);
            if (digit != '9') {
                count = 0;
                return false;
            }
            if (lastTriggerAt > 0L && nowMs >= lastTriggerAt && nowMs - lastTriggerAt < TRIGGER_COOLDOWN_MS) {
                count = 0;
                return false;
            }
            if (count > 0 && (nowMs < timestamps[count - 1] || nowMs - timestamps[count - 1] > DIGIT_MAX_GAP_MS)) {
                count = 0;
            }
            int keepFrom = 0;
            while (keepFrom < count && nowMs - timestamps[keepFrom] > DIGIT_WINDOW_MS) {
                keepFrom++;
            }
            if (keepFrom > 0) {
                int remaining = count - keepFrom;
                System.arraycopy(timestamps, keepFrom, timestamps, 0, remaining);
                count = remaining;
            }
            if (count == CODE_LENGTH) {
                System.arraycopy(timestamps, 1, timestamps, 0, CODE_LENGTH - 1);
                count = CODE_LENGTH - 1;
            }
            timestamps[count++] = nowMs;
            if (count == CODE_LENGTH) {
                count = 0;
                lastTriggerAt = nowMs;
                return true;
            }
            return false;
        }
    }

    static String safeTriggerLabel(String value) {
        if (value == null) return "unknown";
        String lowered = value.toLowerCase(Locale.US);
        return lowered.matches("[a-z0-9_]{1,40}") ? lowered : "unknown";
    }
}
