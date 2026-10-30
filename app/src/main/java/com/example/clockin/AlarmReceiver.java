package com.example.clockin;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

import java.time.LocalDate;

public class AlarmReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        android.util.Log.i("AttendanceReminder", "alarm received day=" + (intent == null ? -1 : intent.getLongExtra(AlarmScheduler.EXTRA_DAY, -1))
                + " type=" + (intent == null ? -1 : intent.getIntExtra(AlarmScheduler.EXTRA_TYPE, -1))
                + " mode=" + AttendanceRules.reminderMode(context) + " unlocked=" + ReminderRuntime.unlocked(context));
        if (intent == null || !AlarmScheduler.signature(context).equals(intent.getStringExtra("signature"))) {
            android.util.Log.i("AttendanceReminder", "alarm rejected: obsolete schedule signature"); return;
        }
        if (!AttendanceRules.isEnabled(context)) { ReminderFlow.cancel(context, "attendance disabled"); return; }
        AlarmScheduler.repairOnWake(context, false);
        LocalDate date = LocalDate.ofEpochDay(intent.getLongExtra(AlarmScheduler.EXTRA_DAY,
                LocalDate.now().toEpochDay()));
        int type = intent.getIntExtra(AlarmScheduler.EXTRA_TYPE, AlarmScheduler.ARRIVAL);
        int attempt = intent.getIntExtra("attempt", 0);
        if (!AttendanceRules.isWorkday(context, date)) return;

        ReminderFlow.Window window = ReminderFlow.window(context, type, date);
        if (!TaskPolicy.within(System.currentTimeMillis(), window.start, window.end)) return;
        if (type == AlarmScheduler.ARRIVAL) {
            if (ReminderFlow.arrivalSettled(context, date.toEpochDay())) {
                android.util.Log.i("AttendanceReminder", "arrival already opened; location check skipped");
                return;
            }
            Intent service = new Intent(context, LocationCheckService.class)
                    .putExtra(AlarmScheduler.EXTRA_DAY, date.toEpochDay())
                    .putExtra("attempt", attempt);
            try {
                context.startForegroundService(service);
            } catch (RuntimeException e) {
                AlarmScheduler.retryArrival(context, date, attempt + 1);
                android.util.Log.i("AttendanceReminder", "location FGS restricted; no location bypass");
            }
            return;
        }

        ReminderFlow.triggered(context, type, date.toEpochDay());
    }
}
