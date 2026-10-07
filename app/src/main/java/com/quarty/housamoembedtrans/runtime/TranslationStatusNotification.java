package com.quarty.housamoembedtrans.runtime;
import com.quarty.housamoembedtrans.translation.TranslationService;

import com.quarty.housamoembedtrans.R;
import com.quarty.housamoembedtrans.ui.SceneConflictsActivity;
import com.quarty.housamoembedtrans.ui.SceneContextActivity;
import com.quarty.housamoembedtrans.ui.SceneFilesActivity;
import com.quarty.housamoembedtrans.ui.SettingsActivity;
import com.quarty.housamoembedtrans.ui.TranslationQueueActivity;
import com.quarty.housamoembedtrans.translation.job.TranslationJobStore;
import com.quarty.housamoembedtrans.translation.job.TranslationTaskExecutor;
import com.quarty.housamoembedtrans.scene.store.SceneStore;

import android.Manifest;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.text.format.DateUtils;
import com.quarty.housamoembedtrans.logging.Log;

import org.json.JSONObject;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Owns the persistent translation status notification in the HET app process. */
public final class TranslationStatusNotification {

    static final int STATUS_STARTED = 1;
    static final int STATUS_SUCCEEDED = 2;
    static final int STATUS_FAILED = 3;
    static final int STATUS_BLOCKED = 4;

    static final String ACTION_TOGGLE_CAPTURE =
        "com.quarty.housamoembedtrans.action.TOGGLE_CAPTURE";

    private static final String TAG = "HET.Notification";
    private static final String CHANNEL_ID = "translation_status";
    // A new ID is required: Android retains the old channel importance on upgrade.
    private static final String JOB_ERROR_CHANNEL_ID = "job_errors_heads_up";
    public static final int NOTIFICATION_ID = 0x484554;
    private static final int SCENE_CONFLICT_NOTIFICATION_ID = 0x484555;
    // Accessed only on the notification (main) thread. Avoid re-posting a
    // dismissed alert on every API/progress snapshot.
    private static int notifiedSceneConflictCount;
    // Main-thread-only alert deduplication, independent of the quiet foreground notification.
    private static final Map<String, AttentionNotice> notifiedAttention = new HashMap<>();
    private static final String ATTENTION_TAG_PREFIX = "het-attention:";
    private static final int SUMMARY_ERROR_NOTIFICATION_BASE = 0x534D;
    private static final int REJECTED_API_RESULT_NOTIFICATION_BASE = 0x524A;
    private static final int SCENE_REJECTION_NOTIFICATION_BASE = 0x534E;
    private static final int SETTINGS_REQUEST_CODE = 1;
    private static final int CAPTURE_REQUEST_CODE = 2;
    private static final int QUEUE_REQUEST_CODE = 3;
    private static final int SCENE_FILES_REQUEST_CODE = 4;
    private static final int SCENE_CONFLICTS_REQUEST_CODE = 5;
    private static final int SCENE_CONTEXT_REVIEW_REQUEST_CODE = 6;
    private static final int REJECTED_API_RESULTS_REQUEST_CODE = 7;
    private static final String NOTIFICATION_INTENT_SCHEME =
        "housamoembedtrans";

    private static final String PREFS_NAME = "translation_notification_state";
    private static final String KEY_STATE = "state";
    private static final String KEY_SCENE = "scene";
    private static final String KEY_REQUEST_ID = "request_id";
    private static final String KEY_STARTED_AT = "started_at";
    private static final String KEY_FINISHED_AT = "finished_at";

    private static final String STATE_IDLE = "idle";
    private static final String STATE_ACTIVE = "active";
    private static final String STATE_SUCCEEDED = "succeeded";
    private static final String STATE_FAILED = "failed";
    private static final String STATE_BLOCKED = "blocked";
    private static final String STATE_STARTUP_FAILED = "startup_failed";

    private static final String KEY_BLOCKED_MESSAGE = "blocked_message";
    private static final String KEY_BLOCKED_REQUEST_ID = "blocked_request_id";
    private static final String STATE_ADMISSION_BLOCKED = "admission_blocked";
    private static final String KEY_STARTUP_FAILED_MESSAGE =
        "startup_failed_message";

    /**
     * Process-local store set by TranslationService once its runtime has been
     * built. While null (before the background startup coordinator runs), the
     * foreground notification must stay lightweight and show zero queue state
     * instead of constructing the store on the main thread.
     */
    private static volatile TranslationJobStore statusJobStore;
    private static volatile SceneStore statusSceneStore;

    private TranslationStatusNotification() {
    }

    /** Installs the process-local store used by notification snapshots. */
    public static void setJobStore(TranslationJobStore store) {
        statusJobStore = store;
    }

    /** Installs the process-local Scene store used for pool diagnostics. */
    public static void setSceneStore(SceneStore store) {
        statusSceneStore = store;
    }

    /** Drop only the previous startup attempt's error before a new attempt. */
    public static void clearStartupFailure(Context context) {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            Context appContext = context.getApplicationContext();
            runOnNotificationThread(() -> clearStartupFailure(appContext));
            return;
        }
        android.content.SharedPreferences prefs = state(context);
        if (STATE_STARTUP_FAILED.equals(prefs.getString(KEY_STATE, STATE_IDLE))) {
            prefs.edit().putString(KEY_STATE, STATE_IDLE)
                .remove(KEY_STARTUP_FAILED_MESSAGE)
                .remove(KEY_STARTED_AT).remove(KEY_FINISHED_AT).apply();
            refresh(context);
        }
    }

    /**
     * Shows a dedicated user-visible failure state for a failed linear
     * startup coordinator run. This is distinct from a per-job translation
     * failure and keeps an actionable jump into the queue.
     */
    public static void startupFailed(Context context, String message) {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            Context appContext = context.getApplicationContext();
            runOnNotificationThread(() -> startupFailed(appContext, message));
            return;
        }
        Context appContext = context.getApplicationContext();
        state(appContext)
            .edit()
            .putString(KEY_STATE, STATE_STARTUP_FAILED)
            .putString(KEY_SCENE, "")
            .putString(
                KEY_STARTUP_FAILED_MESSAGE,
                message == null ? "" : message
            )
            .putLong(KEY_STARTED_AT, System.currentTimeMillis())
            .remove(KEY_FINISHED_AT)
            .apply();
        show(appContext);
    }

    public static void translationStarted(
        Context context,
        String requestId,
        String sceneName
    ) {
        update(context, requestId, sceneName, STATUS_STARTED);
    }

    public static void translationSucceeded(
        Context context,
        String requestId,
        String sceneName
    ) {
        update(context, requestId, sceneName, STATUS_SUCCEEDED);
    }

    public static void translationFailed(
        Context context,
        String requestId,
        String sceneName
    ) {
        update(context, requestId, sceneName, STATUS_FAILED);
    }

    /**
     * Shows a non-terminal user-action-required status for a live blocked job.
     * Either the main request or a later repair may have been blocked.
     */
    public static void translationNeedsUserAction(
        Context context,
        String requestId,
        String sceneName,
        String message
    ) {
        Context appContext = context.getApplicationContext();
        runOnNotificationThread(() -> {
            TranslationTaskExecutor executor = TranslationService.getActiveTaskExecutor();
            if (requestId == null || executor == null
                || !executor.hasUserActionRequiredJob(requestId)) {
                return;
            }
            publishBlocked(appContext, requestId, sceneName, message);
        });
    }

    /** Admission failures have no stored job and must not masquerade as one. */
    public static void admissionNeedsUserAction(
        Context context,
        String sceneName,
        String message
    ) {
        Context appContext = context.getApplicationContext();
        runOnNotificationThread(() -> publishBlocked(appContext, null, sceneName, message));
    }

    public static void translationCanceled(Context context, String requestId) {
        Context appContext = context.getApplicationContext();
        runOnNotificationThread(() -> {
            SharedPreferences prefs = state(appContext);
            if (requestId != null && requestId.equals(prefs.getString(KEY_REQUEST_ID, ""))) {
                clearBlocked(prefs);
            }
            if (STATE_BLOCKED.equals(prefs.getString(KEY_STATE, STATE_IDLE))
                && requestId != null
                && requestId.equals(prefs.getString(KEY_BLOCKED_REQUEST_ID, ""))) {
                clearBlocked(prefs);
            }
            // Reconcile active jobs too, including another job that is still running.
            show(appContext);
        });
    }

    private static void runOnNotificationThread(Runnable action) {
        Runnable guarded = () -> {
            try {
                action.run();
            } catch (RuntimeException error) {
                Log.w(TAG, "Could not update translation status notification", error);
            }
        };
        if (Looper.myLooper() == Looper.getMainLooper()) {
            guarded.run();
        } else {
            new Handler(Looper.getMainLooper()).post(guarded);
        }
    }

    private static void clearBlocked(SharedPreferences prefs) {
        prefs.edit()
            .putString(KEY_STATE, STATE_IDLE)
            .remove(KEY_BLOCKED_REQUEST_ID)
            .remove(KEY_BLOCKED_MESSAGE)
            .remove(KEY_SCENE)
            .remove(KEY_REQUEST_ID)
            .remove(KEY_STARTED_AT)
            .remove(KEY_FINISHED_AT)
            .apply();
    }

    private static void publishBlocked(
        Context context, String requestId, String sceneName, String message
    ) {
        if (sceneName == null || sceneName.trim().isEmpty()) {
            Log.w(
                TAG,
                "Ignoring blocked translation with an empty scene name"
            );
            return;
        }
        Context appContext = context.getApplicationContext();
        state(appContext)
            .edit()
            .putString(KEY_STATE, requestId == null ? STATE_ADMISSION_BLOCKED : STATE_BLOCKED)
            .putString(KEY_BLOCKED_REQUEST_ID, requestId)
            .putString(KEY_SCENE, sceneName)
            .putString(
                KEY_BLOCKED_MESSAGE,
                message == null ? "" : message
            )
            .putLong(KEY_STARTED_AT, System.currentTimeMillis())
            .remove(KEY_FINISHED_AT)
            .apply();
        show(appContext);
    }

    /**
     * Posts one per-job error notification for a failed Summary job. The
     * durable Summary store's {@code notified} flag prevents duplicate calls;
     * this method is only the user-visible channel for that one notification.
     */
    public static void summaryFailed(
        Context context,
        String requestId,
        String ownerType,
        String ownerId,
        String message
    ) {
        Context appContext = context.getApplicationContext();
        String title = summaryFailureTitle(appContext, ownerType);
        String text = failureText(appContext, message, R.string.notification_summary_failed_generic);
        postJobErrorNotification(
            appContext,
            requestId,
            title,
            text,
            ownerId,
            SUMMARY_ERROR_NOTIFICATION_BASE,
            "summary"
        );
    }

    /** Posts one per-job error notification for a failed Translation job. */
    public static void translationFailedDetails(
        Context context,
        String requestId,
        String scene,
        String message
    ) {
        Context appContext = context.getApplicationContext();
        String title = appContext.getString(R.string.notification_translation_failed_title);
        String text = failureText(appContext, message, R.string.notification_translation_failed_generic);
        postJobErrorNotification(
            appContext,
            requestId,
            title,
            text,
            scene,
            SUMMARY_ERROR_NOTIFICATION_BASE + 1000,
            "translation"
        );
    }

    /** A retry is recoverable; never overwrite the task's terminal status. */
    public static void apiRetry(Context context, String requestId, String scene,
        String phase, Throwable error, int retry, int limit) {
        if (context == null) {
            return;
        }
        Context app = context.getApplicationContext();
        runOnNotificationThread(() -> {
            try {
                NotificationManager manager = app.getSystemService(NotificationManager.class);
                if (manager == null || (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
                    && app.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
                        != PackageManager.PERMISSION_GRANTED)) {
                    return;
                }
                createJobErrorChannel(app, manager);
                String reason = app.getString(R.string.notification_api_response_invalid);
                // Do not expose the provider response body or request secrets in a banner.
                for (Throwable cause = error; cause != null; cause = cause.getCause()) {
                    if (cause instanceof com.quarty.housamoembedtrans.provider.TranslationApiClient.HttpStatusException) {
                        reason = "HTTP " + ((com.quarty.housamoembedtrans.provider.TranslationApiClient.HttpStatusException) cause).getStatusCode();
                        break;
                    }
                    if (cause instanceof java.net.SocketTimeoutException) {
                        reason = app.getString(R.string.notification_api_timeout);
                        break;
                    }
                    if (cause instanceof java.io.IOException) {
                        reason = app.getString(R.string.notification_api_network_error);
                        break;
                    }
                }
                String text = app.getString(R.string.notification_api_retry_text,
                    phase, reason, retry, limit);
                Notification notification = buildRetryNotification(app, scene, text, queuePendingIntent(app, requestId, "translation"));
                manager.notify("api-retry:" + requestId + ":" + phase, 1, notification);
            } catch (RuntimeException e) {
                Log.w(TAG, "Could not post API retry notification", e);
            }
        });
    }

    private static void postJobErrorNotification(
        Context context,
        String requestId,
        String title,
        String text,
        String subText,
        int notificationIdBase,
        String failureType
    ) {
        NotificationManager manager = context.getSystemService(
            NotificationManager.class
        );
        if (manager == null) {
            Log.w(TAG, "NotificationManager is unavailable");
            return;
        }
        createJobErrorChannel(context, manager);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
            && context.checkSelfPermission(
                Manifest.permission.POST_NOTIFICATIONS
            ) != PackageManager.PERMISSION_GRANTED) {
            Log.i(TAG, "Notification permission has not been granted");
            return;
        }

        Notification notification = buildJobErrorNotification(context, title, text, subText, queuePendingIntent(context, requestId, failureType));

        int notificationId = notificationIdBase
            + (requestId == null
                ? 0
                : Math.abs(requestId.hashCode() % 1000));
        try {
            manager.notify(notificationId, notification);
        } catch (SecurityException e) {
            Log.w(
                TAG,
                "Could not post " + failureType + " failure notification",
                e
            );
        }
    }

    /** Posts a one-time notification for a newly archived API result. */
    public static void rejectedApiResultArchived(
        Context context,
        JSONObject record
    ) {
        if (record == null) {
            return;
        }
        Context appContext = context.getApplicationContext();
        NotificationManager manager = appContext.getSystemService(
            NotificationManager.class
        );
        if (manager == null) {
            Log.w(TAG, "NotificationManager is unavailable");
            return;
        }
        createJobErrorChannel(appContext, manager);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
            && appContext.checkSelfPermission(
                Manifest.permission.POST_NOTIFICATIONS
            ) != PackageManager.PERMISSION_GRANTED) {
            Log.i(TAG, "Notification permission has not been granted");
            return;
        }

        String jobKind = record.optString("job_kind", "API");
        String requestId = record.optString("request_id", "");
        String text = rejectedResultText(appContext, jobKind);
        Notification notification = buildRejectedResultNotification(appContext, text, rejectedApiResultsPendingIntent(appContext, record.optString("record_id", requestId)));
        try {
            manager.notify(
                stableNotificationId(
                    REJECTED_API_RESULT_NOTIFICATION_BASE,
                    record.optString("record_id", requestId)
                ),
                notification
            );
        } catch (SecurityException e) {
            Log.w(TAG, "Could not post rejected API result notification", e);
        }
    }

    /** Posts a deduplicated, actionable Scene production rejection notice. */
    public static void sceneProductionRejected(
        Context context,
        String sceneName,
        int reasonCode,
        boolean syncWorkerHold,
        boolean hasFormalConflict,
        long generation
    ) {
        if (sceneName == null || sceneName.trim().isEmpty()) {
            return;
        }
        Context appContext = context.getApplicationContext();
        NotificationManager manager = appContext.getSystemService(
            NotificationManager.class
        );
        if (manager == null) {
            Log.w(TAG, "NotificationManager is unavailable");
            return;
        }
        createJobErrorChannel(appContext, manager);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
            && appContext.checkSelfPermission(
                Manifest.permission.POST_NOTIFICATIONS
            ) != PackageManager.PERMISSION_GRANTED) {
            Log.i(TAG, "Notification permission has not been granted");
            return;
        }

        int textResource;
        PendingIntent contentIntent;
        if (syncWorkerHold) {
            textResource = R.string.notification_scene_rejected_sync_active;
            contentIntent = sceneFilesPendingIntent(appContext);
        } else if (hasFormalConflict) {
            textResource = R.string.notification_scene_rejected_conflict;
            contentIntent = sceneConflictsPendingIntent(appContext);
        } else {
            textResource = R.string.notification_scene_rejected_not_synced;
            contentIntent = sceneFilesPendingIntent(appContext);
        }
        Notification notification = buildSceneRejectionNotification(appContext, sceneName, textResource, contentIntent);
        try {
            manager.notify(
                stableNotificationId(
                    SCENE_REJECTION_NOTIFICATION_BASE,
                    generation + "|" + sceneName + "|" + reasonCode
                ),
                notification
            );
        } catch (SecurityException e) {
            Log.w(TAG, "Could not post Scene production rejection notification", e);
        }
    }

    static String summaryFailureTitle(Context context, String ownerType) {
        return context.getString("context".equals(ownerType)
            ? R.string.notification_context_summary_failed_title
            : "group".equals(ownerType) ? R.string.notification_group_summary_failed_title
            : R.string.notification_summary_failed_title);
    }

    static String rejectedResultText(Context context, String jobKind) {
        return context.getString("translation".equals(jobKind)
            ? R.string.notification_rejected_translation_text
            : "summary".equals(jobKind) ? R.string.notification_rejected_summary_text
            : R.string.notification_rejected_api_result_text);
    }

    // Presentation only: retain the original error in the job store and logs.
    // Some callers pass error.message, others pass the serialized error envelope.
    static String failureText(Context context, String message, int fallbackResource) {
        String detail = message == null ? "" : message.trim();
        String type = "";
        int status = 0;
        try {
            if (detail.startsWith("{")) {
                JSONObject error = new JSONObject(detail);
                if (error.optJSONObject("error") != null) error = error.optJSONObject("error");
                type = error.optString("type", "");
                status = error.optInt("status", 0);
                detail = error.optString("message", "");
            }
        } catch (org.json.JSONException ignored) {
            // Malformed diagnostic text must not prevent notification delivery.
        }
        String lower = detail.toLowerCase(java.util.Locale.ROOT);
        java.util.regex.Matcher http = java.util.regex.Pattern
            .compile("(?i)\\bHTTP\\s+(\\d{3})\\b").matcher(detail);
        if (status < 400 && http.find()) status = Integer.parseInt(http.group(1));
        if (status == 400 && lower.contains("max_tokens")
            && (lower.contains("range") || lower.contains("must be") || lower.contains("maximum"))) {
            return context.getString(R.string.notification_error_output_budget);
        }
        if (status >= 400) {
            int resource = status == 401 || status == 403 ? R.string.notification_error_auth
                : status == 429 ? R.string.notification_error_rate
                : status >= 500 ? R.string.notification_error_service
                : R.string.notification_error_http;
            return context.getString(resource, status);
        }
        if (lower.contains("timed out") || lower.contains("timeout")) {
            return context.getString(R.string.notification_error_timeout);
        }
        if (lower.contains("result remained invalid") || lower.contains("translation validation failed")
            || lower.contains("invalid response")
            || "validation".equals(type)) {
            return context.getString(R.string.notification_error_response);
        }
        if (lower.contains("network retries") || lower.contains("unable to resolve host")
            || lower.contains("failed to connect") || lower.contains("connection reset")) {
            return context.getString(R.string.notification_error_network);
        }
        return context.getString(fallbackResource);
    }

    static Notification buildRetryNotification(Context app, String scene, String text, PendingIntent intent) {
        return new Notification.Builder(app, JOB_ERROR_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(app.getString(R.string.notification_api_retry_title))
            .setSubText(scene)
            .setContentText(text)
            .setStyle(new Notification.BigTextStyle().bigText(text))
            .setContentIntent(intent)
            .setAutoCancel(true)
            .setOnlyAlertOnce(true)
            .setTimeoutAfter(60_000L)
            .setCategory(Notification.CATEGORY_ERROR)
            .setVisibility(Notification.VISIBILITY_PRIVATE)
            .build();
    }

    static Notification buildJobErrorNotification(Context context, String title, String text, String subText, PendingIntent intent) {
        return new Notification.Builder(
            context,
            JOB_ERROR_CHANNEL_ID
        )
            .setSmallIcon(R.drawable.ic_notification)
            .setColor(context.getColor(R.color.het_primary))
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(new Notification.BigTextStyle().bigText(text))
            .setSubText(subText)
            .setContentIntent(intent)
            .setAutoCancel(true)
            .setOnlyAlertOnce(true)
            .setCategory(Notification.CATEGORY_ERROR)
            .setVisibility(Notification.VISIBILITY_PRIVATE)
            .build();
    }

    static Notification buildRejectedResultNotification(Context appContext, String text, PendingIntent intent) {
        return new Notification.Builder(
            appContext,
            JOB_ERROR_CHANNEL_ID
        )
            .setSmallIcon(R.drawable.ic_notification)
            .setColor(appContext.getColor(R.color.het_primary))
            .setContentTitle(appContext.getString(
                R.string.notification_rejected_api_result_title
            ))
            .setContentText(text)
            .setStyle(new Notification.BigTextStyle().bigText(text))
            .setSubText(appContext.getString(
                R.string.notification_rejected_api_result_subtitle
            ))
            .setContentIntent(intent)
            .setAutoCancel(true)
            .setOnlyAlertOnce(true)
            .setCategory(Notification.CATEGORY_ERROR)
            .setVisibility(Notification.VISIBILITY_PRIVATE)
            .build();
    }

    static Notification buildSceneRejectionNotification(Context appContext, String sceneName, int textResource, PendingIntent contentIntent) {
        return new Notification.Builder(
            appContext,
            JOB_ERROR_CHANNEL_ID
        )
            .setSmallIcon(R.drawable.ic_notification)
            .setColor(appContext.getColor(R.color.het_primary))
            .setContentTitle(appContext.getString(
                R.string.notification_scene_rejected_title
            ))
            .setContentText(appContext.getString(textResource))
            .setStyle(new Notification.BigTextStyle().bigText(appContext.getString(textResource)))
            .setSubText(sceneName)
            .setContentIntent(contentIntent)
            .setAutoCancel(true)
            .setOnlyAlertOnce(true)
            .setCategory(Notification.CATEGORY_ERROR)
            .setVisibility(Notification.VISIBILITY_PRIVATE)
            .build();
    }

    static Notification buildSceneConflictNotification(Context context, int count, boolean onlyAlertOnce, PendingIntent intent) {
        return new Notification.Builder(context, JOB_ERROR_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setColor(context.getColor(R.color.het_primary))
            .setContentTitle(context.getString(R.string.notification_scene_conflicts_title))
            .setContentText(context.getString(R.string.notification_scene_conflicts_text, count))
            .setContentIntent(intent)
            .setAutoCancel(true)
            .setOnlyAlertOnce(onlyAlertOnce)
            .setCategory(Notification.CATEGORY_ERROR)
            .setVisibility(Notification.VISIBILITY_PRIVATE)
            .build();
    }

    private static int stableNotificationId(int base, String identity) {
        return base ^ (identity == null ? 0 : identity.hashCode());
    }

    private static void update(
        Context context,
        String requestId,
        String sceneName,
        int status
    ) {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            Context appContext = context.getApplicationContext();
            runOnNotificationThread(() -> update(appContext, requestId, sceneName, status));
            return;
        }
        if (sceneName == null || sceneName.trim().isEmpty()) {
            Log.w(TAG, "Ignoring translation status with an empty scene name");
            return;
        }

        Context appContext = context.getApplicationContext();
        SharedPreferences state = state(appContext);
        String currentRequestId = state.getString(KEY_REQUEST_ID, "");
        if (status != STATUS_STARTED && !requestId.equals(currentRequestId)) {
            // A late terminal callback must not overwrite a different task.
            show(appContext);
            return;
        }
        if (status == STATUS_STARTED) {
            TranslationTaskExecutor executor = TranslationService.getActiveTaskExecutor();
            TranslationTaskExecutor.ActiveTranslationSnapshot active = executor == null
                ? null : executor.getActiveTranslationSnapshot(requestId);
            if (active == null || !requestId.equals(active.requestId)) {
                show(appContext);
                return;
            }
        }
        long now = System.currentTimeMillis();
        SharedPreferences.Editor editor = state.edit().putString(KEY_SCENE, sceneName).putString(KEY_REQUEST_ID, requestId)
            .remove(KEY_BLOCKED_REQUEST_ID)
            .remove(KEY_BLOCKED_MESSAGE);

        if (status == STATUS_STARTED) {
            editor
                .putString(KEY_STATE, STATE_ACTIVE)
                .putLong(KEY_STARTED_AT, now)
                .remove(KEY_FINISHED_AT)
                .apply();
        } else if (status == STATUS_SUCCEEDED || status == STATUS_FAILED) {
            String activeScene = state.getString(KEY_SCENE, "");
            if (!sceneName.equals(activeScene)
                || !STATE_ACTIVE.equals(state.getString(KEY_STATE, STATE_IDLE))) {
                editor.putLong(KEY_STARTED_AT, now);
            }
            editor
                .putString(
                    KEY_STATE,
                    status == STATUS_SUCCEEDED
                        ? STATE_SUCCEEDED
                        : STATE_FAILED
                )
                .putLong(KEY_FINISHED_AT, now)
                .apply();
        } else {
            Log.w(TAG, "Ignoring unknown translation status " + status);
            return;
        }

        show(appContext);
    }

    public static void refresh(Context context) {
        show(context.getApplicationContext());
    }

    /** Ephemeral rendering inputs for style checks; never installed as runtime state. */
    static final class PreviewStatus {
        String status = STATE_IDLE;
        String scene;
        String message;
        long startedAt = System.currentTimeMillis() - 65_000L;
        long finishedAt = System.currentTimeMillis();
        boolean paused;
        int heldQueuedJobCount;
        int manualRerunCandidateCount;
        int pendingMutationCount;
        String pendingMutationDiagnostic = "";
        boolean mutationFailed;
        boolean repairingStartupJobs;
        boolean manualStartupRepair;
        SceneSyncRuntimeState.Snapshot sceneSync = new SceneSyncRuntimeState.Snapshot(
            true, true, SceneSyncRuntimeState.Phase.IDLE, 0, 0,
            SceneSyncRuntimeState.Action.NONE, SceneSyncRuntimeState.Outcome.NONE,
            java.util.Collections.emptyList());
    }

    static void preparePreviewChannels(Context context, NotificationManager manager) {
        createChannel(context, manager);
        createJobErrorChannel(context, manager);
    }

    private static Notification buildStatusNotification(Context context, boolean forceOngoing) {
        return buildStatusNotification(context, forceOngoing, null, null, null);
    }

    /** Uses the same attention decision as live status refreshes, without publishing live state. */
    static Notification buildStatusPreviewNotification(Context context, PreviewStatus preview,
        PendingIntent previewIntent) {
        List<AttentionNotice> notices = new ArrayList<>();
        Notification status = buildStatusNotification(context, false, preview, previewIntent, notices);
        if (!notices.isEmpty()) return notices.get(0).notification;
        if (STATE_FAILED.equals(preview.status)) {
            // Live failures already have a separate translationFailedDetails notification.
            return buildJobErrorNotification(context,
                context.getString(R.string.notification_translation_failed_title),
                context.getString(R.string.notification_translation_failed_generic),
                preview.scene, previewIntent);
        }
        if (preview.sceneSync.pendingConflictCount > 0) {
            return buildSceneConflictNotification(context, preview.sceneSync.pendingConflictCount,
                false, previewIntent);
        }
        return status;
    }

    private static Notification buildStatusNotification(Context context, boolean forceOngoing,
        PreviewStatus preview, PendingIntent previewIntent, List<AttentionNotice> notices) {
        SharedPreferences state = preview == null ? state(context) : null;
        String status = preview == null ? state.getString(KEY_STATE, STATE_IDLE) : preview.status;
        if (preview == null && (STATE_ACTIVE.equals(status) || STATE_IDLE.equals(status)
            || STATE_SUCCEEDED.equals(status) || STATE_FAILED.equals(status))) {
            TranslationTaskExecutor executor = TranslationService.getActiveTaskExecutor();
            TranslationTaskExecutor.ActiveTranslationSnapshot active = executor == null
                ? null : executor.getActiveTranslationSnapshot(state.getString(KEY_REQUEST_ID, ""));
            if (active != null) {
                state.edit().putString(KEY_STATE, STATE_ACTIVE)
                    .putString(KEY_REQUEST_ID, active.requestId)
                    .putString(KEY_SCENE, active.scene)
                    .putLong(KEY_STARTED_AT, active.startedAt)
                    .remove(KEY_FINISHED_AT).apply();
                status = STATE_ACTIVE;
            } else if (STATE_ACTIVE.equals(status)) {
                // Includes legacy active notices and notices left by process death.
                String completedRequestId = state.getString(KEY_REQUEST_ID, "");
                clearBlocked(state);
                // A queue refresh can precede the terminal observer callback.
                // Retain only its identity, never its active display or timer.
                state.edit().putString(KEY_REQUEST_ID, completedRequestId).apply();
                status = STATE_IDLE;
            }
        }
        if (preview == null && STATE_BLOCKED.equals(status)) {
            String requestId = state.getString(KEY_BLOCKED_REQUEST_ID, "");
            TranslationTaskExecutor executor = TranslationService.getActiveTaskExecutor();
            if (requestId.isEmpty()
                || (executor != null && !executor.hasUserActionRequiredJob(requestId))) {
                // Also migrates legacy notices that cannot identify their job.
                clearBlocked(state);
                status = STATE_IDLE;
            } else if (executor == null) {
                // Startup has not reconstructed live jobs yet; do not advertise
                // an unverified persisted blocker as an actionable task.
                status = STATE_IDLE;
            }
        }
        String scene = preview == null ? state.getString(KEY_SCENE, "") : preview.scene;
        long startedAt = preview == null ? state.getLong(KEY_STARTED_AT, 0L) : preview.startedAt;
        long finishedAt = preview == null ? state.getLong(KEY_FINISHED_AT, System.currentTimeMillis()) : preview.finishedAt;
        boolean paused = preview == null ? RuntimeControlStore.isCapturePaused(context) : preview.paused;
        TranslationJobStore jobStore = preview == null ? statusJobStore : null;
        SceneStore sceneStore = preview == null ? statusSceneStore : null;
        // The notification may be rebuilt on the service/main thread.  Read
        // only the store's O(1) in-memory snapshot; durable candidate scans
        // belong to startup repair or the queue activity's I/O executor.
        // Before the background startup coordinator builds the store, keep the
        // foreground notification lightweight and avoid constructing it here.
        int heldQueuedJobCount = preview != null ? preview.heldQueuedJobCount : jobStore == null
            ? 0
            : jobStore.getHeldQueuedJobCount();
        int manualRerunCandidateCount = preview != null ? preview.manualRerunCandidateCount : jobStore == null
            ? 0
            : jobStore.getManualRerunCandidateCount();
        int pendingMutationCount = preview != null ? preview.pendingMutationCount : sceneStore == null
            ? 0
            : sceneStore.getDeferredMutationCountSnapshot();
        String pendingMutationDiagnostic = preview != null ? preview.pendingMutationDiagnostic : sceneStore == null
            ? ""
            : sceneStore.getDeferredMutationDiagnosticSnapshot();
        boolean hasPendingMutationNotice = pendingMutationCount > 0
            || !pendingMutationDiagnostic.trim().isEmpty();
        boolean mutationFailed = preview != null ? preview.mutationFailed
            : sceneStore != null && sceneStore.hasDeferredMutationFailureSnapshot();
        boolean repairingStartupJobs = preview != null ? preview.repairingStartupJobs : jobStore != null
            && jobStore.isRepairingStartupJobs();
        boolean manualStartupRepair = preview != null ? preview.manualStartupRepair : jobStore != null
            && jobStore.isManualStartupRepairInProgress();
        SceneSyncRuntimeState.Snapshot sceneSync =
            preview == null ? SceneSyncRuntimeState.getInstance().getSnapshot() : preview.sceneSync;
        boolean sceneSyncActive =
            sceneSync.phase != SceneSyncRuntimeState.Phase.IDLE;
        boolean manualApply =
            sceneSync.phase == SceneSyncRuntimeState.Phase.MANUAL_APPLY;
        boolean hasPendingConflicts = sceneSync.pendingConflictCount > 0;
        boolean sceneNeedsAttention = isSceneAttention(sceneSync);

        if (notices != null) {
            if (STATE_STARTUP_FAILED.equals(status)) {
                String message = preview != null ? preview.message
                    : state.getString(KEY_STARTUP_FAILED_MESSAGE, "");
                if (message.isEmpty()) message = context.getString(R.string.notification_startup_failed);
                notices.add(attention(context, "startup", startedAt + "|" + message, 0,
                    context.getString(R.string.notification_startup_failed_title),
                    context.getString(R.string.notification_startup_failed),
                    previewIntent != null ? previewIntent : queuePendingIntent(context)));
            }
            if (STATE_BLOCKED.equals(status) || STATE_ADMISSION_BLOCKED.equals(status)) {
                String message = preview != null ? preview.message
                    : state.getString(KEY_BLOCKED_MESSAGE, "");
                String requestId = preview != null ? "style-preview"
                    : state.getString(KEY_BLOCKED_REQUEST_ID, "");
                if (message.isEmpty()) message = context.getString(R.string.notification_translation_needs_user_action);
                notices.add(attention(context, "blocked", status + "|" + requestId + "|" + scene + "|" + message, 0,
                    context.getString(R.string.notification_attention_title),
                    context.getString(R.string.notification_blocked_action),
                    previewIntent != null ? previewIntent : queuePendingIntent(context, requestId)));
            }
            if (!repairingStartupJobs && (heldQueuedJobCount > 0 || manualRerunCandidateCount > 0)) {
                String message = heldQueuedJobCount > 0 && manualRerunCandidateCount > 0
                    ? context.getString(R.string.notification_recovery_waiting_with_failures,
                        heldQueuedJobCount, manualRerunCandidateCount)
                    : heldQueuedJobCount > 0
                        ? context.getString(R.string.notification_queued_jobs_waiting, heldQueuedJobCount)
                        : context.getString(R.string.notification_failed_jobs_waiting, manualRerunCandidateCount);
                notices.add(attention(context, "queue",
                    (heldQueuedJobCount > 0 ? "held" : "") + (manualRerunCandidateCount > 0 ? "failed" : ""),
                    heldQueuedJobCount + manualRerunCandidateCount,
                    context.getString(R.string.notification_attention_title), message,
                    previewIntent != null ? previewIntent : queuePendingIntent(context)));
            }
            if (!sceneSyncActive && !hasPendingConflicts && sceneNeedsAttention
                && sceneSync.lastOutcome != SceneSyncRuntimeState.Outcome.QUEUED_BEHIND_GATE) {
                String message = context.getString(sceneSync.lastOutcome == SceneSyncRuntimeState.Outcome.NEEDS_ATTENTION
                    ? R.string.notification_scene_sync_attention : R.string.notification_scene_sync_failed);
                notices.add(attention(context, "scene-sync", sceneSync.lastAction + "|" + sceneSync.lastOutcome, 0,
                    context.getString(R.string.notification_scene_attention_title), message,
                    previewIntent != null ? previewIntent : sceneFilesPendingIntent(context)));
            }
            if (mutationFailed) {
                notices.add(attention(context, "scene-mutation", pendingMutationDiagnostic, 0,
                    context.getString(R.string.notification_mutation_failure_title),
                    context.getString(R.string.notification_scene_mutation_pool_failure),
                    previewIntent != null ? previewIntent : sceneFilesPendingIntent(context)));
            }
        }

        PendingIntent scenePageIntent = null;
        String sceneActionTitle = null;
        if (hasPendingConflicts
            && (!sceneSyncActive || manualApply)) {
            scenePageIntent = (previewIntent != null ? previewIntent : sceneConflictsPendingIntent(context));
            sceneActionTitle = context.getString(
                R.string.notification_action_scene_conflicts,
                sceneSync.pendingConflictCount
            );
        } else if (manualApply) {
            scenePageIntent = (previewIntent != null ? previewIntent : sceneConflictsPendingIntent(context));
            sceneActionTitle = context.getString(
                R.string.notification_action_view_scene_conflicts
            );
        } else if (sceneSyncActive || sceneNeedsAttention) {
            scenePageIntent = (previewIntent != null ? previewIntent : sceneFilesPendingIntent(context));
            sceneActionTitle = context.getString(
                R.string.notification_action_view_scene_sync
            );
        }

        if (hasPendingMutationNotice && scenePageIntent == null) {
            scenePageIntent = (previewIntent != null ? previewIntent : sceneFilesPendingIntent(context));
            sceneActionTitle = context.getString(
                R.string.notification_action_view_scene_mutation_pool
            );
        }

        PendingIntent contentIntent;
        if (STATE_STARTUP_FAILED.equals(status)
            || STATE_BLOCKED.equals(status)
            || STATE_ADMISSION_BLOCKED.equals(status)) {
            contentIntent = (previewIntent != null ? previewIntent : queuePendingIntent(context));
        } else if (manualApply
            || (hasPendingConflicts && !sceneSyncActive)) {
            contentIntent = (previewIntent != null ? previewIntent : sceneConflictsPendingIntent(context));
        } else if (sceneSyncActive || sceneNeedsAttention) {
            contentIntent = (previewIntent != null ? previewIntent : sceneFilesPendingIntent(context));
        } else {
            contentIntent = (previewIntent != null ? previewIntent : settingsPendingIntent(context));
        }

        Notification.Builder builder = new Notification.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setColor(context.getColor(R.color.het_primary))
            .setContentTitle(context.getString(
                STATE_STARTUP_FAILED.equals(status)
                    ? R.string.notification_startup_failed_title
                    : R.string.notification_translation_title
            ))
            .setCategory(
                STATE_STARTUP_FAILED.equals(status)
                    ? Notification.CATEGORY_ERROR
                    : Notification.CATEGORY_PROGRESS
            )
            .setVisibility(Notification.VISIBILITY_PRIVATE)
            .setOnlyAlertOnce(true)
            .setContentIntent(contentIntent)
            .addAction(
                R.drawable.ic_notification,
                context.getString(
                    paused
                        ? R.string.notification_action_resume_capture
                        : R.string.notification_action_pause_capture
                ),
                (previewIntent != null ? previewIntent : capturePendingIntent(context))
            );

        String actionableTaskTitle;
        if (manualStartupRepair) {
            actionableTaskTitle = context.getString(
                    R.string.notification_action_view_queue_repair
            );
        } else if (heldQueuedJobCount > 0 || manualRerunCandidateCount > 0) {
            actionableTaskTitle = heldQueuedJobCount > 0
                    && manualRerunCandidateCount > 0
                    ? context.getString(
                        R.string.notification_action_manage_queue_with_failures,
                        heldQueuedJobCount,
                        manualRerunCandidateCount
                    )
                    : heldQueuedJobCount > 0
                        ? context.getString(
                            R.string.notification_action_manage_queue,
                            heldQueuedJobCount
                        )
                        : context.getString(
                            R.string.notification_action_manage_failed_jobs,
                            manualRerunCandidateCount
                        );
        } else if (STATE_STARTUP_FAILED.equals(status)) {
            actionableTaskTitle = context.getString(
                    R.string.notification_action_view_startup_failure
            );
        } else if (STATE_BLOCKED.equals(status) || STATE_ADMISSION_BLOCKED.equals(status)) {
            actionableTaskTitle = context.getString(
                    R.string.notification_action_view_queue_repair
            );
        } else {
            actionableTaskTitle = context.getString(
                R.string.notification_action_view_tasks
            );
        }
        builder.addAction(
            R.drawable.ic_notification,
            actionableTaskTitle,
            (previewIntent != null ? previewIntent : queuePendingIntent(context))
        );
        builder.addAction(
            R.drawable.ic_notification,
            context.getString(R.string.notification_action_view_waiting_results),
            (previewIntent != null ? previewIntent : rejectedApiResultsPendingIntent(context))
        );

        if (STATE_ACTIVE.equals(status) && startedAt > 0L) {
            builder
                .setContentText(context.getString(
                    R.string.notification_translating_scene,
                    scene
                ))
                .setSubText(context.getString(R.string.notification_elapsed_time))
                .setWhen(startedAt)
                .setShowWhen(true)
                .setUsesChronometer(true)
                .setOngoing(true);
        } else {
            builder
                .setShowWhen(false)
                .setUsesChronometer(false)
                .setOngoing(false);

            if (STATE_STARTUP_FAILED.equals(status)) {
                builder.setContentText(context.getString(R.string.notification_startup_failed));
                builder.setSubText(context.getString(
                    R.string.notification_startup_failed_subtitle
                ));
            } else if (STATE_BLOCKED.equals(status) || STATE_ADMISSION_BLOCKED.equals(status)) {
                builder.setContentText(context.getString(R.string.notification_blocked_action));
                builder.setSubText(context.getString(
                    R.string.notification_translation_needs_user_action
                ));
            } else if (sceneSyncActive) {
                builder
                    .setContentText(context.getString(
                        scenePhaseContentResource(sceneSync.phase)
                    ))
                    .setOngoing(true);
            } else if (repairingStartupJobs) {
                builder.setContentText(context.getString(
                    R.string.notification_repairing_damaged_jobs
                ));
            } else if (heldQueuedJobCount > 0
                || manualRerunCandidateCount > 0) {
                builder.setContentText(
                    heldQueuedJobCount > 0 && manualRerunCandidateCount > 0
                        ? context.getString(
                            R.string.notification_recovery_waiting_with_failures,
                            heldQueuedJobCount,
                            manualRerunCandidateCount
                        )
                        : heldQueuedJobCount > 0
                            ? context.getString(
                                R.string.notification_queued_jobs_waiting,
                                heldQueuedJobCount
                            )
                            : context.getString(
                                R.string.notification_failed_jobs_waiting,
                                manualRerunCandidateCount
                            )
                );
            } else if (sceneSync.lastOutcome
                == SceneSyncRuntimeState.Outcome.QUEUED_BEHIND_GATE) {
                builder.setContentText(context.getString(
                    R.string.notification_scene_queued_behind_gate
                ));
            } else if (hasPendingConflicts) {
                builder.setContentText(context.getString(
                    R.string.scene_conflicts_count,
                    sceneSync.pendingConflictCount
                ));
            } else if (sceneNeedsAttention) {
                builder.setContentText(context.getString(
                    sceneSync.lastOutcome
                            == SceneSyncRuntimeState.Outcome.NEEDS_ATTENTION
                        ? R.string.notification_scene_sync_attention
                        : R.string.notification_scene_sync_failed
                ));
            } else if (STATE_SUCCEEDED.equals(status)) {
                builder.setContentText(context.getString(
                    R.string.notification_translation_succeeded,
                    scene
                ));
                builder.setSubText(context.getString(
                    R.string.notification_finished_duration,
                    formatDuration(startedAt, finishedAt)
                ));
            } else if (STATE_FAILED.equals(status)) {
                builder.setContentText(context.getString(
                    R.string.notification_translation_failed,
                    scene
                ));
                builder.setSubText(context.getString(
                    R.string.notification_finished_duration,
                    formatDuration(startedAt, finishedAt)
                ));
            } else {
                builder.setContentText(context.getString(
                    paused
                        ? R.string.notification_capture_paused
                        : R.string.notification_waiting
                ));
            }
        }

        if (forceOngoing) {
            builder.setOngoing(true);
        }

        if (hasPendingMutationNotice) {
            builder.setSubText(mutationFailed
                ? context.getString(R.string.notification_scene_mutation_pool_failure)
                : context.getString(R.string.notification_scene_mutation_pool_pending, pendingMutationCount));
        }

        return builder.build();
    }

    private static int scenePhaseContentResource(
        SceneSyncRuntimeState.Phase phase
    ) {
        switch (phase) {
            case FULL_SYNC:
                return R.string.scene_sync_phase_full_sync;
            case MANUAL_REFRESH:
                return R.string.scene_sync_phase_refresh;
            case MANUAL_APPLY:
                return R.string.scene_sync_phase_manual_apply;
            case IDLE:
            default:
                return R.string.scene_sync_phase_idle;
        }
    }

    private static boolean isSceneAttention(
        SceneSyncRuntimeState.Snapshot snapshot
    ) {
        if (snapshot.lastOutcome != SceneSyncRuntimeState.Outcome.FAILED
            && snapshot.lastOutcome
                != SceneSyncRuntimeState.Outcome.NEEDS_ATTENTION
            && snapshot.lastOutcome
                != SceneSyncRuntimeState.Outcome.QUEUED_BEHIND_GATE) {
            return false;
        }
        switch (snapshot.lastAction) {
            case PORT_REGISTERED:
            case AUTO_SYNC:
            case MANUAL_REFRESH:
            case LOCAL_REFRESH:
            case CHOOSE_GAME:
            case CHOOSE_HET:
                return true;
            case NONE:
            case SERVICE_STARTED:
            case SERVICE_STOPPED:
            case PORT_UNREGISTERED:
            case API_ACTIVITY:
            default:
                return false;
        }
    }

    private static void show(Context context) {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            runOnNotificationThread(() -> show(context));
            return;
        }
        NotificationManager manager = context.getSystemService(NotificationManager.class);
        if (manager == null) {
            Log.w(TAG, "NotificationManager is unavailable");
            return;
        }

        createChannel(context, manager);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
            && context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED) {
            Log.i(TAG, "Notification permission has not been granted");
            return;
        }

        refreshSceneConflictNotification(context, manager);
        List<AttentionNotice> notices = new ArrayList<>();
        Notification notification = buildStatusNotification(context, false, null, null, notices);
        refreshAttentionNotifications(context, manager, notices);
        if (SceneSyncUiVisibility.isSceneSyncUiVisible()) {
            return;
        }

        try {
            manager.notify(NOTIFICATION_ID, notification);
        } catch (SecurityException e) {
            Log.w(TAG, "Could not post translation notification", e);
        }
    }

    private static final class AttentionNotice {
        final String category;
        final String identity;
        final int count;
        final String text;
        final Notification notification;

        AttentionNotice(String category, String identity, int count, String text, Notification notification) {
            this.category = category;
            this.identity = identity;
            this.count = count;
            this.text = text;
            this.notification = notification;
        }
    }

    private static AttentionNotice attention(Context context, String category, String identity,
        int count, String title, String text, PendingIntent intent) {
        Notification notification = Notification.Builder.recoverBuilder(context,
            buildJobErrorNotification(context, title, text,
                context.getString(R.string.notification_attention_subtitle), intent))
            .setStyle(new Notification.BigTextStyle().bigText(text))
            .setOnlyAlertOnce(false)
            .build();
        return new AttentionNotice(category, identity, count, text, notification);
    }

    private static void refreshAttentionNotifications(Context context, NotificationManager manager,
        List<AttentionNotice> notices) {
        createJobErrorChannel(context, manager);
        NotificationChannel channel = manager.getNotificationChannel(JOB_ERROR_CHANNEL_ID);
        if (!manager.areNotificationsEnabled()
            || (channel != null && channel.getImportance() == NotificationManager.IMPORTANCE_NONE)) return;

        Map<String, AttentionNotice> current = new HashMap<>();
        for (AttentionNotice notice : notices) current.put(notice.category, notice);
        for (String category : new ArrayList<>(notifiedAttention.keySet())) {
            if (!current.containsKey(category)) {
                manager.cancel(ATTENTION_TAG_PREFIX + category, 1);
                notifiedAttention.remove(category);
            }
        }
        for (AttentionNotice notice : notices) {
            AttentionNotice previous = notifiedAttention.get(notice.category);
            boolean newProblem = previous == null || !previous.identity.equals(notice.identity)
                || notice.count > previous.count;
            // A dismissed alert stays dismissed until the problem changes or first resolves.
            if (!newProblem && previous.text.equals(notice.text)) continue;
            Notification notification = Notification.Builder.recoverBuilder(context, notice.notification)
                .setOnlyAlertOnce(!newProblem)
                .build();
            try {
                manager.notify(ATTENTION_TAG_PREFIX + notice.category, 1, notification);
                notifiedAttention.put(notice.category, notice);
            } catch (RuntimeException error) {
                Log.w(TAG, "Could not post attention notification category=" + notice.category, error);
            }
        }
    }

    private static void refreshSceneConflictNotification(
        Context context,
        NotificationManager manager
    ) {
        SceneSyncRuntimeState.Snapshot snapshot =
            SceneSyncRuntimeState.getInstance().getSnapshot();
        // Do not treat the temporary startup/offline snapshot as resolution.
        if (!snapshot.serviceAvailable) {
            return;
        }
        int count = snapshot.pendingConflictCount;
        if (count == 0 || SceneSyncUiVisibility.isSceneSyncUiVisible()) {
            manager.cancel(SCENE_CONFLICT_NOTIFICATION_ID);
            notifiedSceneConflictCount = count;
            return;
        }
        if (count == notifiedSceneConflictCount) {
            return;
        }
        createJobErrorChannel(context, manager);
        Notification notification = buildSceneConflictNotification(context, count, count <= notifiedSceneConflictCount, sceneConflictsPendingIntent(context));
        try {
            manager.notify(SCENE_CONFLICT_NOTIFICATION_ID, notification);
            notifiedSceneConflictCount = count;
        } catch (SecurityException e) {
            Log.w(TAG, "Could not post Scene conflict notification", e);
        }
    }

    private static void createJobErrorChannel(
        Context context,
        NotificationManager manager
    ) {
        NotificationChannel channel = new NotificationChannel(
            JOB_ERROR_CHANNEL_ID,
            context.getString(R.string.notification_job_errors_channel_name),
            NotificationManager.IMPORTANCE_HIGH
        );
        channel.setDescription(context.getString(
            R.string.notification_job_errors_channel_description
        ));
        channel.setShowBadge(false);
        manager.createNotificationChannel(channel);
    }

    private static void createChannel(
        Context context,
        NotificationManager manager
    ) {
        NotificationChannel channel = new NotificationChannel(
            CHANNEL_ID,
            context.getString(R.string.notification_channel_name),
            NotificationManager.IMPORTANCE_LOW
        );
        channel.setDescription(context.getString(R.string.notification_channel_description));
        channel.setShowBadge(false);
        manager.createNotificationChannel(channel);
    }

    private static PendingIntent settingsPendingIntent(Context context) {
        Intent intent = new Intent(context, SettingsActivity.class)
            .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        return PendingIntent.getActivity(
            context,
            SETTINGS_REQUEST_CODE,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE
        );
    }

    private static PendingIntent sceneFilesPendingIntent(Context context) {
        Intent intent = new Intent(context, SceneFilesActivity.class)
            .addFlags(
                Intent.FLAG_ACTIVITY_CLEAR_TOP
                    | Intent.FLAG_ACTIVITY_SINGLE_TOP
            );
        return PendingIntent.getActivity(
            context,
            SCENE_FILES_REQUEST_CODE,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT
                | PendingIntent.FLAG_IMMUTABLE
        );
    }

    private static PendingIntent sceneConflictsPendingIntent(Context context) {
        Intent intent = new Intent(context, SceneConflictsActivity.class)
            .addFlags(
                Intent.FLAG_ACTIVITY_CLEAR_TOP
                    | Intent.FLAG_ACTIVITY_SINGLE_TOP
            );
        return PendingIntent.getActivity(
            context,
            SCENE_CONFLICTS_REQUEST_CODE,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT
                | PendingIntent.FLAG_IMMUTABLE
        );
    }

    private static PendingIntent sceneContextReviewPendingIntent(Context context) {
        Intent intent = new Intent(context, SceneContextActivity.class)
            .putExtra(SceneContextActivity.EXTRA_REVIEW_MODE, true)
            .addFlags(
                Intent.FLAG_ACTIVITY_CLEAR_TOP
                    | Intent.FLAG_ACTIVITY_SINGLE_TOP
            );
        return PendingIntent.getActivity(
            context,
            SCENE_CONTEXT_REVIEW_REQUEST_CODE,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT
                | PendingIntent.FLAG_IMMUTABLE
        );
    }

    private static PendingIntent rejectedApiResultsPendingIntent(Context context) {
        return rejectedApiResultsPendingIntent(context, null);
    }

    private static PendingIntent rejectedApiResultsPendingIntent(
        Context context,
        String recordId
    ) {
        Intent intent = new Intent(context, TranslationQueueActivity.class)
            .putExtra(
                TranslationQueueActivity.EXTRA_MANAGEMENT_ONLY,
                true
            )
            .addFlags(
                Intent.FLAG_ACTIVITY_REORDER_TO_FRONT
                    | Intent.FLAG_ACTIVITY_SINGLE_TOP
            );
        if (recordId != null && !recordId.trim().isEmpty()) {
            intent.putExtra(
                TranslationQueueActivity.EXTRA_NOTIFICATION_REJECTED_RECORD_ID,
                recordId
            );
            intent.setData(notificationIdentityUri(
                "record",
                "rejected_api_result",
                recordId
            ));
        }
        return PendingIntent.getActivity(
            context,
            REJECTED_API_RESULTS_REQUEST_CODE
                ^ (recordId == null ? 0 : recordId.hashCode()),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT
                | PendingIntent.FLAG_IMMUTABLE
        );
    }

    private static PendingIntent capturePendingIntent(Context context) {
        boolean desiredPaused = !RuntimeControlStore.isCapturePaused(context);
        Intent intent = new Intent(context, TranslationQueueActivity.class)
            .putExtra(
                TranslationQueueActivity.EXTRA_NOTIFICATION_CAPTURE_CONTROL,
                true
            )
            .putExtra(
                TranslationQueueActivity.EXTRA_NOTIFICATION_CAPTURE_DESIRED_PAUSED,
                desiredPaused
            )
            .addFlags(
                Intent.FLAG_ACTIVITY_REORDER_TO_FRONT
                    | Intent.FLAG_ACTIVITY_SINGLE_TOP
            );
        return PendingIntent.getActivity(
            context,
            CAPTURE_REQUEST_CODE,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE
        );
    }

    private static PendingIntent queuePendingIntent(Context context) {
        return queuePendingIntent(context, null);
    }

    private static PendingIntent queuePendingIntent(
        Context context,
        String requestId
    ) {
        return queuePendingIntent(context, requestId, "queue");
    }

    private static PendingIntent queuePendingIntent(
        Context context,
        String requestId,
        String failureType
    ) {
        Intent intent = new Intent(context, TranslationQueueActivity.class)
            .addFlags(
                Intent.FLAG_ACTIVITY_REORDER_TO_FRONT
                    | Intent.FLAG_ACTIVITY_SINGLE_TOP
            );
        if (requestId != null && !requestId.trim().isEmpty()) {
            intent.putExtra(
                TranslationQueueActivity.EXTRA_NOTIFICATION_REQUEST_ID,
                requestId
            );
            intent.setData(notificationIdentityUri(
                "request",
                failureType,
                requestId
            ));
        }
        return PendingIntent.getActivity(
            context,
            QUEUE_REQUEST_CODE
                ^ (requestId == null ? 0 : requestId.hashCode()),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT
                | PendingIntent.FLAG_IMMUTABLE
        );
    }

    private static Uri notificationIdentityUri(
        String identityType,
        String category,
        String id
    ) {
        return new Uri.Builder()
            .scheme(NOTIFICATION_INTENT_SCHEME)
            .authority("notification")
            .appendPath(identityType)
            .appendPath(category == null ? "unknown" : category)
            .appendPath(id)
            .build();
    }

    private static String formatDuration(long startedAt, long finishedAt) {
        long elapsedSeconds = startedAt <= 0L
            ? 0L
            : Math.max(0L, finishedAt - startedAt) / 1_000L;
        return DateUtils.formatElapsedTime(elapsedSeconds);
    }

    private static SharedPreferences state(Context context) {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
    }

    public static Notification buildForeground(Context context) {
        Context appContext = context.getApplicationContext();

        NotificationManager manager = appContext.getSystemService(NotificationManager.class);

        if (manager == null) {
            throw new IllegalStateException("NotificationManager is unavailable");
        }

        createChannel(appContext, manager);

        return buildStatusNotification(appContext, true);
    }
}
