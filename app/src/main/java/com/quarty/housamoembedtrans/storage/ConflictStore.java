package com.quarty.housamoembedtrans.storage;

import android.content.Context;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Durable storage for one manual Scene conflict.
 *
 * <p>A conflict is published by writing both exact candidate byte arrays and
 * the state file into a private sibling directory, then renaming that
 * directory into {@code files/scene_conflicts/<SceneName>}.  Readers only
 * accept a complete directory whose state hashes match both candidate files;
 * a crash before the rename therefore cannot expose a partial conflict.</p>
 */
public final class ConflictStore {
    public static final String DIRECTORY_NAME = "scene_conflicts";
    public static final String GAME_FILE_NAME = "game.json";
    public static final String HET_FILE_NAME = "het.json";
    public static final String STATE_FILE_NAME = "state.json";
    public static final int STATE_FORMAT_VERSION = 1;
    private static final int MAX_STATE_BYTES = 64 * 1024;
    private static final String TEMP_PREFIX = ".incoming-";
    private static final String BACKUP_PREFIX = ".backup-";

    public enum FailureKind {
        INVALID_NAME,
        INVALID_CANDIDATE,
        MISSING_FILE,
        INVALID_STATE,
        HASH_MISMATCH,
        IO
    }

    /** Typed failure used by startup recovery and the manual-conflict UI. */
    public static final class ConflictFailure extends IOException {
        private static final long serialVersionUID = 1L;
        public final FailureKind kind;

        ConflictFailure(FailureKind kind, String message) {
            super(message);
            this.kind = kind;
        }

        ConflictFailure(FailureKind kind, String message, Throwable cause) {
            super(message, cause);
            this.kind = kind;
        }
    }

    /** Exact candidates read once from one complete formal conflict. */
    public static final class ConflictRecord {
        public final String sceneName;
        public final byte[] gameBytes;
        public final byte[] hetBytes;
        public final long createdAtMillis;
        public final String gameSha256;
        public final String hetSha256;

        private ConflictRecord(
            String sceneName,
            byte[] gameBytes,
            byte[] hetBytes,
            long createdAtMillis,
            String gameSha256,
            String hetSha256
        ) {
            this.sceneName = sceneName;
            // The record owns these freshly-read arrays.  Do not clone them:
            // a 32 MiB candidate must not be duplicated merely to return it
            // to the caller that requested the conflict details.
            this.gameBytes = gameBytes;
            this.hetBytes = hetBytes;
            this.createdAtMillis = createdAtMillis;
            this.gameSha256 = gameSha256;
            this.hetSha256 = hetSha256;
        }
    }

    /** Lightweight metadata returned after a successful durable publish. */
    public static final class ConflictMetadata {
        public final String sceneName;
        public final long createdAtMillis;
        public final String gameSha256;
        public final String hetSha256;

        private ConflictMetadata(
            String sceneName,
            long createdAtMillis,
            String gameSha256,
            String hetSha256
        ) {
            this.sceneName = sceneName;
            this.createdAtMillis = createdAtMillis;
            this.gameSha256 = gameSha256;
            this.hetSha256 = hetSha256;
        }
    }

    private static final class StateMetadata {
        private final long createdAtMillis;
        private final String gameSha256;
        private final String hetSha256;

        private StateMetadata(
            long createdAtMillis,
            String gameSha256,
            String hetSha256
        ) {
            this.createdAtMillis = createdAtMillis;
            this.gameSha256 = gameSha256;
            this.hetSha256 = hetSha256;
        }
    }

    private static final class HashedFile {
        private final String sha256;

        private HashedFile(String sha256) {
            this.sha256 = sha256;
        }
    }

    /** Report returned after cleaning interrupted temporary directories. */
    public static final class RecoveryReport {
        public final List<String> completeSceneNames;
        public final List<String> removedTemporaryDirectories;
        public final List<String> invalidFormalDirectories;

        private RecoveryReport(
            List<String> completeSceneNames,
            List<String> removedTemporaryDirectories,
            List<String> invalidFormalDirectories
        ) {
            this.completeSceneNames = immutableSorted(completeSceneNames);
            this.removedTemporaryDirectories = immutableSorted(
                removedTemporaryDirectories
            );
            this.invalidFormalDirectories = immutableSorted(
                invalidFormalDirectories
            );
        }
    }

    private final File conflictDirectory;

    public ConflictStore(Context context) {
        this(
            new File(
                requireContext(context).getFilesDir(),
                DIRECTORY_NAME
            )
        );
    }

    /** Explicit filesystem seam used by the service and host fixtures. */
    public ConflictStore(File conflictDirectory) {
        if (conflictDirectory == null) {
            throw new IllegalArgumentException(
                "conflictDirectory cannot be null"
            );
        }
        this.conflictDirectory = conflictDirectory;
    }

    public File getDirectory() {
        return conflictDirectory;
    }

    /** Returns whether a formal directory already claims this SceneName. */
    public synchronized boolean hasFormalConflict(String sceneName) {
        try {
            SceneStore.requireSceneName(sceneName);
        } catch (IllegalArgumentException e) {
            return false;
        }
        return formalDirectory(sceneName).isDirectory();
    }

    /** Validates the bounded candidate seam without parsing or copying bytes. */
    public static void validateCandidate(String sceneName, byte[] bytes)
        throws ConflictFailure {
        requireCandidate(sceneName, bytes);
    }

    /**
     * Writes one conflict as an atomic directory publication.  If a complete
     * conflict already exists for the Scene, it is left unchanged and its
     * lightweight metadata is returned; no second candidate is created.
     */
    public synchronized ConflictMetadata persist(
        String sceneName,
        byte[] gameBytes,
        byte[] hetBytes
    ) throws IOException {
        requireCandidate(sceneName, gameBytes);
        requireCandidate(sceneName, hetBytes);

        normalizeScene(sceneName);
        File formalDirectory = formalDirectory(sceneName);
        if (formalDirectory.exists()) {
            return readMetadata(sceneName);
        }

        final long createdAt = System.currentTimeMillis();
        final String gameHash = sha256Hex(gameBytes);
        final String hetHash = sha256Hex(hetBytes);
        boolean published = TransactionalSceneSlots.publishNew(
            conflictDirectory,
            sceneName,
            TEMP_PREFIX,
            ConflictStore::slotIoFailure,
            incoming -> {
                writeExact(new File(incoming, GAME_FILE_NAME), gameBytes);
                writeExact(new File(incoming, HET_FILE_NAME), hetBytes);
                writeState(
                    incoming,
                    sceneName,
                    createdAt,
                    gameHash,
                    hetHash
                );
            }
        );
        if (!published) {
            return readMetadata(sceneName);
        }
        return new ConflictMetadata(
            sceneName,
            createdAt,
            gameHash,
            hetHash
        );
    }

    /**
     * Atomically replaces an existing formal conflict with the supplied
     * fixed candidates.  This is the pending-apply seam: a new game export
     * must replace the old game candidate together with the already-persisted
     * pending candidate, while any write/rename failure leaves the old formal
     * directory available for a retry.
     */
    public synchronized ConflictMetadata replace(
        String sceneName,
        byte[] gameBytes,
        byte[] hetBytes
    ) throws IOException {
        requireCandidate(sceneName, gameBytes);
        requireCandidate(sceneName, hetBytes);

        normalizeScene(sceneName);
        File formalDirectory = formalDirectory(sceneName);
        if (!formalDirectory.isDirectory()) {
            throw new ConflictFailure(
                FailureKind.MISSING_FILE,
                "formal conflict directory is missing"
            );
        }
        long createdAt = System.currentTimeMillis();
        String gameHash = sha256Hex(gameBytes);
        String hetHash = sha256Hex(hetBytes);
        TransactionalSceneSlots.publishReplacement(
            conflictDirectory,
            sceneName,
            TEMP_PREFIX,
            BACKUP_PREFIX,
            ConflictStore::slotIoFailure,
            incoming -> {
                writeExact(new File(incoming, GAME_FILE_NAME), gameBytes);
                writeExact(new File(incoming, HET_FILE_NAME), hetBytes);
                writeState(
                    incoming,
                    sceneName,
                    createdAt,
                    gameHash,
                    hetHash
                );
            }
        );
        return new ConflictMetadata(
            sceneName,
            createdAt,
            gameHash,
            hetHash
        );
    }

    /** Reads one complete conflict, rejecting partial or tampered state. */
    public synchronized ConflictRecord read(String sceneName)
        throws ConflictFailure {
        try {
            SceneStore.requireSceneName(sceneName);
        } catch (IllegalArgumentException e) {
            throw new ConflictFailure(
                FailureKind.INVALID_NAME,
                "invalid conflict SceneName",
                e
            );
        }
        File directory = formalDirectory(sceneName);
        if (!directory.isDirectory()) {
            throw new ConflictFailure(
                FailureKind.MISSING_FILE,
                "formal conflict directory is missing"
            );
        }
        File gameFile = new File(directory, GAME_FILE_NAME);
        File hetFile = new File(directory, HET_FILE_NAME);
        File stateFile = new File(directory, STATE_FILE_NAME);
        if (!gameFile.isFile() || !hetFile.isFile() || !stateFile.isFile()) {
            throw new ConflictFailure(
                FailureKind.MISSING_FILE,
                "formal conflict directory is incomplete"
            );
        }

        byte[] gameBytes = readExact(gameFile, SceneStore.MAX_SCENE_BYTES);
        byte[] hetBytes = readExact(hetFile, SceneStore.MAX_SCENE_BYTES);
        JSONObject state = readState(stateFile);
        StateMetadata metadata = parseState(sceneName, state);
        if (!metadata.gameSha256.equals(sha256Hex(gameBytes))
            || !metadata.hetSha256.equals(sha256Hex(hetBytes))) {
            throw new ConflictFailure(
                FailureKind.HASH_MISMATCH,
                "conflict state hash does not match candidate bytes"
            );
        }
        return new ConflictRecord(
            sceneName,
            gameBytes,
            hetBytes,
            metadata.createdAtMillis,
            metadata.gameSha256,
            metadata.hetSha256
        );
    }

    /**
     * Validates a formal conflict without retaining either candidate in
     * memory.  This is used by startup/listing and duplicate detection; the
     * UI calls {@link #read(String)} only when it actually needs the bodies.
     */
    public synchronized ConflictMetadata readMetadata(String sceneName)
        throws ConflictFailure {
        validateSceneName(sceneName);
        return readMetadataFromDirectory(
            sceneName,
            formalDirectory(sceneName)
        );
    }

    private ConflictMetadata readMetadataFromDirectory(
        String sceneName,
        File directory
    ) throws ConflictFailure {
        if (!directory.isDirectory()) {
            throw new ConflictFailure(
                FailureKind.MISSING_FILE,
                "formal conflict directory is missing"
            );
        }
        File gameFile = new File(directory, GAME_FILE_NAME);
        File hetFile = new File(directory, HET_FILE_NAME);
        File stateFile = new File(directory, STATE_FILE_NAME);
        if (!gameFile.isFile() || !hetFile.isFile() || !stateFile.isFile()) {
            throw new ConflictFailure(
                FailureKind.MISSING_FILE,
                "formal conflict directory is incomplete"
            );
        }
        HashedFile game = hashFile(gameFile, SceneStore.MAX_SCENE_BYTES);
        HashedFile het = hashFile(hetFile, SceneStore.MAX_SCENE_BYTES);
        StateMetadata metadata = parseState(sceneName, readState(stateFile));
        if (!metadata.gameSha256.equals(game.sha256)
            || !metadata.hetSha256.equals(het.sha256)) {
            throw new ConflictFailure(
                FailureKind.HASH_MISMATCH,
                "conflict state hash does not match candidate bytes"
            );
        }
        return new ConflictMetadata(
            sceneName,
            metadata.createdAtMillis,
            metadata.gameSha256,
            metadata.hetSha256
        );
    }

    /** Returns only complete formal conflicts, in deterministic name order. */
    public synchronized List<String> listCompleteSceneNames() {
        if (!conflictDirectory.isDirectory()) {
            return Collections.emptyList();
        }
        List<String> names = new ArrayList<>();
        File[] entries = conflictDirectory.listFiles();
        if (entries == null) {
            return Collections.emptyList();
        }
        for (File entry : entries) {
            if (!entry.isDirectory() || !SceneStore.isValidSceneName(entry.getName())) {
                continue;
            }
            try {
                readMetadata(entry.getName());
                names.add(entry.getName());
            } catch (ConflictFailure ignored) {
                // Corrupt entries remain available for diagnostics/recovery,
                // but are never exposed as selectable conflicts.
            }
        }
        Collections.sort(names);
        return Collections.unmodifiableList(names);
    }

    /**
     * Enumerates claimed Scene identities without reading candidate bodies or
     * state.  A malformed formal directory is still a claim and therefore
     * remains blocked until an explicit repair/removal; callers can use
     * {@link #listCompleteSceneNames()} for selectable records only.
     */
    public synchronized List<String> listClaimedSceneNames() {
        if (!conflictDirectory.isDirectory()) {
            return Collections.emptyList();
        }
        File[] entries = conflictDirectory.listFiles();
        if (entries == null) {
            return Collections.emptyList();
        }
        List<String> names = new ArrayList<>();
        for (File entry : entries) {
            if (SceneStore.isValidSceneName(entry.getName())) {
                names.add(entry.getName());
            }
        }
        Collections.sort(names);
        return Collections.unmodifiableList(names);
    }

    /** Strict sync enumeration; only a missing root is treated as empty. */
    public synchronized List<String> listClaimedSceneNamesStrict()
        throws ConflictFailure {
        if (!conflictDirectory.exists()) {
            return Collections.emptyList();
        }
        if (!conflictDirectory.isDirectory()) {
            throw new ConflictFailure(
                FailureKind.IO,
                "conflict root is not a directory"
            );
        }
        File[] entries = conflictDirectory.listFiles();
        if (entries == null) {
            throw new ConflictFailure(
                FailureKind.IO,
                "could not enumerate conflict root"
            );
        }
        List<String> names = new ArrayList<>();
        for (File entry : entries) {
            if (SceneStore.isValidSceneName(entry.getName())) {
                names.add(entry.getName());
            }
        }
        Collections.sort(names);
        return Collections.unmodifiableList(names);
    }

    /** Removes one formal conflict after a successful resolution. */
    public synchronized void remove(String sceneName) throws IOException {
        try {
            SceneStore.requireSceneName(sceneName);
        } catch (IllegalArgumentException e) {
            throw new ConflictFailure(
                FailureKind.INVALID_NAME,
                "invalid conflict SceneName",
                e
            );
        }
        normalizeScene(sceneName);
        File directory = formalDirectory(sceneName);
        if (directory.exists() && !deleteRecursively(directory)) {
            throw new ConflictFailure(
                FailureKind.IO,
                "could not remove conflict directory"
            );
        }
    }

    /**
     * Cleans interrupted private-directory writes and validates every formal
     * conflict.  There is deliberately no root-level ready marker or batch
     * state: a complete child directory is the publication unit.
     */
    public synchronized RecoveryReport recover() throws IOException {
        if (!conflictDirectory.exists()) {
            return new RecoveryReport(
                Collections.emptyList(),
                Collections.emptyList(),
                Collections.emptyList()
            );
        }
        if (!conflictDirectory.isDirectory()) {
            throw new ConflictFailure(
                FailureKind.IO,
                "conflict root is not a directory"
            );
        }

        List<String> removedTemporary = new ArrayList<>();
        List<String> complete = new ArrayList<>();
        List<String> invalid = new ArrayList<>();
        File[] entries = conflictDirectory.listFiles();
        if (entries == null) {
            throw new ConflictFailure(
                FailureKind.IO,
                "could not enumerate conflict root"
            );
        }
        Set<String> sceneNames = new HashSet<>();
        for (File entry : entries) {
            if (!entry.exists()) {
                continue;
            }
            if (entry.getName().startsWith(TEMP_PREFIX)) {
                cleanupOrThrow(
                    entry,
                    "could not remove uncommitted conflict incoming directory"
                );
                removedTemporary.add(entry.getName());
                continue;
            }
            String sceneName;
            try {
                sceneName = TransactionalSceneSlots.slotSceneName(
                    entry,
                    BACKUP_PREFIX,
                    STATE_FILE_NAME,
                    ConflictStore::readSlotSceneName
                );
            } catch (TransactionalSceneSlots.SlotFailure e) {
                throw mapSlotFailure(e);
            }
            if (sceneName != null) {
                sceneNames.add(sceneName);
                continue;
            }
            if (entry.isDirectory()
                && SceneStore.isValidSceneName(entry.getName())) {
                sceneNames.add(entry.getName());
                continue;
            }
            if (entry.getName().startsWith(TEMP_PREFIX)
                || entry.getName().startsWith(BACKUP_PREFIX)) {
                cleanupOrThrow(
                    entry,
                    "could not remove conflict residue with unknown SceneName"
                );
                removedTemporary.add(entry.getName());
            }
        }
        for (String sceneName : sceneNames) {
            normalizeScene(sceneName);
            File formal = formalDirectory(sceneName);
            if (formal.isDirectory()) {
                try {
                    readMetadataFromDirectory(sceneName, formal);
                    complete.add(sceneName);
                } catch (ConflictFailure e) {
                    if (e.kind == FailureKind.IO) {
                        throw e;
                    }
                    invalid.add(sceneName);
                }
            }
        }
        return new RecoveryReport(complete, removedTemporary, invalid);
    }

    private void normalizeScene(String sceneName) throws IOException {
        validateSceneName(sceneName);
        try {
            TransactionalSceneSlots.normalize(
                conflictDirectory,
                sceneName,
                TEMP_PREFIX,
                BACKUP_PREFIX,
                STATE_FILE_NAME,
                ConflictStore::readSlotSceneName,
                (directory, name) -> readMetadataFromDirectory(name, directory),
                failure -> failure instanceof ConflictFailure
                    && ((ConflictFailure) failure).kind == FailureKind.IO,
                ConflictStore::slotIoFailure,
                false
            );
        } catch (TransactionalSceneSlots.SlotFailure e) {
            throw mapSlotFailure(e);
        }
    }

    private static String readSlotSceneName(File stateFile)
        throws TransactionalSceneSlots.SlotFailure {
        try {
            JSONObject state = readState(stateFile);
            Object value = state.get("scene_name");
            if (!(value instanceof String)
                || !SceneStore.isValidSceneName((String) value)) {
                throw new TransactionalSceneSlots.SlotFailure(
                    TransactionalSceneSlots.FailureKind.INVALID_STATE,
                    "conflict slot state SceneName is invalid"
                );
            }
            return (String) value;
        } catch (TransactionalSceneSlots.SlotFailure e) {
            throw e;
        } catch (ConflictFailure e) {
            throw new TransactionalSceneSlots.SlotFailure(
                e.kind == FailureKind.IO
                    ? TransactionalSceneSlots.FailureKind.IO
                    : TransactionalSceneSlots.FailureKind.INVALID_STATE,
                "could not read conflict slot state",
                e
            );
        } catch (JSONException e) {
            throw new TransactionalSceneSlots.SlotFailure(
                TransactionalSceneSlots.FailureKind.INVALID_STATE,
                "conflict slot state SceneName is invalid",
                e
            );
        }
    }

    private static ConflictFailure mapSlotFailure(
        TransactionalSceneSlots.SlotFailure failure
    ) {
        return new ConflictFailure(
            failure.kind == TransactionalSceneSlots.FailureKind.IO
                ? FailureKind.IO
                : FailureKind.INVALID_STATE,
            failure.getMessage(),
            failure
        );
    }

    static IOException slotIoFailure(
        String message,
        Throwable cause
    ) {
        if ("slot file parent is missing".equals(message)) {
            message = "conflict file parent is missing";
        } else if ("could not write slot file".equals(message)) {
            message = "could not write conflict file";
        }
        return new ConflictFailure(FailureKind.IO, message, cause);
    }

    private static void cleanupOrThrow(File file, String message)
        throws ConflictFailure {
        TransactionalSceneSlots.cleanupOrThrow(
            file,
            message,
            ConflictStore::slotIoFailure
        );
    }

    private File formalDirectory(String sceneName) {
        return new File(conflictDirectory, sceneName);
    }

    private static void requireCandidate(String sceneName, byte[] bytes)
        throws ConflictFailure {
        if (!SceneStore.isValidSceneName(sceneName)) {
            throw new ConflictFailure(
                FailureKind.INVALID_NAME,
                "invalid conflict SceneName"
            );
        }
        if (bytes == null
            || bytes.length == 0
            || bytes.length > SceneStore.MAX_SCENE_BYTES) {
            throw new ConflictFailure(
                FailureKind.INVALID_CANDIDATE,
                "conflict candidate length is outside the Scene limit"
            );
        }
    }

    private static void validateSceneName(String sceneName)
        throws ConflictFailure {
        try {
            SceneStore.requireSceneName(sceneName);
        } catch (IllegalArgumentException e) {
            throw new ConflictFailure(
                FailureKind.INVALID_NAME,
                "invalid conflict SceneName",
                e
            );
        }
    }

    private static StateMetadata parseState(
        String sceneName,
        JSONObject state
    ) throws ConflictFailure {
        try {
            if (state.length() != 5) {
                throw new ConflictFailure(
                    FailureKind.INVALID_STATE,
                    "conflict state must contain exactly five fields"
                );
            }
            Object formatVersion = state.get("format_version");
            Object stateSceneName = state.get("scene_name");
            Object createdAt = state.get("created_at");
            Object gameHash = state.get("game_sha256");
            Object hetHash = state.get("het_sha256");
            if (!isIntegralJsonNumber(formatVersion)
                || ((Number) formatVersion).longValue()
                    != STATE_FORMAT_VERSION
                || !(stateSceneName instanceof String)
                || !sceneName.equals(stateSceneName)
                || !isIntegralJsonNumber(createdAt)
                || ((Number) createdAt).longValue() < 0L
                || !(gameHash instanceof String)
                || !(hetHash instanceof String)
                || !isSha256Hex((String) gameHash)
                || !isSha256Hex((String) hetHash)) {
                throw new ConflictFailure(
                    FailureKind.INVALID_STATE,
                    "conflict state field types or values are invalid"
                );
            }
            return new StateMetadata(
                ((Number) createdAt).longValue(),
                (String) gameHash,
                (String) hetHash
            );
        } catch (ConflictFailure e) {
            throw e;
        } catch (JSONException | ClassCastException e) {
            throw new ConflictFailure(
                FailureKind.INVALID_STATE,
                "conflict state is missing a required field",
                e
            );
        }
    }

    private static boolean isIntegralJsonNumber(Object value) {
        return value instanceof Byte
            || value instanceof Short
            || value instanceof Integer
            || value instanceof Long;
    }

    private static JSONObject readState(File stateFile) throws ConflictFailure {
        byte[] bytes = readExact(stateFile, MAX_STATE_BYTES);
        try {
            String text = decodeStrictUtf8(bytes);
            return new JSONObject(text);
        } catch (Exception e) {
            throw new ConflictFailure(
                FailureKind.INVALID_STATE,
                "conflict state is not valid UTF-8 JSON",
                e
            );
        }
    }

    private static void writeState(
        File directory,
        String sceneName,
        long createdAt,
        String gameHash,
        String hetHash
    ) throws IOException {
        JSONObject state = new JSONObject();
        try {
            state.put("format_version", STATE_FORMAT_VERSION);
            state.put("scene_name", sceneName);
            state.put("created_at", createdAt);
            state.put("game_sha256", gameHash);
            state.put("het_sha256", hetHash);
        } catch (JSONException e) {
            throw new ConflictFailure(
                FailureKind.IO,
                "could not encode conflict state",
                e
            );
        }
        writeExact(
            new File(directory, STATE_FILE_NAME),
            state.toString().getBytes(StandardCharsets.UTF_8)
        );
    }

    private static byte[] readExact(File file, int maxBytes)
        throws ConflictFailure {
        if (!file.isFile()) {
            throw new ConflictFailure(
                FailureKind.MISSING_FILE,
                "conflict file is missing"
            );
        }
        try (InputStream input = new FileInputStream(file);
             ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[8192];
            int read;
            int total = 0;
            while ((read = input.read(buffer)) != -1) {
                if (read == 0) {
                    continue;
                }
                if (total > maxBytes - read) {
                    throw new ConflictFailure(
                        FailureKind.INVALID_CANDIDATE,
                        "conflict file exceeds the Scene limit"
                    );
                }
                output.write(buffer, 0, read);
                total += read;
            }
            if (total == 0) {
                throw new ConflictFailure(
                    FailureKind.INVALID_CANDIDATE,
                    "conflict file is empty"
                );
            }
            return output.toByteArray();
        } catch (ConflictFailure e) {
            throw e;
        } catch (IOException e) {
            throw new ConflictFailure(
                FailureKind.IO,
                "could not read conflict file",
                e
            );
        }
    }

    private static HashedFile hashFile(File file, int maxBytes)
        throws ConflictFailure {
        if (!file.isFile()) {
            throw new ConflictFailure(
                FailureKind.MISSING_FILE,
                "conflict file is missing"
            );
        }
        MessageDigest digest = SceneDigest.newSha256();
        try (InputStream input = new FileInputStream(file)) {
            byte[] buffer = new byte[8192];
            int read;
            int total = 0;
            while ((read = input.read(buffer)) != -1) {
                if (read == 0) {
                    continue;
                }
                if (total > maxBytes - read) {
                    throw new ConflictFailure(
                        FailureKind.INVALID_CANDIDATE,
                        "conflict file exceeds the Scene limit"
                    );
                }
                digest.update(buffer, 0, read);
                total += read;
            }
            if (total == 0) {
                throw new ConflictFailure(
                    FailureKind.INVALID_CANDIDATE,
                    "conflict file is empty"
                );
            }
            return new HashedFile(SceneDigest.lowerHex(digest.digest()));
        } catch (ConflictFailure e) {
            throw e;
        } catch (IOException e) {
            throw new ConflictFailure(
                FailureKind.IO,
                "could not hash conflict file",
                e
            );
        }
    }

    private static void writeExact(File file, byte[] bytes) throws IOException {
        TransactionalSceneSlots.writeExact(
            file,
            bytes,
            ConflictStore::slotIoFailure
        );
    }

    private static boolean deleteRecursively(File file) {
        return TransactionalSceneSlots.deleteRecursively(file);
    }

    private static String decodeStrictUtf8(byte[] bytes)
        throws CharacterCodingException {
        return TransactionalSceneSlots.decodeStrictUtf8(bytes);
    }

    private static String sha256Hex(byte[] bytes) {
        return SceneDigest.sha256Hex(bytes);
    }

    private static boolean isSha256Hex(String value) {
        if (value == null || value.length() != 64) {
            return false;
        }
        for (int index = 0; index < value.length(); index++) {
            char c = value.charAt(index);
            boolean hex = (c >= '0' && c <= '9')
                || (c >= 'a' && c <= 'f');
            if (!hex) {
                return false;
            }
        }
        return true;
    }

    private static List<String> immutableSorted(List<String> values) {
        List<String> copy = new ArrayList<>(values);
        Collections.sort(copy);
        return Collections.unmodifiableList(copy);
    }

    private static Context requireContext(Context context) {
        if (context == null) {
            throw new IllegalArgumentException("context cannot be null");
        }
        return context;
    }
}
