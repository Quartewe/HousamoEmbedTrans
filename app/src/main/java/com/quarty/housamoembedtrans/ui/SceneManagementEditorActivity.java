package com.quarty.housamoembedtrans.ui;

import com.google.android.material.appbar.MaterialToolbar;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.card.MaterialCardView;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;

import com.quarty.housamoembedtrans.R;
import com.quarty.housamoembedtrans.context.model.GroupContextEntry;
import com.quarty.housamoembedtrans.context.review.ContextReviewCoordinator;
import com.quarty.housamoembedtrans.context.review.ReviewTransactionJournal;
import com.quarty.housamoembedtrans.context.store.SceneContextStore;
import com.quarty.housamoembedtrans.scene.store.SceneAnnotationStore;
import com.quarty.housamoembedtrans.scene.store.SceneStore;
import com.quarty.housamoembedtrans.storage.config.ConfigStore;
import com.quarty.housamoembedtrans.summary.job.SummaryJobStore;
import com.quarty.housamoembedtrans.summary.policy.ContextCompressionCoordinator;
import com.quarty.housamoembedtrans.summary.policy.GroupCompressionCoordinator;
import com.quarty.housamoembedtrans.translation.job.TranslationJobStore;

import android.content.Intent;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextUtils;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.widget.CheckBox;
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
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Full-page Scene editor used by the management detail surface. */
public final class SceneManagementEditorActivity extends AppCompatActivity {
    public static final String EXTRA_SCENE_NAME =
        "com.quarty.housamoembedtrans.ui.EXTRA_SCENE_EDITOR_NAME";
    private static final String STATE_READY =
        "scene_management_editor.state_ready";
    private static final String STATE_SELECTED_CONTEXTS =
        "scene_management_editor.selected_contexts";
    private static final String STATE_SUMMARY_LANGUAGES =
        "scene_management_editor.summary_languages";
    private static final String STATE_SUMMARY_TEXTS =
        "scene_management_editor.summary_texts";
    private static final String STATE_SUMMARY_LANGUAGE_INPUT =
        "scene_management_editor.summary_language_input";
    private static final String STATE_SOURCE_EXPANDED =
        "scene_management_editor.source_expanded";
    private static final String STATE_TRANSLATION_LANGUAGE =
        "scene_management_editor.translation_language";
    private static final String STATE_TRANSLATION_PATCHES =
        "scene_management_editor.translation_patches";
    private static final String STATE_TRANSLATION_COLLAPSED =
        "scene_management_editor.translation_collapsed";
    private static final String STATE_DIRTY =
        "scene_management_editor.dirty";

    private final ExecutorService ioExecutor = Executors.newSingleThreadExecutor();
    private final Map<String, EditText> summaryInputs = new LinkedHashMap<>();
    private final Map<String, CheckBox> relationChecks = new LinkedHashMap<>();
    private final List<String> selectedContextIds = new ArrayList<>();

    private MaterialToolbar toolbar;
    private LinearLayout content;
    private LinearLayout pageActions;
    private TextView status;
    private String sceneName;
    private SceneManagementDetailData.SceneData sceneData;
    private JSONObject annotation;
    private JSONObject initialAnnotation;
    private String expectedAnnotation;
    private List<JSONObject> contexts = new ArrayList<>();
    private List<JSONObject> groups = new ArrayList<>();
    private SceneContextStore contextStore;
    private SceneStore sceneStore;
    private ContextReviewCoordinator reviewCoordinator;
    private PendingProcessMoveController pendingMoveController;
    private boolean dirty;
    private boolean loading;
    private boolean saving;
    private boolean stylePreview;
    private boolean suppressDirty;
    private LinearLayout relationBody;
    private LinearLayout relationAvailable;
    private LinearLayout relationCategories;
    private MaterialCardView relationCard;
    private TextView relationHeader;
    private LinearLayout summaryRows;
    private LinearLayout summaryGeneratedRows;
    private TextView summaryEmpty;
    private TextView summaryManualLabel;
    private MaterialButton summaryAddButton;
    private EditText summaryLanguageInput;
    private LinearLayout sourceRows;
    private TextView sourceArrow;
    private LinearLayout sourceHeader;
    private TextView translationLanguageSelector;
    private String translationLanguage;
    private SceneTranslationEditor translationEditor;
    private final Set<String> collapsedTranslationPaths = new LinkedHashSet<>();
    private boolean summaryHasGenerated;
    private Bundle pendingRestoreState;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        pendingRestoreState = savedInstanceState;
        setContentView(R.layout.activity_scene_management_editor);
        SystemBarInsets.apply(findViewById(R.id.root_scene_management_editor));
        toolbar = findViewById(R.id.toolbar_scene_management_editor);
        content = findViewById(R.id.container_scene_management_editor);
        pageActions = findViewById(R.id.page_actions);
        status = new TextView(this);
        status.setTextAppearance(this, R.style.TextAppearance_HET_StaticDetail_Status);
        content.addView(status, new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        ));
        toolbar.setNavigationOnClickListener(
            view -> getOnBackPressedDispatcher().onBackPressed()
        );
        getOnBackPressedDispatcher().addCallback(this, new OnBackPressedCallback(true) {
            @Override
            public void handleOnBackPressed() {
                if (!dirty || saving) {
                    setEnabled(false);
                    getOnBackPressedDispatcher().onBackPressed();
                } else {
                    confirmDiscard();
                }
            }
        });

        stylePreview = StylePreview.isEnabled(this);
        sceneName = getIntent() == null
            ? null
            : getIntent().getStringExtra(EXTRA_SCENE_NAME);
        if (stylePreview) {
            loadStylePreview();
        } else {
            sceneStore = new SceneStore(this);
            contextStore = new SceneContextStore(this);
            TranslationJobStore translationJobs = TranslationJobStore.getInstance(this);
            SummaryJobStore summaryJobs = SummaryJobStore.createForAndroid(this);
            ContextCompressionCoordinator contextCompression =
                new ContextCompressionCoordinator(contextStore, summaryJobs);
            GroupCompressionCoordinator groupCompression =
                new GroupCompressionCoordinator(
                    contextStore,
                    summaryJobs,
                    contextCompression
                );
            reviewCoordinator = new ContextReviewCoordinator(
                contextStore,
                translationJobs,
                summaryJobs,
                contextCompression,
                groupCompression
            );
            pendingMoveController = new PendingProcessMoveController(this);
            load();
        }
    }

    private void loadStylePreview() {
        JSONObject payload = StylePreview.payloadOf(getIntent());
        try {
            if (payload == null) {
                throw new IllegalStateException("preview payload is unavailable");
            }
            sceneData = SceneManagementDetailData.readPreview(payload);
            if (TextUtils.isEmpty(sceneName)) {
                sceneName = sceneData.sceneName;
            }
            JSONObject previewAnnotation = payload.optJSONObject("annotation");
            annotation = previewAnnotation == null
                ? new JSONObject()
                    .put("version", 1)
                    .put("scene", sceneName)
                : copyJson(previewAnnotation);
            initialAnnotation = copyJson(annotation);
            expectedAnnotation = annotation.toString();
            contexts = copyArray(payload.optJSONArray("contexts"));
            groups = copyArray(payload.optJSONArray("groups"));
            loading = false;
            dirty = false;
            render();
            restoreEditorState(pendingRestoreState);
            pendingRestoreState = null;
        } catch (Exception error) {
            showError(getString(
                R.string.scene_management_editor_failed,
                safeMessage(error)
            ));
        }
    }

    private void load() {
        if (loading || TextUtils.isEmpty(sceneName)) {
            if (TextUtils.isEmpty(sceneName)) {
                showError(getString(R.string.scene_management_editor_missing));
            }
            return;
        }
        loading = true;
        status.setVisibility(View.VISIBLE);
        status.setText(R.string.scene_management_editor_loading);
        ioExecutor.execute(() -> {
            try {
                SceneStore.ValidatedScene valid =
                    sceneStore.readValidSceneByName(sceneName);
                if (valid == null) {
                    throw new IllegalStateException(
                        getString(R.string.scene_management_editor_missing)
                    );
                }
                ConfigStore config = new ConfigStore(this);
                JSONObject characters = config.loadJson(
                    ConfigStore.CHARDICT_FILE_NAME
                ).json;
                JSONObject terms = config.loadJson(
                    ConfigStore.GAMETERMS_FILE_NAME
                ).json;
                SceneManagementDetailData.SceneData data =
                    SceneManagementDetailData.read(valid, characters, terms);
                JSONObject loadedAnnotation = new SceneAnnotationStore(
                    getFilesDir()
                ).read(sceneName);
                JSONObject loadedAnnotationSnapshot = copyJson(loadedAnnotation);
                List<JSONObject> loadedContexts = new ArrayList<>();
                for (JSONObject context : contextStore.listContexts()) {
                    loadedContexts.add(copyJson(context));
                }
                List<JSONObject> loadedGroups = new ArrayList<>();
                for (JSONObject group : contextStore.listGroups()) {
                    loadedGroups.add(copyJson(group));
                }
                runOnUiThread(() -> {
                    if (isFinishing() || isDestroyed()) return;
                    loading = false;
                    sceneData = data;
                    annotation = loadedAnnotation;
                    initialAnnotation = loadedAnnotationSnapshot;
                    expectedAnnotation = loadedAnnotation.toString();
                    contexts = loadedContexts;
                    groups = loadedGroups;
                    dirty = false;
                    render();
                    restoreEditorState(pendingRestoreState);
                    pendingRestoreState = null;
                });
            } catch (Exception error) {
                runOnUiThread(() -> {
                    if (isFinishing() || isDestroyed()) return;
                    loading = false;
                    showError(getString(
                        R.string.scene_management_editor_failed,
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
        content.addView(status, 0, new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        ));
        status.setVisibility(View.GONE);
        if (sceneData == null) return;

        ensureTranslationEditor();

        addEditorHeading();
        addIdentitySection();
        addLanguageSection();
        addSummarySection();
        addRelationSection();
        addSourceSection();
        addBottomActions();
    }

    private void ensureTranslationEditor() {
        if (sceneData == null) return;
        if (translationEditor == null) {
            translationEditor = new SceneTranslationEditor(
                sceneData.source,
                sceneData.sequenceByOrder
            );
        }
        if (!isTranslationLanguageAvailable(translationLanguage)) {
            translationLanguage = defaultTranslationLanguage();
        }
    }

    private void addEditorHeading() {
        TextView heading = addText(content,
            getString(R.string.scene_management_editor_title), 17, true);
        heading.setPadding(0, 0, 0, dp(2));
        TextView hint = addText(content,
            getString(R.string.scene_management_editor_draft_hint), 10, false);
        hint.setPadding(0, 0, 0, dp(9));
    }

    private void addIdentitySection() {
        MaterialCardView card = card();
        addSectionTitle(card, getString(R.string.scene_management_editor_scene_name));
        TextView sceneValue = addText(card, sceneData.sceneName, 11, true);
        sceneValue.setTextColor(ContextCompat.getColor(this, R.color.het_on_surface));
        sceneValue.setPadding(0, 0, 0, dp(2));
        addText(card, getString(R.string.scene_management_editor_source_note), 10, false);
        content.addView(card);
    }

    private void addLanguageSection() {
        MaterialCardView card = card();
        addSectionTitle(card, getString(R.string.scene_management_editor_languages));
        if (sceneData.languages.isEmpty()) {
            addText(card, getString(R.string.scene_detail_no_languages), 12, false);
        } else {
            StringBuilder languageText = new StringBuilder();
            for (String language : sceneData.languages) {
                if (languageText.length() > 0) languageText.append("、");
                languageText.append(languageLabel(language));
            }
            addText(card, languageText.toString(), 11, false);
            View divider = new View(this);
            divider.setBackgroundColor(ContextCompat.getColor(
                this,
                R.color.het_outline_soft
            ));
            cardContent(card).addView(divider, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                dp(1)
            ));
            LinearLayout actionGroup = new LinearLayout(this);
            actionGroup.setOrientation(LinearLayout.VERTICAL);
            actionGroup.setPadding(0, dp(8), 0, 0);
            cardContent(card).addView(actionGroup, matchParams());
            for (String language : sceneData.languages) {
                MaterialButton move = compactButton();
                move.setAllCaps(false);
                move.setTextSize(13);
                move.setText(getString(
                    R.string.scene_management_editor_move_language,
                    languageLabel(language)
                ));
                applyDanger(move);
                move.setEnabled(!stylePreview);
                move.setOnClickListener(view -> moveLanguage(language));
                LinearLayout.LayoutParams moveParams = wrapButtonParams();
                moveParams.gravity = Gravity.END;
                moveParams.bottomMargin = dp(6);
                actionGroup.addView(move, moveParams);
            }
        }
        content.addView(card);
    }

    private void addSummarySection() {
        MaterialCardView card = card();
        addSectionTitle(card, getString(R.string.scene_management_editor_summary));
        LinearLayout body = new LinearLayout(this);
        body.setOrientation(LinearLayout.VERTICAL);
        body.setPadding(0, 0, 0, 0);
        cardContent(card).addView(body, matchParams());
        summaryGeneratedRows = null;
        summaryHasGenerated = false;
        JSONObject generated = sceneData.source == null
            ? null
            : sceneData.source.optJSONObject("summary");
        if (generated != null) {
            Iterator<String> generatedLanguages = generated.keys();
            List<String> generatedKeys = new ArrayList<>();
            while (generatedLanguages.hasNext()) {
                generatedKeys.add(generatedLanguages.next());
            }
            Collections.sort(generatedKeys, String.CASE_INSENSITIVE_ORDER);
            LinearLayout generatedRows = insetPanel();
            for (String language : generatedKeys) {
                String text = generated.optString(language, "").trim();
                if (text.isEmpty()) continue;
                addGeneratedSummaryRow(generatedRows, language, text);
            }
            if (generatedRows.getChildCount() > 0) {
                TextView generatedLabel = addText(
                    body,
                    getString(R.string.scene_management_editor_generated_summary),
                    11,
                    true
                );
                generatedLabel.setPadding(0, 0, 0, dp(4));
                body.addView(generatedRows, matchParams());
                summaryGeneratedRows = generatedRows;
                summaryHasGenerated = true;
            }
        }
        TextView manualLabel = addText(
            body,
            getString(R.string.scene_management_editor_manual_summaries),
            11,
            true
        );
        manualLabel.setPadding(0, summaryHasGenerated ? dp(10) : 0, 0, dp(4));
        summaryManualLabel = manualLabel;
        LinearLayout rows = insetPanel();
        body.addView(rows, matchParams());
        summaryRows = rows;
        summaryInputs.clear();
        JSONObject summaries = annotation == null
            ? null
            : annotation.optJSONObject("manual_summaries");
        if (summaries != null) {
            Iterator<String> languages = summaries.keys();
            List<String> keys = new ArrayList<>();
            while (languages.hasNext()) keys.add(languages.next());
            Collections.sort(keys, String.CASE_INSENSITIVE_ORDER);
            for (String language : keys) {
                JSONObject record = summaries.optJSONObject(language);
                addSummaryRow(rows, language, record == null
                    ? ""
                    : record.optString("text", ""));
            }
        }
        TextView empty = addText(rows,
            getString(R.string.scene_management_editor_summary_empty),
            10,
            false
        );
        summaryEmpty = empty;
        summaryLanguageInput = null;
        MaterialButton add = compactButton(
            R.string.scene_management_editor_add_manual_summary
        );
        summaryAddButton = add;
        add.setAllCaps(false);
        add.setTextSize(13);
        add.setOnClickListener(view -> {
            if (!summaryInputs.isEmpty()) return;
            addSummaryRow(rows, defaultLanguage(), "");
            updateSummaryEmptyState();
            markDirty();
        });
        LinearLayout.LayoutParams addParams = wrapButtonParams();
        addParams.topMargin = dp(8);
        addParams.gravity = Gravity.END;
        body.addView(add, addParams);
        updateSummaryEmptyState();
        content.addView(card);
    }

    private void addGeneratedSummaryRow(
        LinearLayout parent,
        String language,
        String text
    ) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.VERTICAL);
        row.setPadding(0, 0, 0, dp(8));
        addText(row, languageLabel(language), 10, true);
        TextView value = addText(row, text, 12, false);
        value.setTextColor(ContextCompat.getColor(this, R.color.het_on_surface));
        value.setPadding(0, dp(4), 0, 0);
        parent.addView(row, matchParams());
    }

    private void addSummaryRow(
        LinearLayout rows,
        String language,
        String text
    ) {
        MaterialCardView rowCard = card();
        cardContent(rowCard).setPadding(dp(9), dp(8), dp(9), dp(9));
        LinearLayout row = column();
        row.setPadding(0, 0, 0, 0);
        cardContent(rowCard).addView(row, matchParams());
        addText(row, languageLabel(language), 11, true);
        addFieldLabel(row, getString(R.string.scene_management_editor_summary_text));
        EditText input = editorInput();
        input.setMinHeight(dp(76));
        input.setGravity(Gravity.TOP | Gravity.START);
        input.setText(text);
        input.setHint(R.string.scene_context_manual_summary);
        input.addTextChangedListener(new SimpleTextWatcher() {
            @Override public void afterTextChanged(Editable editable) {
                markDirty();
            }
        });
        row.addView(input, matchParams());
        summaryInputs.put(language, input);
        MaterialButton remove = compactButton(R.string.scene_management_editor_remove_summary);
        remove.setAllCaps(false);
        applyDangerText(remove);
        remove.setOnClickListener(view -> {
            summaryInputs.remove(language);
            rows.removeView(rowCard);
            updateSummaryEmptyState();
            markDirty();
        });
        row.addView(remove, wrapButtonParams());
        rows.addView(rowCard);
    }

    private void updateSummaryEmptyState() {
        if (summaryEmpty != null) {
            summaryEmpty.setVisibility(
                summaryInputs.isEmpty() && !summaryHasGenerated
                    ? View.VISIBLE
                    : View.GONE
            );
        }
        if (summaryAddButton != null) {
            summaryAddButton.setVisibility(
                summaryInputs.isEmpty() ? View.VISIBLE : View.GONE
            );
        }
        boolean showManualPanel = !summaryHasGenerated || !summaryInputs.isEmpty();
        if (summaryManualLabel != null) {
            summaryManualLabel.setVisibility(
                showManualPanel ? View.VISIBLE : View.GONE
            );
        }
        if (summaryRows != null) {
            summaryRows.setVisibility(showManualPanel ? View.VISIBLE : View.GONE);
        }
    }

    private void addRelationSection() {
        MaterialCardView card = card();
        String title = getString(R.string.scene_management_editor_relations);
        relationCard = card;
        selectedContextIds.clear();
        selectedContextIds.addAll(readOrderedContextIds());
        cardContent(card).setPadding(0, 0, 0, 0);
        LinearLayout body = column();
        body.setPadding(0, dp(8), 0, dp(10));
        body.setVisibility(View.GONE);
        relationBody = body;
        relationHeader = groupHeader(
            cardContent(card),
            title,
            selectedContextIds.size(),
            body
        );
        cardContent(card).addView(body, matchParams());
        TextView availableLabel = addText(body,
            getString(R.string.scene_management_editor_add_context), 12, true);
        availableLabel.setPadding(dp(8), 0, dp(8), dp(4));
        LinearLayout available = new LinearLayout(this);
        available.setOrientation(LinearLayout.VERTICAL);
        available.setPadding(dp(8), 0, dp(8), 0);
        relationAvailable = available;
        body.addView(available, matchParams());
        TextView categoriesLabel = addText(body,
            getString(R.string.scene_management_editor_relation_categories),
            12,
            true
        );
        categoriesLabel.setPadding(dp(8), dp(10), dp(8), dp(4));
        LinearLayout categories = new LinearLayout(this);
        categories.setOrientation(LinearLayout.VERTICAL);
        categories.setPadding(dp(8), 0, dp(8), dp(4));
        relationCategories = categories;
        body.addView(categories, matchParams());
        renderRelationAvailable(card);
        renderRelationCategories();
        renderRelationOrder(card);
        content.addView(card);
    }

    private void renderRelationAvailable(MaterialCardView card) {
        if (relationAvailable == null) return;
        relationAvailable.removeAllViews();
        relationChecks.clear();
        for (JSONObject context : contexts) {
            String id = context.optString("id", "").trim();
            if (id.isEmpty() || selectedContextIds.contains(id)) continue;
            CheckBox check = new CheckBox(this);
            check.setText(context.optString("display_name", id));
            check.setTextSize(13);
            check.setMinHeight(dp(36));
            check.setPadding(dp(8), dp(6), dp(8), dp(6));
            check.setOnCheckedChangeListener((button, checked) -> {
                if (checked) {
                    if (!selectedContextIds.contains(id)) selectedContextIds.add(id);
                    refreshRelationViews(card);
                    markDirty();
                }
            });
            relationChecks.put(id, check);
            relationAvailable.addView(check, matchParams());
        }
        if (relationAvailable.getChildCount() == 0) {
            addText(relationAvailable,
                getString(R.string.scene_management_editor_relation_available_empty),
                10,
                false
            );
        }
    }

    private void renderRelationCategories() {
        if (relationCategories == null) return;
        relationCategories.removeAllViews();
        addRelationCategories(relationCategories);
    }

    private void refreshRelationViews(MaterialCardView card) {
        updateRelationHeader();
        renderRelationOrder(card);
        renderRelationAvailable(card);
        renderRelationCategories();
    }

    private void renderRelationOrder(MaterialCardView card) {
        LinearLayout body = relationBody == null ? cardContent(card) : relationBody;
        View old = body.getTag() instanceof View ? (View) body.getTag() : null;
        if (old != null) body.removeView(old);
        if (selectedContextIds.isEmpty()) {
            body.setTag(null);
            return;
        }
        LinearLayout order = column();
        order.setPadding(dp(8), dp(4), dp(8), 0);
        for (int index = 0; index < selectedContextIds.size(); index++) {
            String id = selectedContextIds.get(index);
            JSONObject context = findById(contexts, id);
            String label = context == null ? id : context.optString("display_name", id);
            LinearLayout row = new LinearLayout(this);
            row.setGravity(Gravity.CENTER_VERTICAL);
            TextView title = addText(row, label, 12, false);
            LinearLayout.LayoutParams titleParams =
                (LinearLayout.LayoutParams) title.getLayoutParams();
            titleParams.width = 0;
            titleParams.weight = 1;
            title.setLayoutParams(titleParams);
            final int position = index;
            MaterialButton up = smallButton(R.string.scene_management_editor_relation_up);
            up.setEnabled(position > 0);
            up.setOnClickListener(view -> moveSelected(position, position - 1, card));
            row.addView(up);
            MaterialButton down = smallButton(R.string.scene_management_editor_relation_down);
            down.setEnabled(position + 1 < selectedContextIds.size());
            down.setOnClickListener(view -> moveSelected(position, position + 1, card));
            row.addView(down);
            MaterialButton remove = smallButton(R.string.scene_management_editor_relation_remove);
            remove.setOnClickListener(view -> {
                selectedContextIds.remove(id);
                CheckBox check = relationChecks.get(id);
                if (check != null) {
                    check.setOnCheckedChangeListener(null);
                    check.setChecked(false);
                }
                refreshRelationViews(card);
                markDirty();
            });
            row.addView(remove);
            order.addView(row, matchParams());
        }
        body.addView(order, 0, matchParams());
        body.setTag(order);
    }

    private void addRelationCategories(LinearLayout parent) {
        Set<String> selected = new LinkedHashSet<>(selectedContextIds);
        Set<String> added = new LinkedHashSet<>();
        for (JSONObject group : groups) {
            if (group == null) continue;
            JSONArray members = group.optJSONArray("contexts");
            boolean related = false;
            for (int index = 0; members != null && index < members.length(); index++) {
                JSONObject entry = members.optJSONObject(index);
                String contextId = entry == null
                    ? ""
                    : entry.optString(GroupContextEntry.CONTEXT_ID, "").trim();
                if (!contextId.isEmpty() && selected.contains(contextId)) {
                    related = true;
                    break;
                }
            }
            if (!related) continue;
            String groupId = group.optString("id", "").trim();
            String label = group.optString("display_name", groupId).trim();
            if (!label.isEmpty() && added.add(groupId.isEmpty() ? label : groupId)) {
                addText(parent, label, 11, true);
            }
        }
        if (added.isEmpty()) {
            addText(parent,
                getString(R.string.scene_management_editor_relation_categories_empty),
                10,
                false
            );
        }
    }

    private void moveSelected(int from, int to, MaterialCardView card) {
        if (from < 0 || from >= selectedContextIds.size()
            || to < 0 || to >= selectedContextIds.size()) return;
        String value = selectedContextIds.remove(from);
        selectedContextIds.add(to, value);
        renderRelationOrder(card);
        markDirty();
    }

    private void addSourceSection() {
        MaterialCardView card = card();
        cardContent(card).setPadding(0, 0, 0, 0);
        LinearLayout header = new LinearLayout(this);
        header.setGravity(Gravity.CENTER_VERTICAL);
        header.setMinimumHeight(dp(52));
        header.setPadding(dp(12), dp(7), dp(8), dp(7));
        TextView title = addText(
            header,
            getString(R.string.scene_management_editor_source),
            12,
            true
        );
        title.setIncludeFontPadding(false);
        LinearLayout.LayoutParams titleParams =
            (LinearLayout.LayoutParams) title.getLayoutParams();
        titleParams.width = 0;
        titleParams.weight = 1;
        title.setLayoutParams(titleParams);

        final List<String> languages = translationLanguages();
        TextView selector = new TextView(this);
        translationLanguageSelector = selector;
        int selectedLanguage = languages.indexOf(translationLanguage);
        if (selectedLanguage < 0 && translationLanguage != null) {
            for (int index = 0; index < languages.size(); index++) {
                if (translationLanguage.equalsIgnoreCase(languages.get(index))) {
                    selectedLanguage = index;
                    translationLanguage = languages.get(index);
                    break;
                }
            }
        }
        if (selectedLanguage < 0 && !languages.isEmpty()) selectedLanguage = 0;
        if (selectedLanguage >= 0) translationLanguage = languages.get(selectedLanguage);
        selector.setText(
            translationLanguage == null ? "—" : languageLabel(translationLanguage)
        );
        selector.setTextSize(12);
        selector.setTextColor(ContextCompat.getColor(this, R.color.het_primary_strong));
        selector.setGravity(Gravity.CENTER_VERTICAL | Gravity.END);
        selector.setIncludeFontPadding(false);
        selector.setMaxLines(1);
        selector.setEllipsize(TextUtils.TruncateAt.END);
        selector.setPadding(dp(4), 0, dp(4), 0);
        selector.setEnabled(!languages.isEmpty() && !saving);
        selector.setOnClickListener(view -> showTranslationLanguagePicker(selector, languages));
        header.addView(selector, new LinearLayout.LayoutParams(dp(132), dp(40)));

        TextView arrow = new TextView(this);
        arrow.setText("›");
        arrow.setTextSize(21);
        arrow.setGravity(Gravity.CENTER);
        arrow.setTextColor(ContextCompat.getColor(this, R.color.het_on_surface_muted));
        header.addView(arrow, new LinearLayout.LayoutParams(dp(24), dp(34)));
        LinearLayout rows = new LinearLayout(this);
        rows.setOrientation(LinearLayout.VERTICAL);
        rows.setPadding(dp(8), dp(4), dp(8), dp(8));
        rows.setVisibility(View.GONE);
        sourceRows = rows;
        sourceArrow = arrow;
        sourceHeader = header;
        header.setContentDescription(getString(
            R.string.scene_management_editor_source_expand
        ));
        header.setOnClickListener(view -> {
            boolean open = rows.getVisibility() == View.VISIBLE;
            rows.setVisibility(open ? View.GONE : View.VISIBLE);
            arrow.setRotation(open ? 0f : 90f);
            header.setContentDescription(getString(
                open
                    ? R.string.scene_management_editor_source_expand
                    : R.string.scene_management_editor_source_collapse
            ));
        });
        cardContent(card).addView(header, matchParams());
        cardContent(card).addView(rows, matchParams());
        renderTranslationCards();
        content.addView(card);
    }

    private void renderTranslationCards() {
        if (sourceRows == null) return;
        sourceRows.removeAllViews();
        if (translationEditor == null || translationEditor.cards().isEmpty()) {
            addText(
                sourceRows,
                getString(R.string.scene_management_editor_no_source),
                12,
                false
            );
            return;
        }
        for (SceneTranslationEditor.Card card : translationEditor.cards()) {
            addTranslationCard(sourceRows, card);
        }
    }

    private void addTranslationCard(
        ViewGroup parent,
        SceneTranslationEditor.Card cardData
    ) {
        MaterialCardView card = card();
        LinearLayout body = cardContent(card);
        body.setPadding(dp(9), dp(8), dp(9), dp(9));
        LinearLayout header = new LinearLayout(this);
        header.setGravity(Gravity.CENTER_VERTICAL);
        TextView order = addText(
            header,
            cardData.orderLabel.isEmpty() ? "" : "#" + cardData.orderLabel,
            10,
            true
        );
        order.setTextColor(ContextCompat.getColor(this, R.color.het_on_surface_muted));
        order.setIncludeFontPadding(false);
        LinearLayout.LayoutParams orderParams =
            (LinearLayout.LayoutParams) order.getLayoutParams();
        orderParams.width = ViewGroup.LayoutParams.WRAP_CONTENT;
        orderParams.rightMargin = dp(7);
        order.setLayoutParams(orderParams);
        TextView title = addText(
            header,
            translationCardTitle(cardData),
            11,
            true
        );
        title.setIncludeFontPadding(false);
        LinearLayout.LayoutParams titleParams =
            (LinearLayout.LayoutParams) title.getLayoutParams();
        titleParams.width = 0;
        titleParams.weight = 1;
        title.setLayoutParams(titleParams);

        MaterialButton edit = null;
        if (cardData.isText()
            && translationLanguage != null
            && translationEditor.hasTranslation(cardData.path, translationLanguage)) {
            edit = compactButton(R.string.scene_management_editor_edit_translation);
            edit.setTextSize(11);
            edit.setEnabled(!stylePreview);
            header.addView(edit, wrapButtonParams());
        }
        TextView arrow = new TextView(this);
        arrow.setText("›");
        arrow.setTextSize(21);
        arrow.setGravity(Gravity.CENTER);
        arrow.setTextColor(ContextCompat.getColor(this, R.color.het_on_surface_muted));
        header.addView(arrow, new LinearLayout.LayoutParams(dp(24), dp(34)));
        body.addView(header, matchParams());

        LinearLayout details = new LinearLayout(this);
        details.setOrientation(LinearLayout.VERTICAL);
        details.setPadding(dp(4), dp(7), dp(4), 0);
        body.addView(details, matchParams());
        addTranslationCardDetails(details, cardData, edit);
        boolean expanded = !collapsedTranslationPaths.contains(cardData.path);
        details.setVisibility(expanded ? View.VISIBLE : View.GONE);
        arrow.setRotation(expanded ? 90f : 0f);
        header.setContentDescription(expanded
            ? getString(R.string.scene_management_editor_card_collapse)
            : getString(R.string.scene_management_editor_card_expand));
        header.setOnClickListener(view -> {
            boolean open = details.getVisibility() == View.VISIBLE;
            details.setVisibility(open ? View.GONE : View.VISIBLE);
            arrow.setRotation(open ? 0f : 90f);
            if (open) collapsedTranslationPaths.add(cardData.path);
            else collapsedTranslationPaths.remove(cardData.path);
            header.setContentDescription(open
                ? getString(R.string.scene_management_editor_card_expand)
                : getString(R.string.scene_management_editor_card_collapse));
        });
        LinearLayout.LayoutParams cardParams =
            (LinearLayout.LayoutParams) card.getLayoutParams();
        cardParams.bottomMargin = dp(8);
        parent.addView(card, cardParams);
    }

    private void addTranslationCardDetails(
        LinearLayout details,
        SceneTranslationEditor.Card cardData,
        MaterialButton editButton
    ) {
        if (cardData.isText()) {
            addFieldLabel(
                details,
                getString(R.string.scene_management_editor_original_text)
            );
            TextView original = addText(
                details,
                SceneManagementDetailData.restoreProtectedText(
                    cardData.sourceText,
                    sceneData.protectedTokens
                ),
                12,
                false
            );
            original.setTextColor(ContextCompat.getColor(this, R.color.het_on_surface));
            original.setPadding(0, 0, 0, dp(7));
            addTranslationValue(details, cardData, editButton);
        } else {
            addTranslationMeta(details, cardData);
        }
        for (SceneTranslationEditor.Card child : cardData.children) {
            addTranslationCard(details, child);
        }
    }

    private void addTranslationValue(
        LinearLayout parent,
        SceneTranslationEditor.Card cardData,
        MaterialButton editButton
    ) {
        String language = translationLanguage;
        addFieldLabel(parent, languageLabel(language));
        LinearLayout valueContainer = new LinearLayout(this);
        valueContainer.setOrientation(LinearLayout.VERTICAL);
        valueContainer.setTag(cardData.path);
        boolean available = language != null
            && translationEditor.hasTranslation(cardData.path, language);
        if (!available) {
            addText(
                valueContainer,
                getString(R.string.scene_management_editor_translation_missing),
                11,
                false
            );
        } else {
            TextView value = addText(
                valueContainer,
                displayTranslation(translationEditor.draft(cardData.path, language)),
                12,
                false
            );
            value.setTextColor(ContextCompat.getColor(this, R.color.het_on_surface));
            value.setPadding(0, 0, 0, dp(5));
            if (editButton != null) {
                LinearLayout translationContainer = valueContainer;
                editButton.setOnClickListener(view -> {
                    parent.setVisibility(View.VISIBLE);
                    collapsedTranslationPaths.remove(cardData.path);
                    View headerView = editButton.getParent() instanceof View
                        ? (View) editButton.getParent()
                        : null;
                    if (headerView instanceof ViewGroup) {
                        View headerArrow = ((ViewGroup) headerView).getChildAt(
                            ((ViewGroup) headerView).getChildCount() - 1
                        );
                        if (headerArrow != null) headerArrow.setRotation(90f);
                    }
                    if (translationContainer.getChildAt(0) instanceof EditText) {
                        finishTranslationEdit(
                            translationContainer,
                            cardData,
                            editButton
                        );
                    } else {
                        beginTranslationEdit(
                            translationContainer,
                            cardData,
                            editButton
                        );
                    }
                });
            }
        }
        parent.addView(valueContainer, matchParams());
    }

    private void addTranslationMeta(
        LinearLayout parent,
        SceneTranslationEditor.Card cardData
    ) {
        if ("choice".equals(cardData.type)) {
            addSourceMetaText(
                parent,
                getString(R.string.scene_management_editor_source_choice)
            );
            if (!cardData.mergeLabel.trim().isEmpty()) {
                addSourceMetaText(parent, getString(
                    R.string.scene_management_editor_source_merge,
                    cardData.mergeLabel
                ));
            }
        } else if ("branch".equals(cardData.type)) {
            if (!cardData.targetLabel.trim().isEmpty()) {
                addSourceMetaText(parent, getString(
                    R.string.scene_management_editor_source_target,
                    cardData.targetLabel
                ));
            }
        } else if ("if".equals(cardData.type)) {
            String condition = SceneManagementDetailData.restoreProtectedText(
                cardData.condition,
                sceneData.protectedTokens
            );
            if (!condition.trim().isEmpty()) addSourceMetaText(parent, getString(
                R.string.scene_management_editor_source_condition,
                condition
            ));
            if (!cardData.targetLabel.trim().isEmpty()) addSourceMetaText(parent,
                getString(R.string.scene_management_editor_source_target,
                    cardData.targetLabel));
            if (!cardData.mergeLabel.trim().isEmpty()) addSourceMetaText(parent,
                getString(R.string.scene_management_editor_source_merge,
                    cardData.mergeLabel));
        }
    }

    private void addSourceMetaText(ViewGroup parent, String value) {
        if (value == null || value.trim().isEmpty()) return;
        TextView text = addText(parent, value, 10, false);
        text.setIncludeFontPadding(false);
        text.setPadding(0, dp(2), 0, dp(3));
    }

    private void beginTranslationEdit(
        LinearLayout container,
        SceneTranslationEditor.Card cardData,
        MaterialButton editButton
    ) {
        final String language = translationLanguage;
        if (stylePreview || language == null) return;
        if (!translationEditor.hasTranslation(cardData.path, language)) return;
        if (container.getChildCount() < 1) return;
        TextView current = container.getChildAt(0) instanceof TextView
            ? (TextView) container.getChildAt(0)
            : null;
        if (current == null) return;
        EditText input = editorInput();
        input.setGravity(Gravity.TOP | Gravity.START);
        input.setSingleLine(false);
        input.setMinLines(2);
        input.setText(translationEditor.draft(cardData.path, language));
        input.setSelection(input.length());
        input.setTag(language);
        input.addTextChangedListener(new SimpleTextWatcher() {
            @Override public void afterTextChanged(Editable editable) {
                if (translationEditor.setDraft(
                    cardData.path,
                    language,
                    editable.toString()
                )) {
                    markDirty();
                }
            }
        });
        container.removeViewAt(0);
        container.addView(input, 0, matchParams());
        editButton.setText(R.string.scene_management_editor_done_translation);
    }

    private void finishTranslationEdit(
        LinearLayout container,
        SceneTranslationEditor.Card cardData,
        MaterialButton editButton
    ) {
        if (container.getChildCount() < 1
            || !(container.getChildAt(0) instanceof EditText)) return;
        EditText input = (EditText) container.getChildAt(0);
        Object languageTag = input.getTag();
        String language = languageTag instanceof String
            ? (String) languageTag
            : translationLanguage;
        if (language == null) return;
        translationEditor.setDraft(cardData.path, language, input.getText().toString());
        TextView value = new TextView(this);
        value.setText(displayTranslation(
            translationEditor.draft(cardData.path, language)
        ));
        value.setTextSize(14);
        value.setTextColor(ContextCompat.getColor(this, R.color.het_on_surface));
        value.setIncludeFontPadding(false);
        value.setPadding(0, 0, 0, dp(5));
        container.removeView(input);
        container.addView(value, 0, matchParams());
        editButton.setText(R.string.scene_management_editor_edit_translation);
    }

    private String translationCardTitle(SceneTranslationEditor.Card cardData) {
        if ("choice".equals(cardData.type)) {
            return getString(R.string.scene_management_editor_source_choice);
        }
        if ("branch".equals(cardData.type)) {
            return cardData.targetLabel.trim().isEmpty()
                ? getString(R.string.scene_management_editor_source_branch)
                : getString(
                    R.string.scene_management_editor_source_branch_target,
                    cardData.targetLabel
                );
        }
        if ("if".equals(cardData.type)) {
            return getString(R.string.scene_management_editor_source_if);
        }
        if ("option".equals(cardData.type)) {
            return getString(R.string.scene_detail_option);
        }
        return speakerLabel(cardData.speaker);
    }

    private String displayTranslation(String value) {
        String restored = SceneManagementDetailData.restoreProtectedText(
            value == null ? "" : value,
            sceneData == null ? Collections.<JSONObject>emptyList() : sceneData.protectedTokens
        );
        return restored.isEmpty() ? "—" : restored;
    }

    private List<String> translationLanguages() {
        LinkedHashSet<String> values = new LinkedHashSet<>();
        if (sceneData != null) values.addAll(sceneData.languages);
        if (translationEditor != null) values.addAll(translationEditor.languages());
        return new ArrayList<>(values);
    }

    private void showTranslationLanguagePicker(
        TextView selector,
        List<String> languages
    ) {
        if (saving || languages == null || languages.isEmpty()) return;
        StyledPopupMenu popup = new StyledPopupMenu(this, selector, Gravity.END)
            .setWrapContentWidth();
        for (int index = 0; index < languages.size(); index++) {
            MenuItem item = popup.getMenu().add(
                Menu.NONE,
                index + 1,
                index,
                languageLabel(languages.get(index))
            );
            item.setCheckable(true);
            item.setChecked(languages.get(index).equals(translationLanguage));
        }
        popup.setOnMenuItemClickListener(item -> {
            int which = item.getItemId() - 1;
            if (which < 0 || which >= languages.size()) return false;
            translationLanguage = languages.get(which);
            selector.setText(languageLabel(translationLanguage));
            renderTranslationCards();
            return true;
        });
        popup.show();
    }

    private boolean isTranslationLanguageAvailable(String language) {
        if (language == null) return false;
        for (String candidate : translationLanguages()) {
            if (language.equals(candidate) || language.equalsIgnoreCase(candidate)) return true;
        }
        return false;
    }

    private String defaultTranslationLanguage() {
        List<String> languages = translationLanguages();
        if (sceneData != null && !TextUtils.isEmpty(sceneData.targetLanguage)) {
            for (String language : languages) {
                if (sceneData.targetLanguage.equals(language)
                    || sceneData.targetLanguage.equalsIgnoreCase(language)) {
                    return language;
                }
            }
        }
        return languages.isEmpty() ? null : languages.get(0);
    }

    private void addBottomActions() {
        pageActions.setVisibility(View.VISIBLE);
        MaterialButton save = smallButton(R.string.scene_management_editor_save);
        save.setTextSize(13);
        save.setTextColor(ContextCompat.getColor(this, R.color.het_on_primary_container));
        save.setBackgroundTintList(ContextCompat.getColorStateList(
            this,
            R.color.het_primary_container
        ));
        save.setEnabled(!stylePreview);
        save.setOnClickListener(view -> save());
        MaterialButton reset = smallButton(R.string.scene_management_editor_reset);
        reset.setTextSize(13);
        reset.setTextColor(ContextCompat.getColor(this, R.color.het_on_surface));
        reset.setBackgroundTintList(ContextCompat.getColorStateList(this, R.color.het_surface_container_high));
        reset.setOnClickListener(view -> resetEditor());
        LinearLayout.LayoutParams saveParams = wrapButtonParams();
        saveParams.gravity = Gravity.CENTER_VERTICAL;
        saveParams.rightMargin = dp(6);
        pageActions.addView(save, saveParams);
        LinearLayout.LayoutParams resetParams = wrapButtonParams();
        resetParams.gravity = Gravity.CENTER_VERTICAL;
        resetParams.rightMargin = stylePreview ? 0 : dp(6);
        pageActions.addView(reset, resetParams);
        MaterialButton moveScene = compactButton(R.string.scene_management_editor_move_scene);
        moveScene.setTextSize(13);
        applyDanger(moveScene);
        moveScene.setOnClickListener(view -> moveScene());
        if (stylePreview) {
            moveScene.setVisibility(View.GONE);
        }
        LinearLayout.LayoutParams moveParams = wrapButtonParams();
        moveParams.gravity = Gravity.CENTER_VERTICAL;
        moveParams.leftMargin = 0;
        pageActions.addView(moveScene, moveParams);
    }

    private void resetEditor() {
        if (saving || initialAnnotation == null) return;
        try {
            annotation = copyJson(initialAnnotation);
            if (translationEditor != null) translationEditor.reset();
            collapsedTranslationPaths.clear();
            translationLanguage = defaultTranslationLanguage();
            dirty = false;
            render();
        } catch (Exception error) {
            showError(getString(
                R.string.scene_management_editor_failed,
                safeMessage(error)
            ));
        }
    }

    private void save() {
        if (stylePreview || saving || sceneData == null) return;
        List<SceneTranslationEditor.Patch> restoreConflicts =
            translationRestoreConflicts();
        if (!restoreConflicts.isEmpty()) {
            showTranslationRestoreConflicts(restoreConflicts);
            return;
        }
        final JSONObject newAnnotation;
        final JSONObject expectedScene;
        final List<SceneStore.TranslationEdit> translationEdits;
        final List<JSONObject> contextDrafts = new ArrayList<>();
        final boolean membershipChanged;
        try {
            expectedScene = copyJson(sceneData.source);
            translationEdits = captureTranslationEdits();
            newAnnotation = buildAnnotation();
            membershipChanged = sceneMembershipChanged();
            if (membershipChanged) {
                for (JSONObject context : contexts) {
                    JSONObject draft = copyJson(context);
                    updateSceneMembership(draft);
                    contextDrafts.add(draft);
                }
            }
        } catch (Exception error) {
            showError(getString(
                R.string.scene_management_editor_save_failed,
                safeMessage(error)
            ));
            return;
        }
        saving = true;
        setTranslationControlsEnabled(false);
        if (!membershipChanged) {
            saveAnnotationOnly(newAnnotation, expectedScene, translationEdits);
            return;
        }
        ioExecutor.execute(() -> {
            try {
                ContextReviewCoordinator.EditRisk risk =
                    reviewCoordinator.assessReview(contextDrafts, groups);
                runOnUiThread(() -> {
                    if (isFinishing() || isDestroyed()) return;
                    confirmRiskThenSave(
                        newAnnotation,
                        contextDrafts,
                        risk,
                        expectedScene,
                        translationEdits
                    );
                });
            } catch (Exception error) {
                runOnUiThread(() -> {
                    if (isFinishing() || isDestroyed()) return;
                    finishSaveFailure(error);
                });
            }
        });
    }

    /**
     * Annotation-only edits must not manufacture Context/Group drafts. The
     * sidecar has its own AtomicFile/CAS gate and is safe to commit under the
    * shared root lock after interrupted Review recovery.
     */
    private void saveAnnotationOnly(
        JSONObject newAnnotation,
        JSONObject expectedScene,
        List<SceneStore.TranslationEdit> translationEdits
    ) {
        ioExecutor.execute(() -> {
            boolean translationsSaved = false;
            try {
                sceneStore.updateTranslations(
                    sceneName,
                    expectedScene,
                    translationEdits
                );
                translationsSaved = !translationEdits.isEmpty();
                SceneContextStore.withRootAccess(() -> {
                    java.io.File filesRoot = contextStore.getDirectory().getParentFile();
                    ReviewTransactionJournal.recover(filesRoot);
                    new SceneAnnotationStore(filesRoot).saveInReview(
                        sceneStore,
                        sceneName,
                        expectedAnnotation,
                        newAnnotation
                    );
                    return null;
                });
                runOnUiThread(() -> {
                    if (isFinishing() || isDestroyed()) return;
                    finishSaveSuccess();
                });
            } catch (Exception error) {
                final boolean saved = translationsSaved;
                runOnUiThread(() -> {
                    if (isFinishing() || isDestroyed()) return;
                    finishSaveFailure(error, saved);
                });
            }
        });
    }

    private void confirmRiskThenSave(
        JSONObject newAnnotation,
        List<JSONObject> contextDrafts,
        ContextReviewCoordinator.EditRisk risk,
        JSONObject expectedScene,
        List<SceneStore.TranslationEdit> translationEdits
    ) {
        if (risk == null || (risk.affectedWork <= 0
            && risk.userRequestedUnsentIds.isEmpty())) {
            saveWithRisk(
                newAnnotation,
                contextDrafts,
                risk,
                false,
                expectedScene,
                translationEdits
            );
            return;
        }
        String message = risk.userRequestedUnsentIds.isEmpty()
            ? getString(R.string.scene_management_editor_risk_message, risk.affectedWork)
            : getString(R.string.scene_management_editor_risk_unsent, risk.userRequestedUnsentIds.size());
        new UiMaterialAlertDialogBuilder(this)
            .setTitle(R.string.scene_management_editor_risk_title)
            .setMessage(message)
            .setNegativeButton(R.string.cancel_action, (dialog, which) -> resetSaving())
            .setPositiveButton(R.string.scene_management_editor_save,
                (dialog, which) -> saveWithRisk(
                    newAnnotation,
                    contextDrafts,
                    risk,
                    !risk.userRequestedUnsentIds.isEmpty(),
                    expectedScene,
                    translationEdits
                ))
            .setOnCancelListener(dialog -> resetSaving())
            .show();
    }

    private void saveWithRisk(
        JSONObject newAnnotation,
        List<JSONObject> contextDrafts,
        ContextReviewCoordinator.EditRisk risk,
        boolean discardUnsent,
        JSONObject expectedScene,
        List<SceneStore.TranslationEdit> translationEdits
    ) {
        ioExecutor.execute(() -> {
            boolean translationsSaved = false;
            try {
                sceneStore.updateTranslations(
                    sceneName,
                    expectedScene,
                    translationEdits
                );
                translationsSaved = !translationEdits.isEmpty();
                ContextReviewCoordinator.Options options =
                    loadOptions();
                reviewCoordinator.save(
                    contextDrafts,
                    groups,
                    contextStore.getActiveContextId(),
                    contextStore.getActiveGroupId(),
                    options,
                    risk,
                    discardUnsent,
                    sceneName,
                    expectedAnnotation,
                    newAnnotation,
                    sceneStore
                );
                runOnUiThread(() -> {
                    if (isFinishing() || isDestroyed()) return;
                    finishSaveSuccess();
                });
            } catch (Exception error) {
                final boolean saved = translationsSaved;
                runOnUiThread(() -> {
                    if (isFinishing() || isDestroyed()) return;
                    finishSaveFailure(error, saved);
                });
            }
        });
    }

    private void finishSaveFailure(Throwable error) {
        finishSaveFailure(error, false);
    }

    private void finishSaveFailure(Throwable error, boolean translationsSaved) {
        resetSaving();
        showError(getString(
            translationsSaved
                ? R.string.scene_management_editor_translation_save_partial
                : R.string.scene_management_editor_save_failed,
            safeMessage(error)
        ));
    }

    private void resetSaving() {
        saving = false;
        setTranslationControlsEnabled(true);
    }

    private void setTranslationControlsEnabled(boolean enabled) {
        if (translationLanguageSelector != null) {
            translationLanguageSelector.setEnabled(enabled
                && !translationLanguages().isEmpty());
        }
        if (sourceHeader != null) sourceHeader.setEnabled(enabled);
        setTranslationControlsEnabled(content, enabled);
        setTranslationControlsEnabled(pageActions, enabled);
    }

    private void setTranslationControlsEnabled(View view, boolean enabled) {
        if (!(view instanceof ViewGroup)) return;
        ViewGroup group = (ViewGroup) view;
        for (int index = 0; index < group.getChildCount(); index++) {
            View child = group.getChildAt(index);
            if (child instanceof EditText
                || child instanceof MaterialButton
                || child instanceof CheckBox) {
                child.setEnabled(enabled);
            }
            setTranslationControlsEnabled(child, enabled);
        }
    }

    private void finishSaveSuccess() {
        if (isFinishing() || isDestroyed()) return;
        Toast.makeText(
            this,
            R.string.scene_management_editor_saved,
            Toast.LENGTH_SHORT
        ).show();
        setResult(RESULT_OK);
        finish();
    }

    private JSONObject buildAnnotation() throws Exception {
        JSONObject draft = annotation == null
            ? new JSONObject()
            : copyJson(annotation);
        draft.put("version", 1);
        draft.put("scene", sceneName);
        JSONObject summaries = new JSONObject();
        for (Map.Entry<String, EditText> entry : summaryInputs.entrySet()) {
            String language = entry.getKey().trim();
            String text = entry.getValue().getText().toString().trim();
            if (language.isEmpty() || text.isEmpty()) {
                entry.getValue().setError(getString(R.string.error_required));
                throw new IllegalArgumentException(getString(R.string.error_required));
            }
            JSONObject previous = null;
            if (annotation != null) {
                JSONObject previousSummaries = annotation.optJSONObject(
                    "manual_summaries"
                );
                previous = previousSummaries == null
                    ? null
                    : previousSummaries.optJSONObject(language);
            }
            JSONObject record = previous != null
                && text.equals(previous.optString("text", ""))
                ? copyJson(previous)
                : manualRecord(text);
            summaries.put(language, record);
        }
        draft.put("manual_summaries", summaries);
        JSONArray contextOrder = new JSONArray();
        for (String id : selectedContextIds) {
            if (findById(contexts, id) != null) contextOrder.put(id);
        }
        draft.put("context_order", contextOrder);
        return draft;
    }

    private void updateSceneMembership(JSONObject context) throws Exception {
        String id = context.optString("id", "");
        boolean selected = selectedContextIds.contains(id);
        JSONArray scenes = context.optJSONArray("scenes");
        if (scenes == null) scenes = new JSONArray();
        JSONArray updated = new JSONArray();
        boolean found = false;
        for (int index = 0; index < scenes.length(); index++) {
            JSONObject entry = scenes.optJSONObject(index);
            if (entry == null) continue;
            if (sceneName.equals(entry.optString("scene", ""))) {
                found = true;
                if (selected) updated.put(entry);
            } else {
                updated.put(entry);
            }
        }
        if (selected && !found) updated.put(sceneEntry(sceneName));
        context.put("scenes", updated);
    }

    private boolean sceneMembershipChanged() {
        for (JSONObject context : contexts) {
            String id = context == null ? "" : context.optString("id", "");
            if (id.isEmpty()) continue;
            boolean wasSelected = containsScene(context, sceneName);
            if (wasSelected != selectedContextIds.contains(id)) return true;
        }
        return false;
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

    private void updateRelationHeader() {
        if (relationHeader != null) {
            relationHeader.setText(getString(R.string.scene_management_editor_relations));
            Object tag = relationHeader.getTag();
            if (tag instanceof TextView) {
                ((TextView) tag).setText(getString(
                    R.string.scene_management_editor_relation_count,
                    selectedContextIds.size()
                ));
            }
        }
    }

    private void removeAllSummaryRows() {
        if (summaryRows == null) return;
        summaryRows.removeAllViews();
        summaryInputs.clear();
        summaryEmpty = null;
    }

    private void restoreEditorState(Bundle state) {
        if (state == null || !state.getBoolean(STATE_READY, false)
            || sceneData == null) return;
        suppressDirty = true;
        try {
            if (translationEditor != null) {
                translationEditor.restoreState(state.getString(
                    STATE_TRANSLATION_PATCHES,
                    ""
                ));
                if (!translationEditor.restoreConflicts().isEmpty()) {
                    showTranslationRestoreConflicts(
                        translationEditor.restoreConflicts()
                    );
                }
            }
            String restoredLanguage = state.getString(STATE_TRANSLATION_LANGUAGE, "");
            if (!TextUtils.isEmpty(restoredLanguage)
                && isTranslationLanguageAvailable(restoredLanguage)) {
                for (String language : translationLanguages()) {
                    if (restoredLanguage.equals(language)
                        || restoredLanguage.equalsIgnoreCase(language)) {
                        translationLanguage = language;
                        break;
                    }
                }
            }
            collapsedTranslationPaths.clear();
            ArrayList<String> collapsed = state.getStringArrayList(
                STATE_TRANSLATION_COLLAPSED
            );
            if (collapsed != null) collapsedTranslationPaths.addAll(collapsed);
            renderTranslationCards();
            if (translationLanguageSelector != null) {
                int selected = translationLanguages().indexOf(translationLanguage);
                if (selected >= 0) {
                    translationLanguageSelector.setText(languageLabel(translationLanguage));
                }
            }
            ArrayList<String> selected = state.getStringArrayList(
                STATE_SELECTED_CONTEXTS
            );
            if (selected != null) {
                selectedContextIds.clear();
                for (String id : selected) {
                    if (findById(contexts, id) != null
                        && !selectedContextIds.contains(id)) {
                        selectedContextIds.add(id);
                    }
                }
                refreshRelationViews(relationCard);
            }

            ArrayList<String> languages = state.getStringArrayList(
                STATE_SUMMARY_LANGUAGES
            );
            ArrayList<String> texts = state.getStringArrayList(
                STATE_SUMMARY_TEXTS
            );
            if (languages != null && summaryRows != null) {
                removeAllSummaryRows();
                for (int index = 0; index < languages.size(); index++) {
                    String language = languages.get(index);
                    if (TextUtils.isEmpty(language)) continue;
                    String text = texts != null && index < texts.size()
                        ? texts.get(index)
                        : "";
                    addSummaryRow(summaryRows, language, text);
                }
                summaryEmpty = addText(summaryRows,
                    getString(R.string.scene_management_editor_summary_empty),
                    10,
                    false
                );
                updateSummaryEmptyState();
                if (summaryLanguageInput != null) {
                    summaryLanguageInput.setText(state.getString(
                        STATE_SUMMARY_LANGUAGE_INPUT,
                        ""
                    ));
                }
            }
            if (state.containsKey(STATE_SOURCE_EXPANDED)) {
                setSourceExpanded(state.getBoolean(STATE_SOURCE_EXPANDED, false));
            }
            if (summaryLanguageInput != null
                && !state.containsKey(STATE_SUMMARY_LANGUAGES)) {
                summaryLanguageInput.setText(state.getString(
                    STATE_SUMMARY_LANGUAGE_INPUT,
                    ""
                ));
            }
            dirty = state.getBoolean(STATE_DIRTY, false);
        } finally {
            suppressDirty = false;
        }
    }

    @Override
    protected void onSaveInstanceState(Bundle outState) {
        super.onSaveInstanceState(outState);
        if (sceneData == null) return;
        outState.putBoolean(STATE_READY, true);
        outState.putStringArrayList(
            STATE_SELECTED_CONTEXTS,
            new ArrayList<>(selectedContextIds)
        );
        ArrayList<String> languages = new ArrayList<>();
        ArrayList<String> texts = new ArrayList<>();
        for (Map.Entry<String, EditText> entry : summaryInputs.entrySet()) {
            languages.add(entry.getKey());
            texts.add(entry.getValue().getText().toString());
        }
        outState.putStringArrayList(STATE_SUMMARY_LANGUAGES, languages);
        outState.putStringArrayList(STATE_SUMMARY_TEXTS, texts);
        if (summaryLanguageInput != null) {
            outState.putString(
                STATE_SUMMARY_LANGUAGE_INPUT,
                summaryLanguageInput.getText().toString()
            );
        }
        outState.putBoolean(
            STATE_SOURCE_EXPANDED,
            sourceRows != null && sourceRows.getVisibility() == View.VISIBLE
        );
        outState.putString(
            STATE_TRANSLATION_LANGUAGE,
            translationLanguage == null ? "" : translationLanguage
        );
        outState.putStringArrayList(
            STATE_TRANSLATION_COLLAPSED,
            new ArrayList<>(collapsedTranslationPaths)
        );
        if (translationEditor != null) {
            outState.putString(
                STATE_TRANSLATION_PATCHES,
                translationEditor.saveState()
            );
        }
        outState.putBoolean(STATE_DIRTY, dirty);
    }

    /** Package-private hand-off for the persistence integration. */
    List<SceneTranslationEditor.Patch> changedTranslationPatches() {
        return translationEditor == null
            ? Collections.<SceneTranslationEditor.Patch>emptyList()
            : translationEditor.changedPatches();
    }

    List<SceneTranslationEditor.Patch> translationRestoreConflicts() {
        return translationEditor == null
            ? Collections.<SceneTranslationEditor.Patch>emptyList()
            : translationEditor.restoreConflicts();
    }

    private List<SceneStore.TranslationEdit> captureTranslationEdits() {
        List<SceneStore.TranslationEdit> edits = new ArrayList<>();
        for (SceneTranslationEditor.Patch patch : changedTranslationPatches()) {
            edits.add(new SceneStore.TranslationEdit(
                patch.getPath(),
                patch.getLanguage(),
                patch.getExpected(),
                patch.getValue()
            ));
        }
        return Collections.unmodifiableList(edits);
    }

    private void showTranslationRestoreConflicts(
        List<SceneTranslationEditor.Patch> conflicts
    ) {
        if (conflicts == null || conflicts.isEmpty()) return;
        LinearLayout body = column();
        TextView hint = addText(
            body,
            getString(R.string.scene_management_editor_translation_restore_copy_hint),
            11,
            false
        );
        hint.setPadding(0, 0, 0, dp(8));
        TextView drafts = addText(body, restoreConflictDrafts(conflicts), 12, false);
        drafts.setTextColor(ContextCompat.getColor(this, R.color.het_on_surface));
        drafts.setTextIsSelectable(true);
        drafts.setPadding(0, 0, 0, 0);
        new UiMaterialAlertDialogBuilder(this)
            .setTitle(getString(
                R.string.scene_management_editor_translation_restore_conflict,
                conflicts.size()
            ))
            .setView(body)
            .setPositiveButton(R.string.scene_management_editor_keep, null)
            .show();
    }

    private String restoreConflictDrafts(
        List<SceneTranslationEditor.Patch> conflicts
    ) {
        StringBuilder result = new StringBuilder();
        for (SceneTranslationEditor.Patch patch : conflicts) {
            if (result.length() > 0) result.append("\n\n");
            result.append(languageLabel(patch.getLanguage()))
                .append(" · ")
                .append(patch.getPath())
                .append('\n')
                .append(patch.getValue() == null ? "" : patch.getValue());
        }
        return result.toString();
    }

    private void setSourceExpanded(boolean expanded) {
        if (sourceRows == null) return;
        sourceRows.setVisibility(expanded ? View.VISIBLE : View.GONE);
        if (sourceArrow != null) {
            sourceArrow.setRotation(expanded ? 90f : 0f);
        }
        if (sourceHeader != null) {
            sourceHeader.setContentDescription(getString(
                expanded
                    ? R.string.scene_management_editor_source_collapse
                    : R.string.scene_management_editor_source_expand
            ));
        }
    }

    private void moveLanguage(String language) {
        if (stylePreview || pendingMoveController == null || sceneData == null) return;
        try {
            String id = SceneStore.languageCanonicalId(sceneName, language);
            pendingMoveController.confirmMove(
                "language",
                id,
                sceneName + " · " + languageLabel(language),
                () -> {
                    setResult(RESULT_OK);
                    finish();
                }
            );
        } catch (Exception error) {
            showError(safeMessage(error));
        }
    }

    private void moveScene() {
        if (stylePreview || pendingMoveController == null || sceneData == null) return;
        pendingMoveController.confirmMove(
            "scene",
            sceneName,
            sceneName,
            () -> {
                setResult(RESULT_OK);
                finish();
            }
        );
    }

    private void confirmDiscard() {
        new UiMaterialAlertDialogBuilder(this)
            .setTitle(R.string.scene_management_editor_unsaved_title)
            .setMessage(R.string.scene_management_editor_unsaved_message)
            .setNegativeButton(R.string.scene_management_editor_keep, null)
            .setPositiveButton(
                R.string.scene_management_editor_discard,
                (dialog, which) -> {
                    dirty = false;
                    getOnBackPressedDispatcher().onBackPressed();
                }
            )
            .show();
    }

    private void markDirty() {
        if (!suppressDirty) dirty = true;
    }

    private void showError(String message) {
        status.setVisibility(View.VISIBLE);
        status.setText(message == null ? "" : message);
        status.setTextColor(ContextCompat.getColor(this, R.color.het_error));
    }

    private MaterialCardView card() {
        MaterialCardView card = new MaterialCardView(this);
        card.setCardBackgroundColor(ContextCompat.getColor(
            this,
            R.color.het_surface_container
        ));
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
        LinearLayout column = new LinearLayout(this);
        column.setOrientation(LinearLayout.VERTICAL);
        column.setPadding(dp(12), dp(12), dp(12), dp(12));
        return column;
    }

    private LinearLayout insetPanel() {
        LinearLayout panel = new LinearLayout(this);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setPadding(dp(8), dp(8), dp(8), dp(8));
        GradientDrawable background = new GradientDrawable();
        background.setColor(ContextCompat.getColor(this, R.color.het_surface));
        background.setStroke(
            dp(1),
            ContextCompat.getColor(this, R.color.het_outline_soft)
        );
        background.setCornerRadius(dp(10));
        panel.setBackground(background);
        return panel;
    }

    private void addSectionTitle(ViewGroup parent, String text) {
        TextView title = addText(parent, text, 12, true);
        title.setPadding(0, 0, 0, dp(6));
    }

    private void addFieldLabel(ViewGroup parent, String text) {
        TextView label = addText(parent, text, 11, true);
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

    private TextView collapsibleHeader(
        ViewGroup parent,
        String title,
        View body
    ) {
        LinearLayout header = new LinearLayout(this);
        header.setGravity(Gravity.CENTER_VERTICAL);
        header.setMinimumHeight(dp(48));
        header.setPadding(dp(12), dp(7), dp(8), dp(7));
        TextView titleView = addText(header, title, 12, true);
        titleView.setIncludeFontPadding(false);
        LinearLayout.LayoutParams titleParams =
            (LinearLayout.LayoutParams) titleView.getLayoutParams();
        titleParams.width = 0;
        titleParams.weight = 1;
        titleView.setLayoutParams(titleParams);
        TextView arrow = new TextView(this);
        arrow.setText("›");
        arrow.setTextSize(21);
        arrow.setGravity(Gravity.CENTER);
        arrow.setTextColor(ContextCompat.getColor(this, R.color.het_on_surface_muted));
        header.addView(arrow, new LinearLayout.LayoutParams(dp(24), dp(34)));
        header.setContentDescription(getString(
            R.string.scene_management_editor_source_expand
        ));
        header.setOnClickListener(view -> {
            boolean open = body.getVisibility() == View.VISIBLE;
            body.setVisibility(open ? View.GONE : View.VISIBLE);
            arrow.setRotation(open ? 0f : 90f);
            header.setContentDescription(getString(
                open
                    ? R.string.scene_management_editor_source_expand
                    : R.string.scene_management_editor_source_collapse
            ));
        });
        parent.addView(header, matchParams());
        sourceHeader = header;
        return arrow;
    }

    private TextView groupHeader(
        ViewGroup parent,
        String title,
        int count,
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
        copy.setPadding(0, 0, 0, dp(2));
        TextView countText = addText(copyColumn, getString(
            R.string.scene_management_editor_relation_count,
            count
        ), 10, false);
        countText.setIncludeFontPadding(false);
        copy.setTag(countText);
        header.addView(copyColumn, new LinearLayout.LayoutParams(0, dp(34), 1f));
        TextView arrow = new TextView(this);
        arrow.setText("›");
        arrow.setTextSize(21);
        arrow.setGravity(Gravity.CENTER);
        arrow.setTextColor(ContextCompat.getColor(this, R.color.het_on_surface_muted));
        header.addView(arrow, new LinearLayout.LayoutParams(dp(24), dp(34)));
        header.setContentDescription(title);
        header.setOnClickListener(view -> {
            if (saving) return;
            boolean open = body.getVisibility() == View.VISIBLE;
            body.setVisibility(open ? View.GONE : View.VISIBLE);
            arrow.setRotation(open ? 0f : 90f);
        });
        parent.addView(header, matchParams());
        return copy;
    }

    private TextView addValue(ViewGroup parent, String label, String value) {
        TextView labelView = addText(parent, label, 11, true);
        TextView valueView = addText(parent, fallback(value), 11, false);
        valueView.setPadding(0, 0, 0, dp(7));
        return valueView;
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

    private MaterialButton compactButton() {
        MaterialButton button = new MaterialButton(this);
        button.setAllCaps(false);
        button.setMinHeight(dp(34));
        button.setMinWidth(0);
        button.setTextSize(13);
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

    private MaterialButton compactButton(int textRes) {
        MaterialButton button = compactButton();
        button.setText(textRes);
        return button;
    }

    private MaterialButton smallButton(int textRes) {
        return compactButton(textRes);
    }

    private void applyDanger(MaterialButton button) {
        button.setBackgroundTintList(ContextCompat.getColorStateList(this, R.color.het_error));
        button.setTextColor(ContextCompat.getColor(this, R.color.het_on_error));
        button.setStrokeColor(ContextCompat.getColorStateList(this, R.color.het_error));
    }

    private void applyDangerText(MaterialButton button) {
        button.setTextColor(ContextCompat.getColor(this, R.color.het_error));
    }

    private LinearLayout.LayoutParams matchParams() {
        return new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        );
    }

    private LinearLayout.LayoutParams wrapButtonParams() {
        return new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT,
            dp(34)
        );
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    /**
     * The order is HET-only annotation metadata.  Context documents remain
     * the source of membership facts; stale/deleted relations are discarded
     * and newly associated Contexts are appended in store order.
     */
    private List<String> readOrderedContextIds() {
        LinkedHashSet<String> associated = new LinkedHashSet<>();
        for (JSONObject context : contexts) {
            String id = context == null ? "" : context.optString("id", "");
            if (!id.isEmpty() && containsScene(context, sceneName)) associated.add(id);
        }
        List<String> result = new ArrayList<>();
        JSONArray saved = annotation == null ? null : annotation.optJSONArray("context_order");
        for (int index = 0; saved != null && index < saved.length(); index++) {
            String id = saved.optString(index, "");
            if (!id.isEmpty() && associated.remove(id)) result.add(id);
        }
        result.addAll(associated);
        return result;
    }

    private static boolean containsScene(JSONObject context, String scene) {
        JSONArray scenes = context == null ? null : context.optJSONArray("scenes");
        for (int index = 0; scenes != null && index < scenes.length(); index++) {
            JSONObject entry = scenes.optJSONObject(index);
            if (entry != null && scene.equals(entry.optString("scene", ""))) {
                return true;
            }
        }
        return false;
    }

    private static JSONObject findById(List<JSONObject> values, String id) {
        for (JSONObject value : values) {
            if (value != null && id.equals(value.optString("id", ""))) return value;
        }
        return null;
    }

    private static String findLanguage(Map<String, EditText> values, String language) {
        for (String key : values.keySet()) {
            if (key.equals(language) || key.equalsIgnoreCase(language)) return key;
        }
        return null;
    }

    private static JSONObject sceneEntry(String scene) throws Exception {
        long now = System.currentTimeMillis();
        return new JSONObject()
            .put("entry_id", java.util.UUID.randomUUID().toString())
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

    private static List<JSONObject> copyArray(JSONArray values) throws Exception {
        List<JSONObject> result = new ArrayList<>();
        if (values == null) {
            return result;
        }
        for (int index = 0; index < values.length(); index++) {
            JSONObject value = values.optJSONObject(index);
            if (value != null) {
                result.add(copyJson(value));
            }
        }
        return result;
    }

    private String fallback(String value) {
        return value == null || value.trim().isEmpty() ? "—" : value;
    }

    private String speakerLabel(String value) {
        if (value == null || value.trim().isEmpty()) {
            return getString(R.string.scene_management_editor_narration);
        }
        if ("mc".equalsIgnoreCase(value)
            || "character-mc".equalsIgnoreCase(value)) {
            return getString(R.string.scene_management_editor_main_character);
        }
        return value;
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

    private String defaultLanguage() {
        return "zh-cn";
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
        if (pendingMoveController != null) pendingMoveController.close();
        super.onDestroy();
    }

    private abstract static class SimpleTextWatcher implements TextWatcher {
        @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
        @Override public void onTextChanged(CharSequence s, int start, int before, int count) {}
    }
}
