package com.quarty.housamoembedtrans.translation;

import com.quarty.housamoembedtrans.storage.ContextContentHash;
import com.quarty.housamoembedtrans.storage.ContextFactLanguages;
import com.quarty.housamoembedtrans.storage.GroupContextEntry;
import com.quarty.housamoembedtrans.storage.GroupStore;
import com.quarty.housamoembedtrans.storage.SceneContextStore;
import com.quarty.housamoembedtrans.storage.SummaryJobStore;

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
        public boolean dependenciesMissing;
        public boolean finalJobsRequested;
        public int pendingJobsRemoved;
        public String requestId;
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
