package com.quarty.housamoembedtrans.ui;

import com.quarty.housamoembedtrans.R;
import com.quarty.housamoembedtrans.bridge.HetBridgeContract;
import com.quarty.housamoembedtrans.management.pending.PendingProcessControlClient;
import com.quarty.housamoembedtrans.runtime.TranslationStatusNotification;
import com.quarty.housamoembedtrans.context.store.SceneContextStore;
import com.quarty.housamoembedtrans.scene.store.SceneStore;
import com.quarty.housamoembedtrans.storage.config.ConfigStore;
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
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import org.json.JSONArray;
import org.json.JSONObject;

/** Lets the user number the startup jobs held out of the dispatch queue. */
public final class TranslationQueueActivity extends AppCompatActivity {

    private static final String STATE_SELECTED_IDS = "selected_ids";
    private static final String STATE_SELECTED_SUMMARY_IDS =
        "selected_summary_ids";
    private static final String STATE_SELECTED_PENDING_TARGETS =
        "selected_pending_targets";
    private static final String PENDING_REASON_USER_REQUESTED =
        "user_requested";
    /** Opens the same page in persistent failed-job management mode. */
    public static final String EXTRA_MANAGEMENT_ONLY =
        "com.quarty.housamoembedtrans.extra.MANAGEMENT_ONLY";

    /** Exact owner identity captured from a live management store. */
    private static final class PendingMoveTarget {
        final String kind;
        final String canonicalId;
        final String label;

        PendingMoveTarget(String kind, String canonicalId, String label) {
            this.kind = kind;
            this.canonicalId = canonicalId;
            this.label = label;
        }

        String selectionKey() {
            return kind + ":" + canonicalId;
        }
    }

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
    private PendingProcessControlClient pendingClient;
    private PendingProcessMoveController pendingMoveController;
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
    private LinearLayout pendingSection;
    private LinearLayout pendingItemContainer;
    private TextView pendingSummary;
    private TextView pendingEmptyMessage;
    private MaterialButton pendingRefreshButton;
    private MaterialButton pendingMoveButton;
    private List<JSONObject> pendingProcesses = new ArrayList<>();
    private List<String> damagedPendingCandidates = new ArrayList<>();
    private final Set<String> selectedPendingMoveKeys = new HashSet<>();
    private boolean pendingReady;
    private boolean pendingLoading;
    private boolean pendingActive;
    private int pendingRefreshGeneration;

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
        pendingSection = findViewById(R.id.pending_process_section);
        pendingItemContainer = findViewById(R.id.pending_process_items);
        pendingSummary = findViewById(R.id.tv_pending_process_summary);
        pendingEmptyMessage = findViewById(R.id.tv_pending_process_empty);
        pendingRefreshButton = findViewById(R.id.btn_refresh_pending_processes);
        pendingMoveButton = findViewById(R.id.btn_move_pending_process);

        if (managementOnly) {
            pendingClient = new PendingProcessControlClient(this);
            pendingMoveController = new PendingProcessMoveController(this);
            pendingSection.setVisibility(View.VISIBLE);
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
            ArrayList<String> restoredPendingTargets =
                savedInstanceState.getStringArrayList(
                    STATE_SELECTED_PENDING_TARGETS
                );
            if (restoredPendingTargets != null) {
                selectedPendingMoveKeys.addAll(restoredPendingTargets);
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
        pendingRefreshButton.setOnClickListener(view -> {
            if (!busy && pendingClient != null) {
                if (!pendingClient.isConnected()) {
                    ensureTranslationService();
                    try {
                        pendingClient.bind();
                    } catch (RuntimeException error) {
                        showPendingOperationFailure(error);
                    }
                    renderPendingProcesses();
                    return;
                }
                refreshPendingProcesses();
            }
        });
        pendingMoveButton.setOnClickListener(view -> {
            if (!busy) {
                showPendingMoveDialog();
            }
        });

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
        if (managementOnly && pendingClient != null) {
            if (pendingClient.isConnected()) {
                refreshPendingProcesses();
            } else {
                // The first real read is triggered by onServiceConnected;
                // keep the management page in an explicit waiting state while
                // Binder is still completing the asynchronous bind.
                renderPendingProcesses();
            }
        }
    }

    @Override
    protected void onStart() {
        super.onStart();
        jobStore.setQueueListener(queueListener);
        if (managementOnly && pendingClient != null) {
            pendingActive = true;
            pendingClient.setConnectionListener(connected -> {
                if (!connected) {
                    return;
                }
                runOnUiThread(() -> {
                    if (isPendingUiActive()) {
                        refreshPendingProcesses();
                    }
                });
            });
            // A bind-only Service instance does not run its startup sequence.
            // Wake the existing exported Service before reading its manager.
            ensureTranslationService();
            try {
                pendingClient.bind();
            } catch (RuntimeException error) {
                showPendingOperationFailure(error);
            }
        }
    }

    @Override
    protected void onStop() {
        jobStore.clearQueueListener(queueListener);
        if (managementOnly && pendingClient != null) {
            pendingActive = false;
            pendingRefreshGeneration++;
            pendingClient.setConnectionListener(null);
            pendingClient.unbind();
        }
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
        outState.putStringArrayList(
            STATE_SELECTED_PENDING_TARGETS,
            new ArrayList<>(selectedPendingMoveKeys)
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
        pendingActive = false;
        pendingRefreshGeneration++;
        if (pendingClient != null) {
            pendingClient.close();
        }
        if (pendingMoveController != null) {
            pendingMoveController.close();
        }
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

    private boolean isPendingUiActive() {
        return managementOnly
            && pendingActive
            && !isDestroyed()
            && !isFinishing();
    }

    /** Reads the Service-owned pending index on the Activity I/O executor. */
    private void refreshPendingProcesses() {
        if (!isPendingUiActive() || pendingClient == null) {
            return;
        }
        final int generation = ++pendingRefreshGeneration;
        pendingLoading = true;
        renderPendingProcesses();
        ioExecutor.execute(() -> {
            final List<JSONObject> loaded = new ArrayList<>();
            final List<String> loadedDamaged = new ArrayList<>();
            try {
                JSONArray entries = pendingClient.listPendingProcesses();
                for (int index = 0; index < entries.length(); index++) {
                    JSONObject entry = entries.optJSONObject(index);
                    if (entry != null) {
                        loaded.add(new JSONObject(entry.toString()));
                    }
                }
                loadedDamaged.addAll(jobStore.getDamagedRequestIds());
                loadedDamaged.sort(String::compareToIgnoreCase);
            } catch (Exception error) {
                runOnUiThread(() -> {
                    if (!isPendingUiActive()
                        || generation != pendingRefreshGeneration) {
                        return;
                    }
                    pendingLoading = false;
                    pendingReady = false;
                    pendingProcesses = new ArrayList<>();
                    damagedPendingCandidates = new ArrayList<>();
                    renderPendingProcesses();
                    if (!(error instanceof
                        PendingProcessControlClient.ServiceNotReadyException)) {
                        Toast.makeText(
                            this,
                            getString(
                                R.string.pending_process_load_failed,
                                safeMessage(error)
                            ),
                            Toast.LENGTH_LONG
                        ).show();
                    }
                });
                return;
            }
            runOnUiThread(() -> {
                if (!isPendingUiActive()
                    || generation != pendingRefreshGeneration) {
                    return;
                }
                pendingLoading = false;
                pendingReady = true;
                pendingProcesses = loaded;
                damagedPendingCandidates = loadedDamaged;
                renderPendingProcesses();
            });
        });
    }

    private void renderPendingProcesses() {
        if (!managementOnly || pendingSection == null) {
            return;
        }
        pendingSection.setVisibility(View.VISIBLE);
        pendingItemContainer.removeAllViews();
        if (pendingLoading) {
            pendingSummary.setVisibility(View.GONE);
            pendingEmptyMessage.setText(R.string.pending_process_loading);
            pendingEmptyMessage.setVisibility(View.VISIBLE);
            pendingRefreshButton.setEnabled(false);
            pendingMoveButton.setEnabled(false);
            return;
        }
        if (!pendingReady) {
            pendingSummary.setVisibility(View.GONE);
            pendingEmptyMessage.setText(
                pendingClient != null && pendingClient.isConnected()
                    ? R.string.pending_process_service_not_ready
                    : R.string.pending_process_service_unavailable
            );
            pendingEmptyMessage.setVisibility(View.VISIBLE);
            pendingRefreshButton.setEnabled(!busy);
            pendingMoveButton.setEnabled(false);
            return;
        }

        pendingSummary.setText(getString(
            R.string.pending_process_count,
            pendingProcesses.size()
        ));
        pendingSummary.setVisibility(
            pendingProcesses.isEmpty() ? View.GONE : View.VISIBLE
        );
        pendingEmptyMessage.setText(R.string.pending_process_empty);
        pendingEmptyMessage.setVisibility(
            pendingProcesses.isEmpty() ? View.VISIBLE : View.GONE
        );
        pendingRefreshButton.setEnabled(!busy);
        pendingMoveButton.setEnabled(
            !busy && pendingClient != null && pendingClient.isConnected()
        );

        LayoutInflater inflater = LayoutInflater.from(this);
        DateFormat dateFormat = DateFormat.getDateTimeInstance(
            DateFormat.MEDIUM,
            DateFormat.SHORT
        );
        for (JSONObject entry : pendingProcesses) {
            View item = inflater.inflate(
                R.layout.item_pending_process,
                pendingItemContainer,
                false
            );
            MaterialCardView card = item.findViewById(
                R.id.card_pending_process
            );
            TextView kind = item.findViewById(R.id.tv_pending_process_kind);
            TextView canonicalId = item.findViewById(
                R.id.tv_pending_process_canonical_id
            );
            TextView pendingKey = item.findViewById(
                R.id.tv_pending_process_key
            );
            TextView reason = item.findViewById(
                R.id.tv_pending_process_reason
            );
            TextView created = item.findViewById(
                R.id.tv_pending_process_created
            );
            TextView mode = item.findViewById(R.id.tv_pending_process_mode);
            MaterialButton details = item.findViewById(
                R.id.btn_pending_process_details
            );
            MaterialButton restore = item.findViewById(
                R.id.btn_pending_process_restore
            );
            MaterialButton delete = item.findViewById(
                R.id.btn_pending_process_delete
            );

            String key = entry.optString("pending_key", "");
            kind.setText(entry.optString("kind", ""));
            canonicalId.setText(entry.optString("canonical_id", ""));
            pendingKey.setText(getString(
                R.string.pending_process_key,
                key
            ));
            reason.setText(getString(
                R.string.pending_process_reason,
                entry.optString("reason", "")
            ));
            created.setText(getString(
                R.string.pending_process_created,
                dateFormat.format(new Date(entry.optLong("created_at", 0L)))
            ));
            mode.setText(getString(
                R.string.pending_process_mode,
                entry.optString("restore_mode", "")
            ));
            details.setOnClickListener(view -> showPendingDetails(key));
            restore.setEnabled(
                !busy && "snapshot".equals(
                    entry.optString("restore_mode", "")
                )
            );
            restore.setOnClickListener(view ->
                confirmRestorePending(entry)
            );
            delete.setEnabled(!busy);
            delete.setOnClickListener(view ->
                confirmPermanentDeletePending(key)
            );
            card.setEnabled(!busy);
            details.setEnabled(!busy);
            pendingItemContainer.addView(item);
        }

        if (!damagedPendingCandidates.isEmpty()) {
            TextView heading = new TextView(this);
            heading.setText(R.string.pending_process_damaged_candidates_title);
            heading.setTextAppearance(
                R.style.TextAppearance_MaterialComponents_Subtitle2
            );
            int topPadding = Math.round(
                12 * getResources().getDisplayMetrics().density
            );
            heading.setPadding(0, topPadding, 0, 0);
            pendingItemContainer.addView(heading);
            for (String requestId : damagedPendingCandidates) {
                MaterialButton action = new MaterialButton(this);
                action.setAllCaps(false);
                action.setText(getString(
                    R.string.pending_process_move_damaged_job,
                    requestId
                ));
                action.setEnabled(!busy);
                action.setOnClickListener(view -> previewPendingMove(
                    "damaged_translation_job",
                    requestId,
                    PENDING_REASON_USER_REQUESTED
                ));
                pendingItemContainer.addView(
                    action,
                    new LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT
                    )
                );
            }
        }
    }

    private void showPendingDetails(String pendingKey) {
        if (!isPendingUiActive() || busy || pendingKey == null) {
            return;
        }
        setBusy(true);
        ioExecutor.execute(() -> {
            try {
                JSONObject entry = pendingClient.readPendingProcess(pendingKey);
                runOnUiThread(() -> {
                    if (!isPendingUiActive()) {
                        return;
                    }
                    setBusy(false);
                    new MaterialAlertDialogBuilder(this)
                        .setTitle(R.string.pending_process_details_title)
                        .setMessage(prettyJson(entry))
                        .setPositiveButton(android.R.string.ok, null)
                        .show();
                });
            } catch (Exception error) {
                showPendingOperationFailure(error);
            }
        });
    }

    private void confirmRestorePending(JSONObject entry) {
        if (!isPendingUiActive() || busy || entry == null) {
            return;
        }
        String pendingKey = entry.optString("pending_key", "");
        if (!"snapshot".equals(entry.optString("restore_mode", ""))) {
            Toast.makeText(
                this,
                R.string.pending_process_restore_unavailable,
                Toast.LENGTH_SHORT
            ).show();
            return;
        }
        new MaterialAlertDialogBuilder(this)
            .setTitle(R.string.pending_process_restore_title)
            .setMessage(getString(
                R.string.pending_process_restore_message,
                entry.optString("canonical_id", "")
            ))
            .setNegativeButton(R.string.cancel_action, null)
            .setPositiveButton(
                R.string.pending_process_restore,
                (dialog, which) -> restorePending(pendingKey)
            )
            .show();
    }

    private void restorePending(String pendingKey) {
        if (!isPendingUiActive() || busy || pendingKey == null) {
            return;
        }
        setBusy(true);
        ioExecutor.execute(() -> {
            try {
                pendingClient.restorePendingProcess(pendingKey);
                runOnUiThread(() -> {
                    if (!isPendingUiActive()) {
                        return;
                    }
                    setBusy(false);
                    Toast.makeText(
                        this,
                        R.string.pending_process_restored,
                        Toast.LENGTH_SHORT
                    ).show();
                    refreshPendingProcesses();
                });
            } catch (Exception error) {
                showPendingOperationFailure(error);
            }
        });
    }

    private void confirmPermanentDeletePending(String pendingKey) {
        if (!isPendingUiActive() || busy || pendingKey == null) {
            return;
        }
        new MaterialAlertDialogBuilder(this)
            .setTitle(R.string.pending_process_delete_title)
            .setMessage(R.string.pending_process_delete_message)
            .setNegativeButton(R.string.cancel_action, null)
            .setPositiveButton(
                R.string.pending_process_delete,
                (dialog, which) -> permanentlyDeletePending(pendingKey)
            )
            .show();
    }

    private void permanentlyDeletePending(String pendingKey) {
        if (!isPendingUiActive() || busy || pendingKey == null) {
            return;
        }
        setBusy(true);
        ioExecutor.execute(() -> {
            try {
                pendingClient.permanentlyDeletePendingProcess(pendingKey);
                runOnUiThread(() -> {
                    if (!isPendingUiActive()) {
                        return;
                    }
                    setBusy(false);
                    Toast.makeText(
                        this,
                        R.string.pending_process_deleted,
                        Toast.LENGTH_SHORT
                    ).show();
                    refreshPendingProcesses();
                });
            } catch (Exception error) {
                showPendingOperationFailure(error);
            }
        });
    }

    private void showPendingMoveDialog() {
        if (!isPendingUiActive() || busy || pendingClient == null) {
            return;
        }
        setBusy(true);
        ioExecutor.execute(() -> {
            try {
                List<PendingMoveTarget> targets = loadPendingMoveTargets();
                runOnUiThread(() -> {
                    if (!isPendingUiActive()) {
                        return;
                    }
                    setBusy(false);
                    showStructuredPendingMoveDialog(targets);
                });
            } catch (Exception error) {
                showPendingOperationFailure(error);
            }
        });
    }

    /**
     * Builds batch candidates from live stores so callers never type internal
     * kind/canonical-id pairs. Pending owners are already hidden by each store.
     */
    private List<PendingMoveTarget> loadPendingMoveTargets() throws Exception {
        List<PendingMoveTarget> targets = new ArrayList<>();
        Set<String> seen = new HashSet<>();

        SceneStore candidateSceneStore = new SceneStore(this);
        for (SceneStore.SceneInfo scene :
            candidateSceneStore.listSceneInfos()) {
            addPendingMoveTarget(
                targets,
                seen,
                "scene",
                scene.sceneName,
                "scene · " + scene.sceneName
            );
            for (String language : scene.languages) {
                addPendingMoveTarget(
                    targets,
                    seen,
                    "language",
                    SceneStore.languageCanonicalId(
                        scene.sceneName,
                        language
                    ),
                    "language · " + scene.sceneName + " / " + language
                );
            }
        }

        for (JSONObject context : sceneContextStore.listContexts()) {
            String id = context.optString("id", "");
            addPendingMoveTarget(
                targets,
                seen,
                "context",
                id,
                "context · " + context.optString("display_name", id)
            );
        }
        for (JSONObject group : sceneContextStore.listGroups()) {
            String id = group.optString("id", "");
            addPendingMoveTarget(
                targets,
                seen,
                "group",
                id,
                "group · " + group.optString("display_name", id)
            );
        }

        ConfigStore candidateConfigStore = new ConfigStore(this);
        addDictionaryPendingTargets(
            targets,
            seen,
            "character",
            candidateConfigStore.loadJson(
                ConfigStore.CHARDICT_FILE_NAME
            ).json
        );
        addDictionaryPendingTargets(
            targets,
            seen,
            "term",
            candidateConfigStore.loadJson(
                ConfigStore.GAMETERMS_FILE_NAME
            ).json
        );
        for (String requestId : jobStore.getDamagedRequestIds()) {
            addPendingMoveTarget(
                targets,
                seen,
                "damaged_translation_job",
                requestId,
                "damaged task · " + requestId
            );
        }
        targets.sort((left, right) -> {
            int kindOrder = left.kind.compareToIgnoreCase(right.kind);
            return kindOrder != 0
                ? kindOrder
                : left.label.compareToIgnoreCase(right.label);
        });
        return targets;
    }

    private static void addDictionaryPendingTargets(
        List<PendingMoveTarget> targets,
        Set<String> seen,
        String kind,
        JSONObject dictionary
    ) {
        if (dictionary == null) {
            return;
        }
        Iterator<String> keys = dictionary.keys();
        while (keys.hasNext()) {
            String key = keys.next();
            addPendingMoveTarget(
                targets,
                seen,
                kind,
                key,
                kind + " · " + key
            );
        }
    }

    private static void addPendingMoveTarget(
        List<PendingMoveTarget> targets,
        Set<String> seen,
        String kind,
        String canonicalId,
        String label
    ) {
        if (kind == null || kind.isEmpty()
            || canonicalId == null || canonicalId.isEmpty()) {
            return;
        }
        PendingMoveTarget target = new PendingMoveTarget(
            kind,
            canonicalId,
            label
        );
        if (seen.add(target.selectionKey())) {
            targets.add(target);
        }
    }

    private void showStructuredPendingMoveDialog(
        List<PendingMoveTarget> targets
    ) {
        if (targets == null || targets.isEmpty()) {
            Toast.makeText(
                this,
                R.string.pending_process_batch_empty,
                Toast.LENGTH_SHORT
            ).show();
            return;
        }
        Set<String> availableKeys = new HashSet<>();
        CharSequence[] labels = new CharSequence[targets.size()];
        boolean[] checked = new boolean[targets.size()];
        for (int index = 0; index < targets.size(); index++) {
            PendingMoveTarget target = targets.get(index);
            String key = target.selectionKey();
            availableKeys.add(key);
            labels[index] = target.label;
            checked[index] = selectedPendingMoveKeys.contains(key);
        }
        selectedPendingMoveKeys.retainAll(availableKeys);

        new MaterialAlertDialogBuilder(this)
            .setTitle(R.string.pending_process_batch_select_title)
            .setMultiChoiceItems(labels, checked, (dialog, which, selected) -> {
                String key = targets.get(which).selectionKey();
                if (selected) {
                    selectedPendingMoveKeys.add(key);
                } else {
                    selectedPendingMoveKeys.remove(key);
                }
            })
            .setNegativeButton(R.string.cancel_action, null)
            .setPositiveButton(R.string.pending_process_preview, (dialog, which) -> {
                List<PendingMoveTarget> selected = new ArrayList<>();
                for (PendingMoveTarget target : targets) {
                    if (selectedPendingMoveKeys.contains(
                        target.selectionKey()
                    )) {
                        selected.add(target);
                    }
                }
                if (selected.isEmpty()) {
                    Toast.makeText(
                        this,
                        R.string.pending_process_batch_none_selected,
                        Toast.LENGTH_SHORT
                    ).show();
                    return;
                }
                for (PendingMoveTarget target : selected) {
                    if ("character".equals(target.kind)
                        && "mc".equals(target.canonicalId)) {
                        Toast.makeText(
                            this,
                            R.string.pending_process_batch_main_character_rejected,
                            Toast.LENGTH_LONG
                        ).show();
                        return;
                    }
                }
                previewPendingBatch(selected);
            })
            .show();
    }

    private void previewPendingBatch(List<PendingMoveTarget> selected) {
        if (!isPendingUiActive() || busy || pendingClient == null) {
            return;
        }
        final List<PendingMoveTarget> ordered = new ArrayList<>(selected);
        ordered.sort((left, right) -> {
            int priority = Integer.compare(
                pendingMovePriority(left.kind),
                pendingMovePriority(right.kind)
            );
            return priority != 0
                ? priority
                : left.selectionKey().compareToIgnoreCase(
                    right.selectionKey()
                );
        });
        setBusy(true);
        ioExecutor.execute(() -> {
            try {
                JSONArray previews = new JSONArray();
                for (PendingMoveTarget target : ordered) {
                    JSONObject item = new JSONObject();
                    item.put("kind", target.kind);
                    item.put("canonical_id", target.canonicalId);
                    item.put(
                        "impact",
                        pendingClient.previewPendingMove(
                            target.kind,
                            target.canonicalId
                        )
                    );
                    previews.put(item);
                }
                runOnUiThread(() -> {
                    if (!isPendingUiActive()) {
                        return;
                    }
                    setBusy(false);
                    showPendingBatchConfirmation(ordered, previews);
                });
            } catch (Exception error) {
                showPendingOperationFailure(error);
            }
        });
    }

    private static int pendingMovePriority(String kind) {
        if ("language".equals(kind) || "context".equals(kind)) {
            return 0;
        }
        if ("scene".equals(kind) || "group".equals(kind)) {
            return 2;
        }
        return 1;
    }

    private void showPendingBatchConfirmation(
        List<PendingMoveTarget> targets,
        JSONArray previews
    ) {
        StringBuilder labels = new StringBuilder();
        for (PendingMoveTarget target : targets) {
            if (labels.length() > 0) {
                labels.append('\n');
            }
            labels.append("• ").append(target.label);
        }
        JSONObject payload = new JSONObject();
        try {
            payload.put("items", previews);
        } catch (Exception ignored) {
            // JSONArray values created above are always valid JSON values.
        }
        new MaterialAlertDialogBuilder(this)
            .setTitle(R.string.pending_process_batch_preview_title)
            .setMessage(getString(
                R.string.pending_process_batch_preview_message,
                targets.size(),
                labels.toString(),
                prettyJson(payload)
            ))
            .setNegativeButton(R.string.cancel_action, null)
            .setPositiveButton(
                R.string.pending_process_move,
                (dialog, which) -> movePendingBatch(targets)
            )
            .show();
    }

    private void movePendingBatch(List<PendingMoveTarget> targets) {
        if (!isPendingUiActive() || busy || pendingClient == null) {
            return;
        }
        final List<PendingMoveTarget> ordered = new ArrayList<>(targets);
        setBusy(true);
        ioExecutor.execute(() -> {
            List<String> movedKeys = new ArrayList<>();
            try {
                for (PendingMoveTarget target : ordered) {
                    pendingClient.movePendingProcess(
                        target.kind,
                        target.canonicalId,
                        PENDING_REASON_USER_REQUESTED
                    );
                    movedKeys.add(target.selectionKey());
                }
                runOnUiThread(() -> {
                    if (!isPendingUiActive()) {
                        return;
                    }
                    selectedPendingMoveKeys.removeAll(movedKeys);
                    setBusy(false);
                    Toast.makeText(
                        this,
                        getString(
                            R.string.pending_process_batch_moved,
                            movedKeys.size()
                        ),
                        Toast.LENGTH_SHORT
                    ).show();
                    refreshPendingProcesses();
                });
            } catch (Exception error) {
                showPendingBatchFailure(error, movedKeys);
            }
        });
    }

    private void showPendingBatchFailure(
        Throwable error,
        List<String> movedKeys
    ) {
        final List<String> completed = new ArrayList<>(movedKeys);
        runOnUiThread(() -> {
            if (!isPendingUiActive()) {
                return;
            }
            selectedPendingMoveKeys.removeAll(completed);
            setBusy(false);
            Toast.makeText(
                this,
                getString(
                    R.string.pending_process_batch_failed,
                    completed.size(),
                    safeMessage(error)
                ),
                Toast.LENGTH_LONG
            ).show();
            refreshPendingProcesses();
        });
    }

    private void previewPendingMove(
        String kind,
        String canonicalId,
        String reason
    ) {
        if (!isPendingUiActive() || busy || pendingClient == null) {
            return;
        }
        setBusy(true);
        ioExecutor.execute(() -> {
            try {
                JSONObject preview = pendingClient.previewPendingMove(
                    kind,
                    canonicalId
                );
                runOnUiThread(() -> {
                    if (!isPendingUiActive()) {
                        return;
                    }
                    setBusy(false);
                    showPendingMoveConfirmation(
                        kind,
                        canonicalId,
                        reason,
                        preview
                    );
                });
            } catch (Exception error) {
                showPendingOperationFailure(error);
            }
        });
    }

    private void showPendingMoveConfirmation(
        String kind,
        String canonicalId,
        String reason,
        JSONObject preview
    ) {
        String impact = prettyJson(preview);
        new MaterialAlertDialogBuilder(this)
            .setTitle(R.string.pending_process_preview_title)
            .setMessage(getString(
                R.string.pending_process_preview_message,
                kind,
                canonicalId,
                reason,
                impact
            ))
            .setNegativeButton(R.string.cancel_action, null)
            .setPositiveButton(
                R.string.pending_process_move,
                (dialog, which) -> movePending(kind, canonicalId, reason)
            )
            .show();
    }

    private void movePending(
        String kind,
        String canonicalId,
        String reason
    ) {
        if (!isPendingUiActive() || busy || pendingClient == null) {
            return;
        }
        setBusy(true);
        ioExecutor.execute(() -> {
            try {
                pendingClient.movePendingProcess(kind, canonicalId, reason);
                runOnUiThread(() -> {
                    if (!isPendingUiActive()) {
                        return;
                    }
                    setBusy(false);
                    Toast.makeText(
                        this,
                        R.string.pending_process_moved,
                        Toast.LENGTH_SHORT
                    ).show();
                    refreshPendingProcesses();
                });
            } catch (Exception error) {
                showPendingOperationFailure(error);
            }
        });
    }

    private void showPendingOperationFailure(Throwable error) {
        runOnUiThread(() -> {
            if (!isPendingUiActive()) {
                return;
            }
            setBusy(false);
            if (error instanceof
                PendingProcessControlClient.ServiceNotReadyException) {
                pendingLoading = false;
                pendingReady = false;
                pendingProcesses = new ArrayList<>();
                renderPendingProcesses();
            }
            Toast.makeText(
                this,
                getString(
                    R.string.pending_process_operation_failed,
                    safeMessage(error)
                ),
                Toast.LENGTH_LONG
            ).show();
        });
    }

    private static String prettyJson(JSONObject value) {
        String text;
        try {
            text = value == null ? "{}" : value.toString(2);
        } catch (Exception error) {
            text = value == null ? "{}" : value.toString();
        }
        final int maxLength = 32 * 1024;
        return text.length() <= maxLength
            ? text
            : text.substring(0, maxLength)
                + "\n…";
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
                        ? R.string.translation_job_move_scene_pending
                        : R.string.translation_job_retry
                );
                primary.setEnabled(!busy);
                primary.setOnClickListener(view -> {
                    if (!sceneValidation) {
                        rerunSingle(job);
                    } else {
                        moveSceneValidationToPending(job);
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

    /**
     * SCENE_FILE_DAMAGED is a Scene-management action, not a damaged task
     * record action.  Keep the Translation Job intact and route the exact
     * Scene identity through the shared structured move controller.
     */
    private void moveSceneValidationToPending(
        TranslationJobStore.TerminalJob job
    ) {
        if (!isPendingUiActive() || busy || pendingMoveController == null
            || job == null) {
            return;
        }
        String scene = job.getScene();
        if (scene == null || scene.trim().isEmpty()) {
            showPendingOperationFailure(new IllegalArgumentException(
                "scene_validation job has no Scene identity"
            ));
            return;
        }
        setBusy(true);
        String reason = job.getSceneValidationReason();
        if (reason == null || reason.trim().isEmpty()) {
            reason = "scene_invalid";
        }
        final String pendingReason = reason;
        pendingMoveController.confirmMove(
            "scene",
            scene,
            scene,
            pendingReason,
            () -> {
                refreshJobs();
                refreshPendingProcesses();
            },
            () -> setBusy(false)
        );
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
        if (managementOnly && pendingSection != null) {
            pendingRefreshButton.setEnabled(!value);
            pendingMoveButton.setEnabled(
                !value
                    && pendingReady
                    && pendingClient != null
                    && pendingClient.isConnected()
            );
            for (int index = 0;
                index < pendingItemContainer.getChildCount();
                index++) {
                pendingItemContainer.getChildAt(index).setEnabled(!value);
            }
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
