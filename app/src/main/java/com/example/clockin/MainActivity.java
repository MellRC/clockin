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
import android.app.TimePickerDialog;
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
import org.json.JSONException;
import org.json.JSONObject;

public class MainActivity extends Activity {
    private static final int REQUEST_LOCATION = 30;
    private LinearLayout content;
    private TextView summary;
    private TextView locationStatus;
    private Switch masterSwitch;
    private ScrollView scroll;
    private ScrollView salaryScroll;
    private ScrollView settingsScroll;
    private FrameLayout pageHost;
    private View scheduleAnchor;
    private View settingsAnchor;
    private TextView weekTitleView, weekCopyView, cycleSummaryView;
    private LinearLayout alternatingScheduleSettings;
    private Button weekDoubleButton, weekSingleButton;
    private TextView morningTimeView, lunchStartView, lunchEndView, workEndView;
    private TextView salaryEarnedView, salaryRateView, salaryStateView;
    private ProgressBar salaryProgress;
    private FrameLayout salaryCoinLayer;
    private String lastDisplayedEarnings;
    private EditText salaryInput;
    private final List<TextView> salaryIcons = new ArrayList<>();
    private final List<TextView> eventTimeViews = new ArrayList<>();
    private final List<Button> accentButtons = new ArrayList<>();
    private final List<Button> cycleModeButtons = new ArrayList<>();
    private final Handler ticker = new Handler(Looper.getMainLooper());
    private final Runnable tickerTask = new Runnable() {
        @Override public void run() { updateSalary(); ticker.postDelayed(this, 1000L); }
    };
    private View logoView;
    private Button primaryTestButton;
    private String salaryCycle = "alternating";
    private WebView webView;
    private boolean webReady;

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        AttendanceRules.ensureDefaults(this);
        salaryCycle = WorkConfig.cycle(this);
        WorkConfig.setTheme(this, "honey");
        buildWebView();
    }

    @Override protected void onResume() {
        super.onResume();
        if (webView != null) syncWebState();
        ticker.removeCallbacks(tickerTask);
    }

    @Override protected void onPause() { super.onPause(); ticker.removeCallbacks(tickerTask); }

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
            state.put("notificationPermission", Build.VERSION.SDK_INT < 24
                    || ReminderNotifications.isLockNotificationReady(this));
            android.app.NotificationManager notifications = getSystemService(android.app.NotificationManager.class);
            state.put("fullScreenPermission", Build.VERSION.SDK_INT < 34
                    || (notifications != null && notifications.canUseFullScreenIntent()));
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
                    syncWebState();
                    Toast.makeText(MainActivity.this, "已暂停自动打卡", Toast.LENGTH_SHORT).show();
                }
            });
        }

        @JavascriptInterface public void setOfficeLocation() {
            runOnUiThread(MainActivity.this::setOfficeLocation);
        }

        @JavascriptInterface public void setCycle(String cycle) {
            runOnUiThread(() -> {
                if (!"alternating".equals(cycle) && !"double".equals(cycle) && !"single".equals(cycle)) return;
                salaryCycle = cycle;
                WorkConfig.setSalary(MainActivity.this, WorkConfig.salary(MainActivity.this), cycle);
                if (AttendanceRules.isEnabled(MainActivity.this)) AlarmScheduler.scheduleWeek(MainActivity.this);
                syncWebState();
            });
        }

        @JavascriptInterface public void setThisWeekDoubleRest(boolean doubleRest) {
            runOnUiThread(() -> MainActivity.this.setThisWeekDoubleRest(doubleRest));
        }

        @JavascriptInterface public void setSalary(String value) {
            try {
                double salary = Math.max(0d, Double.parseDouble(value));
                WorkConfig.setSalary(MainActivity.this, salary, WorkConfig.cycle(MainActivity.this));
            } catch (RuntimeException ignored) { }
        }

        @JavascriptInterface public void saveTimes(String start, String lunchStart, String lunchEnd, String end) {
            runOnUiThread(() -> {
                try {
                    LocalTime a = LocalTime.parse(start), b = LocalTime.parse(lunchStart);
                    LocalTime c = LocalTime.parse(lunchEnd), d = LocalTime.parse(end);
                    if (!a.isBefore(b) || !b.isBefore(c) || !c.isBefore(d)) {
                        Toast.makeText(MainActivity.this, "请按上班、午休、下班顺序设置时间", Toast.LENGTH_LONG).show();
                        return;
                    }
                    WorkConfig.setTimes(MainActivity.this, a, b, c, d);
                    if (AttendanceRules.isEnabled(MainActivity.this)) AlarmScheduler.scheduleWeek(MainActivity.this);
                    syncWebState();
                } catch (RuntimeException ignored) {
                    Toast.makeText(MainActivity.this, "时间格式不正确", Toast.LENGTH_SHORT).show();
                }
            });
        }

        @JavascriptInterface public void testWeCom() {
            runOnUiThread(() -> {
                if (!WeComLauncher.launch(MainActivity.this)) {
                    Toast.makeText(MainActivity.this, "请先安装企业微信", Toast.LENGTH_LONG).show();
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

    private void buildView() {
        FrameLayout root = new FrameLayout(this);
        scroll = new ScrollView(this);
        content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(dp(20), dp(16), dp(20), dp(112));
        content.setBackgroundColor(0xFFFFFFFF);
        scroll.addView(content);
        pageHost = new FrameLayout(this);
        root.addView(pageHost, new FrameLayout.LayoutParams(-1, -1));

        LinearLayout top = horizontal();
        ImageView logo = new ImageView(this);
        logoView = logo;
        logo.setImageResource(com.example.clockin.R.drawable.ic_launcher_foreground);
        logo.setScaleType(ImageView.ScaleType.FIT_CENTER);
        logo.setPadding(dp(4), dp(4), dp(4), dp(4));
        logo.setContentDescription("工力全开小工力兽 Logo");
        logo.setBackground(round(0xFFFFCB6F, 0, 15));
        top.addView(logo, new LinearLayout.LayoutParams(dp(40), dp(40)));
        LinearLayout brand = new LinearLayout(this);
        brand.setOrientation(LinearLayout.VERTICAL);
        brand.setPadding(dp(10), 0, 0, 0);
        brand.addView(text("工力全开", 18, 0xFF3F3529, true));
        brand.addView(text("让每次打卡都准时", 11, 0xFF897F72, false));
        top.addView(brand, new LinearLayout.LayoutParams(0, -2, 1));
        top.addView(iconButton("＋", v -> setOfficeLocation()));
        top.addView(iconButton("🔔", v -> openFullScreenSettings()), margin(7, 0, 0, 0));
        content.addView(top, margin(0, 5, 0, 18));

        content.addView(text("今天的打卡", 24, 0xFF3F3529, true));
        TextView date = text(LocalDate.now().format(
                DateTimeFormatter.ofPattern("EEEE，M月d日", Locale.CHINA)), 12, 0xFF897F72, false);
        content.addView(date, margin(0, 4, 0, 15));

        LinearLayout statusCard = horizontal();
        statusCard.setPadding(dp(15), dp(12), dp(14), dp(12));
        statusCard.setBackground(round(0xFFFFFBF5, 0xFFF0E8DC, 17));
        LinearLayout statusWords = new LinearLayout(this);
        statusWords.setOrientation(LinearLayout.VERTICAL);
        statusWords.addView(text("今日自动打卡", 11, 0xFF897F72, false));
        summary = text("", 16, 0xFF3F3529, true);
        statusWords.addView(summary, margin(0, 4, 0, 0));
        statusWords.addView(text("早间到公司后打开企业微信", 10, 0xFF897F72, false), margin(0, 3, 0, 0));
        statusCard.addView(statusWords, new LinearLayout.LayoutParams(0, -2, 1));
        masterSwitch = new Switch(this);
        masterSwitch.setThumbTintList(ColorStateList.valueOf(0xFFFFFFFF));
        masterSwitch.setTrackTintList(ColorStateList.valueOf(0xFFFFE3A6));
        masterSwitch.setOnClickListener(v -> {
            if (masterSwitch.isChecked()) enableSchedule();
            else {
                AttendanceRules.setEnabled(this, false);
                refresh();
                Toast.makeText(this, "已暂停自动打卡", Toast.LENGTH_SHORT).show();
            }
        });
        statusCard.addView(masterSwitch);
        content.addView(statusCard, margin(0, 0, 0, 21));

        LinearLayout section = horizontal();
        TextView scheduleTitle = text("今天的安排  ›", 17, 0xFF3F3529, true);
        section.addView(scheduleTitle, new LinearLayout.LayoutParams(0, -2, 1));
        section.addView(text("工作日 · 4 个时段", 10, 0xFF897F72, false));
        content.addView(section, margin(0, 0, 0, 9));
        scheduleAnchor = section;
        LinearLayout events = new LinearLayout(this);
        events.setOrientation(LinearLayout.VERTICAL);
        addEvent(events, "☼", 0xFFFFCB6F, "07:30", "到达公司后打开", "限时 60 分钟 · 300 米围栏", "待触发", true);
        addEvent(events, "♨", 0xFFFFCB6F, "12:30", "午间下班卡", "定时打开企业微信", "已安排", false);
        addEvent(events, "↗", 0xFFFFCB6F, "13:15", "午间上班卡", "定时打开企业微信", "已安排", false);
        addEvent(events, "☾", 0xFFFFCB6F, "18:00", "下班卡", "定时打开企业微信", "已安排", false);
        content.addView(events);

        LinearLayout locationHeader = horizontal();
        locationHeader.addView(text("附近打卡点  ›", 17, 0xFF3F3529, true),
                new LinearLayout.LayoutParams(0, -2, 1));
        locationHeader.addView(text("300 米范围", 10, 0xFF897F72, false));
        content.addView(locationHeader, margin(0, 21, 0, 9));
        LinearLayout place = horizontal();
        place.setPadding(dp(14), dp(13), dp(0), dp(13));
        place.setBackground(round(0xFFFFFBF5, 0xFFF0E8DC, 17));
        LinearLayout placeText = new LinearLayout(this);
        placeText.setOrientation(LinearLayout.VERTICAL);
        TextView tag = text("⌖  公司位置", 10, 0xFF8A5A00, true);
        placeText.addView(tag);
        placeText.addView(text("公司打卡范围", 13, 0xFF3F3529, true), margin(0, 8, 0, 3));
        locationStatus = text("", 10, 0xFF897F72, false);
        placeText.addView(locationStatus);
        Button setLocation = textButton("重新设置　→", 0xFF8A5A00, 11);
        setLocation.setBackgroundColor(0x00000000);
        setLocation.setPadding(0, 0, 0, 0);
        setLocation.setOnClickListener(v -> setOfficeLocation());
        placeText.addView(setLocation, margin(-8, 7, 0, 0));
        place.addView(placeText, new LinearLayout.LayoutParams(0, -2, 1));
        FrameLayout map = new FrameLayout(this);
        map.setBackground(round(0xFFFFF5E3, 0, 15));
        TextView radius = text("300m", 10, 0xFF8A5A00, true);
        radius.setGravity(Gravity.CENTER);
        radius.setBackground(round(0x44FFCB6F, 0x55E0A12B, 60));
        FrameLayout.LayoutParams rp = new FrameLayout.LayoutParams(dp(72), dp(72), Gravity.CENTER);
        map.addView(radius, rp);
        TextView pin = text("●", 12, 0xFFFFFFFF, true);
        pin.setGravity(Gravity.CENTER);
        pin.setTextColor(0xFF4B3919);
        pin.setBackground(round(0xFFFFCB6F, 0, 30));
        FrameLayout.LayoutParams pp = new FrameLayout.LayoutParams(dp(27), dp(27), Gravity.CENTER);
        map.addView(pin, pp);
        place.addView(map, new LinearLayout.LayoutParams(dp(126), dp(118)));
        content.addView(place);

        LinearLayout weekHeader = horizontal();
        weekHeader.addView(text("本周排班", 17, 0xFF3F3529, true),
                new LinearLayout.LayoutParams(0, -2, 1));
        cycleSummaryView = text("大小周自动交替", 10, 0xFF897F72, false);
        weekHeader.addView(cycleSummaryView);
        content.addView(weekHeader, margin(0, 21, 0, 9));
        LinearLayout week = horizontal();
        week.setPadding(dp(13), dp(12), dp(12), dp(12));
        week.setBackground(round(0xFFFFFFFF, 0xFFF0E8DC, 17));
        TextView sun = text("☀", 18, 0xFFBA7B2D, false);
        sun.setGravity(Gravity.CENTER);
        sun.setBackground(round(0xFFFFF2DF, 0, 12));
        week.addView(sun, new LinearLayout.LayoutParams(dp(40), dp(40)));
        LinearLayout weekWords = new LinearLayout(this);
        weekWords.setOrientation(LinearLayout.VERTICAL);
        weekTitleView = text("", 12, 0xFF3F3529, true);
        weekWords.addView(weekTitleView);
        weekCopyView = text("", 9, 0xFF897F72, false);
        weekWords.addView(weekCopyView, margin(0, 4, 0, 0));
        week.addView(weekWords, new LinearLayout.LayoutParams(0, -2, 1));
        content.addView(week);

        LinearLayout actions = horizontal();
        Button permissions = textButton("权限设置", 0xFF70552B, 12);
        permissions.setBackground(round(0xFFFFF8EC, 0xFFF0E8DC, 13));
        permissions.setOnClickListener(v -> showPermissionNotes());
        primaryTestButton = textButton("立即试开", 0xFF4B3919, 12);
        primaryTestButton.setBackground(round(0xFFFFCB6F, 0, 13));
        primaryTestButton.setOnClickListener(v -> {
            if (!WeComLauncher.launch(this)) Toast.makeText(this, "请先安装企业微信", Toast.LENGTH_LONG).show();
        });
        actions.addView(permissions, new LinearLayout.LayoutParams(0, dp(45), 1));
        actions.addView(primaryTestButton, new LinearLayout.LayoutParams(0, dp(45), 1));
        content.addView(actions, margin(0, 13, 0, 10));
        content.addView(text("权限与后台运行", 17, 0xFF3F3529, true), margin(0, 19, 0, 8));
        LinearLayout permissionsList = new LinearLayout(this);
        permissionsList.setOrientation(LinearLayout.VERTICAL);
        permissionsList.setPadding(dp(12), dp(4), dp(12), dp(4));
        permissionsList.setBackground(round(0xFFFFFFFF, 0xFFF0E8DC, 17));
        addSettingRow(permissionsList, "自动打开服务", "清理企微最近任务", v -> openAccessibilitySettings());
        addSettingRow(permissionsList, "精确闹钟", "按设定时间触发", v -> openExactAlarmSettings());
        addSettingRow(permissionsList, "锁屏提醒", "锁屏时震动并响铃", v -> openFullScreenSettings());
        addSettingRow(permissionsList, "后台运行", "减少系统休眠限制", v -> openBatterySettings());
        settingsAnchor = permissionsList;
        content.addView(permissionsList);
        TextView note = text("锁屏时提醒解锁 · 解锁后继续打开企业微信", 9, 0xFF9A8F80, false);
        note.setGravity(Gravity.CENTER);
        content.addView(note);

        pageHost.addView(scroll, new FrameLayout.LayoutParams(-1, -1));
        salaryScroll = buildSalaryPage();
        settingsScroll = buildSettingsPage();
        pageHost.addView(salaryScroll, new FrameLayout.LayoutParams(-1, -1));
        pageHost.addView(settingsScroll, new FrameLayout.LayoutParams(-1, -1));
        salaryScroll.setVisibility(View.GONE);
        settingsScroll.setVisibility(View.GONE);

        FrameLayout navFrame = new FrameLayout(this);
        navFrame.setBackground(round(0xFFFFFEFC, 0xFFF0E8DC, 28));
        navFrame.setElevation(dp(10));
        View navIndicator = new View(this);
        navIndicator.setBackground(round(0xFFFFF4D9, 0, 24));
        FrameLayout.LayoutParams indicatorParams = new FrameLayout.LayoutParams(dp(98), dp(50));
        indicatorParams.leftMargin = dp(7);
        indicatorParams.topMargin = dp(6);
        navFrame.addView(navIndicator, indicatorParams);

        LinearLayout nav = horizontal();
        nav.setPadding(dp(7), dp(6), dp(7), dp(6));
        nav.setBackgroundColor(0x00000000);
        navFrame.addView(nav, new FrameLayout.LayoutParams(-1, -1));
        addNavItem(nav, navIndicator, "⌂", "首页", true, () -> showPage(scroll));
        addNavItem(nav, navIndicator, "¥", "实时工资", false, () -> showPage(salaryScroll));
        addNavItem(nav, navIndicator, "⚙", "设置", false, () -> showPage(settingsScroll));
        nav.post(() -> {
            if (nav.getChildCount() == 0) return;
            View first = nav.getChildAt(0);
            FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) navIndicator.getLayoutParams();
            lp.width = first.getWidth();
            lp.height = first.getHeight();
            lp.leftMargin = first.getLeft();
            lp.topMargin = first.getTop();
            navIndicator.setLayoutParams(lp);
        });
        FrameLayout.LayoutParams navParams = new FrameLayout.LayoutParams(dp(310), dp(62),
                Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL);
        navParams.bottomMargin = dp(18);
        root.addView(navFrame, navParams);
        setContentView(root);
        applyAccent(0xFFFFCB6F);
    }

    private void refresh() {
        if (webView != null) {
            syncWebState();
            return;
        }
        LocalDate today = LocalDate.now();
        boolean enabled = AttendanceRules.isEnabled(this);
        summary.setText(enabled ? "已为你开启" : "已暂停");
        if (masterSwitch != null) masterSwitch.setChecked(enabled);
        if (locationStatus != null) locationStatus.setText(OfficeLocation.isSet(this)
                ? "进入范围后自动打开企业微信" : "请在公司内记录当前位置");
        refreshScheduleLabels(today);
        if (morningTimeView != null) morningTimeView.setText(WorkConfig.start(this).toString());
        if (lunchStartView != null) lunchStartView.setText(WorkConfig.lunchStart(this).toString());
        if (lunchEndView != null) lunchEndView.setText(WorkConfig.lunchEnd(this).toString());
        if (workEndView != null) workEndView.setText(WorkConfig.end(this).toString());
        if (eventTimeViews.size() == 4) {
            eventTimeViews.get(0).setText(WorkConfig.start(this).toString());
            eventTimeViews.get(1).setText(WorkConfig.lunchStart(this).toString());
            eventTimeViews.get(2).setText(WorkConfig.lunchEnd(this).toString());
            eventTimeViews.get(3).setText(WorkConfig.end(this).toString());
        }
        updateSalary();
    }

    private void refreshScheduleLabels(LocalDate today) {
        boolean alternating = "alternating".equals(salaryCycle);
        boolean thisDouble = "double".equals(salaryCycle) || (alternating && AttendanceRules.isDoubleRestWeek(this, today));
        boolean nextDouble = alternating && AttendanceRules.isDoubleRestWeek(this, today.plusWeeks(1));
        if (cycleSummaryView != null) cycleSummaryView.setText(alternating ? "大小周自动交替"
                : "double".equals(salaryCycle) ? "固定双休" : "固定单休");
        if (weekTitleView != null) weekTitleView.setText("本周 · " + (thisDouble ? "双休" : "单休"));
        if (weekCopyView != null) weekCopyView.setText(alternating
                ? "下周自动" + (nextDouble ? "双休 · 周末休息" : "单休 · 周六上班")
                : thisDouble ? "每周六、周日休息" : "每周周六上班 · 周日休息");
        if (alternatingScheduleSettings != null) alternatingScheduleSettings.setVisibility(alternating ? View.VISIBLE : View.GONE);
        for (Button button : cycleModeButtons) {
            boolean selected = String.valueOf(button.getTag()).equals(salaryCycle);
            button.setTextColor(selected ? 0xFF805000 : 0xFF81786B);
            button.setBackground(round(selected ? 0xFFFFF4D9 : 0xFFFFFAF2,
                    selected ? 0xFFE7B64F : 0xFFEAE1D3, 12));
        }
        styleScheduleOption(weekDoubleButton, alternating && thisDouble);
        styleScheduleOption(weekSingleButton, alternating && !thisDouble);
    }

    private void styleScheduleOption(Button button, boolean selected) {
        if (button == null) return;
        button.setTextColor(selected ? 0xFF805000 : 0xFF81786B);
        button.setBackground(round(selected ? 0xFFFFF4D9 : 0xFFFFFAF2,
                selected ? 0xFFE7B64F : 0xFFEAE1D3, 12));
    }

    private ScrollView buildSalaryPage() {
        ScrollView page = new ScrollView(this);
        LinearLayout body = new LinearLayout(this);
        body.setOrientation(LinearLayout.VERTICAL);
        body.setPadding(dp(20), dp(18), dp(20), dp(112));
        page.setBackgroundColor(0xFFFFFFFF);
        page.addView(body);
        body.addView(text("Oi，爆点金币", 24, 0xFF3F3529, true));

        FrameLayout heroFrame = new FrameLayout(this);
        heroFrame.setBackground(round(0xFFFFFBF5, 0xFFF0E8DC, 20));
        salaryCoinLayer = new FrameLayout(this);
        salaryCoinLayer.setClipChildren(false);
        LinearLayout hero = new LinearLayout(this);
        hero.setOrientation(LinearLayout.VERTICAL);
        hero.setGravity(Gravity.CENTER);
        hero.setPadding(dp(18), dp(20), dp(18), dp(16));
        hero.addView(text("今天已赚", 11, 0xFF897F72, false));
        salaryEarnedView = text("¥—", 38, 0xFF3F3529, true);
        salaryEarnedView.setGravity(Gravity.CENTER);
        hero.addView(salaryEarnedView, margin(0, 6, 0, 3));
        salaryStateView = text("输入月薪后开始计算", 10, 0xFF897F72, false);
        hero.addView(salaryStateView);
        LinearLayout rateRow = horizontal();
        rateRow.setGravity(Gravity.CENTER_VERTICAL);
        TextView rateLabel = text("每秒收入", 11, 0xFF897F72, false);
        rateRow.addView(rateLabel, new LinearLayout.LayoutParams(0, -2, 1));
        salaryRateView = text("¥— / 秒", 14, 0xFF8A5A00, true);
        rateRow.addView(salaryRateView);
        hero.addView(rateRow, margin(0, 17, 0, 0));
        heroFrame.addView(hero, new FrameLayout.LayoutParams(-1, -2));
        heroFrame.addView(salaryCoinLayer, new FrameLayout.LayoutParams(-1, -1));
        body.addView(heroFrame);

        salaryProgress = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        salaryProgress.setMax(1000);
        salaryProgress.setProgressTintList(ColorStateList.valueOf(0xFFFFCB6F));
        salaryProgress.setProgressBackgroundTintList(ColorStateList.valueOf(0xFFF2EDE4));
        body.addView(salaryProgress, margin(0, 16, 0, 4));
        TextView progressText = text("今日工时进度", 10, 0xFF897F72, false);
        body.addView(progressText);

        body.addView(text("薪资设置", 15, 0xFF3F3529, true), margin(0, 21, 0, 8));
        LinearLayout inputs = new LinearLayout(this);
        inputs.setOrientation(LinearLayout.VERTICAL);
        inputs.setPadding(dp(14), dp(12), dp(14), dp(12));
        inputs.setBackground(round(0xFFFFFFFF, 0xFFF0E8DC, 17));
        inputs.addView(text("税前月薪（元）", 10, 0xFF897F72, false));
        salaryInput = new EditText(this);
        salaryInput.setSingleLine(true);
        salaryInput.setInputType(android.text.InputType.TYPE_CLASS_NUMBER | android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL);
        salaryInput.setHint("输入你的月薪");
        salaryInput.setTextSize(15);
        salaryInput.setTextColor(0xFF3F3529);
        salaryInput.setHintTextColor(0xFFB1A99D);
        double savedSalary = WorkConfig.salary(this);
        if (savedSalary > 0) salaryInput.setText(String.format(Locale.US, "%.2f", savedSalary));
        inputs.addView(salaryInput, margin(0, 4, 0, 9));
        Button saveSalary = textButton("保存薪资", 0xFF4B3919, 12);
        saveSalary.setBackground(round(0xFFFFCB6F, 0, 12));
        accentButtons.add(saveSalary);
        saveSalary.setOnClickListener(v -> saveSalary());
        inputs.addView(saveSalary, new LinearLayout.LayoutParams(-1, dp(42)));
        body.addView(inputs);

        body.addView(text("按平均月工作日估算；休息时段不累计。金额只在本机计算。", 9,
                0xFF9A8F80, false), margin(0, 14, 0, 0));
        salaryInput.addTextChangedListener(new android.text.TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) { updateSalary(); }
            @Override public void afterTextChanged(android.text.Editable s) {}
        });
        return page;
    }

    private void addCycleButton(LinearLayout row, String label, String value) {
        Button button = textButton(label, 0xFF81786B, 10);
        button.setTag(value);
        cycleModeButtons.add(button);
        button.setBackground(round(value.equals(salaryCycle) ? 0xFFFFF4D9 : 0xFFFFFAF2,
                value.equals(salaryCycle) ? 0xFFE7B64F : 0xFFEAE1D3, 12));
        row.addView(button, new LinearLayout.LayoutParams(0, dp(38), 1));
        button.setOnClickListener(v -> {
            salaryCycle = value;
            WorkConfig.setSalary(this, WorkConfig.salary(this), value);
            if (AttendanceRules.isEnabled(this)) AlarmScheduler.scheduleWeek(this);
            refresh();
        });
    }

    private Button scheduleOptionButton(String label) {
        Button button = textButton(label, 0xFF81786B, 10);
        button.setBackground(round(0xFFFFFAF2, 0xFFEAE1D3, 12));
        return button;
    }

    private ScrollView buildSettingsPage() {
        ScrollView page = new ScrollView(this);
        LinearLayout body = new LinearLayout(this);
        body.setOrientation(LinearLayout.VERTICAL);
        body.setPadding(dp(20), dp(18), dp(20), dp(112));
        page.setBackgroundColor(0xFFFFFFFF);
        page.addView(body);
        body.addView(text("设置", 24, 0xFF3F3529, true));
        body.addView(text("调整工作时间和排班", 12, 0xFF897F72, false), margin(0, 4, 0, 17));
        LinearLayout hours = new LinearLayout(this);
        hours.setOrientation(LinearLayout.VERTICAL);
        hours.setPadding(dp(14), dp(14), dp(14), dp(14));
        hours.setBackground(round(0xFFFFFFFF, 0xFFF0E8DC, 17));
        hours.addView(text("工作时间", 15, 0xFF3F3529, true));
        hours.addView(text("自动打卡和工资计时共用这组时间。", 10, 0xFF897F72, false), margin(0, 4, 0, 9));
        morningTimeView = addTimeRow(hours, "上班打卡", WorkConfig.start(this));
        lunchStartView = addTimeRow(hours, "午间下班", WorkConfig.lunchStart(this));
        lunchEndView = addTimeRow(hours, "午间上班", WorkConfig.lunchEnd(this));
        workEndView = addTimeRow(hours, "下班时间", WorkConfig.end(this));
        Button saveTimes = textButton("保存工作时间", 0xFF4B3919, 12);
        saveTimes.setBackground(round(0xFFFFCB6F, 0, 12));
        accentButtons.add(saveTimes);
        saveTimes.setOnClickListener(v -> saveWorkTimes());
        hours.addView(saveTimes, new LinearLayout.LayoutParams(-1, dp(44)));
        body.addView(hours);

        LinearLayout schedule = new LinearLayout(this);
        schedule.setOrientation(LinearLayout.VERTICAL);
        schedule.setPadding(dp(14), dp(14), dp(14), dp(14));
        schedule.setBackground(round(0xFFFFFFFF, 0xFFF0E8DC, 17));
        schedule.addView(text("排班方式", 15, 0xFF3F3529, true));
        schedule.addView(text("大小周模式下只需设定本周，之后自动单双周交替。", 10, 0xFF897F72, false), margin(0, 4, 0, 9));
        schedule.addView(text("工作周期", 10, 0xFF897F72, false));
        LinearLayout cycleOptions = horizontal();
        cycleOptions.setGravity(Gravity.CENTER);
        addCycleButton(cycleOptions, "大小周", "alternating");
        addCycleButton(cycleOptions, "固定双休", "double");
        addCycleButton(cycleOptions, "固定单休", "single");
        schedule.addView(cycleOptions, margin(0, 6, 0, 8));
        alternatingScheduleSettings = new LinearLayout(this);
        alternatingScheduleSettings.setOrientation(LinearLayout.VERTICAL);
        alternatingScheduleSettings.addView(text("本周安排", 10, 0xFF897F72, false));
        LinearLayout weekOptions = horizontal();
        weekOptions.setGravity(Gravity.CENTER);
        weekDoubleButton = scheduleOptionButton("本周双休");
        weekSingleButton = scheduleOptionButton("本周单休");
        weekOptions.addView(weekDoubleButton, new LinearLayout.LayoutParams(0, dp(38), 1));
        LinearLayout.LayoutParams singleWeekLp = new LinearLayout.LayoutParams(0, dp(38), 1);
        singleWeekLp.setMargins(dp(6), 0, 0, 0);
        weekOptions.addView(weekSingleButton, singleWeekLp);
        weekDoubleButton.setOnClickListener(v -> setThisWeekDoubleRest(true));
        weekSingleButton.setOnClickListener(v -> setThisWeekDoubleRest(false));
        alternatingScheduleSettings.addView(weekOptions, margin(0, 6, 0, 0));
        schedule.addView(alternatingScheduleSettings);
        body.addView(schedule, margin(0, 12, 0, 0));

        LinearLayout permissions = new LinearLayout(this);
        permissions.setOrientation(LinearLayout.VERTICAL);
        permissions.setPadding(dp(14), dp(14), dp(14), dp(8));
        permissions.setBackground(round(0xFFFFFFFF, 0xFFF0E8DC, 17));
        permissions.addView(text("打卡提醒与权限", 15, 0xFF3F3529, true));
        permissions.addView(text("定位、锁屏提醒和后台运行权限。", 10, 0xFF897F72, false), margin(0, 4, 0, 6));
        addSettingRow(permissions, "自动打开服务", "清理企微最近任务", v -> openAccessibilitySettings());
        addSettingRow(permissions, "精确闹钟", "按设定时间触发", v -> openExactAlarmSettings());
        addSettingRow(permissions, "锁屏提醒", "锁屏时震动并响铃", v -> openFullScreenSettings());
        addSettingRow(permissions, "后台运行", "减少系统休眠限制", v -> openBatterySettings());
        body.addView(permissions);
        return page;
    }

    private TextView addTimeRow(LinearLayout parent, String label, LocalTime time) {
        LinearLayout row = horizontal();
        row.setPadding(0, dp(7), 0, dp(7));
        row.addView(text(label, 12, 0xFF3F3529, true), new LinearLayout.LayoutParams(0, -2, 1));
        TextView value = text(time.toString(), 13, 0xFF8A5A00, true);
        value.setGravity(Gravity.CENTER);
        value.setPadding(dp(13), dp(8), dp(13), dp(8));
        value.setBackground(round(0xFFFFF4D9, 0, 11));
        value.setOnClickListener(v -> new TimePickerDialog(this, (view, hour, minute) ->
                value.setText(String.format(Locale.US, "%02d:%02d", hour, minute)),
                timeFromText(value.getText().toString()).getHour(),
                timeFromText(value.getText().toString()).getMinute(), true).show());
        row.addView(value);
        parent.addView(row);
        return value;
    }

    private LocalTime timeFromText(String value) {
        try { return LocalTime.parse(value); } catch (RuntimeException e) { return LocalTime.of(9, 0); }
    }

    private void saveWorkTimes() {
        LocalTime start = timeFromText(morningTimeView.getText().toString());
        LocalTime lunchStart = timeFromText(lunchStartView.getText().toString());
        LocalTime lunchEnd = timeFromText(lunchEndView.getText().toString());
        LocalTime end = timeFromText(workEndView.getText().toString());
        if (!start.isBefore(lunchStart) || !lunchStart.isBefore(lunchEnd) || !lunchEnd.isBefore(end)) {
            Toast.makeText(this, "请按上班、午休、下班顺序设置时间", Toast.LENGTH_LONG).show();
            return;
        }
        WorkConfig.setTimes(this, start, lunchStart, lunchEnd, end);
        if (AttendanceRules.isEnabled(this)) AlarmScheduler.scheduleWeek(this);
        refresh();
        Toast.makeText(this, "工作时间已保存", Toast.LENGTH_SHORT).show();
    }

    private void saveSalary() {
        double value = 0;
        try { value = Double.parseDouble(salaryInput.getText().toString()); } catch (RuntimeException ignored) { }
        WorkConfig.setSalary(this, Math.max(0, value), salaryCycle);
        updateSalary();
        Toast.makeText(this, "薪资与排班已保存", Toast.LENGTH_SHORT).show();
    }

    private void updateSalary() {
        if (salaryEarnedView == null || salaryInput == null) return;
        double salary = 0;
        try { salary = Double.parseDouble(salaryInput.getText().toString()); } catch (RuntimeException ignored) { }
        double weeklyDays = "double".equals(salaryCycle) ? 5 : "single".equals(salaryCycle) ? 6 : 5.5;
        double averageMonthlyDays = weeklyDays * 365.2425 / 7.0 / 12.0;
        LocalTime start = WorkConfig.start(this), lunchStart = WorkConfig.lunchStart(this);
        LocalTime lunchEnd = WorkConfig.lunchEnd(this), end = WorkConfig.end(this);
        double workSecondsPerDay = (lunchStart.toSecondOfDay() - start.toSecondOfDay())
                + (end.toSecondOfDay() - lunchEnd.toSecondOfDay());
        double perSecond = salary > 0 && averageMonthlyDays > 0 && workSecondsPerDay > 0
                ? salary / averageMonthlyDays / workSecondsPerDay : 0;
        LocalTime now = LocalTime.now();
        double elapsed = Math.max(0, Math.min(now.toSecondOfDay(), lunchStart.toSecondOfDay()) - start.toSecondOfDay())
                + Math.max(0, Math.min(now.toSecondOfDay(), end.toSecondOfDay()) - lunchEnd.toSecondOfDay());
        if (!AttendanceRules.isWorkday(this, LocalDate.now())) elapsed = 0;
        double earned = perSecond * elapsed;
        String earnedText = salary > 0 ? String.format(Locale.US, "¥%.2f", earned) : "¥—";
        if (!earnedText.equals(lastDisplayedEarnings)) {
            salaryEarnedView.setText(earnedText);
            if (lastDisplayedEarnings != null && salary > 0 && salaryScroll != null
                    && salaryScroll.getVisibility() == View.VISIBLE) animateEarningsTick();
            lastDisplayedEarnings = earnedText;
        }
        salaryRateView.setText(salary > 0 ? String.format(Locale.US, "¥%.4f / 秒", perSecond) : "¥— / 秒");
        if (salaryProgress != null && workSecondsPerDay > 0) salaryProgress.setProgress((int) Math.min(1000, elapsed / workSecondsPerDay * 1000));
        if (salaryStateView != null) {
            String state = salary == 0 ? "输入月薪后开始计算"
                    : !AttendanceRules.isWorkday(this, LocalDate.now()) ? "今天是休息日 · 收入暂停"
                    : now.isBefore(start) ? "还未到上班时间 · 收入暂停"
                    : !now.isBefore(end) ? "已下班 · 今日累计完成"
                    : !now.isBefore(lunchStart) && now.isBefore(lunchEnd) ? "午休中 · 收入暂停"
                    : "上班中 · 收入实时累计";
            salaryStateView.setText(state);
        }
    }

    private int themeForeground(int color) { return ((color >> 16 & 0xFF) * 299 + (color >> 8 & 0xFF) * 587 + (color & 0xFF) * 114) / 1000 < 145 ? 0xFFFFFFFF : 0xFF4B3919; }

    private void applyAccent(int color) {
        int foreground = themeForeground(color);
        if (logoView != null) logoView.setBackground(round(color, 0, 15));
        if (primaryTestButton != null) primaryTestButton.setBackground(round(color, 0, 13));
        if (primaryTestButton != null) primaryTestButton.setTextColor(foreground);
        if (masterSwitch != null) masterSwitch.setTrackTintList(ColorStateList.valueOf(color));
        for (Button button : accentButtons) { button.setBackground(round(color, 0, 12)); button.setTextColor(foreground); }
        for (TextView icon : salaryIcons) icon.setTextColor(color);
        if (salaryProgress != null) salaryProgress.setProgressTintList(ColorStateList.valueOf(color));
        if (salaryRateView != null) salaryRateView.setTextColor(color);
    }

    private int withAlpha(int color, int alpha) { return (color & 0x00FFFFFF) | ((alpha & 0xFF) << 24); }

    private void showPage(ScrollView selected) {
        scroll.setVisibility(selected == scroll ? View.VISIBLE : View.GONE);
        salaryScroll.setVisibility(selected == salaryScroll ? View.VISIBLE : View.GONE);
        settingsScroll.setVisibility(selected == settingsScroll ? View.VISIBLE : View.GONE);
        if (selected == scroll) scroll.smoothScrollTo(0, 0);
        if (selected == salaryScroll) updateSalary();
    }

    private void animateEarningsTick() {
        if (salaryCoinLayer == null) return;
        salaryEarnedView.animate().cancel();
        salaryEarnedView.setScaleX(1f);
        salaryEarnedView.setScaleY(1f);
        salaryEarnedView.animate().scaleX(1.08f).scaleY(1.08f).setDuration(110L)
                .withEndAction(() -> salaryEarnedView.animate().scaleX(1f).scaleY(1f)
                        .setDuration(170L).start()).start();
        salaryCoinLayer.post(() -> {
            int width = salaryCoinLayer.getWidth();
            for (int i = 0; i < 2; i++) {
                int size = dp(i == 0 ? 42 : 36);
                TextView coin = text("¥", i == 0 ? 17 : 14, 0xFF8F5200, true);
                coin.setGravity(Gravity.CENTER);
                coin.setBackground(round(0xFFFFD05A, 0xFFD98C08, 28));
                coin.setElevation(dp(7));
                coin.setAlpha(0f);
                coin.setScaleX(0.72f);
                coin.setScaleY(0.72f);
                FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(size, size);
                lp.leftMargin = i == 0 ? dp(13) : Math.max(dp(13), width - size - dp(13));
                lp.topMargin = Math.max(0, salaryCoinLayer.getHeight() / 2 - size / 2);
                salaryCoinLayer.addView(coin, lp);
                coin.animate().alpha(1f).scaleX(1f).scaleY(1f)
                        .translationY(-dp(46)).rotationY(i == 0 ? 260f : -260f)
                        .setStartDelay(i * 55L).setDuration(430L)
                        .withEndAction(() -> coin.animate().alpha(0f).scaleX(0.85f).scaleY(0.85f)
                                .setDuration(190L).withEndAction(() -> salaryCoinLayer.removeView(coin)).start()).start();
            }
        });
    }

    private void setOfficeLocation() {
        if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.ACCESS_FINE_LOCATION,
                    Manifest.permission.ACCESS_COARSE_LOCATION}, REQUEST_LOCATION);
            return;
        }
        LocationManager manager = getSystemService(LocationManager.class);
        LocationListener listener = location -> {
            OfficeLocation.save(this, location);
            refresh();
            syncWebState();
            Toast.makeText(this, "已保存公司位置", Toast.LENGTH_SHORT).show();
        };
        try {
            String provider = manager.isProviderEnabled(LocationManager.GPS_PROVIDER)
                    ? LocationManager.GPS_PROVIDER
                    : manager.isProviderEnabled(LocationManager.NETWORK_PROVIDER)
                    ? LocationManager.NETWORK_PROVIDER : null;
            if (provider == null) {
                Toast.makeText(this, "请先打开手机定位", Toast.LENGTH_LONG).show();
                return;
            }
            manager.requestSingleUpdate(provider, listener, Looper.getMainLooper());
            Toast.makeText(this, "正在获取当前位置…", Toast.LENGTH_SHORT).show();
        } catch (SecurityException e) {
            Toast.makeText(this, "没有定位权限", Toast.LENGTH_LONG).show();
        }
    }

    @Override public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] results) {
        super.onRequestPermissionsResult(requestCode, permissions, results);
        if (requestCode == REQUEST_LOCATION && results.length > 0
                && results[0] == PackageManager.PERMISSION_GRANTED) setOfficeLocation();
        else if (requestCode == REQUEST_LOCATION) Toast.makeText(this, "需要精确定位来设置公司范围", Toast.LENGTH_LONG).show();
    }

    private void enableSchedule() {
        if (!OfficeLocation.isSet(this)) {
            Toast.makeText(this, "请先在公司设置位置", Toast.LENGTH_LONG).show();
            refresh();
            return;
        }
        if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            Toast.makeText(this, "请先允许精确定位", Toast.LENGTH_LONG).show();
            refresh();
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
            refresh();
            return;
        }
        if (Build.VERSION.SDK_INT >= 31 && !AlarmScheduler.canScheduleExact(this)) {
            openExactAlarmSettings();
            Toast.makeText(this, "允许精确闹钟后，再点一次开启", Toast.LENGTH_LONG).show();
            refresh();
            return;
        }
        if (!ClockInAccessibilityService.isEnabled(this)) {
            openAccessibilitySettings();
            Toast.makeText(this, "开启“工力全开自动打卡服务”后，再点一次", Toast.LENGTH_LONG).show();
            refresh();
            return;
        }
        AttendanceRules.setEnabled(this, true);
        AlarmScheduler.scheduleWeek(this);
        refresh();
        Toast.makeText(this, "已安排未来一周的打卡", Toast.LENGTH_LONG).show();
    }

    private void setThisWeekDoubleRest(boolean doubleRest) {
        AttendanceRules.setThisWeekDoubleRest(this, doubleRest);
        if (AttendanceRules.isEnabled(this)) AlarmScheduler.scheduleWeek(this);
        refresh();
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
        if (Build.VERSION.SDK_INT >= 34) {
            try {
                startActivity(new Intent(Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT,
                        android.net.Uri.parse("package:" + getPackageName())));
            } catch (RuntimeException ignored) { showPermissionNotes(); }
        } else showPermissionNotes();
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
                .setMessage("需要精确定位和始终允许定位、通知与锁屏提醒、精确闹钟、无障碍服务，以及手机厂商的后台弹出和自启动权限。关闭企业微信最近任务需要无障碍服务。系统锁屏密码必须由你本人解锁。")
                .setPositiveButton("知道了", null).show();
    }

    private void requestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED) requestPermissions(
                new String[]{Manifest.permission.POST_NOTIFICATIONS}, 31);
    }

    private LinearLayout horizontal() {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        return row;
    }

    private GradientDrawable round(int fill, int stroke, int radiusDp) {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setColor(fill);
        drawable.setCornerRadius(dp(radiusDp));
        if (stroke != 0) drawable.setStroke(dp(1), stroke);
        return drawable;
    }

    private Button textButton(String label, int color, int size) {
        Button button = new Button(this);
        button.setText(label);
        button.setTextColor(color);
        button.setTextSize(size);
        button.setAllCaps(false);
        button.setMinHeight(dp(36));
        button.setMinimumHeight(dp(36));
        button.setPadding(dp(10), 0, dp(10), 0);
        button.setStateListAnimator(null);
        return button;
    }

    private Button iconButton(String label, View.OnClickListener listener) {
        Button button = textButton(label, 0xFF3F3529, 19);
        button.setBackground(round(0xFFFFF8EC, 0, 22));
        button.setMinWidth(dp(40));
        button.setMinimumWidth(dp(40));
        button.setPadding(0, 0, 0, 0);
        button.setOnClickListener(listener);
        return button;
    }

    private void addEvent(LinearLayout parent, String icon, int color, String time,
                          String title, String detail, String state, boolean current) {
        LinearLayout row = horizontal();
        row.setPadding(dp(8), dp(7), dp(8), dp(7));
        row.setBackground(round(0xFFFFFFFF, 0xFFF0E8DC, 15));
        TextView art = text(icon, 24, 0xFFE0A12B, true);
        art.setGravity(Gravity.CENTER);
        art.setBackgroundColor(android.graphics.Color.TRANSPARENT);
        salaryIcons.add(art);
        row.addView(art, new LinearLayout.LayoutParams(dp(47), dp(58)));
        TextView timeView = text(time, 12, 0xFF776D61, true);
        timeView.setGravity(Gravity.CENTER);
        eventTimeViews.add(timeView);
        row.addView(timeView, new LinearLayout.LayoutParams(dp(51), -2));
        LinearLayout description = new LinearLayout(this);
        description.setOrientation(LinearLayout.VERTICAL);
        TextView heading = text(title, 12, 0xFF3F3529, true);
        heading.setMaxLines(1);
        heading.setEllipsize(android.text.TextUtils.TruncateAt.END);
        description.addView(heading);
        description.addView(text(detail, 9, 0xFF938879, false), margin(0, 4, 0, 0));
        row.addView(description, new LinearLayout.LayoutParams(0, -2, 1));
        TextView badge = text((current ? "● " : "✓ ") + state, 9,
                current ? 0xFF805000 : 0xFF81786B, current);
        badge.setPadding(dp(7), dp(5), dp(7), dp(5));
        badge.setBackground(round(current ? 0xFFFFE8B3 : 0xFFFFFAF2,
                current ? 0xFFE7B64F : 0xFFEAE1D3, 16));
        row.addView(badge, margin(5, 0, 0, 0));
        parent.addView(row, margin(0, 0, 0, 6));
    }

    private void addSettingRow(LinearLayout parent, String title, String detail,
                               View.OnClickListener listener) {
        LinearLayout row = horizontal();
        row.setPadding(dp(5), dp(11), dp(5), dp(11));
        LinearLayout words = new LinearLayout(this);
        words.setOrientation(LinearLayout.VERTICAL);
        words.addView(text(title, 12, 0xFF3F3529, true));
        words.addView(text(detail, 9, 0xFF897F72, false), margin(0, 3, 0, 0));
        row.addView(words, new LinearLayout.LayoutParams(0, -2, 1));
        row.addView(text("›", 19, 0xFF897F72, false));
        row.setOnClickListener(listener);
        parent.addView(row, new LinearLayout.LayoutParams(-1, -2));
        View divider = new View(this);
        divider.setBackgroundColor(0xFFF0E8DC);
        parent.addView(divider, new LinearLayout.LayoutParams(-1, dp(1)));
    }

    private void addNavItem(LinearLayout nav, View indicator, String icon, String label, boolean active, Runnable action) {
        LinearLayout item = new LinearLayout(this);
        item.setOrientation(LinearLayout.VERTICAL);
        item.setGravity(Gravity.CENTER);
        item.setPadding(0, dp(4), 0, dp(3));
        item.setBackgroundColor(0x00000000);
        TextView glyph = text(icon, 17, active ? 0xFF8A5A00 : 0xFF928779, true);
        glyph.setGravity(Gravity.CENTER);
        item.addView(glyph);
        TextView caption = text(label, 9, active ? 0xFF3F3529 : 0xFF928779, active);
        caption.setGravity(Gravity.CENTER);
        item.addView(caption);
        nav.addView(item, new LinearLayout.LayoutParams(0, -1, 1));
        item.setOnClickListener(v -> {
            for (int i = 0; i < nav.getChildCount(); i++) {
                View sibling = nav.getChildAt(i);
                boolean selected = sibling == item;
                LinearLayout navItem = (LinearLayout) sibling;
                TextView navGlyph = (TextView) navItem.getChildAt(0);
                TextView navCaption = (TextView) navItem.getChildAt(1);
                navGlyph.setTextColor(selected ? 0xFF8A5A00 : 0xFF928779);
                navCaption.setTextColor(selected ? 0xFF3F3529 : 0xFF928779);
                navCaption.setTypeface(null, selected
                        ? android.graphics.Typeface.BOLD : android.graphics.Typeface.NORMAL);
                if (selected) {
                    indicator.animate().translationX(sibling.getLeft() - nav.getChildAt(0).getLeft())
                            .setDuration(320L)
                            .setInterpolator(new android.view.animation.DecelerateInterpolator(1.8f)).start();
                    navGlyph.animate().scaleX(1.2f).scaleY(1.2f).setDuration(110L)
                            .withEndAction(() -> navGlyph.animate().scaleX(1f).scaleY(1f).setDuration(160L).start())
                            .start();
                }
            }
            action.run();
        });
    }

    private TextView text(String value, int size, int color, boolean bold) {
        TextView view = new TextView(this);
        view.setText(value);
        view.setTextSize(size);
        view.setTextColor(color);
        if (bold) view.setTypeface(null, android.graphics.Typeface.BOLD);
        return view;
    }

    private LinearLayout.LayoutParams margin(int left, int top, int right, int bottom) {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        params.setMargins(dp(left), dp(top), dp(right), dp(bottom));
        return params;
    }

    private int dp(int value) { return Math.round(value * getResources().getDisplayMetrics().density); }
}
