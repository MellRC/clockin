package com.example.clockin;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.KeyguardManager;
import android.content.Context;
import android.content.Intent;
import android.graphics.Color;
import android.media.AudioAttributes;
import android.net.Uri;
import android.os.Build;
import android.os.PowerManager;
import android.os.Vibrator;
import android.os.VibrationEffect;
import android.os.SystemClock;

public final class ReminderNotifications {
    public static final String EXTRA_REMINDER_POSTED = "reminder_posted";
    public static final String EXTRA_SHOW_LOCK_REMINDER = "show_lock_reminder";
    private static final String NORMAL_CHANNEL = "clock_in_reminders";
    private static final String URGENT_CHANNEL = "clock_in_urgent_loop";
    private static final int LOCK_REMINDER_ID = 511;
    static final String PREFS = "clock_in_reminder";
    static final String VIBRATION_START = "vibration_start_elapsed";
    static final long[] VIBRATION_PATTERN = {0, 700, 350, 700, 350, 1000};
    static final long VIBRATION_CYCLE_MS = 3100;

    private ReminderNotifications() {}

    public static void show(Context context, String title, String message, boolean urgent) {
        if (urgent) vibrateIfScreenOff(context);
        NotificationManager manager = context.getSystemService(NotificationManager.class);
        if (manager == null) return;
        String channelId = urgent ? URGENT_CHANNEL : NORMAL_CHANNEL;
        createChannel(context, manager, channelId, urgent);
        Intent open = new Intent(context, MainActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        PendingIntent content = PendingIntent.getActivity(context, 512,
                open, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        Notification.Builder builder = Build.VERSION.SDK_INT >= 26
                ? new Notification.Builder(context, channelId) : new Notification.Builder(context);
        Notification notification = builder.setSmallIcon(android.R.drawable.ic_lock_idle_alarm)
                .setContentTitle(title).setContentText(message).setCategory(Notification.CATEGORY_ALARM)
                .setVisibility(Notification.VISIBILITY_PUBLIC).setAutoCancel(!urgent)
                .setContentIntent(content)
                .setVibrate(urgent ? VIBRATION_PATTERN : null)
                .build();
        manager.notify(LOCK_REMINDER_ID, notification);
    }

    public static boolean isScreenOff(Context context) {
        PowerManager power = (PowerManager) context.getSystemService(Context.POWER_SERVICE);
        return power != null && !power.isInteractive();
    }

    public static boolean shouldShowLockedReminder(Context context) {
        if (isScreenOff(context)) return true;
        KeyguardManager keyguard = context.getSystemService(KeyguardManager.class);
        return keyguard != null && keyguard.isKeyguardLocked();
    }

    public static boolean isLockNotificationReady(Context context) {
        NotificationManager manager = context.getSystemService(NotificationManager.class);
        if (manager == null || !manager.areNotificationsEnabled()) return false;
        if (Build.VERSION.SDK_INT < 26) return true;
        NotificationChannel channel = manager.getNotificationChannel(URGENT_CHANNEL);
        return channel == null || (channel.getImportance() >= NotificationManager.IMPORTANCE_HIGH
                && channel.getLockscreenVisibility() != Notification.VISIBILITY_SECRET);
    }

    public static void showLockedAttendanceReminder(Context context, int type, long day) {
        vibrateIfScreenOff(context);
        NotificationManager manager = context.getSystemService(NotificationManager.class);
        if (manager == null) return;
        createChannel(context, manager, URGENT_CHANNEL, true);
        Intent open = new Intent(context, TriggerActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP)
                .putExtra(AlarmScheduler.EXTRA_TYPE, type)
                .putExtra(AlarmScheduler.EXTRA_DAY, day)
                .putExtra(EXTRA_REMINDER_POSTED, true)
                .putExtra(EXTRA_SHOW_LOCK_REMINDER, true);
        PendingIntent fullScreen = PendingIntent.getActivity(context, 511,
                open, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        Notification.Builder builder = Build.VERSION.SDK_INT >= 26
                ? new Notification.Builder(context, URGENT_CHANNEL) : new Notification.Builder(context);
        Notification notification = builder.setSmallIcon(android.R.drawable.ic_lock_idle_alarm)
                .setContentTitle("打卡时间到啦！")
                .setContentText("请解锁手机；若未自动弹出，请允许工力全开使用全屏提醒。")
                .setCategory(Notification.CATEGORY_ALARM)
                .setVisibility(Notification.VISIBILITY_PUBLIC).setAutoCancel(true)
                .setContentIntent(fullScreen).setFullScreenIntent(fullScreen, true).build();
        manager.notify(LOCK_REMINDER_ID, notification);
    }

    private static void vibrateIfScreenOff(Context context) {
        PowerManager power = (PowerManager) context.getSystemService(Context.POWER_SERVICE);
        if (power == null || power.isInteractive()) return;
        Vibrator vibrator = context.getSystemService(Vibrator.class);
        if (vibrator == null || !vibrator.hasVibrator()) return;
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .putLong(VIBRATION_START, SystemClock.elapsedRealtime()).apply();
        VibrationEffect effect = VibrationEffect.createWaveform(VIBRATION_PATTERN, 0);
        AudioAttributes attributes = new AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_ALARM).build();
        vibrator.vibrate(effect, attributes);
    }

    public static void stopVibration(Context context) {
        Vibrator vibrator = context.getSystemService(Vibrator.class);
        if (vibrator != null) vibrator.cancel();
        NotificationManager manager = context.getSystemService(NotificationManager.class);
        if (manager != null) manager.cancel(LOCK_REMINDER_ID);
    }

    private static void createChannel(Context context, NotificationManager manager, String channelId, boolean urgent) {
        if (Build.VERSION.SDK_INT < 26) return;
        NotificationChannel channel = new NotificationChannel(channelId, "打卡提醒",
                urgent ? NotificationManager.IMPORTANCE_HIGH : NotificationManager.IMPORTANCE_DEFAULT);
        channel.setLockscreenVisibility(Notification.VISIBILITY_PUBLIC);
        // The repeating alarm vibration is controlled directly so posting the
        // notification cannot replace it with Android's short notification buzz.
        channel.enableVibration(!urgent);
        if (urgent) {
            Uri sound = android.provider.Settings.System.DEFAULT_ALARM_ALERT_URI;
            channel.setSound(sound, new AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ALARM).build());
        }
        manager.createNotificationChannel(channel);
    }
}
