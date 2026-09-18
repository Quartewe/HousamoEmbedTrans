package com.quarty.housamoembedtrans;

import android.app.Application;

import com.quarty.housamoembedtrans.ui.UiThemePreference;

/** Applies UI-only preferences before any activity or notification page is created. */
public final class HousamoApplication extends Application {
    @Override
    public void onCreate() {
        super.onCreate();
        UiThemePreference.apply(this);
    }
}
