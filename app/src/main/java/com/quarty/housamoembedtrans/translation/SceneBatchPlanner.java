package com.quarty.housamoembedtrans.translation;

import com.quarty.housamoembedtrans.storage.HistoryMapping;

import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Pure planning rules for the "Edit all Scenes" batch page.
 *
 * <p>The page keeps only an in-memory draft while the user edits. On the final
 * confirmation it asks this class for one {@link CommitPlan}: which Scene
 * Contexts need their ordered Scene membership rewritten, which Translation
 * Jobs must be created for scenes explicitly marked "send API", and which
 * queued Job mappings must be repaired after the structural edit.</p>
 *
 * <p>This class deliberately has no Android or store dependencies so the
 * draft/commit semantics can be covered by host JUnit tests.</p>
 */
public final class SceneBatchPlanner {

    private SceneBatchPlanner() {
        throw new AssertionError("No instances");
    }

    /** Immutable context membership snapshot used by the planner. */
    public static final class ContextSnapshot {
        public final String id;
        public final String displayName;
        public final List<String> scenes;

        public ContextSnapshot(String id, List<String> scenes) {
            this(id, id, scenes);
        }

        public ContextSnapshot(
            String id,
            String displayName,
            List<String> scenes
        ) {
            if (id == null || id.trim().isEmpty()) {
                throw new IllegalArgumentException("context id is required");
            }
            this.id = id;
            this.displayName = displayName == null || displayName.trim().isEmpty()
                ? id
                : displayName;
            this.scenes = Collections.unmodifiableList(
                new ArrayList<>(scenes == null ? Collections.emptyList() : scenes)
            );
        }
    }

    /** Immutable group membership snapshot used by the planner. */
    public static final class GroupSnapshot {
        public final String id;
        public final String displayName;
        public final List<String> contextIds;

        public GroupSnapshot(String id, List<String> contextIds) {
            this(id, id, contextIds);
        }

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
                new ArrayList<>(contextIds == null ? Collections.emptyList() : contextIds)
            );
        }
    }

    /** Lightweight persisted Translation Job fact used for de-duplication. */
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

    /** Mutable in-memory choice for one Scene. */
    public static final class SceneDraft {
        private final String scene;
        private String contextId;
        private int position;
        private String groupId;
        private boolean sendApi;

        SceneDraft(
            String scene,
            String contextId,
            int position,
            String groupId,
            boolean sendApi
        ) {
            if (scene == null || scene.trim().isEmpty()) {
                throw new IllegalArgumentException("scene is required");
            }
            this.scene = scene;
            this.contextId = contextId;
            this.position = position;
            this.groupId = groupId;
            this.sendApi = sendApi;
        }

        public String getScene() {
            return scene;
        }

        public String getContextId() {
            return contextId;
        }

        public void setContextId(String contextId) {
            this.contextId = contextId;
        }

        public int getPosition() {
            return position;
        }

        public void setPosition(int position) {
            this.position = position;
        }

        public String getGroupId() {
            return groupId;
        }

        public void setGroupId(String groupId) {
            this.groupId = groupId;
        }

        public boolean isSendApi() {
            return sendApi;
        }

        public void setSendApi(boolean sendApi) {
            this.sendApi = sendApi;
        }
    }

    /** Immutable page state: local scenes plus current Context/Group/Job facts. */
    public static final class Plan {
        public final List<SceneDraft> scenes;
        public final List<ContextSnapshot> contexts;
        public final List<GroupSnapshot> groups;
        public final List<JobSnapshot> jobs;

        Plan(
            List<SceneDraft> scenes,
            List<ContextSnapshot> contexts,
            List<GroupSnapshot> groups,
            List<JobSnapshot> jobs
        ) {
            this.scenes = Collections.unmodifiableList(
                new ArrayList<>(scenes == null ? Collections.emptyList() : scenes)
            );
            this.contexts = Collections.unmodifiableList(
                new ArrayList<>(contexts == null ? Collections.emptyList() : contexts)
            );
            this.groups = Collections.unmodifiableList(
                new ArrayList<>(groups == null ? Collections.emptyList() : groups)
            );
            this.jobs = Collections.unmodifiableList(
                new ArrayList<>(jobs == null ? Collections.emptyList() : jobs)
            );
        }
    }

    /** One Scene Context whose ordered membership must be persisted. */
    public static final class ContextEdit {
        public final String contextId;
        public final List<String> scenes;

        ContextEdit(String contextId, List<String> scenes) {
            this.contextId = contextId;
            this.scenes = Collections.unmodifiableList(
                new ArrayList<>(scenes == null ? Collections.emptyList() : scenes)
            );
        }
    }

    /** One Translation Job to create because the user marked the Scene "send API". */
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

    /** The single commit action produced by a confirmed draft. */
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
                new ArrayList<>(contextEdits == null ? Collections.emptyList() : contextEdits)
            );
            this.jobCreations = Collections.unmodifiableList(
                new ArrayList<>(jobCreations == null ? Collections.emptyList() : jobCreations)
            );
            this.mappingRewrites = Collections.unmodifiableList(
                new ArrayList<>(mappingRewrites == null ? Collections.emptyList() : mappingRewrites)
            );
        }
    }

    /** Pure seam so commit planning can stay independent of JSON/Android. */
    public interface RequestFactory {
        byte[] build(String scene) throws Exception;
    }

    /**
     * Builds the initial page draft. Each Scene keeps its current Context when
     * it is already a member; otherwise it starts with no history. API sending
     * is always opt-in.
     */
    public static Plan createInitialPlan(
        List<String> sceneNames,
        List<ContextSnapshot> contexts,
        List<GroupSnapshot> groups,
        List<JobSnapshot> jobs
    ) {
        List<String> names = new ArrayList<>(
            sceneNames == null ? Collections.emptyList() : sceneNames
        );
        Collections.sort(names);
        Map<String, ContextSnapshot> contextById = indexContexts(contexts);

        List<SceneDraft> drafts = new ArrayList<>();
        for (String scene : names) {
            String contextId = null;
            int position = 0;
            for (ContextSnapshot context : contexts) {
                int index = context.scenes.indexOf(scene);
                if (index >= 0) {
                    contextId = context.id;
                    position = index;
                    break;
                }
            }
            JobSnapshot mappedJob = findMappingJob(scene, jobs);
            if (contextId == null
                && mappedJob != null
                && mappedJob.contextId != null
                && contextById.containsKey(mappedJob.contextId)) {
                contextId = mappedJob.contextId;
                position = contextById.get(contextId).scenes.indexOf(scene);
                if (position < 0) {
                    position = 0;
                }
            }
            String groupId = null;
            if (contextId != null
                && mappedJob != null
                && contextId.equals(mappedJob.contextId)) {
                groupId = mappedJob.groupId;
            }
            drafts.add(new SceneDraft(scene, contextId, position, groupId, false));
        }
        return new Plan(drafts, contexts, groups, jobs);
    }

    /**
     * Returns user-facing validation errors. Empty result means the draft may
     * be committed.
     */
    public static List<String> validate(Plan plan) {
        List<String> errors = new ArrayList<>();
        if (plan == null) {
            errors.add("Plan is null");
            return errors;
        }
        Map<String, ContextSnapshot> contextById = indexContexts(plan.contexts);
        Map<String, GroupSnapshot> groupById = indexGroups(plan.groups);

        for (SceneDraft draft : plan.scenes) {
            if (draft.getContextId() != null
                && !contextById.containsKey(draft.getContextId())) {
                errors.add("Scene " + draft.getScene()
                    + " references unknown context " + draft.getContextId());
            }
            if (draft.getGroupId() != null
                && !groupById.containsKey(draft.getGroupId())) {
                errors.add("Scene " + draft.getScene()
                    + " references unknown group " + draft.getGroupId());
                continue;
            }
            if (draft.getContextId() == null && draft.getGroupId() != null) {
                errors.add("Scene " + draft.getScene()
                    + " has a group but no context");
            } else if (draft.getContextId() != null
                && draft.getGroupId() != null) {
                GroupSnapshot group = groupById.get(draft.getGroupId());
                if (group == null || !group.contextIds.contains(draft.getContextId())) {
                    errors.add("Scene " + draft.getScene()
                        + " group " + draft.getGroupId()
                        + " does not contain context " + draft.getContextId());
                }
            }
            if (draft.getPosition() < 0) {
                errors.add("Scene " + draft.getScene()
                    + " has a negative position");
            }
        }
        return errors;
    }

    /**
     * Computes the one-shot commit from the confirmed draft.
     *
     * <p>The planner assumes the page's Context selection is the Scene's single
     * membership for this batch edit. Scenes assigned to a Context are placed in
     * that Context's ordered list at the chosen insertion position; Scenes with
     * no history are removed from every Context.</p>
     */
    public static CommitPlan planCommit(Plan plan, RequestFactory requestFactory)
        throws Exception {
        if (plan == null) {
            throw new IllegalArgumentException("plan is null");
        }
        if (requestFactory == null) {
            throw new IllegalArgumentException("requestFactory is null");
        }
        List<String> validationErrors = validate(plan);
        if (!validationErrors.isEmpty()) {
            throw new IllegalArgumentException(
                "Cannot commit invalid scene batch: " + validationErrors
            );
        }

        Map<String, List<SceneDraft>> assigned = new LinkedHashMap<>();
        for (ContextSnapshot context : plan.contexts) {
            assigned.put(context.id, new ArrayList<>());
        }
        for (SceneDraft draft : plan.scenes) {
            if (draft.getContextId() != null) {
                List<SceneDraft> members = assigned.get(draft.getContextId());
                if (members != null) {
                    members.add(draft);
                }
            }
        }

        List<ContextEdit> contextEdits = new ArrayList<>();
        List<ContextSnapshot> finalContexts = new ArrayList<>();
        for (ContextSnapshot context : plan.contexts) {
            List<SceneDraft> members = assigned.get(context.id);
            members.sort((left, right) -> {
                int byPosition = Integer.compare(
                    left.getPosition(),
                    right.getPosition()
                );
                if (byPosition != 0) {
                    return byPosition;
                }
                return left.getScene().compareTo(right.getScene());
            });
            List<String> finalSceneNames = new ArrayList<>();
            for (SceneDraft member : members) {
                finalSceneNames.add(member.getScene());
            }
            finalContexts.add(new ContextSnapshot(
                context.id,
                context.displayName,
                finalSceneNames
            ));
            if (!context.scenes.equals(finalSceneNames)) {
                contextEdits.add(new ContextEdit(context.id, finalSceneNames));
            }
        }

        List<JobCreation> jobCreations = new ArrayList<>();
        for (SceneDraft draft : plan.scenes) {
            if (!draft.isSendApi()) {
                continue;
            }
            if (hasExistingJob(draft.getScene(), plan.jobs)) {
                continue;
            }
            byte[] requestJson = requestFactory.build(draft.getScene());
            Object historyMapping = draft.getContextId() == null
                ? JSONObject.NULL
                : HistoryMapping.fromActivePointers(
                    draft.getContextId(),
                    draft.getGroupId()
                );
            jobCreations.add(new JobCreation(
                draft.getScene(),
                requestJson,
                historyMapping
            ));
        }

        List<ContextReviewPlanner.QueuedJobState> queuedJobs =
            new ArrayList<>();
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
        List<ContextReviewPlanner.ContextSnapshot> reviewContexts =
            new ArrayList<>();
        for (ContextSnapshot context : finalContexts) {
            reviewContexts.add(new ContextReviewPlanner.ContextSnapshot(
                context.id,
                context.scenes
            ));
        }
        List<ContextReviewPlanner.GroupSnapshot> reviewGroups =
            new ArrayList<>();
        for (GroupSnapshot group : plan.groups) {
            reviewGroups.add(new ContextReviewPlanner.GroupSnapshot(
                group.id,
                group.contextIds
            ));
        }
        List<ContextReviewPlanner.MappingRewrite> mappingRewrites =
            ContextReviewPlanner.planMappingRewrites(
                reviewContexts,
                reviewGroups,
                queuedJobs
            );

        return new CommitPlan(contextEdits, jobCreations, mappingRewrites);
    }

    /** Returns true when the Scene already has an active or completed Job. */
    public static boolean hasExistingJob(
        String scene,
        List<JobSnapshot> jobs
    ) {
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
        if (jobs == null) {
            return null;
        }
        for (JobSnapshot job : jobs) {
            if (scene.equals(job.scene)
                && job.contextId != null
                && !job.contextId.trim().isEmpty()) {
                return job;
            }
        }
        return null;
    }

    private static Map<String, ContextSnapshot> indexContexts(
        List<ContextSnapshot> contexts
    ) {
        Map<String, ContextSnapshot> result = new LinkedHashMap<>();
        if (contexts != null) {
            for (ContextSnapshot context : contexts) {
                result.put(context.id, context);
            }
        }
        return result;
    }

    private static Map<String, GroupSnapshot> indexGroups(
        List<GroupSnapshot> groups
    ) {
        Map<String, GroupSnapshot> result = new LinkedHashMap<>();
        if (groups != null) {
            for (GroupSnapshot group : groups) {
                result.put(group.id, group);
            }
        }
        return result;
    }

}
