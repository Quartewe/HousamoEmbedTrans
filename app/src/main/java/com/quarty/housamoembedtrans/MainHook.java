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

    private static final class RVAConfig {
        long initBase = 0;
        long initText = 0;
        long pageTextChange = 0;
        long addSelection = 0;
        long showSelection = 0;
    }

    // Tools
    private static long parseRVA(String value) {
        value = value.trim();

        if (value.startsWith("0x") || value.startsWith("0X")) {
            return Long.parseUnsignedLong(value.substring(2), 16);
        }

        return Long.parseUnsignedLong(value, 10);
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

    private static native void nativeStart(RVAConfig config);

    @Override
    public void initZygote(StartupParam startupParam) {
        sModulePath = startupParam.modulePath;
        XposedBridge.log("[HousamoTrans] Module path: " + sModulePath);
    }

    @Override
    public void handleLoadPackage(LoadPackageParam lpparam) {
        RVAConfig config = new RVAConfig();

        if (!TARGET_PACKAGE.equals(lpparam.packageName)) return;
        if (s_loaded) return; // 防止重复加载（某些情况下 handleLoadPackage 会多调）
        XposedBridge.log("[HousamoTrans] Target package detected, initializing ShadowHook...");

        try {
            // 从模块 APK 的 assets 目录读取 RVA 配置
            String jsonText = readModuleAsset("RVA.json");
            JSONObject json = new JSONObject(jsonText);
            // 解析 RVA 配置
            config.initBase = parseRVA(json.getString("RVA_InitBase"));
            config.initText = parseRVA(json.getString("RVA_InitText"));
            config.pageTextChange = parseRVA(json.getString("RVA_PageTextChange"));
            config.addSelection = parseRVA(json.getString("RVA_AddSelection"));
            config.showSelection = parseRVA(json.getString("RVA_ShowSelection"));
        } catch (Exception e) {
            XposedBridge.log("[HousamoTrans] FATAL: Failed to read RVA.json from assets: " + e.getMessage());
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
            nativeStart(config);
            XposedBridge.log("[HousamoTrans] Native hook RVA setup complete.");
            s_loaded = true;
        } catch (UnsatisfiedLinkError e) {
            XposedBridge.log("[HousamoTrans] FATAL: Failed to load housamo_trans.so: " + e.getMessage());
        }
    }
}
