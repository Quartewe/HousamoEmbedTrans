package com.quarty.housamoembedtrans;

import android.app.Application;
import android.content.Context;

import com.quarty.housamoembedtrans.bridge.HetBridgeContract;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.IXposedHookZygoteInit;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage.LoadPackageParam;

/** Framework adapter; the shared game runtime has no Xposed dependency. */
public final class XposedEntry implements IXposedHookLoadPackage, IXposedHookZygoteInit {
    private String modulePath;
    private boolean installed;

    @Override public void initZygote(StartupParam startupParam) {
        modulePath = startupParam.modulePath;
    }

    @Override public void handleLoadPackage(LoadPackageParam param) {
        if (installed || !HetBridgeContract.TARGET_PACKAGE.equals(param.packageName)
            || !HetBridgeContract.TARGET_PACKAGE.equals(param.processName)) return;
        installed = true;
        XposedHelpers.findAndHookMethod(Application.class, "attach", Context.class,
            new XC_MethodHook() {
                @Override protected void afterHookedMethod(MethodHookParam hook) {
                    if (!hook.hasThrowable()) {
                        MainHook.start((Application) hook.thisObject, modulePath, "lsp", false);
                    }
                }
            });
    }
}
