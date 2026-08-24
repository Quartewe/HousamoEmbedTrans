package com.quarty.housamoembedtrans.ui;

import com.quarty.housamoembedtrans.R;
import com.quarty.housamoembedtrans.bridge.HetBridgeContract;
import com.quarty.housamoembedtrans.runtime.SceneSyncRuntimeState;
import com.quarty.housamoembedtrans.runtime.SceneSyncUiVisibility;
import com.quarty.housamoembedtrans.storage.ConflictStore;
import com.quarty.housamoembedtrans.storage.SceneStore;
import com.quarty.housamoembedtrans.translation.TranslationService;

import android.content.Intent;
import android.os.Bundle;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;

import com.google.android.material.appbar.MaterialToolbar;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.card.MaterialCardView;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Displays and applies complete, independently stored Scene conflicts. */
public final class SceneConflictsActivity extends AppCompatActivity {
    private static final String TAG = "HET.SceneConflicts";
    private static final String STATE_EXPANDED_SCENES = "expanded_scenes";

    private final SceneSyncRuntimeState runtimeState =
        SceneSyncRuntimeState.getInstance();
    private final SceneSyncUiVisibility.ActivityFlag visibilityFlag =
        SceneSyncUiVisibility.newSceneConflictsFlag();
    private final SceneSyncRuntimeBinding runtimeBinding =
        new SceneSyncRuntimeBinding(
            this,
            runtimeState,
            visibilityFlag,
            this::acceptRuntimeSnapshot
        );
    private final ExecutorService ioExecutor =
        Executors.newSingleThreadExecutor();
    private final ArrayList<ConflictRow> conflicts = new ArrayList<>();
    private final Set<String> expandedScenes = new HashSet<>();

    private SceneStore sceneStore;
    private ConflictStore conflictStore;
    private ConflictAdapter adapter;
    private TextView runtimeStatus;
    private TextView summary;
    private TextView emptyMessage;
    private SceneSyncRuntimeState.Snapshot runtimeSnapshot =
        runtimeState.getSnapshot();
    private boolean loading;
    private boolean reloadRequested;
    private boolean manualActionPending;
    private boolean serviceStartFailed;
    private int loadGeneration;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_scene_conflicts);
        SystemBarInsets.apply(findViewById(R.id.root_scene_conflicts));

        if (savedInstanceState != null) {
            ArrayList<String> restored = savedInstanceState.getStringArrayList(
                STATE_EXPANDED_SCENES
            );
            if (restored != null) {
                expandedScenes.addAll(restored);
            }
        }

        sceneStore = new SceneStore(this);
        conflictStore = new ConflictStore(this);
        runtimeStatus = findViewById(
            R.id.tv_scene_conflicts_runtime_status
        );
        summary = findViewById(R.id.tv_scene_conflicts_summary);
        emptyMessage = findViewById(R.id.tv_scene_conflicts_empty);
        ListView conflictList = findViewById(R.id.list_scene_conflicts);
        adapter = new ConflictAdapter();
        conflictList.setAdapter(adapter);
        conflictList.setEmptyView(emptyMessage);

        MaterialToolbar toolbar = findViewById(
            R.id.toolbar_scene_conflicts
        );
        toolbar.setNavigationOnClickListener(view ->
            getOnBackPressedDispatcher().onBackPressed()
        );

        renderRuntimeSnapshot(runtimeSnapshot);
        ensureTranslationService();
        requestConflictReload();
    }

    @Override
    protected void onStart() {
        super.onStart();
        runtimeBinding.start();
    }

    @Override
    protected void onStop() {
        runtimeBinding.stop();
        super.onStop();
    }

    @Override
    protected void onSaveInstanceState(Bundle outState) {
        outState.putStringArrayList(
            STATE_EXPANDED_SCENES,
            new ArrayList<>(expandedScenes)
        );
        super.onSaveInstanceState(outState);
    }

    @Override
    protected void onDestroy() {
        runtimeBinding.stop();
        loadGeneration++;
        ioExecutor.shutdownNow();
        visibilityFlag.close();
        super.onDestroy();
    }

    private void ensureTranslationService() {
        Intent intent = new Intent(this, TranslationService.class)
            .setPackage(getPackageName())
            .setAction(HetBridgeContract.ACTION_START_TRANSLATION_SERVICE);
        try {
            ContextCompat.startForegroundService(this, intent);
        } catch (RuntimeException e) {
            serviceStartFailed = true;
            Log.e(TAG, "Could not start same-package TranslationService", e);
            renderRuntimeSnapshot(runtimeSnapshot);
        }
    }

    private void acceptRuntimeSnapshot(
        SceneSyncRuntimeState.Snapshot changed
    ) {
        if (changed == null || isFinishing() || isDestroyed()) {
            return;
        }
        SceneSyncRuntimeState.Snapshot previous = runtimeSnapshot;
        runtimeSnapshot = changed;
        if (changed.serviceAvailable) {
            serviceStartFailed = false;
        }

        boolean manualAction = isManualAction(changed.lastAction);
        boolean terminal = manualAction
            && changed.lastOutcome != SceneSyncRuntimeState.Outcome.STARTED;
        boolean serviceUnavailableDuringPending =
            manualActionPending && !changed.serviceAvailable;
        boolean completedPendingAction = manualActionPending && terminal;
        boolean completedAcrossRecreation = previous != null
            && previous.phase == SceneSyncRuntimeState.Phase.MANUAL_APPLY
            && changed.phase != SceneSyncRuntimeState.Phase.MANUAL_APPLY
            && terminal;
        if (completedPendingAction || serviceUnavailableDuringPending) {
            manualActionPending = false;
        }

        renderRuntimeSnapshot(changed);
        adapter.notifyDataSetChanged();
        if (serviceUnavailableDuringPending) {
            showManualOutcome(
                SceneSyncRuntimeState.Outcome.UNAVAILABLE
            );
        } else if (completedPendingAction || completedAcrossRecreation) {
            showManualOutcome(changed.lastOutcome);
        }

        boolean phaseFinished = previous != null
            && previous.phase == SceneSyncRuntimeState.Phase.MANUAL_APPLY
            && changed.phase != SceneSyncRuntimeState.Phase.MANUAL_APPLY;
        boolean conflictCountChanged = previous != null
            && previous.pendingConflictCount != changed.pendingConflictCount;
        if (phaseFinished || conflictCountChanged) {
            requestConflictReload();
        }
    }

    private void renderRuntimeSnapshot(
        SceneSyncRuntimeState.Snapshot snapshot
    ) {
        String service;
        if (snapshot.serviceAvailable) {
            service = getString(R.string.scene_sync_service_ready);
        } else if (serviceStartFailed) {
            service = getString(R.string.scene_sync_service_unavailable);
        } else {
            service = getString(R.string.scene_sync_service_starting);
        }
        String game = getString(
            snapshot.gamePortAvailable
                ? R.string.scene_sync_game_online
                : R.string.scene_sync_game_offline
        );
        runtimeStatus.setText(getString(
            R.string.scene_conflicts_runtime_status,
            service,
            game,
            SceneSyncUiText.phaseLabel(this, snapshot.phase),
            snapshot.pendingConflictCount
        ));
    }

    private void requestConflictReload() {
        if (loading) {
            reloadRequested = true;
            return;
        }
        loading = true;
        reloadRequested = false;
        summary.setText(R.string.scene_conflicts_loading);
        emptyMessage.setText(R.string.scene_conflicts_loading);
        adapter.notifyDataSetChanged();
        int generation = ++loadGeneration;

        ioExecutor.execute(() -> {
            LoadResult result;
            try {
                result = loadConflicts();
            } catch (Exception e) {
                Log.e(TAG, "Could not enumerate complete Scene conflicts", e);
                result = LoadResult.failed();
            }
            LoadResult completed = result;
            runOnUiThread(() -> completeConflictReload(generation, completed));
        });
    }

    private LoadResult loadConflicts() throws Exception {
        // The Service owns recovery/normalization.  This Activity may hold a
        // different ConflictStore instance and must remain read-only so it
        // cannot delete an incoming or backup slot while a sync publishes it.
        List<String> sceneNames = conflictStore.listClaimedSceneNames();
        Log.i(
            TAG,
            "Conflict claims enumerated="
                + sceneNames.size()
        );
        ArrayList<ConflictRow> loaded = new ArrayList<>(sceneNames.size());
        for (String sceneName : sceneNames) {
            if (Thread.currentThread().isInterrupted()) {
                break;
            }
            try {
                ConflictStore.ConflictRecord record =
                    conflictStore.read(sceneName);
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
            } catch (Exception e) {
                Log.e(
                    TAG,
                    "Could not prepare conflict presentation scene="
                        + sceneName,
                    e
                );
                loaded.add(ConflictRow.invalid(sceneName));
            }
        }
        return LoadResult.success(loaded);
    }

    private void completeConflictReload(int generation, LoadResult result) {
        if (generation != loadGeneration || isFinishing() || isDestroyed()) {
            return;
        }
        loading = false;
        conflicts.clear();
        conflicts.addAll(result.rows);
        retainExpandedScenes();
        adapter.notifyDataSetChanged();

        if (result.failed) {
            summary.setText(R.string.scene_conflicts_load_failed);
            emptyMessage.setText(R.string.scene_conflicts_load_failed);
        } else {
            summary.setText(getString(
                R.string.scene_conflicts_count,
                conflicts.size()
            ));
            emptyMessage.setText(R.string.scene_conflicts_empty);
        }

        if (reloadRequested) {
            requestConflictReload();
        }
    }

    private void retainExpandedScenes() {
        HashSet<String> names = new HashSet<>();
        for (ConflictRow row : conflicts) {
            names.add(row.sceneName);
        }
        expandedScenes.retainAll(names);
    }

    private void toggleExpanded(String sceneName) {
        if (expandedScenes.contains(sceneName)) {
            expandedScenes.remove(sceneName);
        } else {
            expandedScenes.add(sceneName);
        }
        adapter.notifyDataSetChanged();
    }

    private void chooseCandidate(String sceneName, boolean chooseHet) {
        if (!canChooseCandidates()) {
            return;
        }
        manualActionPending = true;
        adapter.notifyDataSetChanged();
        if (chooseHet) {
            runtimeState.chooseHet(sceneName, false);
        } else {
            runtimeState.chooseGame(sceneName);
        }
    }

    private boolean canChooseCandidates() {
        return !loading
            && !manualActionPending
            && runtimeSnapshot.serviceAvailable
            && runtimeSnapshot.phase == SceneSyncRuntimeState.Phase.IDLE;
    }

    private static boolean isManualAction(
        SceneSyncRuntimeState.Action action
    ) {
        return action == SceneSyncRuntimeState.Action.CHOOSE_GAME
            || action == SceneSyncRuntimeState.Action.CHOOSE_HET;
    }

    private void showManualOutcome(SceneSyncRuntimeState.Outcome outcome) {
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

    private final class ConflictAdapter extends BaseAdapter {
        private final LayoutInflater inflater =
            LayoutInflater.from(SceneConflictsActivity.this);

        @Override
        public int getCount() {
            return conflicts.size();
        }

        @Override
        public ConflictRow getItem(int position) {
            return conflicts.get(position);
        }

        @Override
        public long getItemId(int position) {
            return position;
        }

        @Override
        public View getView(
            int position,
            View convertView,
            ViewGroup parent
        ) {
            ViewHolder holder;
            if (convertView == null) {
                convertView = inflater.inflate(
                    R.layout.item_scene_conflict,
                    parent,
                    false
                );
                holder = new ViewHolder(convertView);
                convertView.setTag(holder);
            } else {
                holder = (ViewHolder) convertView.getTag();
            }

            ConflictRow row = getItem(position);
            holder.name.setText(row.sceneName);
            if (row.presentation == null) {
                bindInvalidRow(holder);
            } else {
                bindValidRow(holder, row.presentation);
            }
            return convertView;
        }

        private void bindInvalidRow(ViewHolder holder) {
            holder.changes.setVisibility(View.GONE);
            holder.gameSummary.setVisibility(View.GONE);
            holder.hetSummary.setVisibility(View.GONE);
            holder.error.setVisibility(View.VISIBLE);
            holder.toggle.setVisibility(View.GONE);
            holder.details.setVisibility(View.GONE);
            holder.actions.setVisibility(View.GONE);
            holder.card.setClickable(false);
            holder.card.setOnClickListener(null);
        }

        private void bindValidRow(
            ViewHolder holder,
            SceneConflictPresentation presentation
        ) {
            holder.changes.setVisibility(View.VISIBLE);
            holder.gameSummary.setVisibility(View.VISIBLE);
            holder.hetSummary.setVisibility(View.VISIBLE);
            holder.error.setVisibility(View.GONE);
            holder.toggle.setVisibility(View.VISIBLE);
            holder.actions.setVisibility(View.VISIBLE);

            holder.changes.setText(getString(
                R.string.scene_conflict_changes,
                presentation.structureChangeCount,
                presentation.originalChangeCount,
                presentation.translationChangeCount
            ));
            holder.gameSummary.setText(formatSide(
                R.string.scene_conflict_game_side,
                presentation.game
            ));
            holder.hetSummary.setText(formatSide(
                R.string.scene_conflict_het_side,
                presentation.het
            ));

            boolean expanded = expandedScenes.contains(
                presentation.sceneName
            );
            holder.toggle.setText(
                expanded
                    ? R.string.scene_conflict_collapse
                    : R.string.scene_conflict_expand
            );
            holder.details.setVisibility(
                expanded ? View.VISIBLE : View.GONE
            );
            holder.details.setText(
                expanded ? formatAlignedTexts(presentation) : ""
            );
            View.OnClickListener toggle = view ->
                toggleExpanded(presentation.sceneName);
            holder.toggle.setOnClickListener(toggle);
            holder.card.setClickable(true);
            holder.card.setOnClickListener(toggle);

            boolean choicesEnabled = canChooseCandidates();
            holder.chooseGame.setEnabled(choicesEnabled);
            holder.chooseHet.setEnabled(choicesEnabled);
            holder.chooseGame.setOnClickListener(view ->
                chooseCandidate(presentation.sceneName, false)
            );
            holder.chooseHet.setOnClickListener(view ->
                chooseCandidate(presentation.sceneName, true)
            );
        }
    }

    private static final class ViewHolder {
        private final MaterialCardView card;
        private final TextView name;
        private final TextView changes;
        private final TextView gameSummary;
        private final TextView hetSummary;
        private final TextView error;
        private final MaterialButton toggle;
        private final TextView details;
        private final LinearLayout actions;
        private final MaterialButton chooseGame;
        private final MaterialButton chooseHet;

        private ViewHolder(View root) {
            card = root.findViewById(R.id.card_scene_conflict);
            name = root.findViewById(R.id.tv_scene_conflict_name);
            changes = root.findViewById(R.id.tv_scene_conflict_changes);
            gameSummary = root.findViewById(
                R.id.tv_scene_conflict_game_summary
            );
            hetSummary = root.findViewById(
                R.id.tv_scene_conflict_het_summary
            );
            error = root.findViewById(R.id.tv_scene_conflict_error);
            toggle = root.findViewById(R.id.btn_scene_conflict_toggle);
            details = root.findViewById(R.id.tv_scene_conflict_details);
            actions = root.findViewById(
                R.id.layout_scene_conflict_actions
            );
            chooseGame = root.findViewById(
                R.id.btn_scene_conflict_choose_game
            );
            chooseHet = root.findViewById(
                R.id.btn_scene_conflict_choose_het
            );
        }
    }

    private static final class ConflictRow {
        private final String sceneName;
        private final SceneConflictPresentation presentation;

        private ConflictRow(
            String sceneName,
            SceneConflictPresentation presentation
        ) {
            this.sceneName = sceneName;
            this.presentation = presentation;
        }

        private static ConflictRow valid(
            SceneConflictPresentation presentation
        ) {
            return new ConflictRow(presentation.sceneName, presentation);
        }

        private static ConflictRow invalid(String sceneName) {
            return new ConflictRow(sceneName, null);
        }
    }

    private static final class LoadResult {
        private final List<ConflictRow> rows;
        private final boolean failed;

        private LoadResult(List<ConflictRow> rows, boolean failed) {
            this.rows = rows;
            this.failed = failed;
        }

        private static LoadResult success(List<ConflictRow> rows) {
            return new LoadResult(rows, false);
        }

        private static LoadResult failed() {
            return new LoadResult(new ArrayList<>(), true);
        }
    }
}
