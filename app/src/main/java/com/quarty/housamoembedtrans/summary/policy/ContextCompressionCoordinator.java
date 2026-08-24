package com.quarty.housamoembedtrans.summary.policy;

import com.quarty.housamoembedtrans.context.store.ContextStore;
import com.quarty.housamoembedtrans.context.model.ContextFactLanguages;
import com.quarty.housamoembedtrans.context.store.SceneContextStore;
import com.quarty.housamoembedtrans.summary.job.SummaryJobStore;

import org.json.JSONObject;

import java.util.Arrays;
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
    }

    private static void requireText(String value, String label) {
        if (value == null || value.trim().isEmpty()) {
            throw new IllegalArgumentException(label + " must not be empty");
        }
    }
}
