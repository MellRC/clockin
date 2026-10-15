package com.example.clockin;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.GestureDescription;
import android.content.Context;
import android.graphics.Path;
import android.graphics.Rect;
import android.os.Handler;
import android.os.Looper;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;

public class ClockInAccessibilityService extends AccessibilityService {
    private static volatile ClockInAccessibilityService instance;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private boolean launchPending;

    @Override public void onServiceConnected() { super.onServiceConnected(); instance = this; }
    @Override public void onAccessibilityEvent(AccessibilityEvent event) { }
    @Override public void onInterrupt() { }

    @Override public void onDestroy() {
        if (instance == this) instance = null;
        handler.removeCallbacksAndMessages(null);
        super.onDestroy();
    }

    public static boolean isEnabled(Context context) {
        if (instance != null) return true;
        String enabled = android.provider.Settings.Secure.getString(
                context.getContentResolver(), android.provider.Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
        return enabled != null && enabled.contains(context.getPackageName());
    }

    public static boolean prepareLaunch() {
        ClockInAccessibilityService service = instance;
        if (service == null || service.launchPending) return false;
        service.launchPending = true;
        service.performGlobalAction(GLOBAL_ACTION_RECENTS);
        service.handler.postDelayed(service::dismissWeComThenLaunch, 650L);
        return true;
    }

    private void dismissWeComThenLaunch() {
        AccessibilityNodeInfo root = getRootInActiveWindow();
        AccessibilityNodeInfo label = findWeComLabel(root);
        if (label == null) {
            launchPending = false;
            WeComLauncher.launch(this);
            return;
        }
        Rect bounds = new Rect();
        label.getBoundsInScreen(bounds);
        AccessibilityNodeInfo card = label;
        for (int i = 0; i < 5 && card.getParent() != null; i++) {
            AccessibilityNodeInfo parent = card.getParent();
            Rect candidate = new Rect();
            parent.getBoundsInScreen(candidate);
            if (candidate.height() > bounds.height() * 3 && candidate.width() > bounds.width()) {
                bounds = candidate;
                break;
            }
            card = parent;
        }
        if (bounds.isEmpty()) {
            launchPending = false;
            WeComLauncher.launch(this);
            return;
        }
        float x = bounds.centerX();
        float startY = Math.min(bounds.bottom - 20, bounds.centerY() + bounds.height() / 4f);
        float endY = Math.max(32f, bounds.top - 120f);
        Path path = new Path();
        path.moveTo(x, startY);
        path.lineTo(x, endY);
        GestureDescription gesture = new GestureDescription.Builder()
                .addStroke(new GestureDescription.StrokeDescription(path, 0, 350)).build();
        dispatchGesture(gesture, new GestureResultCallback() {
            @Override public void onCompleted(GestureDescription g) { launchAfterRecents(); }
            @Override public void onCancelled(GestureDescription g) { launchAfterRecents(); }
        }, null);
    }

    private void launchAfterRecents() {
        handler.postDelayed(() -> {
            launchPending = false;
            WeComLauncher.launch(this);
        }, 400L);
    }

    private AccessibilityNodeInfo findWeComLabel(AccessibilityNodeInfo node) {
        if (node == null) return null;
        CharSequence text = node.getText();
        CharSequence desc = node.getContentDescription();
        if (matches(text) || matches(desc)) return AccessibilityNodeInfo.obtain(node);
        for (int i = 0; i < node.getChildCount(); i++) {
            AccessibilityNodeInfo found = findWeComLabel(node.getChild(i));
            if (found != null) return found;
        }
        return null;
    }

    private boolean matches(CharSequence value) {
        return value != null && (value.toString().contains("企业微信")
                || value.toString().equalsIgnoreCase("WeCom"));
    }
}
