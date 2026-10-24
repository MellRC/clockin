package com.example.clockin;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Log;

/** Records intent submission separately from observed foreground and attendance outcome. */
public final class WeComLauncher {
    public static final String PACKAGE = "com.tencent.wework";
    public static final int NOT_INSTALLED = 0;
    public static final int SUBMITTED = 1;
    public static final int ALREADY_FOREGROUND = 2;
    public static final int BLOCKED = 3;
    public static final int BACKGROUND_BLOCKED = 4;
    private static final String TAG = "AttendanceReminder";
    private static final Handler handler = new Handler(Looper.getMainLooper());
    private static Request pending;
    private WeComLauncher() { }
    public static int launch(Context c) { return launch(c, "manual", java.util.UUID.randomUUID().toString()); }
    public static int launch(Context c, String task) { return launch(c, task, java.util.UUID.randomUUID().toString()); }
    static boolean busy() { return pending != null; }
    static boolean hasRequest(String id) { return pending != null && pending.id.equals(id); }

    static boolean backgroundLaunchBlocked(Context c) {
        if (Build.VERSION.SDK_INT < 29) return false;
        boolean visible = (c instanceof Activity && !((Activity) c).isFinishing()) || MainActivity.visible;
        return !visible && ClockInAccessibilityService.connected() == null;
    }

    static int launch(Context c, String task, String requestId) {
        if (busy()) return BLOCKED;
        Intent intent = c.getPackageManager().getLaunchIntentForPackage(PACKAGE);
        if (intent == null || intent.resolveActivity(c.getPackageManager()) == null) {
            Log.i(TAG, "task=" + task + " launch unavailable: package/entry missing");
            return NOT_INSTALLED;
        }
        ClockInAccessibilityService service = ClockInAccessibilityService.connected();
        boolean visible = (c instanceof Activity && !((Activity)c).isFinishing()) || MainActivity.visible;
        if (backgroundLaunchBlocked(c)) {
            Log.i(TAG, "task=" + task + " BAL condition absent; user notification required");
            return BACKGROUND_BLOCKED;
        }
        ClockInAccessibilityService.Foreground before = service == null ? null : service.foreground();
        if (before != null && PACKAGE.equals(before.pkg)) {
            Log.i(TAG, "task=" + task + " already foreground; counted as open, no new launch");
            if ("manual".equals(task)) ReminderFlow.manualLaunchFinished(c, requestId, true);
            return ALREADY_FOREGROUND;
        }
        Request request = new Request(c.getApplicationContext(), task, requestId,
                intent.resolveActivity(c.getPackageManager()).getClassName(), before);
        // Persist BEFORE calling the launch API, including launches from the manual test button.
        if (!c.getSharedPreferences("launch_requests", Context.MODE_PRIVATE).edit()
                .putString("request", requestId).putString("task", task)
                .putLong("uptime", request.started).putBoolean("foregroundObserved", false)
                .putBoolean("statusNotificationSubmitted", false)
                .putBoolean("confirmed", false).commit()) return BLOCKED;
        pending = request;
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);
        try {
            Context launcher = visible ? c : service;
            launcher.startActivity(intent);
            request.submitted = true;
            handler.postDelayed(request.timeout, TaskPolicy.LAUNCH_CONFIRM_MS);
            Log.i(TAG, "task=" + task + " request=" + requestId + " submitted BAL="
                    + (visible ? "visible_activity_or_notification_tap" : "system_bound_accessibility")
                    + "; foreground unconfirmed");
            return SUBMITTED;
        } catch (RuntimeException e) {
            pending = null;
            Log.i(TAG, "task=" + task + " request=" + requestId + " rejected=" + e.getClass().getSimpleName());
            return BACKGROUND_BLOCKED;
        }
    }

    static void windowEvidence(ClockInAccessibilityService service, long eventTime,
                               String eventPackage, int eventWindow, boolean systemWindowsChanged) {
        Request r = pending;
        if (r == null || !r.submitted || eventTime <= r.started) return;
        ClockInAccessibilityService.Foreground after = service.foreground();
        if (after == null) return;
        // Never read text or node descendants. A window event alone is not sufficient.
        if (!PACKAGE.equals(after.pkg)) {
            // Finishing our lock screen can briefly expose the launcher. Keep observing
            // this bounded request; a new user interaction still cancels attribution.
            return;
        }
        if ((!systemWindowsChanged && !PACKAGE.equals(eventPackage)) || eventWindow != after.windowId) return;
        if (!LaunchEvidence.matches(r.started, SystemClock.uptimeMillis(), eventTime,
                r.before != null && !PACKAGE.equals(r.before.pkg),
                r.before == null ? -1 : r.before.windowId, after.windowId,
                PACKAGE, after.pkg)) return;
        finish(r, true, "focused active WeCom application window after pending request");
    }

    static void userInteraction(long eventTime) {
        Request r = pending;
        if (r != null && eventTime > r.started) finish(r, false, "new user interaction: causality ambiguous");
    }
    static void cancelTask(String id) {
        Request r = pending;
        if (r != null && r.task.equals(id)) {
            handler.removeCallbacks(r.timeout);
            pending = null;
        }
    }
    static void cancelAll(Context c, String reason) {
        if (pending != null) finish(pending, false, reason);
    }
    private static void finish(Request r, boolean confirmed, String reason) {
        if (pending != r) return;
        handler.removeCallbacks(r.timeout);
        pending = null; // Consume first; duplicate/reentrant events cannot vibrate twice.
        r.context.getSharedPreferences("launch_requests", Context.MODE_PRIVATE).edit()
                .putBoolean("foregroundObserved", confirmed).putBoolean("confirmed", false)
                .putString("outcome", reason).commit();
        boolean statusSubmitted = "manual".equals(r.task)
                ? ReminderFlow.manualLaunchFinished(r.context, r.id, confirmed)
                : ReminderFlow.launchFinished(r.context, r.task, r.id, confirmed);
        // The mode-specific status channel supplies reminder-mode vibration. Silent mode
        // deliberately has no vibration. This is not proof of causal attribution or attendance.
        r.context.getSharedPreferences("launch_requests", Context.MODE_PRIVATE).edit()
                .putBoolean("statusNotificationSubmitted", statusSubmitted).commit();
        Log.i(TAG, "task=" + r.task + " request=" + r.id + " foregroundObserved=" + confirmed + " requestAttribution=unavailable"
                + " statusNotificationSubmitted=" + statusSubmitted + " evidence=" + reason + "; attendance unverified");
    }
    private static final class Request {
        final Context context;
        final String task, id, targetClass;
        final long started = SystemClock.uptimeMillis();
        final ClockInAccessibilityService.Foreground before;
        boolean submitted;
        final Runnable timeout;
        Request(Context c, String task, String id, String targetClass, ClockInAccessibilityService.Foreground before) {
            this.context = c; this.task = task; this.id = id; this.targetClass = targetClass; this.before = before;
            timeout = () -> finish(this, false, "foreground confirmation timeout; no synthetic feedback");
        }
    }
}
