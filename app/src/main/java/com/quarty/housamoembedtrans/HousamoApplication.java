package com.quarty.housamoembedtrans;

import android.app.Application;
import android.content.Context;
import android.content.ComponentName;
import android.content.Intent;
import android.widget.Toast;

import com.quarty.housamoembedtrans.bridge.HetBridgeContract;
import com.quarty.housamoembedtrans.logging.Log;
import com.quarty.housamoembedtrans.storage.config.RuntimeResourceUpdater;
import com.quarty.housamoembedtrans.translation.TranslationService;

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
        runtimeResourceUpdater = new RuntimeResourceUpdater(this, this::startTranslationService);
        registerActivityLifecycleCallbacks(runtimeResourceUpdater);
    }

    /** Runs only when a HET Activity enters the foreground, never on Provider creation. */
    private void startTranslationService() {
        Intent intent = new Intent(HetBridgeContract.ACTION_START_TRANSLATION_SERVICE)
            .setComponent(new ComponentName(
                this, TranslationService.class));
        try {
            ComponentName started = startForegroundService(intent);
            if (started == null) {
                throw new IllegalStateException("Service start was not accepted");
            }
            Log.i("HET.Application", "Foreground entry requested translation service START");
        } catch (RuntimeException error) {
            Log.e("HET.Application", "Could not start translation service", error);
            Toast.makeText(this, R.string.service_start_failed, Toast.LENGTH_LONG).show();
        }
    }
}
