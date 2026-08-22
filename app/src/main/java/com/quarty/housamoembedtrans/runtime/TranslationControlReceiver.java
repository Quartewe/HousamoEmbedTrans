package com.quarty.housamoembedtrans.runtime;

import com.quarty.housamoembedtrans.bridge.HetBridgeContract;

import android.os.Build;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.util.Log;

/** Handles notification actions in the module process. */
public final class TranslationControlReceiver extends BroadcastReceiver {

    private static final String TAG = "HET.Notification";

    @Override
    public void onReceive(Context context, Intent intent) {
        if (intent == null
            || !TranslationStatusNotification.ACTION_TOGGLE_CAPTURE.equals(
                intent.getAction()
            )) {
            return;
        }

        boolean paused = RuntimeControlStore.toggleCapturePaused(context);
        Intent serviceIntent = new Intent(
            context.getApplicationContext(),
            com.quarty.housamoembedtrans.translation.TranslationService.class
        )
            .setAction(HetBridgeContract.ACTION_SET_CAPTURE_PAUSED)
            .putExtra(HetBridgeContract.EXTRA_CAPTURE_PAUSED, paused);
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.getApplicationContext().startForegroundService(
                    serviceIntent
                );
            } else {
                context.getApplicationContext().startService(serviceIntent);
            }
        } catch (RuntimeException e) {
            // Keep the durable preference; the next explicit service start or
            // port registration will replay it to native.
            Log.w(TAG, "Could not wake TranslationService for capture control", e);
        }
        TranslationStatusNotification.refresh(context);
        Log.i(TAG, "Capture control requested paused=" + paused);
    }
}
