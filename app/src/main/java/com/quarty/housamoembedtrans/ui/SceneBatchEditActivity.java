package com.quarty.housamoembedtrans.ui;

import com.quarty.housamoembedtrans.R;
import com.quarty.housamoembedtrans.storage.ConfigStore;
import com.quarty.housamoembedtrans.storage.GroupContextEntry;
import com.quarty.housamoembedtrans.storage.SceneContextStore;
import com.quarty.housamoembedtrans.storage.SceneStore;
import com.quarty.housamoembedtrans.storage.SummaryJobStore;
import com.quarty.housamoembedtrans.storage.SummaryJobWakeup;
import com.quarty.housamoembedtrans.translation.ContextCompressionCoordinator;
import com.quarty.housamoembedtrans.translation.ContextReviewCoordinator;
import com.quarty.housamoembedtrans.translation.GroupCompressionCoordinator;
import com.quarty.housamoembedtrans.translation.SceneBatchPlanner;
import com.quarty.housamoembedtrans.translation.SceneTranslationRequestBuilder;
import com.quarty.housamoembedtrans.translation.TranslationJobStore;

import android.os.Bundle;
import android.text.TextUtils;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.CheckBox;
import android.widget.LinearLayout;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

import com.google.android.material.appbar.MaterialToolbar;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Atomic editor for all Context-owned Scene membership lists and optional new
 * Translation Jobs. A Scene may independently belong to any number of
 * Contexts; a Job's single History Mapping is edited in a separate section.
 */
public final class SceneBatchEditActivity extends AppCompatActivity {

    private final ExecutorService ioExecutor = Executors.newSingleThreadExecutor();

    private SceneStore sceneStore;
    private SceneContextStore sceneContextStore;
    private TranslationJobStore translationJobStore;
    private ContextReviewCoordinator contextReviewCoordinator;

    private SceneBatchPlanner.Plan plan;
    private LinearLayout sceneContainer;
    private TextView resultView;
    private MaterialButton commitButton;
    private boolean busy;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_scene_batch_edit);
        SystemBarInsets.apply(findViewById(R.id.root_scene_batch_edit));
        MaterialToolbar toolbar = findViewById(R.id.toolbar_scene_batch_edit);
        toolbar.setNavigationOnClickListener(view -> onBackPressed());

        sceneStore = new SceneStore(this);
        sceneContextStore = new SceneContextStore(this);
        translationJobStore = TranslationJobStore.getInstance(this);
        SummaryJobStore summaryJobStore = SummaryJobStore.createForAndroid(this);
        ContextCompressionCoordinator contextCoordinator =
            new ContextCompressionCoordinator(sceneContextStore, summaryJobStore);
        GroupCompressionCoordinator groupCoordinator =
            new GroupCompressionCoordinator(
                sceneContextStore,
                summaryJobStore,
                contextCoordinator
            );
        contextReviewCoordinator = new ContextReviewCoordinator(
            sceneContextStore,
            translationJobStore,
            summaryJobStore,
            contextCoordinator,
            groupCoordinator
        );

        sceneContainer = findViewById(R.id.container_scene_batch);
        resultView = findViewById(R.id.tv_scene_batch_result);
        commitButton = findViewById(R.id.btn_scene_batch_commit);
        commitButton.setOnClickListener(view -> confirmCommit());
        refreshAsync();
    }

    @Override
    public void onBackPressed() {
        if (!busy) {
            super.onBackPressed();
        }
    }

    @Override
    protected void onDestroy() {
        ioExecutor.shutdownNow();
        super.onDestroy();
    }

    private void refreshAsync() {
        setBusy(true);
        ioExecutor.execute(() -> {
            try {
                List<String> sceneNames = sceneStore.listValidSceneNames();
                List<SceneBatchPlanner.ContextDraft> contexts =
                    loadContextDrafts();
                List<SceneBatchPlanner.GroupSnapshot> groups =
                    loadGroupSnapshots();
                List<SceneBatchPlanner.JobSnapshot> jobs = loadJobs();
                SceneBatchPlanner.Plan loaded = SceneBatchPlanner
                    .createInitialPlan(sceneNames, contexts, groups, jobs);
                runOnUiThread(() -> {
                    if (isFinishing() || isDestroyed()) {
                        return;
                    }
                    plan = loaded;
                    setBusy(false);
                });
            } catch (Exception e) {
                runOnUiThread(() -> {
                    if (isFinishing() || isDestroyed()) {
                        return;
                    }
                    setBusy(false);
                    showResult(getString(
                        R.string.scene_batch_edit_load_failed,
                        safeMessage(e)
                    ));
                });
            }
        });
    }

    private List<SceneBatchPlanner.ContextDraft> loadContextDrafts()
        throws Exception {
        List<SceneBatchPlanner.ContextDraft> result = new ArrayList<>();
        for (JSONObject context : sceneContextStore.listContexts()) {
            List<String> scenes = new ArrayList<>();
            JSONArray array = context.optJSONArray("scenes");
            if (array != null) {
                for (int index = 0; index < array.length(); index++) {
                    JSONObject entry = array.optJSONObject(index);
                    if (entry != null) {
                        scenes.add(entry.optString("scene", ""));
                    }
                }
            }
            result.add(new SceneBatchPlanner.ContextDraft(
                context.optString("id", ""),
                context.optString("display_name", ""),
                context.optLong("revision", -1L),
                scenes
            ));
        }
        return result;
    }

    private List<SceneBatchPlanner.GroupSnapshot> loadGroupSnapshots()
        throws Exception {
        List<SceneBatchPlanner.GroupSnapshot> result = new ArrayList<>();
        for (JSONObject group : sceneContextStore.listGroups()) {
            List<String> contextIds = new ArrayList<>();
            JSONArray array = group.optJSONArray("contexts");
            if (array != null) {
                for (int index = 0; index < array.length(); index++) {
                    contextIds.add(GroupContextEntry.contextIdAt(array, index));
                }
            }
            result.add(new SceneBatchPlanner.GroupSnapshot(
                group.optString("id", ""),
                group.optString("display_name", ""),
                contextIds
            ));
        }
        return result;
    }

    private List<SceneBatchPlanner.JobSnapshot> loadJobs() throws Exception {
        List<SceneBatchPlanner.JobSnapshot> jobs = new ArrayList<>();
        for (TranslationJobStore.ReviewJob job
            : translationJobStore.listReviewJobs()) {
            jobs.add(new SceneBatchPlanner.JobSnapshot(
                job.getRequestId(),
                job.getScene(),
                job.getStatus(),
                job.getContextId(),
                job.getGroupId()
            ));
        }
        return jobs;
    }

    private void renderContent() {
        sceneContainer.removeAllViews();
        if (plan == null) {
            return;
        }
        normalizeHistoryRoutes();
        sceneContainer.addView(sectionTitle(
            R.string.scene_batch_edit_context_membership_section
        ));
        if (plan.contexts.isEmpty()) {
            sceneContainer.addView(bodyText(
                R.string.scene_batch_edit_no_contexts
            ));
        } else {
            for (SceneBatchPlanner.ContextDraft context : plan.contexts) {
                renderContext(context);
            }
        }

        sceneContainer.addView(sectionTitle(
            R.string.scene_batch_edit_api_section
        ));
        if (plan.scenes.isEmpty()) {
            sceneContainer.addView(bodyText(R.string.scene_batch_edit_empty));
        } else {
            for (SceneBatchPlanner.SceneDraft draft : plan.scenes) {
                ApiRowHolder holder = new ApiRowHolder(
                    draft,
                    existingJobStatus(draft.getScene())
                );
                sceneContainer.addView(holder.root);
                rebuildApiRow(holder);
            }
        }
    }

    private void renderContext(SceneBatchPlanner.ContextDraft context) {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(0, 16, 0, 16);

        TextView title = new TextView(this);
        title.setText(getString(
            R.string.scene_batch_edit_context_heading,
            context.displayName
        ));
        title.setTextSize(17);
        card.addView(title);

        List<String> members = context.getScenes();
        if (members.isEmpty()) {
            card.addView(bodyText(R.string.scene_batch_edit_context_empty));
        }
        for (int index = 0; index < members.size(); index++) {
            final int position = index;
            String scene = members.get(index);
            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setPadding(0, 6, 0, 6);

            TextView name = new TextView(this);
            name.setText(scene);
            name.setLayoutParams(new LinearLayout.LayoutParams(
                0,
                LinearLayout.LayoutParams.WRAP_CONTENT,
                1f
            ));
            row.addView(name);

            MaterialButton up = smallButton(R.string.scene_batch_edit_move_up);
            up.setEnabled(index > 0);
            up.setOnClickListener(view -> {
                if (!busy && context.moveScene(position, position - 1)) {
                    renderContent();
                }
            });
            row.addView(up);

            MaterialButton down = smallButton(
                R.string.scene_batch_edit_move_down
            );
            down.setEnabled(index + 1 < members.size());
            down.setOnClickListener(view -> {
                if (!busy && context.moveScene(position, position + 1)) {
                    renderContent();
                }
            });
            row.addView(down);

            MaterialButton remove = smallButton(
                R.string.scene_batch_edit_remove_scene
            );
            remove.setOnClickListener(view -> {
                if (!busy && context.removeScene(scene)) {
                    normalizeHistoryRoutes();
                    renderContent();
                }
            });
            row.addView(remove);
            card.addView(row);
        }

        List<String> available = new ArrayList<>();
        for (String scene : plan.localScenes) {
            if (!context.contains(scene)) {
                available.add(scene);
            }
        }
        LinearLayout addRow = new LinearLayout(this);
        addRow.setOrientation(LinearLayout.HORIZONTAL);
        Spinner spinner = new Spinner(this);
        ArrayAdapter<String> adapter = new ArrayAdapter<>(
            this,
            android.R.layout.simple_spinner_item,
            available
        );
        adapter.setDropDownViewResource(
            android.R.layout.simple_spinner_dropdown_item
        );
        spinner.setAdapter(adapter);
        spinner.setLayoutParams(new LinearLayout.LayoutParams(
            0,
            LinearLayout.LayoutParams.WRAP_CONTENT,
            1f
        ));
        spinner.setEnabled(!available.isEmpty());
        addRow.addView(spinner);
        MaterialButton add = smallButton(R.string.scene_batch_edit_add_scene);
        add.setEnabled(!available.isEmpty());
        add.setOnClickListener(view -> {
            Object selected = spinner.getSelectedItem();
            if (!busy
                && selected != null
                && context.addScene(selected.toString())) {
                renderContent();
            }
        });
        addRow.addView(add);
        card.addView(addRow);
        sceneContainer.addView(card);
    }

    private void normalizeHistoryRoutes() {
        for (SceneBatchPlanner.SceneDraft draft : plan.scenes) {
            SceneBatchPlanner.ContextDraft context = contextById(
                draft.getHistoryContextId()
            );
            if (context == null || !context.contains(draft.getScene())) {
                draft.setHistoryContextId(null);
                draft.setHistoryGroupId(null);
                continue;
            }
            if (!groupContains(
                draft.getHistoryGroupId(),
                draft.getHistoryContextId()
            )) {
                draft.setHistoryGroupId(null);
            }
        }
    }

    private List<IdLabel> contextChoices(String scene) {
        List<IdLabel> result = new ArrayList<>();
        result.add(new IdLabel(
            null,
            getString(R.string.scene_batch_edit_no_history)
        ));
        for (SceneBatchPlanner.ContextDraft context : plan.contexts) {
            if (context.contains(scene)) {
                result.add(new IdLabel(context.id, context.displayName));
            }
        }
        return result;
    }

    private List<IdLabel> groupChoices(String contextId) {
        List<IdLabel> result = new ArrayList<>();
        result.add(new IdLabel(
            null,
            getString(R.string.scene_batch_edit_no_group)
        ));
        if (contextId == null) {
            return result;
        }
        for (SceneBatchPlanner.GroupSnapshot group : plan.groups) {
            if (group.contextIds.contains(contextId)) {
                result.add(new IdLabel(group.id, group.displayName));
            }
        }
        return result;
    }

    private void rebuildApiRow(ApiRowHolder holder) {
        holder.updating = true;
        try {
            holder.contextChoices = contextChoices(holder.draft.getScene());
            holder.contextSpinner.setAdapter(idAdapter(holder.contextChoices));
            holder.contextSpinner.setSelection(indexOfId(
                holder.contextChoices,
                holder.draft.getHistoryContextId()
            ));

            holder.groupChoices = groupChoices(
                holder.draft.getHistoryContextId()
            );
            holder.groupSpinner.setAdapter(idAdapter(holder.groupChoices));
            holder.groupSpinner.setSelection(indexOfId(
                holder.groupChoices,
                holder.draft.getHistoryGroupId()
            ));
            holder.groupSpinner.setEnabled(
                holder.draft.getHistoryContextId() != null
            );

            boolean occupied = SceneBatchPlanner.hasExistingJob(
                holder.draft.getScene(),
                plan.jobs
            );
            if (occupied) {
                holder.draft.setSendApi(false);
            }
            holder.sendApiCheck.setChecked(holder.draft.isSendApi());
            holder.sendApiCheck.setEnabled(!occupied);
        } finally {
            holder.updating = false;
        }
    }

    private ArrayAdapter<IdLabel> idAdapter(List<IdLabel> values) {
        ArrayAdapter<IdLabel> adapter = new ArrayAdapter<>(
            this,
            android.R.layout.simple_spinner_item,
            values
        );
        adapter.setDropDownViewResource(
            android.R.layout.simple_spinner_dropdown_item
        );
        return adapter;
    }

    private void confirmCommit() {
        if (busy || plan == null) {
            return;
        }
        List<String> errors = SceneBatchPlanner.validate(plan);
        if (!errors.isEmpty()) {
            Toast.makeText(
                this,
                getString(
                    R.string.scene_batch_edit_invalid_selection,
                    errors.get(0)
                ),
                Toast.LENGTH_LONG
            ).show();
            return;
        }

        setBusy(true);
        ioExecutor.execute(() -> {
            try {
                String targetLanguage = defaultTargetLanguage();
                SceneBatchPlanner.CommitPlan commit = SceneBatchPlanner
                    .planCommit(
                        plan,
                        scene -> SceneTranslationRequestBuilder.buildRequest(
                            loadSceneDocument(scene),
                            targetLanguage
                        )
                    );
                List<String> changedContextIds = new ArrayList<>();
                for (SceneBatchPlanner.ContextEdit edit : commit.contextEdits) {
                    changedContextIds.add(edit.contextId);
                }
                ContextReviewCoordinator.EditRisk risk =
                    contextReviewCoordinator.assessSceneBatch(
                        changedContextIds
                    );
                runOnUiThread(() -> showManualRiskOrConfirm(commit, risk));
            } catch (Exception e) {
                runOnUiThread(() -> {
                    if (isFinishing() || isDestroyed()) {
                        return;
                    }
                    setBusy(false);
                    showResult(getString(
                        R.string.scene_batch_edit_commit_failed,
                        safeMessage(e)
                    ));
                });
            }
        });
    }

    private void showManualRiskOrConfirm(
        SceneBatchPlanner.CommitPlan commit,
        ContextReviewCoordinator.EditRisk risk
    ) {
        if (isFinishing() || isDestroyed()) {
            return;
        }
        int unsent = risk.userRequestedUnsentIds.size();
        int running = risk.userRequestedRunningIds.size();
        if (unsent == 0 && running == 0) {
            showFinalConfirmation(commit, risk);
            return;
        }
        int positive = unsent > 0
            ? R.string.scene_batch_edit_discard_summary_continue
            : R.string.scene_batch_edit_continue_running_summary;
        new MaterialAlertDialogBuilder(this)
            .setTitle(R.string.scene_batch_edit_manual_summary_title)
            .setMessage(getString(
                R.string.scene_batch_edit_manual_summary_message,
                unsent,
                running
            ))
            .setNegativeButton(
                R.string.scene_batch_edit_cancel_edit,
                (dialog, which) -> setBusy(false)
            )
            .setPositiveButton(
                positive,
                (dialog, which) -> showFinalConfirmation(commit, risk)
            )
            .setOnCancelListener(dialog -> setBusy(false))
            .show();
    }

    private void showFinalConfirmation(
        SceneBatchPlanner.CommitPlan commit,
        ContextReviewCoordinator.EditRisk risk
    ) {
        new MaterialAlertDialogBuilder(this)
            .setTitle(R.string.scene_batch_edit_commit_confirm_title)
            .setMessage(getString(
                R.string.scene_batch_edit_commit_confirm_message,
                commit.contextEdits.size(),
                commit.jobCreations.size(),
                commit.mappingRewrites.size(),
                risk.affectedWork
            ))
            .setNegativeButton(
                R.string.cancel_action,
                (dialog, which) -> setBusy(false)
            )
            .setPositiveButton(
                R.string.scene_batch_edit_commit,
                (dialog, which) -> executeCommit(commit, risk)
            )
            .setOnCancelListener(dialog -> setBusy(false))
            .show();
    }

    private void executeCommit(
        SceneBatchPlanner.CommitPlan commit,
        ContextReviewCoordinator.EditRisk risk
    ) {
        ioExecutor.execute(() -> {
            try {
                ContextReviewCoordinator.BatchSaveResult result =
                    contextReviewCoordinator.saveSceneBatch(
                        commit,
                        loadOptions(),
                        risk,
                        !risk.userRequestedUnsentIds.isEmpty()
                    );
                if (result.jobsCreated > 0) {
                    SummaryJobWakeup.signal(this);
                }
                runOnUiThread(() -> {
                    if (isFinishing() || isDestroyed()) {
                        return;
                    }
                    showResult(getString(
                        R.string.scene_batch_edit_commit_result,
                        result.contextsUpdated,
                        result.jobsCreated,
                        result.jobsSkipped,
                        result.mappingsRewritten,
                        result.userRequestedJobsCanceled,
                        result.jobsAwaitingRecovery
                    ));
                    refreshAsync();
                });
            } catch (Exception e) {
                runOnUiThread(() -> {
                    if (isFinishing() || isDestroyed()) {
                        return;
                    }
                    String message = e instanceof
                        ContextReviewCoordinator.ConcurrentEditException
                            ? getString(
                                R.string.scene_batch_edit_concurrent_changed
                            )
                            : getString(
                                R.string.scene_batch_edit_commit_failed,
                                safeMessage(e)
                            );
                    showResult(message);
                    refreshAsync();
                });
            }
        });
    }

    private JSONObject loadSceneDocument(String scene) throws Exception {
        SceneStore.ValidatedScene validated = sceneStore.readValidSceneByName(
            scene
        );
        if (validated == null) {
            throw new IllegalArgumentException(
                "Local scene file is missing: " + scene
            );
        }
        return new JSONObject(new String(
            validated.bytes,
            java.nio.charset.StandardCharsets.UTF_8
        ));
    }

    private ContextReviewCoordinator.Options loadOptions() throws Exception {
        JSONObject userSettings = new ConfigStore(this)
            .load()
            .config
            .getJSONObject("UserSettings");
        JSONObject contextHistory = userSettings.optJSONObject(
            "ContextHistory"
        );
        ContextReviewCoordinator.Options options =
            new ContextReviewCoordinator.Options();
        options.autoCompression = contextHistory != null
            && contextHistory.optBoolean("EnableAutoCompression", false);
        options.continueAfterManual = contextHistory != null
            && contextHistory.optBoolean(
                "ContinueAutoSummaryAfterManual",
                false
            );
        return options;
    }

    private String defaultTargetLanguage() {
        try {
            return new ConfigStore(this)
                .load()
                .config
                .getJSONObject("UserSettings")
                .optString("TargetLanguage", "zh-cn");
        } catch (Exception e) {
            return "zh-cn";
        }
    }

    private SceneBatchPlanner.ContextDraft contextById(String contextId) {
        if (contextId == null) {
            return null;
        }
        for (SceneBatchPlanner.ContextDraft context : plan.contexts) {
            if (context.id.equals(contextId)) {
                return context;
            }
        }
        return null;
    }

    private boolean groupContains(String groupId, String contextId) {
        if (groupId == null || contextId == null) {
            return false;
        }
        for (SceneBatchPlanner.GroupSnapshot group : plan.groups) {
            if (group.id.equals(groupId)) {
                return group.contextIds.contains(contextId);
            }
        }
        return false;
    }

    private String existingJobStatus(String scene) {
        String fallback = null;
        for (SceneBatchPlanner.JobSnapshot job : plan.jobs) {
            if (!scene.equals(job.scene)) {
                continue;
            }
            if (isActiveOrCompleted(job.status)) {
                return job.status;
            }
            if (fallback == null) {
                fallback = job.status;
            }
        }
        return fallback;
    }

    private TextView sectionTitle(int textRes) {
        TextView title = new TextView(this);
        title.setText(textRes);
        title.setTextSize(20);
        title.setPadding(0, 24, 0, 8);
        return title;
    }

    private TextView bodyText(int textRes) {
        TextView text = new TextView(this);
        text.setText(textRes);
        text.setTextSize(14);
        text.setPadding(0, 8, 0, 8);
        return text;
    }

    private MaterialButton smallButton(int textRes) {
        MaterialButton button = new MaterialButton(this);
        button.setText(textRes);
        button.setTextSize(12);
        button.setMinWidth(0);
        button.setMinimumWidth(0);
        return button;
    }

    private static TextView fieldLabel(
        SceneBatchEditActivity activity,
        int textRes
    ) {
        TextView label = new TextView(activity);
        label.setText(textRes);
        label.setTextSize(12);
        label.setPadding(0, 8, 0, 2);
        return label;
    }

    private static int indexOfId(List<IdLabel> values, String id) {
        for (int index = 0; index < values.size(); index++) {
            if (sameNullable(values.get(index).id, id)) {
                return index;
            }
        }
        return 0;
    }

    private void setBusy(boolean busy) {
        this.busy = busy;
        commitButton.setEnabled(!busy);
        setEnabledRecursive(sceneContainer, !busy);
        if (!busy && plan != null) {
            renderContent();
        }
    }

    private static void setEnabledRecursive(View view, boolean enabled) {
        view.setEnabled(enabled);
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int index = 0; index < group.getChildCount(); index++) {
                setEnabledRecursive(group.getChildAt(index), enabled);
            }
        }
    }

    private void showResult(String message) {
        resultView.setText(message);
        Toast.makeText(this, message, Toast.LENGTH_LONG).show();
    }

    private static String safeMessage(Throwable throwable) {
        String message = throwable.getMessage();
        return TextUtils.isEmpty(message)
            ? throwable.getClass().getSimpleName()
            : message;
    }

    private static boolean sameNullable(String left, String right) {
        return left == null ? right == null : left.equals(right);
    }

    private static boolean isActiveOrCompleted(String status) {
        return "queued".equals(status)
            || "running".equals(status)
            || "completed".equals(status);
    }

    private static final class IdLabel {
        final String id;
        final String label;

        IdLabel(String id, String label) {
            this.id = id;
            this.label = label;
        }

        @Override
        public String toString() {
            return label;
        }
    }

    private final class ApiRowHolder {
        final LinearLayout root;
        final SceneBatchPlanner.SceneDraft draft;
        final Spinner contextSpinner;
        final Spinner groupSpinner;
        final CheckBox sendApiCheck;
        List<IdLabel> contextChoices = Collections.emptyList();
        List<IdLabel> groupChoices = Collections.emptyList();
        boolean updating;

        ApiRowHolder(
            SceneBatchPlanner.SceneDraft draft,
            String existingStatus
        ) {
            this.draft = draft;
            root = new LinearLayout(SceneBatchEditActivity.this);
            root.setOrientation(LinearLayout.VERTICAL);
            root.setPadding(0, 12, 0, 12);

            TextView sceneName = new TextView(SceneBatchEditActivity.this);
            sceneName.setText(draft.getScene());
            sceneName.setTextSize(16);
            root.addView(sceneName);

            if (existingStatus != null && !existingStatus.isEmpty()) {
                TextView status = new TextView(SceneBatchEditActivity.this);
                status.setTextSize(12);
                status.setText(isActiveOrCompleted(existingStatus)
                    ? getString(
                        R.string.scene_batch_edit_existing_job,
                        existingStatus
                    )
                    : getString(
                        R.string.scene_batch_edit_existing_other_job,
                        existingStatus
                    ));
                root.addView(status);
            }

            root.addView(fieldLabel(
                SceneBatchEditActivity.this,
                R.string.scene_batch_edit_history_context
            ));
            contextSpinner = new Spinner(SceneBatchEditActivity.this);
            root.addView(contextSpinner);

            root.addView(fieldLabel(
                SceneBatchEditActivity.this,
                R.string.scene_batch_edit_history_group
            ));
            groupSpinner = new Spinner(SceneBatchEditActivity.this);
            root.addView(groupSpinner);

            sendApiCheck = new CheckBox(SceneBatchEditActivity.this);
            sendApiCheck.setText(R.string.scene_batch_edit_send_api);
            root.addView(sendApiCheck);

            contextSpinner.setOnItemSelectedListener(
                new AdapterView.OnItemSelectedListener() {
                    @Override
                    public void onItemSelected(
                        AdapterView<?> parent,
                        View view,
                        int position,
                        long id
                    ) {
                        if (updating || position < 0
                            || position >= contextChoices.size()) {
                            return;
                        }
                        String selectedId = contextChoices.get(position).id;
                        if (!sameNullable(
                            selectedId,
                            ApiRowHolder.this.draft.getHistoryContextId()
                        )) {
                            ApiRowHolder.this.draft.setHistoryContextId(
                                selectedId
                            );
                            ApiRowHolder.this.draft.setHistoryGroupId(null);
                        }
                        rebuildApiRow(ApiRowHolder.this);
                    }

                    @Override
                    public void onNothingSelected(AdapterView<?> parent) {
                        if (!updating) {
                            ApiRowHolder.this.draft.setHistoryContextId(null);
                            ApiRowHolder.this.draft.setHistoryGroupId(null);
                            rebuildApiRow(ApiRowHolder.this);
                        }
                    }
                }
            );

            groupSpinner.setOnItemSelectedListener(
                new AdapterView.OnItemSelectedListener() {
                    @Override
                    public void onItemSelected(
                        AdapterView<?> parent,
                        View view,
                        int position,
                        long id
                    ) {
                        if (!updating && position >= 0
                            && position < groupChoices.size()) {
                            ApiRowHolder.this.draft.setHistoryGroupId(
                                groupChoices.get(position).id
                            );
                        }
                    }

                    @Override
                    public void onNothingSelected(AdapterView<?> parent) {
                        if (!updating) {
                            ApiRowHolder.this.draft.setHistoryGroupId(null);
                        }
                    }
                }
            );

            sendApiCheck.setOnCheckedChangeListener(
                (button, checked) -> {
                    if (!updating) {
                        ApiRowHolder.this.draft.setSendApi(checked);
                    }
                }
            );
        }
    }
}
