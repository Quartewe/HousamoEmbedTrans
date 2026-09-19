package com.quarty.housamoembedtrans.ui;

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
            ) ? "Anthropic" : "OpenAI 兼容";
            String model = translationApi == null
                ? ""
                : translationApi.optString("Model", "");
            String target = userSettings.optString("TargetLanguage", "zh-cn");
            return protocol
                + " · "
                + (model.isEmpty() ? "模型未填写" : model)
                + " · "
                + targetLabel(target)
                + " · "
                + (apiKey == null || apiKey.isEmpty()
                    ? "API key 未设置"
                    : "API key 已设置");
        }
        if (TRANSLATION_REPAIR.equals(definition.id)) {
            return (translationApi == null
                ? 0
                : translationApi.optInt("ResultRepairCount", 0))
                + " 次结果修复 · 流式修复"
                + (translationApi != null
                    && translationApi.optBoolean("EnableStreamingRepair", false)
                    ? "开启"
                    : "关闭");
        }
        if (CAPTURE_SYNC.equals(definition.id)) {
            String mode = sceneSync == null
                ? "manual"
                : sceneSync.optString("ConflictResolutionMode", "manual");
            return userSettings.optInt("SceneWorkerCount", 1)
                + " 个剧情线程 · "
                + ("game".equals(mode)
                    ? "以游戏为准"
                    : "het".equals(mode) ? "以本地翻译为准" : "每次询问冲突");
        }
        if (CONTEXT_SUMMARY.equals(definition.id)) {
            return "自动压缩"
                + (context != null
                    && context.optBoolean("EnableAutoCompression", false)
                    ? "开启"
                    : "关闭")
                + " · 最近 "
                + (context == null
                    ? 30
                    : context.optInt("DefaultRecentPercent", 30))
                + "% · "
                + (context == null
                    ? 10
                    : context.optInt("DefaultRecentSceneLimit", 10))
                + " 段剧情";
        }
        if (TASK_RECOVERY.equals(definition.id)) {
            return "翻译"
                + (queue != null
                    && queue.optBoolean("AutoRecoverPreviousJobs", false)
                    ? "自动恢复"
                    : "手动恢复")
                + " · 摘要"
                + (userSettings.optJSONObject("SummaryQueue") != null
                    && userSettings.optJSONObject("SummaryQueue")
                        .optBoolean("AutoRecoverPreviousJobs", false)
                    ? "自动恢复"
                    : "手动恢复");
        }
        if (CHARACTER_MATCHING.equals(definition.id)) {
            return "高 "
                + (weights == null ? 4.0 : weights.optDouble("HighRelevance", 4.0))
                + " · 中 "
                + (weights == null ? 3.0 : weights.optDouble("MidRelevance", 3.0))
                + " · 相关角色 "
                + (weights == null ? 1 : weights.optInt("RelatedNum", 1));
        }
        return userSettings.optBoolean("EnablePageRecDebug", false)
                || userSettings.optBoolean("EnableParseOnlyDebug", false)
                || userSettings.optBoolean("EnableFailedApiResponseDump", false)
                || userSettings.optBoolean("EnableApiBodyLogging", false)
                || userSettings.optBoolean("DebugOmitThinkingParameters", false)
            ? "部分调试选项已开启"
            : "调试选项已关闭";
    }

    private static String targetLabel(String target) {
        if ("zh-cn".equalsIgnoreCase(target)) {
            return "简体中文";
        }
        if ("zh-tw".equalsIgnoreCase(target)) {
            return "繁体中文";
        }
        if ("en".equalsIgnoreCase(target)) {
            return "English";
        }
        return target == null || target.trim().isEmpty() ? "自定义语言" : target;
    }
}
