package com.example.clockin;

import android.app.AlarmManager;
import android.app.KeyguardManager;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.os.PowerManager;
import android.os.Vibrator;
import android.util.Log;

import org.json.JSONException;
import org.json.JSONObject;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

/** Single entry point for trigger, unlock, notification and user actions. */
public final class ReminderFlow {
    public static final String MODE_REMINDER = "reminder";
    public static final String MODE_SILENT = "silent";

    public static final String PENDING = "pending";
    public static final String WAITING_UNLOCK = "waiting_unlock";
    public static final String REQUESTING_OPEN = "requesting_open";
    public static final String WAITING_RESULT = "waiting_result";
    public static final String WAITING_USER = "waiting_user";
    public static final String ENDED = "ended";
    public static final String EXPIRED = "expired";

    public static final String RESULT_UNVERIFIED = "unverified";
    public static final String RESULT_COMPLETE = "confirmed_complete";
    public static final String RESULT_INCOMPLETE = "explicitly_incomplete";
    public static final String RESULT_UNKNOWN = "unable_to_confirm";

    private static final String TAG = "AttendanceReminder";
    private static final String PREF = "attendance_tasks_v2";
    private static final String LEGACY_PREF = "attendance_reminder_flow";
    private static final String REMINDER_CHANNEL = "attendance_task_reminder_v3";
    static final String LOCK_CHANNEL = "attendance_lock_reminder_v1";
    private static final String SILENT_CHANNEL = "attendance_task_silent_v2";
    private static final String FOREGROUND_REMINDER_CHANNEL = "attendance_foreground_status_reminder_v2";
    private static final String FOREGROUND_SILENT_CHANNEL = "attendance_foreground_status_silent_v2";
    private static final int NOTIFICATION_ID = 611;
    private static final int EXPIRY_REQUEST_BASE = 17000;
    private static final int VIBRATION_REQUEST_BASE = 33000;
    private static boolean initialized;
    private static final java.util.Set<String> locationChecks = new java.util.HashSet<>();
    private static String vibrationOwner = "";
    private static final DateTimeFormatter CLOCK = DateTimeFormatter.ofPattern("HH:mm");

    private ReminderFlow() { }

    public static synchronized void initialize(Context context) {
        Context c = context.getApplicationContext();
        ensureChannels(c);
        if (!initialized) {
            initialized = true;
            stopAllVibration(c);
        }
        android.content.SharedPreferences p = prefs(c);
        if (!p.getBoolean("notification_v3_migrated", false)) {
            NotificationManager manager = c.getSystemService(NotificationManager.class);
            if (manager != null) { manager.cancel(611); manager.cancel(511); }
            p.edit().putBoolean("notification_v3_migrated", true).commit();
        }
        if (!p.getBoolean("legacy_migrated", false)) {
            Vibrator legacyVibrator = c.getSystemService(Vibrator.class);
            if (legacyVibrator != null) legacyVibrator.cancel();
            NotificationManager nm = c.getSystemService(NotificationManager.class);
            if (nm != null) {
                nm.cancel(NOTIFICATION_ID);
                nm.cancel(511);
            }
            // Keep legacy records intact. A day-level flag cannot safely be mapped to a task.
            p.edit().putBoolean("legacy_migrated", true).commit();
            log("none", "legacy records retained; only explicit v2 task identities drive execution");
        }
    }

    public static synchronized void triggered(Context context, int type, long workdayEpoch) {
        Context c = context.getApplicationContext();
        initialize(c);
        LocalDate workday = LocalDate.ofEpochDay(workdayEpoch);
        Window window = window(c, type, workday);
        long now = System.currentTimeMillis();
        String id = taskId(workdayEpoch, type, window.start, window.end);
        log(id, "TRIGGER " + screen(c));
        if (!AttendanceRules.isEnabled(c) || !AttendanceRules.isWorkday(c, workday)) {
            log(id, "ignored: disabled or non-workday");
            return;
        }
        if (now < window.start || now >= window.end) {
            log(id, "ignored outside half-open validity interval [start,end)");
            return;
        }

        Task task = readTask(c, id);
        if (type < AlarmScheduler.ARRIVAL || type > AlarmScheduler.EVENING_OUT) return;
        if (type == AlarmScheduler.ARRIVAL && !OfficeLocation.currentInside(c)) return;
        if (task == null) {
            task = new Task(id, type, workdayEpoch, window.start, window.end);
            if (!saveTask(c, task, "task created")) return;
        }
        if (!locationSatisfied(c, task)) {
            log(id, "trigger rejected: fresh company location required");
            return;
        }
        if (RESULT_COMPLETE.equals(task.result) || EXPIRED.equals(task.state) || ENDED.equals(task.state)) {
            log(id, "duplicate trigger ignored result=" + task.result + " state=" + task.state);
            return;
        }
        if (!WAITING_UNLOCK.equals(task.state) && !REQUESTING_OPEN.equals(task.state)
                && !WAITING_RESULT.equals(task.state) && !WAITING_USER.equals(task.state)) {
            task.state = WAITING_UNLOCK;
            saveTask(c, task, "trigger accepted");
        } else {
            log(id, "duplicate trigger deduplicated state=" + task.state);
        }
        scheduleExpiry(c, task);
        ReminderRuntime.attach(c);
        task = readTask(c, id); // Registration recheck may have consumed the task.
        if (!WAITING_UNLOCK.equals(task.state)) return;

        if (MODE_SILENT.equals(AttendanceRules.reminderMode(c))) {
            stopVibrationFor(c, id);
            notifySilentOnce(c, task);
            if (!isNotificationAllowed(c)) scheduleUnlockCheck(c, task);
            tick(c);
            return;
        }

        if (unlocked(c)) {
            processUnlock(c, task, "trigger while already unlocked");
        } else {
            postReminder(c, task, "解锁后将尝试打开企业微信。", true);
            startVibration(c, id);
            if (!isNotificationAllowed(c)) {
                log(id, "notification unavailable; unlock will still try to open WeCom");
                scheduleUnlockCheck(c, task);
            }
            if (unlocked(c)) processUnlock(c, task, "post-trigger unlock recheck");
        }
    }

    /** Open the app during a live window and start that slot if it has not started. */
    public static synchronized void catchUpOpen(Context context) {
        Context c = context.getApplicationContext();
        if (!AttendanceRules.isEnabled(c)) return;
        LocalDate today = LocalDate.now();
        if (!AttendanceRules.isWorkday(c, today)) return;
        long now = System.currentTimeMillis();
        for (int type = AlarmScheduler.ARRIVAL; type <= AlarmScheduler.EVENING_OUT; type++) {
            Window window = window(c, type, today);
            if (!TaskPolicy.within(now, window.start, window.end)) continue;
            if (readTask(c, taskId(today.toEpochDay(), type, window.start, window.end)) != null) continue;
            if (type == AlarmScheduler.ARRIVAL) {
                Intent service = new Intent(c, LocationCheckService.class)
                        .putExtra(AlarmScheduler.EXTRA_DAY, today.toEpochDay())
                        .putExtra("attempt", 0);
                try {
                    c.startForegroundService(service);
                } catch (RuntimeException e) {
                    AlarmScheduler.retryArrival(c, today, 1);
                }
                continue;
            }
            triggered(c, type, today.toEpochDay());
        }
    }

    public static synchronized void unlockedEvent(Context context, String source) {
        Context c = context.getApplicationContext();
        initialize(c);
        log("none", "UNLOCK event source=" + source + " " + screen(c));
        if (!unlocked(c)) {
            log("none", "unlock event rejected by live interactive/keyguard check");
            return;
        }
        stopAllVibration(c); // Always stop the pulse and its scheduling before validation.
        prefs(c).edit().putLong("unlock_generation", unlockGeneration(c) + 1).commit();
        long now = System.currentTimeMillis();
        for (Task task : allTasks(c)) {
            if (!WAITING_UNLOCK.equals(task.state)) continue;
            if (now >= task.end) {
                expireTask(c, task, "expired before unlock");
                continue;
            }
            if (now < task.start) continue;
            if (!AttendanceRules.isEnabled(c)
                    || !AttendanceRules.isWorkday(c, LocalDate.ofEpochDay(task.workday))) {
                endTask(c, task, "task disabled or workday rule no longer applies");
                continue;
            }
            processUnlock(c, task, source);
        }
    }

    public static synchronized void expire(Context context, String taskId) {
        Context c = context.getApplicationContext();
        Task task = readTask(c, taskId);
        if (task == null || ENDED.equals(task.state) || EXPIRED.equals(task.state)) return;
        if (System.currentTimeMillis() < task.end) {
            scheduleExpiry(c, task);
            return;
        }
        expireTask(c, task, "validity interval ended");
        reconcile(c);
    }

    public static synchronized void stop(Context context, String taskId) {
        Context c = context.getApplicationContext();
        Task task = readTask(c, taskId);
        if (task == null || !isActive(task.state)) {
            log(taskId, "stale stop action ignored");
            return;
        }
        endTask(c, task, "reminder stopped by user");
        reconcile(c);
    }

    /** Notification tap and unlock event both enter the same launch gate. */
    public static synchronized void openAction(Context context, String taskId) {
        Context c = context.getApplicationContext();
        initialize(c);
        Task task = readTask(c, taskId);
        if (task == null || !isActive(task.state)) {
            log(taskId, "stale notification tap ignored");
            cancelTaskNotification(c, taskId);
            return;
        }
        // Keep the same notification so launch progress/results can update in place.
        // A swipe still dismisses it permanently for this task.
        log(taskId, "notification clicked; using common launch gate");
        requestOpen(context, task, "notification click", false, true);
    }

    public static synchronized void retryOpen(Context context, String taskId) {
        Context c = context.getApplicationContext();
        Task task = readTask(c, taskId);
        if (task == null || !isActive(task.state)) return;
        requestOpen(context, task, "in-app user action", false, true);
    }

    public static synchronized void confirmComplete(Context context, String taskId) {
        Context c = context.getApplicationContext();
        Task task = readTask(c, taskId);
        if (task == null || !isActive(task.state) || !validNow(c, task)) return;
        WeComLauncher.cancelTask(taskId);
        task.result = RESULT_COMPLETE;
        task.resultSource = "user_manual";
        task.resultAt = System.currentTimeMillis();
        task.manualConfirmedAt = task.resultAt;
        task.state = ENDED;
        saveTask(c, task, "manual completion confirmation");
        stopVibrationFor(c, taskId);
        cancelTaskNotification(c, taskId);
        cancelExpiry(c, taskId);
        reconcile(c);
    }

    /** A future evidence parser may call this only with date/type/time-matched page evidence. */
    static synchronized void recordVerifiedResult(Context context, String taskId, String requestId,
            long workday, int type, long clockedAt, boolean complete) {
        Context c = context.getApplicationContext();
        Task task = readTask(c, taskId);
        if (task == null || !validNow(c, task) || !isActive(task.state)
                || RESULT_COMPLETE.equals(task.result) || task.attempts == 0 || requestId == null || requestId.isEmpty()
                || !requestId.equals(task.requestId) || workday != task.workday || type != task.type
                || !TaskPolicy.within(clockedAt, task.start, task.end) || clockedAt > System.currentTimeMillis()) return;
        WeComLauncher.cancelTask(taskId);
        task.launch = "waiting_user";
        task.lastUnlockGeneration = unlockGeneration(c);
        task.result = complete ? RESULT_COMPLETE : RESULT_INCOMPLETE;
        task.resultSource = "accessibility_verified_page";
        task.evidence = "matched_workday_type_time_and_request";
        task.automaticResult = task.result;
        task.automaticResultAt = System.currentTimeMillis();
        task.resultAt = System.currentTimeMillis();
        if (complete) {
            task.state = ENDED;
            stopVibrationFor(c, taskId);
            cancelTaskNotification(c, taskId);
            cancelExpiry(c, taskId);
        } else {
            task.state = WAITING_UNLOCK;
        }
        saveTask(c, task, "verified attendance result=" + task.result);
        reconcile(c);
    }

    public static synchronized void cancel(Context context, String reason) {
        Context c = context.getApplicationContext();
        initialize(c);
        for (Task task : allTasks(c)) {
            if (!isActive(task.state)) continue;
            stopVibrationFor(c, task.id);
            task.state = ENDED;
            saveTask(c, task, reason);
            WeComLauncher.cancelTask(task.id);
            cancelTaskNotification(c, task.id);
            cancelExpiry(c, task.id);
        }
        AlarmScheduler.cancelAll(c);
        ReminderRuntime.detach();
        stopAllVibration(c);
        LockReminderActivity.refresh();
        NotificationManager nm = c.getSystemService(NotificationManager.class);
        if (nm != null) nm.cancel(NOTIFICATION_ID);
        prefs(c).edit().remove("notification_task_id").commit();
    }

    public static synchronized void onModeChanged(Context context, String mode) {
        Context c = context.getApplicationContext();
        initialize(c);
        stopAllVibration(c);
        // Do not create a launch or a new notification during a mode change.
        for (Task task : allTasks(c)) {
            if (!isActive(task.state)) continue;
            if (!validNow(c, task)) { expireTask(c, task, "invalid at mode switch"); continue; }
            cancelFullScreen(c, task.id);
            if (task.notificationAttempted)
                postActionNotification(c, task, MODE_SILENT.equals(mode), notificationMessage(task), false);
        }
        LockReminderActivity.refresh();
        tick(c);
        log("none", "mode changed=" + mode + "; completion and notification ledger preserved");
    }

    public static synchronized void restore(Context context) {
        restore(context, true);
    }

    static synchronized void restore(Context context, boolean retryIfUnlocked) {
        Context c = context.getApplicationContext();
        initialize(c);
        for (Task task : allTasks(c)) {
            if (!isActive(task.state)) continue;
            recoverInFlight(c, task);
            if (!validNow(c, task)) { expireTask(c, task, "invalid after restore"); continue; }
            scheduleExpiry(c, task);
            postActionNotification(c, task, MODE_SILENT.equals(AttendanceRules.reminderMode(c)),
                    notificationMessage(task), WAITING_UNLOCK.equals(task.state) && !unlocked(c));
        }
        if (hasActive(c)) ReminderRuntime.attach(c);
        if (retryIfUnlocked && MODE_REMINDER.equals(AttendanceRules.reminderMode(c)) && unlocked(c)) {
            for (Task task : allTasks(c)) if (WAITING_UNLOCK.equals(task.state)
                    && RESULT_UNVERIFIED.equals(task.result) && task.attempts == 0 && validNow(c, task))
                processUnlock(c, task, "reminder restore registration recheck");
        }
        tick(c);
    }

    static synchronized void tick(Context c) {
        if (c == null) return;
        LockReminderActivity.refresh();
        Task vibrating = null;
        long next = Long.MAX_VALUE;
        boolean active = false;
        for (Task t : allTasks(c)) {
            if (!isActive(t.state)) continue;
            if (!validNow(c, t)) { expireTask(c, t, "deadline/rules check"); continue; }
            active = true;
            next = Math.min(next, t.end - System.currentTimeMillis());
            if (vibrating == null && WAITING_UNLOCK.equals(t.state)
                    && RESULT_UNVERIFIED.equals(t.result) && !t.notificationDismissed
                    && !t.waitingVibrationSuppressed && !t.lockReminderDismissed) vibrating = t;
        }
        if (!active) { stopAllVibration(c); ReminderRuntime.detach(); return; }
        if (MODE_SILENT.equals(AttendanceRules.reminderMode(c)) || unlocked(c) || vibrating == null) {
            stopAllVibration(c);
        } else {
            long now = System.currentTimeMillis();
            long until = prefs(c).getLong("vibrating_until", 0);
            String owner = prefs(c).getString("vibrating_task_id", "");
            if (vibrating.id.equals(owner) && now < until) vibrationOwner = owner;
            else startVibrationSegment(c, vibrating);
        }
        ReminderRuntime.nextTick(next); // Deadline only. Vibration intervals run in the system, even when the CPU sleeps.
    }

    static synchronized void vibrationCycle(Context context, String taskId) {
        Context c = context.getApplicationContext();
        if (taskId == null || taskId.isEmpty()) return;
        String owner = prefs(c).getString("vibrating_task_id", "");
        if (!taskId.equals(owner)) { log(taskId, "stale vibration cycle ignored"); return; }
        prefs(c).edit().remove("vibrating_task_id").remove("vibrating_until").commit();
        vibrationOwner = "";
        log(taskId, "bounded vibration segment ended; checking next segment " + screen(c));
        tick(c);
    }

    static synchronized void observerDisconnected(Context c) {
        stopAllVibration(c);
        WeComLauncher.cancelAll(c, "accessibility disconnected");
        for (Task t : allTasks(c)) if (isActive(t.state))
            postActionNotification(c, t, true, "解锁监听不可用，请点击打开企业微信。", false);
        ReminderRuntime.detach();
    }

    public static synchronized void dismissed(Context c, String id) {
        Task t = readTask(c, id);
        if (t == null) return;
        t.notificationDismissed = true;
        saveTask(c, t, "notification dismissed; attendance result unchanged");
        stopVibrationFor(c, id);
        LockReminderActivity.refresh();
    }

    static synchronized boolean launchFinished(Context c, String id, String requestId, boolean foreground) {
        Task t = readTask(c, id);
        if (t == null || !requestId.equals(t.requestId) || !isActive(t.state) || !validNow(c, t)) return false;
        t.foregroundObserved = foreground;
        t.foregroundConfirmed = false; // Window metadata cannot attribute the cause of a launch.
        t.launch = "waiting_user";
        t.state = WAITING_USER;
        t.result = RESULT_UNKNOWN;
        t.resultSource = "automatic_recognition_not_implemented";
        t.resultAt = System.currentTimeMillis();
        boolean saved = saveTask(c, t, "foreground observed=" + foreground + "; request attribution and attendance remain unknown");
        if (saved) {
            stopVibrationFor(c, id);
            // The open itself is the feedback. Do not raise another banner or vibration.
            postActionNotification(c, t, true, notificationMessage(t), false, false);
            log(id, "opened; further alerts suppressed; attendance remains unverified");
        }
        return false;
    }

    static synchronized boolean manualLaunchFinished(Context context, String requestId, boolean foreground) {
        Context c = context.getApplicationContext();
        return foreground && postForegroundStatusOnce(c, requestId, "manual");
    }

    static synchronized void locationReady(Context c, String id) {
        locationChecks.remove(id);
        Task t = readTask(c, id);
        if (t == null || !t.locationPending) return;
        t.locationPending = false;
        saveTask(c, t, "location check returned");
        if (!locationSatisfied(c, t) || !unlocked(c) || t.locationUnlockGeneration != unlockGeneration(c)) return;
        requestOpen(c, t, "location checked for original event", t.locationAutomatic, !t.locationAutomatic);
    }

    private static void reconcile(Context c) {
        LockReminderActivity.refresh();
        if (!hasActive(c)) ReminderRuntime.detach();
        else { ReminderRuntime.attach(c); tick(c); }
    }

    private static boolean hasActive(Context c) {
        for (Task t : allTasks(c)) if (isActive(t.state)) return true;
        return false;
    }
    private static long unlockGeneration(Context c) { return prefs(c).getLong("unlock_generation", 0); }
    private static boolean locationSatisfied(Context c, Task t) {
        return t.type != AlarmScheduler.ARRIVAL || OfficeLocation.currentInside(c);
    }

    public static synchronized JSONObject pendingReview(Context context) {
        Context c = context.getApplicationContext();
        Task latest = null;
        for (Task task : allTasks(c)) {
            if (!validNow(c, task)) continue;
            if (!RESULT_UNKNOWN.equals(task.result) || !WAITING_USER.equals(task.state)) continue;
            if (latest == null || task.attemptedAt > latest.attemptedAt) latest = task;
        }
        if (latest == null) return null;
        JSONObject value = new JSONObject();
        try {
            value.put("taskId", latest.id);
            value.put("type", typeTitle(latest.type));
            value.put("interval", interval(latest));
            value.put("result", latest.result);
            value.put("launch", latest.launch);
        } catch (JSONException ignored) { }
        return value;
    }

    public static boolean isNotificationAllowed(Context context) {
        NotificationManager nm = context.getSystemService(NotificationManager.class);
        if (nm == null || !nm.areNotificationsEnabled()) return false;
        if (Build.VERSION.SDK_INT >= 33 && context.checkSelfPermission(
                android.Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            return false;
        }
        if (Build.VERSION.SDK_INT >= 26) {
            NotificationChannel channel = nm.getNotificationChannel(MODE_SILENT.equals(AttendanceRules.reminderMode(context))
                    ? SILENT_CHANNEL : REMINDER_CHANNEL);
            if (channel != null && channel.getImportance() == NotificationManager.IMPORTANCE_NONE) return false;
            NotificationChannel lock = nm.getNotificationChannel(LOCK_CHANNEL);
            return MODE_SILENT.equals(AttendanceRules.reminderMode(context))
                    || lock == null || lock.getImportance() != NotificationManager.IMPORTANCE_NONE;
        }
        return true;
    }

    static boolean isForegroundStatusEnabled(Context context) {
        Context c = context.getApplicationContext();
        NotificationManager nm = c.getSystemService(NotificationManager.class);
        if (nm == null || !notificationsGloballyEnabled(c)) return false;
        NotificationChannel channel = nm.getNotificationChannel(foregroundStatusChannelId(c));
        return channel != null && channel.getImportance() >= NotificationManager.IMPORTANCE_HIGH;
    }

    static String foregroundStatusChannelId(Context context) {
        return MODE_SILENT.equals(AttendanceRules.reminderMode(context))
                ? FOREGROUND_SILENT_CHANNEL : FOREGROUND_REMINDER_CHANNEL;
    }

    static String foregroundStatusState(Context context) {
        Context c = context.getApplicationContext();
        NotificationManager nm = c.getSystemService(NotificationManager.class);
        if (!notificationsGloballyEnabled(c)) return "通知权限已关闭";
        NotificationChannel channel = nm == null ? null : nm.getNotificationChannel(foregroundStatusChannelId(c));
        if (channel == null || channel.getImportance() < NotificationManager.IMPORTANCE_HIGH)
            return "顶部状态提示渠道未设为高优先级";
        if (MODE_SILENT.equals(AttendanceRules.reminderMode(c)))
            return "静默模式状态提示无声、无震；横幅仍受勿扰、ColorOS 和系统设置控制";
        return (channel.shouldVibrate() ? "提醒模式状态提示无声并震动一次"
                : "提醒模式状态提示震动已在渠道设置中关闭")
                + "；横幅仍受勿扰、ColorOS 和系统设置控制";
    }

    private static boolean notificationsGloballyEnabled(Context c) {
        NotificationManager nm = c.getSystemService(NotificationManager.class);
        return nm != null && nm.areNotificationsEnabled()
                && (Build.VERSION.SDK_INT < 33 || c.checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS)
                == android.content.pm.PackageManager.PERMISSION_GRANTED);
    }

    public static boolean canUseFullScreen(Context c) {
        NotificationManager nm = c.getSystemService(NotificationManager.class);
        if (nm == null || !isNotificationAllowed(c)) return false;
        NotificationChannel channel = nm.getNotificationChannel(LOCK_CHANNEL);
        return (channel == null || channel.getImportance() >= NotificationManager.IMPORTANCE_HIGH)
                && (Build.VERSION.SDK_INT < 34 || nm.canUseFullScreenIntent());
    }

    static synchronized String lockReminderText(Context c, String id) {
        Task t = readTask(c, id);
        if (t == null || !MODE_REMINDER.equals(AttendanceRules.reminderMode(c)) || unlocked(c)
                || !validNow(c, t) || !WAITING_UNLOCK.equals(t.state)
                || !RESULT_UNVERIFIED.equals(t.result) || t.notificationDismissed
                || t.lockReminderDismissed) return null;
        return typeTitle(t.type) + " · " + interval(t);
    }

    static synchronized LockReminderDetails lockReminderDetails(Context c, String id) {
        Task t = readTask(c, id);
        if (t == null || lockReminderText(c, id) == null) return null;
        ZoneId zone = ZoneId.systemDefault();
        DateTimeFormatter dateTime = DateTimeFormatter.ofPattern("M 月 d 日 HH:mm");
        String from = LocalDateTime.ofInstant(java.time.Instant.ofEpochMilli(t.start), zone).format(dateTime);
        String to = LocalDateTime.ofInstant(java.time.Instant.ofEpochMilli(t.end), zone).format(dateTime);
        String owner = LocalDate.ofEpochDay(t.workday).format(DateTimeFormatter.ofPattern("M 月 d 日"));
        return new LockReminderDetails(typeTitle(t.type), owner, from + "—" + to);
    }

    /** A user's unlock gesture suppresses this task's waiting haptics without changing its result. */
    static synchronized boolean requestUnlockFromReminder(Context context, String id, String source) {
        Context c = context.getApplicationContext();
        Task task = readTask(c, id);
        if (task == null || !isActive(task.state) || !WAITING_UNLOCK.equals(task.state)
                || !validNow(c, task) || RESULT_COMPLETE.equals(task.result)
                || !MODE_REMINDER.equals(AttendanceRules.reminderMode(c))) {
            log(id, "unlock request rejected source=" + source + " " + screen(c));
            return false;
        }
        // System vibration cancellation must happen before requesting Keyguard dismissal.
        cancelVibrationCycle(c, task.id);
        boolean cancelCalled = stopAllVibration(c);
        boolean oldSuppressed = task.waitingVibrationSuppressed;
        task.waitingVibrationSuppressed = true;
        if (!saveTask(c, task, "user responded to reminder; waiting haptics suppressed; unlock must be system-confirmed")) {
            task.waitingVibrationSuppressed = oldSuppressed;
            log(id, "unlock request not submitted: haptic suppression persistence failed source=" + source);
            return false;
        }
        log(id, "user unlock requested source=" + source + " waiting vibration suppressed="
                + task.waitingVibrationSuppressed + " system vibration cancel called="
                + cancelCalled + " " + screen(c));
        return true;
    }

    /** System Back retains the existing dismiss behavior; it never means unlock or completion. */
    static synchronized boolean collapseLockReminder(Context context, String id, String source) {
        Context c = context.getApplicationContext();
        Task task = readTask(c, id);
        if (task == null || !isActive(task.state) || !WAITING_UNLOCK.equals(task.state)
                || !validNow(c, task) || RESULT_COMPLETE.equals(task.result)
                || !MODE_REMINDER.equals(AttendanceRules.reminderMode(c))) return false;
        cancelVibrationCycle(c, id);
        boolean cancelCalled = stopAllVibration(c);
        task.lockReminderDismissed = true;
        task.waitingVibrationSuppressed = true;
        task.lockReminderDismissedAt = System.currentTimeMillis();
        task.lockReminderDismissSource = source == null ? "system_back" : source;
        if (!saveTask(c, task, "reminder dismissed by system back; no unlock or result change")) return false;
        LockReminderActivity.onDismissPersisted(id);
        cancelFullScreen(c, id);
        postActionNotification(c, task, false,
                "提醒页面已关闭，等待震动已停止。", false);
        log(id, "system back dismissed reminder; vibration cancel called=" + cancelCalled + " " + screen(c));
        return true;
    }

    private static void processUnlock(Context c, Task task, String source) {
        log(task.id, "unlock decision source=" + source + " result=" + task.result + " " + screen(c));
        if (!unlocked(c)) return;
        stopVibrationFor(c, task.id);
        if (!validNow(c, task)) {
            expireTask(c, task, "interval invalid at unlock");
            return;
        }
        if (RESULT_COMPLETE.equals(task.result)) {
            log(task.id, "confirmed complete; launch suppressed");
            return;
        }
        if (RESULT_UNKNOWN.equals(task.result) || WAITING_USER.equals(task.state)) {
            log(task.id, "unverified result already reviewed; automatic repeat suppressed");
            return;
        }
        requestOpen(c, task, source, true, false);
    }

    private static void requestOpen(Context c, Task task, String source,
                                    boolean automatic, boolean userInitiated) {
        stopAllVibration(c);
        cancelFullScreen(c, task.id);
        LockReminderActivity.refresh();
        if (!validNow(c, task)) { expireTask(c, task, "invalid before launch"); return; }
        if (!unlocked(c) || !isActive(task.state) || RESULT_COMPLETE.equals(task.result)) return;
        if (WeComLauncher.busy() || "requesting".equals(task.launch)
                || "waiting_result".equals(task.launch)) {
            log(task.id, "duplicate or concurrent request rejected source=" + source); return;
        }
        long now = System.currentTimeMillis();
        if (automatic) {
            if ("background_launch_blocked".equals(task.resultSource)
                    && task.lastUnlockGeneration == unlockGeneration(c)
                    && WeComLauncher.backgroundLaunchBlocked(c)) {
                postActionNotification(c, task, MODE_SILENT.equals(AttendanceRules.reminderMode(c)),
                        notificationMessage(task), false);
                return;
            }
            if (RESULT_UNKNOWN.equals(task.result) || WAITING_USER.equals(task.state)) return;
            if (RESULT_INCOMPLETE.equals(task.result)) {
                if (!TaskPolicy.canRetry(now, task.attemptedAt, task.automaticAttempts,
                        unlockGeneration(c), task.lastUnlockGeneration)) return;
            } else if (task.attempts > 0) return;
        } else if (task.attempts > 0 && now - task.attemptedAt < TaskPolicy.LAUNCH_CONFIRM_MS) {
            log(task.id, "simultaneous user/unlock event coalesced"); return;
        }
        if (!locationSatisfied(c, task)) {
            if (!task.locationPending) {
                task.locationPending = true;
                task.locationAutomatic = automatic;
                task.locationUnlockGeneration = unlockGeneration(c);
                saveTask(c, task, "fresh location required before launch source=" + source);
                locationChecks.add(task.id);
                LocationCheckService.checkTask(c, task.id, task.workday);
            }
            return;
        }
        task.locationPending = false;
        task.state = REQUESTING_OPEN;
        task.launch = "requesting";
        task.attempts++;
        if (automatic) task.automaticAttempts++;
        task.attemptedAt = now;
        task.lastUnlockGeneration = unlockGeneration(c);
        task.requestId = java.util.UUID.randomUUID().toString();
        task.foregroundConfirmed = false;
        task.foregroundObserved = false;
        task.previousResult = task.result;
        task.previousResultSource = task.resultSource;
        task.previousResultAt = task.resultAt;
        task.result = RESULT_UNVERIFIED;
        if (!saveTask(c, task, "launch request registered source=" + source + " " + screen(c))) return;
        int outcome = WeComLauncher.launch(c, task.id, task.requestId);
        if (outcome == WeComLauncher.ALREADY_FOREGROUND) {
            launchFinished(c, task.id, task.requestId, true);
            return;
        }
        if (outcome == WeComLauncher.BACKGROUND_BLOCKED) {
            task.attempts = Math.max(0, task.attempts - 1);
            if (automatic) task.automaticAttempts = Math.max(0, task.automaticAttempts - 1);
            task.state = WAITING_UNLOCK;
            task.launch = "not_attempted";
            task.result = task.previousResult == null || task.previousResult.isEmpty()
                    ? RESULT_UNVERIFIED : task.previousResult;
            task.resultSource = "background_launch_blocked";
            task.requestId = "";
            saveTask(c, task, "background launch blocked; attempt not consumed");
            postActionNotification(c, task, MODE_SILENT.equals(AttendanceRules.reminderMode(c)),
                    notificationMessage(task), false);
            return;
        }
        boolean requested = outcome == WeComLauncher.SUBMITTED;
        task.launch = requested ? "waiting_result" : "waiting_user";
        task.state = requested ? REQUESTING_OPEN : WAITING_USER;
        if (!requested) {
            task.result = RESULT_UNKNOWN;
            task.resultSource = outcome == WeComLauncher.NOT_INSTALLED
                    ? "wecom_not_installed" : "launch_unavailable_not_attendance_failure";
        }
        saveTask(c, task, "startActivity returned=" + requested + "; foreground not inferred");
        if (requested) {
            postActionNotification(c, task, true, notificationMessage(task), false, false);
            return;
        }
        // Launch did not start. Keep an alert so the user can open WeCom from the card.
        postActionNotification(c, task, MODE_SILENT.equals(AttendanceRules.reminderMode(c)),
                notificationMessage(task), false, true);
    }

    /** Morning location checks stop once this arrival has already opened WeCom. */
    static synchronized boolean arrivalSettled(Context context, long workdayEpoch) {
        Context c = context.getApplicationContext();
        Window window = window(c, AlarmScheduler.ARRIVAL, LocalDate.ofEpochDay(workdayEpoch));
        Task task = readTask(c, taskId(workdayEpoch, AlarmScheduler.ARRIVAL, window.start, window.end));
        if (task == null) return false;
        return task.attempts > 0 || RESULT_COMPLETE.equals(task.result)
                || ENDED.equals(task.state) || EXPIRED.equals(task.state)
                || REQUESTING_OPEN.equals(task.state) || WAITING_RESULT.equals(task.state)
                || WAITING_USER.equals(task.state);
    }

    static Window window(Context c, int type, LocalDate workday) {
        long[] bounds = TaskPolicy.window(workday, type, WorkConfig.start(c), WorkConfig.lunchStart(c),
                WorkConfig.lunchEnd(c), WorkConfig.end(c), ZoneId.systemDefault());
        return new Window(bounds[0], bounds[1]);
    }

    private static String taskId(long workday, int type, long start, long end) {
        return workday + ":" + typeName(type) + ":" + start + ":" + end;
    }

    private static boolean validNow(Context c, Task task) {
        long now = System.currentTimeMillis();
        Window configured = window(c, task.type, LocalDate.ofEpochDay(task.workday));
        return configured.start == task.start && configured.end == task.end
                && AttendanceRules.isEnabled(c) && TaskPolicy.within(now, task.start, task.end)
                && AttendanceRules.isWorkday(c, LocalDate.ofEpochDay(task.workday));
    }

    private static boolean unlocked(Context c) { return ReminderRuntime.unlocked(c); }

    private static String screen(Context c) {
        PowerManager pm = c.getSystemService(PowerManager.class);
        KeyguardManager km = c.getSystemService(KeyguardManager.class);
        return "interactive=" + (pm != null && pm.isInteractive())
                + ", keyguardLocked=" + (km != null && km.isKeyguardLocked())
                + ", deviceLocked=" + (km != null && km.isDeviceLocked())
                + ", secure=" + (km != null && km.isDeviceSecure());
    }

    private static void notifySilentOnce(Context c, Task task) {
        postActionNotification(c, task, true, "有效区间内真正解锁且条件满足时，才尝试打开企业微信。", false);
    }
    private static String notificationMessage(Task task) {
        if ("background_launch_blocked".equals(task.resultSource) && WAITING_UNLOCK.equals(task.state))
            return "后台暂时不能打开企业微信，请点击这条通知打开。";
        if (task.foregroundObserved) return "已观察到企业微信进入前台。打卡结果待确认，请核对本次记录。";
        if (RESULT_UNKNOWN.equals(task.result)) return "无法确认企业微信是否打开。打卡结果待确认，可点击再次打开。";
        if (REQUESTING_OPEN.equals(task.state)) return "已请求打开企业微信，正在等待前台切换。打卡结果待确认。";
        return "请在有效区间内解锁；符合条件后尝试打开企业微信。";
    }
    private static void postReminder(Context c, Task task, String message, boolean ongoing) {
        postActionNotification(c, task, false, message, ongoing, true);
    }
    private static void postActionNotification(Context c, Task task, boolean silent, String message, boolean ongoing) {
        postActionNotification(c, task, silent, message, ongoing, true);
    }
    private static void postActionNotification(Context c, Task task, boolean silent, String message,
                                              boolean ongoing, boolean allowRepost) {
        if (task.notificationDismissed || !validNow(c, task)
                || RESULT_COMPLETE.equals(task.result) || !isActive(task.state)) return;
        NotificationManager nm = c.getSystemService(NotificationManager.class);
        ensureChannels(c);
        if (nm == null || !isNotificationAllowed(c)) {
            log(task.id, "notification unavailable; lockscreen visibility not asserted"); return;
        }
        if (!allowRepost && !task.notificationAttempted) {
            log(task.id, "quiet update skipped; no existing reminder to change");
            return;
        }
        boolean first = allowRepost && !task.notificationAttempted;
        String existingChannel = null;
        if (!first) {
            for (android.service.notification.StatusBarNotification active : nm.getActiveNotifications())
                if (task.id.equals(active.getTag()) && task.notificationId == active.getId())
                    existingChannel = active.getNotification().getChannelId();
            // A swipe sets notificationDismissed and never comes back. A missing card after
            // process death is posted again. A quiet update must not become a new alert.
            if (existingChannel == null && allowRepost) first = true;
            if (existingChannel == null) existingChannel = SILENT_CHANNEL;
        } else {
            // Write-ahead ledger: a crash can omit a notification, but cannot duplicate it.
            task.notificationAttempted = true;
            if (!saveTask(c, task, "notification request recorded before notify")) return;
        }
        Intent open = new Intent(c, ReminderOpenActivity.class).setAction(ReminderOpenActivity.OPEN)
                .setData(Uri.parse("clockin://open/" + Uri.encode(task.id)))
                .putExtra(ReminderActionReceiver.TASK, task.id);
        PendingIntent content = PendingIntent.getActivity(c, 0, open,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        Intent dismiss = new Intent(c, ReminderActionReceiver.class).setAction(ReminderActionReceiver.DISMISS)
                .setData(Uri.parse("clockin://dismiss/" + Uri.encode(task.id)))
                .putExtra(ReminderActionReceiver.TASK, task.id);
        PendingIntent deleted = PendingIntent.getBroadcast(c, 0, dismiss,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        Intent stop = new Intent(c, ReminderActionReceiver.class).setAction(ReminderActionReceiver.STOP)
                .setData(Uri.parse("clockin://stop/" + Uri.encode(task.id)))
                .putExtra(ReminderActionReceiver.TASK, task.id);
        PendingIntent stopped = PendingIntent.getBroadcast(c, 0, stop,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        String channel = !allowRepost || silent ? SILENT_CHANNEL : (first ? LOCK_CHANNEL : existingChannel);
        String text = typeTitle(task.type) + " · " + interval(task) + "。" + message;
        Notification.Builder n = new Notification.Builder(c, channel)
                .setSmallIcon(android.R.drawable.ic_lock_idle_alarm).setContentTitle("打卡提醒")
                .setContentText(text).setStyle(new Notification.BigTextStyle().bigText(text))
                .setCategory(Notification.CATEGORY_REMINDER).setVisibility(Notification.VISIBILITY_PRIVATE)
                .setContentIntent(content).setDeleteIntent(deleted).setAutoCancel(false)
                .setOnlyAlertOnce(true).setTimeoutAfter(Math.max(1, task.end - System.currentTimeMillis()))
                .setDefaults(0).setSound(null).setVibrate(new long[0])
                .addAction(android.R.drawable.ic_menu_agenda, "我已打卡", completeAction(c, task))
                .addAction(android.R.drawable.ic_menu_close_clear_cancel, "停止本次提醒", stopped);
        if (allowRepost && first && ongoing && !silent && !unlocked(c) && canUseFullScreen(c)
                && !task.lockReminderDismissed && !task.waitingVibrationSuppressed) {
            Intent lock = new Intent(c, LockReminderActivity.class)
                    .setData(Uri.parse("clockin://lock/" + Uri.encode(task.id)))
                    .putExtra(ReminderActionReceiver.TASK, task.id);
            n.setFullScreenIntent(PendingIntent.getActivity(c, 0, lock,
                    PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE), true);
            n.setCategory(Notification.CATEGORY_ALARM);
        }
        try { nm.notify(task.id, task.notificationId, n.build()); }
        catch (SecurityException e) { log(task.id, "notification permission changed"); }
        log(task.id, "notification " + (first ? "posted" : "updated") + " request channel=" + channel
                + " fullScreenEligible=" + canUseFullScreen(c) + "; actual presentation unknown");
    }

    private static boolean postForegroundStatusOnce(Context c, String requestId, String taskId) {
        if (requestId == null || requestId.isEmpty() || !isForegroundStatusEnabled(c)) {
            log(taskId, "foreground status not posted: permission or high-priority channel unavailable");
            return false;
        }
        android.content.SharedPreferences ledger = c.getSharedPreferences("attendance_launch_status", Context.MODE_PRIVATE);
        String key = "shown." + requestId;
        if (ledger.getBoolean(key, false)) {
            log(taskId, "foreground status duplicate suppressed request=" + requestId);
            return false;
        }
        if (!ledger.edit().putBoolean(key, true).commit()) {
            log(taskId, "foreground status skipped: request ledger persistence failed");
            return false;
        }
        NotificationManager nm = c.getSystemService(NotificationManager.class);
        String text = "已观察到企业微信进入前台，打卡结果待确认。";
        boolean silent = MODE_SILENT.equals(AttendanceRules.reminderMode(c));
        Notification.Builder notification = new Notification.Builder(c, foregroundStatusChannelId(c))
                .setSmallIcon(android.R.drawable.ic_lock_idle_alarm)
                .setContentTitle("企业微信已打开")
                .setContentText(text)
                .setCategory(Notification.CATEGORY_STATUS)
                .setVisibility(Notification.VISIBILITY_PRIVATE)
                .setAutoCancel(true).setTimeoutAfter(7_000)
                .setOnlyAlertOnce(false).setDefaults(0).setSound(null)
                .setVibrate(silent ? new long[0] : new long[]{0, 80});
        try {
            nm.notify("foreground:" + requestId, 1, notification.build());
            log(taskId, "foreground status submitted once request=" + requestId + " mode="
                    + (silent ? "silent (no vibration)" : "reminder (one channel vibration)")
                    + "; presentation controlled by OS/channel");
            return true;
        } catch (SecurityException e) {
            log(taskId, "foreground status notification denied by system");
            return false;
        }
    }

    private static PendingIntent completeAction(Context c, Task task) {
        Intent intent = new Intent(c, ReminderOpenActivity.class).setAction(ReminderOpenActivity.CONFIRM)
                .setData(Uri.parse("clockin://confirm/" + Uri.encode(task.id)))
                .putExtra(ReminderActionReceiver.TASK, task.id);
        return PendingIntent.getActivity(c, 0, intent, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }

    private static void ensureChannels(Context c) {
        NotificationManager nm = c.getSystemService(NotificationManager.class);
        if (nm == null) return;
        for (String id : new String[]{REMINDER_CHANNEL, SILENT_CHANNEL}) {
            NotificationChannel channel = new NotificationChannel(id,
                    id.equals(SILENT_CHANNEL) ? "打卡提醒（静默）" : "打卡提醒（原生间隔震动）",
                    NotificationManager.IMPORTANCE_LOW);
            channel.enableVibration(false);
            channel.setSound(null, null);
            nm.createNotificationChannel(channel);
        }
        NotificationChannel lock = new NotificationChannel(LOCK_CHANNEL, "锁屏打卡提醒",
                NotificationManager.IMPORTANCE_HIGH);
        lock.setDescription("提醒模式的锁屏卡片和全屏入口。等待震动由原生任务控制；静默模式使用独立渠道。");
        lock.setSound(null, null);
        lock.enableVibration(false);
        nm.createNotificationChannel(lock);
        NotificationChannel reminderStatus = new NotificationChannel(FOREGROUND_REMINDER_CHANNEL,
                "企业微信前台状态提示（提醒模式）", NotificationManager.IMPORTANCE_HIGH);
        reminderStatus.setDescription("观察到企业微信进入前台后，无声震动一次；不代表打卡完成。");
        reminderStatus.setSound(null, null);
        reminderStatus.enableVibration(true);
        reminderStatus.setVibrationPattern(new long[]{0, 80});
        nm.createNotificationChannel(reminderStatus);
        NotificationChannel silentStatus = new NotificationChannel(FOREGROUND_SILENT_CHANNEL,
                "企业微信前台状态提示（静默模式）", NotificationManager.IMPORTANCE_HIGH);
        silentStatus.setDescription("观察到企业微信进入前台后显示无声、无震状态提示；不代表打卡完成。");
        silentStatus.setSound(null, null);
        silentStatus.enableVibration(false);
        nm.createNotificationChannel(silentStatus);
    }
    private static void startVibration(Context c, String id) { tick(c); }
    private static boolean stopAllVibration(Context c) {
        ReminderRuntime.stopTick();
        String persistedOwner = prefs(c).getString("vibrating_task_id", "");
        String owner = !persistedOwner.isEmpty() ? persistedOwner : vibrationOwner;
        if (!owner.isEmpty()) cancelVibrationCycle(c, owner);
        boolean cancelCalled = ReminderHaptics.stop(c);
        if (!owner.isEmpty()) log(owner, "system vibration cancel called=" + cancelCalled
                + "; stop alarm and callback cleared " + screen(c));
        prefs(c).edit().remove("vibrating_task_id").remove("vibrating_until").commit();
        vibrationOwner = "";
        return cancelCalled;
    }
    private static void stopVibrationFor(Context c, String id) {
        cancelVibrationCycle(c, id);
        String persistedOwner = prefs(c).getString("vibrating_task_id", "");
        if (id.equals(vibrationOwner) || id.equals(persistedOwner)) stopAllVibration(c);
    }

    private static void startVibrationSegment(Context c, Task task) {
        long remaining = Math.max(1, task.end - System.currentTimeMillis());
        long segment = Math.min(remaining, TaskPolicy.VIBRATION_SEGMENT_MS);
        cancelVibrationCycle(c, task.id);
        boolean recorded = prefs(c).edit().putString("vibrating_task_id", task.id)
                .putLong("vibrating_until", System.currentTimeMillis() + segment).commit();
        if (!recorded) {
            log(task.id, "vibration skipped: task ownership could not be persisted");
            return;
        }
        boolean submitted = ReminderHaptics.waiting(c, segment);
        if (submitted) {
            vibrationOwner = task.id;
            scheduleVibrationCycle(c, task.id, System.currentTimeMillis() + segment);
        } else {
            vibrationOwner = "";
            prefs(c).edit().remove("vibrating_task_id").remove("vibrating_until").commit();
        }
        log(task.id, "vibration segment submitted=" + submitted + " durationMs=" + segment
                + " pulseMs=" + TaskPolicy.VIBRATION_PULSE_MS + " intervalMs=" + TaskPolicy.VIBRATION_INTERVAL_MS
                + " stopAlarmScheduled=" + submitted + " " + screen(c));
    }

    private static void scheduleVibrationCycle(Context c, String id, long at) {
        AlarmManager manager = c.getSystemService(AlarmManager.class);
        if (manager == null) { log(id, "vibration segment finite; next segment alarm unavailable"); return; }
        PendingIntent pending = PendingIntent.getBroadcast(c, vibrationRequestCode(id), vibrationIntent(c, id),
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        try {
            if (Build.VERSION.SDK_INT >= 31 && manager.canScheduleExactAlarms())
                manager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pending);
            else manager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pending);
        } catch (SecurityException e) {
            manager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pending);
            log(id, "exact vibration continuation unavailable; current bounded segment still ends on time");
        }
    }

    private static void cancelVibrationCycle(Context c, String id) {
        if (id == null || id.isEmpty()) return;
        AlarmManager manager = c.getSystemService(AlarmManager.class);
        PendingIntent pending = PendingIntent.getBroadcast(c, vibrationRequestCode(id), vibrationIntent(c, id),
                PendingIntent.FLAG_NO_CREATE | PendingIntent.FLAG_IMMUTABLE);
        if (pending != null) {
            if (manager != null) manager.cancel(pending);
            pending.cancel();
        }
    }

    private static Intent vibrationIntent(Context c, String id) {
        return new Intent(c, ReminderActionReceiver.class).setAction(ReminderActionReceiver.VIBRATION_CYCLE)
                .setData(Uri.parse("clockin://vibration/" + Uri.encode(id)))
                .putExtra(ReminderActionReceiver.TASK, id);
    }

    private static int vibrationRequestCode(String id) {
        return VIBRATION_REQUEST_BASE + (id.hashCode() & 0x3fff);
    }

    private static final long UNLOCK_CHECK_MS = 30_000L;
    private static final int UNLOCK_CHECK_REQUEST = 7101;

    /** Notification permission is optional. While it is off, keep waking until unlock opens WeCom. */
    public static synchronized void unlockCheck(Context context) {
        Context c = context.getApplicationContext();
        initialize(c);
        if (unlocked(c)) {
            log("none", "unlock check found device unlocked; opening WeCom without notification");
            unlockedEvent(c, "notification-off unlock check");
            return;
        }
        long now = System.currentTimeMillis();
        long nextEnd = Long.MAX_VALUE;
        boolean waiting = false;
        for (Task task : allTasks(c)) {
            if (!WAITING_UNLOCK.equals(task.state) || !RESULT_UNVERIFIED.equals(task.result) || !validNow(c, task))
                continue;
            waiting = true;
            nextEnd = Math.min(nextEnd, task.end);
        }
        if (!waiting) return;
        long at = Math.min(now + UNLOCK_CHECK_MS, nextEnd);
        if (at <= now) return;
        scheduleUnlockCheckAt(c, at);
    }

    private static void scheduleUnlockCheck(Context c, Task task) {
        long at = Math.min(System.currentTimeMillis() + UNLOCK_CHECK_MS, task.end);
        if (at <= System.currentTimeMillis()) return;
        scheduleUnlockCheckAt(c, at);
    }

    private static void scheduleUnlockCheckAt(Context c, long at) {
        AlarmManager manager = c.getSystemService(AlarmManager.class);
        if (manager == null) return;
        Intent intent = new Intent(c, ReminderActionReceiver.class)
                .setAction(ReminderActionReceiver.UNLOCK_CHECK)
                .setData(Uri.parse("clockin://unlock-check"));
        PendingIntent pending = PendingIntent.getBroadcast(c, UNLOCK_CHECK_REQUEST, intent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        try {
            if (Build.VERSION.SDK_INT >= 31 && manager.canScheduleExactAlarms())
                manager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pending);
            else manager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pending);
        } catch (SecurityException e) {
            manager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pending);
        }
        log("none", "unlock check scheduled notificationOff=true");
    }

    private static void scheduleExpiry(Context c, Task task) {
        AlarmManager manager = c.getSystemService(AlarmManager.class);
        if (manager == null) return;
        Intent intent = expiryIntent(c, task.id);
        int requestCode = expiryRequestCode(task.id);
        PendingIntent pending = PendingIntent.getBroadcast(c, requestCode, intent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        try {
            if (Build.VERSION.SDK_INT >= 31 && manager.canScheduleExactAlarms()) {
                manager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, task.end, pending);
            } else manager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, task.end, pending);
        } catch (SecurityException e) {
            manager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, task.end, pending);
        }
    }

    private static void cancelExpiry(Context c, String taskId) {
        AlarmManager manager = c.getSystemService(AlarmManager.class);
        if (manager == null) return;
        PendingIntent pending = PendingIntent.getBroadcast(c, expiryRequestCode(taskId),
                expiryIntent(c, taskId), PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        manager.cancel(pending);
        pending.cancel();
    }

    private static Intent expiryIntent(Context c, String taskId) {
        return new Intent(c, ReminderActionReceiver.class).setAction(ReminderActionReceiver.EXPIRE)
                .setData(Uri.parse("clockin://expire/" + Uri.encode(taskId)))
                .putExtra(ReminderActionReceiver.TASK, taskId);
    }

    private static int expiryRequestCode(String taskId) {
        return EXPIRY_REQUEST_BASE + (taskId.hashCode() & 0x3fff);
    }

    private static void cancelTaskNotification(Context c, String taskId) {
        cancelFullScreen(c, taskId);
        NotificationManager nm = c.getSystemService(NotificationManager.class);
        Task t = readTask(c, taskId);
        if (nm != null && t != null) nm.cancel(taskId, t.notificationId);
    }

    private static void cancelFullScreen(Context c, String taskId) {
        Intent intent = new Intent(c, LockReminderActivity.class)
                .setData(Uri.parse("clockin://lock/" + Uri.encode(taskId)));
        PendingIntent pending = PendingIntent.getActivity(c, 0, intent,
                PendingIntent.FLAG_NO_CREATE | PendingIntent.FLAG_IMMUTABLE);
        if (pending != null) pending.cancel();
    }

    private static void endTask(Context c, Task task, String reason) {
        stopVibrationFor(c, task.id);
        WeComLauncher.cancelTask(task.id);
        task.locationPending = false;
        task.state = ENDED;
        saveTask(c, task, reason);
        LockReminderActivity.refresh();
        cancelTaskNotification(c, task.id);
        cancelExpiry(c, task.id);
    }

    private static void recoverInFlight(Context c, Task task) {
        if (!locationChecks.contains(task.id)) task.locationPending = false;
        if (WeComLauncher.hasRequest(task.requestId)) return;
        if (!REQUESTING_OPEN.equals(task.state) && !"waiting_result".equals(task.launch)
                && !"requesting".equals(task.launch)) {
            saveTask(c, task, "restore pending acquisition reset");
            return;
        }
        task.state = WAITING_USER;
        task.result = RESULT_UNKNOWN;
        task.resultSource = "process_rebuilt_during_launch_request";
        task.resultAt = System.currentTimeMillis();
        task.launch = "waiting_user";
        saveTask(c, task, "in-flight launch recovered without retry");
    }

    private static void expireTask(Context c, Task task, String reason) {
        stopVibrationFor(c, task.id);
        WeComLauncher.cancelTask(task.id);
        task.locationPending = false;
        task.state = EXPIRED;
        saveTask(c, task, reason);
        LockReminderActivity.refresh();
        cancelTaskNotification(c, task.id);
        cancelExpiry(c, task.id);
    }

    private static boolean isActive(String state) {
        return PENDING.equals(state) || WAITING_UNLOCK.equals(state) || REQUESTING_OPEN.equals(state)
                || WAITING_RESULT.equals(state) || WAITING_USER.equals(state);
    }

    private static String typeName(int type) {
        if (type == AlarmScheduler.ARRIVAL) return "arrival";
        if (type == AlarmScheduler.LUNCH_OUT) return "lunch_out";
        if (type == AlarmScheduler.LUNCH_IN) return "lunch_in";
        return "evening_out";
    }

    private static String typeTitle(int type) {
        if (type == AlarmScheduler.ARRIVAL) return "上班打卡";
        if (type == AlarmScheduler.LUNCH_OUT) return "午间下班打卡";
        if (type == AlarmScheduler.LUNCH_IN) return "午间上班打卡";
        return "下班打卡";
    }

    private static String interval(Task task) {
        ZoneId zone = ZoneId.systemDefault();
        String start = LocalDateTime.ofInstant(java.time.Instant.ofEpochMilli(task.start), zone).format(CLOCK);
        String end = LocalDateTime.ofInstant(java.time.Instant.ofEpochMilli(task.end), zone).format(CLOCK);
        if (!java.time.Instant.ofEpochMilli(task.start).atZone(zone).toLocalDate().equals(
                java.time.Instant.ofEpochMilli(task.end).atZone(zone).toLocalDate()))
            return start + " 至次日 " + end;
        return start + "–" + end;
    }

    private static Task readTask(Context c, String id) {
        if (id == null || id.isEmpty()) return null;
        String raw = prefs(c).getString(taskKey(id), null);
        if (raw == null) return null;
        try { return Task.from(new JSONObject(raw)); }
        catch (JSONException ignored) { log(id, "invalid persisted task record"); return null; }
    }

    private static List<Task> allTasks(Context c) {
        List<Task> tasks = new ArrayList<>();
        for (String key : prefs(c).getAll().keySet()) {
            if (!key.startsWith("task.")) continue;
            String raw = prefs(c).getString(key, null);
            if (raw == null) continue;
            try { tasks.add(Task.from(new JSONObject(raw))); }
            catch (JSONException ignored) { log(key, "invalid persisted task record"); }
        }
        tasks.sort(java.util.Comparator.comparingLong(t -> t.start));
        return tasks;
    }

    private static boolean saveTask(Context c, Task task, String reason) {
        String previous = "new";
        Task old = readTask(c, task.id);
        if (old != null) previous = old.state;
        try {
            if (task.notificationId == 0) {
                task.notificationId = prefs(c).getInt("next_notification_id", 1000);
                if (!prefs(c).edit().putInt("next_notification_id", task.notificationId + 1).commit()) return false;
            }
            if (!prefs(c).edit().putString(taskKey(task.id), task.toJson().toString()).commit()) return false;
        } catch (JSONException e) {
            Log.e(TAG, "task=" + task.id + " persistence failed", e);
            return false;
        }
        log(task.id, "state=" + previous + "->" + task.state + " result=" + task.result + " launch=" + task.launch
                + " attempts=" + task.attempts + " (" + reason + ")");
        return true;
    }

    private static String taskKey(String id) { return "task." + id; }
    private static android.content.SharedPreferences prefs(Context c) {
        return c.getSharedPreferences(PREF, Context.MODE_PRIVATE);
    }
    private static void log(String id, String message) {
        Log.i(TAG, "task=" + (id == null || id.isEmpty() ? "none" : id) + " " + message);
    }

    static final class Window {
        final long start, end;
        Window(long start, long end) { this.start = start; this.end = end; }
    }

    static final class LockReminderDetails {
        final String type, workday, interval;
        LockReminderDetails(String type, String workday, String interval) {
            this.type = type; this.workday = workday; this.interval = interval;
        }
    }

    private static final class Task {
        final String id;
        final int type;
        final long workday, start, end;
        String state = PENDING;
        String result = RESULT_UNVERIFIED;
        String launch = "not_attempted";
        String resultSource = "none";
        String evidence = "";
        String automaticResult = "";
        long automaticResultAt, manualConfirmedAt;
        String previousResult = "";
        String previousResultSource = "";
        String previousEvidence = "";
        int attempts, automaticAttempts;
        long attemptedAt, resultAt, previousResultAt;
        boolean notificationAttempted, notificationDismissed, foregroundConfirmed, foregroundObserved, locationPending, locationAutomatic;
        boolean lockReminderDismissed, waitingVibrationSuppressed;
        long lockReminderDismissedAt;
        String lockReminderDismissSource = "";
        String requestId = "";
        int notificationId;
        long lastUnlockGeneration, locationUnlockGeneration;

        Task(String id, int type, long workday, long start, long end) {
            this.id = id; this.type = type; this.workday = workday; this.start = start; this.end = end;
        }
        JSONObject toJson() throws JSONException {
            JSONObject j = new JSONObject();
            j.put("id", id); j.put("type", type); j.put("workday", workday); j.put("start", start); j.put("end", end);
            j.put("automaticResult", automaticResult); j.put("automaticResultAt", automaticResultAt);
            j.put("manualConfirmedAt", manualConfirmedAt);
            j.put("state", state); j.put("result", result); j.put("launch", launch);
            j.put("resultSource", resultSource); j.put("evidence", evidence); j.put("attempts", attempts);
            j.put("automaticAttempts", automaticAttempts); j.put("previousResult", previousResult);
            j.put("previousResultSource", previousResultSource); j.put("previousEvidence", previousEvidence);
            j.put("previousResultAt", previousResultAt);
            j.put("attemptedAt", attemptedAt); j.put("resultAt", resultAt);
            j.put("notificationAttempted", notificationAttempted);
            j.put("notificationDismissed", notificationDismissed); j.put("notificationId", notificationId);
            j.put("lockReminderDismissed", lockReminderDismissed);
            j.put("waitingVibrationSuppressed", waitingVibrationSuppressed);
            j.put("lockReminderDismissedAt", lockReminderDismissedAt);
            j.put("lockReminderDismissSource", lockReminderDismissSource);
            j.put("requestId", requestId); j.put("foregroundConfirmed", foregroundConfirmed); j.put("foregroundObserved", foregroundObserved);
            j.put("lastUnlockGeneration", lastUnlockGeneration); j.put("locationUnlockGeneration", locationUnlockGeneration);
            j.put("locationPending", locationPending); j.put("locationAutomatic", locationAutomatic);
            return j;
        }
        static Task from(JSONObject j) throws JSONException {
            Task t = new Task(j.getString("id"), j.getInt("type"), j.getLong("workday"),
                    j.getLong("start"), j.getLong("end"));
            t.automaticResult = j.optString("automaticResult", "");
            t.automaticResultAt = j.optLong("automaticResultAt", 0);
            t.manualConfirmedAt = j.optLong("manualConfirmedAt", 0);
            t.state = j.optString("state", WAITING_UNLOCK);
            t.result = j.optString("result", RESULT_UNVERIFIED);
            t.launch = j.optString("launch", "not_attempted");
            t.resultSource = j.optString("resultSource", "none");
            t.evidence = j.optString("evidence", "");
            t.attempts = j.optInt("attempts", 0);
            t.automaticAttempts = j.optInt("automaticAttempts", t.attempts);
            t.previousResult = j.optString("previousResult", "");
            t.previousResultSource = j.optString("previousResultSource", "");
            t.previousEvidence = j.optString("previousEvidence", "");
            t.previousResultAt = j.optLong("previousResultAt", 0);
            t.attemptedAt = j.optLong("attemptedAt", 0);
            t.resultAt = j.optLong("resultAt", 0);
            t.notificationAttempted = j.optBoolean("notificationAttempted", false);
            t.notificationDismissed = j.optBoolean("notificationDismissed", false);
            t.lockReminderDismissed = j.optBoolean("lockReminderDismissed", false);
            t.waitingVibrationSuppressed = j.optBoolean("waitingVibrationSuppressed", false);
            t.lockReminderDismissedAt = j.optLong("lockReminderDismissedAt", 0);
            t.lockReminderDismissSource = j.optString("lockReminderDismissSource", "");
            t.notificationId = j.optInt("notificationId", 0);
            t.requestId = j.optString("requestId", "");
            t.foregroundConfirmed = false;
            t.foregroundObserved = j.optBoolean("foregroundObserved", false);
            t.lastUnlockGeneration = j.optLong("lastUnlockGeneration", 0);
            t.locationUnlockGeneration = j.optLong("locationUnlockGeneration", 0);
            t.locationPending = j.optBoolean("locationPending", false);
            t.locationAutomatic = j.optBoolean("locationAutomatic", false);
            return t;
        }
    }
}
