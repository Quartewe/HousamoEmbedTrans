package com.quarty.housamoembedtrans.runtime;

import android.content.Context;
import android.content.SharedPreferences;

/** Stores controls that must take effect while the hooked game is running. */
public final class RuntimeControlStore {

    private static final String PREFS_NAME = "runtime_controls";
    private static final String KEY_CAPTURE_PAUSED = "capture_paused";

    private RuntimeControlStore() {
    }

    public static boolean isCapturePaused(Context context) {
        return preferences(context).getBoolean(KEY_CAPTURE_PAUSED, false);
    }

    public static boolean toggleCapturePaused(Context context) {
        boolean wasPaused = isCapturePaused(context);
        boolean paused = !wasPaused;
        boolean saved = preferences(context)
            .edit()
            .putBoolean(KEY_CAPTURE_PAUSED, paused)
            .commit();
        return saved ? paused : wasPaused;
    }

    public static boolean setCapturePaused(Context context, boolean paused) {
        boolean saved = preferences(context)
            .edit()
            .putBoolean(KEY_CAPTURE_PAUSED, paused)
            .commit();
        return saved ? paused : isCapturePaused(context);
    }

    private static SharedPreferences preferences(Context context) {
        return context.getApplicationContext().getSharedPreferences(
            PREFS_NAME,
            Context.MODE_PRIVATE
        );
    }
}
