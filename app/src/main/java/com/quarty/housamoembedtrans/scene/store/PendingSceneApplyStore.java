package com.quarty.housamoembedtrans.scene.store;

import com.quarty.housamoembedtrans.util.IoUtils;

import android.content.Context;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
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

        long createdAt = System.currentTimeMillis();
        TransactionalSceneSlots.publishReplacement(
            rootDirectory,
            sceneName,
            INCOMING_PREFIX,
            BACKUP_PREFIX,
            PendingSceneApplyStore::slotIoFailure,
            incoming -> {
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
            }
        );
        return new PendingRecord(
            sceneName,
            exactCandidateBytes,
            createdAt,
            expectedGameSha256,
            candidateHash,
            overwriteIfGameChanged
        );
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
            String sceneName;
            try {
                sceneName = TransactionalSceneSlots.slotSceneName(
                    entry,
                    BACKUP_PREFIX,
                    STATE_FILE_NAME,
                    PendingSceneApplyStore::readSlotSceneName
                );
            } catch (TransactionalSceneSlots.SlotFailure e) {
                throw mapSlotFailure(e);
            }
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
        try {
            return TransactionalSceneSlots.normalize(
                rootDirectory,
                sceneName,
                INCOMING_PREFIX,
                BACKUP_PREFIX,
                STATE_FILE_NAME,
                PendingSceneApplyStore::readSlotSceneName,
                (directory, name) -> readDirectory(directory, name),
                failure -> failure instanceof PendingFailure
                    && ((PendingFailure) failure).kind == FailureKind.IO,
                PendingSceneApplyStore::slotIoFailure,
                true
            );
        } catch (TransactionalSceneSlots.SlotFailure e) {
            throw mapSlotFailure(e);
        }
    }

    private static String readSlotSceneName(File stateFile)
        throws TransactionalSceneSlots.SlotFailure {
        try (InputStream input = new FileInputStream(stateFile)) {
            byte[] bytes = IoUtils.readAllBytesLimited(input, MAX_STATE_BYTES);
            JSONObject state = new JSONObject(decodeStrictUtf8(bytes));
            Object value = state.get("scene_name");
            if (!(value instanceof String)
                || !SceneStore.isValidSceneName((String) value)) {
                throw new TransactionalSceneSlots.SlotFailure(
                    TransactionalSceneSlots.FailureKind.INVALID_STATE,
                    "pending slot state SceneName is invalid"
                );
            }
            return (String) value;
        } catch (TransactionalSceneSlots.SlotFailure e) {
            throw e;
        } catch (IOException e) {
            throw new TransactionalSceneSlots.SlotFailure(
                TransactionalSceneSlots.FailureKind.IO,
                "could not inspect pending slot state",
                e
            );
        } catch (Exception e) {
            throw new TransactionalSceneSlots.SlotFailure(
                TransactionalSceneSlots.FailureKind.INVALID_STATE,
                "pending slot state is invalid",
                e
            );
        }
    }

    private static PendingFailure mapSlotFailure(
        TransactionalSceneSlots.SlotFailure failure
    ) {
        return new PendingFailure(
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
            message = "pending file parent is missing";
        } else if ("could not write slot file".equals(message)) {
            message = "could not write pending file";
        }
        return new PendingFailure(FailureKind.IO, message, cause);
    }

    private static void cleanupOrThrow(File file, String message)
        throws PendingFailure {
        TransactionalSceneSlots.cleanupOrThrow(
            file,
            message,
            PendingSceneApplyStore::slotIoFailure
        );
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
        TransactionalSceneSlots.writeExact(
            file,
            bytes,
            PendingSceneApplyStore::slotIoFailure
        );
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
        return SceneDigest.sha256Hex(bytes);
    }

    private static String decodeStrictUtf8(byte[] bytes)
        throws CharacterCodingException {
        return TransactionalSceneSlots.decodeStrictUtf8(bytes);
    }

    private static boolean deleteRecursively(File file) {
        return TransactionalSceneSlots.deleteRecursively(file);
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
