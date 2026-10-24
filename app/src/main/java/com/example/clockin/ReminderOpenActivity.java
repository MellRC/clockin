package com.example.clockin;
import android.app.Activity;
import android.os.Bundle;
/** A direct notification target. Never routes through the home activity. */
public final class ReminderOpenActivity extends Activity {
    public static final String OPEN="com.example.clockin.OPEN_WECOM_FROM_REMINDER";
    public static final String CONFIRM="com.example.clockin.CONFIRM_ATTENDANCE_FROM_REMINDER";
    private boolean handled;
    @Override protected void onCreate(Bundle b) { super.onCreate(b); }
    @Override protected void onResume() {
        super.onResume();
        if (!handled && getIntent()!=null && ReminderRuntime.unlocked(this)) {
            handled = true;
            String task = getIntent().getStringExtra(ReminderActionReceiver.TASK);
            if (OPEN.equals(getIntent().getAction())) ReminderFlow.openAction(this, task);
            else if (CONFIRM.equals(getIntent().getAction())) ReminderFlow.confirmComplete(this, task);
        }
        finish();
    }
}
