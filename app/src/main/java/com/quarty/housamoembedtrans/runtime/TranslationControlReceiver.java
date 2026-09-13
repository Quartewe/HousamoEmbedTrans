package com.quarty.housamoembedtrans.runtime;
import com.quarty.housamoembedtrans.translation.TranslationService;

import com.quarty.housamoembedtrans.bridge.HetBridgeContract;

import android.os.Build;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.util.Log;

/** Handles notification actions in the module process. */
public final class TranslationControlReceiver extends BroadcastReceiver {

    private static final String TAG = "HET.Notification";

    /** Reports each side effect of one explicit capture-control wake. */
    public static final class CaptureControlWakeResult {
        private final boolean serviceStarted;
        private final boolean notificationRefreshed;

        private CaptureControlWakeResult(
            boolean serviceStarted,
            boolean notificationRefreshed
        ) {
            this.serviceStarted = serviceStarted;
            this.notificationRefreshed = notificationRefreshed;
        }

        public boolean isServiceStarted() {
            return serviceStarted;
        }

        public boolean isNotificationRefreshed() {
            return notificationRefreshed;
        }
    }

    /** Wakes the Service and rebuilds the notification after a control write. */
    public static CaptureControlWakeResult wakeTranslationServiceAndRefresh(
        Context context
    ) {
        Context appContext = context.getApplicationContext();
        Intent serviceIntent = new Intent(
            appContext,
            TranslationService.class
        )
            .setAction(HetBridgeContract.ACTION_START_TRANSLATION_SERVICE);
        boolean started = true;
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                appContext.startForegroundService(serviceIntent);
            } else {
                appContext.startService(serviceIntent);
            }
        } catch (RuntimeException e) {
            // Keep the durable preference; the next explicit service start or
            // port registration will replay it to native.
            started = false;
            Log.w(TAG, "Could not wake TranslationService for capture control", e);
        }
        boolean notificationRefreshed = true;
        try {
            TranslationStatusNotification.refresh(appContext);
        } catch (RuntimeException e) {
            // A notification failure must not hide the persisted control or
            // leave a caller waiting for a result.
            notificationRefreshed = false;
            Log.w(TAG, "Could not refresh capture control notification", e);
        }
        return new CaptureControlWakeResult(started, notificationRefreshed);
    }

    @Override
    public void onReceive(Context context, Intent intent) {
        if (intent == null
            || !TranslationStatusNotification.ACTION_TOGGLE_CAPTURE.equals(
                intent.getAction()
            )) {
            return;
        }

        // Keep legacy broadcast senders safe: a notification action must not
        // toggle capture behind the user's back.  Route it through the task
        // page, which confirms a pause and applies an explicit desired state.
        boolean desiredPaused = !RuntimeControlStore.isCapturePaused(context);
        Intent controlIntent = new Intent(
            context,
            com.quarty.housamoembedtrans.ui.TranslationQueueActivity.class
        )
            .putExtra(
                com.quarty.housamoembedtrans.ui.TranslationQueueActivity
                    .EXTRA_NOTIFICATION_CAPTURE_CONTROL,
                true
            )
            .putExtra(
                com.quarty.housamoembedtrans.ui.TranslationQueueActivity
                    .EXTRA_NOTIFICATION_CAPTURE_DESIRED_PAUSED,
                desiredPaused
            )
            .addFlags(
                Intent.FLAG_ACTIVITY_NEW_TASK
                    | Intent.FLAG_ACTIVITY_CLEAR_TOP
                    | Intent.FLAG_ACTIVITY_SINGLE_TOP
            );
        try {
            context.startActivity(controlIntent);
            Log.i(TAG, "Capture control routed to task-page confirmation");
        } catch (RuntimeException error) {
            Log.w(TAG, "Could not open task-page capture control", error);
        }
    }
}
