package com.quarty.housamoembedtrans.summary.job;

import com.quarty.housamoembedtrans.bridge.HetBridgeContract;
import com.quarty.housamoembedtrans.translation.TranslationService;

import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.util.Log;

/**
 * Process-wide durable Summary admission wake path.
 *
 * <p>While the foreground Service is alive, the callback schedules its drain
 * directly.  The explicit Service start is still issued as a safety net for
 * the lifecycle race where the callback's Service instance begins shutting
 * down immediately after accepting the wake.  SummaryJobStore has already
 * committed the job and recovery will find it on the next start if Android
 * rejects either wake request.</p>
 */
public final class SummaryJobWakeup {
    private static final String TAG = "HET.SummaryWakeup";

    @FunctionalInterface
    public interface ServiceWakeCallback {
        /**
         * Schedules work when this Service is fully started.  The caller also
         * issues an explicit start request as a lifecycle safety net.
         */
        void wake();
    }

    /** Host-test seam for the explicit Service-start safety net. */
    @FunctionalInterface
    interface ExplicitServiceStarter {
        void start(Context context);
    }

    private static volatile ServiceWakeCallback serviceWakeCallback;

    private SummaryJobWakeup() {
        throw new AssertionError("No instances");
    }

    public static void setServiceWakeCallback(ServiceWakeCallback callback) {
        serviceWakeCallback = callback;
    }

    public static void clearServiceWakeCallback(ServiceWakeCallback callback) {
        if (callback != null && serviceWakeCallback == callback) {
            serviceWakeCallback = null;
        }
    }

    public static void signal(Context context) {
        signal(context, SummaryJobWakeup::startExplicitService);
    }

    static void signal(
        Context context,
        ExplicitServiceStarter explicitServiceStarter
    ) {
        ServiceWakeCallback callback = serviceWakeCallback;
        if (callback != null) {
            try {
                callback.wake();
            } catch (Throwable error) {
                Log.w(TAG, "Running TranslationService wake failed", error);
            }
        }
        if (explicitServiceStarter == null) {
            return;
        }
        try {
            explicitServiceStarter.start(context);
        } catch (Throwable error) {
            // Durable admission must not be converted into a failed request
            // merely because a background-start policy rejected this wake.
            Log.w(TAG, "Could not wake TranslationService for Summary job", error);
        }
    }

    private static void startExplicitService(Context context) {
        if (context == null) {
            return;
        }
        Context appContext = context.getApplicationContext();
        Intent intent = new Intent(
            appContext,
            TranslationService.class
        ).setAction(HetBridgeContract.ACTION_START_TRANSLATION_SERVICE);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            appContext.startForegroundService(intent);
        } else {
            appContext.startService(intent);
        }
    }
}
