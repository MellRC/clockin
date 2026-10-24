package com.example.clockin;

/** Correlates foreground observations only. It cannot prove which actor initiated the launch. */
final class LaunchEvidence {
    static boolean matches(long requested, long now, long event, boolean knownDifferentApp,
                           int beforeWindow, int afterWindow, String expectedPackage, String observedPackage) {
        return knownDifferentApp && beforeWindow != afterWindow && event > requested
                && now >= event && now - requested <= TaskPolicy.LAUNCH_CONFIRM_MS
                && expectedPackage != null && expectedPackage.equals(observedPackage);
    }
}
