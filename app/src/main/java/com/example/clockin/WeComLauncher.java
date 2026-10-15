package com.example.clockin;

import android.content.Context;
import android.content.Intent;

public final class WeComLauncher {
    public static final String PACKAGE = "com.tencent.wework";

    private WeComLauncher() {}

    public static boolean launch(Context context) {
        Intent intent = context.getPackageManager().getLaunchIntentForPackage(PACKAGE);
        if (intent == null) return false;
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);
        try {
            context.startActivity(intent);
            return true;
        } catch (RuntimeException ignored) {
            return false;
        }
    }
}
