package com.quarty.housamoembedtrans.ui;

import com.quarty.housamoembedtrans.R;
import com.quarty.housamoembedtrans.storage.config.ConfigStore;

import android.content.Intent;
import android.content.res.ColorStateList;
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
import java.util.LinkedHashSet;
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
    private static final String STATE_EXPANDED = "dictionary_detail.expanded";
    private static final String STATE_SCROLL_Y = "dictionary_detail.scroll_y";

    private final ExecutorService ioExecutor = Executors.newSingleThreadExecutor();
    private final Set<String> expandedCards = new LinkedHashSet<>();

    private NestedScrollView scrollView;
    private MaterialToolbar toolbar;
    private TextView status;
    private LinearLayout content;
    private String kind;
    private String key;
    private JSONObject record;
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
        if (isSupportedRequest()) {
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

        MaterialCardView overview = cardColumn();
        addField(
            overview,
            getString(R.string.dictionary_detail_original_name),
            isCharacter() && "mc".equals(key)
                ? getString(R.string.management_batch_main_character_label)
                : key
        );
        addField(overview, getString(R.string.dictionary_detail_english), value("en"));
        addField(
            overview,
            getString(R.string.dictionary_detail_traditional_chinese),
            value("zh-tw")
        );
        addField(
            overview,
            getString(R.string.dictionary_detail_simplified_chinese),
            value("zh-cn")
        );
        if (isCharacter()) {
            addField(overview, getString(R.string.field_info), value("info"));
            addField(
                overview,
                getString(R.string.field_description),
                value("description")
            );
            addField(
                overview,
                getString(R.string.field_speech_style),
                value("speech_style")
            );
        } else {
            addField(
                overview,
                getString(R.string.field_description),
                value("description")
            );
        }
        content.addView(overview);

        if (isCharacter()) {
            addCharacterArray("alias", R.string.field_alias, true);
            addCharacterArray("school", R.string.field_school, false);
            addCharacterArray("guild", R.string.field_guild, false);
            addCharacterArray("origin_world", R.string.field_origin_world, false);
            addCharacterArray("relationships", R.string.field_relationships, true);
        }
        addEditButton(editingAllowed);
        restoreScrollIfNeeded();
    }

    private void addEditButton(boolean enabled) {
        editButton = detailButton(R.style.Widget_HET_Button_Secondary);
        editButton.setText(isCharacter()
            ? ("mc".equals(key) ? R.string.edit_mc : R.string.edit_character)
            : R.string.edit_game_term);
        editButton.setAllCaps(false);
        editButton.setEnabled(enabled && !pendingMoveBusy);
        editButton.setOnClickListener(view -> openEditor());
        content.addView(editButton, actionParams());
        moveButton = null;
        if (record != null && !(isCharacter() && "mc".equals(key))) {
            moveButton = detailButton(R.style.Widget_HET_Button_Danger);
            moveButton.setText(R.string.pending_process_move);
            moveButton.setAllCaps(false);
            moveButton.setEnabled(
                enabled
                    && !pendingMoveBusy
                    && pendingProcessMoveController != null
            );
            moveButton.setOnClickListener(view -> moveRecordToPending());
            content.addView(moveButton, actionParams());
        }
    }

    private LinearLayout.LayoutParams actionParams() {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        );
        params.topMargin = dp(4);
        params.bottomMargin = dp(4);
        return params;
    }

    private MaterialButton detailButton(int role) {
        MaterialButton button = new MaterialButton(this);
        boolean danger = role == R.style.Widget_HET_Button_Danger;
        button.setAllCaps(false);
        button.setMinHeight(dp(40));
        button.setMinWidth(0);
        button.setCornerRadius(dp(20));
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

    private void moveRecordToPending() {
        if (!isSupportedRequest()
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
        startActivity(intent);
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

    private boolean isCharacter() {
        return KIND_CHARACTER.equals(kind);
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
