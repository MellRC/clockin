package com.example.clockin;

import android.app.Activity;
import android.app.KeyguardManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.Color;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

public class TriggerActivity extends Activity {
    private static final int BRAND_YELLOW = Color.rgb(255, 203, 111);
    private boolean launched;
    private float downX;
    private float downY;
    private FrameLayout reminderRoot;
    private final Handler pulseHandler = new Handler(Looper.getMainLooper());
    private TextView reminderTitle;
    private PulseWaveView waveView;
    private long vibrationStart;
    private boolean userPresentReceiverRegistered;
    private final BroadcastReceiver userPresentReceiver = new BroadcastReceiver() {
        @Override public void onReceive(Context context, Intent intent) {
            if (!Intent.ACTION_USER_PRESENT.equals(intent.getAction())) return;
            pulseHandler.postDelayed(() -> {
                KeyguardManager keyguard = getSystemService(KeyguardManager.class);
                if (keyguard == null || !keyguard.isKeyguardLocked()) launch();
            }, 350L);
        }
    };
    private final Runnable pulseFrame = new Runnable() {
        @Override public void run() {
            if (reminderTitle == null || waveView == null) return;
            long elapsed = Math.max(0, SystemClock.elapsedRealtime() - vibrationStart);
            long phase = elapsed % ReminderNotifications.VIBRATION_CYCLE_MS;
            long pulseElapsed = phase < 700 ? phase : (phase >= 1050 && phase < 1750 ? phase - 1050 : -1);
            float tilt = pulseElapsed < 0 ? 0f
                    : (float) Math.sin(pulseElapsed * .045) * 6f;
            reminderTitle.setPivotX(reminderTitle.getWidth() / 2f);
            reminderTitle.setPivotY(reminderTitle.getHeight() / 2f);
            reminderTitle.setRotation(tilt);
            waveView.setPhase(phase);
            pulseHandler.postDelayed(this, 16);
        }
    };

    public static void open(Context context, int type, long day, boolean showLockReminder) {
        Intent intent = new Intent(context, TriggerActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP)
                .putExtra(AlarmScheduler.EXTRA_TYPE, type)
                .putExtra(AlarmScheduler.EXTRA_DAY, day)
                .putExtra(ReminderNotifications.EXTRA_REMINDER_POSTED, true)
                .putExtra(ReminderNotifications.EXTRA_SHOW_LOCK_REMINDER, showLockReminder);
        try {
            context.startActivity(intent);
        } catch (RuntimeException e) {
            ReminderNotifications.show(context, "打卡时间到啦！", "请解锁手机后打开企业微信。", false);
        }
    }

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        KeyguardManager keyguard = getSystemService(KeyguardManager.class);
        boolean locked = keyguard != null && keyguard.isKeyguardLocked();
        boolean showReminder = getIntent().getBooleanExtra(
                ReminderNotifications.EXTRA_SHOW_LOCK_REMINDER, false) && locked;
        if (showReminder) showLockReminder();
        tryUnlockThenLaunch();
    }

    @Override
    protected void onResume() {
        super.onResume();
        tryUnlockThenLaunch();
    }

    @Override
    protected void onStart() {
        super.onStart();
        IntentFilter filter = new IntentFilter(Intent.ACTION_USER_PRESENT);
        if (Build.VERSION.SDK_INT >= 33) registerReceiver(userPresentReceiver, filter, Context.RECEIVER_NOT_EXPORTED);
        else registerReceiver(userPresentReceiver, filter);
        userPresentReceiverRegistered = true;
    }

    private void showLockReminder() {
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED
                | WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON
                | WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        getWindow().setStatusBarColor(BRAND_YELLOW);
        getWindow().setNavigationBarColor(BRAND_YELLOW);

        reminderRoot = new FrameLayout(this);
        reminderRoot.setBackgroundColor(BRAND_YELLOW);
        reminderRoot.setClipChildren(false);

        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setGravity(Gravity.CENTER_HORIZONTAL);
        content.setPadding(dp(28), dp(24), dp(28), dp(24));
        content.setClipChildren(false);
        FrameLayout.LayoutParams contentParams = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.CENTER);

        FrameLayout titleWrap = new FrameLayout(this);
        titleWrap.setClipChildren(false);
        waveView = new PulseWaveView(this);
        titleWrap.addView(waveView, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));
        reminderTitle = new TextView(this);
        reminderTitle.setText("打卡时间到啦！");
        reminderTitle.setTextColor(Color.rgb(255, 249, 230));
        reminderTitle.setTextSize(25);
        reminderTitle.setGravity(Gravity.CENTER);
        reminderTitle.setTypeface(android.graphics.Typeface.DEFAULT, android.graphics.Typeface.BOLD);
        reminderTitle.setShadowLayer(dp(3), 0, dp(1), 0x558A5A00);
        reminderTitle.setClickable(false);
        titleWrap.addView(reminderTitle, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));
        LinearLayout.LayoutParams titleWrapParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(76));
        titleWrapParams.leftMargin = -dp(58);
        titleWrapParams.rightMargin = -dp(58);
        content.addView(titleWrap, titleWrapParams);

        reminderRoot.addView(content, contentParams);
        reminderRoot.setOnTouchListener((view, event) -> {
            if (event.getAction() == MotionEvent.ACTION_DOWN) {
                downX = event.getX();
                downY = event.getY();
                return true;
            }
            if (event.getAction() == MotionEvent.ACTION_UP) {
                float dx = event.getX() - downX;
                float dy = event.getY() - downY;
                if (dy < -dp(72) && Math.abs(dy) > Math.abs(dx) * 1.2f) {
                    dismissReminderForUnlock();
                }
                return true;
            }
            return event.getAction() == MotionEvent.ACTION_MOVE;
        });
        setContentView(reminderRoot);
        vibrationStart = getSharedPreferences(ReminderNotifications.PREFS, MODE_PRIVATE)
                .getLong(ReminderNotifications.VIBRATION_START, SystemClock.elapsedRealtime());
        pulseHandler.post(pulseFrame);
    }

    private void dismissReminderForUnlock() {
        if (reminderRoot == null) return;
        reminderRoot.animate().translationY(-reminderRoot.getHeight()).alpha(0f)
                .setDuration(220).withEndAction(() -> {
                    KeyguardManager keyguard = getSystemService(KeyguardManager.class);
                    if (keyguard != null && Build.VERSION.SDK_INT >= 26) {
                        keyguard.requestDismissKeyguard(this,
                                new KeyguardManager.KeyguardDismissCallback() {
                                    @Override public void onDismissSucceeded() { launch(); }
                                });
                    }
                }).start();
    }

    private void tryUnlockThenLaunch() {
        if (launched) return;
        KeyguardManager keyguard = getSystemService(KeyguardManager.class);
        if (keyguard != null && keyguard.isKeyguardLocked()) return;
        launch();
    }

    private void launch() {
        if (launched) return;
        launched = true;
        ReminderNotifications.stopVibration(this);
        if (ClockInAccessibilityService.prepareLaunch()) {
            finish();
            return;
        }
        WeComLauncher.launch(this);
        finish();
    }

    private int dp(float value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    @Override
    protected void onDestroy() {
        if (userPresentReceiverRegistered) {
            unregisterReceiver(userPresentReceiver);
            userPresentReceiverRegistered = false;
        }
        pulseHandler.removeCallbacks(pulseFrame);
        super.onDestroy();
    }

    private final class PulseWaveView extends View {
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private long phase;

        PulseWaveView(Context context) {
            super(context);
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(dp(1.5f));
        }

        void setPhase(long phase) {
            this.phase = phase;
            invalidate();
        }

        @Override
        protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);
            float cx = getWidth() / 2f;
            float cy = getHeight() / 2f;
            float maxRadius = Math.min(getWidth() * .48f, dp(230));
            for (long burst : new long[]{0, 1050}) {
                for (int index = 0; index < 3; index++) {
                    long age = phase - burst - index * 230L;
                    if (age < 0 || age >= 950) continue;
                    float progress = age / 950f;
                    float eased = 1f - (float) Math.pow(1f - progress, 3);
                    int alpha = Math.round(158 * (float) Math.pow(1f - progress, 1.3));
                    paint.setColor(Color.argb(alpha, 255, 249, 230));
                    float radius = dp(17) + (maxRadius - dp(17)) * eased;
                    canvas.drawCircle(cx, cy, radius, paint);
                }
            }
        }
    }

}
