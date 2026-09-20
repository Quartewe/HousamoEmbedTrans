package com.quarty.housamoembedtrans.ui;

import com.quarty.housamoembedtrans.R;
import com.quarty.housamoembedtrans.bridge.HetBridgeContract;
import com.quarty.housamoembedtrans.runtime.SceneSyncRuntimeState;
import com.quarty.housamoembedtrans.runtime.SceneSyncUiVisibility;
import com.quarty.housamoembedtrans.scene.store.ConflictStore;
import com.quarty.housamoembedtrans.scene.store.SceneStore;
import com.quarty.housamoembedtrans.scene.sync.SceneSyncSettings;
import com.quarty.housamoembedtrans.storage.config.ConfigStore;
import com.quarty.housamoembedtrans.translation.TranslationService;

import android.content.Intent;
import android.os.Bundle;
import android.text.TextUtils;
import com.quarty.housamoembedtrans.logging.Log;
import android.view.View;
import android.view.ViewGroup;
import android.view.LayoutInflater;
import android.widget.LinearLayout;
import android.widget.RadioGroup;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;

import com.google.android.material.appbar.MaterialToolbar;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.card.MaterialCardView;
import com.google.android.material.radiobutton.MaterialRadioButton;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** UI for process-local Scene Sync state and complete conflict choices. */
public final class SceneFilesActivity extends AppCompatActivity {
    private static final String TAG = "SceneFilesActivity";

    private static final class ConflictRow {
        final String sceneName;
        final SceneConflictPresentation presentation;
        final boolean preview;

        ConflictRow(
            String sceneName,
            SceneConflictPresentation presentation,
            boolean preview
        ) {
            this.sceneName = sceneName == null ? "" : sceneName;
            this.presentation = presentation;
            this.preview = preview;
        }

        static ConflictRow valid(SceneConflictPresentation presentation) {
            return new ConflictRow(
                presentation == null ? "" : presentation.sceneName,
                presentation,
                false
            );
        }

        static ConflictRow invalid(String sceneName) {
            return new ConflictRow(sceneName, null, false);
        }

        static ConflictRow preview(String sceneName) {
            return new ConflictRow(sceneName, null, true);
        }
    }

    private static final class ConflictLoadResult {
        final List<ConflictRow> rows;
        final boolean failed;

        ConflictLoadResult(List<ConflictRow> rows, boolean failed) {
            this.rows = rows == null
                ? Collections.emptyList()
                : rows;
            this.failed = failed;
        }
    }

    private SceneSyncRuntimeState runtimeState;
    private SceneSyncUiVisibility.ActivityFlag visibilityFlag;
    private SceneSyncRuntimeBinding runtimeBinding;
    private ExecutorService ioExecutor;

    private SceneStore sceneStore;
    private ConflictStore conflictStore;
    private ConfigStore configStore;
    private TextView syncScope;
    private TextView syncResult;
    private TextView policyCurrent;
    private TextView policyNote;
    private RadioGroup policyGroup;
    private MaterialRadioButton policyManual;
    private MaterialRadioButton policyGame;
    private MaterialRadioButton policyHet;
    private MaterialButton policySettingsButton;
    private LinearLayout syncConflictContainer;
    private TextView syncConflictSummary;
    private TextView syncConflictEmpty;
    private TextView syncConflictArrow;
    private LinearLayout syncResultContainer;
    private LinearLayout syncConflictHeader;
    private MaterialButton refreshButton;
    private SceneSyncRuntimeState.Snapshot runtimeSnapshot;
    private final ArrayList<ConflictRow> syncConflicts = new ArrayList<>();
    private final Set<String> expandedSyncConflicts = new LinkedHashSet<>();
    private JSONObject stylePreviewPayload;
    private boolean stylePreview;
    private boolean refreshRequestPending;
    private boolean syncConflictLoading;
    private boolean syncConflictLoadFailed;
    private boolean syncConflictSectionExpanded = true;
    private boolean manualConflictActionPending;
    private boolean bindingPolicySelection;
    private boolean policyChangePending;
    private String conflictResolutionMode;
    private int syncConflictLoadGeneration;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        stylePreview = StylePreview.isEnabled(this);
        if (stylePreview) {
            stylePreviewPayload = StylePreview.payloadOf(getIntent());
            if (stylePreviewPayload == null) {
                stylePreviewPayload = StylePreview.sample(
                    StylePreview.KIND_SCENE_SYNC
                );
            }
        } else {
            initializeProductionState();
        }
        setContentView(R.layout.activity_scene_files);
        SystemBarInsets.apply(findViewById(R.id.root_scene_files));
        MaterialToolbar toolbar = findViewById(R.id.toolbar_scene_files);
        toolbar.setNavigationOnClickListener(
            view -> getOnBackPressedDispatcher().onBackPressed()
        );
        syncScope = findViewById(R.id.tv_scene_sync_scope);
        syncResult = findViewById(R.id.tv_scene_sync_result);
        policyCurrent = findViewById(R.id.tv_scene_sync_policy_current);
        policyNote = findViewById(R.id.tv_scene_sync_policy_note);
        policyGroup = findViewById(R.id.group_scene_sync_policy);
        policyManual = findViewById(R.id.radio_scene_sync_policy_manual);
        policyGame = findViewById(R.id.radio_scene_sync_policy_game);
        policyHet = findViewById(R.id.radio_scene_sync_policy_het);
        policySettingsButton = findViewById(
            R.id.btn_scene_sync_policy_settings
        );
        syncConflictHeader = findViewById(
            R.id.header_scene_sync_conflicts
        );
        syncConflictSummary = findViewById(
            R.id.tv_scene_sync_conflicts_summary
        );
        syncConflictArrow = findViewById(
            R.id.tv_scene_sync_conflicts_arrow
        );
        syncConflictEmpty = findViewById(
            R.id.tv_scene_sync_conflicts_empty
        );
        syncConflictContainer = findViewById(
            R.id.container_scene_sync_conflicts
        );
        syncResultContainer = findViewById(
            R.id.container_scene_sync_results
        );
        refreshButton = findViewById(R.id.btn_refresh_scene_sync);

        syncConflictHeader.setOnClickListener(view -> {
            syncConflictSectionExpanded = !syncConflictSectionExpanded;
            renderSyncConflictRows();
        });
        policyGroup.setOnCheckedChangeListener((group, checkedId) -> {
            if (!stylePreview && !bindingPolicySelection) {
                requestPolicyChange(policyModeForId(checkedId));
            }
        });
        policySettingsButton.setOnClickListener(
            view -> openSceneSyncSettings()
        );

        if (!stylePreview) {
            refreshButton.setOnClickListener(view -> requestSceneRefresh());
        }
        if (stylePreview) {
            loadStylePreview();
        } else {
            renderRuntimeSnapshot(runtimeSnapshot);
            renderPolicyMode();
            renderSyncConflictRows();
            updateActionState();
            loadSyncConflictsAsync();
            ensureTranslationService();
        }
    }

    private void initializeProductionState() {
        runtimeState = SceneSyncRuntimeState.getInstance();
        visibilityFlag = SceneSyncUiVisibility.newSceneFilesFlag();
        runtimeBinding = new SceneSyncRuntimeBinding(
            this,
            runtimeState,
            visibilityFlag,
            this::acceptRuntimeSnapshot
        );
        sceneStore = new SceneStore(this);
        conflictStore = new ConflictStore(this);
        configStore = new ConfigStore(this);
        ioExecutor = Executors.newSingleThreadExecutor();
        runtimeSnapshot = runtimeState.getSnapshot();
    }

    private void loadStylePreview() {
        runtimeSnapshot = previewRuntimeSnapshot(
            stylePreviewPayload.optJSONObject("runtime")
        );
        conflictResolutionMode = SceneSyncSettings.DEFAULT_CONFLICT_RESOLUTION_MODE;
        syncConflictLoading = false;
        syncConflictLoadFailed = false;
        syncConflicts.clear();
        expandedSyncConflicts.clear();
        if (runtimeSnapshot.pendingConflictCount > 0) {
            String previewSceneName = previewValue(
                stylePreviewPayload,
                "selected_scene"
            ).trim();
            if (previewSceneName.isEmpty()) {
                previewSceneName = getString(
                    R.string.scene_sync_preview_scene
                );
            }
            syncConflicts.add(ConflictRow.preview(previewSceneName));
            expandedSyncConflicts.add(previewSceneName);
        }
        renderPolicyMode();
        renderRuntimeSnapshot(runtimeSnapshot);
        renderSyncConflictRows();
        String previewResult = previewValue(
            stylePreviewPayload,
            "last_result"
        );
        if (TextUtils.isEmpty(previewResult)) {
            previewResult = getString(R.string.style_preview_read_only_body);
        }
        if (syncResult != null) {
            syncResult.setText(previewResult);
        }
        refreshRequestPending = false;
        updateActionState();
    }

    private SceneSyncRuntimeState.Snapshot previewRuntimeSnapshot(
        JSONObject value
    ) {
        JSONObject runtime = value == null ? new JSONObject() : value;
        ArrayList<SceneSyncRuntimeState.SceneSummary> summaries = new ArrayList<>();
        JSONArray values = runtime.optJSONArray("scene_summaries");
        if (values != null) {
            for (int index = 0; index < values.length(); index++) {
                JSONObject item = values.optJSONObject(index);
                if (item == null) {
                    continue;
                }
                String scene = item.optString("scene", "").trim();
                if (scene.isEmpty()) {
                    continue;
                }
                summaries.add(new SceneSyncRuntimeState.SceneSummary(
                    scene,
                    enumValue(
                        SceneSyncRuntimeState.Direction.class,
                        item.optString("direction", "UNKNOWN"),
                        SceneSyncRuntimeState.Direction.UNKNOWN
                    ),
                    enumValue(
                        SceneSyncRuntimeState.Status.class,
                        item.optString("status", "NOT_PROCESSED"),
                        SceneSyncRuntimeState.Status.NOT_PROCESSED
                    )
                ));
            }
        }
        return new SceneSyncRuntimeState.Snapshot(
            runtime.optBoolean("service_available", false),
            runtime.optBoolean("game_port_available", false),
            enumValue(
                SceneSyncRuntimeState.Phase.class,
                runtime.optString("phase", "IDLE"),
                SceneSyncRuntimeState.Phase.IDLE
            ),
            Math.max(0, runtime.optInt("active_api_jobs", 0)),
            Math.max(0, runtime.optInt("pending_conflict_count", 0)),
            enumValue(
                SceneSyncRuntimeState.Action.class,
                runtime.optString("last_action", "NONE"),
                SceneSyncRuntimeState.Action.NONE
            ),
            enumValue(
                SceneSyncRuntimeState.Outcome.class,
                runtime.optString("last_outcome", "NONE"),
                SceneSyncRuntimeState.Outcome.NONE
            ),
            summaries
        );
    }

    private static String previewValue(JSONObject value, String key) {
        if (value == null) {
            return "";
        }
        return value.optString(key, "");
    }

    private static <T extends Enum<T>> T enumValue(
        Class<T> type,
        String value,
        T fallback
    ) {
        if (value == null) {
            return fallback;
        }
        try {
            return Enum.valueOf(type, value.trim().toUpperCase(java.util.Locale.ROOT));
        } catch (IllegalArgumentException error) {
            return fallback;
        }
    }

    @Override
    protected void onStart() {
        super.onStart();
        if (!stylePreview && runtimeBinding != null) {
            runtimeBinding.start();
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (!stylePreview && configStore != null) {
            loadConflictResolutionModeAsync();
        }
    }

    @Override
    protected void onStop() {
        if (!stylePreview && runtimeBinding != null) {
            runtimeBinding.stop();
        }
        super.onStop();
    }

    private void acceptRuntimeSnapshot(
        SceneSyncRuntimeState.Snapshot changed
    ) {
        if (stylePreview || changed == null || isFinishing() || isDestroyed()) {
            return;
        }
        SceneSyncRuntimeState.Snapshot previous = runtimeSnapshot;
        runtimeSnapshot = changed;
        boolean manualAction = changed.lastAction
            == SceneSyncRuntimeState.Action.CHOOSE_GAME
            || changed.lastAction == SceneSyncRuntimeState.Action.CHOOSE_HET;
        boolean terminalManualAction = manualAction
            && changed.lastOutcome != SceneSyncRuntimeState.Outcome.STARTED;
        boolean pendingManualAction = manualConflictActionPending;
        boolean manualActionFinished = pendingManualAction
            && (terminalManualAction || !changed.serviceAvailable);
        if (manualActionFinished) {
            manualConflictActionPending = false;
        }
        renderRuntimeSnapshot(changed);
        updateActionState();
        if (manualActionFinished) {
            showManualOutcome(
                !changed.serviceAvailable
                    ? SceneSyncRuntimeState.Outcome.UNAVAILABLE
                    : changed.lastOutcome
            );
        }
        boolean phaseFinished = previous != null
            && previous.phase == SceneSyncRuntimeState.Phase.MANUAL_APPLY
            && changed.phase != SceneSyncRuntimeState.Phase.MANUAL_APPLY;
        boolean conflictCountChanged = previous != null
            && previous.pendingConflictCount != changed.pendingConflictCount;
        if (phaseFinished || conflictCountChanged) {
            loadSyncConflictsAsync();
        }
    }

    private void showManualOutcome(
        SceneSyncRuntimeState.Outcome outcome
    ) {
        int message;
        switch (outcome) {
            case SUCCEEDED:
                message = R.string.scene_conflict_action_succeeded;
                break;
            case NEEDS_ATTENTION:
                message = R.string.scene_conflict_action_pending_offline;
                break;
            case QUEUED_BEHIND_GATE:
                message = R.string.scene_conflict_action_queued_behind_gate;
                break;
            case BUSY:
            case DEFERRED:
                message = R.string.scene_conflict_action_busy;
                break;
            case UNAVAILABLE:
                message = R.string.scene_conflict_action_unavailable;
                break;
            case FAILED:
            case LOCAL_ONLY:
            case NONE:
            default:
                message = R.string.scene_conflict_action_failed;
                break;
        }
        Toast.makeText(this, message, Toast.LENGTH_LONG).show();
    }

    private void renderRuntimeSnapshot(
        SceneSyncRuntimeState.Snapshot snapshot
    ) {
        if (snapshot == null) {
            return;
        }
        if (syncScope != null) {
            if (snapshot.serviceAvailable && snapshot.gamePortAvailable) {
                syncScope.setText(R.string.scene_sync_scope_online);
            } else if (!snapshot.gamePortAvailable) {
                syncScope.setText(R.string.scene_sync_scope_local);
            } else {
                syncScope.setText(R.string.scene_sync_scope_service_unavailable);
            }
        }

        if (syncResult != null) {
            if (refreshRequestPending) {
                syncResult.setText(
                    snapshot.serviceAvailable && snapshot.gamePortAvailable
                        ? R.string.scene_sync_result_preparing
                        : R.string.scene_sync_result_refreshing_local
                );
            } else {
                syncResult.setText(getString(
                    R.string.scene_sync_result_last,
                    syncOutcomeLabel(snapshot)
                ));
            }
        }

        if (refreshButton != null) {
            if (snapshot.phase != SceneSyncRuntimeState.Phase.IDLE) {
                refreshButton.setText(R.string.scene_sync_action_syncing);
            } else if (snapshot.serviceAvailable
                && snapshot.gamePortAvailable) {
                refreshButton.setText(R.string.scene_sync_action_sync);
            } else {
                refreshButton.setText(R.string.scene_sync_action_refresh_local);
            }
        }

        int pendingConflicts = snapshot.pendingConflictCount;
        if (syncConflictSummary != null) {
            syncConflictSummary.setText(
                pendingConflicts == 0
                    ? getString(R.string.scene_sync_conflicts_none)
                    : getString(
                        R.string.scene_sync_conflicts_count,
                        pendingConflicts
                    )
            );
        }
        renderSceneSummaries(snapshot.sceneSummaries);
        renderSyncConflictRows();
    }

    private String syncOutcomeLabel(
        SceneSyncRuntimeState.Snapshot snapshot
    ) {
        if (snapshot == null || snapshot.lastAction
            == SceneSyncRuntimeState.Action.NONE) {
            return getString(R.string.scene_sync_result_ready);
        }
        if (snapshot.lastAction == SceneSyncRuntimeState.Action.LOCAL_REFRESH
            || (!snapshot.gamePortAvailable
                && snapshot.lastAction
                    == SceneSyncRuntimeState.Action.MANUAL_REFRESH)) {
            switch (snapshot.lastOutcome) {
                case STARTED:
                    return getString(
                        R.string.scene_sync_result_refreshing_local
                    );
                case FAILED:
                    return getString(R.string.scene_sync_result_failed);
                case UNAVAILABLE:
                    return getString(
                        R.string.scene_sync_result_unavailable
                    );
                default:
                    return getString(
                        R.string.scene_sync_result_local_refreshed
                    );
            }
        }
        switch (snapshot.lastOutcome) {
            case STARTED:
                return getString(R.string.scene_sync_result_started);
            case BUSY:
                return getString(R.string.scene_sync_result_busy);
            case DEFERRED:
                return getString(R.string.scene_sync_result_deferred);
            case QUEUED_BEHIND_GATE:
                return getString(R.string.scene_sync_result_queued);
            case LOCAL_ONLY:
                return getString(
                    R.string.scene_sync_result_local_refreshed
                );
            case SUCCEEDED:
                return getString(R.string.scene_sync_result_succeeded);
            case NEEDS_ATTENTION:
                return getString(
                    R.string.scene_sync_result_needs_attention
                );
            case FAILED:
                return getString(R.string.scene_sync_result_failed);
            case UNAVAILABLE:
                return getString(R.string.scene_sync_result_unavailable);
            case NONE:
            default:
                return getString(R.string.scene_sync_result_ready);
        }
    }

    private void renderSceneSummaries(
        List<SceneSyncRuntimeState.SceneSummary> sceneSummaries
    ) {
        if (sceneSummaries == null) {
            sceneSummaries = Collections.emptyList();
        }
        if (sceneSummaries.isEmpty()) {
            if (syncResultContainer != null) {
                syncResultContainer.removeAllViews();
                TextView empty = new TextView(this);
                empty.setText(R.string.scene_sync_recent_empty);
                empty.setTextAppearance(
                    R.style.TextAppearance_HET_StaticManagement_Meta
                );
                syncResultContainer.addView(empty);
            }
            return;
        }

        if (syncResultContainer != null) {
            syncResultContainer.removeAllViews();
            for (SceneSyncRuntimeState.SceneSummary scene : sceneSummaries) {
                LinearLayout row = new LinearLayout(this);
                row.setOrientation(LinearLayout.VERTICAL);
                row.setPadding(
                    0,
                    dp(10),
                    0,
                    dp(10)
                );

                TextView name = new TextView(this);
                name.setText(scene.sceneName);
                name.setTextAppearance(
                    R.style.TextAppearance_HET_StaticManagement_Title
                );
                row.addView(name);

                TextView direction = new TextView(this);
                direction.setText(getString(
                    R.string.scene_sync_recent_item,
                    directionLabel(scene.direction),
                    statusLabel(scene.status)
                ));
                direction.setTextAppearance(
                    R.style.TextAppearance_HET_StaticManagement_Meta
                );
                LinearLayout.LayoutParams directionParams =
                    new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT
                    );
                directionParams.topMargin = dp(4);
                row.addView(direction, directionParams);

                syncResultContainer.addView(row, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                ));
            }
        }
    }

    private String directionLabel(
        SceneSyncRuntimeState.Direction direction
    ) {
        switch (direction) {
            case GAME_TO_HET:
                return getString(R.string.scene_sync_direction_game_to_het);
            case HET_TO_GAME:
                return getString(R.string.scene_sync_direction_het_to_game);
            case BIDIRECTIONAL:
                return getString(R.string.scene_sync_direction_bidirectional);
            case LOCAL:
                return getString(R.string.scene_sync_direction_local);
            case UNKNOWN:
            default:
                return getString(R.string.scene_sync_direction_unknown);
        }
    }

    private String statusLabel(SceneSyncRuntimeState.Status status) {
        switch (status) {
            case PROCESSED:
                return getString(R.string.scene_sync_status_processed);
            case DELETED:
                return getString(R.string.scene_sync_status_deleted);
            case NEEDS_ATTENTION:
                return getString(R.string.scene_sync_status_needs_attention);
            case NOT_PROCESSED:
            default:
                return getString(R.string.scene_sync_status_not_processed);
        }
    }

    private void ensureTranslationService() {
        if (stylePreview) {
            return;
        }
        Intent intent = new Intent(this, TranslationService.class)
            .setPackage(getPackageName())
            .setAction(HetBridgeContract.ACTION_START_TRANSLATION_SERVICE);
        try {
            ContextCompat.startForegroundService(this, intent);
        } catch (RuntimeException error) {
            Log.w(TAG, "Could not start Scene Sync service", error);
        }
    }

    private void openSceneSyncSettings() {
        if (stylePreview) {
            return;
        }
        startActivity(new Intent(this, SettingsCategoryActivity.class)
            .putExtra(
                SettingsCategoryActivity.EXTRA_CATEGORY,
                SettingsCategory.CAPTURE_SYNC
            ));
    }

    private void loadConflictResolutionModeAsync() {
        if (stylePreview || configStore == null || ioExecutor == null) {
            return;
        }
        ioExecutor.execute(() -> {
            String mode = null;
            try {
                ConfigStore.LoadResult loaded = configStore.load();
                JSONObject userSettings = loaded.config.optJSONObject(
                    "UserSettings"
                );
                mode = ConfigStore.getConflictResolutionMode(userSettings);
            } catch (Exception error) {
                Log.w(TAG, "Could not load Scene Sync conflict mode", error);
            }
            String loadedMode = mode;
            runOnUiThread(() -> {
                if (isFinishing() || isDestroyed() || policyChangePending) {
                    return;
                }
                conflictResolutionMode = loadedMode;
                renderPolicyMode();
            });
        });
    }

    private void renderPolicyMode() {
        if (policyCurrent == null || policyGroup == null) {
            return;
        }
        boolean hasMode = SceneSyncSettings.CONFLICT_MODE_GAME.equals(
            conflictResolutionMode
        ) || SceneSyncSettings.CONFLICT_MODE_HET.equals(
            conflictResolutionMode
        ) || SceneSyncSettings.CONFLICT_MODE_MANUAL.equals(
            conflictResolutionMode
        );
        if (!hasMode) {
            policyCurrent.setText(
                stylePreview
                    ? R.string.scene_sync_policy_preview
                    : R.string.scene_sync_policy_unavailable
            );
            setPolicyControlsEnabled(false);
            policySettingsButton.setEnabled(!stylePreview);
            policyNote.setText(R.string.scene_sync_policy_load_failed);
            return;
        }

        int checkedId = R.id.radio_scene_sync_policy_manual;
        int label = R.string.scene_sync_policy_manual;
        if (SceneSyncSettings.CONFLICT_MODE_GAME.equals(
            conflictResolutionMode
        )) {
            checkedId = R.id.radio_scene_sync_policy_game;
            label = R.string.scene_sync_policy_game;
        } else if (SceneSyncSettings.CONFLICT_MODE_HET.equals(
            conflictResolutionMode
        )) {
            checkedId = R.id.radio_scene_sync_policy_het;
            label = R.string.scene_sync_policy_het;
        }
        policyCurrent.setText(getString(
            R.string.scene_sync_policy_current,
            getString(label)
        ));
        policyNote.setText(R.string.scene_sync_policy_note);
        setPolicyControlsEnabled(!stylePreview && !policyChangePending);
        policySettingsButton.setEnabled(!stylePreview);
        bindingPolicySelection = true;
        policyGroup.check(checkedId);
        bindingPolicySelection = false;
    }

    private void setPolicyControlsEnabled(boolean enabled) {
        if (policyGroup != null) {
            policyGroup.setEnabled(enabled);
        }
        if (policyManual != null) {
            policyManual.setEnabled(enabled);
        }
        if (policyGame != null) {
            policyGame.setEnabled(enabled);
        }
        if (policyHet != null) {
            policyHet.setEnabled(enabled);
        }
    }

    private static boolean isKnownPolicyMode(String mode) {
        return SceneSyncSettings.CONFLICT_MODE_GAME.equals(mode)
            || SceneSyncSettings.CONFLICT_MODE_HET.equals(mode)
            || SceneSyncSettings.CONFLICT_MODE_MANUAL.equals(mode);
    }

    private String policyModeForId(int checkedId) {
        if (checkedId == R.id.radio_scene_sync_policy_game) {
            return SceneSyncSettings.CONFLICT_MODE_GAME;
        }
        if (checkedId == R.id.radio_scene_sync_policy_het) {
            return SceneSyncSettings.CONFLICT_MODE_HET;
        }
        if (checkedId == R.id.radio_scene_sync_policy_manual) {
            return SceneSyncSettings.CONFLICT_MODE_MANUAL;
        }
        return null;
    }

    private void requestPolicyChange(String requestedMode) {
        if (stylePreview || configStore == null || ioExecutor == null
            || requestedMode == null
            || policyChangePending
            || requestedMode.equals(conflictResolutionMode)) {
            renderPolicyMode();
            return;
        }

        final String previousMode = conflictResolutionMode;
        policyChangePending = true;
        setPolicyControlsEnabled(false);
        ioExecutor.execute(() -> {
            boolean saved = false;
            String failure = null;
            try {
                ConfigStore.LoadResult loaded = configStore.load();
                JSONObject updated = new JSONObject(
                    loaded.config.toString()
                );
                JSONObject userSettings = updated.optJSONObject(
                    "UserSettings"
                );
                if (userSettings == null) {
                    throw new IOException("UserSettings is missing");
                }
                JSONObject sceneSync = userSettings.optJSONObject("SceneSync");
                if (sceneSync == null) {
                    sceneSync = new JSONObject();
                    userSettings.put("SceneSync", sceneSync);
                }
                sceneSync.put(
                    "ConflictResolutionMode",
                    SceneSyncSettings.normalizeConflictResolutionMode(
                        requestedMode
                    )
                );
                configStore.save(updated);
                saved = true;
            } catch (Exception error) {
                failure = safeMessage(error);
                Log.w(TAG, "Could not save Scene Sync conflict mode", error);
            }

            final boolean completed = saved;
            final String errorMessage = failure;
            runOnUiThread(() -> {
                if (isFinishing() || isDestroyed()) {
                    return;
                }
                policyChangePending = false;
                conflictResolutionMode = completed
                    ? requestedMode
                    : previousMode;
                renderPolicyMode();
                if (!completed) {
                    Toast.makeText(
                        this,
                        getString(
                            R.string.scene_sync_policy_save_failed,
                            TextUtils.isEmpty(errorMessage)
                                ? getString(R.string.scene_sync_policy_unavailable)
                                : errorMessage
                        ),
                        Toast.LENGTH_LONG
                    ).show();
                }
            });
        });
    }

    private void loadSyncConflictsAsync() {
        if (stylePreview || conflictStore == null || sceneStore == null
            || ioExecutor == null) {
            return;
        }
        syncConflictLoading = true;
        syncConflictLoadFailed = false;
        renderSyncConflictRows();
        int generation = ++syncConflictLoadGeneration;
        ioExecutor.execute(() -> {
            ConflictLoadResult loaded;
            try {
                loaded = loadSyncConflicts();
            } catch (Exception error) {
                Log.e(TAG, "Could not load Scene Sync conflicts", error);
                loaded = new ConflictLoadResult(
                    Collections.emptyList(),
                    true
                );
            }
            ConflictLoadResult completed = loaded;
            runOnUiThread(() -> {
                if (generation != syncConflictLoadGeneration
                    || isFinishing()
                    || isDestroyed()) {
                    return;
                }
                syncConflictLoading = false;
                syncConflictLoadFailed = completed.failed;
                syncConflicts.clear();
                syncConflicts.addAll(completed.rows);
                retainExpandedSyncConflicts();
                renderSyncConflictRows();
            });
        });
    }

    private ConflictLoadResult loadSyncConflicts() throws Exception {
        List<String> sceneNames = conflictStore.listClaimedSceneNames();
        ArrayList<ConflictRow> loaded = new ArrayList<>(sceneNames.size());
        for (String sceneName : sceneNames) {
            if (Thread.currentThread().isInterrupted()) {
                break;
            }
            try {
                ConflictStore.ConflictRecord record = conflictStore.read(
                    sceneName
                );
                SceneStore.ValidatedScene game = sceneStore.validate(
                    record.gameBytes
                );
                SceneStore.ValidatedScene het = sceneStore.validate(
                    record.hetBytes
                );
                loaded.add(ConflictRow.valid(
                    SceneConflictPresentation.fromValidatedCandidates(
                        sceneName,
                        game,
                        het
                    )
                ));
            } catch (Exception error) {
                Log.e(
                    TAG,
                    "Could not prepare inline conflict scene=" + sceneName,
                    error
                );
                loaded.add(ConflictRow.invalid(sceneName));
            }
        }
        return new ConflictLoadResult(loaded, false);
    }

    private void retainExpandedSyncConflicts() {
        Set<String> names = new LinkedHashSet<>();
        for (ConflictRow row : syncConflicts) {
            names.add(row.sceneName);
        }
        expandedSyncConflicts.retainAll(names);
    }

    private void renderSyncConflictRows() {
        if (syncConflictContainer == null) {
            return;
        }
        int previousScrollY = findViewById(R.id.scroll_scene_files)
            .getScrollY();
        syncConflictContainer.removeAllViews();
        boolean showEmpty;
        if (syncConflictLoading) {
            syncConflictEmpty.setText(R.string.scene_sync_conflicts_loading);
            showEmpty = true;
        } else if (syncConflictLoadFailed) {
            syncConflictEmpty.setText(
                R.string.scene_sync_conflicts_load_failed
            );
            showEmpty = true;
        } else if (syncConflicts.isEmpty()) {
            syncConflictEmpty.setText(
                stylePreview
                    ? R.string.scene_sync_conflicts_preview
                    : R.string.scene_sync_conflicts_empty
            );
            showEmpty = true;
        } else {
            showEmpty = false;
            LayoutInflater inflater = LayoutInflater.from(this);
            for (ConflictRow row : syncConflicts) {
                View item = inflater.inflate(
                    R.layout.item_scene_sync_conflict,
                    syncConflictContainer,
                    false
                );
                bindSyncConflictRow(item, row);
                LinearLayout.LayoutParams params =
                    new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT
                    );
                params.bottomMargin = dp(8);
                syncConflictContainer.addView(item, params);
            }
        }
        syncConflictContainer.setVisibility(
            syncConflictSectionExpanded ? View.VISIBLE : View.GONE
        );
        syncConflictEmpty.setVisibility(
            syncConflictSectionExpanded && showEmpty ? View.VISIBLE : View.GONE
        );
        syncConflictArrow.setText(syncConflictSectionExpanded ? "⌃" : "⌄");
        if (syncConflictHeader != null) {
            syncConflictHeader.setContentDescription(getString(
                syncConflictSectionExpanded
                    ? R.string.scene_sync_conflicts_collapse
                    : R.string.scene_sync_conflicts_expand
            ));
        }
        if (previousScrollY > 0) {
            findViewById(R.id.scroll_scene_files).post(() ->
                findViewById(R.id.scroll_scene_files).scrollTo(
                    0,
                    previousScrollY
                )
            );
        }
    }

    private void bindSyncConflictRow(View root, ConflictRow row) {
        MaterialCardView card = root.findViewById(
            R.id.card_scene_sync_conflict
        );
        LinearLayout header = root.findViewById(
            R.id.header_scene_sync_conflict
        );
        LinearLayout body = root.findViewById(
            R.id.body_scene_sync_conflict
        );
        TextView name = root.findViewById(R.id.tv_scene_sync_conflict_name);
        TextView arrow = root.findViewById(
            R.id.tv_scene_sync_conflict_arrow
        );
        TextView changes = root.findViewById(
            R.id.tv_scene_sync_conflict_changes
        );
        TextView gameSummary = root.findViewById(
            R.id.tv_scene_sync_conflict_game_summary
        );
        TextView hetSummary = root.findViewById(
            R.id.tv_scene_sync_conflict_het_summary
        );
        TextView error = root.findViewById(R.id.tv_scene_sync_conflict_error);
        TextView details = root.findViewById(
            R.id.tv_scene_sync_conflict_details
        );
        LinearLayout gameCandidate = root.findViewById(
            R.id.container_scene_sync_conflict_game
        );
        LinearLayout hetCandidate = root.findViewById(
            R.id.container_scene_sync_conflict_het
        );
        MaterialButton chooseGame = root.findViewById(
            R.id.btn_scene_sync_conflict_choose_game
        );
        MaterialButton chooseHet = root.findViewById(
            R.id.btn_scene_sync_conflict_choose_het
        );

        name.setText(row.sceneName);
        boolean expanded = expandedSyncConflicts.contains(row.sceneName);
        arrow.setText(expanded ? "⌃" : "⌄");
        header.setContentDescription(getString(
            expanded
                ? R.string.scene_sync_conflict_collapse
                : R.string.scene_sync_conflict_expand
        ));
        header.setOnClickListener(view -> toggleSyncConflict(row.sceneName));
        body.setVisibility(expanded ? View.VISIBLE : View.GONE);
        card.setClickable(false);
        card.setOnClickListener(null);

        if (!row.preview && row.presentation == null) {
            changes.setVisibility(View.GONE);
            gameCandidate.setVisibility(View.GONE);
            hetCandidate.setVisibility(View.GONE);
            error.setVisibility(View.VISIBLE);
            details.setVisibility(View.GONE);
            return;
        }

        changes.setVisibility(View.VISIBLE);
        gameCandidate.setVisibility(View.VISIBLE);
        hetCandidate.setVisibility(View.VISIBLE);
        error.setVisibility(View.GONE);
        details.setVisibility(View.VISIBLE);
        if (row.preview) {
            changes.setText(R.string.scene_sync_preview_conflict_changes);
            gameSummary.setText(
                R.string.scene_sync_preview_game_candidate
            );
            hetSummary.setText(R.string.scene_sync_preview_het_candidate);
            details.setText(R.string.scene_sync_preview_conflict_details);
        } else {
            SceneConflictPresentation presentation = row.presentation;
            changes.setText(getString(
                R.string.scene_conflict_changes,
                presentation.structureChangeCount,
                presentation.originalChangeCount,
                presentation.translationChangeCount
            ));
            gameSummary.setText(formatSide(
                R.string.scene_conflict_game_side,
                presentation.game
            ));
            hetSummary.setText(formatSide(
                R.string.scene_conflict_het_side,
                presentation.het
            ));
            details.setText(formatAlignedTexts(presentation));
        }
        details.setVisibility(expanded ? View.VISIBLE : View.GONE);
        boolean choicesEnabled = !row.preview && canChooseCandidates();
        chooseGame.setEnabled(choicesEnabled);
        chooseHet.setEnabled(choicesEnabled);
        chooseGame.setOnClickListener(view ->
            chooseCandidate(row.sceneName, false)
        );
        chooseHet.setOnClickListener(view ->
            chooseCandidate(row.sceneName, true)
        );
    }

    private void toggleSyncConflict(String sceneName) {
        if (expandedSyncConflicts.contains(sceneName)) {
            expandedSyncConflicts.remove(sceneName);
        } else {
            expandedSyncConflicts.add(sceneName);
        }
        renderSyncConflictRows();
    }

    private void chooseCandidate(String sceneName, boolean chooseHet) {
        if (!canChooseCandidates()) {
            return;
        }
        manualConflictActionPending = true;
        renderSyncConflictRows();
        if (chooseHet) {
            runtimeState.chooseHet(sceneName, false);
        } else {
            runtimeState.chooseGame(sceneName);
        }
    }

    private boolean canChooseCandidates() {
        return !stylePreview
            && !syncConflictLoading
            && !manualConflictActionPending
            && runtimeSnapshot != null
            && runtimeSnapshot.serviceAvailable
            && runtimeSnapshot.phase == SceneSyncRuntimeState.Phase.IDLE;
    }

    private String formatSide(
        int titleResource,
        SceneConflictPresentation.SideSummary side
    ) {
        return getString(
            R.string.scene_conflict_side_details,
            getString(titleResource),
            displayValue(side.rawLanguage),
            displayValue(side.targetLanguage),
            formatLanguages(side.languages),
            formatTranslated(side.translated),
            formatStringMap(side.summaries),
            formatStringMap(side.providers),
            formatStringMap(side.models)
        );
    }

    private String formatLanguages(List<String> languages) {
        if (languages.isEmpty()) {
            return getString(R.string.scene_conflict_none);
        }
        StringBuilder result = new StringBuilder();
        for (String language : languages) {
            if (result.length() > 0) {
                result.append(", ");
            }
            result.append(language);
        }
        return result.toString();
    }

    private String formatTranslated(Map<String, Boolean> translated) {
        if (translated.isEmpty()) {
            return getString(R.string.scene_conflict_none);
        }
        StringBuilder result = new StringBuilder();
        for (Map.Entry<String, Boolean> entry : translated.entrySet()) {
            appendMapSeparator(result);
            result.append(entry.getKey())
                .append(": ")
                .append(getString(
                    Boolean.TRUE.equals(entry.getValue())
                        ? R.string.scene_conflict_yes
                        : R.string.scene_conflict_no
                ));
        }
        return result.toString();
    }

    private String formatStringMap(Map<String, String> values) {
        if (values.isEmpty()) {
            return getString(R.string.scene_conflict_none);
        }
        StringBuilder result = new StringBuilder();
        for (Map.Entry<String, String> entry : values.entrySet()) {
            appendMapSeparator(result);
            result.append(entry.getKey())
                .append(": ")
                .append(displayValue(entry.getValue()));
        }
        return result.toString();
    }

    private static void appendMapSeparator(StringBuilder result) {
        if (result.length() > 0) {
            result.append("\n");
        }
    }

    private String displayValue(String value) {
        return value == null || value.isEmpty()
            ? getString(R.string.scene_conflict_empty_value)
            : value;
    }

    private String formatAlignedTexts(
        SceneConflictPresentation presentation
    ) {
        if (presentation.alignedTexts.isEmpty()) {
            return getString(R.string.scene_conflict_no_text_items);
        }
        StringBuilder details = new StringBuilder();
        for (SceneConflictPresentation.AlignedText aligned
            : presentation.alignedTexts) {
            if (details.length() > 0) {
                details.append("\n\n");
            }
            details.append(getString(
                R.string.scene_conflict_order_key,
                aligned.order.labelIndex,
                aligned.order.pageNo,
                aligned.order.commandIndex,
                aligned.order.subIndex
            ));
            details.append("\n").append(formatTextSide(
                R.string.scene_conflict_game_side,
                aligned.game
            ));
            details.append("\n").append(formatTextSide(
                R.string.scene_conflict_het_side,
                aligned.het
            ));
        }
        return details.toString();
    }

    private String formatTextSide(
        int titleResource,
        SceneConflictPresentation.TextSide side
    ) {
        String title = getString(titleResource);
        if (side == null) {
            return getString(R.string.scene_conflict_missing_text_side, title);
        }
        return getString(
            R.string.scene_conflict_text_side_details,
            title,
            displayValue(side.speaker),
            displayValue(side.originalText),
            formatStringMap(side.translations)
        );
    }

    private void requestSceneRefresh() {
        if (stylePreview || runtimeState == null || sceneStore == null
            || ioExecutor == null || !canRequestSceneRefresh()) {
            return;
        }

        refreshRequestPending = true;
        renderRuntimeSnapshot(runtimeSnapshot);
        updateActionState();

        ioExecutor.execute(() -> {
            boolean localReadFailed = false;
            try {
                // Keep the existing local-list read as the offline refresh
                // boundary before asking the runtime controller to sync.
                sceneStore.listSceneInfos();
            } catch (RuntimeException error) {
                localReadFailed = true;
                Log.e(TAG, "Could not reload local Scenes before refresh", error);
            }
            final boolean failed = localReadFailed;
            runOnUiThread(() -> {
                if (isFinishing() || isDestroyed()) {
                    return;
                }
                if (failed) {
                    refreshRequestPending = false;
                    runtimeSnapshot = runtimeState.getSnapshot();
                    renderRuntimeSnapshot(runtimeSnapshot);
                    updateActionState();
                    Toast.makeText(
                        this,
                        R.string.scene_sync_result_failed,
                        Toast.LENGTH_LONG
                    ).show();
                    return;
                }

                loadSyncConflictsAsync();
                try {
                    runtimeState.requestRefresh();
                } catch (RuntimeException error) {
                    Log.e(TAG, "Could not request Scene Sync refresh", error);
                }
                refreshRequestPending = false;
                runtimeSnapshot = runtimeState.getSnapshot();
                renderRuntimeSnapshot(runtimeSnapshot);
                updateActionState();
            });
        });
    }

    private boolean canRequestSceneRefresh() {
        return !stylePreview
            && runtimeSnapshot != null
            && !refreshRequestPending
            && runtimeSnapshot.phase == SceneSyncRuntimeState.Phase.IDLE;
    }

    private void updateActionState() {
        if (stylePreview) {
            refreshButton.setEnabled(false);
            setPolicyControlsEnabled(false);
            policySettingsButton.setEnabled(false);
            return;
        }
        refreshButton.setEnabled(canRequestSceneRefresh());
        if (!policyChangePending) {
            setPolicyControlsEnabled(isKnownPolicyMode(conflictResolutionMode));
        }
        policySettingsButton.setEnabled(!policyChangePending);
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    private static String safeMessage(Throwable throwable) {
        String message = throwable.getMessage();
        return TextUtils.isEmpty(message)
            ? throwable.getClass().getSimpleName()
            : message;
    }

    @Override
    protected void onDestroy() {
        if (!stylePreview && runtimeBinding != null) {
            runtimeBinding.stop();
        }
        if (!stylePreview && visibilityFlag != null) {
            visibilityFlag.close();
        }
        syncConflictLoadGeneration++;
        if (!stylePreview && ioExecutor != null) {
            ioExecutor.shutdownNow();
        }
        super.onDestroy();
    }
}
