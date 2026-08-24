package com.quarty.housamoembedtrans.ui;

import com.quarty.housamoembedtrans.R;
import com.quarty.housamoembedtrans.bridge.HetBridgeContract;
import com.quarty.housamoembedtrans.runtime.TranslationStatusNotification;
import com.quarty.housamoembedtrans.context.store.SceneContextStore;
import com.quarty.housamoembedtrans.summary.job.SummaryJobStore;
import com.quarty.housamoembedtrans.translation.delivery.TerminalOutcome;
import com.quarty.housamoembedtrans.translation.job.TranslationJobStore;
import com.quarty.housamoembedtrans.translation.TranslationService;
import com.quarty.housamoembedtrans.translation.job.TranslationTaskExecutor;

import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.content.Intent;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

import com.google.android.material.appbar.MaterialToolbar;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.card.MaterialCardView;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;

import androidx.core.content.ContextCompat;

import java.io.File;
import java.text.DateFormat;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Lets the user number the startup jobs held out of the dispatch queue. */
public final class TranslationQueueActivity extends AppCompatActivity {

    private static final String STATE_SELECTED_IDS = "selected_ids";
    private static final String STATE_SELECTED_SUMMARY_IDS =
        "selected_summary_ids";
    /** Opens the same page in persistent failed-job management mode. */
    public static final String EXTRA_MANAGEMENT_ONLY =
        "com.quarty.housamoembedtrans.extra.MANAGEMENT_ONLY";

    private final ExecutorService ioExecutor =
        Executors.newSingleThreadExecutor();
    private final Handler summaryRecoveryHandler =
        new Handler(Looper.getMainLooper());
    private final Runnable summaryRecoveryRefresh =
        () -> {
            if (!isDestroyed() && !isFinishing() && !busy && !submitted) {
                refreshJobs();
            }
        };
    private final ArrayList<String> selectedRequestIds =
        new ArrayList<>();
    private boolean busy;
    private boolean submitted;
    private final TranslationJobStore.QueueListener queueListener =
        (hasPendingJobs, heldQueuedJobCount, repairingStartupJobs) ->
            runOnUiThread(() -> {
                if (!isDestroyed() && !isFinishing()
                    && !busy && !submitted) {
                    refreshJobs();
                }
            });

    private TranslationJobStore jobStore;
    private SummaryJobStore summaryJobStore;
    private SceneContextStore sceneContextStore;
    private List<TranslationJobStore.HeldQueuedJob> jobs =
        new ArrayList<>();
    private List<TranslationJobStore.TerminalJob> failedJobs =
        new ArrayList<>();
    private List<SummaryJobStore.RecoveryJob> summaryJobs =
        new ArrayList<>();
    private List<SummaryJobStore.FailedJob> failedSummaryJobs =
        new ArrayList<>();
    private List<TranslationTaskExecutor.BlockedJob> userActionJobs =
        new ArrayList<>();
    private final ArrayList<String> selectedSummaryRequestIds =
        new ArrayList<>();
    private Map<String, String> summaryOwnerNames = new HashMap<>();
    private boolean managementOnly;
    private int refreshGeneration;
    private LinearLayout itemContainer;
    private LinearLayout failedItemContainer;
    private LinearLayout summaryItemContainer;
    private LinearLayout failedSummaryItemContainer;
    private LinearLayout userActionItemContainer;
    private TextView summary;
    private TextView emptyMessage;
    private TextView failedSummary;
    private TextView failedEmptyMessage;
    private TextView userActionSummary;
    private TextView userActionEmptyMessage;
    private TextView summarySummary;
    private TextView summaryEmptyMessage;
    private TextView failedSummarySummary;
    private TextView failedSummaryEmptyMessage;
    private MaterialButton submitButton;
    private MaterialButton summarySubmitButton;
    private boolean repairingStartupJobs;
    private boolean summaryRecoveryReady;
    private boolean summaryRecoveryUnavailable;
    private int summaryRecoveryWaitAttempts;
    /** Store identity that produced the currently rendered recovery rows. */
    private SummaryJobStore renderedSummaryRecoveryStore;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_translation_queue);
        SystemBarInsets.apply(findViewById(R.id.root_translation_queue));

        jobStore = TranslationJobStore.getInstance(this);
        summaryJobStore = SummaryJobStore.createForAndroid(this);
        sceneContextStore = new SceneContextStore(this);
        managementOnly = getIntent().getBooleanExtra(
            EXTRA_MANAGEMENT_ONLY,
            false
        );
        itemContainer = findViewById(R.id.translation_queue_items);
        failedItemContainer = findViewById(R.id.translation_failed_items);
        summaryItemContainer = findViewById(R.id.summary_recovery_items);
        summary = findViewById(R.id.tv_translation_queue_summary);
        emptyMessage = findViewById(R.id.tv_translation_queue_empty);
        failedSummary = findViewById(R.id.tv_translation_failed_summary);
        failedEmptyMessage = findViewById(
            R.id.tv_translation_failed_empty
        );
        userActionSummary = findViewById(R.id.tv_user_action_summary);
        userActionEmptyMessage = findViewById(R.id.tv_user_action_empty);
        userActionItemContainer = findViewById(R.id.user_action_items);
        summarySummary = findViewById(R.id.tv_summary_recovery_summary);
        summaryEmptyMessage = findViewById(R.id.tv_summary_recovery_empty);
        failedSummarySummary = findViewById(R.id.tv_summary_failed_summary);
        failedSummaryEmptyMessage = findViewById(R.id.tv_summary_failed_empty);
        failedSummaryItemContainer = findViewById(R.id.summary_failed_items);
        submitButton = findViewById(R.id.btn_submit_translation_queue);
        summarySubmitButton = findViewById(R.id.btn_submit_summary_recovery);

        if (managementOnly) {
            findViewById(R.id.tv_translation_queue_intro).setVisibility(
                View.GONE
            );
            findViewById(R.id.tv_translation_queue_summary).setVisibility(
                View.GONE
            );
            findViewById(R.id.tv_translation_queue_empty).setVisibility(
                View.GONE
            );
            findViewById(R.id.translation_queue_items).setVisibility(
                View.GONE
            );
            submitButton.setVisibility(View.GONE);
            findViewById(R.id.summary_recovery_section).setVisibility(
                View.GONE
            );
            summarySubmitButton.setVisibility(View.GONE);
        }

        if (savedInstanceState != null) {
            ArrayList<String> restored = savedInstanceState.getStringArrayList(
                STATE_SELECTED_IDS
            );
            if (restored != null) {
                selectedRequestIds.addAll(restored);
            }
            ArrayList<String> restoredSummary =
                savedInstanceState.getStringArrayList(
                    STATE_SELECTED_SUMMARY_IDS
                );
            if (restoredSummary != null) {
                selectedSummaryRequestIds.addAll(restoredSummary);
            }
        }

        MaterialToolbar toolbar = findViewById(
            R.id.toolbar_translation_queue
        );
        if (managementOnly) {
            toolbar.setTitle(R.string.translation_job_management_title);
        }
        toolbar.setNavigationOnClickListener(view ->
            confirmCancelAndFinish()
        );
        submitButton.setOnClickListener(view -> submitOrder());
        summarySubmitButton.setOnClickListener(view -> submitSummaryRecovery());

        refreshJobs();
    }

    @Override
    protected void onResume() {
        super.onResume();
        summaryRecoveryWaitAttempts = 0;
        summaryRecoveryUnavailable = false;
        if (!busy && !submitted) {
            refreshJobs();
        }
    }

    @Override
    protected void onStart() {
        super.onStart();
        jobStore.setQueueListener(queueListener);
    }

    @Override
    protected void onStop() {
        jobStore.clearQueueListener(queueListener);
        super.onStop();
    }

    @Override
    protected void onSaveInstanceState(Bundle outState) {
        outState.putStringArrayList(
            STATE_SELECTED_IDS,
            new ArrayList<>(selectedRequestIds)
        );
        outState.putStringArrayList(
            STATE_SELECTED_SUMMARY_IDS,
            new ArrayList<>(selectedSummaryRequestIds)
        );
        super.onSaveInstanceState(outState);
    }

    @Override
    public void onBackPressed() {
        confirmCancelAndFinish();
    }

    @Override
    protected void onDestroy() {
        summaryRecoveryHandler.removeCallbacks(summaryRecoveryRefresh);
        ioExecutor.shutdownNow();
        super.onDestroy();
    }

    private void refreshJobs() {
        final int generation = ++refreshGeneration;
        if (!managementOnly) {
            // Invalidate the rendered owner before a new asynchronous snapshot
            // starts.  A button press during the refresh must not submit rows
            // from the previous Service epoch.
            summaryRecoveryReady = false;
            renderedSummaryRecoveryStore = null;
        }
        ioExecutor.execute(() -> {
            final boolean repairing = jobStore.isRepairingStartupJobs();
            final List<TranslationJobStore.HeldQueuedJob> loadedJobs =
                managementOnly
                    ? new ArrayList<>()
                    : jobStore.getHeldQueuedJobs();
            final List<TranslationJobStore.TerminalJob> loadedFailed;
            final List<SummaryJobStore.RecoveryJob> loadedSummary;
            final List<SummaryJobStore.FailedJob> loadedFailedSummary;
            final List<TranslationTaskExecutor.BlockedJob> loadedUserAction;
            final SummaryJobStore loadedSummaryStore;
            final boolean loadedRecoveryReady;
            final Map<String, String> loadedSummaryNames = new HashMap<>();
            try {
                loadedFailed = jobStore.listRetainedFailedJobs();
                TranslationTaskExecutor activeExecutor =
                    TranslationService.getActiveTaskExecutor();
                loadedUserAction = activeExecutor == null
                    ? new ArrayList<>()
                    : activeExecutor.listUserActionRequiredJobs();
                SummaryJobStore activeSummaryStore = managementOnly
                    ? null
                    : TranslationService.getActiveSummaryRecoveryStore();
                boolean recoveryReady = managementOnly
                    || (activeSummaryStore != null
                        && activeSummaryStore.isRecoveryDecisionOpen());
                loadedSummaryStore = activeSummaryStore;
                loadedRecoveryReady = recoveryReady;
                loadedSummary = !managementOnly && recoveryReady
                    ? activeSummaryStore.listRecoveryJobs()
                    : new ArrayList<>();
                loadedFailedSummary = summaryJobStore.listFailedJobs();
                for (SummaryJobStore.RecoveryJob job : loadedSummary) {
                    loadedSummaryNames.put(
                        job.getRequestId(),
                        summaryOwnerName(job)
                    );
                }
                for (SummaryJobStore.FailedJob job : loadedFailedSummary) {
                    loadedSummaryNames.put(
                        job.getRequestId(),
                        summaryOwnerName(job)
                    );
                }
            } catch (Exception error) {
                runOnUiThread(() -> {
                    if (!isDestroyed() && !isFinishing()
                        && generation == refreshGeneration) {
                        Toast.makeText(
                            this,
                            getString(
                                R.string.translation_job_operation_failed,
                                safeMessage(error)
                            ),
                            Toast.LENGTH_LONG
                        ).show();
                    }
                });
                return;
            }
            runOnUiThread(() -> {
                if (isDestroyed() || isFinishing()
                    || generation != refreshGeneration) {
                    return;
                }
                repairingStartupJobs = repairing;
                jobs = loadedJobs;
                failedJobs = loadedFailed;
                failedSummaryJobs = loadedFailedSummary;
                userActionJobs = loadedUserAction;
                summaryOwnerNames = loadedSummaryNames;
                SummaryJobStore currentSummaryStore =
                    TranslationService.getActiveSummaryRecoveryStore();
                summaryRecoveryReady = managementOnly
                    || (loadedRecoveryReady
                        && currentSummaryStore == loadedSummaryStore
                        && currentSummaryStore != null
                        && currentSummaryStore.isRecoveryDecisionOpen());
                // The I/O snapshot is only valid for the exact ready store
                // that produced it.  A lifecycle swap or a readiness change
                // keeps the UI in bounded preparing mode instead of showing
                // an empty/stale list as a committed recovery snapshot.
                summaryJobs = summaryRecoveryReady
                    ? loadedSummary
                    : new ArrayList<>();
                renderedSummaryRecoveryStore = summaryRecoveryReady
                    ? loadedSummaryStore
                    : null;
                if (summaryRecoveryReady) {
                    summaryRecoveryWaitAttempts = 0;
                    summaryRecoveryUnavailable = false;
                } else if (!managementOnly) {
                    summaryRecoveryWaitAttempts++;
                    summaryRecoveryUnavailable =
                        summaryRecoveryWaitAttempts >= 60;
                }
                Set<String> currentIds = new HashSet<>();
                for (TranslationJobStore.HeldQueuedJob job : jobs) {
                    currentIds.add(job.getRequestId());
                }
                if (!managementOnly) {
                    for (TranslationJobStore.TerminalJob job : failedJobs) {
                        if (!job.isSceneValidationFailure()) {
                            currentIds.add(job.getRequestId());
                        }
                    }
                }
                selectedRequestIds.removeIf(
                    requestId -> !currentIds.contains(requestId)
                );
                Set<String> currentSummaryIds = new HashSet<>();
                for (SummaryJobStore.RecoveryJob job : summaryJobs) {
                    currentSummaryIds.add(job.getRequestId());
                }
                selectedSummaryRequestIds.removeIf(
                    requestId -> !currentSummaryIds.contains(requestId)
                );
                renderJobs();
                if (!managementOnly
                    && !summaryRecoveryReady
                    && !summaryRecoveryUnavailable) {
                    summaryRecoveryHandler.removeCallbacks(
                        summaryRecoveryRefresh
                    );
                    summaryRecoveryHandler.postDelayed(
                        summaryRecoveryRefresh,
                        500L
                    );
                } else {
                    summaryRecoveryHandler.removeCallbacks(
                        summaryRecoveryRefresh
                    );
                }
            });
        });
    }

    private void renderJobs() {
        itemContainer.removeAllViews();
        failedItemContainer.removeAllViews();
        summaryItemContainer.removeAllViews();
        failedSummaryItemContainer.removeAllViews();
        userActionItemContainer.removeAllViews();

        if (!managementOnly && repairingStartupJobs) {
            summary.setText(R.string.translation_queue_repairing);
            summary.setVisibility(View.VISIBLE);
            emptyMessage.setVisibility(View.GONE);
            submitButton.setEnabled(false);
        } else if (!managementOnly) {
            summary.setText(getString(
                R.string.translation_queue_count,
                jobs.size()
            ));

            boolean empty = jobs.isEmpty();
            emptyMessage.setVisibility(empty ? View.VISIBLE : View.GONE);
            summary.setVisibility(empty ? View.GONE : View.VISIBLE);
            submitButton.setEnabled(
                (!jobs.isEmpty() || hasRerunCandidates()) && !busy
            );
        }

        failedSummary.setText(getString(
            R.string.translation_job_failed_count,
            failedJobs.size(),
            countRerunCandidates()
        ));
        failedSummary.setVisibility(
            failedJobs.isEmpty() ? View.GONE : View.VISIBLE
        );
        failedEmptyMessage.setVisibility(
            failedJobs.isEmpty() ? View.VISIBLE : View.GONE
        );

        LayoutInflater inflater = LayoutInflater.from(this);
        DateFormat dateFormat = DateFormat.getDateTimeInstance(
            DateFormat.MEDIUM,
            DateFormat.SHORT
        );

        for (TranslationJobStore.HeldQueuedJob job : jobs) {
            View item = inflater.inflate(
                R.layout.item_translation_queue,
                itemContainer,
                false
            );
            MaterialCardView card = item.findViewById(
                R.id.card_translation_queue_item
            );
            TextView number = item.findViewById(
                R.id.tv_translation_queue_number
            );
            TextView scene = item.findViewById(
                R.id.tv_translation_queue_scene
            );
            TextView language = item.findViewById(
                R.id.tv_translation_queue_language
            );
            TextView createdAt = item.findViewById(
                R.id.tv_translation_queue_created_at
            );

            int selectedIndex = selectedRequestIds.indexOf(
                job.getRequestId()
            );
            boolean selected = selectedIndex >= 0;
            card.setChecked(selected);
            number.setVisibility(
                selected ? View.VISIBLE : View.INVISIBLE
            );
            if (selected) {
                number.setText(getString(
                    R.string.translation_queue_selection_number,
                    selectedIndex + 1
                ));
            }

            scene.setText(job.getScene());
            language.setText(getString(
                R.string.translation_queue_target_language,
                job.getTargetLanguage()
            ));
            createdAt.setText(getString(
                R.string.translation_queue_created_at,
                dateFormat.format(new Date(job.getCreatedAt()))
            ));
            card.setOnClickListener(view ->
                toggleSelection(job.getRequestId())
            );

            itemContainer.addView(item);
        }

        renderFailedJobs();
        renderSummaryRecovery();
        renderFailedSummaryJobs();
        renderUserActionJobs();
    }

    private boolean hasRerunCandidates() {
        return countRerunCandidates() > 0;
    }

    private int countRerunCandidates() {
        int count = 0;
        for (TranslationJobStore.TerminalJob job : failedJobs) {
            if (!job.isSceneValidationFailure()) {
                count++;
            }
        }
        return count;
    }

    private void renderUserActionJobs() {
        boolean empty = userActionJobs.isEmpty();
        findViewById(R.id.user_action_section).setVisibility(
            empty ? View.GONE : View.VISIBLE
        );
        userActionSummary.setText(getString(
            R.string.user_action_required_summary,
            userActionJobs.size()
        ));
        userActionSummary.setVisibility(empty ? View.GONE : View.VISIBLE);
        userActionEmptyMessage.setVisibility(empty ? View.VISIBLE : View.GONE);
        if (empty) {
            return;
        }

        LayoutInflater inflater = LayoutInflater.from(this);
        for (TranslationTaskExecutor.BlockedJob job : userActionJobs) {
            View item = inflater.inflate(
                R.layout.item_user_action,
                userActionItemContainer,
                false
            );
            MaterialCardView card = item.findViewById(
                R.id.card_user_action_item
            );
            TextView scene = item.findViewById(
                R.id.tv_user_action_scene
            );
            TextView reason = item.findViewById(
                R.id.tv_user_action_reason
            );
            MaterialButton retry = item.findViewById(
                R.id.btn_retry_user_action
            );

            scene.setText(job.getScene());
            reason.setText(job.getReason());
            retry.setOnClickListener(view -> retryUserAction(job));
            retry.setEnabled(!busy);
            card.setEnabled(!busy);
            userActionItemContainer.addView(item);
        }
    }

    private void retryUserAction(TranslationTaskExecutor.BlockedJob job) {
        if (busy) {
            return;
        }
        TranslationTaskExecutor activeExecutor =
            TranslationService.getActiveTaskExecutor();
        if (activeExecutor == null
            || !activeExecutor.retryUserActionRequiredJob(
                job.getRequestId()
            )) {
            Toast.makeText(
                this,
                R.string.user_action_required_not_found,
                Toast.LENGTH_LONG
            ).show();
            refreshJobs();
            return;
        }
        setBusy(true);
        ioExecutor.execute(() -> {
            runOnUiThread(() -> {
                if (isDestroyed()) {
                    return;
                }
                setBusy(false);
                TranslationStatusNotification.refresh(this);
                Toast.makeText(
                    this,
                    R.string.user_action_required_retry_queued,
                    Toast.LENGTH_LONG
                ).show();
                refreshJobs();
            });
        });
    }

    private void renderSummaryRecovery() {
        boolean empty = summaryJobs.isEmpty();
        boolean waitingForService = !managementOnly && !summaryRecoveryReady;
        boolean unavailable = waitingForService && summaryRecoveryUnavailable;
        findViewById(R.id.summary_recovery_section).setVisibility(
            managementOnly ? View.GONE : View.VISIBLE
        );
        summarySummary.setText(getString(
            R.string.summary_recovery_summary,
            summaryJobs.size()
        ));
        summarySummary.setVisibility(empty || waitingForService
            ? View.GONE : View.VISIBLE);
        summaryEmptyMessage.setText(unavailable
            ? R.string.summary_recovery_unavailable
            : waitingForService
                ? R.string.summary_recovery_preparing
            : R.string.summary_recovery_empty);
        summaryEmptyMessage.setVisibility(
            empty || waitingForService ? View.VISIBLE : View.GONE
        );
        summarySubmitButton.setVisibility(empty ? View.GONE : View.VISIBLE);
        summarySubmitButton.setEnabled(
            !empty
                && summaryRecoveryReady
                && !busy
                && !repairingStartupJobs
        );

        if (empty || waitingForService) {
            return;
        }

        LayoutInflater inflater = LayoutInflater.from(this);
        DateFormat dateFormat = DateFormat.getDateTimeInstance(
            DateFormat.MEDIUM,
            DateFormat.SHORT
        );
        List<SummaryJobStore.RecoveryJob> ordered =
            new ArrayList<>(summaryJobs);
        ordered.sort(Comparator
            .comparingInt((SummaryJobStore.RecoveryJob job) ->
                "group".equals(job.getOwnerType()) ? 0 : 1)
            .thenComparing(SummaryJobStore.RecoveryJob::getOwnerType)
            .thenComparing(SummaryJobStore.RecoveryJob::getOwnerId)
            .thenComparing(SummaryJobStore.RecoveryJob::getRequestKind)
            .thenComparing(SummaryJobStore.RecoveryJob::getRequestId)
        );
        for (SummaryJobStore.RecoveryJob job : ordered) {
            View item = inflater.inflate(
                R.layout.item_summary_recovery,
                summaryItemContainer,
                false
            );
            MaterialCardView card = item.findViewById(
                R.id.card_summary_recovery_item
            );
            TextView title = item.findViewById(
                R.id.tv_summary_recovery_title
            );
            TextView detail = item.findViewById(
                R.id.tv_summary_recovery_detail
            );
            TextView createdAt = item.findViewById(
                R.id.tv_summary_recovery_created_at
            );

            boolean selected = selectedSummaryRequestIds.contains(
                job.getRequestId()
            );
            card.setChecked(selected);
            String ownerName = summaryOwnerNames.getOrDefault(
                job.getRequestId(),
                job.getOwnerId()
            );
            title.setText(ownerName);
            detail.setText(getString(
                R.string.summary_recovery_detail,
                job.getRequestKind(),
                job.getOwnerType(),
                job.getOwnerId(),
                job.getTargetLang()
            ));
            createdAt.setText(getString(
                R.string.summary_recovery_created_at,
                dateFormat.format(new Date(job.getCreatedAt()))
            ));
            card.setOnClickListener(view ->
                toggleSummarySelection(job.getRequestId())
            );
            card.setEnabled(!busy && !repairingStartupJobs);
            summaryItemContainer.addView(item);
        }
    }

    private String summaryOwnerName(SummaryJobStore.RecoveryJob job) {
        return summaryOwnerName(job.getOwnerType(), job.getOwnerId());
    }

    private String summaryOwnerName(SummaryJobStore.FailedJob job) {
        return summaryOwnerName(job.getOwnerType(), job.getOwnerId());
    }

    private String summaryOwnerName(String ownerType, String ownerId) {
        try {
            if ("context".equals(ownerType)) {
                return sceneContextStore.getContext(ownerId)
                    .optString("display_name", ownerId);
            }
            if ("group".equals(ownerType)) {
                return sceneContextStore.getGroup(ownerId)
                    .optString("display_name", ownerId);
            }
        } catch (Exception ignored) {
            // Fall back to the stable id.
        }
        return ownerId;
    }

    private void toggleSummarySelection(String requestId) {
        if (busy || repairingStartupJobs) {
            return;
        }
        int selectedIndex = selectedSummaryRequestIds.indexOf(requestId);
        if (selectedIndex >= 0) {
            selectedSummaryRequestIds.remove(selectedIndex);
        } else {
            selectedSummaryRequestIds.add(requestId);
        }
        renderJobs();
    }

    private void submitSummaryRecovery() {
        if (managementOnly || busy || repairingStartupJobs
            || !summaryRecoveryReady
            || renderedSummaryRecoveryStore == null
            || summaryJobs.isEmpty()) {
            return;
        }
        setBusy(true);
        final SummaryJobStore expectedSummaryStore =
            renderedSummaryRecoveryStore;
        final ArrayList<String> restoreIds =
            new ArrayList<>(selectedSummaryRequestIds);
        final int restoreCount = restoreIds.size();
        final int discardCount = summaryJobs.size() - restoreCount;
        ioExecutor.execute(() -> {
            try {
                TranslationService.applyActiveSummaryRecoveryDecision(
                    expectedSummaryStore,
                    restoreIds
                );
                runOnUiThread(() -> {
                    if (isDestroyed()) {
                        return;
                    }
                    setBusy(false);
                    TranslationStatusNotification.refresh(this);
                    Toast.makeText(
                        this,
                        getString(
                            R.string.summary_recovery_result,
                            restoreCount,
                            discardCount
                        ),
                        Toast.LENGTH_LONG
                    ).show();
                    refreshJobs();
                });
            } catch (Exception error) {
                showOperationFailure(error);
            }
        });
    }

    private void renderFailedSummaryJobs() {
        boolean empty = failedSummaryJobs.isEmpty();
        findViewById(R.id.summary_failed_section).setVisibility(
            empty ? View.GONE : View.VISIBLE
        );
        failedSummarySummary.setText(getString(
            R.string.summary_failed_summary,
            failedSummaryJobs.size()
        ));
        failedSummarySummary.setVisibility(empty ? View.GONE : View.VISIBLE);
        failedSummaryEmptyMessage.setVisibility(
            empty ? View.VISIBLE : View.GONE
        );
        if (empty) {
            return;
        }

        LayoutInflater inflater = LayoutInflater.from(this);
        DateFormat dateFormat = DateFormat.getDateTimeInstance(
            DateFormat.MEDIUM,
            DateFormat.SHORT
        );
        for (SummaryJobStore.FailedJob job : failedSummaryJobs) {
            View item = inflater.inflate(
                R.layout.item_summary_failed,
                failedSummaryItemContainer,
                false
            );
            MaterialCardView card = item.findViewById(
                R.id.card_summary_failed_item
            );
            TextView title = item.findViewById(
                R.id.tv_summary_failed_title
            );
            TextView detail = item.findViewById(
                R.id.tv_summary_failed_detail
            );
            TextView error = item.findViewById(
                R.id.tv_summary_failed_error
            );
            MaterialButton retry = item.findViewById(
                R.id.btn_summary_failed_retry
            );

            String ownerName = summaryOwnerNames.getOrDefault(
                job.getRequestId(),
                job.getOwnerId()
            );
            title.setText(ownerName);
            detail.setText(getString(
                R.string.summary_failed_detail,
                job.getRequestKind(),
                job.getOwnerType(),
                job.getOwnerId(),
                job.getTargetLang(),
                dateFormat.format(new Date(job.getUpdatedAt()))
            ));
            error.setText(job.getErrorMessage().isEmpty()
                ? getString(R.string.translation_job_failure_generic)
                : job.getErrorMessage());
            retry.setOnClickListener(view -> retrySummaryFailed(job));
            retry.setEnabled(!busy);
            card.setEnabled(!busy);
            failedSummaryItemContainer.addView(item);
        }
    }

    private void retrySummaryFailed(SummaryJobStore.FailedJob job) {
        if (busy) {
            return;
        }
        setBusy(true);
        ioExecutor.execute(() -> {
            try {
                summaryJobStore.retryFailedJob(job.getRequestId());
                boolean serviceStarted = ensureTranslationService();
                runOnUiThread(() -> {
                    if (isDestroyed()) {
                        return;
                    }
                    setBusy(false);
                    TranslationStatusNotification.refresh(this);
                    Toast.makeText(
                        this,
                        serviceStarted
                            ? R.string.summary_failed_retry_queued
                            : R.string.translation_job_service_unavailable,
                        Toast.LENGTH_LONG
                    ).show();
                    refreshJobs();
                });
            } catch (Exception error) {
                showOperationFailure(error);
            }
        });
    }

    private void renderFailedJobs() {
        LayoutInflater inflater = LayoutInflater.from(this);
        DateFormat dateFormat = DateFormat.getDateTimeInstance(
            DateFormat.MEDIUM,
            DateFormat.SHORT
        );

        for (TranslationJobStore.TerminalJob job : failedJobs) {
            View item = inflater.inflate(
                R.layout.item_translation_failed,
                failedItemContainer,
                false
            );
            MaterialCardView card = item.findViewById(
                R.id.card_translation_failed_item
            );
            TextView number = item.findViewById(
                R.id.tv_translation_failed_number
            );
            TextView scene = item.findViewById(
                R.id.tv_translation_failed_scene
            );
            TextView language = item.findViewById(
                R.id.tv_translation_failed_language
            );
            TextView updatedAt = item.findViewById(
                R.id.tv_translation_failed_updated_at
            );
            TextView status = item.findViewById(
                R.id.tv_translation_failed_status
            );
            TextView failure = item.findViewById(
                R.id.tv_translation_failed_summary
            );
            MaterialButton primary = item.findViewById(
                R.id.btn_translation_failed_primary
            );

            boolean sceneValidation = job.isSceneValidationFailure();
            int selectedIndex = selectedRequestIds.indexOf(
                job.getRequestId()
            );
            boolean selected = selectedIndex >= 0 && !managementOnly;
            card.setChecked(selected);
            number.setVisibility(
                selected ? View.VISIBLE : View.INVISIBLE
            );
            if (selected) {
                number.setText(getString(
                    R.string.translation_queue_selection_number,
                    selectedIndex + 1
                ));
            }

            scene.setText(job.getScene());
            language.setText(getString(
                R.string.translation_queue_target_language,
                job.getTargetLanguage()
            ));
            updatedAt.setText(getString(
                R.string.translation_job_failed_at,
                dateFormat.format(new Date(job.getUpdatedAt()))
            ));
            status.setText(getString(
                R.string.translation_job_status,
                friendlyStatus(job)
            ));
            failure.setText(friendlyFailureSummary(job));

            if (managementOnly) {
                primary.setText(
                    sceneValidation
                        ? R.string.translation_job_sync_scene
                        : R.string.translation_job_retry
                );
                primary.setEnabled(!sceneValidation && !busy);
                primary.setOnClickListener(view -> {
                    if (!sceneValidation) {
                        rerunSingle(job);
                    } else {
                        showFailureDetails(job);
                    }
                });
                card.setOnClickListener(view -> showFailureDetails(job));
            } else {
                primary.setText(R.string.translation_job_details);
                primary.setOnClickListener(view -> showFailureDetails(job));
                card.setOnClickListener(view -> {
                    if (!busy && !repairingStartupJobs && !sceneValidation) {
                        toggleSelection(job.getRequestId());
                    } else {
                        showFailureDetails(job);
                    }
                });
            }

            if (busy || (!managementOnly && repairingStartupJobs)) {
                card.setEnabled(false);
                primary.setEnabled(false);
            }
            failedItemContainer.addView(item);
        }
    }

    private String friendlyStatus(TranslationJobStore.TerminalJob job) {
        if (job.getDeliveryState() == TerminalOutcome.DeliveryState.PENDING) {
            return getString(R.string.translation_job_status_delivery_pending);
        }
        if (job.getDeliveryState()
            == TerminalOutcome.DeliveryState.ACKNOWLEDGED) {
            return getString(R.string.translation_job_status_acknowledged);
        }
        return getString(R.string.translation_job_status_failed);
    }

    private String friendlyFailureSummary(
        TranslationJobStore.TerminalJob job
    ) {
        if (job.isSceneValidationFailure()) {
            return getString(R.string.translation_job_scene_validation_hint);
        }
        String type = job.getErrorType() == null
            ? ""
            : job.getErrorType().trim().toLowerCase(java.util.Locale.ROOT);
        if (type.contains("auth") || type.contains("credential")
            || type.contains("permission")) {
            return getString(R.string.translation_job_failure_auth);
        }
        if (type.contains("rate") || type.contains("quota")) {
            return getString(R.string.translation_job_failure_rate_limit);
        }
        if (type.contains("network") || type.contains("timeout")
            || type.contains("connect")) {
            return getString(R.string.translation_job_failure_network);
        }
        if (type.contains("result") || type.contains("schema")
            || type.contains("validation")) {
            return getString(R.string.translation_job_failure_result);
        }
        if (type.contains("service") || type.contains("server")
            || type.contains("http")) {
            return getString(R.string.translation_job_failure_service);
        }
        return getString(R.string.translation_job_failure_generic);
    }

    private void showFailureDetails(
        TranslationJobStore.TerminalJob job
    ) {
        String message = friendlyFailureSummary(job);
        new MaterialAlertDialogBuilder(this)
            .setTitle(job.getScene())
            .setMessage(message)
            .setPositiveButton(android.R.string.ok, null)
            .show();
    }

    private void toggleSelection(String requestId) {
        if (busy || repairingStartupJobs) {
            return;
        }
        int selectedIndex = selectedRequestIds.indexOf(requestId);
        if (selectedIndex >= 0) {
            selectedRequestIds.remove(selectedIndex);
        } else {
            selectedRequestIds.add(requestId);
        }
        renderJobs();
    }

    private void submitOrder() {
        if (managementOnly
            || busy
            || repairingStartupJobs
            || (jobs.isEmpty() && !hasRerunCandidates())) {
            return;
        }

        int totalCount = jobs.size();
        ArrayList<String> orderedSelection =
            new ArrayList<>(selectedRequestIds);
        int selectedHeldCount = 0;
        for (String requestId : orderedSelection) {
            for (TranslationJobStore.HeldQueuedJob job : jobs) {
                if (requestId.equals(job.getRequestId())) {
                    selectedHeldCount++;
                    break;
                }
            }
        }
        int selectedRerunCount = orderedSelection.size() - selectedHeldCount;
        final int selectedHeldCountFinal = selectedHeldCount;
        final int selectedRerunCountFinal = selectedRerunCount;
        final int unselectedCount = totalCount - selectedHeldCount;
        setBusy(true);

        ioExecutor.execute(() -> {
            try {
                jobStore.applyManualRecoveryOrder(orderedSelection);
                boolean serviceStarted = ensureTranslationService();
                runOnUiThread(() -> {
                    if (isDestroyed()) {
                        return;
                    }
                    submitted = true;
                    TranslationStatusNotification.refresh(this);
                    Toast.makeText(
                        this,
                        serviceStarted
                            ? getString(
                                R.string.translation_queue_submit_mixed_result,
                                selectedHeldCountFinal,
                                selectedRerunCountFinal,
                                unselectedCount
                            )
                            : getString(
                                R.string.translation_job_service_unavailable
                            ),
                        Toast.LENGTH_LONG
                    ).show();
                    finish();
                });
            } catch (Exception e) {
                showOperationFailure(e);
            }
        });
    }

    private void confirmCancelAndFinish() {
        if (busy) {
            return;
        }
        if (managementOnly) {
            finish();
            return;
        }
        if (repairingStartupJobs) {
            finish();
            return;
        }
        if (submitted || jobs.isEmpty()) {
            finish();
            return;
        }

        new MaterialAlertDialogBuilder(this)
            .setTitle(R.string.translation_queue_cancel_title)
            .setMessage(getString(
                R.string.translation_queue_cancel_message,
                jobs.size()
            ))
            .setNegativeButton(R.string.keep_editing, null)
            .setPositiveButton(
                R.string.translation_queue_cancel_tasks,
                (dialog, which) -> cancelAllAndFinish()
            )
            .show();
    }

    private void cancelAllAndFinish() {
        int canceledCount = jobs.size();
        setBusy(true);

        ioExecutor.execute(() -> {
            try {
                jobStore.cancelHeldQueuedJobs();
                runOnUiThread(() -> {
                    if (isDestroyed()) {
                        return;
                    }
                    submitted = true;
                    TranslationStatusNotification.refresh(this);
                    Toast.makeText(
                        this,
                        getString(
                            R.string.translation_queue_cancel_result,
                            canceledCount
                        ),
                        Toast.LENGTH_LONG
                    ).show();
                    finish();
                });
            } catch (Exception e) {
                showOperationFailure(e);
            }
        });
    }

    private void setBusy(boolean value) {
        busy = value;
        submitButton.setEnabled(
            !value
                && !managementOnly
                && !repairingStartupJobs
                && (!jobs.isEmpty() || hasRerunCandidates())
        );
        summarySubmitButton.setEnabled(
            !value
                && !managementOnly
                && !repairingStartupJobs
                && !summaryJobs.isEmpty()
        );
        for (int index = 0; index < itemContainer.getChildCount(); index++) {
            itemContainer.getChildAt(index).setEnabled(!value);
        }
        for (int index = 0; index < failedItemContainer.getChildCount(); index++) {
            failedItemContainer.getChildAt(index).setEnabled(!value);
        }
        for (int index = 0; index < summaryItemContainer.getChildCount(); index++) {
            summaryItemContainer.getChildAt(index).setEnabled(!value);
        }
        for (int index = 0; index < failedSummaryItemContainer.getChildCount(); index++) {
            failedSummaryItemContainer.getChildAt(index).setEnabled(!value);
        }
        for (int index = 0; index < userActionItemContainer.getChildCount(); index++) {
            userActionItemContainer.getChildAt(index).setEnabled(!value);
        }
    }

    private void rerunSingle(TranslationJobStore.TerminalJob job) {
        if (busy || job == null || job.isSceneValidationFailure()) {
            return;
        }
        setBusy(true);
        ioExecutor.execute(() -> {
            try {
                jobStore.rerunManualCandidate(job.getRequestId());
                boolean serviceStarted = ensureTranslationService();
                runOnUiThread(() -> {
                    if (isDestroyed()) {
                        return;
                    }
                    setBusy(false);
                    TranslationStatusNotification.refresh(this);
                    Toast.makeText(
                        this,
                        serviceStarted
                            ? R.string.translation_job_retry_queued
                            : R.string.translation_job_service_unavailable,
                        Toast.LENGTH_LONG
                    ).show();
                    refreshJobs();
                });
            } catch (Exception error) {
                showOperationFailure(error);
            }
        });
    }

    /** Starts the foreground owner when a local rerun is admitted. */
    private boolean ensureTranslationService() {
        Intent intent = new Intent(this, TranslationService.class)
            .setPackage(getPackageName())
            .setAction(HetBridgeContract.ACTION_START_TRANSLATION_SERVICE);
        try {
            ContextCompat.startForegroundService(this, intent);
            return true;
        } catch (RuntimeException error) {
            // The durable queue is already safe; the next service start will
            // claim it.  Surface the transient owner failure without rolling
            // back the admitted local rerun.
            android.util.Log.w(
                "HET.TranslationQueue",
                "Could not start TranslationService after durable admission",
                error
            );
            return false;
        }
    }

    private void showOperationFailure(Exception error) {
        runOnUiThread(() -> {
            if (isDestroyed()) {
                return;
            }
            setBusy(false);
            refreshJobs();
            Toast.makeText(
                this,
                getString(
                    R.string.translation_job_operation_failed,
                    safeMessage(error)
                ),
                Toast.LENGTH_LONG
            ).show();
        });
    }

    private static String safeMessage(Throwable error) {
        String message = error.getMessage();
        return message == null || message.trim().isEmpty()
            ? error.getClass().getSimpleName()
            : message;
    }
}
