package com.quarty.housamoembedtrans.scene.store;

import com.quarty.housamoembedtrans.context.store.SceneContextStore;
import com.quarty.housamoembedtrans.context.review.ReviewTransactionJournal;
import com.quarty.housamoembedtrans.util.IoUtils;
import android.util.AtomicFile;
import org.json.JSONObject;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Iterator;

/** HET-only metadata. Never part of a Scene wire document or Scene export. */
public final class SceneAnnotationStore {
    private final File filesRoot;
    private static final int MAX_BYTES = 1024 * 1024;

    public SceneAnnotationStore(File filesRoot) {
        if (filesRoot == null) throw new IllegalArgumentException("files root is required");
        this.filesRoot = filesRoot;
    }

    public static String relativePath(String scene) {
        if (!SceneStore.isValidSceneName(scene)) throw new IllegalArgumentException("invalid Scene identity");
        return "scenes/.annotations/" + SceneStore.fileNameForScene(scene);
    }

    public JSONObject read(String scene) throws Exception {
        return SceneContextStore.withRootAccess(() -> {
            // Scene detail can be opened without constructing a
            // SceneContextStore. Recover an interrupted Context Review before
            // reading the sidecar so the UI never observes a half-committed
            // annotation/Context relation pair.
            ReviewTransactionJournal.recover(filesRoot);
            return readLocked(scene);
        });
    }

    private JSONObject readLocked(String scene) throws Exception {
        File file = new File(filesRoot, relativePath(scene));
        if (!IoUtils.atomicFileExists(file)) return new JSONObject().put("version", 1)
            .put("scene", scene).put("manual_summaries", new JSONObject());
        byte[] bytes;
        try (InputStream input = new AtomicFile(file).openRead()) {
            bytes = IoUtils.readAllBytesLimited(input, MAX_BYTES);
        }
        JSONObject document = new JSONObject(new String(bytes, StandardCharsets.UTF_8));
        validate(scene, document);
        return document;
    }

    /** Caller owns the Review journal and root access for the entire commit. */
    public void saveInReview(
        SceneStore sceneStore,
        String scene,
        String expected,
        JSONObject document
    ) throws Exception {
        if (sceneStore == null) throw new IllegalArgumentException("Scene store is required");
        if (expected == null || document == null) {
            throw new IllegalArgumentException("expected annotation and document are required");
        }
        validate(scene, document);
        sceneStore.requireSceneAvailableForManagement(scene);
        if (!readLocked(scene).toString().equals(expected)) {
            throw new IOException("Scene annotations changed; reopen the editor before saving");
        }
        byte[] bytes = document.toString().getBytes(StandardCharsets.UTF_8);
        if (bytes.length > MAX_BYTES) throw new IOException("annotation exceeds size limit");
        File file = new File(filesRoot, relativePath(scene));
        IoUtils.ensureDirectory(file.getParentFile());
        IoUtils.writeAtomically(file, bytes);
    }

    public void delete(String scene) throws Exception {
        SceneContextStore.withRootAccess(() -> {
            ReviewTransactionJournal.recover(filesRoot);
            File file = new File(filesRoot, relativePath(scene));
            File backup = new File(file.getPath() + ".bak");
            if (file.exists() && !file.delete()) {
                throw new IOException("could not delete Scene annotation");
            }
            if (backup.exists() && !backup.delete()) {
                throw new IOException("could not delete Scene annotation backup");
            }
            return null;
        });
    }

    private static void validate(String scene, JSONObject document) throws Exception {
        relativePath(scene);
        if (document.getInt("version") != 1 || !scene.equals(document.getString("scene"))) {
            throw new IOException("invalid annotation identity/version");
        }
        JSONObject summaries = document.getJSONObject("manual_summaries");
        Iterator<String> languages = summaries.keys();
        while (languages.hasNext()) {
            String language = languages.next();
            JSONObject summary = summaries.getJSONObject(language);
            if (language.trim().isEmpty() || summary.getString("text").trim().isEmpty()
                || summary.getLong("updated_at") < 0) throw new IOException("invalid manual summary");
        }
    }
}
