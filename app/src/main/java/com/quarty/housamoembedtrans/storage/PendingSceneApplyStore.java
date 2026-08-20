package com.quarty.housamoembedtrans.storage;

import com.quarty.housamoembedtrans.util.IoUtils;

import android.content.Context;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Durable one-Scene offline apply directive.
 *
 * <p>The directory itself is the publication unit.  There is at most one
 * directive per SceneName and no claim/lease/running identity is persisted.
 * A malformed directive is discarded by {@link #recover()} and is never
 * reconstructed from the formal Scene mirror.</p>
 */
public final class PendingSceneApplyStore {
    public static final String DIRECTORY_NAME = "pending_scene_apply";
    public static final String SCENE_FILE_NAME = "scene.json";
    public static final String STATE_FILE_NAME = "state.json";
    public static final int STATE_FORMAT_VERSION = 1;
    private static final String INCOMING_PREFIX = ".incoming-";
    private static final String BACKUP_PREFIX = ".backup-";
    private static final int MAX_STATE_BYTES = 16 * 1024;

    public enum FailureKind {
        INVALID_NAME,
        INVALID_STATE,
        INVALID_CANDIDATE,
        MISSING_FILE,
        HASH_MISMATCH,
        IO
    }

    public static final class PendingFailure extends IOException {
        private static final long serialVersionUID = 1L;
        public final FailureKind kind;

        PendingFailure(FailureKind kind, String message) {
            super(message);
            this.kind = kind;
        }

        PendingFailure(FailureKind kind, String message, Throwable cause) {
            super(message, cause);
            this.kind = kind;
        }
    }

    public static final class PendingRecord {
        public final String sceneName;
        public final byte[] candidateBytes;
        public final long createdAtMillis;
        public final String expectedGameSha256;
        public final String candidateSha256;
        public final boolean overwriteIfGameChanged;

        private PendingRecord(
            String sceneName,
            byte[] candidateBytes,
            long createdAtMillis,
            String expectedGameSha256,
            String candidateSha256,
            boolean overwriteIfGameChanged
        ) {
            this.sceneName = sceneName;
            this.candidateBytes = candidateBytes;
            this.createdAtMillis = createdAtMillis;
            this.expectedGameSha256 = expectedGameSha256;
            this.candidateSha256 = candidateSha256;
            this.overwriteIfGameChanged = overwriteIfGameChanged;
        }
    }

    public static final class RecoveryReport {
        public final List<String> validSceneNames;
        public final List<String> discardedSceneNames;

        private RecoveryReport(
            List<String> validSceneNames,
            List<String> discardedSceneNames
        ) {
            this.validSceneNames = immutableSorted(validSceneNames);
            this.discardedSceneNames = immutableSorted(discardedSceneNames);
        }
    }

    @FunctionalInterface
    public interface CandidateValidator {
        void validate(String sceneName, byte[] candidateBytes) throws Exception;
    }

    private final File rootDirectory;
    private final CandidateValidator candidateValidator;

    public PendingSceneApplyStore(Context context) {
        this(
            new File(
                requireContext(context).getFilesDir(),
                DIRECTORY_NAME
            ),
            new SceneStore(context)
        );
    }

    /** Explicit directory/validator seam for host fixtures. */
    public PendingSceneApplyStore(File rootDirectory, SceneStore sceneValidator) {
        this(
            rootDirectory,
            (sceneName, bytes) -> sceneValidator.validateRawSceneBytes(
                sceneName,
                bytes
            )
        );
    }

    /** Pure host seam when Android SceneStore validation is unavailable. */
    public PendingSceneApplyStore(
        File rootDirectory,
        CandidateValidator candidateValidator
    ) {
        if (rootDirectory == null || candidateValidator == null) {
            throw new IllegalArgumentException(
                "rootDirectory and candidateValidator are required"
            );
        }
        this.rootDirectory = rootDirectory;
        this.candidateValidator = candidateValidator;
    }

    public File getDirectory() {
        return rootDirectory;
    }

    private File formalDirectory(String sceneName) {
        return new File(rootDirectory, sceneName);
    }

    /**
     * Atomically replaces the one directive for a SceneName.  The expected
     * game hash is supplied by the conflict snapshot, not inferred from the
     * current mirror at write time.
     */
    public synchronized PendingRecord save(
        String sceneName,
        byte[] candidateBytes,
        String expectedGameSha256,
        boolean overwriteIfGameChanged
    ) throws IOException {
        sceneName = requireSceneName(sceneName);
        expectedGameSha256 = requireSha256(
            expectedGameSha256,
            "expected_game_sha256"
        );
        try {
            candidateValidator.validate(
                sceneName,
                candidateBytes
            );
        } catch (Exception e) {
            throw new PendingFailure(
                FailureKind.INVALID_CANDIDATE,
                "pending candidate is not a valid Scene",
                e
            );
        }
        byte[] exactCandidateBytes = candidateBytes;
        String candidateHash = sha256Hex(exactCandidateBytes);
        normalizeScene(sceneName);
        ensureDirectory(rootDirectory);

        File incoming = incomingDirectory(sceneName);
        if (incoming.exists() || !incoming.mkdir()) {
            throw new PendingFailure(
                FailureKind.IO,
                "could not create pending incoming directory"
            );
        }
        File formal = formalDirectory(sceneName);
        File backup = backupDirectory(sceneName);
        long createdAt = System.currentTimeMillis();
        boolean oldMoved = false;
        boolean published = false;
        try {
            writeExact(
                new File(incoming, SCENE_FILE_NAME),
                exactCandidateBytes
            );
            writeState(
                incoming,
                sceneName,
                createdAt,
                expectedGameSha256,
                candidateHash,
                overwriteIfGameChanged
            );
            if (formal.exists()) {
                if (backup.exists()) {
                    throw new PendingFailure(
                        FailureKind.IO,
                        "fixed pending backup slot is not empty"
                    );
                }
                if (!formal.renameTo(backup)) {
                    throw new PendingFailure(
                        FailureKind.IO,
                        "could not stage previous pending directive"
                    );
                }
                oldMoved = true;
            }
            if (!incoming.renameTo(formal)) {
                throw new PendingFailure(
                    FailureKind.IO,
                    "could not publish pending directive"
                );
            }
            published = true;
            if (!deleteRecursively(backup)) {
                throw new PendingFailure(
                    FailureKind.IO,
                    "could not remove published pending backup"
                );
            }
            return new PendingRecord(
                sceneName,
                exactCandidateBytes,
                createdAt,
                expectedGameSha256,
                candidateHash,
                overwriteIfGameChanged
            );
        } catch (IOException e) {
            if (oldMoved
                && !formal.exists()
                && !backup.renameTo(formal)) {
                e.addSuppressed(new IOException(
                    "could not restore previous pending directive"
                ));
            }
            throw e;
        } finally {
            if (!published && incoming.exists()
                && !deleteRecursively(incoming)) {
                throw new PendingFailure(
                    FailureKind.IO,
                    "could not remove incomplete pending directive"
                );
            }
        }
    }

    /** Reads and verifies one complete directive. */
    public synchronized PendingRecord read(String sceneName) throws IOException {
        sceneName = requireSceneName(sceneName);
        normalizeScene(sceneName);
        File directory = formalDirectory(sceneName);
        return readDirectory(directory, sceneName);
    }

    /** Idempotently removes one directive after a successful apply. */
    public synchronized void remove(String sceneName) throws IOException {
        sceneName = requireSceneName(sceneName);
        normalizeScene(sceneName);
        File directory = formalDirectory(sceneName);
        if (directory.exists() && !deleteRecursively(directory)) {
            throw new PendingFailure(
                FailureKind.IO,
                "could not remove pending directive"
            );
        }
    }

    public synchronized boolean contains(String sceneName) {
        try {
            SceneStore.requireSceneName(sceneName);
        } catch (IllegalArgumentException e) {
            return false;
        }
        return formalDirectory(sceneName).isDirectory();
    }

    /**
     * Repairs private directories and discards malformed formal directives;
     * each discarded identity is returned to the caller for user-facing
     * notification without leaking hashes or internal paths.
     */
    public synchronized RecoveryReport recover() throws IOException {
        if (!rootDirectory.exists()) {
            return new RecoveryReport(
                Collections.emptyList(),
                Collections.emptyList()
            );
        }
        if (!rootDirectory.isDirectory()) {
            throw new PendingFailure(
                FailureKind.IO,
                "pending root is not a directory"
            );
        }
        File[] entries = rootDirectory.listFiles();
        if (entries == null) {
            throw new PendingFailure(
                FailureKind.IO,
                "could not enumerate pending root"
            );
        }
        List<String> valid = new ArrayList<>();
        List<String> discarded = new ArrayList<>();
        Set<String> sceneNames = new HashSet<>();
        for (File entry : entries) {
            String name = entry.getName();
            if (!entry.exists()) {
                continue;
            }
            if (name.startsWith(INCOMING_PREFIX)) {
                cleanupOrThrow(
                    entry,
                    "could not remove uncommitted pending incoming directory"
                );
                continue;
            }
            String sceneName = slotSceneName(entry, BACKUP_PREFIX);
            if (sceneName != null) {
                sceneNames.add(sceneName);
                continue;
            }
            if (entry.isDirectory() && SceneStore.isValidSceneName(name)) {
                sceneNames.add(name);
                continue;
            }
            if (name.startsWith(INCOMING_PREFIX)
                || name.startsWith(BACKUP_PREFIX)) {
                cleanupOrThrow(
                    entry,
                    "could not remove pending residue with unknown SceneName"
                );
            }
        }
        for (String sceneName : sceneNames) {
            boolean discardedFormal = normalizeScene(sceneName);
            File formal = formalDirectory(sceneName);
            if (formal.isDirectory()) {
                readDirectory(formal, sceneName);
                valid.add(sceneName);
            } else if (discardedFormal) {
                discarded.add(sceneName);
            }
        }
        return new RecoveryReport(valid, discarded);
    }

    /** Returns valid pending names after per-entry recovery. */
    public synchronized List<String> listPendingSceneNames() throws IOException {
        return recover().validSceneNames;
    }

    /**
     * Normalizes one Scene's transaction slots before a read or a new write.
     * Returns true only when a damaged formal directive was discarded; an
     * incoming-only slot is uncommitted and is deliberately not reported.
     */
    private boolean normalizeScene(String sceneName) throws IOException {
        ensureDirectory(rootDirectory);
        File formal = formalDirectory(sceneName);
        boolean formalValid = false;
        PendingFailure formalFailure = null;
        if (formal.exists()) {
            try {
                readDirectory(formal, sceneName);
                formalValid = true;
            } catch (PendingFailure e) {
                if (e.kind == FailureKind.IO) {
                    throw e;
                }
                formalFailure = e;
            }
        }

        cleanupAllIncoming();

        if (formalValid) {
            for (File backup : backupDirectoriesForCleanup(sceneName)) {
                cleanupOrThrow(
                    backup,
                    "could not remove stale pending backup directory"
                );
            }
            return false;
        }

        List<File> validBackups = new ArrayList<>();
        for (File backup : slotDirectories(sceneName, BACKUP_PREFIX)) {
            try {
                readDirectory(backup, sceneName);
                validBackups.add(backup);
            } catch (PendingFailure e) {
                if (e.kind == FailureKind.IO) {
                    throw e;
                }
                cleanupOrThrow(
                    backup,
                    "could not remove invalid pending backup directory"
                );
            }
        }

        if (validBackups.size() > 1) {
            throw new PendingFailure(
                FailureKind.IO,
                "multiple valid pending backups have no deterministic winner"
            );
        }
        if (validBackups.size() == 1) {
            if (formal.exists()) {
                cleanupOrThrow(
                    formal,
                    "could not remove damaged pending directive"
                );
            }
            if (!validBackups.get(0).renameTo(formal)) {
                throw new PendingFailure(
                    FailureKind.IO,
                    "could not restore pending backup"
                );
            }
            return false;
        }
        if (formalFailure != null) {
            cleanupOrThrow(
                formal,
                "could not discard damaged pending directive"
            );
            return true;
        }
        return false;
    }

    private List<File> slotDirectories(String sceneName, String prefix)
        throws PendingFailure {
        List<File> matches = new ArrayList<>();
        if (!rootDirectory.isDirectory()) {
            return matches;
        }
        File[] entries = rootDirectory.listFiles();
        if (entries == null) {
            throw new PendingFailure(
                FailureKind.IO,
                "could not enumerate pending root"
            );
        }
        for (File entry : entries) {
            if (sceneName.equals(slotSceneName(entry, prefix))) {
                matches.add(entry);
            }
        }
        return matches;
    }

    private List<File> backupDirectoriesForCleanup(String sceneName)
        throws PendingFailure {
        List<File> matches = new ArrayList<>();
        if (!rootDirectory.isDirectory()) {
            return matches;
        }
        File[] entries = rootDirectory.listFiles();
        if (entries == null) {
            throw new PendingFailure(
                FailureKind.IO,
                "could not enumerate pending root"
            );
        }
        for (File entry : entries) {
            if (!entry.getName().startsWith(BACKUP_PREFIX)) {
                continue;
            }
            if (backupBelongsToScene(entry, sceneName)) {
                matches.add(entry);
            }
        }
        return matches;
    }

    private static boolean backupBelongsToScene(
        File entry,
        String sceneName
    ) throws PendingFailure {
        String suffix = entry.getName().substring(BACKUP_PREFIX.length());
        String directName = SceneStore.isValidSceneName(suffix) ? suffix : null;
        String legacyName = legacySceneName(suffix);
        String stateName = slotSceneNameFromState(entry, BACKUP_PREFIX);
        if (stateName != null) {
            if (!stateName.equals(directName) && !stateName.equals(legacyName)) {
                throw new PendingFailure(
                    FailureKind.INVALID_STATE,
                    "pending backup state SceneName does not match its slot"
                );
            }
            return sceneName.equals(stateName);
        }
        if (directName != null && legacyName != null
            && !directName.equals(legacyName)) {
            throw new PendingFailure(
                FailureKind.IO,
                "pending backup identity is ambiguous without state"
            );
        }
        return sceneName.equals(directName != null ? directName : legacyName);
    }

    private void cleanupAllIncoming() throws PendingFailure {
        if (!rootDirectory.isDirectory()) {
            return;
        }
        File[] entries = rootDirectory.listFiles();
        if (entries == null) {
            throw new PendingFailure(
                FailureKind.IO,
                "could not enumerate pending root"
            );
        }
        for (File entry : entries) {
            if (entry.getName().startsWith(INCOMING_PREFIX)) {
                cleanupOrThrow(
                    entry,
                    "could not remove uncommitted pending incoming directory"
                );
            }
        }
    }

    private static String slotSceneName(File entry, String prefix)
        throws PendingFailure {
        if (entry == null || !entry.getName().startsWith(prefix)) {
            return null;
        }
        String stateName = slotSceneNameFromState(entry, prefix);
        if (stateName != null) {
            String suffix = entry.getName().substring(prefix.length());
            String directName = SceneStore.isValidSceneName(suffix) ? suffix : null;
            String legacyName = legacySceneName(suffix);
            if (stateName.equals(directName) || stateName.equals(legacyName)) {
                return stateName;
            }
            throw new PendingFailure(
                FailureKind.INVALID_STATE,
                "pending backup state SceneName does not match its slot"
            );
        }
        String suffix = entry.getName().substring(prefix.length());
        String legacyName = legacySceneName(suffix);
        String directName = SceneStore.isValidSceneName(suffix) ? suffix : null;
        if (legacyName != null && directName != null
            && !legacyName.equals(directName)) {
            throw new PendingFailure(
                FailureKind.IO,
                "pending residue SceneName is ambiguous without state"
            );
        }
        return legacyName != null ? legacyName : directName;
    }

    private static String slotSceneNameFromState(File entry, String prefix)
        throws PendingFailure {
        if (entry == null || !entry.getName().startsWith(prefix)) {
            return null;
        }
        File stateFile = new File(entry, STATE_FILE_NAME);
        if (!stateFile.isFile()) {
            return null;
        }
        try (InputStream input = new FileInputStream(stateFile)) {
            byte[] bytes = IoUtils.readAllBytesLimited(input, MAX_STATE_BYTES);
            JSONObject state = new JSONObject(decodeStrictUtf8(bytes));
            Object value = state.get("scene_name");
            return value instanceof String
                && SceneStore.isValidSceneName((String) value)
                ? (String) value
                : null;
        } catch (IOException e) {
            throw new PendingFailure(
                FailureKind.IO,
                "could not inspect pending slot state",
                e
            );
        } catch (Exception ignored) {
            return null;
        }
    }

    private static String legacySceneName(String suffix) {
        if (suffix.length() > 37
            && suffix.charAt(suffix.length() - 37) == '-') {
            String uuid = suffix.substring(suffix.length() - 36);
            String sceneName = suffix.substring(0, suffix.length() - 37);
            if (SceneStore.isValidSceneName(sceneName) && isUuid(uuid)) {
                return sceneName;
            }
        }
        return SceneStore.isValidSceneName(suffix) ? suffix : null;
    }

    private static boolean isUuid(String value) {
        if (value.length() != 36) {
            return false;
        }
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            boolean hex = (c >= '0' && c <= '9')
                || (c >= 'a' && c <= 'f')
                || (c >= 'A' && c <= 'F');
            if (i == 8 || i == 13 || i == 18 || i == 23) {
                if (c != '-') {
                    return false;
                }
            } else if (!hex) {
                return false;
            }
        }
        return true;
    }

    private File incomingDirectory(String sceneName) {
        return new File(rootDirectory, INCOMING_PREFIX + sceneName);
    }

    private File backupDirectory(String sceneName) {
        return new File(rootDirectory, BACKUP_PREFIX + sceneName);
    }

    private static void cleanupOrThrow(File file, String message)
        throws PendingFailure {
        if (file.exists() && !deleteRecursively(file)) {
            throw new PendingFailure(FailureKind.IO, message);
        }
    }

    private static boolean isDiscardable(FailureKind kind) {
        return kind == FailureKind.INVALID_NAME
            || kind == FailureKind.INVALID_STATE
            || kind == FailureKind.INVALID_CANDIDATE
            || kind == FailureKind.MISSING_FILE
            || kind == FailureKind.HASH_MISMATCH;
    }

    private PendingRecord readDirectory(File directory, String expectedName)
        throws IOException {
        if (!directory.isDirectory()) {
            throw new PendingFailure(
                FailureKind.MISSING_FILE,
                "pending directory is missing"
            );
        }
        File sceneFile = new File(directory, SCENE_FILE_NAME);
        File stateFile = new File(directory, STATE_FILE_NAME);
        if (!sceneFile.isFile() || !stateFile.isFile()) {
            throw new PendingFailure(
                FailureKind.MISSING_FILE,
                "pending directory is incomplete"
            );
        }
        byte[] candidate;
        try (InputStream input = new FileInputStream(sceneFile)) {
            candidate = IoUtils.readAllBytesLimited(
                input,
                SceneStore.MAX_SCENE_BYTES
            );
        } catch (IoUtils.InputLimitExceededException e) {
            // The candidate is user/content data.  An oversized body is
            // permanently invalid for this directive and must be discarded,
            // not retried as a transient filesystem failure.
            throw new PendingFailure(
                FailureKind.INVALID_CANDIDATE,
                "pending candidate exceeds the Scene size limit",
                e
            );
        } catch (IOException e) {
            throw new PendingFailure(
                FailureKind.IO,
                "could not read pending candidate",
                e
            );
        } catch (Exception e) {
            throw new PendingFailure(
                FailureKind.IO,
                "could not read pending candidate",
                e
            );
        }
        JSONObject state;
        try (InputStream input = new FileInputStream(stateFile)) {
            byte[] bytes = IoUtils.readAllBytesLimited(input, MAX_STATE_BYTES);
            state = new JSONObject(decodeStrictUtf8(bytes));
        } catch (Exception e) {
            throw new PendingFailure(
                FailureKind.INVALID_STATE,
                "pending state is not valid UTF-8 JSON",
                e
            );
        }
        String sceneName = parseStateName(state, expectedName);
        long createdAt = parseCreatedAt(state);
        String expectedHash = parseSha(state, "expected_game_sha256");
        String candidateHash = parseSha(state, "candidate_sha256");
        boolean overwrite = parseBoolean(state, "overwrite_if_game_changed");
        try {
            candidateValidator.validate(sceneName, candidate);
            String actualHash = sha256Hex(candidate);
            if (!candidateHash.equals(actualHash)) {
                throw new PendingFailure(
                    FailureKind.HASH_MISMATCH,
                    "pending candidate hash does not match state"
                );
            }
        } catch (PendingFailure e) {
            throw e;
        } catch (Exception e) {
            throw new PendingFailure(
                FailureKind.INVALID_CANDIDATE,
                "pending candidate is not a valid Scene",
                e
            );
        }
        return new PendingRecord(
            sceneName,
            candidate,
            createdAt,
            expectedHash,
            candidateHash,
            overwrite
        );
    }

    private static String parseStateName(JSONObject state, String expected)
        throws PendingFailure {
        ensureExactStateKeys(state);
        try {
            Object value = state.get("scene_name");
            if (!(value instanceof String)
                || !expected.equals(SceneStore.requireSceneName((String) value))) {
                throw new PendingFailure(
                    FailureKind.INVALID_STATE,
                    "pending state SceneName does not match directory"
                );
            }
            return (String) value;
        } catch (JSONException | IllegalArgumentException e) {
            throw new PendingFailure(
                FailureKind.INVALID_STATE,
                "pending state SceneName is invalid",
                e
            );
        }
    }

    private static long parseCreatedAt(JSONObject state) throws PendingFailure {
        try {
            Object value = state.get("created_at");
            if (!(value instanceof Byte)
                && !(value instanceof Short)
                && !(value instanceof Integer)
                && !(value instanceof Long)) {
                throw new PendingFailure(
                    FailureKind.INVALID_STATE,
                    "pending created_at must be an integer"
                );
            }
            long createdAt = ((Number) value).longValue();
            if (createdAt < 0L) {
                throw new PendingFailure(
                    FailureKind.INVALID_STATE,
                    "pending created_at must be non-negative"
                );
            }
            return createdAt;
        } catch (JSONException e) {
            throw new PendingFailure(
                FailureKind.INVALID_STATE,
                "pending created_at is missing",
                e
            );
        }
    }

    private static String parseSha(JSONObject state, String key)
        throws PendingFailure {
        try {
            Object value = state.get(key);
            if (!(value instanceof String)) {
                throw new PendingFailure(
                    FailureKind.INVALID_STATE,
                    key + " must be a string"
                );
            }
            return requireSha256((String) value, key);
        } catch (JSONException e) {
            throw new PendingFailure(
                FailureKind.INVALID_STATE,
                key + " is missing",
                e
            );
        }
    }

    private static boolean parseBoolean(JSONObject state, String key)
        throws PendingFailure {
        try {
            Object value = state.get(key);
            if (!(value instanceof Boolean)) {
                throw new PendingFailure(
                    FailureKind.INVALID_STATE,
                    key + " must be a boolean"
                );
            }
            return (Boolean) value;
        } catch (JSONException e) {
            throw new PendingFailure(
                FailureKind.INVALID_STATE,
                key + " is missing",
                e
            );
        }
    }

    private static void ensureExactStateKeys(JSONObject state)
        throws PendingFailure {
        if (state.length() != 6) {
            throw new PendingFailure(
                FailureKind.INVALID_STATE,
                "pending state must contain exactly six fields"
            );
        }
        try {
            Object version = state.get("format_version");
            if (!(version instanceof Byte)
                && !(version instanceof Short)
                && !(version instanceof Integer)
                && !(version instanceof Long)
                || ((Number) version).longValue() != STATE_FORMAT_VERSION) {
                throw new PendingFailure(
                    FailureKind.INVALID_STATE,
                    "pending format_version is invalid"
                );
            }
        } catch (JSONException e) {
            throw new PendingFailure(
                FailureKind.INVALID_STATE,
                "pending format_version is missing",
                e
            );
        }
    }

    private static void writeState(
        File directory,
        String sceneName,
        long createdAt,
        String expectedHash,
        String candidateHash,
        boolean overwrite
    ) throws IOException {
        JSONObject state = new JSONObject();
        try {
            state.put("format_version", STATE_FORMAT_VERSION);
            state.put("scene_name", sceneName);
            state.put("created_at", createdAt);
            state.put("expected_game_sha256", expectedHash);
            state.put("candidate_sha256", candidateHash);
            state.put("overwrite_if_game_changed", overwrite);
        } catch (JSONException e) {
            throw new PendingFailure(
                FailureKind.IO,
                "could not encode pending state",
                e
            );
        }
        writeExact(
            new File(directory, STATE_FILE_NAME),
            state.toString().getBytes(StandardCharsets.UTF_8)
        );
    }

    private static void writeExact(File file, byte[] bytes) throws IOException {
        File parent = file.getParentFile();
        if (parent == null || !parent.isDirectory()) {
            throw new PendingFailure(
                FailureKind.IO,
                "pending file parent is missing"
            );
        }
        try (FileOutputStream output = new FileOutputStream(file)) {
            output.write(bytes);
            output.flush();
            output.getFD().sync();
        } catch (IOException e) {
            throw new PendingFailure(
                FailureKind.IO,
                "could not write pending file",
                e
            );
        }
    }

    private static void ensureDirectory(File directory) throws IOException {
        if (directory.isDirectory()) {
            return;
        }
        if (directory.exists()
            || (!directory.mkdirs() && !directory.isDirectory())) {
            throw new PendingFailure(
                FailureKind.IO,
                "could not create pending root"
            );
        }
    }

    private static String requireSceneName(String sceneName)
        throws PendingFailure {
        try {
            return SceneStore.requireSceneName(sceneName);
        } catch (IllegalArgumentException e) {
            throw new PendingFailure(
                FailureKind.INVALID_NAME,
                "invalid pending SceneName",
                e
            );
        }
    }

    private static String requireSha256(String value, String key)
        throws PendingFailure {
        if (value == null || value.length() != 64) {
            throw new PendingFailure(
                FailureKind.INVALID_STATE,
                key + " must be a lowercase SHA-256"
            );
        }
        for (int index = 0; index < value.length(); index++) {
            char c = value.charAt(index);
            if (!((c >= '0' && c <= '9')
                || (c >= 'a' && c <= 'f'))) {
                throw new PendingFailure(
                    FailureKind.INVALID_STATE,
                    key + " must be a lowercase SHA-256"
                );
            }
        }
        return value;
    }

    private static String sha256Hex(byte[] bytes) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(bytes);
            StringBuilder output = new StringBuilder(64);
            for (byte value : digest) {
                output.append(Character.forDigit((value >>> 4) & 0xf, 16));
                output.append(Character.forDigit(value & 0xf, 16));
            }
            return output.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is unavailable", e);
        }
    }

    private static String decodeStrictUtf8(byte[] bytes)
        throws CharacterCodingException {
        return StandardCharsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(bytes))
            .toString();
    }

    private static boolean deleteRecursively(File file) {
        if (file == null || !file.exists()) {
            return true;
        }
        if (file.isDirectory()) {
            File[] children = file.listFiles();
            if (children == null) {
                return false;
            }
            for (File child : children) {
                if (!deleteRecursively(child)) {
                    return false;
                }
            }
        }
        return file.delete();
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
