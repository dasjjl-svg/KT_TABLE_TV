package net.quber.afactorymode;

import android.accessibilityservice.AccessibilityService;
import android.os.SystemClock;
import android.view.KeyEvent;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;
import java.util.List;

public final class PanelReturnAccessibilityService extends AccessibilityService {
    private static final int MAX_INSPECTED_NODES = 32;
    private static final int MAX_INSPECTED_DEPTH = 6;
    private static final int MAX_EVENT_TEXTS = 8;
    private final ReturnTriggerPolicy.DigitDetector digitDetector = new ReturnTriggerPolicy.DigitDetector();
    private int swallowUpKeyCode = -1;
    private long swallowUpUntil;

    @Override
    protected void onServiceConnected() {
        super.onServiceConnected();
        digitDetector.reset();
        swallowUpKeyCode = -1;
        swallowUpUntil = 0L;
    }

    @Override
    public void onInterrupt() {
        digitDetector.reset();
    }

    @Override
    protected boolean onKeyEvent(KeyEvent event) {
        if (event == null || !MaintenancePolicy.PACKAGE.equals(getPackageName())) return false;
        long now = SystemClock.elapsedRealtime();
        int action = event.getAction();
        int keyCode = event.getKeyCode();
        if (action == KeyEvent.ACTION_UP && swallowUpKeyCode == keyCode && now <= swallowUpUntil) {
            swallowUpKeyCode = -1;
            swallowUpUntil = 0L;
            return true;
        }
        boolean matched = digitDetector.onKeyEvent(action, keyCode, event.getRepeatCount(), now,
                PanelReturnLauncher.isForeground());
        if (matched && PanelReturnLauncher.bringToFront(this, "code_9999")) {
            swallowUpKeyCode = keyCode;
            swallowUpUntil = now + 1000L;
            return true;
        }
        return false;
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        if (event == null || !MaintenancePolicy.PACKAGE.equals(getPackageName())
                || PanelReturnLauncher.isForeground()) {
            return;
        }
        CharSequence pkg = event.getPackageName();
        if (pkg == null || !ReturnTriggerPolicy.allowedVoiceSourcePackage(pkg.toString())) return;
        if (matchesEventText(event)) {
            PanelReturnLauncher.bringToFront(this, "voice_ui");
            return;
        }
        AccessibilityNodeInfo source = null;
        try {
            source = event.getSource();
            if (source == null) source = getRootInActiveWindow();
            if (source != null && scanNodeTree(source, 0, new int[]{0})) {
                PanelReturnLauncher.bringToFront(this, "voice_ui");
            }
        } catch (RuntimeException ignored) {
        } finally {
            if (source != null) {
                try { source.recycle(); } catch (RuntimeException ignored) { }
            }
        }
    }

    private static boolean matchesEventText(AccessibilityEvent event) {
        CharSequence description = event.getContentDescription();
        if (description != null && ReturnTriggerPolicy.matchesVoiceCommand(description.toString())) {
            return true;
        }
        List<CharSequence> texts = event.getText();
        if (texts == null) return false;
        int limit = Math.min(texts.size(), MAX_EVENT_TEXTS);
        for (int i = 0; i < limit; i++) {
            CharSequence item = texts.get(i);
            if (item != null && ReturnTriggerPolicy.matchesVoiceCommand(item.toString())) {
                return true;
            }
        }
        return false;
    }

    private static boolean scanNodeTree(AccessibilityNodeInfo node, int depth, int[] visited) {
        if (node == null || depth > MAX_INSPECTED_DEPTH || visited[0] >= MAX_INSPECTED_NODES) return false;
        visited[0]++;
        if (node.isVisibleToUser()) {
            CharSequence text = node.getText();
            if (text != null && ReturnTriggerPolicy.matchesVoiceCommand(text.toString())) return true;
            CharSequence description = node.getContentDescription();
            if (description != null && ReturnTriggerPolicy.matchesVoiceCommand(description.toString())) return true;
        }
        if (depth == MAX_INSPECTED_DEPTH || visited[0] >= MAX_INSPECTED_NODES) return false;
        int children = node.getChildCount();
        for (int i = 0; i < children && visited[0] < MAX_INSPECTED_NODES; i++) {
            AccessibilityNodeInfo child = node.getChild(i);
            if (child == null) continue;
            try {
                if (scanNodeTree(child, depth + 1, visited)) return true;
            } finally {
                try { child.recycle(); } catch (RuntimeException ignored) { }
            }
        }
        return false;
    }
}
