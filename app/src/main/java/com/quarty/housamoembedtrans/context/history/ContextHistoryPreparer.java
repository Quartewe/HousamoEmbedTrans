package com.quarty.housamoembedtrans.context.history;
import com.quarty.housamoembedtrans.provider.TranslationConfig;

import com.quarty.housamoembedtrans.context.model.GroupContextEntry;
import com.quarty.housamoembedtrans.context.model.HistoryMapping;
import com.quarty.housamoembedtrans.context.store.SceneContextStore;
import com.quarty.housamoembedtrans.scene.store.SceneStore;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.LinkedHashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Deep Context/Group module for preparing immutable History input before a
 * Translation Request.  It owns route loading, expansion inputs, manual
 * suppression and the source-hash observation; callers only consume the
 * returned preparation.
 */
public final class ContextHistoryPreparer {

    /** Immutable result consumed by the Translation worker. */
    public static final class HistoryPreparation {
        private final String contextId;
        private final String storageName;
        private final String capturedSourceHashExcludingScene;
        private final boolean requestContextSummary;
        private final HistoryResolution resolution;
        private final HistoryPayload payload;

        private HistoryPreparation(
            String contextId,
            String storageName,
            String capturedSourceHashExcludingScene,
            boolean requestContextSummary,
            HistoryResolution resolution
        ) {
            this.contextId = contextId;
            this.storageName = storageName;
            this.capturedSourceHashExcludingScene =
                capturedSourceHashExcludingScene;
            this.requestContextSummary = requestContextSummary;
            this.resolution = resolution;
            this.payload = resolution != null && resolution.isReady()
                ? resolution.getPayload()
                : HistoryPayload.empty();
        }

        public static HistoryPreparation blocked(HistoryResolution resolution) {
            return new HistoryPreparation(
                null,
                null,
                null,
                false,
                resolution
            );
        }

        public static HistoryPreparation noHistory() {
            return ready(
                null,
                null,
                null,
                false,
                HistoryResolution.ready(HistoryPayload.empty())
            );
        }

        private static HistoryPreparation ready(
            String contextId,
            String storageName,
            String capturedSourceHashExcludingScene,
            boolean requestContextSummary,
            HistoryResolution resolution
        ) {
            return new HistoryPreparation(
                contextId,
                storageName,
                capturedSourceHashExcludingScene,
                requestContextSummary,
                resolution
            );
        }

        public String getContextId() {
            return contextId;
        }

        public String getStorageName() {
            return storageName;
        }

        public String getCapturedSourceHashExcludingScene() {
            return capturedSourceHashExcludingScene;
        }

        public boolean isRequestContextSummary() {
            return requestContextSummary;
        }

        public HistoryResolution getResolution() {
            return resolution;
        }

        public HistoryPayload getPayload() {
            return payload;
        }
    }

    private final SceneContextStore store;
    private final SceneStore sceneStore;
    private final HistoryResolver.SceneOriginalLoader sceneOriginalLoader;


    public ContextHistoryPreparer(SceneContextStore store) {
        this(store, null, null);
    }

    public ContextHistoryPreparer(SceneContextStore store,
        SceneStore sceneStore,
        HistoryResolver.SceneOriginalLoader sceneOriginalLoader) {
        if (store == null) {
            throw new IllegalArgumentException(
                "store is required"
            );
        }
        this.store = store;
        this.sceneStore = sceneStore;
        this.sceneOriginalLoader = sceneOriginalLoader;
    }

    /**
     * Prepares one valid History Mapping.  The mapping is already structurally
     * validated by the Translation Job store; malformed values are still
     * converted into the established user-action resolution for safety.
     */
    public HistoryPreparation prepare(
        JSONObject mapping,
        String requestId,
        String scene,
        String targetLang,
        TranslationConfig config,
        HistoryResolver.SceneSummaryProducer sceneSummaryProducer
    ) {
        if (mapping == null
            || HistoryMapping.resolutionOfValue(mapping)
                != HistoryMapping.Resolution.VALID) {
            return HistoryPreparation.blocked(
                HistoryResolution.userActionRequired(
                    "history_mapping is missing, malformed, or uses an invalid id; "
                        + "fix the mapping before sending this job"
                )
            );
        }
        if (config == null) {
            return HistoryPreparation.blocked(
                HistoryResolution.userActionRequired(
                    "translation configuration is missing for history preparation"
                )
            );
        }

        String contextId = mapping.optString(HistoryMapping.CONTEXT_ID, "");
        String groupId = mapping.isNull(HistoryMapping.GROUP_ID)
            ? null
            : mapping.optString(HistoryMapping.GROUP_ID, null);
        try {
            JSONObject context = loadHistoryContext(contextId, scene);
            if (context == null) {
                return HistoryPreparation.blocked(
                    HistoryResolution.userActionRequired(
                        "selected context is missing: " + contextId
                    )
                );
            }
            String storageName = context.optString("storage_name", "");
            boolean autoCompression = config.isContextAutoCompressionEnabled();
            boolean manualSuppressed =
                !config.isContinueAutoSummaryAfterManual()
                    && store.getContextStore().hasManualSummary(
                        storageName,
                        targetLang
                    );
            boolean requestContextSummary = autoCompression
                && !manualSuppressed
                && hasSameInlineSummaryCoverage(context.getJSONArray("scenes"), scene);
            String capturedHash = autoCompression
                ? store.getContextStore().computeContextSourceHashExcludingScene(
                    storageName,
                    scene,
                    targetLang
                )
                : null;

            JSONObject group = groupId == null
                ? null
                : store.getGroup(groupId);
            Map<String, JSONObject> predecessorContexts = group == null
                ? null
                : loadPredecessorContexts(group, contextId);

            HistoryResolver.Options options = new HistoryResolver.Options();
            options.autoCompression = autoCompression;
            options.pendingSummaryMode = config.getPendingSummaryMode();
            options.sceneOriginalLoader = sceneOriginalLoader;
            options.defaultRecentPercent = config.getDefaultRecentPercent();
            options.defaultRecentLimit = config.getDefaultRecentSceneLimit();
            options.sceneSummaryProducer = sceneSummaryProducer;
            HistoryResolution resolution = HistoryResolver.resolve(
                context,
                group,
                predecessorContexts,
                targetLang,
                scene,
                options
            );
            if (!resolution.isReady()) {
                return HistoryPreparation.blocked(resolution);
            }
            JSONObject currentHistory = resolution.getPayload().toJson()
                .optJSONObject("current_context_summary");
            if (currentHistory != null) {
                String source = currentHistory.optString("source", "");
                JSONArray historyScenes = currentHistory.optJSONArray("scenes");
                boolean manualOnlyFallback = "manual".equals(source)
                    && historyScenes != null && historyScenes.length() == 0;
                // Missing Scene summaries must not become a complete automatic checkpoint,
                // even when ContinueAutoSummaryAfterManual is enabled.
                if ("pending_originals".equals(source) || manualOnlyFallback) {
                    requestContextSummary = false;
                }
            }
            return HistoryPreparation.ready(
                contextId,
                storageName,
                capturedHash,
                requestContextSummary,
                resolution
            );
        } catch (Exception e) {
            return HistoryPreparation.blocked(
                HistoryResolution.userActionRequired(
                    "could not resolve context history requestId="
                        + requestId
                        + ": "
                        + safeMessage(e)
                )
            );
        }
    }

    /**
     * Inline compression sees natural predecessors, but its persisted cutoff
     * still describes a physical prefix. If those member sets differ, let the
     * existing snapshot job assemble that physical range instead.
     */
    private static boolean hasSameInlineSummaryCoverage(JSONArray entries, String scene) {
        List<JSONObject> ordered = SceneEntryOrdering.sortedEntries(entries);
        int naturalIndex = SceneEntryOrdering.indexOfScene(ordered, scene);
        if (naturalIndex < 0) {
            return false;
        }
        Set<String> expected = new HashSet<>();
        for (int index = 0; index <= naturalIndex; index++) {
            expected.add(ordered.get(index).optString("entry_id", ""));
        }
        for (int index = 0; index < entries.length(); index++) {
            JSONObject entry = entries.optJSONObject(index);
            if (entry == null || !expected.remove(entry.optString("entry_id", ""))) {
                return false;
            }
            if (scene.equals(entry.optString("scene", ""))) {
                return expected.isEmpty();
            }
        }
        return false;
    }

    /** Loads only group entries before the selected current Context. */
    private Map<String, JSONObject> loadPredecessorContexts(
        JSONObject group,
        String currentContextId
    ) throws Exception {
        JSONArray entries = group.optJSONArray("contexts");
        int currentIndex = GroupContextEntry.indexOfContextId(
            entries,
            currentContextId
        );
        if (currentIndex < 0) {
            throw new IllegalArgumentException(
                "selected group does not contain the current context: "
                    + currentContextId
            );
        }
        Map<String, JSONObject> result = new LinkedHashMap<>();
        for (int index = 0; index < currentIndex; index++) {
            String contextId = GroupContextEntry.contextIdAt(entries, index);
            if (contextId.isEmpty() || result.containsKey(contextId)) {
                continue;
            }
            result.put(contextId, loadHistoryContext(contextId, null));
        }
        return result;
    }

    private JSONObject loadHistoryContext(
        String contextId,
        String beforeScene
    ) throws Exception {
        return SceneContextStore.withRootAccess(() -> {
            JSONObject context = store.getContext(contextId);
            JSONArray scenes = context.getJSONArray("scenes");
            JSONArray historyScenes = new JSONArray();
            for (JSONObject entry : SceneEntryOrdering.sortedEntries(scenes)) {
                if (entry == null) {
                    throw new IllegalStateException(
                        "context contains a null scene entry"
                    );
                }
                if (entry.getString("scene").equals(beforeScene)) {
                    break;
                }
                historyScenes.put(entry);
            }
            if (beforeScene != null && historyScenes.length() == scenes.length()) {
                // Leave a stale mapping to the existing resolver without writes.
                return context;
            }
            if (sceneStore != null
                && SceneContextStore.fillMissingSceneSummaries(historyScenes, sceneStore)) {
                // Commit before capturing the source hash or resolving history.
                // The root gate serializes this with edits and live observations;
                // updateContext preserves the existing revision/atomic-write path.
                context = store.updateContext(
                    contextId,
                    context,
                    context.getLong("revision")
                );
            }
            return context;
        });
    }

    private static String safeMessage(Exception error) {
        String message = error.getMessage();
        return message == null || message.trim().isEmpty()
            ? error.getClass().getSimpleName()
            : message;
    }
}
