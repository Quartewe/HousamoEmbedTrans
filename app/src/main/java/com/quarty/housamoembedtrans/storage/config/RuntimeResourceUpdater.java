package com.quarty.housamoembedtrans.storage.config;

import android.app.Activity;
import android.app.Application;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.widget.Toast;

import com.quarty.housamoembedtrans.bridge.HetBridgeContract;
import com.quarty.housamoembedtrans.logging.Log;
import com.quarty.housamoembedtrans.util.IoUtils;

import org.json.JSONObject;

import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

/** Checks when the user opens HET, including a warm process already serving the game. */
public final class RuntimeResourceUpdater implements Application.ActivityLifecycleCallbacks {
    private static final String TAG = "HET-RuntimeUpdate";
    private static final String RESOURCE_URL =
        "https://raw.githubusercontent.com/Quartewe/HousamoEmbedTrans/master/app/src/main/assets/runtime.json";
    private final Application application;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final AtomicBoolean checking = new AtomicBoolean();
    private int startedActivities;
    private boolean changingConfiguration;
    private Consumer<String> manualCompletion;

    public RuntimeResourceUpdater(Application application) {
        this.application = application;
    }

    @Override
    public void onActivityStarted(Activity activity) {
        boolean opening = startedActivities++ == 0 && !changingConfiguration;
        changingConfiguration = false;
        if (opening) requestCheck(null);
    }

    /** Called on the UI thread; joins an active startup check instead of downloading twice. */
    public void requestCheck(Consumer<String> completion) {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            throw new IllegalStateException("requestCheck requires the UI thread");
        }
        if (completion != null) manualCompletion = completion;
        if (!checking.compareAndSet(false, true)) return;
        Thread worker = new Thread(() -> {
            String message = null;
            boolean notifyAutomatically = false;
            try {
                message = checkResources();
                notifyAutomatically = message != null;
            } catch (PackageManager.NameNotFoundException missing) {
                Log.i(TAG, "Game is not installed; skipping runtime update");
                message = "未安装游戏，无法校验 RVA 资源版本。";
            } catch (Exception error) {
                Log.w(TAG, "Runtime resource update failed; existing resource retained", error);
                message = "RVA 资源更新未完成：" + error.getMessage();
                notifyAutomatically = true;
            }
            final String result = message;
            final boolean notify = notifyAutomatically;
            main.post(() -> {
                checking.set(false);
                Consumer<String> callback = manualCompletion;
                manualCompletion = null;
                if (callback != null) {
                    callback.accept(result == null ? "RVA 资源版本与已安装游戏版本一致，无需更新。" : result);
                } else if (notify) {
                    showMessage(result);
                }
            });
        }, "HET-runtime-update");
        worker.setDaemon(true);
        worker.start();
    }

    private String checkResources() throws Exception {
        String gameVersion = installedGameVersion();
        ConfigStore store = new ConfigStore(application);
        ConfigStore.JsonLoadResult local = store.loadJson(ConfigStore.RUNTIME_FILE_NAME);
        String localVersion = local.json.getString("GameVersion").trim();
        if (!local.invalidUserOverride && gameVersion.equals(localVersion)) {
            Log.i(TAG, "Runtime matches game version=" + gameVersion);
            return null;
        }
        Log.i(TAG, "Checking remote runtime local=" + localVersion + " game=" + gameVersion);
        JSONObject remote = fetchRuntime();
        String remoteVersion = remote.getString("GameVersion").trim();
        if (!gameVersion.equals(remoteVersion)) {
            throw new IOException("未找到匹配游戏的资源。游戏：" + gameVersion
                + "，本地资源：" + localVersion + "，仓库资源：" + remoteVersion);
        }
        if (!gameVersion.equals(installedGameVersion())) {
            throw new IOException("检查期间游戏版本发生变化，请重新打开 HET");
        }
        if (store.updateRuntimeForGame(remote, gameVersion)) {
            Log.i(TAG, "Runtime updated version=" + gameVersion);
            return "RVA 资源已自动更新至 " + gameVersion + "，请重启游戏使其生效。";
        }
        return null;
    }

    private String installedGameVersion() throws Exception {
        String version = application.getPackageManager().getPackageInfo(
            HetBridgeContract.TARGET_PACKAGE, 0
        ).versionName;
        if (version == null || version.trim().isEmpty()) throw new IOException("无法读取游戏版本号");
        return version.trim();
    }

    private static JSONObject fetchRuntime() throws Exception {
        HttpURLConnection connection = (HttpURLConnection) new URL(RESOURCE_URL).openConnection();
        connection.setConnectTimeout(20_000);
        connection.setReadTimeout(30_000);
        connection.setUseCaches(false);
        connection.setRequestProperty("User-Agent", "HousamoEmbedTrans-RuntimeUpdate");
        try {
            int status = connection.getResponseCode();
            if (status != HttpURLConnection.HTTP_OK) {
                throw new IOException("资源仓库返回 HTTP " + status);
            }
            try (InputStream input = connection.getInputStream()) {
                return new JSONObject(new String(
                    IoUtils.readAllBytesLimited(input, 4 * 1024 * 1024), StandardCharsets.UTF_8
                ));
            }
        } finally {
            connection.disconnect();
        }
    }

    private void showMessage(String message) {
        main.post(() -> {
            if (startedActivities > 0) {
                Toast.makeText(application, message, Toast.LENGTH_LONG).show();
            }
        });
    }

    @Override public void onActivityStopped(Activity activity) {
        startedActivities--;
        changingConfiguration = activity.isChangingConfigurations();
    }
    @Override public void onActivityCreated(Activity activity, Bundle state) { }
    @Override public void onActivityResumed(Activity activity) { }
    @Override public void onActivityPaused(Activity activity) { }
    @Override public void onActivitySaveInstanceState(Activity activity, Bundle state) { }
    @Override public void onActivityDestroyed(Activity activity) { }
}
