package com.quarty.housamoembedtrans.translation;

import com.quarty.housamoembedtrans.storage.HistoryMapping;

import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Pure planning rules for the Context/Group Review stage.
 *
 * <p>This class deliberately contains no Android or store dependencies so the
 * Active-Group invariant and the queued-only {@code history_mapping} rewrite
 * plan can be covered by host JUnit tests.</p>
 */
public final class ContextReviewPlanner {

    private ContextReviewPlanner() {
        throw new AssertionError("No instances");
    }

    /** Immutable context membership snapshot used by the planner. */
    public static final class ContextSnapshot {
        public final String id;
        public final List<String> scenes;

        public ContextSnapshot(String id, List<String> scenes) {
            if (id == null || id.trim().isEmpty()) {
                throw new IllegalArgumentException("context id is required");
            }
            this.id = id;
            this.scenes = Collections.unmodifiableList(
                new ArrayList<>(scenes == null ? Collections.emptyList() : scenes)
            );
        }
    }

    /** Immutable group membership snapshot used by the planner. */
    public static final class GroupSnapshot {
        public final String id;
        public final List<String> contextIds;

        public GroupSnapshot(String id, List<String> contextIds) {
            if (id == null || id.trim().isEmpty()) {
                throw new IllegalArgumentException("group id is required");
            }
            this.id = id;
            this.contextIds = Collections.unmodifiableList(
                new ArrayList<>(contextIds == null ? Collections.emptyList() : contextIds)
            );
        }
    }

    /** A queued Translation Job's route references read from {@code state.json}. */
    public static final class QueuedJobState {
        public final String requestId;
        public final String scene;
        public final String contextId;
        public final String groupId;

        public QueuedJobState(
            String requestId,
            String scene,
            String contextId,
            String groupId
        ) {
            if (requestId == null || requestId.trim().isEmpty()) {
                throw new IllegalArgumentException("requestId is required");
            }
            this.requestId = requestId;
            this.scene = scene == null ? "" : scene;
            this.contextId = contextId;
            this.groupId = groupId;
        }
    }

    /** One queued Job whose {@code history_mapping} must be rewritten. */
    public static final class MappingRewrite {
        public final String requestId;
        public final Object historyMapping;

        public MappingRewrite(String requestId, Object historyMapping) {
            this.requestId = requestId;
            this.historyMapping = historyMapping;
        }
    }

    /**
     * Returns a user-facing error message when the Active Context/Active Group
     * combination is invalid, or {@code null} when it is valid. When only one
     * of the two pointers is set, the combination is always valid.
     */
    public static String validateActiveGroup(
        List<ContextSnapshot> contexts,
        List<GroupSnapshot> groups,
        String activeContextId,
        String activeGroupId
    ) {
        if (activeContextId == null && activeGroupId == null) {
            return null;
        }
        Map<String, ContextSnapshot> contextById = indexContexts(contexts);
        Map<String, GroupSnapshot> groupById = indexGroups(groups);

        if (activeContextId != null && !contextById.containsKey(activeContextId)) {
            return "Active context no longer exists";
        }
        if (activeGroupId != null && !groupById.containsKey(activeGroupId)) {
            return "Active group no longer exists";
        }
        if (activeContextId != null && activeGroupId != null) {
            GroupSnapshot group = groupById.get(activeGroupId);
            if (!group.contextIds.contains(activeContextId)) {
                return "Active group must contain the active context";
            }
        }
        return null;
    }

    /**
     * Computes the queued Job mapping rewrites required by a Review save.
     *
     * <p>A Job whose context was deleted or no longer contains the Job's scene
     * is changed to explicit no-history. A Job whose context still exists but
     * whose group was deleted or no longer contains that context keeps its
     * context and clears only {@code group_id}.</p>
     */
    public static List<MappingRewrite> planMappingRewrites(
        List<ContextSnapshot> contexts,
        List<GroupSnapshot> groups,
        List<QueuedJobState> queuedJobs
    ) {
        List<MappingRewrite> rewrites = new ArrayList<>();
        if (queuedJobs == null || queuedJobs.isEmpty()) {
            return rewrites;
        }
        Map<String, ContextSnapshot> contextById = indexContexts(contexts);
        Map<String, GroupSnapshot> groupById = indexGroups(groups);

        for (QueuedJobState job : queuedJobs) {
            if (job.contextId == null || job.contextId.trim().isEmpty()) {
                continue;
            }
            ContextSnapshot context = contextById.get(job.contextId);
            if (context == null || !context.scenes.contains(job.scene)) {
                rewrites.add(new MappingRewrite(job.requestId, JSONObject.NULL));
                continue;
            }
            if (job.groupId != null && !job.groupId.trim().isEmpty()) {
                GroupSnapshot group = groupById.get(job.groupId);
                if (group == null || !group.contextIds.contains(job.contextId)) {
                    rewrites.add(new MappingRewrite(
                        job.requestId,
                        HistoryMapping.fromActivePointers(job.contextId, null)
                    ));
                }
            }
        }
        return rewrites;
    }

    private static Map<String, ContextSnapshot> indexContexts(
        List<ContextSnapshot> contexts
    ) {
        Map<String, ContextSnapshot> result = new HashMap<>();
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
        Map<String, GroupSnapshot> result = new HashMap<>();
        if (groups != null) {
            for (GroupSnapshot group : groups) {
                result.put(group.id, group);
            }
        }
        return result;
    }
}
