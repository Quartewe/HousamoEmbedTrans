package com.quarty.housamoembedtrans.management.pending;

import com.quarty.housamoembedtrans.context.store.SceneContextStore;
import com.quarty.housamoembedtrans.scene.store.SceneStore;
import com.quarty.housamoembedtrans.translation.job.TranslationJobStore;

import org.json.JSONObject;

import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.regex.Pattern;

/** PendingProcess owner adapter for a complete Scene or one Scene language. */
public final class SceneLanguagePendingOwner implements PendingProcessOwner {

    public static final String KIND_SCENE = "scene";
    public static final String KIND_LANGUAGE = "language";
    private static final Pattern BASE64_URL_TOKEN = Pattern.compile(
        "[A-Za-z0-9_-]+"
    );

    private final SceneStore store;
    private final SceneContextStore contextStore;
    private final TranslationJobStore jobStore;
    private final String kind;

    private SceneLanguagePendingOwner(
        SceneStore store,
        SceneContextStore contextStore,
        TranslationJobStore jobStore,
        String kind
    ) {
        if (store == null || kind == null) {
            throw new IllegalArgumentException("store and kind are required");
        }
        if (KIND_SCENE.equals(kind)
            && (contextStore == null || jobStore == null)) {
            throw new IllegalArgumentException(
                "Scene pending owner requires Context and Translation stores"
            );
        }
        this.store = store;
        this.contextStore = contextStore;
        this.jobStore = jobStore;
        this.kind = kind;
    }

    public static SceneLanguagePendingOwner forScenes(
        SceneStore store,
        SceneContextStore contextStore,
        TranslationJobStore jobStore
    ) {
        return new SceneLanguagePendingOwner(
            store,
            contextStore,
            jobStore,
            KIND_SCENE
        );
    }

    public static SceneLanguagePendingOwner forLanguages(SceneStore store) {
        return new SceneLanguagePendingOwner(
            store,
            null,
            null,
            KIND_LANGUAGE
        );
    }

    /** Creates the stable reversible PendingProcess identity for one language. */
    public static String canonicalIdForLanguage(
        String sceneName,
        String language
    ) throws PendingProcessStore.PendingProcessException {
        try {
            return SceneStore.languageCanonicalId(sceneName, language);
        } catch (SceneStore.PendingException e) {
            throw mapFailure(e, "could not create language canonical id");
        }
    }

    /** Strictly checks whether a language PendingProcess belongs to a Scene. */
    public static boolean languageBelongsToScene(
        String languageCanonicalId,
        String sceneName
    ) throws PendingProcessStore.PendingProcessException {
        if (!SceneStore.isValidSceneName(sceneName)) {
            throw invalidCanonicalId("invalid Scene canonical id");
        }
        return sceneName.equals(
            parseLanguageIdentity(languageCanonicalId).sceneName
        );
    }

    @Override
    public String kind() {
        return kind;
    }

    /** Returns bounded confirmation details rather than the stored snapshot. */
    @Override
    public JSONObject previewMove(String canonicalId) throws Exception {
        requireCanonicalId(canonicalId);
        try {
            if (KIND_SCENE.equals(kind)) {
                return PendingProcessStore.copyJsonOrNull(
                    store.previewSceneForPending(canonicalId)
                );
            }
            LanguageIdentity identity = parseLanguageIdentity(canonicalId);
            return PendingProcessStore.copyJsonOrNull(
                store.previewLanguageForPending(
                    identity.sceneName,
                    identity.language
                )
            );
        } catch (SceneStore.PendingException e) {
            throw mapFailure(e, "could not preview " + kind);
        } catch (PendingProcessStore.PendingProcessException e) {
            throw e;
        } catch (Exception e) {
            throw ioFailure("could not preview " + kind, e);
        }
    }

    /** Captures the full restorable snapshot only after confirmation. */
    @Override
    public PendingProcessStore.MovePayload prepareMove(
        String canonicalId,
        String reason
    ) throws Exception {
        requireCanonicalId(canonicalId);
        try {
            JSONObject snapshot;
            JSONObject restoreMetadata = null;
            if (KIND_SCENE.equals(kind)) {
                snapshot = store.snapshotSceneForPending(canonicalId);
                JSONObject relations = contextStore
                    .snapshotSceneRelationsForPending(canonicalId);
                restoreMetadata = new JSONObject()
                    .put("mode", PendingProcessStore.RESTORE_MODE_SNAPSHOT)
                    .put("relations", relations);
            } else {
                LanguageIdentity identity = parseLanguageIdentity(canonicalId);
                snapshot = store.snapshotLanguageForPending(
                    identity.sceneName,
                    identity.language
                );
            }
            return PendingProcessStore.MovePayload.snapshot(
                kind,
                PendingProcessStore.copyJsonOrNull(snapshot),
                reason,
                restoreMetadata,
                null
            );
        } catch (SceneStore.PendingException e) {
            throw mapFailure(e, "could not snapshot " + kind);
        } catch (SceneContextStore.StorageException e) {
            throw mapFailure(e, "could not snapshot Scene relations");
        } catch (PendingProcessStore.PendingProcessException e) {
            throw e;
        } catch (Exception e) {
            throw ioFailure("could not snapshot " + kind, e);
        }
    }

    @Override
    public void hide(String canonicalId, JSONObject pendingEntry)
        throws Exception {
        JSONObject snapshot = snapshotFromEntry(canonicalId, pendingEntry);
        try {
            if (KIND_SCENE.equals(kind)) {
                store.hideSceneForPending(canonicalId, snapshot);
            } else {
                LanguageIdentity identity = parseLanguageIdentity(canonicalId);
                store.hideLanguageForPending(
                    identity.sceneName,
                    identity.language,
                    snapshot
                );
            }
        } catch (SceneStore.PendingException e) {
            throw mapFailure(e, "could not hide " + kind);
        } catch (PendingProcessStore.PendingProcessException e) {
            throw e;
        } catch (Exception e) {
            throw ioFailure("could not hide " + kind, e);
        }
    }

    @Override
    public void restore(String canonicalId, JSONObject pendingEntry)
        throws Exception {
        JSONObject snapshot = snapshotFromEntry(canonicalId, pendingEntry);
        try {
            if (KIND_SCENE.equals(kind)) {
                store.restoreSceneFromPending(canonicalId, snapshot);
                contextStore.restoreSceneRelationsFromPending(
                    canonicalId,
                    relationsFromEntry(canonicalId, pendingEntry)
                );
            } else {
                LanguageIdentity identity = parseLanguageIdentity(canonicalId);
                store.restoreLanguageFromPending(
                    identity.sceneName,
                    identity.language,
                    snapshot
                );
            }
        } catch (SceneStore.PendingException e) {
            throw mapFailure(e, "could not restore " + kind);
        } catch (SceneContextStore.StorageException e) {
            throw mapFailure(e, "could not restore Scene relations");
        } catch (PendingProcessStore.PendingProcessException e) {
            throw e;
        } catch (Exception e) {
            throw ioFailure("could not restore " + kind, e);
        }
    }

    @Override
    public void permanentlyDelete(
        String canonicalId,
        JSONObject pendingEntry
    ) throws Exception {
        JSONObject snapshot = snapshotFromEntry(canonicalId, pendingEntry);
        try {
            if (KIND_SCENE.equals(kind)) {
                store.permanentlyDeleteSceneFromPending(
                    canonicalId,
                    snapshot
                );
                contextStore.removeSceneRelationsForPending(
                    canonicalId,
                    relationsFromEntry(canonicalId, pendingEntry)
                );
                jobStore.cancelUnfinishedJobsForSceneForManagement(
                    canonicalId
                );
            } else {
                LanguageIdentity identity = parseLanguageIdentity(canonicalId);
                store.permanentlyDeleteLanguageFromPending(
                    identity.sceneName,
                    identity.language,
                    snapshot
                );
            }
        } catch (SceneStore.PendingException e) {
            throw mapFailure(e, "could not permanently delete " + kind);
        } catch (SceneContextStore.StorageException e) {
            throw mapFailure(e, "could not remove Scene relations");
        } catch (PendingProcessStore.PendingProcessException e) {
            throw e;
        } catch (Exception e) {
            throw ioFailure("could not permanently delete " + kind, e);
        }
    }

    private JSONObject snapshotFromEntry(
        String canonicalId,
        JSONObject pendingEntry
    ) throws PendingProcessStore.PendingProcessException {
        requireCanonicalId(canonicalId);
        if (pendingEntry == null
            || !kind.equals(pendingEntry.optString("kind", ""))
            || !canonicalId.equals(
                pendingEntry.optString("canonical_id", "")
            )) {
            throw new PendingProcessStore.PendingProcessException(
                PendingProcessStore.FailureKind.INVALID_STATE,
                "Scene/language pending entry identity does not match owner"
            );
        }
        JSONObject payload = pendingEntry.optJSONObject("payload");
        if (payload == null
            || !"snapshot".equals(payload.optString("type", ""))
            || !PendingProcessStore.hasExactlyKeys(payload, "type", "snapshot")) {
            throw new PendingProcessStore.PendingProcessException(
                PendingProcessStore.FailureKind.INVALID_STATE,
                "Scene/language pending entry is not a strict snapshot"
            );
        }
        JSONObject snapshot = payload.optJSONObject("snapshot");
        if (snapshot == null) {
            throw new PendingProcessStore.PendingProcessException(
                PendingProcessStore.FailureKind.INVALID_STATE,
                "Scene/language pending snapshot is missing"
            );
        }
        return PendingProcessStore.copyJsonValueObject(snapshot);
    }

    private JSONObject relationsFromEntry(
        String canonicalId,
        JSONObject pendingEntry
    ) throws PendingProcessStore.PendingProcessException {
        if (!KIND_SCENE.equals(kind)
            || pendingEntry == null
            || !KIND_SCENE.equals(pendingEntry.optString("kind", ""))
            || !canonicalId.equals(
                pendingEntry.optString("canonical_id", "")
            )) {
            throw new PendingProcessStore.PendingProcessException(
                PendingProcessStore.FailureKind.INVALID_STATE,
                "Scene relation pending entry identity does not match owner"
            );
        }
        JSONObject restore = pendingEntry.optJSONObject("restore");
        if (restore == null
            || !PendingProcessStore.hasExactlyKeys(restore, "mode", "relations")
            || !PendingProcessStore.RESTORE_MODE_SNAPSHOT.equals(
                restore.optString("mode", "")
            )) {
            throw new PendingProcessStore.PendingProcessException(
                PendingProcessStore.FailureKind.INVALID_STATE,
                "Scene relation restore metadata is invalid"
            );
        }
        JSONObject relations = restore.optJSONObject("relations");
        if (relations == null) {
            throw new PendingProcessStore.PendingProcessException(
                PendingProcessStore.FailureKind.INVALID_STATE,
                "Scene relation restore snapshot is missing"
            );
        }
        return PendingProcessStore.copyJsonValueObject(relations);
    }

    private void requireCanonicalId(String canonicalId)
        throws PendingProcessStore.PendingProcessException {
        if (KIND_SCENE.equals(kind)) {
            if (!SceneStore.isValidSceneName(canonicalId)) {
                throw invalidCanonicalId("invalid Scene canonical id");
            }
            return;
        }
        parseLanguageIdentity(canonicalId);
    }

    private static LanguageIdentity parseLanguageIdentity(String canonicalId)
        throws PendingProcessStore.PendingProcessException {
        if (canonicalId == null || canonicalId.length() > 384) {
            throw invalidCanonicalId("invalid language canonical id");
        }
        int delimiter = canonicalId.indexOf('.');
        if (delimiter < 1
            || delimiter != canonicalId.lastIndexOf('.')
            || delimiter == canonicalId.length() - 1) {
            throw invalidCanonicalId(
                "language canonical id must contain one separator"
            );
        }
        String sceneName = canonicalId.substring(0, delimiter);
        String token = canonicalId.substring(delimiter + 1);
        if (!SceneStore.isValidSceneName(sceneName)
            || !BASE64_URL_TOKEN.matcher(token).matches()) {
            throw invalidCanonicalId("language canonical id is invalid");
        }
        final byte[] utf8;
        final String language;
        try {
            utf8 = Base64.getUrlDecoder().decode(token);
            language = StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(utf8))
                .toString();
        } catch (IllegalArgumentException | CharacterCodingException e) {
            throw new PendingProcessStore.PendingProcessException(
                PendingProcessStore.FailureKind.INVALID_ARGUMENT,
                "language canonical id encoding is invalid",
                e
            );
        }
        try {
            if (!canonicalId.equals(
                SceneStore.languageCanonicalId(sceneName, language)
            )) {
                throw invalidCanonicalId(
                    "language canonical id is not canonical"
                );
            }
        } catch (SceneStore.PendingException e) {
            throw mapFailure(e, "language canonical id is invalid");
        }
        return new LanguageIdentity(sceneName, language);
    }

    private static PendingProcessStore.PendingProcessException mapFailure(
        SceneStore.PendingException failure,
        String action
    ) {
        PendingProcessStore.FailureKind kind;
        switch (failure.kind) {
            case NOT_FOUND:
                kind = PendingProcessStore.FailureKind.NOT_FOUND;
                break;
            case CONFLICT:
                kind = PendingProcessStore.FailureKind.CONFLICT;
                break;
            case INVALID_ARGUMENT:
                kind = PendingProcessStore.FailureKind.INVALID_ARGUMENT;
                break;
            case INVALID_STATE:
                kind = PendingProcessStore.FailureKind.INVALID_STATE;
                break;
            case IO:
            default:
                kind = PendingProcessStore.FailureKind.IO;
                break;
        }
        String detail = failure.getMessage();
        return new PendingProcessStore.PendingProcessException(
            kind,
            detail == null || detail.isEmpty()
                ? action
                : action + ": " + detail,
            failure
        );
    }

    private static PendingProcessStore.PendingProcessException mapFailure(
        SceneContextStore.StorageException failure,
        String action
    ) {
        PendingProcessStore.FailureKind kind;
        switch (failure.kind) {
            case NOT_FOUND:
                kind = PendingProcessStore.FailureKind.NOT_FOUND;
                break;
            case CONFLICT:
            case ALREADY_EXISTS:
            case INVALID_ACTIVE_GROUP:
                kind = PendingProcessStore.FailureKind.CONFLICT;
                break;
            case INVALID_ARGUMENT:
                kind = PendingProcessStore.FailureKind.INVALID_ARGUMENT;
                break;
            case INVALID_STATE:
                kind = PendingProcessStore.FailureKind.INVALID_STATE;
                break;
            case IO:
            default:
                kind = PendingProcessStore.FailureKind.IO;
                break;
        }
        String detail = failure.getMessage();
        return new PendingProcessStore.PendingProcessException(
            kind,
            detail == null || detail.isEmpty()
                ? action
                : action + ": " + detail,
            failure
        );
    }

    private static PendingProcessStore.PendingProcessException ioFailure(
        String message,
        Exception failure
    ) {
        return new PendingProcessStore.PendingProcessException(
            PendingProcessStore.FailureKind.IO,
            message,
            failure
        );
    }

    private static PendingProcessStore.PendingProcessException
        invalidCanonicalId(String message) {
        return new PendingProcessStore.PendingProcessException(
            PendingProcessStore.FailureKind.INVALID_ARGUMENT,
            message
        );
    }

    private static final class LanguageIdentity {
        private final String sceneName;
        private final String language;

        private LanguageIdentity(String sceneName, String language) {
            this.sceneName = sceneName;
            this.language = language;
        }
    }
}
