package com.quarty.housamoembedtrans.context.review;
import com.quarty.housamoembedtrans.summary.policy.ContextCompressionCoordinator;
import com.quarty.housamoembedtrans.summary.policy.GroupCompressionCoordinator;
import com.quarty.housamoembedtrans.translation.job.TranslationJobStore;
import com.quarty.housamoembedtrans.translation.request.SceneTranslationRequestBuilder;

import com.quarty.housamoembedtrans.context.history.ContextContentHash;
import com.quarty.housamoembedtrans.context.model.ContextFactLanguages;
import com.quarty.housamoembedtrans.context.model.GroupContextEntry;
import com.quarty.housamoembedtrans.context.model.HistoryMapping;
import com.quarty.housamoembedtrans.context.store.SceneContextStore;
import com.quarty.housamoembedtrans.scene.store.SceneStore;
import com.quarty.housamoembedtrans.summary.job.SummaryJobStore;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayInputStream;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Applies a saved Context/Group Review in one service-local transaction-like
 * sequence: context writes, group writes, deletions, Active pointer commit,
 * queued Translation Job mapping rewrites, and compression reconciliation.
 *
 * <p>The underlying storage layer remains {@link SceneContextStore}; this class
 * only coordinates cross-entity rules that belong to the Review boundary.</p>
 */
public final class ContextReviewCoordinator {

    /** Compression policy for one Review save. */
    public static final class Options {
        public boolean autoCompression;
        public boolean continueAfterManual;
    }

    /** Structured result of a Review save. */
    public static final class SaveResult {
        public int contextsCreated;
        public int contextsUpdated;
        public int contextsDeleted;
        public int groupsCreated;
        public int groupsUpdated;
        public int groupsDeleted;
        public int mappingsRewritten;
        public int userRequestedJobsCanceled;
        public boolean activePointersChanged;
        /** Draft id to persisted id for Contexts touched by this save. */
        public final Map<String, String> contextIdMap = new LinkedHashMap<>();
    }

    /** Fixed pre-commit view of work affected by one Scene Batch edit. */
    public static final class EditRisk {
        public final int affectedWork;
        public final List<String> contextIds;
        public final List<String> groupIds;
        public final List<String> userRequestedUnsentIds;
        public final List<String> userRequestedRunningIds;
        private final String snapshotToken;

        private EditRisk(
            int affectedWork,
            List<String> contextIds,
            List<String> groupIds,
            List<String> userRequestedUnsentIds,
            List<String> userRequestedRunningIds,
            String snapshotToken
        ) {
            this.affectedWork = affectedWork;
            this.contextIds = immutableCopy(contextIds);
            this.groupIds = immutableCopy(groupIds);
            this.userRequestedUnsentIds = immutableCopy(
                userRequestedUnsentIds
            );
            this.userRequestedRunningIds = immutableCopy(
                userRequestedRunningIds
            );
            this.snapshotToken = snapshotToken;
        }

        private boolean sameSnapshot(EditRisk other) {
            return other != null
                && snapshotToken.equals(other.snapshotToken);
        }
    }

    /** Durable outcome of one atomic Scene Batch commit. */
    public static final class BatchSaveResult {
        public int contextsUpdated;
        public int jobsCreated;
        public int jobsSkipped;
        public int mappingsRewritten;
        public int userRequestedJobsCanceled;
        public int jobsAwaitingRecovery;
        private final List<String> deferredRequestIds = new ArrayList<>();
    }

    /** Signals that the page-load/risk snapshot no longer owns the commit. */
    public static final class ConcurrentEditException
        extends IllegalStateException {
        public ConcurrentEditException(String message) {
            super(message);
        }
    }

    private final SceneContextStore sceneContextStore;
    private final TranslationJobStore translationJobStore;
    private final SummaryJobStore summaryJobStore;
    private final ContextCompressionCoordinator contextCompressionCoordinator;
    private final GroupCompressionCoordinator groupCompressionCoordinator;

    public ContextReviewCoordinator(
        SceneContextStore sceneContextStore,
        TranslationJobStore translationJobStore,
        SummaryJobStore summaryJobStore,
        ContextCompressionCoordinator contextCompressionCoordinator,
        GroupCompressionCoordinator groupCompressionCoordinator
    ) {
        if (sceneContextStore == null
            || translationJobStore == null
            || summaryJobStore == null
            || contextCompressionCoordinator == null
            || groupCompressionCoordinator == null) {
            throw new IllegalArgumentException(
                "all ContextReviewCoordinator collaborators are required"
            );
        }
        this.sceneContextStore = sceneContextStore;
        this.translationJobStore = translationJobStore;
        this.summaryJobStore = summaryJobStore;
        this.contextCompressionCoordinator = contextCompressionCoordinator;
        this.groupCompressionCoordinator = groupCompressionCoordinator;
    }

    /**
     * Captures the exact active-work snapshot that the Scene Batch UI asks the
     * user to approve. Dependent Groups are derived from their current ordered
     * Context membership, not from any Scene-side back-reference.
     */
    public EditRisk assessSceneBatch(List<String> changedContextIds)
        throws Exception {
        return SceneContextStore.withRootAccess(() ->
            assessSceneBatchLocked(changedContextIds)
        );
    }

    /** Captures affected work for the complete Context/Group Review draft. */
    public EditRisk assessReview(
        List<JSONObject> contextDrafts,
        List<JSONObject> groupDrafts
    ) throws Exception {
        return SceneContextStore.withRootAccess(() ->
            assessReviewLocked(contextDrafts, groupDrafts)
        );
    }

    /**
     * Commits Context-owned Scene lists, queued mapping repair, optional
     * Translation admissions, and summary reconciliation under one root lock
     * and one Review before-image journal.
     */
    public BatchSaveResult saveSceneBatch(
        SceneBatchPlanner.CommitPlan commit,
        Options options,
        EditRisk acceptedRisk,
        boolean discardUserRequestedUnsent
    ) throws Exception {
        if (commit == null || acceptedRisk == null) {
            throw new IllegalArgumentException(
                "batch commit and accepted risk snapshot are required"
            );
        }
        return SceneContextStore.withRootAccess(() -> {
            List<String> transactionAdmissions = new ArrayList<>();
            ReviewTransactionJournal journal = ReviewTransactionJournal.begin(
                sceneContextStore,
                translationJobStore,
                summaryJobStore
            );
            try {
                BatchSaveResult result = saveSceneBatchLocked(
                    commit,
                    options == null ? new Options() : options,
                    acceptedRisk,
                    discardUserRequestedUnsent,
                    transactionAdmissions
                );
                journal.commit();
                try {
                    result.jobsAwaitingRecovery = translationJobStore
                        .publishReviewTransactionAdmissions(
                            result.deferredRequestIds
                        )
                        .size();
                } catch (Exception publicationFailure) {
                    // The committed request/state files retain their durable
                    // publication hold. Translation startup recovery clears it
                    // after Review journal recovery, so this is not a failed
                    // transaction and must not be reported as one.
                    result.jobsAwaitingRecovery =
                        result.deferredRequestIds.size();
                }
                return result;
            } catch (Exception failure) {
                boolean rolledBack = false;
                try {
                    journal.rollback();
                    rolledBack = true;
                } catch (Exception rollbackFailure) {
                    failure.addSuppressed(rollbackFailure);
                }
                if (rolledBack) {
                    try {
                        translationJobStore
                            .discardRolledBackReviewAdmissions(
                                transactionAdmissions
                            );
                    } catch (Exception cleanupFailure) {
                        failure.addSuppressed(cleanupFailure);
                    }
                }
                throw failure;
            }
        });
    }

    private synchronized BatchSaveResult saveSceneBatchLocked(
        SceneBatchPlanner.CommitPlan commit,
        Options options,
        EditRisk acceptedRisk,
        boolean discardUserRequestedUnsent,
        List<String> transactionAdmissions
    ) throws Exception {
        List<String> changedContextIds = new ArrayList<>();
        for (SceneBatchPlanner.ContextEdit edit : commit.contextEdits) {
            if (edit == null) {
                throw new IllegalArgumentException("batch Context edit is null");
            }
            changedContextIds.add(edit.contextId);
        }

        EditRisk currentRisk = assessSceneBatchLocked(changedContextIds);
        if (!acceptedRisk.sameSnapshot(currentRisk)) {
            throw new ConcurrentEditException(
                "associated work changed after confirmation; reload the page"
            );
        }
        if (!currentRisk.userRequestedUnsentIds.isEmpty()
            && !discardUserRequestedUnsent) {
            throw new IllegalStateException(
                "user-requested unsent Summary Jobs require an explicit "
                    + "discard decision"
            );
        }

        BatchSaveResult result = new BatchSaveResult();
        for (String requestId : currentRisk.userRequestedUnsentIds) {
            if (!summaryJobStore.cancelUserRequestedIfUnsent(requestId)) {
                throw new ConcurrentEditException(
                    "Summary Job changed before it could be canceled: "
                        + requestId
                );
            }
            result.userRequestedJobsCanceled++;
        }

        Map<String, JSONObject> previousContexts = entityMap(
            sceneContextStore.listContexts()
        );
        Map<String, JSONObject> previousGroups = entityMap(
            sceneContextStore.listGroups()
        );

        for (SceneBatchPlanner.ContextEdit edit : commit.contextEdits) {
            applyBatchContextEdit(edit);
            result.contextsUpdated++;
        }

        List<JSONObject> finalContexts = sceneContextStore.listContexts();
        List<JSONObject> finalGroups = sceneContextStore.listGroups();
        List<ContextReviewPlanner.MappingRewrite> rewrites =
            ContextReviewPlanner.planMappingRewrites(
                plannerContexts(finalContexts, Collections.emptyMap()),
                plannerGroups(finalGroups, Collections.emptyMap()),
                queuedJobStates()
            );
        for (ContextReviewPlanner.MappingRewrite rewrite : rewrites) {
            translationJobStore.rewriteHistoryMapping(
                rewrite.requestId,
                rewrite.historyMapping
            );
            result.mappingsRewritten++;
        }

        for (SceneBatchPlanner.JobCreation creation : commit.jobCreations) {
            validateBatchHistoryRoute(creation);
            if (translationJobStore.hasActiveOrCompletedJobForScene(
                creation.scene
            )) {
                result.jobsSkipped++;
                continue;
            }
            String requestId = SceneTranslationRequestBuilder.buildRequestId(
                creation.requestJson
            );
            try {
                boolean created = translationJobStore
                    .createQueuedJobForReviewTransaction(
                    requestId,
                    new ByteArrayInputStream(creation.requestJson),
                    creation.historyMapping
                );
                if (created) {
                    result.jobsCreated++;
                    result.deferredRequestIds.add(requestId);
                    transactionAdmissions.add(requestId);
                } else {
                    result.jobsSkipped++;
                }
            } catch (TranslationJobStore.AdmissionException duplicate) {
                result.jobsSkipped++;
            }
        }

        List<String> dependentGroupIds = dependentGroupIds(
            new HashSet<>(changedContextIds),
            finalGroups
        );
        reconcileSummaries(
            sceneContextStore,
            contextCompressionCoordinator,
            groupCompressionCoordinator,
            uniqueSorted(changedContextIds),
            dependentGroupIds,
            options,
            previousContexts,
            previousGroups
        );
        return result;
    }

    private EditRisk assessSceneBatchLocked(List<String> changedContextIds)
        throws Exception {
        List<String> contexts = uniqueSorted(changedContextIds);
        Set<String> contextSet = new HashSet<>(contexts);
        for (String contextId : contexts) {
            sceneContextStore.getContext(contextId);
        }
        List<String> groups = dependentGroupIds(
            contextSet,
            sceneContextStore.listGroups()
        );
        return assessOwnersLocked(contexts, groups);
    }

    private EditRisk assessReviewLocked(
        List<JSONObject> contextDrafts,
        List<JSONObject> groupDrafts
    ) throws Exception {
        if (contextDrafts == null || groupDrafts == null) {
            throw new IllegalArgumentException(
                "Context and Group Review drafts are required"
            );
        }
        Map<String, JSONObject> currentContexts = entityMap(
            sceneContextStore.listContexts()
        );
        Map<String, JSONObject> currentGroups = entityMap(
            sceneContextStore.listGroups()
        );
        Map<String, JSONObject> draftContexts = existingDraftMap(
            contextDrafts
        );
        Map<String, JSONObject> draftGroups = existingDraftMap(groupDrafts);

        Set<String> changedContexts = changedEntityIds(
            currentContexts,
            draftContexts,
            true
        );
        Set<String> changedGroups = changedEntityIds(
            currentGroups,
            draftGroups,
            false
        );

        // A Context fact change also affects every existing Group that
        // referenced it before the save or will reference it afterwards.
        addGroupsReferencingContexts(
            changedGroups,
            changedContexts,
            currentGroups.values()
        );
        addGroupsReferencingContexts(
            changedGroups,
            changedContexts,
            draftGroups.values()
        );
        return assessOwnersLocked(
            uniqueSorted(new ArrayList<>(changedContexts)),
            uniqueSorted(new ArrayList<>(changedGroups))
        );
    }

    private EditRisk assessOwnersLocked(
        List<String> contexts,
        List<String> groups
    ) throws Exception {
        Set<String> contextSet = new HashSet<>(contexts);
        Set<String> groupSet = new HashSet<>(groups);
        List<String> workFacts = new ArrayList<>();
        List<String> manualUnsent = new ArrayList<>();
        List<String> manualRunning = new ArrayList<>();
        int affected = 0;

        for (TranslationJobStore.ReviewJob job
            : translationJobStore.listReviewJobs()) {
            if (!isAffected(
                job.getContextId(),
                job.getGroupId(),
                contextSet,
                groupSet
            )) {
                continue;
            }
            affected++;
            workFacts.add(
                "translation|" + job.getRequestId()
                    + "|" + job.getStatus()
                    + "|" + nullToken(job.getContextId())
                    + "|" + nullToken(job.getGroupId())
            );
        }

        for (String requestId : summaryJobStore.listRequestIds()) {
            JSONObject request = summaryJobStore.readRequest(requestId);
            JSONObject state = summaryJobStore.readState(requestId);
            String status = state.optString("status", "");
            if (!"queued".equals(status)
                && !"running".equals(status)
                && !"awaiting_user".equals(status)) {
                continue;
            }
            String ownerType = request.optString("owner_type", "");
            String ownerId = request.optString("owner_id", "");
            boolean ownerAffected = ("context".equals(ownerType)
                    && contextSet.contains(ownerId))
                || ("group".equals(ownerType) && groupSet.contains(ownerId));
            if (!ownerAffected) {
                continue;
            }
            affected++;
            boolean userRequested = state.optBoolean(
                "user_requested",
                false
            );
            workFacts.add(
                "summary|" + requestId
                    + "|" + status
                    + "|" + ownerType
                    + "|" + ownerId
                    + "|" + userRequested
            );
            if (userRequested) {
                if ("running".equals(status)) {
                    manualRunning.add(requestId);
                } else {
                    manualUnsent.add(requestId);
                }
            }
        }

        Collections.sort(workFacts);
        Collections.sort(manualUnsent);
        Collections.sort(manualRunning);
        StringBuilder token = new StringBuilder();
        appendTokenValues(token, "context", contexts);
        appendTokenValues(token, "group", groups);
        appendTokenValues(token, "work", workFacts);
        return new EditRisk(
            affected,
            contexts,
            groups,
            manualUnsent,
            manualRunning,
            token.toString()
        );
    }

    /**
     * Saves the complete Review draft set and performs the derived follow-ups.
     * New context/group drafts may use a temporary id starting with
     * {@code new-}; references to that temporary id inside group drafts are
     * remapped to the persisted UUID.
     */
    public SaveResult save(
        List<JSONObject> contextDrafts,
        List<JSONObject> groupDrafts,
        String activeContextId,
        String activeGroupId,
        Options options,
        EditRisk acceptedRisk,
        boolean discardUserRequestedUnsent
    ) throws Exception {
        if (acceptedRisk == null) {
            throw new IllegalArgumentException(
                "accepted Review risk snapshot is required"
            );
        }
        return SceneContextStore.withRootAccess(() -> {
            ReviewTransactionJournal journal =
                ReviewTransactionJournal.begin(
                    sceneContextStore,
                    translationJobStore,
                    summaryJobStore
                );
            try {
                EditRisk currentRisk = assessReviewLocked(
                    contextDrafts,
                    groupDrafts
                );
                if (!acceptedRisk.sameSnapshot(currentRisk)) {
                    throw new ConcurrentEditException(
                        "associated work changed after Review confirmation"
                    );
                }
                if (!currentRisk.userRequestedUnsentIds.isEmpty()
                    && !discardUserRequestedUnsent) {
                    throw new IllegalStateException(
                        "user-requested unsent Summary Jobs require an "
                            + "explicit discard decision"
                    );
                }
                int canceled = 0;
                for (String requestId
                    : currentRisk.userRequestedUnsentIds) {
                    if (!summaryJobStore.cancelUserRequestedIfUnsent(
                        requestId
                    )) {
                        throw new ConcurrentEditException(
                            "Summary Job changed before it could be canceled: "
                                + requestId
                        );
                    }
                    canceled++;
                }
                SaveResult result = saveLocked(
                    contextDrafts,
                    groupDrafts,
                    activeContextId,
                    activeGroupId,
                    options
                );
                result.userRequestedJobsCanceled = canceled;
                journal.commit();
                return result;
            } catch (Exception failure) {
                try {
                    journal.rollback();
                } catch (Exception rollbackFailure) {
                    failure.addSuppressed(rollbackFailure);
                }
                throw failure;
            }
        });
    }

    private synchronized SaveResult saveLocked(
        List<JSONObject> contextDrafts,
        List<JSONObject> groupDrafts,
        String activeContextId,
        String activeGroupId,
        Options options
    ) throws Exception {
        if (contextDrafts == null || groupDrafts == null) {
            throw new IllegalArgumentException(
                "contextDrafts and groupDrafts are required"
            );
        }
        contextDrafts = deepCopies(contextDrafts, "Context");
        groupDrafts = deepCopies(groupDrafts, "Group");
        Options effective = options == null ? new Options() : options;
        SaveResult result = new SaveResult();

        Set<String> previousContextIds = new HashSet<>(
            sceneContextStore.listContextIds()
        );
        Set<String> previousGroupIds = new HashSet<>(
            sceneContextStore.listGroupIds()
        );
        Map<String, JSONObject> previousContexts =
            entityMap(sceneContextStore.listContexts());
        Map<String, JSONObject> previousGroups =
            entityMap(sceneContextStore.listGroups());
        String previousActiveContextId = sceneContextStore.getActiveContextId();

        SceneContextStore.ReviewStateResult persisted =
            sceneContextStore.commitReviewState(
                contextDrafts,
                groupDrafts,
                activeContextId,
                activeGroupId
            );
        result.contextsCreated = persisted.contextsCreated;
        result.contextsUpdated = persisted.contextsUpdated;
        result.contextsDeleted = persisted.contextsDeleted;
        result.groupsCreated = persisted.groupsCreated;
        result.groupsUpdated = persisted.groupsUpdated;
        result.groupsDeleted = persisted.groupsDeleted;
        result.activePointersChanged = persisted.activePointersChanged;
        result.contextIdMap.putAll(persisted.contextIdMap);

        Map<String, String> contextIdMap = persisted.contextIdMap;
        List<String> finalContextIds = persisted.contextIds;
        List<String> finalGroupIds = persisted.groupIds;
        Set<String> deletedGroupIds = new HashSet<>(previousGroupIds);
        deletedGroupIds.removeAll(finalGroupIds);
        Set<String> deletedContextIds = new HashSet<>(previousContextIds);
        deletedContextIds.removeAll(finalContextIds);
        summaryJobStore.invalidateJobsForOwners(
            deletedContextIds,
            deletedGroupIds
        );

        // Rewrite queued Translation Job mappings that lost their route.
        List<ContextReviewPlanner.ContextSnapshot> contexts =
            plannerContexts(contextDrafts, contextIdMap);
        List<ContextReviewPlanner.GroupSnapshot> groups =
            plannerGroups(groupDrafts, contextIdMap);
        List<ContextReviewPlanner.QueuedJobState> queuedJobs =
            queuedJobStates();
        List<ContextReviewPlanner.MappingRewrite> rewrites =
            ContextReviewPlanner.planMappingRewrites(
                contexts,
                groups,
                queuedJobs
            );
        for (ContextReviewPlanner.MappingRewrite rewrite : rewrites) {
            translationJobStore.rewriteHistoryMapping(
                rewrite.requestId,
                rewrite.historyMapping
            );
            result.mappingsRewritten++;
        }

        // 6. Reconcile derived summaries for affected target languages.
        reconcileSummaries(
            sceneContextStore,
            contextCompressionCoordinator,
            groupCompressionCoordinator,
            finalContextIds,
            finalGroupIds,
            effective,
            previousContexts,
            previousGroups
        );

        // The management Activity owns a different SceneContextStore facade
        // from the running Service, so its pointer commit cannot rely on the
        // Service facade's in-memory listener. Advance the newly active Group
        // here, inside the same outer Review journal. A bare Active Group
        // switch deliberately does not enter this branch.
        if (!sameNullable(
            previousActiveContextId,
            persisted.activeContextId
        )) {
            GroupCompressionCoordinator.Options groupOptions =
                new GroupCompressionCoordinator.Options();
            groupOptions.autoCompression = effective.autoCompression;
            groupOptions.continueAfterManual = effective.continueAfterManual;
            groupCompressionCoordinator.onActiveContextChanged(
                previousActiveContextId,
                persisted.activeContextId,
                groupOptions
            );
        }

        return result;
    }

    private List<ContextReviewPlanner.QueuedJobState> queuedJobStates()
        throws Exception {
        List<ContextReviewPlanner.QueuedJobState> result = new ArrayList<>();
        for (TranslationJobStore.ReviewJob job
            : translationJobStore.listReviewJobs()) {
            if (!"queued".equals(job.getStatus())) {
                continue;
            }
            result.add(new ContextReviewPlanner.QueuedJobState(
                job.getRequestId(),
                job.getScene(),
                job.getContextId(),
                job.getGroupId()
            ));
        }
        return result;
    }

    static void reconcileSummaries(
        SceneContextStore sceneContextStore,
        ContextCompressionCoordinator contextCompressionCoordinator,
        GroupCompressionCoordinator groupCompressionCoordinator,
        List<String> contextIds,
        List<String> groupIds,
        Options options,
        Map<String, JSONObject> previousContexts,
        Map<String, JSONObject> previousGroups
    ) throws Exception {
        ContextCompressionCoordinator.Options contextOptions =
            new ContextCompressionCoordinator.Options();
        contextOptions.autoCompression = options.autoCompression;
        contextOptions.continueAfterManual = options.continueAfterManual;
        GroupCompressionCoordinator.Options groupOptions =
            new GroupCompressionCoordinator.Options();
        groupOptions.autoCompression = options.autoCompression;
        groupOptions.continueAfterManual = options.continueAfterManual;
        Map<String, JSONObject> currentContexts = entityMap(
            sceneContextStore.listContexts()
        );

        for (String contextId : contextIds) {
            JSONObject current = sceneContextStore.getContext(contextId);
            JSONObject previous = previousContexts.get(contextId);
            Set<String> languages = new HashSet<>();
            languages.addAll(ContextFactLanguages.collect(current));
            languages.addAll(ContextFactLanguages.collect(previous));
            for (String language : languages) {
                reconcileContextLanguage(
                    contextCompressionCoordinator,
                    contextId,
                    language,
                    current,
                    previous,
                    contextOptions
                );
            }
        }
        for (String groupId : groupIds) {
            JSONObject current = sceneContextStore.getGroup(groupId);
            JSONObject previous = previousGroups.get(groupId);
            boolean membershipChanged = groupMembershipChanged(
                previous,
                current
            );
            Set<String> languages = new HashSet<>();
            languages.addAll(summaryLanguages(current));
            languages.addAll(summaryLanguages(previous));
            languages.addAll(groupContextFactLanguages(
                current,
                currentContexts
            ));
            languages.addAll(groupContextFactLanguages(
                previous,
                previousContexts
            ));
            for (String language : languages) {
                reconcileGroupLanguage(
                    sceneContextStore,
                    groupCompressionCoordinator,
                    groupId,
                    language,
                    current,
                    previous,
                    membershipChanged,
                    previousContexts,
                    groupOptions
                );
            }
        }
    }

    private static void reconcileContextLanguage(
        ContextCompressionCoordinator contextCompressionCoordinator,
        String contextId,
        String language,
        JSONObject current,
        JSONObject previous,
        ContextCompressionCoordinator.Options options
    ) throws Exception {
        JSONObject currentLanguage = languageObject(current, language);
        JSONObject previousLanguage = languageObject(previous, language);
        boolean currentManual = currentLanguage != null
            && currentLanguage.has("manual");
        boolean previousManual = previousLanguage != null
            && previousLanguage.has("manual");
        if (currentManual) {
            contextCompressionCoordinator.setManualSummary(
                contextId,
                language,
                currentLanguage.getJSONObject("manual").getString("text"),
                options
            );
        } else if (previousManual) {
            contextCompressionCoordinator.deleteManualSummary(
                contextId,
                language,
                options
            );
        } else {
            contextCompressionCoordinator.onContextFactsChanged(
                contextId,
                language,
                options
            );
        }
    }

    private static void reconcileGroupLanguage(
        SceneContextStore sceneContextStore,
        GroupCompressionCoordinator groupCompressionCoordinator,
        String groupId,
        String language,
        JSONObject current,
        JSONObject previous,
        boolean membershipChanged,
        Map<String, JSONObject> previousContexts,
        GroupCompressionCoordinator.Options options
    ) throws Exception {
        JSONObject currentLanguage = languageObject(current, language);
        JSONObject previousLanguage = languageObject(previous, language);
        boolean currentManual = currentLanguage != null
            && currentLanguage.has("manual");
        boolean previousManual = previousLanguage != null
            && previousLanguage.has("manual");
        if (currentManual) {
            groupCompressionCoordinator.setGroupManualSummary(
                groupId,
                language,
                currentLanguage.getJSONObject("manual").getString("text"),
                options
            );
        } else if (previousManual) {
            groupCompressionCoordinator.deleteGroupManualSummary(
                groupId,
                language,
                options
            );
        } else if (shouldReconcileActiveGroup(
            sceneContextStore,
            groupId,
            language,
            current,
            membershipChanged,
            previousContexts
        )) {
            groupCompressionCoordinator.onGroupFactsChanged(
                groupId,
                language,
                options
            );
        }
    }

    /**
     * Group auto-summary reconciliation must only run when this save actually
     * changed the Group membership or one of its member Contexts' semantic
     * facts. A bare Active Group pointer switch therefore produces no Group
     * Summary Job.
     */
    private static boolean shouldReconcileActiveGroup(
        SceneContextStore sceneContextStore,
        String groupId,
        String language,
        JSONObject currentGroup,
        boolean membershipChanged,
        Map<String, JSONObject> previousContexts
    ) throws Exception {
        if (membershipChanged) {
            return true;
        }
        JSONArray contexts = currentGroup == null
            ? null
            : currentGroup.optJSONArray("contexts");
        if (contexts == null) {
            return false;
        }
        for (int index = 0; index < contexts.length(); index++) {
            String contextId = GroupContextEntry.contextIdAt(contexts, index);
            if (contextId.isEmpty()) {
                continue;
            }
            JSONObject previous = previousContexts.get(contextId);
            JSONObject currentContext = sceneContextStore.getContext(contextId);
            if (previous == null) {
                return true;
            }
            if (!ContextContentHash.compute(previous, language)
                .equals(ContextContentHash.compute(currentContext, language))) {
                return true;
            }
        }
        return false;
    }

    private static boolean groupMembershipChanged(
        JSONObject previous,
        JSONObject current
    ) {
        JSONArray previousContexts = previous == null
            ? null
            : previous.optJSONArray("contexts");
        JSONArray currentContexts = current == null
            ? null
            : current.optJSONArray("contexts");
        if (previousContexts == null || currentContexts == null) {
            return previousContexts != currentContexts;
        }
        if (previousContexts.length() != currentContexts.length()) {
            return true;
        }
        for (int index = 0; index < previousContexts.length(); index++) {
            JSONObject previousEntry = GroupContextEntry.require(
                previousContexts,
                index
            );
            JSONObject currentEntry = GroupContextEntry.require(
                currentContexts,
                index
            );
            if (!previousEntry.optString(GroupContextEntry.ENTRY_ID, "")
                .equals(currentEntry.optString(GroupContextEntry.ENTRY_ID, ""))
                || !previousEntry.optString(GroupContextEntry.CONTEXT_ID, "")
                    .equals(currentEntry.optString(GroupContextEntry.CONTEXT_ID, ""))) {
                return true;
            }
        }
        return false;
    }

    private static JSONObject languageObject(JSONObject entity, String language) {
        if (entity == null) {
            return null;
        }
        JSONObject summary = entity.optJSONObject("summary");
        return summary == null ? null : summary.optJSONObject(language);
    }

    private static Set<String> summaryLanguages(JSONObject entity) {
        Set<String> result = new HashSet<>();
        JSONObject summary = entity == null
            ? null
            : entity.optJSONObject("summary");
        if (summary == null) {
            return result;
        }
        Iterator<String> keys = summary.keys();
        while (keys.hasNext()) {
            String language = keys.next();
            if (language != null && !language.trim().isEmpty()) {
                result.add(language);
            }
        }
        return result;
    }

    private static Map<String, JSONObject> entityMap(List<JSONObject> entities) {
        Map<String, JSONObject> result = new LinkedHashMap<>();
        if (entities != null) {
            for (JSONObject entity : entities) {
                result.put(entity.optString("id", ""), entity);
            }
        }
        return result;
    }

    private static Map<String, JSONObject> existingDraftMap(
        List<JSONObject> drafts
    ) {
        Map<String, JSONObject> result = new LinkedHashMap<>();
        for (JSONObject draft : drafts) {
            if (draft == null) {
                continue;
            }
            String id = draft.optString("id", "");
            if (!id.isEmpty() && !id.startsWith("new-")) {
                result.put(id, draft);
            }
        }
        return result;
    }

    private static Set<String> changedEntityIds(
        Map<String, JSONObject> current,
        Map<String, JSONObject> drafts,
        boolean context
    ) {
        Set<String> result = new HashSet<>();
        for (Map.Entry<String, JSONObject> entry : current.entrySet()) {
            JSONObject draft = drafts.get(entry.getKey());
            if (draft == null
                || !entityFactsEqual(entry.getValue(), draft, context)) {
                result.add(entry.getKey());
            }
        }
        return result;
    }

    private static boolean entityFactsEqual(
        JSONObject current,
        JSONObject draft,
        boolean context
    ) {
        if (!jsonValueEquals(
            current.opt("summary"),
            draft.opt("summary")
        )) {
            return false;
        }
        if (context) {
            return jsonValueEquals(
                    current.opt("manual_descriptions"),
                    draft.opt("manual_descriptions")
                )
                && jsonValueEquals(
                    current.opt("scenes"),
                    draft.opt("scenes")
                );
        }
        return jsonValueEquals(
            current.opt("contexts"),
            draft.opt("contexts")
        );
    }

    private static boolean jsonValueEquals(Object left, Object right) {
        boolean leftNull = left == null || left == JSONObject.NULL;
        boolean rightNull = right == null || right == JSONObject.NULL;
        if (leftNull || rightNull) {
            return leftNull == rightNull;
        }
        if (left instanceof JSONObject && right instanceof JSONObject) {
            JSONObject leftObject = (JSONObject) left;
            JSONObject rightObject = (JSONObject) right;
            Set<String> keys = new HashSet<>();
            Iterator<String> leftKeys = leftObject.keys();
            while (leftKeys.hasNext()) {
                keys.add(leftKeys.next());
            }
            Iterator<String> rightKeys = rightObject.keys();
            while (rightKeys.hasNext()) {
                String key = rightKeys.next();
                if (!keys.contains(key)) {
                    return false;
                }
            }
            if (keys.size() != rightObject.length()) {
                return false;
            }
            for (String key : keys) {
                if (!jsonValueEquals(
                    leftObject.opt(key),
                    rightObject.opt(key)
                )) {
                    return false;
                }
            }
            return true;
        }
        if (left instanceof JSONArray && right instanceof JSONArray) {
            JSONArray leftArray = (JSONArray) left;
            JSONArray rightArray = (JSONArray) right;
            if (leftArray.length() != rightArray.length()) {
                return false;
            }
            for (int index = 0; index < leftArray.length(); index++) {
                if (!jsonValueEquals(
                    leftArray.opt(index),
                    rightArray.opt(index)
                )) {
                    return false;
                }
            }
            return true;
        }
        return left.equals(right);
    }

    private static void addGroupsReferencingContexts(
        Set<String> target,
        Set<String> contextIds,
        Collection<JSONObject> groups
    ) {
        if (contextIds.isEmpty()) {
            return;
        }
        for (JSONObject group : groups) {
            JSONArray entries = group.optJSONArray("contexts");
            if (entries == null) {
                continue;
            }
            for (int index = 0; index < entries.length(); index++) {
                if (contextIds.contains(GroupContextEntry.contextIdAt(
                    entries,
                    index
                ))) {
                    String groupId = group.optString("id", "");
                    if (!groupId.isEmpty() && !groupId.startsWith("new-")) {
                        target.add(groupId);
                    }
                    break;
                }
            }
        }
    }

    private void applyBatchContextEdit(SceneBatchPlanner.ContextEdit edit)
        throws Exception {
        JSONObject current = sceneContextStore.getContext(edit.contextId);
        long currentRevision = current.optLong("revision", -1L);
        if (currentRevision != edit.expectedRevision) {
            throw new ConcurrentEditException(
                "Context changed after the batch page loaded: "
                    + edit.contextId
            );
        }
        JSONObject draft = new JSONObject(current.toString());
        draft.put("revision", edit.expectedRevision);
        draft.put("scenes", buildBatchSceneEntries(current, edit.scenes));
        sceneContextStore.updateContext(
            edit.contextId,
            draft,
            edit.expectedRevision
        );
    }

    private static JSONArray buildBatchSceneEntries(
        JSONObject currentContext,
        List<String> sceneNames
    ) throws Exception {
        Map<String, JSONObject> existing = new LinkedHashMap<>();
        JSONArray currentScenes = currentContext.optJSONArray("scenes");
        if (currentScenes != null) {
            for (int index = 0; index < currentScenes.length(); index++) {
                JSONObject entry = currentScenes.optJSONObject(index);
                if (entry == null) {
                    throw new IllegalArgumentException(
                        "Context contains an invalid Scene entry"
                    );
                }
                String scene = entry.optString("scene", "");
                if (scene.isEmpty() || existing.put(scene, entry) != null) {
                    throw new IllegalArgumentException(
                        "Context contains an empty or duplicate Scene"
                    );
                }
            }
        }
        Set<String> seen = new HashSet<>();
        JSONArray output = new JSONArray();
        long now = System.currentTimeMillis();
        for (String sceneName : nullSafe(sceneNames)) {
            if (sceneName == null
                || sceneName.trim().isEmpty()
                || !seen.add(sceneName)) {
                throw new IllegalArgumentException(
                    "batch Context Scene list is empty or duplicated"
                );
            }
            JSONObject entry = existing.get(sceneName);
            if (entry == null) {
                entry = new JSONObject()
                    .put("entry_id", UUID.randomUUID().toString())
                    .put("scene", sceneName)
                    .put("scene_file", SceneStore.fileNameForScene(sceneName))
                    .put("created_at", now)
                    .put("updated_at", now)
                    .put("summaries", new JSONObject());
            } else {
                entry = new JSONObject(entry.toString());
            }
            output.put(entry);
        }
        return output;
    }

    private void validateBatchHistoryRoute(
        SceneBatchPlanner.JobCreation creation
    ) throws Exception {
        HistoryMapping.Resolution resolution =
            HistoryMapping.resolutionOfValue(creation.historyMapping);
        if (resolution == HistoryMapping.Resolution.NO_HISTORY) {
            return;
        }
        if (resolution != HistoryMapping.Resolution.VALID) {
            throw new IllegalArgumentException(
                "invalid Translation history mapping for " + creation.scene
            );
        }
        JSONObject mapping = (JSONObject) creation.historyMapping;
        String contextId = mapping.optString(HistoryMapping.CONTEXT_ID, "");
        JSONObject context = sceneContextStore.getContext(contextId);
        if (!contextContainsScene(context, creation.scene)) {
            throw new ConcurrentEditException(
                "history Context no longer contains Scene " + creation.scene
            );
        }
        if (mapping.isNull(HistoryMapping.GROUP_ID)) {
            return;
        }
        String groupId = mapping.optString(HistoryMapping.GROUP_ID, "");
        JSONObject group = sceneContextStore.getGroup(groupId);
        if (!groupContainsContext(group, contextId)) {
            throw new ConcurrentEditException(
                "history Group no longer contains Context " + contextId
            );
        }
    }

    private static Set<String> groupContextFactLanguages(
        JSONObject group,
        Map<String, JSONObject> contexts
    ) {
        Set<String> result = new HashSet<>();
        if (group == null || contexts == null) {
            return result;
        }
        JSONArray entries = group.optJSONArray("contexts");
        if (entries == null) {
            return result;
        }
        for (int index = 0; index < entries.length(); index++) {
            JSONObject context = contexts.get(GroupContextEntry.contextIdAt(
                entries,
                index
            ));
            result.addAll(ContextFactLanguages.collect(context));
        }
        return result;
    }

    private static boolean contextContainsScene(
        JSONObject context,
        String scene
    ) {
        JSONArray entries = context == null
            ? null
            : context.optJSONArray("scenes");
        if (entries == null) {
            return false;
        }
        for (int index = 0; index < entries.length(); index++) {
            JSONObject entry = entries.optJSONObject(index);
            if (entry != null
                && scene.equals(entry.optString("scene", ""))) {
                return true;
            }
        }
        return false;
    }

    private static boolean groupContainsContext(
        JSONObject group,
        String contextId
    ) {
        JSONArray entries = group == null
            ? null
            : group.optJSONArray("contexts");
        if (entries == null) {
            return false;
        }
        for (int index = 0; index < entries.length(); index++) {
            if (contextId.equals(GroupContextEntry.contextIdAt(
                entries,
                index
            ))) {
                return true;
            }
        }
        return false;
    }

    private static List<String> dependentGroupIds(
        Set<String> contextIds,
        List<JSONObject> groups
    ) {
        List<String> result = new ArrayList<>();
        if (contextIds == null || contextIds.isEmpty() || groups == null) {
            return result;
        }
        for (JSONObject group : groups) {
            JSONArray entries = group.optJSONArray("contexts");
            if (entries == null) {
                continue;
            }
            for (int index = 0; index < entries.length(); index++) {
                if (contextIds.contains(GroupContextEntry.contextIdAt(
                    entries,
                    index
                ))) {
                    result.add(group.optString("id", ""));
                    break;
                }
            }
        }
        return uniqueSorted(result);
    }

    private static boolean isAffected(
        String contextId,
        String groupId,
        Set<String> contexts,
        Set<String> groups
    ) {
        return (contextId != null && contexts.contains(contextId))
            || (groupId != null && groups.contains(groupId));
    }

    private static List<String> immutableCopy(List<String> values) {
        return Collections.unmodifiableList(new ArrayList<>(values));
    }

    private static List<String> uniqueSorted(List<String> values) {
        Set<String> unique = new HashSet<>();
        for (String value : nullSafe(values)) {
            if (value != null && !value.trim().isEmpty()) {
                unique.add(value);
            }
        }
        List<String> result = new ArrayList<>(unique);
        Collections.sort(result);
        return result;
    }

    private static void appendTokenValues(
        StringBuilder token,
        String kind,
        List<String> values
    ) {
        for (String value : values) {
            token.append(kind).append('|').append(value).append('\n');
        }
    }

    private static String nullToken(String value) {
        return value == null ? "<null>" : value;
    }

    private static boolean sameNullable(String left, String right) {
        return left == null ? right == null : left.equals(right);
    }

    private static List<JSONObject> deepCopies(
        List<JSONObject> drafts,
        String kind
    ) {
        List<JSONObject> copies = new ArrayList<>();
        for (JSONObject draft : drafts) {
            if (draft == null) {
                throw new IllegalArgumentException(kind + " draft is null");
            }
            try {
                copies.add(new JSONObject(draft.toString()));
            } catch (org.json.JSONException e) {
                throw new IllegalArgumentException(
                    "could not copy " + kind + " draft",
                    e
                );
            }
        }
        return copies;
    }

    private static List<ContextReviewPlanner.ContextSnapshot> plannerContexts(
        List<JSONObject> drafts,
        Map<String, String> idMap
    ) {
        List<ContextReviewPlanner.ContextSnapshot> result = new ArrayList<>();
        for (JSONObject draft : drafts) {
            String id = mappedId(draft.optString("id", ""), idMap);
            List<String> scenes = new ArrayList<>();
            JSONArray sceneArray = draft.optJSONArray("scenes");
            if (sceneArray != null) {
                for (int index = 0; index < sceneArray.length(); index++) {
                    JSONObject entry = sceneArray.optJSONObject(index);
                    if (entry != null) {
                        scenes.add(entry.optString("scene", ""));
                    }
                }
            }
            result.add(new ContextReviewPlanner.ContextSnapshot(id, scenes));
        }
        return result;
    }

    private static List<ContextReviewPlanner.GroupSnapshot> plannerGroups(
        List<JSONObject> drafts,
        Map<String, String> idMap
    ) {
        List<ContextReviewPlanner.GroupSnapshot> result = new ArrayList<>();
        for (JSONObject draft : drafts) {
            String id = mappedId(draft.optString("id", ""), idMap);
            List<String> contextIds = new ArrayList<>();
            JSONArray contextArray = draft.optJSONArray("contexts");
            if (contextArray != null) {
                for (int index = 0; index < contextArray.length(); index++) {
                    String contextId = GroupContextEntry.contextIdAt(
                        contextArray,
                        index
                    );
                    if (!contextId.isEmpty()) {
                        contextIds.add(mappedId(contextId, idMap));
                    }
                }
            }
            result.add(new ContextReviewPlanner.GroupSnapshot(id, contextIds));
        }
        return result;
    }

    private static String mappedId(String id, Map<String, String> idMap) {
        String mapped = idMap.get(id);
        return mapped == null ? id : mapped;
    }

    private static List<String> nullSafe(List<String> values) {
        return values == null ? new ArrayList<String>() : values;
    }
}
