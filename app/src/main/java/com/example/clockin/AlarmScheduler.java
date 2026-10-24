package com.example.clockin;

import android.app.*;
import android.content.*;
import android.net.Uri;
import android.os.Build;
import java.time.LocalDate;
import java.util.*;

public final class AlarmScheduler {
    public static final String EXTRA_DAY = "day", EXTRA_TYPE = "type";
    public static final int ARRIVAL = 1, LUNCH_OUT = 2, LUNCH_IN = 3, EVENING_OUT = 4;
    private static final String PREF = "scheduled_alarms_v4";
    private static boolean repairedThisProcess;
    private AlarmScheduler() { }
    public static boolean canScheduleExact(Context c) {
        AlarmManager a = c.getSystemService(AlarmManager.class);
        return Build.VERSION.SDK_INT < 31 || a != null && a.canScheduleExactAlarms();
    }
    public static void scheduleWeek(Context c) { scheduleWeek(c, true); }
    public static void rescheduleAll(Context c) {
        repairedThisProcess = true;
        cancelAll(c);
        scheduleWeek(c, true, true);
    }
    public static void repairOnWake(Context c, boolean catchUp) {
        AttendanceRules.ensureDefaults(c);
        if (!AttendanceRules.isEnabled(c)) return;
        if (!repairedThisProcess) {
            repairedThisProcess = true;
            scheduleWeek(c, catchUp, true);
            return;
        }
        scheduleWeek(c, false, false);
    }
    public static void scheduleWeek(Context c, boolean catchUp) {
        scheduleWeek(c, catchUp, false);
    }
    private static void scheduleWeek(Context c, boolean catchUp, boolean force) {
        AttendanceRules.ensureDefaults(c);
        if (!AttendanceRules.isEnabled(c)) return;
        android.util.Log.i("AttendanceReminder", "scheduling enabled exact=" + canScheduleExact(c) + " catchUp=" + catchUp);
        long now = System.currentTimeMillis();
        clearPreviousSchedule(c);
        android.content.SharedPreferences prefs = c.getSharedPreferences(PREF, Context.MODE_PRIVATE);
        java.util.Set<String> keep = new java.util.HashSet<>();
        int created = 0;
        for (int i = -1; i < 8; i++) {
            LocalDate day = LocalDate.now().plusDays(i);
            if (!AttendanceRules.isWorkday(c, day)) continue;
            for (int type = ARRIVAL; type <= EVENING_OUT; type++) {
                ReminderFlow.Window w = ReminderFlow.window(c, type, day);
                if (now >= w.end) continue;
                long at;
                if (w.start > now) at = w.start;
                else if (catchUp) at = now;
                else continue;
                String key = day.toEpochDay() + ":" + type;
                keep.add(key);
                if (!force && prefs.getLong(key, Long.MIN_VALUE) == at && alarmRegistered(c, key)) continue;
                scheduleAt(c, day, type, at, 0, key);
                created++;
            }
        }
        AlarmManager am = c.getSystemService(AlarmManager.class);
        android.content.SharedPreferences.Editor editor = prefs.edit();
        boolean removed = false;
        for (String key : new ArrayList<>(prefs.getAll().keySet())) {
            if (keep.contains(key)) continue;
            long at = prefs.getLong(key, Long.MIN_VALUE);
            if (at > now) continue;
            cancelKey(c, am, key);
            editor.remove(key);
            removed = true;
        }
        if (removed) editor.commit();
        android.util.Log.i("AttendanceReminder", "schedule diff created=" + created + " kept=" + keep.size());
    }
    public static void retryArrival(Context c, LocalDate day, int attempt) {
        if (attempt > TaskPolicy.MAX_AUTOMATIC_ATTEMPTS) {
            android.util.Log.i("AttendanceReminder", "arrival location retries exhausted day=" + day);
            return;
        }
        if (!AttendanceRules.isEnabled(c) || !AttendanceRules.isWorkday(c, day)) return;
        ReminderFlow.Window w = ReminderFlow.window(c, ARRIVAL, day);
        long next = System.currentTimeMillis() + 60_000;
        if (next < w.end) scheduleAt(c, day, ARRIVAL, next, attempt, day.toEpochDay() + ":" + ARRIVAL);
    }
    static String signature(Context c) {
        return WorkConfig.start(c) + ":" + WorkConfig.lunchStart(c) + ":" + WorkConfig.lunchEnd(c)
                + ":" + WorkConfig.end(c) + ":" + WorkConfig.cycle(c);
    }
    private static boolean alarmRegistered(Context c, String key) {
        return PendingIntent.getBroadcast(c, 0, intent(c, key),
                PendingIntent.FLAG_NO_CREATE | PendingIntent.FLAG_IMMUTABLE) != null;
    }
    private static Intent intent(Context c, String key) {
        return new Intent(c, AlarmReceiver.class).setAction(c.getPackageName() + ".TASK_ALARM")
                .setData(Uri.parse("clockin://schedule/" + Uri.encode(key)));
    }
    private static void clearPreviousSchedule(Context c) {
        android.content.SharedPreferences legacy = c.getSharedPreferences("scheduled_alarms_v3", Context.MODE_PRIVATE);
        if (legacy.getAll().isEmpty()) return;
        AlarmManager am = c.getSystemService(AlarmManager.class);
        for (String key : new ArrayList<>(legacy.getAll().keySet())) cancelKey(c, am, key);
        legacy.edit().clear().commit();
    }
    private static void cancelKey(Context c, AlarmManager am, String key) {
        PendingIntent pi = PendingIntent.getBroadcast(c, 0, intent(c, key),
                PendingIntent.FLAG_NO_CREATE | PendingIntent.FLAG_IMMUTABLE);
        if (pi != null) { if (am != null) am.cancel(pi); pi.cancel(); }
    }
    private static void scheduleAt(Context c, LocalDate date, int type, long at, int attempt, String key) {
        AlarmManager am = c.getSystemService(AlarmManager.class);
        if (am == null) return;
        Intent i = intent(c, key).putExtra(EXTRA_DAY, date.toEpochDay()).putExtra(EXTRA_TYPE, type)
                .putExtra("attempt", attempt).putExtra("signature", signature(c));
        PendingIntent pi = PendingIntent.getBroadcast(c, 0, i, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        c.getSharedPreferences(PREF, Context.MODE_PRIVATE).edit().putLong(key, at).commit();
        try {
            if (canScheduleExact(c)) am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi);
            else am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi);
        } catch (SecurityException e) { am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi); }
    }
    static void cancelAll(Context c) {
        AlarmManager am = c.getSystemService(AlarmManager.class);
        android.content.SharedPreferences p = c.getSharedPreferences(PREF, Context.MODE_PRIVATE);
        for (String key : p.getAll().keySet()) cancelKey(c, am, key);
        p.edit().clear().commit();
        clearPreviousSchedule(c);
        if (AttendanceRules.prefs(c).getBoolean("legacy_alarms_removed", false)) return;
        // Remove alarms generated by the previous version. Arrival attempts were bounded by the 60-minute window.
        for (int d = -1; d < 9; d++) for (int type = 1; type <= 4; type++) for (int attempt = 0; attempt <= 60; attempt++) {
            long day = LocalDate.now().plusDays(d).toEpochDay();
            int id = (int)Math.abs((day * 31L + type * 7L + attempt) % Integer.MAX_VALUE);
            Intent old = new Intent(c, AlarmReceiver.class).setAction(c.getPackageName() + ".ALARM." + day + "." + type + "." + attempt);
            PendingIntent pi = PendingIntent.getBroadcast(c, id, old, PendingIntent.FLAG_NO_CREATE | PendingIntent.FLAG_IMMUTABLE);
            if (pi != null) { if (am != null) am.cancel(pi); pi.cancel(); }
        }
        AttendanceRules.prefs(c).edit().putBoolean("legacy_alarms_removed", true).commit();
    }
}
