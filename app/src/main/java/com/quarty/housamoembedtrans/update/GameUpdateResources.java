package com.quarty.housamoembedtrans.update;

import android.content.Context;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.Signature;
import android.os.Build;
import android.os.Bundle;
import android.os.ParcelFileDescriptor;
import android.util.AtomicFile;

import com.quarty.housamoembedtrans.bridge.HetBridgeContract;
import com.quarty.housamoembedtrans.storage.config.ConfigStore;
import com.quarty.housamoembedtrans.storage.config.RuntimeResourceUpdater;
import com.quarty.housamoembedtrans.translation.IGameObbPort;
import com.quarty.housamoembedtrans.util.IoUtils;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InterruptedIOException;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.zip.ZipFile;

/** Release selection and sender-side validation. One instance belongs to one worker operation. */
final class GameUpdateResources implements AutoCloseable {
    static final String PACKAGE = HetBridgeContract.TARGET_PACKAGE;
    private static final String REPO = "quartawa/housamo-obb";
    private static final String API = "https://api.github.com/repos/" + REPO + "/releases/";
    private final Context context;
    private final File directory;
    private volatile boolean canceled;
    private HttpURLConnection active;

    interface Progress { void update(String detail, long received, long total); }

    GameUpdateResources(Context context, File directory) {
        this.context = context;
        this.directory = directory;
    }

    PackageInfo installed() throws Exception {
        return context.getPackageManager().getPackageInfo(PACKAGE, PackageManager.GET_SIGNING_CERTIFICATES);
    }

    static JSONObject identity(PackageInfo info) throws Exception {
        return new JSONObject().put("version", info.versionName).put("code", info.getLongVersionCode())
            .put("signers", signers(info)).put("firstInstallTime", info.firstInstallTime);
    }

    private static String signers(PackageInfo info) throws Exception {
        if (info.signingInfo == null) throw new IOException("无法读取游戏签名");
        Signature[] signatures = info.signingInfo.getApkContentsSigners();
        List<String> hashes = new ArrayList<>();
        for (Signature signature : signatures) hashes.add(hex(MessageDigest.getInstance("SHA-256")
            .digest(signature.toByteArray())));
        if (hashes.isEmpty()) throw new IOException("游戏签名为空");
        java.util.Collections.sort(hashes);
        return String.join(",", hashes);
    }

    boolean matches(JSONObject expected) throws Exception {
        JSONObject current = identity(installed());
        return current.getString("version").equals(expected.getString("version"))
            && current.getLong("code") == expected.getLong("code")
            && current.getString("signers").equals(expected.getString("signers"))
            && (expected.optLong("firstInstallTime") == 0
                || current.getLong("firstInstallTime") == expected.getLong("firstInstallTime"));
    }

    private boolean patched(PackageInfo info) throws IOException {
        try (ZipFile zip = new ZipFile(info.applicationInfo.sourceDir)) {
            return zip.getEntry("assets/lspatch/config.json") != null;
        }
    }

    JSONObject discover(boolean repairOnly) throws Exception {
        PackageInfo current = installed();
        JSONObject source = identity(current);
        boolean isPatched = patched(current);
        JSONObject release;
        boolean upgrade = false;
        if (!repairOnly && isPatched) {
            release = json(API + "latest");
            String latest = releaseVersion(release);
            upgrade = compareVersion(latest, current.versionName) > 0;
            if (!upgrade) release = releaseFor(current.versionName);
        } else {
            release = releaseFor(current.versionName);
        }
        String version = releaseVersion(release);
        JSONArray assets = new JSONArray();
        Set<String> names = new HashSet<>();
        boolean hasApk = false;
        int obbs = 0;
        JSONArray entries = release.getJSONArray("assets");
        for (int i = 0; i < entries.length(); i++) {
            JSONObject item = entries.getJSONObject(i);
            String name = item.getString("name");
            boolean apk = upgrade && name.equals("housamo-" + version + "-lspatch-sign.apk");
            if (!apk && !isObb(name)) continue;
            if (!names.add(name)) throw new IOException("Release 存在重复附件：" + name);
            String digest = item.optString("digest");
            if (!digest.matches("sha256:[0-9a-fA-F]{64}")) {
                throw new IOException("Release 附件缺少 SHA-256：" + name);
            }
            long size = item.getLong("size");
            if (size <= 0) throw new IOException("Release 附件大小无效：" + name);
            assets.put(new JSONObject().put("name", name).put("size", size)
                .put("sha256", digest.substring(7).toLowerCase(java.util.Locale.ROOT))
                .put("apk", apk));
            if (apk) hasApk = true; else obbs++;
        }
        if (obbs == 0 || (upgrade && !hasApk)) throw new IOException("目标 Release 缺少配套 APK 或 OBB");
        // A reused filename must preserve the old game's content until APK installation succeeds.
        if (upgrade) {
            JSONObject oldRelease = releaseFor(current.versionName);
            Map<String, String> oldHashes = new HashMap<>();
            JSONArray oldAssets = oldRelease.getJSONArray("assets");
            for (int i = 0; i < oldAssets.length(); i++) {
                JSONObject a = oldAssets.getJSONObject(i);
                if (isObb(a.getString("name"))) oldHashes.put(a.getString("name"), a.optString("digest"));
            }
            for (int i = 0; i < assets.length(); i++) {
                JSONObject a = assets.getJSONObject(i);
                String oldHash = oldHashes.get(a.getString("name"));
                if (oldHash != null && !oldHash.equalsIgnoreCase("sha256:" + a.getString("sha256"))) {
                    throw new IOException("新旧版本使用同名但内容不同的 OBB，无法预先写入：" + a.getString("name"));
                }
            }
        }
        if (!matches(source)) throw new IOException("检查期间游戏版本发生变化，请重新检查");
        return new JSONObject().put("source", source).put("version", version).put("upgrade", upgrade)
            .put("patched", isPatched).put("assets", assets).put("phase", "READY").put("session", -1);
    }

    private JSONObject releaseFor(String version) throws Exception {
        if (version == null || !version.matches("\\d+\\.\\d+\\.\\d+")) throw new IOException("无法识别游戏版本");
        JSONObject release = json(API + "tags/v" + version);
        if (!version.equals(releaseVersion(release))) throw new IOException("Release 版本与游戏版本不一致");
        return release;
    }

    private static String releaseVersion(JSONObject release) throws Exception {
        String tag = release.getString("tag_name");
        if (release.optBoolean("draft") || release.optBoolean("prerelease")
            || !tag.matches("v\\d+\\.\\d+\\.\\d+")) throw new IOException("没有可用的正式游戏 Release");
        return tag.substring(1);
    }

    private static int compareVersion(String a, String b) throws IOException {
        if (b == null || !b.matches("\\d+\\.\\d+\\.\\d+")) throw new IOException("无法识别当前游戏版本");
        String[] aa = a.split("\\."), bb = b.split("\\.");
        for (int i = 0; i < 3; i++) {
            int order = new java.math.BigInteger(aa[i]).compareTo(new java.math.BigInteger(bb[i]));
            if (order != 0) return order;
        }
        return 0;
    }

    JSONObject prepareRuntime(String version) throws Exception {
        checkCanceled();
        JSONObject runtime = new ConfigStore(context).loadJson(ConfigStore.RUNTIME_FILE_NAME).json;
        if (!version.equals(runtime.optString("GameVersion"))) runtime = RuntimeResourceUpdater.fetchRuntime();
        ConfigStore.validateRuntime(runtime);
        if (!version.equals(runtime.getString("GameVersion"))) throw new IOException("目标游戏的运行时资源尚未发布：" + version);
        checkCanceled();
        return runtime;
    }

    File assetFile(JSONObject asset) throws Exception {
        String name = asset.getString("name");
        if (!isObb(name) && !name.matches("housamo-\\d+\\.\\d+\\.\\d+-lspatch-sign\\.apk")) {
            throw new IOException("无效的更新附件名");
        }
        return new File(directory, name);
    }

    File download(JSONObject plan, JSONObject asset, Progress progress) throws Exception {
        checkCanceled();
        File target = assetFile(asset);
        long expected = asset.getLong("size");
        String hash = asset.getString("sha256");
        if (target.isFile() && target.length() == expected && hash.equals(hash(target))) return target;
        AtomicFile atomic = new AtomicFile(target);
        FileOutputStream out = null;
        HttpURLConnection connection = open("https://github.com/" + REPO + "/releases/download/v"
            + plan.getString("version") + "/" + asset.getString("name"));
        try {
            requireSuccess(connection.getResponseCode());
            out = atomic.startWrite();
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            long received = 0, reported = 0;
            try (InputStream input = connection.getInputStream()) {
                byte[] buffer = new byte[64 * 1024];
                int count;
                while ((count = input.read(buffer)) != -1) {
                    checkCanceled();
                    received += count;
                    if (received > expected) throw new IOException("附件大小与 Release 不一致");
                    out.write(buffer, 0, count);
                    digest.update(buffer, 0, count);
                    long now = android.os.SystemClock.elapsedRealtime();
                    if (now - reported >= 250) {
                        progress.update(asset.getString("name"), received, expected);
                        reported = now;
                    }
                }
            }
            if (received != expected || !hash.equals(hex(digest.digest()))) throw new IOException("附件完整性校验失败，请重新下载");
            atomic.finishWrite(out);
            out = null;
            progress.update(asset.getString("name"), expected, expected);
            return target;
        } finally {
            if (out != null) atomic.failWrite(out);
            disconnect(connection);
        }
    }

    JSONObject validateApk(JSONObject plan, File apk) throws Exception {
        if (!matches(plan.getJSONObject("source"))) throw new IOException("当前游戏已变化，请重新检查更新");
        PackageInfo info = context.getPackageManager().getPackageArchiveInfo(apk.getAbsolutePath(), PackageManager.GET_SIGNING_CERTIFICATES);
        if (info == null || !PACKAGE.equals(info.packageName) || !plan.getString("version").equals(info.versionName)) {
            throw new IOException("APK 包名或版本与目标 Release 不符");
        }
        if (!signers(info).equals(plan.getJSONObject("source").getString("signers"))) {
            throw new SignatureMismatchException();
        }
        if (info.getLongVersionCode() <= plan.getJSONObject("source").getLong("code")) {
            throw new IOException("APK versionCode 没有高于当前游戏");
        }
        if (info.applicationInfo == null || info.applicationInfo.minSdkVersion > Build.VERSION.SDK_INT) {
            throw new IOException("APK 要求更高的 Android 版本");
        }
        if (!Arrays.asList(Build.SUPPORTED_ABIS).contains("arm64-v8a")) throw new IOException("本更新需要 arm64-v8a 设备");
        try (ZipFile zip = new ZipFile(apk)) {
            if (zip.getEntry("lib/arm64-v8a/libil2cpp.so") == null
                || zip.getEntry("assets/lspatch/config.json") == null) throw new IOException("APK 缺少 arm64 游戏库或 LSPatch 配置");
        }
        return identity(info);
    }

    void writeObbs(JSONObject plan, IGameObbPort port, Progress progress) throws Exception {
        if (port == null) throw new GameDisconnectedException();
        JSONObject source = plan.getJSONObject("source");
        if (!matches(source)) throw new IOException("游戏版本发生变化，请重新检查");
        Bundle snapshot = port.inspect();
        if (!source.getString("version").equals(snapshot.getString("version"))) throw new GameDisconnectedException();
        ArrayList<String> existing = snapshot.getStringArrayList("files");
        if (existing == null) throw new IOException("游戏未返回 OBB 文件列表");
        JSONArray assets = plan.getJSONArray("assets");
        for (int i = 0; i < assets.length(); i++) {
            JSONObject asset = assets.getJSONObject(i);
            if (asset.getBoolean("apk")) continue;
            checkCanceled();
            String name = asset.getString("name");
            if (existing.contains(name)) continue;
            File file = assetFile(asset);
            if (file.length() != asset.getLong("size") || !asset.getString("sha256").equals(hash(file))) {
                throw new IOException("本地 OBB 校验失败，请重新准备资源");
            }
            if (!matches(source) || !source.getString("version").equals(port.inspect().getString("version"))) {
                throw new IOException("写入前游戏版本发生变化，请重新检查");
            }
            progress.update(name, -1, -1);
            try (ParcelFileDescriptor fd = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)) {
                port.install(name, fd);
            }
            ArrayList<String> published = port.inspect().getStringArrayList("files");
            if (published == null || !published.contains(name)) throw new IOException("游戏未保存目标 OBB：" + name);
        }
    }

    void requireConnected(IGameObbPort port, JSONObject source) throws Exception {
        if (port == null) throw new GameDisconnectedException();
        if (!matches(source) || !source.getString("version").equals(port.inspect().getString("version"))) {
            throw new GameDisconnectedException();
        }
    }

    private JSONObject json(String url) throws Exception {
        HttpURLConnection connection = open(url);
        try {
            requireSuccess(connection.getResponseCode());
            try (InputStream in = connection.getInputStream()) {
                JSONObject value = new JSONObject(new String(IoUtils.readAllBytesLimited(in, 4 * 1024 * 1024), StandardCharsets.UTF_8));
                checkCanceled();
                return value;
            }
        } finally { disconnect(connection); }
    }

    private synchronized HttpURLConnection open(String url) throws IOException {
        checkCanceled();
        HttpURLConnection connection = (HttpURLConnection) new URL(url).openConnection();
        connection.setConnectTimeout(20_000);
        connection.setReadTimeout(30_000);
        connection.setRequestProperty("User-Agent", "HousamoEmbedTrans-GameUpdate");
        connection.setRequestProperty("Accept-Encoding", "identity");
        active = connection;
        return connection;
    }

    private void disconnect(HttpURLConnection connection) {
        synchronized (this) { if (active == connection) active = null; }
        connection.disconnect();
    }

    private static void requireSuccess(int code) throws IOException {
        if (code != 200) throw new IOException("资源服务器返回 HTTP " + code
            + (code == 404 ? "，尚未发布对应资源" : code == 403 || code == 429 ? "，请稍后重试" : ""));
    }

    private static boolean isObb(String name) {
        return name.matches("(?:main|patch)\\.\\d+\\.jp\\.co\\.lifewonders\\.housamo\\.obb");
    }

    private String hash(File file) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        try (InputStream input = new FileInputStream(file)) {
            byte[] buffer = new byte[64 * 1024];
            int count;
            while ((count = input.read(buffer)) != -1) { checkCanceled(); digest.update(buffer, 0, count); }
        }
        return hex(digest.digest());
    }

    private static String hex(byte[] bytes) {
        StringBuilder result = new StringBuilder(bytes.length * 2);
        for (byte value : bytes) result.append(String.format(java.util.Locale.ROOT, "%02x", value & 255));
        return result.toString();
    }

    void checkCanceled() throws InterruptedIOException {
        if (canceled) throw new InterruptedIOException("下载已取消，已完成的文件可在重试时复用");
    }

    @Override public synchronized void close() {
        canceled = true;
        if (active != null) active.disconnect();
        active = null;
    }

    static final class SignatureMismatchException extends IOException {
        SignatureMismatchException() { super("发布的修补包签名与当前游戏不同，无法覆盖安装。请使用相同签名的修补包。"); }
    }
    static final class GameDisconnectedException extends IOException {
        GameDisconnectedException() { super("请先启动已启用 HET 模块的游戏，连接后继续更新。"); }
    }
}
