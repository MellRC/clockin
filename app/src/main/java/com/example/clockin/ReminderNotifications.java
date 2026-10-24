package com.example.clockin;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.media.AudioAttributes;
import android.net.Uri;
import android.os.Build;

public final class ReminderNotifications {
    private static final String NORMAL_CHANNEL = "clock_in_reminders";
    private static final String URGENT_CHANNEL = "clock_in_urgent_loop";
    private static final String SILENT_CHANNEL = "clock_in_silent_status_v1";
    private static final int NOTIFICATION_ID = 511;

    private ReminderNotifications() {}

    public static void show(Context context, String title, String message, boolean urgent) {
        NotificationManager manager = context.getSystemService(NotificationManager.class);
        if (manager == null) return;
        boolean silent = ReminderFlow.MODE_SILENT.equals(AttendanceRules.reminderMode(context));
        boolean effectiveUrgent = urgent && !silent;
        String channelId = silent ? SILENT_CHANNEL : (effectiveUrgent ? URGENT_CHANNEL : NORMAL_CHANNEL);
        createChannel(manager, channelId, effectiveUrgent, silent);
        Intent open = new Intent(context, MainActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        PendingIntent content = PendingIntent.getActivity(context, 512, open,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        Notification.Builder builder = Build.VERSION.SDK_INT >= 26
                ? new Notification.Builder(context, channelId) : new Notification.Builder(context);
        manager.notify(NOTIFICATION_ID, builder.setSmallIcon(android.R.drawable.ic_lock_idle_alarm)
                .setContentTitle(title).setContentText(message).setCategory(Notification.CATEGORY_ALARM)
                .setVisibility(Notification.VISIBILITY_PUBLIC).setAutoCancel(!effectiveUrgent)
                .setContentIntent(content).build());
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
        ReminderFlow.triggered(context, type, day);
    }

    private static void createChannel(NotificationManager manager, String channelId,
                                      boolean urgent, boolean silent) {
        if (Build.VERSION.SDK_INT < 26) return;
        NotificationChannel channel = new NotificationChannel(channelId, "打卡提醒",
                urgent ? NotificationManager.IMPORTANCE_HIGH : NotificationManager.IMPORTANCE_DEFAULT);
        channel.setLockscreenVisibility(Notification.VISIBILITY_PUBLIC);
        channel.enableVibration(urgent && !silent);
        if (silent) {
            channel.setSound(null, null);
        } else if (urgent) {
            channel.setSound(Uri.parse("content://settings/system/alarm_alert"),
                    new AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ALARM).build());
        }
        manager.createNotificationChannel(channel);
    }
}
