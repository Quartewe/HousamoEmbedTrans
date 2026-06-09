package com.quarty.housamoembedtrans;

import com.bytedance.shadowhook.ShadowHook;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.callbacks.XC_LoadPackage.LoadPackageParam;
import de.robv.android.xposed.IXposedHookZygoteInit;

import org.json.JSONObject;
import java.io.InputStream;
import java.io.ByteArrayOutputStream;
import java.io.FileNotFoundException;
import java.nio.charset.StandardCharsets;
import java.util.zip.ZipFile;
import java.util.zip.ZipEntry;

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
    private static String sModulePath = null;
    private static boolean s_loaded = false;

    private static final class RVA {
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
        AdvCommandLayout advCommand = new AdvCommandLayout();
        StringGridRowLayout stringGridRow = new StringGridRowLayout();
        AdvCommandCharacterLayout advCommandCharacter = new AdvCommandCharacterLayout();
        AdvCommandSelectionLayout advCommandSelection = new AdvCommandSelectionLayout();
        AdvCommandJumpLayout advCommandJump = new AdvCommandJumpLayout();
        TextColumnsLayout textColumns = new TextColumnsLayout();
    }

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

    private static String readModuleAsset(String name) throws Exception {
        // 获取模块 APK 路径并读取 assets 目录下的指定文件内容
        try (ZipFile zip = new ZipFile(sModulePath)) {
            
            ZipEntry entry = zip.getEntry("assets/" + name);
            if (entry == null) {
                throw new FileNotFoundException("assets/" + name);
            }

            try (InputStream input = zip.getInputStream(entry);
                ByteArrayOutputStream output = new ByteArrayOutputStream()) {

                byte[] buffer = new byte[4096];
                int n;
                while ((n = input.read(buffer)) != -1) {
                    output.write(buffer, 0, n);
                }

                return output.toString(StandardCharsets.UTF_8.name());
            }
        }
    }

    private static RVA Init_RVA(JSONObject json) throws Exception {
        RVA rva = new RVA();
        JSONObject rva_config = json.getJSONObject("RVA");
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

    private static native void nativeStart(RVA rva, Layout layout);

    @Override
    public void initZygote(StartupParam startupParam) {
        sModulePath = startupParam.modulePath;
        XposedBridge.log("[HousamoTrans] Module path: " + sModulePath);
    }

    @Override
    public void handleLoadPackage(LoadPackageParam lpparam) {
        RVA rva = new RVA();
        Layout layout = new Layout();

        if (!TARGET_PACKAGE.equals(lpparam.packageName)) return;
        if (s_loaded) return; // 防止重复加载（某些情况下 handleLoadPackage 会多调）
        XposedBridge.log("[HousamoTrans] Target package detected, initializing ShadowHook...");

        try {
            // 从模块 APK 的 assets 目录读取 RVA 配置
            String jsonText = readModuleAsset("config.json");
            JSONObject json = new JSONObject(jsonText);
            // 解析 RVA 配置
            rva = Init_RVA(json);
            layout = Init_Layout(json);
        } catch (Exception e) {
            XposedBridge.log("[HousamoTrans] FATAL: Failed to read config.json from assets: " + e.getMessage());
            return;
        }

        try {
        // 初始化 ShadowHook（必须在 loadLibrary 之前）
        ShadowHook.init(new ShadowHook.ConfigBuilder()
                .setMode(ShadowHook.Mode.UNIQUE)
                .build());
        XposedBridge.log("[HousamoTrans] ShadowHook init ok");
        } catch (Throwable t) {
            XposedBridge.log("[HousamoTrans] FATAL: ShadowHook initialization failed: " + t.getMessage());
            return;
        }

        try {
            // 加载 native 库，触发 JNI_OnLoad → 在 native 层设置 hook
            System.loadLibrary("housamo_trans");
            XposedBridge.log("[HousamoTrans] Native library loaded successfully.");
            nativeStart(rva, layout);
            XposedBridge.log("[HousamoTrans] Native hook RVA setup complete.");
            s_loaded = true;
        } catch (UnsatisfiedLinkError e) {
            XposedBridge.log("[HousamoTrans] FATAL: Failed to load housamo_trans.so: " + e.getMessage());
        }
    }
}
