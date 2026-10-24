package com.quarty.housamoembedtrans.runtime;
import com.quarty.housamoembedtrans.translation.TranslationService;

import com.quarty.housamoembedtrans.bridge.HetBridgeContract;
import com.quarty.housamoembedtrans.bridge.ChoiceBackResult;
import com.quarty.housamoembedtrans.translation.IChoiceBackCallback;
import com.quarty.housamoembedtrans.R;

import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.RemoteException;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.widget.Toast;
import com.quarty.housamoembedtrans.logging.Log;

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
        if (intent != null
            && TranslationStatusNotification.ACTION_CHOICE_BACK.equals(intent.getAction())) {
            Context appContext = context.getApplicationContext();
            try {
                TranslationService.requestChoiceBackFromNotification(
                    new IChoiceBackCallback.Stub() {
                        @Override
                        public void onCompleted(int result) {
                            showChoiceBackResult(appContext, result);
                        }
                    }
                );
            } catch (RemoteException error) {
                Log.w(TAG, "Choice-back notification dispatch lost its connection", error);
                showChoiceBackResult(appContext, ChoiceBackResult.UNKNOWN);
            }
            return;
        }
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

    private static void showChoiceBackResult(Context context, int result) {
        final int message;
        switch (result) {
            case ChoiceBackResult.RESTORED:
                message = R.string.notification_choice_back_restored;
                break;
            case ChoiceBackResult.NO_TARGET:
                message = R.string.notification_choice_back_no_target;
                break;
            case ChoiceBackResult.BUSY:
                message = R.string.notification_choice_back_busy;
                break;
            case ChoiceBackResult.UNAVAILABLE:
                message = R.string.notification_choice_back_unavailable;
                break;
            case ChoiceBackResult.GAME_NOT_CONNECTED:
                message = R.string.notification_choice_back_disconnected;
                break;
            case ChoiceBackResult.FAILED:
                message = R.string.notification_choice_back_failed;
                break;
            default:
                message = R.string.notification_choice_back_unknown;
                break;
        }
        new Handler(Looper.getMainLooper()).post(() ->
            Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
        );
    }
}
