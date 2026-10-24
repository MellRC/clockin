package com.example.clockin;

import android.accessibilityservice.AccessibilityService;
import android.content.Context;
import android.provider.Settings;
import android.text.TextUtils;
import android.view.accessibility.*;

public class ClockInAccessibilityService extends AccessibilityService {
    private static ClockInAccessibilityService instance;
    static ClockInAccessibilityService connected() { return instance; }
    @Override protected void onServiceConnected() {
        instance = this;
        AlarmScheduler.repairOnWake(this, true);
        ReminderFlow.restore(this);
    }
    @Override public void onAccessibilityEvent(AccessibilityEvent event) {
        if (event == null) return;
        ReminderRuntime.observe("accessibility_window_unlock_recheck");
        if (event.getEventType() == AccessibilityEvent.TYPE_TOUCH_INTERACTION_START
                || event.getEventType() == AccessibilityEvent.TYPE_VIEW_CLICKED) {
            WeComLauncher.userInteraction(event.getEventTime());
        } else if (event.getEventType() == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED
                || event.getEventType() == AccessibilityEvent.TYPE_WINDOWS_CHANGED) {
            WeComLauncher.windowEvidence(this, event.getEventTime(),
                    String.valueOf(event.getPackageName()), event.getWindowId(),
                    event.getEventType() == AccessibilityEvent.TYPE_WINDOWS_CHANGED);
        }
        // Actual attendance nodes have not been inspected. No result parser is enabled.
        // Never use getText(), search descendants, log page text or perform actions.
    }
    static final class Foreground {
        final String pkg; final int windowId;
        Foreground(String pkg, int id) { this.pkg = pkg; this.windowId = id; }
    }
    Foreground foreground() {
        if (!ReminderRuntime.unlocked(this)) return null;
        try {
            for (AccessibilityWindowInfo w : getWindows()) {
                if (w.getType() != AccessibilityWindowInfo.TYPE_APPLICATION || !w.isActive() || !w.isFocused()) continue;
                AccessibilityNodeInfo root = w.getRoot();
                if (root == null) return null;
                CharSequence pkg = root.getPackageName();
                root.recycle();
                return pkg == null ? null : new Foreground(pkg.toString(), w.getId());
            }
        } catch (RuntimeException ignored) { }
        return null;
    }
    @Override public void onInterrupt() { ReminderFlow.observerDisconnected(this); }
    @Override public void onDestroy() {
        instance = null;
        ReminderFlow.observerDisconnected(this);
        super.onDestroy();
    }
    public static boolean isEnabled(Context context) {
        String enabled = Settings.Secure.getString(context.getContentResolver(), Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
        if (TextUtils.isEmpty(enabled)) return false;
        String expected = context.getPackageName() + "/.ClockInAccessibilityService";
        for (String component : enabled.split(":")) {
            if (component.equalsIgnoreCase(expected) || component.equalsIgnoreCase(context.getPackageName()
                    + "/com.example.clockin.ClockInAccessibilityService")) return true;
        }
        return false;
    }
}
