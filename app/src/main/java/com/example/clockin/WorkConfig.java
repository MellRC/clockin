package com.example.clockin;

import android.content.Context;
import android.content.SharedPreferences;

import java.time.LocalTime;

/** Shared work-hour and salary settings used by the UI and alarm scheduler. */
public final class WorkConfig {
    private static final String PREFS = "work_config";
    private WorkConfig() {}

    private static SharedPreferences prefs(Context context) {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    public static LocalTime start(Context c) { return LocalTime.parse(prefs(c).getString("start", "07:30")); }
    public static LocalTime lunchStart(Context c) { return LocalTime.parse(prefs(c).getString("lunch_start", "12:30")); }
    public static LocalTime lunchEnd(Context c) { return LocalTime.parse(prefs(c).getString("lunch_end", "13:15")); }
    public static LocalTime end(Context c) { return LocalTime.parse(prefs(c).getString("end", "18:00")); }
    public static double salary(Context c) { return Double.longBitsToDouble(prefs(c).getLong("salary_bits", Double.doubleToLongBits(0d))); }
    public static String cycle(Context c) { return prefs(c).getString("cycle", "alternating"); }
    public static String theme(Context c) { return prefs(c).getString("theme", "honey"); }

    public static boolean setTimes(Context c, LocalTime start, LocalTime lunchStart,
                                LocalTime lunchEnd, LocalTime end) {
        return prefs(c).edit().putString("start", start.toString())
                .putString("lunch_start", lunchStart.toString())
                .putString("lunch_end", lunchEnd.toString())
                .putString("end", end.toString()).commit();
    }

    public static boolean setSalary(Context c, double salary, String cycle) {
        return prefs(c).edit().putLong("salary_bits", Double.doubleToLongBits(salary))
                .putString("cycle", cycle).commit();
    }

    public static boolean setTheme(Context c, String theme) {
        return prefs(c).edit().putString("theme", theme).commit();
    }
}
