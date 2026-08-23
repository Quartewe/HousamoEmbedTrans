package com.quarty.housamoembedtrans.ui;

import com.quarty.housamoembedtrans.R;
import com.quarty.housamoembedtrans.runtime.SceneSyncRuntimeState;
import com.quarty.housamoembedtrans.runtime.SceneSyncUiVisibility;
import com.quarty.housamoembedtrans.runtime.TranslationStatusNotification;
import com.quarty.housamoembedtrans.storage.SceneStore;

import android.content.ContentResolver;
import android.content.Intent;
import android.database.Cursor;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Bundle;
import android.provider.DocumentsContract;
import android.provider.OpenableColumns;
import android.text.TextUtils;
import android.util.Log;
import android.view.View;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AppCompatActivity;

import com.google.android.material.appbar.MaterialToolbar;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** UI for schema-valid scene files and process-local Scene Sync state. */
public final class SceneFilesActivity extends AppCompatActivity {
    private static final String TAG = "SceneFilesActivity";

    private final SceneSyncRuntimeState runtimeState =
        SceneSyncRuntimeState.getInstance();
    private final SceneSyncUiVisibility.ActivityFlag visibilityFlag =
        SceneSyncUiVisibility.newSceneFilesFlag();
    private final List<SceneStore.SceneInfo> scenes = new ArrayList<>();
    private final List<String> sceneFileLabels = new ArrayList<>();
    private final List<String> sceneLanguages = new ArrayList<>();
    private final ExecutorService ioExecutor =
        Executors.newSingleThreadExecutor();

    private SceneStore sceneStore;
    private TextView summary;
    private TextView lastResult;
    private TextView runtimeStatus;
    private TextView refreshScope;
    private TextView refreshResult;
    private TextView lastSyncSummary;
    private MaterialButton importButton;
    private MaterialButton exportButton;
    private MaterialButton deleteLanguageButton;
    private MaterialButton deleteFileButton;
    private MaterialButton refreshButton;
    private MaterialButton conflictsButton;
    private Typeface conflictsButtonTypeface;
    private Spinner sceneFileSpinner;
    private Spinner sceneLanguageSpinner;
    private ArrayAdapter<String> sceneFileAdapter;
    private ArrayAdapter<String> sceneLanguageAdapter;
    private ActivityResultLauncher<String[]> importLauncher;
    private ActivityResultLauncher<Uri> exportLauncher;
    private SceneSyncRuntimeState.Snapshot runtimeSnapshot =
        runtimeState.getSnapshot();
    private SceneSyncRuntimeState.Listener runtimeListener;
    private boolean busy = true;
    private boolean refreshRequestPending;
    private boolean started;
    private int lifecycleGeneration;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_scene_files);
        SystemBarInsets.apply(findViewById(R.id.root_scene_files));
        MaterialToolbar toolbar = findViewById(R.id.toolbar_scene_files);
        toolbar.setNavigationOnClickListener(
            view -> getOnBackPressedDispatcher().onBackPressed()
        );

        sceneStore = new SceneStore(this);
        summary = findViewById(R.id.tv_scene_summary);
        lastResult = findViewById(R.id.tv_scene_last_result);
        runtimeStatus = findViewById(R.id.tv_scene_sync_runtime_status);
        refreshScope = findViewById(R.id.tv_scene_sync_refresh_scope);
        refreshResult = findViewById(R.id.tv_scene_sync_refresh_result);
        lastSyncSummary = findViewById(R.id.tv_scene_sync_last_summary);
        importButton = findViewById(R.id.btn_import_scenes);
        exportButton = findViewById(R.id.btn_export_scenes);
        deleteLanguageButton = findViewById(R.id.btn_delete_scene_language);
        deleteFileButton = findViewById(R.id.btn_delete_scene_file);
        refreshButton = findViewById(R.id.btn_refresh_scene_sync);
        conflictsButton = findViewById(R.id.btn_scene_conflicts);
        conflictsButtonTypeface = conflictsButton.getTypeface();
        sceneFileSpinner = findViewById(R.id.spinner_scene_file);
        sceneLanguageSpinner = findViewById(R.id.spinner_scene_language);

        sceneFileAdapter = new ArrayAdapter<>(
            this,
            android.R.layout.simple_spinner_item,
            sceneFileLabels
        );
        sceneFileAdapter.setDropDownViewResource(
            android.R.layout.simple_spinner_dropdown_item
        );
        sceneFileSpinner.setAdapter(sceneFileAdapter);

        sceneLanguageAdapter = new ArrayAdapter<>(
            this,
            android.R.layout.simple_spinner_item,
            sceneLanguages
        );
        sceneLanguageAdapter.setDropDownViewResource(
            android.R.layout.simple_spinner_dropdown_item
        );
        sceneLanguageSpinner.setAdapter(sceneLanguageAdapter);

        importLauncher = registerForActivityResult(
            new ActivityResultContracts.OpenMultipleDocuments(),
            this::importScenes
        );
        exportLauncher = registerForActivityResult(
            new ActivityResultContracts.OpenDocumentTree(),
            this::exportScenes
        );

        importButton.setOnClickListener(view -> importLauncher.launch(new String[] {
            "application/json",
            "text/json",
            "text/plain",
            "application/octet-stream"
        }));
        exportButton.setOnClickListener(view -> exportLauncher.launch(null));
        deleteLanguageButton.setOnClickListener(
            view -> confirmDeleteLanguage()
        );
        deleteFileButton.setOnClickListener(view -> confirmDeleteFile());
        refreshButton.setOnClickListener(view -> requestSceneRefresh());
        conflictsButton.setOnClickListener(view -> startActivity(
            new Intent(this, SceneConflictsActivity.class)
        ));
        sceneFileSpinner.setOnItemSelectedListener(
            new AdapterView.OnItemSelectedListener() {
                @Override
                public void onItemSelected(
                    AdapterView<?> parent,
                    View view,
                    int position,
                    long id
                ) {
                    updateLanguageChoices(null);
                }

                @Override
                public void onNothingSelected(AdapterView<?> parent) {
                    updateLanguageChoices(null);
                }
            }
        );
        sceneLanguageSpinner.setOnItemSelectedListener(
            new AdapterView.OnItemSelectedListener() {
                @Override
                public void onItemSelected(
                    AdapterView<?> parent,
                    View view,
                    int position,
                    long id
                ) {
                    updateActionState();
                }

                @Override
                public void onNothingSelected(AdapterView<?> parent) {
                    updateActionState();
                }
            }
        );
        renderRuntimeSnapshot(runtimeSnapshot);
        updateActionState();
        refreshScenesAsync();
    }

    @Override
    protected void onStart() {
        super.onStart();
        started = true;
        int generation = ++lifecycleGeneration;
        runtimeListener = changed -> runOnUiThread(() -> {
            if (!started
                || lifecycleGeneration != generation
                || isFinishing()
                || isDestroyed()) {
                return;
            }
            acceptRuntimeSnapshot(changed);
        });
        visibilityFlag.setVisible(true);
        runtimeState.addListener(runtimeListener);
    }

    @Override
    protected void onStop() {
        started = false;
        lifecycleGeneration++;
        SceneSyncRuntimeState.Listener listener = runtimeListener;
        runtimeListener = null;
        runtimeState.removeListener(listener);
        visibilityFlag.setVisible(false);
        TranslationStatusNotification.refresh(this);
        super.onStop();
    }

    private void acceptRuntimeSnapshot(
        SceneSyncRuntimeState.Snapshot changed
    ) {
        if (changed == null || isFinishing() || isDestroyed()) {
            return;
        }
        runtimeSnapshot = changed;
        renderRuntimeSnapshot(changed);
        updateActionState();
    }

    private void renderRuntimeSnapshot(
        SceneSyncRuntimeState.Snapshot snapshot
    ) {
        String service = getString(
            snapshot.serviceAvailable
                ? R.string.scene_sync_service_ready
                : R.string.scene_sync_service_unavailable
        );
        String game = getString(
            snapshot.gamePortAvailable
                ? R.string.scene_sync_game_online
                : R.string.scene_sync_game_offline
        );
        runtimeStatus.setText(getString(
            R.string.scene_files_runtime_status,
            service,
            game,
            phaseLabel(snapshot.phase),
            snapshot.activeApiJobs
        ));

        boolean localOnly = !snapshot.serviceAvailable
            || !snapshot.gamePortAvailable;
        refreshScope.setText(
            localOnly
                ? R.string.scene_files_refresh_scope_local
                : R.string.scene_files_refresh_scope_online
        );
        if (refreshRequestPending) {
            refreshResult.setText(R.string.scene_files_refreshing_local);
        } else {
            refreshResult.setText(getString(
                R.string.scene_files_last_refresh,
                refreshOutcomeLabel(snapshot)
            ));
        }

        int pendingConflicts = snapshot.pendingConflictCount;
        conflictsButton.setText(getString(
            R.string.scene_files_conflicts_button,
            pendingConflicts
        ));
        conflictsButton.setTypeface(
            conflictsButtonTypeface,
            pendingConflicts > 0 ? Typeface.BOLD : Typeface.NORMAL
        );
        renderSceneSummaries(snapshot.sceneSummaries);
    }

    private String phaseLabel(SceneSyncRuntimeState.Phase phase) {
        if (phase == null) {
            return getString(R.string.scene_sync_phase_idle);
        }
        switch (phase) {
            case FULL_SYNC:
                return getString(R.string.scene_sync_phase_full_sync);
            case MANUAL_REFRESH:
                return getString(R.string.scene_sync_phase_refresh);
            case MANUAL_APPLY:
                return getString(R.string.scene_sync_phase_manual_apply);
            case IDLE:
            default:
                return getString(R.string.scene_sync_phase_idle);
        }
    }

    private String refreshOutcomeLabel(
        SceneSyncRuntimeState.Snapshot snapshot
    ) {
        boolean localOnly = !snapshot.serviceAvailable
            || !snapshot.gamePortAvailable;
        if (localOnly
            && snapshot.lastAction
                == SceneSyncRuntimeState.Action.MANUAL_REFRESH) {
            if (snapshot.lastOutcome == SceneSyncRuntimeState.Outcome.FAILED) {
                return getString(R.string.scene_files_refresh_failed);
            }
            if (snapshot.lastOutcome
                == SceneSyncRuntimeState.Outcome.UNAVAILABLE) {
                return getString(R.string.scene_files_refresh_unavailable);
            }
            return getString(R.string.scene_files_refresh_local_only);
        }
        if (snapshot.lastAction == SceneSyncRuntimeState.Action.LOCAL_REFRESH) {
            if (snapshot.lastOutcome == SceneSyncRuntimeState.Outcome.STARTED) {
                return getString(R.string.scene_files_refreshing_local);
            }
            if (snapshot.lastOutcome == SceneSyncRuntimeState.Outcome.FAILED) {
                return getString(R.string.scene_files_refresh_failed);
            }
            return getString(R.string.scene_files_refresh_local_only);
        }
        if (snapshot.lastAction
            != SceneSyncRuntimeState.Action.MANUAL_REFRESH) {
            return getString(R.string.scene_files_refresh_ready);
        }

        switch (snapshot.lastOutcome) {
            case STARTED:
                return getString(R.string.scene_files_refresh_started);
            case BUSY:
                return getString(R.string.scene_files_refresh_busy);
            case DEFERRED:
                return getString(R.string.scene_files_refresh_deferred);
            case QUEUED_BEHIND_GATE:
                return getString(
                    R.string.scene_files_refresh_queued_behind_gate
                );
            case LOCAL_ONLY:
                return getString(R.string.scene_files_refresh_local_only);
            case SUCCEEDED:
                return getString(R.string.scene_files_refresh_succeeded);
            case NEEDS_ATTENTION:
                return getString(
                    R.string.scene_files_refresh_needs_attention
                );
            case FAILED:
                return getString(R.string.scene_files_refresh_failed);
            case UNAVAILABLE:
                return getString(R.string.scene_files_refresh_unavailable);
            case NONE:
            default:
                return getString(R.string.scene_files_refresh_ready);
        }
    }

    private void renderSceneSummaries(
        List<SceneSyncRuntimeState.SceneSummary> sceneSummaries
    ) {
        if (sceneSummaries.isEmpty()) {
            lastSyncSummary.setText(
                R.string.scene_files_last_summary_empty
            );
            return;
        }

        StringBuilder readable = new StringBuilder();
        for (SceneSyncRuntimeState.SceneSummary scene : sceneSummaries) {
            if (readable.length() > 0) {
                readable.append('\n');
            }
            readable.append(getString(
                R.string.scene_files_sync_summary_item,
                scene.sceneName,
                directionLabel(scene.direction),
                statusLabel(scene.status)
            ));
        }
        lastSyncSummary.setText(readable.toString());
    }

    private String directionLabel(
        SceneSyncRuntimeState.Direction direction
    ) {
        switch (direction) {
            case GAME_TO_HET:
                return getString(
                    R.string.scene_files_direction_game_to_het
                );
            case HET_TO_GAME:
                return getString(
                    R.string.scene_files_direction_het_to_game
                );
            case BIDIRECTIONAL:
                return getString(
                    R.string.scene_files_direction_bidirectional
                );
            case LOCAL:
                return getString(R.string.scene_files_direction_local);
            case UNKNOWN:
            default:
                return getString(R.string.scene_files_direction_unknown);
        }
    }

    private String statusLabel(SceneSyncRuntimeState.Status status) {
        switch (status) {
            case PROCESSED:
                return getString(R.string.scene_files_status_processed);
            case DELETED:
                return getString(R.string.scene_files_status_deleted);
            case NEEDS_ATTENTION:
                return getString(
                    R.string.scene_files_status_needs_attention
                );
            case NOT_PROCESSED:
            default:
                return getString(
                    R.string.scene_files_status_not_processed
                );
        }
    }

    private void importScenes(List<Uri> uris) {
        if (uris == null || uris.isEmpty()) {
            return;
        }

        setBusy(true);
        ioExecutor.execute(() -> {
            int imported = 0;
            int deferred = 0;
            int rejected = 0;
            StringBuilder details = new StringBuilder();
            for (Uri uri : uris) {
                try (InputStream input = getContentResolver().openInputStream(uri)) {
                    if (input == null) {
                        throw new IOException("document could not be opened");
                    }
                    SceneStore.MutationReceipt<SceneStore.ValidatedScene> receipt =
                        sceneStore.importScene(input);
                    if (receipt.disposition
                        == SceneStore.MutationDisposition.DEFERRED) {
                        deferred++;
                    } else {
                        imported++;
                    }
                } catch (Exception e) {
                    rejected++;
                    appendFailure(details, displayName(uri), safeMessage(e));
                }
            }

            int finalImported = imported;
            int finalDeferred = deferred;
            int finalRejected = rejected;
            List<SceneStore.SceneInfo> refreshedScenes = sceneStore.listSceneInfos();
            runOnUiThread(() -> {
                if (isFinishing() || isDestroyed()) {
                    return;
                }
                updateScenes(refreshedScenes, null, null);
                setBusy(false);
                String result = getString(
                    R.string.scene_import_result,
                    finalImported,
                    finalRejected
                );
                String deferredResult = finalDeferred == 0
                    ? ""
                    : getString(
                        R.string.scene_import_deferred_result,
                        finalDeferred
                    );
                String combinedDetails = TextUtils.isEmpty(details)
                    ? deferredResult
                    : TextUtils.isEmpty(deferredResult)
                        ? details.toString()
                        : deferredResult + "\n" + details;
                showResult(result, combinedDetails);
            });
        });
    }

    private void exportScenes(Uri treeUri) {
        if (treeUri == null) {
            return;
        }

        try {
            getContentResolver().takePersistableUriPermission(
                treeUri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION
                    | Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            );
        } catch (SecurityException ignored) {
            // The one-time grant remains valid for this export even when a
            // document provider does not offer persistable permissions.
        }

        setBusy(true);
        ioExecutor.execute(() -> {
            int exported = 0;
            int failed = 0;
            StringBuilder details = new StringBuilder();
            try {
                List<SceneStore.ValidatedScene> scenes = sceneStore.listValidScenes();
                Map<String, Uri> existing = listDocuments(treeUri);
                for (SceneStore.ValidatedScene scene : scenes) {
                    try {
                        String fileName = SceneStore.fileNameForScene(scene.sceneName);
                        Uri documentUri = existing.get(fileName);
                        if (documentUri == null) {
                            Uri parent = parentDocumentUri(treeUri);
                            documentUri = DocumentsContract.createDocument(
                                getContentResolver(),
                                parent,
                                "application/json",
                                fileName
                            );
                        }
                        if (documentUri == null) {
                            throw new IOException("document provider refused the file");
                        }

                        try (OutputStream output = getContentResolver()
                            .openOutputStream(documentUri, "wt")) {
                            if (output == null) {
                                throw new IOException("document could not be opened for writing");
                            }
                            output.write(scene.bytes);
                            output.flush();
                        }
                        exported++;
                    } catch (Exception e) {
                        failed++;
                        appendFailure(details, scene.sceneName, safeMessage(e));
                    }
                }
            } catch (Exception e) {
                failed++;
                appendFailure(details, getString(R.string.scene_export_folder), safeMessage(e));
            }

            int finalExported = exported;
            int finalFailed = failed;
            runOnUiThread(() -> {
                if (isFinishing() || isDestroyed()) {
                    return;
                }
                setBusy(false);
                showResult(getString(
                    R.string.scene_export_result,
                    finalExported,
                    finalFailed
                ), details.toString());
            });
        });
    }

    private Map<String, Uri> listDocuments(Uri treeUri) throws Exception {
        String parentId = DocumentsContract.getTreeDocumentId(treeUri);
        Uri childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(
            treeUri,
            parentId
        );
        String[] projection = {
            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_MIME_TYPE
        };
        Map<String, Uri> documents = new HashMap<>();

        try (Cursor cursor = getContentResolver().query(
            childrenUri,
            projection,
            null,
            null,
            null
        )) {
            if (cursor == null) {
                throw new IOException("document provider returned no directory listing");
            }
            while (cursor.moveToNext()) {
                String documentId = cursor.getString(0);
                String name = cursor.getString(1);
                String mimeType = cursor.getString(2);
                if (!DocumentsContract.Document.MIME_TYPE_DIR.equals(mimeType)) {
                    documents.put(
                        name,
                        DocumentsContract.buildDocumentUriUsingTree(treeUri, documentId)
                    );
                }
            }
        }
        return documents;
    }

    private static Uri parentDocumentUri(Uri treeUri) {
        return DocumentsContract.buildDocumentUriUsingTree(
            treeUri,
            DocumentsContract.getTreeDocumentId(treeUri)
        );
    }

    private void refreshScenesAsync() {
        setBusy(true);
        ioExecutor.execute(() -> {
            try {
                List<SceneStore.SceneInfo> refreshedScenes =
                    sceneStore.listSceneInfos();
                runOnUiThread(() -> {
                    if (!isFinishing() && !isDestroyed()) {
                        updateScenes(refreshedScenes, null, null);
                        setBusy(false);
                    }
                });
            } catch (RuntimeException e) {
                Log.e(TAG, "Could not load local Scene list", e);
                runOnUiThread(() -> {
                    if (isFinishing() || isDestroyed()) {
                        return;
                    }
                    setBusy(false);
                    showResult(
                        getString(R.string.scene_files_local_refresh_failed),
                        ""
                    );
                });
            }
        });
    }

    private void requestSceneRefresh() {
        if (!canRequestSceneRefresh()) {
            return;
        }

        SceneStore.SceneInfo selected = selectedScene();
        String preferredSceneName = selected == null
            ? null
            : selected.sceneName;
        String preferredLanguage = selectedLanguage();
        refreshRequestPending = true;
        renderRuntimeSnapshot(runtimeSnapshot);
        updateActionState();

        ioExecutor.execute(() -> {
            final List<SceneStore.SceneInfo> refreshedScenes;
            try {
                refreshedScenes = sceneStore.listSceneInfos();
            } catch (RuntimeException e) {
                Log.e(
                    TAG,
                    "Could not reload local Scenes before manual refresh",
                    e
                );
                runOnUiThread(() -> {
                    if (isFinishing() || isDestroyed()) {
                        return;
                    }
                    refreshRequestPending = false;
                    renderRuntimeSnapshot(runtimeSnapshot);
                    refreshResult.setText(getString(
                        R.string.scene_files_last_refresh,
                        getString(
                            R.string.scene_files_local_refresh_failed
                        )
                    ));
                    updateActionState();
                    Toast.makeText(
                        this,
                        R.string.scene_files_local_refresh_failed,
                        Toast.LENGTH_LONG
                    ).show();
                });
                return;
            }

            runOnUiThread(() -> {
                if (isFinishing() || isDestroyed()) {
                    return;
                }
                updateScenes(
                    refreshedScenes,
                    preferredSceneName,
                    preferredLanguage
                );

                boolean requestFailed = false;
                try {
                    runtimeState.requestRefresh();
                } catch (RuntimeException e) {
                    requestFailed = true;
                    Log.e(TAG, "Could not request Scene Sync refresh", e);
                }

                refreshRequestPending = false;
                runtimeSnapshot = runtimeState.getSnapshot();
                renderRuntimeSnapshot(runtimeSnapshot);
                updateActionState();
                if (requestFailed) {
                    refreshResult.setText(getString(
                        R.string.scene_files_last_refresh,
                        getString(R.string.scene_files_refresh_failed)
                    ));
                    Toast.makeText(
                        this,
                        R.string.scene_files_refresh_failed,
                        Toast.LENGTH_LONG
                    ).show();
                }
            });
        });
    }

    private void updateScenes(
        List<SceneStore.SceneInfo> refreshedScenes,
        String preferredSceneName,
        String preferredLanguage
    ) {
        SceneStore.SceneInfo previousScene = selectedScene();
        String wantedSceneName = preferredSceneName;
        if (wantedSceneName == null && previousScene != null) {
            wantedSceneName = previousScene.sceneName;
        }

        String wantedLanguage = preferredLanguage;
        if (wantedLanguage == null && previousScene != null
            && previousScene.sceneName.equals(wantedSceneName)) {
            wantedLanguage = selectedLanguage();
        }

        scenes.clear();
        scenes.addAll(refreshedScenes);
        sceneFileLabels.clear();
        if (scenes.isEmpty()) {
            sceneFileLabels.add(getString(R.string.scene_files_empty));
        } else {
            for (SceneStore.SceneInfo scene : scenes) {
                sceneFileLabels.add(scene.sceneName);
            }
        }
        sceneFileAdapter.notifyDataSetChanged();

        int selectedIndex = indexOfScene(wantedSceneName);
        sceneFileSpinner.setSelection(selectedIndex < 0 ? 0 : selectedIndex, false);
        int pendingMutations = sceneStore.getDeferredMutationCount();
        String pendingMutationDiagnostic =
            sceneStore.getDeferredMutationDiagnostic();
        boolean hasPendingMutationNotice = pendingMutations > 0
            || !pendingMutationDiagnostic.trim().isEmpty();
        String sceneSummary = getString(
            R.string.scene_files_summary,
            scenes.size()
        );
        if (hasPendingMutationNotice) {
            sceneSummary += "\n" + getString(
                pendingMutations > 0
                    ? R.string.scene_files_mutation_pool_pending
                    : R.string.scene_files_mutation_pool_failure,
                pendingMutations,
                pendingMutationDiagnostic
            );
        }
        summary.setText(sceneSummary);
        updateLanguageChoices(wantedLanguage);
    }

    private void setBusy(boolean busy) {
        this.busy = busy;
        updateActionState();
    }

    private void updateLanguageChoices(String preferredLanguage) {
        sceneLanguages.clear();
        SceneStore.SceneInfo scene = selectedScene();
        if (scene == null || scene.languages.isEmpty()) {
            sceneLanguages.add(getString(R.string.scene_languages_empty));
        } else {
            sceneLanguages.addAll(scene.languages);
        }
        sceneLanguageAdapter.notifyDataSetChanged();

        int selectedIndex = sceneLanguages.indexOf(preferredLanguage);
        sceneLanguageSpinner.setSelection(selectedIndex < 0 ? 0 : selectedIndex, false);
        updateActionState();
    }

    private boolean canRequestSceneRefresh() {
        return !busy
            && !refreshRequestPending
            && runtimeSnapshot.phase == SceneSyncRuntimeState.Phase.IDLE
            && runtimeSnapshot.activeApiJobs == 0;
    }

    private void updateActionState() {
        boolean hasScene = selectedScene() != null;
        boolean hasLanguage = selectedLanguage() != null;
        boolean localUiBusy = busy || refreshRequestPending;
        importButton.setEnabled(!localUiBusy);
        exportButton.setEnabled(!localUiBusy && !scenes.isEmpty());
        sceneFileSpinner.setEnabled(!localUiBusy && hasScene);
        sceneLanguageSpinner.setEnabled(!localUiBusy && hasLanguage);
        deleteLanguageButton.setEnabled(!localUiBusy && hasLanguage);
        deleteFileButton.setEnabled(!localUiBusy && hasScene);
        refreshButton.setEnabled(canRequestSceneRefresh());
        conflictsButton.setEnabled(true);
    }

    private SceneStore.SceneInfo selectedScene() {
        int position = sceneFileSpinner.getSelectedItemPosition();
        return position >= 0 && position < scenes.size()
            ? scenes.get(position)
            : null;
    }

    private String selectedLanguage() {
        SceneStore.SceneInfo scene = selectedScene();
        int position = sceneLanguageSpinner.getSelectedItemPosition();
        return scene != null
            && !scene.languages.isEmpty()
            && position >= 0
            && position < sceneLanguages.size()
            ? sceneLanguages.get(position)
            : null;
    }

    private int indexOfScene(String sceneName) {
        if (sceneName == null) {
            return -1;
        }
        for (int index = 0; index < scenes.size(); index++) {
            if (sceneName.equals(scenes.get(index).sceneName)) {
                return index;
            }
        }
        return -1;
    }

    private void confirmDeleteLanguage() {
        SceneStore.SceneInfo scene = selectedScene();
        String language = selectedLanguage();
        if (scene == null || language == null) {
            return;
        }

        new MaterialAlertDialogBuilder(this)
            .setTitle(R.string.delete_scene_language_title)
            .setMessage(getString(
                R.string.delete_scene_language_message,
                language,
                scene.sceneName
            ))
            .setNegativeButton(R.string.cancel_action, null)
            .setPositiveButton(
                R.string.delete_scene_language,
                (dialog, which) -> deleteLanguage(scene.sceneName, language)
            )
            .show();
    }

    private void deleteLanguage(String sceneName, String language) {
        setBusy(true);
        ioExecutor.execute(() -> {
            try {
                SceneStore.MutationReceipt<SceneStore.ValidatedScene> receipt =
                    sceneStore.removeLanguage(sceneName, language);
                List<SceneStore.SceneInfo> refreshedScenes = sceneStore.listSceneInfos();
                runOnUiThread(() -> {
                    if (isFinishing() || isDestroyed()) {
                        return;
                    }
                    updateScenes(refreshedScenes, sceneName, null);
                    setBusy(false);
                    String message = receipt.disposition
                        == SceneStore.MutationDisposition.DEFERRED
                        ? getString(
                            R.string.scene_mutation_deferred,
                            sceneName
                        )
                        : getString(
                            R.string.scene_language_deleted,
                            language,
                            sceneName
                        );
                    showResult(message, "");
                });
            } catch (Exception e) {
                showOperationFailure(safeMessage(e));
            }
        });
    }

    private void confirmDeleteFile() {
        SceneStore.SceneInfo scene = selectedScene();
        if (scene == null) {
            return;
        }

        String preferredSceneName = neighboringSceneName(
            sceneFileSpinner.getSelectedItemPosition()
        );
        new MaterialAlertDialogBuilder(this)
            .setTitle(R.string.delete_scene_file_title)
            .setMessage(getString(
                R.string.delete_scene_file_message,
                scene.sceneName
            ))
            .setNegativeButton(R.string.cancel_action, null)
            .setPositiveButton(
                R.string.delete_scene_file,
                (dialog, which) -> deleteFile(
                    scene.sceneName,
                    preferredSceneName
                )
            )
            .show();
    }

    private String neighboringSceneName(int selectedIndex) {
        if (scenes.size() <= 1 || selectedIndex < 0) {
            return null;
        }
        int neighborIndex = selectedIndex + 1 < scenes.size()
            ? selectedIndex + 1
            : selectedIndex - 1;
        return scenes.get(neighborIndex).sceneName;
    }

    private void deleteFile(String sceneName, String preferredSceneName) {
        setBusy(true);
        ioExecutor.execute(() -> {
            try {
                SceneStore.MutationReceipt<Void> receipt =
                    sceneStore.deleteScene(sceneName);
                List<SceneStore.SceneInfo> refreshedScenes = sceneStore.listSceneInfos();
                runOnUiThread(() -> {
                    if (isFinishing() || isDestroyed()) {
                        return;
                    }
                    updateScenes(refreshedScenes, preferredSceneName, null);
                    setBusy(false);
                    String message = receipt.disposition
                        == SceneStore.MutationDisposition.DEFERRED
                        ? getString(
                            R.string.scene_mutation_deferred,
                            sceneName
                        )
                        : getString(
                            R.string.scene_file_deleted,
                            sceneName
                        );
                    showResult(message, "");
                });
            } catch (Exception e) {
                showOperationFailure(safeMessage(e));
            }
        });
    }

    private void showOperationFailure(String reason) {
        runOnUiThread(() -> {
            if (isFinishing() || isDestroyed()) {
                return;
            }
            setBusy(false);
            showResult(getString(R.string.scene_operation_failed, reason), "");
        });
    }

    private void showResult(String message, String details) {
        lastResult.setText(TextUtils.isEmpty(details)
            ? message
            : message + "\n\n" + details);
        Toast.makeText(this, message, Toast.LENGTH_LONG).show();
    }

    private String displayName(Uri uri) {
        try (Cursor cursor = getContentResolver().query(
            uri,
            new String[] {OpenableColumns.DISPLAY_NAME},
            null,
            null,
            null
        )) {
            if (cursor != null && cursor.moveToFirst()) {
                String name = cursor.getString(0);
                if (!TextUtils.isEmpty(name)) {
                    return name;
                }
            }
        } catch (Exception ignored) {
            // Fall through to URI representation.
        }
        return uri.toString();
    }

    private static void appendFailure(StringBuilder details, String name, String reason) {
        if (details.length() > 0) {
            details.append('\n');
        }
        details.append("• ").append(name).append(": ").append(reason);
    }

    private static String safeMessage(Throwable throwable) {
        String message = throwable.getMessage();
        return TextUtils.isEmpty(message)
            ? throwable.getClass().getSimpleName()
            : message;
    }

    @Override
    protected void onDestroy() {
        started = false;
        lifecycleGeneration++;
        SceneSyncRuntimeState.Listener listener = runtimeListener;
        runtimeListener = null;
        runtimeState.removeListener(listener);
        visibilityFlag.close();
        ioExecutor.shutdownNow();
        super.onDestroy();
    }
}
