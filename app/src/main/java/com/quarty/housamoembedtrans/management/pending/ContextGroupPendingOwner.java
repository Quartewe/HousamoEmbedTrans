package com.quarty.housamoembedtrans.management.pending;

import com.quarty.housamoembedtrans.context.store.SceneContextStore;
import com.quarty.housamoembedtrans.scene.store.SceneStore;
import com.quarty.housamoembedtrans.summary.job.SummaryJobStore;
import com.quarty.housamoembedtrans.translation.job.TranslationJobStore;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.IOException;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

/**
 * Pending owner adapter for the Context and Group stores.
 *
 * <p>SceneContextStore already owns the multi-file relationship/index
 * transaction.  This adapter only supplies the owner-side operation and
 * passes the exact snapshot through that transaction; it never edits an
 * entity or an index directly.</p>
 */
public final class ContextGroupPendingOwner implements PendingProcessOwner {

    public static final String KIND_CONTEXT = "context";
    public static final String KIND_GROUP = "group";

    private final SceneContextStore store;
    private final SummaryJobStore summaryJobStore;
    private final TranslationJobStore translationJobStore;
    private final PendingProcessStore pendingProcessStore;
    private final SceneStore sceneStore;
    private final String kind;

    private ContextGroupPendingOwner(
        SceneContextStore store,
        SummaryJobStore summaryJobStore,
        TranslationJobStore translationJobStore,
        PendingProcessStore pendingProcessStore,
        SceneStore sceneStore,
        String kind
    ) {
        if (store == null
            || summaryJobStore == null
            || translationJobStore == null
            || pendingProcessStore == null
            || sceneStore == null
            || kind == null) {
            throw new IllegalArgumentException(
                "stores and kind are required"
            );
        }
        this.store = store;
        this.summaryJobStore = summaryJobStore;
        this.translationJobStore = translationJobStore;
        this.pendingProcessStore = pendingProcessStore;
        this.sceneStore = sceneStore;
        this.kind = kind;
    }

    /** Creates an owner for a Scene Context record. */
    public static ContextGroupPendingOwner forContexts(
        SceneContextStore store,
        SummaryJobStore summaryJobStore,
        TranslationJobStore translationJobStore,
        PendingProcessStore pendingProcessStore,
        SceneStore sceneStore
    ) {
        return new ContextGroupPendingOwner(
            store,
            summaryJobStore,
            translationJobStore,
            pendingProcessStore,
            sceneStore,
            KIND_CONTEXT
        );
    }

    /** Creates an owner for a Context Group record. */
    public static ContextGroupPendingOwner forGroups(
        SceneContextStore store,
        SummaryJobStore summaryJobStore,
        TranslationJobStore translationJobStore,
        PendingProcessStore pendingProcessStore,
        SceneStore sceneStore
    ) {
        return new ContextGroupPendingOwner(
            store,
            summaryJobStore,
            translationJobStore,
            pendingProcessStore,
            sceneStore,
            KIND_GROUP
        );
    }

    @Override
    public String kind() {
        return kind;
    }

    /** Returns the owner-provided immutable snapshot preview. */
    @Override
    public JSONObject previewMove(String canonicalId) throws Exception {
        requireCanonicalId(canonicalId);
        try {
            return PendingProcessStore.copyJsonOrNull(isGroup()
                ? store.snapshotGroupForPending(canonicalId)
                : store.snapshotContextForPending(canonicalId));
        } catch (SceneContextStore.StorageException e) {
            throw mapFailure(e);
        }
    }

    /** Prepares a snapshot payload; Store supplies restore/sync defaults. */
    @Override
    public PendingProcessStore.MovePayload prepareMove(
        String canonicalId,
        String reason
    ) throws Exception {
        return PendingProcessStore.MovePayload.snapshot(
            kind,
            previewMove(canonicalId),
            reason,
            null,
            null
        );
    }

    /** Hides the exact Context/Group snapshot after index publication. */
    @Override
    public void hide(String canonicalId, JSONObject pendingEntry)
        throws Exception {
        JSONObject snapshot = snapshotFromEntry(canonicalId, pendingEntry);
        try {
            if (isGroup()) {
                store.hideGroupForPending(canonicalId, snapshot);
            } else {
                store.hideContextForPending(canonicalId, snapshot);
            }
        } catch (SceneContextStore.StorageException e) {
            throw mapFailure(e);
        }
    }

    /** Restores the exact owner snapshot through SceneContextStore. */
    @Override
    public void restore(String canonicalId, JSONObject pendingEntry)
        throws Exception {
        JSONObject snapshot = snapshotFromEntry(canonicalId, pendingEntry);
        try {
            if (isGroup()) {
                snapshot = coordinateGroupRestore(snapshot);
                store.restoreGroupFromPending(canonicalId, snapshot);
            } else {
                snapshot = coordinateContextRestore(snapshot);
                store.restoreContextFromPending(canonicalId, snapshot);
            }
        } catch (SceneContextStore.StorageException e) {
            throw mapFailure(e);
        } catch (PendingProcessStore.PendingProcessException e) {
            throw e;
        }
    }

    /**
     * Reconciles Context relations against one current live/pending snapshot.
     * The caller owns the defensive entry copy, so pruning never mutates the
     * durable PendingProcess entry or its stored snapshot.
     */
    private JSONObject coordinateContextRestore(JSONObject snapshot)
        throws PendingProcessStore.PendingProcessException {
        PendingProcessStore.ReferenceSnapshot references;
        Set<String> liveGroupIds;
        Set<String> liveSceneNames;
        try {
            references = pendingProcessStore.snapshotReferences();
            liveGroupIds = new HashSet<>(store.listGroupIds());
            liveSceneNames = new HashSet<>(
                sceneStore.listFormalSceneNamesStrict()
            );
        } catch (SceneContextStore.StorageException e) {
            throw mapFailure(e);
        } catch (IOException e) {
            throw ioFailure(
                "could not inspect PendingProcess references for Context restore",
                e
            );
        } catch (SceneStore.RawSceneFailure e) {
            throw ioFailure(
                "could not enumerate live Scenes for Context restore",
                e
            );
        }

        JSONObject coordinated = PendingProcessStore.copyJsonValueObject(snapshot);
        JSONArray memberships = coordinated.optJSONArray("memberships");
        if (memberships != null) {
            JSONArray retainedMemberships = new JSONArray();
            for (int index = 0; index < memberships.length(); index++) {
                JSONObject membership = memberships.optJSONObject(index);
                if (membership == null) {
                    retainedMemberships.put(JSONObject.NULL);
                    continue;
                }
                Object rawGroupId = membership.opt("group_id");
                if (!(rawGroupId instanceof String)
                    || ((String) rawGroupId).isEmpty()) {
                    retainedMemberships.put(membership);
                    continue;
                }
                String groupId = (String) rawGroupId;
                Boolean groupPending = isPending(
                    references,
                    ContextGroupPendingOwner.KIND_GROUP,
                    groupId
                );
                if (liveGroupIds.contains(groupId)) {
                    retainedMemberships.put(membership);
                } else if (Boolean.TRUE.equals(groupPending)) {
                    throw conflict(
                        "cannot restore Context before its pending Group is "
                            + "restored: " + groupId
                    );
                } else if (groupPending == null) {
                    retainedMemberships.put(membership);
                }
                // A missing, non-pending Group was permanently deleted; its
                // membership must not be revived with the Context snapshot.
            }
            put(coordinated, "memberships", retainedMemberships);
        }

        JSONObject entity = coordinated.optJSONObject("entity");
        JSONArray scenes = entity == null
            ? null
            : entity.optJSONArray("scenes");
        if (entity != null && scenes != null) {
            JSONArray retainedScenes = new JSONArray();
            for (int index = 0; index < scenes.length(); index++) {
                JSONObject sceneEntry = scenes.optJSONObject(index);
                if (sceneEntry == null) {
                    retainedScenes.put(JSONObject.NULL);
                    continue;
                }
                Object rawSceneName = sceneEntry.opt("scene");
                if (!(rawSceneName instanceof String)
                    || ((String) rawSceneName).isEmpty()) {
                    retainedScenes.put(sceneEntry);
                    continue;
                }
                String sceneName = (String) rawSceneName;
                Boolean scenePending = isPending(
                    references,
                    SceneLanguagePendingOwner.KIND_SCENE,
                    sceneName
                );
                if (liveSceneNames.contains(sceneName)
                    || Boolean.TRUE.equals(scenePending)
                    || scenePending == null) {
                    retainedScenes.put(sceneEntry);
                }
                // A missing, non-pending Scene was permanently deleted; do
                // not allow this Context snapshot to resurrect its entry.
            }
            put(entity, "scenes", retainedScenes);
        }
        return coordinated;
    }

    /** Reconciles Group Context members against one current live/pending view. */
    private JSONObject coordinateGroupRestore(JSONObject snapshot)
        throws PendingProcessStore.PendingProcessException {
        PendingProcessStore.ReferenceSnapshot references;
        Set<String> liveContextIds;
        try {
            references = pendingProcessStore.snapshotReferences();
            liveContextIds = new HashSet<>(store.listContextIds());
        } catch (SceneContextStore.StorageException e) {
            throw mapFailure(e);
        } catch (IOException e) {
            throw ioFailure(
                "could not inspect PendingProcess references for Group restore",
                e
            );
        }

        JSONObject coordinated = PendingProcessStore.copyJsonValueObject(snapshot);
        JSONObject entity = coordinated.optJSONObject("entity");
        JSONArray contexts = entity == null
            ? null
            : entity.optJSONArray("contexts");
        if (entity != null && contexts != null) {
            JSONArray retainedContexts = new JSONArray();
            for (int index = 0; index < contexts.length(); index++) {
                JSONObject contextEntry = contexts.optJSONObject(index);
                if (contextEntry == null) {
                    retainedContexts.put(JSONObject.NULL);
                    continue;
                }
                Object rawContextId = contextEntry.opt("context_id");
                if (!(rawContextId instanceof String)
                    || ((String) rawContextId).isEmpty()) {
                    retainedContexts.put(contextEntry);
                    continue;
                }
                String contextId = (String) rawContextId;
                Boolean contextPending = isPending(
                    references,
                    ContextGroupPendingOwner.KIND_CONTEXT,
                    contextId
                );
                if (liveContextIds.contains(contextId)) {
                    retainedContexts.put(contextEntry);
                } else if (Boolean.TRUE.equals(contextPending)) {
                    throw conflict(
                        "cannot restore Group before its pending Context is "
                            + "restored: " + contextId
                    );
                } else if (contextPending == null) {
                    retainedContexts.put(contextEntry);
                }
                // A missing, non-pending Context was permanently deleted; its
                // membership must not be revived with the Group snapshot.
            }
            put(entity, "contexts", retainedContexts);
        }
        return coordinated;
    }

    private static Boolean isPending(
        PendingProcessStore.ReferenceSnapshot references,
        String kind,
        String canonicalId
    ) {
        try {
            return references.isPending(kind, canonicalId);
        } catch (IllegalArgumentException ignored) {
            // Preserve malformed entries for SceneContextStore's strict
            // snapshot validation instead of silently dropping corruption.
            return null;
        }
    }

    private static PendingProcessStore.PendingProcessException conflict(
        String message
    ) {
        return new PendingProcessStore.PendingProcessException(
            PendingProcessStore.FailureKind.CONFLICT,
            message
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

    /** Confirms permanent deletion of the exact owner snapshot. */
    @Override
    public void permanentlyDelete(String canonicalId, JSONObject pendingEntry)
        throws Exception {
        JSONObject snapshot = snapshotFromEntry(canonicalId, pendingEntry);
        try {
            if (isGroup()) {
                store.confirmGroupPermanentlyDeletedForPending(
                    canonicalId,
                    snapshot
                );
                translationJobStore.clearQueuedHistoryMappingsForDeletedGroup(
                    canonicalId
                );
                summaryJobStore.invalidateJobsForOwners(
                    Collections.emptySet(),
                    Collections.singleton(canonicalId)
                );
            } else {
                store.confirmContextPermanentlyDeletedForPending(
                    canonicalId,
                    snapshot
                );
                translationJobStore.clearQueuedHistoryMappingsForDeletedContext(
                    canonicalId
                );
                summaryJobStore.invalidateJobsForOwners(
                    Collections.singleton(canonicalId),
                    Collections.emptySet()
                );
            }
        } catch (SceneContextStore.StorageException e) {
            throw mapFailure(e);
        }
    }

    private boolean isGroup() {
        return KIND_GROUP.equals(kind);
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
                "Context/Group pending entry identity does not match owner"
            );
        }
        JSONObject payload = pendingEntry.optJSONObject("payload");
        if (payload == null
            || !"snapshot".equals(payload.optString("type", ""))
            || !PendingProcessStore.hasExactlyKeys(payload, "type", "snapshot")) {
            throw new PendingProcessStore.PendingProcessException(
                PendingProcessStore.FailureKind.INVALID_STATE,
                "Context/Group pending entry is not a strict snapshot"
            );
        }
        JSONObject snapshot = payload.optJSONObject("snapshot");
        if (snapshot == null) {
            throw new PendingProcessStore.PendingProcessException(
                PendingProcessStore.FailureKind.INVALID_STATE,
                "Context/Group pending snapshot is missing"
            );
        }
        return PendingProcessStore.copyJsonValueObject(snapshot);
    }

    private void requireCanonicalId(String canonicalId)
        throws PendingProcessStore.PendingProcessException {
        if (canonicalId == null || canonicalId.trim().isEmpty()) {
            throw new PendingProcessStore.PendingProcessException(
                PendingProcessStore.FailureKind.INVALID_ARGUMENT,
                "Context/Group canonical id is required"
            );
        }
    }

    private static PendingProcessStore.PendingProcessException mapFailure(
        SceneContextStore.StorageException failure
    ) {
        PendingProcessStore.FailureKind kind;
        switch (failure.kind) {
            case NOT_FOUND:
                kind = PendingProcessStore.FailureKind.NOT_FOUND;
                break;
            case ALREADY_EXISTS:
            case CONFLICT:
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
        return new PendingProcessStore.PendingProcessException(
            kind,
            failure.getMessage(),
            failure
        );
    }

    private static void put(JSONObject object, String key, Object value) {
        try {
            object.put(key, value);
        } catch (org.json.JSONException e) {
            throw new IllegalArgumentException(
                "could not update coordinated snapshot",
                e
            );
        }
    }

}
