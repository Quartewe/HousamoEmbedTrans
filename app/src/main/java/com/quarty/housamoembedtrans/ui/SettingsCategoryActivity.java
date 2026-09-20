package com.quarty.housamoembedtrans.ui;

import com.quarty.housamoembedtrans.R;
import com.quarty.housamoembedtrans.provider.TranslationApiClient;
import com.quarty.housamoembedtrans.storage.config.ConfigStore;

import android.content.Intent;
import android.os.Bundle;
import android.text.Editable;
import android.text.InputFilter;
import android.text.InputType;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;

import com.google.android.material.appbar.MaterialToolbar;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.card.MaterialCardView;
import com.google.android.material.slider.Slider;
import com.google.android.material.switchmaterial.SwitchMaterial;
import com.google.android.material.textfield.TextInputLayout;

import org.json.JSONObject;

import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Edits one settings category and persists it through ConfigStore. */
public final class SettingsCategoryActivity extends AppCompatActivity {
    public static final String EXTRA_CATEGORY =
        "com.quarty.housamoembedtrans.extra.SETTINGS_CATEGORY";

    private static final int TYPE_TEXT = 1;
    private static final int TYPE_NUMBER = 2;
    private static final int TYPE_DECIMAL = 3;
    private static final int TYPE_SELECT = 4;
    private static final int TYPE_BOOLEAN = 5;
    private static final int TYPE_SECRET = 6;
    private static final int TYPE_SLIDER = 7;

    private static final String STATE_CATEGORY =
        "settings_category_state_category";
    private static final String STATE_INITIAL_SIGNATURE =
        "settings_category_state_initial_signature";
    private static final String STATE_FIELDS =
        "settings_category_state_fields";
    private static final String STATE_CUSTOM_TARGET_LANGUAGE =
        "custom-target-language";

    private static final class FieldSpec {
        final String id;
        final int label;
        final int hint;
        final int type;
        final String[] path;
        final String[] codes;
        final int[] optionLabels;
        final double min;
        final double max;
        final double step;
        final int maxLength;
        final boolean integerSlider;

        FieldSpec(
            String id,
            int label,
            int hint,
            int type,
            String[] path,
            String[] codes,
            int[] optionLabels,
            double min,
            double max,
            double step,
            int maxLength,
            boolean integerSlider
        ) {
            this.id = id;
            this.label = label;
            this.hint = hint;
            this.type = type;
            this.path = path;
            this.codes = codes;
            this.optionLabels = optionLabels;
            this.min = min;
            this.max = max;
            this.step = step;
            this.maxLength = maxLength;
            this.integerSlider = integerSlider;
        }

        FieldSpec(
            String id,
            int label,
            int hint,
            int type,
            String[] path,
            String[] codes,
            int[] optionLabels,
            double min,
            double max
        ) {
            this(
                id,
                label,
                hint,
                type,
                path,
                codes,
                optionLabels,
                min,
                max,
                0,
                0,
                false
            );
        }
    }

    private static final class ChoiceState {
        final String[] codes;
        final int[] labels;
        int position;

        ChoiceState(String[] codes, int[] labels, int position) {
            this.codes = codes;
            this.labels = labels;
            this.position = position;
        }
    }

    private ConfigStore configStore;
    private SettingsCategory.Definition definition;
    private JSONObject currentConfig;
    private JSONObject userSettings;
    private String currentApiKey = "";
    private String initialSignature;
    private LinearLayout fieldsContainer;
    private MaterialButton saveButton;
    private LinearLayout additionalInfoContainer;
    private final Map<String, View> controls = new HashMap<>();
    private final Map<String, View> rows = new HashMap<>();
    private final ExecutorService modelQueryExecutor =
        Executors.newSingleThreadExecutor();
    private MaterialButton queryModelsButton;
    private TextView modelQueryStatus;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_settings_category);
        SystemBarInsets.apply(findViewById(R.id.root_settings_category));

        definition = SettingsCategory.find(
            getIntent().getStringExtra(EXTRA_CATEGORY)
        );
        if (definition == null) {
            finish();
            return;
        }
        configStore = new ConfigStore(this);
        fieldsContainer = findViewById(R.id.settings_fields);
        additionalInfoContainer = findViewById(R.id.settings_additional_info);
        saveButton = findViewById(R.id.btn_save_settings_category);

        MaterialToolbar toolbar = findViewById(R.id.toolbar_settings_category);
        toolbar.setTitle(definition.title);
        toolbar.setNavigationOnClickListener(view -> onBackPressed());
        ((TextView) findViewById(R.id.tv_settings_category_title))
            .setText(definition.title);
        findViewById(R.id.tv_settings_category_description)
            .setContentDescription(getString(definition.description));
        ((TextView) findViewById(R.id.tv_settings_category_description))
            .setText(definition.description);
        ((TextView) findViewById(R.id.tv_settings_category_scope))
            .setText(definition.applyScope);
        saveButton.setOnClickListener(view -> saveCategory());

        if (!loadConfig()) {
            saveButton.setEnabled(false);
            return;
        }
        renderFields();
        if (!restoreDraftState(savedInstanceState)) {
            initialSignature = captureSignature();
        }
        updateSaveButton();
    }

    @Override
    protected void onSaveInstanceState(Bundle outState) {
        saveDraftState(outState);
        super.onSaveInstanceState(outState);
    }

    @Override
    protected void onDestroy() {
        modelQueryExecutor.shutdownNow();
        super.onDestroy();
    }

    @Override
    public void onBackPressed() {
        if (initialSignature != null && !initialSignature.equals(captureSignature())) {
            new UiMaterialAlertDialogBuilder(this)
                .setTitle(R.string.settings_category_unsaved_title)
                .setMessage(R.string.settings_category_unsaved_message)
                .setNegativeButton(R.string.cancel_action, null)
                .setPositiveButton(
                    R.string.discard_changes,
                    (dialog, which) -> finish()
                )
                .show();
            return;
        }
        super.onBackPressed();
    }

    private boolean loadConfig() {
        try {
            ConfigStore.LoadResult result = configStore.load();
            currentConfig = result.config;
            userSettings = currentConfig.getJSONObject("UserSettings");
            currentApiKey = configStore.loadApiKey();
            return true;
        } catch (Exception error) {
            Toast.makeText(
                this,
                getString(R.string.config_load_failed, safeMessage(error)),
                Toast.LENGTH_LONG
            ).show();
            return false;
        }
    }

    private void renderFields() {
        fieldsContainer.removeAllViews();
        if (additionalInfoContainer != null) {
            additionalInfoContainer.removeAllViews();
        }
        controls.clear();
        rows.clear();
        queryModelsButton = null;
        modelQueryStatus = null;
        if (SettingsCategory.DIAGNOSTICS.equals(definition.id)) {
            fieldsContainer.addView(createInfoCard(
                R.string.settings_rebuild_diagnostics_title,
                R.string.settings_rebuild_diagnostics_body,
                false
            ));
            fieldsContainer.addView(createStylePreviewCard());
        }
        for (FieldSpec spec : fieldsFor(definition.id)) {
            View row = createField(spec);
            rows.put(spec.id, row);
            fieldsContainer.addView(row);
        }
        if (SettingsCategory.CHARACTER_MATCHING.equals(definition.id)
            && additionalInfoContainer != null) {
            additionalInfoContainer.addView(createMatchingRulesCard());
        }
        updateConditionalVisibility();
    }

    private MaterialCardView createMatchingRulesCard() {
        MaterialCardView card = new MaterialCardView(this);
        card.setCardBackgroundColor(getColor(R.color.het_surface_container));
        card.setCardElevation(0);
        card.setRadius(dp(18));
        card.setStrokeColor(getColor(R.color.het_outline_soft));
        card.setStrokeWidth(dp(1));
        LinearLayout.LayoutParams cardParams = new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        );
        cardParams.topMargin = dp(8);
        card.setLayoutParams(cardParams);

        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(dp(12), dp(10), dp(12), dp(10));
        card.addView(content);

        LinearLayout header = new LinearLayout(this);
        header.setGravity(Gravity.CENTER_VERTICAL);
        content.addView(header, new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ));
        LinearLayout copy = new LinearLayout(this);
        copy.setOrientation(LinearLayout.VERTICAL);
        header.addView(copy, new LinearLayout.LayoutParams(
            0,
            LinearLayout.LayoutParams.WRAP_CONTENT,
            1f
        ));
        TextView title = labelView(
            R.string.settings_rebuild_matching_rules_heading
        );
        copy.addView(title);
        TextView hint = new TextView(this);
        hint.setText(R.string.settings_rebuild_matching_rules_hint);
        hint.setTextColor(getColor(R.color.het_on_surface_muted));
        hint.setTextSize(11);
        hint.setLineSpacing(0, 1.45f);
        hint.setIncludeFontPadding(false);
        hint.setPadding(0, dp(4), 0, 0);
        copy.addView(hint);

        TextView arrow = new TextView(this);
        arrow.setText("›");
        arrow.setTextColor(getColor(R.color.het_primary_strong));
        arrow.setTextSize(22);
        arrow.setGravity(Gravity.CENTER);
        arrow.setMinWidth(dp(32));
        arrow.setMinHeight(dp(40));
        header.addView(arrow);

        TextView body = new TextView(this);
        body.setText(R.string.settings_rebuild_matching_rules_detail);
        body.setTextColor(getColor(R.color.het_on_surface_muted));
        body.setTextSize(12);
        body.setLineSpacing(0, 1.45f);
        body.setIncludeFontPadding(false);
        body.setPadding(0, dp(8), 0, 0);
        body.setVisibility(View.GONE);
        content.addView(body);

        card.setClickable(true);
        card.setFocusable(true);
        card.setContentDescription(getString(
            R.string.settings_rebuild_matching_rules_heading
        ));
        card.setOnClickListener(view -> {
            boolean expanded = body.getVisibility() == View.VISIBLE;
            body.setVisibility(expanded ? View.GONE : View.VISIBLE);
            arrow.setText(expanded ? "›" : "⌄");
        });
        return card;
    }

    private MaterialCardView createInfoCard(int title, int body, boolean collapsible) {
        MaterialCardView card = new MaterialCardView(this);
        card.setCardBackgroundColor(getColor(R.color.het_surface_container));
        card.setCardElevation(0);
        card.setRadius(dp(18));
        card.setStrokeColor(getColor(R.color.het_outline_soft));
        card.setStrokeWidth(dp(1));
        LinearLayout.LayoutParams cardParams = new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        );
        cardParams.topMargin = dp(8);
        card.setLayoutParams(cardParams);

        LinearLayout bodyLayout = new LinearLayout(this);
        bodyLayout.setOrientation(LinearLayout.VERTICAL);
        bodyLayout.setPadding(dp(12), dp(10), dp(12), dp(10));
        card.addView(bodyLayout);

        TextView titleView = new TextView(this);
        titleView.setText(title);
        titleView.setTextColor(getColor(R.color.het_on_surface));
        titleView.setTextSize(13);
        titleView.setTypeface(null, android.graphics.Typeface.BOLD);
        bodyLayout.addView(titleView);

        TextView bodyView = new TextView(this);
        bodyView.setText(body);
        bodyView.setTextColor(getColor(R.color.het_on_surface_muted));
        bodyView.setTextSize(12);
        bodyView.setLineSpacing(0, 1.45f);
        bodyView.setPadding(0, dp(5), 0, 0);
        bodyLayout.addView(bodyView);
        if (collapsible) {
            bodyView.setVisibility(View.GONE);
            card.setOnClickListener(view -> bodyView.setVisibility(
                bodyView.getVisibility() == View.VISIBLE ? View.GONE : View.VISIBLE
            ));
            card.setContentDescription(getString(title));
        }
        return card;
    }

    private MaterialCardView createStylePreviewCard() {
        MaterialCardView card = new MaterialCardView(this);
        card.setCardBackgroundColor(getColor(R.color.het_surface_container));
        card.setCardElevation(0);
        card.setRadius(dp(18));
        card.setStrokeColor(getColor(R.color.het_outline_soft));
        card.setStrokeWidth(dp(1));
        LinearLayout.LayoutParams cardParams = new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        );
        cardParams.topMargin = dp(8);
        card.setLayoutParams(cardParams);

        LinearLayout body = new LinearLayout(this);
        body.setOrientation(LinearLayout.VERTICAL);
        body.setPadding(dp(12), dp(10), dp(12), dp(10));
        card.addView(body);

        TextView title = labelView(R.string.settings_style_preview_title);
        body.addView(title);

        TextView hint = new TextView(this);
        hint.setText(R.string.settings_style_preview_body);
        hint.setTextColor(getColor(R.color.het_on_surface_muted));
        hint.setTextSize(11);
        hint.setLineSpacing(0, 1.45f);
        hint.setIncludeFontPadding(false);
        hint.setPadding(0, dp(4), 0, 0);
        body.addView(hint);

        MaterialButton open = new MaterialButton(
            this,
            null,
            com.google.android.material.R.attr.materialButtonOutlinedStyle
        );
        open.setText(R.string.settings_style_preview_action);
        open.setAllCaps(false);
        open.setTextSize(13);
        open.setMinHeight(dp(36));
        open.setOnClickListener(view -> startActivity(
            new Intent(this, StylePreviewActivity.class)
        ));
        LinearLayout.LayoutParams buttonParams = new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        );
        buttonParams.topMargin = dp(8);
        buttonParams.gravity = Gravity.END;
        body.addView(open, buttonParams);
        return card;
    }

    /** Saves this category's in-memory form and its original dirty baseline. */
    private void saveDraftState(Bundle outState) {
        if (definition == null || controls.isEmpty() || initialSignature == null) {
            return;
        }
        Bundle fields = new Bundle();
        for (FieldSpec spec : fieldsFor(definition.id)) {
            View control = controls.get(spec.id);
            if (spec.type == TYPE_BOOLEAN && control instanceof SwitchMaterial) {
                fields.putBoolean(spec.id, ((SwitchMaterial) control).isChecked());
            } else if (spec.type == TYPE_SELECT && control instanceof TextView) {
                fields.putInt(spec.id, ((ChoiceState) control.getTag()).position);
            } else if (spec.type == TYPE_SLIDER && control instanceof Slider) {
                fields.putFloat(spec.id, ((Slider) control).getValue());
            } else if (control instanceof EditText) {
                // This also covers TYPE_SECRET. The value is kept only in the
                // Activity recreation state and is never logged or merged into
                // config.json.
                fields.putString(spec.id, textOf((EditText) control));
            }
        }
        View customTargetLanguage = controls.get(STATE_CUSTOM_TARGET_LANGUAGE);
        if (customTargetLanguage instanceof EditText) {
            fields.putString(
                STATE_CUSTOM_TARGET_LANGUAGE,
                textOf((EditText) customTargetLanguage)
            );
        }
        outState.putString(STATE_CATEGORY, definition.id);
        outState.putString(STATE_INITIAL_SIGNATURE, initialSignature);
        outState.putBundle(STATE_FIELDS, fields);
    }

    /** Restores the unsaved form without changing durable configuration. */
    private boolean restoreDraftState(Bundle savedInstanceState) {
        if (savedInstanceState == null
            || !definition.id.equals(savedInstanceState.getString(STATE_CATEGORY))) {
            return false;
        }
        String restoredSignature = savedInstanceState.getString(
            STATE_INITIAL_SIGNATURE
        );
        Bundle fields = savedInstanceState.getBundle(STATE_FIELDS);
        if (restoredSignature == null || fields == null) {
            return false;
        }
        for (FieldSpec spec : fieldsFor(definition.id)) {
            View control = controls.get(spec.id);
            if (spec.type == TYPE_BOOLEAN && control instanceof SwitchMaterial) {
                if (fields.containsKey(spec.id)) {
                    ((SwitchMaterial) control).setChecked(
                        fields.getBoolean(spec.id)
                    );
                }
            } else if (spec.type == TYPE_SELECT && control instanceof TextView) {
                if (fields.containsKey(spec.id)) {
                    int position = fields.getInt(
                        spec.id,
                        ((ChoiceState) control.getTag()).position
                    );
                    if (position >= 0 && position < spec.codes.length) {
                        ChoiceState state = (ChoiceState) control.getTag();
                        state.position = position;
                        ((TextView) control).setText(
                            getString(spec.optionLabels[position])
                        );
                    }
                }
            } else if (spec.type == TYPE_SLIDER && control instanceof Slider) {
                if (fields.containsKey(spec.id)) {
                    ((Slider) control).setValue(fields.getFloat(
                        spec.id,
                        ((Slider) control).getValue()
                    ));
                }
            } else if (control instanceof EditText && fields.containsKey(spec.id)) {
                String value = fields.getString(spec.id);
                ((EditText) control).setText(value == null ? "" : value);
            }
        }
        View customTargetLanguage = controls.get(STATE_CUSTOM_TARGET_LANGUAGE);
        if (customTargetLanguage instanceof EditText
            && fields.containsKey(STATE_CUSTOM_TARGET_LANGUAGE)) {
            String value = fields.getString(STATE_CUSTOM_TARGET_LANGUAGE);
            ((EditText) customTargetLanguage).setText(value == null ? "" : value);
        }
        initialSignature = restoredSignature;
        updateConditionalVisibility();
        return true;
    }

    private View createField(FieldSpec spec) {
        if (spec.type == TYPE_BOOLEAN) {
            MaterialCardView card = fieldCard();
            LinearLayout row = cardBody(false);
            row.setMinimumHeight(dp(48));
            LinearLayout copy = new LinearLayout(this);
            copy.setOrientation(LinearLayout.VERTICAL);
            row.addView(copy, new LinearLayout.LayoutParams(
                0,
                LinearLayout.LayoutParams.WRAP_CONTENT,
                1f
            ));
            copy.addView(labelView(spec.label));
            addHint(copy, spec.hint);
            SwitchMaterial toggle = new SwitchMaterial(this);
            toggle.setChecked(readBoolean(spec.path));
            toggle.setOnCheckedChangeListener(
                (button, checked) -> {
                    updateConditionalVisibility();
                    updateSaveButton();
                }
            );
            controls.put(spec.id, toggle);
            LinearLayout.LayoutParams toggleParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            );
            toggleParams.leftMargin = dp(12);
            row.addView(toggle, toggleParams);
            card.addView(row);
            return card;
        }
        if (spec.type == TYPE_SELECT) {
            MaterialCardView card = fieldCard();
            LinearLayout body = cardBody(true);
            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER_VERTICAL);
            body.addView(row, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ));
            LinearLayout copy = new LinearLayout(this);
            copy.setOrientation(LinearLayout.VERTICAL);
            row.addView(copy, new LinearLayout.LayoutParams(
                0,
                LinearLayout.LayoutParams.WRAP_CONTENT,
                1f
            ));
            copy.addView(labelView(spec.label));
            addHint(copy, spec.hint);
            String selectedValue = readString(spec.path);
            int selectedIndex = indexOf(spec.codes, selectedValue);
            if ("target-language".equals(spec.id)
                && !spec.codes[selectedIndex].equalsIgnoreCase(selectedValue)) {
                selectedIndex = spec.codes.length - 1;
            }
            // The prototype keeps the selected value as ordinary text.  The
            // whole field row opens the picker; a filled MaterialButton makes
            // this control look like a primary action and consumes too much
            // vertical space on the 360dp layout.
            TextView chooser = new TextView(this);
            chooser.setText(optionLabels(spec.optionLabels)[selectedIndex]);
            chooser.setTextSize(13);
            chooser.setTextColor(getColor(R.color.het_primary_strong));
            chooser.setGravity(Gravity.CENTER_VERTICAL | Gravity.END);
            chooser.setIncludeFontPadding(false);
            chooser.setMaxLines(1);
            chooser.setEllipsize(android.text.TextUtils.TruncateAt.END);
            chooser.setPadding(dp(4), 0, 0, 0);
            chooser.setTag(new ChoiceState(
                spec.codes,
                spec.optionLabels,
                selectedIndex
            ));
            row.setMinimumHeight(dp(48));
            row.setClickable(true);
            row.setFocusable(true);
            row.setOnClickListener(view -> showChoicePicker(spec, chooser));
            LinearLayout.LayoutParams chooserParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                dp(48)
            );
            chooserParams.leftMargin = dp(12);
            row.addView(chooser, chooserParams);
            controls.put(spec.id, chooser);
            if ("target-language".equals(spec.id)) {
                TextInputLayout customLayout = textInput(
                    R.string.settings_rebuild_custom_target,
                    R.string.settings_rebuild_custom_target_hint,
                    InputType.TYPE_CLASS_TEXT
                );
                EditText custom = (EditText) customLayout.getEditText();
                custom.setFilters(new InputFilter[] {
                    new InputFilter.LengthFilter(100)
                });
                if (selectedIndex == spec.codes.length - 1) {
                    custom.setText(selectedValue);
                }
                controls.put("custom-target-language", custom);
                LinearLayout customContainer = new LinearLayout(this);
                customContainer.setOrientation(LinearLayout.VERTICAL);
                LinearLayout.LayoutParams customContainerParams =
                    new LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT
                    );
                customContainerParams.topMargin = dp(6);
                customContainer.setLayoutParams(customContainerParams);
                rows.put("custom-target-language", customContainer);
                custom.addTextChangedListener(new TextWatcher() {
                    @Override
                    public void beforeTextChanged(
                        CharSequence source,
                        int start,
                        int count,
                        int after
                    ) {
                    }

                    @Override
                    public void onTextChanged(
                        CharSequence source,
                        int start,
                        int before,
                        int count
                    ) {
                        updateSaveButton();
                    }

                    @Override
                    public void afterTextChanged(Editable editable) {
                    }
                });
                LinearLayout.LayoutParams customParams =
                    (LinearLayout.LayoutParams) customLayout.getLayoutParams();
                customParams.topMargin = 0;
                customContainer.addView(customLayout, customParams);
                addFieldHint(
                    customContainer,
                    R.string.settings_rebuild_custom_target_hint
                );
                body.addView(customContainer, customContainerParams);
            }
            card.addView(body);
            return card;
        }
        if (spec.type == TYPE_SLIDER) {
            MaterialCardView card = fieldCard();
            LinearLayout body = cardBody(true);
            LinearLayout heading = new LinearLayout(this);
            heading.setGravity(Gravity.CENTER_VERTICAL);
            heading.setOrientation(LinearLayout.HORIZONTAL);
            body.addView(heading, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ));
            TextView label = labelView(spec.label);
            heading.addView(label, new LinearLayout.LayoutParams(
                0,
                LinearLayout.LayoutParams.WRAP_CONTENT,
                1f
            ));
            TextView valueView = new TextView(this);
            valueView.setTextColor(getColor(R.color.het_on_primary_container));
            valueView.setTextSize(12);
            valueView.setTypeface(null, android.graphics.Typeface.BOLD);
            valueView.setGravity(Gravity.CENTER);
            valueView.setIncludeFontPadding(false);
            valueView.setMinWidth(dp(34));
            valueView.setMinHeight(dp(24));
            valueView.setPadding(dp(8), dp(3), dp(8), dp(3));
            android.graphics.drawable.GradientDrawable valueBackground =
                new android.graphics.drawable.GradientDrawable();
            valueBackground.setColor(getColor(R.color.het_primary_container));
            valueBackground.setCornerRadius(dp(999));
            valueView.setBackground(valueBackground);
            LinearLayout.LayoutParams valueParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            );
            valueParams.leftMargin = dp(12);
            heading.addView(valueView, valueParams);
            Slider slider = new Slider(this);
            slider.setValueFrom((float) spec.min);
            slider.setValueTo((float) spec.max);
            slider.setStepSize((float) spec.step);
            slider.setTrackHeight(dp(6));
            slider.setThumbRadius(dp(10));
            slider.setHaloRadius(dp(16));
            slider.setTickVisible(false);
            float value = (float) readDouble(spec.path, spec.min);
            value = Math.max((float) spec.min, Math.min((float) spec.max, value));
            slider.setValue(value);
            valueView.setText(formatSliderValue(spec, value));
            slider.addOnChangeListener((view, changed, fromUser) ->
                {
                    valueView.setText(formatSliderValue(spec, changed));
                    updateSaveButton();
                }
            );
            controls.put(spec.id, slider);
            LinearLayout.LayoutParams sliderParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                dp(42)
            );
            body.addView(slider, sliderParams);
            addHint(body, spec.hint);
            card.addView(body);
            return card;
        }
        int inputType = spec.type == TYPE_SECRET
            ? InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD
            : spec.type == TYPE_DECIMAL
                ? InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_DECIMAL
                : spec.type == TYPE_NUMBER
                    ? InputType.TYPE_CLASS_NUMBER
                    : InputType.TYPE_CLASS_TEXT;
        TextInputLayout inputLayout = textInput(spec.label, spec.hint, inputType);
        EditText input = inputLayout.getEditText();
        if (spec.maxLength > 0) {
            input.setFilters(new InputFilter[] {
                new InputFilter.LengthFilter(spec.maxLength)
            });
        }
        String value = spec.type == TYPE_SECRET
            ? currentApiKey
            : readString(spec.path);
        input.setText(value);
        if ("base-url".equals(spec.id)) {
            inputLayout.setPlaceholderText(
                getString(R.string.settings_rebuild_base_url_placeholder)
            );
        } else if ("model".equals(spec.id)) {
            inputLayout.setPlaceholderText(
                getString(R.string.settings_rebuild_model_placeholder)
            );
        }
        controls.put(spec.id, input);
        attachValidation(spec, input, inputLayout);
        if ("model".equals(spec.id)) {
            // Keep the query action inside the same outlined field.  The
            // button is overlaid on the field's trailing edge so it does not
            // become a second, oversized control on a narrow screen.
            LinearLayout field = new LinearLayout(this);
            field.setOrientation(LinearLayout.VERTICAL);
            LinearLayout.LayoutParams fieldParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            );
            fieldParams.topMargin = dp(8);
            field.setLayoutParams(fieldParams);

            FrameLayout inputFrame = new FrameLayout(this);
            field.addView(inputFrame, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ));
            inputFrame.addView(inputLayout, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                dp(56)
            ));
            input.setPadding(dp(14), 0, dp(78), 0);
            queryModelsButton = new MaterialButton(this);
            queryModelsButton.setText(R.string.settings_rebuild_model_query_action);
            queryModelsButton.setMinHeight(0);
            queryModelsButton.setMinWidth(0);
            queryModelsButton.setInsetTop(0);
            queryModelsButton.setInsetBottom(0);
            queryModelsButton.setPadding(0, dp(3), 0, dp(3));
            queryModelsButton.setTextSize(12);
            queryModelsButton.setSingleLine(true);
            queryModelsButton.setMaxLines(1);
            queryModelsButton.setGravity(Gravity.CENTER);
            queryModelsButton.setElevation(0f);
            queryModelsButton.setStateListAnimator(null);
            queryModelsButton.setCornerRadius(dp(8));
            queryModelsButton.setStrokeWidth(0);
            queryModelsButton.setBackgroundTintList(
                android.content.res.ColorStateList.valueOf(
                    getColor(R.color.het_primary_container)
                )
            );
            queryModelsButton.setTextColor(getColor(R.color.het_on_primary_container));
            queryModelsButton.setOnClickListener(view -> queryModels());
            FrameLayout.LayoutParams queryParams = new FrameLayout.LayoutParams(
                dp(56),
                dp(28),
                Gravity.END | Gravity.TOP
            );
            queryParams.setMargins(0, 0, dp(5), 0);
            inputFrame.addView(queryModelsButton, queryParams);
            inputFrame.addOnLayoutChangeListener((view, left, top, right, bottom,
                                                   oldLeft, oldTop, oldRight, oldBottom) ->
                positionQueryButton(inputFrame, input, queryModelsButton)
            );
            inputFrame.post(() -> positionQueryButton(
                inputFrame,
                input,
                queryModelsButton
            ));
            modelQueryStatus = new TextView(this);
            modelQueryStatus.setText(R.string.settings_rebuild_model_query_idle);
            modelQueryStatus.setTextColor(getColor(R.color.het_on_surface_muted));
            modelQueryStatus.setTextSize(11);
            modelQueryStatus.setIncludeFontPadding(false);
            modelQueryStatus.setLineSpacing(0, 1.45f);
            modelQueryStatus.setMinHeight(dp(29));
            modelQueryStatus.setPadding(dp(14), dp(6), dp(14), dp(6));
            modelQueryStatus.setBackgroundResource(R.drawable.bg_settings_rebuild_query_status);
            addFieldHint(field, spec.hint);
            LinearLayout.LayoutParams statusParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            );
            statusParams.topMargin = dp(2);
            field.addView(modelQueryStatus, statusParams);
            return field;
        }
        LinearLayout field = new LinearLayout(this);
        field.setOrientation(LinearLayout.VERTICAL);
        field.setLayoutParams(new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ));
        ((LinearLayout.LayoutParams) field.getLayoutParams()).topMargin = dp(8);
        field.addView(inputLayout);
        addFieldHint(field, spec.hint);
        return field;
    }

    /** Align the query action with the measured edit field, below any floating label. */
    private void positionQueryButton(
        FrameLayout inputFrame,
        EditText input,
        MaterialButton button
    ) {
        if (inputFrame == null || input == null || button == null
            || inputFrame.getHeight() <= 0 || input.getHeight() <= 0
            || button.getMeasuredHeight() <= 0) {
            return;
        }
        int[] frameLocation = new int[2];
        int[] inputLocation = new int[2];
        inputFrame.getLocationInWindow(frameLocation);
        input.getLocationInWindow(inputLocation);
        int inputCenterY = inputLocation[1] + input.getHeight() / 2;
        int topMargin = Math.max(
            0,
            inputCenterY - frameLocation[1] - button.getMeasuredHeight() / 2
        );
        ViewGroup.LayoutParams rawParams = button.getLayoutParams();
        if (!(rawParams instanceof FrameLayout.LayoutParams)) {
            return;
        }
        FrameLayout.LayoutParams params = (FrameLayout.LayoutParams) rawParams;
        int gravity = Gravity.END | Gravity.TOP;
        if (params.gravity == gravity
            && params.topMargin == topMargin
            && params.rightMargin == dp(5)
            && params.leftMargin == 0
            && params.bottomMargin == 0) {
            return;
        }
        params.gravity = gravity;
        params.leftMargin = 0;
        params.topMargin = topMargin;
        params.rightMargin = dp(5);
        params.bottomMargin = 0;
        button.setLayoutParams(params);
    }

    private MaterialCardView fieldCard() {
        MaterialCardView card = new MaterialCardView(this);
        card.setCardBackgroundColor(getColor(R.color.het_surface_container));
        card.setCardElevation(0);
        card.setRadius(dp(18));
        card.setStrokeColor(getColor(R.color.het_outline_soft));
        card.setStrokeWidth(dp(1));
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        );
        params.topMargin = dp(8);
        card.setLayoutParams(params);
        return card;
    }

    private LinearLayout cardBody(boolean vertical) {
        LinearLayout body = new LinearLayout(this);
        body.setOrientation(vertical ? LinearLayout.VERTICAL : LinearLayout.HORIZONTAL);
        body.setGravity(Gravity.CENTER_VERTICAL);
        body.setPadding(dp(12), dp(13), dp(12), dp(13));
        return body;
    }

    private TextView labelView(int resourceId) {
        TextView label = new TextView(this);
        label.setText(resourceId);
        label.setTextColor(getColor(R.color.het_on_surface));
        label.setTextSize(13);
        label.setTypeface(null, android.graphics.Typeface.BOLD);
        return label;
    }

    private void showChoicePicker(FieldSpec spec, TextView chooser) {
        ChoiceState state = (ChoiceState) chooser.getTag();
        String[] labels = optionLabels(state.labels);
        StyledPopupMenu popup = new StyledPopupMenu(this, chooser, Gravity.END)
            .setWrapContentWidth();
        for (int index = 0; index < labels.length; index++) {
            MenuItem item = popup.getMenu().add(
                Menu.NONE,
                index + 1,
                index,
                labels[index]
            );
            item.setCheckable(true);
            item.setChecked(index == state.position);
        }
        popup.setOnMenuItemClickListener(item -> {
            int which = item.getItemId() - 1;
            if (which < 0 || which >= labels.length) {
                return false;
            }
            state.position = which;
            chooser.setText(labels[which]);
            updateConditionalVisibility();
            updateSaveButton();
            return true;
        });
        popup.show();
    }

    private TextInputLayout textInput(
        int label,
        int hint,
        int inputType
    ) {
        TextInputLayout layout = (TextInputLayout) LayoutInflater.from(this)
            .inflate(R.layout.view_settings_rebuild_text_input, null, false);
        layout.setBoxBackgroundMode(TextInputLayout.BOX_BACKGROUND_OUTLINE);
        layout.setBoxBackgroundColor(getColor(R.color.het_surface_container));
        layout.setBoxCornerRadii(dp(18), dp(18), dp(18), dp(18));
        layout.setBoxStrokeColorStateList(ContextCompat.getColorStateList(
            this,
            R.color.het_settings_rebuild_text_input_stroke
        ));
        layout.setBoxStrokeWidth(dp(1));
        layout.setBoxStrokeWidthFocused(dp(2));
        layout.setExpandedHintEnabled(false);
        layout.setHintEnabled(true);
        layout.setHintTextAppearance(
            R.style.TextAppearance_HET_SettingsRebuild_FloatingHint
        );
        android.content.res.ColorStateList hintColor =
            android.content.res.ColorStateList.valueOf(
                getColor(R.color.het_on_surface)
            );
        layout.setDefaultHintTextColor(hintColor);
        layout.setHintTextColor(hintColor);
        layout.setPlaceholderTextAppearance(
            R.style.TextAppearance_HET_SettingsRebuild_Placeholder
        );
        layout.setHint(getString(label));
        layout.setLayoutParams(new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            dp(56)
        ));
        ((LinearLayout.LayoutParams) layout.getLayoutParams()).topMargin = 0;
        layout.setMinimumHeight(dp(56));
        EditText input = layout.findViewById(R.id.settings_rebuild_text_input);
        input.setInputType(inputType);
        input.setMaxLines(1);
        input.setTextSize(14);
        input.setTextColor(getColor(R.color.het_on_surface));
        input.setIncludeFontPadding(false);
        input.setGravity(Gravity.CENTER_VERTICAL);
        input.setMinHeight(dp(56));
        input.setPadding(dp(14), 0, dp(14), 0);
        if (inputType == (InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD)) {
            layout.setEndIconMode(TextInputLayout.END_ICON_PASSWORD_TOGGLE);
        }
        return layout;
    }

    private void attachValidation(
        FieldSpec spec,
        EditText input,
        TextInputLayout layout
    ) {
        input.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(
                CharSequence source,
                int start,
                int count,
                int after
            ) {
            }

            @Override
            public void onTextChanged(
                CharSequence source,
                int start,
                int before,
                int count
            ) {
                validateTextField(spec, input, layout, false);
                updateSaveButton();
            }

            @Override
            public void afterTextChanged(Editable editable) {
            }
        });
    }

    private void validateTextField(
        FieldSpec spec,
        EditText input,
        TextInputLayout layout,
        boolean required
    ) {
        String value = textOf(input);
        if (value.isEmpty()) {
            if (required) {
                layout.setError(getString(R.string.settings_error_required));
            } else {
                layout.setError(null);
            }
            return;
        }
        if (spec.maxLength > 0 && value.length() > spec.maxLength) {
            layout.setError(getString(R.string.settings_rebuild_error_too_long));
            return;
        }
        if (spec.type == TYPE_DECIMAL || spec.type == TYPE_NUMBER) {
            try {
                if (spec.type == TYPE_DECIMAL) {
                    double parsed = Double.parseDouble(value);
                    if (!Double.isFinite(parsed)
                        || parsed < spec.min
                        || parsed > spec.max) {
                        throw new NumberFormatException();
                    }
                } else {
                    int parsed = Integer.parseInt(value);
                    if (parsed < spec.min || parsed > spec.max) {
                        throw new NumberFormatException();
                    }
                }
            } catch (NumberFormatException error) {
                layout.setError(getString(
                    spec.type == TYPE_DECIMAL
                        ? R.string.settings_error_positive
                        : R.string.settings_error_range
                ));
                return;
            }
        }
        layout.setError(null);
    }

    private double readDouble(String[] path, double fallback) {
        Object value = getPath(userSettings, path);
        return value instanceof Number ? ((Number) value).doubleValue() : fallback;
    }

    private String formatSliderValue(FieldSpec spec, float value) {
        if (spec.integerSlider) {
            return String.valueOf(Math.round(value));
        }
        return String.format(Locale.US, "%.2f", value);
    }

    private void addHint(LinearLayout row, int hint) {
        addHint(row, hint, 4, false);
    }

    private void addFieldHint(LinearLayout row, int hint) {
        addHint(row, hint, 6, true);
    }

    private void addHint(LinearLayout row, int hint, int topMarginDp) {
        addHint(row, hint, topMarginDp, false);
    }

    private void addHint(
        LinearLayout row,
        int hint,
        int topMarginDp,
        boolean insetHorizontal
    ) {
        TextView text = new TextView(this);
        text.setText(hint);
        text.setTextColor(getColor(R.color.het_on_surface_muted));
        text.setTextSize(11);
        text.setLineSpacing(0, 1.45f);
        text.setIncludeFontPadding(false);
        if (insetHorizontal) {
            text.setPadding(dp(14), 0, dp(14), 0);
        }
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        );
        params.topMargin = dp(topMarginDp);
        text.setLayoutParams(params);
        row.addView(text);
    }

    private void updateConditionalVisibility() {
        boolean streaming = isChecked("enable-streaming-response");
        setRowVisibility("repair-gradient-count", streaming);
        setRowVisibility("use-full-scene-for-repair", streaming);
        boolean compression = isChecked("enable-auto-compression");
        setRowVisibility("continue-auto-summary-after-manual", compression);
        boolean recovery = isChecked("translation-auto-recover")
            || isChecked("summary-auto-recover");
        setRowVisibility("recovery-sort-order", recovery);
        View custom = rows.get("custom-target-language");
        View target = controls.get("target-language");
        if (custom != null && target instanceof TextView) {
            ChoiceState state = (ChoiceState) target.getTag();
            custom.setVisibility(
                state.position == state.codes.length - 1
                    ? View.VISIBLE
                    : View.GONE
            );
        }
    }

    private void setRowVisibility(String id, boolean visible) {
        View row = rows.get(id);
        if (row != null) {
            row.setVisibility(visible ? View.VISIBLE : View.GONE);
        }
    }

    private void updateSaveButton() {
        if (saveButton == null || initialSignature == null || controls.isEmpty()) {
            return;
        }
        saveButton.setText(initialSignature.equals(captureSignature())
            ? R.string.settings_rebuild_settings_saved
            : R.string.settings_category_save);
    }

    private void saveCategory() {
        try {
            // Reload immediately before saving so this screen only merges its own
            // category into the latest config instead of writing an old snapshot
            // over changes made by another screen or by the background service.
            ConfigStore.LoadResult latest = configStore.load();
            JSONObject updated = new JSONObject(latest.config.toString());
            JSONObject updatedSettings = updated.optJSONObject("UserSettings");
            if (updatedSettings == null) {
                throw new IllegalStateException("missing UserSettings");
            }
            for (FieldSpec spec : fieldsFor(definition.id)) {
                if (spec.type == TYPE_BOOLEAN) {
                    putPath(updatedSettings, spec.path, isChecked(spec.id));
                } else if (spec.type == TYPE_SELECT) {
                    String value = selectedCode(spec);
                    if ("target-language".equals(spec.id)
                        && "custom".equals(value)) {
                        EditText custom = (EditText) controls.get(
                            "custom-target-language"
                        );
                        value = requireText(custom);
                    }
                    putPath(updatedSettings, spec.path, value);
                } else if (spec.type == TYPE_SECRET) {
                    // API key has its own durable store and never enters config.json.
                    // An empty value is meaningful: it clears a previously saved key.
                    currentApiKey = textOf((EditText) controls.get(spec.id));
                } else if (spec.type == TYPE_TEXT) {
                    // Base URL is intentionally optional; an empty value lets the
                    // provider use its own default endpoint.
                    putPath(
                        updatedSettings,
                        spec.path,
                        textOf((EditText) controls.get(spec.id))
                    );
                } else if (spec.type == TYPE_SLIDER) {
                    Slider slider = (Slider) controls.get(spec.id);
                    double value = slider.getValue();
                    putPath(
                        updatedSettings,
                        spec.path,
                        spec.integerSlider ? Math.round(value) : value
                    );
                } else {
                    putPath(
                        updatedSettings,
                        spec.path,
                        parseNumber(spec, (EditText) controls.get(spec.id))
                    );
                }
            }
            validateCrossFields(updatedSettings);
            configStore.save(updated);
            if (hasField("api-key")) {
                configStore.saveApiKey(currentApiKey);
            }
            currentConfig = updated;
            userSettings = updatedSettings;
            initialSignature = captureSignature();
            updateSaveButton();
            Toast.makeText(this, R.string.settings_category_saved, Toast.LENGTH_SHORT).show();
        } catch (ValidationException error) {
            error.field.setError(error.getMessage());
            error.field.requestFocus();
        } catch (Exception error) {
            Toast.makeText(
                this,
                getString(R.string.config_save_failed, safeMessage(error)),
                Toast.LENGTH_LONG
            ).show();
        }
    }

    private void validateCrossFields(JSONObject settings)
        throws ValidationException {
        if (!SettingsCategory.CHARACTER_MATCHING.equals(definition.id)) {
            return;
        }
        JSONObject weights = settings.optJSONObject("CharacterWeight");
        if (weights == null) {
            throw new ValidationException(
                (EditText) controls.get("high-relevance"),
                getString(R.string.settings_error_required)
            );
        }
        double high = weights.optDouble("HighRelevance", 4.0);
        double mid = weights.optDouble("MidRelevance", 3.0);
        double low = weights.optDouble("TextLowScore", 3.0);
        double mentioned = weights.optDouble("TextMentionedScore", 1.0);
        if (high < mid) {
            throw new ValidationException(
                (EditText) controls.get("high-relevance"),
                getString(R.string.settings_error_high_less_than_mid)
            );
        }
        if (low < mentioned) {
            throw new ValidationException(
                (EditText) controls.get("text-low-score"),
                getString(R.string.settings_error_low_less_than_mentioned)
            );
        }
    }

    private Object parseNumber(FieldSpec spec, EditText field)
        throws ValidationException {
        String value = requireText(field);
        try {
            if (spec.type == TYPE_DECIMAL) {
                double parsed = Double.parseDouble(value);
                if (!Double.isFinite(parsed)
                    || parsed < spec.min
                    || parsed > spec.max) {
                    throw new NumberFormatException();
                }
                return parsed;
            }
            int parsed = Integer.parseInt(value);
            if (parsed < spec.min || parsed > spec.max) {
                throw new NumberFormatException();
            }
            return parsed;
        } catch (NumberFormatException error) {
            throw new ValidationException(
                field,
                getString(
                    spec.type == TYPE_DECIMAL
                        ? R.string.settings_error_positive
                        : R.string.settings_error_range
                )
            );
        }
    }

    private String requireText(EditText field) throws ValidationException {
        String value = field == null || field.getText() == null
            ? ""
            : field.getText().toString().trim();
        if (value.isEmpty()) {
            throw new ValidationException(
                field,
                getString(R.string.settings_error_required)
            );
        }
        return value;
    }

    private String captureSignature() {
        StringBuilder result = new StringBuilder();
        for (FieldSpec spec : fieldsFor(definition.id)) {
            result.append(spec.id).append('=');
            if (spec.type == TYPE_BOOLEAN) {
                result.append(isChecked(spec.id));
            } else if (spec.type == TYPE_SELECT) {
                result.append(selectedCode(spec));
                if ("target-language".equals(spec.id)) {
                    result.append('|').append(textOf((EditText) controls.get(
                        "custom-target-language"
                    )));
                }
            } else if (spec.type == TYPE_SLIDER) {
                result.append(((Slider) controls.get(spec.id)).getValue());
            } else {
                result.append(textOf((EditText) controls.get(spec.id)));
            }
            result.append(';');
        }
        if (hasField("api-key")) {
            result.append("api-key=").append(textOf((EditText) controls.get("api-key")));
        }
        return result.toString();
    }

    private void queryModels() {
        if (queryModelsButton == null) {
            return;
        }
        final String protocol = selectedCodeById("protocol");
        final String baseUrl = textOf((EditText) controls.get("base-url"));
        final String apiKey = textOf((EditText) controls.get("api-key"));
        queryModelsButton.setEnabled(false);
        queryModelsButton.setText(R.string.settings_rebuild_model_query_loading);
        if (modelQueryStatus != null) {
            modelQueryStatus.setText(R.string.settings_rebuild_model_query_loading);
            modelQueryStatus.setTextColor(getColor(R.color.het_on_surface_muted));
        }
        modelQueryExecutor.execute(() -> {
            try {
                List<String> models = TranslationApiClient.listModels(
                    protocol,
                    baseUrl,
                    apiKey
                );
                runOnUiThread(() -> {
                    if (isFinishing() || isDestroyed()) {
                        return;
                    }
                    queryModelsButton.setEnabled(true);
                    queryModelsButton.setText(R.string.settings_rebuild_model_query_action);
                    if (modelQueryStatus != null) {
                        modelQueryStatus.setText(getString(
                            R.string.settings_rebuild_model_query_success,
                            models == null ? 0 : models.size()
                        ));
                        modelQueryStatus.setTextColor(getColor(R.color.het_good));
                    }
                    showModelPicker(models);
                });
            } catch (Exception error) {
                runOnUiThread(() -> {
                    if (isFinishing() || isDestroyed()) {
                        return;
                    }
                    queryModelsButton.setEnabled(true);
                    queryModelsButton.setText(R.string.settings_rebuild_model_query_retry);
                    if (modelQueryStatus != null) {
                        modelQueryStatus.setText(getString(
                            R.string.settings_rebuild_model_query_failure,
                            safeMessage(error)
                        ));
                        modelQueryStatus.setTextColor(getColor(R.color.het_error));
                    }
                    Toast.makeText(
                        this,
                        getString(
                            R.string.settings_rebuild_model_query_failure,
                            safeMessage(error)
                        ),
                        Toast.LENGTH_LONG
                    ).show();
                });
            }
        });
    }

    private void showModelPicker(List<String> models) {
        if (models == null || models.isEmpty()) {
            if (modelQueryStatus != null) {
                modelQueryStatus.setText(R.string.settings_rebuild_model_query_empty);
                modelQueryStatus.setTextColor(getColor(R.color.het_warning));
            }
            Toast.makeText(
                this,
                getString(R.string.settings_rebuild_model_query_empty),
                Toast.LENGTH_LONG
            ).show();
            return;
        }
        String[] values = models.toArray(new String[0]);
        EditText model = (EditText) controls.get("model");
        int selected = models.indexOf(textOf(model));
        new UiMaterialAlertDialogBuilder(this)
            .setTitle(getString(R.string.settings_category_model_picker_title, values.length))
            .setSingleChoiceItems(values, selected, (dialog, which) -> {
                model.setText(values[which]);
                model.setSelection(values[which].length());
                dialog.dismiss();
            })
            .setNegativeButton(R.string.cancel_action, null)
            .show();
    }

    private boolean hasField(String id) {
        return controls.containsKey(id);
    }

    private boolean isChecked(String id) {
        View view = controls.get(id);
        return view instanceof SwitchMaterial && ((SwitchMaterial) view).isChecked();
    }

    private String selectedCode(FieldSpec spec) {
        return selectedCodeById(spec.id);
    }

    private String selectedCodeById(String id) {
        View view = controls.get(id);
        if (!(view instanceof TextView)) {
            return "";
        }
        ChoiceState state = (ChoiceState) view.getTag();
        return state.position >= 0 && state.position < state.codes.length
            ? state.codes[state.position]
            : state.codes[0];
    }

    private String readString(String[] path) {
        Object value = getPath(userSettings, path);
        return value == null ? "" : String.valueOf(value);
    }

    private boolean readBoolean(String[] path) {
        Object value = getPath(userSettings, path);
        return value instanceof Boolean && (Boolean) value;
    }

    private static Object getPath(JSONObject root, String[] path) {
        Object current = root;
        for (String key : path) {
            if (!(current instanceof JSONObject)) {
                return null;
            }
            current = ((JSONObject) current).opt(key);
        }
        return current;
    }

    private static void putPath(JSONObject root, String[] path, Object value)
        throws Exception {
        JSONObject current = root;
        for (int index = 0; index < path.length - 1; index++) {
            JSONObject next = current.optJSONObject(path[index]);
            if (next == null) {
                next = new JSONObject();
                current.put(path[index], next);
            }
            current = next;
        }
        current.put(path[path.length - 1], value);
    }

    private static int indexOf(String[] values, String wanted) {
        if (wanted != null) {
            for (int index = 0; index < values.length; index++) {
                if (values[index].equalsIgnoreCase(wanted)) {
                    return index;
                }
            }
        }
        return 0;
    }

    private static String textOf(EditText field) {
        return field == null || field.getText() == null
            ? ""
            : field.getText().toString().trim();
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    private String[] optionLabels(int[] ids) {
        String[] values = new String[ids.length];
        for (int index = 0; index < ids.length; index++) {
            values[index] = getString(ids[index]);
        }
        return values;
    }

    private static List<FieldSpec> fieldsFor(String categoryId) {
        if (SettingsCategory.TRANSLATION_SERVICE.equals(categoryId)) {
            return Arrays.asList(
                select("protocol", R.string.settings_field_protocol, R.string.settings_field_hint_protocol,
                    new String[] {"openai", "anthropic"}, new int[] {R.string.settings_option_openai, R.string.settings_option_anthropic}, new String[] {"TranslationApi", "Protocol"}),
                text("base-url", R.string.settings_field_base_url, R.string.settings_field_hint_base_url, new String[] {"TranslationApi", "BaseUrl"}, 500),
                secret("api-key", R.string.settings_field_api_key, R.string.settings_field_hint_api_key),
                text("model", R.string.settings_field_model, R.string.settings_field_hint_model, new String[] {"TranslationApi", "Model"}, 200),
                select("target-language", R.string.settings_field_target_language, R.string.settings_field_hint_target_language,
                    new String[] {"zh-cn", "zh-tw", "en", "custom"}, new int[] {R.string.settings_option_zh_cn, R.string.settings_option_zh_tw, R.string.settings_option_en, R.string.settings_category_target_custom}, new String[] {"TargetLanguage"}),
                number("max-output-tokens", R.string.settings_field_max_output_tokens, R.string.settings_field_hint_max_output_tokens, new String[] {"TranslationApi", "MaxTokens"}, 1, Integer.MAX_VALUE),
                number("context-length", R.string.settings_field_context_length, R.string.settings_field_hint_context_length, new String[] {"Api", "context_length"}, 1, 1_000_000),
                slider("max-concurrent-requests", R.string.settings_field_api_concurrency, R.string.settings_field_hint_api_concurrency, new String[] {"Api", "max_concurrent_requests"}, 1, 8, 1, true),
                slider("network-retry-count", R.string.settings_field_network_retry_count, R.string.settings_field_hint_network_retry_count, new String[] {"TranslationApi", "NetworkRetryCount"}, 0, 5, 1, true)
            );
        }
        if (SettingsCategory.TRANSLATION_REPAIR.equals(categoryId)) {
            return Arrays.asList(
                slider("result-repair-count", R.string.settings_field_result_repair_count, R.string.settings_field_hint_result_repair_count, new String[] {"TranslationApi", "ResultRepairCount"}, 0, 5, 1, true),
                select("pending-summary-mode", R.string.settings_field_pending_summary_mode, R.string.settings_field_hint_pending_summary_mode,
                    new String[] {"wait", "skip", "original"}, new int[] {R.string.settings_option_summary_wait, R.string.settings_option_summary_skip, R.string.settings_option_summary_original}, new String[] {"TranslationApi", "PendingSummaryMode"}),
                bool("enable-streaming-response", R.string.settings_field_enable_streaming_response, R.string.settings_field_hint_enable_streaming_response, new String[] {"TranslationApi", "EnableStreamingResponse"}),
                slider("repair-gradient-count", R.string.settings_field_repair_gradient_count, R.string.settings_field_hint_repair_gradient_count, new String[] {"TranslationApi", "RepairGradientCount"}, 2, 8, 1, true),
                bool("use-full-scene-for-repair", R.string.settings_field_use_full_scene_for_repair, R.string.settings_field_hint_use_full_scene_for_repair, new String[] {"TranslationApi", "UseFullSceneForRepair"})
            );
        }
        if (SettingsCategory.CAPTURE_SYNC.equals(categoryId)) {
            return Arrays.asList(
                bool("auto-sync-new-translation", R.string.settings_field_auto_sync_new_translation, R.string.settings_field_hint_auto_sync_new_translation, new String[] {"SceneSync", "AutoSyncOnNewTranslation"}),
                bool("overwrite-existing-json", R.string.settings_field_overwrite_existing_json, R.string.settings_field_hint_overwrite_existing_json, new String[] {"OverwriteExistingJson"}),
                slider("scene-worker-count", R.string.settings_field_scene_worker_count, R.string.settings_field_hint_scene_worker_count, new String[] {"SceneWorkerCount"}, 1, 4, 1, true),
                select("conflict-resolution-mode", R.string.settings_field_conflict_resolution_mode, R.string.settings_field_hint_conflict_resolution_mode,
                    new String[] {"manual", "game", "het"}, new int[] {R.string.settings_option_conflict_manual, R.string.settings_option_conflict_game, R.string.settings_option_conflict_het}, new String[] {"SceneSync", "ConflictResolutionMode"})
            );
        }
        if (SettingsCategory.CONTEXT_SUMMARY.equals(categoryId)) {
            return Arrays.asList(
                bool("enable-auto-compression", R.string.settings_field_enable_auto_compression, R.string.settings_field_hint_enable_auto_compression, new String[] {"ContextHistory", "EnableAutoCompression"}),
                bool("continue-auto-summary-after-manual", R.string.settings_field_continue_auto_summary_after_manual, R.string.settings_field_hint_continue_auto_summary_after_manual, new String[] {"ContextHistory", "ContinueAutoSummaryAfterManual"}),
                bool("enable-startup-review", R.string.settings_field_enable_startup_review, R.string.settings_field_hint_enable_startup_review, new String[] {"ContextHistory", "EnableStartupReview"}),
                slider("default-recent-percent", R.string.settings_field_default_recent_percent, R.string.settings_field_hint_default_recent_percent, new String[] {"ContextHistory", "DefaultRecentPercent"}, 1, 100, 1, true),
                number("default-recent-scene-limit", R.string.settings_field_default_recent_scene_limit, R.string.settings_field_hint_default_recent_scene_limit, new String[] {"ContextHistory", "DefaultRecentSceneLimit"}, 1, 10_000),
                slider("context-summary-retry-count", R.string.settings_field_context_summary_retry_count, R.string.settings_field_hint_context_summary_retry_count, new String[] {"ContextHistory", "ContextSummaryRetryCount"}, 0, 5, 1, true),
                slider("group-summary-retry-count", R.string.settings_field_group_summary_retry_count, R.string.settings_field_hint_group_summary_retry_count, new String[] {"ContextHistory", "GroupSummaryRetryCount"}, 0, 5, 1, true)
            );
        }
        if (SettingsCategory.TASK_RECOVERY.equals(categoryId)) {
            return Arrays.asList(
                bool("translation-auto-recover", R.string.settings_field_translation_auto_recover, R.string.settings_field_hint_translation_auto_recover, new String[] {"TranslationQueue", "AutoRecoverPreviousJobs"}),
                bool("summary-auto-recover", R.string.settings_field_summary_auto_recover, R.string.settings_field_hint_summary_auto_recover, new String[] {"SummaryQueue", "AutoRecoverPreviousJobs"}),
                select("recovery-sort-order", R.string.settings_field_recovery_sort_order, R.string.settings_field_hint_recovery_sort_order,
                    new String[] {"created_asc", "created_desc", "started_asc", "started_desc"}, new int[] {R.string.settings_option_recovery_created_asc, R.string.settings_option_recovery_created_desc, R.string.settings_option_recovery_started_asc, R.string.settings_option_recovery_started_desc}, new String[] {"TranslationQueue", "RecoverySortOrder"})
            );
        }
        if (SettingsCategory.CHARACTER_MATCHING.equals(categoryId)) {
            return Arrays.asList(
                decimal("high-relevance", R.string.settings_field_high_relevance, R.string.settings_field_hint_high_relevance, new String[] {"CharacterWeight", "HighRelevance"}, 0.01, 100_000),
                decimal("mid-relevance", R.string.settings_field_mid_relevance, R.string.settings_field_hint_mid_relevance, new String[] {"CharacterWeight", "MidRelevance"}, 0.01, 100_000),
                decimal("density-high", R.string.settings_field_density_high, R.string.settings_field_hint_density_high, new String[] {"CharacterWeight", "DensityHigh"}, 0.01, 100_000),
                decimal("text-low-score", R.string.settings_field_text_low_score, R.string.settings_field_hint_text_low_score, new String[] {"CharacterWeight", "TextLowScore"}, 0.01, 100_000),
                decimal("text-mentioned-score", R.string.settings_field_text_mentioned_score, R.string.settings_field_hint_text_mentioned_score, new String[] {"CharacterWeight", "TextMentionedScore"}, 0.01, 100_000),
                number("related-num", R.string.settings_field_related_num, R.string.settings_field_hint_related_num, new String[] {"CharacterWeight", "RelatedNum"}, 1, 100_000),
                number("low-term-score", R.string.settings_field_low_term_score, R.string.settings_field_hint_low_term_score, new String[] {"CharacterWeight", "LowTermScore"}, 1, 100_000)
            );
        }
        return Arrays.asList(
            bool("enable-page-rec-debug", R.string.settings_field_enable_page_rec_debug, R.string.settings_field_hint_enable_page_rec_debug, new String[] {"EnablePageRecDebug"}),
            bool("enable-parse-only-debug", R.string.settings_field_enable_parse_only_debug, R.string.settings_field_hint_enable_parse_only_debug, new String[] {"EnableParseOnlyDebug"}),
            bool("enable-failed-api-response-dump", R.string.settings_field_enable_failed_api_response_dump, R.string.settings_field_hint_enable_failed_api_response_dump, new String[] {"EnableFailedApiResponseDump"}),
            bool("enable-api-body-logging", R.string.settings_field_enable_api_body_logging, R.string.settings_field_hint_enable_api_body_logging, new String[] {"EnableApiBodyLogging"}),
                select("thinking-strength", R.string.settings_field_thinking_strength, R.string.settings_field_hint_thinking_strength,
                    new String[] {"none", "minimal", "low", "medium", "high", "xhigh", "max"}, new int[] {R.string.settings_option_thinking_none, R.string.settings_option_thinking_minimal, R.string.settings_option_thinking_low, R.string.settings_option_thinking_medium, R.string.settings_option_thinking_high, R.string.settings_option_thinking_xhigh, R.string.settings_option_thinking_max}, new String[] {"Api", "ThinkingStrength"}),
            bool("omit-thinking-parameters", R.string.settings_field_omit_thinking_parameters, R.string.settings_field_hint_omit_thinking_parameters, new String[] {"DebugOmitThinkingParameters"})
        );
    }

    private static FieldSpec text(String id, int label, int hint, String[] path) {
        return new FieldSpec(id, label, hint, TYPE_TEXT, path, null, null, 0, 0);
    }

    private static FieldSpec text(
        String id,
        int label,
        int hint,
        String[] path,
        int maxLength
    ) {
        return new FieldSpec(
            id,
            label,
            hint,
            TYPE_TEXT,
            path,
            null,
            null,
            0,
            0,
            0,
            maxLength,
            false
        );
    }

    private static FieldSpec secret(String id, int label, int hint) {
        return new FieldSpec(id, label, hint, TYPE_SECRET, null, null, null, 0, 0);
    }

    private static FieldSpec number(String id, int label, int hint, String[] path, double min, double max) {
        return new FieldSpec(id, label, hint, TYPE_NUMBER, path, null, null, min, max);
    }

    private static FieldSpec decimal(String id, int label, int hint, String[] path, double min, double max) {
        return new FieldSpec(id, label, hint, TYPE_DECIMAL, path, null, null, min, max);
    }

    private static FieldSpec bool(String id, int label, int hint, String[] path) {
        return new FieldSpec(id, label, hint, TYPE_BOOLEAN, path, null, null, 0, 0);
    }

    private static FieldSpec slider(
        String id,
        int label,
        int hint,
        String[] path,
        double min,
        double max,
        double step,
        boolean integer
    ) {
        return new FieldSpec(
            id,
            label,
            hint,
            TYPE_SLIDER,
            path,
            null,
            null,
            min,
            max,
            step,
            0,
            integer
        );
    }

    private static FieldSpec select(String id, int label, int hint, String[] codes, int[] labels) {
        return new FieldSpec(id, label, hint, TYPE_SELECT, null, codes, labels, 0, 0);
    }

    private static FieldSpec select(String id, int label, int hint, String[] codes, int[] labels, String[] path) {
        return new FieldSpec(id, label, hint, TYPE_SELECT, path, codes, labels, 0, 0);
    }

    private String readStringOrDefault(String[] path, String fallback) {
        String value = readString(path);
        return value.isEmpty() ? fallback : value;
    }

    private static String[] optionLabels(int[] ids, SettingsCategoryActivity activity) {
        String[] result = new String[ids.length];
        for (int index = 0; index < ids.length; index++) {
            result[index] = activity.getString(ids[index]);
        }
        return result;
    }

    private static final class ValidationException extends Exception {
        final EditText field;

        ValidationException(EditText field, String message) {
            super(message);
            this.field = field;
        }
    }

    private static String safeMessage(Throwable error) {
        String message = error.getMessage();
        return message == null || message.trim().isEmpty()
            ? error.getClass().getSimpleName()
            : message;
    }
}
