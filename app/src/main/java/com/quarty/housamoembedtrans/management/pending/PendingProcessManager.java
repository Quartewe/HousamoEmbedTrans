package com.quarty.housamoembedtrans.management.pending;

import com.quarty.housamoembedtrans.context.store.SceneContextStore;
import com.quarty.housamoembedtrans.scene.store.SceneStore;
import com.quarty.housamoembedtrans.storage.config.ConfigStore;
import com.quarty.housamoembedtrans.summary.job.SummaryJobStore;
import com.quarty.housamoembedtrans.summary.job.SummaryJobWakeup;
import com.quarty.housamoembedtrans.translation.job.TranslationJobStore;

import android.content.Context;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.HashMap;
import java.util.HashSet;

/**
 * Single management API for preview, move, restore, permanent deletion,
 * listing and startup replay. Storage details stay inside registered owners.
 */
public final class PendingProcessManager {

    private static final String TAG = "HET.PendingProcess";

    /**
     * Serializes durable Pending reference mutations with final Scene-policy
     * publication.  A full sync takes this lock immediately before publishing
     * its target; a management mutation holds it across owner/index changes.
     */
    public static final Object POLICY_PUBLICATION_LOCK = new Object();

    /** Process-local wakeup for consumers that derive holds from the index. */
    @FunctionalInterface
    public interface ReferenceChangeListener {
        void onReferencesChanged();
    }

    private final PendingProcessStore store;
    private final Map<String, PendingProcessOwner> owners;
    private final SceneStore sceneStore;
    private final TranslationJobStore terminalDeliveryStore;
    private volatile ReferenceChangeListener referenceChangeListener;

    /** Production composition root for the unified management pending list. */
    public static PendingProcessManager createForAndroid(Context context) {
        if (context == null) {
            throw new IllegalArgumentException("context is required");
        }
        Context appContext = context.getApplicationContext();
        Context safeContext = appContext != null ? appContext : context;
        PendingProcessStore pendingStore = new PendingProcessStore(safeContext);
        SceneStore sceneStore = new SceneStore(safeContext);
        SceneContextStore sceneContextStore = new SceneContextStore(
            safeContext
        );
        ConfigStore configStore = new ConfigStore(safeContext);
        TranslationJobStore translationJobStore =
            TranslationJobStore.getInstance(safeContext);
        SummaryJobStore summaryJobStore = SummaryJobStore.createForAndroid(
            safeContext
        );

        List<PendingProcessOwner> owners = new ArrayList<>();
        owners.add(SceneLanguagePendingOwner.forScenes(
            sceneStore,
            sceneContextStore,
            translationJobStore
        ));
        owners.add(SceneLanguagePendingOwner.forLanguages(sceneStore));
        owners.add(ContextGroupPendingOwner.forContexts(
            sceneContextStore,
            summaryJobStore,
            translationJobStore,
            pendingStore,
            sceneStore
        ));
        owners.add(ContextGroupPendingOwner.forGroups(
            sceneContextStore,
            summaryJobStore,
            translationJobStore,
            pendingStore,
            sceneStore
        ));
        owners.add(DictionaryRecordPendingOwner.forCharacters(configStore));
        owners.add(DictionaryRecordPendingOwner.forTerms(configStore));
        owners.add(new DamagedTranslationJobPendingOwner(
            translationJobStore
        ));
        return new PendingProcessManager(
            pendingStore,
            owners,
            sceneStore,
            translationJobStore,
            () -> {
                try {
                    translationJobStore.refreshManagementPendingBlocks();
                } catch (Exception e) {
                    Log.e(
                        TAG,
                        "Could not refresh Translation Job pending holds",
                        e
                    );
                }
                SummaryJobWakeup.signal(safeContext);
            }
        );
    }

    public PendingProcessManager(
        PendingProcessStore store,
        List<? extends PendingProcessOwner> owners
    ) {
        this(store, owners, () -> { });
    }

    public PendingProcessManager(
        PendingProcessStore store,
        List<? extends PendingProcessOwner> owners,
        ReferenceChangeListener referenceChangeListener
    ) {
        this(store, owners, null, null, referenceChangeListener);
    }

    private PendingProcessManager(
        PendingProcessStore store,
        List<? extends PendingProcessOwner> owners,
        SceneStore sceneStore,
        TranslationJobStore terminalDeliveryStore,
        ReferenceChangeListener referenceChangeListener
    ) {
        if (store == null
            || owners == null
            || referenceChangeListener == null) {
            throw new IllegalArgumentException(
                "store, owners and listener are required"
            );
        }
        Map<String, PendingProcessOwner> byKind = new LinkedHashMap<>();
        for (PendingProcessOwner owner : owners) {
            if (owner == null
                || owner.kind() == null
                || owner.kind().isEmpty()) {
                throw new IllegalArgumentException(
                    "pending owner and kind are required"
                );
            }
            if (byKind.put(owner.kind(), owner) != null) {
                throw new IllegalArgumentException(
                    "duplicate pending owner kind: " + owner.kind()
                );
            }
        }
        this.store = store;
        this.owners = Collections.unmodifiableMap(byKind);
        this.sceneStore = sceneStore;
        this.terminalDeliveryStore = terminalDeliveryStore;
        this.referenceChangeListener = referenceChangeListener;
    }

    /**
     * Adds a consumer for later PendingProcess reference changes. Startup
     * recovery intentionally runs before TerminalDeliveryCoordinator exists;
     * callers may therefore attach the consumer once that coordinator has
     * been created. No callback is invoked while this manager is locked.
     */
    public synchronized void addReferenceChangeListener(
        ReferenceChangeListener listener
    ) {
        if (listener == null) {
            throw new IllegalArgumentException("listener is required");
        }
        ReferenceChangeListener previous = referenceChangeListener;
        referenceChangeListener = () -> {
            notifyReferenceChange(previous);
            notifyReferenceChange(listener);
        };
    }


    /** Returns confirmation metadata without changing any owner or index. */
    public synchronized JSONObject previewMove(
        String kind,
        String canonicalId
    ) throws Exception {
        PendingProcessOwner owner = requireOwner(kind);
        JSONObject details = owner.previewMove(canonicalId);
        try {
            return new JSONObject()
                .put("kind", kind)
                .put("canonical_id", canonicalId)
                .put(
                    "pending_key",
                    PendingProcessStore.pendingKeyFor(kind, canonicalId)
                )
                .put("details", copy(details));
        } catch (JSONException | IllegalArgumentException e) {
            throw new IOException("could not create pending move preview", e);
        }
    }

    /** Captures owner state, publishes it, then completes idempotent hiding. */
    public JSONObject moveToPending(
        String kind,
        String canonicalId,
        String reason
    ) throws Exception {
        return moveToPending(kind, canonicalId, reason, null);
    }

    /** Moves one owner after an optional Scene policy publication barrier. */
    public JSONObject moveToPending(
        String kind,
        String canonicalId,
        String reason,
        PendingProcessStore.MovePublicationBarrier publicationBarrier
    ) throws Exception {
        JSONObject entry;
        ReferenceChangeListener listener;
        entry = runManagementMutation(() -> {
            synchronized (PendingProcessManager.this) {
                PendingProcessOwner owner = requireOwner(kind);
                PendingProcessStore.MovePayload payload = owner.prepareMove(
                    canonicalId,
                    reason
                );
                return publicationBarrier == null
                    ? store.move(owner, canonicalId, payload)
                    : store.move(
                        owner,
                        canonicalId,
                        payload,
                        publicationBarrier
                    );
            }
        });
        synchronized (this) {
            listener = referenceChangeListener;
        }
        notifyReferenceChange(listener);
        return entry;
    }

    /** Restores a snapshot owner; delete-only references are rejected. */
    public JSONObject restore(String pendingKey)
        throws Exception {
        JSONObject restored;
        ReferenceChangeListener listener;
        restored = runManagementMutation(() -> {
            synchronized (PendingProcessManager.this) {
                JSONObject entry = requirePending(pendingKey);
                return store.restore(
                    requireOwner(entry.optString("kind", "")),
                    pendingKey
                );
            }
        });
        synchronized (this) {
            listener = referenceChangeListener;
        }
        notifyReferenceChange(listener);
        return restored;
    }

    /** Applies owner-specific permanent cleanup, then unpublishes the entry. */
    public JSONObject permanentlyDelete(String pendingKey)
        throws Exception {
        JSONObject removed;
        ReferenceChangeListener listener;
        removed = runManagementMutation(() -> {
            synchronized (PendingProcessManager.this) {
                JSONObject entry = requirePending(pendingKey);
                String kind = entry.optString("kind", "");
                String canonicalId = entry.optString("canonical_id", "");
                if (terminalDeliveryStore != null) {
                    Map<String, Set<String>> references = new HashMap<>();
                    Set<String> ids = new HashSet<>();
                    ids.add(canonicalId);
                    references.put(kind, ids);
                    terminalDeliveryStore
                        .suppressTerminalDeliveriesForReferences(references);
                }
                if (SceneLanguagePendingOwner.KIND_SCENE.equals(kind)) {
                    permanentlyDeleteLanguageChildren(canonicalId);
                }
                return store.permanentlyDelete(
                    requireOwner(kind),
                    pendingKey
                );
            }
        });
        synchronized (this) {
            listener = referenceChangeListener;
        }
        notifyReferenceChange(listener);
        return removed;
    }

    /** Metadata-only list suitable for the unified Tasks waiting tab. */
    public synchronized JSONArray listPending() throws IOException {
        return store.listPending();
    }

    public synchronized JSONObject readPending(String pendingKey)
        throws IOException {
        return store.readPending(pendingKey);
    }

    /** Snapshot of currently pending Scene-family names for policy overlay. */
    public Set<String> snapshotManagementPendingSceneNames()
        throws Exception {
        SceneStore scenes = sceneStore;
        return scenes == null
            ? Collections.emptySet()
            : scenes.snapshotManagementPendingSceneNames();
    }

    private <T> T runManagementMutation(
        TranslationJobStore.ManagementMutation<T> mutation
    ) throws Exception {
        synchronized (POLICY_PUBLICATION_LOCK) {
            TranslationJobStore terminalStore = terminalDeliveryStore;
            if (terminalStore == null) {
                return mutation.run();
            }
            try {
                return terminalStore.withManagementMutation(mutation);
            } catch (TranslationJobStore.ManagementMutationBusyException busy) {
                throw new PendingProcessStore.PendingProcessException(
                    PendingProcessStore.FailureKind.CONFLICT,
                    busy.getMessage(),
                    busy
                );
            }
        }
    }

    /** Replays the one durable PendingProcess journal with its owning adapter. */
    public void recover() throws Exception {
        ReferenceChangeListener listener;
        synchronized (this) {
            String kind = store.getRecoveryOwnerKind();
            if (kind != null) {
                store.recover(requireOwner(kind));
            } else {
                store.recover();
            }
            listener = referenceChangeListener;
        }
        notifyReferenceChange(listener);
    }

    private static void notifyReferenceChange(
        ReferenceChangeListener listener
    ) {
        if (listener == null) {
            return;
        }
        try {
            listener.onReferencesChanged();
        } catch (RuntimeException e) {
            Log.e(TAG, "PendingProcess reference listener failed", e);
        }
    }

    private JSONObject requirePending(String pendingKey) throws IOException {
        JSONObject entry = store.readPendingIfPresent(pendingKey);
        if (entry == null) {
            throw new IOException(
                "PendingProcess entry does not exist: " + pendingKey
            );
        }
        return entry;
    }

    private PendingProcessOwner requireOwner(String kind) throws IOException {
        PendingProcessOwner owner = owners.get(kind);
        if (owner == null) {
            throw new IOException("No PendingProcess owner for kind: " + kind);
        }
        return owner;
    }

    private void permanentlyDeleteLanguageChildren(String sceneName)
        throws Exception {
        JSONArray pending = store.listPending();
        PendingProcessOwner languageOwner = null;
        for (int index = 0; index < pending.length(); index++) {
            JSONObject metadata = pending.optJSONObject(index);
            if (metadata == null
                || !SceneLanguagePendingOwner.KIND_LANGUAGE.equals(
                    metadata.optString("kind", "")
                )
                || !SceneLanguagePendingOwner.languageBelongsToScene(
                    metadata.optString("canonical_id", ""),
                    sceneName
                )) {
                continue;
            }
            if (languageOwner == null) {
                languageOwner = requireOwner(
                    SceneLanguagePendingOwner.KIND_LANGUAGE
                );
            }
            store.permanentlyDelete(
                languageOwner,
                metadata.optString("pending_key", "")
            );
        }
    }

    private static JSONObject copy(JSONObject value) throws IOException {
        if (value == null) {
            return new JSONObject();
        }
        try {
            return new JSONObject(value.toString());
        } catch (JSONException e) {
            throw new IOException("could not copy pending preview", e);
        }
    }
}
