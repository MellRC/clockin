package com.example.clockin;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Looper;
import android.os.Handler;
import android.provider.Settings;
import android.view.View;
import android.view.Gravity;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ImageView;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;
import android.webkit.JavascriptInterface;
import android.webkit.WebChromeClient;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.view.ContextThemeWrapper;
import android.graphics.drawable.GradientDrawable;
import android.graphics.Color;
import android.content.res.ColorStateList;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import java.time.LocalTime;
import java.time.DayOfWeek;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import org.json.JSONException;
import org.json.JSONObject;

public class MainActivity extends Activity {
    private static final int REQUEST_LOCATION = 30;
    private WebView webView;
    private boolean webReady;
    private String pendingLocationSaveId = "";
    static boolean visible;

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        AttendanceRules.ensureDefaults(this);
        ReminderFlow.initialize(this);
        AlarmScheduler.repairOnWake(this, true);
        ReminderFlow.restore(this);
        WorkConfig.setTheme(this, "honey");
        buildWebView();
    }

    @Override protected void onResume() {
        super.onResume();
        visible = true;
        ReminderFlow.restore(this);
        ReminderFlow.catchUpOpen(this);
        syncWebState();
    }

    @Override protected void onPause() {
        visible = false;
        super.onPause();
    }

    private void buildWebView() {
        getWindow().setStatusBarColor(Color.rgb(245, 209, 27));
        getWindow().setNavigationBarColor(Color.WHITE);
        getWindow().getDecorView().setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR | View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR);
        webView = new WebView(this);
        webView.setVerticalScrollBarEnabled(false);
        webView.setHorizontalScrollBarEnabled(false);
        WebSettings settings = webView.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setAllowFileAccess(true);
        settings.setAllowContentAccess(false);
        settings.setAllowFileAccessFromFileURLs(false);
        settings.setAllowUniversalAccessFromFileURLs(false);
        webView.setWebChromeClient(new WebChromeClient());
        webView.addJavascriptInterface(new NativeBridge(), "AndroidBridge");
        webView.setWebViewClient(new WebViewClient() {
            @Override public void onPageFinished(WebView view, String url) {
                webReady = true;
                view.evaluateJavascript("document.body.classList.add('android-app');", null);
                syncWebState();
            }
        });
        setContentView(webView);
        webView.loadUrl("file:///android_asset/demo.html");
    }

    private void syncWebState() {
        if (webView == null || !webReady) return;
        webView.evaluateJavascript("if(window.applyNativeState)applyNativeState(" + appStateJson() + ");", null);
    }

    private void reportSaveResult(String requestId, boolean success) {
        if (requestId == null || requestId.isEmpty()) {
            Toast.makeText(this, success ? "设置已保存" : "保存失败，请重试", Toast.LENGTH_SHORT).show();
            return;
        }
        if (webView == null || !webReady) return;
        String id = JSONObject.quote(requestId);
        webView.evaluateJavascript("if(window.onNativeSaveResult)onNativeSaveResult(" + id + ","
                + success + ");", null);
    }

    private String appStateJson() {
        JSONObject state = new JSONObject();
        try {
            state.put("enabled", AttendanceRules.isEnabled(this));
            state.put("locationSet", OfficeLocation.isSet(this));
            state.put("salary", WorkConfig.salary(this));
            state.put("cycle", WorkConfig.cycle(this));
            state.put("anchorDoubleRest", AttendanceRules.isDoubleRestWeek(this, LocalDate.now()));
            state.put("start", WorkConfig.start(this).toString());
            state.put("lunchStart", WorkConfig.lunchStart(this).toString());
            state.put("lunchEnd", WorkConfig.lunchEnd(this).toString());
            state.put("end", WorkConfig.end(this).toString());
            state.put("locationPermission", checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION)
                    == PackageManager.PERMISSION_GRANTED);
            state.put("backgroundLocationPermission", Build.VERSION.SDK_INT < 29
                    || checkSelfPermission(Manifest.permission.ACCESS_BACKGROUND_LOCATION)
                    == PackageManager.PERMISSION_GRANTED);
            state.put("notificationPermission", ReminderFlow.isNotificationAllowed(this));
            state.put("reminderMode", AttendanceRules.reminderMode(this));
            state.put("pendingAttendanceReview", ReminderFlow.pendingReview(this));
            state.put("fullScreenPermission", ReminderFlow.canUseFullScreen(this));
            state.put("reminderVibrationBlocked", ReminderHaptics.blockedReason(this));
            state.put("foregroundStatusState", ReminderFlow.foregroundStatusState(this));
            state.put("foregroundStatusPermission", ReminderFlow.isForegroundStatusEnabled(this));
            state.put("exactAlarmPermission", Build.VERSION.SDK_INT < 31 || AlarmScheduler.canScheduleExact(this));
            state.put("accessibilityPermission", ClockInAccessibilityService.isEnabled(this));
            android.os.PowerManager power = (android.os.PowerManager) getSystemService(POWER_SERVICE);
            state.put("batteryPermission", Build.VERSION.SDK_INT < 23
                    || (power != null && power.isIgnoringBatteryOptimizations(getPackageName())));
        } catch (JSONException ignored) { }
        return state.toString();
    }

    private final class NativeBridge {
        @JavascriptInterface public String getAppState() { return appStateJson(); }

        @JavascriptInterface public void setEnabled(boolean enabled) {
            runOnUiThread(() -> {
                if (enabled) enableSchedule();
                else {
                    AttendanceRules.setEnabled(MainActivity.this, false);
                    ReminderFlow.cancel(MainActivity.this, "attendance paused");
                    stopService(new Intent(MainActivity.this, LocationCheckService.class));
                    syncWebState();
                    Toast.makeText(MainActivity.this, "已暂停自动打卡", Toast.LENGTH_SHORT).show();
                }
            });
        }

        @JavascriptInterface public void setReminderMode(String mode, String requestId) {
            runOnUiThread(() -> {
                if (!ReminderFlow.MODE_SILENT.equals(mode) && !ReminderFlow.MODE_REMINDER.equals(mode)) {
                    reportSaveResult(requestId, false); return;
                }
                if (!AttendanceRules.setReminderMode(MainActivity.this, mode)) {
                    reportSaveResult(requestId, false); return;
                }
                ReminderFlow.onModeChanged(MainActivity.this, mode);
                syncWebState();
                reportSaveResult(requestId, true);
            });
        }

        @JavascriptInterface public void confirmAttendance(String taskId) {
            runOnUiThread(() -> {
                ReminderFlow.confirmComplete(MainActivity.this, taskId);
                syncWebState();
                Toast.makeText(MainActivity.this, "已记录手动确认；这不代表系统已核验企业微信记录", Toast.LENGTH_LONG).show();
            });
        }

        @JavascriptInterface public void retryWeCom(String taskId) {
            runOnUiThread(() -> {
                ReminderFlow.retryOpen(MainActivity.this, taskId);
                syncWebState();
            });
        }

        @JavascriptInterface public void setOfficeLocation(String requestId) {
            runOnUiThread(() -> {
                pendingLocationSaveId = requestId == null ? "" : requestId;
                MainActivity.this.setOfficeLocation();
            });
        }

        @JavascriptInterface public void openTimePicker(String key, String currentValue) {
            if (!"start".equals(key) && !"lunchStart".equals(key)
                    && !"lunchEnd".equals(key) && !"end".equals(key)) return;
            runOnUiThread(() -> {
                LocalTime current;
                try { current = LocalTime.parse(currentValue); }
                catch (RuntimeException e) {
                    current = "start".equals(key) ? WorkConfig.start(MainActivity.this)
                            : "lunchStart".equals(key) ? WorkConfig.lunchStart(MainActivity.this)
                            : "lunchEnd".equals(key) ? WorkConfig.lunchEnd(MainActivity.this)
                            : WorkConfig.end(MainActivity.this);
                }
                showWheelTimePicker(current, selected -> {
                    if (webView == null || !webReady) return;
                    String time = JSONObject.quote(selected.toString());
                    String field = JSONObject.quote(key);
                    webView.evaluateJavascript("if(window.onNativeTimeSelected)window.onNativeTimeSelected("
                            + field + "," + time + ");", null);
                });
            });
        }

        @JavascriptInterface public void setCycle(String cycle, String requestId) {
            runOnUiThread(() -> {
                if (!"alternating".equals(cycle) && !"double".equals(cycle) && !"single".equals(cycle)) {
                    reportSaveResult(requestId, false); return;
                }
                if (!WorkConfig.setSalary(MainActivity.this, WorkConfig.salary(MainActivity.this), cycle)) {
                    reportSaveResult(requestId, false); return;
                }
                if (AttendanceRules.isEnabled(MainActivity.this)) {
                    ReminderFlow.cancel(MainActivity.this, "workday schedule changed");
                    AlarmScheduler.scheduleWeek(MainActivity.this);
                }
                syncWebState();
                reportSaveResult(requestId, true);
            });
        }

        @JavascriptInterface public void setThisWeekDoubleRest(boolean doubleRest, String requestId) {
            runOnUiThread(() -> {
                if (!AttendanceRules.setThisWeekDoubleRest(MainActivity.this, doubleRest)) {
                    reportSaveResult(requestId, false); return;
                }
                if (AttendanceRules.isEnabled(MainActivity.this)) {
                    ReminderFlow.cancel(MainActivity.this, "this week's workday schedule changed");
                    AlarmScheduler.scheduleWeek(MainActivity.this);
                }
                syncWebState();
                reportSaveResult(requestId, true);
            });
        }

        @JavascriptInterface public void setSalary(String value) {
            try {
                double salary = Math.max(0d, Double.parseDouble(value));
                WorkConfig.setSalary(MainActivity.this, salary, WorkConfig.cycle(MainActivity.this));
            } catch (RuntimeException ignored) { }
        }

        @JavascriptInterface public void saveSalary(String value, String requestId) {
            runOnUiThread(() -> {
                try {
                    double salary = Double.parseDouble(value);
                    if (!Double.isFinite(salary) || salary < 0) { reportSaveResult(requestId, false); return; }
                    reportSaveResult(requestId, WorkConfig.setSalary(MainActivity.this, salary,
                            WorkConfig.cycle(MainActivity.this)));
                } catch (RuntimeException e) { reportSaveResult(requestId, false); }
            });
        }

        @JavascriptInterface public void saveTimes(String start, String lunchStart, String lunchEnd, String end, String requestId) {
            runOnUiThread(() -> {
                try {
                    LocalTime a = LocalTime.parse(start), b = LocalTime.parse(lunchStart);
                    LocalTime c = LocalTime.parse(lunchEnd), d = LocalTime.parse(end);
                    if (!a.isBefore(b) || !b.isBefore(c) || !c.isBefore(d)) {
                        reportSaveResult(requestId, false);
                        return;
                    }
                    if (!WorkConfig.setTimes(MainActivity.this, a, b, c, d)) {
                        reportSaveResult(requestId, false); return;
                    }
                    if (AttendanceRules.isEnabled(MainActivity.this)) {
                        ReminderFlow.cancel(MainActivity.this, "work time schedule changed");
                        AlarmScheduler.scheduleWeek(MainActivity.this);
                    }
                    syncWebState();
                    reportSaveResult(requestId, true);
                } catch (RuntimeException ignored) { reportSaveResult(requestId, false); }
            });
        }

        @JavascriptInterface public void testWeCom() {
            runOnUiThread(() -> {
                int outcome = WeComLauncher.launch(MainActivity.this);
                if (outcome == WeComLauncher.NOT_INSTALLED) {
                    Toast.makeText(MainActivity.this, "请先安装企业微信", Toast.LENGTH_LONG).show();
                } else if (outcome == WeComLauncher.ALREADY_FOREGROUND) {
                    Toast.makeText(MainActivity.this, "企业微信已在前台，请核对打卡", Toast.LENGTH_LONG).show();
                } else if (outcome != WeComLauncher.SUBMITTED) {
                    Toast.makeText(MainActivity.this, "暂时无法打开企业微信，请从通知进入", Toast.LENGTH_LONG).show();
                }
            });
        }

        @JavascriptInterface public void showPermissionNotes() {
            runOnUiThread(MainActivity.this::showPermissionNotes);
        }

        @JavascriptInterface public void openReminderSettings() {
            runOnUiThread(MainActivity.this::openFullScreenSettings);
        }

        @JavascriptInterface public void openLocationSettings() {
            runOnUiThread(() -> startActivity(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                    android.net.Uri.parse("package:" + getPackageName()))));
        }

        @JavascriptInterface public void openNotificationSettings() {
            runOnUiThread(() -> {
                Intent intent = new Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                        .putExtra(Settings.EXTRA_APP_PACKAGE, getPackageName());
                try { startActivity(intent); }
                catch (RuntimeException ignored) { openApplicationDetails(); }
            });
        }

        @JavascriptInterface public void openForegroundStatusSettings() {
            runOnUiThread(() -> {
                Intent intent = new Intent(Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS)
                        .putExtra(Settings.EXTRA_APP_PACKAGE, getPackageName())
                        .putExtra(Settings.EXTRA_CHANNEL_ID, ReminderFlow.foregroundStatusChannelId(MainActivity.this));
                try { startActivity(intent); }
                catch (RuntimeException e) { openNotificationSettings(); }
            });
        }

        @JavascriptInterface public void openExactAlarmPermissionSettings() {
            runOnUiThread(MainActivity.this::openExactAlarmSettings);
        }

        @JavascriptInterface public void openAccessibilityPermissionSettings() {
            runOnUiThread(MainActivity.this::openAccessibilitySettings);
        }

        @JavascriptInterface public void openBatteryPermissionSettings() {
            runOnUiThread(MainActivity.this::openBatterySettings);
        }

        @JavascriptInterface public void openFullScreenPermissionSettings() {
            runOnUiThread(MainActivity.this::openFullScreenSettings);
        }
    }

    private void showWheelTimePicker(LocalTime current, Consumer<LocalTime> onSelected) {
        ContextThemeWrapper themedContext = new ContextThemeWrapper(this, R.style.WheelTimePickerDialogTheme);

        LinearLayout pickerRow = new LinearLayout(themedContext);
        pickerRow.setOrientation(LinearLayout.HORIZONTAL);
        pickerRow.setGravity(Gravity.CENTER);
        pickerRow.setPadding(dp(18), dp(8), dp(18), dp(4));
        pickerRow.setBackgroundColor(Color.WHITE);

        TimeWheelPickerView hours = createTimeWheel(themedContext, "小时", 23, current.getHour());
        TimeWheelPickerView minutes = createTimeWheel(themedContext, "分钟", 59, current.getMinute());
        pickerRow.addView(hours, new LinearLayout.LayoutParams(0, dp(190), 1));
        TextView separator = text(":", 27, 0xFF2D2D2D, true);
        separator.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams separatorParams = new LinearLayout.LayoutParams(dp(28), dp(190));
        pickerRow.addView(separator, separatorParams);
        pickerRow.addView(minutes, new LinearLayout.LayoutParams(0, dp(190), 1));

        android.app.AlertDialog dialog = new android.app.AlertDialog.Builder(themedContext)
                .setTitle("选择时间")
                .setView(pickerRow)
                .setNegativeButton("取消", null)
                .setPositiveButton("确定", (d, which) -> onSelected.accept(
                        LocalTime.of(hours.getValue(), minutes.getValue())))
                .create();
        dialog.show();
        dialog.getButton(android.app.AlertDialog.BUTTON_POSITIVE).setTextColor(0xFF6B4A00);
        dialog.getButton(android.app.AlertDialog.BUTTON_NEGATIVE).setTextColor(0xFF71685B);
    }

    private TimeWheelPickerView createTimeWheel(ContextThemeWrapper themedContext, String label, int max, int selected) {
        return new TimeWheelPickerView(themedContext, label, max, selected);
    }
    private void setOfficeLocation() {
        if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.ACCESS_FINE_LOCATION,
                    Manifest.permission.ACCESS_COARSE_LOCATION}, REQUEST_LOCATION);
            return;
        }
        LocationManager manager = getSystemService(LocationManager.class);
        LocationListener listener = location -> {
            boolean saved = OfficeLocation.save(this, location);
            if (saved) syncWebState();
            reportSaveResult(pendingLocationSaveId, saved);
            pendingLocationSaveId = "";
        };
        try {
            String provider = manager.isProviderEnabled(LocationManager.GPS_PROVIDER)
                    ? LocationManager.GPS_PROVIDER
                    : manager.isProviderEnabled(LocationManager.NETWORK_PROVIDER)
                    ? LocationManager.NETWORK_PROVIDER : null;
            if (provider == null) {
                reportSaveResult(pendingLocationSaveId, false);
                pendingLocationSaveId = "";
                return;
            }
            manager.requestSingleUpdate(provider, listener, Looper.getMainLooper());
            Toast.makeText(this, "正在获取当前位置…", Toast.LENGTH_SHORT).show();
        } catch (SecurityException e) {
            reportSaveResult(pendingLocationSaveId, false);
            pendingLocationSaveId = "";
        }
    }

    @Override public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] results) {
        super.onRequestPermissionsResult(requestCode, permissions, results);
        if (requestCode == REQUEST_LOCATION && results.length > 0
                && results[0] == PackageManager.PERMISSION_GRANTED) setOfficeLocation();
        else if (requestCode == REQUEST_LOCATION) {
            reportSaveResult(pendingLocationSaveId, false);
            pendingLocationSaveId = "";
            Toast.makeText(this, "需要精确定位来设置公司范围", Toast.LENGTH_LONG).show();
        }
    }

    private void enableSchedule() {
        if (!OfficeLocation.isSet(this)) {
            Toast.makeText(this, "请先在公司设置位置", Toast.LENGTH_LONG).show();
            syncWebState();
            return;
        }
        if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            Toast.makeText(this, "请先允许精确定位", Toast.LENGTH_LONG).show();
            syncWebState();
            return;
        }
        if (Build.VERSION.SDK_INT >= 29 && checkSelfPermission(Manifest.permission.ACCESS_BACKGROUND_LOCATION)
                != PackageManager.PERMISSION_GRANTED) {
            new android.app.AlertDialog.Builder(this)
                    .setMessage("请在系统定位权限中选择“始终允许”，后台才能检测是否到公司。")
                    .setPositiveButton("打开应用权限", (d, w) -> startActivity(new Intent(
                            Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                            android.net.Uri.parse("package:" + getPackageName()))))
                    .setNegativeButton("稍后", null).show();
            syncWebState();
            return;
        }
        if (Build.VERSION.SDK_INT >= 31 && !AlarmScheduler.canScheduleExact(this)) {
            openExactAlarmSettings();
            Toast.makeText(this, "允许精确闹钟后，再点一次开启", Toast.LENGTH_LONG).show();
            syncWebState();
            return;
        }
        if (!ClockInAccessibilityService.isEnabled(this)) {
            openAccessibilitySettings();
            Toast.makeText(this, "开启“工力全开解锁与窗口辅助”后，再点一次", Toast.LENGTH_LONG).show();
            syncWebState();
            return;
        }
        AttendanceRules.setEnabled(this, true);
        AlarmScheduler.scheduleWeek(this);
        syncWebState();
        Toast.makeText(this, "已安排未来一周的打卡", Toast.LENGTH_LONG).show();
    }

    private void setThisWeekDoubleRest(boolean doubleRest) {
        AttendanceRules.setThisWeekDoubleRest(this, doubleRest);
        if (AttendanceRules.isEnabled(this)) {
            ReminderFlow.cancel(this, "this week's workday schedule changed");
            AlarmScheduler.scheduleWeek(this);
        }
        syncWebState();
        String next = AttendanceRules.isDoubleRestWeek(this, LocalDate.now().plusWeeks(1)) ? "双休" : "单休";
        Toast.makeText(this, "本周已设置，下周自动" + next, Toast.LENGTH_SHORT).show();
    }

    private void openAccessibilitySettings() { startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)); }

    private void openApplicationDetails() {
        startActivity(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                android.net.Uri.parse("package:" + getPackageName())));
    }

    private void openExactAlarmSettings() {
        if (Build.VERSION.SDK_INT >= 31) {
            try {
                startActivity(new Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM,
                        android.net.Uri.parse("package:" + getPackageName())));
            } catch (RuntimeException ignored) {
                startActivity(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                        android.net.Uri.parse("package:" + getPackageName())));
            }
        }
    }

    private void openFullScreenSettings() {
        try {
            if (Build.VERSION.SDK_INT >= 34 && !getSystemService(android.app.NotificationManager.class).canUseFullScreenIntent()) {
                startActivity(new Intent(Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT,
                        android.net.Uri.parse("package:" + getPackageName())));
            } else {
                startActivity(new Intent(Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS)
                        .putExtra(Settings.EXTRA_APP_PACKAGE, getPackageName())
                        .putExtra(Settings.EXTRA_CHANNEL_ID, ReminderFlow.LOCK_CHANNEL));
            }
        } catch (RuntimeException ignored) {
            startActivity(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                    android.net.Uri.parse("package:" + getPackageName())));
        }
    }

    private void openBatterySettings() {
        try {
            startActivity(new Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS));
        } catch (RuntimeException ignored) {
            startActivity(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                    android.net.Uri.parse("package:" + getPackageName())));
        }
    }

    private void showPermissionNotes() {
        new android.app.AlertDialog.Builder(this).setTitle("首次设置")
                .setMessage("需要定位、通知和精确闹钟权限。无障碍由你主动授权，用于解锁与窗口身份观察，不读取聊天文字，不自动点击。自动打卡结果识别尚未实现。系统限制自动打开时，请点击通知入口。锁屏必须由你本人解锁。")
                .setPositiveButton("知道了", null).show();
    }

    private TextView text(String value, int size, int color, boolean bold) {
        TextView view = new TextView(this);
        view.setText(value);
        view.setTextSize(size);
        view.setTextColor(color);
        if (bold) view.setTypeface(null, android.graphics.Typeface.BOLD);
        return view;
    }

    private int dp(int value) { return Math.round(value * getResources().getDisplayMetrics().density); }
}