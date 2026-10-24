package com.example.clockin;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

public class BootReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        if (intent == null) return;
        String action = intent.getAction();
        if (!Intent.ACTION_BOOT_COMPLETED.equals(action) && !Intent.ACTION_TIME_CHANGED.equals(action)
                && !Intent.ACTION_TIMEZONE_CHANGED.equals(action) && !Intent.ACTION_MY_PACKAGE_REPLACED.equals(action)) return;
        AttendanceRules.ensureDefaults(context);
        AlarmScheduler.rescheduleAll(context);
        ReminderFlow.restore(context);
    }
}
