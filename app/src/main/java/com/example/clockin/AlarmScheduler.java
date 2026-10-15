package com.example.clockin;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.os.Build;

import java.time.LocalDate;
import java.time.LocalTime;

public final class AlarmScheduler {
    public static final String EXTRA_DAY = "day";
    public static final String EXTRA_TYPE = "type";
    public static final int ARRIVAL = 1;
    public static final int LUNCH_OUT = 2;
    public static final int LUNCH_IN = 3;
    public static final int EVENING_OUT = 4;
    private static final long ARRIVAL_RETRY_MINUTES = 1;

    private AlarmScheduler() {}

    public static boolean canScheduleExact(Context context) {
        AlarmManager am = context.getSystemService(AlarmManager.class);
        return Build.VERSION.SDK_INT < 31 || (am != null && am.canScheduleExactAlarms());
    }

    public static void scheduleWeek(Context context) {
        scheduleWeek(context, true);
    }

    public static void scheduleWeek(Context context, boolean catchUpCurrentMorning) {
        AttendanceRules.ensureDefaults(context);
        if (!AttendanceRules.isEnabled(context)) return;
        LocalDate today = LocalDate.now();
        LocalTime now = LocalTime.now();
        for (int i = 0; i < 8; i++) {
            LocalDate date = today.plusDays(i);
            if (!AttendanceRules.isWorkday(context, date)) continue;
            LocalTime start = WorkConfig.start(context);
            boolean morningWindowOpen = i == 0 && catchUpCurrentMorning
                    && !now.isBefore(start) && !now.isAfter(start.plusMinutes(60));
            if (morningWindowOpen) {
                scheduleAt(context, date, ARRIVAL, System.currentTimeMillis() + 500L, 0);
            } else {
                schedule(context, date, ARRIVAL, start);
            }
            schedule(context, date, LUNCH_OUT, WorkConfig.lunchStart(context));
            schedule(context, date, LUNCH_IN, WorkConfig.lunchEnd(context));
            schedule(context, date, EVENING_OUT, WorkConfig.end(context));
        }
    }

    public static void retryArrival(Context context, LocalDate date, int attempt) {
        long now = System.currentTimeMillis();
        long windowEnd = date.atTime(WorkConfig.start(context).plusMinutes(60))
                .atZone(java.time.ZoneId.systemDefault())
                .toInstant().toEpochMilli();
        if (now >= windowEnd) return;
        long next = Math.min(now + ARRIVAL_RETRY_MINUTES * 60_000L, windowEnd);
        scheduleAt(context, date, ARRIVAL, next, attempt);
    }

    private static void schedule(Context context, LocalDate date, int type, LocalTime time) {
        long at = date.atTime(time).atZone(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli();
        if (at > System.currentTimeMillis()) scheduleAt(context, date, type, at, 0);
    }

    private static void scheduleAt(Context context, LocalDate date, int type, long at, int attempt) {
        AlarmManager am = context.getSystemService(AlarmManager.class);
        if (am == null) return;
        int id = (int) Math.abs((date.toEpochDay() * 31L + type * 7L + attempt) % Integer.MAX_VALUE);
        Intent intent = new Intent(context, AlarmReceiver.class)
                .setAction(context.getPackageName() + ".ALARM." + date.toEpochDay() + "." + type + "." + attempt)
                .putExtra(EXTRA_DAY, date.toEpochDay()).putExtra(EXTRA_TYPE, type)
                .putExtra("attempt", attempt);
        PendingIntent pi = PendingIntent.getBroadcast(context, id, intent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        Intent showIntent = new Intent(context, MainActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        PendingIntent show = PendingIntent.getActivity(context, 8801, showIntent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        if (Build.VERSION.SDK_INT >= 31 && !canScheduleExact(context)) {
            am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi);
        } else {
            try {
                am.setAlarmClock(new AlarmManager.AlarmClockInfo(at, show), pi);
            } catch (SecurityException ignored) {
                am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi);
            }
        }
    }
}
