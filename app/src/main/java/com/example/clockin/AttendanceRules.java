package com.example.clockin;

import android.content.Context;
import android.content.SharedPreferences;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;

public final class AttendanceRules {
    private static final String PREFS = "attendance_rules";
    private static final String KEY_ANCHOR_MONDAY = "anchor_monday";
    private static final String KEY_ANCHOR_DOUBLE_REST = "anchor_double_rest";
    private static final String KEY_ENABLED = "enabled";

    private AttendanceRules() {}

    public static SharedPreferences prefs(Context context) {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    public static void ensureDefaults(Context context) {
        SharedPreferences p = prefs(context);
        if (!p.contains(KEY_ANCHOR_MONDAY)) {
            LocalDate monday = LocalDate.now().with(DayOfWeek.MONDAY);
            p.edit().putLong(KEY_ANCHOR_MONDAY, monday.toEpochDay())
                    .putBoolean(KEY_ANCHOR_DOUBLE_REST, true).apply();
        }
    }

    public static boolean isEnabled(Context context) {
        return prefs(context).getBoolean(KEY_ENABLED, false);
    }

    public static void setEnabled(Context context, boolean enabled) {
        prefs(context).edit().putBoolean(KEY_ENABLED, enabled).apply();
    }

    public static boolean isWorkday(Context context, LocalDate date) {
        DayOfWeek day = date.getDayOfWeek();
        if (day != DayOfWeek.SATURDAY && day != DayOfWeek.SUNDAY) return true;
        if (day == DayOfWeek.SUNDAY) return false;
        String cycle = WorkConfig.cycle(context);
        if ("double".equals(cycle)) return false;
        if ("single".equals(cycle)) return true;
        return !isDoubleRestWeek(context, date);
    }

    public static boolean isDoubleRestWeek(Context context, LocalDate date) {
        ensureDefaults(context);
        SharedPreferences p = prefs(context);
        LocalDate anchor = LocalDate.ofEpochDay(p.getLong(KEY_ANCHOR_MONDAY, 0));
        long weekIndex = Math.floorDiv(ChronoUnit.DAYS.between(anchor, date), 7L);
        boolean anchorDoubleRest = p.getBoolean(KEY_ANCHOR_DOUBLE_REST, true);
        return (Math.floorMod(weekIndex, 2L) == 0L) == anchorDoubleRest;
    }

    public static void setThisWeekDoubleRest(Context context, boolean doubleRest) {
        LocalDate monday = LocalDate.now().with(DayOfWeek.MONDAY);
        prefs(context).edit().putLong(KEY_ANCHOR_MONDAY, monday.toEpochDay())
                .putBoolean(KEY_ANCHOR_DOUBLE_REST, doubleRest).apply();
    }

    public static String weekLabel(Context context, LocalDate date) {
        String cycle = WorkConfig.cycle(context);
        if ("double".equals(cycle)) return "双休周";
        if ("single".equals(cycle)) return "单休（周六上班）";
        return isDoubleRestWeek(context, date) ? "双休周" : "单休（周六上班）";
    }
}
