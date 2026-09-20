package com.quarty.housamoembedtrans.provider;

import com.quarty.housamoembedtrans.storage.config.ConfigStore;
import com.quarty.housamoembedtrans.storage.config.PromptStore;

import android.content.Context;

import org.json.JSONObject;

import java.math.BigInteger;
import java.util.Locale;

/** Immutable configuration snapshot used by one claimed translation job. */
public final class TranslationConfig {
    private final String protocol;
    private final String apiUrl;
    private final String model;
    private final int networkRetryCount;
    private final int resultRepairCount;
    private final boolean streamingResponseEnabled;
    private final int repairGradientCount;
    private final boolean useFullSceneForRepair;
    private final boolean dumpFailedApiResponse;
    private final boolean logApiBodies;
    private final boolean omitThinkingParameters;
    private final String apiKey;
    private final String systemPrompt;
    private final ThinkingStrength thinkingStrength;
    private final int contextLength;
    private final int maxTokens;
    private final String pendingSummaryMode;
    private final boolean contextAutoCompression;
    private final boolean continueAutoSummaryAfterManual;
    private final int defaultRecentPercent;
    private final int defaultRecentSceneLimit;

    private TranslationConfig(
        String protocol,
        String apiUrl,
        String model,
        int networkRetryCount,
        int resultRepairCount,
        boolean streamingResponseEnabled,
        int repairGradientCount,
        boolean useFullSceneForRepair,
        boolean dumpFailedApiResponse,
        boolean logApiBodies,
        boolean omitThinkingParameters,
        String apiKey,
        String systemPrompt,
        ThinkingStrength thinkingStrength,
        int contextLength,
        int maxTokens,
        String pendingSummaryMode,
        boolean contextAutoCompression,
        boolean continueAutoSummaryAfterManual,
        int defaultRecentPercent,
        int defaultRecentSceneLimit
    ) {
        this.protocol = protocol;
        this.apiUrl = apiUrl;
        this.model = model;
        this.networkRetryCount = networkRetryCount;
        this.resultRepairCount = resultRepairCount;
        this.streamingResponseEnabled = streamingResponseEnabled;
        this.repairGradientCount = repairGradientCount;
        this.useFullSceneForRepair = useFullSceneForRepair;
        this.dumpFailedApiResponse = dumpFailedApiResponse;
        this.logApiBodies = logApiBodies;
        this.omitThinkingParameters = omitThinkingParameters;
        this.apiKey = apiKey;
        this.systemPrompt = systemPrompt;
        this.thinkingStrength = thinkingStrength;
        this.contextLength = contextLength;
        this.maxTokens = maxTokens;
        this.pendingSummaryMode = pendingSummaryMode;
        this.contextAutoCompression = contextAutoCompression;
        this.continueAutoSummaryAfterManual = continueAutoSummaryAfterManual;
        this.defaultRecentPercent = defaultRecentPercent;
        this.defaultRecentSceneLimit = defaultRecentSceneLimit;
    }

    public String getProtocol() {
        return protocol;
    }

    public String getApiUrl() {
        return apiUrl;
    }

    public String getModel() {
        return model;
    }

    public int getNetworkRetryCount() {
        return networkRetryCount;
    }

    public int getResultRepairCount() {
        return resultRepairCount;
    }

    public int getRepairGradientCount() {
        return repairGradientCount;
    }

    public boolean shouldUseFullSceneForRepair() {
        return useFullSceneForRepair;
    }

    public boolean shouldDumpFailedApiResponse() {
        return dumpFailedApiResponse;
    }

    public boolean shouldLogApiBodies() {
        return logApiBodies;
    }

    public boolean isStreamingResponseEnabled() {
        return streamingResponseEnabled;
    }

    public boolean shouldSendThinkingParameters() {
        return !omitThinkingParameters && thinkingStrength.isEnabled();
    }

    public String getApiKey() {
        return apiKey;
    }

    public String getSystemPrompt() {
        return systemPrompt;
    }

    public ThinkingStrength getThinkingStrength() {
        return thinkingStrength;
    }

    public String getPendingSummaryMode() {
        return pendingSummaryMode;
    }

    public int getMaxTokens() {
        return maxTokens;
    }

    public int getContextLength() {
        return contextLength;
    }

    public boolean isContextAutoCompressionEnabled() {
        return contextAutoCompression;
    }

    public boolean isContinueAutoSummaryAfterManual() {
        return continueAutoSummaryAfterManual;
    }

    public int getDefaultRecentPercent() {
        return defaultRecentPercent;
    }

    public int getDefaultRecentSceneLimit() {
        return defaultRecentSceneLimit;
    }

    public static TranslationConfig load(Context context) throws Exception {
        ConfigStore store = new ConfigStore(context);
        ConfigStore.TranslationConfigSnapshot snapshot =
            store.loadTranslationConfigSnapshot();
        return fromUserSettings(
            snapshot.config.getJSONObject("UserSettings"),
            snapshot.apiKey,
            new PromptStore(context).loadTranslationPrompt()
        );
    }

    /** Pure-JSON constructor used by host JUnit tests. */
    public static TranslationConfig fromUserSettings(
        JSONObject userSettings,
        String apiKey,
        String systemPrompt
    ) throws Exception {
        if (userSettings == null) {
            throw new IllegalArgumentException("UserSettings cannot be null");
        }
        JSONObject api = userSettings.optJSONObject("TranslationApi");
        if (api == null) {
            throw new IllegalArgumentException(
                "UserSettings.TranslationApi is required"
            );
        }
        JSONObject executionApi = userSettings.optJSONObject("Api");
        String thinkingStrengthValue = executionApi != null
            && executionApi.has("ThinkingStrength")
            ? executionApi.getString("ThinkingStrength")
            : ConfigStore.DEFAULT_THINKING_STRENGTH;
        int contextLength = executionApi != null
            && executionApi.has("context_length")
            ? requirePositiveInt(
                executionApi.get("context_length"),
                "context_length"
            )
            : ConfigStore.DEFAULT_CONTEXT_LENGTH;
        JSONObject contextHistory = userSettings.optJSONObject("ContextHistory");
        boolean contextAutoCompression = contextHistory != null
            && contextHistory.optBoolean(
                "EnableAutoCompression",
                false
            );
        boolean continueAutoSummaryAfterManual = contextHistory != null
            && contextHistory.optBoolean(
                "ContinueAutoSummaryAfterManual",
                false
            );
        ConfigStore.ContextHistoryRetention retention =
            ConfigStore.getContextHistoryRetention(userSettings);

        boolean hasSplitRetryCounts = api.has("NetworkRetryCount")
            || api.has("ResultRepairCount");
        int networkRetryCount = hasSplitRetryCounts
            ? optionalInt(
                api,
                "NetworkRetryCount",
                ConfigStore.DEFAULT_NETWORK_RETRY_COUNT
            )
            : optionalInt(
                api,
                "RetryCount",
                ConfigStore.DEFAULT_NETWORK_RETRY_COUNT
            );
        int resultRepairCount = hasSplitRetryCounts
            ? optionalInt(
                api,
                "ResultRepairCount",
                ConfigStore.DEFAULT_RESULT_REPAIR_COUNT
            )
            : optionalInt(
                api,
                "RetryCount",
                ConfigStore.DEFAULT_RESULT_REPAIR_COUNT
            );

        TranslationConfig config = new TranslationConfig(
            api.optString("Protocol", "openai")
                .trim()
                .toLowerCase(Locale.ROOT),
            api.optString("BaseUrl", "").trim(),
            api.optString("Model", "").trim(),
            networkRetryCount,
            resultRepairCount,
            api.optBoolean("EnableStreamingResponse", true),
            optionalInt(
                api,
                "RepairGradientCount",
                ConfigStore.DEFAULT_REPAIR_GRADIENT_COUNT
            ),
            api.optBoolean(
                "UseFullSceneForRepair",
                ConfigStore.DEFAULT_USE_FULL_SCENE_FOR_REPAIR
            ),
            userSettings.optBoolean("EnableFailedApiResponseDump", false),
            userSettings.optBoolean("EnableApiBodyLogging", false),
            userSettings.optBoolean("DebugOmitThinkingParameters", true),
            apiKey == null ? "" : apiKey,
            systemPrompt,
            ThinkingStrength.fromConfigValue(thinkingStrengthValue),
            contextLength,
            optionalInt(api, "MaxTokens", ConfigStore.DEFAULT_TRANSLATION_MAX_TOKENS),
            api.optString("PendingSummaryMode", "wait"),
            contextAutoCompression,
            continueAutoSummaryAfterManual,
            retention.recentPercent,
            retention.recentSceneLimit
        );

        config.validate();
        return config;
    }

    private static int optionalInt(
        JSONObject object,
        String key,
        int defaultValue
    ) throws Exception {
        return object.has(key)
            ? requireInt(object.get(key), key)
            : defaultValue;
    }

    private static int requirePositiveInt(Object value, String key)
        throws Exception {
        int parsed = requireInt(value, key);
        if (parsed <= 0) {
            throw new IllegalArgumentException(key + " must be positive");
        }
        return parsed;
    }

    private static int requireInt(Object value, String key) {
        if (!(value instanceof Number)
            || value instanceof Double
            || value instanceof Float) {
            throw new IllegalArgumentException(key + " must be an integer");
        }
        BigInteger parsed;
        try {
            parsed = new BigInteger(value.toString());
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(key + " must be an integer", e);
        }
        if (parsed.compareTo(BigInteger.valueOf(Integer.MIN_VALUE)) < 0
            || parsed.compareTo(BigInteger.valueOf(Integer.MAX_VALUE)) > 0) {
            throw new IllegalArgumentException(key + " must be an integer");
        }
        return parsed.intValue();
    }

    private void validate() {
        if (!"openai".equals(protocol) && !"anthropic".equals(protocol)) {
            throw new IllegalArgumentException(
                "Protocol must be openai or anthropic"
            );
        }
        if (model.isEmpty()) {
            throw new IllegalArgumentException("Model is empty");
        }
        if (networkRetryCount < 0
            || networkRetryCount > ConfigStore.MAX_TRANSLATION_RETRY_COUNT) {
            throw new IllegalArgumentException(
                "NetworkRetryCount must be an integer from 0 to "
                    + ConfigStore.MAX_TRANSLATION_RETRY_COUNT
            );
        }
        if (resultRepairCount < 0
            || resultRepairCount > ConfigStore.MAX_TRANSLATION_RETRY_COUNT) {
            throw new IllegalArgumentException(
                "ResultRepairCount must be an integer from 0 to "
                    + ConfigStore.MAX_TRANSLATION_RETRY_COUNT
            );
        }
        if (repairGradientCount < ConfigStore.MIN_REPAIR_GRADIENT_COUNT
            || repairGradientCount > ConfigStore.MAX_REPAIR_GRADIENT_COUNT) {
            throw new IllegalArgumentException(
                "RepairGradientCount must be an integer from "
                    + ConfigStore.MIN_REPAIR_GRADIENT_COUNT
                    + " to "
                    + ConfigStore.MAX_REPAIR_GRADIENT_COUNT
            );
        }
        if (!"wait".equals(pendingSummaryMode) && !"skip".equals(pendingSummaryMode)
            && !"original".equals(pendingSummaryMode)) {
            throw new IllegalArgumentException("Invalid PendingSummaryMode");
        }
        if (maxTokens <= 0) {
            throw new IllegalArgumentException("MaxTokens must be positive");
        }
        if (contextLength <= 0) {
            throw new IllegalArgumentException(
                "context_length must be positive"
            );
        }
        if (systemPrompt == null || systemPrompt.trim().isEmpty()) {
            throw new IllegalArgumentException("System prompt is empty");
        }
    }
}
