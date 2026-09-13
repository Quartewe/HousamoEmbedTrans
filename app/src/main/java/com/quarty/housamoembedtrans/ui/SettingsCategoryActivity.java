package com.quarty.housamoembedtrans.ui;

import com.quarty.housamoembedtrans.R;
import com.quarty.housamoembedtrans.provider.ApiConcurrencySettings;
import com.quarty.housamoembedtrans.provider.TranslationApiClient;
import com.quarty.housamoembedtrans.storage.config.ConfigStore;

import android.os.Bundle;
import android.text.InputType;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.widget.ArrayAdapter;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

import com.google.android.material.appbar.MaterialToolbar;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.switchmaterial.SwitchMaterial;
import com.google.android.material.textfield.TextInputLayout;

import org.json.JSONObject;

import java.util.ArrayList;
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
            this.id = id;
            this.label = label;
            this.hint = hint;
            this.type = type;
            this.path = path;
            this.codes = codes;
            this.optionLabels = optionLabels;
            this.min = min;
            this.max = max;
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
    private final Map<String, View> controls = new HashMap<>();
    private final Map<String, View> rows = new HashMap<>();
    private final ExecutorService modelQueryExecutor =
        Executors.newSingleThreadExecutor();
    private MaterialButton queryModelsButton;

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
        saveButton = findViewById(R.id.btn_save_settings_category);

        MaterialToolbar toolbar = findViewById(R.id.toolbar_settings_category);
        toolbar.setTitle(definition.title);
        toolbar.setNavigationOnClickListener(view -> onBackPressed());
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
            new MaterialAlertDialogBuilder(this)
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
        controls.clear();
        rows.clear();
        for (FieldSpec spec : fieldsFor(definition.id)) {
            View row = createField(spec);
            rows.put(spec.id, row);
            fieldsContainer.addView(row);
        }
        updateConditionalVisibility();
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
            } else if (spec.type == TYPE_SELECT && control instanceof Spinner) {
                fields.putInt(
                    spec.id,
                    ((Spinner) control).getSelectedItemPosition()
                );
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
            } else if (spec.type == TYPE_SELECT && control instanceof Spinner) {
                if (fields.containsKey(spec.id)) {
                    int position = fields.getInt(
                        spec.id,
                        ((Spinner) control).getSelectedItemPosition()
                    );
                    if (position >= 0 && position < spec.codes.length) {
                        ((Spinner) control).setSelection(position);
                    }
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
            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.VERTICAL);
            row.setPadding(0, dp(4), 0, dp(4));
            SwitchMaterial toggle = new SwitchMaterial(this);
            toggle.setText(spec.label);
            toggle.setChecked(readBoolean(spec.path));
            toggle.setOnCheckedChangeListener(
                (button, checked) -> updateConditionalVisibility()
            );
            controls.put(spec.id, toggle);
            row.addView(toggle);
            addHint(row, spec.hint);
            return row;
        }
        if (spec.type == TYPE_SELECT) {
            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.VERTICAL);
            TextView label = new TextView(this);
            label.setText(spec.label);
            label.setTextAppearance(
                com.google.android.material.R.style.TextAppearance_MaterialComponents_Body1
            );
            row.addView(label);
            Spinner spinner = new Spinner(this);
            spinner.setAdapter(new ArrayAdapter<String>(
                this,
                android.R.layout.simple_spinner_dropdown_item,
                optionLabels(spec.optionLabels)
            ));
            String selectedValue = readString(spec.path);
            int selectedIndex = indexOf(spec.codes, selectedValue);
            if ("target-language".equals(spec.id)
                && !spec.codes[selectedIndex].equalsIgnoreCase(selectedValue)) {
                selectedIndex = spec.codes.length - 1;
            }
            spinner.setSelection(selectedIndex);
            spinner.setTag(spec.codes);
            spinner.setOnItemSelectedListener(
                new android.widget.AdapterView.OnItemSelectedListener() {
                    @Override
                    public void onItemSelected(
                        android.widget.AdapterView<?> parent,
                        View view,
                        int position,
                        long id
                    ) {
                        updateConditionalVisibility();
                    }

                    @Override
                    public void onNothingSelected(
                        android.widget.AdapterView<?> parent
                    ) {
                        updateConditionalVisibility();
                    }
                }
            );
            LinearLayout.LayoutParams spinnerParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            );
            spinnerParams.topMargin = dp(4);
            row.addView(spinner, spinnerParams);
            controls.put(spec.id, spinner);
            addHint(row, spec.hint);
            if ("target-language".equals(spec.id)) {
                TextInputLayout customLayout = textInput(
                    R.string.settings_field_target_language,
                    R.string.settings_field_hint_target_language,
                    InputType.TYPE_CLASS_TEXT
                );
                EditText custom = (EditText) customLayout.getEditText();
                custom.setHint(R.string.settings_category_target_custom);
                if (selectedIndex == spec.codes.length - 1) {
                    custom.setText(selectedValue);
                }
                controls.put("custom-target-language", custom);
                row.addView(customLayout);
            }
            return row;
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
        String value = spec.type == TYPE_SECRET
            ? currentApiKey
            : readString(spec.path);
        input.setText(value);
        controls.put(spec.id, input);
        if ("model".equals(spec.id)) {
            // Keep the query action beside the model field.  Its helper copy
            // belongs below the row so the button centers on the editable
            // control and the input retains usable width on narrow screens.
            inputLayout.setHelperText(null);
            LinearLayout field = new LinearLayout(this);
            field.setOrientation(LinearLayout.VERTICAL);
            LinearLayout.LayoutParams fieldParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            );
            fieldParams.topMargin = dp(8);
            field.setLayoutParams(fieldParams);

            LinearLayout inputRow = new LinearLayout(this);
            inputRow.setGravity(Gravity.CENTER_VERTICAL);
            inputRow.setOrientation(LinearLayout.HORIZONTAL);
            field.addView(inputRow, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ));
            inputRow.addView(inputLayout, new LinearLayout.LayoutParams(
                0,
                LinearLayout.LayoutParams.WRAP_CONTENT,
                1f
            ));
            queryModelsButton = new MaterialButton(this);
            queryModelsButton.setText(R.string.settings_category_query_models);
            queryModelsButton.setOnClickListener(view -> queryModels());
            LinearLayout.LayoutParams queryParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            );
            queryParams.setMarginStart(dp(8));
            inputRow.addView(queryModelsButton, queryParams);
            addHint(field, spec.hint);
            return field;
        }
        return inputLayout;
    }

    private TextInputLayout textInput(
        int label,
        int hint,
        int inputType
    ) {
        TextInputLayout layout = new TextInputLayout(this);
        layout.setHint(getString(label));
        layout.setHelperText(getString(hint));
        layout.setLayoutParams(new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ));
        ((LinearLayout.LayoutParams) layout.getLayoutParams()).topMargin = dp(8);
        EditText input = new com.google.android.material.textfield.TextInputEditText(this);
        input.setInputType(inputType);
        input.setMaxLines(inputType == InputType.TYPE_CLASS_TEXT ? 1 : 1);
        layout.addView(input);
        if (inputType == (InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD)) {
            layout.setEndIconMode(TextInputLayout.END_ICON_PASSWORD_TOGGLE);
        }
        return layout;
    }

    private void addHint(LinearLayout row, int hint) {
        TextView text = new TextView(this);
        text.setText(hint);
        text.setTextAppearance(
            com.google.android.material.R.style.TextAppearance_MaterialComponents_Caption
        );
        text.setTextColor(getColor(R.color.het_on_surface_muted));
        row.addView(text);
    }

    private void updateConditionalVisibility() {
        boolean streaming = isChecked("enable-streaming-repair");
        setRowVisibility("repair-gradient-count", streaming);
        setRowVisibility("use-full-scene-for-repair", streaming);
        boolean compression = isChecked("enable-auto-compression");
        setRowVisibility("continue-auto-summary-after-manual", compression);
        boolean recovery = isChecked("translation-auto-recover")
            || isChecked("summary-auto-recover");
        setRowVisibility("recovery-sort-order", recovery);
        View custom = controls.get("custom-target-language");
        View target = controls.get("target-language");
        if (custom != null && target instanceof Spinner) {
            String[] codes = (String[]) target.getTag();
            custom.setVisibility(
                ((Spinner) target).getSelectedItemPosition() == codes.length - 1
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
        queryModelsButton.setText(R.string.settings_category_query_models_loading);
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
                    queryModelsButton.setText(R.string.settings_category_query_models);
                    showModelPicker(models);
                });
            } catch (Exception error) {
                runOnUiThread(() -> {
                    if (isFinishing() || isDestroyed()) {
                        return;
                    }
                    queryModelsButton.setEnabled(true);
                    queryModelsButton.setText(R.string.settings_category_query_models_retry);
                    Toast.makeText(
                        this,
                        getString(
                            R.string.settings_category_query_models_failed,
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
            Toast.makeText(
                this,
                getString(R.string.settings_category_query_models_failed, "no models returned"),
                Toast.LENGTH_LONG
            ).show();
            return;
        }
        String[] values = models.toArray(new String[0]);
        EditText model = (EditText) controls.get("model");
        int selected = models.indexOf(textOf(model));
        new MaterialAlertDialogBuilder(this)
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
        if (!(view instanceof Spinner)) {
            return "";
        }
        Spinner spinner = (Spinner) view;
        String[] codes = (String[]) spinner.getTag();
        int position = spinner.getSelectedItemPosition();
        return position >= 0 && position < codes.length ? codes[position] : codes[0];
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
                text("base-url", R.string.settings_field_base_url, R.string.settings_field_hint_base_url, new String[] {"TranslationApi", "BaseUrl"}),
                secret("api-key", R.string.settings_field_api_key, R.string.settings_field_hint_api_key),
                text("model", R.string.settings_field_model, R.string.settings_field_hint_model, new String[] {"TranslationApi", "Model"}),
                select("target-language", R.string.settings_field_target_language, R.string.settings_field_hint_target_language,
                    new String[] {"zh-cn", "zh-tw", "en", "custom"}, new int[] {R.string.settings_option_zh_cn, R.string.settings_option_zh_tw, R.string.settings_option_en, R.string.settings_category_target_custom}, new String[] {"TargetLanguage"}),
                select("thinking-strength", R.string.settings_field_thinking_strength, R.string.settings_field_hint_thinking_strength,
                    new String[] {"none", "minimal", "low", "medium", "high", "xhigh", "max"}, new int[] {R.string.settings_option_thinking_none, R.string.settings_option_thinking_minimal, R.string.settings_option_thinking_low, R.string.settings_option_thinking_medium, R.string.settings_option_thinking_high, R.string.settings_option_thinking_xhigh, R.string.settings_option_thinking_max}, new String[] {"Api", "ThinkingStrength"}),
                number("context-length", R.string.settings_field_context_length, R.string.settings_field_hint_context_length, new String[] {"Api", "context_length"}, 1, 1_000_000),
                number("max-concurrent-requests", R.string.settings_field_api_concurrency, R.string.settings_field_hint_api_concurrency, new String[] {"Api", "max_concurrent_requests"}, 1, 8),
                number("network-retry-count", R.string.settings_field_network_retry_count, R.string.settings_field_hint_network_retry_count, new String[] {"TranslationApi", "NetworkRetryCount"}, 0, 5)
            );
        }
        if (SettingsCategory.TRANSLATION_REPAIR.equals(categoryId)) {
            return Arrays.asList(
                number("result-repair-count", R.string.settings_field_result_repair_count, R.string.settings_field_hint_result_repair_count, new String[] {"TranslationApi", "ResultRepairCount"}, 0, 5),
                bool("enable-streaming-repair", R.string.settings_field_enable_streaming_repair, R.string.settings_field_hint_enable_streaming_repair, new String[] {"TranslationApi", "EnableStreamingRepair"}),
                number("repair-gradient-count", R.string.settings_field_repair_gradient_count, R.string.settings_field_hint_repair_gradient_count, new String[] {"TranslationApi", "RepairGradientCount"}, 2, 8),
                bool("use-full-scene-for-repair", R.string.settings_field_use_full_scene_for_repair, R.string.settings_field_hint_use_full_scene_for_repair, new String[] {"TranslationApi", "UseFullSceneForRepair"})
            );
        }
        if (SettingsCategory.CAPTURE_SYNC.equals(categoryId)) {
            return Arrays.asList(
                bool("overwrite-existing-json", R.string.settings_field_overwrite_existing_json, R.string.settings_field_hint_overwrite_existing_json, new String[] {"OverwriteExistingJson"}),
                number("scene-worker-count", R.string.settings_field_scene_worker_count, R.string.settings_field_hint_scene_worker_count, new String[] {"SceneWorkerCount"}, 1, 4),
                select("conflict-resolution-mode", R.string.settings_field_conflict_resolution_mode, R.string.settings_field_hint_conflict_resolution_mode,
                    new String[] {"manual", "game", "het"}, new int[] {R.string.settings_option_conflict_manual, R.string.settings_option_conflict_game, R.string.settings_option_conflict_het}, new String[] {"SceneSync", "ConflictResolutionMode"})
            );
        }
        if (SettingsCategory.CONTEXT_SUMMARY.equals(categoryId)) {
            return Arrays.asList(
                bool("enable-auto-compression", R.string.settings_field_enable_auto_compression, R.string.settings_field_hint_enable_auto_compression, new String[] {"ContextHistory", "EnableAutoCompression"}),
                bool("continue-auto-summary-after-manual", R.string.settings_field_continue_auto_summary_after_manual, R.string.settings_field_hint_continue_auto_summary_after_manual, new String[] {"ContextHistory", "ContinueAutoSummaryAfterManual"}),
                bool("enable-startup-review", R.string.settings_field_enable_startup_review, R.string.settings_field_hint_enable_startup_review, new String[] {"ContextHistory", "EnableStartupReview"}),
                number("default-recent-percent", R.string.settings_field_default_recent_percent, R.string.settings_field_hint_default_recent_percent, new String[] {"ContextHistory", "DefaultRecentPercent"}, 1, 100),
                number("default-recent-scene-limit", R.string.settings_field_default_recent_scene_limit, R.string.settings_field_hint_default_recent_scene_limit, new String[] {"ContextHistory", "DefaultRecentSceneLimit"}, 1, 10_000),
                number("context-summary-retry-count", R.string.settings_field_context_summary_retry_count, R.string.settings_field_hint_context_summary_retry_count, new String[] {"ContextHistory", "ContextSummaryRetryCount"}, 0, 5),
                number("group-summary-retry-count", R.string.settings_field_group_summary_retry_count, R.string.settings_field_hint_group_summary_retry_count, new String[] {"ContextHistory", "GroupSummaryRetryCount"}, 0, 5)
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
            bool("enable-failed-api-response-dump", R.string.settings_field_enable_failed_api_response_dump, R.string.settings_field_hint_enable_failed_api_response_dump, new String[] {"EnableFailedApiResponseDump"})
        );
    }

    private static FieldSpec text(String id, int label, int hint, String[] path) {
        return new FieldSpec(id, label, hint, TYPE_TEXT, path, null, null, 0, 0);
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
