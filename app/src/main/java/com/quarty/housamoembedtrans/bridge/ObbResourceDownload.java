package com.quarty.housamoembedtrans.bridge;

import android.content.Context;
import android.net.Uri;
import android.os.Bundle;
import android.os.ParcelFileDescriptor;

import com.quarty.housamoembedtrans.logging.Log;
import com.quarty.housamoembedtrans.translation.IGameObbPort;
import com.quarty.housamoembedtrans.util.IoUtils;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InterruptedIOException;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/** One user-requested check/download. Owns temporary HET files and HTTP cancellation. */
public final class ObbResourceDownload implements AutoCloseable {
    public interface ProgressListener {
        void onProgress(String message, long received, long total);
    }

    private static final String REPOSITORY = "quartawa/housamo-obb";
    private final File cacheDirectory;
    private volatile boolean canceled;
    private HttpURLConnection active;

    public ObbResourceDownload(Context context) {
        cacheDirectory = context.getCacheDir();
    }

    public String run(IGameObbPort port, ProgressListener progress) throws Exception {
        if (port == null) {
            throw new IOException("游戏未连接，或游戏中的 HET 模块尚未更新。请先启动游戏并启用模块，再重试。");
        }
        checkCanceled();
        progress.onProgress("正在读取游戏版本和 OBB 目录…", 0, -1);
        Bundle snapshot = port.inspect();
        String version = snapshot.getString("version");
        String tag = "v" + version;
        progress.onProgress("游戏 " + version + "，正在查找 OBB Release " + tag + "…", 0, -1);
        JSONObject release;
        HttpURLConnection connection = open(
            "https://api.github.com/repos/" + REPOSITORY + "/releases/tags/" + Uri.encode(tag)
        );
        try {
            int status = connection.getResponseCode();
            if (status == 404) throw new IOException("尚未发布与游戏版本对应的 OBB Release：" + tag);
            requireSuccess(status);
            try (InputStream input = connection.getInputStream()) {
                release = new JSONObject(new String(
                    IoUtils.readAllBytesLimited(input, 4 * 1024 * 1024), StandardCharsets.UTF_8
                ));
            }
        } finally {
            disconnect(connection);
        }
        String releaseTag = release.getString("tag_name");
        requireMatchingVersion(version, releaseTag);
        if (release.optBoolean("draft")) throw new IOException("OBB Release 尚未发布");
        List<String> assets = new ArrayList<>();
        Set<String> names = new HashSet<>();
        JSONArray entries = release.getJSONArray("assets");
        for (int index = 0; index < entries.length(); index++) {
            String name = entries.getJSONObject(index).getString("name");
            if (name.matches("(?:main|patch)\\.\\d+\\.jp\\.co\\.lifewonders\\.housamo\\.obb")) {
                if (!names.add(name)) throw new IOException("Release 中存在重复 OBB 附件");
                assets.add(name);
            }
        }
        if (assets.isEmpty()) throw new IOException("Release " + tag + " 没有原始 OBB 附件");
        ArrayList<String> existing = snapshot.getStringArrayList("files");
        int installed = 0;
        for (String name : assets) {
            checkCanceled();
            if (existing.contains(name)) continue;
            File temporary = File.createTempFile("het-obb-", ".part", cacheDirectory);
            try {
                download(tag, name, temporary, progress);
                checkCanceled();
                // Recheck the game version before publishing; no OBB version is persisted.
                Bundle current = port.inspect();
                requireMatchingVersion(current.getString("version"), releaseTag);
                checkCanceled();
                progress.onProgress("正在保存到游戏 OBB 目录：" + name, 0, -1);
                try (ParcelFileDescriptor descriptor = ParcelFileDescriptor.open(
                    temporary, ParcelFileDescriptor.MODE_READ_ONLY
                )) {
                    if (port.install(name, descriptor)) installed++;
                }
                Log.i("HET-OBB", "Resource saved release=" + tag + " asset=" + name);
            } finally {
                if (!temporary.delete()) {
                    Log.w("HET-OBB", "Could not remove download cache: " + temporary.getName());
                }
            }
        }
        checkCanceled();
        return "版本校验通过：游戏 " + version
            + "、OBB Release " + releaseTag + " 一致。\n"
            + (installed == 0
                ? "所需 OBB 文件均已存在，无需下载。"
                : "已补齐 " + installed + " 个 OBB 文件。请完全退出并重新启动游戏。")
            + "\n仅检查版本与文件是否存在，未校验文件内容。";
    }

    /** OBB version is its Release tag; the numeric OBB filename is not a versionName. */
    private static void requireMatchingVersion(String game, String release)
        throws IOException {
        if (!("v" + game).equals(release)) {
            throw new IOException("版本不一致，已停止 OBB 处理：\n游戏：" + game
                + "\nOBB Release：" + release);
        }
    }

    private void download(String tag, String name, File target, ProgressListener progress)
        throws IOException {
        progress.onProgress("正在下载：" + name, 0, -1);
        Log.i("HET-OBB", "Download started release=" + tag + " asset=" + name);
        HttpURLConnection connection = open(
            "https://github.com/" + REPOSITORY + "/releases/download/"
                + Uri.encode(tag) + "/" + Uri.encode(name)
        );
        try {
            requireSuccess(connection.getResponseCode());
            long expected = connection.getContentLengthLong();
            progress.onProgress("正在下载：" + name, 0, expected);
            long received = 0;
            long lastProgress = 0;
            try (InputStream input = connection.getInputStream();
                 FileOutputStream output = new FileOutputStream(target)) {
                byte[] buffer = new byte[64 * 1024];
                int count;
                while ((count = input.read(buffer)) != -1) {
                    checkCanceled();
                    output.write(buffer, 0, count);
                    received += count;
                    long now = android.os.SystemClock.elapsedRealtime();
                    if (now - lastProgress >= 500) {
                        reportDownload(progress, name, received, expected);
                        lastProgress = now;
                    }
                }
            }
            reportDownload(progress, name, received, expected);
            // Transport completion only; no hash, OBB structure or content validation.
            if (expected >= 0 && received != expected) {
                throw new IOException("下载连接提前结束，请重试");
            }
        } finally {
            disconnect(connection);
        }
    }

    private static void reportDownload(ProgressListener progress, String name,
                                       long received, long total) {
        progress.onProgress(String.format(Locale.ROOT,
            "正在下载：%s\n%.1f MB%s", name, received / 1048576.0,
            total > 0 ? String.format(Locale.ROOT, " / %.1f MB", total / 1048576.0) : ""),
            received, total);
    }
    private synchronized HttpURLConnection open(String url) throws IOException {
        checkCanceled();
        HttpURLConnection connection = (HttpURLConnection) new URL(url).openConnection();
        connection.setConnectTimeout(20_000);
        connection.setReadTimeout(60_000);
        connection.setRequestProperty("User-Agent", "HousamoEmbedTrans-OBB");
        connection.setRequestProperty("Accept-Encoding", "identity");
        active = connection;
        return connection;
    }

    private void disconnect(HttpURLConnection connection) {
        synchronized (this) {
            if (active == connection) active = null;
        }
        connection.disconnect();
    }

    private static void requireSuccess(int status) throws IOException {
        if (status != HttpURLConnection.HTTP_OK) {
            throw new IOException("资源服务器返回 HTTP " + status
                + (status == 403 || status == 429 ? "，可能触发访问限额，请稍后重试" : ""));
        }
    }

    private void checkCanceled() throws InterruptedIOException {
        if (canceled || Thread.currentThread().isInterrupted()) {
            throw new InterruptedIOException("资源检查已取消");
        }
    }

    @Override
    public void close() {
        HttpURLConnection connection;
        synchronized (this) {
            canceled = true;
            connection = active;
            active = null;
        }
        if (connection != null) connection.disconnect();
    }
}
