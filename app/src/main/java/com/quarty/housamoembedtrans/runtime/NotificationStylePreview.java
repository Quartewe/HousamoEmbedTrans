package com.quarty.housamoembedtrans.runtime;

import android.Manifest;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;

import com.quarty.housamoembedtrans.R;
import com.quarty.housamoembedtrans.ui.StylePreviewActivity;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Manual visual samples only. Never runs a job, writes a Store, or changes live status. */
public final class NotificationStylePreview {
    private static final String TAG_PREFIX = "het-style-notification:";
    private static final int NOTIFICATION_ID = 1;

    private enum Kind { STATUS, ERROR, SUMMARY, RETRY, REJECTED, SCENE_REJECTION, CONFLICT, PROPOSED }

    public enum Sample {
        IDLE(R.string.notification_preview_idle, Kind.STATUS),
        PAUSED(R.string.notification_preview_paused, Kind.STATUS),
        ACTIVE(R.string.notification_preview_active, Kind.STATUS),
        SUCCEEDED(R.string.notification_preview_succeeded, Kind.STATUS),
        FAILED(R.string.notification_preview_failed, Kind.STATUS),
        STARTUP_FAILED(R.string.notification_preview_startup_failed, Kind.STATUS),
        BLOCKED(R.string.notification_preview_blocked, Kind.STATUS),
        ADMISSION_BLOCKED(R.string.notification_preview_admission_blocked, Kind.STATUS),
        STARTUP_REPAIR(R.string.notification_preview_startup_repair, Kind.STATUS),
        MANUAL_REPAIR(R.string.notification_preview_manual_repair, Kind.STATUS),
        HELD(R.string.notification_preview_held, Kind.STATUS),
        FAILED_WAITING(R.string.notification_preview_failed_waiting, Kind.STATUS),
        HELD_AND_FAILED(R.string.notification_preview_held_and_failed, Kind.STATUS),
        SYNC_FULL(R.string.notification_preview_sync_full, Kind.STATUS),
        SYNC_REFRESH(R.string.notification_preview_sync_refresh, Kind.STATUS),
        SYNC_APPLY(R.string.notification_preview_sync_apply, Kind.STATUS),
        SYNC_QUEUED(R.string.notification_preview_sync_queued, Kind.STATUS),
        CONFLICT_WAITING(R.string.notification_preview_conflict_waiting, Kind.STATUS),
        SYNC_FAILED(R.string.notification_preview_sync_failed, Kind.STATUS),
        SYNC_ATTENTION(R.string.notification_preview_sync_attention, Kind.STATUS),
        MUTATION_PENDING(R.string.notification_preview_mutation_pending, Kind.STATUS),
        MUTATION_FAILED(R.string.notification_preview_mutation_failed, Kind.STATUS),
        TRANSLATION_FAILURE(R.string.notification_preview_translation_failure, Kind.ERROR),
        SUMMARY_CONTEXT(R.string.notification_preview_summary_context, Kind.SUMMARY),
        SUMMARY_GROUP(R.string.notification_preview_summary_group, Kind.SUMMARY),
        RETRY_HTTP(R.string.notification_preview_retry_http, Kind.RETRY),
        RETRY_TIMEOUT(R.string.notification_preview_retry_timeout, Kind.RETRY),
        RETRY_NETWORK(R.string.notification_preview_retry_network, Kind.RETRY),
        RETRY_FORMAT(R.string.notification_preview_retry_format, Kind.RETRY),
        REJECTED_TRANSLATION(R.string.notification_preview_rejected_translation, Kind.REJECTED),
        REJECTED_SUMMARY(R.string.notification_preview_rejected_summary, Kind.REJECTED),
        REJECT_SYNC(R.string.notification_preview_reject_sync, Kind.SCENE_REJECTION),
        REJECT_CONFLICT(R.string.notification_preview_reject_conflict, Kind.SCENE_REJECTION),
        REJECT_UNSYNCED(R.string.notification_preview_reject_unsynced, Kind.SCENE_REJECTION),
        CONFLICT_ALERT(R.string.notification_preview_conflict_alert, Kind.CONFLICT),
        PERSIST_FAILURE(R.string.notification_preview_persist_failure, Kind.PROPOSED, R.string.notification_preview_persist_failure_body, R.string.notification_preview_persist_failure_brief),
        CLAIM_FAILURE(R.string.notification_preview_claim_failure, Kind.PROPOSED, R.string.notification_preview_claim_failure_body, R.string.notification_preview_claim_failure_brief),
        LOCAL_SAVE_FAILURE(R.string.notification_preview_local_save_failure, Kind.PROPOSED, R.string.notification_preview_local_save_failure_body, R.string.notification_preview_local_save_failure_brief),
        UNSENT_FAILURE(R.string.notification_preview_unsent_failure, Kind.PROPOSED, R.string.notification_preview_unsent_failure_body, R.string.notification_preview_unsent_failure_brief),
        PARAMETER_FAILURE(R.string.notification_preview_parameter_failure, Kind.PROPOSED, R.string.notification_preview_parameter_failure_body, R.string.notification_preview_parameter_failure_brief),
        AUTH_FAILURE(R.string.notification_preview_auth_failure, Kind.PROPOSED, R.string.notification_preview_auth_failure_body, R.string.notification_preview_auth_failure_brief),
        QUOTA_FAILURE(R.string.notification_preview_quota_failure, Kind.PROPOSED, R.string.notification_preview_quota_failure_body, R.string.notification_preview_quota_failure_brief),
        SERVICE_FAILURE(R.string.notification_preview_service_failure, Kind.PROPOSED, R.string.notification_preview_service_failure_body, R.string.notification_preview_service_failure_brief),
        OUTPUT_LIMIT(R.string.notification_preview_output_limit, Kind.PROPOSED, R.string.notification_preview_output_limit_body, R.string.notification_preview_output_limit_brief),
        RESPONSE_DETAIL(R.string.notification_preview_response_detail, Kind.PROPOSED, R.string.notification_preview_response_detail_body, R.string.notification_preview_response_detail_brief),
        PROVIDER_ERROR(R.string.notification_preview_provider_error, Kind.PROPOSED, R.string.notification_preview_provider_error_body, R.string.notification_preview_provider_error_brief),
        SUMMARY_RETRY(R.string.notification_preview_summary_retry, Kind.PROPOSED, R.string.notification_preview_summary_retry_body, R.string.notification_preview_summary_retry_brief),
        REPAIR_RESULT_RETRY(R.string.notification_preview_repair_result_retry, Kind.PROPOSED, R.string.notification_preview_repair_result_retry_body, R.string.notification_preview_repair_result_retry_brief),
        LONG_WAIT(R.string.notification_preview_long_wait, Kind.PROPOSED, R.string.notification_preview_long_wait_body, R.string.notification_preview_long_wait_brief);

        public final int label;
        private final Kind kind;
        private final int body;
        private final int brief;

        Sample(int label, Kind kind) { this(label, kind, 0, 0); }
        Sample(int label, Kind kind, int body, int brief) {
            this.label = label;
            this.kind = kind;
            this.body = body;
            this.brief = brief;
        }

        public boolean isProposed() { return kind == Kind.PROPOSED; }
    }

    private NotificationStylePreview() {}

    public static List<Sample> samples(boolean proposed) {
        List<Sample> result = new ArrayList<>();
        for (Sample sample : Sample.values()) {
            if (sample.isProposed() == proposed) result.add(sample);
        }
        return Collections.unmodifiableList(result);
    }

    /** Returns only after NotificationManager accepted the call, not proof of a visible banner. */
    public static void post(Context context, Sample sample) {
        NotificationManager manager = context.getSystemService(NotificationManager.class);
        if (manager == null) throw new IllegalStateException(context.getString(R.string.notification_preview_no_manager));
        if ((Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
            && context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED) || !manager.areNotificationsEnabled()) {
            throw new IllegalStateException(context.getString(R.string.notification_preview_disabled));
        }
        TranslationStatusNotification.preparePreviewChannels(context, manager);
        PendingIntent intent = PendingIntent.getActivity(context, 0,
            new Intent(context, StylePreviewActivity.class)
                .setData(Uri.parse("housamoembedtrans://notification-style/" + sample.name()))
                .putExtra(StylePreviewActivity.EXTRA_NOTIFICATION_PREVIEW, true)
                .putExtra(StylePreviewActivity.EXTRA_NOTIFICATION_PROPOSED, sample.isProposed())
                .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        Notification notification = build(context, sample, intent);
        NotificationChannel channel = manager.getNotificationChannel(notification.getChannelId());
        if (channel != null && channel.getImportance() == NotificationManager.IMPORTANCE_NONE) {
            throw new IllegalStateException(context.getString(R.string.notification_preview_channel_disabled, channel.getName()));
        }
        // Keep real templates/channels, but clearly identify the sample in the system tray.
        notification = Notification.Builder.recoverBuilder(context, notification)
            .setContentTitle(context.getString(R.string.notification_preview_title_prefix,
                notification.extras.getCharSequence(Notification.EXTRA_TITLE, "")))
            .build();
        String tag = TAG_PREFIX + sample.name();
        manager.cancel(tag, NOTIFICATION_ID); // Allow the same sample to alert on each manual tap.
        manager.notify(tag, NOTIFICATION_ID, notification);
    }

    public static void clear(Context context) {
        NotificationManager manager = context.getSystemService(NotificationManager.class);
        if (manager == null) return;
        for (Sample sample : Sample.values()) manager.cancel(TAG_PREFIX + sample.name(), NOTIFICATION_ID);
    }

    private static Notification build(Context context, Sample sample, PendingIntent intent) {
        String scene = context.getString(R.string.notification_preview_scene);
        switch (sample.kind) {
            case STATUS:
                return TranslationStatusNotification.buildStatusPreviewNotification(
                    context, status(context, sample), intent);
            case RETRY:
                int phase = sample == Sample.RETRY_NETWORK ? R.string.notification_api_repair_phase
                    : sample == Sample.RETRY_FORMAT ? R.string.notification_api_format_phase
                    : R.string.notification_api_main_phase;
                String reason = sample == Sample.RETRY_HTTP ? "HTTP 429"
                    : context.getString(sample == Sample.RETRY_TIMEOUT ? R.string.notification_api_timeout
                        : sample == Sample.RETRY_NETWORK ? R.string.notification_api_network_error
                        : R.string.notification_api_response_invalid);
                return TranslationStatusNotification.buildRetryNotification(context, scene,
                    context.getString(R.string.notification_api_retry_text,
                        context.getString(phase), reason, 1, 3), intent);
            case SUMMARY:
                return TranslationStatusNotification.buildJobErrorNotification(context,
                    TranslationStatusNotification.summaryFailureTitle(context,
                        sample == Sample.SUMMARY_CONTEXT ? "context" : "group"),
                    TranslationStatusNotification.failureText(context,
                        context.getString(R.string.notification_preview_summary_error),
                        R.string.notification_summary_failed_generic),
                    scene, intent);
            case REJECTED:
                return TranslationStatusNotification.buildRejectedResultNotification(context,
                    TranslationStatusNotification.rejectedResultText(context,
                        sample == Sample.REJECTED_TRANSLATION ? "translation" : "summary"), intent);
            case SCENE_REJECTION:
                return TranslationStatusNotification.buildSceneRejectionNotification(context, scene,
                    sample == Sample.REJECT_SYNC ? R.string.notification_scene_rejected_sync_active
                        : sample == Sample.REJECT_CONFLICT ? R.string.notification_scene_rejected_conflict
                        : R.string.notification_scene_rejected_not_synced, intent);
            case CONFLICT:
                return TranslationStatusNotification.buildSceneConflictNotification(context, 3, false, intent);
            case PROPOSED:
                return Notification.Builder.recoverBuilder(context,
                    TranslationStatusNotification.buildJobErrorNotification(context,
                        context.getString(sample.label), context.getString(sample.brief),
                        context.getString(R.string.notification_preview_proposed_subtitle), intent))
                    .setStyle(new Notification.BigTextStyle().bigText(context.getString(sample.body)))
                    .build();
            case ERROR:
            default:
                // Feed the real diagnostic shape through the same localized presentation path.
                return TranslationStatusNotification.buildJobErrorNotification(context,
                    context.getString(R.string.notification_translation_failed_title),
                    TranslationStatusNotification.failureText(context,
                        "{\"type\":\"network\",\"status\":0,\"message\":\"main stream exhausted network retries: HTTP 400: Range of max_tokens should be [1, 131072]\"}",
                        R.string.notification_translation_failed_generic),
                    scene, intent);
        }
    }

    private static TranslationStatusNotification.PreviewStatus status(Context context, Sample sample) {
        TranslationStatusNotification.PreviewStatus value = new TranslationStatusNotification.PreviewStatus();
        value.scene = context.getString(R.string.notification_preview_scene);
        value.message = context.getString(sample == Sample.STARTUP_FAILED
            ? R.string.notification_preview_startup_error : R.string.notification_preview_blocked_message);
        SceneSyncRuntimeState.Phase phase = SceneSyncRuntimeState.Phase.IDLE;
        SceneSyncRuntimeState.Outcome outcome = SceneSyncRuntimeState.Outcome.NONE;
        int conflicts = 0;
        switch (sample) {
            case PAUSED: value.paused = true; break;
            case ACTIVE: value.status = "active"; break;
            case SUCCEEDED: value.status = "succeeded"; break;
            case FAILED: value.status = "failed"; break;
            case STARTUP_FAILED: value.status = "startup_failed"; break;
            case BLOCKED: value.status = "blocked"; break;
            case ADMISSION_BLOCKED: value.status = "admission_blocked"; break;
            case STARTUP_REPAIR: value.repairingStartupJobs = true; break;
            case MANUAL_REPAIR:
                value.repairingStartupJobs = true;
                value.manualStartupRepair = true;
                break;
            case HELD: value.heldQueuedJobCount = 3; break;
            case FAILED_WAITING: value.manualRerunCandidateCount = 2; break;
            case HELD_AND_FAILED:
                value.heldQueuedJobCount = 3;
                value.manualRerunCandidateCount = 2;
                break;
            case SYNC_FULL: phase = SceneSyncRuntimeState.Phase.FULL_SYNC; break;
            case SYNC_REFRESH: phase = SceneSyncRuntimeState.Phase.MANUAL_REFRESH; break;
            case SYNC_APPLY: phase = SceneSyncRuntimeState.Phase.MANUAL_APPLY; break;
            case SYNC_QUEUED: outcome = SceneSyncRuntimeState.Outcome.QUEUED_BEHIND_GATE; break;
            case CONFLICT_WAITING: conflicts = 3; break;
            case SYNC_FAILED: outcome = SceneSyncRuntimeState.Outcome.FAILED; break;
            case SYNC_ATTENTION: outcome = SceneSyncRuntimeState.Outcome.NEEDS_ATTENTION; break;
            case MUTATION_PENDING:
                value.pendingMutationCount = 2;
                value.pendingMutationDiagnostic = context.getString(R.string.notification_preview_mutation_pending_message);
                break;
            case MUTATION_FAILED:
                value.mutationFailed = true;
                value.pendingMutationDiagnostic = context.getString(R.string.notification_preview_mutation_error);
                break;
            default: break;
        }
        value.sceneSync = new SceneSyncRuntimeState.Snapshot(true, true, phase, 0, conflicts,
            SceneSyncRuntimeState.Action.MANUAL_REFRESH, outcome, Collections.emptyList());
        return value;
    }
}
