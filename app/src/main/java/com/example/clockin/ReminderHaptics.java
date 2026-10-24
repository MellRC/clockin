package com.example.clockin;

import android.app.NotificationManager;
import android.content.Context;
import android.media.AudioAttributes;
import android.media.AudioManager;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.os.Build;
import android.os.VibrationAttributes;
import android.util.Log;

final class ReminderHaptics {
    static boolean waiting(Context c, long remaining) {
        if (!ReminderFlow.isNotificationAllowed(c)) {
            Log.i("AttendanceReminder", "waiting vibration skipped: notification permission/channel disabled");
            return false;
        }
        return vibrate(c, VibrationEffect.createWaveform(TaskPolicy.waitingPattern(remaining), -1));
    }
    static String blockedReason(Context c) {
        Vibrator v = c.getSystemService(Vibrator.class);
        AudioManager a = c.getSystemService(AudioManager.class);
        NotificationManager n = c.getSystemService(NotificationManager.class);
        if (v == null || !v.hasVibrator()) return "设备没有可用振动器";
        if (a == null || n == null) return "系统服务不可用";
        if (!ReminderFlow.isNotificationAllowed(c)) return "本应用通知或提醒渠道已关闭";
        if (a.getRingerMode() == AudioManager.RINGER_MODE_SILENT) return "系统处于静音模式";
        if (n.getCurrentInterruptionFilter() != NotificationManager.INTERRUPTION_FILTER_ALL) return "系统免打扰已开启";
        return "";
    }
    private static boolean vibrate(Context c, VibrationEffect effect) {
        String blocked = blockedReason(c);
        if (!blocked.isEmpty()) {
            Log.i("AttendanceReminder", "vibration skipped reason=" + blocked);
            return false;
        }
        Vibrator v = c.getSystemService(Vibrator.class);
        try {
            if (Build.VERSION.SDK_INT >= 33) {
                v.vibrate(effect, new VibrationAttributes.Builder().setUsage(
                        VibrationAttributes.USAGE_ALARM).build());
            } else {
                v.vibrate(effect, new AudioAttributes.Builder().setUsage(
                        AudioAttributes.USAGE_ALARM).build());
            }
            return true;
        } catch (RuntimeException e) {
            Log.i("AttendanceReminder", "vibration request failed: " + e.getClass().getSimpleName());
            return false;
        }
    }
    static boolean stop(Context c) {
        Vibrator v = c.getSystemService(Vibrator.class);
        if (v == null) {
            Log.i("AttendanceReminder", "system vibration cancel unavailable: vibrator service missing");
            return false;
        }
        try {
            v.cancel();
            Log.i("AttendanceReminder", "system vibration cancel called");
            return true;
        } catch (RuntimeException e) {
            Log.i("AttendanceReminder", "system vibration cancel failed: " + e.getClass().getSimpleName());
            return false;
        }
    }
}
