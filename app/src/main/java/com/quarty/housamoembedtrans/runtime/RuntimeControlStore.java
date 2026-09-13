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
        boolean saved = trySetCapturePaused(context, paused);
        return saved ? paused : wasPaused;
    }

    public static boolean setCapturePaused(Context context, boolean paused) {
        boolean saved = trySetCapturePaused(context, paused);
        return saved ? paused : isCapturePaused(context);
    }

    /**
     * Persists the requested capture state and reports the commit result.
     *
     * <p>The boolean-returning setters preserve the effective-state API used
     * by the runtime.  UI controls that need to distinguish a failed commit
     * must use this method because SharedPreferences may update its in-memory
     * view even when {@code commit()} reports failure.</p>
     */
    public static boolean trySetCapturePaused(
        Context context,
        boolean paused
    ) {
        return preferences(context)
            .edit()
            .putBoolean(KEY_CAPTURE_PAUSED, paused)
            .commit();
    }

    private static SharedPreferences preferences(Context context) {
        return context.getApplicationContext().getSharedPreferences(
            PREFS_NAME,
            Context.MODE_PRIVATE
        );
    }
}
