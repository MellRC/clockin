package com.example.clockin;

import java.time.*;

/** Pure policy shared by alarms, location checks and the task gate. */
final class TaskPolicy {
    static final long RETRY_COOLDOWN_MS = 60_000;
    static final int MAX_AUTOMATIC_ATTEMPTS = 3;
    static final long LOCATION_MAX_AGE_MS = 180_000;
    static final long LAUNCH_CONFIRM_MS = 5_000;
    static final long VIBRATION_INTERVAL_MS = 2_000;
    static final long VIBRATION_PULSE_MS = 350;
    static final long VIBRATION_SEGMENT_MS = 8 * 60_000L;
    static final float SWIPE_EXIT_FRACTION = 0.35f;
    static final float SWIPE_AXIS_RATIO = 1.5f;
    static final float SWIPE_FAST_MIN_DP = 48f;
    static final float SWIPE_FAST_VELOCITY_DP_S = 1000f;
    static final long SWIPE_SPRING_MS = 200;
    static final long SWIPE_EXIT_MS = 210;
    static final long LOCK_HINT_INITIAL_DELAY_MS = 500;
    static final long LOCK_HINT_SWEEP_MS = 1_200;
    static final long LOCK_HINT_PAUSE_MS = 1_800;
    static final long LOCK_HINT_CYCLE_MS = 3_000;
    static final float LOCK_HINT_BAND_WIDTH_DP = 20f;
    static final float LOCK_HINT_BEND_DP = 6f;
    static final float LOCK_TITLE_SIZE_SP = 32f;
    static final float LOCK_HINT_SIZE_SP = 18f;
    static final float LOCK_TITLE_CENTER_FRACTION = .46f;
    static final float LOCK_HINT_BOTTOM_DP = 76f;
    static final float LOCK_TITLE_HORIZONTAL_DP = 28f;
    static final float LOCK_HINT_ICON_WIDTH_DP = 7f;
    static final float LOCK_HINT_ICON_HEIGHT_DP = 14f;
    static final float LOCK_HINT_ICON_GAP_DP = 4f;
    static final float LOCK_HINT_TEXT_GAP_DP = 8f;
    static final float LOCK_HINT_STROKE_DP = 1.8f;

    static boolean shouldCollapseSwipe(float dx, float dy, float vxPxPerSecond,
                                       int widthPx, float density, int touchSlop,
                                       boolean multiTouch, boolean cancelled) {
        if (cancelled || multiTouch || density <= 0 || widthPx <= 0 || dx <= touchSlop
                || dx <= Math.abs(dy) * SWIPE_AXIS_RATIO) return false;
        float distanceDp = dx / density;
        float velocityDp = vxPxPerSecond / density;
        return dx >= widthPx * SWIPE_EXIT_FRACTION
                || (distanceDp >= SWIPE_FAST_MIN_DP && velocityDp >= SWIPE_FAST_VELOCITY_DP_S);
    }

    static long[] window(LocalDate day, int type, LocalTime start, LocalTime lunch,
                         LocalTime back, LocalTime end, ZoneId zone) {
        LocalDateTime a = day.atTime(start);
        LocalDateTime b = after(a, lunch), c = after(b, back), d = after(c, end);
        LocalDateTime from = type == 1 ? a : type == 2 ? b : type == 3 ? c : d;
        LocalDateTime to = type == 1 ? a.plusMinutes(60) : type == 2 ? c
                : type == 3 ? c.plusMinutes(60) : d.plusMinutes(60);
        return new long[]{from.atZone(zone).toInstant().toEpochMilli(),
                to.atZone(zone).toInstant().toEpochMilli()};
    }
    private static LocalDateTime after(LocalDateTime previous, LocalTime time) {
        LocalDateTime value = previous.toLocalDate().atTime(time);
        return value.isAfter(previous) ? value : value.plusDays(1);
    }
    static long[] waitingPattern(long remaining) {
        remaining = Math.max(1, Math.min(remaining, VIBRATION_SEGMENT_MS));
        int cycles = (int)((remaining + VIBRATION_INTERVAL_MS - 1) / VIBRATION_INTERVAL_MS);
        long[] pattern = new long[1 + cycles * 2];
        for (int i = 0; i < cycles; i++) {
            long on = Math.min(VIBRATION_PULSE_MS, remaining);
            pattern[1 + i * 2] = on;
            remaining -= on;
            long off = Math.min(VIBRATION_INTERVAL_MS - VIBRATION_PULSE_MS, remaining);
            pattern[2 + i * 2] = off;
            remaining -= off;
        }
        return pattern;
    }
    static boolean unlocked(boolean interactive, boolean keyguardLocked, boolean deviceLocked) {
        return interactive && !keyguardLocked && !deviceLocked;
    }
    static boolean within(long now, long start, long end) { return now >= start && now < end; }
    static boolean canRetry(long now, long attempted, int count, long unlock, long attemptedUnlock) {
        return count < MAX_AUTOMATIC_ATTEMPTS && now - attempted >= RETRY_COOLDOWN_MS
                && unlock > attemptedUnlock;
    }
    static boolean freshLocation(long elapsedNow, long locationElapsed) {
        long age = elapsedNow - locationElapsed;
        return locationElapsed > 0 && age >= 0 && age <= LOCATION_MAX_AGE_MS;
    }
}
