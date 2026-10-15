package com.example.clockin;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

import java.time.LocalDate;

public class AlarmReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        if (!AttendanceRules.isEnabled(context)) return;
        AlarmScheduler.scheduleWeek(context, false);
        LocalDate date = LocalDate.ofEpochDay(intent.getLongExtra(AlarmScheduler.EXTRA_DAY,
                LocalDate.now().toEpochDay()));
        int type = intent.getIntExtra(AlarmScheduler.EXTRA_TYPE, AlarmScheduler.ARRIVAL);
        int attempt = intent.getIntExtra("attempt", 0);
        if (!AttendanceRules.isWorkday(context, date)) return;

        if (type == AlarmScheduler.ARRIVAL) {
            Intent service = new Intent(context, LocationCheckService.class)
                    .putExtra(AlarmScheduler.EXTRA_DAY, date.toEpochDay())
                    .putExtra("attempt", attempt);
            try {
                context.startForegroundService(service);
            } catch (RuntimeException e) {
                AlarmScheduler.retryArrival(context, date, attempt + 1);
                ReminderNotifications.show(context, "需要打开企业微信",
                        "请解锁手机并检查定位权限，然后打开企业微信。", true);
            }
            return;
        }

        if (ReminderNotifications.shouldShowLockedReminder(context)) {
            ReminderNotifications.showLockedAttendanceReminder(context, type, date.toEpochDay());
        } else {
            TriggerActivity.open(context, type, date.toEpochDay(), false);
        }
    }
}
