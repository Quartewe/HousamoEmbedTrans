package com.quarty.housamoembedtrans.ui;

import com.quarty.housamoembedtrans.R;
import com.quarty.housamoembedtrans.bridge.HetBridgeContract;
import com.quarty.housamoembedtrans.bridge.TranslationJobControlClient;
import com.quarty.housamoembedtrans.management.pending.PendingProcessControlClient;
import com.quarty.housamoembedtrans.runtime.RuntimeControlStore;
import com.quarty.housamoembedtrans.runtime.TranslationControlReceiver;
import com.quarty.housamoembedtrans.runtime.TranslationStatusNotification;
import com.quarty.housamoembedtrans.context.store.SceneContextStore;
import com.quarty.housamoembedtrans.context.history.HistoryResolution;
import com.quarty.housamoembedtrans.scene.store.SceneStore;
import com.quarty.housamoembedtrans.storage.config.ConfigStore;
import com.quarty.housamoembedtrans.summary.job.SummaryJobStore;
import com.quarty.housamoembedtrans.translation.delivery.TerminalOutcome;
import com.quarty.housamoembedtrans.translation.job.TranslationJobStore;
import com.quarty.housamoembedtrans.translation.TranslationService;
import com.quarty.housamoembedtrans.translation.job.TranslationTaskExecutor;
import com.quarty.housamoembedtrans.util.TranslationJobStatus;

import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.content.Intent;
import android.content.DialogInterface;
import android.content.res.ColorStateList;
import android.view.LayoutInflater;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewParent;
import android.view.Gravity;
import android.text.Editable;
import android.text.TextWatcher;
import android.widget.EditText;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.app.AlertDialog;

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
import java.util.Locale;
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
    private static final String STATE_TASK_TAB = "task_tab";
    private static final String STATE_TASK_SEARCH_QUERY = "task_search_query";
    private static final String STATE_TASK_SCOPE = "task_scope";
    private static final String STATE_TASK_SORT = "task_sort";
    private static final String STATE_OPEN_TASK_SECTIONS =
        "open_task_sections";
    private static final String STATE_TASK_LIST_SCROLL_Y =
        "task_list_scroll_y";
    private static final String STATE_TASK_DETAIL_OPEN = "task_detail_open";
    private static final String STATE_TASK_DETAIL_PAGE = "task_detail_page";
    private static final String STATE_TASK_DETAIL_REQUEST_ID =
        "task_detail_request_id";
    private static final String STATE_TASK_DETAIL_PENDING_KEY =
        "task_detail_pending_key";
    private static final String STATE_TASK_DETAIL_REJECTED_ID =
        "task_detail_rejected_id";
    private static final String STATE_NOTIFICATION_REQUEST_ID =
        "notification_request_id";
    private static final String STATE_NOTIFICATION_REJECTED_RECORD_ID =
        "notification_rejected_record_id";
    private static final String PENDING_REASON_USER_REQUESTED =
        "user_requested";
    /** Opens the same page in persistent failed-job management mode. */
    public static final String EXTRA_MANAGEMENT_ONLY =
        "com.quarty.housamoembedtrans.extra.MANAGEMENT_ONLY";
    /** Opens the matching task from a per-job status notification. */
    public static final String EXTRA_NOTIFICATION_REQUEST_ID =
        "com.quarty.housamoembedtrans.extra.NOTIFICATION_REQUEST_ID";
    /** Opens the matching archived result from its notification. */
    public static final String EXTRA_NOTIFICATION_REJECTED_RECORD_ID =
        "com.quarty.housamoembedtrans.extra.NOTIFICATION_REJECTED_RECORD_ID";
    /** Opens this page from the status notification's capture action. */
    public static final String EXTRA_NOTIFICATION_CAPTURE_CONTROL =
        "com.quarty.housamoembedtrans.extra.NOTIFICATION_CAPTURE_CONTROL";
    /** Explicit state requested by a notification action; never a toggle. */
    public static final String EXTRA_NOTIFICATION_CAPTURE_DESIRED_PAUSED =
        "com.quarty.housamoembedtrans.extra.NOTIFICATION_CAPTURE_DESIRED_PAUSED";

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
    private boolean busy;
    private boolean submitted;
    private final Runnable summaryRecoveryRefresh =
        () -> {
            if (!isDestroyed() && !isFinishing() && !busy && !submitted) {
                refreshJobs();
            }
        };
    private final ArrayList<String> selectedRequestIds =
        new ArrayList<>();
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
    private TranslationJobControlClient translationJobClient;
    private PendingProcessControlClient pendingClient;
    private PendingProcessMoveController pendingMoveController;
    private List<TranslationJobStore.HeldQueuedJob> jobs =
        new ArrayList<>();
    private List<TranslationJobStore.ReviewJob> activeJobs =
        new ArrayList<>();
    private List<TranslationJobStore.ReviewJob> canceledJobs = new ArrayList<>();
    private List<TranslationJobStore.TerminalJob> failedJobs =
        new ArrayList<>();
    /** Terminal outcomes that still need game delivery/acknowledgement. */
    private List<TranslationJobStore.TerminalJob> deliveryJobs =
        new ArrayList<>();
    private List<SummaryJobStore.RecoveryJob> summaryJobs =
        new ArrayList<>();
    private List<SummaryJobStore.FailedJob> failedSummaryJobs =
        new ArrayList<>();
    private List<TranslationTaskExecutor.BlockedJob> userActionJobs =
        new ArrayList<>();
    private final ArrayList<String> selectedSummaryRequestIds =
        new ArrayList<>();
    private final ArrayList<UiTask> stylePreviewTasks = new ArrayList<>();
    private boolean stylePreview;
    private Map<String, String> summaryOwnerNames = new HashMap<>();
    private boolean managementOnly;
    private int refreshGeneration;
    private LinearLayout itemContainer;
    private LinearLayout activeItemContainer;
    private LinearLayout activeSection;
    private LinearLayout failedItemContainer;
    private LinearLayout summaryItemContainer;
    private LinearLayout failedSummaryItemContainer;
    private LinearLayout userActionItemContainer;
    private TextView summary;
    private TextView activeSummary;
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
    private LinearLayout pageActions;
    private MaterialButton summarySubmitButton;
    private MaterialButton captureControlButton;
    private MaterialButton openPendingManagementButton;
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
    private LinearLayout rejectedSection;
    private RejectedApiResultsController rejectedController;
    private List<JSONObject> pendingProcesses = new ArrayList<>();
    private List<String> damagedPendingCandidates = new ArrayList<>();
    private final Set<String> selectedPendingMoveKeys = new HashSet<>();
    private boolean pendingReady;
    private boolean pendingLoading;
    private boolean pendingActive;
    private int pendingRefreshGeneration;
    /** Invalidates cancellation callbacks after the Activity leaves the UI. */
    private int activeUiGeneration;
    /** Tracks only the stop operation so lifecycle cleanup cannot clear other work. */
    private boolean stopInFlight;
    /** Captures the control intent shown by the task page. */
    private boolean capturePaused;
    /** Keeps capture persistence independent from Translation Job operations. */
    private boolean captureControlBusy;
    /** Invalidates capture-control callbacks after the Activity leaves the UI. */
    private int captureControlGeneration;
    /** One confirmation slot shared by capture, stop, and pending-delete actions. */
    private AlertDialog controlDialog;
    private boolean notificationCaptureControlPending;
    private boolean notificationCaptureDesiredPaused;
    private String notificationRequestId;
    private String notificationRejectedRecordId;

    /* Task page state mirrors the prototype's four tabs and three sections. */
    private static final int TAB_ALL = 0;
    private static final int TAB_TRANSLATION = 1;
    private static final int TAB_SUMMARY = 2;
    private static final int TAB_WAITING = 3;
    private int taskTab = TAB_ALL;
    private String taskSearchQuery = "";
    private String taskScope = "all";
    private String taskSort = "recent";
    private final Set<String> openTaskSections = new HashSet<>();
    private EditText taskSearchInput;
    private ImageButton taskSearchClearButton;
    private TextView taskScopeSummary;
    private LinearLayout taskSectionsContainer;
    private LinearLayout waitingContent;
    private LinearLayout waitingRecords;
    private TextView waitingCount;
    private TextView waitingEmptyMessage;
    private int waitingPendingVisibleCount;
    private int waitingPendingTotalCount;
    private int waitingRejectedVisibleCount;
    private int waitingRejectedTotalCount;
    private boolean waitingPendingLoaded;
    private boolean waitingRejectedLoaded;
    private boolean waitingPendingUnavailable;
    private MaterialButton taskSortButton;
    private MaterialButton taskMoreButton;
    private MaterialButton[] taskTabButtons;
    private View taskListContent;
    private androidx.core.widget.NestedScrollView taskDetailContent;
    private int taskListScrollY;
    private boolean resetDetailScrollOnNextRender;
    private boolean taskDetailOpen;
    private UiTask detailTask;
    private JSONObject detailPendingRecord;
    private JSONObject detailRejectedRecord;
    private String detailPendingKey;
    /** Saved UI identities waiting for the corresponding live snapshot. */
    private String restoredTaskDetailRequestId;
    private String restoredPendingDetailKey;
    private String restoredRejectedDetailRecordId;
    private boolean restoreTaskListScrollOnNextRender;
    private boolean taskSnapshotReady;
    private enum DetailPage {
        NONE,
        TASK,
        PENDING,
        REJECTED
    }
    private DetailPage detailPage = DetailPage.NONE;

    private enum UiTaskKind {
        HELD,
        ACTIVE,
        CANCELED,
        TERMINAL,
        SUMMARY_RECOVERY,
        SUMMARY_FAILED,
        USER_ACTION
    }

    private static final class UiTask {
        final UiTaskKind kind;
        final String requestId;
        final String title;
        final String objectType;
        final String language;
        final String status;
        final String reason;
        final long timestamp;
        final boolean translation;
        final boolean completed;
        final boolean actionNeeded;
        final Object source;

        UiTask(
            UiTaskKind kind,
            String requestId,
            String title,
            String objectType,
            String language,
            String status,
            String reason,
            long timestamp,
            boolean translation,
            boolean completed,
            boolean actionNeeded,
            Object source
        ) {
            this.kind = kind;
            this.requestId = requestId == null ? "" : requestId;
            this.title = title == null || title.isEmpty() ? "（未命名对象）" : title;
            this.objectType = objectType == null ? "" : objectType;
            this.language = language == null ? "" : language;
            this.status = status == null ? "读取状态失败" : status;
            this.reason = reason == null ? "" : reason;
            this.timestamp = timestamp;
            this.translation = translation;
            this.completed = completed;
            this.actionNeeded = actionNeeded;
            this.source = source;
        }

        boolean unfinished() {
            return !completed && kind != UiTaskKind.CANCELED;
        }

        String typeLabel() {
            return translation ? "翻译任务" : "摘要任务";
        }
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_translation_queue);
        SystemBarInsets.apply(findViewById(R.id.root_translation_queue));

        stylePreview = StylePreview.isEnabled(this);
        if (!stylePreview) {
            jobStore = TranslationJobStore.getInstance(this);
            summaryJobStore = SummaryJobStore.createForAndroid(this);
            sceneContextStore = new SceneContextStore(this);
            translationJobClient = new TranslationJobControlClient(this);
        }
        managementOnly = getIntent().getBooleanExtra(
            EXTRA_MANAGEMENT_ONLY,
            false
        );
        notificationRequestId = nonEmptyExtra(
            getIntent(),
            EXTRA_NOTIFICATION_REQUEST_ID
        );
        notificationRejectedRecordId = nonEmptyExtra(
            getIntent(),
            EXTRA_NOTIFICATION_REJECTED_RECORD_ID
        );
        boolean explicitNotificationDetailTarget =
            notificationRequestId != null || notificationRejectedRecordId != null;
        if (getIntent() != null) {
            getIntent().removeExtra(EXTRA_NOTIFICATION_REQUEST_ID);
            getIntent().removeExtra(EXTRA_NOTIFICATION_REJECTED_RECORD_ID);
        }
        itemContainer = findViewById(R.id.translation_queue_items);
        activeSection = findViewById(R.id.translation_active_section);
        activeItemContainer = findViewById(R.id.translation_active_items);
        activeSummary = findViewById(R.id.tv_translation_active_summary);
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
        pageActions = findViewById(R.id.page_actions);
        summarySubmitButton = findViewById(R.id.btn_submit_summary_recovery);
        captureControlButton = findViewById(R.id.btn_capture_control);
        openPendingManagementButton = findViewById(
            R.id.btn_open_pending_management
        );
        pendingSection = findViewById(R.id.pending_process_section);
        pendingItemContainer = findViewById(R.id.pending_process_items);
        pendingSummary = findViewById(R.id.tv_pending_process_summary);
        pendingEmptyMessage = findViewById(R.id.tv_pending_process_empty);
        pendingRefreshButton = findViewById(R.id.btn_refresh_pending_processes);
        pendingMoveButton = findViewById(R.id.btn_move_pending_process);
        rejectedSection = findViewById(R.id.rejected_api_results_section);
        taskSearchInput = findViewById(R.id.task_search_input);
        taskSearchClearButton = findViewById(R.id.btn_task_search_clear);
        taskScopeSummary = findViewById(R.id.tv_task_scope_summary);
        taskSectionsContainer = findViewById(R.id.task_sections_container);
        waitingContent = findViewById(R.id.waiting_content);
        waitingRecords = findViewById(R.id.waiting_records);
        waitingCount = findViewById(R.id.tv_waiting_count);
        waitingEmptyMessage = findViewById(R.id.tv_waiting_empty);
        taskSortButton = findViewById(R.id.btn_task_sort);
        taskMoreButton = findViewById(R.id.btn_task_more);
        taskListContent = findViewById(R.id.task_list_content);
        taskDetailContent = findViewById(R.id.task_detail_content);
        if (taskDetailContent != null) {
            taskDetailContent.setVisibility(View.GONE);
        }
        taskTabButtons = new MaterialButton[] {
            findViewById(R.id.btn_task_tab_all),
            findViewById(R.id.btn_task_tab_translation),
            findViewById(R.id.btn_task_tab_summary),
            findViewById(R.id.btn_task_tab_waiting)
        };
        prepareWaitingContent();
        openTaskSections.add("unfinished");
        openTaskSections.add("recent");
        PrimaryNavigation.attach(
            this,
            findViewById(R.id.primary_navigation),
            PrimaryNavigation.Destination.TASKS
        );
        if (managementOnly) {
            findViewById(R.id.primary_navigation).setVisibility(View.GONE);
        }
        if (stylePreview) {
            installStylePreviewNavigation();
        }

        if (managementOnly) {
            pendingClient = new PendingProcessControlClient(this);
            pendingMoveController = new PendingProcessMoveController(this);
            rejectedController = new RejectedApiResultsController(
                this,
                findViewById(R.id.root_translation_queue),
                ioExecutor,
                new RejectedApiResultsController.DetailListener() {
                    @Override
                    public void onDetailRequested(JSONObject record) {
                        showRejectedResultDetails(record);
                    }

                    @Override
                    public void onDetailUnavailable(String recordId) {
                        onRejectedDetailUnavailable(recordId);
                    }

                    @Override
                    public void onRecordDeleted(String recordId) {
                        onRejectedResultDeleted(recordId);
                    }

                    @Override
                    public void onRecordsChanged(
                        int visibleCount,
                        int totalCount
                    ) {
                        onRejectedRecordsChanged(visibleCount, totalCount);
                    }
                }
            );
            rejectedController.setUnifiedPresentation(true);
            pendingSection.setVisibility(View.VISIBLE);
            rejectedSection.setVisibility(View.VISIBLE);
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
            if (taskSectionsContainer != null) {
                taskSectionsContainer.setVisibility(View.GONE);
            }
            if (waitingContent != null) {
                waitingContent.setVisibility(View.VISIBLE);
            }
            activeSection.setVisibility(View.GONE);
            submitButton.setVisibility(View.GONE);
            findViewById(R.id.summary_recovery_section).setVisibility(
                View.GONE
            );
            summarySubmitButton.setVisibility(View.GONE);
            captureControlButton.setVisibility(View.GONE);
            openPendingManagementButton.setVisibility(View.GONE);
            taskTab = TAB_WAITING;
        }

        restoreTaskUiState(savedInstanceState, explicitNotificationDetailTarget);

        // The legacy sections remain in the XML for the existing store and
        // control helpers, but the task page renders its own projection.  Do
        // not expose two competing task layouts at the same time.
        hideLegacyTaskSections();

        if (taskSearchInput != null) {
            taskSearchInput.addTextChangedListener(new TextWatcher() {
                @Override
                public void beforeTextChanged(
                    CharSequence source,
                    int start,
                    int count,
                    int after
                ) {
                }

                @Override
                public void onTextChanged(
                    CharSequence source,
                    int start,
                    int before,
                    int count
                ) {
                    taskSearchQuery = source == null
                        ? ""
                        : source.toString();
                    updateTaskSearchClearButton();
                    renderTaskPage();
                }

                @Override
                public void afterTextChanged(Editable editable) {
                }
            });
        }
        if (taskSearchClearButton != null) {
            taskSearchClearButton.setOnClickListener(view -> {
                if (taskSearchInput != null) {
                    taskSearchInput.setText("");
                    taskSearchInput.requestFocus();
                }
            });
        }
        updateTaskSearchClearButton();
        for (int index = 0; index < taskTabButtons.length; index++) {
            final int tab = index;
            if (taskTabButtons[index] != null) {
                taskTabButtons[index].setOnClickListener(
                    view -> selectTaskTab(tab)
                );
            }
        }
        if (taskSortButton != null) {
            taskSortButton.setOnClickListener(view -> showTaskSortDialog());
        }
        if (taskMoreButton != null) {
            taskMoreButton.setOnClickListener(view -> showTaskMoreDialog());
        }

        MaterialToolbar toolbar = findViewById(
            R.id.toolbar_translation_queue
        );
        if (stylePreview) {
            toolbar.setNavigationIcon(R.drawable.ic_task_back);
            toolbar.setNavigationContentDescription(R.string.back_action);
            toolbar.setNavigationOnClickListener(view -> finish());
        } else if (managementOnly) {
            toolbar.setTitle(R.string.translation_job_management_title);
            toolbar.setNavigationIcon(R.drawable.ic_task_back);
            toolbar.setNavigationContentDescription(R.string.back_action);
            toolbar.setNavigationOnClickListener(view ->
                confirmCancelAndFinish()
            );
        } else {
            toolbar.setNavigationIcon((android.graphics.drawable.Drawable) null);
            toolbar.setNavigationOnClickListener(view ->
                onCaptureControlClicked()
            );
        }
        toolbar.setNavigationContentDescription(
            stylePreview || managementOnly
                ? R.string.back_action
                : R.string.capture_control_pause
        );
        captureControlButton.setOnClickListener(view ->
            onCaptureControlClicked()
        );
        submitButton.setOnClickListener(view -> showRecoveryOrderDialog());
        summarySubmitButton.setOnClickListener(
            view -> showSummaryRecoveryDialog()
        );
        openPendingManagementButton.setOnClickListener(view ->
            selectTaskTab(TAB_WAITING)
        );
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

        if (stylePreview) {
            disableStylePreviewActions();
        }

        if (!stylePreview) {
            initializeWaitingController();
        }
        if (!managementOnly && !stylePreview) {
            refreshCaptureControl();
        }
        if (stylePreview) {
            loadStylePreviewTasks();
        } else {
            refreshJobs();
        }
    }

    /** Loads task rows without constructing a durable JobStore or Service client. */
    private void loadStylePreviewTasks() {
        stylePreviewTasks.clear();
        JSONObject payload = StylePreview.payloadOf(getIntent());
        JSONArray values = payload == null
            ? null
            : payload.optJSONArray("tasks");
        if (values != null) {
            for (int index = 0; index < values.length(); index++) {
                JSONObject value = values.optJSONObject(index);
                if (value == null) {
                    continue;
                }
                String state = value.optString("status", "queued");
                String kind = value.optString("kind", "translation");
                boolean translation = !"summary".equals(kind);
                UiTaskKind taskKind;
                int statusResource;
                int reasonResource;
                boolean completed = false;
                boolean actionNeeded = false;
                if ("running".equals(state)) {
                    taskKind = UiTaskKind.ACTIVE;
                    statusResource = R.string.task_status_running;
                    reasonResource = R.string.task_reason_running;
                    actionNeeded = true;
                } else if ("failed".equals(state)) {
                    taskKind = translation
                        ? UiTaskKind.TERMINAL
                        : UiTaskKind.SUMMARY_FAILED;
                    statusResource = translation
                        ? R.string.task_status_failed
                        : R.string.task_status_summary_failed;
                    reasonResource = R.string.task_reason_summary_recovery;
                    actionNeeded = true;
                } else if ("completed".equals(state)) {
                    taskKind = UiTaskKind.TERMINAL;
                    statusResource = R.string.task_status_acknowledged;
                    reasonResource = R.string.task_reason_completed;
                    completed = true;
                } else {
                    taskKind = UiTaskKind.HELD;
                    statusResource = R.string.task_status_queued;
                    reasonResource = R.string.task_reason_queued;
                }
                stylePreviewTasks.add(new UiTask(
                    taskKind,
                    value.optString("request_id", "preview-" + index),
                    value.optString("scene", "Preview object"),
                    translation
                        ? getString(R.string.task_object_scene)
                        : getString(R.string.task_object_context),
                    value.optString("target_language", "zh-cn"),
                    getString(statusResource),
                    getString(reasonResource),
                    value.optLong("created_at", 1_725_000_000_000L),
                    translation,
                    completed,
                    actionNeeded,
                    null
                ));
            }
        }
        taskSnapshotReady = true;
        waitingPendingLoaded = true;
        waitingRejectedLoaded = true;
        JSONArray waiting = payload == null
            ? null
            : payload.optJSONArray("waiting");
        waitingPendingVisibleCount = waiting == null ? 0 : waiting.length();
        waitingPendingTotalCount = waitingPendingVisibleCount;
        waitingRejectedVisibleCount = 0;
        waitingRejectedTotalCount = 0;
        renderTaskPage();
    }

    private void disableStylePreviewActions() {
        if (submitButton != null) submitButton.setVisibility(View.GONE);
        if (pageActions != null) pageActions.setVisibility(View.GONE);
        if (summarySubmitButton != null) summarySubmitButton.setVisibility(View.GONE);
        if (captureControlButton != null) captureControlButton.setVisibility(View.GONE);
        if (openPendingManagementButton != null) {
            openPendingManagementButton.setVisibility(View.GONE);
        }
        if (pendingRefreshButton != null) pendingRefreshButton.setVisibility(View.GONE);
        if (pendingMoveButton != null) pendingMoveButton.setVisibility(View.GONE);
    }

    /** Keeps the real navigation bar's visual footprint without opening live pages. */
    private void installStylePreviewNavigation() {
        View navigation = findViewById(R.id.primary_navigation);
        if (navigation == null) return;
        View home = navigation.findViewById(R.id.nav_home);
        if (home != null) home.setOnClickListener(view -> {
            startActivity(StylePreview.intentFor(this, StylePreview.KIND_HOME));
            finish();
        });
        View tasks = navigation.findViewById(R.id.nav_tasks);
        View management = navigation.findViewById(R.id.nav_management);
        View settings = navigation.findViewById(R.id.nav_settings);
        if (tasks != null) tasks.setOnClickListener(view -> { });
        if (management != null) {
            management.setOnClickListener(view -> {
                startActivity(StylePreview.intentFor(
                    this,
                    StylePreview.KIND_MANAGEMENT_HOME
                ));
                finish();
            });
        }
        if (settings != null) {
            settings.setOnClickListener(view -> startActivity(
                new Intent(this, StylePreviewActivity.class).addFlags(
                    Intent.FLAG_ACTIVITY_CLEAR_TOP
                        | Intent.FLAG_ACTIVITY_SINGLE_TOP
                )
            ));
        }
    }

    private void restoreTaskUiState(
        Bundle savedInstanceState,
        boolean explicitNotificationDetailTarget
    ) {
        if (savedInstanceState == null) {
            if (managementOnly) {
                taskTab = TAB_WAITING;
            }
            return;
        }
        if (notificationRequestId == null) {
            notificationRequestId = nonEmptyString(
                savedInstanceState.getString(STATE_NOTIFICATION_REQUEST_ID)
            );
        }
        if (notificationRejectedRecordId == null) {
            notificationRejectedRecordId = nonEmptyString(
                savedInstanceState.getString(
                    STATE_NOTIFICATION_REJECTED_RECORD_ID
                )
            );
        }
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

        int restoredTab = savedInstanceState.getInt(STATE_TASK_TAB, TAB_ALL);
        if (restoredTab >= TAB_ALL && restoredTab <= TAB_WAITING) {
            taskTab = restoredTab;
        }
        String restoredQuery = savedInstanceState.getString(
            STATE_TASK_SEARCH_QUERY
        );
        if (restoredQuery != null) {
            taskSearchQuery = restoredQuery;
        }
        String restoredScope = savedInstanceState.getString(STATE_TASK_SCOPE);
        if ("all".equals(restoredScope)
            || "unfinished".equals(restoredScope)
            || "action-needed".equals(restoredScope)
            || "completed".equals(restoredScope)) {
            taskScope = restoredScope;
        }
        String restoredSort = savedInstanceState.getString(STATE_TASK_SORT);
        if ("recent".equals(restoredSort)
            || "name".equals(restoredSort)
            || "action".equals(restoredSort)
            || "completion".equals(restoredSort)) {
            taskSort = restoredSort;
        }
        ArrayList<String> restoredSections =
            savedInstanceState.getStringArrayList(STATE_OPEN_TASK_SECTIONS);
        if (restoredSections != null) {
            openTaskSections.clear();
            for (String section : restoredSections) {
                if ("unfinished".equals(section)
                    || "completed".equals(section)
                    || "recent".equals(section)
                    || "action-needed".equals(section)) {
                    openTaskSections.add(section);
                }
            }
        }
        taskListScrollY = Math.max(
            0,
            savedInstanceState.getInt(STATE_TASK_LIST_SCROLL_Y, 0)
        );
        restoreTaskListScrollOnNextRender = savedInstanceState.containsKey(
            STATE_TASK_LIST_SCROLL_Y
        );

        boolean savedNotificationDetailTarget =
            notificationRequestId != null || notificationRejectedRecordId != null;
        if (!explicitNotificationDetailTarget
            && !savedNotificationDetailTarget
            && savedInstanceState.getBoolean(STATE_TASK_DETAIL_OPEN, false)) {
            String detailPageName = savedInstanceState.getString(
                STATE_TASK_DETAIL_PAGE
            );
            if (DetailPage.TASK.name().equals(detailPageName)) {
                restoredTaskDetailRequestId = nonEmptyString(
                    savedInstanceState.getString(STATE_TASK_DETAIL_REQUEST_ID)
                );
            } else if (DetailPage.PENDING.name().equals(detailPageName)) {
                restoredPendingDetailKey = nonEmptyString(
                    savedInstanceState.getString(STATE_TASK_DETAIL_PENDING_KEY)
                );
                if (restoredPendingDetailKey != null) {
                    taskTab = TAB_WAITING;
                }
            } else if (DetailPage.REJECTED.name().equals(detailPageName)) {
                restoredRejectedDetailRecordId = nonEmptyString(
                    savedInstanceState.getString(STATE_TASK_DETAIL_REJECTED_ID)
                );
                if (restoredRejectedDetailRecordId != null) {
                    taskTab = TAB_WAITING;
                }
            }
        }
        if (managementOnly) {
            taskTab = TAB_WAITING;
        }
        if (taskSearchInput != null) {
            taskSearchInput.setText(taskSearchQuery);
        }
    }

    /** Creates the waiting-record helpers lazily so opening the task page
     * does not bind the exported service until that tab is actually used. */
    private void initializeWaitingController() {
        if (pendingClient != null) {
            return;
        }
        pendingClient = new PendingProcessControlClient(this);
        pendingMoveController = new PendingProcessMoveController(this);
        rejectedController = new RejectedApiResultsController(
            this,
            findViewById(R.id.root_translation_queue),
            ioExecutor,
            new RejectedApiResultsController.DetailListener() {
                @Override
                public void onDetailRequested(JSONObject record) {
                    showRejectedResultDetails(record);
                }

                @Override
                public void onDetailUnavailable(String recordId) {
                    onRejectedDetailUnavailable(recordId);
                }

            @Override
            public void onRecordDeleted(String recordId) {
                onRejectedResultDeleted(recordId);
            }

            @Override
            public void onRecordsChanged(int visibleCount, int totalCount) {
                onRejectedRecordsChanged(visibleCount, totalCount);
            }
        }
        );
        rejectedController.setUnifiedPresentation(true);
        rejectedController.setActive(false);
    }

    private void hideLegacyTaskSections() {
        int[] ids = new int[] {
            R.id.btn_open_pending_management,
            R.id.translation_active_section,
            R.id.user_action_section,
            R.id.summary_recovery_section,
            R.id.tv_translation_queue_summary,
            R.id.tv_translation_queue_empty,
            R.id.translation_queue_items,
            R.id.tv_translation_failed_summary,
            R.id.tv_translation_failed_empty,
            R.id.translation_failed_items,
            R.id.summary_failed_section,
            R.id.btn_submit_translation_queue
        };
        for (int id : ids) {
            View view = findViewById(id);
            if (view != null) {
                view.setVisibility(View.GONE);
            }
        }
        if (summarySubmitButton != null) {
            summarySubmitButton.setVisibility(View.GONE);
        }
    }

    /**
     * Keep the old store-specific renderers as data owners, but place their
     * cards under the one waiting surface used by the task page.
     */
    private void prepareWaitingContent() {
        if (waitingRecords == null || pendingSection == null
            || rejectedSection == null) {
            return;
        }
        ViewParent pendingParent = pendingSection.getParent();
        if (pendingParent instanceof ViewGroup) {
            ((ViewGroup) pendingParent).removeView(pendingSection);
        }
        ViewParent rejectedParent = rejectedSection.getParent();
        if (rejectedParent instanceof ViewGroup) {
            ((ViewGroup) rejectedParent).removeView(rejectedSection);
        }
        waitingRecords.addView(pendingSection);
        waitingRecords.addView(rejectedSection);
        flattenWaitingSection(pendingSection);
        flattenWaitingSection(rejectedSection);

        if (pendingSection.getChildCount() > 0) {
            pendingSection.getChildAt(0).setVisibility(View.GONE);
        }
        if (rejectedSection.getChildCount() > 1) {
            rejectedSection.getChildAt(0).setVisibility(View.GONE);
            rejectedSection.getChildAt(1).setVisibility(View.GONE);
        }
        if (pendingSummary != null) {
            pendingSummary.setVisibility(View.GONE);
        }
        if (pendingEmptyMessage != null) {
            pendingEmptyMessage.setVisibility(View.GONE);
        }
        // The controller hides these during render as well; keeping the
        // initial layout closed avoids a one-frame legacy panel.
        TextView rejectedSummary = findViewById(
            R.id.tv_rejected_api_results_summary
        );
        TextView rejectedEmpty = findViewById(
            R.id.tv_rejected_api_results_empty
        );
        if (rejectedSummary != null) {
            rejectedSummary.setVisibility(View.GONE);
        }
        if (rejectedEmpty != null) {
            rejectedEmpty.setVisibility(View.GONE);
        }
        hideWaitingActionRow(pendingRefreshButton);
        hideWaitingActionRow(pendingMoveButton);
        setZeroTopMargin(pendingItemContainer);
        LinearLayout rejectedItems = findViewById(
            R.id.rejected_api_results_items
        );
        setZeroTopMargin(rejectedItems);
    }

    private void flattenWaitingSection(LinearLayout section) {
        section.setBackground(null);
        section.setPadding(0, 0, 0, 0);
        ViewGroup.LayoutParams rawParams = section.getLayoutParams();
        if (rawParams instanceof LinearLayout.LayoutParams) {
            LinearLayout.LayoutParams params =
                (LinearLayout.LayoutParams) rawParams;
            params.topMargin = 0;
            params.bottomMargin = 0;
            section.setLayoutParams(params);
        }
    }

    private void hideWaitingActionRow(View control) {
        if (control == null) {
            return;
        }
        ViewParent parent = control.getParent();
        if (parent instanceof View) {
            ((View) parent).setVisibility(View.GONE);
        }
    }

    private void setZeroTopMargin(View view) {
        if (view == null) {
            return;
        }
        ViewGroup.LayoutParams rawParams = view.getLayoutParams();
        if (rawParams instanceof LinearLayout.LayoutParams) {
            LinearLayout.LayoutParams params =
                (LinearLayout.LayoutParams) rawParams;
            params.topMargin = 0;
            view.setLayoutParams(params);
        }
    }

    private void updateTaskSearchClearButton() {
        if (taskSearchClearButton == null) {
            return;
        }
        boolean hasQuery = taskSearchQuery != null
            && !taskSearchQuery.trim().isEmpty();
        taskSearchClearButton.setVisibility(
            hasQuery ? View.VISIBLE : View.GONE
        );
    }

    private void updateWaitingSummary() {
        if (waitingCount == null || waitingEmptyMessage == null) {
            return;
        }
        int visible = waitingPendingVisibleCount
            + waitingRejectedVisibleCount;
        int total = waitingPendingTotalCount + waitingRejectedTotalCount;
        waitingCount.setText(getString(R.string.task_waiting_count, visible));
        if (waitingPendingUnavailable) {
            waitingEmptyMessage.setText(
                pendingClient != null && pendingClient.isConnected()
                    ? R.string.pending_process_service_not_ready
                    : R.string.pending_process_service_unavailable
            );
            waitingEmptyMessage.setVisibility(
                waitingRejectedVisibleCount == 0 ? View.VISIBLE : View.GONE
            );
            return;
        }
        if (!waitingPendingLoaded || !waitingRejectedLoaded) {
            waitingEmptyMessage.setVisibility(View.GONE);
            return;
        }
        waitingEmptyMessage.setText(
            total == 0
                ? R.string.task_waiting_empty
                : R.string.task_waiting_no_results
        );
        waitingEmptyMessage.setVisibility(
            visible == 0 ? View.VISIBLE : View.GONE
        );
    }

    private void onRejectedRecordsChanged(int visibleCount, int totalCount) {
        if (rejectedController == null || !rejectedController.hasLoadedSnapshot()) {
            return;
        }
        waitingRejectedVisibleCount = Math.max(0, visibleCount);
        waitingRejectedTotalCount = Math.max(0, totalCount);
        waitingRejectedLoaded = true;
        updateWaitingSummary();
        restoreWaitingListScrollIfReady();
    }

    private void onRejectedDetailUnavailable(String recordId) {
        notificationRejectedRecordId = null;
        restoredRejectedDetailRecordId = null;
        Toast.makeText(
            this,
            R.string.task_detail_unavailable,
            Toast.LENGTH_SHORT
        ).show();
    }

    private void activateWaitingController() {
        initializeWaitingController();
        pendingActive = true;
        if (rejectedController != null) {
            rejectedController.setActive(true);
        }
        routeRejectedNotificationWhenReady();
        restoreRejectedDetailIfReady();
        pendingClient.setConnectionListener(connected -> {
            if (connected) {
                runOnUiThread(() -> {
                    if (isPendingUiActive()) {
                        refreshPendingProcesses();
                    }
                });
            }
        });
        if (pendingClient.isConnected()) {
            refreshPendingProcesses();
        } else {
            try {
                ensureTranslationService();
                pendingClient.bind();
            } catch (RuntimeException error) {
                showPendingOperationFailure(error);
            }
            renderPendingProcesses();
        }
        if (rejectedController != null) {
            rejectedController.refresh();
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        PrimaryNavigation.animateContentOnResume(this);
        if (stylePreview) {
            return;
        }
        captureNotificationIntent(getIntent());
        if (!managementOnly) {
            refreshCaptureControl();
        }
        summaryRecoveryWaitAttempts = 0;
        summaryRecoveryUnavailable = false;
        if (!busy && !submitted) {
            refreshJobs();
        }
        if (taskTab == TAB_WAITING && pendingClient != null) {
            activateWaitingController();
        }
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        boolean nextStylePreview = StylePreview.isEnabled(intent);
        boolean nextManagementOnly = intent.getBooleanExtra(
            EXTRA_MANAGEMENT_ONLY,
            false
        );
        if (nextStylePreview != stylePreview
            || nextManagementOnly != managementOnly) {
            setIntent(intent);
            recreate();
            return;
        }
        setIntent(intent);
        notificationRequestId = nonEmptyExtra(
            intent,
            EXTRA_NOTIFICATION_REQUEST_ID
        );
        notificationRejectedRecordId = nonEmptyExtra(
            intent,
            EXTRA_NOTIFICATION_REJECTED_RECORD_ID
        );
        intent.removeExtra(EXTRA_NOTIFICATION_REQUEST_ID);
        intent.removeExtra(EXTRA_NOTIFICATION_REJECTED_RECORD_ID);
        if (notificationRequestId != null || notificationRejectedRecordId != null) {
            clearRestoredDetailTargets();
        }
        routeRejectedNotificationWhenReady();
        captureNotificationIntent(intent);
    }

    @Override
    protected void onStart() {
        super.onStart();
        if (stylePreview) {
            return;
        }
        jobStore.setQueueListener(queueListener);
        if (!managementOnly && translationJobClient != null) {
            activeUiGeneration++;
            if (stopInFlight) {
                stopInFlight = false;
                if (busy) {
                    setBusy(false);
                }
            }
        }
        if (taskTab == TAB_WAITING && pendingClient != null) {
            pendingActive = true;
            if (rejectedController != null) {
                rejectedController.setActive(true);
            }
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
        if (stylePreview) {
            super.onStop();
            return;
        }
        jobStore.clearQueueListener(queueListener);
        captureControlGeneration++;
        captureControlBusy = false;
        dismissControlDialog();
        if (!managementOnly && translationJobClient != null) {
            activeUiGeneration++;
            translationJobClient.unbind();
        }
        if (pendingClient != null) {
            pendingActive = false;
            if (rejectedController != null) {
                rejectedController.setActive(false);
            }
            pendingRefreshGeneration++;
            pendingClient.setConnectionListener(null);
            pendingClient.unbind();
        }
        super.onStop();
    }

    @Override
    protected void onSaveInstanceState(Bundle outState) {
        if (taskListContent != null
            && taskListContent.getVisibility() == View.VISIBLE) {
            taskListScrollY = taskListContent.getScrollY();
        }
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
        outState.putInt(STATE_TASK_TAB, taskTab);
        outState.putString(STATE_TASK_SEARCH_QUERY, taskSearchQuery);
        outState.putString(STATE_TASK_SCOPE, taskScope);
        outState.putString(STATE_TASK_SORT, taskSort);
        outState.putStringArrayList(
            STATE_OPEN_TASK_SECTIONS,
            new ArrayList<>(openTaskSections)
        );
        outState.putInt(STATE_TASK_LIST_SCROLL_Y, taskListScrollY);
        outState.putBoolean(STATE_TASK_DETAIL_OPEN, taskDetailOpen);
        if (notificationRequestId != null) {
            outState.putString(
                STATE_NOTIFICATION_REQUEST_ID,
                notificationRequestId
            );
        }
        if (notificationRejectedRecordId != null) {
            outState.putString(
                STATE_NOTIFICATION_REJECTED_RECORD_ID,
                notificationRejectedRecordId
            );
        }
        if (taskDetailOpen) {
            outState.putString(
                STATE_TASK_DETAIL_PAGE,
                detailPage.name()
            );
            if (detailPage == DetailPage.TASK && detailTask != null) {
                String requestId = nonEmptyString(detailTask.requestId);
                if (requestId != null) {
                    outState.putString(
                        STATE_TASK_DETAIL_REQUEST_ID,
                        requestId
                    );
                }
            } else if (detailPage == DetailPage.PENDING) {
                String pendingKey = nonEmptyString(detailPendingKey);
                if (pendingKey == null && detailPendingRecord != null) {
                    pendingKey = nonEmptyString(
                        detailPendingRecord.optString("pending_key", "")
                    );
                }
                if (pendingKey != null) {
                    outState.putString(
                        STATE_TASK_DETAIL_PENDING_KEY,
                        pendingKey
                    );
                }
            } else if (detailPage == DetailPage.REJECTED
                && detailRejectedRecord != null) {
                String recordId = nonEmptyString(
                    detailRejectedRecord.optString("record_id", "")
                );
                if (recordId != null) {
                    outState.putString(
                        STATE_TASK_DETAIL_REJECTED_ID,
                        recordId
                    );
                }
            }
        }
        super.onSaveInstanceState(outState);
    }

    @Override
    public void onBackPressed() {
        if (stylePreview) {
            if (taskDetailOpen) {
                closeTaskDetails();
            } else {
                finish();
            }
            return;
        }
        if (taskDetailOpen) {
            closeTaskDetails();
            return;
        }
        confirmCancelAndFinish();
    }

    @Override
    protected void onDestroy() {
        summaryRecoveryHandler.removeCallbacks(summaryRecoveryRefresh);
        if (rejectedController != null) {
            rejectedController.close();
        }
        ioExecutor.shutdownNow();
        activeUiGeneration++;
        captureControlGeneration++;
        captureControlBusy = false;
        dismissControlDialog();
        pendingActive = false;
        pendingRefreshGeneration++;
        if (pendingClient != null) {
            pendingClient.close();
        }
        if (translationJobClient != null) {
            translationJobClient.close();
        }
        if (pendingMoveController != null) {
            pendingMoveController.close();
        }
        super.onDestroy();
    }

    private void refreshCaptureControl() {
        if (managementOnly || stylePreview) {
            return;
        }
        capturePaused = RuntimeControlStore.isCapturePaused(this);
        int icon = capturePaused
            ? R.drawable.ic_task_play
            : R.drawable.ic_task_pause;
        String description = getString(
            capturePaused
                ? R.string.capture_control_resume
                : R.string.capture_control_pause
        );
        if (captureControlButton != null) {
            captureControlButton.setIconResource(icon);
            captureControlButton.setContentDescription(description);
            captureControlButton.setText("");
            captureControlButton.setEnabled(
                !busy && !captureControlBusy && !isFinishing()
            );
        }
        MaterialToolbar toolbar = findViewById(
            R.id.toolbar_translation_queue
        );
        if (toolbar != null && !taskDetailOpen) {
            toolbar.setNavigationIcon(icon);
            toolbar.setNavigationContentDescription(description);
            toolbar.setNavigationOnClickListener(view ->
                onCaptureControlClicked()
            );
            toolbar.getNavigationIcon().setAlpha(
                !busy && !captureControlBusy && !isFinishing() ? 255 : 128
            );
        }
        handleNotificationCaptureControl();
    }

    private void captureNotificationIntent(Intent intent) {
        if (intent == null
            || !intent.getBooleanExtra(
                EXTRA_NOTIFICATION_CAPTURE_CONTROL,
                false
            )) {
            return;
        }
        notificationCaptureControlPending = true;
        notificationCaptureDesiredPaused = intent.getBooleanExtra(
            EXTRA_NOTIFICATION_CAPTURE_DESIRED_PAUSED,
            false
        );
        // Do not replay the same notification action on a later resume.
        intent.removeExtra(EXTRA_NOTIFICATION_CAPTURE_CONTROL);
        intent.removeExtra(EXTRA_NOTIFICATION_CAPTURE_DESIRED_PAUSED);
        handleNotificationCaptureControl();
    }

    private void handleNotificationCaptureControl() {
        if (!notificationCaptureControlPending
            || managementOnly
            || busy
            || captureControlBusy
            || !isCaptureUiActive()) {
            return;
        }
        boolean currentPaused = RuntimeControlStore.isCapturePaused(this);
        boolean desiredPaused = notificationCaptureDesiredPaused;
        if (currentPaused == desiredPaused) {
            notificationCaptureControlPending = false;
            refreshCaptureControl();
            return;
        }
        if (desiredPaused) {
            // Pause is deliberately confirmed. Keep a pending action if
            // another control dialog is currently visible.
            if (!canShowControlDialog()) {
                return;
            }
            notificationCaptureControlPending = false;
            showCapturePauseDialog();
            return;
        }
        notificationCaptureControlPending = false;
        applyCapturePaused(false);
    }

    private void onCaptureControlClicked() {
        if (managementOnly || busy || captureControlBusy
            || captureControlButton == null || !isCaptureUiActive()) {
            return;
        }
        // The notification action may have changed the durable intent while
        // this Activity was visible.  Read it again at the decision point.
        capturePaused = RuntimeControlStore.isCapturePaused(this);
        refreshCaptureControl();
        if (capturePaused) {
            applyCapturePaused(false);
        } else {
            showCapturePauseDialog();
        }
    }

    private void showCapturePauseDialog() {
        if (!canShowControlDialog()) {
            return;
        }
        AlertDialog dialog = new UiMaterialAlertDialogBuilder(this)
            .setTitle(R.string.capture_control_pause_title)
            .setMessage(R.string.capture_control_pause_message)
            .setNegativeButton(R.string.cancel_action, null)
            .setPositiveButton(
                R.string.capture_control_pause_action,
                (shown, which) -> {
                    releaseControlDialog(shown);
                    applyCapturePaused(true);
                }
            )
            .create();
        showControlDialog(dialog);
    }

    private void applyCapturePaused(boolean paused) {
        if (managementOnly || busy || captureControlBusy
            || !isCaptureUiActive()) {
            return;
        }
        final int generation = captureControlGeneration;
        final android.content.Context appContext = getApplicationContext();
        captureControlBusy = true;
        refreshCaptureControl();
        try {
            ioExecutor.execute(() -> {
                boolean saved = false;
                boolean serviceStarted = false;
                boolean operationFailed = false;
                boolean synchronizationFailed = false;
                try {
                    saved = RuntimeControlStore.trySetCapturePaused(
                        appContext,
                        paused
                    );
                } catch (RuntimeException error) {
                    operationFailed = true;
                }
                if (saved) {
                    try {
                        TranslationControlReceiver.CaptureControlWakeResult
                            wakeResult = TranslationControlReceiver
                                .wakeTranslationServiceAndRefresh(appContext);
                        serviceStarted = wakeResult.isServiceStarted();
                        synchronizationFailed =
                            !wakeResult.isNotificationRefreshed();
                    } catch (RuntimeException error) {
                        synchronizationFailed = true;
                    }
                } else if (!operationFailed) {
                    try {
                        // Refresh from the current preference view even when
                        // SharedPreferences reports a failed commit.
                        TranslationStatusNotification.refresh(appContext);
                    } catch (RuntimeException error) {
                        synchronizationFailed = true;
                    }
                }
                final boolean savedResult = saved;
                final boolean serviceStartedResult = serviceStarted;
                final boolean operationFailedResult = operationFailed;
                final boolean synchronizationFailedResult = synchronizationFailed;
                runOnUiThread(() -> {
                    if (!isCaptureUiActive(generation)) {
                        return;
                    }
                    captureControlBusy = false;
                    refreshCaptureControl();
                    if (operationFailedResult || !savedResult) {
                        Toast.makeText(
                            this,
                            R.string.capture_control_save_failed,
                            Toast.LENGTH_LONG
                        ).show();
                        return;
                    }
                    if (synchronizationFailedResult && serviceStartedResult) {
                        Toast.makeText(
                            this,
                            R.string.capture_control_saved_sync_failed,
                            Toast.LENGTH_LONG
                        ).show();
                        return;
                    }
                    Toast.makeText(
                        this,
                        serviceStartedResult
                            ? paused
                                ? R.string.capture_control_pause_saved
                                : R.string.capture_control_resume_saved
                            : R.string.capture_control_saved_waiting_service,
                        Toast.LENGTH_LONG
                    ).show();
                });
            });
        } catch (RuntimeException error) {
            captureControlBusy = false;
            refreshCaptureControl();
            Toast.makeText(
                this,
                R.string.capture_control_save_failed,
                Toast.LENGTH_LONG
            ).show();
        }
    }

    private boolean isCaptureUiActive() {
        return !isDestroyed()
            && !isFinishing()
            && !managementOnly;
    }

    private boolean isCaptureUiActive(int generation) {
        return isCaptureUiActive()
            && generation == captureControlGeneration;
    }

    private boolean canShowControlDialog() {
        return !isDestroyed()
            && !isFinishing()
            && (controlDialog == null || !controlDialog.isShowing());
    }

    private void showControlDialog(AlertDialog dialog) {
        if (!canShowControlDialog()) {
            return;
        }
        controlDialog = dialog;
        controlDialog.setOnDismissListener(dismissed -> {
            if (controlDialog == dismissed) {
                controlDialog = null;
            }
        });
        controlDialog.show();
    }

    private void releaseControlDialog(DialogInterface dialog) {
        if (controlDialog == dialog) {
            controlDialog = null;
        }
    }

    private void dismissControlDialog() {
        if (controlDialog != null) {
            controlDialog.dismiss();
            controlDialog = null;
        }
    }

    private void refreshJobs() {
        if (stylePreview) {
            return;
        }
        final int generation = ++refreshGeneration;
        taskSnapshotReady = false;
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
            final List<TranslationJobStore.ReviewJob> loadedActiveJobs =
                new ArrayList<>();
            final List<TranslationJobStore.ReviewJob> loadedCanceled;
            final List<TranslationJobStore.TerminalJob> loadedFailed;
            final List<TranslationJobStore.TerminalJob> loadedDelivery;
            final List<SummaryJobStore.RecoveryJob> loadedSummary;
            final List<SummaryJobStore.FailedJob> loadedFailedSummary;
            final List<TranslationTaskExecutor.BlockedJob> loadedUserAction;
            final SummaryJobStore loadedSummaryStore;
            final boolean loadedRecoveryReady;
            final Map<String, String> loadedSummaryNames = new HashMap<>();
            try {
                if (!managementOnly) {
                    for (TranslationJobStore.ReviewJob job :
                        jobStore.listReviewJobs()) {
                        if (TranslationJobStatus.RUNNING.wireValue().equals(
                            job.getStatus()
                        )) {
                            loadedActiveJobs.add(job);
                        }
                    }
                }
                loadedFailed = jobStore.listRetainedFailedJobs();
                loadedCanceled = managementOnly
                    ? new ArrayList<>() : jobStore.listCanceledJobs();
                loadedDelivery = managementOnly
                    ? new ArrayList<>()
                    : jobStore.listCompletedJobs();
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
                activeJobs = loadedActiveJobs;
                canceledJobs = loadedCanceled;
                failedJobs = loadedFailed;
                deliveryJobs = loadedDelivery;
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
                taskSnapshotReady = true;
                renderJobs();
                routeNotificationTarget();
                restoreSavedTaskDetailIfReady();
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
        if (taskDetailOpen) {
            renderTaskDetail();
        } else {
            renderTaskPage();
        }
    }

    private void routeNotificationTarget() {
        if (notificationRequestId == null || managementOnly) {
            return;
        }
        String requestId = notificationRequestId;
        notificationRequestId = null;
        UiTask target = findUiTask(requestId);
        if (target == null) {
            Toast.makeText(
                this,
                R.string.task_detail_unavailable,
                Toast.LENGTH_SHORT
            ).show();
            return;
        }
        showTaskDetails(target);
    }

    private void restoreSavedTaskDetailIfReady() {
        if (taskDetailOpen || restoredTaskDetailRequestId == null) {
            return;
        }
        String requestId = restoredTaskDetailRequestId;
        UiTask target = findUiTask(requestId);
        if (target != null) {
            restoredTaskDetailRequestId = null;
            showTaskDetails(target);
            return;
        }
        if (!managementOnly && !summaryRecoveryReady
            && !summaryRecoveryUnavailable) {
            return;
        }
        restoredTaskDetailRequestId = null;
        Toast.makeText(
            this,
            R.string.task_detail_unavailable,
            Toast.LENGTH_SHORT
        ).show();
    }

    private void restorePendingDetailIfReady() {
        if (taskDetailOpen || restoredPendingDetailKey == null
            || !isPendingUiActive() || !pendingReady || pendingLoading) {
            return;
        }
        String pendingKey = restoredPendingDetailKey;
        boolean found = false;
        for (JSONObject entry : pendingProcesses) {
            if (pendingKey.equals(entry.optString("pending_key", ""))) {
                found = true;
                break;
            }
        }
        restoredPendingDetailKey = null;
        if (!found) {
            Toast.makeText(
                this,
                R.string.task_detail_unavailable,
                Toast.LENGTH_SHORT
            ).show();
            return;
        }
        showPendingDetails(pendingKey);
    }

    private void restoreRejectedDetailIfReady() {
        if (taskDetailOpen || restoredRejectedDetailRecordId == null
            || rejectedController == null || !isPendingUiActive()) {
            return;
        }
        String recordId = restoredRejectedDetailRecordId;
        restoredRejectedDetailRecordId = null;
        rejectedController.openRecordWhenLoaded(recordId);
    }

    private void routeRejectedNotificationWhenReady() {
        if (notificationRejectedRecordId == null
            || rejectedController == null || !isPendingUiActive()) {
            return;
        }
        rejectedController.openRecordWhenLoaded(notificationRejectedRecordId);
    }

    private static String nonEmptyExtra(Intent intent, String key) {
        if (intent == null) {
            return null;
        }
        return nonEmptyString(intent.getStringExtra(key));
    }

    private static String nonEmptyString(String value) {
        return value == null || value.trim().isEmpty() ? null : value;
    }

    private void clearRestoredDetailTargets() {
        restoredTaskDetailRequestId = null;
        restoredPendingDetailKey = null;
        restoredRejectedDetailRecordId = null;
    }

    private void renderTaskPage() {
        if (taskDetailOpen) {
            renderTaskDetail();
            return;
        }
        if (taskSectionsContainer == null) {
            return;
        }
        updateTaskTabs();
        boolean waiting = taskTab == TAB_WAITING;
        taskSectionsContainer.setVisibility(waiting ? View.GONE : View.VISIBLE);
        updateTaskRecoveryAction(waiting);
        if (waitingContent != null) {
            waitingContent.setVisibility(waiting ? View.VISIBLE : View.GONE);
        }
        if (pendingSection != null) {
            pendingSection.setVisibility(waiting ? View.VISIBLE : View.GONE);
        }
        if (rejectedSection != null) {
            rejectedSection.setVisibility(waiting ? View.VISIBLE : View.GONE);
        }
        if (taskSearchInput != null) {
            taskSearchInput.setHint(waiting
                ? R.string.task_search_waiting_hint
                : R.string.task_search_tasks_hint);
        }
        if (taskSortButton != null) {
            taskSortButton.setVisibility(waiting ? View.GONE : View.VISIBLE);
        }
        if (taskMoreButton != null) {
            taskMoreButton.setVisibility(waiting ? View.GONE : View.VISIBLE);
        }
        if (waiting) {
            if (stylePreview) {
                renderStylePreviewWaiting();
                return;
            }
            if (pendingClient != null && !pendingActive) {
                activateWaitingController();
            }
            renderPendingProcesses();
            if (rejectedController != null) {
                rejectedController.setSearchQuery(taskSearchQuery);
                rejectedController.refresh();
            }
            updateWaitingSummary();
            restoreWaitingListScrollIfReady();
            return;
        }
        taskSectionsContainer.removeAllViews();
        List<UiTask> all = collectUiTasks();
        List<UiTask> filtered = new ArrayList<>();
        for (UiTask task : all) {
            if (!matchesTaskTab(task) || !matchesTaskScope(task)
                || !matchesTaskSearch(task)) {
                continue;
            }
            filtered.add(task);
        }
        sortUiTasks(filtered);
        taskScopeSummary.setVisibility(View.GONE);

        if (filtered.isEmpty() && !taskSearchQuery.trim().isEmpty()) {
            TextView empty = taskText(
                getString(R.string.task_search_no_results),
                10,
                R.color.het_on_surface_muted
            );
            empty.setPadding(dp(10), dp(14), dp(10), dp(14));
            addDetailSection(taskSectionsContainer, empty);
            restoreTaskListScrollIfNeeded();
            return;
        }

        if ("all".equals(taskScope)) {
            addTaskSection(
                "unfinished",
                R.string.task_section_unfinished,
                filterSection(filtered, "unfinished"),
                openTaskSections.contains("unfinished")
            );
            addTaskSection(
                "completed",
                R.string.task_section_completed,
                filterSection(filtered, "completed"),
                openTaskSections.contains("completed")
            );
            addTaskSection(
                "recent",
                R.string.task_section_recent,
                filtered,
                openTaskSections.contains("recent")
            );
        } else {
            int title = "unfinished".equals(taskScope)
                ? R.string.task_section_unfinished
                : "action-needed".equals(taskScope)
                    ? R.string.task_section_action_needed
                    : R.string.task_section_completed;
            addTaskSection(
                taskScope,
                title,
                filtered,
                openTaskSections.contains(taskScope)
            );
        }
        restoreTaskListScrollIfNeeded();
    }

    private void renderStylePreviewWaiting() {
        if (pendingSection != null) {
            pendingSection.setVisibility(View.GONE);
        }
        if (rejectedSection != null) {
            rejectedSection.setVisibility(View.GONE);
        }
        if (waitingCount != null) {
            waitingCount.setText(getString(
                R.string.task_waiting_count,
                waitingPendingVisibleCount
            ));
        }
        if (waitingEmptyMessage != null) {
            waitingEmptyMessage.setText(R.string.style_preview_waiting_body);
            waitingEmptyMessage.setVisibility(View.VISIBLE);
        }
    }

    private void restoreTaskListScrollIfNeeded() {
        if (!restoreTaskListScrollOnNextRender || taskListContent == null
            || (taskTab != TAB_WAITING && !taskSnapshotReady)) {
            return;
        }
        restoreTaskListScrollOnNextRender = false;
        taskListContent.post(() -> {
            if (!taskDetailOpen && taskListContent != null) {
                taskListContent.scrollTo(0, taskListScrollY);
            }
        });
    }

    private void restoreWaitingListScrollIfReady() {
        if (!waitingPendingLoaded || !waitingRejectedLoaded
            || waitingPendingUnavailable) {
            return;
        }
        restoreTaskListScrollIfNeeded();
    }

    private List<UiTask> collectUiTasks() {
        if (stylePreview) {
            return new ArrayList<>(stylePreviewTasks);
        }
        List<UiTask> result = new ArrayList<>();
        Set<String> requestIds = new HashSet<>();
        Set<String> blockedIds = new HashSet<>();
        for (TranslationTaskExecutor.BlockedJob job : userActionJobs) {
            blockedIds.add(job.getRequestId());
        }
        Set<String> canceledIds = new HashSet<>();
        for (TranslationJobStore.ReviewJob job : canceledJobs) {
            canceledIds.add(job.getRequestId());
        }
        for (TranslationJobStore.HeldQueuedJob job : jobs) {
            if (blockedIds.contains(job.getRequestId()) || canceledIds.contains(job.getRequestId())
                || !requestIds.add(job.getRequestId())) {
                continue;
            }
            result.add(new UiTask(
                UiTaskKind.HELD,
                job.getRequestId(),
                job.getScene(),
                getString(R.string.task_object_scene),
                job.getTargetLanguage(),
                getString(R.string.task_status_queued),
                getString(R.string.task_reason_queued),
                job.getCreatedAt(),
                true,
                false,
                false,
                job
            ));
            requestIds.add(job.getRequestId());
        }
        for (TranslationJobStore.ReviewJob job : activeJobs) {
            if (blockedIds.contains(job.getRequestId()) || canceledIds.contains(job.getRequestId())
                || !requestIds.add(job.getRequestId())) {
                continue;
            }
            result.add(new UiTask(
                UiTaskKind.ACTIVE,
                job.getRequestId(),
                job.getScene(),
                getString(R.string.task_object_scene),
                "",
                getString(R.string.task_status_running),
                getString(R.string.task_reason_running),
                0L,
                true,
                false,
                true,
                job
            ));
            requestIds.add(job.getRequestId());
        }
        for (TranslationJobStore.TerminalJob job : deliveryJobs) {
            if (!requestIds.add(job.getRequestId())) {
                continue;
            }
            boolean deliveryPending = job.requiresDelivery();
            boolean completed = job.getKind() == TerminalOutcome.Kind.COMPLETED
                && job.isLocalSceneSaved()
                && job.getDeliveryState() == TerminalOutcome.DeliveryState.ACKNOWLEDGED;
            boolean actionNeeded = job.getKind() == TerminalOutcome.Kind.FAILED
                || !job.isLocalSceneSaved();
            result.add(new UiTask(
                UiTaskKind.TERMINAL,
                job.getRequestId(),
                job.getScene(),
                getString(R.string.task_object_scene),
                job.getTargetLanguage(),
                terminalStatus(job),
                terminalReason(job),
                job.getUpdatedAt(),
                true,
                completed,
                actionNeeded,
                job
            ));
        }
        for (TranslationJobStore.TerminalJob job : failedJobs) {
            if (!requestIds.add(job.getRequestId())) {
                continue;
            }
            boolean deliveryPending = job.requiresDelivery();
            boolean completed = job.getKind() == TerminalOutcome.Kind.COMPLETED
                && job.isLocalSceneSaved()
                && job.getDeliveryState() == TerminalOutcome.DeliveryState.ACKNOWLEDGED;
            boolean actionNeeded = job.getKind() == TerminalOutcome.Kind.FAILED
                || !job.isLocalSceneSaved();
            result.add(new UiTask(
                UiTaskKind.TERMINAL,
                job.getRequestId(),
                job.getScene(),
                getString(R.string.task_object_scene),
                job.getTargetLanguage(),
                terminalStatus(job),
                terminalReason(job),
                job.getUpdatedAt(),
                true,
                completed,
                actionNeeded,
                job
            ));
        }
        for (TranslationTaskExecutor.BlockedJob job : userActionJobs) {
            if (canceledIds.contains(job.getRequestId()) || !requestIds.add(job.getRequestId())) {
                continue;
            }
            result.add(new UiTask(
                UiTaskKind.USER_ACTION,
                job.getRequestId(),
                job.getScene(),
                getString(R.string.task_object_scene),
                "",
                getString(R.string.task_status_action_needed),
                job.getReason(),
                0L,
                true,
                false,
                true,
                job
            ));
        }
        for (TranslationJobStore.ReviewJob job : canceledJobs) {
            if (!requestIds.add(job.getRequestId())) {
                continue;
            }
            result.add(new UiTask(UiTaskKind.CANCELED, job.getRequestId(), job.getScene(),
                getString(R.string.task_object_scene), "",
                getString(R.string.task_status_canceled),
                getString(R.string.task_reason_canceled), 0L, true, false, false, job));
        }
        for (SummaryJobStore.RecoveryJob job : summaryJobs) {
            result.add(new UiTask(
                UiTaskKind.SUMMARY_RECOVERY,
                job.getRequestId(),
                summaryOwnerName(job),
                "group".equals(job.getOwnerType())
                    ? getString(R.string.task_object_group)
                    : getString(R.string.task_object_context),
                job.getTargetLang(),
                getString(R.string.task_status_summary_queued),
                getString(R.string.task_reason_summary_recovery),
                job.getCreatedAt(),
                false,
                false,
                true,
                job
            ));
        }
        for (SummaryJobStore.FailedJob job : failedSummaryJobs) {
            result.add(new UiTask(
                UiTaskKind.SUMMARY_FAILED,
                job.getRequestId(),
                summaryOwnerName(job),
                "group".equals(job.getOwnerType())
                    ? getString(R.string.task_object_group)
                    : getString(R.string.task_object_context),
                job.getTargetLang(),
                getString(R.string.task_status_summary_failed),
                job.getErrorMessage(),
                job.getUpdatedAt(),
                false,
                false,
                true,
                job
            ));
        }
        return result;
    }

    private String terminalStatus(TranslationJobStore.TerminalJob job) {
        if (job.getKind() == TerminalOutcome.Kind.COMPLETED) {
            if (!job.isLocalSceneSaved()) {
                return getString(R.string.task_status_local_save_pending);
            }
            if (job.getDeliveryState() == TerminalOutcome.DeliveryState.NOT_REQUIRED) {
                return getString(R.string.task_status_local_only);
            }
            return job.getDeliveryState()
                == TerminalOutcome.DeliveryState.ACKNOWLEDGED
                ? getString(R.string.task_status_acknowledged)
                : getString(R.string.task_status_delivery_pending);
        }
        return job.isSceneValidationFailure()
            ? getString(R.string.task_status_scene_damaged)
            : getString(R.string.task_status_failed);
    }

    private String terminalReason(TranslationJobStore.TerminalJob job) {
        if (job.getKind() != TerminalOutcome.Kind.COMPLETED) {
            return friendlyFailureSummary(job);
        }
        if (!job.isLocalSceneSaved()) {
            return getString(R.string.task_reason_local_save_pending)
                + (job.getLocalSceneError().isEmpty() ? "" : "\n" + job.getLocalSceneError());
        }
        if (job.getDeliveryState() == TerminalOutcome.DeliveryState.NOT_REQUIRED) {
            return getString(R.string.task_reason_local_only);
        }
        return job.getDeliveryState()
            == TerminalOutcome.DeliveryState.ACKNOWLEDGED
            ? getString(R.string.task_reason_completed)
            : getString(R.string.task_reason_delivery);
    }

    private boolean matchesTaskTab(UiTask task) {
        return taskTab == TAB_ALL
            || (taskTab == TAB_TRANSLATION && task.translation)
            || (taskTab == TAB_SUMMARY && !task.translation);
    }

    private boolean matchesTaskScope(UiTask task) {
        if ("unfinished".equals(taskScope)) {
            return task.unfinished();
        }
        if ("action-needed".equals(taskScope)) {
            return task.actionNeeded;
        }
        if ("completed".equals(taskScope)) {
            return task.completed;
        }
        return true;
    }

    private boolean matchesTaskSearch(UiTask task) {
        String query = taskSearchQuery == null
            ? ""
            : taskSearchQuery.trim().toLowerCase(Locale.ROOT);
        if (query.isEmpty()) {
            return true;
        }
        String haystack = (task.title + " " + task.objectType + " "
            + task.language + " " + task.status + " " + task.reason)
            .toLowerCase(Locale.ROOT);
        return haystack.contains(query);
    }

    private List<UiTask> filterSection(List<UiTask> items, String section) {
        List<UiTask> result = new ArrayList<>();
        for (UiTask task : items) {
            if ("unfinished".equals(section) && task.unfinished()
                || "completed".equals(section) && task.completed) {
                result.add(task);
            }
        }
        return result;
    }

    private void sortUiTasks(List<UiTask> items) {
        items.sort((left, right) -> {
            if ("name".equals(taskSort)) {
                int name = left.title.compareToIgnoreCase(right.title);
                if (name != 0) {
                    return name;
                }
            } else if ("action".equals(taskSort)) {
                int action = Boolean.compare(right.actionNeeded, left.actionNeeded);
                if (action != 0) {
                    return action;
                }
            } else if ("completion".equals(taskSort)) {
                int state = Boolean.compare(left.completed, right.completed);
                if (state != 0) {
                    return state;
                }
            }
            return Long.compare(right.timestamp, left.timestamp);
        });
    }

    private void addTaskSection(
        String id,
        int titleRes,
        List<UiTask> tasks,
        boolean open
    ) {
        LinearLayout section = new LinearLayout(this);
        section.setOrientation(LinearLayout.VERTICAL);
        section.setBackgroundResource(R.drawable.bg_static_task_section);
        section.setPadding(dp(10), 0, dp(10), 0);
        LinearLayout.LayoutParams sectionParams = new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        );
        sectionParams.bottomMargin = dp(10);
        taskSectionsContainer.addView(section, sectionParams);

        LinearLayout header = new LinearLayout(this);
        header.setBaselineAligned(false);
        header.setMinimumHeight(dp(48));
        header.setGravity(Gravity.CENTER_VERTICAL);
        ImageView marker = new ImageView(this);
        marker.setImageResource(R.drawable.ic_task_chevron);
        marker.setImageTintList(ColorStateList.valueOf(
            ContextCompat.getColor(this, R.color.het_primary_strong)
        ));
        marker.setScaleType(ImageView.ScaleType.CENTER_INSIDE);
        TextView title = taskText(getString(titleRes), 12, R.color.het_on_surface);
        title.setTypeface(title.getTypeface(), android.graphics.Typeface.BOLD);
        title.setGravity(Gravity.CENTER_VERTICAL);
        title.setIncludeFontPadding(false);
        TextView count = taskText(getString(R.string.task_section_count, tasks.size()), 10, R.color.het_on_surface_muted);
        count.setGravity(Gravity.CENTER);
        count.setIncludeFontPadding(false);
        count.setMinWidth(dp(26));
        count.setMinHeight(dp(22));
        count.setPadding(dp(7), dp(3), dp(7), dp(3));
        count.setBackgroundResource(R.drawable.bg_static_task_count);
        LinearLayout.LayoutParams markerParams = new LinearLayout.LayoutParams(dp(22), dp(48));
        markerParams.gravity = Gravity.CENTER_VERTICAL;
        header.addView(marker, markerParams);
        LinearLayout.LayoutParams titleParams = new LinearLayout.LayoutParams(0, dp(48), 1f);
        header.addView(title, titleParams);
        LinearLayout.LayoutParams countParams = new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT,
            dp(22)
        );
        countParams.gravity = Gravity.CENTER_VERTICAL;
        header.addView(count, countParams);
        section.addView(header);
        LinearLayout rows = new LinearLayout(this);
        rows.setOrientation(LinearLayout.VERTICAL);
        rows.setPadding(0, dp(8), 0, dp(8));
        rows.setVisibility(open ? View.VISIBLE : View.GONE);
        if (open) {
            marker.setRotation(90f);
        }
        header.setOnClickListener(view -> {
            boolean nextOpen = rows.getVisibility() != View.VISIBLE;
            rows.setVisibility(nextOpen ? View.VISIBLE : View.GONE);
            marker.setRotation(nextOpen ? 90f : 0f);
            if (nextOpen) {
                openTaskSections.add(id);
            } else {
                openTaskSections.remove(id);
            }
        });
        section.addView(rows);
        if (tasks.isEmpty()) {
            TextView empty = taskText(
                getString(R.string.task_section_empty),
                10,
                R.color.het_on_surface_muted
            );
            empty.setPadding(dp(10), dp(12), dp(10), dp(12));
            rows.addView(empty);
        } else {
            for (UiTask task : tasks) {
                rows.addView(createTaskRow(task));
            }
        }
    }

    private View createTaskRow(UiTask task) {
        MaterialCardView card = new MaterialCardView(this);
        card.setCardBackgroundColor(ContextCompat.getColor(this, R.color.het_surface_container));
        card.setRadius(dp(12));
        card.setCardElevation(0f);
        card.setStrokeWidth(dp(1));
        card.setStrokeColor(ContextCompat.getColor(this, R.color.het_outline_soft));
        LinearLayout.LayoutParams cardParams = new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        );
        cardParams.bottomMargin = dp(7);
        card.setLayoutParams(cardParams);
        LinearLayout body = new LinearLayout(this);
        body.setOrientation(LinearLayout.VERTICAL);
        body.setPadding(dp(11), dp(10), dp(11), dp(8));
        card.addView(body);

        LinearLayout top = new LinearLayout(this);
        top.setGravity(Gravity.CENTER_VERTICAL);
        ImageView icon = new ImageView(this);
        icon.setImageResource(
            task.translation
                ? R.drawable.ic_task_translate
                : R.drawable.ic_task_summary
        );
        icon.setImageTintList(ColorStateList.valueOf(ContextCompat.getColor(
            this,
            R.color.het_on_primary_container
        )));
        icon.setScaleType(ImageView.ScaleType.CENTER);
        icon.setBackgroundResource(R.drawable.bg_static_task_status);
        top.addView(icon, new LinearLayout.LayoutParams(dp(28), dp(28)));
        LinearLayout copy = new LinearLayout(this);
        copy.setOrientation(LinearLayout.VERTICAL);
        copy.setPadding(dp(9), 0, dp(8), 0);
        TextView title = taskText(task.title, 12, R.color.het_on_surface);
        title.setTypeface(title.getTypeface(), android.graphics.Typeface.BOLD);
        title.setMaxLines(1);
        title.setEllipsize(android.text.TextUtils.TruncateAt.END);
        TextView meta = taskText(
            task.typeLabel() + " · " + task.objectType,
            10,
            R.color.het_on_surface_muted
        );
        meta.setMaxLines(1);
        meta.setEllipsize(android.text.TextUtils.TruncateAt.END);
        LinearLayout.LayoutParams metaParams = new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        );
        metaParams.topMargin = dp(3);
        copy.addView(title);
        copy.addView(meta, metaParams);
        TextView time = taskText(
            taskDate(task.timestamp),
            10,
            R.color.het_on_surface_muted
        );
        time.setMaxLines(1);
        time.setEllipsize(android.text.TextUtils.TruncateAt.END);
        LinearLayout.LayoutParams timeParams = new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        );
        timeParams.topMargin = dp(4);
        copy.addView(time, timeParams);
        copy.setMinimumHeight(dp(42));
        top.addView(copy, new LinearLayout.LayoutParams(
            0,
            LinearLayout.LayoutParams.WRAP_CONTENT,
            1f
        ));
        TextView status = taskText(task.status, 9, statusColor(task));
        status.setGravity(Gravity.CENTER);
        status.setPadding(dp(8), dp(4), dp(8), dp(4));
        status.setBackgroundResource(R.drawable.bg_static_task_status);
        top.addView(status, new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT,
            dp(28)
        ));
        body.addView(top);

        card.setOnClickListener(view -> showTaskDetails(task));
        card.setEnabled(!busy && !repairingStartupJobs);
        return card;
    }

    private void confirmDeleteTask(UiTask task) {
        if (stylePreview || busy || repairingStartupJobs
            || task == null || isFinishing()) {
            return;
        }
        new UiMaterialAlertDialogBuilder(this)
            .setTitle(R.string.task_action_delete)
            .setMessage(R.string.task_delete_message)
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(R.string.task_action_delete, (dialog, which) -> {
                setBusy(true);
                ioExecutor.execute(() -> {
                    try {
                        if (task.translation) {
                            TranslationTaskExecutor executor = TranslationService.getActiveTaskExecutor();
                            if (executor != null) {
                                executor.deleteTask(task.requestId);
                            } else {
                                jobStore.requestCancellation(task.requestId);
                                jobStore.deleteTask(task.requestId);
                            }
                        } else {
                            SummaryJobStore activeStore = TranslationService.getActiveSummaryRecoveryStore();
                            (activeStore == null ? summaryJobStore : activeStore)
                                .deleteTaskForManagement(task.requestId);
                        }
                        runOnUiThread(() -> {
                            if (isDestroyed() || isFinishing()) {
                                return;
                            }
                            setBusy(false);
                            selectedRequestIds.remove(task.requestId);
                            selectedSummaryRequestIds.remove(task.requestId);
                            if (detailTask != null && task.requestId.equals(detailTask.requestId)) {
                                closeTaskDetails();
                            }
                            TranslationStatusNotification.refresh(this);
                            refreshJobs();
                        });
                    } catch (TranslationJobStore.ManagementMutationBusyException error) {
                        showOperationFailure(new IllegalStateException(getString(R.string.task_delete_delivery_busy)));
                    } catch (Exception error) {
                        showOperationFailure(error);
                    }
                });
            })
            .show();
    }

    private void confirmRerunCanceledTask(UiTask task) {
        if (stylePreview || managementOnly || busy || repairingStartupJobs
            || task == null || task.kind != UiTaskKind.CANCELED) {
            return;
        }
        new UiMaterialAlertDialogBuilder(this)
            .setTitle(R.string.task_action_rerun_canceled)
            .setMessage(R.string.task_rerun_canceled_message)
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(android.R.string.ok, (dialog, which) -> {
                setBusy(true);
                ioExecutor.execute(() -> {
                    try {
                        TranslationTaskExecutor executor = TranslationService.getActiveTaskExecutor();
                        if (executor != null) {
                            executor.manageCanceledJob(task.requestId, false);
                        } else {
                            throw new IllegalStateException(getString(R.string.task_rerun_service_required));
                        }
                        runOnUiThread(() -> {
                            if (isDestroyed() || isFinishing()) {
                                return;
                            }
                            setBusy(false);
                            if (detailTask != null && task.requestId.equals(detailTask.requestId)) {
                                closeTaskDetails();
                            }
                            TranslationStatusNotification.refresh(this);
                            refreshJobs();
                        });
                    } catch (Exception error) {
                        showOperationFailure(error);
                    }
                });
            })
            .show();
    }

    private MaterialButton taskActionButton(UiTask task) {
        if (stylePreview) {
            return null;
        }
        MaterialButton button = new MaterialButton(
            this,
            null,
            com.google.android.material.R.attr.materialButtonOutlinedStyle
        );
        button.setAllCaps(false);
        button.setTextSize(12f);
        button.setMinHeight(dp(32));
        button.setPadding(dp(10), 0, dp(10), 0);
        switch (task.kind) {
            case CANCELED:
                button.setText(R.string.task_action_rerun_canceled);
                button.setOnClickListener(view -> confirmRerunCanceledTask(task));
                return button;
            case HELD:
                int selectedIndex = selectedRequestIds.indexOf(task.requestId);
                button.setText(selectedIndex >= 0
                    ? getString(R.string.task_action_ordered, selectedIndex + 1)
                    : getString(R.string.task_action_add_order));
                button.setOnClickListener(view -> toggleSelection(task.requestId));
                return button;
            case ACTIVE:
                button.setText(R.string.task_action_details);
                button.setOnClickListener(view -> showTaskDetails(task));
                return button;
            case TERMINAL:
                TranslationJobStore.TerminalJob terminal =
                    (TranslationJobStore.TerminalJob) task.source;
                if (terminal.getKind() != TerminalOutcome.Kind.FAILED) {
                    return null;
                }
                if (terminal.isSceneValidationFailure()) {
                    button.setText(R.string.translation_job_move_scene_pending);
                    button.setOnClickListener(view -> openManagementForScene(terminal));
                } else {
                    button.setText(R.string.translation_job_retry);
                    button.setOnClickListener(view -> rerunSingle(terminal));
                }
                return button;
            case SUMMARY_RECOVERY:
                int summaryIndex = selectedSummaryRequestIds.indexOf(task.requestId);
                button.setText(summaryIndex >= 0
                    ? getString(R.string.task_action_ordered, summaryIndex + 1)
                    : getString(R.string.task_action_restore));
                button.setOnClickListener(view -> toggleSummarySelection(task.requestId));
                return button;
            case SUMMARY_FAILED:
                button.setText(R.string.summary_failed_retry);
                button.setOnClickListener(view -> retrySummaryFailed(
                    (SummaryJobStore.FailedJob) task.source
                ));
                return button;
            case USER_ACTION:
                button.setText(R.string.user_action_required_retry);
                button.setOnClickListener(view -> retryUserAction(
                    (TranslationTaskExecutor.BlockedJob) task.source
                ));
                return button;
            default:
                return null;
        }
    }

    private void showTaskDetails(UiTask task) {
        if (task == null || isFinishing()) {
            return;
        }
        detailPage = DetailPage.TASK;
        detailTask = task;
        detailPendingRecord = null;
        detailRejectedRecord = null;
        detailPendingKey = null;
        taskDetailOpen = true;
        resetDetailScrollOnNextRender = true;
        showDetailShell(getString(R.string.task_detail_title));
        renderTaskDetail();
    }

    private void showDetailShell(String title) {
        if (taskListContent != null && taskListContent.getVisibility() == View.VISIBLE) {
            taskListScrollY = taskListContent.getScrollY();
        }
        if (taskListContent != null) {
            taskListContent.setVisibility(View.GONE);
        }
        if (taskDetailContent != null) {
            taskDetailContent.setVisibility(View.VISIBLE);
        }
        View toolbarActions = findViewById(R.id.task_toolbar_actions);
        if (toolbarActions != null) {
            toolbarActions.setVisibility(View.GONE);
        }
        if (captureControlButton != null) {
            captureControlButton.setVisibility(View.GONE);
        }
        if (pageActions != null) {
            pageActions.setVisibility(View.GONE);
        }
        View primaryNavigation = findViewById(R.id.primary_navigation);
        if (primaryNavigation != null) {
            primaryNavigation.setVisibility(View.GONE);
        }
        MaterialToolbar toolbar = findViewById(R.id.toolbar_translation_queue);
        toolbar.setNavigationIcon(R.drawable.ic_task_back);
        if (title == null || title.isEmpty()) {
            toolbar.setTitle(R.string.task_detail_title);
        } else {
            toolbar.setTitle(title);
        }
        toolbar.setNavigationContentDescription(R.string.back_action);
        toolbar.setNavigationOnClickListener(view -> closeTaskDetails());
    }

    private void closeTaskDetails() {
        if (!taskDetailOpen) {
            confirmCancelAndFinish();
            return;
        }
        taskDetailOpen = false;
        resetDetailScrollOnNextRender = false;
        detailPage = DetailPage.NONE;
        detailTask = null;
        detailPendingRecord = null;
        detailRejectedRecord = null;
        detailPendingKey = null;
        if (taskDetailContent != null) {
            taskDetailContent.setVisibility(View.GONE);
        }
        if (taskListContent != null) {
            taskListContent.setVisibility(View.VISIBLE);
        }
        View toolbarActions = findViewById(R.id.task_toolbar_actions);
        if (toolbarActions != null) {
            toolbarActions.setVisibility(
                managementOnly ? View.GONE : View.VISIBLE
            );
        }
        if (captureControlButton != null) {
            captureControlButton.setVisibility(View.GONE);
        }
        View primaryNavigation = findViewById(R.id.primary_navigation);
        if (primaryNavigation != null) {
            primaryNavigation.setVisibility(
                managementOnly ? View.GONE : View.VISIBLE
            );
            if (stylePreview) installStylePreviewNavigation();
        }
        MaterialToolbar toolbar = findViewById(R.id.toolbar_translation_queue);
        if (stylePreview) {
            toolbar.setNavigationIcon(R.drawable.ic_task_back);
            toolbar.setTitle(R.string.task_page_title);
            toolbar.setNavigationContentDescription(R.string.back_action);
            toolbar.setNavigationOnClickListener(view -> finish());
        } else if (managementOnly) {
            toolbar.setNavigationIcon(R.drawable.ic_task_back);
            toolbar.setTitle(R.string.translation_job_management_title);
            toolbar.setNavigationContentDescription(R.string.back_action);
            toolbar.setNavigationOnClickListener(view ->
                confirmCancelAndFinish()
            );
        } else {
            toolbar.setTitle(R.string.task_page_title);
            toolbar.setNavigationIcon((android.graphics.drawable.Drawable) null);
            refreshCaptureControl();
        }
        renderTaskPage();
        if (taskListContent != null) {
            taskListContent.post(() -> taskListContent.scrollTo(0, taskListScrollY));
        }
    }

    private void renderTaskDetail() {
        if (!taskDetailOpen || taskDetailContent == null) {
            return;
        }
        LinearLayout container = findViewById(R.id.task_detail_container);
        if (container == null) {
            return;
        }
        container.removeAllViews();
        if (detailPage == DetailPage.TASK && detailTask != null) {
            UiTask current = findUiTask(detailTask.requestId);
            if (current == null) {
                closeTaskDetails();
                Toast.makeText(
                    this,
                    R.string.task_detail_unavailable,
                    Toast.LENGTH_SHORT
                ).show();
                return;
            }
            detailTask = current;
            addTaskDetailPage(container, detailTask);
        } else if (detailPage == DetailPage.PENDING
            && detailPendingRecord != null) {
            addPendingDetailPage(container, detailPendingRecord);
        } else if (detailPage == DetailPage.REJECTED
            && detailRejectedRecord != null) {
            addRejectedDetailPage(container, detailRejectedRecord);
        }
        if (resetDetailScrollOnNextRender) {
            resetDetailScrollOnNextRender = false;
            taskDetailContent.post(() -> taskDetailContent.scrollTo(0, 0));
        }
    }

    private UiTask findUiTask(String requestId) {
        if (requestId == null || requestId.isEmpty()) {
            return null;
        }
        for (UiTask task : collectUiTasks()) {
            if (requestId.equals(task.requestId)) {
                return task;
            }
        }
        return null;
    }

    private void addTaskDetailPage(LinearLayout container, UiTask task) {
        addDetailHeading(
            container,
            task.translation
                ? R.drawable.ic_task_translate
                : R.drawable.ic_task_summary,
            task.title,
            task.typeLabel() + " · " + task.objectType,
            task.status,
            statusColor(task)
        );
        String targetLanguage = task.language.isEmpty()
            ? getString(R.string.task_language_default)
            : task.language;
        addTaskMetaRow(
            container,
            getString(R.string.task_detail_object),
            task.objectType,
            getString(R.string.task_detail_time),
            taskDate(task.timestamp),
            true
        );
        addTaskMetaRow(
            container,
            getString(R.string.task_detail_target),
            targetLanguage,
            getString(R.string.task_detail_status),
            task.status,
            false
        );

        if (!task.reason.isEmpty()) {
            addDetailNotice(
                container,
                getString(R.string.task_detail_reason),
                task.reason,
                task.actionNeeded
            );
        }
        addTaskActions(container, task);
        addDetailHistory(container);
        if (!task.requestId.isEmpty()) {
            TextView request = taskDetailText(
                getString(R.string.task_detail_request) + "\n"
                    + task.requestId,
                R.style.TextAppearance_HET_StaticTask_DetailMetadata
            );
            request.setPadding(dp(2), dp(8), dp(2), 0);
            container.addView(request);
        }
    }

    private void addTaskMetaRow(
        LinearLayout container,
        String firstLabel,
        String firstValue,
        String secondLabel,
        String secondValue,
        boolean firstRow
    ) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams rowParams = new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        );
        rowParams.topMargin = dp(firstRow ? 14 : 8);
        row.setLayoutParams(rowParams);
        row.addView(taskMetaCard(firstLabel, firstValue),
            new LinearLayout.LayoutParams(
                0,
                LinearLayout.LayoutParams.WRAP_CONTENT,
                1f
            ));
        LinearLayout.LayoutParams secondParams = new LinearLayout.LayoutParams(
            0,
            LinearLayout.LayoutParams.WRAP_CONTENT,
            1f
        );
        secondParams.leftMargin = dp(8);
        row.addView(taskMetaCard(secondLabel, secondValue), secondParams);
        container.addView(row);
    }

    private View taskMetaCard(String label, String value) {
        MaterialCardView card = new MaterialCardView(this);
        card.setCardBackgroundColor(ContextCompat.getColor(
            this,
            R.color.het_surface_container
        ));
        card.setRadius(dp(14));
        card.setCardElevation(0f);
        card.setStrokeWidth(dp(1));
        card.setStrokeColor(ContextCompat.getColor(
            this,
            R.color.het_outline_soft
        ));
        View cell = detailMetaCell(label, value);
        cell.setPadding(dp(10), dp(10), dp(10), dp(10));
        card.addView(cell);
        return card;
    }

    private void showPendingDetailPage(JSONObject record) {
        if (!isPendingUiActive() || record == null) {
            return;
        }
        detailPage = DetailPage.PENDING;
        detailTask = null;
        detailPendingRecord = record;
        detailRejectedRecord = null;
        detailPendingKey = record.optString("pending_key", "");
        taskDetailOpen = true;
        resetDetailScrollOnNextRender = true;
        showDetailShell(getString(R.string.task_waiting_detail_title));
        renderTaskDetail();
    }

    void showRejectedResultDetails(JSONObject record) {
        if (!isPendingUiActive() || record == null) {
            return;
        }
        detailPage = DetailPage.REJECTED;
        detailTask = null;
        detailPendingRecord = null;
        detailRejectedRecord = record;
        detailPendingKey = null;
        if (notificationRejectedRecordId != null
            && notificationRejectedRecordId.equals(
                record.optString("record_id", "")
            )) {
            notificationRejectedRecordId = null;
        }
        taskDetailOpen = true;
        resetDetailScrollOnNextRender = true;
        showDetailShell(getString(R.string.task_waiting_detail_title));
        renderTaskDetail();
    }

    void onRejectedResultDeleted(String recordId) {
        if (detailPage != DetailPage.REJECTED || detailRejectedRecord == null) {
            return;
        }
        if (recordId == null || recordId.equals(
            detailRejectedRecord.optString("record_id", "")
        )) {
            closeTaskDetails();
        }
    }

    private void addPendingDetailPage(
        LinearLayout container,
        JSONObject record
    ) {
        String kind = record.optString("kind", "");
        String canonicalId = record.optString("canonical_id", "");
        String title = canonicalId.isEmpty()
            ? pendingKindLabel(kind)
            : canonicalId;
        addDetailHeading(
            container,
            0,
            title,
            getString(
                R.string.task_waiting_detail_subtitle,
                pendingKindLabel(kind)
            ),
            getString(R.string.task_status_management_pending),
            R.color.het_warning
        );
        addTaskMetaRow(
            container,
            getString(R.string.task_waiting_object),
            pendingKindLabel(kind),
            getString(R.string.task_waiting_canonical_id),
            canonicalId,
            true
        );
        addTaskMetaRow(
            container,
            getString(R.string.task_waiting_created),
            taskDate(record.optLong("created_at", 0L)),
            getString(R.string.task_waiting_language),
            pendingLanguageLabel(record),
            false
        );
        String reason = record.optString("reason", "");
        addDetailNotice(
            container,
            getString(R.string.task_waiting_reason),
            reason.isEmpty() ? getString(R.string.task_value_unrecorded) : reason,
            true
        );
        addPendingActions(container, record);
    }

    private void addPendingActions(
        LinearLayout container,
        JSONObject record
    ) {
        LinearLayout section = new LinearLayout(this);
        section.setOrientation(LinearLayout.VERTICAL);
        section.setPadding(dp(12), dp(12), dp(12), dp(12));
        TextView title = taskDetailText(
            getString(R.string.task_detail_actions),
            R.style.TextAppearance_HET_StaticTask_DetailCardTitle
        );
        section.addView(title);
        LinearLayout actionGroup = new LinearLayout(this);
        actionGroup.setOrientation(LinearLayout.VERTICAL);
        actionGroup.setGravity(Gravity.END);
        LinearLayout.LayoutParams actionGroupParams = new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        );
        actionGroupParams.topMargin = dp(10);
        section.addView(actionGroup, actionGroupParams);
        String key = record.optString("pending_key", "");
        MaterialButton restore = detailButton(R.string.pending_process_restore);
        restore.setEnabled(!busy && "snapshot".equals(
            record.optString("restore_mode", "")
        ));
        restore.setOnClickListener(view -> confirmRestorePending(record));
        actionGroup.addView(restore);
        MaterialButton delete = detailButton(R.string.pending_process_delete);
        delete.setOnClickListener(view -> confirmPermanentDeletePending(key));
        LinearLayout.LayoutParams deleteParams = new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        );
        deleteParams.topMargin = dp(8);
        actionGroup.addView(delete, deleteParams);
        addDetailSection(container, section);
    }

    private void addRejectedDetailPage(
        LinearLayout container,
        JSONObject record
    ) {
        String requestId = record.optString("request_id", "");
        String jobKind = record.optString("job_kind", "");
        String recordKind = record.optString("kind", "");
        String subject = rejectedSubject(record);
        String scene = rejectedPayloadValue(
            record,
            "scene",
            "scene_name"
        );
        UiTask relatedTask = findUiTask(requestId);
        String relatedTaskSubject = getString(
            R.string.task_waiting_related_unavailable
        );
        if (relatedTask != null && relatedTask.title != null
            && !relatedTask.title.trim().isEmpty()) {
            relatedTaskSubject = relatedTask.title;
        }
        String resultType = rejectedResultKindLabel(recordKind);
        addDetailHeading(
            container,
            R.drawable.ic_task_translate,
            subject,
            getString(
                R.string.task_waiting_result_subtitle,
                rejectedJobKindLabel(jobKind) + " · " + resultType
            ),
            getString(R.string.task_status_management_pending),
            R.color.het_warning
        );
        addTaskMetaRow(
            container,
            getString(R.string.task_waiting_related_task),
            relatedTaskSubject,
            getString(R.string.task_waiting_related_scene),
            scene.isEmpty()
                ? getString(R.string.task_value_unrecorded)
                : scene,
            true
        );
        addTaskMetaRow(
            container,
            getString(R.string.task_waiting_language),
            pendingLanguageLabel(record),
            getString(R.string.task_waiting_created),
            taskDate(record.optLong("created_at", 0L)),
            false
        );
        String reason = record.optString("reason", "");
        addDetailNotice(
            container,
            getString(R.string.task_waiting_reason),
            reason.isEmpty() ? getString(R.string.task_value_unrecorded) : reason,
            true
        );
        String payload = record.opt("payload") == null
            ? getString(R.string.rejected_api_result_empty_payload)
            : prettyJson(record.optJSONObject("payload"));
        if (payload.equals("{}") && record.opt("payload") != null) {
            payload = String.valueOf(record.opt("payload"));
        }
        if (!requestId.isEmpty()) {
            payload = getString(R.string.task_detail_request) + ": "
                + requestId + "\n\n" + payload;
        }
        addDetailTextCard(
            container,
            getString(R.string.task_waiting_result_payload),
            payload
        );
        LinearLayout actions = new LinearLayout(this);
        actions.setOrientation(LinearLayout.VERTICAL);
        actions.setPadding(dp(12), dp(12), dp(12), dp(12));
        TextView actionTitle = taskDetailText(
            getString(R.string.task_detail_actions),
            R.style.TextAppearance_HET_StaticTask_DetailCardTitle
        );
        actions.addView(actionTitle);
        LinearLayout actionGroup = new LinearLayout(this);
        actionGroup.setOrientation(LinearLayout.VERTICAL);
        actionGroup.setGravity(Gravity.END);
        LinearLayout.LayoutParams actionGroupParams = new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        );
        actionGroupParams.topMargin = dp(10);
        actions.addView(actionGroup, actionGroupParams);
        MaterialButton delete = detailButton(R.string.rejected_api_result_delete);
        delete.setOnClickListener(view -> {
            if (rejectedController != null) {
                rejectedController.deleteRecordFromDetail(record);
            }
        });
        actionGroup.addView(delete);
        addDetailSection(container, actions);
    }

    private String rejectedSubject(JSONObject record) {
        String subject = rejectedPayloadValue(
            record,
            "subject",
            "scene",
            "scene_name",
            "name"
        );
        return subject.isEmpty()
            ? getString(R.string.task_waiting_related_unavailable)
            : subject;
    }

    private String rejectedPayloadValue(JSONObject record, String... keys) {
        if (record == null || keys == null) {
            return "";
        }
        for (String keyName : keys) {
            String direct = record.optString(keyName, "").trim();
            if (!direct.isEmpty()) {
                return direct;
            }
        }
        JSONObject payload = record.optJSONObject("payload");
        if (payload == null) {
            return "";
        }
        for (String keyName : keys) {
            String value = payload.optString(keyName, "").trim();
            if (!value.isEmpty()) {
                return value;
            }
        }
        return "";
    }

    private String rejectedJobKindLabel(String kind) {
        if ("translation".equals(kind)) {
            return getString(R.string.task_tab_translation);
        }
        if ("summary".equals(kind)) {
            return getString(R.string.task_tab_summary);
        }
        return getString(R.string.task_value_unrecorded);
    }

    private String rejectedResultKindLabel(String kind) {
        if ("legal".equalsIgnoreCase(kind)) {
            return getString(R.string.task_waiting_result_legal);
        }
        if ("illegal".equalsIgnoreCase(kind)) {
            return getString(R.string.task_waiting_result_illegal);
        }
        if (ManagementBatchController.KIND_SCENE.equals(kind)
            || ManagementBatchController.KIND_CONTEXT.equals(kind)
            || ManagementBatchController.KIND_GROUP.equals(kind)
            || ManagementBatchController.KIND_CHARACTER.equals(kind)
            || ManagementBatchController.KIND_TERM.equals(kind)
            || ManagementBatchController.KIND_LANGUAGE.equals(kind)) {
            return pendingKindLabel(kind);
        }
        return kind == null || kind.trim().isEmpty()
            ? getString(R.string.task_value_unrecorded)
            : kind;
    }

    private void addDetailTextCard(
        LinearLayout container,
        String titleValue,
        String value
    ) {
        LinearLayout body = detailCardContainer();
        TextView title = taskDetailText(
            titleValue,
            R.style.TextAppearance_HET_StaticTask_DetailCardTitle
        );
        body.addView(title);
        TextView content = taskDetailText(
            value,
            R.style.TextAppearance_HET_StaticTask_DetailOriginal
        );
        content.setTextIsSelectable(true);
        content.setPadding(0, dp(4), 0, 0);
        body.addView(content);
        addDetailSection(container, body);
    }

    private void addTaskActions(LinearLayout container, UiTask task) {
        LinearLayout section = new LinearLayout(this);
        section.setOrientation(LinearLayout.VERTICAL);
        section.setPadding(dp(12), dp(12), dp(12), dp(12));
        TextView title = taskDetailText(
            getString(R.string.task_detail_actions),
            R.style.TextAppearance_HET_StaticTask_DetailCardTitle
        );
        section.addView(title);
        MaterialButton action = null;
        MaterialButton compressionAction = null;
        if (stylePreview) {
            action = null;
        } else if (task.kind == UiTaskKind.ACTIVE) {
            action = detailButton(R.string.translation_stop_action);
            TranslationJobStore.ReviewJob active =
                (TranslationJobStore.ReviewJob) task.source;
            action.setOnClickListener(view -> confirmStopActiveJob(active));
        } else {
            action = taskActionButton(task);
        }
        if (task.kind == UiTaskKind.USER_ACTION
            && task.source instanceof TranslationTaskExecutor.BlockedJob
            && ((TranslationTaskExecutor.BlockedJob) task.source).getReasonKind()
                == HistoryResolution.ReasonKind.CONTEXT_LENGTH
            && !stylePreview) {
            TranslationTaskExecutor.BlockedJob blocked =
                (TranslationTaskExecutor.BlockedJob) task.source;
            compressionAction = detailButton(
                R.string.user_action_enable_auto_compression
            );
            compressionAction.setOnClickListener(view ->
                enableAutoCompressionAndRetry(blocked)
            );
        }
        MaterialButton delete = detailButton(R.string.task_action_delete);
        delete.setEnabled(!stylePreview && !busy && !repairingStartupJobs);
        delete.setOnClickListener(view -> confirmDeleteTask(task));
        section.addView(delete);
        if (task.kind == UiTaskKind.USER_ACTION && !stylePreview) {
            MaterialButton stop = detailButton(R.string.translation_stop_action);
            stop.setEnabled(!busy && !repairingStartupJobs);
            stop.setOnClickListener(view -> confirmStopRequest(task.requestId));
            section.addView(stop);
        }
        if (action != null || compressionAction != null) {
            LinearLayout actionGroup = new LinearLayout(this);
            actionGroup.setOrientation(LinearLayout.VERTICAL);
            actionGroup.setGravity(Gravity.END);
            LinearLayout.LayoutParams groupParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            );
            groupParams.topMargin = dp(10);
            section.addView(actionGroup, groupParams);
            if (compressionAction != null) {
                compressionAction.setEnabled(!busy && !repairingStartupJobs);
                actionGroup.addView(compressionAction);
            }
            if (action != null) {
                action.setEnabled(!busy && !repairingStartupJobs);
                if (compressionAction != null) {
                    LinearLayout.LayoutParams actionParams =
                        new LinearLayout.LayoutParams(
                            LinearLayout.LayoutParams.WRAP_CONTENT,
                            LinearLayout.LayoutParams.WRAP_CONTENT
                        );
                    actionParams.topMargin = dp(8);
                    actionGroup.addView(action, actionParams);
                } else {
                    actionGroup.addView(action);
                }
            }
        }
        addDetailSection(container, section);
    }

    private void addDetailHistory(LinearLayout container) {
        LinearLayout section = new LinearLayout(this);
        section.setOrientation(LinearLayout.VERTICAL);
        section.setPadding(dp(12), dp(12), dp(12), dp(12));
        TextView title = taskDetailText(
            getString(R.string.task_detail_history),
            R.style.TextAppearance_HET_StaticTask_DetailCardTitle
        );
        section.addView(title);
        TextView empty = taskDetailText(
            getString(R.string.task_history_unavailable),
            R.style.TextAppearance_HET_StaticTask_DetailMetadata
        );
        empty.setPadding(0, dp(10), 0, dp(3));
        section.addView(empty);
        addDetailSection(container, section);
    }

    private void addDetailHeading(
        LinearLayout container,
        int iconResource,
        String titleValue,
        String subtitle,
        String status,
        int statusColorRes
    ) {
        LinearLayout heading = new LinearLayout(this);
        heading.setGravity(Gravity.CENTER_VERTICAL);
        if (iconResource != 0) {
            ImageView icon = new ImageView(this);
            icon.setImageResource(iconResource);
            icon.setImageTintList(ColorStateList.valueOf(ContextCompat.getColor(
                this,
                R.color.het_on_primary_container
            )));
            icon.setScaleType(ImageView.ScaleType.CENTER);
            icon.setBackgroundResource(R.drawable.bg_static_task_status);
            heading.addView(icon, new LinearLayout.LayoutParams(dp(32), dp(32)));
        }
        LinearLayout copy = new LinearLayout(this);
        copy.setOrientation(LinearLayout.VERTICAL);
        copy.setPadding(iconResource == 0 ? 0 : dp(10), 0, dp(8), 0);
        TextView title = taskDetailText(
            titleValue,
            R.style.TextAppearance_HET_StaticTask_DetailHeading
        );
        title.setMaxLines(1);
        title.setEllipsize(android.text.TextUtils.TruncateAt.END);
        TextView sub = taskDetailText(
            subtitle,
            R.style.TextAppearance_HET_StaticTask_DetailKind
        );
        sub.setMaxLines(2);
        sub.setEllipsize(android.text.TextUtils.TruncateAt.END);
        copy.addView(title);
        copy.addView(sub);
        copy.setMinimumHeight(dp(48));
        heading.addView(copy, new LinearLayout.LayoutParams(
            0,
            LinearLayout.LayoutParams.WRAP_CONTENT,
            1f
        ));
        TextView statusView = taskDetailText(
            status,
            R.style.TextAppearance_HET_StaticTask_DetailStatus
        );
        statusView.setTextColor(ContextCompat.getColor(this, statusColorRes));
        statusView.setGravity(Gravity.CENTER);
        statusView.setPadding(dp(8), dp(5), dp(8), dp(5));
        statusView.setBackgroundResource(R.drawable.bg_static_task_status);
        heading.addView(statusView, new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT,
            dp(30)
        ));
        MaterialCardView card = new MaterialCardView(this);
        card.setCardBackgroundColor(ContextCompat.getColor(
            this,
            R.color.het_surface_container
        ));
        card.setRadius(dp(18));
        card.setCardElevation(0f);
        card.setStrokeWidth(dp(1));
        card.setStrokeColor(ContextCompat.getColor(
            this,
            R.color.het_outline_soft
        ));
        heading.setPadding(dp(13), dp(13), dp(13), dp(13));
        card.addView(heading);
        container.addView(card);
    }

    private LinearLayout detailCardContainer() {
        LinearLayout cardContent = new LinearLayout(this);
        cardContent.setOrientation(LinearLayout.VERTICAL);
        cardContent.setPadding(dp(12), dp(12), dp(12), dp(12));
        return cardContent;
    }

    private View detailMetaCell(String label, String value) {
        LinearLayout cell = new LinearLayout(this);
        cell.setOrientation(LinearLayout.VERTICAL);
        TextView labelView = taskDetailText(
            label,
            R.style.TextAppearance_HET_StaticTask_DetailMetaLabel
        );
        TextView valueView = taskDetailText(
            value == null || value.isEmpty() ? "—" : value,
            R.style.TextAppearance_HET_StaticTask_DetailMetaValue
        );
        valueView.setMaxLines(2);
        valueView.setEllipsize(android.text.TextUtils.TruncateAt.END);
        cell.addView(labelView);
        LinearLayout.LayoutParams valueParams = new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        );
        valueParams.topMargin = dp(4);
        cell.addView(valueView, valueParams);
        cell.setPadding(dp(10), dp(10), dp(10), dp(10));
        return cell;
    }

    private void addDetailSection(LinearLayout container, View content) {
        MaterialCardView card = new MaterialCardView(this);
        card.setCardBackgroundColor(ContextCompat.getColor(
            this,
            R.color.het_surface_container
        ));
        card.setRadius(dp(18));
        card.setCardElevation(0f);
        card.setStrokeWidth(dp(1));
        card.setStrokeColor(ContextCompat.getColor(
            this,
            R.color.het_outline_soft
        ));
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        );
        params.topMargin = dp(14);
        card.setLayoutParams(params);
        card.addView(content);
        container.addView(card);
    }

    private void addDetailNotice(
        LinearLayout container,
        String titleValue,
        String message,
        boolean error
    ) {
        MaterialCardView card = new MaterialCardView(this);
        card.setCardBackgroundColor(ContextCompat.getColor(
            this,
            error ? R.color.het_error_container : R.color.het_surface_container_high
        ));
        card.setRadius(dp(14));
        card.setCardElevation(0f);
        LinearLayout body = new LinearLayout(this);
        body.setOrientation(LinearLayout.VERTICAL);
        body.setPadding(dp(12), dp(11), dp(12), dp(11));
        TextView title = taskDetailText(
            titleValue,
            R.style.TextAppearance_HET_StaticTask_DetailCardTitle
        );
        if (error) {
            title.setTextColor(ContextCompat.getColor(this, R.color.het_error));
        }
        body.addView(title);
        TextView detail = taskDetailText(
            message,
            R.style.TextAppearance_HET_StaticTask_DetailOriginal
        );
        if (error) {
            detail.setTextColor(ContextCompat.getColor(
                this,
                R.color.het_on_surface
            ));
        }
        LinearLayout.LayoutParams detailParams = new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        );
        detailParams.topMargin = dp(4);
        body.addView(detail, detailParams);
        card.addView(body);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        );
        params.topMargin = dp(14);
        card.setLayoutParams(params);
        container.addView(card);
    }

    private MaterialButton detailButton(int textRes) {
        MaterialButton button = new MaterialButton(
            this,
            null,
            com.google.android.material.R.attr.materialButtonOutlinedStyle
        );
        button.setAllCaps(false);
        button.setText(textRes);
        button.setTextSize(13f);
        button.setIncludeFontPadding(false);
        button.setMinHeight(dp(34));
        return button;
    }

    private TextView taskDetailText(String value, int style) {
        TextView text = new TextView(this);
        text.setTextAppearance(this, style);
        text.setText(value == null ? "" : value);
        return text;
    }

    private TextView taskText(String value, float size, int colorRes) {
        return createTaskText(value, size + 2f, colorRes);
    }

    private TextView taskIconText(String value, float size, int colorRes) {
        return createTaskText(value, size, colorRes);
    }

    private TextView createTaskText(String value, float size, int colorRes) {
        TextView text = new TextView(this);
        text.setText(value == null ? "" : value);
        text.setTextSize(size);
        text.setTextColor(ContextCompat.getColor(this, colorRes));
        text.setIncludeFontPadding(false);
        return text;
    }

    private int statusColor(UiTask task) {
        if (task.actionNeeded && !task.completed) {
            return R.color.het_error;
        }
        if (task.completed) {
            return R.color.het_good;
        }
        return R.color.het_primary_strong;
    }

    private String taskDate(long timestamp) {
        if (timestamp <= 0L) {
            return getString(R.string.task_time_unknown);
        }
        return DateFormat.getDateTimeInstance(
            DateFormat.MEDIUM,
            DateFormat.SHORT
        ).format(new Date(timestamp));
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    private void updateTaskTabs() {
        if (taskTabButtons == null) {
            return;
        }
        for (int index = 0; index < taskTabButtons.length; index++) {
            MaterialButton button = taskTabButtons[index];
            if (button == null) {
                continue;
            }
            button.setChecked(index == taskTab);
            button.setSelected(index == taskTab);
            boolean selected = index == taskTab;
            button.setBackgroundTintList(ColorStateList.valueOf(
                ContextCompat.getColor(
                    this,
                    selected
                        ? R.color.het_primary_container
                        : android.R.color.transparent
                )
            ));
            button.setCornerRadius(dp(8));
            button.setMinHeight(dp(31));
            button.setInsetTop(0);
            button.setInsetBottom(0);
            button.setPadding(dp(4), 0, dp(4), 0);
            button.setTextColor(ContextCompat.getColor(
                this,
                selected ? R.color.het_on_primary_container : R.color.het_on_surface
            ));
        }
        if (taskScopeSummary != null) {
            taskScopeSummary.setVisibility(View.GONE);
        }
    }

    private void selectTaskTab(int tab) {
        if (tab < TAB_ALL || tab > TAB_WAITING) {
            return;
        }
        taskTab = tab;
        if (tab == TAB_WAITING && !stylePreview) {
            initializeWaitingController();
            activateWaitingController();
        }
        renderTaskPage();
    }

    private void showTaskSortDialog() {
        if (taskTab == TAB_WAITING) {
            return;
        }
        showTaskMenu(
            taskSortButton,
            getString(R.string.task_sort_title),
            new String[] {
                getString(R.string.task_sort_recent),
                getString(R.string.task_sort_name),
                getString(R.string.task_sort_action),
                getString(R.string.task_sort_completion)
            },
            new String[] {"recent", "name", "action", "completion"},
            taskSort,
            value -> {
                taskSort = value;
                renderTaskPage();
            }
        );
    }

    private void showTaskMoreDialog() {
        if (taskTab == TAB_WAITING || taskMoreButton == null) {
            return;
        }
        showTaskMenu(
            taskMoreButton,
            getString(R.string.task_more_title),
            new String[] {
                getString(R.string.task_scope_all),
                getString(R.string.task_scope_unfinished),
                getString(R.string.task_scope_action_needed),
                getString(R.string.task_scope_completed)
            },
            new String[] {"all", "unfinished", "action-needed", "completed"},
            taskScope,
            value -> {
                taskScope = value;
                openTaskSections.add(taskScope);
                renderTaskPage();
            }
        );
    }

    private void showTaskMenu(
        View anchor,
        String title,
        String[] labels,
        String[] values,
        String selectedValue,
        java.util.function.Consumer<String> onSelected
    ) {
        if (anchor == null || labels == null || values == null
            || labels.length != values.length || onSelected == null) {
            return;
        }
        StyledPopupMenu popup = new StyledPopupMenu(this, anchor, Gravity.END);
        popup.setTitle(title);
        Menu menu = popup.getMenu();
        for (int index = 0; index < labels.length; index++) {
            MenuItem item = menu.add(Menu.NONE, index + 1, index, labels[index]);
            item.setCheckable(true);
            item.setChecked(values[index].equals(selectedValue));
        }
        popup.setOnMenuItemClickListener(item -> {
            int index = item.getItemId() - 1;
            if (index < 0 || index >= values.length) {
                return false;
            }
            onSelected.accept(values[index]);
            return true;
        });
        popup.show();
    }

    private void updateTaskRecoveryAction(boolean waiting) {
        if (submitButton == null && summarySubmitButton == null) {
            return;
        }
        boolean recoveryPage = !stylePreview
            && !managementOnly
            && !waiting
            && !taskDetailOpen;
        boolean summaryVisible = recoveryPage
            && summaryRecoveryReady
            && !summaryJobs.isEmpty()
            && (taskTab == TAB_SUMMARY
                || (taskTab == TAB_ALL && selectedRequestIds.isEmpty()));
        boolean translationVisible = recoveryPage
            && !selectedRequestIds.isEmpty()
            && (taskTab == TAB_TRANSLATION
                || (taskTab == TAB_ALL && !summaryVisible));
        boolean visible = translationVisible || summaryVisible;
        if (submitButton != null) {
            submitButton.setVisibility(
                translationVisible ? View.VISIBLE : View.GONE
            );
        }
        if (summarySubmitButton != null) {
            summarySubmitButton.setVisibility(
                summaryVisible ? View.VISIBLE : View.GONE
            );
        }
        if (pageActions != null) {
            pageActions.setVisibility(visible ? View.VISIBLE : View.GONE);
        }
        View primaryNavigation = findViewById(R.id.primary_navigation);
        if (primaryNavigation != null && !taskDetailOpen) {
            primaryNavigation.setVisibility(
                visible || managementOnly ? View.GONE : View.VISIBLE
            );
        }
        if (submitButton != null) {
            submitButton.setEnabled(
                translationVisible
                    && !busy
                    && !repairingStartupJobs
            );
        }
        if (summarySubmitButton != null) {
            summarySubmitButton.setEnabled(
                summaryVisible
                    && !busy
                    && !repairingStartupJobs
            );
        }
    }

    /** Confirm the selected recovery order; sorting only changes presentation. */
    private void showRecoveryOrderDialog() {
        if (stylePreview || managementOnly || repairingStartupJobs
            || (jobs.isEmpty() && !hasRerunCandidates())) {
            Toast.makeText(
                this,
                R.string.task_recovery_empty,
                Toast.LENGTH_SHORT
            ).show();
            return;
        }
        List<String> labels = new ArrayList<>();
        for (UiTask task : collectUiTasks()) {
            if (task.kind == UiTaskKind.HELD
                || (task.kind == UiTaskKind.TERMINAL
                    && task.source instanceof TranslationJobStore.TerminalJob
                    && !((TranslationJobStore.TerminalJob) task.source)
                        .isSceneValidationFailure())) {
                labels.add(task.title + " · " + task.status);
            }
        }
        new UiMaterialAlertDialogBuilder(this)
            .setTitle(R.string.task_recovery_title)
            .setMessage(getString(R.string.task_recovery_message, labels.size()))
            .setNegativeButton(R.string.cancel_action, null)
            .setPositiveButton(
                R.string.translation_queue_submit,
                (dialog, which) -> submitOrder()
            )
            .show();
    }

    private void showSummaryRecoveryDialog() {
        if (stylePreview || managementOnly || busy || repairingStartupJobs
            || !summaryRecoveryReady
            || renderedSummaryRecoveryStore == null
            || summaryJobs.isEmpty()) {
            return;
        }
        final SummaryJobStore expectedSummaryStore =
            renderedSummaryRecoveryStore;
        final ArrayList<String> snapshotIds = new ArrayList<>();
        Set<String> snapshotIdSet = new HashSet<>();
        for (SummaryJobStore.RecoveryJob job : summaryJobs) {
            String requestId = job.getRequestId();
            snapshotIds.add(requestId);
            snapshotIdSet.add(requestId);
        }
        final ArrayList<String> restoreIds = new ArrayList<>();
        for (String requestId : selectedSummaryRequestIds) {
            if (snapshotIdSet.contains(requestId)
                && !restoreIds.contains(requestId)) {
                restoreIds.add(requestId);
            }
        }
        int restoreCount = restoreIds.size();
        int discardCount = snapshotIds.size() - restoreCount;
        new UiMaterialAlertDialogBuilder(this)
            .setTitle(R.string.summary_recovery_confirm_title)
            .setMessage(getString(
                R.string.summary_recovery_confirm_message,
                restoreCount,
                discardCount
            ))
            .setNegativeButton(R.string.cancel_action, null)
            .setPositiveButton(
                R.string.summary_recovery_submit,
                (dialog, which) -> submitSummaryRecovery(
                    expectedSummaryStore,
                    restoreIds,
                    snapshotIds
                )
            )
            .show();
    }

    private void openManagementForScene(TranslationJobStore.TerminalJob job) {
        if (job == null) {
            return;
        }
        if (taskDetailOpen) {
            closeTaskDetails();
        }
        if (pendingClient == null) {
            initializeWaitingController();
        }
        selectTaskTab(TAB_WAITING);
        if (isPendingUiActive()) {
            moveSceneValidationToPending(job);
        }
    }

    /** Renders only ordinary Translation Jobs that are durably running. */
    private void renderActiveJobs() {
        if (managementOnly || activeSection == null) {
            return;
        }
        activeItemContainer.removeAllViews();
        boolean empty = activeJobs.isEmpty();
        activeSection.setVisibility(empty ? View.GONE : View.VISIBLE);
        if (empty) {
            return;
        }
        activeSummary.setText(getString(
            R.string.translation_stop_count,
            activeJobs.size()
        ));
        LayoutInflater inflater = LayoutInflater.from(this);
        for (TranslationJobStore.ReviewJob job : activeJobs) {
            View item = inflater.inflate(
                R.layout.item_translation_active,
                activeItemContainer,
                false
            );
            MaterialCardView card = item.findViewById(
                R.id.card_translation_active_item
            );
            TextView scene = item.findViewById(
                R.id.tv_translation_active_scene
            );
            TextView status = item.findViewById(
                R.id.tv_translation_active_status
            );
            MaterialButton details = item.findViewById(
                R.id.btn_translation_active_details
            );
            scene.setText(job.getScene());
            status.setText(getString(
                R.string.translation_stop_status_running
            ));
            card.setOnClickListener(view -> showActiveJobDetails(job));
            details.setOnClickListener(view -> showActiveJobDetails(job));
            card.setEnabled(!busy);
            details.setEnabled(!busy);
            activeItemContainer.addView(item);
        }
    }

    private boolean isPendingUiActive() {
        return taskTab == TAB_WAITING
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
                restorePendingDetailIfReady();
                restoreWaitingListScrollIfReady();
            });
        });
    }

    private void renderPendingProcesses() {
        if (taskTab != TAB_WAITING || pendingSection == null) {
            return;
        }
        pendingSection.setVisibility(View.VISIBLE);
        pendingItemContainer.removeAllViews();
        if (pendingLoading) {
            waitingPendingLoaded = false;
            waitingPendingUnavailable = false;
            waitingPendingVisibleCount = 0;
            waitingPendingTotalCount = 0;
            updateWaitingSummary();
            pendingSummary.setVisibility(View.GONE);
            pendingEmptyMessage.setText(R.string.pending_process_loading);
            pendingEmptyMessage.setVisibility(View.GONE);
            pendingRefreshButton.setEnabled(false);
            pendingMoveButton.setEnabled(false);
            return;
        }
        if (!pendingReady) {
            waitingPendingLoaded = true;
            waitingPendingUnavailable = true;
            waitingPendingVisibleCount = 0;
            waitingPendingTotalCount = 0;
            updateWaitingSummary();
            pendingSummary.setVisibility(View.GONE);
            pendingEmptyMessage.setText(
                pendingClient != null && pendingClient.isConnected()
                    ? R.string.pending_process_service_not_ready
                    : R.string.pending_process_service_unavailable
            );
            pendingEmptyMessage.setVisibility(View.GONE);
            pendingRefreshButton.setEnabled(!busy);
            pendingMoveButton.setEnabled(false);
            return;
        }

        List<JSONObject> visibleProcesses = new ArrayList<>();
        for (JSONObject entry : pendingProcesses) {
            if (matchesPendingSearch(entry)) {
                visibleProcesses.add(entry);
            }
        }
        waitingPendingLoaded = true;
        waitingPendingUnavailable = false;
        waitingPendingVisibleCount = visibleProcesses.size();
        waitingPendingTotalCount = pendingProcesses.size();
        updateWaitingSummary();
        pendingSummary.setText(getString(
            R.string.pending_process_count,
            visibleProcesses.size()
        ));
        pendingSummary.setVisibility(View.GONE);
        pendingEmptyMessage.setText(
            pendingProcesses.isEmpty()
                ? R.string.pending_process_empty
                : R.string.task_waiting_no_results
        );
        pendingEmptyMessage.setVisibility(View.GONE);
        pendingRefreshButton.setEnabled(!busy);
        pendingMoveButton.setEnabled(
            !busy && pendingClient != null && pendingClient.isConnected()
        );

        LayoutInflater inflater = LayoutInflater.from(this);
        DateFormat dateFormat = DateFormat.getDateTimeInstance(
            DateFormat.MEDIUM,
            DateFormat.SHORT
        );
        for (JSONObject entry : visibleProcesses) {
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
            String kindValue = entry.optString("kind", "");
            String canonicalValue = entry.optString("canonical_id", "");
            kind.setText(canonicalValue.isEmpty()
                ? pendingKindLabel(kindValue)
                : canonicalValue);
            canonicalId.setText(getString(
                R.string.task_waiting_object
            ) + " · " + pendingKindLabel(kindValue));
            pendingKey.setVisibility(View.GONE);
            reason.setText(entry.optString("reason", "").trim().isEmpty()
                ? getString(R.string.task_value_unrecorded)
                : entry.optString("reason", ""));
            created.setText(dateFormat.format(new Date(
                entry.optLong("created_at", 0L)
            )));
            mode.setVisibility(View.GONE);
            details.setVisibility(View.GONE);
            card.setOnClickListener(view -> showPendingDetails(key));
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

    }

    private String pendingKindLabel(String kind) {
        if (ManagementBatchController.KIND_SCENE.equals(kind)) {
            return getString(R.string.task_object_scene);
        }
        if (ManagementBatchController.KIND_CONTEXT.equals(kind)) {
            return getString(R.string.task_object_context);
        }
        if (ManagementBatchController.KIND_GROUP.equals(kind)) {
            return getString(R.string.task_object_group);
        }
        if (ManagementBatchController.KIND_CHARACTER.equals(kind)) {
            return getString(R.string.chardict_title);
        }
        if (ManagementBatchController.KIND_TERM.equals(kind)) {
            return getString(R.string.gameterms_title);
        }
        if (ManagementBatchController.KIND_LANGUAGE.equals(kind)) {
            return getString(R.string.target_language);
        }
        return getString(R.string.task_value_unrecorded);
    }

    private String pendingLanguageLabel(JSONObject record) {
        String language = record.optString("language", "").trim();
        if (language.isEmpty()) {
            language = record.optString("target_language", "").trim();
        }
        return language.isEmpty()
            ? getString(R.string.task_value_unrecorded)
            : language;
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
                    showPendingDetailPage(entry);
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
        new UiMaterialAlertDialogBuilder(this)
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
                    if (detailPage == DetailPage.PENDING
                        && pendingKey.equals(detailPendingKey)) {
                        closeTaskDetails();
                    }
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
        if (!isPendingUiActive() || busy || captureControlBusy
            || pendingKey == null
            || !canShowControlDialog()) {
            return;
        }
        AlertDialog dialog = new UiMaterialAlertDialogBuilder(this)
            .setTitle(R.string.pending_process_delete_title)
            .setMessage(R.string.pending_process_delete_message)
            .setNegativeButton(R.string.cancel_action, null)
            .setPositiveButton(
                R.string.pending_process_delete,
                (shown, which) -> {
                    releaseControlDialog(shown);
                    permanentlyDeletePending(pendingKey);
                }
            )
            .create();
        showControlDialog(dialog);
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
                    if (detailPage == DetailPage.PENDING
                        && pendingKey.equals(detailPendingKey)) {
                        closeTaskDetails();
                    }
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

        new UiMaterialAlertDialogBuilder(this)
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
        new UiMaterialAlertDialogBuilder(this)
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
        new UiMaterialAlertDialogBuilder(this)
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
        if (stylePreview || busy) {
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

    /** Enables the global compression setting, then retries this held job. */
    private void enableAutoCompressionAndRetry(
        TranslationTaskExecutor.BlockedJob job
    ) {
        if (stylePreview || busy || repairingStartupJobs || job == null
            || job.getReasonKind() != HistoryResolution.ReasonKind.CONTEXT_LENGTH) {
            return;
        }
        setBusy(true);
        ioExecutor.execute(() -> {
            try {
                ConfigStore configStore = new ConfigStore(this);
                ConfigStore.LoadResult latest = configStore.load();
                JSONObject updated = new JSONObject(latest.config.toString());
                JSONObject settings = updated.optJSONObject("UserSettings");
                if (settings == null) {
                    throw new IllegalStateException("missing UserSettings");
                }
                JSONObject contextHistory = settings.optJSONObject(
                    "ContextHistory"
                );
                if (contextHistory == null) {
                    contextHistory = new JSONObject();
                    settings.put("ContextHistory", contextHistory);
                }
                contextHistory.put("EnableAutoCompression", true);
                configStore.save(updated);

                TranslationTaskExecutor activeExecutor =
                    TranslationService.getActiveTaskExecutor();
                boolean retried = activeExecutor != null
                    && activeExecutor.retryUserActionRequiredJob(
                        job.getRequestId()
                    );
                runOnUiThread(() -> {
                    if (isDestroyed()) {
                        return;
                    }
                    setBusy(false);
                    TranslationStatusNotification.refresh(this);
                    Toast.makeText(
                        this,
                        retried
                            ? R.string.user_action_enable_auto_compression_queued
                            : R.string.user_action_enable_auto_compression_not_found,
                        Toast.LENGTH_LONG
                    ).show();
                    refreshJobs();
                });
            } catch (Exception error) {
                // Keep the blocker when loading or saving the setting fails.
                showOperationFailure(error);
            }
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

    private void submitSummaryRecovery(
        SummaryJobStore expectedSummaryStore,
        ArrayList<String> restoreIds,
        ArrayList<String> snapshotIds
    ) {
        if (stylePreview || managementOnly || busy || repairingStartupJobs
            || !summaryRecoveryReady
            || restoreIds == null
            || snapshotIds == null
            || snapshotIds.isEmpty()) {
            return;
        }
        if (renderedSummaryRecoveryStore != expectedSummaryStore
            || !matchesSummaryRecoverySnapshot(snapshotIds)) {
            Toast.makeText(
                this,
                R.string.summary_recovery_changed,
                Toast.LENGTH_LONG
            ).show();
            refreshJobs();
            return;
        }
        setBusy(true);
        final int restoreCount = restoreIds.size();
        final int discardCount = snapshotIds.size() - restoreCount;
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

    private boolean matchesSummaryRecoverySnapshot(List<String> snapshotIds) {
        if (summaryJobs.size() != snapshotIds.size()) {
            return false;
        }
        Set<String> currentIds = new HashSet<>();
        for (SummaryJobStore.RecoveryJob job : summaryJobs) {
            currentIds.add(job.getRequestId());
        }
        return currentIds.size() == snapshotIds.size()
            && currentIds.containsAll(snapshotIds);
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
        if (stylePreview || busy) {
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
        new UiMaterialAlertDialogBuilder(this)
            .setTitle(job.getScene())
            .setMessage(message)
            .setPositiveButton(android.R.string.ok, null)
            .show();
    }

    private void showActiveJobDetails(
        TranslationJobStore.ReviewJob job
    ) {
        if (stylePreview || managementOnly || busy || captureControlBusy || job == null
            || isFinishing()
            || !canShowControlDialog()) {
            return;
        }
        String status = getString(R.string.translation_stop_status_running);
        AlertDialog dialog = new UiMaterialAlertDialogBuilder(this)
            .setTitle(job.getScene())
            .setMessage(getString(
                R.string.translation_stop_details_message,
                status
            ))
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(
                R.string.translation_stop_action,
                (shown, which) -> {
                    releaseControlDialog(shown);
                    confirmStopActiveJob(job);
                }
            )
            .create();
        showControlDialog(dialog);
    }

    private void confirmStopActiveJob(TranslationJobStore.ReviewJob job) {
        if (job != null) {
            confirmStopRequest(job.getRequestId());
        }
    }

    private void confirmStopRequest(String requestId) {
        if (stylePreview || managementOnly || busy || captureControlBusy || requestId == null
            || isFinishing()
            || !canShowControlDialog()) {
            return;
        }
        AlertDialog dialog = new UiMaterialAlertDialogBuilder(this)
            .setTitle(R.string.translation_stop_confirm_title)
            .setMessage(R.string.translation_stop_confirm_message)
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(
                R.string.translation_stop_action,
                (shown, which) -> {
                    releaseControlDialog(shown);
                    stopActiveJob(requestId);
                }
            )
            .create();
        showControlDialog(dialog);
    }

    private void stopActiveJob(String requestId) {
        if (stylePreview || managementOnly || busy || requestId == null
            || requestId.trim().isEmpty() || translationJobClient == null) {
            return;
        }
        final int generation = activeUiGeneration;
        ensureTranslationService();
        try {
            translationJobClient.bind();
        } catch (RuntimeException error) {
            showStopOperationFailure(error, generation);
            return;
        }
        stopInFlight = true;
        setBusy(true);
        ioExecutor.execute(() -> {
            final int result;
            try {
                if (!translationJobClient.awaitConnected(3_000L)) {
                    translationJobClient.requireConnected();
                }
                result = translationJobClient.cancelTranslation(requestId);
            } catch (InterruptedException error) {
                Thread.currentThread().interrupt();
                showStopOperationFailure(error, generation);
                return;
            } catch (Exception error) {
                showStopOperationFailure(error, generation);
                return;
            }
            runOnUiThread(() -> {
                if (!isActiveUi(generation)) {
                    return;
                }
                stopInFlight = false;
                setBusy(false);
                TranslationStatusNotification.refresh(this);
                Toast.makeText(
                    this,
                    stopResultMessage(result),
                    Toast.LENGTH_LONG
                ).show();
                refreshJobs();
            });
        });
    }

    private String stopResultMessage(int result) {
        switch (result) {
            case HetBridgeContract.CANCEL_RESULT_RUNNING_CANCELED:
                return getString(R.string.translation_stop_result_running);
            case HetBridgeContract.CANCEL_RESULT_QUEUED_CANCELED:
                return getString(R.string.translation_stop_result_queued);
            case HetBridgeContract.CANCEL_RESULT_ALREADY_CANCELED:
                return getString(
                    R.string.translation_stop_result_already_canceled
                );
            case HetBridgeContract.CANCEL_RESULT_NOT_FOUND:
                return getString(R.string.translation_stop_result_not_found);
            case HetBridgeContract.CANCEL_RESULT_NOT_CANCELABLE:
                return getString(
                    R.string.translation_stop_result_not_cancelable
                );
            case HetBridgeContract.CANCEL_RESULT_RETRYABLE_PERSISTENCE:
                return getString(R.string.translation_stop_result_retryable);
            default:
                return getString(
                    R.string.translation_stop_operation_failed,
                    "unknown cancellation result " + result
                );
        }
    }

    private boolean matchesPendingSearch(JSONObject entry) {
        String query = taskSearchQuery == null
            ? ""
            : taskSearchQuery.trim().toLowerCase(Locale.ROOT);
        if (query.isEmpty()) {
            return true;
        }
        String haystack = pendingKindLabel(entry.optString("kind", "")) + " "
            + entry.optString("canonical_id", "") + " "
            + entry.optString("reason", "") + " "
            + entry.optString("scene", "") + " "
            + entry.optString("subject", "") + " "
            + entry.optString("language", "") + " "
            + entry.optString("target_language", "");
        return haystack.toLowerCase(Locale.ROOT).contains(query);
    }

    private boolean isActiveUi(int generation) {
        return !isDestroyed()
            && !isFinishing()
            && !managementOnly
            && generation == activeUiGeneration;
    }

    private void showStopOperationFailure(
        Throwable error,
        int generation
    ) {
        runOnUiThread(() -> {
            if (!isActiveUi(generation)) {
                return;
            }
            stopInFlight = false;
            setBusy(false);
            String message = error instanceof
                TranslationJobControlClient.ServiceUnavailableException
                ? getString(R.string.translation_stop_service_unavailable)
                : getString(
                    R.string.translation_stop_operation_failed,
                    safeMessage(error)
                );
            Toast.makeText(this, message, Toast.LENGTH_LONG).show();
        });
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
        if (stylePreview || busy || repairingStartupJobs) {
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
        if (stylePreview || managementOnly
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
        if (stylePreview) {
            finish();
            return;
        }
        if (busy || captureControlBusy) {
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

        new UiMaterialAlertDialogBuilder(this)
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
        if (stylePreview) {
            finish();
            return;
        }
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
        refreshCaptureControl();
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
        for (int index = 0; index < activeItemContainer.getChildCount(); index++) {
            View activeItem = activeItemContainer.getChildAt(index);
            activeItem.setEnabled(!value);
            MaterialButton activeDetails = activeItem.findViewById(
                R.id.btn_translation_active_details
            );
            if (activeDetails != null) {
                activeDetails.setEnabled(!value);
            }
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
        if (taskTab == TAB_WAITING && pendingSection != null) {
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
        if (stylePreview || busy || job == null || job.isSceneValidationFailure()) {
            return;
        }
        setBusy(true);
        ioExecutor.execute(() -> {
            try {
                jobStore.withManagementMutation(() ->
                    jobStore.rerunManualCandidate(job.getRequestId())
                );
                boolean serviceStarted = ensureTranslationService();
                runOnUiThread(() -> {
                    if (isDestroyed()) {
                        return;
                    }
                    setBusy(false);
                    if (detailTask != null
                        && job.getRequestId().equals(detailTask.requestId)) {
                        closeTaskDetails();
                    }
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

    /** Wakes the foreground owner after a durable queue or control change. */
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
            com.quarty.housamoembedtrans.logging.Log.w(
                "HET.TranslationQueue",
                "Could not start TranslationService after local admission",
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
