package com.quarty.housamoembedtrans.update;

import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageInstaller;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.util.AtomicFile;

import com.quarty.housamoembedtrans.bridge.TranslationJobControlClient;
import com.quarty.housamoembedtrans.logging.Log;
import com.quarty.housamoembedtrans.storage.config.ConfigStore;
import com.quarty.housamoembedtrans.translation.TranslationService;
import com.quarty.housamoembedtrans.translation.IGameObbPort;
import com.quarty.housamoembedtrans.util.IoUtils;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CopyOnWriteArraySet;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Owns one user-requested update. Plan, files and installer callbacks are serialized on worker.
 * Activities subscribe on main and never own the operation. No translation queue or game state is used.
 * Durable boundaries: prepared plan -> OBB published -> session recorded -> commit -> runtime saved.
 */
public final class GameUpdateController {
    public enum Phase { CHECKING, READY, DOWNLOADING, WRITING_OBB, INSTALLING, CONFIRM_INSTALL,
        READY_INSTALL, RUNTIME, COMPLETE, ERROR, NEED_GAME, SIGNATURE, PERMISSION, CANCELED }

    public static final class Snapshot {
        public final Phase phase;
        public final String source, target, detail;
        public final boolean upgrade, patched, busy, hasPlan, runtimePending;
        public final long received, total, downloadSize;
        public final int session;
        Snapshot(Phase phase, String detail, JSONObject plan, boolean busy, long received, long total) {
            this.phase = phase;
            this.detail = detail;
            this.busy = busy;
            this.received = received;
            this.total = total;
            hasPlan = plan != null;
            runtimePending = plan != null && "RUNTIME".equals(plan.optString("phase"));
            JSONObject original = plan == null ? null : plan.optJSONObject("source");
            source = original == null ? "" : original.optString("version");
            target = plan == null ? "" : plan.optString("version");
            upgrade = plan != null && plan.optBoolean("upgrade");
            patched = plan != null && plan.optBoolean("patched");
            session = plan == null ? -1 : plan.optInt("session", -1);
            long bytes = 0;
            JSONArray assets = plan == null ? null : plan.optJSONArray("assets");
            if (assets != null) for (int i = 0; i < assets.length(); i++) bytes += assets.optJSONObject(i).optLong("size");
            downloadSize = bytes;
        }
    }

    public interface Listener { void changed(Snapshot snapshot); }
    private interface Work { void run(GameUpdateResources resources) throws Exception; }
    private static GameUpdateController instance;
    public static synchronized GameUpdateController get(Context context) {
        if (instance == null) instance = new GameUpdateController(context.getApplicationContext());
        return instance;
    }

    private final Context context;
    private final File directory;
    private final AtomicFile record;
    private final ExecutorService worker = Executors.newSingleThreadExecutor(r -> new Thread(r, "HET-game-update"));
    private final Handler main = new Handler(Looper.getMainLooper());
    private final CopyOnWriteArraySet<Listener> listeners = new CopyOnWriteArraySet<>();
    private final AtomicBoolean busy = new AtomicBoolean();
    private volatile GameUpdateResources activeResources;
    private volatile Snapshot snapshot = new Snapshot(Phase.CHECKING, "正在读取游戏更新状态…", null, false, -1, -1);
    // The following fields are worker-owned, except confirmation which is handed off on main.
    private JSONObject plan;
    private boolean loaded;
    private volatile Intent confirmation;

    private GameUpdateController(Context context) {
        this.context = context;
        directory = new File(context.getNoBackupFilesDir(), "game-update");
        record = new AtomicFile(new File(directory, "state.json"));
    }

    public Snapshot snapshot() { return snapshot; }
    public void addListener(Listener listener) { listeners.add(listener); listener.changed(snapshot); }
    public void removeListener(Listener listener) { listeners.remove(listener); }

    /** The Activity takes the platform Intent only while resumed. It is never launched in background. */
    public Intent takeConfirmation() {
        if (Looper.myLooper() != Looper.getMainLooper()) throw new IllegalStateException("UI thread required");
        Intent intent = confirmation;
        confirmation = null;
        return intent;
    }

    public void open() { submit(this::restore); }

    /** Closing a completed operation makes the next visit check for a newer release. */
    public void dismissCompleted() {
        worker.execute(() -> {
            try {
                load();
                if (plan != null && "COMPLETE".equals(plan.optString("phase"))) {
                    record.delete();
                    plan = null;
                    publish(Phase.CHECKING, "正在读取游戏更新状态…");
                }
            } catch (Exception error) { failed(error); }
        });
    }

    public void checkAgain(boolean repairCurrent) {
        submit(resources -> {
            load();
            if (plan != null && plan.optInt("session", -1) >= 0
                && installer().getSessionInfo(plan.getInt("session")) != null) {
                throw new IOException("已有安装会话，请先完成或取消系统安装");
            }
            publish(Phase.CHECKING, "正在检查游戏版本与配套资源…");
            JSONObject next = resources.discover(repairCurrent);
            plan = next;
            save();
            clearUnusedDownloads();
            ready();
        });
    }

    public void continueUpdate() {
        submit(resources -> {
            load();
            if (plan == null) { restore(resources); return; }
            if (targetInstalled(resources) && plan.optBoolean("obbReady")) { finishRuntime(resources); return; }
            if (!resources.matches(plan.getJSONObject("source"))) throw new IOException("游戏安装已变化，请重新检查更新");
            if (plan.optBoolean("upgrade") && !context.getPackageManager().canRequestPackageInstalls()) {
                publish(Phase.PERMISSION, "请允许 HET 安装应用，返回后继续。");
                return;
            }
            if (!plan.optBoolean("obbReady")) prepare(resources);
            if (plan.getBoolean("upgrade")) install(resources); else finishRuntime(resources);
        });
    }

    /** Only downloads are cancelable. OBB publish and PackageInstaller commit have separate boundaries. */
    public void cancelDownload() {
        Snapshot current = snapshot;
        GameUpdateResources resources = activeResources;
        if (resources != null && current.phase == Phase.DOWNLOADING) resources.close();
    }

    private void submit(Work action) {
        if (!busy.compareAndSet(false, true)) return;
        worker.execute(() -> {
            try (GameUpdateResources resources = new GameUpdateResources(context, directory)) {
                activeResources = resources;
                action.run(resources);
            } catch (Exception error) {
                failed(error);
            } finally {
                activeResources = null;
                busy.set(false);
                republish();
            }
        });
    }

    private void load() throws Exception {
        if (loaded) return;
        if (!directory.isDirectory() && !directory.mkdirs()) throw new IOException("无法创建更新资源目录");
        if (record.getBaseFile().exists() || new File(directory, "state.json.bak").exists()) {
            try (InputStream input = record.openRead()) {
                plan = new JSONObject(new String(IoUtils.readAllBytesLimited(input, 8 * 1024 * 1024), StandardCharsets.UTF_8));
            }
        }
        loaded = true;
    }

    private void save() throws Exception {
        FileOutputStream output = null;
        try {
            output = record.startWrite();
            output.write(plan.toString().getBytes(StandardCharsets.UTF_8));
            record.finishWrite(output);
        } catch (Exception error) {
            if (output != null) record.failWrite(output);
            throw error;
        }
    }

    private void restore(GameUpdateResources resources) throws Exception {
        load();
        if (plan == null) {
            publish(Phase.CHECKING, "正在检查游戏版本与配套资源…");
            plan = resources.discover(false);
            save();
            ready();
            return;
        }
        if (plan.optBoolean("obbReady") && targetInstalled(resources)) {
            if ("COMPLETE".equals(plan.optString("phase"))) {
                publish(Phase.COMPLETE, completionMessage());
            } else {
                finishRuntime(resources);
            }
            return;
        }
        if (!resources.matches(plan.getJSONObject("source"))) {
            publish(Phase.ERROR, "游戏安装已变化，请重新检查更新。");
            return;
        }
        int id = plan.optInt("session", -1);
        if (id >= 0 && installer().getSessionInfo(id) != null) {
            publish(confirmation != null ? Phase.CONFIRM_INSTALL : Phase.INSTALLING,
                "安装会话尚未完成。返回系统安装界面完成确认；也可重新发起安装，复用已准备的资源。");
        } else if (plan.optBoolean("obbReady") && plan.optBoolean("upgrade")) {
            plan.put("session", -1);
            save();
            publish(Phase.READY_INSTALL, "新版 OBB 已保存，可以继续安装游戏。");
        } else if ("SIGNATURE".equals(plan.optString("phase"))) {
            publish(Phase.SIGNATURE, plan.optString("error"));
        } else {
            ready();
        }
    }

    private void ready() {
        boolean upgrade = plan.optBoolean("upgrade");
        publish(Phase.READY, upgrade ? "资源准备完成后，将打开系统安装界面。下载后校验 APK 签名。"
            : plan.optBoolean("patched") ? "检查当前版本 OBB，并匹配运行时资源。"
            : "原版游戏通过应用商店更新；这里可以补齐当前版本 OBB 和运行时资源。");
    }

    private void prepare(GameUpdateResources resources) throws Exception {
        try (TranslationJobControlClient client = new TranslationJobControlClient(context)) {
            client.bind();
            if (!client.awaitConnected(10_000)) throw new GameUpdateResources.GameDisconnectedException();
            IGameObbPort port = client.getGameObbPort();
            resources.requireConnected(port, plan.getJSONObject("source"));
            java.util.ArrayList<String> existing = port.inspect().getStringArrayList("files");
            if (existing == null) throw new IOException("游戏未返回 OBB 文件列表");
            publish(Phase.DOWNLOADING, "正在准备匹配的运行时资源…");
            // Save the exact runtime selected for this target before touching game resources.
            if (!plan.has("runtime")) plan.put("runtime", resources.prepareRuntime(plan.getString("version")));
            save();
            JSONArray assets = plan.getJSONArray("assets");
            // Validate the APK before spending time on OBB downloads or publishing anything to the game.
            for (int pass = 0; pass < 2; pass++) for (int i = 0; i < assets.length(); i++) {
                JSONObject asset = assets.getJSONObject(i);
                if (asset.getBoolean("apk") != (pass == 0)) continue;
                if (!asset.getBoolean("apk") && existing.contains(asset.getString("name"))) continue;
                File file = resources.download(plan, asset,
                    (detail, received, total) -> publish(Phase.DOWNLOADING, detail, received, total));
                if (asset.getBoolean("apk")) { plan.put("target", resources.validateApk(plan, file)); save(); }
            }
            resources.checkCanceled();
            // After this transition cancellation no longer interrupts the game's file publication.
            publish(Phase.WRITING_OBB, "正在将新版 OBB 保存到游戏目录…");
            resources.writeObbs(plan, port,
                (detail, received, total) -> publish(Phase.WRITING_OBB, detail));
            plan.put("obbReady", true).put("phase", "READY_INSTALL");
            save();
        }
    }

    private void install(GameUpdateResources resources) throws Exception {
        publish(Phase.INSTALLING, "正在准备系统安装会话…");
        JSONObject apkAsset = null;
        JSONArray assets = plan.getJSONArray("assets");
        for (int i = 0; i < assets.length(); i++) {
            if (assets.getJSONObject(i).getBoolean("apk")) apkAsset = assets.getJSONObject(i);
        }
        if (apkAsset == null) throw new IOException("更新记录缺少 APK");
        File apk = resources.download(plan, apkAsset, (detail, received, total) ->
            publish(Phase.INSTALLING, "正在核对安装包：" + detail, received, total));
        JSONObject target = resources.validateApk(plan, apk);
        plan.put("target", target);
        int oldId = plan.optInt("session", -1);
        // Only a user's explicit continue action replaces an interrupted installer session.
        if (oldId >= 0 && installer().getSessionInfo(oldId) != null) installer().abandonSession(oldId);
        confirmation = null;
        PackageInstaller.SessionParams params = new PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL);
        params.setAppPackageName(GameUpdateResources.PACKAGE);
        params.setSize(apk.length());
        if (Build.VERSION.SDK_INT >= 31) params.setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_REQUIRED);
        int id = installer().createSession(params);
        try {
            plan.put("session", id).put("phase", "INSTALLING");
            save();
            try (PackageInstaller.Session session = installer().openSession(id)) {
                try (InputStream input = new FileInputStream(apk); OutputStream output = session.openWrite("base.apk", 0, apk.length())) {
                    byte[] buffer = new byte[64 * 1024];
                    int count;
                    while ((count = input.read(buffer)) != -1) output.write(buffer, 0, count);
                    session.fsync(output);
                }
                if (!resources.matches(plan.getJSONObject("source"))) throw new IOException("提交安装前游戏版本发生变化");
                Intent callback = new Intent(context, GameUpdateInstallReceiver.class)
                    .setAction("com.quarty.housamoembedtrans.GAME_UPDATE_RESULT")
                    .setData(Uri.parse("het-game-update://session/" + id));
                int flags = PendingIntent.FLAG_UPDATE_CURRENT;
                if (Build.VERSION.SDK_INT >= 31) flags |= PendingIntent.FLAG_MUTABLE;
                session.commit(PendingIntent.getBroadcast(context, id, callback, flags).getIntentSender());
            }
            publish(Phase.INSTALLING, "等待系统安装确认…");
        } catch (Exception error) {
            installer().abandonSession(id);
            plan.put("session", -1).put("phase", "READY_INSTALL");
            save();
            throw error;
        }
    }

    void installResult(Intent intent, Runnable finished) {
        worker.execute(() -> {
            boolean priorBusy = busy.getAndSet(true);
            try (GameUpdateResources resources = new GameUpdateResources(context, directory)) {
                load();
                if (plan == null || intent.getData() == null) return;
                int id = Integer.parseInt(intent.getData().getLastPathSegment());
                if (id != plan.optInt("session", -1)) return;
                int status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE);
                if (status == PackageInstaller.STATUS_PENDING_USER_ACTION) {
                    Intent next = intent.getParcelableExtra(Intent.EXTRA_INTENT);
                    if (next == null) throw new IOException("系统未提供安装确认界面");
                    plan.put("phase", "CONFIRM_INSTALL");
                    save();
                    main.post(() -> { confirmation = next; notifyListeners(); });
                    publish(Phase.CONFIRM_INSTALL, "请在系统界面确认更新游戏。");
                } else if (status == PackageInstaller.STATUS_SUCCESS) {
                    if (!targetInstalled(resources)) throw new IOException("安装回调成功，但游戏身份与目标版本不一致");
                    plan.put("session", -1).put("phase", "RUNTIME");
                    save();
                    finishRuntime(resources);
                } else {
                    confirmation = null;
                    plan.put("session", -1).put("phase", "READY_INSTALL");
                    save();
                    publish(Phase.READY_INSTALL, status == PackageInstaller.STATUS_FAILURE_ABORTED
                        ? "安装已取消。旧游戏和资源保留，可以继续安装。"
                        : "安装未完成：" + intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE));
                }
            } catch (Exception error) { failed(error); }
            finally { busy.set(priorBusy); republish(); finished.run(); }
        });
    }

    private boolean targetInstalled(GameUpdateResources resources) throws Exception {
        if (plan.optBoolean("upgrade")) return plan.has("target") && resources.matches(plan.getJSONObject("target"));
        return resources.matches(plan.getJSONObject("source"));
    }

    private void finishRuntime(GameUpdateResources resources) throws Exception {
        publish(Phase.RUNTIME, "正在匹配运行时资源…");
        if (!targetInstalled(resources)) throw new IOException("当前游戏与目标安装信息不一致");
        if (!plan.has("runtime")) plan.put("runtime", resources.prepareRuntime(plan.getString("version")));
        plan.put("phase", "RUNTIME");
        save();
        new ConfigStore(context).updateRuntimeForGame(plan.getJSONObject("runtime"), plan.getString("version"));
        if (!targetInstalled(resources)) throw new IOException("保存运行时资源期间游戏版本发生变化，请重新检查");
        main.post(TranslationService::announceOnlineIfRunning);
        plan.put("phase", "COMPLETE").put("session", -1);
        save();
        clearDownloadedAssets();
        publish(Phase.COMPLETE, completionMessage());
    }

    private String completionMessage() {
        return "游戏、OBB 与运行时资源已就绪。请完全退出并重新启动游戏。\n下载文件已校验 SHA-256；游戏目录中的已有 OBB 仅检查是否存在。";
    }

    private void failed(Exception error) {
        Log.w("HET-GameUpdate", "Game update did not finish", error);
        Phase phase = error instanceof GameUpdateResources.SignatureMismatchException ? Phase.SIGNATURE
            : error instanceof GameUpdateResources.GameDisconnectedException ? Phase.NEED_GAME
            : error instanceof java.io.InterruptedIOException ? Phase.CANCELED : Phase.ERROR;
        String message = error.getMessage() == null ? error.getClass().getSimpleName() : error.getMessage();
        if (error instanceof android.content.pm.PackageManager.NameNotFoundException) message = "尚未安装游戏，请先安装游戏并启用 HET 模块。";
        if (plan != null && "RUNTIME".equals(plan.optString("phase"))) {
            message = "游戏和 OBB 已就绪，运行时资源尚未完成：" + message;
        }
        try {
            if (plan != null) {
                plan.put("error", message);
                if (phase == Phase.SIGNATURE) plan.put("phase", "SIGNATURE");
                save();
            }
        } catch (Exception saveError) { Log.w("HET-GameUpdate", "Could not save failure state", saveError); }
        publish(phase, message);
    }

    private PackageInstaller installer() { return context.getPackageManager().getPackageInstaller(); }

    private void clearUnusedDownloads() throws Exception {
        java.util.Set<String> keep = new java.util.HashSet<>();
        JSONArray assets = plan.getJSONArray("assets");
        for (int i = 0; i < assets.length(); i++) keep.add(assets.getJSONObject(i).getString("name"));
        File[] files = directory.listFiles();
        if (files != null) for (File file : files) {
            String name = file.getName();
            if (name.endsWith(".new") || name.endsWith(".bak")) name = name.substring(0, name.length() - 4);
            if ((name.endsWith(".apk") || name.endsWith(".obb")) && !keep.contains(name)) file.delete();
        }
    }

    private void clearDownloadedAssets() throws Exception {
        JSONArray assets = plan.getJSONArray("assets");
        try (GameUpdateResources resources = new GameUpdateResources(context, directory)) {
            for (int i = 0; i < assets.length(); i++) new AtomicFile(resources.assetFile(assets.getJSONObject(i))).delete();
        }
    }

    private void publish(Phase phase, String detail) { publish(phase, detail, -1, -1); }
    private void publish(Phase phase, String detail, long received, long total) {
        snapshot = new Snapshot(phase, detail, plan, busy.get(), received, total);
        main.post(this::notifyListeners);
    }
    private void republish() { publish(snapshot.phase, snapshot.detail, snapshot.received, snapshot.total); }
    private void notifyListeners() { for (Listener listener : listeners) listener.changed(snapshot); }
}
