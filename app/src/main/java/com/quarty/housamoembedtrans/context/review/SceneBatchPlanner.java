package com.quarty.housamoembedtrans.context.review;

import com.quarty.housamoembedtrans.context.model.HistoryMapping;

import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Pure planning rules for the Context-owned "Edit all Scenes" draft. */
public final class SceneBatchPlanner {

    private SceneBatchPlanner() {
        throw new AssertionError("No instances");
    }

    /**
     * Mutable Context aggregate. Membership and order live here, never on a
     * Scene. The original revision/list remain the final-commit CAS baseline.
     */
    public static final class ContextDraft {
        public final String id;
        public final String displayName;
        public final long revision;
        private final List<String> originalScenes;
        private final List<String> scenes;

        public ContextDraft(
            String id,
            String displayName,
            long revision,
            List<String> scenes
        ) {
            if (id == null || id.trim().isEmpty()) {
                throw new IllegalArgumentException("context id is required");
            }
            if (revision < 0L) {
                throw new IllegalArgumentException("context revision is required");
            }
            this.id = id;
            this.displayName = displayName == null || displayName.trim().isEmpty()
                ? id
                : displayName;
            this.revision = revision;
            List<String> copy = new ArrayList<>(
                scenes == null ? Collections.emptyList() : scenes
            );
            this.originalScenes = Collections.unmodifiableList(
                new ArrayList<>(copy)
            );
            this.scenes = copy;
        }

        public List<String> getScenes() {
            return Collections.unmodifiableList(scenes);
        }

        public List<String> getOriginalScenes() {
            return originalScenes;
        }

        public boolean contains(String scene) {
            return scenes.contains(scene);
        }

        public boolean addScene(String scene) {
            if (scene == null || scene.trim().isEmpty() || scenes.contains(scene)) {
                return false;
            }
            scenes.add(scene);
            return true;
        }

        public boolean removeScene(String scene) {
            return scenes.remove(scene);
        }

        public boolean moveScene(int from, int to) {
            if (from < 0 || from >= scenes.size()
                || to < 0 || to >= scenes.size()
                || from == to) {
                return false;
            }
            String scene = scenes.remove(from);
            scenes.add(to, scene);
            return true;
        }
    }

    /** Immutable Group membership used to validate one Job history route. */
    public static final class GroupSnapshot {
        public final String id;
        public final String displayName;
        public final List<String> contextIds;

        public GroupSnapshot(
            String id,
            String displayName,
            List<String> contextIds
        ) {
            if (id == null || id.trim().isEmpty()) {
                throw new IllegalArgumentException("group id is required");
            }
            this.id = id;
            this.displayName = displayName == null || displayName.trim().isEmpty()
                ? id
                : displayName;
            this.contextIds = Collections.unmodifiableList(
                new ArrayList<>(
                    contextIds == null
                        ? Collections.emptyList()
                        : contextIds
                )
            );
        }
    }

    /** Lightweight durable Translation Job fact used only for display/planning. */
    public static final class JobSnapshot {
        public final String requestId;
        public final String scene;
        public final String status;
        public final String contextId;
        public final String groupId;

        public JobSnapshot(
            String requestId,
            String scene,
            String status,
            String contextId,
            String groupId
        ) {
            if (requestId == null || requestId.trim().isEmpty()) {
                throw new IllegalArgumentException("requestId is required");
            }
            this.requestId = requestId;
            this.scene = scene == null ? "" : scene;
            this.status = status == null ? "" : status;
            this.contextId = contextId;
            this.groupId = groupId;
        }
    }

    /** Mutable API choice; it does not own Context membership. */
    public static final class SceneDraft {
        private final String scene;
        private String historyContextId;
        private String historyGroupId;
        private boolean sendApi;

        SceneDraft(
            String scene,
            String historyContextId,
            String historyGroupId,
            boolean sendApi
        ) {
            if (scene == null || scene.trim().isEmpty()) {
                throw new IllegalArgumentException("scene is required");
            }
            this.scene = scene;
            this.historyContextId = historyContextId;
            this.historyGroupId = historyGroupId;
            this.sendApi = sendApi;
        }

        public String getScene() {
            return scene;
        }

        public String getHistoryContextId() {
            return historyContextId;
        }

        public void setHistoryContextId(String historyContextId) {
            this.historyContextId = historyContextId;
        }

        public String getHistoryGroupId() {
            return historyGroupId;
        }

        public void setHistoryGroupId(String historyGroupId) {
            this.historyGroupId = historyGroupId;
        }

        public boolean isSendApi() {
            return sendApi;
        }

        public void setSendApi(boolean sendApi) {
            this.sendApi = sendApi;
        }
    }

    public static final class Plan {
        public final List<String> localScenes;
        public final List<SceneDraft> scenes;
        public final List<ContextDraft> contexts;
        public final List<GroupSnapshot> groups;
        public final List<JobSnapshot> jobs;

        Plan(
            List<String> localScenes,
            List<SceneDraft> scenes,
            List<ContextDraft> contexts,
            List<GroupSnapshot> groups,
            List<JobSnapshot> jobs
        ) {
            this.localScenes = Collections.unmodifiableList(
                new ArrayList<>(localScenes)
            );
            this.scenes = Collections.unmodifiableList(new ArrayList<>(scenes));
            this.contexts = Collections.unmodifiableList(new ArrayList<>(contexts));
            this.groups = Collections.unmodifiableList(new ArrayList<>(groups));
            this.jobs = Collections.unmodifiableList(new ArrayList<>(jobs));
        }
    }

    public static final class ContextEdit {
        public final String contextId;
        public final long expectedRevision;
        public final List<String> scenes;

        ContextEdit(
            String contextId,
            long expectedRevision,
            List<String> scenes
        ) {
            this.contextId = contextId;
            this.expectedRevision = expectedRevision;
            this.scenes = Collections.unmodifiableList(new ArrayList<>(scenes));
        }
    }

    public static final class JobCreation {
        public final String scene;
        public final byte[] requestJson;
        public final Object historyMapping;

        JobCreation(String scene, byte[] requestJson, Object historyMapping) {
            if (scene == null || scene.trim().isEmpty()) {
                throw new IllegalArgumentException("scene is required");
            }
            if (requestJson == null || requestJson.length == 0) {
                throw new IllegalArgumentException("requestJson is required");
            }
            this.scene = scene;
            this.requestJson = requestJson;
            this.historyMapping = historyMapping;
        }
    }

    public static final class CommitPlan {
        public final List<ContextEdit> contextEdits;
        public final List<JobCreation> jobCreations;
        public final List<ContextReviewPlanner.MappingRewrite> mappingRewrites;

        CommitPlan(
            List<ContextEdit> contextEdits,
            List<JobCreation> jobCreations,
            List<ContextReviewPlanner.MappingRewrite> mappingRewrites
        ) {
            this.contextEdits = Collections.unmodifiableList(
                new ArrayList<>(contextEdits)
            );
            this.jobCreations = Collections.unmodifiableList(
                new ArrayList<>(jobCreations)
            );
            this.mappingRewrites = Collections.unmodifiableList(
                new ArrayList<>(mappingRewrites)
            );
        }
    }

    public interface RequestFactory {
        byte[] build(String scene) throws Exception;
    }

    public static Plan createInitialPlan(
        List<String> sceneNames,
        List<ContextDraft> contexts,
        List<GroupSnapshot> groups,
        List<JobSnapshot> jobs
    ) {
        List<String> names = new ArrayList<>(
            sceneNames == null ? Collections.emptyList() : sceneNames
        );
        Collections.sort(names);
        List<ContextDraft> contextValues = contexts == null
            ? Collections.emptyList()
            : contexts;
        List<JobSnapshot> jobValues = jobs == null
            ? Collections.emptyList()
            : jobs;
        List<SceneDraft> sceneDrafts = new ArrayList<>();
        for (String scene : names) {
            JobSnapshot mapped = findMappingJob(scene, jobValues);
            sceneDrafts.add(new SceneDraft(
                scene,
                mapped == null ? null : mapped.contextId,
                mapped == null ? null : mapped.groupId,
                false
            ));
        }
        return new Plan(
            names,
            sceneDrafts,
            contextValues,
            groups == null ? Collections.emptyList() : groups,
            jobValues
        );
    }

    public static List<String> validate(Plan plan) {
        List<String> errors = new ArrayList<>();
        if (plan == null) {
            errors.add("Plan is null");
            return errors;
        }
        Map<String, ContextDraft> contextById = indexContexts(plan.contexts);
        Map<String, GroupSnapshot> groupById = indexGroups(plan.groups);
        for (ContextDraft context : plan.contexts) {
            Set<String> seen = new HashSet<>();
            for (String scene : context.getScenes()) {
                if (scene == null || scene.trim().isEmpty() || !seen.add(scene)) {
                    errors.add("Context " + context.id
                        + " has an empty or duplicate Scene " + scene);
                }
            }
        }
        for (SceneDraft draft : plan.scenes) {
            String contextId = draft.getHistoryContextId();
            String groupId = draft.getHistoryGroupId();
            ContextDraft context = contextById.get(contextId);
            if (contextId != null && context == null) {
                errors.add("Scene " + draft.getScene()
                    + " references unknown history Context " + contextId);
                continue;
            }
            if (context != null && !context.contains(draft.getScene())) {
                errors.add("Scene " + draft.getScene()
                    + " is not a member of history Context " + contextId);
            }
            if (contextId == null && groupId != null) {
                errors.add("Scene " + draft.getScene()
                    + " has a history Group but no Context");
                continue;
            }
            if (groupId != null) {
                GroupSnapshot group = groupById.get(groupId);
                if (group == null || !group.contextIds.contains(contextId)) {
                    errors.add("Scene " + draft.getScene()
                        + " Group does not contain its history Context");
                }
            }
        }
        return errors;
    }

    public static CommitPlan planCommit(Plan plan, RequestFactory requestFactory)
        throws Exception {
        if (plan == null || requestFactory == null) {
            throw new IllegalArgumentException("plan and requestFactory are required");
        }
        List<String> errors = validate(plan);
        if (!errors.isEmpty()) {
            throw new IllegalArgumentException(
                "Cannot commit invalid scene batch: " + errors
            );
        }

        List<ContextEdit> contextEdits = new ArrayList<>();
        for (ContextDraft context : plan.contexts) {
            if (!context.getOriginalScenes().equals(context.getScenes())) {
                contextEdits.add(new ContextEdit(
                    context.id,
                    context.revision,
                    context.getScenes()
                ));
            }
        }

        List<JobCreation> jobCreations = new ArrayList<>();
        for (SceneDraft draft : plan.scenes) {
            if (!draft.isSendApi()
                || hasExistingJob(draft.getScene(), plan.jobs)) {
                continue;
            }
            byte[] requestJson = requestFactory.build(draft.getScene());
            Object mapping = draft.getHistoryContextId() == null
                ? JSONObject.NULL
                : HistoryMapping.fromActivePointers(
                    draft.getHistoryContextId(),
                    draft.getHistoryGroupId()
                );
            jobCreations.add(new JobCreation(
                draft.getScene(),
                requestJson,
                mapping
            ));
        }

        List<ContextReviewPlanner.ContextSnapshot> finalContexts =
            new ArrayList<>();
        for (ContextDraft context : plan.contexts) {
            finalContexts.add(new ContextReviewPlanner.ContextSnapshot(
                context.id,
                context.getScenes()
            ));
        }
        List<ContextReviewPlanner.GroupSnapshot> finalGroups = new ArrayList<>();
        for (GroupSnapshot group : plan.groups) {
            finalGroups.add(new ContextReviewPlanner.GroupSnapshot(
                group.id,
                group.contextIds
            ));
        }
        List<ContextReviewPlanner.QueuedJobState> queuedJobs = new ArrayList<>();
        for (JobSnapshot job : plan.jobs) {
            if ("queued".equals(job.status)) {
                queuedJobs.add(new ContextReviewPlanner.QueuedJobState(
                    job.requestId,
                    job.scene,
                    job.contextId,
                    job.groupId
                ));
            }
        }
        return new CommitPlan(
            contextEdits,
            jobCreations,
            ContextReviewPlanner.planMappingRewrites(
                finalContexts,
                finalGroups,
                queuedJobs
            )
        );
    }

    public static boolean hasExistingJob(String scene, List<JobSnapshot> jobs) {
        if (scene == null || jobs == null) {
            return false;
        }
        for (JobSnapshot job : jobs) {
            if (scene.equals(job.scene)
                && ("queued".equals(job.status)
                    || "running".equals(job.status)
                    || "completed".equals(job.status))) {
                return true;
            }
        }
        return false;
    }

    private static JobSnapshot findMappingJob(
        String scene,
        List<JobSnapshot> jobs
    ) {
        for (JobSnapshot job : jobs) {
            if (scene.equals(job.scene)
                && job.contextId != null
                && !job.contextId.trim().isEmpty()) {
                return job;
            }
        }
        return null;
    }

    private static Map<String, ContextDraft> indexContexts(
        List<ContextDraft> contexts
    ) {
        Map<String, ContextDraft> result = new LinkedHashMap<>();
        for (ContextDraft context : contexts) {
            if (result.put(context.id, context) != null) {
                throw new IllegalArgumentException(
                    "duplicate Context draft id " + context.id
                );
            }
        }
        return result;
    }

    private static Map<String, GroupSnapshot> indexGroups(
        List<GroupSnapshot> groups
    ) {
        Map<String, GroupSnapshot> result = new LinkedHashMap<>();
        for (GroupSnapshot group : groups) {
            if (result.put(group.id, group) != null) {
                throw new IllegalArgumentException(
                    "duplicate Group id " + group.id
                );
            }
        }
        return result;
    }
}
