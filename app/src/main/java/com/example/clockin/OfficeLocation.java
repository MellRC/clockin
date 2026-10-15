package com.example.clockin;

import android.content.Context;
import android.content.SharedPreferences;
import android.location.Location;

public final class OfficeLocation {
    private static final String PREFS = "office_location";
    private static final String LAT = "latitude";
    private static final String LNG = "longitude";
    private static final String SET = "is_set";
    public static final float RADIUS_METERS = 300f;

    private OfficeLocation() {}

    public static void save(Context context, Location location) {
        prefs(context).edit().putFloat(LAT, (float) location.getLatitude())
                .putFloat(LNG, (float) location.getLongitude()).putBoolean(SET, true).apply();
    }

    public static boolean isSet(Context context) {
        return prefs(context).getBoolean(SET, false);
    }

    public static boolean contains(Context context, Location current) {
        if (!isSet(context) || current == null) return false;
        SharedPreferences p = prefs(context);
        float[] result = new float[1];
        Location.distanceBetween(current.getLatitude(), current.getLongitude(),
                p.getFloat(LAT, 0f), p.getFloat(LNG, 0f), result);
        return result[0] <= RADIUS_METERS;
    }

    private static SharedPreferences prefs(Context context) {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }
}
