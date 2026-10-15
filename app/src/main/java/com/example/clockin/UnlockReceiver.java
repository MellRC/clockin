package com.example.clockin;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

public class UnlockReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        if (Intent.ACTION_USER_PRESENT.equals(intent.getAction())) {
            ReminderNotifications.stopVibration(context);
        }
    }
}
