package com.quarty.housamoembedtrans.summary.policy;
import com.quarty.housamoembedtrans.summary.request.SummaryRequestAssembler;

import com.quarty.housamoembedtrans.context.history.ContextContentHash;
import com.quarty.housamoembedtrans.context.model.ContextFactLanguages;
import com.quarty.housamoembedtrans.context.model.GroupContextEntry;
import com.quarty.housamoembedtrans.context.store.GroupStore;
import com.quarty.housamoembedtrans.context.store.SceneContextStore;
import com.quarty.housamoembedtrans.summary.job.SummaryJobStore;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.Arrays;

/**
 * Coordinates Group-layer compression facts that are not part of the live
 * Translation first-summary path: Group Summary advancement on Active Context
 * switch/new activation, Final-Summary dependency gating, Group Manual Summary
 * suppression/release, and downstream invalidation.
 *
 * <p>Group compression is only triggered when the Active Context moves to a new
 * position inside the Active Group (or a new context is activated there).
 * Merely switching the Active Group is not a trigger. A Group Summary Job is
 * only created after every Context Final Summary in the covered prefix
 * {@code C[1]..C[n-2]} is available with a matching {@code source_hash}.</p>
 */
public final class GroupCompressionCoordinator {

    private static final String GROUP_OWNER_TYPE =
        SceneContextStore.MANUAL_CLOSURE_GROUP_OWNER;
    private static final String STATUS_QUEUED = "queued";
    private static final String STATUS_RUNNING = "running";
    private static final String STATUS_AWAITING_USER = "awaiting_user";
    private static final String STATUS_FAILED = "failed";

    /** Immutable automatic-compression policy for one reconciliation. */
    public static final class Options {
        public boolean autoCompression;
        public boolean continueAfterManual;
    }

    /** Structured result of one Group compression operation. */
    public static final class Result {
        public boolean groupJobCreated;
        public boolean groupJobReused;
        public boolean groupJobActive;
        public boolean suppressedByManual;
        public boolean suppressedByClosure;
        public boolean dependenciesMissing;
        public boolean finalJobsRequested;
        public int pendingJobsRemoved;
        public String requestId;
        /** True when the Group has no Context entries to summarize. */
        public boolean noFacts;

        /** Manual Group closure state/result fields. */
        public boolean closureOpened;
        public boolean closureReopened;
        public boolean closureClosed;
        public boolean closureQueued;
        public boolean closureActive;
        public boolean closureCompleted;
        public boolean closureRetryable;
        public boolean closeBlockedByActiveJobs;
        public boolean closeIntentSaved;
        public boolean admissionFailed;
        public boolean requiresLatestFactsConfirmation;
        public long closureEpoch;
        public String closureSourceHash = "";
        public String closureFailure = "";
        public final List<String> activeSummaryRequestIds =
            new ArrayList<>();
        public final List<String> missingContextIds = new ArrayList<>();
    }

    private final SceneContextStore sceneContextStore;
    private final SummaryJobStore summaryJobStore;
    private final ContextCompressionCoordinator contextCompressionCoordinator;

    public GroupCompressionCoordinator(
        SceneContextStore sceneContextStore,
        SummaryJobStore summaryJobStore,
        ContextCompressionCoordinator contextCompressionCoordinator
    ) {
        if (sceneContextStore == null || summaryJobStore == null) {
            throw new IllegalArgumentException(
                "sceneContextStore and summaryJobStore are required"
            );
        }
        this.sceneContextStore = sceneContextStore;
        this.summaryJobStore = summaryJobStore;
        this.contextCompressionCoordinator = contextCompressionCoordinator;
    }

    public SceneContextStore getSceneContextStore() {
        return sceneContextStore;
    }

    public SummaryJobStore getSummaryJobStore() {
        return summaryJobStore;
    }

    /** Returns the durable Group closure round used by the management UI. */
    public SceneContextStore.ManualClosureState getManualClosureState(
        String groupId
    ) throws Exception {
        requireText(groupId, "group_id");
        return SceneContextStore.withRootAccess(() ->
            sceneContextStore.getManualClosureState(
                GROUP_OWNER_TYPE,
                groupId
            )
        );
    }

    /** True while automatic Group Summary reconciliation is held. */
    public boolean isManualClosureOpen(String groupId) throws Exception {
        return getManualClosureState(groupId).isOpen();
    }

    /**
     * Starts a Group closure. The first round is restricted to the Active Group;
     * a closed Group can be reopened without changing the Active Group.
     */
    public Result openManualClosure(String groupId) throws Exception {
        return SceneContextStore.withRootAccess(() ->
            openManualClosureLocked(groupId)
        );
    }

    private Result openManualClosureLocked(String groupId) throws Exception {
        requireText(groupId, "group_id");
        SceneContextStore.ManualClosureState current =
            sceneContextStore.getManualClosureState(
                GROUP_OWNER_TYPE,
                groupId
            );
        if (current.isNone()
            && !groupId.equals(sceneContextStore.getActiveGroupId())) {
            throw new SceneContextStore.StorageException(
                SceneContextStore.FailureKind.CONFLICT,
                "the first Group closure must start on the active Group"
            );
        }
        SceneContextStore.ManualClosureState next =
            sceneContextStore.beginManualClosure(
                GROUP_OWNER_TYPE,
                groupId
            );
        Result result = new Result();
        result.closureEpoch = next.epoch;
        result.closureOpened = true;
        result.closureReopened = current.isClosed();
        return result;
    }

    /** Reopens a closed Group round without waiting for old Summary Jobs. */
    public Result reopenManualClosure(String groupId) throws Exception {
        return SceneContextStore.withRootAccess(() ->
            reopenManualClosureLocked(groupId)
        );
    }

    private Result reopenManualClosureLocked(String groupId) throws Exception {
        requireText(groupId, "group_id");
        SceneContextStore.ManualClosureState current =
            sceneContextStore.getManualClosureState(
                GROUP_OWNER_TYPE,
                groupId
            );
        if (!current.isClosed()) {
            throw new SceneContextStore.StorageException(
                SceneContextStore.FailureKind.INVALID_STATE,
                "Group closure is not closed"
            );
        }
        SceneContextStore.ManualClosureState next =
            sceneContextStore.reopenManualClosure(
                GROUP_OWNER_TYPE,
                groupId
            );
        Result result = new Result();
        result.closureEpoch = next.epoch;
        result.closureOpened = true;
        result.closureReopened = true;
        return result;
    }

    /**
     * Ends the current open Group round after the caller's confirmation. The
     * cutoff is always the last Group entry, and the closed intent remains
     * durable when admission fails so an explicit retry can recover it.
     */
    public Result endManualClosure(
        String groupId,
        String targetLang
    ) throws Exception {
        return SceneContextStore.withRootAccess(() ->
            endManualClosureLocked(groupId, targetLang)
        );
    }

    private Result endManualClosureLocked(
        String groupId,
        String targetLang
    ) throws Exception {
        requireText(groupId, "group_id");
        requireText(targetLang, "target_lang");
        SceneContextStore.ManualClosureState current =
            sceneContextStore.getManualClosureState(
                GROUP_OWNER_TYPE,
                groupId
            );
        if (!current.isOpen()) {
            throw new SceneContextStore.StorageException(
                SceneContextStore.FailureKind.INVALID_STATE,
                "Group closure is not open"
            );
        }
        Result result = new Result();
        result.closureEpoch = current.epoch;
        if (summaryJobStore.hasActiveJobsForOwner(
            GROUP_OWNER_TYPE,
            groupId
        )) {
            result.closeBlockedByActiveJobs = true;
            result.closureActive = true;
            result.activeSummaryRequestIds.addAll(
                activeSummaryRequestIdsLocked(groupId)
            );
            return result;
        }

        JSONObject group = sceneContextStore.getGroup(groupId);
        JSONArray groupContexts = group.optJSONArray("contexts");
        Map<String, JSONObject> contextsById =
            loadManualClosureContexts(group, targetLang, result);
        if (result.noFacts || result.dependenciesMissing) {
            return result;
        }
        String cutoff = GroupContextEntry.entryIdAt(
            groupContexts,
            groupContexts.length() - 1
        );
        String sourceHash = SummaryRequestAssembler.computeGroupSnapshotSourceHash(
            group,
            contextsById,
            cutoff,
            targetLang
        );
        SceneContextStore.ManualClosureState closed =
            sceneContextStore.closeManualClosure(
                GROUP_OWNER_TYPE,
                groupId,
                targetLang,
                cutoff,
                sourceHash
            );
        result.closureClosed = true;
        result.closeIntentSaved = true;
        result.closureEpoch = closed.epoch;
        result.closureSourceHash = sourceHash;
        admitClosureRequestLocked(
            groupId,
            targetLang,
            cutoff,
            closed.epoch,
            sourceHash,
            result
        );
        return result;
    }

    /**
     * Retries a closed Group round. Passing {@code true} requires an explicit
     * user confirmation when the current Group facts changed.
     */
    public Result retryManualClosure(
        String groupId,
        String targetLang,
        boolean acceptCurrentFacts
    ) throws Exception {
        return SceneContextStore.withRootAccess(() ->
            retryManualClosureLocked(groupId, targetLang, acceptCurrentFacts)
        );
    }

    public Result retryManualClosure(
        String groupId,
        String targetLang
    ) throws Exception {
        return retryManualClosure(groupId, targetLang, false);
    }

    private Result retryManualClosureLocked(
        String groupId,
        String targetLang,
        boolean acceptCurrentFacts
    ) throws Exception {
        requireText(groupId, "group_id");
        SceneContextStore.ManualClosureState current =
            sceneContextStore.getManualClosureState(
                GROUP_OWNER_TYPE,
                groupId
            );
        if (!current.isClosed()) {
            throw new SceneContextStore.StorageException(
                SceneContextStore.FailureKind.INVALID_STATE,
                "Group closure is not closed"
            );
        }
        Result result = new Result();
        result.closureEpoch = current.epoch;
        if (current.targetLang != null && !current.targetLang.trim().isEmpty()) {
            targetLang = current.targetLang;
        }
        requireText(targetLang, "target_lang");
        if (sceneContextStore.isManualClosureWritebackComplete(
            GROUP_OWNER_TYPE,
            groupId,
            current.epoch,
            current.requestId
        )) {
            result.closureCompleted = true;
            return result;
        }
        if (summaryJobStore.hasActiveJobsForOwner(
            GROUP_OWNER_TYPE,
            groupId
        )) {
            result.closureActive = true;
            result.closeBlockedByActiveJobs = true;
            result.activeSummaryRequestIds.addAll(
                activeSummaryRequestIdsLocked(groupId)
            );
            return result;
        }

        JSONObject group = sceneContextStore.getGroup(groupId);
        JSONArray groupContexts = group.optJSONArray("contexts");
        Map<String, JSONObject> contextsById =
            loadManualClosureContexts(group, targetLang, result);
        if (result.noFacts) {
            return result;
        }
        if (result.dependenciesMissing) {
            result.closureRetryable = true;
            return result;
        }
        String cutoff = GroupContextEntry.entryIdAt(
            groupContexts,
            groupContexts.length() - 1
        );
        String sourceHash = SummaryRequestAssembler.computeGroupSnapshotSourceHash(
            group,
            contextsById,
            cutoff,
            targetLang
        );
        boolean factsChanged = !sourceHash.equals(current.sourceHash);
        if (factsChanged && !acceptCurrentFacts) {
            result.requiresLatestFactsConfirmation = true;
            result.closureSourceHash = sourceHash;
            return result;
        }

        String oldRequestId = current.requestId;
        if (!oldRequestId.isEmpty() && summaryJobStore.hasJob(oldRequestId)) {
            JSONObject state = summaryJobStore.readState(oldRequestId);
            String status = state.optString("status", "");
            if (STATUS_FAILED.equals(status) && !factsChanged) {
                summaryJobStore.retryFailedJob(oldRequestId);
                result.requestId = oldRequestId;
                result.closureQueued = true;
                result.closureActive = true;
                return result;
            }
            if (isActiveSummaryStatus(status)) {
                result.requestId = oldRequestId;
                result.closureActive = true;
                return result;
            }
        }

        if (factsChanged || !oldRequestId.isEmpty()) {
            current = sceneContextStore.updateManualClosureIntent(
                GROUP_OWNER_TYPE,
                groupId,
                current.epoch,
                targetLang,
                cutoff,
                sourceHash
            );
        }
        result.closureSourceHash = sourceHash;
        admitClosureRequestLocked(
            groupId,
            targetLang,
            cutoff,
            current.epoch,
            sourceHash,
            result
        );
        return result;
    }

    private Map<String, JSONObject> loadManualClosureContexts(
        JSONObject group,
        String targetLang,
        Result result
    ) throws Exception {
        JSONArray groupContexts = group.optJSONArray("contexts");
        if (groupContexts == null || groupContexts.length() == 0) {
            result.noFacts = true;
            return null;
        }
        Map<String, JSONObject> contextsById = new HashMap<>();
        ContextCompressionCoordinator.Options contextOptions =
            new ContextCompressionCoordinator.Options();
        contextOptions.autoCompression = true;
        for (int index = 0; index < groupContexts.length(); index++) {
            String contextId = GroupContextEntry.contextIdAt(groupContexts, index);
            JSONObject context;
            try {
                context = sceneContextStore.getContext(contextId);
            } catch (SceneContextStore.StorageException missing) {
                if (missing.kind == SceneContextStore.FailureKind.NOT_FOUND) {
                    addMissingContext(result, contextId);
                    continue;
                }
                throw missing;
            }
            contextsById.put(contextId, context);
            if (!isFinalAvailable(context, targetLang)) {
                addMissingContext(result, contextId);
                if (contextCompressionCoordinator != null) {
                    ContextCompressionCoordinator.Result contextResult =
                        contextCompressionCoordinator.onContextFactsChanged(
                            contextId,
                            targetLang,
                            contextOptions
                        );
                    if (contextResult.finalJobCreated
                        || contextResult.finalJobActive
                        || contextResult.finalReused) {
                        result.finalJobsRequested = true;
                    }
                }
            }
        }
        return contextsById;
    }

    private static void addMissingContext(Result result, String contextId) {
        result.dependenciesMissing = true;
        if (!result.missingContextIds.contains(contextId)) {
            result.missingContextIds.add(contextId);
        }
    }

    private void admitClosureRequestLocked(
        String groupId,
        String targetLang,
        String cutoff,
        long epoch,
        String sourceHash,
        Result result
    ) throws Exception {
        JSONObject request = new JSONObject()
            .put("request_kind", "group_snapshot")
            .put("owner_type", GROUP_OWNER_TYPE)
            .put("owner_id", groupId)
            .put("target_lang", targetLang)
            .put("cutoff", cutoff)
            .put("source_hash", sourceHash)
            .put("manual_closure", true)
            .put("closure_epoch", epoch);
        SummaryJobStore.AdmissionResult admission;
        try {
            admission = summaryJobStore.admitUserRequested(request);
        } catch (Exception failure) {
            result.admissionFailed = true;
            result.closureRetryable = true;
            result.closureFailure = safeMessage(failure);
            return;
        }
        result.requestId = admission.requestId;
        if (SummaryJobStore.DISPOSITION_ACTIVE_TARGET_REJECTED.equals(
            admission.disposition
        )) {
            result.closeBlockedByActiveJobs = true;
            result.closureActive = true;
            result.activeSummaryRequestIds.addAll(
                activeSummaryRequestIdsLocked(groupId)
            );
            return;
        }
        if (!admission.created
            && !SummaryJobStore.DISPOSITION_DUPLICATE_REJECTED.equals(
                admission.disposition
            )) {
            result.admissionFailed = true;
            result.closureRetryable = true;
            result.closureFailure = admission.disposition;
            return;
        }

        try {
            sceneContextStore.recordManualClosureRequest(
                GROUP_OWNER_TYPE,
                groupId,
                epoch,
                admission.requestId
            );
        } catch (Exception bindingFailure) {
            // Keep the durable closed intent. A later explicit retry can
            // rediscover the request directory and bind the same epoch.
            result.admissionFailed = true;
            result.closureRetryable = true;
            result.closureFailure = safeMessage(bindingFailure);
            return;
        }

        String status = "";
        try {
            status = summaryJobStore.readState(admission.requestId)
                .optString("status", "");
        } catch (Exception stateFailure) {
            result.admissionFailed = true;
            result.closureRetryable = true;
            result.closureFailure = safeMessage(stateFailure);
            return;
        }
        if (isActiveSummaryStatus(status)) {
            result.closureQueued = true;
            result.closureActive = true;
        } else if (STATUS_FAILED.equals(status)) {
            result.closureRetryable = true;
        } else {
            result.admissionFailed = true;
            result.closureRetryable = true;
            result.closureFailure = "summary job is not active: " + status;
        }
    }

    private List<String> activeSummaryRequestIdsLocked(String groupId)
        throws Exception {
        List<String> ids = new ArrayList<>();
        for (String requestId : summaryJobStore.listRequestIds()) {
            try {
                JSONObject request = summaryJobStore.readRequest(requestId);
                if (!GROUP_OWNER_TYPE.equals(
                    request.optString("owner_type", "")
                ) || !groupId.equals(request.optString("owner_id", ""))) {
                    continue;
                }
                if (isActiveSummaryStatus(
                    summaryJobStore.readState(requestId)
                        .optString("status", "")
                )) {
                    ids.add(requestId);
                }
            } catch (Exception ignored) {
                // hasActiveJobsForOwner is the authoritative blocking check;
                // a damaged row should not make the closure appear clear.
            }
        }
        return ids;
    }

    private static boolean isActiveSummaryStatus(String status) {
        return STATUS_QUEUED.equals(status)
            || STATUS_RUNNING.equals(status)
            || STATUS_AWAITING_USER.equals(status);
    }

    private static String safeMessage(Throwable error) {
        if (error == null || error.getMessage() == null
            || error.getMessage().trim().isEmpty()) {
            return error == null
                ? "unknown failure"
                : error.getClass().getSimpleName();
        }
        return error.getMessage();
    }

    /**
     * Active Context switch/new-activation listener entry point. It computes
     * the Group Summary target for the new position inside the Active Group and
     * reconciles every target language that already has Group/Context summary
     * facts. No-op when the new context is not in an Active Group or there is
     * no {@code C[1]..C[n-2]} prefix to compress.
     */
    public Result onActiveContextChanged(
        String previousContextId,
        String newContextId,
        Options options
    ) throws Exception {
        return SceneContextStore.withRootAccess(() ->
            onActiveContextChangedLocked(
                previousContextId,
                newContextId,
                options
            )
        );
    }

    private Result onActiveContextChangedLocked(
        String previousContextId,
        String newContextId,
        Options options
    ) throws Exception {
        Result result = new Result();
        Options effective = options == null ? new Options() : options;
        if (!effective.autoCompression
            || newContextId == null
            || newContextId.trim().isEmpty()) {
            return result;
        }
        String groupId = sceneContextStore.getActiveGroupId();
        if (groupId == null) {
            return result;
        }
        JSONObject group = sceneContextStore.getGroup(groupId);
        JSONArray groupContexts = group.optJSONArray("contexts");
        int currentIndex = indexOf(groupContexts, newContextId);
        if (currentIndex < 2) {
            return result;
        }
        Map<String, JSONObject> contextsById = loadContextsById(groupContexts);
        Set<String> languages = collectGroupLanguages(
            group,
            groupContexts,
            currentIndex,
            contextsById
        );
        for (String targetLang : languages) {
            merge(
                result,
                reconcileGroupSnapshot(
                    group,
                    groupContexts,
                    contextsById,
                    currentIndex,
                    targetLang,
                    effective
                )
            );
        }
        return result;
    }

    /**
     * Reconciles the Active Group snapshot for one target language. Used after
     * Context Final Summary write-back, Group member edits, or Manual Summary
     * deletion when the Active Context position already exists.
     */
    public Result reconcileActiveGroup(
        String targetLang,
        Options options
    ) throws Exception {
        return SceneContextStore.withRootAccess(() ->
            reconcileActiveGroupLocked(targetLang, options)
        );
    }

    private Result reconcileActiveGroupLocked(
        String targetLang,
        Options options
    ) throws Exception {
        Result result = new Result();
        Options effective = options == null ? new Options() : options;
        if (!effective.autoCompression
            || targetLang == null
            || targetLang.trim().isEmpty()) {
            return result;
        }
        String groupId = sceneContextStore.getActiveGroupId();
        if (groupId == null) {
            return result;
        }
        String contextId = sceneContextStore.getActiveContextId();
        if (contextId == null) {
            return result;
        }
        JSONObject group = sceneContextStore.getGroup(groupId);
        JSONArray groupContexts = group.optJSONArray("contexts");
        int currentIndex = indexOf(groupContexts, contextId);
        if (currentIndex < 2) {
            return result;
        }
        Map<String, JSONObject> contextsById = loadContextsById(groupContexts);
        return reconcileGroupSnapshot(
            group,
            groupContexts,
            contextsById,
            currentIndex,
            targetLang,
            effective
        );
    }

    /**
     * Downstream invalidation after a Context Final Summary becomes available.
     * Only an Active Group whose covered prefix contains this context is
     * reconciled, and only for the written target language.
     */
    public Result onContextFinalWritten(
        String contextId,
        String targetLang,
        Options options
    ) throws Exception {
        return SceneContextStore.withRootAccess(() ->
            onContextFinalWrittenLocked(contextId, targetLang, options)
        );
    }

    private Result onContextFinalWrittenLocked(
        String contextId,
        String targetLang,
        Options options
    ) throws Exception {
        Result result = new Result();
        if (contextId == null || contextId.trim().isEmpty()) {
            return result;
        }
        String groupId = sceneContextStore.getActiveGroupId();
        if (groupId == null) {
            return result;
        }
        String activeContextId = sceneContextStore.getActiveContextId();
        if (activeContextId == null) {
            return result;
        }
        JSONObject group = sceneContextStore.getGroup(groupId);
        JSONArray groupContexts = group.optJSONArray("contexts");
        int currentIndex = indexOf(groupContexts, activeContextId);
        if (currentIndex < 2) {
            return result;
        }
        int contextIndex = indexOf(groupContexts, contextId);
        if (contextIndex < 0 || contextIndex > currentIndex - 2) {
            return result;
        }
        return reconcileActiveGroupLocked(targetLang, options);
    }

    /**
     * Downstream invalidation after Group member add/delete/reorder or any
     * Group semantic manual edit. Only the Active Group is automatically
     * reconciled; inactive Groups produce no API work.
     */
    public Result onGroupFactsChanged(
        String groupId,
        String targetLang,
        Options options
    ) throws Exception {
        return SceneContextStore.withRootAccess(() ->
            onGroupFactsChangedLocked(groupId, targetLang, options)
        );
    }

    private Result onGroupFactsChangedLocked(
        String groupId,
        String targetLang,
        Options options
    ) throws Exception {
        Result result = new Result();
        if (groupId == null || groupId.trim().isEmpty()) {
            return result;
        }
        if (!groupId.equals(sceneContextStore.getActiveGroupId())) {
            return result;
        }
        return reconcileActiveGroupLocked(targetLang, options);
    }

    /**
     * Persists a Group Manual Summary and, when automatic work is suppressed,
     * removes not-yet-sent Group Summary Jobs for that group and language.
     * Running jobs are left alone; their later legal results lose write-back
     * eligibility through the normal observation path.
     */
    public Result setGroupManualSummary(
        String groupId,
        String targetLang,
        String text,
        Options options
    ) throws Exception {
        return SceneContextStore.withRootAccess(() ->
            setGroupManualSummaryLocked(groupId, targetLang, text, options)
        );
    }

    private Result setGroupManualSummaryLocked(
        String groupId,
        String targetLang,
        String text,
        Options options
    ) throws Exception {
        requireText(groupId, "group_id");
        requireText(targetLang, "target_lang");
        requireText(text, "manual summary text");
        Options effective = options == null ? new Options() : options;

        GroupStore store = sceneContextStore.getGroupStore();
        String storageName = requireGroupStorageName(groupId);
        store.writeManualSummary(storageName, targetLang, text);

        Result result = new Result();
        if (!effective.continueAfterManual) {
            result.pendingJobsRemoved = removePendingGroupJobs(
                groupId,
                targetLang
            );
            return result;
        }
        if (effective.autoCompression) {
            merge(result, onGroupFactsChangedLocked(
                groupId,
                targetLang,
                effective
            ));
        }
        return result;
    }

    /**
     * Removes a Group Manual Summary. When automatic compression is enabled the
     * Active Group snapshot is reconciled immediately for the released language.
     */
    public Result deleteGroupManualSummary(
        String groupId,
        String targetLang,
        Options options
    ) throws Exception {
        return SceneContextStore.withRootAccess(() ->
            deleteGroupManualSummaryLocked(groupId, targetLang, options)
        );
    }

    private Result deleteGroupManualSummaryLocked(
        String groupId,
        String targetLang,
        Options options
    ) throws Exception {
        requireText(groupId, "group_id");
        requireText(targetLang, "target_lang");
        Options effective = options == null ? new Options() : options;

        GroupStore store = sceneContextStore.getGroupStore();
        String storageName = requireGroupStorageName(groupId);
        store.deleteManualSummary(storageName, targetLang);

        Result result = new Result();
        if (effective.autoCompression) {
            merge(result, onGroupFactsChangedLocked(
                groupId,
                targetLang,
                effective
            ));
        }
        return result;
    }

    // ── Internal reconciliation ─────────────────────────────────────────

    private Result reconcileGroupSnapshot(
        JSONObject group,
        JSONArray groupContexts,
        Map<String, JSONObject> contextsById,
        int currentIndex,
        String targetLang,
        Options options
    ) throws Exception {
        Result result = new Result();
        if (options == null || !options.autoCompression) {
            return result;
        }

        String groupId = group.optString("id", "");
        String storageName = group.optString("storage_name", "");
        SceneContextStore.ManualClosureState closure =
            sceneContextStore.getManualClosureState(
                GROUP_OWNER_TYPE,
                groupId
            );
        if (closure.isOpen()
            || (closure.isClosed()
                && closure.epoch > 0L
                && (closure.requestId.isEmpty()
                    || closure.completedRequestId.isEmpty()))) {
            result.suppressedByClosure = true;
            return result;
        }
        GroupStore groupStore = sceneContextStore.getGroupStore();
        if (groupStore.hasManualSummary(storageName, targetLang)
            && !options.continueAfterManual) {
            result.suppressedByManual = true;
            return result;
        }

        int prefixEndExclusive = currentIndex - 1; // indices 0..currentIndex-2
        for (int index = 0; index < prefixEndExclusive; index++) {
            String contextId = GroupContextEntry.contextIdAt(groupContexts, index);
            JSONObject context = contextsById.get(contextId);
            if (context == null) {
                result.dependenciesMissing = true;
                result.missingContextIds.add(contextId);
                continue;
            }
            if (!isFinalAvailable(context, targetLang)) {
                result.dependenciesMissing = true;
                result.missingContextIds.add(contextId);
                if (contextCompressionCoordinator != null) {
                    ContextCompressionCoordinator.Result contextResult =
                        contextCompressionCoordinator.onContextFactsChanged(
                            contextId,
                            targetLang,
                            toContextOptions(options)
                        );
                    if (contextResult.finalJobCreated
                        || contextResult.finalJobActive
                        || contextResult.finalReused) {
                        result.finalJobsRequested = true;
                    }
                }
            }
        }
        if (result.dependenciesMissing) {
            return result;
        }

        String cutoff = GroupContextEntry.entryIdAt(groupContexts, currentIndex - 2);
        String sourceHash = SummaryRequestAssembler.computeGroupSnapshotSourceHash(
            group,
            contextsById,
            cutoff,
            targetLang
        );
        JSONObject request = new JSONObject()
            .put("request_kind", "group_snapshot")
            .put("owner_type", "group")
            .put("owner_id", groupId)
            .put("target_lang", targetLang)
            .put("cutoff", cutoff)
            .put("source_hash", sourceHash);

        SummaryAdmissionCoordinator.Decision decision =
            SummaryAdmissionCoordinator.admit(
                summaryJobStore,
                request,
                false
            );
        result.requestId = decision.requestId;
        switch (decision.outcome) {
            case CREATED:
                result.groupJobCreated = true;
                break;
            case REUSED_DUPLICATE:
                result.groupJobReused = true;
                break;
            case REUSED_ACTIVE:
            case MARKED_RERUN:
                result.groupJobActive = true;
                break;
            default:
                throw new IllegalStateException(
                    "Unhandled Summary admission outcome: " + decision.outcome
                );
        }
        return result;
    }

    private int removePendingGroupJobs(
        String groupId,
        String targetLang
    ) throws Exception {
        return SummaryAdmissionCoordinator.removePendingAutomaticJobs(
            summaryJobStore,
            "group",
            groupId,
            targetLang,
            new java.util.HashSet<>(Arrays.asList("group_snapshot"))
        );
    }

    private Map<String, JSONObject> loadContextsById(JSONArray groupContexts)
        throws Exception {
        Map<String, JSONObject> result = new HashMap<>();
        if (groupContexts != null) {
            for (int index = 0; index < groupContexts.length(); index++) {
                String contextId = GroupContextEntry.contextIdAt(groupContexts, index);
                if (!contextId.isEmpty()
                    && !result.containsKey(contextId)) {
                    result.put(contextId, sceneContextStore.getContext(contextId));
                }
            }
        }
        return result;
    }

    private Set<String> collectGroupLanguages(
        JSONObject group,
        JSONArray groupContexts,
        int currentIndex,
        Map<String, JSONObject> contextsById
    ) {
        Set<String> languages = new HashSet<>();
        JSONObject summary = group.optJSONObject("summary");
        if (summary != null) {
            addKeys(languages, summary);
        }
        int prefixEndExclusive = currentIndex - 1;
        for (int index = 0; index < prefixEndExclusive; index++) {
            JSONObject context = contextsById.get(
                GroupContextEntry.contextIdAt(groupContexts, index)
            );
            if (context != null) {
                languages.addAll(ContextFactLanguages.collect(context));
            }
        }
        return languages;
    }

    private static void addKeys(Set<String> target, JSONObject object) {
        Iterator<String> keys = object.keys();
        while (keys.hasNext()) {
            target.add(keys.next());
        }
    }

    private boolean isFinalAvailable(JSONObject context, String targetLang) {
        JSONObject summary = context.optJSONObject("summary");
        JSONObject language = summary == null
            ? null
            : summary.optJSONObject(targetLang);
        JSONObject finalRecord = language == null
            ? null
            : language.optJSONObject("final");
        if (finalRecord == null) {
            return false;
        }
        String sourceHash = finalRecord.optString("source_hash", "");
        return !sourceHash.isEmpty()
            && sourceHash.equals(ContextContentHash.compute(context, targetLang));
    }

    private String requireGroupStorageName(String groupId) throws Exception {
        JSONObject group = sceneContextStore.getGroup(groupId);
        String storageName = group.optString("storage_name", "");
        if (storageName.isEmpty()) {
            throw new IllegalStateException(
                "group has no storage_name groupId=" + groupId
            );
        }
        return storageName;
    }

    private static ContextCompressionCoordinator.Options toContextOptions(
        Options options
    ) {
        ContextCompressionCoordinator.Options converted =
            new ContextCompressionCoordinator.Options();
        if (options != null) {
            converted.autoCompression = options.autoCompression;
            converted.continueAfterManual = options.continueAfterManual;
        }
        return converted;
    }

    private static void merge(Result target, Result source) {
        if (source == null) {
            return;
        }
        target.groupJobCreated |= source.groupJobCreated;
        target.groupJobReused |= source.groupJobReused;
        target.groupJobActive |= source.groupJobActive;
        target.suppressedByManual |= source.suppressedByManual;
        target.suppressedByClosure |= source.suppressedByClosure;
        target.dependenciesMissing |= source.dependenciesMissing;
        target.finalJobsRequested |= source.finalJobsRequested;
        target.pendingJobsRemoved += source.pendingJobsRemoved;
        if (source.requestId != null && target.requestId == null) {
            target.requestId = source.requestId;
        }
        target.missingContextIds.addAll(source.missingContextIds);
    }

    private static int indexOf(JSONArray array, String value) {
        if (array == null || value == null) {
            return -1;
        }
        for (int index = 0; index < array.length(); index++) {
            if (value.equals(GroupContextEntry.contextIdAt(array, index))) {
                return index;
            }
        }
        return -1;
    }

    private static void requireText(String value, String label) {
        if (value == null || value.trim().isEmpty()) {
            throw new IllegalArgumentException(label + " must not be empty");
        }
    }
}
