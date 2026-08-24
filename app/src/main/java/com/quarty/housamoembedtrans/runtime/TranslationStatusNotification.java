package com.quarty.housamoembedtrans.runtime;

import com.quarty.housamoembedtrans.R;
import com.quarty.housamoembedtrans.ui.SceneConflictsActivity;
import com.quarty.housamoembedtrans.ui.SceneContextActivity;
import com.quarty.housamoembedtrans.ui.SceneFilesActivity;
import com.quarty.housamoembedtrans.ui.SettingsActivity;
import com.quarty.housamoembedtrans.ui.TranslationQueueActivity;
import com.quarty.housamoembedtrans.ui.RejectedApiResultsActivity;
import com.quarty.housamoembedtrans.translation.ContextReviewGate;
import com.quarty.housamoembedtrans.translation.TranslationJobStore;
import com.quarty.housamoembedtrans.storage.SceneStore;

import android.Manifest;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.os.Build;
import android.text.format.DateUtils;
import android.util.Log;

import org.json.JSONObject;

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
    private static final String JOB_ERROR_CHANNEL_ID = "job_errors";
    public static final int NOTIFICATION_ID = 0x484554;
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

    private static final String PREFS_NAME = "translation_notification_state";
    private static final String KEY_STATE = "state";
    private static final String KEY_SCENE = "scene";
    private static final String KEY_STARTED_AT = "started_at";
    private static final String KEY_FINISHED_AT = "finished_at";

    private static final String STATE_IDLE = "idle";
    private static final String STATE_ACTIVE = "active";
    private static final String STATE_SUCCEEDED = "succeeded";
    private static final String STATE_FAILED = "failed";
    private static final String STATE_BLOCKED = "blocked";
    private static final String STATE_STARTUP_FAILED = "startup_failed";

    private static final String KEY_BLOCKED_MESSAGE = "blocked_message";
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

    /**
     * Shows a dedicated user-visible failure state for a failed linear
     * startup coordinator run. This is distinct from a per-job translation
     * failure and keeps an actionable jump into the queue.
     */
    public static void startupFailed(Context context, String message) {
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
        String sceneName
    ) {
        update(context, sceneName, STATUS_STARTED);
    }

    public static void translationSucceeded(
        Context context,
        String sceneName
    ) {
        update(context, sceneName, STATUS_SUCCEEDED);
    }

    public static void translationFailed(
        Context context,
        String sceneName
    ) {
        update(context, sceneName, STATUS_FAILED);
    }

    /**
     * Shows a non-terminal user-action-required status. The job remains
     * unsent and is never represented as a translation failure.
     */
    public static void translationNeedsUserAction(
        Context context,
        String sceneName,
        String message
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
            .putString(KEY_STATE, STATE_BLOCKED)
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
        String title = ownerId == null || ownerId.trim().isEmpty()
            ? appContext.getString(R.string.notification_summary_failed_title)
            : ownerId;
        String text = message == null || message.trim().isEmpty()
            ? appContext.getString(R.string.notification_summary_failed_generic)
            : message;
        postJobErrorNotification(
            appContext,
            requestId,
            title,
            text,
            appContext.getString(
                R.string.notification_summary_failed_subtitle,
                ownerType == null ? "" : ownerType
            ),
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
        String title = scene == null || scene.trim().isEmpty()
            ? appContext.getString(R.string.notification_translation_failed_title)
            : scene;
        String text = message == null || message.trim().isEmpty()
            ? appContext.getString(R.string.notification_translation_failed_generic)
            : message;
        postJobErrorNotification(
            appContext,
            requestId,
            title,
            text,
            appContext.getString(
                R.string.notification_translation_failed_subtitle
            ),
            SUMMARY_ERROR_NOTIFICATION_BASE + 1000,
            "translation"
        );
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

        Notification notification = new Notification.Builder(
            context,
            JOB_ERROR_CHANNEL_ID
        )
            .setSmallIcon(R.drawable.ic_notification)
            .setColor(context.getColor(R.color.het_primary))
            .setContentTitle(title)
            .setContentText(text)
            .setSubText(subText)
            .setContentIntent(queuePendingIntent(context))
            .setAutoCancel(true)
            .setOnlyAlertOnce(true)
            .setCategory(Notification.CATEGORY_ERROR)
            .setVisibility(Notification.VISIBILITY_PRIVATE)
            .build();

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
        String reason = record.optString("reason", "unknown");
        String text = appContext.getString(
            R.string.notification_rejected_api_result_text,
            jobKind,
            requestId,
            reason
        );
        Notification notification = new Notification.Builder(
            appContext,
            JOB_ERROR_CHANNEL_ID
        )
            .setSmallIcon(R.drawable.ic_notification)
            .setColor(appContext.getColor(R.color.het_primary))
            .setContentTitle(appContext.getString(
                R.string.notification_rejected_api_result_title
            ))
            .setContentText(text)
            .setSubText(appContext.getString(
                R.string.notification_rejected_api_result_subtitle
            ))
            .setContentIntent(rejectedApiResultsPendingIntent(appContext))
            .setAutoCancel(true)
            .setOnlyAlertOnce(true)
            .setCategory(Notification.CATEGORY_ERROR)
            .setVisibility(Notification.VISIBILITY_PRIVATE)
            .build();
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
        Notification notification = new Notification.Builder(
            appContext,
            JOB_ERROR_CHANNEL_ID
        )
            .setSmallIcon(R.drawable.ic_notification)
            .setColor(appContext.getColor(R.color.het_primary))
            .setContentTitle(appContext.getString(
                R.string.notification_scene_rejected_title
            ))
            .setContentText(appContext.getString(textResource, sceneName))
            .setContentIntent(contentIntent)
            .setAutoCancel(true)
            .setOnlyAlertOnce(true)
            .setCategory(Notification.CATEGORY_ERROR)
            .setVisibility(Notification.VISIBILITY_PRIVATE)
            .build();
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

    private static int stableNotificationId(int base, String identity) {
        return base ^ (identity == null ? 0 : identity.hashCode());
    }

    private static void update(
        Context context,
        String sceneName,
        int status
    ) {
        if (sceneName == null || sceneName.trim().isEmpty()) {
            Log.w(TAG, "Ignoring translation status with an empty scene name");
            return;
        }

        Context appContext = context.getApplicationContext();
        SharedPreferences state = state(appContext);
        long now = System.currentTimeMillis();
        SharedPreferences.Editor editor = state.edit().putString(KEY_SCENE, sceneName);

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

    private static Notification buildStatusNotification(Context context, boolean forceOngoing) {
        SharedPreferences state = state(context);
        String status = state.getString(KEY_STATE, STATE_IDLE);
        String scene = state.getString(KEY_SCENE, "");
        long startedAt = state.getLong(KEY_STARTED_AT, 0L);
        long finishedAt = state.getLong(KEY_FINISHED_AT, System.currentTimeMillis());
        boolean paused = RuntimeControlStore.isCapturePaused(context);
        TranslationJobStore jobStore = statusJobStore;
        SceneStore sceneStore = statusSceneStore;
        // The notification may be rebuilt on the service/main thread.  Read
        // only the store's O(1) in-memory snapshot; durable candidate scans
        // belong to startup repair or the queue activity's I/O executor.
        // Before the background startup coordinator builds the store, keep the
        // foreground notification lightweight and avoid constructing it here.
        int heldQueuedJobCount = jobStore == null
            ? 0
            : jobStore.getHeldQueuedJobCount();
        int manualRerunCandidateCount = jobStore == null
            ? 0
            : jobStore.getManualRerunCandidateCount();
        int pendingMutationCount = sceneStore == null
            ? 0
            : sceneStore.getDeferredMutationCountSnapshot();
        String pendingMutationDiagnostic = sceneStore == null
            ? ""
            : sceneStore.getDeferredMutationDiagnosticSnapshot();
        boolean hasPendingMutationNotice = pendingMutationCount > 0
            || !pendingMutationDiagnostic.trim().isEmpty();
        boolean repairingStartupJobs = jobStore != null
            && jobStore.isRepairingStartupJobs();
        boolean manualStartupRepair = jobStore != null
            && jobStore.isManualStartupRepairInProgress();
        SceneSyncRuntimeState.Snapshot sceneSync =
            SceneSyncRuntimeState.getInstance().getSnapshot();
        boolean sceneSyncActive =
            sceneSync.phase != SceneSyncRuntimeState.Phase.IDLE;
        boolean manualApply =
            sceneSync.phase == SceneSyncRuntimeState.Phase.MANUAL_APPLY;
        boolean hasPendingConflicts = sceneSync.pendingConflictCount > 0;
        boolean sceneNeedsAttention = isSceneAttention(sceneSync);

        PendingIntent scenePageIntent = null;
        String sceneActionTitle = null;
        if (hasPendingConflicts
            && (!sceneSyncActive || manualApply)) {
            scenePageIntent = sceneConflictsPendingIntent(context);
            sceneActionTitle = context.getString(
                R.string.notification_action_scene_conflicts,
                sceneSync.pendingConflictCount
            );
        } else if (manualApply) {
            scenePageIntent = sceneConflictsPendingIntent(context);
            sceneActionTitle = context.getString(
                R.string.notification_action_view_scene_conflicts
            );
        } else if (sceneSyncActive || sceneNeedsAttention) {
            scenePageIntent = sceneFilesPendingIntent(context);
            sceneActionTitle = context.getString(
                R.string.notification_action_view_scene_sync
            );
        }

        if (hasPendingMutationNotice && scenePageIntent == null) {
            scenePageIntent = sceneFilesPendingIntent(context);
            sceneActionTitle = context.getString(
                R.string.notification_action_view_scene_mutation_pool
            );
        }

        PendingIntent contentIntent;
        if (STATE_STARTUP_FAILED.equals(status)
            || STATE_BLOCKED.equals(status)) {
            contentIntent = queuePendingIntent(context);
        } else if (manualApply
            || (hasPendingConflicts && !sceneSyncActive)) {
            contentIntent = sceneConflictsPendingIntent(context);
        } else if (sceneSyncActive || sceneNeedsAttention) {
            contentIntent = sceneFilesPendingIntent(context);
        } else {
            contentIntent = settingsPendingIntent(context);
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
                capturePendingIntent(context)
            );

        if (ContextReviewGate.get().isPending()) {
            builder.addAction(
                R.drawable.ic_notification,
                context.getString(
                    R.string.notification_action_review_contexts
                ),
                sceneContextReviewPendingIntent(context)
            );
        }

        if (manualStartupRepair) {
            builder.addAction(
                R.drawable.ic_notification,
                context.getString(
                    R.string.notification_action_view_queue_repair
                ),
                queuePendingIntent(context)
            );
        } else if (heldQueuedJobCount > 0 || manualRerunCandidateCount > 0) {
            builder.addAction(
                R.drawable.ic_notification,
                heldQueuedJobCount > 0 && manualRerunCandidateCount > 0
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
                        ),
                queuePendingIntent(context)
            );
        }
        if (STATE_STARTUP_FAILED.equals(status)) {
            builder.addAction(
                R.drawable.ic_notification,
                context.getString(
                    R.string.notification_action_view_startup_failure
                ),
                queuePendingIntent(context)
            );
        }
        if (STATE_BLOCKED.equals(status)) {
            builder.addAction(
                R.drawable.ic_notification,
                context.getString(
                    R.string.notification_action_view_queue_repair
                ),
                queuePendingIntent(context)
            );
        }
        if (scenePageIntent != null) {
            builder.addAction(
                R.drawable.ic_notification,
                sceneActionTitle,
                scenePageIntent
            );
        }

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
                String startupFailedMessage = state.getString(
                    KEY_STARTUP_FAILED_MESSAGE,
                    context.getString(R.string.notification_startup_failed)
                );
                builder.setContentText(
                    startupFailedMessage.isEmpty()
                        ? context.getString(R.string.notification_startup_failed)
                        : startupFailedMessage
                );
                builder.setSubText(context.getString(
                    R.string.notification_startup_failed_subtitle
                ));
            } else if (STATE_BLOCKED.equals(status)) {
                String blockedMessage = state.getString(
                    KEY_BLOCKED_MESSAGE,
                    context.getString(R.string.notification_waiting)
                );
                builder.setContentText(blockedMessage);
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
                        ? R.string.scene_files_refresh_needs_attention
                        : R.string.scene_files_refresh_failed
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
            builder.setSubText(context.getString(
                pendingMutationCount > 0
                    ? R.string.notification_scene_mutation_pool_pending
                    : R.string.notification_scene_mutation_pool_failure,
                pendingMutationCount,
                pendingMutationDiagnostic
            ));
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
        if (SceneSyncUiVisibility.isSceneSyncUiVisible()) {
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

        Notification notification = buildStatusNotification(context, false);

        try {
            manager.notify(NOTIFICATION_ID, notification);
        } catch (SecurityException e) {
            Log.w(TAG, "Could not post translation notification", e);
        }
    }

    private static void createJobErrorChannel(
        Context context,
        NotificationManager manager
    ) {
        NotificationChannel channel = new NotificationChannel(
            JOB_ERROR_CHANNEL_ID,
            context.getString(R.string.notification_job_errors_channel_name),
            NotificationManager.IMPORTANCE_DEFAULT
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
        Intent intent = new Intent(context, RejectedApiResultsActivity.class)
            .addFlags(
                Intent.FLAG_ACTIVITY_CLEAR_TOP
                    | Intent.FLAG_ACTIVITY_SINGLE_TOP
            );
        return PendingIntent.getActivity(
            context,
            REJECTED_API_RESULTS_REQUEST_CODE,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT
                | PendingIntent.FLAG_IMMUTABLE
        );
    }

    private static PendingIntent capturePendingIntent(Context context) {
        Intent intent = new Intent(context, TranslationControlReceiver.class)
            .setAction(ACTION_TOGGLE_CAPTURE);
        return PendingIntent.getBroadcast(
            context,
            CAPTURE_REQUEST_CODE,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE
        );
    }

    private static PendingIntent queuePendingIntent(Context context) {
        Intent intent = new Intent(context, TranslationQueueActivity.class)
            .addFlags(
                Intent.FLAG_ACTIVITY_CLEAR_TOP
                    | Intent.FLAG_ACTIVITY_SINGLE_TOP
            );
        return PendingIntent.getActivity(
            context,
            QUEUE_REQUEST_CODE,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT
                | PendingIntent.FLAG_IMMUTABLE
        );
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
