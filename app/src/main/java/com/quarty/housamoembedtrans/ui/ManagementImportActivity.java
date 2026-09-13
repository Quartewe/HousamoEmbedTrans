package com.quarty.housamoembedtrans.ui;

import com.quarty.housamoembedtrans.R;
import com.quarty.housamoembedtrans.bridge.HetBridgeContract;
import com.quarty.housamoembedtrans.bridge.TranslationJobControlClient;

import android.app.Activity;
import android.content.Intent;
import android.content.res.ColorStateList;
import android.net.Uri;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.CheckBox;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.OnBackPressedCallback;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;

import com.google.android.material.appbar.MaterialToolbar;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.card.MaterialCardView;

import org.json.JSONObject;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * SAF-first management import flow shared by the Scene, Context, Group and
 * dictionary pages.
 *
 * <p>This Activity owns selection, per-file parsing, preflight, conflict
 * choices and the full JSON preview.  The final result is an app-private
 * session token.  The caller must pass that token to the unified store
 * coordinator, which rechecks the expected snapshot fingerprint before its
 * durable commit.</p>
 */
public final class ManagementImportActivity extends AppCompatActivity {
    public static final String EXTRA_EXISTING_SNAPSHOT_JSON =
        "management_import.existing_snapshot_json";
    public static final String EXTRA_AUTO_CANCEL_TASKS =
        "management_import.auto_cancel_tasks";
    public static final String EXTRA_SESSION_TOKEN =
        "management_import.session_token";
    public static final String EXTRA_PREVIEW_FINGERPRINT =
        "management_import.preview_fingerprint";
    public static final String EXTRA_SNAPSHOT_FINGERPRINT =
        "management_import.snapshot_fingerprint";
    public static final String EXTRA_RESULT_STATE =
        "management_import.result_state";
    public static final int RESULT_PREPARED = Activity.RESULT_FIRST_USER + 41;

    private enum Phase {
        FILES,
        PREFLIGHT,
        PREVIEW
    }

    private enum FileState {
        READING,
        READY,
        ERROR
    }

    private static final class FileRow {
        final String token;
        final Uri uri;
        final String name;
        FileState state = FileState.READING;
        String error;
        ManagementImportModel.Document document;
        boolean selected;

        FileRow(String token, Uri uri, String name) {
            this.token = token;
            this.uri = uri;
            this.name = name;
        }
    }

    private final ExecutorService ioExecutor = Executors.newSingleThreadExecutor();
    private final LinkedHashMap<String, FileRow> rows = new LinkedHashMap<>();
    private final LinkedHashMap<String, ManagementImportModel.ConflictAction>
        conflictActions = new LinkedHashMap<>();
    private final LinkedHashMap<String, Spinner> conflictSpinners =
        new LinkedHashMap<>();

    private ActivityResultLauncher<String[]> fileLauncher;
    private MaterialToolbar toolbar;
    private TextView statusView;
    private LinearLayout fileContainer;
    private LinearLayout preflightContainer;
    private TextView previewView;
    private ScrollView fileScroll;
    private ScrollView preflightScroll;
    private ScrollView previewScroll;
    private LinearLayout previewActions;
    private MaterialButton addButton;
    private MaterialButton removeModeButton;
    private MaterialButton removeCancelButton;
    private MaterialButton confirmFilesButton;
    private MaterialButton generatePreviewButton;
    private MaterialButton applyButton;
    private Spinner conflictDefaultSpinner;
    private MaterialButton taskActionButton;

    private final Set<String> selectedForRemoval = new LinkedHashSet<>();
    private Phase phase = Phase.FILES;
    private boolean removeMode;
    private boolean busy;
    private boolean snapshotReady;
    private boolean snapshotLoading;
    private boolean submissionUnknown;
    private long sessionGeneration;
    private ManagementImportModel.Batch batch;
    private ManagementImportModel.ExistingSnapshot existingSnapshot;
    private ManagementImportModel.PreparedImport prepared;
    private ManagementImportModel.ConflictAction defaultConflictAction =
        ManagementImportModel.ConflictAction.OVERWRITE;
    private ManagementImportModel.TaskAction taskAction =
        ManagementImportModel.TaskAction.KEEP;
    private String sessionToken;
    private String pendingSnapshotFingerprint;
    private boolean pendingSubmissionRestored;
    private boolean pendingRecoveryUnavailable;
    private boolean destroyed;
    private TranslationJobControlClient managementClient;

    /** Loads the real snapshot through the Service Binder pipe. */
    public static Intent newIntent(Activity caller, boolean autoCancelTasks) {
        return new Intent(caller, ManagementImportActivity.class)
            .putExtra(EXTRA_AUTO_CANCEL_TASKS, autoCancelTasks);
    }

    public static Intent newIntent(
        Activity caller,
        JSONObject existingSnapshot,
        boolean autoCancelTasks
    ) {
        Intent intent = new Intent(caller, ManagementImportActivity.class)
            .putExtra(EXTRA_AUTO_CANCEL_TASKS, autoCancelTasks);
        if (existingSnapshot != null) {
            String encoded = existingSnapshot.toString();
            if (encoded.getBytes(java.nio.charset.StandardCharsets.UTF_8).length
                > 512 * 1024) {
                throw new IllegalArgumentException(
                    "large management snapshots must use the Service-owned snapshot overload"
                );
            }
            intent.putExtra(
                EXTRA_EXISTING_SNAPSHOT_JSON,
                encoded
            );
        }
        return intent;
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_management_import);
        SystemBarInsets.apply(findViewById(R.id.root_management_import));
        bindViews();
        readSnapshot();
        fileLauncher = registerForActivityResult(
            new ActivityResultContracts.OpenMultipleDocuments(),
            this::appendDocuments
        );
        addButton.setOnClickListener(view -> launchPicker());
        removeModeButton.setOnClickListener(view -> {
            if (busy || rows.isEmpty()) return;
            if (removeMode) {
                removeSelected();
                return;
            }
            removeMode = true;
            selectedForRemoval.clear();
            renderFiles();
        });
        removeCancelButton.setOnClickListener(view -> {
            removeMode = false;
            selectedForRemoval.clear();
            renderFiles();
        });
        confirmFilesButton.setOnClickListener(view -> confirmFiles());
        generatePreviewButton.setOnClickListener(view -> generatePreview());
        applyButton.setOnClickListener(view -> confirmApply());
        getOnBackPressedDispatcher().addCallback(
            this,
            new OnBackPressedCallback(true) {
                @Override
                public void handleOnBackPressed() {
                    handleBack();
                }
            }
        );
        renderFiles();
        restorePendingSubmission();
        if (!snapshotReady && !pendingSubmissionRestored
            && !pendingRecoveryUnavailable) {
            loadSnapshotFromService();
        }
    }

    private void bindViews() {
        toolbar = findViewById(R.id.toolbar_management_import);
        toolbar.setNavigationOnClickListener(view ->
            getOnBackPressedDispatcher().onBackPressed()
        );
        statusView = findViewById(R.id.tv_management_import_status);
        fileContainer = findViewById(R.id.container_management_import_files);
        preflightContainer = findViewById(
            R.id.container_management_import_preflight
        );
        previewView = findViewById(R.id.tv_management_import_preview);
        fileScroll = findViewById(R.id.scroll_management_import_files);
        preflightScroll = findViewById(R.id.scroll_management_import_preflight);
        previewScroll = findViewById(R.id.scroll_management_import_preview);
        previewActions = findViewById(
            R.id.container_management_import_preview_actions
        );
        addButton = findViewById(R.id.btn_management_import_add);
        removeModeButton = findViewById(
            R.id.btn_management_import_remove_mode
        );
        removeCancelButton = findViewById(
            R.id.btn_management_import_remove_cancel
        );
        confirmFilesButton = findViewById(
            R.id.btn_management_import_confirm_files
        );
        generatePreviewButton = findViewById(
            R.id.btn_management_import_generate_preview
        );
        applyButton = findViewById(R.id.btn_management_import_apply);
    }

    private void readSnapshot() {
        if (getIntent().getBooleanExtra(EXTRA_AUTO_CANCEL_TASKS, false)) {
            taskAction = ManagementImportModel.TaskAction.CANCEL;
        }
        String encoded = getIntent().getStringExtra(EXTRA_EXISTING_SNAPSHOT_JSON);
        if (encoded == null || encoded.trim().isEmpty()) {
            existingSnapshot = ManagementImportModel.ExistingSnapshot.empty();
            snapshotReady = false;
            return;
        }
        try {
            existingSnapshot = ManagementImportModel.ExistingSnapshot.fromJson(
                new JSONObject(encoded)
            );
            snapshotReady = true;
        } catch (Exception error) {
            existingSnapshot = ManagementImportModel.ExistingSnapshot.empty();
            snapshotReady = false;
            statusView.setText(
                getString(
                    R.string.management_import_failed,
                    "管理快照无法解析"
                )
            );
        }
    }

    /** Restores only the small durable submit pointer; the preview stays on disk. */
    private void restorePendingSubmission() {
        try {
            ManagementImportSessionStore.PendingSubmission pending =
                ManagementImportSessionStore.readPending(this);
            if (pending == null) {
                return;
            }
            sessionToken = pending.sessionToken;
            pendingSnapshotFingerprint = pending.snapshotFingerprint;
            pendingSubmissionRestored = true;
            submissionUnknown = true;
            renderPendingSubmission();
        } catch (Exception error) {
            // Keep the unreadable pointer in place.  A new token must not be
            // created until this unresolved handoff is repaired or retried.
            pendingRecoveryUnavailable = true;
            statusView.setText(getString(
                R.string.management_import_failed,
                safeMessage(error)
            ));
            applyButton.setEnabled(false);
        }
    }

    private void loadSnapshotFromService() {
        if (snapshotLoading || snapshotReady || isFinishing()) {
            return;
        }
        snapshotLoading = true;
        statusView.setText(R.string.management_import_snapshot_loading);
        try {
            ensureManagementClient();
        } catch (Exception error) {
            snapshotLoading = false;
            statusView.setText(getString(
                R.string.management_import_failed,
                safeMessage(error)
            ));
            return;
        }
        ioExecutor.execute(() -> {
            try {
                TranslationJobControlClient client = managementClient;
                if (client == null || !client.awaitConnected(10000L)) {
                    throw new IOException("TranslationService is not ready");
                }
                JSONObject snapshot = null;
                Exception lastFailure = null;
                for (int attempt = 0; attempt < 24; attempt++) {
                    try {
                        snapshot = client.readManagementImportSnapshot();
                        break;
                    } catch (TranslationJobControlClient.ServiceUnavailableException
                        notReady) {
                        lastFailure = notReady;
                        try {
                            Thread.sleep(250L);
                        } catch (InterruptedException interrupted) {
                            Thread.currentThread().interrupt();
                            throw interrupted;
                        }
                    }
                }
                if (snapshot == null) {
                    throw lastFailure == null
                        ? new IOException("TranslationService is not ready")
                        : lastFailure;
                }
                ManagementImportModel.ExistingSnapshot parsed =
                    ManagementImportModel.ExistingSnapshot.fromJson(snapshot);
                postUiIfAlive(() -> {
                    snapshotLoading = false;
                    existingSnapshot = parsed;
                    snapshotReady = true;
                    statusView.setText(
                        R.string.management_import_snapshot_ready
                    );
                    if (phase == Phase.PREFLIGHT && batch != null) {
                        renderPreflight();
                    } else {
                        renderFiles();
                    }
                });
            } catch (Exception error) {
                postUiIfAlive(() -> {
                    snapshotLoading = false;
                    statusView.setText(getString(
                        R.string.management_import_failed,
                        safeMessage(error)
                    ));
                    if (!pendingSubmissionRestored) {
                        renderFiles();
                    }
                });
            }
        });
    }

    private void ensureManagementClient() throws Exception {
        if (managementClient != null) {
            return;
        }
        Intent serviceIntent = new Intent(
            this,
            com.quarty.housamoembedtrans.translation.TranslationService.class
        ).setAction(HetBridgeContract.ACTION_START_TRANSLATION_SERVICE);
        ContextCompat.startForegroundService(this, serviceIntent);
        managementClient = new TranslationJobControlClient(this);
        managementClient.bind();
    }

    private void launchPicker() {
        if (busy || phase != Phase.FILES) return;
        removeMode = false;
        selectedForRemoval.clear();
        try {
            fileLauncher.launch(new String[] {
                "application/json",
                "text/json",
                "text/plain",
                "application/octet-stream"
            });
        } catch (RuntimeException error) {
            showError(error);
        }
    }

    private void appendDocuments(List<Uri> values) {
        if (values == null || values.isEmpty()) return;
        phase = Phase.FILES;
        batch = null;
        prepared = null;
        defaultConflictAction = ManagementImportModel.ConflictAction.OVERWRITE;
        conflictActions.clear();
        conflictSpinners.clear();
        conflictDefaultSpinner = null;
        taskActionButton = null;
        removeMode = false;
        selectedForRemoval.clear();
        final long generation = ++sessionGeneration;
        for (Uri uri : values) {
            if (uri == null) continue;
            try {
                getContentResolver().takePersistableUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION
                );
            } catch (SecurityException ignored) {
                // A transient grant is sufficient while this Activity lives.
            }
            String token = java.util.UUID.randomUUID().toString();
            FileRow row = new FileRow(
                token,
                uri,
                ManagementTransfer.displayName(getContentResolver(), uri)
            );
            rows.put(token, row);
            readRow(row, generation);
        }
        renderFiles();
    }

    private void readRow(FileRow row, long generation) {
        busy = true;
        ioExecutor.execute(() -> {
            try {
                ManagementImportModel.Document document =
                    ManagementTransfer.readDocument(
                        getContentResolver(),
                        row.uri,
                        row.name
                    );
                postUiIfAlive(() -> {
                    if (generation != sessionGeneration || !rows.containsKey(row.token)) {
                        return;
                    }
                    row.document = document;
                    row.state = FileState.READY;
                    row.error = null;
                    busy = hasReadingRows();
                    renderFiles();
                });
            } catch (Exception error) {
                postUiIfAlive(() -> {
                    if (generation != sessionGeneration || !rows.containsKey(row.token)) {
                        return;
                    }
                    row.state = FileState.ERROR;
                    row.error = safeMessage(error);
                    busy = hasReadingRows();
                    renderFiles();
                });
            }
        });
    }

    private boolean hasReadingRows() {
        for (FileRow row : rows.values()) {
            if (row.state == FileState.READING) return true;
        }
        return false;
    }

    private void renderFiles() {
        if (fileContainer == null) return;
        fileContainer.removeAllViews();
        LayoutInflater inflater = LayoutInflater.from(this);
        for (FileRow row : rows.values()) {
            View view = inflater.inflate(
                R.layout.item_management_import_file,
                fileContainer,
                false
            );
            CheckBox check = view.findViewById(
                R.id.check_management_import_file
            );
            TextView name = view.findViewById(
                R.id.tv_management_import_file_name
            );
            TextView state = view.findViewById(
                R.id.tv_management_import_file_state
            );
            name.setText(row.name);
            check.setVisibility(removeMode ? View.VISIBLE : View.GONE);
            check.setClickable(removeMode);
            check.setFocusable(removeMode);
            check.setChecked(selectedForRemoval.contains(row.token));
            check.setOnCheckedChangeListener((button, checked) -> {
                if (checked) selectedForRemoval.add(row.token);
                else selectedForRemoval.remove(row.token);
                updateFileActions();
            });
            if (row.state == FileState.READING) {
                state.setText(R.string.management_import_reading);
                state.setTextColor(
                    ContextCompat.getColor(this, R.color.het_on_surface_muted)
                );
            } else if (row.state == FileState.READY) {
                state.setText(getString(
                    R.string.management_import_ready,
                    typeLabel(row.document.type),
                    row.document.records.size()
                ));
                state.setTextColor(
                    ContextCompat.getColor(this, R.color.het_good)
                );
            } else {
                state.setText(getString(
                    R.string.management_import_failed,
                    row.error == null
                        ? getString(R.string.management_import_unknown)
                        : row.error
                ));
                state.setTextColor(
                    ContextCompat.getColor(this, R.color.het_error)
                );
            }
            fileContainer.addView(view);
        }
        updateFileActions();
        if (rows.isEmpty()) {
            statusView.setText(R.string.management_import_empty);
        } else if (hasReadingRows()) {
            statusView.setText(R.string.management_import_reading);
        } else {
            statusView.setText(getString(
                R.string.management_import_files_summary,
                rows.size()
            ));
        }
    }

    private void updateFileActions() {
        addButton.setEnabled(!busy && phase == Phase.FILES);
        removeModeButton.setVisibility(
            removeMode ? View.GONE : View.VISIBLE
        );
        removeCancelButton.setVisibility(
            removeMode ? View.VISIBLE : View.GONE
        );
        removeModeButton.setEnabled(
            !busy && phase == Phase.FILES && !rows.isEmpty()
        );
        removeCancelButton.setEnabled(!busy);
        confirmFilesButton.setEnabled(
            !busy && snapshotReady && phase == Phase.FILES && allReady()
        );
        if (removeMode) {
            removeModeButton.setText(
                getString(
                    R.string.management_import_remove_selected,
                    selectedForRemoval.size()
                )
            );
        } else {
            removeModeButton.setText(
                R.string.management_import_remove_mode
            );
        }
        if (removeMode && selectedForRemoval.size() > 0) {
            removeModeButton.setVisibility(View.VISIBLE);
            removeModeButton.setEnabled(!busy);
        }
    }

    private boolean allReady() {
        if (rows.isEmpty()) return false;
        for (FileRow row : rows.values()) {
            if (row.state != FileState.READY || row.document == null) return false;
        }
        return true;
    }

    private void confirmFiles() {
        if (busy || !snapshotReady || !allReady()) {
            statusView.setText(R.string.management_import_wait_all);
            return;
        }
        setBusy(true);
        final long generation = sessionGeneration;
        ioExecutor.execute(() -> {
            try {
                List<ManagementImportModel.Document> documents = new ArrayList<>();
                for (FileRow row : rows.values()) {
                    documents.add(row.document);
                }
                ManagementImportModel.Batch combined =
                    ManagementImportModel.combineImportDocuments(documents);
                postUiIfAlive(() -> {
                    if (generation != sessionGeneration) return;
                    batch = combined;
                    phase = Phase.PREFLIGHT;
                    setBusy(false);
                    renderPreflight();
                });
            } catch (Exception error) {
                postUiIfAlive(() -> {
                    if (generation != sessionGeneration) return;
                    setBusy(false);
                    showError(error);
                });
            }
        });
    }

    private void renderPreflight() {
        fileScroll.setVisibility(View.GONE);
        confirmFilesButton.setVisibility(View.GONE);
        preflightScroll.setVisibility(View.VISIBLE);
        previewActions.setVisibility(View.VISIBLE);
        generatePreviewButton.setVisibility(View.VISIBLE);
        applyButton.setVisibility(View.VISIBLE);
        previewScroll.setVisibility(View.GONE);
        preflightContainer.removeAllViews();
        conflictSpinners.clear();
        conflictDefaultSpinner = null;
        taskActionButton = null;
        addHeading(R.string.management_import_preflight_title);
        addSummary();
        List<ManagementImportModel.Conflict> conflicts = new ArrayList<>();
        try {
            ManagementImportModel.PreparedImport first =
                ManagementImportModel.prepareImport(
                    batch,
                    existingSnapshot,
                    conflictActions,
                    defaultConflictAction,
                    taskAction
                );
            prepared = first;
            conflicts.addAll(first.conflicts);
        } catch (Exception error) {
            showError(error);
            return;
        }
        TextView conflictHeading = new TextView(this);
        conflictHeading.setText(getString(
            R.string.management_import_conflicts_title,
            conflicts.size()
        ));
        conflictHeading.setTextAppearance(
            this,
            com.google.android.material.R.style.TextAppearance_MaterialComponents_Headline6
        );
        conflictHeading.setTextColor(
            ContextCompat.getColor(this, R.color.het_on_surface)
        );
        LinearLayout.LayoutParams conflictHeadingParams =
            new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            );
        conflictHeadingParams.topMargin = dp(12);
        conflictHeadingParams.bottomMargin = dp(4);
        conflictHeading.setLayoutParams(conflictHeadingParams);
        preflightContainer.addView(conflictHeading);
        if (!conflicts.isEmpty()) {
            addConflictDefaultControl();
        }
        if (conflicts.isEmpty()) {
            preflightContainer.addView(cardText(
                getString(R.string.management_import_no_conflicts),
                com.google.android.material.R.style.TextAppearance_MaterialComponents_Body2
            ));
        } else {
            LayoutInflater inflater = LayoutInflater.from(this);
            for (ManagementImportModel.Conflict conflict : conflicts) {
                View row = inflater.inflate(
                    R.layout.item_management_import_conflict,
                    preflightContainer,
                    false
                );
                TextView title = row.findViewById(
                    R.id.tv_management_import_conflict_title
                );
                title.setText(
                    typeLabel(conflict.incoming.kind) + " · "
                        + conflict.incoming.canonicalId
                        + " · " + conflict.incoming.sourceName
                );
                Spinner spinner = row.findViewById(
                    R.id.spinner_management_import_conflict_action
                );
                ArrayAdapter<String> adapter = new ArrayAdapter<>(
                    this,
                    android.R.layout.simple_spinner_item,
                    new String[] {
                        getString(
                            R.string.management_import_action_default,
                            conflictActionLabel(defaultConflictAction)
                        ),
                        getString(R.string.management_import_action_overwrite),
                        getString(R.string.management_import_action_copy),
                        getString(R.string.management_import_action_skip)
                    }
                );
                adapter.setDropDownViewResource(
                    android.R.layout.simple_spinner_dropdown_item
                );
                spinner.setAdapter(adapter);
                ManagementImportModel.ConflictAction explicitAction =
                    conflictActions.get(conflict.key);
                spinner.setSelection(
                    explicitAction == null
                        ? 0
                        : actionIndex(explicitAction) + 1
                );
                conflictSpinners.put(conflict.key, spinner);
                spinner.setOnItemSelectedListener(
                    new android.widget.AdapterView.OnItemSelectedListener() {
                        @Override
                        public void onItemSelected(
                            android.widget.AdapterView<?> parent,
                            View view,
                            int position,
                            long id
                        ) {
                            if (busy) return;
                            if (conflictSpinners.get(conflict.key) != spinner) {
                                return;
                            }
                            ManagementImportModel.ConflictAction previous =
                                conflictActions.get(conflict.key);
                            if (position == 0) {
                                if (previous == null) return;
                                conflictActions.remove(conflict.key);
                            } else {
                                ManagementImportModel.ConflictAction next =
                                    actionAt(position - 1);
                                if (next == previous) return;
                                conflictActions.put(conflict.key, next);
                            }
                            ++sessionGeneration;
                            prepared = null;
                            phase = Phase.PREFLIGHT;
                            applyButton.setEnabled(false);
                            previewScroll.setVisibility(View.GONE);
                            generatePreviewButton.setEnabled(true);
                        }

                        @Override
                        public void onNothingSelected(
                            android.widget.AdapterView<?> parent
                        ) {
                        }
                    }
                );
                spinner.setEnabled(!busy);
                preflightContainer.addView(row);
            }
        }
        addTaskActionControl(conflicts);
        generatePreviewButton.setEnabled(!busy);
        applyButton.setEnabled(false);
    }

    /** Shows a compact retry entry without rebuilding the large preview model. */
    private void renderPendingSubmission() {
        phase = Phase.PREVIEW;
        fileScroll.setVisibility(View.GONE);
        confirmFilesButton.setVisibility(View.GONE);
        preflightScroll.setVisibility(View.GONE);
        previewActions.setVisibility(View.VISIBLE);
        generatePreviewButton.setVisibility(View.GONE);
        applyButton.setVisibility(View.VISIBLE);
        previewScroll.setVisibility(View.VISIBLE);
        previewView.setText(R.string.management_import_pending_submission);
        statusView.setText(R.string.management_import_pending_submission);
        applyButton.setEnabled(!busy);
    }

    private void addHeading(int textId) {
        TextView heading = new TextView(this);
        heading.setText(textId);
        heading.setTextAppearance(
            this,
            com.google.android.material.R.style.TextAppearance_MaterialComponents_Headline6
        );
        heading.setTextColor(
            ContextCompat.getColor(this, R.color.het_on_surface)
        );
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        );
        params.bottomMargin = dp(4);
        heading.setLayoutParams(params);
        preflightContainer.addView(heading);
    }

    private void addSummary() {
        StringBuilder summary = new StringBuilder();
        summary.append(getString(
            R.string.management_import_files_summary,
            batch.documents.size()
        ));
        for (ManagementImportModel.Document document : batch.documents) {
            summary.append('\n')
                .append(document.sourceName)
                .append(" · ")
                .append(typeLabel(document.type))
                .append(" · ")
                .append(document.records.size());
        }
        preflightContainer.addView(cardText(
            summary.toString(),
            com.google.android.material.R.style.TextAppearance_MaterialComponents_Body2
        ));
    }

    private void addConflictDefaultControl() {
        MaterialCardView card = importCard();
        LinearLayout body = new LinearLayout(this);
        body.setOrientation(LinearLayout.VERTICAL);
        body.setPadding(dp(12), dp(12), dp(12), dp(8));
        TextView heading = new TextView(this);
        heading.setText(R.string.management_import_default_action);
        heading.setTextAppearance(
            this,
            com.google.android.material.R.style.TextAppearance_MaterialComponents_Subtitle1
        );
        heading.setTextColor(
            ContextCompat.getColor(this, R.color.het_on_surface)
        );
        body.addView(heading);
        TextView hint = new TextView(this);
        hint.setText(R.string.management_import_default_action_hint);
        hint.setTextAppearance(
            this,
            com.google.android.material.R.style.TextAppearance_MaterialComponents_Body2
        );
        hint.setTextColor(
            ContextCompat.getColor(this, R.color.het_on_surface)
        );
        body.addView(hint, new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        ));
        Spinner spinner = new Spinner(this);
        ArrayAdapter<String> adapter = new ArrayAdapter<>(
            this,
            android.R.layout.simple_spinner_item,
            new String[] {
                getString(R.string.management_import_action_overwrite),
                getString(R.string.management_import_action_copy),
                getString(R.string.management_import_action_skip)
            }
        );
        adapter.setDropDownViewResource(
            android.R.layout.simple_spinner_dropdown_item
        );
        spinner.setAdapter(adapter);
        spinner.setSelection(actionIndex(defaultConflictAction));
        conflictDefaultSpinner = spinner;
        spinner.setOnItemSelectedListener(
            new android.widget.AdapterView.OnItemSelectedListener() {
                @Override
                public void onItemSelected(
                    android.widget.AdapterView<?> parent,
                    View view,
                    int position,
                    long id
                ) {
                    if (busy) return;
                    if (conflictDefaultSpinner != spinner) return;
                    ManagementImportModel.ConflictAction next = actionAt(position);
                    if (next == defaultConflictAction) return;
                    defaultConflictAction = next;
                    ++sessionGeneration;
                    prepared = null;
                    phase = Phase.PREFLIGHT;
                    applyButton.setEnabled(false);
                    previewScroll.setVisibility(View.GONE);
                    renderPreflight();
                }

                @Override
                public void onNothingSelected(
                    android.widget.AdapterView<?> parent
                ) {
                }
            }
        );
        spinner.setEnabled(!busy);
        body.addView(spinner, new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        ));
        card.addView(body);
        preflightContainer.addView(card);
    }

    private void addTaskActionControl(
        List<ManagementImportModel.Conflict> conflicts
    ) {
        int taskCount = 0;
        for (ManagementImportModel.Conflict conflict : conflicts) {
            taskCount += conflict.activeRequestIds.size();
        }
        MaterialCardView card = importCard();
        LinearLayout body = new LinearLayout(this);
        body.setOrientation(LinearLayout.VERTICAL);
        body.setPadding(dp(12), dp(12), dp(12), dp(8));
        TextView heading = new TextView(this);
        heading.setText(R.string.management_import_tasks_title);
        heading.setTextAppearance(
            this,
            com.google.android.material.R.style.TextAppearance_MaterialComponents_Subtitle1
        );
        heading.setTextColor(
            ContextCompat.getColor(this, R.color.het_on_surface)
        );
        body.addView(heading);
        MaterialButton taskButton = styledButton(
            R.style.Widget_HET_Button_Secondary
        );
        updateTaskButton(taskButton, taskCount);
        taskButton.setOnClickListener(view -> {
            if (busy) return;
            taskAction = taskAction == ManagementImportModel.TaskAction.KEEP
                ? ManagementImportModel.TaskAction.CANCEL
                : ManagementImportModel.TaskAction.KEEP;
            updateTaskButton(taskButton, taskCount);
            ++sessionGeneration;
            prepared = null;
            phase = Phase.PREFLIGHT;
            applyButton.setEnabled(false);
            previewScroll.setVisibility(View.GONE);
            generatePreviewButton.setEnabled(true);
        });
        taskButton.setEnabled(!busy);
        taskActionButton = taskButton;
        body.addView(taskButton, new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        ));
        card.addView(body);
        preflightContainer.addView(card);
    }

    private void updateTaskButton(MaterialButton button, int count) {
        button.setText(getString(
            taskAction == ManagementImportModel.TaskAction.CANCEL
                ? R.string.management_import_tasks_cancel
                : R.string.management_import_tasks_keep,
            count
        ));
    }

    private MaterialCardView importCard() {
        MaterialCardView card = new MaterialCardView(this);
        card.setCardBackgroundColor(
            ContextCompat.getColor(this, R.color.het_surface_container)
        );
        card.setRadius(dp(14));
        card.setCardElevation(0);
        card.setStrokeColor(ColorStateList.valueOf(
            ContextCompat.getColor(this, R.color.het_outline_soft)
        ));
        card.setStrokeWidth(dp(1));
        card.setLayoutParams(cardParams());
        return card;
    }

    private MaterialButton styledButton(int styleResource) {
        MaterialButton button = new MaterialButton(this);
        boolean primary = styleResource == R.style.Widget_HET_Button_Primary;
        button.setAllCaps(false);
        button.setMinHeight(dp(40));
        button.setMinWidth(0);
        button.setCornerRadius(dp(20));
        button.setInsetTop(0);
        button.setInsetBottom(0);
        button.setBackgroundTintList(ColorStateList.valueOf(
            ContextCompat.getColor(
                this,
                primary
                    ? R.color.het_primary_container
                    : R.color.het_surface_container_high
            )
        ));
        button.setTextColor(ContextCompat.getColor(
            this,
            primary ? R.color.het_on_primary_container : R.color.het_on_surface
        ));
        button.setStrokeWidth(primary ? 0 : dp(1));
        if (!primary) {
            button.setStrokeColor(ColorStateList.valueOf(
                ContextCompat.getColor(this, R.color.het_outline_soft)
            ));
        }
        return button;
    }

    private MaterialCardView cardText(String value, int appearance) {
        MaterialCardView card = importCard();
        TextView text = new TextView(this);
        text.setTextAppearance(this, appearance);
        text.setTextColor(
            ContextCompat.getColor(this, R.color.het_on_surface)
        );
        text.setText(value);
        text.setTextIsSelectable(true);
        text.setPadding(dp(12), dp(12), dp(12), dp(12));
        card.addView(text);
        return card;
    }

    private LinearLayout.LayoutParams cardParams() {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        );
        params.bottomMargin = dp(12);
        return params;
    }

    private String typeLabel(String type) {
        if (ManagementImportModel.KIND_SCENE.equals(type)) {
            return getString(R.string.management_import_type_scene);
        }
        if (ManagementImportModel.KIND_CONTEXT.equals(type)) {
            return getString(R.string.management_import_type_context);
        }
        if (ManagementImportModel.KIND_GROUP.equals(type)) {
            return getString(R.string.management_import_type_group);
        }
        if (ManagementImportModel.KIND_CHARACTER.equals(type)) {
            return getString(R.string.management_import_type_character);
        }
        if (ManagementImportModel.KIND_TERM.equals(type)) {
            return getString(R.string.management_import_type_term);
        }
        return getString(R.string.management_import_type_unknown);
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    private void generatePreview() {
        if (pendingSubmissionRestored || pendingRecoveryUnavailable
            || busy || batch == null || phase != Phase.PREFLIGHT) return;
        final long generation = sessionGeneration;
        final ManagementImportModel.Batch selectedBatch = batch;
        final ManagementImportModel.ExistingSnapshot selectedSnapshot =
            existingSnapshot;
        final Map<String, ManagementImportModel.ConflictAction> decisions =
            new LinkedHashMap<>(conflictActions);
        final ManagementImportModel.ConflictAction selectedDefault =
            defaultConflictAction;
        final ManagementImportModel.TaskAction selectedTaskAction = taskAction;
        setBusy(true);
        ioExecutor.execute(() -> {
            try {
                ManagementImportModel.PreparedImport result =
                    ManagementImportModel.prepareImport(
                        selectedBatch,
                        selectedSnapshot,
                        decisions,
                        selectedDefault,
                        selectedTaskAction
                    );
                String previewText = pretty(result.toJson());
                postUiIfAlive(() -> {
                    if (generation != sessionGeneration) {
                        setBusy(false);
                        return;
                    }
                    prepared = result;
                    phase = Phase.PREVIEW;
                    setBusy(false);
                    previewView.setText(previewText);
                    previewScroll.setVisibility(View.VISIBLE);
                    applyButton.setEnabled(true);
                });
            } catch (Exception error) {
                postUiIfAlive(() -> {
                    if (generation != sessionGeneration) {
                        setBusy(false);
                        return;
                    }
                    setBusy(false);
                    showError(error);
                });
            }
        });
    }

    private void confirmApply() {
        if (busy) return;
        if (pendingRecoveryUnavailable) {
            showError(new IllegalStateException(
                getString(R.string.management_import_pending_submission)
            ));
            return;
        }
        if (pendingSubmissionRestored
            && sessionToken != null
            && pendingSnapshotFingerprint != null) {
            submitImportSession(sessionToken, pendingSnapshotFingerprint);
            return;
        }
        if (prepared == null) {
            showError(new IllegalStateException(
                getString(R.string.management_import_no_preview)
            ));
            return;
        }
        final ManagementImportModel.PreparedImport selectedPrepared = prepared;
        final long generation = sessionGeneration;
        setBusy(true);
        ioExecutor.execute(() -> {
            String newToken = null;
            try {
                JSONObject preview = selectedPrepared.toJson();
                newToken = ManagementImportSessionStore.write(this, preview);
                final String token = newToken;
                final String fingerprint = selectedPrepared.snapshot.fingerprint;
                ManagementImportSessionStore.writePending(
                    this,
                    token,
                    fingerprint
                );
                postUiIfAlive(() -> {
                    if (generation != sessionGeneration) {
                        setBusy(false);
                        ioExecutor.execute(() -> clearResolvedSession(token));
                        return;
                    }
                    setBusy(false);
                    sessionToken = token;
                    pendingSnapshotFingerprint = fingerprint;
                    pendingSubmissionRestored = true;
                    submissionUnknown = true;
                    submitImportSession(token, fingerprint);
                });
            } catch (Exception error) {
                if (newToken != null) {
                    ManagementImportSessionStore.delete(this, newToken);
                }
                postUiIfAlive(() -> {
                    if (generation != sessionGeneration) {
                        setBusy(false);
                        return;
                    }
                    setBusy(false);
                    showError(error);
                });
            }
        });
    }

    private void submitImportSession(String token, String expectedSnapshot) {
        try {
            ensureManagementClient();
        } catch (Exception error) {
            submissionUnknown = true;
            pendingSubmissionRestored = true;
            renderPendingSubmission();
            showError(error);
            return;
        }
        setBusy(true);
        ioExecutor.execute(() -> {
            try {
                TranslationJobControlClient client = managementClient;
                if (client == null) {
                    throw new IOException("TranslationService is not ready");
                }
                if (!client.awaitConnected(10000L)) {
                    throw new IOException("TranslationService is not ready");
                }
                JSONObject response = null;
                Exception lastFailure = null;
                for (int attempt = 0; attempt < 24; attempt++) {
                    try {
                        response = client.applyManagementImport(
                            token,
                            expectedSnapshot
                        );
                        break;
                    } catch (TranslationJobControlClient.ServiceUnavailableException
                        notReady) {
                        lastFailure = notReady;
                        try {
                            Thread.sleep(250L);
                        } catch (InterruptedException interrupted) {
                            Thread.currentThread().interrupt();
                            throw interrupted;
                        }
                    }
                }
                if (response == null) {
                    throw lastFailure == null
                        ? new IOException("TranslationService is not ready")
                        : lastFailure;
                }
                final JSONObject result = response;
                postUiIfAlive(() -> handleApplyResponse(token, result));
            } catch (Exception error) {
                postUiIfAlive(() -> {
                    setBusy(false);
                    // The Binder pipe may have closed after COMMITTING. Keep
                    // this exact token and make a later retry idempotent.
                    submissionUnknown = true;
                    pendingSubmissionRestored = true;
                    renderPendingSubmission();
                    statusView.setText(getString(
                        R.string.management_import_failed,
                        safeMessage(error)
                    ));
                    applyButton.setEnabled(true);
                });
            }
        });
    }

    private void handleApplyResponse(String token, JSONObject result) {
        setBusy(false);
        String state = result.optString("state", "");
        boolean ok = result.optBoolean("ok", false);
        if (ok && "applied".equals(state)) {
            clearResolvedSession(token);
            submissionUnknown = false;
            pendingSubmissionRestored = false;
            setResult(
                Activity.RESULT_OK,
                new Intent().putExtra(EXTRA_RESULT_STATE, state)
            );
            Toast.makeText(
                this,
                R.string.management_import_applied,
                Toast.LENGTH_SHORT
            ).show();
            finish();
        } else if (ok && "recovery_pending".equals(state)) {
            // COMMITTING is durable. The Service owns the retry on startup;
            // do not submit a second new preview token.
            submissionUnknown = true;
            pendingSubmissionRestored = true;
            setResult(
                Activity.RESULT_OK,
                new Intent().putExtra(EXTRA_RESULT_STATE, state)
            );
            Toast.makeText(
                this,
                R.string.management_import_recovery_pending,
                Toast.LENGTH_LONG
            ).show();
            finish();
        } else if (isDefiniteRejection(result)) {
            clearResolvedSession(token);
            sessionToken = null;
            pendingSnapshotFingerprint = null;
            submissionUnknown = false;
            pendingSubmissionRestored = false;
            prepared = null;
            if ("snapshot_changed".equals(result.optString("error", ""))) {
                snapshotReady = false;
                loadSnapshotFromService();
                statusView.setText(R.string.management_import_preview_stale);
                if (batch != null) {
                    phase = Phase.PREFLIGHT;
                    renderPreflight();
                } else {
                    resetToFiles();
                }
            } else {
                if (batch != null) {
                    phase = Phase.PREFLIGHT;
                    renderPreflight();
                } else {
                    resetToFiles();
                }
                showError(new IOException(result.optString(
                    "message",
                    "导入未应用"
                )));
            }
        } else {
            // A response outside the known result contract is still
            // uncertain. Keep the exact pointer and allow an idempotent retry.
            submissionUnknown = true;
            pendingSubmissionRestored = true;
            statusView.setText(getString(
                R.string.management_import_failed,
                result.optString("message", "导入结果未知")
            ));
            applyButton.setEnabled(true);
        }
    }

    private void handleBack() {
        if (busy) return;
        if (removeMode) {
            removeMode = false;
            selectedForRemoval.clear();
            renderFiles();
            return;
        }
        if (phase == Phase.PREVIEW) {
            if (pendingSubmissionRestored) {
                // The durable pointer remains available for a later retry.
                finish();
                return;
            }
            phase = Phase.PREFLIGHT;
            previewScroll.setVisibility(View.GONE);
            applyButton.setEnabled(false);
            return;
        }
        if (phase == Phase.PREFLIGHT) {
            phase = Phase.FILES;
            batch = null;
            prepared = null;
            conflictActions.clear();
            conflictSpinners.clear();
            conflictDefaultSpinner = null;
            taskActionButton = null;
            fileScroll.setVisibility(View.VISIBLE);
            confirmFilesButton.setVisibility(View.VISIBLE);
            preflightScroll.setVisibility(View.GONE);
            previewActions.setVisibility(View.GONE);
            renderFiles();
            return;
        }
        finish();
    }

    private void setBusy(boolean value) {
        busy = value;
        if (phase == Phase.FILES) {
            updateFileActions();
        }
        for (Spinner spinner : conflictSpinners.values()) {
            spinner.setEnabled(!value);
        }
        if (conflictDefaultSpinner != null) {
            conflictDefaultSpinner.setEnabled(!value);
        }
        if (taskActionButton != null) {
            taskActionButton.setEnabled(!value);
        }
        if (generatePreviewButton != null) {
            generatePreviewButton.setEnabled(!value && phase == Phase.PREFLIGHT);
            applyButton.setEnabled(
                !value
                    && phase == Phase.PREVIEW
                    && (prepared != null || pendingSubmissionRestored)
            );
        }
    }

    private void resetToFiles() {
        phase = Phase.FILES;
        batch = null;
        prepared = null;
        conflictActions.clear();
        conflictSpinners.clear();
        conflictDefaultSpinner = null;
        taskActionButton = null;
        fileScroll.setVisibility(View.VISIBLE);
        confirmFilesButton.setVisibility(View.VISIBLE);
        preflightScroll.setVisibility(View.GONE);
        previewActions.setVisibility(View.GONE);
        previewScroll.setVisibility(View.GONE);
        renderFiles();
    }

    private void removeSelected() {
        if (selectedForRemoval.isEmpty()) return;
        ++sessionGeneration;
        for (String token : new ArrayList<>(selectedForRemoval)) {
            rows.remove(token);
        }
        selectedForRemoval.clear();
        removeMode = false;
        renderFiles();
    }

    private String conflictActionLabel(
        ManagementImportModel.ConflictAction action
    ) {
        if (action == ManagementImportModel.ConflictAction.COPY) {
            return getString(R.string.management_import_action_copy);
        }
        if (action == ManagementImportModel.ConflictAction.SKIP) {
            return getString(R.string.management_import_action_skip);
        }
        return getString(R.string.management_import_action_overwrite);
    }

    private int actionIndex(ManagementImportModel.ConflictAction action) {
        if (action == ManagementImportModel.ConflictAction.COPY) return 1;
        if (action == ManagementImportModel.ConflictAction.SKIP) return 2;
        return 0;
    }

    private ManagementImportModel.ConflictAction actionAt(int index) {
        if (index == 1) return ManagementImportModel.ConflictAction.COPY;
        if (index == 2) return ManagementImportModel.ConflictAction.SKIP;
        return ManagementImportModel.ConflictAction.OVERWRITE;
    }

    private void showError(Throwable error) {
        Toast.makeText(
            this,
            safeMessage(error),
            Toast.LENGTH_LONG
        ).show();
    }

    private void clearResolvedSession(String token) {
        // Keep the preview until the pointer is gone.  If pointer cleanup
        // fails, the retained token can still be queried idempotently.
        if (ManagementImportSessionStore.deletePending(this, token)) {
            ManagementImportSessionStore.delete(this, token);
        }
    }

    private static boolean isDefiniteRejection(JSONObject result) {
        if (result == null || result.optBoolean("ok", false)) {
            return false;
        }
        String error = result.optString("error", "");
        return "invalid_session".equals(error)
            || "invalid_snapshot".equals(error)
            || "snapshot_changed".equals(error)
            || "import_not_applied".equals(error);
    }

    private void postUiIfAlive(Runnable action) {
        runOnUiThread(() -> {
            if (destroyed || isFinishing()) {
                return;
            }
            action.run();
        });
    }

    private static String pretty(JSONObject value) {
        try {
            return value.toString(2);
        } catch (Exception error) {
            return value.toString();
        }
    }

    private static String safeMessage(Throwable error) {
        if (error == null) return "operation_failed";
        String message = error.getMessage();
        return message == null || message.trim().isEmpty()
            ? error.getClass().getSimpleName()
            : message;
    }

    @Override
    protected void onDestroy() {
        destroyed = true;
        ioExecutor.shutdownNow();
        if (managementClient != null) {
            managementClient.close();
            managementClient = null;
        }
        super.onDestroy();
    }
}
