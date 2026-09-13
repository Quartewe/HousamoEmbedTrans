package com.quarty.housamoembedtrans.summary.policy;

import com.quarty.housamoembedtrans.context.store.ContextStore;
import com.quarty.housamoembedtrans.context.model.ContextFactLanguages;
import com.quarty.housamoembedtrans.context.store.SceneContextStore;
import com.quarty.housamoembedtrans.summary.job.SummaryJobStore;

import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Set;

/**
 * Coordinates Context-layer compression facts that are not part of the live
 * Translation first-summary path: Final Summary reconciliation, the Active
 * Context deactivation boundary, Manual Summary suppression/release, and
 * automatic rebuild for one affected target language.
 *
 * <p>All automatic rebuild decisions are per target language. Other language
 * summary records are never touched by this coordinator.</p>
 */
public final class ContextCompressionCoordinator {

    private static final String CONTEXT_OWNER_TYPE =
        SceneContextStore.MANUAL_CLOSURE_CONTEXT_OWNER;
    private static final String FINAL_CUTOFF = "final";
    private static final String STATUS_QUEUED = "queued";
    private static final String STATUS_RUNNING = "running";
    private static final String STATUS_AWAITING_USER = "awaiting_user";
    private static final String STATUS_FAILED = "failed";

    /** Immutable automatic-compression policy for one reconciliation. */
    public static final class Options {
        public boolean autoCompression;
        public boolean continueAfterManual;
    }

    /** Structured result of one Context compression operation. */
    public static final class Result {
        public boolean manualWritten;
        public boolean manualDeleted;
        public boolean suppressedByManual;
        public boolean finalReused;
        public boolean finalJobCreated;
        public boolean finalJobActive;
        public int pendingJobsRemoved;
        public String requestId;
        /** True when the Context has no Scene facts from which to summarize. */
        public boolean noFacts;
        /** True when automatic Final Summary work is held by an open round. */
        public boolean suppressedByClosure;

        /** Manual Context closure state/result fields. */
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
    }

    private final SceneContextStore sceneContextStore;
    private final SummaryJobStore summaryJobStore;

    public ContextCompressionCoordinator(
        SceneContextStore sceneContextStore,
        SummaryJobStore summaryJobStore
    ) {
        if (sceneContextStore == null || summaryJobStore == null) {
            throw new IllegalArgumentException(
                "sceneContextStore and summaryJobStore are required"
            );
        }
        this.sceneContextStore = sceneContextStore;
        this.summaryJobStore = summaryJobStore;
    }

    public SceneContextStore getSceneContextStore() {
        return sceneContextStore;
    }

    public SummaryJobStore getSummaryJobStore() {
        return summaryJobStore;
    }

    /** Returns the durable Context closure round used by the management UI. */
    public SceneContextStore.ManualClosureState getManualClosureState(
        String contextId
    ) throws Exception {
        requireText(contextId, "context_id");
        return SceneContextStore.withRootAccess(() ->
            sceneContextStore.getManualClosureState(
                CONTEXT_OWNER_TYPE,
                contextId
            )
        );
    }

    /** True while automatic Final Summary reconciliation is held. */
    public boolean isManualClosureOpen(String contextId) throws Exception {
        return getManualClosureState(contextId).isOpen();
    }

    /**
     * Starts a Context closure. The first round is restricted to the active
     * Context; a closed Context is reopened without changing either pointer.
     */
    public Result openManualClosure(String contextId) throws Exception {
        return SceneContextStore.withRootAccess(() ->
            openManualClosureLocked(contextId)
        );
    }

    private Result openManualClosureLocked(String contextId) throws Exception {
        requireText(contextId, "context_id");
        SceneContextStore.ManualClosureState current =
            sceneContextStore.getManualClosureState(
                CONTEXT_OWNER_TYPE,
                contextId
            );
        if (current.isNone()
            && !contextId.equals(sceneContextStore.getActiveContextId())) {
            throw new SceneContextStore.StorageException(
                SceneContextStore.FailureKind.CONFLICT,
                "the first Context closure must start on the active Context"
            );
        }
        SceneContextStore.ManualClosureState next =
            sceneContextStore.beginManualClosure(
                CONTEXT_OWNER_TYPE,
                contextId
            );
        Result result = new Result();
        result.closureEpoch = next.epoch;
        result.closureOpened = true;
        result.closureReopened = current.isClosed();
        return result;
    }

    /** Reopens a closed Context round without waiting for old Summary Jobs. */
    public Result reopenManualClosure(String contextId) throws Exception {
        return SceneContextStore.withRootAccess(() ->
            reopenManualClosureLocked(contextId)
        );
    }

    private Result reopenManualClosureLocked(String contextId) throws Exception {
        requireText(contextId, "context_id");
        SceneContextStore.ManualClosureState current =
            sceneContextStore.getManualClosureState(
                CONTEXT_OWNER_TYPE,
                contextId
            );
        if (!current.isClosed()) {
            throw new SceneContextStore.StorageException(
                SceneContextStore.FailureKind.INVALID_STATE,
                "Context closure is not closed"
            );
        }
        SceneContextStore.ManualClosureState next =
            sceneContextStore.reopenManualClosure(
                CONTEXT_OWNER_TYPE,
                contextId
            );
        Result result = new Result();
        result.closureEpoch = next.epoch;
        result.closureOpened = true;
        result.closureReopened = true;
        return result;
    }

    /**
     * Ends the current open round after the caller's confirmation. The closed
     * intent is durable even when Summary admission fails, so the UI can offer
     * an explicit retry without claiming that a request was queued.
     */
    public Result endManualClosure(
        String contextId,
        String targetLang
    ) throws Exception {
        return SceneContextStore.withRootAccess(() ->
            endManualClosureLocked(contextId, targetLang)
        );
    }

    private Result endManualClosureLocked(
        String contextId,
        String targetLang
    ) throws Exception {
        requireText(contextId, "context_id");
        requireText(targetLang, "target_lang");
        SceneContextStore.ManualClosureState current =
            sceneContextStore.getManualClosureState(
                CONTEXT_OWNER_TYPE,
                contextId
            );
        if (!current.isOpen()) {
            throw new SceneContextStore.StorageException(
                SceneContextStore.FailureKind.INVALID_STATE,
                "Context closure is not open"
            );
        }
        Result result = new Result();
        result.closureEpoch = current.epoch;
        if (summaryJobStore.hasActiveJobsForOwner(
            CONTEXT_OWNER_TYPE,
            contextId
        )) {
            result.closeBlockedByActiveJobs = true;
            result.closureActive = true;
            result.activeSummaryRequestIds.addAll(
                activeSummaryRequestIdsLocked(contextId)
            );
            return result;
        }

        JSONObject context = sceneContextStore.getContext(contextId);
        org.json.JSONArray scenes = context.optJSONArray("scenes");
        if (scenes == null || scenes.length() == 0) {
            result.noFacts = true;
            return result;
        }
        String storageName = requireStorageName(contextId);
        String sourceHash = sceneContextStore.getContextStore()
            .computeContextSourceHash(storageName, targetLang);
        SceneContextStore.ManualClosureState closed =
            sceneContextStore.closeManualClosure(
                CONTEXT_OWNER_TYPE,
                contextId,
                targetLang,
                FINAL_CUTOFF,
                sourceHash
            );
        result.closureClosed = true;
        result.closeIntentSaved = true;
        result.closureEpoch = closed.epoch;
        result.closureSourceHash = sourceHash;
        admitClosureRequestLocked(
            contextId,
            targetLang,
            closed.epoch,
            sourceHash,
            result
        );
        return result;
    }

    /**
     * Retries a closed round. A caller should pass {@code true} only after the
     * user confirmed rebuilding when current Context facts changed.
     */
    public Result retryManualClosure(
        String contextId,
        String targetLang,
        boolean acceptCurrentFacts
    ) throws Exception {
        return SceneContextStore.withRootAccess(() ->
            retryManualClosureLocked(contextId, targetLang, acceptCurrentFacts)
        );
    }

    public Result retryManualClosure(
        String contextId,
        String targetLang
    ) throws Exception {
        return retryManualClosure(contextId, targetLang, false);
    }

    private Result retryManualClosureLocked(
        String contextId,
        String targetLang,
        boolean acceptCurrentFacts
    ) throws Exception {
        requireText(contextId, "context_id");
        SceneContextStore.ManualClosureState current =
            sceneContextStore.getManualClosureState(
                CONTEXT_OWNER_TYPE,
                contextId
            );
        if (!current.isClosed()) {
            throw new SceneContextStore.StorageException(
                SceneContextStore.FailureKind.INVALID_STATE,
                "Context closure is not closed"
            );
        }
        Result result = new Result();
        result.closureEpoch = current.epoch;
        if (current.targetLang != null && !current.targetLang.trim().isEmpty()) {
            targetLang = current.targetLang;
        }
        requireText(targetLang, "target_lang");
        if (sceneContextStore.isManualClosureWritebackComplete(
            CONTEXT_OWNER_TYPE,
            contextId,
            current.epoch,
            current.requestId
        )) {
            result.closureCompleted = true;
            return result;
        }
        if (summaryJobStore.hasActiveJobsForOwner(
            CONTEXT_OWNER_TYPE,
            contextId
        )) {
            result.closureActive = true;
            result.closeBlockedByActiveJobs = true;
            result.activeSummaryRequestIds.addAll(
                activeSummaryRequestIdsLocked(contextId)
            );
            return result;
        }

        JSONObject context = sceneContextStore.getContext(contextId);
        org.json.JSONArray scenes = context.optJSONArray("scenes");
        if (scenes == null || scenes.length() == 0) {
            result.noFacts = true;
            return result;
        }
        String storageName = requireStorageName(contextId);
        String sourceHash = sceneContextStore.getContextStore()
            .computeContextSourceHash(storageName, targetLang);
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
                CONTEXT_OWNER_TYPE,
                contextId,
                current.epoch,
                targetLang,
                FINAL_CUTOFF,
                sourceHash
            );
        }
        result.closureSourceHash = sourceHash;
        admitClosureRequestLocked(
            contextId,
            targetLang,
            current.epoch,
            sourceHash,
            result
        );
        return result;
    }

    private void admitClosureRequestLocked(
        String contextId,
        String targetLang,
        long epoch,
        String sourceHash,
        Result result
    ) throws Exception {
        JSONObject request = new JSONObject()
            .put("request_kind", "context_final")
            .put("owner_type", CONTEXT_OWNER_TYPE)
            .put("owner_id", contextId)
            .put("target_lang", targetLang)
            .put("cutoff", FINAL_CUTOFF)
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
                activeSummaryRequestIdsLocked(contextId)
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
                CONTEXT_OWNER_TYPE,
                contextId,
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

    private List<String> activeSummaryRequestIdsLocked(String contextId)
        throws Exception {
        List<String> ids = new ArrayList<>();
        for (String requestId : summaryJobStore.listRequestIds()) {
            try {
                JSONObject request = summaryJobStore.readRequest(requestId);
                if (!CONTEXT_OWNER_TYPE.equals(
                    request.optString("owner_type", "")
                ) || !contextId.equals(request.optString("owner_id", ""))) {
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
            return error == null ? "unknown failure" : error.getClass().getSimpleName();
        }
        return error.getMessage();
    }

    /**
     * Persists a Manual Summary and, when automatic work is suppressed,
     * removes not-yet-sent Context Summary/Final Summary jobs for that
     * context and language. Running jobs are left alone; their later legal
     * results lose write-back eligibility through the normal observation path.
     */
    public Result setManualSummary(
        String contextId,
        String targetLang,
        String text,
        Options options
    ) throws Exception {
        return SceneContextStore.withRootAccess(() ->
            setManualSummaryLocked(contextId, targetLang, text, options)
        );
    }

    private Result setManualSummaryLocked(
        String contextId,
        String targetLang,
        String text,
        Options options
    ) throws Exception {
        requireText(contextId, "context_id");
        requireText(targetLang, "target_lang");
        requireText(text, "manual summary text");
        Options effective = options == null ? new Options() : options;

        ContextStore store = sceneContextStore.getContextStore();
        String storageName = requireStorageName(contextId);
        store.writeManualSummary(storageName, targetLang, text);

        Result result = new Result();
        result.manualWritten = true;
        if (!effective.continueAfterManual) {
            result.pendingJobsRemoved = removePendingAutomaticJobs(
                contextId,
                targetLang
            );
            return result;
        }
        if (effective.autoCompression) {
            copyReconcile(result, reconcileFinal(
                contextId,
                targetLang,
                effective,
                false
            ));
        }
        return result;
    }

    /**
     * Removes a Manual Summary. When automatic compression is enabled the
     * existing Final Summary is reused if its {@code source_hash} still matches
     * the current Context Content Hash; otherwise a Final Summary rebuild job
     * is created or marked for rerun.
     */
    public Result deleteManualSummary(
        String contextId,
        String targetLang,
        Options options
    ) throws Exception {
        return SceneContextStore.withRootAccess(() ->
            deleteManualSummaryLocked(contextId, targetLang, options)
        );
    }

    private Result deleteManualSummaryLocked(
        String contextId,
        String targetLang,
        Options options
    ) throws Exception {
        requireText(contextId, "context_id");
        requireText(targetLang, "target_lang");
        Options effective = options == null ? new Options() : options;

        ContextStore store = sceneContextStore.getContextStore();
        String storageName = requireStorageName(contextId);
        store.deleteManualSummary(storageName, targetLang);

        Result result = new Result();
        result.manualDeleted = true;
        if (effective.autoCompression) {
            copyReconcile(result, reconcileFinal(
                contextId,
                targetLang,
                effective,
                false
            ));
        }
        return result;
    }

    /**
     * User-visible Final Summary generation/update request. It follows the same
     * hash reuse / suppression rules as automatic reconciliation.
     */
    public Result requestFinalSummary(
        String contextId,
        String targetLang,
        Options options
    ) throws Exception {
        return SceneContextStore.withRootAccess(() ->
            requestFinalSummaryLocked(contextId, targetLang, options)
        );
    }

    private Result requestFinalSummaryLocked(
        String contextId,
        String targetLang,
        Options options
    ) throws Exception {
        requireText(contextId, "context_id");
        requireText(targetLang, "target_lang");
        // This is an explicit user admission, so it remains available when
        // automatic compression is disabled. The global manual-summary
        // suppression rule still applies through continueAfterManual.
        Options effective = new Options();
        effective.autoCompression = true;
        effective.continueAfterManual = options != null
            && options.continueAfterManual;
        return reconcileFinal(contextId, targetLang, effective, true);
    }

    /**
     * Active Context deactivation boundary. A Manual Summary wins when it
     * exists and automatic work is suppressed; otherwise the Final Summary is
     * reused when its hash matches, or a Final Summary Summary Job is created /
     * marked for rerun. The switch itself never waits for that job.
     */
    public Result onContextDeactivated(
        String contextId,
        String targetLang,
        Options options
    ) throws Exception {
        return SceneContextStore.withRootAccess(() ->
            onContextDeactivatedLocked(contextId, targetLang, options)
        );
    }

    private Result onContextDeactivatedLocked(
        String contextId,
        String targetLang,
        Options options
    ) throws Exception {
        requireText(contextId, "context_id");
        requireText(targetLang, "target_lang");
        Options effective = options == null ? new Options() : options;
        return reconcileFinal(contextId, targetLang, effective, false);
    }

    /**
     * Active Context switch listener entry point. Reconciles every target
     * language already present in the previous Context's summary container,
     * so the deactivation boundary is applied per language.
     */
    public void onActiveContextChanged(
        String previousContextId,
        Options options
    ) throws Exception {
        SceneContextStore.withRootAccess(() -> {
            onActiveContextChangedLocked(previousContextId, options);
            return null;
        });
    }

    private void onActiveContextChangedLocked(
        String previousContextId,
        Options options
    ) throws Exception {
        if (previousContextId == null || previousContextId.trim().isEmpty()) {
            return;
        }
        JSONObject context = sceneContextStore.getContext(previousContextId);
        Set<String> languages = ContextFactLanguages.collect(context);
        for (String language : languages) {
            onContextDeactivatedLocked(
                previousContextId,
                language,
                options
            );
        }
    }

    /**
     * Automatic rebuild after a Context edit that affects one target language.
     * Only that language's Final Summary is reconciled; other language records
     * stay untouched.
     */
    public Result onContextFactsChanged(
        String contextId,
        String targetLang,
        Options options
    ) throws Exception {
        return SceneContextStore.withRootAccess(() ->
            onContextFactsChangedLocked(contextId, targetLang, options)
        );
    }

    private Result onContextFactsChangedLocked(
        String contextId,
        String targetLang,
        Options options
    ) throws Exception {
        requireText(contextId, "context_id");
        requireText(targetLang, "target_lang");
        Options effective = options == null ? new Options() : options;
        return reconcileFinal(contextId, targetLang, effective, false);
    }

    private Result reconcileFinal(
        String contextId,
        String targetLang,
        Options options,
        boolean userRequested
    ) throws Exception {
        Result result = new Result();
        if (options == null || !options.autoCompression) {
            return result;
        }

        ContextStore store = sceneContextStore.getContextStore();
        String storageName = requireStorageName(contextId);
        SceneContextStore.ManualClosureState closure =
            sceneContextStore.getManualClosureState(
                CONTEXT_OWNER_TYPE,
                contextId
            );
        if (closure.isOpen()
            || (closure.isClosed()
                && closure.epoch > 0L
                && (closure.requestId.isEmpty()
                    || closure.completedRequestId.isEmpty()))) {
            result.suppressedByClosure = true;
            return result;
        }
        JSONObject context = sceneContextStore.getContext(contextId);
        org.json.JSONArray scenes = context.optJSONArray("scenes");
        if (scenes == null || scenes.length() == 0) {
            result.noFacts = true;
            return result;
        }
        if (store.hasManualSummary(storageName, targetLang)
            && !options.continueAfterManual) {
            result.suppressedByManual = true;
            return result;
        }

        String sourceHash = store.computeContextSourceHash(
            storageName,
            targetLang
        );
        JSONObject finalRecord = store.getSummaryRecord(
            storageName,
            targetLang,
            "final"
        );
        if (finalRecord != null
            && sourceHash.equals(finalRecord.optString("source_hash", ""))) {
            result.finalReused = true;
            return result;
        }

        JSONObject request = finalRequest(contextId, targetLang, sourceHash);
        SummaryAdmissionCoordinator.Decision decision =
            SummaryAdmissionCoordinator.admit(
                summaryJobStore,
                request,
                userRequested
            );
        result.requestId = decision.requestId;
        switch (decision.outcome) {
            case CREATED:
                result.finalJobCreated = true;
                break;
            case REUSED_DUPLICATE:
                result.finalReused = true;
                break;
            case REUSED_ACTIVE:
            case MARKED_RERUN:
                result.finalJobActive = true;
                break;
            default:
                throw new IllegalStateException(
                    "Unhandled Summary admission outcome: " + decision.outcome
                );
        }
        return result;
    }

    private int removePendingAutomaticJobs(
        String contextId,
        String targetLang
    ) throws Exception {
        return SummaryAdmissionCoordinator.removePendingAutomaticJobs(
            summaryJobStore,
            "context",
            contextId,
            targetLang,
            new java.util.HashSet<>(Arrays.asList(
                "context_snapshot",
                "context_final"
            ))
        );
    }

    private String requireStorageName(String contextId) throws Exception {
        JSONObject context = sceneContextStore.getContext(contextId);
        String storageName = context.optString("storage_name", "");
        if (storageName.isEmpty()) {
            throw new IllegalStateException(
                "context has no storage_name contextId=" + contextId
            );
        }
        return storageName;
    }

    private static JSONObject finalRequest(
        String contextId,
        String targetLang,
        String sourceHash
    ) throws org.json.JSONException {
        return new JSONObject()
            .put("request_kind", "context_final")
            .put("owner_type", "context")
            .put("owner_id", contextId)
            .put("target_lang", targetLang)
            .put("cutoff", "final")
            .put("source_hash", sourceHash);
    }

    private static void copyReconcile(Result target, Result source) {
        target.suppressedByManual = source.suppressedByManual;
        target.finalReused = source.finalReused;
        target.finalJobCreated = source.finalJobCreated;
        target.finalJobActive = source.finalJobActive;
        target.requestId = source.requestId;
        target.noFacts = source.noFacts;
        target.suppressedByClosure = source.suppressedByClosure;
    }

    private static void requireText(String value, String label) {
        if (value == null || value.trim().isEmpty()) {
            throw new IllegalArgumentException(label + " must not be empty");
        }
    }
}
