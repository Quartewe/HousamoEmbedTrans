package com.quarty.housamoembedtrans.ui;

import android.content.Context;

import androidx.appcompat.app.AppCompatDelegate;

/** Stores the UI-only theme choice outside config.json. */
public final class UiThemePreference {
    public static final String SYSTEM = "system";
    public static final String LIGHT = "light";
    public static final String DARK = "dark";

    private static final String PREFS = "housamo_trans_ui_preferences";
    private static final String KEY_THEME = "theme_mode";

    private UiThemePreference() {
    }

    public static String read(Context context) {
        String value = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_THEME, SYSTEM);
        return SYSTEM.equals(value) || LIGHT.equals(value) || DARK.equals(value)
            ? value
            : SYSTEM;
    }

    public static void apply(Context context) {
        AppCompatDelegate.setDefaultNightMode(nightMode(read(context)));
    }

    public static boolean saveAndApply(Context context, String mode) {
        String normalized = SYSTEM.equals(mode)
            || LIGHT.equals(mode)
            || DARK.equals(mode)
            ? mode
            : SYSTEM;
        String previous = read(context);
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_THEME, normalized)
            .apply();
        AppCompatDelegate.setDefaultNightMode(nightMode(normalized));
        return !normalized.equals(previous);
    }

    public static int nightMode(String mode) {
        if (LIGHT.equals(mode)) {
            return AppCompatDelegate.MODE_NIGHT_NO;
        }
        if (DARK.equals(mode)) {
            return AppCompatDelegate.MODE_NIGHT_YES;
        }
        return AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM;
    }

    public static int label(String mode) {
        if (LIGHT.equals(mode)) {
            return com.quarty.housamoembedtrans.R.string.settings_rebuild_theme_light;
        }
        if (DARK.equals(mode)) {
            return com.quarty.housamoembedtrans.R.string.settings_rebuild_theme_dark;
        }
        return com.quarty.housamoembedtrans.R.string.settings_rebuild_theme_system;
    }
}
