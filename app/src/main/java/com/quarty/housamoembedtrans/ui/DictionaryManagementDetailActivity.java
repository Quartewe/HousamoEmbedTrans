package com.quarty.housamoembedtrans.ui;

import com.quarty.housamoembedtrans.R;
import com.quarty.housamoembedtrans.storage.config.ConfigStore;

import android.content.Intent;
import android.content.res.ColorStateList;
import android.graphics.Typeface;
import android.os.Bundle;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;
import androidx.core.widget.NestedScrollView;

import com.google.android.material.appbar.MaterialToolbar;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.card.MaterialCardView;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.Collections;
import java.util.HashMap;
import java.util.Iterator;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Read-only detail page for one global character or game-term record. */
public final class DictionaryManagementDetailActivity extends AppCompatActivity {
    public static final String EXTRA_KIND =
        "com.quarty.housamoembedtrans.ui.EXTRA_DICTIONARY_KIND";
    public static final String EXTRA_KEY =
        "com.quarty.housamoembedtrans.ui.EXTRA_DICTIONARY_KEY";

    private static final String KIND_CHARACTER = "character";
    private static final String KIND_TERM = "term";
    private static final int REQUEST_EDIT_RECORD = 4105;
    private static final int I18N_META_CARD_HEIGHT_DP = 72;
    private static final float AUXILIARY_META_CARD_HEIGHT_MULTIPLIER = 1.4f;
    public static final String EXTRA_EDITOR_MOVED_PENDING =
        "com.quarty.housamoembedtrans.ui.EXTRA_EDITOR_MOVED_PENDING";
    private static final String STATE_EXPANDED = "dictionary_detail.expanded";
    private static final String STATE_SCROLL_Y = "dictionary_detail.scroll_y";

    private final ExecutorService ioExecutor = Executors.newSingleThreadExecutor();
    private final Set<String> expandedCards = new LinkedHashSet<>();

    private NestedScrollView scrollView;
    private MaterialToolbar toolbar;
    private TextView status;
    private LinearLayout content;
    private LinearLayout pageActions;
    private String kind;
    private String key;
    private JSONObject record;
    private boolean stylePreview;
    private Map<String, String> relationshipTargetNames = Collections.emptyMap();
    private long loadGeneration;
    private boolean lifecycleStarted;
    private boolean destroyed;
    private boolean editingAllowed;
    private PendingProcessMoveController pendingProcessMoveController;
    private MaterialButton editButton;
    private MaterialButton moveButton;
    private boolean pendingMoveBusy;
    private int savedScrollY = -1;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_dictionary_management_detail);
        SystemBarInsets.apply(findViewById(R.id.root_dictionary_management_detail));

        toolbar = findViewById(R.id.toolbar_dictionary_management_detail);
        toolbar.setNavigationOnClickListener(
            view -> getOnBackPressedDispatcher().onBackPressed()
        );
        scrollView = findViewById(R.id.scroll_dictionary_management_detail);
        status = findViewById(R.id.tv_dictionary_management_detail_status);
        content = findViewById(R.id.container_dictionary_management_detail);
        pageActions = findViewById(R.id.page_actions);

        if (savedInstanceState != null) {
            String[] expanded = savedInstanceState.getStringArray(STATE_EXPANDED);
            if (expanded != null) {
                Collections.addAll(expandedCards, expanded);
            }
            savedScrollY = savedInstanceState.getInt(STATE_SCROLL_Y, -1);
        }

        Intent intent = getIntent();
        kind = intent == null ? null : intent.getStringExtra(EXTRA_KIND);
        key = intent == null ? null : intent.getStringExtra(EXTRA_KEY);
        stylePreview = isStylePreviewRequest();
        if (stylePreview) {
            record = StylePreview.payloadOf(intent);
            if (record == null) {
                record = StylePreview.sample(
                    isCharacter()
                        ? StylePreview.KIND_CHARACTER_DETAIL
                        : StylePreview.KIND_TERM_DETAIL
                );
            }
            editingAllowed = false;
            toolbar.setTitle(isCharacter()
                ? R.string.dictionary_detail_character_title
                : R.string.dictionary_detail_term_title);
            renderRecord();
            return;
        }
        if (!isSupportedRequest()) {
            showFailure(getString(R.string.dictionary_detail_unavailable));
        } else {
            if (!(isCharacter() && "mc".equals(key))) {
                pendingProcessMoveController =
                    new PendingProcessMoveController(this);
            }
            toolbar.setTitle(isCharacter()
                ? R.string.dictionary_detail_character_title
                : R.string.dictionary_detail_term_title);
            status.setText(R.string.dictionary_detail_loading);
            addEditButton(false);
        }
    }

    @Override
    protected void onStart() {
        super.onStart();
        lifecycleStarted = true;
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (isSupportedRequest() && !stylePreview) {
            loadRecordAsync();
        }
    }

    @Override
    protected void onPause() {
        rememberScroll();
        super.onPause();
    }

    @Override
    protected void onStop() {
        lifecycleStarted = false;
        loadGeneration++;
        super.onStop();
    }

    @Override
    protected void onSaveInstanceState(Bundle outState) {
        rememberScroll();
        outState.putStringArray(
            STATE_EXPANDED,
            expandedCards.toArray(new String[0])
        );
        outState.putInt(STATE_SCROLL_Y, savedScrollY);
        super.onSaveInstanceState(outState);
    }

    @Override
    protected void onDestroy() {
        destroyed = true;
        loadGeneration++;
        if (pendingProcessMoveController != null) {
            pendingProcessMoveController.close();
            pendingProcessMoveController = null;
        }
        ioExecutor.shutdownNow();
        super.onDestroy();
    }

    private void loadRecordAsync() {
        final long request = ++loadGeneration;
        final Locale displayLocale = interfaceLocale();
        record = null;
        editingAllowed = false;
        content.removeAllViews();
        pageActions.removeAllViews();
        pageActions.setVisibility(View.GONE);
        status.setVisibility(View.VISIBLE);
        status.setText(R.string.dictionary_detail_loading);
        addEditButton(false);

        ioExecutor.execute(() -> {
            try {
                ConfigStore configStore = new ConfigStore(this);
                String fileName = isCharacter()
                    ? ConfigStore.CHARDICT_FILE_NAME
                    : ConfigStore.GAMETERMS_FILE_NAME;
                ConfigStore.JsonLoadResult result = configStore.loadJson(fileName);
                JSONObject loaded = result.json.optJSONObject(key);
                if (loaded == null) {
                    throw new IllegalStateException(
                        getString(R.string.dictionary_detail_missing)
                    );
                }
                Map<String, String> targetNames = isCharacter()
                    ? buildRelationshipTargetNames(result.json, displayLocale)
                    : Collections.emptyMap();
                runOnUiThread(() -> {
                    if (!isCurrentRequest(request)) {
                        return;
                    }
                    record = loaded;
                    relationshipTargetNames = targetNames;
                    editingAllowed = !result.invalidUserOverride;
                    renderRecord();
                });
            } catch (Exception error) {
                runOnUiThread(() -> {
                    if (!isCurrentRequest(request)) {
                        return;
                    }
                    showFailure(getString(
                        R.string.dictionary_detail_load_failed,
                        safeMessage(error)
                    ));
                });
            }
        });
    }

    private boolean isCurrentRequest(long request) {
        return !destroyed
            && lifecycleStarted
            && !isFinishing()
            && !isDestroyed()
            && request == loadGeneration;
    }

    private void renderRecord() {
        if (record == null) {
            showFailure(getString(R.string.dictionary_detail_missing));
            return;
        }
        status.setVisibility(View.GONE);
        content.removeAllViews();
        pageActions.removeAllViews();
        pageActions.setVisibility(View.GONE);

        LinearLayout heading = new LinearLayout(this);
        heading.setOrientation(LinearLayout.HORIZONTAL);
        heading.setGravity(Gravity.TOP | Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams headingParams = new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        );
        headingParams.bottomMargin = dp(8);
        LinearLayout headingCopy = new LinearLayout(this);
        headingCopy.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams copyParams = new LinearLayout.LayoutParams(
            0,
            ViewGroup.LayoutParams.WRAP_CONTENT,
            1f
        );
        TextView title = new TextView(this);
        title.setTextSize(19);
        title.setTextColor(ContextCompat.getColor(this, R.color.het_on_surface));
        title.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        title.setIncludeFontPadding(false);
        title.setText(displayRecordTitle());
        title.setMaxLines(1);
        title.setEllipsize(TextUtils.TruncateAt.END);
        title.setTextIsSelectable(true);
        headingCopy.addView(title);
        TextView kindLabel = new TextView(this);
        kindLabel.setTextSize(12);
        kindLabel.setTextColor(ContextCompat.getColor(this, R.color.het_on_surface_muted));
        kindLabel.setIncludeFontPadding(false);
        kindLabel.setText(isCharacter()
            ? R.string.dictionary_detail_character_kind
            : R.string.dictionary_detail_term_kind);
        LinearLayout.LayoutParams kindParams = new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        );
        kindParams.topMargin = dp(3);
        headingCopy.addView(kindLabel, kindParams);
        heading.addView(headingCopy, copyParams);
        addMoveButtonToActions();
        content.addView(heading, headingParams);

        LinearLayout metadata = new LinearLayout(this);
        metadata.setOrientation(LinearLayout.VERTICAL);
        if (isCharacter()) {
            addMetaRow(
                metadata,
                new String[] {
                    getString(R.string.dictionary_detail_original_name),
                    getString(R.string.dictionary_detail_simplified_chinese)
                },
                new String[] {
                    "mc".equals(key)
                        ? getString(R.string.management_batch_main_character_label)
                        : key,
                    value("zh-cn")
                }
            );
            addMetaRow(
                metadata,
                new String[] {
                    getString(R.string.dictionary_detail_traditional_chinese),
                    getString(R.string.dictionary_detail_english)
                },
                new String[] {value("zh-tw"), value("en")}
            );
            addMetaRow(
                metadata,
                new String[] {
                    getString(R.string.dictionary_detail_alias),
                    getString(R.string.dictionary_detail_school)
                },
                new String[] {
                    arraySummary("alias", true),
                    arraySummary("school", false)
                }
            );
            addMetaRow(
                metadata,
                new String[] {
                    getString(R.string.dictionary_detail_guild),
                    getString(R.string.dictionary_detail_origin_world)
                },
                new String[] {
                    arraySummary("guild", false),
                    arraySummary("origin_world", false)
                }
            );
            addMetaRow(
                metadata,
                new String[] {
                    getString(R.string.dictionary_detail_relationships),
                    getString(R.string.dictionary_detail_speech_style)
                },
                new String[] {
                    arraySummary("relationships", true),
                    value("speech_style")
                }
            );
        } else {
            addMetaRow(
                metadata,
                new String[] {
                    getString(R.string.dictionary_detail_original_name),
                    getString(R.string.dictionary_detail_simplified_chinese)
                },
                new String[] {key, value("zh-cn")}
            );
            addMetaRow(
                metadata,
                new String[] {
                    getString(R.string.dictionary_detail_traditional_chinese),
                    getString(R.string.dictionary_detail_english)
                },
                new String[] {value("zh-tw"), value("en")}
            );
        }
        content.addView(metadata, new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        ));
        addDescriptionParagraphs();
        addEditButton(editingAllowed);
        restoreScrollIfNeeded();
    }

    private String displayRecordTitle() {
        if (isCharacter() && "mc".equals(key)) {
            return getString(R.string.management_batch_main_character_label);
        }
        String localized = localizedRecordValue();
        return TextUtils.isEmpty(localized) ? key : localized;
    }

    private String localizedRecordValue() {
        Locale locale = interfaceLocale();
        String language = locale.getLanguage();
        String country = locale.getCountry();
        String localized;
        if ("en".equals(language)) {
            localized = value("en");
        } else if ("zh".equals(language)
            && ("TW".equalsIgnoreCase(country)
                || "HK".equalsIgnoreCase(country)
                || "MO".equalsIgnoreCase(country))) {
            localized = value("zh-tw");
        } else {
            localized = value("zh-cn");
        }
        if (TextUtils.isEmpty(localized)) {
            localized = value("name");
        }
        if (TextUtils.isEmpty(localized)) {
            localized = value("en");
        }
        return localized;
    }

    private void addMetaRow(
        LinearLayout parent,
        String[] labels,
        String[] values
    ) {
        LinearLayout row = new LinearLayout(this);
        row.setGravity(Gravity.TOP);
        row.setBaselineAligned(false);
        LinearLayout.LayoutParams rowParams = new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        );
        rowParams.bottomMargin = dp(8);
        parent.addView(row, rowParams);
        for (int index = 0; index < labels.length; index++) {
            boolean i18nField = isI18nField(labels[index]);
            MaterialCardView cell = metaCell(labels[index], values[index]);
            LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                0,
                dp(i18nField
                    ? I18N_META_CARD_HEIGHT_DP
                    : Math.round(
                        I18N_META_CARD_HEIGHT_DP
                            * AUXILIARY_META_CARD_HEIGHT_MULTIPLIER
                    )),
                1f
            );
            if (index > 0) {
                params.leftMargin = dp(4);
            }
            if (index + 1 < labels.length) {
                params.rightMargin = dp(4);
            }
            row.addView(cell, params);
        }
    }

    private MaterialCardView metaCell(String label, String rawValue) {
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
        LinearLayout column = new LinearLayout(this);
        column.setOrientation(LinearLayout.VERTICAL);
        column.setPadding(dp(10), dp(10), dp(10), dp(10));

        TextView labelView = new TextView(this);
        labelView.setTextSize(12);
        labelView.setTextColor(ContextCompat.getColor(this, R.color.het_on_surface_muted));
        labelView.setIncludeFontPadding(false);
        labelView.setText(label);
        labelView.setSingleLine(true);
        labelView.setEllipsize(TextUtils.TruncateAt.END);
        column.addView(labelView);

        TextView valueView = new TextView(this);
        valueView.setTextSize(13);
        valueView.setTextColor(ContextCompat.getColor(this, R.color.het_on_surface));
        valueView.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        valueView.setIncludeFontPadding(false);
        valueView.setEllipsize(TextUtils.TruncateAt.END);
        valueView.setHorizontallyScrolling(false);
        valueView.setText(TextUtils.isEmpty(rawValue)
            ? getString(R.string.dictionary_detail_unfilled)
            : rawValue);
        valueView.setTextIsSelectable(false);
        valueView.setMovementMethod(null);
        LinearLayout.LayoutParams valueParams = new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            0,
            1f
        );
        valueParams.topMargin = dp(3);
        valueView.addOnLayoutChangeListener((view, left, top, right, bottom,
            oldLeft, oldTop, oldRight, oldBottom) -> {
            valueView.post(() -> {
                int availableHeight = valueView.getHeight()
                    - valueView.getCompoundPaddingTop()
                    - valueView.getCompoundPaddingBottom();
                android.text.Layout layout = valueView.getLayout();
                if (layout == null) {
                    return;
                }
                int completeLines = 0;
                for (int index = 0; index < layout.getLineCount(); index++) {
                    if (layout.getLineBottom(index) > availableHeight) {
                        break;
                    }
                    completeLines++;
                }
                if (completeLines < layout.getLineCount()) {
                    int maxLines = Math.max(1, completeLines);
                    if (valueView.getMaxLines() != maxLines) {
                        valueView.setMaxLines(maxLines);
                    }
                }
            });
        });
        column.addView(valueView, valueParams);
        column.setLayoutParams(new ViewGroup.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT
        ));
        card.addView(column);
        return card;
    }

    private boolean isI18nField(String label) {
        return getString(R.string.dictionary_detail_original_name).equals(label)
            || getString(R.string.dictionary_detail_simplified_chinese).equals(label)
            || getString(R.string.dictionary_detail_traditional_chinese).equals(label)
            || getString(R.string.dictionary_detail_english).equals(label);
    }

    private void addDescriptionParagraphs() {
        if (isCharacter()) {
            addDescriptionParagraph(value("info"));
            addDescriptionParagraph(value("description"));
        } else {
            addDescriptionParagraph(value("description"));
        }
    }

    private void addDescriptionParagraph(String rawValue) {
        if (TextUtils.isEmpty(rawValue)) {
            return;
        }
        TextView paragraph = new TextView(this);
        paragraph.setTextSize(12);
        paragraph.setTextColor(ContextCompat.getColor(this, R.color.het_on_surface_muted));
        paragraph.setIncludeFontPadding(false);
        paragraph.setLineSpacing(0, 1.5f);
        paragraph.setText(rawValue);
        paragraph.setTextIsSelectable(true);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        );
        params.topMargin = dp(2);
        params.bottomMargin = dp(2);
        content.addView(paragraph, params);
    }

    private String arraySummary(String field, boolean objectEntries) {
        JSONArray values = record == null ? null : record.optJSONArray(field);
        if (values == null || values.length() == 0) {
            return "";
        }
        List<String> parts = new ArrayList<>();
        for (int index = 0; index < values.length(); index++) {
            Object item = values.opt(index);
            String part = "";
            if (item instanceof JSONObject && objectEntries) {
                JSONObject object = (JSONObject) item;
                if ("alias".equals(field)) {
                    part = firstNonEmpty(
                        object.optString("name", ""),
                        object.optString("zh-cn", ""),
                        object.optString("zh-tw", ""),
                        object.optString("en", "")
                    );
                } else {
                    String target = firstNonEmpty(
                        object.optString("target", ""),
                        object.optString("name", "")
                    );
                    String mappedTarget = relationshipTargetNames.get(target);
                    if (!TextUtils.isEmpty(mappedTarget)) {
                        target = mappedTarget;
                    }
                    if ("mc".equals(target)) {
                        target = getString(R.string.management_batch_main_character_label);
                    }
                    String type = firstNonEmpty(
                        object.optString("type", ""),
                        object.optString("relation", "")
                    );
                    part = joinParts(target, type);
                }
            } else if (item instanceof JSONObject) {
                JSONObject object = (JSONObject) item;
                part = firstNonEmpty(
                    object.optString("name", ""),
                    object.optString("value", ""),
                    object.optString("zh-cn", ""),
                    object.optString("en", "")
                );
            } else if (item != null && !JSONObject.NULL.equals(item)) {
                part = String.valueOf(item);
            }
            if (!TextUtils.isEmpty(part.trim())) {
                parts.add(part.trim());
            }
        }
        return TextUtils.join("、", parts);
    }

    private String firstNonEmpty(String... values) {
        for (String candidate : values) {
            if (!TextUtils.isEmpty(candidate)) {
                return candidate;
            }
        }
        return "";
    }

    private String joinParts(String first, String second) {
        if (TextUtils.isEmpty(first)) {
            return second;
        }
        if (TextUtils.isEmpty(second)) {
            return first;
        }
        return first + " · " + second;
    }

    private void addEditButton(boolean enabled) {
        editButton = detailButton(R.style.Widget_HET_Button_Secondary);
        editButton.setText(isCharacter()
            ? ("mc".equals(key) ? R.string.edit_mc : R.string.edit_character)
            : R.string.edit_game_term);
        editButton.setAllCaps(false);
        editButton.setEnabled(
            (stylePreview || enabled) && !pendingMoveBusy
        );
        editButton.setOnClickListener(view -> openEditor());
        pageActions.setVisibility(View.VISIBLE);
        pageActions.addView(editButton, actionParams());
        if (record == null) {
            moveButton = null;
        }
    }

    private LinearLayout.LayoutParams actionParams() {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        );
        params.gravity = Gravity.CENTER_VERTICAL;
        if (pageActions.getChildCount() > 0) {
            params.leftMargin = dp(8);
        }
        return params;
    }

    private MaterialButton detailButton(int role) {
        MaterialButton button = new MaterialButton(this);
        boolean danger = role == R.style.Widget_HET_Button_Danger;
        button.setAllCaps(false);
        button.setTextSize(13);
        button.setMinHeight(dp(34));
        button.setMinWidth(0);
        button.setPadding(dp(12), 0, dp(12), 0);
        button.setCornerRadius(dp(17));
        button.setInsetTop(0);
        button.setInsetBottom(0);
        button.setBackgroundTintList(ColorStateList.valueOf(
            ContextCompat.getColor(
                this,
                danger
                    ? R.color.het_error
                    : R.color.het_surface_container_high
            )
        ));
        button.setTextColor(ContextCompat.getColor(
            this,
            danger ? R.color.het_on_error : R.color.het_on_surface
        ));
        button.setStrokeColor(ColorStateList.valueOf(
            ContextCompat.getColor(
                this,
                danger ? R.color.het_error : R.color.het_outline_soft
            )
        ));
        button.setStrokeWidth(dp(1));
        return button;
    }

    private void addMoveButtonToActions() {
        moveButton = null;
        if (stylePreview
            || record == null
            || (isCharacter() && "mc".equals(key))) {
            return;
        }
        moveButton = detailButton(R.style.Widget_HET_Button_Danger);
        moveButton.setText(R.string.dictionary_editor_move_pending);
        moveButton.setContentDescription(getString(R.string.dictionary_editor_move_pending));
        moveButton.setEnabled(
            editingAllowed
                && !pendingMoveBusy
                && pendingProcessMoveController != null
        );
        moveButton.setOnClickListener(view -> moveRecordToPending());
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT,
            dp(34)
        );
        params.gravity = Gravity.CENTER_VERTICAL;
        if (pageActions.getChildCount() > 0) {
            params.leftMargin = dp(8);
        }
        pageActions.setVisibility(View.VISIBLE);
        pageActions.addView(moveButton, params);
    }

    private void moveRecordToPending() {
        if (stylePreview
            || !isSupportedRequest()
            || (isCharacter() && "mc".equals(key))
            || !editingAllowed
            || record == null
            || pendingProcessMoveController == null
            || pendingMoveBusy) {
            return;
        }
        setPendingMoveBusy(true);
        pendingProcessMoveController.confirmMove(
            isCharacter() ? "character" : "term",
            key,
            key,
            () -> {
                setResult(RESULT_OK);
                finish();
            },
            () -> setPendingMoveBusy(false)
        );
    }

    private void setPendingMoveBusy(boolean busy) {
        pendingMoveBusy = busy;
        if (editButton != null) {
            editButton.setEnabled(!busy && editingAllowed && record != null);
        }
        if (moveButton != null) {
            moveButton.setEnabled(
                !busy
                    && editingAllowed
                    && record != null
                    && pendingProcessMoveController != null
            );
        }
    }

    private void openEditor() {
        if (stylePreview) {
            startActivity(StylePreview.intentFor(
                this,
                isCharacter()
                    ? StylePreview.KIND_CHARACTER_EDITOR
                    : StylePreview.KIND_TERM_EDITOR
            ));
            return;
        }
        if (record == null || !isSupportedRequest() || !editingAllowed || pendingMoveBusy) {
            return;
        }
        Intent intent = new Intent(
            this,
            isCharacter()
                ? CharacterDictionaryActivity.class
                : GameTermsActivity.class
        );
        intent.putExtra(
            isCharacter()
                ? CharacterDictionaryActivity.EXTRA_CHARACTER_NAME
                : GameTermsActivity.EXTRA_TERM_NAME,
            key
        );
        startActivityForResult(intent, REQUEST_EDIT_RECORD);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != REQUEST_EDIT_RECORD || resultCode != RESULT_OK) {
            return;
        }
        if (data != null && data.getBooleanExtra(EXTRA_EDITOR_MOVED_PENDING, false)) {
            setResult(RESULT_OK, data);
            finish();
            return;
        }
        loadRecordAsync();
    }

    private void addCharacterArray(
        String field,
        int labelResource,
        boolean objectEntries
    ) {
        if (!record.has(field)) {
            return;
        }
        JSONArray values = record.optJSONArray(field);
        if (values == null) {
            return;
        }
        String cardKey = "character." + field;
        boolean expanded = expandedCards.contains(cardKey);
        MaterialCardView card = cardColumn();
        LinearLayout header = new LinearLayout(this);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);
        header.setMinimumHeight(dp(56));
        header.setPadding(dp(16), dp(8), dp(12), dp(8));
        TextView title = new TextView(this);
        title.setTextAppearance(this, R.style.Widget_HET_SectionTitle);
        title.setText(labelResource);
        header.addView(title, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        TextView count = new TextView(this);
        count.setTextAppearance(this, R.style.Widget_HET_SectionArrow);
        count.setText(getString(R.string.dictionary_detail_array_count, values.length()));
        header.addView(count);
        TextView arrow = new TextView(this);
        arrow.setTextAppearance(this, R.style.Widget_HET_SectionArrow);
        header.addView(arrow, new LinearLayout.LayoutParams(dp(32), ViewGroup.LayoutParams.WRAP_CONTENT));

        LinearLayout body = new LinearLayout(this);
        body.setOrientation(LinearLayout.VERTICAL);
        body.setPadding(dp(16), 0, dp(16), dp(12));
        if (values.length() == 0) {
            addText(body, getString(R.string.dictionary_detail_array_empty), true);
        } else {
            for (int index = 0; index < values.length(); index++) {
                Object item = values.opt(index);
                if (objectEntries && item instanceof JSONObject) {
                    JSONObject object = (JSONObject) item;
                    if ("alias".equals(field)) {
                        addField(
                            body,
                            getString(R.string.dictionary_detail_original_name),
                            object.optString("name", "")
                        );
                        addField(
                            body,
                            getString(R.string.dictionary_detail_english),
                            object.optString("en", "")
                        );
                        addField(
                            body,
                            getString(R.string.dictionary_detail_traditional_chinese),
                            object.optString("zh-tw", "")
                        );
                        addField(
                            body,
                            getString(R.string.dictionary_detail_simplified_chinese),
                            object.optString("zh-cn", "")
                        );
                        addField(body, getString(R.string.field_called), object.optString("called", ""));
                    } else {
                        String target = object.optString("target", "");
                        String mappedTarget = relationshipTargetNames.get(target);
                        if (!TextUtils.isEmpty(mappedTarget)) {
                            target = mappedTarget;
                        }
                        if ("mc".equals(target)) {
                            target = getString(R.string.management_batch_main_character_label);
                        }
                        addField(body, getString(R.string.field_target), target);
                        addField(body, getString(R.string.field_type), object.optString("type", ""));
                    }
                } else if (!objectEntries && item instanceof String) {
                    addText(body, (String) item, false);
                }
            }
        }
        body.setVisibility(expanded ? View.VISIBLE : View.GONE);
        header.setSelected(expanded);
        arrow.setText(expanded ? R.string.array_indicator_expanded : R.string.array_indicator_collapsed);
        header.setContentDescription(getString(
            expanded
                ? R.string.dictionary_detail_collapse_array
                : R.string.dictionary_detail_expand_array,
            getString(labelResource)
        ));
        header.setOnClickListener(view -> {
            boolean next = body.getVisibility() != View.VISIBLE;
            body.setVisibility(next ? View.VISIBLE : View.GONE);
            arrow.setText(next ? R.string.array_indicator_expanded : R.string.array_indicator_collapsed);
            header.setContentDescription(getString(
                next
                    ? R.string.dictionary_detail_collapse_array
                    : R.string.dictionary_detail_expand_array,
                getString(labelResource)
            ));
            if (next) {
                expandedCards.add(cardKey);
            } else {
                expandedCards.remove(cardKey);
            }
        });
        LinearLayout cardContent = (LinearLayout) card.getTag();
        cardContent.setPadding(0, 0, 0, 0);
        cardContent.addView(header);
        cardContent.addView(body);
        content.addView(card);
    }

    private MaterialCardView cardColumn() {
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
        LinearLayout column = new LinearLayout(this);
        column.setOrientation(LinearLayout.VERTICAL);
        column.setPadding(dp(16), dp(12), dp(16), dp(8));
        card.addView(column);
        card.setTag(column);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        );
        params.bottomMargin = dp(12);
        card.setLayoutParams(params);
        return card;
    }

    private void addField(View parent, String label, String rawValue) {
        LinearLayout column = parent instanceof MaterialCardView
            ? (LinearLayout) parent.getTag()
            : (LinearLayout) parent;
        TextView labelView = new TextView(this);
        labelView.setTextAppearance(this, R.style.Widget_HET_FieldLabel);
        labelView.setText(label);
        column.addView(labelView);
        TextView valueView = new TextView(this);
        valueView.setTextAppearance(
            this,
            com.google.android.material.R.style.TextAppearance_MaterialComponents_Body1
        );
        valueView.setText(TextUtils.isEmpty(rawValue)
            ? getString(R.string.dictionary_detail_empty_value)
            : rawValue);
        valueView.setTextIsSelectable(true);
        valueView.setPadding(0, 0, 0, dp(8));
        column.addView(valueView);
    }

    private void addText(LinearLayout parent, String text, boolean compact) {
        TextView value = new TextView(this);
        value.setTextAppearance(
            this,
            com.google.android.material.R.style.TextAppearance_MaterialComponents_Body2
        );
        value.setText(text);
        value.setTextIsSelectable(true);
        value.setPadding(0, compact ? dp(2) : dp(4), 0, compact ? dp(2) : dp(8));
        parent.addView(value);
    }

    private String value(String field) {
        return record == null ? "" : record.optString(field, "");
    }

    private void showFailure(String message) {
        status.setVisibility(View.VISIBLE);
        status.setText(message);
        content.removeAllViews();
        pageActions.removeAllViews();
        pageActions.setVisibility(View.GONE);
        addEditButton(false);
    }

    private void rememberScroll() {
        if (scrollView != null && record != null) {
            savedScrollY = scrollView.getScrollY();
        }
    }

    private void restoreScrollIfNeeded() {
        if (savedScrollY >= 0) {
            final int target = savedScrollY;
            final long generation = loadGeneration;
            scrollView.post(() -> {
                if (!destroyed && generation == loadGeneration) {
                    scrollView.scrollTo(0, target);
                }
            });
        }
    }

    private Map<String, String> buildRelationshipTargetNames(
        JSONObject dictionary,
        Locale locale
    ) {
        Map<String, String> names = new HashMap<>();
        Iterator<String> keys = dictionary.keys();
        while (keys.hasNext()) {
            String dictionaryKey = keys.next();
            names.put(
                dictionaryKey,
                SceneManagementDetailData.relationshipTargetLabel(
                    dictionaryKey,
                    dictionary,
                    locale,
                    ""
                )
            );
        }
        keys = dictionary.keys();
        while (keys.hasNext()) {
            String dictionaryKey = keys.next();
            JSONObject candidate = dictionary.optJSONObject(dictionaryKey);
            if (candidate == null) {
                continue;
            }
            String canonical = candidate.optString("key", "").trim();
            if (!canonical.isEmpty()
                && !dictionary.has(canonical)
                && !names.containsKey(canonical)) {
                names.put(
                    canonical,
                    SceneManagementDetailData.relationshipTargetLabel(
                        canonical,
                        dictionary,
                        locale,
                        ""
                    )
                );
            }
        }
        return names;
    }

    private Locale interfaceLocale() {
        android.os.LocaleList locales = getResources()
            .getConfiguration()
            .getLocales();
        return locales.isEmpty() ? Locale.getDefault() : locales.get(0);
    }

    private boolean isSupportedRequest() {
        return (KIND_CHARACTER.equals(kind) || KIND_TERM.equals(kind))
            && !TextUtils.isEmpty(key);
    }

    private boolean isStylePreviewRequest() {
        if (!StylePreview.isEnabled(this)) {
            return false;
        }
        String previewKind = StylePreview.kindOf(getIntent());
        return (isCharacter() && StylePreview.KIND_CHARACTER_DETAIL.equals(previewKind))
            || (isTerm() && StylePreview.KIND_TERM_DETAIL.equals(previewKind));
    }

    private boolean isCharacter() {
        return KIND_CHARACTER.equals(kind);
    }

    private boolean isTerm() {
        return KIND_TERM.equals(kind);
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    private String safeMessage(Exception error) {
        String message = error.getMessage();
        return TextUtils.isEmpty(message)
            ? error.getClass().getSimpleName()
            : message;
    }
}
