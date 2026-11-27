package com.quarty.housamoembedtrans.ui;

import com.quarty.housamoembedtrans.storage.json.JsonSchemaValidator;
import com.quarty.housamoembedtrans.util.IoUtils;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** One import page's cancellable reader of the existing repository resources. */
final class CommunityLibrary {
    // Set this only after a LAH community repository is available.
    private static final String REPO = "";
    private static final String API = "https://api.github.com/repos/" + REPO + "/git/trees/";

    static boolean isConfigured() {
        return !REPO.isEmpty();
    }

    static final class Asset {
        final String path, sha, name;
        final boolean context;

        Asset(String path, String sha, boolean context) {
            this.path = path;
            this.sha = sha;
            this.context = context;
            String file = path.substring(path.lastIndexOf('/') + 1);
            this.name = file.substring(0, file.length() - ".json".length());
        }
    }

    static final class Group {
        final String key, title;
        final Asset context;
        final ManagementImportModel.Document document;
        final List<Asset> scenes;

        Group(String key, String title, Asset context,
              ManagementImportModel.Document document, List<Asset> scenes) {
            this.key = key;
            this.title = title;
            this.context = context;
            this.document = document;
            this.scenes = Collections.unmodifiableList(new ArrayList<>(scenes));
        }
    }

    private final File cache;
    private final JsonSchemaValidator schema;
    private volatile boolean closed;
    private HttpURLConnection active;

    CommunityLibrary(File cacheDirectory, JsonSchemaValidator schema) {
        this.cache = new File(cacheDirectory, "community-translations");
        this.schema = schema;
    }

    List<Group> load() throws Exception {
        if (!isConfigured()) return Collections.emptyList();
        JSONArray root = tree("master");
        String resourceTree = childTree(root, "resource");
        if (resourceTree == null) throw new IOException("资源仓库没有剧情目录");
        JSONArray resources = tree(resourceTree);
        Map<String, Asset> scenes = assets(resources, "resource/", false);
        List<Group> groups = new ArrayList<>();
        Set<String> referenced = new LinkedHashSet<>();
        String contextsTree = childTree(resources, "contexts");
        if (contextsTree != null) {
            for (Asset asset : assets(tree(contextsTree), "resource/contexts/", true).values()) {
                ManagementImportModel.Document document = read(asset);
                JSONObject context = document.records.get(0).document;
                JSONArray entries = context.getJSONArray("scenes");
                LinkedHashMap<String, Asset> available = new LinkedHashMap<>();
                for (int i = 0; i < entries.length(); i++) {
                    JSONObject entry = entries.getJSONObject(i);
                    String name = entry.getString("scene");
                    referenced.add(name);
                    Asset scene = scenes.get(name);
                    if (scene != null) available.put(name, scene);
                }
                groups.add(new Group(asset.path, context.getString("display_name"), asset,
                    document, new ArrayList<>(available.values())));
            }
        }
        List<Asset> other = new ArrayList<>();
        for (Asset scene : scenes.values()) if (!referenced.contains(scene.name)) other.add(scene);
        if (!other.isEmpty()) groups.add(new Group("other", "其他剧情", null, null, other));
        checkOpen();
        return groups;
    }

    ManagementImportModel.Document read(Asset asset) throws Exception {
        checkOpen();
        File saved = new File(cache, asset.sha + ".json");
        byte[] bytes;
        if (saved.isFile()) {
            try (InputStream input = new FileInputStream(saved)) {
                bytes = IoUtils.readAllBytesLimited(input, ManagementTransfer.MAX_DOCUMENT_BYTES);
            }
        } else {
            String url = new URI("https", "raw.githubusercontent.com",
                "/" + REPO + "/master/" + asset.path, null).toASCIIString();
            bytes = fetch(url, ManagementTransfer.MAX_DOCUMENT_BYTES);
        }
        checkOpen();
        ManagementImportModel.Document document = ManagementImportModel.parseDocument(
            asset.path.substring(asset.path.lastIndexOf('/') + 1), bytes, schema);
        String kind = asset.context ? ManagementImportModel.KIND_CONTEXT : ManagementImportModel.KIND_SCENE;
        if (!kind.equals(document.type) || document.records.size() != 1
            || (!asset.context && !asset.name.equals(document.records.get(0).document.optString("scene")))) {
            throw new IOException("资源内容与清单不符：" + asset.name);
        }
        if (!saved.isFile()) save(bytes);
        checkOpen();
        return document;
    }

    private void save(byte[] bytes) throws Exception {
        // Key the actual bytes, not the earlier listing: a concurrent repository
        // update may change this file without requiring a repository-wide snapshot.
        MessageDigest digest = MessageDigest.getInstance("SHA-1");
        digest.update(("blob " + bytes.length + "\0").getBytes(StandardCharsets.UTF_8));
        byte[] hash = digest.digest(bytes);
        StringBuilder key = new StringBuilder();
        for (byte b : hash) key.append(String.format(java.util.Locale.ROOT, "%02x", b & 255));
        if (!cache.isDirectory() && !cache.mkdirs() && !cache.isDirectory()) {
            throw new IOException("无法创建社区译文缓存");
        }
        File saved = new File(cache, key + ".json");
        if (saved.isFile()) return;
        File temporary = File.createTempFile("download-", ".part", cache);
        try {
            try (FileOutputStream output = new FileOutputStream(temporary)) {
                output.write(bytes);
                output.getFD().sync();
            }
            Files.move(temporary.toPath(), saved.toPath(), StandardCopyOption.ATOMIC_MOVE,
                StandardCopyOption.REPLACE_EXISTING);
        } finally {
            temporary.delete();
        }
    }

    private JSONArray tree(String ref) throws Exception {
        JSONObject result = new JSONObject(new String(fetch(API + ref, 4 * 1024 * 1024), StandardCharsets.UTF_8));
        if (result.optBoolean("truncated")) throw new IOException("资源清单不完整，请稍后重试");
        return result.getJSONArray("tree");
    }

    private static String childTree(JSONArray tree, String name) throws Exception {
        for (int i = 0; i < tree.length(); i++) {
            JSONObject entry = tree.getJSONObject(i);
            if (name.equals(entry.getString("path")) && "tree".equals(entry.getString("type")))
                return entry.getString("sha");
        }
        return null;
    }

    private static Map<String, Asset> assets(JSONArray tree, String prefix, boolean context) throws Exception {
        Map<String, Asset> result = new LinkedHashMap<>();
        for (int i = 0; i < tree.length(); i++) {
            JSONObject entry = tree.getJSONObject(i);
            String name = entry.getString("path");
            if (!"blob".equals(entry.getString("type")) || !name.endsWith(".json")) continue;
            Asset asset = new Asset(prefix + name, entry.getString("sha"), context);
            result.put(asset.name, asset);
        }
        return result;
    }

    private byte[] fetch(String url, int limit) throws Exception {
        HttpURLConnection connection;
        synchronized (this) {
            checkOpen();
            connection = (HttpURLConnection) new URL(url).openConnection();
            active = connection;
        }
        connection.setConnectTimeout(20_000);
        connection.setReadTimeout(30_000);
        connection.setUseCaches(false);
        connection.setRequestProperty("User-Agent", "HousamoEmbedTrans-CommunityImport");
        try {
            checkOpen();
            int status = connection.getResponseCode();
            if (status != HttpURLConnection.HTTP_OK) throw new IOException("资源仓库返回 HTTP " + status);
            try (InputStream input = connection.getInputStream()) {
                return IoUtils.readAllBytesLimited(input, limit);
            }
        } finally {
            synchronized (this) { if (active == connection) active = null; }
            connection.disconnect();
        }
    }

    private void checkOpen() throws IOException {
        if (closed || Thread.currentThread().isInterrupted()) throw new IOException("下载已取消");
    }

    void close() {
        HttpURLConnection connection;
        synchronized (this) {
            closed = true;
            connection = active;
        }
        if (connection != null) connection.disconnect();
    }
}
