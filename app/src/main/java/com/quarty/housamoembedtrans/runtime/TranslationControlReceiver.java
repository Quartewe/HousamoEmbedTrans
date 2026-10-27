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
    public static final String EXTRA_RENDER_PAUSED = "render_paused";

    /** Reports each side effect of one explicit runtime-control wake. */
    public static final class ControlWakeResult {
        private final boolean serviceStarted;
        private final boolean notificationRefreshed;

        private ControlWakeResult(
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
    public static ControlWakeResult wakeTranslationServiceAndRefresh(
        Context context
    ) {
        return wakeTranslationServiceAndRefresh(context, false);
    }

    public static ControlWakeResult wakeTaskProductionControl(Context context) {
        return wakeTranslationServiceAndRefresh(context, true);
    }

    private static ControlWakeResult wakeTranslationServiceAndRefresh(
        Context context, boolean taskProductionOnly
    ) {
        Context appContext = context.getApplicationContext();
        Intent serviceIntent = new Intent(
            appContext,
            TranslationService.class
        )
            .setAction(HetBridgeContract.ACTION_START_TRANSLATION_SERVICE)
            .putExtra(TranslationService.EXTRA_TASK_PRODUCTION_CONTROL, taskProductionOnly);
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
        return new ControlWakeResult(started, notificationRefreshed);
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
            || !TranslationStatusNotification.ACTION_SET_RENDER_PAUSED.equals(
                intent.getAction()
            )) {
            return;
        }

        Context appContext = context.getApplicationContext();
        boolean desiredPaused = intent.getBooleanExtra(EXTRA_RENDER_PAUSED, false);
        PendingResult pending = goAsync();
        new Thread(() -> {
            try {
                int message = applyRenderPaused(appContext, desiredPaused);
                if (message != 0) {
                    new Handler(Looper.getMainLooper()).post(() ->
                        Toast.makeText(appContext, message, Toast.LENGTH_LONG).show());
                }
            } finally {
                pending.finish();
            }
        }, "HET.RenderControl").start();
    }

    /** Called off the UI thread; home and notification reuse the existing latch. */
    public static int applyRenderPaused(Context context, boolean paused) {
        try {
            if (!RuntimeControlStore.trySetCapturePaused(context, paused)) {
                return R.string.render_control_save_failed;
            }
        } catch (RuntimeException error) {
            Log.w(TAG, "Could not save render pause", error);
            return R.string.render_control_save_failed;
        }
        ControlWakeResult result = wakeTranslationServiceAndRefresh(context);
        if (!result.isServiceStarted()) return R.string.render_control_waiting_service;
        if (!result.isNotificationRefreshed()) return R.string.render_control_sync_failed;
        return 0;
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
