package com.quarty.housamoembedtrans.translation;

import com.quarty.housamoembedtrans.storage.ContextStore;
import com.quarty.housamoembedtrans.storage.HistoryMapping;
import com.quarty.housamoembedtrans.storage.RejectedApiResultStore;
import com.quarty.housamoembedtrans.storage.SceneContextStore;
import com.quarty.housamoembedtrans.storage.SummaryJobStore;

import org.json.JSONObject;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Coordinates the Context Summary side of a Translation Request's first
 * {@code summary} event.
 *
 * <p>It persists the Scene Summary, releases same-context waiters, writes back
 * {@code summary.<target_lang>.current} when eligible, archives results that
 * lost write-back eligibility, and creates a background
 * {@code context_snapshot} Summary Job when the response omitted the Context
 * Summary and no older valid record exists.</p>
 */
public final class ContextSummaryCoordinator {

    /** Immutable per-request automatic-compression policy. */
    public static final class Options {
        public boolean autoCompression;
        public boolean continueAfterManual;
    }

    /** Structured result of one first-summary observation. */
    public static final class WritebackResult {
        public boolean sceneSummaryPersisted;
        public boolean released;
        public boolean currentWritten;
        public boolean summaryJobCreated;
        public boolean rejectedArchived;
        public boolean lateSummaryJobAllowed;
        public String reason = "";
        public String summaryJobRequestId;
    }

    /** Notification seam for Context Summary write-back decisions. */
    public interface RejectedResultListener {
        void onRejectedApiResultArchived(JSONObject record);

        /** Called when an already-sent backfill result may still write back. */
        default void onLateContextSummaryWritebackAllowed(
            String requestId,
            String contextId,
            String targetLang
        ) {
        }
    }

    /** Process-local gate for Translation Requests waiting on a Scene Summary. */
    public static final class ContextSummaryReleaseGate {
        private static final class Key {
            private final String contextId;
            private final String scene;
            private final String targetLang;

            private Key(String contextId, String scene, String targetLang) {
                this.contextId = contextId;
                this.scene = scene;
                this.targetLang = targetLang;
            }

            @Override
            public boolean equals(Object other) {
                if (this == other) {
                    return true;
                }
                if (!(other instanceof Key)) {
                    return false;
                }
                Key that = (Key) other;
                return contextId.equals(that.contextId)
                    && scene.equals(that.scene)
                    && targetLang.equals(that.targetLang);
            }

            @Override
            public int hashCode() {
                return java.util.Objects.hash(contextId, scene, targetLang);
            }
        }

        private final Map<Key, List<Runnable>> waiters = new HashMap<>();

        public synchronized boolean hasWaiters(
            String contextId,
            String scene,
            String targetLang
        ) {
            Key key = new Key(contextId, scene, targetLang);
            List<Runnable> pending = waiters.get(key);
            return pending != null && !pending.isEmpty();
        }

        public synchronized void await(
            String contextId,
            String scene,
            String targetLang,
            Runnable onRelease
        ) {
            if (onRelease == null) {
                throw new IllegalArgumentException("onRelease cannot be null");
            }
            Key key = new Key(contextId, scene, targetLang);
            waiters.computeIfAbsent(key, ignored -> new ArrayList<>())
                .add(onRelease);
        }

        public boolean release(
            String contextId,
            String scene,
            String targetLang
        ) {
            List<Runnable> pending;
            synchronized (this) {
                Key key = new Key(contextId, scene, targetLang);
                pending = waiters.remove(key);
            }
            if (pending == null || pending.isEmpty()) {
                return false;
            }
            for (Runnable runnable : pending) {
                runnable.run();
            }
            return true;
        }
    }

    private final SceneContextStore sceneContextStore;
    private final SummaryJobStore summaryJobStore;
    private final RejectedApiResultStore rejectedStore;
    private final ContextSummaryReleaseGate releaseGate;
    private final RejectedResultListener rejectedListener;

    public ContextSummaryCoordinator(
        SceneContextStore sceneContextStore,
        SummaryJobStore summaryJobStore,
        RejectedApiResultStore rejectedStore,
        ContextSummaryReleaseGate releaseGate,
        RejectedResultListener rejectedListener
    ) {
        if (sceneContextStore == null
            || summaryJobStore == null
            || rejectedStore == null
            || releaseGate == null) {
            throw new IllegalArgumentException(
                "sceneContextStore, summaryJobStore, rejectedStore and "
                    + "releaseGate are required"
            );
        }
        this.sceneContextStore = sceneContextStore;
        this.summaryJobStore = summaryJobStore;
        this.rejectedStore = rejectedStore;
        this.releaseGate = releaseGate;
        this.rejectedListener = rejectedListener;
    }

    public SceneContextStore getSceneContextStore() {
        return sceneContextStore;
    }

    /**
     * Decides whether an ordinary Translation Request should set
     * {@code request_context_summary}: automatic compression is on, the job
     * uses a valid Context history mapping, and a Manual Summary is not
     * suppressing automatic work.
     */
    public boolean shouldRequestContextSummary(
        JSONObject state,
        String contextId,
        String targetLang,
        Options options
    ) {
        if (options == null || !options.autoCompression) {
            return false;
        }
        if (HistoryMapping.resolution(state) != HistoryMapping.Resolution.VALID
            || contextId == null
            || contextId.trim().isEmpty()) {
            return false;
        }
        try {
            JSONObject context = sceneContextStore.getContext(contextId);
            ContextStore contextStore = sceneContextStore.getContextStore();
            String storageName = context.optString("storage_name", "");
            if (storageName.isEmpty()) {
                return false;
            }
            if (!options.continueAfterManual
                && contextStore.hasManualSummary(storageName, targetLang)) {
                return false;
            }
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * Handles the first summary event of one Translation Request. This method
     * never throws for ordinary degradation branches; callers may wrap it only
     * to prevent one observation from killing the provider stream.
     */
    public WritebackResult acceptFirstSummary(
        String requestId,
        String contextId,
        String scene,
        String targetLang,
        String summary,
        String contextSummary,
        String invalidContextSummary,
        String capturedSourceHashExcludingScene,
        Options options
    ) throws Exception {
        return SceneContextStore.withRootAccess(() ->
            acceptFirstSummaryLocked(
                requestId,
                contextId,
                scene,
                targetLang,
                summary,
                contextSummary,
                invalidContextSummary,
                capturedSourceHashExcludingScene,
                options
            )
        );
    }

    private WritebackResult acceptFirstSummaryLocked(
        String requestId,
        String contextId,
        String scene,
        String targetLang,
        String summary,
        String contextSummary,
        String invalidContextSummary,
        String capturedSourceHashExcludingScene,
        Options options
    ) throws Exception {
        WritebackResult result = new WritebackResult();
        if (contextId == null || contextId.trim().isEmpty()
            || scene == null || scene.trim().isEmpty()
            || targetLang == null || targetLang.trim().isEmpty()) {
            return result;
        }

        JSONObject context = sceneContextStore.getContext(contextId);
        String storageName = context.optString("storage_name", "");
        if (storageName.isEmpty()) {
            throw new IllegalStateException(
                "context has no storage_name contextId=" + contextId
            );
        }
        ContextStore contextStore = sceneContextStore.getContextStore();
        if (contextStore.findSceneEntryId(storageName, scene) == null) {
            result.reason = "scene_not_in_context";
            return result;
        }

        if (summary != null && !summary.trim().isEmpty()) {
            contextStore.writeSceneSummary(
                storageName,
                scene,
                targetLang,
                summary
            );
            result.sceneSummaryPersisted = true;
        }
        result.released = releaseGate.release(contextId, scene, targetLang);

        if (options == null || !options.autoCompression) {
            return result;
        }

        String entryId = contextStore.findSceneEntryId(storageName, scene);
        if (entryId == null) {
            result.reason = "scene_not_in_context";
            return result;
        }
        String currentSourceHash = contextStore
            .computeContextSourceHashToCutoff(
                storageName,
                targetLang,
                entryId
            );

        if (contextSummary != null && invalidContextSummary == null) {
            ContextStore.CurrentSummaryWriteResult writeback =
                contextStore.writeCurrentContextSummaryIfFactsMatch(
                    storageName,
                    scene,
                    targetLang,
                    contextSummary,
                    capturedSourceHashExcludingScene,
                    currentSourceHash,
                    options.continueAfterManual
                );
            if (writeback.status
                == ContextStore.CurrentSummaryWriteStatus.CONTEXT_CHANGED) {
                archiveRejected(
                    requestId,
                    "context_changed",
                    "legal",
                    new JSONObject().put("context_summary", contextSummary)
                );
                result.rejectedArchived = true;
                result.reason = "context_changed";
                return result;
            }
            if (writeback.status
                == ContextStore.CurrentSummaryWriteStatus.MANUAL_SUMMARY_ACTIVE) {
                archiveRejected(
                    requestId,
                    "manual_summary_active",
                    "legal",
                    new JSONObject().put("context_summary", contextSummary)
                );
                result.rejectedArchived = true;
                result.reason = "manual_summary_active";
                return result;
            }
            result.currentWritten = true;
            result.lateSummaryJobAllowed = removeUnsentContextSnapshotJob(
                contextId,
                targetLang,
                writeback.entryId,
                writeback.sourceHash
            );
            if (result.lateSummaryJobAllowed
                && rejectedListener != null) {
                rejectedListener.onLateContextSummaryWritebackAllowed(
                    requestId,
                    contextId,
                    targetLang
                );
            }
            return result;
        }

        if (invalidContextSummary != null) {
            archiveRejected(
                requestId,
                "invalid_context_summary",
                "illegal",
                invalidContextSummary
            );
            result.rejectedArchived = true;
            result.reason = "invalid_context_summary";
        }

        boolean manualSuppressesBackfill =
            contextStore.hasManualSummary(storageName, targetLang)
                && (options == null || !options.continueAfterManual);
        if (manualSuppressesBackfill) {
            result.reason = "manual_summary_active";
        }
        if (!hasApplicableCurrentSummary(
                contextStore,
                storageName,
                targetLang,
                entryId,
                currentSourceHash
            )
            && !manualSuppressesBackfill) {
            result.summaryJobRequestId = createContextSnapshotJob(
                contextId,
                targetLang,
                entryId,
                currentSourceHash
            );
            result.summaryJobCreated =
                result.summaryJobRequestId != null;
        }
        return result;
    }

    private static boolean hasApplicableCurrentSummary(
        ContextStore contextStore,
        String storageName,
        String targetLang,
        String cutoffEntryId,
        String sourceHash
    ) throws Exception {
        JSONObject current = contextStore.getSummaryRecord(
            storageName,
            targetLang,
            "current"
        );
        return current != null
            && !current.optString("text", "").trim().isEmpty()
            && cutoffEntryId.equals(current.optString("cutoff", ""))
            && sourceHash.equals(current.optString("source_hash", ""));
    }

    private boolean removeUnsentContextSnapshotJob(
        String contextId,
        String targetLang,
        String entryId,
        String sourceHash
    ) throws Exception {
        JSONObject request = snapshotRequest(
            contextId,
            targetLang,
            entryId,
            sourceHash
        );
        String requestId = SummaryJobStore.computeRequestId(request);
        if (!summaryJobStore.hasJob(requestId)) {
            return false;
        }
        JSONObject state = summaryJobStore.readState(requestId);
        String status = state.optString("status", "");
        // Only unsent jobs are removed. Running jobs may still return a legal
        // late result and are allowed to write back unless user edits revoke it.
        if (("queued".equals(status) || "awaiting_user".equals(status))
            && !summaryJobStore.isUserRequested(requestId)) {
            summaryJobStore.removeCompletedJob(requestId);
            return false;
        }
        return "running".equals(status);
    }

    private String createContextSnapshotJob(
        String contextId,
        String targetLang,
        String entryId,
        String sourceHash
    ) throws Exception {
        JSONObject request = snapshotRequest(
            contextId,
            targetLang,
            entryId,
            sourceHash
        );
        return SummaryAdmissionCoordinator.admit(
            summaryJobStore,
            request,
            false
        ).requestId;
    }

    private static JSONObject snapshotRequest(
        String contextId,
        String targetLang,
        String entryId,
        String sourceHash
    ) throws org.json.JSONException {
        return new JSONObject()
            .put("request_kind", "context_snapshot")
            .put("owner_type", "context")
            .put("owner_id", contextId)
            .put("target_lang", targetLang)
            .put("cutoff", entryId)
            .put("source_hash", sourceHash);
    }

    private void archiveRejected(
        String requestId,
        String reason,
        String kind,
        Object payload
    ) throws Exception {
        JSONObject record = rejectedStore.archive(
            "translation",
            requestId,
            reason,
            kind,
            payload
        );
        if (rejectedListener != null) {
            rejectedListener.onRejectedApiResultArchived(record);
        }
    }
}
