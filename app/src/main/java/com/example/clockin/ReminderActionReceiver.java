package com.example.clockin;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
public final class ReminderActionReceiver extends BroadcastReceiver {
 public static final String DISMISS="com.example.clockin.DISMISS_REMINDER";
 public static final String STOP="com.example.clockin.STOP_REMINDER", EXPIRE="com.example.clockin.EXPIRE_REMINDER", VIBRATION_CYCLE="com.example.clockin.VIBRATION_CYCLE", UNLOCK_CHECK="com.example.clockin.UNLOCK_CHECK", TASK="reminder_task_id";
 @Override public void onReceive(Context c,Intent i){if(i!=null&&DISMISS.equals(i.getAction()))ReminderFlow.dismissed(c,i.getStringExtra(TASK));else if(i!=null&&STOP.equals(i.getAction()))ReminderFlow.stop(c,i.getStringExtra(TASK));else if(i!=null&&EXPIRE.equals(i.getAction()))ReminderFlow.expire(c,i.getStringExtra(TASK));else if(i!=null&&VIBRATION_CYCLE.equals(i.getAction()))ReminderFlow.vibrationCycle(c,i.getStringExtra(TASK));else if(i!=null&&UNLOCK_CHECK.equals(i.getAction()))ReminderFlow.unlockCheck(c);}
}
