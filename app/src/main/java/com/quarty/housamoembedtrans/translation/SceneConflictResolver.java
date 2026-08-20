package com.quarty.housamoembedtrans.translation;

import com.quarty.housamoembedtrans.storage.ConflictStore;
import com.quarty.housamoembedtrans.storage.SceneSyncSettings;
import com.quarty.housamoembedtrans.storage.SceneStore;

import java.io.IOException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.List;

/**
 * Decides one conflict mode outside the storage monitor.
 *
 * <p>ConflictStore only owns durable directory publication and reads.  The
 * sink callbacks below may perform Binder calls and wait for apply results;
 * they are intentionally invoked without holding any ConflictStore lock.</p>
 */
public final class SceneConflictResolver {
    public interface ApplySink {
        void applyGameCandidate(byte[] gameBytes) throws Exception;

        void applyHetCandidate(byte[] hetBytes) throws Exception;
    }

    public enum Kind {
        GAME_APPLIED,
        HET_APPLIED,
        MANUAL_RECORDED,
        ALREADY_PENDING,
        FAILED
    }

    public static final class Decision {
        public final String sceneName;
        public final String mode;
        public final Kind kind;
        public final ConflictStore.ConflictMetadata metadata;
        public final Exception error;

        private Decision(
            String sceneName,
            String mode,
            Kind kind,
            ConflictStore.ConflictMetadata metadata,
            Exception error
        ) {
            this.sceneName = sceneName;
            this.mode = mode;
            this.kind = kind;
            this.metadata = metadata;
            this.error = error;
        }

        public boolean isBlocked() {
            return kind != Kind.GAME_APPLIED
                && kind != Kind.HET_APPLIED;
        }
    }

    private final ConflictStore conflictStore;

    public SceneConflictResolver(ConflictStore conflictStore) {
        if (conflictStore == null) {
            throw new IllegalArgumentException(
                "conflictStore cannot be null"
            );
        }
        this.conflictStore = conflictStore;
    }

    /** Returns whether a formal conflict claims this SceneName already. */
    public boolean hasFormalConflict(String sceneName) {
        return conflictStore.hasFormalConflict(sceneName);
    }

    /** Returns all claimed formal identities, including corrupt records. */
    public List<String> listClaimedSceneNames() {
        return conflictStore.listClaimedSceneNames();
    }

    /** Strict sync seam: existing roots/list failures are not empty mirrors. */
    public List<String> listClaimedSceneNamesStrict()
        throws ConflictStore.ConflictFailure {
        return conflictStore.listClaimedSceneNamesStrict();
    }

    /** Reads one fixed pending conflict without exposing the storage monitor. */
    public ConflictStore.ConflictRecord readPending(String sceneName)
        throws ConflictStore.ConflictFailure {
        return conflictStore.read(sceneName);
    }

    /** Replaces both pending candidates as one durable publication unit. */
    public ConflictStore.ConflictMetadata replacePending(
        String sceneName,
        byte[] gameBytes,
        byte[] hetBytes
    ) throws IOException {
        return conflictStore.replace(sceneName, gameBytes, hetBytes);
    }

    /** Rebuilds a pending conflict even if an earlier formal directory vanished. */
    public ConflictStore.ConflictMetadata replaceOrPersistPending(
        String sceneName,
        byte[] gameBytes,
        byte[] hetBytes
    ) throws IOException {
        if (conflictStore.hasFormalConflict(sceneName)) {
            return conflictStore.replace(sceneName, gameBytes, hetBytes);
        }
        return conflictStore.persist(sceneName, gameBytes, hetBytes);
    }

    /** Removes a formal conflict after its chosen candidate is committed. */
    public void removeConflict(String sceneName) throws java.io.IOException {
        conflictStore.remove(sceneName);
    }

    /**
     * Reconciles a seeded claim against the exact current pair.  A matching
     * claim remains blocked for manual review; a stale claim is removed so the
     * caller can continue normal pair reconciliation.
     */
    public boolean retainCurrentClaim(
        String sceneName,
        byte[] gameBytes,
        byte[] hetBytes
    ) throws IOException {
        if (!conflictStore.hasFormalConflict(sceneName)) {
            return false;
        }
        ConflictStore.ConflictMetadata metadata =
            conflictStore.readMetadata(sceneName);
        if (sameCandidates(metadata, gameBytes, hetBytes)) {
            return true;
        }
        conflictStore.remove(sceneName);
        return false;
    }

    /**
     * Resolves one pair independently.  Automatic failure is reported as a
     * blocked result and never falls back to the other candidate; a formal
     * conflict is retained only when its candidates still match the current
     * pair, while stale records are invalidated before reconciliation.
     */
    public Decision resolve(
        String sceneName,
        byte[] gameBytes,
        byte[] hetBytes,
        String mode,
        ApplySink sink
    ) {
        SceneStore.requireSceneName(sceneName);
        String normalizedMode =
            SceneSyncSettings.normalizeConflictResolutionMode(mode);
        try {
            ConflictStore.validateCandidate(sceneName, gameBytes);
            ConflictStore.validateCandidate(sceneName, hetBytes);
            if (conflictStore.hasFormalConflict(sceneName)) {
                ConflictStore.ConflictMetadata metadata =
                    conflictStore.readMetadata(sceneName);
                if (sameCandidates(metadata, gameBytes, hetBytes)) {
                    return new Decision(
                        sceneName,
                        normalizedMode,
                        Kind.ALREADY_PENDING,
                        metadata,
                        null
                    );
                }
                try {
                    conflictStore.remove(sceneName);
                } catch (Exception e) {
                    return failed(sceneName, normalizedMode, e);
                }
            }

            if (SceneSyncSettings.CONFLICT_MODE_GAME.equals(normalizedMode)) {
                if (sink == null) {
                    return failed(
                        sceneName,
                        normalizedMode,
                        new IllegalArgumentException("apply sink is null")
                    );
                }
                try {
                    // The operation already owns these exact validated bytes;
                    // do not clone a potentially 32 MiB body for a callback.
                    sink.applyGameCandidate(gameBytes);
                    return new Decision(
                        sceneName,
                        normalizedMode,
                        Kind.GAME_APPLIED,
                        null,
                        null
                    );
                } catch (Exception e) {
                    return failed(sceneName, normalizedMode, e);
                }
            }
            if (SceneSyncSettings.CONFLICT_MODE_HET.equals(normalizedMode)) {
                if (sink == null) {
                    return failed(
                        sceneName,
                        normalizedMode,
                        new IllegalArgumentException("apply sink is null")
                    );
                }
                try {
                    sink.applyHetCandidate(hetBytes);
                    return new Decision(
                        sceneName,
                        normalizedMode,
                        Kind.HET_APPLIED,
                        null,
                        null
                    );
                } catch (Exception e) {
                    return failed(sceneName, normalizedMode, e);
                }
            }

            ConflictStore.ConflictMetadata metadata =
                conflictStore.persist(sceneName, gameBytes, hetBytes);
            return new Decision(
                sceneName,
                normalizedMode,
                Kind.MANUAL_RECORDED,
                metadata,
                null
            );
        } catch (Exception e) {
            return failed(sceneName, normalizedMode, e);
        }
    }

    private static Decision failed(
        String sceneName,
        String mode,
        Exception error
    ) {
        return new Decision(
            sceneName,
            mode,
            Kind.FAILED,
            null,
            error
        );
    }

    private static boolean sameCandidates(
        ConflictStore.ConflictMetadata metadata,
        byte[] gameBytes,
        byte[] hetBytes
    ) throws IOException {
        if (metadata == null) {
            return false;
        }
        return metadata.gameSha256.equals(sha256Hex(gameBytes))
            && metadata.hetSha256.equals(sha256Hex(hetBytes));
    }

    private static String sha256Hex(byte[] bytes) throws IOException {
        if (bytes == null) {
            throw new IOException("conflict candidate is null");
        }
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(bytes);
            StringBuilder result = new StringBuilder(digest.length * 2);
            for (byte value : digest) {
                result.append(String.format("%02x", value & 0xff));
            }
            return result.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IOException("SHA-256 is unavailable", e);
        }
    }
}
