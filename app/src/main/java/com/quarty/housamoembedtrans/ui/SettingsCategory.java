package com.quarty.housamoembedtrans.ui;

import android.content.Context;

import com.quarty.housamoembedtrans.R;

import org.json.JSONObject;

/** Stable ordering and labels for the settings navigation cards. */
public final class SettingsCategory {
    public static final String TRANSLATION_SERVICE = "translation-service";
    public static final String TRANSLATION_REPAIR = "translation-repair";
    public static final String CAPTURE_SYNC = "capture-sync";
    public static final String CONTEXT_SUMMARY = "context-summary";
    public static final String TASK_RECOVERY = "task-recovery";
    public static final String CHARACTER_MATCHING = "character-matching";
    public static final String DIAGNOSTICS = "appearance-diagnostics";

    public static final class Definition {
        public final String id;
        public final int title;
        public final int description;
        public final int applyScope;

        private Definition(
            String id,
            int title,
            int description,
            int applyScope
        ) {
            this.id = id;
            this.title = title;
            this.description = description;
            this.applyScope = applyScope;
        }
    }

    private static final Definition[] DEFINITIONS = {
        new Definition(
            TRANSLATION_SERVICE,
            R.string.settings_category_translation_service,
            R.string.settings_category_translation_service_description,
            R.string.settings_category_translation_service_scope
        ),
        new Definition(
            TRANSLATION_REPAIR,
            R.string.settings_category_translation_repair,
            R.string.settings_category_translation_repair_description,
            R.string.settings_category_translation_repair_scope
        ),
        new Definition(
            CAPTURE_SYNC,
            R.string.settings_category_capture_sync,
            R.string.settings_category_capture_sync_description,
            R.string.settings_category_capture_sync_scope
        ),
        new Definition(
            CONTEXT_SUMMARY,
            R.string.settings_category_context_summary,
            R.string.settings_category_context_summary_description,
            R.string.settings_category_context_summary_scope
        ),
        new Definition(
            TASK_RECOVERY,
            R.string.settings_category_task_recovery,
            R.string.settings_category_task_recovery_description,
            R.string.settings_category_task_recovery_scope
        ),
        new Definition(
            CHARACTER_MATCHING,
            R.string.settings_category_character_matching,
            R.string.settings_category_character_matching_description,
            R.string.settings_category_character_matching_scope
        ),
        new Definition(
            DIAGNOSTICS,
            R.string.settings_category_diagnostics,
            R.string.settings_category_diagnostics_description,
            R.string.settings_category_diagnostics_scope
        )
    };

    private SettingsCategory() {
    }

    public static Definition[] definitions() {
        return DEFINITIONS.clone();
    }

    public static Definition find(String id) {
        if (id == null) {
            return null;
        }
        for (Definition definition : DEFINITIONS) {
            if (definition.id.equals(id)) {
                return definition;
            }
        }
        return null;
    }

    /** Human-readable summary for the home card, derived from current config. */
    public static String summary(
        Context uiContext,
        Definition definition,
        JSONObject userSettings,
        String apiKey
    ) {
        if (definition == null || userSettings == null) {
            return "";
        }
        JSONObject translationApi = userSettings.optJSONObject("TranslationApi");
        JSONObject api = userSettings.optJSONObject("Api");
        JSONObject queue = userSettings.optJSONObject("TranslationQueue");
        JSONObject context = userSettings.optJSONObject("ContextHistory");
        JSONObject weights = userSettings.optJSONObject("CharacterWeight");
        JSONObject sceneSync = userSettings.optJSONObject("SceneSync");
        if (TRANSLATION_SERVICE.equals(definition.id)) {
            String protocol = "anthropic".equalsIgnoreCase(
                translationApi == null ? "" : translationApi.optString("Protocol")
            ) ? uiContext.getString(R.string.settings_option_anthropic)
                : uiContext.getString(R.string.settings_option_openai);
            String model = translationApi == null
                ? ""
                : translationApi.optString("Model", "");
            String target = userSettings.optString("TargetLanguage", "zh-cn");
            return protocol
                + " · "
                + (model.isEmpty() ? uiContext.getString(R.string.settings_summary_model_empty) : model)
                + " · "
                + targetLabel(uiContext, target)
                + " · "
                + (apiKey == null || apiKey.isEmpty()
                    ? uiContext.getString(R.string.settings_category_api_key_empty)
                    : uiContext.getString(R.string.settings_category_api_key_set));
        }
        if (TRANSLATION_REPAIR.equals(definition.id)) {
            return uiContext.getString(
                R.string.settings_summary_repair,
                translationApi == null ? 0 : translationApi.optInt("ResultRepairCount", 0),
                uiContext.getString(translationApi != null
                    && translationApi.optBoolean("EnableStreamingResponse", true)
                    ? R.string.settings_summary_on : R.string.settings_summary_off)
            );
        }
        if (CAPTURE_SYNC.equals(definition.id)) {
            String mode = sceneSync == null
                ? "manual"
                : sceneSync.optString("ConflictResolutionMode", "manual");
            return uiContext.getString(
                R.string.settings_summary_capture,
                userSettings.optInt("SceneWorkerCount", 1),
                uiContext.getString("game".equals(mode)
                    ? R.string.settings_option_conflict_game
                    : "het".equals(mode) ? R.string.settings_option_conflict_het
                        : R.string.settings_option_conflict_manual)
            );
        }
        if (CONTEXT_SUMMARY.equals(definition.id)) {
            return uiContext.getString(
                R.string.settings_summary_context,
                uiContext.getString(context != null
                    && context.optBoolean("EnableAutoCompression", false)
                    ? R.string.settings_summary_on : R.string.settings_summary_off),
                context == null ? 30 : context.optInt("DefaultRecentPercent", 30),
                context == null ? 10 : context.optInt("DefaultRecentSceneLimit", 10)
            );
        }
        if (TASK_RECOVERY.equals(definition.id)) {
            return uiContext.getString(
                R.string.settings_summary_recovery,
                uiContext.getString(queue != null
                    && queue.optBoolean("AutoRecoverPreviousJobs", false)
                    ? R.string.settings_summary_recovery_auto
                    : R.string.settings_summary_recovery_manual),
                uiContext.getString(userSettings.optJSONObject("SummaryQueue") != null
                    && userSettings.optJSONObject("SummaryQueue")
                        .optBoolean("AutoRecoverPreviousJobs", false)
                    ? R.string.settings_summary_recovery_auto
                    : R.string.settings_summary_recovery_manual)
            );
        }
        if (CHARACTER_MATCHING.equals(definition.id)) {
            return uiContext.getString(
                R.string.settings_summary_matching,
                Double.toString(weights == null ? 4.0 : weights.optDouble("HighRelevance", 4.0)),
                Double.toString(weights == null ? 3.0 : weights.optDouble("MidRelevance", 3.0)),
                weights == null ? 1 : weights.optInt("RelatedNum", 1)
            );
        }
        return userSettings.optBoolean("EnablePageRecDebug", false)
                || userSettings.optBoolean("EnablePageRecTasks", false)
                || userSettings.optBoolean("EnableParseOnlyDebug", false)
                || userSettings.optBoolean("EnableFailedApiResponseDump", false)
                || userSettings.optBoolean("EnableApiBodyLogging", false)
                || userSettings.optBoolean("DebugOmitThinkingParameters", true)
            ? uiContext.getString(R.string.settings_summary_debug_on)
            : uiContext.getString(R.string.settings_summary_debug_off);
    }

    private static String targetLabel(Context uiContext, String target) {
        if ("zh-cn".equalsIgnoreCase(target)) {
            return uiContext.getString(R.string.settings_option_zh_cn);
        }
        if ("zh-tw".equalsIgnoreCase(target)) {
            return uiContext.getString(R.string.settings_option_zh_tw);
        }
        if ("en".equalsIgnoreCase(target)) {
            return uiContext.getString(R.string.settings_option_en);
        }
        return target == null || target.trim().isEmpty() ? uiContext.getString(R.string.settings_summary_custom_language) : target;
    }
}
