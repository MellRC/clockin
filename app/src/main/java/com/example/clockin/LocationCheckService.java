package com.example.clockin;

import android.Manifest;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;

import java.time.LocalDate;
import java.util.concurrent.atomic.AtomicBoolean;

public class LocationCheckService extends Service {
    private static final String CHANNEL = "location_check";
    private final AtomicBoolean finished = new AtomicBoolean(false);
    private LocationManager locationManager;
    private LocationListener listener;
    private Handler handler;
    private LocalDate date;
    private int attempt;

    @Override
    public void onCreate() {
        super.onCreate();
        handler = new Handler(Looper.getMainLooper());
        createChannel();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        date = LocalDate.ofEpochDay(intent == null
                ? LocalDate.now().toEpochDay()
                : intent.getLongExtra(AlarmScheduler.EXTRA_DAY, LocalDate.now().toEpochDay()));
        attempt = intent == null ? 0 : intent.getIntExtra("attempt", 0);
        startForeground(4102, buildNotification());

        if (!OfficeLocation.isSet(this)
                || checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION)
                != PackageManager.PERMISSION_GRANTED) {
            finish(false);
            ReminderNotifications.show(this, "需要设置公司位置", "请打开打卡应用并授权精确定位。", true);
            return START_NOT_STICKY;
        }

        locationManager = getSystemService(LocationManager.class);
        listener = location -> {
            if (finished.get()) return;
            java.time.LocalTime start = WorkConfig.start(LocationCheckService.this);
            boolean withinWindow = LocalDate.now().equals(date)
                    && !java.time.LocalTime.now().isBefore(start)
                    && !java.time.LocalTime.now().isAfter(start.plusMinutes(60));
            if (withinWindow && OfficeLocation.contains(this, location)) {
                finish(true);
            } else if (System.currentTimeMillis() - location.getTime() < 20_000L) {
                // Keep checking until the location request times out.
            }
        };

        boolean requested = false;
        try {
            if (locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER)) {
                locationManager.requestLocationUpdates(LocationManager.GPS_PROVIDER,
                        1000L, 0f, listener, Looper.getMainLooper());
                requested = true;
            }
            if (locationManager.isProviderEnabled(LocationManager.NETWORK_PROVIDER)) {
                locationManager.requestLocationUpdates(LocationManager.NETWORK_PROVIDER,
                        1000L, 0f, listener, Looper.getMainLooper());
                requested = true;
            }
        } catch (SecurityException ignored) {
            requested = false;
        }
        if (!requested) {
            finish(false);
        } else {
            handler.postDelayed(() -> finish(false), 18_000L);
        }
        return START_NOT_STICKY;
    }

    private void finish(boolean arrived) {
        if (!finished.compareAndSet(false, true)) return;
        if (handler != null) handler.removeCallbacksAndMessages(null);
        if (locationManager != null && listener != null) {
            try { locationManager.removeUpdates(listener); } catch (SecurityException ignored) { }
        }
        if (arrived) {
            if (ReminderNotifications.shouldShowLockedReminder(this)) {
                ReminderNotifications.showLockedAttendanceReminder(this, AlarmScheduler.ARRIVAL,
                        date.toEpochDay());
            } else {
                TriggerActivity.open(this, AlarmScheduler.ARRIVAL, date.toEpochDay(), false);
            }
        } else {
            AlarmScheduler.retryArrival(this, date, attempt + 1);
        }
        stopForeground(STOP_FOREGROUND_REMOVE);
        stopSelf();
    }

    private void createChannel() {
        if (Build.VERSION.SDK_INT < 26) return;
        NotificationChannel channel = new NotificationChannel(CHANNEL, "位置检查",
                NotificationManager.IMPORTANCE_LOW);
        getSystemService(NotificationManager.class).createNotificationChannel(channel);
    }

    private Notification buildNotification() {
        Notification.Builder builder = Build.VERSION.SDK_INT >= 26
                ? new Notification.Builder(this, CHANNEL) : new Notification.Builder(this);
        return builder.setSmallIcon(android.R.drawable.ic_menu_mylocation)
                .setContentTitle("正在检查公司位置")
                .setContentText("仅在工作日早上 7:30 到 8:30 检查。")
                .setOngoing(true).build();
    }

    @Override
    public void onDestroy() {
        if (handler != null) handler.removeCallbacksAndMessages(null);
        if (locationManager != null && listener != null) {
            try { locationManager.removeUpdates(listener); } catch (SecurityException ignored) { }
        }
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) { return null; }
}
