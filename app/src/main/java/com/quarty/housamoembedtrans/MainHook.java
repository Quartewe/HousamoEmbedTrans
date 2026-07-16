package com.quarty.housamoembedtrans;

import com.bytedance.shadowhook.ShadowHook;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.callbacks.XC_LoadPackage.LoadPackageParam;
import de.robv.android.xposed.IXposedHookZygoteInit;

import android.app.Application;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.util.AtomicFile;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedHelpers;

import org.json.JSONArray;
import org.json.JSONObject;
import java.io.InputStream;
import java.io.ByteArrayOutputStream;
import java.io.FileNotFoundException;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.HashSet;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.zip.ZipFile;
import java.util.zip.ZipEntry;
import java.io.File;

/**
 * LSPosed 模块入口 — Housamo AI 实时翻译。
 *
 * 工作流程:
 *   1. LSPosed 在目标应用加载时回调 handleLoadPackage
 *   2. 确认是 Housamo (jp.co.lifewonders.housamo)
 *   3. 初始化 ShadowHook → System.loadLibrary("housamo_trans") → 触发 JNI_OnLoad
 *   4. JNI_OnLoad 中定位 libil2cpp.so → ShadowHook → 翻译管线就绪
 */

public class MainHook implements IXposedHookLoadPackage, IXposedHookZygoteInit {

    private static final String TARGET_PACKAGE = "jp.co.lifewonders.housamo";
    private static final String MODULE_PACKAGE = "com.quarty.housamoembedtrans";
    private static final String USER_FILES_AUTHORITY =
        "com.quarty.housamoembedtrans.userfiles";
    private static final String CONFIG_FILE_NAME = "config.json";
    private static final String CHARDICT_FILE_NAME = "chardict.json";
    private static final String GAMETERMS_FILE_NAME = "gameterms.json";
    private static final String PROMPT_FILE_NAME = "prompt.txt";
    private static final String TRANSLATION_SCHEMA_FILE_NAME = "translation_schema.json";
    private static final int HTTP_CONNECT_TIMEOUT_MS = 30_000;
    private static final int HTTP_READ_TIMEOUT_MS = 300_000;
    private static final int MAX_HTTP_RESPONSE_BYTES = 16 * 1024 * 1024;
    private static final int ANTHROPIC_MAX_TOKENS = 8_192;
    private static String sModulePath = null;
    private static boolean s_loaded = false;
    private static boolean s_attach_hook_installed = false;
    private static boolean s_initializing = false;
    private static volatile TranslationConfig sTranslationConfig;
    private static volatile Context sTargetContext;

    private static final class RVA {
        long findScenarioData = 0;
        long initBase = 0;
        long initText = 0;
        long pageTextChange = 0;
        long addSelection = 0;
        long showSelection = 0;
    }

    private static final class Layout {
        Il2CppStringLayout il2CppString = new Il2CppStringLayout();
        Il2CppArrayLayout il2CppArray = new Il2CppArrayLayout();
        Il2CppListLayout il2CppList = new Il2CppListLayout();
        AdvScenarioPageDataLayout advScenarioPageData = new AdvScenarioPageDataLayout();
        ScenarioLabelDataLayout scenarioLabelData = new ScenarioLabelDataLayout();
        AdvScenarioDataLayout advScenarioData = new AdvScenarioDataLayout();
        Il2CppDictionaryLayout il2CppDictionary = new Il2CppDictionaryLayout();
        DictionaryEntryLayout dictionaryEntry = new DictionaryEntryLayout();
        AdvCommandLayout advCommand = new AdvCommandLayout();
        StringGridRowLayout stringGridRow = new StringGridRowLayout();
        AdvCommandCharacterLayout advCommandCharacter = new AdvCommandCharacterLayout();
        AdvCommandSelectionLayout advCommandSelection = new AdvCommandSelectionLayout();
        AdvCommandJumpLayout advCommandJump = new AdvCommandJumpLayout();
        TextColumnsLayout textColumns = new TextColumnsLayout();

        private static final class Il2CppStringLayout {
            long length = 0;
            long chars = 0;
        }

        private static final class Il2CppArrayLayout {
            long length = 0;
            long firstElement = 0;
            int pointerSize = 0;
        }

        private static final class Il2CppListLayout {
            long items = 0;
            long size = 0;
        }

        private static final class AdvScenarioPageDataLayout {
            long commandList = 0;
            long textDataList = 0;
            long scenarioLabelData = 0;
            long pageNo = 0;
            long messageWindowName = 0;
        }

        private static final class ScenarioLabelDataLayout {
            long pageDataList = 0;
            long scenarioLabel = 0;
            long next = 0;
            long commandList = 0;
            long scenarioLabelCommand = 0;
        }

        private static final class AdvScenarioDataLayout {
            long name = 0;
            long jumpDataList = 0;
            long scenarioLabels = 0;
        }

        private static final class Il2CppDictionaryLayout {
            long entries = 0;
            long count = 0;
        }

        private static final class DictionaryEntryLayout {
            long hashCode = 0;
            long key = 0;
            long value = 0;
            long size = 0;
        }

        private static final class AdvCommandLayout {
            long rowData = 0;
            long type = 0;
        }

        private static final class StringGridRowLayout {
            long rowIndex = 0;
            long strings = 0;
        }

        private static final class AdvCommandCharacterLayout {
            long characterInfo = 0;
            long nameText = 0;
        }

        private static final class AdvCommandSelectionLayout {
            long jumpLabel = 0;
        }

        private static final class AdvCommandJumpLayout {
            long jumpLabel = 0;
            long expressionParser = 0;
            int conditionColumn = 0;
        }

        private static final class TextColumnsLayout {
            int raw = 0;
            int en = 0;
            int zhTw = 0;
            int zhCn = 0;
        }
    }

    private static final class CharacterWeight {
        float highRelevance = 4.0f;
        float midRelevance = 3.0f;
        float densityHigh = 1.5f;
        float textLowScore = 3.0f;
        float textMentionedScore = 1.0f;
        int relatedNum = 1;
        int lowTermScore = 3;
    }

    private static final class StartupConfig {
        RVA rva;
        Layout layout;
        CharacterWeight characterWeight;
        boolean enablePageRecDebug;
        boolean enableParseOnlyDebug;
        boolean overwriteExistingJson;
        String targetLanguage;
        String gameVersion;
        TranslationConfig translationConfig;
    }

    private static final class TranslationConfig {
        final String protocol;
        final String apiUrl;
        final String model;
        final String apiKey;
        final String systemPrompt;
        final String responseSchema;

        TranslationConfig(
            String protocol,
            String apiUrl,
            String model,
            String apiKey,
            String systemPrompt,
            String responseSchema
        ) {
            this.protocol = protocol;
            this.apiUrl = apiUrl;
            this.model = model;
            this.apiKey = apiKey;
            this.systemPrompt = systemPrompt;
            this.responseSchema = responseSchema;
        }

        TranslationConfig withRuntimeValues(
            String apiKey,
            String systemPrompt,
            String responseSchema
        ) {
            return new TranslationConfig(
                protocol,
                apiUrl,
                model,
                apiKey,
                systemPrompt,
                responseSchema
            );
        }
    }

    private static final class HttpResult {
        final int statusCode;
        final String body;

        HttpResult(int statusCode, String body) {
            this.statusCode = statusCode;
            this.body = body;
        }
    }

    // Tools
    private static long parseRVA(String value) {
        // 支持十六进制（0x开头）和十进制格式的 RVA 输入
        value = value.trim();

        if (value.startsWith("0x") || value.startsWith("0X")) {
            return Long.parseUnsignedLong(value.substring(2), 16);
        }

        return Long.parseUnsignedLong(value, 10);
    }

    private static long getConfigLong(JSONObject json, String key) throws Exception {
        Object value = json.get(key);

        if (value instanceof Number) {
            return ((Number) value).longValue();
        }

        return parseRVA(String.valueOf(value));
    }

    private static int getConfigInt(JSONObject json, String key) throws Exception {
        long value = getConfigLong(json, key);

        if (value < Integer.MIN_VALUE || value > Integer.MAX_VALUE) {
            throw new IllegalArgumentException(key + " is out of int range: " + value);
        }

        return (int) value;
    }

    private static float getConfigFloat(JSONObject json, String key) throws Exception {
        Object value = json.get(key);
        double parsed;

        if (value instanceof Number) {
            parsed = ((Number) value).doubleValue();
        } else {
            parsed = Double.parseDouble(String.valueOf(value).trim());
        }

        if (Double.isNaN(parsed) || Double.isInfinite(parsed) || parsed <= 0.0 || parsed > 100000.0) {
            throw new IllegalArgumentException(key + " must be a finite positive number: " + value);
        }

        return (float) parsed;
    }

    private static String readModuleAsset(String name) throws Exception {
        // 获取模块 APK 路径并读取 assets 目录下的指定文件内容
        try (ZipFile zip = new ZipFile(sModulePath)) {
            
            ZipEntry entry = zip.getEntry("assets/" + name);
            if (entry == null) {
                throw new FileNotFoundException("assets/" + name);
            }

            try (InputStream input = zip.getInputStream(entry)) {
                return readUtf8(input);
            }
        }
    }

    private static String readPreferredModuleJson(Context context, String name)
        throws Exception {
        Uri uri = new Uri.Builder()
            .scheme("content")
            .authority(USER_FILES_AUTHORITY)
            .appendPath(name)
            .build();

        try {
            context.getContentResolver().takePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION
            );
        } catch (SecurityException ignored) {
            // A temporary grant may not be present yet. openInputStream below
            // still works on devices where the exported provider is visible.
        }

        try (InputStream input = context.getContentResolver().openInputStream(uri)) {
            if (input != null) {
                XposedBridge.log("[HousamoTrans] Using user override: " + name);
                return readUtf8(input);
            }
        } catch (Exception e) {
            XposedBridge.log(
                "[HousamoTrans] No usable user override for "
                    + name
                    + "; falling back to module asset ("
                    + e.getClass().getSimpleName()
                    + ")"
            );
        }

        return readModuleAsset(name);
    }

    private static String readUtf8(InputStream input) throws Exception {
        try (ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[8192];
            int read;

            while ((read = input.read(buffer)) != -1) {
                output.write(buffer, 0, read);
            }

            return output.toString(StandardCharsets.UTF_8.name());
        }
    }

    private static String readApiKey(Context context) {
        try {
            Bundle result = context.getContentResolver().call(
                USER_FILES_AUTHORITY,
                UserConfigProvider.METHOD_GET_API_KEY,
                null,
                null
            );
            return result == null
                ? ""
                : result.getString(UserConfigProvider.RESULT_API_KEY, "");
        } catch (RuntimeException e) {
            XposedBridge.log(
                "[HousamoTrans] Could not read API key from module provider: "
                    + e.getClass().getSimpleName()
            );
            return "";
        }
    }

    /** Called by Native after a scene file has been committed in the game directory. */
    private static boolean storeScene(byte[] sceneBytes) {
        Context context = sTargetContext;
        if (context == null
            || sceneBytes == null
            || sceneBytes.length == 0
            || sceneBytes.length > SceneStore.MAX_SCENE_BYTES) {
            return false;
        }

        try {
            JSONObject scene = new JSONObject(new String(
                sceneBytes,
                StandardCharsets.UTF_8
            ));
            String fileName = SceneStore.fileNameForScene(scene.getString("scene"));
            Uri uri = sceneUri(fileName);
            try (OutputStream output = context.getContentResolver()
                .openOutputStream(uri, "wt")) {
                if (output == null) {
                    return false;
                }
                output.write(sceneBytes);
                output.flush();
            }
            return true;
        } catch (Exception e) {
            XposedBridge.log(
                "[HousamoTrans] Could not mirror generated scene: "
                    + e.getClass().getSimpleName()
                    + ": "
                    + e.getMessage()
            );
            return false;
        }
    }

    private static void startSceneMirrorSync(Context context, File targetSceneDirectory) {
        Thread worker = new Thread(
            () -> syncSceneMirror(context, targetSceneDirectory),
            "HET-scene-sync"
        );
        worker.setDaemon(true);
        worker.start();
    }

    private static void syncSceneMirror(Context context, File targetSceneDirectory) {
        try {
            Bundle result = context.getContentResolver().call(
                USER_FILES_AUTHORITY,
                UserConfigProvider.METHOD_LIST_SCENES,
                null,
                null
            );
            ArrayList<String> listed = result == null
                ? null
                : result.getStringArrayList(UserConfigProvider.RESULT_SCENES);
            Set<String> mirrored = listed == null
                ? new HashSet<>()
                : new HashSet<>(listed);

            int pulled = 0;
            for (String fileName : mirrored) {
                if (!SceneStore.isSimpleSceneFileName(fileName)) {
                    continue;
                }
                try (InputStream input = context.getContentResolver()
                    .openInputStream(sceneUri(fileName))) {
                    if (input == null) {
                        continue;
                    }
                    writeAtomically(
                        new File(targetSceneDirectory, fileName),
                        readBounded(input)
                    );
                    pulled++;
                } catch (Exception e) {
                    XposedBridge.log(
                        "[HousamoTrans] Could not pull scene "
                            + fileName
                            + ": "
                            + e.getClass().getSimpleName()
                    );
                }
            }

            int pushed = 0;
            File[] localFiles = targetSceneDirectory.listFiles(
                file -> file.isFile() && file.getName().endsWith(".json")
            );
            if (localFiles != null) {
                for (File file : localFiles) {
                    if (mirrored.contains(file.getName())) {
                        continue;
                    }
                    try (InputStream input = new FileInputStream(file)) {
                        if (storeScene(readBounded(input))) {
                            pushed++;
                        }
                    } catch (Exception e) {
                        XposedBridge.log(
                            "[HousamoTrans] Could not push scene "
                                + file.getName()
                                + ": "
                                + e.getClass().getSimpleName()
                        );
                    }
                }
            }

            XposedBridge.log(
                "[HousamoTrans] Scene mirror synchronized: pulled="
                    + pulled
                    + " pushed="
                    + pushed
            );
        } catch (Exception e) {
            XposedBridge.log(
                "[HousamoTrans] Scene mirror sync failed: "
                    + e.getClass().getSimpleName()
                    + ": "
                    + e.getMessage()
            );
        }
    }

    private static Uri sceneUri(String fileName) {
        return new Uri.Builder()
            .scheme("content")
            .authority(USER_FILES_AUTHORITY)
            .appendPath(SceneStore.DIRECTORY_NAME)
            .appendPath(fileName)
            .build();
    }

    private static byte[] readBounded(InputStream input) throws IOException {
        try (ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[8192];
            int total = 0;
            int read;
            while ((read = input.read(buffer)) != -1) {
                total += read;
                if (total > SceneStore.MAX_SCENE_BYTES) {
                    throw new IOException("scene file exceeds 32 MiB");
                }
                output.write(buffer, 0, read);
            }
            return output.toByteArray();
        }
    }

    private static void writeAtomically(File file, byte[] bytes) throws IOException {
        AtomicFile atomicFile = new AtomicFile(file);
        FileOutputStream output = null;
        try {
            output = atomicFile.startWrite();
            output.write(bytes);
            atomicFile.finishWrite(output);
        } catch (IOException e) {
            if (output != null) {
                atomicFile.failWrite(output);
            }
            throw e;
        }
    }

    private static RVA Init_RVA(JSONObject json) throws Exception {
        RVA rva = new RVA();
        JSONObject rva_config = json.getJSONObject("RVA");
        rva.findScenarioData = parseRVA(rva_config.getString("RVA_FindScenarioData"));
        rva.initBase = parseRVA(rva_config.getString("RVA_InitBase"));
        rva.initText = parseRVA(rva_config.getString("RVA_InitText"));
        rva.pageTextChange = parseRVA(rva_config.getString("RVA_PageTextChange"));
        rva.addSelection = parseRVA(rva_config.getString("RVA_AddSelection"));
        rva.showSelection = parseRVA(rva_config.getString("RVA_ShowSelection"));
        return rva;
    }

    private static Layout Init_Layout(JSONObject json) throws Exception {
        Layout layout = new Layout();
        JSONObject layoutConfig = json.getJSONObject("Layout");

        JSONObject il2CppString = layoutConfig.getJSONObject("Il2CppString");
        layout.il2CppString.length = getConfigLong(il2CppString, "Length");
        layout.il2CppString.chars = getConfigLong(il2CppString, "Chars");

        JSONObject il2CppArray = layoutConfig.getJSONObject("Il2CppArray");
        layout.il2CppArray.length = getConfigLong(il2CppArray, "Length");
        layout.il2CppArray.firstElement = getConfigLong(il2CppArray, "FirstElement");
        layout.il2CppArray.pointerSize = getConfigInt(il2CppArray, "PointerSize");

        JSONObject il2CppList = layoutConfig.getJSONObject("Il2CppList");
        layout.il2CppList.items = getConfigLong(il2CppList, "Items");
        layout.il2CppList.size = getConfigLong(il2CppList, "Size");

        JSONObject pageData = layoutConfig.getJSONObject("AdvScenarioPageData");
        layout.advScenarioPageData.commandList = getConfigLong(pageData, "CommandList");
        layout.advScenarioPageData.textDataList = getConfigLong(pageData, "TextDataList");
        layout.advScenarioPageData.scenarioLabelData = getConfigLong(pageData, "ScenarioLabelData");
        layout.advScenarioPageData.pageNo = getConfigLong(pageData, "PageNo");
        layout.advScenarioPageData.messageWindowName = getConfigLong(pageData, "MessageWindowName");

        JSONObject scenarioLabelData = layoutConfig.getJSONObject("ScenarioLabelData");
        layout.scenarioLabelData.pageDataList = getConfigLong(scenarioLabelData, "PageDataList");
        layout.scenarioLabelData.scenarioLabel = getConfigLong(scenarioLabelData, "ScenarioLabel");
        layout.scenarioLabelData.next = getConfigLong(scenarioLabelData, "Next");
        layout.scenarioLabelData.commandList = getConfigLong(scenarioLabelData, "CommandList");
        layout.scenarioLabelData.scenarioLabelCommand = getConfigLong(scenarioLabelData, "ScenarioLabelCommand");

        JSONObject scenarioData = layoutConfig.getJSONObject("AdvScenarioData");
        layout.advScenarioData.name = getConfigLong(scenarioData, "Name");
        layout.advScenarioData.jumpDataList = getConfigLong(scenarioData, "JumpDataList");
        layout.advScenarioData.scenarioLabels = getConfigLong(scenarioData, "ScenarioLabels");

        JSONObject il2CppDictionary = layoutConfig.getJSONObject("Il2CppDictionary");
        layout.il2CppDictionary.entries = getConfigLong(il2CppDictionary, "Entries");
        layout.il2CppDictionary.count = getConfigLong(il2CppDictionary, "Count");

        JSONObject dictionaryEntry = layoutConfig.getJSONObject("DictionaryEntry");
        layout.dictionaryEntry.hashCode = getConfigLong(dictionaryEntry, "HashCode");
        layout.dictionaryEntry.key = getConfigLong(dictionaryEntry, "Key");
        layout.dictionaryEntry.value = getConfigLong(dictionaryEntry, "Value");
        layout.dictionaryEntry.size = getConfigLong(dictionaryEntry, "Size");

        JSONObject advCommand = layoutConfig.getJSONObject("AdvCommand");
        layout.advCommand.rowData = getConfigLong(advCommand, "RowData");
        layout.advCommand.type = getConfigLong(advCommand, "Type");

        JSONObject stringGridRow = layoutConfig.getJSONObject("StringGridRow");
        layout.stringGridRow.rowIndex = getConfigLong(stringGridRow, "RowIndex");
        layout.stringGridRow.strings = getConfigLong(stringGridRow, "Strings");

        JSONObject character = layoutConfig.getJSONObject("AdvCommandCharacter");
        layout.advCommandCharacter.characterInfo = getConfigLong(character, "CharacterInfo");
        layout.advCommandCharacter.nameText = getConfigLong(character, "NameText");

        JSONObject selection = layoutConfig.getJSONObject("AdvCommandSelection");
        layout.advCommandSelection.jumpLabel = getConfigLong(selection, "JumpLabel");

        JSONObject jump = layoutConfig.getJSONObject("AdvCommandJump");
        layout.advCommandJump.jumpLabel = getConfigLong(jump, "JumpLabel");
        layout.advCommandJump.expressionParser = getConfigLong(jump, "ExpressionParser");
        layout.advCommandJump.conditionColumn = getConfigInt(jump, "ConditionColumn");

        JSONObject textColumns = layoutConfig.getJSONObject("TextColumns");
        layout.textColumns.raw = getConfigInt(textColumns, "Raw");
        layout.textColumns.en = getConfigInt(textColumns, "En");
        layout.textColumns.zhTw = getConfigInt(textColumns, "ZhTw");
        layout.textColumns.zhCn = getConfigInt(textColumns, "ZhCn");

        return layout;
    }

    private static boolean Init_EnablePageRecDebug(JSONObject json) throws Exception {
        JSONObject userSettings = json.getJSONObject("UserSettings");
        return userSettings.getBoolean("EnablePageRecDebug");
    }

    private static boolean Init_EnableParseOnlyDebug(JSONObject json) throws Exception {
        JSONObject userSettings = json.getJSONObject("UserSettings");
        return userSettings.optBoolean("EnableParseOnlyDebug", false);
    }

    private static boolean Init_OverwriteExistingJson(JSONObject json) throws Exception {
        JSONObject userSettings = json.getJSONObject("UserSettings");
        return userSettings.getBoolean("OverwriteExistingJson");
    }

    private static CharacterWeight Init_CharacterWeight(JSONObject json) throws Exception {
        CharacterWeight weight = new CharacterWeight();
        JSONObject userSettings = json.getJSONObject("UserSettings");
        JSONObject weightConfig = userSettings.getJSONObject("CharacterWeight");

        weight.highRelevance = getConfigFloat(weightConfig, "HighRelevance");
        weight.midRelevance = getConfigFloat(weightConfig, "MidRelevance");
        weight.densityHigh = getConfigFloat(weightConfig, "DensityHigh");
        weight.textLowScore = getConfigFloat(weightConfig, "TextLowScore");
        weight.textMentionedScore = getConfigFloat(
            weightConfig,
            "TextMentionedScore"
        );
        weight.relatedNum = getConfigInt(weightConfig, "RelatedNum");
        weight.lowTermScore = getConfigInt(weightConfig, "LowTermScore");

        if (weight.highRelevance < weight.midRelevance) {
            throw new IllegalArgumentException(
                "CharacterWeight.HighRelevance must be >= MidRelevance"
            );
        }

        if (weight.textLowScore < weight.textMentionedScore) {
            throw new IllegalArgumentException(
                "CharacterWeight.TextLowScore must be >= TextMentionedScore"
            );
        }

        if (weight.relatedNum < 1) {
            throw new IllegalArgumentException(
                "CharacterWeight.RelatedNum must be >= 1"
            );
        }

        if (weight.lowTermScore < 1) {
            throw new IllegalArgumentException(
                "CharacterWeight.LowTermScore must be >= 1"
            );
        }

        return weight;
    }

    private static String Init_TargetLanguage(JSONObject json) throws Exception {
        JSONObject userSettings = json.getJSONObject("UserSettings");
        return userSettings.getString("TargetLanguage");
    }

    private static String Init_GameVersion(JSONObject json) throws Exception {
        return json.getString("GameVersion");
    }

    private static TranslationConfig Init_TranslationConfig(JSONObject json)
        throws Exception {
        JSONObject api = json.getJSONObject("UserSettings")
            .getJSONObject("TranslationApi");
        return new TranslationConfig(
            api.optString("Protocol", "openai").trim().toLowerCase(Locale.ROOT),
            api.optString("BaseUrl", "").trim(),
            api.optString("Model", "").trim(),
            "",
            "",
            ""
        );
    }

    private static StartupConfig parseStartupConfig(String jsonText) throws Exception {
        JSONObject json = new JSONObject(jsonText);
        JSONObject runtimeConfigs = json.getJSONObject("RuntimeConfigs");

        StartupConfig config = new StartupConfig();
        config.rva = Init_RVA(runtimeConfigs);
        config.layout = Init_Layout(runtimeConfigs);
        config.characterWeight = Init_CharacterWeight(json);
        config.enablePageRecDebug = Init_EnablePageRecDebug(json);
        config.enableParseOnlyDebug = Init_EnableParseOnlyDebug(json);
        config.overwriteExistingJson = Init_OverwriteExistingJson(json);
        config.targetLanguage = Init_TargetLanguage(json);
        config.gameVersion = Init_GameVersion(json);
        config.translationConfig = Init_TranslationConfig(json);
        return config;
    }

    private static StartupConfig loadStartupConfig(Context context) throws Exception {
        String preferred = readPreferredModuleJson(context, CONFIG_FILE_NAME);
        try {
            return parseStartupConfig(preferred);
        } catch (Exception e) {
            XposedBridge.log(
                "[HousamoTrans] Preferred config.json is invalid; "
                    + "retrying the bundled default ("
                    + e.getClass().getSimpleName()
                    + ": "
                    + e.getMessage()
                    + ")"
            );
            return parseStartupConfig(readModuleAsset(CONFIG_FILE_NAME));
        }
    }

    private static JSONObject buildOpenAIRequest(
        TranslationConfig config,
        String sceneJson
    ) throws Exception {
        JSONObject request = new JSONObject();
        request.put("model", config.model);

        JSONArray messages = new JSONArray();
        messages.put(new JSONObject()
            .put("role", "system")
            .put("content", config.systemPrompt));
        messages.put(new JSONObject()
            .put("role", "user")
            .put("content", sceneJson));
        request.put("messages", messages);

        JSONObject jsonSchema = new JSONObject();
        jsonSchema.put("name", "housamo_translation");
        jsonSchema.put("strict", true);
        jsonSchema.put("schema", new JSONObject(config.responseSchema));

        request.put("response_format", new JSONObject()
            .put("type", "json_schema")
            .put("json_schema", jsonSchema));
        return request;
    }

    private static JSONObject buildAnthropicRequest(
        TranslationConfig config,
        String sceneJson
    ) throws Exception {
        JSONObject request = new JSONObject();
        request.put("model", config.model);
        request.put("max_tokens", ANTHROPIC_MAX_TOKENS);
        request.put("system", config.systemPrompt);
        request.put("messages", new JSONArray().put(new JSONObject()
            .put("role", "user")
            .put("content", sceneJson)));

        JSONObject format = new JSONObject();
        format.put("type", "json_schema");
        format.put("schema", new JSONObject(config.responseSchema));
        request.put("output_config", new JSONObject().put("format", format));
        return request;
    }

    private static byte[] requestTranslation(byte[] requestJson) {
        if (requestJson == null || requestJson.length == 0) {
            return errorBytes("input", 0, "request is empty");
        }

        TranslationConfig config = sTranslationConfig;
        if (config == null) {
            return errorBytes("config", 0, "translation config is not initialized");
        }

        try {
            validateTranslationConfig(config);

            String sceneJson = new String(requestJson, StandardCharsets.UTF_8);
            JSONObject scene = new JSONObject(sceneJson);
            Map<Integer, String> expectedTexts = collectExpectedTexts(scene);
            Set<String> protectedLabels = collectProtectedLabels(scene);

            JSONObject apiRequest;
            if ("openai".equals(config.protocol)) {
                apiRequest = buildOpenAIRequest(config, sceneJson);
            } else if ("anthropic".equals(config.protocol)) {
                apiRequest = buildAnthropicRequest(config, sceneJson);
            } else {
                throw new IllegalArgumentException(
                    "unsupported API protocol: " + config.protocol
                );
            }

            HttpResult httpResult = postJson(config, apiRequest.toString());
            if (httpResult.statusCode < 200 || httpResult.statusCode >= 300) {
                return errorBytes(
                    "http",
                    httpResult.statusCode,
                    redactSecret(httpResult.body, config.apiKey)
                );
            }

            JSONObject translationResult = "openai".equals(config.protocol)
                ? extractOpenAIResult(httpResult.body)
                : extractAnthropicResult(httpResult.body);

            validateTranslationResult(
                translationResult,
                expectedTexts,
                protectedLabels
            );
            return successBytes(translationResult);
        } catch (Exception e) {
            String message = redactSecret(safeMessage(e), config.apiKey);
            XposedBridge.log(
                "[HousamoTrans] Translation request failed: "
                    + e.getClass().getSimpleName()
                    + ": "
                    + message
            );
            return errorBytes("client", 0, message);
        }
    }

    private static void validateTranslationConfig(TranslationConfig config)
        throws Exception {
        if (!"openai".equals(config.protocol)
            && !"anthropic".equals(config.protocol)) {
            throw new IllegalArgumentException(
                "Protocol must be openai or anthropic"
            );
        }
        if (config.model.isEmpty()) {
            throw new IllegalArgumentException("Model is empty");
        }
        if (config.systemPrompt.isEmpty()) {
            throw new IllegalArgumentException("system prompt is empty");
        }
        new JSONObject(config.responseSchema);
    }

    private static HttpResult postJson(TranslationConfig config, String body)
        throws Exception {
        URL url = new URL(resolveApiEndpoint(config));
        String scheme = url.getProtocol();
        if (!"https".equalsIgnoreCase(scheme) && !"http".equalsIgnoreCase(scheme)) {
            throw new IllegalArgumentException("API URL must use http or https");
        }

        HttpURLConnection connection = (HttpURLConnection) url.openConnection();
        try {
            connection.setRequestMethod("POST");
            connection.setConnectTimeout(HTTP_CONNECT_TIMEOUT_MS);
            connection.setReadTimeout(HTTP_READ_TIMEOUT_MS);
            connection.setInstanceFollowRedirects(false);
            connection.setDoOutput(true);
            connection.setRequestProperty("Accept", "application/json");
            connection.setRequestProperty("Content-Type", "application/json; charset=utf-8");
            connection.setRequestProperty("User-Agent", "HousamoEmbedTrans/1.0");

            if ("openai".equals(config.protocol)) {
                if (!config.apiKey.isEmpty()) {
                    connection.setRequestProperty(
                        "Authorization",
                        "Bearer " + config.apiKey
                    );
                }
            } else {
                if (!config.apiKey.isEmpty()) {
                    connection.setRequestProperty("x-api-key", config.apiKey);
                }
                connection.setRequestProperty("anthropic-version", "2023-06-01");
            }

            byte[] requestBytes = body.getBytes(StandardCharsets.UTF_8);
            connection.setFixedLengthStreamingMode(requestBytes.length);
            try (OutputStream output = connection.getOutputStream()) {
                output.write(requestBytes);
            }

            int statusCode = connection.getResponseCode();
            InputStream responseStream = statusCode >= 400
                ? connection.getErrorStream()
                : connection.getInputStream();
            String responseBody = responseStream == null
                ? ""
                : readHttpBody(responseStream);
            return new HttpResult(statusCode, responseBody);
        } finally {
            connection.disconnect();
        }
    }

    private static String resolveApiEndpoint(TranslationConfig config) {
        String baseUrl = config.apiUrl.trim();
        if (baseUrl.isEmpty()) {
            baseUrl = "openai".equals(config.protocol)
                ? "https://api.openai.com/v1"
                : "https://api.anthropic.com";
        }

        while (baseUrl.endsWith("/")) {
            baseUrl = baseUrl.substring(0, baseUrl.length() - 1);
        }

        if ("openai".equals(config.protocol)) {
            if (baseUrl.endsWith("/chat/completions")) {
                return baseUrl;
            }
            return baseUrl.endsWith("/v1")
                ? baseUrl + "/chat/completions"
                : baseUrl + "/v1/chat/completions";
        }

        if (baseUrl.endsWith("/v1/messages")) {
            return baseUrl;
        }
        return baseUrl.endsWith("/v1")
            ? baseUrl + "/messages"
            : baseUrl + "/v1/messages";
    }

    private static String readHttpBody(InputStream input) throws IOException {
        try (InputStream source = input;
             ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[8192];
            int total = 0;
            int read;
            while ((read = source.read(buffer)) != -1) {
                total += read;
                if (total > MAX_HTTP_RESPONSE_BYTES) {
                    throw new IOException("API response is larger than 16 MiB");
                }
                output.write(buffer, 0, read);
            }
            return output.toString(StandardCharsets.UTF_8.name());
        }
    }

    private static JSONObject extractOpenAIResult(String body) throws Exception {
        JSONObject response = new JSONObject(body);
        JSONArray choices = response.getJSONArray("choices");
        if (choices.length() == 0) {
            throw new IllegalArgumentException("OpenAI response has no choices");
        }

        JSONObject message = choices.getJSONObject(0).getJSONObject("message");
        String refusal = message.optString("refusal", "");
        if (!refusal.isEmpty()) {
            throw new IllegalArgumentException("model refused the request: " + refusal);
        }
        return parseJsonContent(message.opt("content"), "OpenAI");
    }

    private static JSONObject extractAnthropicResult(String body) throws Exception {
        JSONObject response = new JSONObject(body);
        JSONArray content = response.getJSONArray("content");
        for (int index = 0; index < content.length(); index++) {
            JSONObject block = content.optJSONObject(index);
            if (block != null && "text".equals(block.optString("type"))) {
                return parseJsonContent(block.opt("text"), "Anthropic");
            }
        }
        throw new IllegalArgumentException("Anthropic response has no text block");
    }

    private static JSONObject parseJsonContent(Object content, String provider)
        throws Exception {
        if (content instanceof JSONObject) {
            return (JSONObject) content;
        }
        if (!(content instanceof String) || ((String) content).trim().isEmpty()) {
            throw new IllegalArgumentException(provider + " response content is empty");
        }
        return new JSONObject(((String) content).trim());
    }

    private static Map<Integer, String> collectExpectedTexts(JSONObject scene)
        throws Exception {
        Map<Integer, String> expected = new HashMap<>();
        collectExpectedTexts(scene, expected);
        if (expected.isEmpty()) {
            throw new IllegalArgumentException("scene contains no translatable seq entries");
        }
        return expected;
    }

    private static void collectExpectedTexts(
        Object value,
        Map<Integer, String> expected
    ) throws Exception {
        if (value instanceof JSONObject) {
            JSONObject object = (JSONObject) value;
            if (object.has("seq") && object.has("text")) {
                Object seqValue = object.get("seq");
                Object textValue = object.get("text");
                if (!(seqValue instanceof Number) || !(textValue instanceof String)) {
                    throw new IllegalArgumentException("seq/text entry has invalid types");
                }

                int seq = requirePositiveInteger(seqValue, "input seq");
                if (seq < 1 || expected.put(seq, (String) textValue) != null) {
                    throw new IllegalArgumentException("duplicate or invalid seq: " + seq);
                }
            }

            Iterator<String> keys = object.keys();
            while (keys.hasNext()) {
                collectExpectedTexts(object.get(keys.next()), expected);
            }
        } else if (value instanceof JSONArray) {
            JSONArray array = (JSONArray) value;
            for (int index = 0; index < array.length(); index++) {
                collectExpectedTexts(array.get(index), expected);
            }
        }
    }

    private static Set<String> collectProtectedLabels(JSONObject scene)
        throws Exception {
        Set<String> labels = new HashSet<>();
        JSONArray protect = scene.optJSONArray("protect");
        if (protect == null) {
            return labels;
        }

        for (int index = 0; index < protect.length(); index++) {
            JSONObject item = protect.getJSONObject(index);
            String label = item.getString("label");
            if (!label.isEmpty()) {
                labels.add(label);
            }
        }
        return labels;
    }

    private static void validateTranslationResult(
        JSONObject result,
        Map<Integer, String> expectedTexts,
        Set<String> protectedLabels
    ) throws Exception {
        if (result.length() != 2
            || !result.has("summary")
            || !result.has("translations")) {
            throw new IllegalArgumentException(
                "translation result must contain only summary and translations"
            );
        }
        result.getString("summary");

        JSONArray translations = result.getJSONArray("translations");
        if (translations.length() != expectedTexts.size()) {
            throw new IllegalArgumentException(
                "translation count mismatch: expected "
                    + expectedTexts.size()
                    + ", got "
                    + translations.length()
            );
        }

        Set<Integer> returnedSeqs = new HashSet<>();
        for (int index = 0; index < translations.length(); index++) {
            JSONObject translation = translations.getJSONObject(index);
            if (translation.length() != 2
                || !translation.has("seq")
                || !translation.has("text")) {
                throw new IllegalArgumentException(
                    "translations[" + index + "] must contain only seq and text"
                );
            }

            Object seqValue = translation.get("seq");
            if (!(seqValue instanceof Number)) {
                throw new IllegalArgumentException("translated seq must be an integer");
            }
            int seq = requirePositiveInteger(seqValue, "translated seq");
            String translatedText = translation.getString("text");
            String sourceText = expectedTexts.get(seq);
            if (sourceText == null || !returnedSeqs.add(seq)) {
                throw new IllegalArgumentException(
                    "unknown or duplicate translated seq: " + seq
                );
            }

            for (String label : protectedLabels) {
                if (countOccurrences(sourceText, label)
                    != countOccurrences(translatedText, label)) {
                    throw new IllegalArgumentException(
                        "protected label count changed at seq " + seq + ": " + label
                    );
                }
            }
        }

        if (!returnedSeqs.equals(expectedTexts.keySet())) {
            throw new IllegalArgumentException("translated seq set does not match input");
        }
    }

    private static int countOccurrences(String text, String token) {
        if (token.isEmpty()) {
            return 0;
        }

        int count = 0;
        int offset = 0;
        while ((offset = text.indexOf(token, offset)) >= 0) {
            count++;
            offset += token.length();
        }
        return count;
    }

    private static int requirePositiveInteger(Object value, String label) {
        if (!(value instanceof Number)) {
            throw new IllegalArgumentException(label + " must be an integer");
        }

        double number = ((Number) value).doubleValue();
        if (!Double.isFinite(number)
            || number < 1.0
            || number > Integer.MAX_VALUE
            || number != Math.rint(number)) {
            throw new IllegalArgumentException(label + " must be a positive integer");
        }
        return (int) number;
    }

    private static byte[] successBytes(JSONObject result) {
        return result.toString().getBytes(StandardCharsets.UTF_8);
    }

    private static byte[] errorBytes(String type, int status, String message) {
        try {
            JSONObject error = new JSONObject();
            error.put("type", type);
            error.put("status", status);
            error.put("message", truncate(message, 4096));
            return new JSONObject()
                .put("error", error)
                .toString()
                .getBytes(StandardCharsets.UTF_8);
        } catch (Exception ignored) {
            return "{\"error\":{\"type\":\"internal\",\"status\":0}}"
                .getBytes(StandardCharsets.UTF_8);
        }
    }

    private static String truncate(String value, int maxLength) {
        if (value == null) {
            return "";
        }
        return value.length() <= maxLength
            ? value
            : value.substring(0, maxLength);
    }

    private static String redactSecret(String value, String secret) {
        if (value == null || secret == null || secret.isEmpty()) {
            return value;
        }
        return value.replace(secret, "[REDACTED]");
    }

    private static String safeMessage(Throwable throwable) {
        String message = throwable.getMessage();
        return message == null || message.trim().isEmpty()
            ? throwable.getClass().getSimpleName()
            : message;
    }

    private static void installApplicationEntry(LoadPackageParam lpparam) {
        if (s_attach_hook_installed) {
            return;
        }
        s_attach_hook_installed = true;

        XposedHelpers.findAndHookMethod(
            Application.class,
            "attach",
            Context.class,
            new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                    Context context = (Context) param.args[0];
                    initializeTarget(context, lpparam);
                }
            }
        );
    }

    private static void initializeTarget(Context context, LoadPackageParam lpparam) {
        synchronized (MainHook.class) {
            if (s_loaded || s_initializing) {
                return;
            }
            s_initializing = true;
        }

        String baseDir = lpparam.appInfo.dataDir + "/files/housamo_embed_trans";
        sTargetContext = context.getApplicationContext();

        try {
            XposedBridge.log(
                "[HousamoTrans] Target application attached, initializing ShadowHook..."
            );

            StartupConfig startup = loadStartupConfig(context);

            sTranslationConfig = startup.translationConfig.withRuntimeValues(
                readApiKey(context),
                readModuleAsset(PROMPT_FILE_NAME),
                readModuleAsset(TRANSLATION_SCHEMA_FILE_NAME)
            );

            String chardictJson = readPreferredModuleJson(context, CHARDICT_FILE_NAME);
            String gametermsJson = readPreferredModuleJson(context, GAMETERMS_FILE_NAME);

            ShadowHook.init(new ShadowHook.ConfigBuilder()
                .setMode(ShadowHook.Mode.UNIQUE)
                .build());
            XposedBridge.log("[HousamoTrans] ShadowHook init ok");

            new File(baseDir).mkdirs();
            File targetSceneDirectory = new File(baseDir, SceneStore.DIRECTORY_NAME);
            targetSceneDirectory.mkdirs();
            startSceneMirrorSync(sTargetContext, targetSceneDirectory);
            System.loadLibrary("housamo_trans");
            XposedBridge.log("[HousamoTrans] Native library loaded successfully.");

            nativeStart(
                startup.gameVersion,
                startup.rva,
                startup.layout,
                startup.characterWeight,
                startup.enablePageRecDebug,
                startup.enableParseOnlyDebug,
                startup.overwriteExistingJson,
                startup.targetLanguage,
                chardictJson,
                gametermsJson,
                baseDir
            );

            s_loaded = true;
            XposedBridge.log(
                "[HousamoTrans] Native hook setup complete. gameVersion="
                    + startup.gameVersion
                    + " targetLanguage="
                    + startup.targetLanguage
                    + " parseOnlyDebug="
                    + startup.enableParseOnlyDebug
                    + " protocol="
                    + sTranslationConfig.protocol
                    + " model="
                    + sTranslationConfig.model
            );
        } catch (Throwable t) {
            XposedBridge.log(
                "[HousamoTrans] FATAL: Initialization failed: "
                    + t.getClass().getSimpleName()
                    + ": "
                    + t.getMessage()
            );
        } finally {
            synchronized (MainHook.class) {
                s_initializing = false;
            }
        }
    }

    private static native void nativeStart(
        String gameVersion,
        RVA rva,
        Layout layout,
        CharacterWeight characterWeight,
        boolean enablePageRecDebug,
        boolean enableParseOnlyDebug,
        boolean overwriteExistingJson,
        String targetLanguage,
        String chardictJson,
        String gametermsJson,
        String baseDir
    );

    @Override
    public void initZygote(StartupParam startupParam) {
        sModulePath = startupParam.modulePath;
        XposedBridge.log("[HousamoTrans] Module path: " + sModulePath);
    }

    @Override
    public void handleLoadPackage(LoadPackageParam lpparam) {
        if (!TARGET_PACKAGE.equals(lpparam.packageName)
            || !TARGET_PACKAGE.equals(lpparam.processName)) {
            return;
        }

        installApplicationEntry(lpparam);
    }
}
