package com.quarty.housamoembedtrans.translation;

import com.quarty.housamoembedtrans.storage.ConfigStore;
import com.quarty.housamoembedtrans.util.IoUtils;

import android.content.Context;

import org.json.JSONObject;

import java.io.InputStream;
import java.math.BigInteger;
import java.util.Locale;

/** Immutable configuration snapshot used by one claimed translation job. */
public final class TranslationConfig {
    private final String protocol;
    private final String apiUrl;
    private final String model;
    private final int networkRetryCount;
    private final int resultRepairCount;
    private final boolean streamingRepairEnabled;
    private final int repairGradientCount;
    private final boolean useFullSceneForRepair;
    private final boolean dumpFailedApiResponse;
    private final String apiKey;
    private final String systemPrompt;
    private final ThinkingStrength thinkingStrength;
    private final int contextLength;
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
        boolean streamingRepairEnabled,
        int repairGradientCount,
        boolean useFullSceneForRepair,
        boolean dumpFailedApiResponse,
        String apiKey,
        String systemPrompt,
        ThinkingStrength thinkingStrength,
        int contextLength,
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
        this.streamingRepairEnabled = streamingRepairEnabled;
        this.repairGradientCount = repairGradientCount;
        this.useFullSceneForRepair = useFullSceneForRepair;
        this.dumpFailedApiResponse = dumpFailedApiResponse;
        this.apiKey = apiKey;
        this.systemPrompt = systemPrompt;
        this.thinkingStrength = thinkingStrength;
        this.contextLength = contextLength;
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

    public boolean isStreamingRepairEnabled() {
        return streamingRepairEnabled;
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

    public String getApiKey() {
        return apiKey;
    }

    public String getSystemPrompt() {
        return systemPrompt;
    }

    public ThinkingStrength getThinkingStrength() {
        return thinkingStrength;
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
            readAsset(context, "term/prompt.txt")
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
            api.optBoolean(
                "EnableStreamingRepair",
                ConfigStore.DEFAULT_ENABLE_STREAMING_REPAIR
            ),
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
            apiKey == null ? "" : apiKey,
            systemPrompt,
            ThinkingStrength.fromConfigValue(thinkingStrengthValue),
            contextLength,
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
        if (contextLength <= 0) {
            throw new IllegalArgumentException(
                "context_length must be positive"
            );
        }
        if (systemPrompt == null || systemPrompt.trim().isEmpty()) {
            throw new IllegalArgumentException("System prompt is empty");
        }
    }

    private static String readAsset(Context context, String path)
        throws Exception {
        try (InputStream input = context.getAssets().open(path)) {
            return IoUtils.readUtf8Limited(input, 2 * 1024 * 1024);
        }
    }
}
