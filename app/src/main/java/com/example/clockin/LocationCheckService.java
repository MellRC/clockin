package com.example.clockin;

import android.Manifest;
import android.app.*;
import android.content.*;
import android.content.pm.PackageManager;
import android.location.*;
import android.os.*;
import java.time.LocalDate;
import java.util.*;

/** Bounded, shared location acquisition. Repeated starts merge jobs instead of overwriting them. */
public class LocationCheckService extends Service {
    private static final String CHANNEL = "location_check_v2";
    private final Map<Long, Integer> arrivals = new HashMap<>();
    private final Set<String> checks = new HashSet<>();
    private final Handler handler = new Handler(Looper.getMainLooper());
    private LocationManager manager;
    private LocationListener listener;
    private boolean running, finishing;
    private String startedSignature;

    static void checkTask(Context c, String task, long day) {
        if (!OfficeLocation.isSet(c) || !AttendanceRules.isEnabled(c)
                || c.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            ReminderFlow.locationReady(c, task); return;
        }
        Intent i = new Intent(c, LocationCheckService.class).putExtra("check_task", task)
                .putExtra(AlarmScheduler.EXTRA_DAY, day);
        try { c.startForegroundService(i); }
        catch (RuntimeException e) { ReminderFlow.locationReady(c, task); }
    }
    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        if (finishing) { finishing = false; running = false; arrivals.clear(); checks.clear(); }
        android.util.Log.i("AttendanceReminder", "location service startId=" + startId
                + " enabled=" + AttendanceRules.isEnabled(this));
        // Honor the foreground-start contract before any disabled/expired early return.
        NotificationManager nm = getSystemService(NotificationManager.class);
        NotificationChannel channel = new NotificationChannel(CHANNEL, "位置检查", NotificationManager.IMPORTANCE_LOW);
        channel.setSound(null, null); channel.enableVibration(false); nm.createNotificationChannel(channel);
        try {
            startForeground(4102, new Notification.Builder(this, CHANNEL)
                    .setSmallIcon(android.R.drawable.ic_menu_mylocation).setContentTitle("正在检查公司位置")
                    .setContentText("仅在上班有效区间内检查，单次最多 18 秒。")
                    .setOnlyAlertOnce(true).setOngoing(true).build());
        } catch (RuntimeException e) { finish(false); return START_NOT_STICKY; }
        if (intent == null || !AttendanceRules.isEnabled(this)) { finish(false); return START_NOT_STICKY; }
        long day = intent.getLongExtra(AlarmScheduler.EXTRA_DAY, LocalDate.now().toEpochDay());
        ReminderFlow.Window w = ReminderFlow.window(this, AlarmScheduler.ARRIVAL, LocalDate.ofEpochDay(day));
        if (!TaskPolicy.within(System.currentTimeMillis(), w.start, w.end)) {
            if (!running) finish(false);
            return START_NOT_STICKY;
        }
        String task = intent.getStringExtra("check_task");
        if (task != null) checks.add(task);
        else arrivals.put(day, intent.getIntExtra("attempt", 0));
        if (running) return START_NOT_STICKY;
        running = true;
        startedSignature = AlarmScheduler.signature(this);
        if (!OfficeLocation.isSet(this) || checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION)
                != PackageManager.PERMISSION_GRANTED) { finish(false); return START_NOT_STICKY; }
        manager = getSystemService(LocationManager.class);
        listener = location -> {
            if (!AttendanceRules.isEnabled(this)) { finish(false); return; }
            OfficeLocation.observed(location);
            if (OfficeLocation.currentInside(this)) finish(true);
        };
        boolean requested = false;
        try {
            for (String p : new String[]{LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER}) {
                if (manager != null && manager.isProviderEnabled(p)) {
                    manager.requestLocationUpdates(p, 1000, 0, listener, Looper.getMainLooper());
                    requested = true;
                }
            }
        } catch (RuntimeException ignored) { }
        if (!requested) finish(false);
        else handler.postDelayed(() -> finish(false), Math.min(18_000, Math.max(1, w.end - System.currentTimeMillis())));
        return START_NOT_STICKY;
    }
    private void finish(boolean inside) {
        if (finishing) return;
        finishing = true;
        android.util.Log.i("AttendanceReminder", "location service finished inside=" + inside
                + " pendingTasks=" + checks.size());
        cleanup();
        for (String id : checks) ReminderFlow.locationReady(this, id);
        if (AttendanceRules.isEnabled(this) && AlarmScheduler.signature(this).equals(startedSignature)) for (Map.Entry<Long,Integer> entry : arrivals.entrySet()) {
            if (inside) ReminderFlow.triggered(this, AlarmScheduler.ARRIVAL, entry.getKey());
            else AlarmScheduler.retryArrival(this, LocalDate.ofEpochDay(entry.getKey()), entry.getValue() + 1);
        }
        stopForeground(STOP_FOREGROUND_REMOVE);
        stopSelf();
    }
    private void cleanup() {
        handler.removeCallbacksAndMessages(null);
        if (manager != null && listener != null) {
            try { manager.removeUpdates(listener); } catch (RuntimeException ignored) { }
        }
    }
    @Override public void onDestroy() {
        cleanup();
        if (!finishing) for (String id : checks) ReminderFlow.locationReady(this, id);
        super.onDestroy();
    }
    @Override public IBinder onBind(Intent intent) { return null; }
}
