package com.quarty.housamoembedtrans;

import android.content.Context;
import android.util.AtomicFile;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Set;

/** Stores schema-valid scene JSON files under the module app's files/scenes directory. */
final class SceneStore {

    static final String DIRECTORY_NAME = "scenes";
    static final String SCHEMA_ASSET_NAME = "scene_schema.json";
    static final int MAX_SCENE_BYTES = 32 * 1024 * 1024;
    private static final int MAX_FILE_NAME_BYTES = 240;
    private static final String DELETED_SCENES_FILE_NAME = "scene_deletions.json";
    private static final String DELETED_SCENES_KEY = "files";
    private static final Object DELETION_LOCK = new Object();
    private static final String TAG = "HET.SceneStore";

    static final class ValidatedScene {
        final String sceneName;
        final String fileName;
        final byte[] bytes;
        final List<String> languages;

        ValidatedScene(
            String sceneName,
            String fileName,
            byte[] bytes,
            List<String> languages
        ) {
            this.sceneName = sceneName;
            this.fileName = fileName;
            this.bytes = bytes;
            this.languages = Collections.unmodifiableList(
                new ArrayList<>(languages)
            );
        }
    }

    static final class SceneInfo {
        final String sceneName;
        final String fileName;
        final List<String> languages;

        SceneInfo(ValidatedScene scene) {
            sceneName = scene.sceneName;
            fileName = scene.fileName;
            languages = scene.languages;
        }
    }

    private final Context context;
    private final File sceneDirectory;
    private final File incomingDirectory;
    private final File deletedScenesFile;
    private final JsonSchemaValidator schemaValidator;

    SceneStore(Context context) {
        this.context = context.getApplicationContext();
        sceneDirectory = new File(this.context.getFilesDir(), DIRECTORY_NAME);
        incomingDirectory = new File(sceneDirectory, ".incoming");
        deletedScenesFile = new File(
            this.context.getFilesDir(),
            DELETED_SCENES_FILE_NAME
        );
        try {
            JSONObject schema = new JSONObject(new String(
                readAll(this.context.getAssets().open(SCHEMA_ASSET_NAME)),
                StandardCharsets.UTF_8
            ));
            schemaValidator = new JsonSchemaValidator(schema);
        } catch (Exception e) {
            throw new IllegalStateException("could not load scene schema", e);
        }
    }

    ValidatedScene importScene(InputStream input) throws Exception {
        ValidatedScene scene = validate(readAll(input));
        save(scene);
        return scene;
    }

    ValidatedScene validate(byte[] sourceBytes) throws Exception {
        if (sourceBytes == null || sourceBytes.length == 0) {
            throw new IllegalArgumentException("scene file is empty");
        }
        if (sourceBytes.length > MAX_SCENE_BYTES) {
            throw new IllegalArgumentException("scene file exceeds 32 MiB");
        }

        String source = new String(sourceBytes, StandardCharsets.UTF_8);
        if (!source.isEmpty() && source.charAt(0) == '\uFEFF') {
            source = source.substring(1);
        }

        JSONObject json = new JSONObject(source);
        schemaValidator.validate(json);

        String sceneName = json.getString("scene");
        if (sceneName.trim().isEmpty()) {
            throw new IllegalArgumentException("$.scene must not be blank");
        }

        String fileName = fileNameForScene(sceneName);
        byte[] normalized = (json.toString(2) + "\n").getBytes(StandardCharsets.UTF_8);
        if (normalized.length > MAX_SCENE_BYTES) {
            throw new IllegalArgumentException("normalized scene file exceeds 32 MiB");
        }
        return new ValidatedScene(
            sceneName,
            fileName,
            normalized,
            collectLanguages(json)
        );
    }

    synchronized void save(ValidatedScene scene) throws IOException {
        ensureDirectories();
        ValidatedScene existing = null;
        try {
            existing = readValidScene(scene.fileName);
        } catch (Exception ignored) {
            // A newly validated import is allowed to repair a corrupt file.
        }
        if (existing != null && !existing.sceneName.equals(scene.sceneName)) {
            throw new IOException(
                "scene name collides with existing " + existing.sceneName
                    + " at " + scene.fileName
            );
        }
        writeAtomically(new File(sceneDirectory, scene.fileName), scene.bytes);
        clearSceneDeletion(scene.fileName);
    }

    synchronized ValidatedScene removeLanguage(String fileName, String language)
        throws Exception {
        if (!isSimpleSceneFileName(fileName)) {
            throw new IllegalArgumentException("invalid scene file name");
        }
        if (language == null || language.isEmpty()) {
            throw new IllegalArgumentException("language key is empty");
        }

        ValidatedScene scene = readValidScene(fileName);
        if (scene == null || isSceneDeleted(fileName)) {
            throw new IOException("scene file no longer exists");
        }

        JSONObject json = new JSONObject(new String(
            scene.bytes,
            StandardCharsets.UTF_8
        ));
        boolean removed = false;
        removed |= removeObjectKey(json.optJSONObject("translated"), language);
        removed |= removeObjectKey(json.optJSONObject("provider"), language);
        removed |= removeObjectKey(json.optJSONObject("model"), language);
        removed |= removeObjectKey(json.optJSONObject("summary"), language);
        removed |= removeTranslationLanguage(json.optJSONArray("scene_items"), language);
        if (!removed) {
            throw new IllegalArgumentException(
                "scene does not contain language key " + language
            );
        }

        ValidatedScene updated = validate(
            (json.toString(2) + "\n").getBytes(StandardCharsets.UTF_8)
        );
        if (!updated.fileName.equals(fileName)) {
            throw new IOException("scene name changed while removing language");
        }
        save(updated);
        return updated;
    }

    synchronized void deleteScene(String fileName) throws IOException {
        if (!isSimpleSceneFileName(fileName)) {
            throw new IllegalArgumentException("invalid scene file name");
        }

        markSceneDeletion(fileName);
        new AtomicFile(new File(sceneDirectory, fileName)).delete();
    }

    synchronized void acceptIncoming(File temporaryFile, String expectedFileName)
        throws Exception {
        try {
            ValidatedScene scene;
            try (InputStream input = new java.io.FileInputStream(temporaryFile)) {
                scene = validate(readAll(input));
            }
            if (!scene.fileName.equals(expectedFileName)) {
                throw new IllegalArgumentException(
                    "scene field maps to " + scene.fileName
                        + ", not requested file " + expectedFileName
                );
            }
            save(scene);
        } finally {
            temporaryFile.delete();
        }
    }

    File createIncomingFile() throws IOException {
        ensureDirectories();
        return File.createTempFile("scene-", ".json.tmp", incomingDirectory);
    }

    List<ValidatedScene> listValidScenes() {
        Set<String> names = candidateFileNames();
        List<String> sortedNames = new ArrayList<>(names);
        Collections.sort(sortedNames);

        List<ValidatedScene> scenes = new ArrayList<>();
        for (String name : sortedNames) {
            try {
                ValidatedScene scene = readValidScene(name);
                if (scene != null) {
                    scenes.add(scene);
                }
            } catch (Exception ignored) {
                // Invalid files are deliberately not exposed or exported.
            }
        }
        return scenes;
    }

    List<SceneInfo> listSceneInfos() {
        Set<String> names = candidateFileNames();
        List<String> sortedNames = new ArrayList<>(names);
        Collections.sort(sortedNames);

        List<SceneInfo> scenes = new ArrayList<>();
        for (String name : sortedNames) {
            try {
                ValidatedScene scene = readValidScene(name);
                if (scene != null) {
                    scenes.add(new SceneInfo(scene));
                }
            } catch (Exception ignored) {
                // Invalid files are deliberately not exposed in the UI.
            }
        }
        return scenes;
    }

    List<String> listValidFileNames() {
        List<SceneInfo> scenes = listSceneInfos();
        List<String> names = new ArrayList<>(scenes.size());
        for (SceneInfo scene : scenes) {
            names.add(scene.fileName);
        }
        return names;
    }

    List<String> listDeletedFileNames() {
        synchronized (DELETION_LOCK) {
            try {
                List<String> names = new ArrayList<>(readDeletedFileNamesLocked());
                Collections.sort(names);
                return names;
            } catch (Exception e) {
                Log.w(TAG, "Could not read scene deletion markers", e);
                return Collections.emptyList();
            }
        }
    }

    File getValidSceneFile(String fileName) {
        if (isSceneDeleted(fileName)) {
            return null;
        }
        try {
            ValidatedScene scene = readValidScene(fileName);
            if (scene == null) {
                return null;
            }
            return new File(sceneDirectory, fileName);
        } catch (Exception ignored) {
            return null;
        }
    }

    private ValidatedScene readValidScene(String fileName) throws Exception {
        if (!isSimpleSceneFileName(fileName)) {
            return null;
        }

        File file = new File(sceneDirectory, fileName);
        AtomicFile atomicFile = new AtomicFile(file);
        if (!file.isFile() && !new File(file.getPath() + ".bak").isFile()) {
            return null;
        }

        ValidatedScene scene;
        try (InputStream input = atomicFile.openRead()) {
            scene = validate(readAll(input));
        }
        return scene.fileName.equals(fileName) ? scene : null;
    }

    private Set<String> candidateFileNames() {
        Set<String> names = new HashSet<>();
        File[] files = sceneDirectory.listFiles();
        if (files == null) {
            return names;
        }

        for (File file : files) {
            String name = file.getName();
            if (file.isFile() && isSimpleSceneFileName(name)) {
                names.add(name);
            } else if (file.isFile() && name.endsWith(".json.bak")) {
                String baseName = name.substring(0, name.length() - 4);
                if (isSimpleSceneFileName(baseName)) {
                    names.add(baseName);
                }
            }
        }
        names.removeAll(new HashSet<>(listDeletedFileNames()));
        return names;
    }

    private boolean isSceneDeleted(String fileName) {
        return listDeletedFileNames().contains(fileName);
    }

    private void markSceneDeletion(String fileName) throws IOException {
        synchronized (DELETION_LOCK) {
            Set<String> names = readDeletedFileNamesLocked();
            if (names.add(fileName)) {
                writeDeletedFileNamesLocked(names);
            }
        }
    }

    private void clearSceneDeletion(String fileName) throws IOException {
        synchronized (DELETION_LOCK) {
            Set<String> names = readDeletedFileNamesLocked();
            if (names.remove(fileName)) {
                writeDeletedFileNamesLocked(names);
            }
        }
    }

    private Set<String> readDeletedFileNamesLocked() throws IOException {
        Set<String> names = new HashSet<>();
        AtomicFile atomicFile = new AtomicFile(deletedScenesFile);
        if (!deletedScenesFile.isFile()
            && !new File(deletedScenesFile.getPath() + ".bak").isFile()) {
            return names;
        }

        try (InputStream input = atomicFile.openRead()) {
            JSONObject root = new JSONObject(new String(
                readAll(input),
                StandardCharsets.UTF_8
            ));
            JSONArray files = root.getJSONArray(DELETED_SCENES_KEY);
            for (int index = 0; index < files.length(); index++) {
                String name = files.getString(index);
                if (isSimpleSceneFileName(name)) {
                    names.add(name);
                }
            }
            return names;
        } catch (Exception e) {
            throw new IOException("could not read scene deletion markers", e);
        }
    }

    private void writeDeletedFileNamesLocked(Set<String> names)
        throws IOException {
        AtomicFile atomicFile = new AtomicFile(deletedScenesFile);
        if (names.isEmpty()) {
            atomicFile.delete();
            return;
        }

        List<String> sortedNames = new ArrayList<>(names);
        Collections.sort(sortedNames);
        JSONArray files = new JSONArray();
        for (String name : sortedNames) {
            files.put(name);
        }

        try {
            byte[] bytes = (new JSONObject()
                .put(DELETED_SCENES_KEY, files)
                .toString(2) + "\n").getBytes(StandardCharsets.UTF_8);
            writeAtomically(deletedScenesFile, bytes);
        } catch (org.json.JSONException e) {
            throw new IOException("could not write scene deletion markers", e);
        }
    }

    private static List<String> collectLanguages(JSONObject json) {
        Set<String> languages = new HashSet<>();
        addObjectKeys(json.optJSONObject("translated"), languages);
        addObjectKeys(json.optJSONObject("provider"), languages);
        addObjectKeys(json.optJSONObject("model"), languages);
        addObjectKeys(json.optJSONObject("summary"), languages);
        collectTranslationLanguages(json.optJSONArray("scene_items"), languages);

        List<String> sorted = new ArrayList<>(languages);
        Collections.sort(sorted);
        return sorted;
    }

    private static void addObjectKeys(JSONObject object, Set<String> output) {
        if (object == null) {
            return;
        }
        Iterator<String> keys = object.keys();
        while (keys.hasNext()) {
            String key = keys.next();
            if (!key.isEmpty()) {
                output.add(key);
            }
        }
    }

    private static void collectTranslationLanguages(
        Object value,
        Set<String> output
    ) {
        if (value instanceof JSONObject) {
            JSONObject object = (JSONObject) value;
            addObjectKeys(object.optJSONObject("translations"), output);
            Iterator<String> keys = object.keys();
            while (keys.hasNext()) {
                collectTranslationLanguages(object.opt(keys.next()), output);
            }
        } else if (value instanceof JSONArray) {
            JSONArray array = (JSONArray) value;
            for (int index = 0; index < array.length(); index++) {
                collectTranslationLanguages(array.opt(index), output);
            }
        }
    }

    private static boolean removeObjectKey(JSONObject object, String key) {
        return object != null && object.remove(key) != null;
    }

    private static boolean removeTranslationLanguage(Object value, String language) {
        boolean removed = false;
        if (value instanceof JSONObject) {
            JSONObject object = (JSONObject) value;
            removed |= removeObjectKey(
                object.optJSONObject("translations"),
                language
            );
            Iterator<String> keys = object.keys();
            while (keys.hasNext()) {
                removed |= removeTranslationLanguage(
                    object.opt(keys.next()),
                    language
                );
            }
        } else if (value instanceof JSONArray) {
            JSONArray array = (JSONArray) value;
            for (int index = 0; index < array.length(); index++) {
                removed |= removeTranslationLanguage(array.opt(index), language);
            }
        }
        return removed;
    }

    private void ensureDirectories() throws IOException {
        if (!sceneDirectory.isDirectory() && !sceneDirectory.mkdirs()) {
            throw new IOException("could not create " + sceneDirectory);
        }
        if (!incomingDirectory.isDirectory() && !incomingDirectory.mkdirs()) {
            throw new IOException("could not create " + incomingDirectory);
        }
    }

    private static void writeAtomically(File file, byte[] bytes) throws IOException {
        AtomicFile atomicFile = new AtomicFile(file);
        FileOutputStream output = null;
        try {
            output = atomicFile.startWrite();
            output.write(bytes);
            atomicFile.finishWrite(output);
        } catch (IOException e) {
            if (output != null) {
                atomicFile.failWrite(output);
            }
            throw e;
        }
    }

    static String fileNameForScene(String sceneName) {
        byte[] utf8 = sceneName.getBytes(StandardCharsets.UTF_8);
        StringBuilder safe = new StringBuilder(utf8.length + 5);
        for (byte raw : utf8) {
            int value = raw & 0xff;
            boolean allowed = (value >= 'a' && value <= 'z')
                || (value >= 'A' && value <= 'Z')
                || (value >= '0' && value <= '9')
                || value == '_'
                || value == '-'
                || value == '.';
            safe.append(allowed ? (char) value : '_');
        }
        safe.append(".json");
        if (safe.length() > MAX_FILE_NAME_BYTES) {
            throw new IllegalArgumentException("scene-derived file name is too long");
        }
        return safe.toString();
    }

    static boolean isSimpleSceneFileName(String fileName) {
        if (fileName == null
            || !fileName.endsWith(".json")
            || fileName.length() > MAX_FILE_NAME_BYTES) {
            return false;
        }
        for (int index = 0; index < fileName.length(); index++) {
            char value = fileName.charAt(index);
            boolean allowed = (value >= 'a' && value <= 'z')
                || (value >= 'A' && value <= 'Z')
                || (value >= '0' && value <= '9')
                || value == '_'
                || value == '-'
                || value == '.';
            if (!allowed) {
                return false;
            }
        }
        return true;
    }

    static byte[] readAll(InputStream input) throws IOException {
        try (InputStream source = input;
             ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[8192];
            int read;
            int total = 0;
            while ((read = source.read(buffer)) != -1) {
                total += read;
                if (total > MAX_SCENE_BYTES) {
                    throw new IOException("scene file exceeds 32 MiB");
                }
                output.write(buffer, 0, read);
            }
            return output.toByteArray();
        }
    }
}
