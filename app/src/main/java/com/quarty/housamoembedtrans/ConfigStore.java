package com.quarty.housamoembedtrans;

import android.content.Context;
import android.util.AtomicFile;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

/**
 * Stores user-editable JSON resources in the module app's private files directory.
 * Bundled assets remain immutable defaults and are used whenever no user file exists.
 */
final class ConfigStore {

    static final String CONFIG_FILE_NAME = "config.json";
    static final String CHARDICT_FILE_NAME = "chardict.json";
    static final String GAMETERMS_FILE_NAME = "gameterms.json";
    private static final String PREFS_NAME = "housamo_trans_prefs";
    private static final String KEY_API_KEY = "api_key";

    static class JsonLoadResult {
        final JSONObject json;
        final boolean userOverride;
        final boolean invalidUserOverride;

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

    static final class LoadResult extends JsonLoadResult {
        final JSONObject config;

        LoadResult(
            JSONObject config,
            boolean userOverride,
            boolean invalidUserOverride
        ) {
            super(config, userOverride, invalidUserOverride);
            this.config = config;
        }
    }

    private final Context context;

    ConfigStore(Context context) {
        this.context = context.getApplicationContext();
    }

    LoadResult load() throws Exception {
        JsonLoadResult result = loadJson(CONFIG_FILE_NAME);
        return new LoadResult(
            result.json,
            result.userOverride,
            result.invalidUserOverride
        );
    }

    JSONObject loadBundledDefault() throws Exception {
        return loadBundledJson(CONFIG_FILE_NAME);
    }

    void save(JSONObject config) throws IOException {
        saveJson(CONFIG_FILE_NAME, config);
    }

    String loadApiKey() {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getString(KEY_API_KEY, "");
    }

    void saveApiKey(String apiKey) throws IOException {
        boolean saved = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_API_KEY, apiKey == null ? "" : apiKey)
            .commit();
        if (!saved) {
            throw new IOException("could not persist API key");
        }
    }

    JsonLoadResult loadJson(String name) throws Exception {
        File userFile = getUserFile(name);
        AtomicFile atomicFile = new AtomicFile(userFile);

        if (hasAtomicFile(userFile)) {
            try {
                JSONObject json = readJson(atomicFile.openRead());
                validateResource(name, json);
                return new JsonLoadResult(json, true, false);
            } catch (Exception ignored) {
                return new JsonLoadResult(loadBundledJson(name), false, true);
            }
        }

        return new JsonLoadResult(loadBundledJson(name), false, false);
    }

    JSONObject loadBundledJson(String name) throws Exception {
        requireSupportedName(name);
        JSONObject json = readJson(context.getAssets().open(name));
        validateResource(name, json);
        return json;
    }

    void saveJson(String name, JSONObject json) throws IOException {
        try {
            saveBytes(
                name,
                (json.toString(2) + "\n").getBytes(StandardCharsets.UTF_8)
            );
        } catch (Exception e) {
            throw new IOException("could not serialize " + name, e);
        }
    }

    void deleteUserFile(String name) throws IOException {
        new AtomicFile(getUserFile(name)).delete();
    }

    /**
     * Returns an existing and syntactically valid user override, or null.
     * The provider uses this to avoid exposing a corrupt override to the game.
     */
    File getValidUserFile(String name) {
        File file = getUserFile(name);
        AtomicFile atomicFile = new AtomicFile(file);
        if (!hasAtomicFile(file)) {
            return null;
        }

        try {
            JSONObject json = readJson(atomicFile.openRead());
            validateResource(name, json);
            return file;
        } catch (Exception ignored) {
            return null;
        }
    }

    File getUserFile(String name) {
        requireSupportedName(name);
        return new File(context.getFilesDir(), name);
    }

    static void validateCharacterDictionary(JSONObject dictionary) throws Exception {
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

    static void validateCharacterRecord(String name, JSONObject record) throws Exception {
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
        } else if (CHARDICT_FILE_NAME.equals(name)) {
            validateCharacterDictionary(json);
        } else if (GAMETERMS_FILE_NAME.equals(name)) {
            validateObjectDictionary("game term dictionary", json);
        }
    }

    private static void validateConfig(JSONObject config) throws Exception {
        requireNonEmptyString(config, "GameVersion", "config");

        JSONObject userSettings = config.getJSONObject("UserSettings");
        userSettings.getBoolean("EnablePageRecDebug");
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

        JSONObject runtime = config.getJSONObject("RuntimeConfigs");
        runtime.getJSONObject("RVA");
        runtime.getJSONObject("Layout");
    }

    private static void validateObjectDictionary(String label, JSONObject dictionary)
        throws Exception {
        if (dictionary.length() == 0) {
            throw new IllegalArgumentException(label + " must not be empty");
        }

        JSONArray names = dictionary.names();
        if (names == null) {
            throw new IllegalArgumentException(label + " has no entries");
        }

        for (int index = 0; index < names.length(); index++) {
            String name = names.getString(index);
            if (name.trim().isEmpty()) {
                throw new IllegalArgumentException(label + " contains an empty key");
            }
            dictionary.getJSONObject(name);
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
            && !CHARDICT_FILE_NAME.equals(name)
            && !GAMETERMS_FILE_NAME.equals(name)) {
            throw new IllegalArgumentException("unsupported module resource: " + name);
        }
    }
}
