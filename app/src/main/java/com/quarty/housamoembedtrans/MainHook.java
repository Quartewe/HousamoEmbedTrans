package com.quarty.housamoembedtrans;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.callbacks.XC_LoadPackage.LoadPackageParam;

/**
 * LSPosed 模块入口 — Housamo AI 实时翻译。
 *
 * 工作流程:
 *   1. LSPosed 在目标应用加载时回调 handleLoadPackage
 *   2. 确认是 Housamo (jp.co.lifewonders.housamo)
 *   3. System.loadLibrary("housamo_trans") → 触发 JNI_OnLoad
 *   4. JNI_OnLoad 中定位 libil2cpp.so → DobbyHook → 翻译管线就绪
 */
public class MainHook implements IXposedHookLoadPackage {

    private static final String TARGET_PACKAGE = "jp.co.lifewonders.housamo";
    private static boolean s_loaded = false;

    @Override
    public void handleLoadPackage(LoadPackageParam lpparam) {
        if (!TARGET_PACKAGE.equals(lpparam.packageName)) return;
        if (s_loaded) return; // 防止重复加载（某些情况下 handleLoadPackage 会多调）
        s_loaded = true;

        XposedBridge.log("[HousamoTrans] Target package detected, loading native library...");

        try {
            System.loadLibrary("housamo_trans");
            XposedBridge.log("[HousamoTrans] Native library loaded successfully.");
        } catch (UnsatisfiedLinkError e) {
            XposedBridge.log("[HousamoTrans] FATAL: Failed to load housamo_trans.so: " + e.getMessage());
        }
    }
}
