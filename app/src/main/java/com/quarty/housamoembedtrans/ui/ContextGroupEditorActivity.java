package com.quarty.housamoembedtrans.ui;

import com.google.android.material.appbar.MaterialToolbar;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.card.MaterialCardView;
import com.google.android.material.checkbox.MaterialCheckBox;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;

import com.quarty.housamoembedtrans.R;
import com.quarty.housamoembedtrans.context.model.GroupContextEntry;
import com.quarty.housamoembedtrans.context.review.ContextReviewCoordinator;
import com.quarty.housamoembedtrans.context.store.SceneContextStore;
import com.quarty.housamoembedtrans.scene.store.SceneStore;
import com.quarty.housamoembedtrans.storage.config.ConfigStore;
import com.quarty.housamoembedtrans.summary.job.SummaryJobStore;
import com.quarty.housamoembedtrans.summary.policy.ContextCompressionCoordinator;
import com.quarty.housamoembedtrans.summary.policy.GroupCompressionCoordinator;
import com.quarty.housamoembedtrans.translation.job.TranslationJobStore;

import android.os.Bundle;
import android.text.Editable;
import android.text.TextUtils;
import android.text.TextWatcher;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.OnBackPressedCallback;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Full-page Context/Group editor and manual-closure surface. */
public final class ContextGroupEditorActivity extends AppCompatActivity {
    public static final String EXTRA_KIND =
        "com.quarty.housamoembedtrans.ui.EXTRA_CONTEXT_GROUP_EDITOR_KIND";
    public static final String EXTRA_ID =
        "com.quarty.housamoembedtrans.ui.EXTRA_CONTEXT_GROUP_EDITOR_ID";
    public static final String EXTRA_CREATE_KIND =
        "com.quarty.housamoembedtrans.ui.EXTRA_CONTEXT_GROUP_EDITOR_CREATE_KIND";

    private static final String KIND_CONTEXT = "context";
    private static final String KIND_GROUP = "group";
    private static final String STATE_NAME = "context_group_editor.name";
    private static final String STATE_SELECTED_MEMBERS =
        "context_group_editor.selected_members";
    private static final String STATE_SELECTED_GROUPS =
        "context_group_editor.selected_groups";
    private static final String STATE_MANUAL_LANGUAGES =
        "context_group_editor.manual_languages";
    private static final String STATE_DESCRIPTION_LANGUAGES =
        "context_group_editor.description_languages";
    private static final String STATE_MANUAL_DESCRIPTIONS =
        "context_group_editor.manual_descriptions";
    private static final String STATE_MANUAL_SUMMARIES =
        "context_group_editor.manual_summaries";
    private static final String STATE_CLOSURE_LANGUAGE =
        "context_group_editor.closure_language";
    private static final String STATE_DIRTY = "context_group_editor.dirty";

    private final ExecutorService ioExecutor = Executors.newSingleThreadExecutor();
    private final List<String> selectedMemberIds = new ArrayList<>();
    private final Map<String, MaterialCheckBox> memberChecks = new LinkedHashMap<>();
    private final List<String> selectedGroupIds = new ArrayList<>();
    private final Map<String, MaterialCheckBox> groupChecks = new LinkedHashMap<>();
    private final Map<String, EditText> descriptionInputs = new LinkedHashMap<>();
    private final Map<String, ManualRow> manualRows = new LinkedHashMap<>();

    private MaterialToolbar toolbar;
    private LinearLayout content;
    private LinearLayout pageActions;
    private TextView status;
    private String kind;
    private String objectId;
    private boolean creating;
    private JSONObject draft;
    private JSONObject initialDraft;
    private List<JSONObject> contexts = new ArrayList<>();
    private List<JSONObject> groups = new ArrayList<>();
    private List<String> sceneNames = new ArrayList<>();
    private SceneContextStore store;
    private SceneStore sceneStore;
    private SummaryJobStore summaryJobs;
    private ContextCompressionCoordinator contextCompression;
    private GroupCompressionCoordinator groupCompression;
    private ContextReviewCoordinator reviewCoordinator;
    private LinearLayout memberOrder;
    private LinearLayout groupOrder;
    private LinearLayout descriptionRowsContainer;
    private EditText descriptionLanguageInput;
    private LinearLayout manualRowsContainer;
    private TextView manualEmpty;
    private MaterialButton addManualSummaryButton;
    private TextView memberHeaderCount;
    private TextView groupHeaderCount;
    private EditText nameInput;
    private EditText closureLanguageInput;
    private TextView closureStatus;
    private MaterialButton closureAction;
    private MaterialButton closureReopen;
    private SceneContextStore.ManualClosureState closureState;
    private boolean loading;
    private boolean saving;
    private boolean dirty;
    private boolean suppressDirty;
    private boolean stylePreview;
    private Bundle pendingRestoreState;

    private static final class ManualRow {
        final String language;
        final EditText summary;

        ManualRow(String language, EditText summary) {
            this.language = language;
            this.summary = summary;
        }
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        pendingRestoreState = savedInstanceState;
        setContentView(R.layout.activity_context_group_editor);
        SystemBarInsets.apply(findViewById(R.id.root_context_group_editor));
        toolbar = findViewById(R.id.toolbar_context_group_editor);
        content = findViewById(R.id.container_context_group_editor);
        pageActions = findViewById(R.id.page_actions);
        status = new TextView(this);
        status.setTextAppearance(this, R.style.TextAppearance_HET_StaticDetail_Status);
        content.addView(status, matchParams());
        toolbar.setNavigationOnClickListener(
            view -> getOnBackPressedDispatcher().onBackPressed()
        );
        getOnBackPressedDispatcher().addCallback(this, new OnBackPressedCallback(true) {
            @Override
            public void handleOnBackPressed() {
                if (stylePreview) {
                    setEnabled(false);
                    finish();
                    return;
                }
                if (!dirty || saving) {
                    setEnabled(false);
                    getOnBackPressedDispatcher().onBackPressed();
                } else {
                    confirmDiscard();
                }
            }
        });

        kind = getIntent() == null ? null : getIntent().getStringExtra(EXTRA_KIND);
        objectId = getIntent() == null ? null : getIntent().getStringExtra(EXTRA_ID);
        String createKind = getIntent() == null
            ? null
            : getIntent().getStringExtra(EXTRA_CREATE_KIND);
        if (TextUtils.isEmpty(kind)) kind = createKind;
        creating = TextUtils.isEmpty(objectId);
        if (!KIND_CONTEXT.equals(kind) && !KIND_GROUP.equals(kind)) {
            showError(getString(R.string.context_group_editor_missing));
            return;
        }
        stylePreview = StylePreview.isEnabled(this);
        toolbar.setTitle(editorTitle());

        if (stylePreview) {
            loadStylePreview();
            return;
        }

        sceneStore = new SceneStore(this);
        store = new SceneContextStore(this);
        summaryJobs = SummaryJobStore.createForAndroid(this);
        contextCompression = new ContextCompressionCoordinator(store, summaryJobs);
        groupCompression = new GroupCompressionCoordinator(
            store,
            summaryJobs,
            contextCompression
        );
        reviewCoordinator = new ContextReviewCoordinator(
            store,
            TranslationJobStore.getInstance(this),
            summaryJobs,
            contextCompression,
            groupCompression
        );
        load();
    }

    /** Loads only the Intent payload; preview must never construct a durable store. */
    private void loadStylePreview() {
        try {
            JSONObject loaded = StylePreview.payloadOf(getIntent());
            if (loaded == null) {
                loaded = StylePreview.sample(KIND_CONTEXT.equals(kind)
                    ? StylePreview.KIND_CONTEXT_EDITOR
                    : StylePreview.KIND_GROUP_EDITOR);
            }
            draft = copyJson(loaded);
            if (TextUtils.isEmpty(draft.optString("id", ""))) {
                draft.put("id", objectId);
            }
            initialDraft = copyJson(draft);
            contexts = new ArrayList<>();
            groups = new ArrayList<>();
            sceneNames = new ArrayList<>();
            if (KIND_CONTEXT.equals(kind)) {
                contexts.add(copyJson(draft));
                groups.add(StylePreview.sample(StylePreview.KIND_GROUP_EDITOR));
            } else {
                contexts.add(StylePreview.sample(StylePreview.KIND_CONTEXT_EDITOR));
                groups.add(copyJson(draft));
            }
            sceneNames.add(StylePreview.SAMPLE_SCENE_NAME);
            dirty = false;
            render();
            restoreEditorState(pendingRestoreState);
            pendingRestoreState = null;
            applyPreviewReadOnly(content);
            applyPreviewReadOnly(pageActions);
        } catch (Exception error) {
            showError(getString(
                R.string.context_group_editor_failed,
                safeMessage(error)
            ));
        }
    }

    private void load() {
        if (loading) return;
        loading = true;
        status.setVisibility(View.VISIBLE);
        status.setText(R.string.context_group_editor_loading);
        ioExecutor.execute(() -> {
            try {
                JSONObject loaded;
                if (creating) {
                    loaded = KIND_CONTEXT.equals(kind)
                        ? newContextDraft()
                        : newGroupDraft();
                } else {
                    loaded = KIND_CONTEXT.equals(kind)
                        ? store.getContext(objectId)
                        : store.getGroup(objectId);
                }
                List<JSONObject> loadedContexts = new ArrayList<>();
                for (JSONObject context : store.listContexts()) {
                    loadedContexts.add(copyJson(context));
                }
                List<JSONObject> loadedGroups = new ArrayList<>();
                for (JSONObject group : store.listGroups()) {
                    loadedGroups.add(copyJson(group));
                }
                List<String> loadedScenes = new ArrayList<>();
                for (SceneStore.SceneInfo info : sceneStore.listSceneInfos()) {
                    if (info != null && !TextUtils.isEmpty(info.sceneName)) {
                        loadedScenes.add(info.sceneName);
                    }
                }
                Collections.sort(loadedScenes, String.CASE_INSENSITIVE_ORDER);
                JSONObject loadedSnapshot = copyJson(loaded);
                runOnUiThread(() -> {
                    if (isFinishing() || isDestroyed()) return;
                    loading = false;
                    draft = loaded;
                    initialDraft = loadedSnapshot;
                    contexts = loadedContexts;
                    groups = loadedGroups;
                    sceneNames = loadedScenes;
                    dirty = false;
                    render();
                    restoreEditorState(pendingRestoreState);
                    pendingRestoreState = null;
                    if (!creating) loadClosure();
                });
            } catch (Exception error) {
                runOnUiThread(() -> {
                    if (isFinishing() || isDestroyed()) return;
                    loading = false;
                    showError(getString(
                        R.string.context_group_editor_failed,
                        safeMessage(error)
                    ));
                });
            }
        });
    }

    private void render() {
        pageActions.removeAllViews();
        pageActions.setVisibility(View.GONE);
        content.removeAllViews();
        content.addView(status, 0, matchParams());
        status.setVisibility(View.GONE);
        if (draft == null) return;
        addEditorHeading();
        addIdentitySection();
        if (KIND_CONTEXT.equals(kind)) addDescriptionSection();
        addMemberSection();
        if (KIND_CONTEXT.equals(kind)) addGroupSection();
        addSummarySection();
        if (!creating) addClosureSection();
        addBottomActions();
    }

    private void addEditorHeading() {
        TextView heading = addText(content, editorTitle(), 17, true);
        heading.setPadding(0, 0, 0, dp(2));
        TextView hint = addText(content,
            getString(R.string.context_group_editor_draft_hint), 10, false);
        hint.setPadding(0, 0, 0, dp(9));
    }

    private String editorTitle() {
        if (KIND_CONTEXT.equals(kind)) {
            return getString(creating
                ? R.string.context_group_editor_create_context
                : R.string.context_group_editor_edit_context);
        }
        return getString(creating
            ? R.string.context_group_editor_create_group
            : R.string.context_group_editor_edit_group);
    }

    private void addIdentitySection() {
        // The native store has one identity field (display_name). Keep the
        // prototype identity area as one ordinary field without inventing a
        // second name key or an extra same-name card.
        addFieldLabel(content, getString(R.string.context_group_editor_display_name));
        nameInput = editorInput();
        nameInput.setHint(R.string.context_group_editor_display_name);
        nameInput.setSingleLine(true);
        nameInput.setText(draft.optString("display_name", ""));
        nameInput.addTextChangedListener(new DirtyWatcher());
        LinearLayout.LayoutParams params = matchParams();
        params.bottomMargin = dp(10);
        content.addView(nameInput, params);
    }

    private void addDescriptionSection() {
        MaterialCardView card = card();
        addSectionTitle(card, getString(R.string.context_group_editor_description_section));
        LinearLayout rows = column();
        rows.setPadding(0, 0, 0, 0);
        descriptionRowsContainer = rows;
        descriptionInputs.clear();
        cardContent(card).addView(rows, matchParams());

        List<String> languages = descriptionLanguages(draft);
        if (languages.isEmpty()) languages.add(defaultLanguage());
        for (String language : languages) addDescriptionRow(rows, language);

        addFieldLabel(card, getString(R.string.context_group_editor_language));
        descriptionLanguageInput = editorInput();
        descriptionLanguageInput.setHint(R.string.context_group_editor_language);
        descriptionLanguageInput.setSingleLine(true);
        cardContent(card).addView(descriptionLanguageInput, matchParams());
        MaterialButton add = compactButton(R.string.context_group_editor_add_language);
        add.setAllCaps(false);
        add.setLayoutParams(wrapButtonParams());
        add.setOnClickListener(view -> {
            String language = descriptionLanguageInput.getText().toString().trim();
            if (language.isEmpty()) {
                descriptionLanguageInput.setError(getString(R.string.error_required));
                return;
            }
            if (findDescriptionInput(language) != null) {
                descriptionLanguageInput.setError(
                    getString(R.string.scene_annotation_invalid_language)
                );
                return;
            }
            addDescriptionRow(rows, language);
            descriptionLanguageInput.setText("");
            markDirty();
        });
        cardContent(card).addView(add);
        content.addView(card);
    }

    private void addDescriptionRow(LinearLayout rows, String language) {
        LinearLayout row = column();
        row.setPadding(0, 0, 0, dp(6));
        addFieldLabel(row, language);
        EditText input = editorInput();
        input.setHint(R.string.context_group_editor_description);
        input.setMinHeight(dp(76));
        input.setGravity(Gravity.TOP | Gravity.START);
        input.setText(editorDescription(language));
        input.addTextChangedListener(new DirtyWatcher());
        row.addView(input, matchParams());
        descriptionInputs.put(language, input);
        if (!defaultLanguage().equalsIgnoreCase(language)) {
            MaterialButton remove = compactButton(R.string.context_group_editor_remove);
            remove.setTextColor(ContextCompat.getColor(this, R.color.het_error));
            remove.setLayoutParams(wrapButtonParams());
            remove.setOnClickListener(view -> {
                descriptionInputs.remove(language);
                rows.removeView(row);
                markDirty();
            });
            row.addView(remove, wrapButtonParams());
        }
        rows.addView(row, matchParams());
    }

    private void addMemberSection() {
        MaterialCardView card = card();
        String title = KIND_CONTEXT.equals(kind)
            ? getString(R.string.context_group_editor_scene_members)
            : getString(R.string.context_group_editor_context_members);
        selectedMemberIds.clear();
        selectedMemberIds.addAll(readMemberIds(draft));
        memberChecks.clear();
        cardContent(card).setPadding(0, 0, 0, 0);
        LinearLayout body = column();
        body.setPadding(dp(12), dp(8), dp(12), dp(10));
        body.setVisibility(View.GONE);
        TextView memberHeader = groupHeader(
            cardContent(card),
            title,
            getString(
                R.string.context_group_editor_member_count,
                selectedMemberIds.size()
            ),
            body
        );
        memberHeaderCount = headerCount(memberHeader);
        cardContent(card).addView(body, matchParams());

        memberOrder = column();
        memberOrder.setPadding(0, 0, 0, dp(4));
        body.addView(memberOrder, matchParams());
        renderMemberOrder();

        LinearLayout available = column();
        available.setPadding(0, 0, 0, 0);
        if (KIND_CONTEXT.equals(kind)) {
            for (String scene : sceneNames) addMemberCheck(available, scene, scene);
        } else {
            for (JSONObject context : contexts) {
                String id = context.optString("id", "");
                if (!id.isEmpty()) addMemberCheck(
                    available,
                    id,
                    context.optString("display_name", id)
                );
            }
        }
        if (available.getChildCount() == 0) {
            addText(available, getString(
                R.string.context_group_editor_empty_members
            ), 12, false);
        }
        TextView availableLabel = addText(body,
            getString(R.string.context_group_editor_add_member), 12, true);
        availableLabel.setPadding(0, dp(5), 0, dp(3));
        body.addView(available, matchParams());
        content.addView(card);
    }

    private void addGroupSection() {
        MaterialCardView card = card();
        String title = getString(R.string.context_group_editor_groups);
        selectedGroupIds.clear();
        selectedGroupIds.addAll(readGroupIds(objectId, groups));
        groupChecks.clear();
        cardContent(card).setPadding(0, 0, 0, 0);
        LinearLayout body = column();
        body.setPadding(dp(12), dp(8), dp(12), dp(10));
        body.setVisibility(View.GONE);
        TextView groupHeader = groupHeader(
            cardContent(card),
            title,
            getString(
                R.string.context_group_editor_group_count,
                selectedGroupIds.size()
            ),
            body
        );
        groupHeaderCount = headerCount(groupHeader);
        cardContent(card).addView(body, matchParams());
        groupOrder = column();
        groupOrder.setPadding(0, 0, 0, dp(4));
        body.addView(groupOrder, matchParams());
        renderGroupOrder();
        LinearLayout available = column();
        available.setPadding(0, 0, 0, 0);
        for (JSONObject group : groups) {
            String id = group.optString("id", "");
            if (!id.isEmpty() && !id.equals(objectId)) {
                addGroupCheck(available, id, group.optString("display_name", id));
            }
        }
        if (available.getChildCount() == 0) {
            addText(available, getString(R.string.context_group_editor_empty_groups), 10, false);
        }
        TextView availableLabel = addText(body,
            getString(R.string.context_group_editor_add_group), 12, true);
        availableLabel.setPadding(0, dp(5), 0, dp(3));
        body.addView(available, matchParams());
        content.addView(card);
    }

    private void addMemberCheck(
        LinearLayout parent,
        String id,
        String label
    ) {
        if (id.equals(objectId)) return;
        MaterialCheckBox check = new MaterialCheckBox(this);
        check.setText(label);
        check.setTextSize(13);
        check.setMinHeight(dp(36));
        check.setPadding(dp(8), dp(6), dp(8), dp(6));
        check.setChecked(selectedMemberIds.contains(id));
        check.setOnCheckedChangeListener((button, checked) -> {
            if (checked && !selectedMemberIds.contains(id)) {
                selectedMemberIds.add(id);
            } else if (!checked) {
                selectedMemberIds.remove(id);
            }
            updateMemberHeaderCount();
            renderMemberOrder();
            markDirty();
        });
        memberChecks.put(id, check);
        parent.addView(check, matchParams());
    }

    private void addGroupCheck(
        LinearLayout parent,
        String id,
        String label
    ) {
        MaterialCheckBox check = new MaterialCheckBox(this);
        check.setText(label);
        check.setTextSize(13);
        check.setMinHeight(dp(36));
        check.setPadding(dp(8), dp(6), dp(8), dp(6));
        check.setChecked(selectedGroupIds.contains(id));
        check.setOnCheckedChangeListener((button, checked) -> {
            if (checked && !selectedGroupIds.contains(id)) {
                selectedGroupIds.add(id);
            } else if (!checked) {
                selectedGroupIds.remove(id);
            }
            updateGroupHeaderCount();
            renderGroupOrder();
            markDirty();
        });
        groupChecks.put(id, check);
        parent.addView(check, matchParams());
    }

    private void renderMemberOrder() {
        if (memberOrder == null) return;
        memberOrder.removeAllViews();
        addText(memberOrder, getString(R.string.context_group_editor_ordered_members), 12, true);
        if (selectedMemberIds.isEmpty()) {
            addText(memberOrder, getString(
                R.string.context_group_editor_empty_members
            ), 12, false);
            applyMembersToDraft();
            return;
        }
        for (int index = 0; index < selectedMemberIds.size(); index++) {
            String memberId = selectedMemberIds.get(index);
            String label = memberLabel(memberId);
            LinearLayout row = new LinearLayout(this);
            row.setGravity(Gravity.CENTER_VERTICAL);
            TextView title = addText(row, label, 12, false);
            LinearLayout.LayoutParams titleParams =
                (LinearLayout.LayoutParams) title.getLayoutParams();
            titleParams.width = 0;
            titleParams.weight = 1;
            title.setLayoutParams(titleParams);
            final int position = index;
            MaterialButton up = smallButton(R.string.context_group_editor_move_up);
            up.setEnabled(position > 0);
            up.setOnClickListener(view -> moveMember(position, position - 1));
            row.addView(up);
            MaterialButton down = smallButton(R.string.context_group_editor_move_down);
            down.setEnabled(position + 1 < selectedMemberIds.size());
            down.setOnClickListener(view -> moveMember(position, position + 1));
            row.addView(down);
            MaterialButton remove = smallButton(R.string.context_group_editor_remove);
            remove.setOnClickListener(view -> {
                selectedMemberIds.remove(memberId);
                MaterialCheckBox check = memberChecks.get(memberId);
                if (check != null) check.setChecked(false);
                updateMemberHeaderCount();
                renderMemberOrder();
                markDirty();
            });
            row.addView(remove);
            memberOrder.addView(row, matchParams());
        }
        applyMembersToDraft();
    }

    private void renderGroupOrder() {
        if (groupOrder == null) return;
        groupOrder.removeAllViews();
        addText(groupOrder, getString(R.string.context_group_editor_selected_groups), 12, true);
        if (selectedGroupIds.isEmpty()) {
            addText(groupOrder, getString(R.string.context_group_editor_empty_groups), 10, false);
            return;
        }
        for (String groupId : selectedGroupIds) {
            JSONObject group = findById(groups, groupId);
            addText(groupOrder,
                group == null ? groupId : group.optString("display_name", groupId),
                11,
                false
            );
        }
    }

    private void moveMember(int from, int to) {
        if (from < 0 || to < 0 || from >= selectedMemberIds.size()
            || to >= selectedMemberIds.size()) return;
        String member = selectedMemberIds.remove(from);
        selectedMemberIds.add(to, member);
        renderMemberOrder();
        markDirty();
    }

    private void addSummarySection() {
        MaterialCardView card = card();
        addSectionTitle(card, getString(R.string.context_group_editor_summary_records));
        boolean hasRecords = addSummaryRecords(cardContent(card));
        LinearLayout rows = column();
        rows.setPadding(0, 0, 0, 0);
        cardContent(card).addView(rows, matchParams());
        manualRowsContainer = rows;
        manualRows.clear();
        manualEmpty = null;
        List<String> languages = editorManualSummaryLanguages(draft);
        for (String language : languages) addManualRow(rows, language);
        if (!hasRecords && languages.isEmpty()) {
            manualEmpty = addText(rows, getString(
                R.string.context_group_editor_no_summary
            ), 12, false);
        }
        MaterialButton add = compactButton(
            R.string.context_group_editor_add_manual_summary
        );
        addManualSummaryButton = add;
        add.setAllCaps(false);
        add.setOnClickListener(view -> {
            if (!manualRows.isEmpty()) return;
            addManualRow(rows, defaultLanguage());
            add.setVisibility(View.GONE);
            markDirty();
        });
        add.setTextSize(13);
        add.setLayoutParams(wrapButtonParams());
        cardContent(card).addView(add);
        add.setVisibility(languages.isEmpty() ? View.VISIBLE : View.GONE);
        content.addView(card);
    }

    private boolean addSummaryRecords(ViewGroup parent) {
        JSONObject summaries = draft == null ? null : draft.optJSONObject("summary");
        Set<String> languageSet = new LinkedHashSet<>();
        addKeys(languageSet, summaries);
        List<String> languages = new ArrayList<>(languageSet);
        Collections.sort(languages, String.CASE_INSENSITIVE_ORDER);
        boolean hasRecords = false;
        for (String language : languages) {
            JSONObject values = summaries.optJSONObject(language);
            if (values == null) continue;
            boolean hasLanguageRecord = false;
            if (addSummaryRecord(parent, values.optJSONObject("final"),
                R.string.context_group_editor_summary_final)) {
                hasLanguageRecord = true;
            }
            if (addSummaryRecord(parent, values.optJSONObject("current"),
                R.string.context_group_editor_summary_current)) {
                hasLanguageRecord = true;
            }
            if (hasLanguageRecord) {
                hasRecords = true;
            }
        }
        return hasRecords;
    }

    private boolean addSummaryRecord(
        ViewGroup parent,
        JSONObject record,
        int label
    ) {
        if (record == null) return false;
        String text = record.optString("text", "").trim();
        if (text.isEmpty()) return false;
        addText(parent, getString(label) + "：" + text, 11, false);
        return true;
    }

    private void addManualRow(
        LinearLayout rows,
        String language
    ) {
        MaterialCardView rowCard = card();
        cardContent(rowCard).setPadding(dp(9), dp(8), dp(9), dp(9));
        LinearLayout row = column();
        row.setPadding(0, 0, 0, 0);
        cardContent(rowCard).addView(row, matchParams());
        if (manualEmpty != null) manualEmpty.setVisibility(View.GONE);
        addText(row, languageLabel(language), 11, true);
        addFieldLabel(row, getString(R.string.context_group_editor_summary));
        EditText summary = editorInput();
        summary.setHint(R.string.context_group_editor_summary);
        summary.setMinHeight(dp(76));
        summary.setGravity(Gravity.TOP | Gravity.START);
        summary.setText(editorSummary(language));
        summary.addTextChangedListener(new DirtyWatcher());
        row.addView(summary, matchParams());
        manualRows.put(language, new ManualRow(language, summary));
        MaterialButton remove = compactButton(R.string.context_group_editor_remove);
        remove.setTextColor(ContextCompat.getColor(this, R.color.het_error));
        remove.setOnClickListener(view -> {
            manualRows.remove(language);
            rows.removeView(rowCard);
            if (manualRows.isEmpty() && manualEmpty != null) {
                manualEmpty.setVisibility(View.VISIBLE);
            }
            if (manualRows.isEmpty() && addManualSummaryButton != null) {
                addManualSummaryButton.setVisibility(View.VISIBLE);
            }
            markDirty();
        });
        row.addView(remove, wrapButtonParams());
        rows.addView(rowCard);
    }

    private void addClosureSection() {
        MaterialCardView card = card();
        addSectionTitle(card, getString(R.string.context_group_editor_closure));
        addFieldLabel(card, getString(R.string.context_group_editor_closure_status));
        closureStatus = addText(card, getString(
            R.string.context_group_editor_loading
        ), 12, false);
        addFieldLabel(card, getString(R.string.context_group_editor_closure_language));
        closureLanguageInput = editorInput();
        closureLanguageInput.setHint(R.string.context_group_editor_language);
        closureLanguageInput.setSingleLine(true);
        closureLanguageInput.setText(defaultLanguage());
        cardContent(card).addView(closureLanguageInput, matchParams());
        closureAction = compactButton(R.string.context_group_editor_closure_open);
        closureAction.setAllCaps(false);
        closureAction.setEnabled(false);
        cardContent(card).addView(closureAction, matchParams());
        closureReopen = compactButton(R.string.context_group_editor_closure_reopen);
        closureReopen.setAllCaps(false);
        closureReopen.setText(R.string.context_group_editor_closure_reopen);
        applyDanger(closureReopen);
        closureReopen.setVisibility(View.GONE);
        closureReopen.setOnClickListener(view -> confirmReopen());
        cardContent(card).addView(closureReopen, matchParams());
        closureAction.setOnClickListener(view -> runClosureAction());
        content.addView(card);
        if (stylePreview) {
            closureLanguageInput.setEnabled(false);
            closureStatus.setText(R.string.style_preview_read_only_body);
            closureAction.setVisibility(View.GONE);
            closureReopen.setVisibility(View.GONE);
        } else if (closureState != null) {
            renderClosure();
        }
    }

    private void addBottomActions() {
        pageActions.setVisibility(View.VISIBLE);
        MaterialButton save = compactButton(R.string.context_group_editor_save);
        save.setTextColor(ContextCompat.getColor(this, R.color.het_on_primary_container));
        save.setBackgroundTintList(ContextCompat.getColorStateList(
            this,
            R.color.het_primary_container
        ));
        save.setOnClickListener(view -> save());
        MaterialButton reset = compactButton(R.string.context_group_editor_reset);
        reset.setTextColor(ContextCompat.getColor(this, R.color.het_on_surface));
        reset.setBackgroundTintList(ContextCompat.getColorStateList(this, R.color.het_surface_container_high));
        reset.setOnClickListener(view -> resetEditor());
        LinearLayout.LayoutParams saveParams = wrapButtonParams();
        saveParams.gravity = Gravity.CENTER_VERTICAL;
        saveParams.rightMargin = dp(6);
        pageActions.addView(save, saveParams);
        LinearLayout.LayoutParams resetParams = wrapButtonParams();
        resetParams.gravity = Gravity.CENTER_VERTICAL;
        pageActions.addView(reset, resetParams);
    }

    private void resetEditor() {
        if (stylePreview || saving || initialDraft == null) return;
        try {
            draft = copyJson(initialDraft);
            dirty = false;
            render();
            if (!creating) loadClosure();
        } catch (Exception error) {
            showError(getString(
                R.string.context_group_editor_failed,
                safeMessage(error)
            ));
        }
    }

    private void loadClosure() {
        ioExecutor.execute(() -> {
            try {
                closureState = KIND_CONTEXT.equals(kind)
                    ? contextCompression.getManualClosureState(objectId)
                    : groupCompression.getManualClosureState(objectId);
                runOnUiThread(() -> {
                    if (!isFinishing() && !isDestroyed()) renderClosure();
                });
            } catch (Exception error) {
                runOnUiThread(() -> {
                    if (isFinishing() || isDestroyed()) return;
                    if (closureStatus != null) {
                        closureStatus.setText(getString(
                            R.string.context_group_editor_closure_failed,
                            safeMessage(error)
                        ));
                    }
                });
            }
        });
    }

    private void renderClosure() {
        if (closureState == null || closureStatus == null || closureAction == null) return;
        closureAction.setVisibility(View.VISIBLE);
        closureAction.setEnabled(true);
        if (closureReopen != null) closureReopen.setVisibility(View.GONE);
        if (closureState.isNone()) {
            closureStatus.setText(R.string.context_group_editor_closure_none);
            closureAction.setText(R.string.context_group_editor_closure_open);
        } else if (closureState.isOpen()) {
            closureStatus.setText(R.string.context_group_editor_closure_open_status);
            closureAction.setText(R.string.context_group_editor_closure_end);
        } else {
            closureStatus.setText(R.string.context_group_editor_closure_closed);
            closureAction.setText(R.string.context_group_editor_closure_retry);
            if (closureReopen != null) closureReopen.setVisibility(View.VISIBLE);
        }
    }

    private void runClosureAction() {
        if (stylePreview || closureState == null || saving) return;
        if (closureState.isNone()) {
            closureAsync(true, false);
        } else if (closureState.isOpen()) {
            confirmEnd();
        } else {
            closureAsync(false, true);
        }
    }

    private void closureAsync(boolean open, boolean retry) {
        if (stylePreview) return;
        final String requestedLanguage = closureLanguageInput == null
            ? defaultLanguage()
            : closureLanguageInput.getText().toString().trim();
        if (!open && requestedLanguage.isEmpty()) {
            if (closureLanguageInput != null) {
                closureLanguageInput.setError(getString(R.string.error_required));
            }
            if (closureAction != null) closureAction.setEnabled(true);
            return;
        }
        if (closureAction != null) closureAction.setEnabled(false);
        ioExecutor.execute(() -> {
            try {
                Object result;
                if (open) {
                    result = KIND_CONTEXT.equals(kind)
                        ? contextCompression.openManualClosure(objectId)
                        : groupCompression.openManualClosure(objectId);
                } else {
                    result = KIND_CONTEXT.equals(kind)
                        ? contextCompression.retryManualClosure(
                            objectId,
                            requestedLanguage
                        )
                        : groupCompression.retryManualClosure(
                            objectId,
                            requestedLanguage
                        );
                }
                closureState = KIND_CONTEXT.equals(kind)
                    ? contextCompression.getManualClosureState(objectId)
                    : groupCompression.getManualClosureState(objectId);
                runOnUiThread(() -> {
                    if (isFinishing() || isDestroyed()) return;
                    renderClosure();
                    if (!open && requiresLatestFactsConfirmation(result)) {
                        confirmLatestFactsRetry(requestedLanguage);
                        return;
                    }
                    showClosureResult(result, open);
                });
            } catch (Exception error) {
                runOnUiThread(() -> {
                    if (isFinishing() || isDestroyed()) return;
                    if (closureStatus != null) closureStatus.setText(getString(
                        R.string.context_group_editor_closure_failed,
                        safeMessage(error)
                    ));
                    if (closureAction != null) closureAction.setEnabled(true);
                });
            }
        });
    }

    private boolean requiresLatestFactsConfirmation(Object result) {
        if (result instanceof ContextCompressionCoordinator.Result) {
            return ((ContextCompressionCoordinator.Result) result)
                .requiresLatestFactsConfirmation;
        }
        if (result instanceof GroupCompressionCoordinator.Result) {
            return ((GroupCompressionCoordinator.Result) result)
                .requiresLatestFactsConfirmation;
        }
        return false;
    }

    private void confirmLatestFactsRetry(String language) {
        if (isFinishing() || isDestroyed()) return;
        boolean context = KIND_CONTEXT.equals(kind);
        new UiMaterialAlertDialogBuilder(this)
            .setTitle(context
                ? R.string.scene_context_manual_closure_retry_title
                : R.string.scene_group_manual_closure_latest_facts_title)
            .setMessage(context
                ? R.string.scene_context_manual_closure_retry_message
                : R.string.scene_group_manual_closure_latest_facts_message)
            .setNegativeButton(R.string.cancel_action, null)
            .setPositiveButton(context
                ? R.string.scene_context_manual_closure_retry
                : R.string.scene_group_manual_closure_retry,
                (dialog, which) -> retryClosureWithLatestFacts(language))
            .show();
    }

    private void retryClosureWithLatestFacts(String language) {
        if (stylePreview || isFinishing() || isDestroyed()) return;
        if (closureAction != null) closureAction.setEnabled(false);
        ioExecutor.execute(() -> {
            try {
                Object result = KIND_CONTEXT.equals(kind)
                    ? contextCompression.retryManualClosure(
                        objectId,
                        language,
                        true
                    )
                    : groupCompression.retryManualClosure(
                        objectId,
                        language,
                        true
                    );
                closureState = KIND_CONTEXT.equals(kind)
                    ? contextCompression.getManualClosureState(objectId)
                    : groupCompression.getManualClosureState(objectId);
                runOnUiThread(() -> {
                    if (isFinishing() || isDestroyed()) return;
                    renderClosure();
                    showClosureResult(result, false);
                });
            } catch (Exception error) {
                runOnUiThread(() -> {
                    if (isFinishing() || isDestroyed()) return;
                    if (closureStatus != null) closureStatus.setText(getString(
                        R.string.context_group_editor_closure_failed,
                        safeMessage(error)
                    ));
                    if (closureAction != null) closureAction.setEnabled(true);
                });
            }
        });
    }

    private void confirmEnd() {
        if (stylePreview) return;
        new UiMaterialAlertDialogBuilder(this)
            .setTitle(R.string.context_group_editor_closure_end_title)
            .setMessage(R.string.context_group_editor_closure_end_message)
            .setNegativeButton(R.string.cancel_action, null)
            .setPositiveButton(
                R.string.context_group_editor_closure_end,
                (dialog, which) -> endClosure()
            )
            .show();
    }

    private void endClosure() {
        if (stylePreview) return;
        if (closureAction != null) closureAction.setEnabled(false);
        String language = closureLanguageInput == null
            ? defaultLanguage()
            : closureLanguageInput.getText().toString().trim();
        if (language.isEmpty()) {
            if (closureLanguageInput != null) closureLanguageInput.setError(getString(R.string.error_required));
            if (closureAction != null) closureAction.setEnabled(true);
            return;
        }
        ioExecutor.execute(() -> {
            try {
                if (KIND_CONTEXT.equals(kind)) {
                    contextCompression.endManualClosure(objectId, language);
                    closureState = contextCompression.getManualClosureState(objectId);
                } else {
                    groupCompression.endManualClosure(objectId, language);
                    closureState = groupCompression.getManualClosureState(objectId);
                }
                runOnUiThread(() -> {
                    if (isFinishing() || isDestroyed()) return;
                    renderClosure();
                });
            } catch (Exception error) {
                runOnUiThread(() -> {
                    if (isFinishing() || isDestroyed()) return;
                    if (closureStatus != null) closureStatus.setText(getString(
                        R.string.context_group_editor_closure_failed,
                        safeMessage(error)
                    ));
                    if (closureAction != null) closureAction.setEnabled(true);
                });
            }
        });
    }

    private void confirmReopen() {
        if (stylePreview) return;
        new UiMaterialAlertDialogBuilder(this)
            .setTitle(R.string.context_group_editor_closure_reopen_title)
            .setMessage(R.string.context_group_editor_closure_reopen_message)
            .setNegativeButton(R.string.cancel_action, null)
            .setPositiveButton(
                R.string.context_group_editor_closure_reopen,
                (dialog, which) -> ioExecutor.execute(() -> {
                    try {
                        if (KIND_CONTEXT.equals(kind)) {
                            contextCompression.reopenManualClosure(objectId);
                            closureState = contextCompression.getManualClosureState(objectId);
                        } else {
                            groupCompression.reopenManualClosure(objectId);
                            closureState = groupCompression.getManualClosureState(objectId);
                        }
                        runOnUiThread(() -> {
                            if (isFinishing() || isDestroyed()) return;
                            renderClosure();
                        });
                    } catch (Exception error) {
                        runOnUiThread(() -> {
                            if (isFinishing() || isDestroyed()) return;
                            if (closureStatus != null) closureStatus.setText(getString(
                                R.string.context_group_editor_closure_failed,
                                safeMessage(error)
                            ));
                        });
                    }
                })
            )
            .show();
    }

    private void save() {
        if (stylePreview || saving || draft == null || nameInput == null) return;
        String name = nameInput.getText().toString().trim();
        if (name.isEmpty()) {
            nameInput.setError(getString(R.string.error_required));
            return;
        }
        final JSONObject edited;
        try {
            edited = buildEditedDraft(name);
        } catch (Exception error) {
            showError(getString(
                R.string.context_group_editor_save_failed,
                safeMessage(error)
            ));
            return;
        }
        List<JSONObject> allContexts = copyList(contexts);
        List<JSONObject> allGroups = copyList(groups);
        if (KIND_CONTEXT.equals(kind)) replaceOrAdd(allContexts, edited);
        else replaceOrAdd(allGroups, edited);
        if (KIND_CONTEXT.equals(kind)) {
            updateContextGroupMembership(allGroups, edited.optString("id", ""));
        }
        saving = true;
        ioExecutor.execute(() -> {
            try {
                ContextReviewCoordinator.EditRisk risk =
                    reviewCoordinator.assessReview(allContexts, allGroups);
                runOnUiThread(() -> {
                    if (isFinishing() || isDestroyed()) return;
                    confirmRiskThenSave(allContexts, allGroups, risk);
                });
            } catch (Exception error) {
                runOnUiThread(() -> {
                    if (isFinishing() || isDestroyed()) return;
                    saveFailure(error);
                });
            }
        });
    }

    private void confirmRiskThenSave(
        List<JSONObject> allContexts,
        List<JSONObject> allGroups,
        ContextReviewCoordinator.EditRisk risk
    ) {
        if (risk == null || risk.affectedWork <= 0) {
            saveWithRisk(allContexts, allGroups, risk);
            return;
        }
        new UiMaterialAlertDialogBuilder(this)
            .setTitle(R.string.context_group_editor_closure)
            .setMessage(getString(
                R.string.scene_management_editor_risk_message,
                risk.affectedWork
            ))
            .setNegativeButton(R.string.cancel_action, (dialog, which) -> resetSaving())
            .setPositiveButton(
                R.string.context_group_editor_save,
                (dialog, which) -> saveWithRisk(allContexts, allGroups, risk)
            )
            .setOnCancelListener(dialog -> resetSaving())
            .show();
    }

    private void saveWithRisk(
        List<JSONObject> allContexts,
        List<JSONObject> allGroups,
        ContextReviewCoordinator.EditRisk risk
    ) {
        ioExecutor.execute(() -> {
            try {
                ContextReviewCoordinator.Options options = loadOptions();
                reviewCoordinator.save(
                    allContexts,
                    allGroups,
                    store.getActiveContextId(),
                    store.getActiveGroupId(),
                    options,
                    risk,
                    false
                );
                runOnUiThread(() -> {
                    if (isFinishing() || isDestroyed()) return;
                    dirty = false;
                    Toast.makeText(this, R.string.context_group_editor_saved, Toast.LENGTH_SHORT).show();
                    setResult(RESULT_OK);
                    finish();
                });
            } catch (Exception error) {
                runOnUiThread(() -> {
                    if (isFinishing() || isDestroyed()) return;
                    saveFailure(error);
                });
            }
        });
    }

    private ContextReviewCoordinator.Options loadOptions() throws Exception {
        JSONObject userSettings = new ConfigStore(this)
            .load()
            .config
            .getJSONObject("UserSettings");
        JSONObject contextHistory = userSettings.optJSONObject("ContextHistory");
        ContextReviewCoordinator.Options options =
            new ContextReviewCoordinator.Options();
        options.autoCompression = contextHistory != null
            && contextHistory.optBoolean("EnableAutoCompression", false);
        options.continueAfterManual = contextHistory != null
            && contextHistory.optBoolean("ContinueAutoSummaryAfterManual", false);
        return options;
    }

    private void showClosureResult(Object result, boolean opening) {
        if (isFinishing() || isDestroyed()) return;
        if (opening) {
            Toast.makeText(
                this,
                KIND_CONTEXT.equals(kind)
                    ? R.string.scene_context_manual_closure_opened
                    : R.string.scene_group_manual_closure_opened,
                Toast.LENGTH_SHORT
            ).show();
            return;
        }
        if (result instanceof ContextCompressionCoordinator.Result) {
            ContextCompressionCoordinator.Result value =
                (ContextCompressionCoordinator.Result) result;
            if (value.closeBlockedByActiveJobs) {
                if (closureStatus != null) closureStatus.setText(getString(
                    R.string.scene_context_manual_closure_blocked,
                    value.activeSummaryRequestIds.size()
                ));
            } else if (value.noFacts) {
                if (closureStatus != null) {
                    closureStatus.setText(
                        R.string.scene_context_manual_closure_no_facts
                    );
                }
            } else if (value.closureQueued) {
                Toast.makeText(
                    this,
                    R.string.scene_context_manual_closure_queued,
                    Toast.LENGTH_LONG
                ).show();
            } else if (value.closureCompleted) {
                Toast.makeText(
                    this,
                    R.string.scene_context_manual_closure_completed,
                    Toast.LENGTH_SHORT
                ).show();
            } else if (value.admissionFailed || value.closureRetryable) {
                if (closureStatus != null) {
                    closureStatus.setText(
                        R.string.scene_context_manual_closure_retry_status
                    );
                }
            }
        } else if (result instanceof GroupCompressionCoordinator.Result) {
            GroupCompressionCoordinator.Result value =
                (GroupCompressionCoordinator.Result) result;
            if (value.closeBlockedByActiveJobs) {
                if (closureStatus != null) closureStatus.setText(getString(
                    R.string.scene_group_manual_closure_blocked,
                    value.activeSummaryRequestIds.size()
                ));
            } else if (value.dependenciesMissing) {
                if (closureStatus != null) closureStatus.setText(getString(
                    R.string.scene_group_manual_closure_missing_contexts,
                    TextUtils.join(", ", value.missingContextIds)
                ));
            } else if (value.noFacts) {
                if (closureStatus != null) {
                    closureStatus.setText(
                        R.string.scene_group_manual_closure_no_facts
                    );
                }
            } else if (value.closureQueued) {
                Toast.makeText(
                    this,
                    R.string.scene_group_manual_closure_queued,
                    Toast.LENGTH_LONG
                ).show();
            } else if (value.closureCompleted) {
                Toast.makeText(
                    this,
                    R.string.scene_group_manual_closure_completed,
                    Toast.LENGTH_SHORT
                ).show();
            } else if (value.admissionFailed || value.closureRetryable) {
                if (closureStatus != null) {
                    closureStatus.setText(
                        R.string.scene_group_manual_closure_retry_status
                    );
                }
            }
        }
    }

    private JSONObject buildEditedDraft(String name) throws Exception {
        JSONObject edited = copyJson(draft);
        edited.put("display_name", name);
        if (edited.optString("id", "").isEmpty()) {
            edited.put("id", "new-" + UUID.randomUUID());
        }
        applyMembersToDraft(edited);
        stageManualRecords(edited);
        return edited;
    }

    private void updateContextGroupMembership(
        List<JSONObject> allGroups,
        String contextId
    ) {
        for (JSONObject group : allGroups) {
            String groupId = group.optString("id", "");
            boolean selected = selectedGroupIds.contains(groupId);
            JSONArray previous = group.optJSONArray("contexts");
            JSONArray updated = new JSONArray();
            boolean found = false;
            for (int index = 0; previous != null && index < previous.length(); index++) {
                JSONObject entry = previous.optJSONObject(index);
                if (entry == null) continue;
                if (contextId.equals(entry.optString(GroupContextEntry.CONTEXT_ID, ""))) {
                    found = true;
                    if (selected) updated.put(entry);
                } else {
                    updated.put(entry);
                }
            }
            if (selected && !found) updated.put(GroupContextEntry.create(contextId));
            try {
                group.put("contexts", updated);
            } catch (org.json.JSONException error) {
                throw new IllegalStateException(
                    "could not write group context membership",
                    error
                );
            }
        }
    }

    private void stageManualRecords(JSONObject target) throws Exception {
        JSONObject descriptions = new JSONObject();
        JSONObject summary = target.optJSONObject("summary");
        if (summary == null) summary = new JSONObject();
        Iterator<String> languages = summary.keys();
        List<String> summaryLanguages = new ArrayList<>();
        while (languages.hasNext()) summaryLanguages.add(languages.next());
        for (String language : summaryLanguages) {
            JSONObject value = summary.optJSONObject(language);
            if (value != null) value.remove("manual");
        }
        for (Map.Entry<String, EditText> entry : descriptionInputs.entrySet()) {
            String description = entry.getValue() == null
                ? ""
                : entry.getValue().getText().toString().trim();
            if (KIND_CONTEXT.equals(kind) && !description.isEmpty()) {
                descriptions.put(entry.getKey(), manualRecord(description));
            }
        }
        for (ManualRow row : manualRows.values()) {
            String manualSummary = row.summary == null
                ? ""
                : row.summary.getText().toString().trim();
            if (manualSummary.isEmpty()) continue;
            if (!manualSummary.isEmpty()) {
                JSONObject languageSummary = summary.optJSONObject(row.language);
                if (languageSummary == null) {
                    languageSummary = new JSONObject();
                    summary.put(row.language, languageSummary);
                }
                languageSummary.put("manual", manualRecord(manualSummary));
            }
        }
        if (KIND_CONTEXT.equals(kind)) target.put("manual_descriptions", descriptions);
        target.put("summary", summary);
    }

    private void applyMembersToDraft() {
        if (draft != null) applyMembersToDraft(draft);
    }

    private void applyMembersToDraft(JSONObject target) {
        String field = KIND_CONTEXT.equals(kind) ? "scenes" : "contexts";
        JSONArray previous = target.optJSONArray(field);
        Map<String, JSONObject> existing = new LinkedHashMap<>();
        for (int index = 0; previous != null && index < previous.length(); index++) {
            JSONObject entry = previous.optJSONObject(index);
            if (entry == null) continue;
            String memberId = KIND_CONTEXT.equals(kind)
                ? entry.optString("scene", "")
                : entry.optString(GroupContextEntry.CONTEXT_ID, "");
            if (!memberId.isEmpty() && !existing.containsKey(memberId)) {
                existing.put(memberId, entry);
            }
        }
        JSONArray values = new JSONArray();
        for (String memberId : selectedMemberIds) {
            JSONObject entry = existing.get(memberId);
            if (entry == null && KIND_CONTEXT.equals(kind)) {
                try {
                    entry = sceneEntry(memberId);
                } catch (Exception error) {
                    throw new IllegalStateException(
                        "could not create scene member entry", error
                    );
                }
            } else if (entry == null) {
                entry = GroupContextEntry.create(memberId);
            }
            values.put(entry);
        }
        putJson(target, field, values);
    }

    private static void putJson(JSONObject target, String key, Object value) {
        try {
            target.put(key, value);
        } catch (org.json.JSONException error) {
            throw new IllegalStateException("could not write JSON field " + key, error);
        }
    }

    private void confirmDiscard() {
        new UiMaterialAlertDialogBuilder(this)
            .setTitle(R.string.context_group_editor_unsaved_title)
            .setMessage(R.string.context_group_editor_unsaved_message)
            .setNegativeButton(R.string.context_group_editor_keep, null)
            .setPositiveButton(
                R.string.context_group_editor_discard,
                (dialog, which) -> {
                    dirty = false;
                    getOnBackPressedDispatcher().onBackPressed();
                }
            )
            .show();
    }

    private void saveFailure(Throwable error) {
        resetSaving();
        showError(getString(
            R.string.context_group_editor_save_failed,
            safeMessage(error)
        ));
    }

    private void resetSaving() {
        saving = false;
    }

    private void applyPreviewReadOnly(View view) {
        if (view == null) return;
        if (view instanceof MaterialButton
            || view instanceof MaterialCheckBox
            || view instanceof EditText) {
            view.setEnabled(false);
        }
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int index = 0; index < group.getChildCount(); index++) {
                applyPreviewReadOnly(group.getChildAt(index));
            }
        }
    }

    private void markDirty() {
        if (!suppressDirty) dirty = true;
    }

    private final class DirtyWatcher implements TextWatcher {
        @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
        @Override public void onTextChanged(CharSequence s, int start, int before, int count) {}
        @Override public void afterTextChanged(Editable value) { markDirty(); }
    }

    private String memberLabel(String memberId) {
        if (KIND_CONTEXT.equals(kind)) return memberId;
        JSONObject context = findById(contexts, memberId);
        return context == null ? memberId : context.optString("display_name", memberId);
    }

    private List<String> readMemberIds(JSONObject value) {
        List<String> result = new ArrayList<>();
        JSONArray values = value == null ? null
            : value.optJSONArray(KIND_CONTEXT.equals(kind) ? "scenes" : "contexts");
        for (int index = 0; values != null && index < values.length(); index++) {
            JSONObject entry = values.optJSONObject(index);
            String id = KIND_CONTEXT.equals(kind)
                ? entry == null ? "" : entry.optString("scene", "")
                : entry == null ? "" : entry.optString(GroupContextEntry.CONTEXT_ID, "");
            if (!id.isEmpty()) result.add(id);
        }
        return result;
    }

    private static List<String> readGroupIds(
        String contextId,
        List<JSONObject> values
    ) {
        List<String> result = new ArrayList<>();
        if (TextUtils.isEmpty(contextId)) return result;
        for (JSONObject group : values) {
            String groupId = group == null ? "" : group.optString("id", "");
            JSONArray contexts = group == null ? null : group.optJSONArray("contexts");
            for (int index = 0; contexts != null && index < contexts.length(); index++) {
                JSONObject entry = contexts.optJSONObject(index);
                if (entry != null && contextId.equals(
                    entry.optString(GroupContextEntry.CONTEXT_ID, "")
                )) {
                    if (!groupId.isEmpty()) result.add(groupId);
                    break;
                }
            }
        }
        return result;
    }

    private void updateMemberArray(JSONObject target) throws Exception {
        // Kept as a named seam so every reorder/remove goes through one draft owner.
        applyMembersToDraft(target);
    }

    private String editorDescription(String language) {
        JSONObject descriptions = draft.optJSONObject("manual_descriptions");
        JSONObject record = descriptions == null ? null : descriptions.optJSONObject(language);
        return record == null ? "" : record.optString("text", "");
    }

    private String editorSummary(String language) {
        JSONObject summaries = draft.optJSONObject("summary");
        JSONObject languageSummary = summaries == null ? null : summaries.optJSONObject(language);
        JSONObject record = languageSummary == null ? null : languageSummary.optJSONObject("manual");
        return record == null ? "" : record.optString("text", "");
    }

    private List<String> descriptionLanguages(JSONObject value) {
        Set<String> languages = new LinkedHashSet<>();
        if (value != null) {
            addKeys(languages, value.optJSONObject("manual_descriptions"));
        }
        List<String> result = new ArrayList<>(languages);
        Collections.sort(result, String.CASE_INSENSITIVE_ORDER);
        return result;
    }

    private List<String> editorManualSummaryLanguages(JSONObject value) {
        Set<String> languages = new LinkedHashSet<>();
        JSONObject summary = value == null ? null : value.optJSONObject("summary");
        if (summary != null) {
            Iterator<String> keys = summary.keys();
            while (keys.hasNext()) {
                String language = keys.next();
                JSONObject values = summary.optJSONObject(language);
                JSONObject manual = values == null
                    ? null
                    : values.optJSONObject("manual");
                if (manual != null && !TextUtils.isEmpty(
                    manual.optString("text", "").trim()
                )) {
                    languages.add(language);
                }
            }
        }
        List<String> result = new ArrayList<>(languages);
        Collections.sort(result, String.CASE_INSENSITIVE_ORDER);
        return result;
    }

    private EditText findDescriptionInput(String language) {
        for (Map.Entry<String, EditText> entry : descriptionInputs.entrySet()) {
            if (entry.getKey().equalsIgnoreCase(language)) return entry.getValue();
        }
        return null;
    }

    private LinearLayout.LayoutParams wrapButtonParams() {
        return new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT,
            dp(34)
        );
    }

    private static void addKeys(Set<String> target, JSONObject value) {
        if (value == null) return;
        Iterator<String> keys = value.keys();
        while (keys.hasNext()) {
            String key = keys.next();
            if (key != null && !key.trim().isEmpty()) target.add(key);
        }
    }

    private String defaultLanguage() {
        return "zh-cn";
    }

    private String languageLabel(String language) {
        if (language == null || language.trim().isEmpty()) return "—";
        if ("zh-cn".equalsIgnoreCase(language)) return "简体中文";
        if ("zh-tw".equalsIgnoreCase(language)) return "繁体中文";
        if ("en".equalsIgnoreCase(language)) return "English";
        if ("ja".equalsIgnoreCase(language)
            || "ja-jp".equalsIgnoreCase(language)) return "日本語";
        if ("ko".equalsIgnoreCase(language)) return "한국어";
        return language;
    }

    private void showError(String message) {
        status.setVisibility(View.VISIBLE);
        status.setText(message == null ? "" : message);
        status.setTextColor(ContextCompat.getColor(this, R.color.het_error));
    }

    @Override
    protected void onSaveInstanceState(Bundle outState) {
        super.onSaveInstanceState(outState);
        if (nameInput != null) {
            outState.putString(STATE_NAME, nameInput.getText().toString());
        }
        outState.putStringArrayList(
            STATE_SELECTED_MEMBERS,
            new ArrayList<>(selectedMemberIds)
        );
        outState.putStringArrayList(
            STATE_SELECTED_GROUPS,
            new ArrayList<>(selectedGroupIds)
        );
        ArrayList<String> languages = new ArrayList<>();
        ArrayList<String> descriptionLanguages = new ArrayList<>();
        ArrayList<String> descriptions = new ArrayList<>();
        ArrayList<String> summaries = new ArrayList<>();
        for (Map.Entry<String, EditText> entry : descriptionInputs.entrySet()) {
            descriptionLanguages.add(entry.getKey());
            descriptions.add(entry.getValue() == null
                ? ""
                : entry.getValue().getText().toString());
        }
        for (ManualRow row : manualRows.values()) {
            languages.add(row.language);
            summaries.add(row.summary == null
                ? ""
                : row.summary.getText().toString());
        }
        outState.putStringArrayList(STATE_DESCRIPTION_LANGUAGES, descriptionLanguages);
        outState.putStringArrayList(STATE_MANUAL_LANGUAGES, languages);
        outState.putStringArrayList(STATE_MANUAL_DESCRIPTIONS, descriptions);
        outState.putStringArrayList(STATE_MANUAL_SUMMARIES, summaries);
        if (closureLanguageInput != null) {
            outState.putString(
                STATE_CLOSURE_LANGUAGE,
                closureLanguageInput.getText().toString()
            );
        }
        outState.putBoolean(STATE_DIRTY, dirty);
    }

    private void restoreEditorState(Bundle state) {
        if (state == null || draft == null) return;
        suppressDirty = true;
        try {
            if (nameInput != null && state.containsKey(STATE_NAME)) {
                nameInput.setText(state.getString(STATE_NAME, ""));
            }
            ArrayList<String> selected = state.getStringArrayList(
                STATE_SELECTED_MEMBERS
            );
            if (selected != null) {
                selectedMemberIds.clear();
                selectedMemberIds.addAll(selected);
                for (Map.Entry<String, MaterialCheckBox> entry
                    : memberChecks.entrySet()) {
                    entry.getValue().setChecked(
                        selectedMemberIds.contains(entry.getKey())
                    );
                }
                updateMemberHeaderCount();
                renderMemberOrder();
            }
            ArrayList<String> selectedGroups = state.getStringArrayList(
                STATE_SELECTED_GROUPS
            );
            if (selectedGroups != null && KIND_CONTEXT.equals(kind)) {
                selectedGroupIds.clear();
                selectedGroupIds.addAll(selectedGroups);
                for (Map.Entry<String, MaterialCheckBox> entry
                    : groupChecks.entrySet()) {
                    entry.getValue().setChecked(
                        selectedGroupIds.contains(entry.getKey())
                    );
                }
                updateGroupHeaderCount();
                renderGroupOrder();
            }
            ArrayList<String> languages = state.getStringArrayList(
                STATE_MANUAL_LANGUAGES
            );
            ArrayList<String> descriptionLanguages = state.getStringArrayList(
                STATE_DESCRIPTION_LANGUAGES
            );
            ArrayList<String> descriptions = state.getStringArrayList(
                STATE_MANUAL_DESCRIPTIONS
            );
            ArrayList<String> summaries = state.getStringArrayList(
                STATE_MANUAL_SUMMARIES
            );
            if (descriptionLanguages != null && descriptionRowsContainer != null) {
                descriptionRowsContainer.removeAllViews();
                descriptionInputs.clear();
                for (int index = 0; index < descriptionLanguages.size(); index++) {
                    String language = descriptionLanguages.get(index);
                    if (TextUtils.isEmpty(language)) continue;
                    addDescriptionRow(descriptionRowsContainer, language);
                    EditText input = descriptionInputs.get(language);
                    if (input != null && descriptions != null
                        && index < descriptions.size()) {
                        input.setText(descriptions.get(index));
                    }
                }
                if (descriptionInputs.isEmpty()) {
                    addDescriptionRow(descriptionRowsContainer, defaultLanguage());
                }
            }
            if (languages != null) {
                for (int index = 0; index < languages.size(); index++) {
                    String language = languages.get(index);
                    ManualRow row = manualRows.get(language);
                    if (row == null && manualRowsContainer != null) {
                        addManualRow(manualRowsContainer, language);
                        row = manualRows.get(language);
                    }
                    if (row == null) continue;
                    if (row.summary != null && summaries != null
                        && index < summaries.size()) {
                        row.summary.setText(summaries.get(index));
                    }
                }
                if (!manualRows.isEmpty() && addManualSummaryButton != null) {
                    addManualSummaryButton.setVisibility(View.GONE);
                }
            }
            if (closureLanguageInput != null
                && state.containsKey(STATE_CLOSURE_LANGUAGE)) {
                closureLanguageInput.setText(
                    state.getString(STATE_CLOSURE_LANGUAGE, defaultLanguage())
                );
            }
            dirty = state.getBoolean(STATE_DIRTY, false);
        } finally {
            suppressDirty = false;
        }
    }

    private MaterialCardView card() {
        MaterialCardView card = new MaterialCardView(this);
        card.setCardBackgroundColor(ContextCompat.getColor(this, R.color.het_surface_container));
        card.setRadius(dp(14));
        card.setCardElevation(0);
        card.setStrokeWidth(dp(1));
        card.setStrokeColor(ContextCompat.getColor(this, R.color.het_outline_soft));
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        );
        params.bottomMargin = dp(10);
        card.setLayoutParams(params);
        card.setTag(column());
        card.addView(cardContent(card), new ViewGroup.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        ));
        return card;
    }

    private LinearLayout cardContent(MaterialCardView card) {
        return (LinearLayout) card.getTag();
    }

    private LinearLayout column() {
        LinearLayout value = new LinearLayout(this);
        value.setOrientation(LinearLayout.VERTICAL);
        value.setPadding(dp(12), dp(10), dp(12), dp(10));
        return value;
    }

    private void addSectionTitle(ViewGroup parent, String value) {
        TextView title = addText(parent, value, 12, true);
        title.setPadding(0, 0, 0, dp(6));
    }

    private void addFieldLabel(ViewGroup parent, String value) {
        TextView label = addText(parent, value, 11, true);
        label.setTextColor(ContextCompat.getColor(this, R.color.het_on_surface_muted));
        label.setPadding(0, dp(2), 0, dp(4));
    }

    private EditText editorInput() {
        EditText input = new EditText(this);
        input.setTextSize(14);
        input.setIncludeFontPadding(false);
        input.setTextColor(ContextCompat.getColor(this, R.color.het_on_surface));
        input.setHintTextColor(ContextCompat.getColor(this, R.color.het_on_surface_muted));
        input.setMinHeight(dp(40));
        input.setPadding(dp(10), dp(9), dp(10), dp(9));
        GradientDrawable background = new GradientDrawable();
        background.setColor(ContextCompat.getColor(this, R.color.het_surface));
        background.setStroke(dp(1), ContextCompat.getColor(this, R.color.het_outline_soft));
        background.setCornerRadius(dp(10));
        input.setBackground(background);
        return input;
    }

    private TextView groupHeader(
        ViewGroup parent,
        String title,
        String countText,
        View body
    ) {
        LinearLayout header = new LinearLayout(this);
        header.setGravity(Gravity.CENTER_VERTICAL);
        header.setMinimumHeight(dp(48));
        header.setPadding(dp(12), dp(7), dp(8), dp(7));
        LinearLayout copyColumn = new LinearLayout(this);
        copyColumn.setOrientation(LinearLayout.VERTICAL);
        copyColumn.setGravity(Gravity.CENTER_VERTICAL);
        TextView copy = addText(copyColumn, title, 12, true);
        copy.setIncludeFontPadding(false);
        TextView count = addText(copyColumn, countText, 10, false);
        count.setIncludeFontPadding(false);
        copy.setTag(count);
        header.addView(copyColumn, new LinearLayout.LayoutParams(
            0,
            dp(34),
            1f
        ));
        TextView arrow = new TextView(this);
        arrow.setText("›");
        arrow.setTextSize(21);
        arrow.setGravity(Gravity.CENTER);
        arrow.setTextColor(ContextCompat.getColor(this, R.color.het_on_surface_muted));
        header.addView(arrow, new LinearLayout.LayoutParams(dp(24), dp(34)));
        header.setContentDescription(title);
        header.setOnClickListener(view -> {
            boolean open = body.getVisibility() == View.VISIBLE;
            body.setVisibility(open ? View.GONE : View.VISIBLE);
            arrow.setRotation(open ? 0f : 90f);
        });
        parent.addView(header, matchParams());
        return copy;
    }

    private TextView headerCount(TextView title) {
        Object tag = title == null ? null : title.getTag();
        return tag instanceof TextView ? (TextView) tag : null;
    }

    private void updateMemberHeaderCount() {
        if (memberHeaderCount != null) {
            memberHeaderCount.setText(getString(
                R.string.context_group_editor_member_count,
                selectedMemberIds.size()
            ));
        }
    }

    private void updateGroupHeaderCount() {
        if (groupHeaderCount != null) {
            groupHeaderCount.setText(getString(
                R.string.context_group_editor_group_count,
                selectedGroupIds.size()
            ));
        }
    }

    private TextView addText(ViewGroup parent, String value, int size, boolean bold) {
        TextView text = new TextView(this);
        text.setText(value == null ? "" : value);
        text.setTextSize(size + 2f);
        text.setTextColor(ContextCompat.getColor(
            this,
            bold ? R.color.het_on_surface : R.color.het_on_surface_muted
        ));
        if (bold) text.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        if (parent instanceof MaterialCardView) {
            cardContent((MaterialCardView) parent).addView(text, matchParams());
        } else {
            parent.addView(text, matchParams());
        }
        return text;
    }

    private MaterialButton compactButton(int label) {
        MaterialButton button = new MaterialButton(this);
        button.setAllCaps(false);
        button.setText(label);
        button.setTextSize(13);
        button.setMinWidth(0);
        button.setMinHeight(dp(34));
        button.setInsetTop(0);
        button.setInsetBottom(0);
        button.setPadding(dp(12), dp(7), dp(12), dp(7));
        button.setCornerRadius(dp(999));
        button.setBackgroundTintList(ContextCompat.getColorStateList(
            this,
            R.color.het_surface_container_high
        ));
        button.setTextColor(ContextCompat.getColor(this, R.color.het_on_surface));
        button.setStrokeWidth(dp(1));
        button.setStrokeColor(ContextCompat.getColorStateList(
            this,
            R.color.het_outline_soft
        ));
        return button;
    }

    private MaterialButton smallButton(int label) {
        MaterialButton button = compactButton(label);
        button.setTextSize(13);
        return button;
    }

    private void applyDanger(MaterialButton button) {
        button.setBackgroundTintList(ContextCompat.getColorStateList(this, R.color.het_error));
        button.setTextColor(ContextCompat.getColor(this, R.color.het_on_error));
        button.setStrokeColor(ContextCompat.getColorStateList(this, R.color.het_error));
    }

    private LinearLayout.LayoutParams matchParams() {
        return new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        );
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    private static JSONObject newContextDraft() throws Exception {
        return new JSONObject()
            .put("display_name", "")
            .put("scenes", new JSONArray())
            .put("manual_descriptions", new JSONObject())
            .put("summary", new JSONObject());
    }

    private static JSONObject newGroupDraft() throws Exception {
        return new JSONObject()
            .put("display_name", "")
            .put("contexts", new JSONArray())
            .put("summary", new JSONObject());
    }

    private static JSONObject sceneEntry(String scene) throws Exception {
        long now = System.currentTimeMillis();
        return new JSONObject()
            .put("entry_id", UUID.randomUUID().toString())
            .put("scene", scene)
            .put("scene_file", SceneStore.fileNameForScene(scene))
            .put("created_at", now)
            .put("updated_at", now)
            .put("summaries", new JSONObject());
    }

    private static JSONObject manualRecord(String text) throws Exception {
        return new JSONObject()
            .put("text", text)
            .put("updated_at", System.currentTimeMillis());
    }

    private static JSONObject copyJson(JSONObject value) throws Exception {
        return value == null ? new JSONObject() : new JSONObject(value.toString());
    }

    private static List<JSONObject> copyList(List<JSONObject> values) {
        List<JSONObject> result = new ArrayList<>();
        for (JSONObject value : values) {
            try {
                result.add(copyJson(value));
            } catch (Exception ignored) {
            }
        }
        return result;
    }

    private static JSONObject findById(List<JSONObject> values, String id) {
        for (JSONObject value : values) {
            if (value != null && id.equals(value.optString("id", ""))) return value;
        }
        return null;
    }

    private static void replaceOrAdd(List<JSONObject> values, JSONObject value) {
        String id = value.optString("id", "");
        for (int index = 0; index < values.size(); index++) {
            if (id.equals(values.get(index).optString("id", ""))) {
                values.set(index, value);
                return;
            }
        }
        values.add(value);
    }

    private String safeMessage(Throwable error) {
        String message = error == null ? "" : error.getMessage();
        return message == null || message.trim().isEmpty()
            ? error == null ? "" : error.getClass().getSimpleName()
            : message;
    }

    @Override
    protected void onDestroy() {
        ioExecutor.shutdownNow();
        super.onDestroy();
    }
}
