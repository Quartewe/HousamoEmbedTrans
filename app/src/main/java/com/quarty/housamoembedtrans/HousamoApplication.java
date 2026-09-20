package com.quarty.housamoembedtrans;

import android.app.Application;
import android.content.Context;

import com.quarty.housamoembedtrans.logging.Log;
import com.quarty.housamoembedtrans.storage.config.RuntimeResourceUpdater;

import com.quarty.housamoembedtrans.ui.UiThemePreference;

/** Starts private logging before providers, then applies UI preferences. */
public final class HousamoApplication extends Application {
    private RuntimeResourceUpdater runtimeResourceUpdater;

    public RuntimeResourceUpdater getRuntimeResourceUpdater() {
        return runtimeResourceUpdater;
    }
    @Override
    protected void attachBaseContext(Context base) {
        super.attachBaseContext(base);
        Log.initialize(this);
    }

    @Override
    public void onCreate() {
        super.onCreate();
        UiThemePreference.apply(this);
        runtimeResourceUpdater = new RuntimeResourceUpdater(this);
        registerActivityLifecycleCallbacks(runtimeResourceUpdater);
    }
}
