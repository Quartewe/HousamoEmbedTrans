package com.quarty.housamoembedtrans.storage;

import com.quarty.housamoembedtrans.util.IoUtils;

import android.content.Context;
import android.util.AtomicFile;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Stores schema-valid scene JSON files under the module app's files/scenes directory. */
public final class SceneStore {

    public static final String DIRECTORY_NAME = "scenes";
    public static final String SCHEMA_ASSET_PATH = "schema/scene_schema.json";
    public static final int MAX_SCENE_BYTES = 32 * 1024 * 1024;
    public static final int MAX_SCENE_NAME_BYTES = 235;
    private static final int MAX_FILE_NAME_BYTES = 240;
    /** Process-local delete intents; intentionally never persisted. */
    private static final SceneDeletionIntentRegistry PROCESS_DELETION_INTENTS =
        new SceneDeletionIntentRegistry();

    public static final class ValidatedScene {
        public final String sceneName;
        public final byte[] bytes;
        public final List<String> languages;

        ValidatedScene(String sceneName, byte[] bytes, List<String> languages) {
            this.sceneName = sceneName;
            this.bytes = bytes;
            this.languages = Collections.unmodifiableList(
                new ArrayList<>(languages)
            );
        }
    }

    public static final class SceneInfo {
        public final String sceneName;
        public final List<String> languages;

        SceneInfo(ValidatedScene scene) {
            sceneName = scene.sceneName;
            languages = scene.languages;
        }
    }

    /** Immutable token captured by one full-sync operation. */
    public static final class DeletionIntent {
        public final String sceneName;
        public final long token;

        private DeletionIntent(String sceneName, long token) {
            this.sceneName = sceneName;
            this.token = token;
        }
    }

    /** Raw bytes from one formal candidate, retained for mirror export. */
    public static final class RawSceneSnapshot {
        public final String sceneName;
        public final byte[] bytes;
        public final List<String> languages;

        RawSceneSnapshot(String sceneName, byte[] bytes, List<String> languages) {
            this.sceneName = sceneName;
            this.bytes = bytes;
            this.languages = Collections.unmodifiableList(
                new ArrayList<>(languages)
            );
        }
    }

    public enum RawSceneFailureKind {
        READ,
        EMPTY,
        TOO_LARGE,
        INVALID_UTF8,
        INVALID_JSON,
        SCHEMA_INVALID,
        IDENTITY_MISMATCH,
        INTERNAL
    }

    public static final class RawSceneFailure extends Exception {
        private static final long serialVersionUID = 1L;
        public final RawSceneFailureKind kind;

        RawSceneFailure(RawSceneFailureKind kind, Exception cause) {
            super(cause == null ? null : cause.getMessage(), cause);
            this.kind = kind;
        }

        RawSceneFailure(RawSceneFailureKind kind) {
            this(kind, null);
        }
    }

    public enum RawSceneWriteFailureKind {
        START,
        COPY,
        COMMIT
    }

    /** Stable storage-stage failure for the unparsed game apply path. */
    public static final class RawSceneWriteFailure extends IOException {
        private static final long serialVersionUID = 1L;
        public final RawSceneWriteFailureKind kind;

        RawSceneWriteFailure(
            RawSceneWriteFailureKind kind,
            IOException cause
        ) {
            super(cause == null ? null : cause.getMessage(), cause);
            this.kind = kind;
        }
    }

    /**
     * Stages one raw game-side Scene body in AtomicFile's temporary file.
     * The caller must commit only after the complete wire request has passed
     * validation; close/abort leaves the previous formal file untouched.
     */
    public final class RawSceneWriteSession implements AutoCloseable {
        private final String sceneName;
        private final AtomicFile atomicFile;
        private FileOutputStream output;
        private boolean committed;
        private boolean closed;

        private RawSceneWriteSession(String sceneName) throws IOException {
            this.sceneName = requireSceneName(sceneName);
            atomicFile = new AtomicFile(
                new File(sceneDirectory, fileNameForScene(this.sceneName))
            );
            try {
                ensureDirectories();
                output = atomicFile.startWrite();
            } catch (IOException e) {
                throw new RawSceneWriteFailure(
                    RawSceneWriteFailureKind.START,
                    e
                );
            }
        }

        /** Copies exactly one bounded body using a fixed-size buffer. */
        public synchronized void copyFrom(InputStream input, int bodyLength)
            throws IOException {
            if (closed || committed) {
                throw new IOException("raw Scene write session is closed");
            }
            if (input == null
                || bodyLength < 1
                || bodyLength > MAX_SCENE_BYTES) {
                throw new RawSceneWriteFailure(
                    RawSceneWriteFailureKind.COPY,
                    new IOException("invalid raw Scene body length")
                );
            }
            byte[] buffer = new byte[8192];
            int remaining = bodyLength;
            while (remaining > 0) {
                int read = input.read(
                    buffer,
                    0,
                    Math.min(buffer.length, remaining)
                );
                if (read < 0) {
                    throw new IOException("early EOF while writing raw Scene");
                }
                if (read == 0) {
                    continue;
                }
                try {
                    output.write(buffer, 0, read);
                } catch (IOException e) {
                    throw new RawSceneWriteFailure(
                        RawSceneWriteFailureKind.COPY,
                        e
                    );
                }
                remaining -= read;
            }
            if (input.read() != -1) {
                throw new IOException("raw Scene body exceeded declared length");
            }
        }

        /** Publishes the staged file after the surrounding wire request passed. */
        public synchronized void commit() throws IOException {
            if (closed || committed) {
                throw new IOException("raw Scene write session is closed");
            }
            try {
                atomicFile.finishWrite(output);
                output = null;
                committed = true;
                closed = true;
            } catch (RuntimeException e) {
                throw new RawSceneWriteFailure(
                    RawSceneWriteFailureKind.COMMIT,
                    new IOException("could not commit raw Scene", e)
                );
            }
        }

        /** Discards the temporary file and retains the previous formal file. */
        public synchronized void abort() {
            if (closed) {
                return;
            }
            closed = true;
            if (output != null) {
                atomicFile.failWrite(output);
                output = null;
            }
        }

        public synchronized String getSceneName() {
            return sceneName;
        }

        @Override
        public synchronized void close() {
            if (!committed) {
                abort();
            }
        }
    }

    /** Opens a raw staging session without parsing Scene JSON or schema. */
    public RawSceneWriteSession beginRawSceneWrite(String sceneName)
        throws IOException {
        return new RawSceneWriteSession(requireSceneName(sceneName));
    }

    /** Convenience single-file raw write for callers with an already bounded body. */
    public void writeRawSceneAtomically(
        String sceneName,
        InputStream input,
        int bodyLength
    ) throws IOException {
        try (RawSceneWriteSession session = beginRawSceneWrite(sceneName)) {
            session.copyFrom(input, bodyLength);
            session.commit();
        }
    }

    @FunctionalInterface
    public interface RawSceneCandidateConsumer {
        void onCandidate(
            String sceneName,
            RawSceneSnapshot snapshot,
            Exception validationError
        ) throws Exception;
    }

    @FunctionalInterface
    public interface SceneCandidateConsumer {
        /**
         * Receives each formal candidate exactly once.  A null scene means
         * the candidate was readable but invalid; the original exception is
         * supplied for stable REJECTED reason mapping.
         */
        void onCandidate(
            String sceneName,
            ValidatedScene scene,
            Exception validationError
        ) throws Exception;
    }

    private final Context context;
    private final File sceneDirectory;
    private final File incomingDirectory;
    private final JsonSchemaValidator schemaValidator;

    public SceneStore(Context context) {
        this(
            context,
            new File(
                requireContext(context).getFilesDir(),
                DIRECTORY_NAME
            ),
            loadSchema(requireContext(context))
        );
    }

    /** Explicit directory/schema seam for the game-process Scene mirror. */
    public SceneStore(
        Context context,
        File sceneDirectory,
        JSONObject schema
    ) {
        Context applicationContext = requireContext(context).getApplicationContext();
        this.context = applicationContext != null
            ? applicationContext
            : context;
        if (sceneDirectory == null || schema == null) {
            throw new IllegalArgumentException(
                "sceneDirectory and schema cannot be null"
            );
        }
        this.sceneDirectory = sceneDirectory;
        incomingDirectory = new File(sceneDirectory, ".incoming");
        schemaValidator = new JsonSchemaValidator(schema);
    }

    public ValidatedScene importScene(InputStream input) throws Exception {
        ValidatedScene scene = validate(IoUtils.readAllBytesLimited(input, MAX_SCENE_BYTES));
        save(scene);
        return scene;
    }

    /**
     * Enumerates formal Scene candidates in deterministic order.  Each file
     * is opened and read exactly once; the callback must consume or retain the
     * supplied snapshot before returning.  Invalid candidates remain visible
     * as a rejected callback rather than disappearing from the export.
     */
    public void forEachRawSceneCandidate(RawSceneCandidateConsumer consumer)
        throws Exception {
        if (consumer == null) {
            throw new IllegalArgumentException("consumer is null");
        }
        List<String> names = listFormalSceneNamesStrict();
        for (String sceneName : names) {
            RawSceneSnapshot snapshot = null;
            Exception validationError = null;
            try {
                snapshot = readRawSceneSnapshot(sceneName);
            } catch (RawSceneFailure e) {
                validationError = e;
            }
            // Consumer exceptions are intentionally outside the catch above:
            // a pipe/write failure must terminate the whole export, not turn
            // into a second REJECTED record for this file.
            consumer.onCandidate(sceneName, snapshot, validationError);
        }
    }

    public List<String> listFormalSceneNames() {
        List<String> names = new ArrayList<>();
        for (String fileName : candidateFileNames()) {
            try {
                names.add(sceneNameForFileName(fileName));
            } catch (IllegalArgumentException ignored) {
                // candidateFileNames already filters this; keep the storage
                // boundary defensive against a concurrent rename.
            }
        }
        Collections.sort(names);
        return names;
    }

    /**
     * Strict enumeration seam for sync/export.  Missing storage is an empty
     * mirror; an existing non-directory or failed listing is a typed I/O
     * failure, never a silently empty snapshot.
     */
    public List<String> listFormalSceneNamesStrict()
        throws RawSceneFailure {
        if (!sceneDirectory.exists()) {
            return Collections.emptyList();
        }
        if (!sceneDirectory.isDirectory()) {
            throw new RawSceneFailure(
                RawSceneFailureKind.READ,
                new IOException("Scene directory is not a directory")
            );
        }
        File[] files = sceneDirectory.listFiles();
        if (files == null) {
            throw new RawSceneFailure(
                RawSceneFailureKind.READ,
                new IOException("could not enumerate Scene directory")
            );
        }
        List<String> names = new ArrayList<>();
        for (File file : files) {
            if (!file.isFile() || !isSceneFileName(file.getName())) {
                continue;
            }
            try {
                names.add(sceneNameForFileName(file.getName()));
            } catch (IllegalArgumentException ignored) {
                // Invalid candidate names are not formal identities.
            }
        }
        Collections.sort(names);
        return names;
    }

    /** Reads and validates one candidate while preserving its original bytes. */
    public RawSceneSnapshot readRawSceneSnapshot(String sceneName)
        throws RawSceneFailure {
        sceneName = requireSceneName(sceneName);
        File file = new File(sceneDirectory, fileNameForScene(sceneName));
        if (!IoUtils.atomicFileExists(file)) {
            throw new RawSceneFailure(
                RawSceneFailureKind.READ,
                new IOException("scene file does not exist")
            );
        }
        byte[] raw;
        try {
            try (InputStream input = new AtomicFile(file).openRead()) {
                raw = IoUtils.readAllBytesLimited(input, MAX_SCENE_BYTES);
            }
        } catch (IoUtils.InputLimitExceededException e) {
            throw new RawSceneFailure(RawSceneFailureKind.TOO_LARGE, e);
        } catch (IOException e) {
            throw new RawSceneFailure(RawSceneFailureKind.READ, e);
        }
        return validateRawSnapshot(sceneName, raw);
    }

    /** Validates one bounded external body while preserving its exact bytes. */
    public RawSceneSnapshot validateRawSceneBytes(
        String expectedSceneName,
        byte[] rawBytes
    ) throws RawSceneFailure {
        expectedSceneName = requireSceneName(expectedSceneName);
        return validateRawSnapshot(expectedSceneName, rawBytes);
    }

    /** Atomically publishes a previously validated raw snapshot without normalization. */
    public synchronized void saveRawSceneSnapshot(RawSceneSnapshot snapshot)
        throws IOException {
        if (snapshot == null) {
            throw new IllegalArgumentException("snapshot is null");
        }
        String sceneName = requireSceneName(snapshot.sceneName);
        if (snapshot.bytes == null
            || snapshot.bytes.length < 1
            || snapshot.bytes.length > MAX_SCENE_BYTES) {
            throw new IOException("raw Scene body length is outside the limit");
        }
        ensureDirectories();
        IoUtils.writeAtomically(
            new File(sceneDirectory, fileNameForScene(sceneName)),
            snapshot.bytes
        );
        clearSceneDeletionIntent(sceneName);
    }

    public ValidatedScene validate(byte[] sourceBytes) throws Exception {
        if (sourceBytes == null || sourceBytes.length == 0) {
            throw new IllegalArgumentException("scene file is empty");
        }
        if (sourceBytes.length > MAX_SCENE_BYTES) {
            throw new IllegalArgumentException("scene file exceeds 32 MiB");
        }

        String source = decodeStrictUtf8(sourceBytes);
        if (!source.isEmpty() && source.charAt(0) == '\uFEFF') {
            source = source.substring(1);
        }

        JSONObject json = new JSONObject(source);
        schemaValidator.validate(json);

        String sceneName = requireSceneName(json.getString("scene"));
        byte[] normalized = serializeScene(json);
        if (normalized.length > MAX_SCENE_BYTES) {
            throw new IllegalArgumentException("normalized scene file exceeds 32 MiB");
        }
        return new ValidatedScene(
            sceneName,
            normalized,
            collectLanguages(json)
        );
    }

    private RawSceneSnapshot validateRawSnapshot(
        String expectedSceneName,
        byte[] rawBytes
    ) throws RawSceneFailure {
        if (rawBytes == null || rawBytes.length == 0) {
            throw new RawSceneFailure(RawSceneFailureKind.EMPTY);
        }
        if (rawBytes.length > MAX_SCENE_BYTES) {
            throw new RawSceneFailure(RawSceneFailureKind.TOO_LARGE);
        }
        String source;
        try {
            source = decodeStrictUtf8(rawBytes);
        } catch (CharacterCodingException e) {
            throw new RawSceneFailure(RawSceneFailureKind.INVALID_UTF8, e);
        }
        if (!source.isEmpty() && source.charAt(0) == '\uFEFF') {
            source = source.substring(1);
        }
        JSONObject json;
        try {
            json = new JSONObject(source);
        } catch (org.json.JSONException e) {
            throw new RawSceneFailure(RawSceneFailureKind.INVALID_JSON, e);
        }
        try {
            schemaValidator.validate(json);
        } catch (JsonSchemaValidator.ValidationException e) {
            throw new RawSceneFailure(RawSceneFailureKind.SCHEMA_INVALID, e);
        }
        String sceneName;
        try {
            sceneName = requireSceneName(json.getString("scene"));
        } catch (org.json.JSONException e) {
            throw new RawSceneFailure(RawSceneFailureKind.INVALID_JSON, e);
        } catch (IllegalArgumentException e) {
            throw new RawSceneFailure(RawSceneFailureKind.IDENTITY_MISMATCH, e);
        }
        if (!expectedSceneName.equals(sceneName)) {
            throw new RawSceneFailure(
                RawSceneFailureKind.IDENTITY_MISMATCH,
                new IllegalArgumentException(
                    "scene field does not match requested SceneName"
                )
            );
        }
        return new RawSceneSnapshot(
            sceneName,
            rawBytes,
            collectLanguages(json)
        );
    }

    private static String decodeStrictUtf8(byte[] bytes)
        throws CharacterCodingException {
        return StandardCharsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(bytes))
            .toString();
    }

    public synchronized void save(ValidatedScene scene) throws IOException {
        if (scene == null) {
            throw new IllegalArgumentException("scene is null");
        }
        ensureDirectories();
        String sceneName = requireSceneName(scene.sceneName);
        String fileName = fileNameForScene(sceneName);
        ValidatedScene existing = null;
        try {
            existing = readValidScene(fileName);
        } catch (Exception ignored) {
            // A newly validated import is allowed to repair a corrupt file.
        }
        if (existing != null && !existing.sceneName.equals(sceneName)) {
            throw new IOException(
                "scene name collides with existing " + existing.sceneName
                    + " at " + fileName
            );
        }
        IoUtils.writeAtomically(new File(sceneDirectory, fileName), scene.bytes);
        clearSceneDeletionIntent(sceneName);
    }

    public synchronized ValidatedScene removeLanguage(
        String sceneName,
        String language
    )
        throws Exception {
        sceneName = requireSceneName(sceneName);
        if (language == null || language.isEmpty()) {
            throw new IllegalArgumentException("language key is empty");
        }

        ValidatedScene scene = readValidSceneByName(sceneName);
        if (scene == null || isSceneDeleted(sceneName)) {
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

        ValidatedScene updated = validate(serializeScene(json));
        if (!updated.sceneName.equals(sceneName)) {
            throw new IOException("scene name changed while removing language");
        }
        save(updated);
        return updated;
    }

    public synchronized void deleteScene(String sceneName) throws IOException {
        sceneName = requireSceneName(sceneName);
        File file = new File(sceneDirectory, fileNameForScene(sceneName));
        if (PROCESS_DELETION_INTENTS.contains(sceneName)
            && !IoUtils.atomicFileExists(file)) {
            return;
        }
        if (!IoUtils.atomicFileExists(file)) {
            throw new IOException("scene file does not exist");
        }
        new AtomicFile(file).delete();
        if (IoUtils.atomicFileExists(file)) {
            throw new IOException("could not delete scene file");
        }
        PROCESS_DELETION_INTENTS.record(sceneName);
    }

    /** Deletes a game-side mirror without creating an HET delete intent. */
    public synchronized void deleteSceneForSync(String sceneName)
        throws IOException {
        sceneName = requireSceneName(sceneName);
        File file = new File(sceneDirectory, fileNameForScene(sceneName));
        if (!IoUtils.atomicFileExists(file)) {
            return;
        }
        new AtomicFile(file).delete();
        if (IoUtils.atomicFileExists(file)) {
            throw new IOException("could not delete scene file");
        }
    }

    public synchronized void acceptIncoming(
        File temporaryFile,
        String expectedSceneName
    )
        throws Exception {
        try {
            ValidatedScene scene;
            try (InputStream input = new java.io.FileInputStream(temporaryFile)) {
                scene = validate(IoUtils.readAllBytesLimited(input, MAX_SCENE_BYTES));
            }
            expectedSceneName = requireSceneName(expectedSceneName);
            if (!scene.sceneName.equals(expectedSceneName)) {
                throw new IllegalArgumentException(
                    "scene field maps to " + scene.sceneName
                        + ", not requested SceneName " + expectedSceneName
                );
            }
            save(scene);
        } finally {
            temporaryFile.delete();
        }
    }

    public File createIncomingFile() throws IOException {
        ensureDirectories();
        return File.createTempFile("scene-", ".json.tmp", incomingDirectory);
    }

    public List<ValidatedScene> listValidScenes() {
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

    public List<SceneInfo> listSceneInfos() {
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

    public List<String> listValidSceneNames() {
        List<SceneInfo> scenes = listSceneInfos();
        List<String> names = new ArrayList<>(scenes.size());
        for (SceneInfo scene : scenes) {
            names.add(scene.sceneName);
        }
        return names;
    }

    /** Returns process-local delete intents; no filesystem marker is used. */
    public List<String> listDeletedSceneNames() {
        return PROCESS_DELETION_INTENTS.names();
    }

    /** Captures name/token pairs once at a sync-cycle boundary. */
    public Map<String, DeletionIntent> snapshotDeletionIntents() {
        Map<String, DeletionIntent> snapshot = new HashMap<>();
        for (Map.Entry<String, SceneDeletionIntentRegistry.Intent> entry
            : PROCESS_DELETION_INTENTS.snapshot().entrySet()) {
            snapshot.put(
                entry.getKey(),
                new DeletionIntent(entry.getKey(), entry.getValue().token)
            );
        }
        return Collections.unmodifiableMap(snapshot);
    }

    /** Returns whether the current HET process still owns a delete intent. */
    public boolean hasSceneDeletionIntent(String sceneName) {
        sceneName = requireSceneName(sceneName);
        return PROCESS_DELETION_INTENTS.contains(sceneName);
    }

    /** Clears an intent only after the peer has acknowledged DELETE_SCENE. */
    public void clearSceneDeletionIntent(String sceneName) {
        sceneName = requireSceneName(sceneName);
        PROCESS_DELETION_INTENTS.clear(sceneName);
    }

    /** Clears only the intent token captured by a completed operation. */
    public boolean clearMatchingDeletionIntent(String sceneName, long token) {
        sceneName = requireSceneName(sceneName);
        return PROCESS_DELETION_INTENTS.clearMatching(sceneName, token);
    }

    public synchronized ValidatedScene readValidSceneByName(
        String sceneName
    ) throws Exception {
        sceneName = requireSceneName(sceneName);

        String fileName = fileNameForScene(sceneName);
        if (isSceneDeleted(sceneName)) {
            return null;
        }

        ValidatedScene scene = readValidScene(fileName);
        if (scene == null || !sceneName.equals(scene.sceneName)) {
            return null;
        }
        return scene;
    }

    public File getValidSceneFileByName(String sceneName) {
        sceneName = requireSceneName(sceneName);
        if (isSceneDeleted(sceneName)) {
            return null;
        }
        try {
            ValidatedScene scene = readValidSceneByName(sceneName);
            return scene == null
                ? null
                : new File(sceneDirectory, fileNameForScene(sceneName));
        } catch (Exception ignored) {
            return null;
        }
    }

    private ValidatedScene readValidScene(String fileName) throws Exception {
        if (!isSceneFileName(fileName)) {
            return null;
        }

        File file = new File(sceneDirectory, fileName);
        AtomicFile atomicFile = new AtomicFile(file);
        if (!IoUtils.atomicFileExists(file)) {
            return null;
        }

        ValidatedScene scene;
        try (InputStream input = atomicFile.openRead()) {
            scene = validate(IoUtils.readAllBytesLimited(input, MAX_SCENE_BYTES));
        }
        return scene.sceneName.equals(sceneNameForFileName(fileName)) ? scene : null;
    }

    private Set<String> candidateFileNames() {
        Set<String> names = new HashSet<>();
        File[] files = sceneDirectory.listFiles();
        if (files == null) {
            return names;
        }

        for (File file : files) {
            String name = file.getName();
            if (file.isFile() && isSceneFileName(name)) {
                names.add(name);
            }
        }
        for (String deletedSceneName : listDeletedSceneNames()) {
            names.remove(fileNameForScene(deletedSceneName));
        }
        return names;
    }

    private boolean isSceneDeleted(String sceneName) {
        return PROCESS_DELETION_INTENTS.contains(sceneName);
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
        IoUtils.ensureDirectory(sceneDirectory);
        IoUtils.ensureDirectory(incomingDirectory);
    }

    private static Context requireContext(Context context) {
        if (context == null) {
            throw new IllegalArgumentException("context cannot be null");
        }
        return context;
    }

    private static JSONObject loadSchema(Context context) {
        try (InputStream schemaInput = context.getAssets().open(SCHEMA_ASSET_PATH)) {
            return new JSONObject(new String(
                IoUtils.readAllBytesLimited(schemaInput, MAX_SCENE_BYTES),
                StandardCharsets.UTF_8
            ));
        } catch (Exception e) {
            throw new IllegalStateException("could not load scene schema", e);
        }
    }

    /** Serializes a Scene using the native PrettyWriter contract. */
    public static byte[] serializeScene(JSONObject scene) {
        if (scene == null) {
            throw new IllegalArgumentException("scene is null");
        }
        StringBuilder output = new StringBuilder();
        appendJsonValue(scene, output, 0);
        return output.toString().getBytes(StandardCharsets.UTF_8);
    }

    private static void appendJsonValue(
        Object value,
        StringBuilder output,
        int depth
    ) {
        if (value == null || value == JSONObject.NULL) {
            output.append("null");
        } else if (value instanceof JSONObject) {
            appendJsonObject((JSONObject) value, output, depth);
        } else if (value instanceof JSONArray) {
            appendJsonArray((JSONArray) value, output, depth);
        } else if (value instanceof String || value instanceof Character) {
            appendJsonString(value.toString(), output);
        } else if (value instanceof Boolean || value instanceof Number) {
            output.append(value.toString());
        } else {
            throw new IllegalArgumentException(
                "unsupported Scene JSON value: " + value.getClass().getName()
            );
        }
    }

    private static void appendJsonObject(
        JSONObject object,
        StringBuilder output,
        int depth
    ) {
        List<String> keys = new ArrayList<>();
        Iterator<String> iterator = object.keys();
        while (iterator.hasNext()) {
            keys.add(iterator.next());
        }
        if (keys.isEmpty()) {
            output.append("{}");
            return;
        }
        output.append("{\n");
        for (int index = 0; index < keys.size(); index++) {
            if (index > 0) {
                output.append(",\n");
            }
            appendIndent(output, depth + 1);
            appendJsonString(keys.get(index), output);
            output.append(": ");
            appendJsonValue(object.opt(keys.get(index)), output, depth + 1);
        }
        output.append('\n');
        appendIndent(output, depth);
        output.append('}');
    }

    private static void appendJsonArray(
        JSONArray array,
        StringBuilder output,
        int depth
    ) {
        if (array.length() == 0) {
            output.append("[]");
            return;
        }
        output.append("[\n");
        for (int index = 0; index < array.length(); index++) {
            if (index > 0) {
                output.append(",\n");
            }
            appendIndent(output, depth + 1);
            appendJsonValue(array.opt(index), output, depth + 1);
        }
        output.append('\n');
        appendIndent(output, depth);
        output.append(']');
    }

    private static void appendIndent(StringBuilder output, int depth) {
        for (int index = 0; index < depth * 4; index++) {
            output.append(' ');
        }
    }

    private static void appendJsonString(String value, StringBuilder output) {
        output.append('"');
        for (int index = 0; index < value.length(); index++) {
            char character = value.charAt(index);
            switch (character) {
                case '"':
                    output.append("\\\"");
                    break;
                case '\\':
                    output.append("\\\\");
                    break;
                case '\b':
                    output.append("\\b");
                    break;
                case '\t':
                    output.append("\\t");
                    break;
                case '\n':
                    output.append("\\n");
                    break;
                case '\f':
                    output.append("\\f");
                    break;
                case '\r':
                    output.append("\\r");
                    break;
                default:
                    if (character < 0x20) {
                        output.append("\\u");
                        String hex = Integer.toHexString(character).toUpperCase();
                        for (int padding = hex.length(); padding < 4; padding++) {
                            output.append('0');
                        }
                        output.append(hex);
                    } else {
                        output.append(character);
                    }
            }
        }
        output.append('"');
    }

    public static String requireSceneName(String sceneName) {
        if (!isValidSceneName(sceneName)) {
            throw new IllegalArgumentException(
                "scene name must be a bare ASCII identity without a suffix"
            );
        }
        return sceneName;
    }

    public static boolean isValidSceneName(String sceneName) {
        if (sceneName == null || sceneName.isEmpty()) {
            return false;
        }
        byte[] utf8 = sceneName.getBytes(StandardCharsets.UTF_8);
        if (utf8.length > MAX_SCENE_NAME_BYTES) {
            return false;
        }
        for (int index = 0; index < sceneName.length(); index++) {
            char value = sceneName.charAt(index);
            boolean allowed = (value >= 'a' && value <= 'z')
                || (value >= 'A' && value <= 'Z')
                || (value >= '0' && value <= '9')
                || value == '_'
                || value == '-';
            if (!allowed) {
                return false;
            }
        }
        return true;
    }

    public static String fileNameForScene(String sceneName) {
        requireSceneName(sceneName);
        String fileName = sceneName + ".json";
        if (fileName.getBytes(StandardCharsets.UTF_8).length > MAX_FILE_NAME_BYTES) {
            throw new IllegalArgumentException("scene-derived file name is too long");
        }
        return fileName;
    }

    public static String sceneNameForFileName(String fileName) {
        if (!isSceneFileName(fileName)) {
            throw new IllegalArgumentException("invalid scene file name");
        }
        return requireSceneName(fileName.substring(0, fileName.length() - 5));
    }

    /** Validates a formal on-disk filename at a filesystem/provider boundary. */
    public static boolean isSceneFileName(String fileName) {
        if (fileName == null
            || !fileName.endsWith(".json")
            || fileName.length() > MAX_FILE_NAME_BYTES) {
            return false;
        }
        String sceneName = fileName.substring(0, fileName.length() - 5);
        return isValidSceneName(sceneName)
            && fileName.equals(fileNameForScene(sceneName));
    }
}
