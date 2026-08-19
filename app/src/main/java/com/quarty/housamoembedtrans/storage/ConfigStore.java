package com.quarty.housamoembedtrans.storage;

import android.content.Context;
import android.util.AtomicFile;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
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
    public static final boolean DEFAULT_ENABLE_STREAMING_REPAIR = false;
    public static final int DEFAULT_REPAIR_GRADIENT_COUNT = 3;
    public static final boolean DEFAULT_USE_FULL_SCENE_FOR_REPAIR = true;
    public static final int MIN_REPAIR_GRADIENT_COUNT = 2;
    public static final int MAX_REPAIR_GRADIENT_COUNT = 8;
    public static final String DEFAULT_THINKING_STRENGTH = "none";
    public static final int DEFAULT_CONTEXT_LENGTH = 16000;
    public static final int MAX_TRANSLATION_RETRY_COUNT = 5;
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

        if (hasAtomicFile(userFile)) {
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
            saveBytes(
                name,
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
        if (!hasAtomicFile(file)) {
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
        JSONObject normalized = new JSONObject();
        normalized.put("Version", version);
        normalized.put("UserSettings", userSettings);
        return normalized;
    }

    private static boolean isCanonicalConfig(JSONObject config) {
        return config.length() == 2
            && config.has("Version")
            && config.has("UserSettings");
    }

    private static void validateOptionalRetryCount(JSONObject translationApi, String key)
        throws Exception {
        if (!translationApi.has(key)) {
            return;
        }

        Object value = translationApi.get(key);
        int retryCount;
        try {
            retryCount = requireInt(value, key);
        } catch (IllegalArgumentException e) {
            throw invalidRetryCount(key);
        }
        if (retryCount < 0 || retryCount > MAX_TRANSLATION_RETRY_COUNT) {
            throw invalidRetryCount(key);
        }
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

    private static boolean hasAtomicFile(File file) {
        return file.isFile() || new File(file.getPath() + ".bak").isFile();
    }

    private void saveBytes(String name, byte[] data) throws IOException {
        AtomicFile atomicFile = new AtomicFile(getUserFile(name));
        FileOutputStream output = null;

        try {
            output = atomicFile.startWrite();
            output.write(data);
            atomicFile.finishWrite(output);
        } catch (IOException e) {
            if (output != null) {
                atomicFile.failWrite(output);
            }
            throw e;
        }
    }

    private static JSONObject readJson(InputStream input) throws Exception {
        try (InputStream source = input;
             ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[8192];
            int read;

            while ((read = source.read(buffer)) != -1) {
                output.write(buffer, 0, read);
            }

            return new JSONObject(output.toString(StandardCharsets.UTF_8.name()));
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
