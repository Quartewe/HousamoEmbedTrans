package com.quarty.housamoembedtrans.translation;

import com.quarty.housamoembedtrans.storage.ContextContentHash;
import com.quarty.housamoembedtrans.storage.ConfigStore;
import com.quarty.housamoembedtrans.storage.GroupContextEntry;
import com.quarty.housamoembedtrans.storage.ManualDescriptionResolver;
import com.quarty.housamoembedtrans.storage.SceneSummaryResolver;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.Iterator;
import java.util.Map;

/**
 * Pure Java coordinator that expands a Scene Context / Context Group into the
 * immutable {@code previous_context} History Payload.
 *
 * <p>It implements the two-stage language selection, coverage eligibility and
 * the three compression-first fallback chains defined in CONTEXT.md. It does
 * not read Translation Job files, Summary Jobs, or Scene files; callers pass
 * already-loaded Context/Group documents and scene summaries.</p>
 */
public final class HistoryResolver {

    public static final class Options {
        public boolean autoCompression;
        /** Keyed lookup for the specific missing Scene Summary. */
        public SceneSummaryProducer sceneSummaryProducer;
        public int defaultRecentPercent =
            ConfigStore.DEFAULT_CONTEXT_HISTORY_RECENT_PERCENT;
        public int defaultRecentLimit =
            ConfigStore.DEFAULT_CONTEXT_HISTORY_RECENT_SCENE_LIMIT;

        /**
         * When true, automatic Context Summary records are only used when their
         * persisted {@code source_hash} matches the deterministic Context
         * Content Hash for the record's coverage. Defaults to true.
         */
        public boolean validateSourceHash = true;
    }

    @FunctionalInterface
    public interface SceneSummaryProducer {
        boolean hasProducer(
            JSONObject context,
            String scene,
            String targetLang
        );
    }

    private static final class MissingSceneException extends Exception {
        private final JSONObject context;
        private final String scene;
        private final String targetLang;

        private MissingSceneException(
            String message,
            JSONObject context,
            String scene,
            String targetLang
        ) {
            super(message);
            this.context = context;
            this.scene = scene == null ? "" : scene;
            this.targetLang = targetLang == null ? "" : targetLang;
        }
    }

    private static final class InvalidHistoryException extends Exception {
        private InvalidHistoryException(String message) {
            super(message);
        }
    }

    private HistoryResolver() {
        throw new AssertionError("No instances");
    }

    /** Resolves history without a Context Group. */
    public static HistoryResolution resolve(
        JSONObject currentContext,
        JSONObject group,
        String targetLang,
        String currentScene,
        Options options
    ) {
        return resolve(
            currentContext,
            group,
            null,
            targetLang,
            currentScene,
            options
        );
    }

    /**
     * Resolves the full History Payload. {@code contextsById} is only needed
     * when {@code group} is non-null and contains previous contexts.
     */
    public static HistoryResolution resolve(
        JSONObject currentContext,
        JSONObject group,
        Map<String, JSONObject> contextsById,
        String targetLang,
        String currentScene,
        Options options
    ) {
        if (options == null) {
            options = new Options();
        }
        try {
            if (currentContext == null) {
                throw new InvalidHistoryException(
                    "current context is missing"
                );
            }
            if (targetLang == null || targetLang.trim().isEmpty()) {
                throw new InvalidHistoryException(
                    "target_lang must not be empty"
                );
            }
            JSONObject payload = new JSONObject();

            int currentIndex = findSceneIndex(currentContext, currentScene);
            if (currentIndex < 0) {
                throw new InvalidHistoryException(
                    "current scene is not in the selected context: "
                        + currentScene
                );
            }

            String currentLang = selectLanguage(
                currentContext.optJSONObject("summary"),
                targetLang
            );
            putManualDescription(
                payload,
                "current_manual_description",
                currentContext,
                currentLang
            );

            if (currentIndex > 0) {
                JSONObject currentEntry = buildCurrentContextSummary(
                    currentContext,
                    currentLang,
                    currentIndex,
                    options
                );
                if (currentEntry != null) {
                    payload.put("current_context_summary", currentEntry);
                }
            }

            if (group != null) {
                String currentContextId = currentContext.optString("id", "");
                JSONArray groupContexts = group.optJSONArray("contexts");
                int groupIndex = GroupContextEntry.indexOfContextId(
                    groupContexts,
                    currentContextId
                );
                if (groupIndex < 0) {
                    throw new InvalidHistoryException(
                        "selected group does not contain the current context: "
                            + currentContextId
                    );
                }
                if (groupIndex > 0) {
                    if (!options.autoCompression) {
                        putPriorContexts(
                            payload,
                            groupContexts,
                            0,
                            groupIndex,
                            contextsById,
                            targetLang,
                            options
                        );
                    } else {
                        resolveCompressedGroupLayer(
                            payload,
                            group,
                            groupContexts,
                            groupIndex,
                            contextsById,
                            targetLang,
                            options
                        );
                    }
                }
            }

            return HistoryResolution.ready(
                HistoryPayload.of(payload)
            );
        } catch (MissingSceneException e) {
            boolean hasProducer = options.sceneSummaryProducer != null
                && options.sceneSummaryProducer.hasProducer(
                    e.context,
                    e.scene,
                    e.targetLang
                );
            return hasProducer
                ? HistoryResolution.waiting(e.getMessage())
                : HistoryResolution.userActionRequired(e.getMessage());
        } catch (InvalidHistoryException e) {
            return HistoryResolution.userActionRequired(e.getMessage());
        } catch (JSONException e) {
            return HistoryResolution.userActionRequired(
                "invalid context/group JSON: " + e.getMessage()
            );
        }
    }

    /**
     * Applies the {@code context_length} preflight to a frozen provider
     * request. Over-limit is never truncated; it becomes
     * {@code USER_ACTION_REQUIRED} with actionable options.
     */
    public static HistoryResolution checkContextLength(
        PreparedApiRequest request
    ) {
        if (request == null) {
            return HistoryResolution.userActionRequired(
                "prepared provider request is missing"
            );
        }
        if (request.isWithinContextLength()) {
            return HistoryResolution.ready(request.getHistoryPayload());
        }
        return HistoryResolution.userActionRequired(
            "full provider input exceeds context_length="
                + request.getContextLength()
                + " estimated_tokens="
                + request.getEstimatedTokenCount()
                + "; enable global auto compression, shorten the context, "
                + "or remove scenes"
        );
    }

    // ── Current context summary ─────────────────────────────────────────

    private static JSONObject buildCurrentContextSummary(
        JSONObject context,
        String lang,
        int currentIndex,
        Options options
    ) throws MissingSceneException, JSONException {
        JSONObject langObject = languageObject(
            context.optJSONObject("summary"),
            lang
        );
        String manual = manualText(langObject);
        if (manual != null) {
            int k = retentionK(context, options);
            // Manual text replaces only the compressed prefix.  The
            // independent recent-window contract still carries the last K
            // complete Scene summaries before the current Scene.
            JSONArray scenes = sceneSummariesAfterCutoffWithRecentWindow(
                context,
                lang,
                currentIndex,
                currentIndex,
                k
            );
            return new JSONObject()
                .put("source", "manual")
                .put("summary", manual)
                .put("scenes", scenes);
        }

        JSONObject current = langObject == null
            ? null
            : langObject.optJSONObject("current");
        String cutoff = current == null ? "" : current.optString("cutoff", "");
        int cutoffIndex = findEntryIndex(context, cutoff);
        int boundaryIndex = currentIndex - 1;
        if (options.autoCompression
            && current != null
            && cutoffIndex >= 0
            && cutoffIndex <= boundaryIndex
            && isCurrentRecordApplicable(
                context, lang, current, cutoff, options)) {
            int k = retentionK(context, options);
            // A current snapshot may lag behind the request boundary.  Keep
            // the normal recent-K window, but also include every Scene
            // Summary after the snapshot cutoff.  The two ranges can overlap
            // (and the retention window may begin before the cutoff), so
            // assemble them by context position and emit each entry once.
            JSONArray scenes = sceneSummariesAfterCutoffWithRecentWindow(
                context,
                lang,
                cutoffIndex + 1,
                currentIndex,
                k
            );
            return new JSONObject()
                .put("source", "current")
                .put("summary", current.optString("text", ""))
                .put("scenes", scenes);
        }

        JSONArray scenes = sceneSummaries(
            context,
            lang,
            0,
            currentIndex,
            options
        );
        return new JSONObject()
            .put("source", "lossless")
            .put("scenes", scenes);
    }

    // ── Group compressed layer ──────────────────────────────────────────

    private static void resolveCompressedGroupLayer(
        JSONObject payload,
        JSONObject group,
        JSONArray groupContexts,
        int groupIndex,
        Map<String, JSONObject> contextsById,
        String targetLang,
        Options options
    ) throws MissingSceneException, InvalidHistoryException, JSONException {
        int boundaryIndex = groupIndex - 2;

        String groupLang = selectLanguage(
            group.optJSONObject("summary"),
            targetLang
        );
        JSONObject groupLangObject = languageObject(
            group.optJSONObject("summary"),
            groupLang
        );

        String groupManual = manualText(groupLangObject);
        if (groupManual != null) {
            // Group manual covers the earlier prefix; no prior_contexts. The
            // immediately preceding context is still expressed separately as
            // last_context_summary whenever it exists.
            if (boundaryIndex >= 0) {
                payload.put("group_summary", groupManual);
            }
            if (groupIndex >= 1) {
                JSONObject previousContext = requireContext(
                    contextsById,
                    GroupContextEntry.contextIdAt(groupContexts, groupIndex - 1)
                );
                payload.put(
                    "last_context_summary",
                    buildLastContextSummary(previousContext, targetLang, options)
                );
            }
            return;
        }

        String groupSummary = null;
        String groupCutoff = null;
        JSONObject current = groupLangObject == null
            ? null
            : groupLangObject.optJSONObject("current");
        if (current != null && boundaryIndex >= 0) {
            String cutoff = current.optString("cutoff", "");
            int cutoffIndex = GroupContextEntry.indexOfEntryId(
                groupContexts,
                cutoff
            );
            boolean applicable = false;
            if (cutoffIndex >= 0 && cutoffIndex <= boundaryIndex) {
                try {
                    applicable = SummaryRequestAssembler.isGroupCurrentApplicable(
                        group,
                        contextsById,
                        groupLang,
                        current,
                        cutoff
                    );
                } catch (Exception ignored) {
                    applicable = false;
                }
            }
            if (applicable) {
                groupSummary = current.optString("text", "");
                groupCutoff = cutoff;
            }
        }

        if (groupSummary != null) {
            payload.put("group_summary", groupSummary);
            if (groupCutoff != null) {
            int cutoffIndex = GroupContextEntry.indexOfEntryId(
                groupContexts,
                groupCutoff
            );
                if (cutoffIndex >= 0 && cutoffIndex < boundaryIndex) {
                    putPriorContexts(
                        payload,
                        groupContexts,
                        cutoffIndex + 1,
                        boundaryIndex + 1,
                        contextsById,
                        targetLang,
                        options
                    );
                }
            }
            if (groupIndex >= 2) {
                JSONObject previousContext = requireContext(
                    contextsById,
                    GroupContextEntry.contextIdAt(groupContexts, groupIndex - 1)
                );
                payload.put(
                    "last_context_summary",
                    buildLastContextSummary(previousContext, targetLang, options)
                );
            }
            return;
        }

        // No applicable compressed group summary: lossless group layer.
        putPriorContexts(
            payload,
            groupContexts,
            0,
            groupIndex,
            contextsById,
            targetLang,
            options
        );
    }

    private static void putPriorContexts(
        JSONObject payload,
        JSONArray groupContexts,
        int start,
        int end,
        Map<String, JSONObject> contextsById,
        String targetLang,
        Options options
    ) throws MissingSceneException, InvalidHistoryException, JSONException {
        JSONArray prior = new JSONArray();
        for (int index = start; index < end; index++) {
            JSONObject context = requireContext(
                contextsById,
                GroupContextEntry.contextIdAt(groupContexts, index)
            );
            prior.put(buildPriorContextEntry(context, targetLang, options));
        }
        if (prior.length() > 0) {
            payload.put("prior_contexts", prior);
        }
    }

    // ── Context-level entry builders ─────────────────────────────────────

    private static JSONObject buildPriorContextEntry(
        JSONObject context,
        String targetLang,
        Options options
    ) throws MissingSceneException, JSONException {
        JSONObject entry = buildContextEntryBody(context, targetLang, options);
        entry.put("context_id", context.optString("id", ""));
        return entry;
    }

    private static JSONObject buildLastContextSummary(
        JSONObject context,
        String targetLang,
        Options options
    ) throws MissingSceneException, JSONException {
        return buildContextEntryBody(context, targetLang, options);
    }

    private static JSONObject buildContextEntryBody(
        JSONObject context,
        String targetLang,
        Options options
    ) throws MissingSceneException, JSONException {
        String lang = selectLanguage(
            context.optJSONObject("summary"),
            targetLang
        );
        JSONObject langObject = languageObject(
            context.optJSONObject("summary"),
            lang
        );
        JSONObject entry = new JSONObject();

        String manual = manualText(langObject);
        if (manual != null) {
            entry.put("source", "manual");
            entry.put("summary", manual);
            putManualDescription(entry, "manual_description", context, lang);
            return entry;
        }

        JSONObject finalRecord = langObject == null
            ? null
            : langObject.optJSONObject("final");
        if (options.autoCompression
            && finalRecord != null
            && isFinalRecordApplicable(
                context, lang, finalRecord, options)) {
            entry.put("source", "final");
            entry.put("summary", finalRecord.optString("text", ""));
            putManualDescription(entry, "manual_description", context, lang);
            return entry;
        }

        JSONObject current = langObject == null
            ? null
            : langObject.optJSONObject("current");
        String cutoff = current == null ? "" : current.optString("cutoff", "");
        int cutoffIndex = findEntryIndex(context, cutoff);
        JSONArray contextScenes = context.optJSONArray("scenes");
        int endIndex = contextScenes == null ? 0 : contextScenes.length();
        if (options.autoCompression
            && current != null
            && cutoffIndex >= 0
            && cutoffIndex < endIndex
            && isCurrentRecordApplicable(
                context, lang, current, cutoff, options)) {
            entry.put("source", "current");
            entry.put("summary", current.optString("text", ""));
            JSONArray scenes = sceneSummaries(
                context,
                lang,
                cutoffIndex + 1,
                endIndex,
                options
            );
            entry.put("scenes", scenes);
            putManualDescription(entry, "manual_description", context, lang);
            return entry;
        }

        JSONArray scenes = sceneSummaries(
            context,
            lang,
            0,
            endIndex,
            options
        );
        entry.put("source", "lossless");
        entry.put("scenes", scenes);
        putManualDescription(entry, "manual_description", context, lang);
        return entry;
    }

    // ── Scene summary helpers ───────────────────────────────────────────

    private static JSONArray sceneSummariesAfterCutoffWithRecentWindow(
        JSONObject context,
        String lang,
        int cutoffStart,
        int currentIndex,
        int k
    ) throws MissingSceneException, JSONException {
        JSONArray scenes = context.optJSONArray("scenes");
        int endExclusive = Math.min(
            currentIndex,
            scenes == null ? 0 : scenes.length()
        );
        if (endExclusive <= 0) {
            return new JSONArray();
        }

        boolean[] included = new boolean[endExclusive];
        int gapStart = Math.max(0, Math.min(cutoffStart, endExclusive));
        for (int index = gapStart; index < endExclusive; index++) {
            included[index] = true;
        }

        int recentStart = Math.max(0, endExclusive - Math.max(0, k));
        for (int index = recentStart; index < endExclusive; index++) {
            included[index] = true;
        }

        JSONArray result = new JSONArray();
        for (int index = 0; index < endExclusive; index++) {
            if (!included[index]) {
                continue;
            }
            appendSceneSummary(
                result,
                scenes.optJSONObject(index),
                context,
                lang,
                index
            );
        }
        return result;
    }

    private static JSONArray sceneSummaries(
        JSONObject context,
        String lang,
        int start,
        int endExclusive,
        Options options
    ) throws MissingSceneException, JSONException {
        JSONArray result = new JSONArray();
        JSONArray scenes = context.optJSONArray("scenes");
        if (scenes == null) {
            return result;
        }
        for (int index = start; index < endExclusive; index++) {
            JSONObject entry = scenes.optJSONObject(index);
            if (entry == null) {
                throw new MissingSceneException(
                    "scene entry is missing at index " + index,
                    context,
                    "",
                    lang
                );
            }
            appendSceneSummary(result, entry, context, lang, index);
        }
        return result;
    }

    private static void appendSceneSummary(
        JSONArray result,
        JSONObject entry,
        JSONObject context,
        String lang,
        int index
    ) throws MissingSceneException, JSONException {
        if (entry == null) {
            throw new MissingSceneException(
                "scene entry is missing at index " + index,
                context,
                "",
                lang
            );
        }
        String scene = entry.optString("scene", "");
        SceneSummaryResolver.Resolved summary = SceneSummaryResolver.resolve(
            entry.optJSONObject("summaries"),
            lang
        );
        if (summary == null) {
            throw new MissingSceneException(
                "scene summary is missing for scene=" + scene,
                context,
                scene,
                lang
            );
        }
        result.put(new JSONObject()
            .put("scene", scene)
            .put("summary", summary.text));
    }

    // ── Language selection ──────────────────────────────────────────────

    private static String selectLanguage(
        JSONObject summaryContainer,
        String targetLang
    ) {
        if (summaryContainer != null && summaryContainer.has(targetLang)) {
            return targetLang;
        }
        String bestLang = null;
        long bestUpdatedAt = -1L;
        if (summaryContainer != null) {
            Iterator<String> keys = summaryContainer.keys();
            while (keys.hasNext()) {
                String lang = keys.next();
                JSONObject langObject = summaryContainer.optJSONObject(lang);
                long updatedAt = maxUpdatedAt(langObject);
                // A missing/invalid timestamp is not a usable "recent"
                // candidate. If every fallback language is unusable, the
                // caller keeps the requested target language instead.
                if (updatedAt < 0L) {
                    continue;
                }
                // JSONObject.keys() does not promise a stable iteration
                // order. Keep the "most recently updated" rule, but make
                // equal timestamps deterministic so the selected language
                // cannot change when the same document is parsed elsewhere.
                if (bestLang == null
                    || updatedAt > bestUpdatedAt
                    || (updatedAt == bestUpdatedAt
                        && lang.compareTo(bestLang) < 0)) {
                    bestUpdatedAt = updatedAt;
                    bestLang = lang;
                }
            }
        }
        return bestLang != null ? bestLang : targetLang;
    }

    private static long maxUpdatedAt(JSONObject langObject) {
        if (langObject == null) {
            return -1L;
        }
        long updatedAt = -1L;
        String[] keys = {"manual", "current", "final"};
        for (String key : keys) {
            JSONObject record = langObject.optJSONObject(key);
            if (record != null) {
                updatedAt = Math.max(
                    updatedAt,
                    record.optLong("updated_at", -1L)
                );
            }
        }
        return updatedAt;
    }

    private static JSONObject languageObject(
        JSONObject summaryContainer,
        String lang
    ) {
        if (summaryContainer == null || lang == null) {
            return null;
        }
        return summaryContainer.optJSONObject(lang);
    }

    private static String manualText(JSONObject langObject) {
        if (langObject == null) {
            return null;
        }
        JSONObject manual = langObject.optJSONObject("manual");
        if (manual == null) {
            return null;
        }
        String text = manual.optString("text", "").trim();
        return text.isEmpty() ? null : text;
    }

    private static void putManualDescription(
        JSONObject target,
        String field,
        JSONObject context,
        String preferredLang
    ) throws JSONException {
        ManualDescriptionResolver.Resolved resolved =
            ManualDescriptionResolver.resolve(context, preferredLang);
        if (resolved != null) {
            target.put(field, resolved.text);
        }
    }

    // ── Source-hash applicability ───────────────────────────────────────

    private static boolean isFinalRecordApplicable(
        JSONObject context,
        String lang,
        JSONObject finalRecord,
        Options options
    ) {
        if (options != null && !options.validateSourceHash) {
            return true;
        }
        String sourceHash = finalRecord.optString("source_hash", "");
        return !sourceHash.isEmpty()
            && sourceHash.equals(ContextContentHash.compute(context, lang));
    }

    private static boolean isCurrentRecordApplicable(
        JSONObject context,
        String lang,
        JSONObject currentRecord,
        String cutoff,
        Options options
    ) {
        if (options != null && !options.validateSourceHash) {
            return true;
        }
        String sourceHash = currentRecord.optString("source_hash", "");
        return !sourceHash.isEmpty()
            && sourceHash.equals(
                ContextContentHash.computeToCutoff(context, lang, cutoff)
            );
    }

    // ── Index helpers ───────────────────────────────────────────────────

    private static int findSceneIndex(JSONObject context, String scene) {
        JSONArray scenes = context.optJSONArray("scenes");
        if (scenes == null) {
            return -1;
        }
        for (int index = 0; index < scenes.length(); index++) {
            JSONObject entry = scenes.optJSONObject(index);
            if (entry != null && scene.equals(entry.optString("scene", ""))) {
                return index;
            }
        }
        return -1;
    }

    private static int findEntryIndex(JSONObject context, String entryId) {
        if (entryId == null || entryId.isEmpty()) {
            return -1;
        }
        JSONArray scenes = context.optJSONArray("scenes");
        if (scenes == null) {
            return -1;
        }
        for (int index = 0; index < scenes.length(); index++) {
            JSONObject entry = scenes.optJSONObject(index);
            if (entry != null
                && entryId.equals(entry.optString("entry_id", ""))) {
                return index;
            }
        }
        return -1;
    }

    private static JSONObject requireContext(
        Map<String, JSONObject> contextsById,
        String contextId
    ) throws InvalidHistoryException {
        if (contextsById == null || !contextsById.containsKey(contextId)) {
            throw new InvalidHistoryException(
                "context referenced by group is missing: " + contextId
            );
        }
        return contextsById.get(contextId);
    }

    private static int retentionK(JSONObject context, Options options) {
        int percent = options.defaultRecentPercent;
        int limit = options.defaultRecentLimit;
        JSONObject retention = context.optJSONObject("retention");
        if (retention != null
            && !retention.optBoolean("inherit_defaults", true)) {
            percent = retention.optInt(
                "recent_percent",
                options.defaultRecentPercent
            );
            limit = retention.optInt(
                "recent_limit",
                options.defaultRecentLimit
            );
        }
        JSONArray scenes = context.optJSONArray("scenes");
        int sceneCount = scenes == null ? 0 : scenes.length();
        if (sceneCount <= 0) {
            return 0;
        }
        int k = (int) Math.ceil(sceneCount * percent / 100.0);
        k = Math.min(k, limit);
        return Math.max(1, k);
    }
}
