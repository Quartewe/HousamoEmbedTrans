package com.quarty.housamoembedtrans.storage.config;
import com.quarty.housamoembedtrans.provider.ApiConcurrencySettings;
import com.quarty.housamoembedtrans.provider.ThinkingStrength;
import com.quarty.housamoembedtrans.scene.sync.SceneSyncSettings;

import com.quarty.housamoembedtrans.util.IoUtils;

import android.content.Context;
import android.util.AtomicFile;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;

/**
 * Stores user-editable JSON resources in the module app's private files directory.
 * Bundled assets remain immutable defaults and are used whenever no user file exists.
 */
public final class ConfigStore {

    public static final String CONFIG_FILE_NAME = "config.json";
    public static final String RUNTIME_FILE_NAME = "runtime.json";
    public static final String CHARDICT_FILE_NAME = "chardict.json";
    public static final String GAMETERMS_FILE_NAME = "gameterms.json";
    public static final int DEFAULT_NETWORK_RETRY_COUNT = 1;
    public static final int DEFAULT_RESULT_REPAIR_COUNT = 1;
    public static final int DEFAULT_SUMMARY_RETRY_COUNT = 1;
    public static final int MIN_SUMMARY_RETRY_COUNT = 0;
    public static final int MAX_SUMMARY_RETRY_COUNT = 5;
    public static final int DEFAULT_CONTEXT_HISTORY_RECENT_PERCENT = 30;
    public static final int MIN_CONTEXT_HISTORY_RECENT_PERCENT = 1;
    public static final int MAX_CONTEXT_HISTORY_RECENT_PERCENT = 100;
    public static final int DEFAULT_CONTEXT_HISTORY_RECENT_SCENE_LIMIT = 10;
    public static final int MIN_CONTEXT_HISTORY_RECENT_SCENE_LIMIT = 1;
    public static final int MAX_CONTEXT_HISTORY_RECENT_SCENE_LIMIT = 10000;
    public static final boolean DEFAULT_ENABLE_STREAMING_REPAIR = false;
    public static final int DEFAULT_REPAIR_GRADIENT_COUNT = 3;
    public static final boolean DEFAULT_USE_FULL_SCENE_FOR_REPAIR = true;
    public static final int MIN_REPAIR_GRADIENT_COUNT = 2;
    public static final int MAX_REPAIR_GRADIENT_COUNT = 8;
    public static final boolean DEFAULT_AUTO_RECOVER_PREVIOUS_JOBS = false;
    public static final boolean DEFAULT_SUMMARY_AUTO_RECOVER_PREVIOUS_JOBS = false;
    public static final boolean DEFAULT_ENABLE_STARTUP_REVIEW = false;
    public static final String DEFAULT_RECOVERY_SORT_ORDER = "created_asc";
    public static final String DEFAULT_THINKING_STRENGTH = "none";
    public static final int DEFAULT_CONTEXT_LENGTH = 16000;
    public static final int MAX_TRANSLATION_RETRY_COUNT = 5;
    public static final int DEFAULT_SCENE_WORKER_COUNT =
        SceneSyncSettings.DEFAULT_SCENE_WORKER_COUNT;
    public static final int MIN_SCENE_WORKER_COUNT =
        SceneSyncSettings.MIN_SCENE_WORKER_COUNT;
    public static final int MAX_SCENE_WORKER_COUNT =
        SceneSyncSettings.MAX_SCENE_WORKER_COUNT;
    public static final String DEFAULT_CONFLICT_RESOLUTION_MODE =
        SceneSyncSettings.DEFAULT_CONFLICT_RESOLUTION_MODE;
    public static final String CONFLICT_MODE_GAME =
        SceneSyncSettings.CONFLICT_MODE_GAME;
    public static final String CONFLICT_MODE_HET =
        SceneSyncSettings.CONFLICT_MODE_HET;
    public static final String CONFLICT_MODE_MANUAL =
        SceneSyncSettings.CONFLICT_MODE_MANUAL;
    private static final String TERM_ASSET_DIRECTORY = "term/";
    private static final String PREFS_NAME = "housamo_trans_prefs";
    private static final String KEY_API_KEY = "api_key";
    private static final Object CONFIG_ACCESS_LOCK = new Object();

    public static class JsonLoadResult {
        public final JSONObject json;
        public final boolean userOverride;
        public final boolean invalidUserOverride;

        JsonLoadResult(
            JSONObject json,
            boolean userOverride,
            boolean invalidUserOverride
        ) {
            this.json = json;
            this.userOverride = userOverride;
            this.invalidUserOverride = invalidUserOverride;
        }
    }

    public static final class LoadResult extends JsonLoadResult {
        public final JSONObject config;

        LoadResult(
            JSONObject config,
            boolean userOverride,
            boolean invalidUserOverride
        ) {
            super(config, userOverride, invalidUserOverride);
            this.config = config;
        }
    }

    /** Consistent config and API-key snapshot for one translation attempt. */
    public static final class TranslationConfigSnapshot {
        public final JSONObject config;
        public final String apiKey;

        private TranslationConfigSnapshot(JSONObject config, String apiKey) {
            this.config = config;
            this.apiKey = apiKey == null ? "" : apiKey;
        }
    }

    /** Strict, immutable Context/Group Summary retry configuration. */
    public static final class SummaryRetryCounts {
        public final int context;
        public final int group;

        private SummaryRetryCounts(int context, int group) {
            this.context = context;
            this.group = group;
        }
    }

    /** Validated defaults for the recent Scene retention window. */
    public static final class ContextHistoryRetention {
        public final int recentPercent;
        public final int recentSceneLimit;

        private ContextHistoryRetention(int recentPercent, int recentSceneLimit) {
            this.recentPercent = recentPercent;
            this.recentSceneLimit = recentSceneLimit;
        }
    }

    private final Context context;

    public ConfigStore(Context context) {
        this.context = context.getApplicationContext();
    }

    public LoadResult load() throws Exception {
        synchronized (CONFIG_ACCESS_LOCK) {
            return loadConfigUnlocked();
        }
    }

    private LoadResult loadConfigUnlocked() throws Exception {
        JsonLoadResult result = loadJson(CONFIG_FILE_NAME);
        return new LoadResult(
            result.json,
            result.userOverride,
            result.invalidUserOverride
        );
    }

    /** Reads config JSON and API key under one process-local snapshot lock. */
    public TranslationConfigSnapshot loadTranslationConfigSnapshot()
        throws Exception {
        synchronized (CONFIG_ACCESS_LOCK) {
            LoadResult result = loadConfigUnlocked();
            return new TranslationConfigSnapshot(
                result.config,
                loadApiKeyUnlocked()
            );
        }
    }

    public JSONObject loadBundledDefault() throws Exception {
        return loadBundledJson(CONFIG_FILE_NAME);
    }

    public void save(JSONObject config) throws IOException {
        synchronized (CONFIG_ACCESS_LOCK) {
            try {
                JSONObject normalized = normalizeConfig(config);
                validateConfig(normalized);
                saveJson(CONFIG_FILE_NAME, normalized);
            } catch (IOException e) {
                throw e;
            } catch (Exception e) {
                throw new IOException("could not validate config.json", e);
            }
        }
    }

    public String loadApiKey() {
        synchronized (CONFIG_ACCESS_LOCK) {
            return loadApiKeyUnlocked();
        }
    }

    private String loadApiKeyUnlocked() {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getString(KEY_API_KEY, "");
    }

    public void saveApiKey(String apiKey) throws IOException {
        synchronized (CONFIG_ACCESS_LOCK) {
            boolean saved = context.getSharedPreferences(
                PREFS_NAME,
                Context.MODE_PRIVATE
            )
                .edit()
                .putString(KEY_API_KEY, apiKey == null ? "" : apiKey)
                .commit();
            if (!saved) {
                throw new IOException("could not persist API key");
            }
        }
    }

    public JsonLoadResult loadJson(String name) throws Exception {
        File userFile = getUserFile(name);
        AtomicFile atomicFile = new AtomicFile(userFile);

        if (IoUtils.atomicFileExists(userFile)) {
            try {
                JSONObject source = readJson(atomicFile.openRead());
                JSONObject json = normalizeResource(name, source);
                validateResource(name, json);
                if (CONFIG_FILE_NAME.equals(name) && !isCanonicalConfig(source)) {
                    saveJson(CONFIG_FILE_NAME, json);
                }
                return new JsonLoadResult(json, true, false);
            } catch (Exception ignored) {
                return new JsonLoadResult(loadBundledJson(name), false, true);
            }
        }

        return new JsonLoadResult(loadBundledJson(name), false, false);
    }

    public JSONObject loadBundledJson(String name) throws Exception {
        requireSupportedName(name);
        JSONObject json = readJson(
            context.getAssets().open(bundledAssetPath(name))
        );
        validateResource(name, json);
        return json;
    }

    public void saveJson(String name, JSONObject json) throws IOException {
        try {
            IoUtils.writeAtomically(
                getUserFile(name),
                (json.toString(2) + "\n").getBytes(StandardCharsets.UTF_8)
            );
        } catch (Exception e) {
            throw new IOException("could not serialize " + name, e);
        }
    }

    public void deleteUserFile(String name) throws IOException {
        new AtomicFile(getUserFile(name)).delete();
    }

    /**
     * Returns an existing and syntactically valid user override, or null.
     * The provider uses this to avoid exposing a corrupt override to the game.
     */
    public File getValidUserFile(String name) {
        File file = getUserFile(name);
        AtomicFile atomicFile = new AtomicFile(file);
        if (!IoUtils.atomicFileExists(file)) {
            return null;
        }

        try {
            JSONObject source = readJson(atomicFile.openRead());
            JSONObject json = normalizeResource(name, source);
            validateResource(name, json);
            if (CONFIG_FILE_NAME.equals(name) && !isCanonicalConfig(source)) {
                saveJson(CONFIG_FILE_NAME, json);
            }
            return file;
        } catch (Exception ignored) {
            return null;
        }
    }

    public File getUserFile(String name) {
        requireSupportedName(name);
        return new File(context.getFilesDir(), name);
    }

    public static void validateCharacterDictionary(JSONObject dictionary)
        throws Exception {
        if (dictionary.length() == 0) {
            throw new IllegalArgumentException("character dictionary must not be empty");
        }

        JSONArray names = dictionary.names();
        if (names == null) {
            throw new IllegalArgumentException("character dictionary has no character names");
        }

        for (int index = 0; index < names.length(); index++) {
            String name = names.getString(index);
            if (name.trim().isEmpty()) {
                throw new IllegalArgumentException("character name must not be empty");
            }

            validateCharacterRecord(name, dictionary.getJSONObject(name));
        }
    }

    public static void validateCharacterRecord(String name, JSONObject record)
        throws Exception {
        String[] arrayFields = {
            "alias",
            "school",
            "guild",
            "origin_world",
            "relationships"
        };

        for (String field : arrayFields) {
            if (record.has(field) && !(record.get(field) instanceof JSONArray)) {
                throw new IllegalArgumentException(name + "." + field + " must be an array");
            }
        }

        JSONArray aliases = record.optJSONArray("alias");
        if (aliases == null) {
            return;
        }

        for (int index = 0; index < aliases.length(); index++) {
            JSONObject alias = aliases.getJSONObject(index);
            Object aliasName = alias.opt("name");

            if (!(aliasName instanceof String)
                || ((String) aliasName).trim().isEmpty()) {
                throw new IllegalArgumentException(
                    name + ".alias[" + index + "].name must be a non-empty string"
                );
            }
        }
    }

    private static void validateResource(String name, JSONObject json) throws Exception {
        if (CONFIG_FILE_NAME.equals(name)) {
            validateConfig(json);
        } else if (RUNTIME_FILE_NAME.equals(name)) {
            validateRuntime(json);
        } else if (CHARDICT_FILE_NAME.equals(name)) {
            validateCharacterDictionary(json);
        } else if (GAMETERMS_FILE_NAME.equals(name)) {
            validateGameTermDictionary(json);
        }
    }

    private static void validateConfig(JSONObject config) throws Exception {
        requireNonEmptyString(config, "Version", "config");

        JSONObject userSettings = config.getJSONObject("UserSettings");
        userSettings.getBoolean("EnablePageRecDebug");
        if (userSettings.has("EnableParseOnlyDebug")
            && !(userSettings.get("EnableParseOnlyDebug") instanceof Boolean)) {
            throw new IllegalArgumentException(
                "UserSettings.EnableParseOnlyDebug must be a boolean"
            );
        }
        if (userSettings.has("EnableFailedApiResponseDump")
            && !(userSettings.get("EnableFailedApiResponseDump") instanceof Boolean)) {
            throw new IllegalArgumentException(
                "UserSettings.EnableFailedApiResponseDump must be a boolean"
            );
        }
        userSettings.getBoolean("OverwriteExistingJson");
        requireNonEmptyString(userSettings, "TargetLanguage", "UserSettings");
        getSceneWorkerCount(userSettings);
        getConflictResolutionMode(userSettings);

        JSONObject translationApi = userSettings.getJSONObject("TranslationApi");
        String protocol = translationApi.getString("Protocol").trim();
        if (!"openai".equals(protocol) && !"anthropic".equals(protocol)) {
            throw new IllegalArgumentException(
                "UserSettings.TranslationApi.Protocol must be openai or anthropic"
            );
        }
        translationApi.getString("BaseUrl");
        translationApi.getString("Model");
        boolean hasSplitRetryCounts = translationApi.has("NetworkRetryCount")
            || translationApi.has("ResultRepairCount");
        if (hasSplitRetryCounts) {
            validateOptionalRetryCount(translationApi, "NetworkRetryCount");
            validateOptionalRetryCount(translationApi, "ResultRepairCount");
        } else {
            validateOptionalRetryCount(translationApi, "RetryCount");
        }
        requireBoolean(
            translationApi,
            "EnableStreamingRepair",
            "UserSettings.TranslationApi"
        );
        requireBoolean(
            translationApi,
            "UseFullSceneForRepair",
            "UserSettings.TranslationApi"
        );
        validateRepairGradientCount(translationApi);

        JSONObject translationQueue =
            userSettings.getJSONObject("TranslationQueue");
        Object autoRecover = translationQueue.get(
            "AutoRecoverPreviousJobs"
        );
        if (!(autoRecover instanceof Boolean)) {
            throw new IllegalArgumentException(
                "UserSettings.TranslationQueue.AutoRecoverPreviousJobs "
                    + "must be a boolean"
            );
        }
        String recoverySortOrder = translationQueue.getString(
            "RecoverySortOrder"
        );
        if (!isRecoverySortOrder(recoverySortOrder)) {
            throw new IllegalArgumentException(
                "UserSettings.TranslationQueue.RecoverySortOrder "
                    + "must be created_asc, created_desc, "
                    + "started_asc, or started_desc"
            );
        }

        JSONObject summaryQueue = userSettings.optJSONObject("SummaryQueue");
        if (summaryQueue != null) {
            Object summaryAutoRecover = summaryQueue.get(
                "AutoRecoverPreviousJobs"
            );
            if (!(summaryAutoRecover instanceof Boolean)) {
                throw new IllegalArgumentException(
                    "UserSettings.SummaryQueue.AutoRecoverPreviousJobs "
                        + "must be a boolean"
                );
            }
        }

        getSummaryRetryCounts(userSettings);
        getContextHistoryRetention(userSettings);

        JSONObject api = userSettings.optJSONObject("Api");
        if (api != null) {
            String thinkingStrength = api.getString("ThinkingStrength");
            if (!isValidThinkingStrength(thinkingStrength)) {
                throw new IllegalArgumentException(
                    "UserSettings.Api.ThinkingStrength must be one of "
                        + "none, minimal, low, medium, high, xhigh, max"
                );
            }
            requirePositiveInt(
                api.get("context_length"),
                "UserSettings.Api.context_length"
            );
            ApiConcurrencySettings.normalize(
                api.has("max_concurrent_requests")
                    ? api.get("max_concurrent_requests")
                    : null
            );
        }

        JSONObject weights = userSettings.getJSONObject("CharacterWeight");
        String[] weightFields = {
            "HighRelevance",
            "MidRelevance",
            "DensityHigh",
            "TextLowScore",
            "TextMentionedScore",
            "RelatedNum",
            "LowTermScore"
        };
        for (String field : weightFields) {
            double value = weights.getDouble(field);
            if (!Double.isFinite(value) || value <= 0.0) {
                throw new IllegalArgumentException(
                    "UserSettings.CharacterWeight." + field + " must be positive"
                );
            }
        }

        if (weights.getDouble("HighRelevance") < weights.getDouble("MidRelevance")) {
            throw new IllegalArgumentException(
                "CharacterWeight.HighRelevance must be >= MidRelevance"
            );
        }
        if (weights.getDouble("TextLowScore")
            < weights.getDouble("TextMentionedScore")) {
            throw new IllegalArgumentException(
                "CharacterWeight.TextLowScore must be >= TextMentionedScore"
            );
        }
    }

    private static void validateRuntime(JSONObject config) throws Exception {
        requireNonEmptyString(config, "GameVersion", "runtime");

        JSONObject runtime = config.getJSONObject("RuntimeConfigs");
        runtime.getJSONObject("RVA");
        runtime.getJSONObject("Layout");
    }

    private JSONObject normalizeResource(String name, JSONObject json) throws Exception {
        return CONFIG_FILE_NAME.equals(name) ? normalizeConfig(json) : json;
    }

    private JSONObject normalizeConfig(JSONObject config) throws Exception {
        String version = config.has("Version")
            ? requireNonEmptyString(config, "Version", "config")
            : requireNonEmptyString(
                loadBundledJson(CONFIG_FILE_NAME),
                "Version",
                "config"
            );

        JSONObject userSettings = new JSONObject(
            config.getJSONObject("UserSettings").toString()
        );
        JSONObject translationApi = userSettings.optJSONObject(
            "TranslationApi"
        );
        if (translationApi != null) {
            if (!translationApi.has("EnableStreamingRepair")) {
                translationApi.put(
                    "EnableStreamingRepair",
                    DEFAULT_ENABLE_STREAMING_REPAIR
                );
            }
            if (!translationApi.has("RepairGradientCount")) {
                translationApi.put(
                    "RepairGradientCount",
                    DEFAULT_REPAIR_GRADIENT_COUNT
                );
            }
            if (!translationApi.has("UseFullSceneForRepair")) {
                translationApi.put(
                    "UseFullSceneForRepair",
                    DEFAULT_USE_FULL_SCENE_FOR_REPAIR
                );
            }
        }
        JSONObject executionApi = userSettings.optJSONObject("Api");
        if (executionApi == null) {
            executionApi = new JSONObject();
            userSettings.put("Api", executionApi);
        }
        if (!executionApi.has("ThinkingStrength")) {
            executionApi.put(
                "ThinkingStrength",
                DEFAULT_THINKING_STRENGTH
            );
        }
        if (!executionApi.has("context_length")) {
            executionApi.put(
                "context_length",
                DEFAULT_CONTEXT_LENGTH
            );
        }
        if (!executionApi.has("max_concurrent_requests")) {
            executionApi.put(
                "max_concurrent_requests",
                ApiConcurrencySettings.DEFAULT_API_CONCURRENCY
            );
        }
        JSONObject translationQueue;
        if (!userSettings.has("TranslationQueue")) {
            translationQueue = new JSONObject();
            userSettings.put("TranslationQueue", translationQueue);
        } else {
            translationQueue = userSettings.optJSONObject(
                "TranslationQueue"
            );
        }
        if (translationQueue != null
            && !translationQueue.has("AutoRecoverPreviousJobs")) {
            translationQueue.put(
                "AutoRecoverPreviousJobs",
                DEFAULT_AUTO_RECOVER_PREVIOUS_JOBS
            );
        }
        if (translationQueue != null
            && !translationQueue.has("RecoverySortOrder")) {
            translationQueue.put(
                "RecoverySortOrder",
                DEFAULT_RECOVERY_SORT_ORDER
            );
        }
        JSONObject summaryQueue;
        if (!userSettings.has("SummaryQueue")) {
            summaryQueue = new JSONObject();
            userSettings.put("SummaryQueue", summaryQueue);
        } else {
            Object rawSummaryQueue = userSettings.get("SummaryQueue");
            if (!(rawSummaryQueue instanceof JSONObject)) {
                throw new IllegalArgumentException(
                    "UserSettings.SummaryQueue must be an object"
                );
            }
            summaryQueue = (JSONObject) rawSummaryQueue;
        }
        if (!summaryQueue.has("AutoRecoverPreviousJobs")) {
            summaryQueue.put(
                "AutoRecoverPreviousJobs",
                DEFAULT_SUMMARY_AUTO_RECOVER_PREVIOUS_JOBS
            );
        }
        JSONObject contextHistory;
        if (!userSettings.has("ContextHistory")) {
            contextHistory = new JSONObject();
            userSettings.put("ContextHistory", contextHistory);
        } else {
            Object rawContextHistory = userSettings.get("ContextHistory");
            if (!(rawContextHistory instanceof JSONObject)) {
                throw new IllegalArgumentException(
                    "UserSettings.ContextHistory must be an object"
                );
            }
            contextHistory = (JSONObject) rawContextHistory;
        }
        if (!contextHistory.has("EnableAutoCompression")) {
            contextHistory.put("EnableAutoCompression", false);
        }
        if (!contextHistory.has("ContinueAutoSummaryAfterManual")) {
            contextHistory.put("ContinueAutoSummaryAfterManual", false);
        }
        if (!contextHistory.has("EnableStartupReview")) {
            contextHistory.put(
                "EnableStartupReview",
                DEFAULT_ENABLE_STARTUP_REVIEW
            );
        }
        if (!contextHistory.has("DefaultRecentPercent")) {
            contextHistory.put(
                "DefaultRecentPercent",
                DEFAULT_CONTEXT_HISTORY_RECENT_PERCENT
            );
        } else {
            contextHistory.put(
                "DefaultRecentPercent",
                normalizeContextHistoryRecentPercent(
                    contextHistory.get("DefaultRecentPercent")
                )
            );
        }
        if (!contextHistory.has("DefaultRecentSceneLimit")) {
            contextHistory.put(
                "DefaultRecentSceneLimit",
                DEFAULT_CONTEXT_HISTORY_RECENT_SCENE_LIMIT
            );
        } else {
            contextHistory.put(
                "DefaultRecentSceneLimit",
                normalizeContextHistoryRecentSceneLimit(
                    contextHistory.get("DefaultRecentSceneLimit")
                )
            );
        }
        normalizeSceneSyncSettings(userSettings);
        JSONObject normalized = new JSONObject();
        normalized.put("Version", version);
        normalized.put("UserSettings", userSettings);
        return normalized;
    }

    /**
     * Applies the Scene Sync defaults in-place while rejecting malformed
     * values.  Unknown UserSettings members remain untouched.
     */
    public static void normalizeSceneSyncSettings(JSONObject userSettings)
        throws Exception {
        if (userSettings == null) {
            throw new IllegalArgumentException("UserSettings must be an object");
        }

        if (!userSettings.has("SceneWorkerCount")) {
            userSettings.put(
                "SceneWorkerCount",
                DEFAULT_SCENE_WORKER_COUNT
            );
        } else {
            userSettings.put(
                "SceneWorkerCount",
                SceneSyncSettings.normalizeWorkerCount(
                    userSettings.get("SceneWorkerCount")
                )
            );
        }

        JSONObject sceneSync;
        if (!userSettings.has("SceneSync")) {
            sceneSync = new JSONObject();
            userSettings.put("SceneSync", sceneSync);
        } else {
            Object rawSceneSync = userSettings.get("SceneSync");
            if (!(rawSceneSync instanceof JSONObject)) {
                throw new IllegalArgumentException(
                    "UserSettings.SceneSync must be an object"
                );
            }
            sceneSync = (JSONObject) rawSceneSync;
        }

        if (!sceneSync.has("ConflictResolutionMode")) {
            sceneSync.put(
                "ConflictResolutionMode",
                DEFAULT_CONFLICT_RESOLUTION_MODE
            );
        } else {
            sceneSync.put(
                "ConflictResolutionMode",
                SceneSyncSettings.normalizeConflictResolutionMode(
                    sceneSync.get("ConflictResolutionMode")
                )
            );
        }
    }

    /** Returns the validated worker count from a startup settings snapshot. */
    public static int getSceneWorkerCount(JSONObject userSettings)
        throws Exception {
        return userSettings != null && userSettings.has("SceneWorkerCount")
            ? SceneSyncSettings.normalizeWorkerCount(
                userSettings.get("SceneWorkerCount")
            )
            : DEFAULT_SCENE_WORKER_COUNT;
    }

    /** Returns the independent global API concurrency limit. */
    public static int getApiConcurrency(JSONObject userSettings)
        throws Exception {
        if (userSettings == null) {
            return ApiConcurrencySettings.DEFAULT_API_CONCURRENCY;
        }
        JSONObject api = userSettings.optJSONObject("Api");
        return api == null
            ? ApiConcurrencySettings.DEFAULT_API_CONCURRENCY
            : ApiConcurrencySettings.normalize(
                api.has("max_concurrent_requests")
                    ? api.get("max_concurrent_requests")
                    : null
            );
    }

    /** Returns the validated conflict mode from a startup settings snapshot. */
    public static String getConflictResolutionMode(JSONObject userSettings)
        throws Exception {
        if (userSettings == null || !userSettings.has("SceneSync")) {
            return DEFAULT_CONFLICT_RESOLUTION_MODE;
        }
        Object rawSceneSync = userSettings.get("SceneSync");
        if (!(rawSceneSync instanceof JSONObject)) {
            throw new IllegalArgumentException(
                "UserSettings.SceneSync must be an object"
            );
        }
        JSONObject sceneSync = (JSONObject) rawSceneSync;
        return sceneSync.has("ConflictResolutionMode")
            ? SceneSyncSettings.normalizeConflictResolutionMode(
                sceneSync.get("ConflictResolutionMode")
            )
            : DEFAULT_CONFLICT_RESOLUTION_MODE;
    }

    /** Returns the Summary recovery setting; defaults to false. */
    public static boolean getSummaryAutoRecoverPreviousJobs(
        JSONObject userSettings
    ) {
        if (userSettings == null) {
            return DEFAULT_SUMMARY_AUTO_RECOVER_PREVIOUS_JOBS;
        }
        JSONObject summaryQueue = userSettings.optJSONObject("SummaryQueue");
        return summaryQueue == null
            ? DEFAULT_SUMMARY_AUTO_RECOVER_PREVIOUS_JOBS
            : summaryQueue.optBoolean(
                "AutoRecoverPreviousJobs",
                DEFAULT_SUMMARY_AUTO_RECOVER_PREVIOUS_JOBS
            );
    }

    /**
     * Parses both independent Context/Group Summary retry counts with one
     * strict rule. Missing fields use the shared default; present fields must
     * be integral JSON numbers in the inclusive 0..5 range.
     */
    public static SummaryRetryCounts getSummaryRetryCounts(
        JSONObject userSettings
    ) throws Exception {
        JSONObject contextHistory = userSettings == null
            ? null
            : userSettings.optJSONObject("ContextHistory");
        if (userSettings != null
            && userSettings.has("ContextHistory")
            && contextHistory == null) {
            throw new IllegalArgumentException(
                "UserSettings.ContextHistory must be an object"
            );
        }
        int context = parseSummaryRetryCount(
            contextHistory,
            "ContextSummaryRetryCount"
        );
        int group = parseSummaryRetryCount(
            contextHistory,
            "GroupSummaryRetryCount"
        );
        return new SummaryRetryCounts(context, group);
    }

    /** Returns the validated default recent-window settings. */
    public static ContextHistoryRetention getContextHistoryRetention(
        JSONObject userSettings
    ) throws Exception {
        JSONObject contextHistory = userSettings == null
            ? null
            : userSettings.optJSONObject("ContextHistory");
        if (userSettings != null
            && userSettings.has("ContextHistory")
            && contextHistory == null) {
            throw new IllegalArgumentException(
                "UserSettings.ContextHistory must be an object"
            );
        }
        int recentPercent = contextHistory == null
            || !contextHistory.has("DefaultRecentPercent")
            ? DEFAULT_CONTEXT_HISTORY_RECENT_PERCENT
            : normalizeContextHistoryRecentPercent(
                contextHistory.get("DefaultRecentPercent")
            );
        int recentSceneLimit = contextHistory == null
            || !contextHistory.has("DefaultRecentSceneLimit")
            ? DEFAULT_CONTEXT_HISTORY_RECENT_SCENE_LIMIT
            : normalizeContextHistoryRecentSceneLimit(
                contextHistory.get("DefaultRecentSceneLimit")
            );
        return new ContextHistoryRetention(recentPercent, recentSceneLimit);
    }

    private static int parseSummaryRetryCount(
        JSONObject contextHistory,
        String key
    ) throws Exception {
        if (contextHistory == null || !contextHistory.has(key)) {
            return DEFAULT_SUMMARY_RETRY_COUNT;
        }
        int value;
        try {
            value = requireInt(contextHistory.get(key), key);
        } catch (IllegalArgumentException e) {
            throw invalidSummaryRetryCount(key, e);
        }
        if (value < MIN_SUMMARY_RETRY_COUNT
            || value > MAX_SUMMARY_RETRY_COUNT) {
            throw invalidSummaryRetryCount(key, null);
        }
        return value;
    }

    private static IllegalArgumentException invalidSummaryRetryCount(
        String key,
        Throwable cause
    ) {
        IllegalArgumentException error = new IllegalArgumentException(
            "UserSettings.ContextHistory."
                + key
                + " must be an integer from "
                + MIN_SUMMARY_RETRY_COUNT
                + " to "
                + MAX_SUMMARY_RETRY_COUNT
        );
        if (cause != null) {
            error.initCause(cause);
        }
        return error;
    }

    private static boolean isCanonicalConfig(JSONObject config) {
        if (config.length() != 2
            || !config.has("Version")
            || !config.has("UserSettings")) {
            return false;
        }
        JSONObject userSettings = config.optJSONObject("UserSettings");
        JSONObject translationApi = userSettings == null
            ? null
            : userSettings.optJSONObject("TranslationApi");
        JSONObject translationQueue = userSettings == null
            ? null
            : userSettings.optJSONObject("TranslationQueue");
        JSONObject summaryQueue = userSettings == null
            ? null
            : userSettings.optJSONObject("SummaryQueue");
        JSONObject executionApi = userSettings == null
            ? null
            : userSettings.optJSONObject("Api");
        JSONObject contextHistory = userSettings == null
            ? null
            : userSettings.optJSONObject("ContextHistory");
        return translationApi != null
            && translationApi.has("EnableStreamingRepair")
            && translationApi.has("RepairGradientCount")
            && translationApi.has("UseFullSceneForRepair")
            && translationQueue != null
            && translationQueue.has("AutoRecoverPreviousJobs")
            && translationQueue.has("RecoverySortOrder")
            && summaryQueue != null
            && summaryQueue.has("AutoRecoverPreviousJobs")
            && executionApi != null
            && executionApi.has("ThinkingStrength")
            && executionApi.has("context_length")
            && executionApi.has("max_concurrent_requests")
            && contextHistory != null
            && contextHistory.has("DefaultRecentPercent")
            && contextHistory.has("DefaultRecentSceneLimit")
            && userSettings.has("SceneWorkerCount")
            && userSettings.optJSONObject("SceneSync") != null
            && userSettings.optJSONObject("SceneSync").has(
                "ConflictResolutionMode"
            );
    }

    private static boolean isRecoverySortOrder(String value) {
        return "created_asc".equals(value)
            || "created_desc".equals(value)
            || "started_asc".equals(value)
            || "started_desc".equals(value);
    }

    private static boolean isValidThinkingStrength(String value) {
        return "none".equals(value)
            || "minimal".equals(value)
            || "low".equals(value)
            || "medium".equals(value)
            || "high".equals(value)
            || "xhigh".equals(value)
            || "max".equals(value);
    }

    private static void validateOptionalRetryCount(JSONObject translationApi, String key)
        throws Exception {
        if (!translationApi.has(key)) {
            return;
        }

        int retryCount;
        try {
            retryCount = requireInt(translationApi.get(key), key);
        } catch (IllegalArgumentException e) {
            throw invalidRetryCount(key);
        }
        if (retryCount < 0 || retryCount > MAX_TRANSLATION_RETRY_COUNT) {
            throw invalidRetryCount(key);
        }
    }

    private static void validateRepairGradientCount(JSONObject translationApi)
        throws Exception {
        int count;
        try {
            count = requireInt(
                translationApi.get("RepairGradientCount"),
                "RepairGradientCount"
            );
        } catch (IllegalArgumentException e) {
            throw invalidRepairGradientCount();
        }
        if (count < MIN_REPAIR_GRADIENT_COUNT
            || count > MAX_REPAIR_GRADIENT_COUNT) {
            throw invalidRepairGradientCount();
        }
    }

    private static int requirePositiveInt(Object value, String key) {
        int parsed;
        try {
            parsed = requireInt(value, key);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException(key + " must be positive", e);
        }
        if (parsed <= 0) {
            throw new IllegalArgumentException(key + " must be positive");
        }
        return parsed;
    }

    private static int normalizeContextHistoryRecentPercent(Object value) {
        return normalizeContextHistoryRecentSetting(
            value,
            "DefaultRecentPercent",
            MIN_CONTEXT_HISTORY_RECENT_PERCENT,
            MAX_CONTEXT_HISTORY_RECENT_PERCENT
        );
    }

    private static int normalizeContextHistoryRecentSceneLimit(Object value) {
        return normalizeContextHistoryRecentSetting(
            value,
            "DefaultRecentSceneLimit",
            MIN_CONTEXT_HISTORY_RECENT_SCENE_LIMIT,
            MAX_CONTEXT_HISTORY_RECENT_SCENE_LIMIT
        );
    }

    private static int normalizeContextHistoryRecentSetting(
        Object value,
        String key,
        int minimum,
        int maximum
    ) {
        int parsed;
        try {
            parsed = requireInt(value, key);
        } catch (IllegalArgumentException e) {
            throw invalidContextHistoryRecentSetting(
                key,
                minimum,
                maximum,
                e
            );
        }
        if (parsed < minimum || parsed > maximum) {
            throw invalidContextHistoryRecentSetting(
                key,
                minimum,
                maximum,
                null
            );
        }
        return parsed;
    }

    private static IllegalArgumentException invalidContextHistoryRecentSetting(
        String key,
        int minimum,
        int maximum,
        Throwable cause
    ) {
        IllegalArgumentException error = new IllegalArgumentException(
            "UserSettings.ContextHistory."
                + key
                + " must be an integer from "
                + minimum
                + " to "
                + maximum
        );
        if (cause != null) {
            error.initCause(cause);
        }
        return error;
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

    private static IllegalArgumentException invalidRepairGradientCount() {
        return new IllegalArgumentException(
            "UserSettings.TranslationApi.RepairGradientCount "
                + "must be an integer from "
                + MIN_REPAIR_GRADIENT_COUNT
                + " to "
                + MAX_REPAIR_GRADIENT_COUNT
        );
    }

    private static boolean requireBoolean(
        JSONObject object,
        String key,
        String path
    ) throws Exception {
        Object value = object.get(key);
        if (!(value instanceof Boolean)) {
            throw new IllegalArgumentException(
                path + "." + key + " must be a boolean"
            );
        }
        return (Boolean) value;
    }

    private static IllegalArgumentException invalidRetryCount(String key) {
        return new IllegalArgumentException(
            "UserSettings.TranslationApi."
                + key
                + " must be an integer from 0 to "
                + MAX_TRANSLATION_RETRY_COUNT
        );
    }

    public static void validateGameTermDictionary(JSONObject dictionary)
        throws Exception {
        if (dictionary.length() == 0) {
            throw new IllegalArgumentException("game term dictionary must not be empty");
        }

        JSONArray names = dictionary.names();
        if (names == null) {
            throw new IllegalArgumentException("game term dictionary has no entries");
        }

        for (int index = 0; index < names.length(); index++) {
            String name = names.getString(index);
            if (name.trim().isEmpty()) {
                throw new IllegalArgumentException(
                    "game term dictionary contains an empty key"
                );
            }
            validateGameTermRecord(name, dictionary.getJSONObject(name));
        }
    }

    public static void validateGameTermRecord(String name, JSONObject record)
        throws Exception {
        String[] stringFields = {"en", "zh-tw", "zh-cn", "description"};
        for (String field : stringFields) {
            if (record.has(field) && !(record.get(field) instanceof String)) {
                throw new IllegalArgumentException(
                    name + "." + field + " must be a string"
                );
            }
        }
    }

    private static String requireNonEmptyString(
        JSONObject object,
        String key,
        String path
    ) throws Exception {
        String value = object.getString(key).trim();
        if (value.isEmpty()) {
            throw new IllegalArgumentException(path + "." + key + " must not be empty");
        }
        return value;
    }

    private static JSONObject readJson(InputStream input) throws Exception {
        try (InputStream source = input) {
            return new JSONObject(
                IoUtils.readUtf8Limited(source, -1)
            );
        }
    }

    private static void requireSupportedName(String name) {
        if (!CONFIG_FILE_NAME.equals(name)
            && !RUNTIME_FILE_NAME.equals(name)
            && !CHARDICT_FILE_NAME.equals(name)
            && !GAMETERMS_FILE_NAME.equals(name)) {
            throw new IllegalArgumentException("unsupported module resource: " + name);
        }
    }

    private static String bundledAssetPath(String name) {
        if (CHARDICT_FILE_NAME.equals(name)
            || GAMETERMS_FILE_NAME.equals(name)) {
            return TERM_ASSET_DIRECTORY + name;
        }
        return name;
    }
}
