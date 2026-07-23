package com.quarty.housamoembedtrans.ui;

import com.quarty.housamoembedtrans.R;
import com.quarty.housamoembedtrans.storage.ConfigStore;
import com.quarty.housamoembedtrans.storage.SceneStore;
import com.quarty.housamoembedtrans.translation.TranslationApiClient;

import android.content.Intent;
import android.os.Bundle;
import android.text.TextUtils;
import android.view.View;
import android.widget.AdapterView;
import android.widget.EditText;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.IdRes;
import androidx.annotation.StringRes;
import androidx.appcompat.app.AppCompatActivity;

import com.google.android.material.button.MaterialButton;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.switchmaterial.SwitchMaterial;
import com.google.android.material.textfield.TextInputLayout;

import org.json.JSONObject;

import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Module settings UI. It edits only user-owned settings while preserving
 * runtime-owned config fields unchanged.
 */
public class SettingsActivity extends AppCompatActivity {

    private ConfigStore configStore;
    private SceneStore sceneStore;
    private JSONObject currentConfig;

    private TextView configStatus;
    private TextView chardictSummary;
    private TextView gametermsSummary;
    private TextView sceneFilesSummary;
    private Spinner apiProtocol;
    private Spinner targetLanguagePreset;
    private TextInputLayout customTargetLanguageLayout;
    private EditText customTargetLanguage;
    private EditText apiUrl;
    private EditText apiKey;
    private EditText model;
    private EditText networkRetryCount;
    private EditText resultRepairCount;
    private MaterialButton queryModelsButton;
    private SwitchMaterial overwriteJson;
    private SwitchMaterial parseOnlyDebug;
    private SwitchMaterial failedApiResponseDump;
    private SwitchMaterial pageRecDebug;
    private EditText highRelevance;
    private EditText midRelevance;
    private EditText densityHigh;
    private EditText textLowScore;
    private EditText textMentionedScore;
    private EditText relatedNum;
    private EditText lowTermScore;
    private final ExecutorService modelQueryExecutor = Executors.newSingleThreadExecutor();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_settings);
        SystemBarInsets.apply(findViewById(R.id.root_settings));

        configStore = new ConfigStore(this);
        sceneStore = new SceneStore(this);
        bindViews();
        bindSections();
        bindActions();
        loadConfig();
    }

    private void bindViews() {
        configStatus = findViewById(R.id.tv_config_status);
        chardictSummary = findViewById(R.id.tv_chardict_summary);
        gametermsSummary = findViewById(R.id.tv_gameterms_summary);
        sceneFilesSummary = findViewById(R.id.tv_scene_files_summary);
        apiProtocol = findViewById(R.id.spinner_api_protocol);
        targetLanguagePreset = findViewById(R.id.spinner_target_language);
        customTargetLanguageLayout = findViewById(R.id.til_custom_target_language);
        customTargetLanguage = findViewById(R.id.et_custom_target_language);
        apiUrl = findViewById(R.id.et_api_url);
        apiKey = findViewById(R.id.et_api_key);
        model = findViewById(R.id.et_model);
        networkRetryCount = findViewById(R.id.et_network_retry_count);
        resultRepairCount = findViewById(R.id.et_result_repair_count);
        queryModelsButton = findViewById(R.id.btn_query_models);
        overwriteJson = findViewById(R.id.switch_overwrite_json);
        parseOnlyDebug = findViewById(R.id.switch_parse_only_debug);
        failedApiResponseDump = findViewById(
            R.id.switch_failed_api_response_dump
        );
        pageRecDebug = findViewById(R.id.switch_page_rec_debug);
        highRelevance = findViewById(R.id.et_high_relevance);
        midRelevance = findViewById(R.id.et_mid_relevance);
        densityHigh = findViewById(R.id.et_density_high);
        textLowScore = findViewById(R.id.et_text_low_score);
        textMentionedScore = findViewById(R.id.et_text_mentioned_score);
        relatedNum = findViewById(R.id.et_related_num);
        lowTermScore = findViewById(R.id.et_low_term_score);
    }

    private void bindSections() {
        bindSection(
            R.id.header_api,
            R.id.body_api,
            R.id.arrow_api,
            R.string.api_settings,
            true
        );
        bindSection(
            R.id.header_translation,
            R.id.body_translation,
            R.id.arrow_translation,
            R.string.translation_settings,
            true
        );
        bindSection(
            R.id.header_chardict,
            R.id.body_chardict,
            R.id.arrow_chardict,
            R.string.chardict_settings,
            false
        );
        bindSection(
            R.id.header_gameterms,
            R.id.body_gameterms,
            R.id.arrow_gameterms,
            R.string.gameterms_settings,
            false
        );
        bindSection(
            R.id.header_capture,
            R.id.body_capture,
            R.id.arrow_capture,
            R.string.capture_settings,
            true
        );
        bindSection(
            R.id.header_character_weight,
            R.id.body_character_weight,
            R.id.arrow_character_weight,
            R.string.character_weight_settings,
            false
        );
    }

    private void bindSection(
        @IdRes int headerId,
        @IdRes int bodyId,
        @IdRes int arrowId,
        @StringRes int titleId,
        boolean initiallyExpanded
    ) {
        View header = findViewById(headerId);
        View body = findViewById(bodyId);
        TextView arrow = findViewById(arrowId);

        setSectionExpanded(header, body, arrow, titleId, initiallyExpanded);
        header.setOnClickListener(view -> setSectionExpanded(
            header,
            body,
            arrow,
            titleId,
            body.getVisibility() != View.VISIBLE
        ));
    }

    private void setSectionExpanded(
        View header,
        View body,
        TextView arrow,
        @StringRes int titleId,
        boolean expanded
    ) {
        body.setVisibility(expanded ? View.VISIBLE : View.GONE);
        arrow.setText(expanded ? R.string.indicator_expanded : R.string.indicator_collapsed);
        header.setContentDescription(getString(
            expanded ? R.string.collapse_section : R.string.expand_section,
            getString(titleId)
        ));
        header.setSelected(expanded);
    }

    private void bindActions() {
        findViewById(R.id.btn_save).setOnClickListener(view -> saveConfig());
        findViewById(R.id.btn_reset).setOnClickListener(view -> confirmReset());
        queryModelsButton.setOnClickListener(view -> queryAvailableModels());
        targetLanguagePreset.setOnItemSelectedListener(
            new AdapterView.OnItemSelectedListener() {
                @Override
                public void onItemSelected(
                    AdapterView<?> parent,
                    View view,
                    int position,
                    long id
                ) {
                    updateCustomTargetLanguageVisibility();
                }

                @Override
                public void onNothingSelected(AdapterView<?> parent) {
                    updateCustomTargetLanguageVisibility();
                }
            }
        );
        findViewById(R.id.btn_edit_chardict).setOnClickListener(view -> {
            startActivity(new Intent(this, CharacterDictionaryActivity.class));
        });
        findViewById(R.id.btn_edit_gameterms).setOnClickListener(view -> {
            startActivity(new Intent(this, GameTermsActivity.class));
        });
        findViewById(R.id.btn_manage_scene_files).setOnClickListener(view -> {
            startActivity(new Intent(this, SceneFilesActivity.class));
        });
    }

    @Override
    protected void onResume() {
        super.onResume();
        updateChardictSummary();
        updateGameTermsSummary();
        updateSceneFilesSummary();
    }

    private void updateSceneFilesSummary() {
        if (sceneStore == null || sceneFilesSummary == null) {
            return;
        }
        modelQueryExecutor.execute(() -> {
            int count = sceneStore.listValidScenes().size();
            runOnUiThread(() -> {
                if (!isFinishing() && !isDestroyed()) {
                    sceneFilesSummary.setText(getString(
                        R.string.scene_files_summary,
                        count
                    ));
                }
            });
        });
    }

    private void loadConfig() {
        try {
            ConfigStore.LoadResult result = configStore.load();
            currentConfig = result.config;
            showConfig(currentConfig);
            if (result.invalidUserOverride) {
                configStatus.setText(R.string.config_source_invalid);
            } else {
                configStatus.setText(
                    result.userOverride
                        ? R.string.config_source_saved
                        : R.string.config_source_default
                );
            }
        } catch (Exception e) {
            currentConfig = null;
            configStatus.setText(getString(
                R.string.config_load_failed,
                safeMessage(e)
            ));
            findViewById(R.id.btn_save).setEnabled(false);
        }
    }

    private void updateChardictSummary() {
        if (configStore == null || chardictSummary == null) {
            return;
        }

        try {
            ConfigStore.JsonLoadResult result = configStore.loadJson(
                ConfigStore.CHARDICT_FILE_NAME
            );
            int statusId;
            if (result.invalidUserOverride) {
                statusId = R.string.chardict_summary_invalid;
            } else if (result.userOverride) {
                statusId = R.string.chardict_summary_user;
            } else {
                statusId = R.string.chardict_summary_default;
            }
            int characterCount = Math.max(
                0,
                result.json.length() - (result.json.has("mc") ? 1 : 0)
            );
            chardictSummary.setText(getString(statusId, characterCount));
        } catch (Exception e) {
            chardictSummary.setText(getString(
                R.string.chardict_load_failed,
                safeMessage(e)
            ));
        }
    }

    private void updateGameTermsSummary() {
        if (configStore == null || gametermsSummary == null) {
            return;
        }

        try {
            ConfigStore.JsonLoadResult result = configStore.loadJson(
                ConfigStore.GAMETERMS_FILE_NAME
            );
            int statusId;
            if (result.invalidUserOverride) {
                statusId = R.string.gameterms_summary_invalid;
            } else if (result.userOverride) {
                statusId = R.string.gameterms_summary_user;
            } else {
                statusId = R.string.gameterms_summary_default;
            }
            gametermsSummary.setText(getString(statusId, result.json.length()));
        } catch (Exception e) {
            gametermsSummary.setText(getString(
                R.string.gameterms_load_failed,
                safeMessage(e)
            ));
        }
    }

    private void showConfig(JSONObject config) throws Exception {
        JSONObject userSettings = config.getJSONObject("UserSettings");
        JSONObject translationApi = userSettings.optJSONObject("TranslationApi");
        if (translationApi == null) {
            translationApi = new JSONObject();
        }

        selectCode(
            apiProtocol,
            R.array.api_protocol_codes,
            translationApi.optString("Protocol", "openai")
        );
        apiUrl.setText(translationApi.optString("BaseUrl", ""));
        model.setText(translationApi.optString("Model", ""));
        boolean hasSplitRetryCounts = translationApi.has("NetworkRetryCount")
            || translationApi.has("ResultRepairCount");
        networkRetryCount.setText(String.valueOf(hasSplitRetryCounts
            ? translationApi.optInt(
                "NetworkRetryCount",
                ConfigStore.DEFAULT_NETWORK_RETRY_COUNT
            )
            : translationApi.optInt(
                "RetryCount",
                ConfigStore.DEFAULT_NETWORK_RETRY_COUNT
            )));
        resultRepairCount.setText(String.valueOf(hasSplitRetryCounts
            ? translationApi.optInt(
                "ResultRepairCount",
                ConfigStore.DEFAULT_RESULT_REPAIR_COUNT
            )
            : translationApi.optInt(
                "RetryCount",
                ConfigStore.DEFAULT_RESULT_REPAIR_COUNT
            )));

        apiKey.setText(configStore.loadApiKey());

        showTargetLanguage(userSettings.optString("TargetLanguage", "zh-cn"));
        overwriteJson.setChecked(userSettings.optBoolean("OverwriteExistingJson", false));
        parseOnlyDebug.setChecked(userSettings.optBoolean("EnableParseOnlyDebug", false));
        failedApiResponseDump.setChecked(
            userSettings.optBoolean("EnableFailedApiResponseDump", false)
        );
        pageRecDebug.setChecked(userSettings.optBoolean("EnablePageRecDebug", false));

        JSONObject weights = userSettings.getJSONObject("CharacterWeight");
        setJsonNumber(highRelevance, weights, "HighRelevance");
        setJsonNumber(midRelevance, weights, "MidRelevance");
        setJsonNumber(densityHigh, weights, "DensityHigh");
        setJsonNumber(textLowScore, weights, "TextLowScore");
        setJsonNumber(textMentionedScore, weights, "TextMentionedScore");
        setJsonNumber(relatedNum, weights, "RelatedNum");
        setJsonNumber(lowTermScore, weights, "LowTermScore");

        clearErrors();
    }

    private void saveConfig() {
        if (currentConfig == null) {
            return;
        }

        try {
            clearErrors();
            JSONObject updated = buildConfigFromForm(currentConfig);
            configStore.save(updated);

            configStore.saveApiKey(textOf(apiKey));

            currentConfig = updated;
            configStatus.setText(R.string.config_source_saved);
            Toast.makeText(this, R.string.settings_saved, Toast.LENGTH_SHORT).show();
        } catch (ValidationException e) {
            e.field.setError(getString(e.messageId));
            e.field.requestFocus();
        } catch (Exception e) {
            Toast.makeText(
                this,
                getString(R.string.config_save_failed, safeMessage(e)),
                Toast.LENGTH_LONG
            ).show();
        }
    }

    private JSONObject buildConfigFromForm(JSONObject config) throws Exception {
        int parsedNetworkRetryCount = parseRetryCount(networkRetryCount);
        int parsedResultRepairCount = parseRetryCount(resultRepairCount);
        double parsedHighRelevance = positiveDouble(highRelevance);
        double parsedMidRelevance = positiveDouble(midRelevance);
        double parsedDensityHigh = positiveDouble(densityHigh);
        double parsedTextLowScore = positiveDouble(textLowScore);
        double parsedTextMentionedScore = positiveDouble(textMentionedScore);
        int parsedRelatedNum = positiveInt(relatedNum);
        int parsedLowTermScore = positiveInt(lowTermScore);

        if (parsedHighRelevance < parsedMidRelevance) {
            throw new ValidationException(
                highRelevance,
                R.string.error_high_less_than_mid
            );
        }

        if (parsedTextLowScore < parsedTextMentionedScore) {
            throw new ValidationException(
                textLowScore,
                R.string.error_low_less_than_mentioned
            );
        }

        JSONObject userSettings = config.getJSONObject("UserSettings");
        JSONObject translationApi = userSettings.optJSONObject("TranslationApi");
        if (translationApi == null) {
            translationApi = new JSONObject();
            userSettings.put("TranslationApi", translationApi);
        }

        translationApi.put("Protocol", selectedCode(apiProtocol, R.array.api_protocol_codes));
        translationApi.put("BaseUrl", textOf(apiUrl));
        translationApi.put("Model", textOf(model));
        translationApi.remove("RetryCount");
        translationApi.put("NetworkRetryCount", parsedNetworkRetryCount);
        translationApi.put("ResultRepairCount", parsedResultRepairCount);

        userSettings.put("TargetLanguage", selectedTargetLanguage());
        userSettings.put("OverwriteExistingJson", overwriteJson.isChecked());
        userSettings.put("EnableParseOnlyDebug", parseOnlyDebug.isChecked());
        userSettings.put(
            "EnableFailedApiResponseDump",
            failedApiResponseDump.isChecked()
        );
        userSettings.put("EnablePageRecDebug", pageRecDebug.isChecked());

        JSONObject weights = userSettings.getJSONObject("CharacterWeight");
        weights.put("HighRelevance", parsedHighRelevance);
        weights.put("MidRelevance", parsedMidRelevance);
        weights.put("DensityHigh", parsedDensityHigh);
        weights.put("TextLowScore", parsedTextLowScore);
        weights.put("TextMentionedScore", parsedTextMentionedScore);
        weights.put("RelatedNum", parsedRelatedNum);
        weights.put("LowTermScore", parsedLowTermScore);

        return config;
    }

    private void confirmReset() {
        new MaterialAlertDialogBuilder(this)
            .setTitle(R.string.reset_dialog_title)
            .setMessage(R.string.reset_dialog_message)
            .setNegativeButton(R.string.cancel_action, null)
            .setPositiveButton(R.string.reset_action, (dialog, which) -> loadDefaultsIntoForm())
            .show();
    }

    private void queryAvailableModels() {
        String protocol = selectedCode(apiProtocol, R.array.api_protocol_codes);
        String baseUrl = textOf(apiUrl);
        String key = textOf(apiKey);

        setModelQueryRunning(true);
        modelQueryExecutor.execute(() -> {
            try {
                List<String> models = TranslationApiClient.listModels(
                    protocol,
                    baseUrl,
                    key
                );
                runOnUiThread(() -> {
                    if (isFinishing() || isDestroyed()) {
                        return;
                    }
                    setModelQueryRunning(false);
                    showModelPicker(models);
                });
            } catch (Exception e) {
                String message = safeMessage(e);
                runOnUiThread(() -> {
                    if (isFinishing() || isDestroyed()) {
                        return;
                    }
                    setModelQueryRunning(false);
                    new MaterialAlertDialogBuilder(this)
                        .setTitle(R.string.query_models_failed_title)
                        .setMessage(getString(R.string.query_models_failed, message))
                        .setPositiveButton(android.R.string.ok, null)
                        .show();
                });
            }
        });
    }

    private void showModelPicker(List<String> models) {
        String[] modelIds = models.toArray(new String[0]);
        int selectedIndex = models.indexOf(textOf(model));

        new MaterialAlertDialogBuilder(this)
            .setTitle(getString(R.string.select_model_title, modelIds.length))
            .setSingleChoiceItems(modelIds, selectedIndex, (dialog, which) -> {
                model.setText(modelIds[which]);
                model.setSelection(modelIds[which].length());
                dialog.dismiss();
            })
            .setNegativeButton(R.string.cancel_action, null)
            .show();
    }

    private void setModelQueryRunning(boolean running) {
        queryModelsButton.setEnabled(!running);
        queryModelsButton.setText(
            running ? R.string.querying_models : R.string.query_models
        );
    }

    private void loadDefaultsIntoForm() {
        try {
            JSONObject defaults = configStore.loadBundledDefault();
            showConfig(defaults);
            if (currentConfig == null) {
                currentConfig = defaults;
            }
            configStatus.setText(R.string.defaults_loaded);
            findViewById(R.id.btn_save).setEnabled(true);
        } catch (Exception e) {
            Toast.makeText(
                this,
                getString(R.string.config_load_failed, safeMessage(e)),
                Toast.LENGTH_LONG
            ).show();
        }
    }

    private void clearErrors() {
        EditText[] fields = {
            customTargetLanguage,
            networkRetryCount,
            resultRepairCount,
            highRelevance,
            midRelevance,
            densityHigh,
            textLowScore,
            textMentionedScore,
            relatedNum,
            lowTermScore
        };

        for (EditText field : fields) {
            field.setError(null);
        }
    }

    @Override
    protected void onDestroy() {
        modelQueryExecutor.shutdownNow();
        super.onDestroy();
    }

    private static void setJsonNumber(EditText field, JSONObject json, String key)
        throws Exception {
        field.setText(String.valueOf(json.get(key)));
    }

    private String requireText(EditText field) throws ValidationException {
        String value = textOf(field);
        if (TextUtils.isEmpty(value)) {
            throw new ValidationException(field, R.string.error_required);
        }
        return value;
    }

    private double positiveDouble(EditText field) throws ValidationException {
        try {
            double value = Double.parseDouble(requireText(field));
            if (!Double.isFinite(value) || value <= 0.0 || value > 100000.0) {
                throw new NumberFormatException();
            }
            return value;
        } catch (ValidationException e) {
            throw e;
        } catch (Exception e) {
            throw new ValidationException(field, R.string.error_positive_number);
        }
    }

    private int positiveInt(EditText field) throws ValidationException {
        try {
            int value = Integer.parseInt(requireText(field));
            if (value < 1) {
                throw new NumberFormatException();
            }
            return value;
        } catch (ValidationException e) {
            throw e;
        } catch (Exception e) {
            throw new ValidationException(field, R.string.error_positive_integer);
        }
    }

    private int parseRetryCount(EditText field) throws ValidationException {
        try {
            int value = Integer.parseInt(requireText(field));
            if (value < 0 || value > ConfigStore.MAX_TRANSLATION_RETRY_COUNT) {
                throw new NumberFormatException();
            }
            return value;
        } catch (ValidationException e) {
            throw e;
        } catch (Exception e) {
            throw new ValidationException(field, R.string.error_retry_count_range);
        }
    }

    private String selectedCode(Spinner spinner, int codeArrayId) {
        String[] codes = getResources().getStringArray(codeArrayId);
        int position = spinner.getSelectedItemPosition();
        if (position < 0 || position >= codes.length) {
            return codes[0];
        }
        return codes[position];
    }

    private void showTargetLanguage(String language) {
        String[] codes = getResources().getStringArray(R.array.target_language_codes);
        for (int index = 0; index < codes.length - 1; index++) {
            if (codes[index].equalsIgnoreCase(language)) {
                targetLanguagePreset.setSelection(index);
                customTargetLanguage.setText("");
                updateCustomTargetLanguageVisibility();
                return;
            }
        }

        targetLanguagePreset.setSelection(codes.length - 1);
        customTargetLanguage.setText(language);
        updateCustomTargetLanguageVisibility();
    }

    private String selectedTargetLanguage() throws ValidationException {
        String code = selectedCode(
            targetLanguagePreset,
            R.array.target_language_codes
        );
        return "custom".equals(code) ? requireText(customTargetLanguage) : code;
    }

    private void updateCustomTargetLanguageVisibility() {
        boolean custom = "custom".equals(selectedCode(
            targetLanguagePreset,
            R.array.target_language_codes
        ));
        customTargetLanguageLayout.setVisibility(custom ? View.VISIBLE : View.GONE);
        if (!custom) {
            customTargetLanguage.setError(null);
        }
    }

    private void selectCode(Spinner spinner, int codeArrayId, String wantedCode) {
        String[] codes = getResources().getStringArray(codeArrayId);
        for (int index = 0; index < codes.length; index++) {
            if (codes[index].equalsIgnoreCase(wantedCode)) {
                spinner.setSelection(index);
                return;
            }
        }
        spinner.setSelection(0);
    }

    private static String textOf(EditText field) {
        return field.getText() == null ? "" : field.getText().toString().trim();
    }

    private static String safeMessage(Throwable throwable) {
        String message = throwable.getMessage();
        return TextUtils.isEmpty(message)
            ? throwable.getClass().getSimpleName()
            : message;
    }

    private static final class ValidationException extends Exception {
        final EditText field;
        final int messageId;

        ValidationException(EditText field, @StringRes int messageId) {
            this.field = field;
            this.messageId = messageId;
        }
    }
}
