package com.quarty.housamoembedtrans.summary.request;
import com.quarty.housamoembedtrans.provider.ProviderTokenEstimator;
import com.quarty.housamoembedtrans.provider.TranslationConfig;
import com.quarty.housamoembedtrans.summary.policy.GroupCompressionCoordinator;

import com.quarty.housamoembedtrans.context.model.GroupContextEntry;
import com.quarty.housamoembedtrans.context.history.ManualDescriptionResolver;
import com.quarty.housamoembedtrans.context.history.SceneSummaryResolver;

import org.json.JSONArray;
import org.json.JSONObject;

import java.nio.charset.StandardCharsets;
import java.util.Map;

/**
 * Assembles the three Summary Request kinds into one non-streaming provider
 * request using the shared {@code summary_prompt.txt} and
 * {@code summary_result_schema.json} contract.
 *
 * <p>The user content is intentionally minimal:
 * {@code {request_kind, target_lang, summary_input}}. Internal routing fields
 * such as owner ids, cutoffs and source hashes never enter the provider.</p>
 */
public final class SummaryRequestAssembler {

    public static final String SUMMARY_PROMPT_ASSET = "summary_prompt.txt";
    public static final String SUMMARY_RESULT_SCHEMA_ASSET =
        "summary_result_schema.json";

    private static final int ANTHROPIC_MAX_TOKENS = 38_400;

    private SummaryRequestAssembler() {
        throw new AssertionError("No instances");
    }

    /** Immutable provider request formed after the Summary pre-send gates. */
    public static final class SummaryPreparedRequest {
        private final JSONObject providerRequest;
        private final String userContent;
        private final int estimatedTokenCount;
        private final int contextLength;

        private SummaryPreparedRequest(
            JSONObject providerRequest,
            String userContent,
            int estimatedTokenCount,
            int contextLength
        ) {
            this.providerRequest = providerRequest;
            this.userContent = userContent;
            this.estimatedTokenCount = estimatedTokenCount;
            this.contextLength = contextLength;
        }

        public JSONObject getProviderRequest() {
            try {
                return new JSONObject(providerRequest.toString());
            } catch (org.json.JSONException e) {
                throw new IllegalStateException(
                    "frozen summary provider request became invalid",
                    e
                );
            }
        }

        public String getUserContent() {
            return userContent;
        }

        public int getEstimatedTokenCount() {
            return estimatedTokenCount;
        }

        public int getContextLength() {
            return contextLength;
        }

        public boolean isWithinContextLength() {
            return estimatedTokenCount <= contextLength;
        }
    }

    public static SummaryPreparedRequest assemble(
        TranslationConfig config,
        String summaryPrompt,
        String requestKind,
        String targetLang,
        JSONObject summaryInput
    ) throws Exception {
        if (config == null) {
            throw new IllegalArgumentException("config is required");
        }
        if (summaryPrompt == null || summaryPrompt.trim().isEmpty()) {
            throw new IllegalArgumentException("summary prompt is empty");
        }
        if (!isSupportedRequestKind(requestKind)) {
            throw new IllegalArgumentException(
                "unsupported summary request_kind: " + requestKind
            );
        }
        if (targetLang == null || targetLang.trim().isEmpty()) {
            throw new IllegalArgumentException("target_lang is empty");
        }
        if (summaryInput == null) {
            throw new IllegalArgumentException("summary_input is required");
        }

        JSONObject userPayload = new JSONObject()
            .put("request_kind", requestKind)
            .put("target_lang", targetLang)
            .put("summary_input", summaryInput);
        String userContent = userPayload.toString();
        JSONObject providerRequest = buildProviderRequest(
            config,
            summaryPrompt,
            userContent
        );
        int estimatedTokenCount =
            ProviderTokenEstimator.estimate(summaryPrompt)
                + ProviderTokenEstimator.estimate(userContent);
        return new SummaryPreparedRequest(
            providerRequest,
            userContent,
            estimatedTokenCount,
            config.getContextLength()
        );
    }

    public static JSONObject buildSummaryInput(
        JSONObject request,
        JSONObject context,
        JSONObject group,
        Map<String, JSONObject> contextsById
    ) throws Exception {
        if (request == null) {
            throw new IllegalArgumentException("summary request is null");
        }
        String requestKind = request.optString("request_kind", "");
        String targetLang = request.optString("target_lang", "");
        if (requestKind.isEmpty() || targetLang.isEmpty()) {
            throw new IllegalArgumentException(
                "summary request_kind and target_lang are required"
            );
        }
        switch (requestKind) {
            case "context_snapshot":
                requireContext(context);
                return buildContextSnapshotInput(
                    context,
                    request.optString("cutoff", ""),
                    targetLang
                );
            case "context_final":
                requireContext(context);
                return buildContextFinalInput(context, targetLang);
            case "group_snapshot":
                if (group == null) {
                    throw new IllegalArgumentException(
                        "group_snapshot requires a group document"
                    );
                }
                return buildGroupSnapshotInput(
                    group,
                    contextsById,
                    request.optString("cutoff", ""),
                    targetLang,
                    earlierGroupPrefixSummary(
                        group,
                        contextsById,
                        targetLang,
                        request.optString("cutoff", "")
                    )
                );
            default:
                throw new IllegalArgumentException(
                    "unsupported summary request_kind: " + requestKind
                );
        }
    }

    public static JSONObject buildContextSnapshotInput(
        JSONObject context,
        String cutoffEntryId,
        String targetLang
    ) throws Exception {
        requireContext(context);
        JSONArray scenes = context.optJSONArray("scenes");
        if (scenes == null) {
            throw new IllegalArgumentException(
                "context has no scenes array"
            );
        }
        int cutoffIndex = findEntryIndex(scenes, cutoffEntryId);
        if (cutoffIndex < 0) {
            throw new IllegalArgumentException(
                "context_snapshot cutoff is not in context scenes: "
                    + cutoffEntryId
            );
        }

        JSONArray entries = new JSONArray();
        for (int index = 0; index <= cutoffIndex; index++) {
            JSONObject entry = scenes.optJSONObject(index);
            String scene = entry.optString("scene", "");
            String text = sceneSummaryText(entry, targetLang, scene);
            entries.put(new JSONObject()
                .put("kind", "scene")
                .put("scene", scene)
                .put("text", text));
        }
        return new JSONObject().put("entries", entries);
    }

    public static JSONObject buildContextFinalInput(
        JSONObject context,
        String targetLang
    ) throws Exception {
        requireContext(context);
        JSONArray scenes = context.optJSONArray("scenes");
        if (scenes == null) {
            throw new IllegalArgumentException(
                "context has no scenes array"
            );
        }

        JSONArray entries = new JSONArray();
        for (int index = 0; index < scenes.length(); index++) {
            JSONObject entry = scenes.optJSONObject(index);
            String scene = entry.optString("scene", "");
            String text = sceneSummaryText(entry, targetLang, scene);
            entries.put(new JSONObject()
                .put("kind", "scene")
                .put("scene", scene)
                .put("text", text));
        }

        JSONObject input = new JSONObject().put("entries", entries);
        String manualDescription = manualDescription(context, targetLang);
        if (manualDescription != null) {
            input.put("manual_description", manualDescription);
        }
        return input;
    }

    /**
     * A legal earlier Group current summary already covers entries through its
     * cutoff.  Send only the suffix after that cutoff together with
     * {@code prefix_summary}; sending the full prefix again duplicates the
     * same context material in the provider request and can bias the result.
     */
    private static int prefixStartIndex(
        JSONObject group,
        Map<String, JSONObject> contextsById,
        String targetLang,
        String cutoffContextId,
        String prefixSummary,
        int cutoffIndex
    ) {
        if (prefixSummary == null || prefixSummary.trim().isEmpty()) {
            return 0;
        }
        JSONObject summary = group.optJSONObject("summary");
        JSONObject language = summary == null
            ? null
            : summary.optJSONObject(targetLang);
        JSONObject current = language == null
            ? null
            : language.optJSONObject("current");
        String previousCutoff = current == null
            ? ""
            : current.optString("cutoff", "").trim();
        if (previousCutoff.isEmpty()) {
            return 0;
        }
        JSONArray contexts = group.optJSONArray("contexts");
        int previousIndex = GroupContextEntry.indexOfEntryId(
            contexts,
            previousCutoff
        );
        if (previousIndex < 0 || previousIndex >= cutoffIndex) {
            return 0;
        }
        try {
            if (!isGroupCurrentApplicable(
                group,
                contextsById,
                targetLang,
                current,
                previousCutoff
            )) {
                return 0;
            }
        } catch (Exception ignored) {
            return 0;
        }
        return previousIndex + 1;
    }

    public static JSONObject buildGroupSnapshotInput(
        JSONObject group,
        Map<String, JSONObject> contextsById,
        String cutoffContextId,
        String targetLang
    ) throws Exception {
        return buildGroupSnapshotInput(
            group,
            contextsById,
            cutoffContextId,
            targetLang,
            null
        );
    }

    /**
     * Builds the {@code group_snapshot} summary input.
     *
     * @param prefixSummary optional earlier legal Group summary text. When
     *                      present it is included as {@code prefix_summary} so
     *                      the provider can continue from an already summarized
     *                      earlier prefix instead of treating the new entries as
     *                      a from-scratch summary.
     */
    public static JSONObject buildGroupSnapshotInput(
        JSONObject group,
        Map<String, JSONObject> contextsById,
        String cutoffContextId,
        String targetLang,
        String prefixSummary
    ) throws Exception {
        if (group == null || contextsById == null) {
            throw new IllegalArgumentException(
                "group and contextsById are required"
            );
        }
        JSONArray groupContexts = group.optJSONArray("contexts");
        if (groupContexts == null) {
            throw new IllegalArgumentException("group has no contexts array");
        }
        int cutoffIndex = GroupContextEntry.indexOfEntryId(
            groupContexts,
            cutoffContextId
        );
        if (cutoffIndex < 0) {
            throw new IllegalArgumentException(
                "group_snapshot cutoff is not in group contexts: "
                    + cutoffContextId
            );
        }

        JSONObject input = new JSONObject();
        JSONArray entries = new JSONArray();
        int firstEntryIndex = prefixStartIndex(
            group,
            contextsById,
            targetLang,
            cutoffContextId,
            prefixSummary,
            cutoffIndex
        );
        for (int index = firstEntryIndex; index <= cutoffIndex; index++) {
            String contextId = GroupContextEntry.contextIdAt(groupContexts, index);
            JSONObject context = contextsById.get(contextId);
            if (context == null) {
                throw new IllegalArgumentException(
                    "group_snapshot context is missing: " + contextId
                );
            }
            String text = bestContextSummaryText(context, targetLang, contextId);
            entries.put(new JSONObject()
                .put("kind", "context")
                .put("context", contextId)
                .put("text", text));
        }
        input.put("entries", entries);
        if (prefixSummary != null && !prefixSummary.trim().isEmpty()) {
            input.put("prefix_summary", prefixSummary.trim());
        }
        return input;
    }

    /**
     * Finds an earlier legal Group Summary that can be passed as
     * {@code prefix_summary}. Only the auto-derived {@code current} record of
     * the same target language is considered, and only when its cutoff is a
     * strictly earlier context in the same Group.
     */
    private static String earlierGroupPrefixSummary(
        JSONObject group,
        Map<String, JSONObject> contextsById,
        String targetLang,
        String cutoffContextId
    ) {
        if (group == null
            || contextsById == null
            || targetLang == null
            || cutoffContextId == null) {
            return null;
        }
        JSONObject summaryContainer = group.optJSONObject("summary");
        if (summaryContainer == null) {
            return null;
        }
        JSONObject language = summaryContainer.optJSONObject(targetLang);
        if (language == null) {
            return null;
        }
        JSONObject current = language.optJSONObject("current");
        if (current == null) {
            return null;
        }
        String text = current.optString("text", "").trim();
        String sourceHash = current.optString("source_hash", "").trim();
        String previousCutoff = current.optString("cutoff", "").trim();
        if (text.isEmpty() || sourceHash.isEmpty() || previousCutoff.isEmpty()) {
            return null;
        }
        JSONArray groupContexts = group.optJSONArray("contexts");
        if (groupContexts == null) {
            return null;
        }
        int previousIndex = GroupContextEntry.indexOfEntryId(
            groupContexts,
            previousCutoff
        );
        int cutoffIndex = GroupContextEntry.indexOfEntryId(
            groupContexts,
            cutoffContextId
        );
        if (previousIndex >= 0 && cutoffIndex > previousIndex) {
            try {
                if (isGroupCurrentApplicable(
                    group,
                    contextsById,
                    targetLang,
                    current,
                    previousCutoff
                )) {
                    return text;
                }
            } catch (Exception ignored) {
                // A malformed/stale current record is not a usable prefix.
            }
        }
        return null;
    }

    /** Returns true only for a complete current record matching its input. */
    public static boolean isGroupCurrentApplicable(
        JSONObject group,
        Map<String, JSONObject> contextsById,
        String targetLang,
        JSONObject current,
        String cutoffEntryId
    ) throws Exception {
        if (group == null || contextsById == null || current == null
            || targetLang == null || targetLang.trim().isEmpty()
            || cutoffEntryId == null || cutoffEntryId.trim().isEmpty()) {
            return false;
        }
        String text = current.optString("text", "").trim();
        String sourceHash = current.optString("source_hash", "").trim();
        if (text.isEmpty() || sourceHash.isEmpty()) {
            return false;
        }
        String computed = computeGroupSnapshotSourceHash(
            group,
            contextsById,
            cutoffEntryId,
            targetLang
        );
        return sourceHash.equals(computed);
    }

    /**
     * Computes the deterministic Group Snapshot source hash. This mirrors the
     * coordinator-side canonicalization: target language, cutoff, and the
     * ordered context summary entries. The optional prefix summary is an
     * execution detail supplied from an earlier derived record and is not part
     * of the job identity; keeping this method aligned with
     * {@link com.quarty.housamoembedtrans.summary.policy.GroupCompressionCoordinator}
     * avoids churn when a job is claimed/recomputed.
     */
    public static String computeGroupSnapshotSourceHash(
        JSONObject group,
        Map<String, JSONObject> contextsById,
        String cutoff,
        String targetLang
    ) throws Exception {
        JSONObject input = buildGroupSnapshotInput(
            group,
            contextsById,
            cutoff,
            targetLang
        );
        StringBuilder canonical = new StringBuilder();
        canonical.append("target_lang=").append(targetLang).append('\n');
        canonical.append("cutoff=").append(cutoff).append('\n');
        JSONArray entries = input.optJSONArray("entries");
        if (entries != null) {
            for (int index = 0; index < entries.length(); index++) {
                JSONObject entry = entries.optJSONObject(index);
                canonical.append("context=")
                    .append(entry.optString("context", ""))
                    .append('\n')
                    .append("text=")
                    .append(entry.optString("text", ""))
                    .append('\n');
            }
        }
        return com.quarty.housamoembedtrans.util.JobValidator.sha256Hex(
            canonical.toString().getBytes(StandardCharsets.UTF_8)
        );
    }

    private static JSONObject buildProviderRequest(
        TranslationConfig config,
        String summaryPrompt,
        String userContent
    ) throws Exception {
        JSONObject providerRequest;
        if ("openai".equals(config.getProtocol())) {
            JSONArray messages = new JSONArray()
                .put(new JSONObject()
                    .put("role", "system")
                    .put("content", summaryPrompt))
                .put(new JSONObject()
                    .put("role", "user")
                    .put("content", userContent));
            providerRequest = new JSONObject()
                .put("model", config.getModel())
                .put("stream", false)
                .put("messages", messages);
            if (config.getThinkingStrength().isEnabled()) {
                providerRequest.put(
                    "reasoning_effort",
                    config.getThinkingStrength().getConfigValue()
                );
            }
        } else {
            JSONArray messages = new JSONArray().put(
                new JSONObject()
                    .put("role", "user")
                    .put("content", userContent)
            );
            providerRequest = new JSONObject()
                .put("model", config.getModel())
                .put("max_tokens", ANTHROPIC_MAX_TOKENS)
                .put("system", summaryPrompt)
                .put("messages", messages);
            if (config.getThinkingStrength().isEnabled()) {
                providerRequest.put("thinking", new JSONObject()
                    .put("type", "enabled")
                    .put(
                        "budget_tokens",
                        config.getThinkingStrength()
                            .getAnthropicBudgetTokens()
                    ));
            }
        }
        return providerRequest;
    }

    private static String sceneSummaryText(
        JSONObject sceneEntry,
        String targetLang,
        String scene
    ) throws Exception {
        JSONObject summaries = sceneEntry.optJSONObject("summaries");
        if (summaries == null) {
            throw new IllegalArgumentException(
                "scene summary is missing for scene=" + scene
            );
        }
        SceneSummaryResolver.Resolved resolved =
            SceneSummaryResolver.resolve(summaries, targetLang);
        if (resolved == null) {
            throw new IllegalArgumentException(
                "scene summary is missing for target_lang="
                    + targetLang
                    + " scene="
                    + scene
            );
        }
        return resolved.text;
    }

    private static String bestContextSummaryText(
        JSONObject context,
        String targetLang,
        String contextId
    ) throws Exception {
        JSONObject summaryContainer = context.optJSONObject("summary");
        JSONObject languageObject = summaryContainer == null
            ? null
            : summaryContainer.optJSONObject(targetLang);
        if (languageObject == null) {
            throw new IllegalArgumentException(
                "context has no summary for target_lang="
                    + targetLang
                    + " context="
                    + contextId
            );
        }
        String[] keys = {"manual", "final", "current"};
        for (String key : keys) {
            JSONObject record = languageObject.optJSONObject(key);
            if (record != null) {
                String text = record.optString("text", "").trim();
                if (!text.isEmpty()) {
                    return text;
                }
            }
        }
        throw new IllegalArgumentException(
            "context has no summary for target_lang="
                + targetLang
                + " context="
                + contextId
        );
    }

    private static String manualDescription(
        JSONObject context,
        String targetLang
    ) {
        return ManualDescriptionResolver.resolveText(context, targetLang);
    }

    private static int findEntryIndex(JSONArray scenes, String entryId) {
        if (entryId == null || entryId.isEmpty()) {
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

    private static boolean isSupportedRequestKind(String requestKind) {
        return "context_snapshot".equals(requestKind)
            || "context_final".equals(requestKind)
            || "group_snapshot".equals(requestKind);
    }

    private static void requireContext(JSONObject context) {
        if (context == null) {
            throw new IllegalArgumentException(
                "summary request requires a context document"
            );
        }
    }
}
