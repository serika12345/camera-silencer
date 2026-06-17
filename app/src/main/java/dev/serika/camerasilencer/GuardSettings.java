package dev.serika.camerasilencer;

import android.content.Context;
import android.content.SharedPreferences;

final class GuardSettings {
    static final String MODE_FOLLOW_RINGER = "follow_ringer";
    static final String MODE_ALWAYS = "always";
    static final String MODE_MANUAL = "manual";

    private static final String PREFS = "guard_settings";
    private static final String KEY_MODE = "mode";
    private static final String KEY_MANUAL_RUNNING = "manual_running";

    private GuardSettings() {
    }

    static String getMode(Context context) {
        return normalize(preferences(context).getString(KEY_MODE, MODE_MANUAL));
    }

    static void setMode(Context context, String mode) {
        preferences(context).edit()
                .putString(KEY_MODE, normalize(mode))
                .apply();
    }

    static boolean isManualRunning(Context context) {
        return preferences(context).getBoolean(KEY_MANUAL_RUNNING, false);
    }

    static void setManualRunning(Context context, boolean running) {
        preferences(context).edit()
                .putBoolean(KEY_MANUAL_RUNNING, running)
                .apply();
    }

    static boolean shouldRunService(Context context) {
        String mode = getMode(context);
        return !MODE_MANUAL.equals(mode) || isManualRunning(context);
    }

    static boolean shouldPromptOnBoot(Context context) {
        return !MODE_MANUAL.equals(getMode(context));
    }

    static String labelFor(String mode) {
        switch (normalize(mode)) {
            case MODE_FOLLOW_RINGER:
                return "Follow silent/vibrate mode";
            case MODE_ALWAYS:
                return "Always silence camera";
            case MODE_MANUAL:
            default:
                return "Manual control";
        }
    }

    private static SharedPreferences preferences(Context context) {
        return context.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    private static String normalize(String mode) {
        if (MODE_FOLLOW_RINGER.equals(mode) || MODE_ALWAYS.equals(mode)) {
            return mode;
        }
        return MODE_MANUAL;
    }
}
