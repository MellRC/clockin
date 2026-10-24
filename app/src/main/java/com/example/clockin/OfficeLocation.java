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
    public static final float ACCURACY_LIMIT_METERS = 1000f;

    private OfficeLocation() {}

    public static boolean save(Context context, Location location) {
        return prefs(context).edit().putFloat(LAT, (float) location.getLatitude())
                .putFloat(LNG, (float) location.getLongitude()).putBoolean(SET, true).commit();
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

    private static Location recent;
    static void observed(Location location) {
        if (location != null && TaskPolicy.freshLocation(android.os.SystemClock.elapsedRealtime(),
                location.getElapsedRealtimeNanos() / 1_000_000L)) recent = new Location(location);
    }
    static boolean currentInside(Context c) {
        if (c.checkSelfPermission(android.Manifest.permission.ACCESS_FINE_LOCATION)
                != android.content.pm.PackageManager.PERMISSION_GRANTED) return false;
        android.location.LocationManager manager = c.getSystemService(android.location.LocationManager.class);
        if (manager == null) return false;
        try {
            boolean enabled = manager.isProviderEnabled(android.location.LocationManager.GPS_PROVIDER)
                    || manager.isProviderEnabled(android.location.LocationManager.NETWORK_PROVIDER);
            if (!enabled) return false;
            Location candidate = recent;
            for (String provider : manager.getProviders(true)) {
                Location last = manager.getLastKnownLocation(provider);
                if (last != null && (candidate == null || last.getElapsedRealtimeNanos() > candidate.getElapsedRealtimeNanos()))
                    candidate = last;
            }
            if (candidate == null || !contains(c, candidate)) return false;
            if (candidate.hasAccuracy() && candidate.getAccuracy() > ACCURACY_LIMIT_METERS) return false;
            return TaskPolicy.freshLocation(android.os.SystemClock.elapsedRealtime(),
                    candidate.getElapsedRealtimeNanos() / 1_000_000L);
        } catch (SecurityException e) { return false; }
    }

    private static SharedPreferences prefs(Context context) {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }
}
