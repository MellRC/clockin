package com.example.clockin;

import android.app.Activity;
import android.app.KeyguardManager;
import android.content.Intent;
import android.graphics.Color;
import android.os.Build;
import android.os.Bundle;
import android.util.Log;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;
import java.lang.ref.WeakReference;

/** Native full-screen reminder surface. It only requests system unlock; Runtime owns task decisions. */
public final class LockReminderActivity extends Activity {
    private static final String TAG = "AttendanceReminder";
    private static WeakReference<LockReminderActivity> current = new WeakReference<>(null);
    private String taskId;
    private boolean pageReady;
    private boolean unlockRequestInFlight;
    private boolean dismissing;
    private LockReminderSwipeLayout swipeRoot;
    private LockReminderContentView contentView;

    @Override protected void onCreate(Bundle saved) {
        super.onCreate(saved);
        configureWindow();
        current = new WeakReference<>(this);
        display(getIntent());
    }

    private void configureWindow() {
        Window window = getWindow();
        window.setStatusBarColor(Color.TRANSPARENT);
        window.setNavigationBarColor(Color.TRANSPARENT);
        window.addFlags(WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED);
        if (Build.VERSION.SDK_INT >= 27) setShowWhenLocked(true);
        if (Build.VERSION.SDK_INT >= 35) {
            window.getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                    | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION);
        }
    }

    @Override protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        display(intent);
    }

    private void display(Intent intent) {
        stopVisuals();
        pageReady = false;
        unlockRequestInFlight = false;
        dismissing = false;
        String candidate = intent == null ? null : intent.getStringExtra(ReminderActionReceiver.TASK);
        if (ReminderFlow.lockReminderDetails(this, candidate) == null) {
            taskId = candidate;
            Log.i(TAG, "task=" + safeId(candidate) + " lock reminder rejected before drawing " + screenState());
            finish();
            return;
        }

        taskId = candidate;
        setTurnScreenOn(true);
        if (Build.VERSION.SDK_INT < 27) getWindow().addFlags(WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON);
        setContentView(R.layout.activity_lock_reminder);
        swipeRoot = findViewById(R.id.lock_reminder_swipe_root);
        contentView = findViewById(R.id.lock_reminder_content);
        swipeRoot.setListener(new LockReminderSwipeLayout.Listener() {
            @Override public void onGestureStarted() { if (contentView != null) contentView.pauseSweep(); }
            @Override public void onGestureSettled() { if (pageReady && !unlockRequestInFlight) startVisuals(); }
            @Override public boolean onUnlockRequested(String source) { return requestUnlock(source); }
        });
        contentView.setUnlockAction(view -> requestUnlock("hint_tap"));
        contentView.requestApplyInsets();
        pageReady = true;
        Log.i(TAG, "task=" + safeId(taskId) + " native reminder visible content=title+unlock hint " + screenState());
        swipeRoot.getViewTreeObserver().addOnPreDrawListener(new android.view.ViewTreeObserver.OnPreDrawListener() {
            private boolean logged;
            @Override public boolean onPreDraw() {
                if (!logged) {
                    logged = true;
                    swipeRoot.getViewTreeObserver().removeOnPreDrawListener(this);
                    Log.i(TAG, "task=" + safeId(taskId) + " first reminder draw observed " + screenState());
                }
                return true;
            }
        });
        ReminderRuntime.attach(this);
    }

    private boolean requestUnlock(String source) {
        if (!pageReady || unlockRequestInFlight) {
            Log.i(TAG, "task=" + safeId(taskId) + " duplicate or invalid unlock request source=" + source);
            return false;
        }
        if (!ReminderFlow.requestUnlockFromReminder(this, taskId, source)) return false;
        KeyguardManager keyguard = getSystemService(KeyguardManager.class);
        if (keyguard == null) {
            Log.w(TAG, "task=" + safeId(taskId) + " KeyguardManager unavailable; retry enabled");
            return false;
        }
        unlockRequestInFlight = true;
        Log.i(TAG, "task=" + safeId(taskId) + " requesting system Keyguard dismissal source=" + source + " " + screenState());
        try {
            keyguard.requestDismissKeyguard(this, new KeyguardManager.KeyguardDismissCallback() {
                @Override public void onDismissSucceeded() {
                    Log.i(TAG, "task=" + safeId(taskId) + " system dismiss callback succeeded; Runtime recheck required " + screenState());
                    ReminderRuntime.observe("lock_reminder_unlock_callback");
                    refresh();
                    if (!isFinishing() && pageReady) enableUnlockRetry();
                }
                @Override public void onDismissCancelled() {
                    Log.i(TAG, "task=" + safeId(taskId) + " system verification cancelled; no launch " + screenState());
                    enableUnlockRetry();
                }
                @Override public void onDismissError() {
                    Log.w(TAG, "task=" + safeId(taskId) + " system unlock request failed; no launch " + screenState());
                    enableUnlockRetry();
                }
            });
        } catch (RuntimeException error) {
            Log.w(TAG, "task=" + safeId(taskId) + " system unlock request threw; retry enabled", error);
            enableUnlockRetry();
            return false;
        }
        return true;
    }

    private void enableUnlockRetry() {
        unlockRequestInFlight = false;
        if (swipeRoot != null) swipeRoot.unlockRequestFinished();
    }

    @Override protected void onResume() {
        super.onResume();
        ReminderRuntime.observe("lock_reminder_resume_recheck");
        if (!pageReady) return;
        if (ReminderFlow.lockReminderDetails(this, taskId) == null) {
            closePage("task, mode, or device state no longer eligible");
            return;
        }
        if (!unlockRequestInFlight) startVisuals();
    }

    @Override protected void onPause() {
        stopVisuals();
        // A system credential surface can pause this Activity; pause is not an unlock result.
        super.onPause();
    }

    @Override public void onBackPressed() {
        if (pageReady && ReminderFlow.collapseLockReminder(this, taskId, "system_back")) {
            dismissing = true;
            closePage("system back dismissed reminder");
            return;
        }
        super.onBackPressed();
    }

    private void startVisuals() { if (contentView != null && pageReady) contentView.startSweep(); }
    private void stopVisuals() { if (contentView != null) contentView.pauseSweep(); }

    static void refresh() {
        LockReminderActivity activity = current.get();
        if (activity != null && activity.pageReady
                && ReminderFlow.lockReminderDetails(activity, activity.taskId) == null)
            activity.closePage("task or mode invalidated");
    }

    static void onDismissPersisted(String id) {
        LockReminderActivity activity = current.get();
        if (activity != null && id != null && id.equals(activity.taskId)) activity.dismissing = true;
    }

    private void closePage(String reason) {
        if (isFinishing()) return;
        pageReady = false;
        unlockRequestInFlight = false;
        stopVisuals();
        Log.i(TAG, "task=" + safeId(taskId) + " page closed reason=" + reason + " " + screenState());
        finish();
    }

    private String screenState() {
        android.os.PowerManager power = getSystemService(android.os.PowerManager.class);
        KeyguardManager keyguard = getSystemService(KeyguardManager.class);
        return "interactive=" + (power != null && power.isInteractive()) + " keyguard="
                + (keyguard != null && keyguard.isKeyguardLocked()) + " deviceLocked="
                + (keyguard != null && keyguard.isDeviceLocked());
    }

    private String safeId(String id) { return id == null ? "none" : id; }

    @Override protected void onDestroy() {
        pageReady = false;
        stopVisuals();
        if (swipeRoot != null) swipeRoot.animate().cancel();
        if (current.get() == this) current.clear();
        super.onDestroy();
    }
}
