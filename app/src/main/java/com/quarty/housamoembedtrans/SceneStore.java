package com.quarty.housamoembedtrans;

import android.content.Context;
import android.util.AtomicFile;

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
import java.util.List;
import java.util.Set;

/** Stores schema-valid scene JSON files under the module app's files/scenes directory. */
final class SceneStore {

    static final String DIRECTORY_NAME = "scenes";
    static final String SCHEMA_ASSET_NAME = "scene_schema.json";
    static final int MAX_SCENE_BYTES = 32 * 1024 * 1024;
    private static final int MAX_FILE_NAME_BYTES = 240;

    static final class ValidatedScene {
        final String sceneName;
        final String fileName;
        final byte[] bytes;

        ValidatedScene(String sceneName, String fileName, byte[] bytes) {
            this.sceneName = sceneName;
            this.fileName = fileName;
            this.bytes = bytes;
        }
    }

    private final Context context;
    private final File sceneDirectory;
    private final File incomingDirectory;
    private final JsonSchemaValidator schemaValidator;

    SceneStore(Context context) {
        this.context = context.getApplicationContext();
        sceneDirectory = new File(this.context.getFilesDir(), DIRECTORY_NAME);
        incomingDirectory = new File(sceneDirectory, ".incoming");
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
        return new ValidatedScene(sceneName, fileName, normalized);
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

    List<String> listValidFileNames() {
        List<ValidatedScene> scenes = listValidScenes();
        List<String> names = new ArrayList<>(scenes.size());
        for (ValidatedScene scene : scenes) {
            names.add(scene.fileName);
        }
        return names;
    }

    File getValidSceneFile(String fileName) {
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
        return names;
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
