package com.example.clockin;

import android.app.KeyguardManager;
import android.content.*;
import android.os.*;

/** Process-native, event-driven monitoring. The user-enabled accessibility service hosts it. */
final class ReminderRuntime {
    private static Context context;
    private static BroadcastReceiver receiver;
    private static boolean wasUnlocked;
    private static final Handler handler = new Handler(Looper.getMainLooper());
    private static final Runnable pulse = () -> ReminderFlow.tick(context);

    static boolean unlocked(Context c) {
        PowerManager p = c.getSystemService(PowerManager.class);
        KeyguardManager k = c.getSystemService(KeyguardManager.class);
        return p != null && k != null && TaskPolicy.unlocked(p.isInteractive(), k.isKeyguardLocked(), k.isDeviceLocked());
    }
    static void attach(Context c) {
        if (receiver != null) return;
        context = c.getApplicationContext();
        wasUnlocked = unlocked(c);
        receiver = new BroadcastReceiver() {
            @Override public void onReceive(Context c, Intent i) {
                if (Intent.ACTION_SCREEN_OFF.equals(i.getAction())) {
                    wasUnlocked = false;
                    WeComLauncher.cancelAll(c, "screen locked during launch");
                }
                observe(i.getAction());
            }
        };
        IntentFilter f = new IntentFilter(Intent.ACTION_SCREEN_OFF);
        f.addAction(Intent.ACTION_SCREEN_ON);
        f.addAction(Intent.ACTION_USER_PRESENT);
        if (Build.VERSION.SDK_INT >= 33) context.registerReceiver(receiver, f, Context.RECEIVER_EXPORTED);
        else context.registerReceiver(receiver, f);
        // Covers unlock between the initial snapshot and registration. A silent restore
        // that was already unlocked is deliberately not treated as a new unlock.
        observe("listener_registration_recheck");
    }
    static void observe(String source) {
        if (context == null) return;
        boolean current = unlocked(context);
        boolean transition = !wasUnlocked && current;
        wasUnlocked = current;
        if (transition) {
            ReminderFlow.unlockedEvent(context, source);
            // Keyguard can change during a screen-on callback. A live gate rejection
            // must not latch an unlock and swallow the later genuine USER_PRESENT.
            if (context != null) wasUnlocked = unlocked(context);
        }
    }
    static void nextTick(long delay) {
        handler.removeCallbacks(pulse);
        handler.postDelayed(pulse, Math.max(1, delay));
    }
    static void stopTick() { handler.removeCallbacks(pulse); }
    static void detach() {
        stopTick();
        if (receiver != null) context.unregisterReceiver(receiver);
        receiver = null;
        context = null;
    }
}
