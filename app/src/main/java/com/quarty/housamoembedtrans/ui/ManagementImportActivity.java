package com.quarty.housamoembedtrans.ui;

import com.quarty.housamoembedtrans.R;
import com.quarty.housamoembedtrans.bridge.HetBridgeContract;
import com.quarty.housamoembedtrans.bridge.TranslationJobControlClient;
import com.quarty.housamoembedtrans.scene.store.SceneStore;
import com.quarty.housamoembedtrans.storage.json.JsonSchemaValidator;
import com.quarty.housamoembedtrans.util.IoUtils;

import android.app.Activity;
import android.content.Intent;
import android.content.res.ColorStateList;
import android.net.Uri;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.CheckBox;
import android.widget.LinearLayout;
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

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.nio.charset.StandardCharsets;

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
    private static final int MAX_SCENE_SCHEMA_BYTES = 256 * 1024;

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
    private final LinkedHashMap<String, MaterialButton> conflictSelectors =
        new LinkedHashMap<>();

    private ActivityResultLauncher<String[]> fileLauncher;
    private MaterialToolbar toolbar;
    private LinearLayout fileActions;
    private LinearLayout sourceCard;
    private TextView pageEyebrow;
    private TextView pageHeading;
    private TextView pageDescription;
    private TextView statusView;
    private LinearLayout fileContainer;
    private LinearLayout preflightContainer;
    private TextView previewView;
    private LinearLayout previewFilesContainer;
    private DraggableScrollbarNestedScrollView fileScroll;
    private DraggableScrollbarNestedScrollView preflightScroll;
    private DraggableScrollbarNestedScrollView previewScroll;
    private LinearLayout previewActions;
    private View confirmContainer;
    private MaterialButton addButton;
    private MaterialButton removeModeButton;
    private MaterialButton removeCancelButton;
    private MaterialButton confirmFilesButton;
    private MaterialButton generatePreviewButton;
    private MaterialButton applyButton;
    private MaterialButton taskActionButton;

    private final Set<String> selectedForRemoval = new LinkedHashSet<>();
    private Phase phase = Phase.FILES;
    private boolean removeMode;
    private boolean busy;
    private boolean snapshotReady;
    private boolean snapshotLoading;
    private boolean submissionUnknown;
    /** A rejection or stale-snapshot notice that must survive a preflight redraw. */
    private String preflightNotice;
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
    private boolean stylePreview;
    private boolean destroyed;
    private TranslationJobControlClient managementClient;
    private JsonSchemaValidator sceneSchemaValidator;

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
        stylePreview = StylePreview.isEnabled(this);
        sceneSchemaValidator = stylePreview ? null : loadSceneSchemaValidator();
        if (!stylePreview) {
            readSnapshot();
        } else {
            existingSnapshot = ManagementImportModel.ExistingSnapshot.empty();
            snapshotReady = true;
        }
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
        if (stylePreview) {
            loadStylePreview();
            return;
        }
        renderFiles();
        restorePendingSubmission();
        if (!snapshotReady && !pendingSubmissionRestored
            && !pendingRecoveryUnavailable) {
            loadSnapshotFromService();
        }
    }

    /** Builds the import model entirely from the Intent payload. */
    private void loadStylePreview() {
        try {
            JSONObject payload = StylePreview.payloadOf(getIntent());
            if (payload == null) {
                throw new IllegalStateException("preview payload is unavailable");
            }
            JSONArray documents = payload.optJSONArray("documents");
            JSONObject entry = documents == null || documents.length() == 0
                ? null
                : documents.optJSONObject(0);
            JSONObject root = entry == null ? null : entry.optJSONObject("root");
            if (root == null) {
                throw new IllegalStateException("preview import document is unavailable");
            }
            String sourceName = entry.optString(
                "source_name",
                "style-preview-management.json"
            );
            ManagementImportModel.Document document =
                ManagementImportModel.parseDocument(
                    sourceName,
                    root.toString().getBytes(StandardCharsets.UTF_8)
                );
            List<ManagementImportModel.Document> input = new ArrayList<>();
            input.add(document);
            batch = ManagementImportModel.combineImportDocuments(input);
            JSONObject snapshot = payload.optJSONObject("snapshot");
            existingSnapshot = snapshot == null
                ? ManagementImportModel.ExistingSnapshot.empty()
                : ManagementImportModel.ExistingSnapshot.fromJson(snapshot);
            snapshotReady = true;
            prepared = ManagementImportModel.prepareImport(
                batch,
                existingSnapshot,
                new LinkedHashMap<>(),
                defaultConflictAction,
                taskAction
            );
            phase = Phase.PREFLIGHT;
            busy = false;
            preflightNotice = null;
            renderPreflight();
            applyButton.setVisibility(View.GONE);
            statusView.setVisibility(View.VISIBLE);
            statusView.setText(R.string.style_preview_read_only_body);
            for (MaterialButton selector : conflictSelectors.values()) {
                selector.setEnabled(false);
            }
            if (taskActionButton != null) taskActionButton.setEnabled(false);
        } catch (Exception error) {
            statusView.setVisibility(View.VISIBLE);
            statusView.setText(getString(
                R.string.management_import_failed,
                safeMessage(error)
            ));
        }
    }

    /** Loads the existing Scene schema for import-time feedback. */
    private JsonSchemaValidator loadSceneSchemaValidator() {
        try (InputStream input = getAssets().open(SceneStore.SCHEMA_ASSET_PATH)) {
            return new JsonSchemaValidator(new JSONObject(new String(
                IoUtils.readAllBytesLimited(input, MAX_SCENE_SCHEMA_BYTES),
                StandardCharsets.UTF_8
            )));
        } catch (Exception ignored) {
            // Scene documents fail closed in ManagementImportModel when the
            // validator is unavailable; other import kinds keep their parser.
            return null;
        }
    }

    private void bindViews() {
        toolbar = findViewById(R.id.toolbar_management_import);
        toolbar.setNavigationOnClickListener(view ->
            getOnBackPressedDispatcher().onBackPressed()
        );
        fileActions = findViewById(
            R.id.container_management_import_file_actions
        );
        sourceCard = findViewById(
            R.id.container_management_import_source_card
        );
        pageEyebrow = findViewById(R.id.tv_management_import_eyebrow);
        pageHeading = findViewById(R.id.tv_management_import_heading);
        pageDescription = findViewById(R.id.tv_management_import_description);
        statusView = findViewById(R.id.tv_management_import_status);
        fileContainer = findViewById(R.id.container_management_import_files);
        preflightContainer = findViewById(
            R.id.container_management_import_preflight
        );
        previewView = findViewById(R.id.tv_management_import_preview);
        previewFilesContainer = findViewById(
            R.id.container_management_import_preview_files
        );
        fileScroll = findViewById(R.id.scroll_management_import_files);
        preflightScroll = findViewById(R.id.scroll_management_import_preflight);
        previewScroll = findViewById(R.id.scroll_management_import_preview);
        previewActions = findViewById(
            R.id.container_management_import_preview_actions
        );
        confirmContainer = findViewById(R.id.container_management_import_confirm);
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
                    String failureMessage = getString(
                        R.string.management_import_failed,
                        safeMessage(error)
                    );
                    if (!pendingSubmissionRestored
                        && phase == Phase.PREFLIGHT
                        && batch != null) {
                        preflightNotice = failureMessage;
                        renderPreflight();
                    } else if (!pendingSubmissionRestored) {
                        phase = Phase.FILES;
                        renderFiles();
                        statusView.setText(failureMessage);
                    }
                });
            }
        });
    }

    private void ensureManagementClient() throws Exception {
        if (stylePreview) {
            throw new IllegalStateException("management service is disabled in preview");
        }
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
        if (stylePreview || busy || phase != Phase.FILES) return;
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
        if (stylePreview || values == null || values.isEmpty()) return;
        phase = Phase.FILES;
        batch = null;
        prepared = null;
        defaultConflictAction = ManagementImportModel.ConflictAction.OVERWRITE;
        conflictActions.clear();
        conflictSelectors.clear();
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
                            row.name,
                            sceneSchemaValidator
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
        preflightNotice = null;
        setPageHeader(
            R.string.management_import_eyebrow,
            R.string.management_import_source_title,
            R.string.management_import_source_description
        );
        pageDescription.setVisibility(View.GONE);
        sourceCard.setVisibility(View.VISIBLE);
        fileActions.setVisibility(View.VISIBLE);
        fileScroll.setVisibility(View.VISIBLE);
        statusView.setVisibility(View.VISIBLE);
        confirmContainer.setVisibility(View.VISIBLE);
        preflightScroll.setVisibility(View.GONE);
        previewActions.setVisibility(View.GONE);
        previewScroll.setVisibility(View.GONE);
        if (preflightContainer != null) {
            preflightContainer.removeAllViews();
        }
        fileContainer.removeAllViews();
        LayoutInflater inflater = LayoutInflater.from(this);
        for (FileRow row : rows.values()) {
            View view = inflater.inflate(
                R.layout.item_management_import_file,
                fileContainer,
                false
            );
            MaterialCardView fileCard = (MaterialCardView) view;
            fileCard.setCardBackgroundColor(ContextCompat.getColor(
                this,
                row.state == FileState.ERROR
                    ? R.color.het_error_container
                    : R.color.het_surface_container_high
            ));
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
            removeModeButton.setText("");
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
        if (stylePreview) return;
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
                    statusView.setVisibility(View.VISIBLE);
                    statusView.setText(getString(
                        R.string.management_import_failed,
                        safeMessage(error)
                    ));
                    showError(error);
                });
            }
        });
    }

    private void renderPreflight() {
        setPageHeader(
            R.string.management_import_preflight_eyebrow,
            R.string.management_import_preflight_title,
            R.string.management_import_preflight_description
        );
        pageDescription.setVisibility(View.GONE);
        sourceCard.setVisibility(View.GONE);
        fileActions.setVisibility(View.GONE);
        fileScroll.setVisibility(View.GONE);
        if (preflightNotice == null || preflightNotice.trim().isEmpty()) {
            statusView.setVisibility(View.GONE);
        } else {
            statusView.setVisibility(View.VISIBLE);
            statusView.setText(preflightNotice);
        }
        confirmContainer.setVisibility(View.GONE);
        preflightScroll.setVisibility(View.VISIBLE);
        previewActions.setVisibility(View.VISIBLE);
        generatePreviewButton.setVisibility(View.VISIBLE);
        applyButton.setVisibility(View.GONE);
        previewScroll.setVisibility(View.GONE);
        preflightContainer.removeAllViews();
        addPreflightNotice();
        conflictSelectors.clear();
        taskActionButton = null;
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
            preflightNotice = getString(
                R.string.management_import_failed,
                safeMessage(error)
            );
            addPreflightNotice();
            showError(error);
            return;
        }
        MaterialCardView conflictCard = importCard();
        LinearLayout conflictBody = new LinearLayout(this);
        conflictBody.setOrientation(LinearLayout.VERTICAL);
        conflictBody.setPadding(dp(12), dp(11), dp(12), dp(11));
        conflictCard.addView(conflictBody);
        preflightContainer.addView(conflictCard);

        TextView conflictHeading = new TextView(this);
        conflictHeading.setText(R.string.management_import_conflicts_heading);
        conflictHeading.setTextAppearance(
            this,
            R.style.TextAppearance_HET_ManagementImport_Title
        );
        conflictHeading.setTextColor(
            ContextCompat.getColor(this, R.color.het_on_surface)
        );
        LinearLayout.LayoutParams conflictHeadingParams =
            new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            );
        conflictHeadingParams.bottomMargin = dp(4);
        conflictBody.addView(conflictHeading, conflictHeadingParams);
        if (!conflicts.isEmpty()) {
            addConflictDefaultControl(conflictBody);
        }
        if (conflicts.isEmpty()) {
            TextView empty = new TextView(this);
            empty.setTextAppearance(
                this,
                R.style.TextAppearance_HET_ManagementImport_Body
            );
            empty.setTextColor(
                ContextCompat.getColor(this, R.color.het_on_surface_muted)
            );
            empty.setText(R.string.management_import_no_conflicts);
            conflictBody.addView(empty);
        } else {
            LayoutInflater inflater = LayoutInflater.from(this);
            for (ManagementImportModel.Conflict conflict : conflicts) {
                View row = inflater.inflate(
                    R.layout.item_management_import_conflict,
                    conflictBody,
                    false
                );
                TextView title = row.findViewById(
                    R.id.tv_management_import_conflict_title
                );
                title.setText(
                    typeLabel(conflict.incoming.kind) + " · "
                        + conflict.incoming.canonicalId
                );
                TextView detail = row.findViewById(
                    R.id.tv_management_import_conflict_detail
                );
                String existing = conflict.existing == null
                    ? getString(R.string.management_import_unknown)
                    : pretty(conflict.existing);
                detail.setText(getString(
                    R.string.management_import_conflict_existing,
                    conflict.incoming.sourceName + " · " + existing
                ));
                MaterialButton selector = row.findViewById(
                    R.id.btn_management_import_conflict_action
                );
                ManagementImportModel.ConflictAction explicitAction =
                    conflictActions.get(conflict.key);
                selector.setText(explicitAction == null
                    ? getString(
                        R.string.management_import_action_default,
                        conflictActionLabel(defaultConflictAction)
                    )
                    : conflictActionLabel(explicitAction));
                conflictSelectors.put(conflict.key, selector);
                selector.setOnClickListener(view ->
                    showConflictMenu(conflict, selector)
                );
                selector.setEnabled(!busy);
                conflictBody.addView(row);
            }
        }
        addTaskActionControl(conflicts);
        generatePreviewButton.setEnabled(
            !busy && snapshotReady && !snapshotLoading
        );
        applyButton.setEnabled(false);
        if (stylePreview) {
            statusView.setVisibility(View.VISIBLE);
            statusView.setText(R.string.style_preview_read_only_body);
        }
    }

    private void addPreflightNotice() {
        if (preflightContainer == null
            || preflightNotice == null
            || preflightNotice.trim().isEmpty()) {
            return;
        }
        TextView notice = new TextView(this);
        notice.setTextAppearance(
            this,
            R.style.TextAppearance_HET_ManagementImport_Body
        );
        notice.setTextColor(
            ContextCompat.getColor(this, R.color.het_on_surface)
        );
        notice.setText(preflightNotice);
        notice.setBackgroundResource(R.drawable.bg_management_import_file_error);
        notice.setPadding(dp(12), dp(10), dp(12), dp(10));
        preflightContainer.addView(notice, 0, cardParams());
    }

    /** Shows a compact retry entry without rebuilding the large preview model. */
    private void renderPendingSubmission() {
        phase = Phase.PREVIEW;
        setPageHeader(
            R.string.management_import_pending_eyebrow,
            R.string.management_import_pending_title,
            R.string.management_import_pending_description
        );
        pageDescription.setVisibility(View.GONE);
        sourceCard.setVisibility(View.GONE);
        fileActions.setVisibility(View.GONE);
        fileScroll.setVisibility(View.GONE);
        statusView.setVisibility(View.VISIBLE);
        confirmContainer.setVisibility(View.GONE);
        preflightScroll.setVisibility(View.GONE);
        previewActions.setVisibility(View.VISIBLE);
        generatePreviewButton.setVisibility(View.GONE);
        applyButton.setVisibility(View.VISIBLE);
        previewScroll.setVisibility(View.VISIBLE);
        previewFilesContainer.removeAllViews();
        previewView.setText(R.string.management_import_pending_submission);
        statusView.setText(R.string.management_import_pending_submission);
        applyButton.setEnabled(!busy);
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
            R.style.TextAppearance_HET_ManagementImport_Body
        ));
    }

    private void addConflictDefaultControl(LinearLayout parent) {
        LinearLayout body = new LinearLayout(this);
        body.setOrientation(LinearLayout.VERTICAL);
        body.setPadding(0, dp(6), 0, 0);
        TextView heading = new TextView(this);
        heading.setText(R.string.management_import_default_action);
        heading.setTextAppearance(
            this,
            R.style.TextAppearance_HET_ManagementImport_Title
        );
        heading.setTextColor(
            ContextCompat.getColor(this, R.color.het_on_surface)
        );
        body.addView(heading);
        TextView hint = new TextView(this);
        hint.setText(R.string.management_import_default_action_hint);
        hint.setTextAppearance(
            this,
            R.style.TextAppearance_HET_ManagementImport_Meta
        );
        hint.setTextColor(
            ContextCompat.getColor(this, R.color.het_on_surface)
        );
        body.addView(hint, new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        ));
        LinearLayout actions = new LinearLayout(this);
        actions.setGravity(android.view.Gravity.CENTER_VERTICAL);
        actions.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams actionsParams = new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        );
        actionsParams.topMargin = dp(7);
        body.addView(actions, actionsParams);
        addDefaultActionButton(
            actions,
            R.string.management_import_bulk_overwrite,
            ManagementImportModel.ConflictAction.OVERWRITE,
            0
        );
        addDefaultActionButton(
            actions,
            R.string.management_import_bulk_copy,
            ManagementImportModel.ConflictAction.COPY,
            dp(6)
        );
        addDefaultActionButton(
            actions,
            R.string.management_import_bulk_skip,
            ManagementImportModel.ConflictAction.SKIP,
            dp(6)
        );
        parent.addView(body);
    }

    private void addDefaultActionButton(
        LinearLayout parent,
        int textId,
        ManagementImportModel.ConflictAction action,
        int marginStart
    ) {
        MaterialButton button = styledButton(
            action == defaultConflictAction
                ? R.style.Widget_HET_Button_Primary
                : R.style.Widget_HET_Button_Secondary
        );
        button.setText(textId);
        button.setTextSize(12);
        button.setOnClickListener(view -> chooseDefaultConflictAction(action));
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
            0,
            ViewGroup.LayoutParams.WRAP_CONTENT,
            1f
        );
        params.leftMargin = marginStart;
        parent.addView(button, params);
    }

    private void chooseDefaultConflictAction(
        ManagementImportModel.ConflictAction action
    ) {
        if (busy || action == defaultConflictAction) return;
        defaultConflictAction = action;
        preflightNotice = null;
        ++sessionGeneration;
        prepared = null;
        phase = Phase.PREFLIGHT;
        applyButton.setEnabled(false);
        previewScroll.setVisibility(View.GONE);
        renderPreflight();
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
            R.style.TextAppearance_HET_ManagementImport_Title
        );
        heading.setTextColor(
            ContextCompat.getColor(this, R.color.het_on_surface)
        );
        body.addView(heading);
        List<String> requestIds = new ArrayList<>();
        for (ManagementImportModel.Conflict conflict : conflicts) {
            requestIds.addAll(conflict.activeRequestIds);
        }
        TextView taskSummary = new TextView(this);
        taskSummary.setTextAppearance(
            this,
            R.style.TextAppearance_HET_ManagementImport_Meta
        );
        if (requestIds.isEmpty()) {
            taskSummary.setText(getString(
                R.string.management_import_tasks_summary,
                0
            ));
        } else {
            taskSummary.setText(getString(
                R.string.management_import_tasks_request_ids,
                android.text.TextUtils.join("\n", requestIds)
            ));
        }
        body.addView(taskSummary, new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        ));
        if (taskCount == 0) {
            card.addView(body);
            preflightContainer.addView(card);
            return;
        }
        MaterialButton taskButton = styledButton(
            R.style.Widget_HET_Button_Secondary
        );
        updateTaskButton(taskButton, taskCount);
        final int displayedTaskCount = taskCount;
        taskButton.setOnClickListener(view -> {
            if (busy) return;
            taskAction = taskAction == ManagementImportModel.TaskAction.KEEP
                ? ManagementImportModel.TaskAction.CANCEL
                : ManagementImportModel.TaskAction.KEEP;
            preflightNotice = null;
            updateTaskButton(taskButton, displayedTaskCount);
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
        params.bottomMargin = dp(10);
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
            || busy || !snapshotReady || snapshotLoading
            || batch == null || phase != Phase.PREFLIGHT) return;
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
                postUiIfAlive(() -> {
                    if (generation != sessionGeneration) {
                        setBusy(false);
                        return;
                    }
                    prepared = result;
                    phase = Phase.PREVIEW;
                    setBusy(false);
                    setPageHeader(
                        R.string.management_import_preview_eyebrow,
                        R.string.management_import_preview_title,
                        R.string.management_import_preview_description
                    );
                    pageDescription.setVisibility(View.GONE);
                    preflightScroll.setVisibility(View.GONE);
                    generatePreviewButton.setVisibility(View.GONE);
                    applyButton.setVisibility(stylePreview ? View.GONE : View.VISIBLE);
                    renderImportPreview(result);
                    previewScroll.setVisibility(View.VISIBLE);
                    applyButton.setEnabled(!stylePreview);
                    if (stylePreview) {
                        statusView.setVisibility(View.VISIBLE);
                        statusView.setText(R.string.style_preview_read_only_body);
                    }
                });
            } catch (Exception error) {
                postUiIfAlive(() -> {
                    if (generation != sessionGeneration) {
                        setBusy(false);
                        return;
                    }
                    setBusy(false);
                    preflightNotice = getString(
                        R.string.management_import_failed,
                        safeMessage(error)
                    );
                    statusView.setVisibility(View.VISIBLE);
                    statusView.setText(preflightNotice);
                    showError(error);
                });
            }
        });
    }

    /** Renders each selected source independently while preserving the full plan. */
    private void renderImportPreview(
        ManagementImportModel.PreparedImport result
    ) {
        previewView.setText(getString(
            R.string.management_import_preview_summary,
            result.batch.documents.size(),
            result.operations.size(),
            result.conflicts.size()
        ));
        previewFilesContainer.removeAllViews();
        for (ManagementImportModel.Document document : result.batch.documents) {
            JSONArray operations = new JSONArray();
            for (ManagementImportModel.Operation operation : result.operations) {
                if (!document.records.contains(operation.incoming)) {
                    continue;
                }
                try {
                    operations.put(new JSONObject()
                        .put("key", operation.incoming.key())
                        .put("action", operation.action)
                        .put("target_id", operation.targetId)
                        .put("content", new JSONObject(
                            operation.targetDocument.toString()
                        )));
                } catch (Exception error) {
                    try {
                        JSONObject fallback = new JSONObject();
                        fallback.put("key", operation.incoming.key());
                        fallback.put("action", operation.action);
                        fallback.put("target_id", operation.targetId);
                        operations.put(fallback);
                    } catch (Exception ignored) {
                        // The full operation remains in PreparedImport.toJson;
                        // this card only loses its compact display entry.
                    }
                }
            }
            addPreviewFileCard(
                document.sourceName,
                typeLabel(document.type),
                operations.length(),
                pretty(document.root)
            );
        }
    }

    private void addPreviewFileCard(
        String sourceName,
        String type,
        int operationCount,
        String value
    ) {
        MaterialCardView card = importCard();
        LinearLayout body = new LinearLayout(this);
        body.setOrientation(LinearLayout.VERTICAL);
        body.setPadding(dp(12), dp(11), dp(12), dp(11));

        TextView title = new TextView(this);
        title.setTextAppearance(
            this,
            R.style.TextAppearance_HET_ManagementImport_Title
        );
        title.setText(sourceName);
        title.setEllipsize(android.text.TextUtils.TruncateAt.END);
        title.setMaxLines(2);
        body.addView(title);

        TextView meta = new TextView(this);
        meta.setTextAppearance(
            this,
            R.style.TextAppearance_HET_ManagementImport_Meta
        );
        meta.setText(getString(
            R.string.management_import_preview_file_meta,
            type,
            operationCount
        ));
        body.addView(meta, new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        ));

        DraggableScrollbarNestedScrollView codeScroll =
            new DraggableScrollbarNestedScrollView(this);
        codeScroll.setFillViewport(false);
        codeScroll.setNestedScrollingEnabled(true);
        codeScroll.setPaddingRelative(0, 0, dp(14), 0);
        codeScroll.setBackgroundResource(R.drawable.bg_management_import_preview);
        TextView code = new TextView(this);
        code.setTextAppearance(
            this,
            R.style.TextAppearance_HET_ManagementImport_Preview
        );
        code.setText(value);
        code.setTextIsSelectable(true);
        code.setPadding(dp(11), dp(10), dp(11), dp(10));
        codeScroll.addView(code, new ViewGroup.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        ));
        LinearLayout.LayoutParams codeParams = new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            dp(240)
        );
        codeParams.topMargin = dp(7);
        body.addView(codeScroll, codeParams);

        card.addView(body);
        previewFilesContainer.addView(card);
    }

    private void confirmApply() {
        if (stylePreview || busy) return;
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
                    statusView.setVisibility(View.VISIBLE);
                    statusView.setText(getString(
                        R.string.management_import_failed,
                        safeMessage(error)
                    ));
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
                String staleMessage = result.optString("message", "");
                preflightNotice = staleMessage.trim().isEmpty()
                    ? getString(R.string.management_import_preview_stale)
                    : staleMessage;
                loadSnapshotFromService();
                if (batch != null) {
                    phase = Phase.PREFLIGHT;
                    renderPreflight();
                } else {
                    resetToFiles();
                }
            } else {
                String rejectionMessage = result.optString(
                    "message",
                    "导入未应用"
                );
                if (rejectionMessage.trim().isEmpty()) {
                    rejectionMessage = "导入未应用";
                }
                preflightNotice = rejectionMessage;
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
        if (stylePreview) {
            if (phase == Phase.PREVIEW) {
                phase = Phase.PREFLIGHT;
                prepared = null;
                renderPreflight();
                statusView.setVisibility(View.VISIBLE);
                statusView.setText(R.string.style_preview_read_only_body);
                return;
            }
            finish();
            return;
        }
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
            renderPreflight();
            return;
        }
        if (phase == Phase.PREFLIGHT) {
            phase = Phase.FILES;
            batch = null;
            prepared = null;
            conflictActions.clear();
            conflictSelectors.clear();
            taskActionButton = null;
            fileActions.setVisibility(View.VISIBLE);
            fileScroll.setVisibility(View.VISIBLE);
            confirmContainer.setVisibility(View.VISIBLE);
            preflightScroll.setVisibility(View.GONE);
            previewActions.setVisibility(View.GONE);
            previewScroll.setVisibility(View.GONE);
            renderFiles();
            if (!snapshotReady && !snapshotLoading
                && !pendingSubmissionRestored
                && !pendingRecoveryUnavailable) {
                loadSnapshotFromService();
            }
            return;
        }
        finish();
    }

    private void setBusy(boolean value) {
        busy = value;
        if (phase == Phase.FILES) {
            updateFileActions();
        }
        for (MaterialButton selector : conflictSelectors.values()) {
            selector.setEnabled(!value);
        }
        if (taskActionButton != null) {
            taskActionButton.setEnabled(!value);
        }
        if (generatePreviewButton != null) {
            generatePreviewButton.setEnabled(
                !value
                    && phase == Phase.PREFLIGHT
                    && snapshotReady
                    && !snapshotLoading
            );
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
        conflictSelectors.clear();
        taskActionButton = null;
        fileActions.setVisibility(View.VISIBLE);
        fileScroll.setVisibility(View.VISIBLE);
        confirmContainer.setVisibility(View.VISIBLE);
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

    private void showConflictMenu(
        ManagementImportModel.Conflict conflict,
        MaterialButton selector
    ) {
        StyledPopupMenu menu = new StyledPopupMenu(this, selector);
        menu.getMenu().add(
            0,
            0,
            0,
            getString(
                R.string.management_import_action_default,
                conflictActionLabel(defaultConflictAction)
            )
        );
        menu.getMenu().add(
            0,
            1,
            1,
            R.string.management_import_action_overwrite
        );
        menu.getMenu().add(
            0,
            2,
            2,
            R.string.management_import_action_copy
        );
        menu.getMenu().add(
            0,
            3,
            3,
            R.string.management_import_action_skip
        );
        menu.setOnMenuItemClickListener(item -> {
            if (busy) return true;
            ManagementImportModel.ConflictAction previous =
                conflictActions.get(conflict.key);
            ManagementImportModel.ConflictAction next = null;
            if (item.getItemId() != 0) {
                next = actionAt(item.getItemId() - 1);
            }
            if (next == previous) return true;
            preflightNotice = null;
            if (next == null) {
                conflictActions.remove(conflict.key);
                selector.setText(getString(
                    R.string.management_import_action_default,
                    conflictActionLabel(defaultConflictAction)
                ));
            } else {
                conflictActions.put(conflict.key, next);
                selector.setText(conflictActionLabel(next));
            }
            ++sessionGeneration;
            prepared = null;
            phase = Phase.PREFLIGHT;
            applyButton.setEnabled(false);
            previewScroll.setVisibility(View.GONE);
            generatePreviewButton.setVisibility(View.VISIBLE);
            generatePreviewButton.setEnabled(!busy);
            return true;
        });
        menu.show();
    }

    private ManagementImportModel.ConflictAction actionAt(int index) {
        if (index == 1) return ManagementImportModel.ConflictAction.COPY;
        if (index == 2) return ManagementImportModel.ConflictAction.SKIP;
        return ManagementImportModel.ConflictAction.OVERWRITE;
    }

    private void setPageHeader(
        int eyebrowId,
        int headingId,
        int descriptionId
    ) {
        if (pageEyebrow != null) {
            pageEyebrow.setText(eyebrowId);
        }
        if (pageHeading != null) {
            pageHeading.setText(headingId);
        }
        if (pageDescription != null) {
            pageDescription.setText(descriptionId);
        }
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
