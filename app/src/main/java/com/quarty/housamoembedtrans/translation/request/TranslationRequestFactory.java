package com.quarty.housamoembedtrans.translation.request;
import android.content.Context;

import com.quarty.housamoembedtrans.context.history.HistoryPayload;
import com.quarty.housamoembedtrans.provider.PreparedApiRequest;
import com.quarty.housamoembedtrans.provider.TranslationConfig;
import com.quarty.housamoembedtrans.storage.config.ConfigStore;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Builds provider requests while keeping the scene prefix stable for caching. */
public final class TranslationRequestFactory {

    private TranslationRequestFactory() {
        throw new AssertionError("No instances");
    }

    public static PreparedApiRequest buildMainRequest(
        TranslationConfig config,
        JSONObject originalScene
    ) throws Exception {
        return buildMainRequest(config, originalScene, HistoryPayload.empty());
    }

    public static PreparedApiRequest buildMainRequest(
        TranslationConfig config,
        JSONObject originalScene,
        HistoryPayload historyPayload
    ) throws Exception {
        return buildMainRequestInternal(
            null,
            config,
            originalScene,
            historyPayload,
            null,
            false
        );
    }

    /**
     * Builds a new main request from the latest formal dictionaries.  The
     * caller fixes {@code requestTerms} for this request; it controls only
     * the optional internal-term input and its main-request prompt clause.
     */
    public static PreparedApiRequest buildMainRequest(
        Context context,
        TranslationConfig config,
        JSONObject originalScene,
        HistoryPayload historyPayload,
        JSONObject internalTerms,
        boolean requestTerms
    ) throws Exception {
        return buildMainRequestInternal(
            context,
            config,
            originalScene,
            historyPayload,
            internalTerms,
            requestTerms
        );
    }

    private static PreparedApiRequest buildMainRequestInternal(
        Context context,
        TranslationConfig config,
        JSONObject originalScene,
        HistoryPayload historyPayload,
        JSONObject internalTerms,
        boolean requestTerms
    ) throws Exception {
        JSONObject matchedScene = prepareScene(
            context,
            originalScene,
            internalTerms,
            requestTerms
        );
        boolean requestContextSummary = matchedScene.optBoolean(
            "request_context_summary",
            false
        );
        JSONObject userPayload = augmentScene(
            matchedScene,
            historyPayload,
            requestContextSummary
        );
        return buildProviderRequest(
            config,
            userPayload.toString(),
            null,
            historyPayload,
            requestTerms
        );
    }

    public static PreparedApiRequest buildRepairRequest(
        TranslationConfig config,
        JSONObject originalScene,
        List<TranslationGradientPlanner.Block> contextBlocks,
        Map<Integer, TranslationResultValidator.Result> failures,
        TranslationResultValidator validator,
        String summary,
        boolean useFullScene
    ) throws Exception {
        return buildRepairRequest(
            null,
            config,
            originalScene,
            contextBlocks,
            failures,
            validator,
            summary,
            useFullScene,
            HistoryPayload.empty(),
            null
        );
    }

    public static PreparedApiRequest buildRepairRequest(
        TranslationConfig config,
        JSONObject originalScene,
        List<TranslationGradientPlanner.Block> contextBlocks,
        Map<Integer, TranslationResultValidator.Result> failures,
        TranslationResultValidator validator,
        String summary,
        boolean useFullScene,
        HistoryPayload historyPayload
    ) throws Exception {
        return buildRepairRequest(
            null,
            config,
            originalScene,
            contextBlocks,
            failures,
            validator,
            summary,
            useFullScene,
            historyPayload,
            null
        );
    }

    /**
     * Builds a body-repair request using the same current dictionary match
     * seam as a main request.  Repair never enables the terms protocol or
     * appends the internal-dictionary prompt.
     */
    public static PreparedApiRequest buildRepairRequest(
        Context context,
        TranslationConfig config,
        JSONObject originalScene,
        List<TranslationGradientPlanner.Block> contextBlocks,
        Map<Integer, TranslationResultValidator.Result> failures,
        TranslationResultValidator validator,
        String summary,
        boolean useFullScene,
        HistoryPayload historyPayload,
        JSONObject internalTerms
    ) throws Exception {
        if (summary == null || summary.trim().isEmpty()) {
            throw new IllegalArgumentException(
                "accepted summary is required for repair"
            );
        }
        if (failures == null || failures.isEmpty()) {
            throw new IllegalArgumentException(
                "repair request requires failed seqs"
            );
        }

        String stableContext = useFullScene
            ? originalScene.toString()
            : buildBlockContext(originalScene, contextBlocks).toString();

        List<Integer> orderedSeqs = new ArrayList<>(failures.keySet());
        Collections.sort(orderedSeqs);
        JSONArray retrySeqs = new JSONArray();
        JSONArray feedback = new JSONArray();
        StringBuilder retryText = new StringBuilder();
        for (Integer seq : orderedSeqs) {
            retrySeqs.put(seq);
            String sourceText = validator.getSourceTexts().get(seq);
            if (sourceText != null) {
                retryText.append(sourceText).append('\n');
            }
            TranslationResultValidator.Result result = failures.get(seq);
            if (result == null) {
                result = validator.missing(seq);
            }
            feedback.put(validator.buildFeedback(result));
        }

        JSONObject instruction = new JSONObject()
            .put("summary", summary)
            .put("retry_seqs", retrySeqs)
            .put("retry_feedback", feedback)
            .put(
                "protect",
                selectProtect(
                    originalScene.optJSONArray("protect"),
                    retryText.toString()
                )
            );

        JSONObject matchedScene = prepareScene(
            context,
            new JSONObject(stableContext),
            internalTerms,
            internalTerms != null
        );
        JSONObject userPayload = augmentScene(
            matchedScene,
            historyPayload,
            false
        );
        String userContent = userPayload.toString();

        return buildProviderRequest(
            config,
            userContent,
            instruction.toString(),
            historyPayload,
            false
        );
    }

    public static JSONObject buildFinalResult(
        TranslationConfig config,
        String targetLanguage,
        String summary,
        String contextSummary,
        Map<Integer, String> finalTranslations
    ) throws Exception {
        if (summary == null || summary.trim().isEmpty()) {
            throw new IllegalArgumentException(
                "translation summary is empty"
            );
        }
        List<Integer> orderedSeqs = new ArrayList<>(
            finalTranslations.keySet()
        );
        Collections.sort(orderedSeqs);
        JSONArray translations = new JSONArray();
        for (Integer seq : orderedSeqs) {
            translations.put(new JSONObject()
                .put("seq", seq)
                .put("text", finalTranslations.get(seq)));
        }

        JSONObject result = new JSONObject()
            .put("summary", summary)
            .put("translations", translations)
            .put("provider", config.getProtocol())
            .put("model", config.getModel())
            .put("target_lang", targetLanguage);
        if (contextSummary != null) {
            result.put("context_summary", contextSummary);
        }
        return result;
    }

    /**
     * Reads the three current resources and matches a request while the
     * ConfigStore access lock is held.  A null Context is reserved for the
     * existing host seam and deliberately preserves the supplied Scene.
     */
    private static JSONObject prepareScene(
        Context context,
        JSONObject originalScene,
        JSONObject internalTerms,
        boolean useInternalTerms
    ) throws Exception {
        if (originalScene == null) {
            throw new IllegalArgumentException("scene cannot be null");
        }
        if (context == null) {
            return new JSONObject(originalScene.toString());
        }

        return ConfigStore.withConfigAccess(() -> {
            ConfigStore store = new ConfigStore(context);
            JSONObject currentConfig = store.loadJson(
                ConfigStore.CONFIG_FILE_NAME
            ).json;
            JSONObject currentCharacters = store.loadJson(
                ConfigStore.CHARDICT_FILE_NAME
            ).json;
            JSONObject currentTerms = store.loadJson(
                ConfigStore.GAMETERMS_FILE_NAME
            ).json;
            JSONObject weights = null;
            JSONObject userSettings = currentConfig.optJSONObject(
                "UserSettings"
            );
            if (userSettings != null) {
                weights = userSettings.optJSONObject("CharacterWeight");
            }
            return FormalDictionaryMatcher.matchScene(
                originalScene,
                currentCharacters,
                currentTerms,
                useInternalTerms ? internalTerms : null,
                weights
            );
        });
    }

    private static JSONObject augmentScene(
        JSONObject originalScene,
        HistoryPayload historyPayload,
        boolean requestContextSummary
    ) throws Exception {
        if (originalScene == null) {
            throw new IllegalArgumentException("scene cannot be null");
        }
        JSONObject userPayload = new JSONObject(originalScene.toString());
        if (historyPayload != null && !historyPayload.isEmpty()) {
            userPayload.put("previous_context", historyPayload.toJson());
        }
        if (requestContextSummary) {
            userPayload.put("request_context_summary", true);
        }
        return userPayload;
    }

    private static PreparedApiRequest buildProviderRequest(
        TranslationConfig config,
        String userContent,
        String repairInstruction,
        HistoryPayload historyPayload,
        boolean includeInternalTermsPrompt
    ) throws Exception {
        if (repairInstruction != null) {
            userContent +=
                "\n\n<internal_repair_instruction>\n"
                    + repairInstruction
                    + "\n</internal_repair_instruction>";
        }

        String systemPrompt = config.getSystemPrompt();
        if (includeInternalTermsPrompt && repairInstruction == null) {
            systemPrompt = InternalTermsPrompt.appendTo(systemPrompt);
        }

        JSONObject providerRequest;
        if ("openai".equals(config.getProtocol())) {
            JSONArray messages = new JSONArray()
                .put(new JSONObject()
                    .put("role", "system")
                    .put("content", systemPrompt))
                .put(new JSONObject()
                    .put("role", "user")
                    .put("content", userContent));
            providerRequest = new JSONObject()
                .put("model", config.getModel())
                .put("max_tokens", config.getMaxTokens())
                .put("stream", config.isStreamingResponseEnabled())
                .put("messages", messages);
            if (config.shouldSendThinkingParameters()) {
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
                .put("max_tokens", config.getMaxTokens())
                .put("stream", config.isStreamingResponseEnabled())
                .put("system", systemPrompt)
                .put("messages", messages);
            if (config.shouldSendThinkingParameters()) {
                providerRequest.put("thinking", new JSONObject()
                    .put("type", "enabled")
                    .put(
                        "budget_tokens",
                        config.getThinkingStrength()
                            .getAnthropicBudgetTokens()
                    ));
            }
        }

        return new PreparedApiRequest(
            config,
            providerRequest,
            userContent,
            historyPayload
        );
    }

    private static JSONObject buildBlockContext(
        JSONObject originalScene,
        List<TranslationGradientPlanner.Block> blocks
    ) throws Exception {
        if (blocks == null || blocks.isEmpty()) {
            throw new IllegalArgumentException(
                "gradient repair context requires at least one block"
            );
        }

        int firstRoot = Integer.MAX_VALUE;
        int lastRoot = -1;
        for (TranslationGradientPlanner.Block block : blocks) {
            firstRoot = Math.min(firstRoot, block.getFirstRootIndex());
            lastRoot = Math.max(lastRoot, block.getLastRootIndex());
        }

        JSONObject context = new JSONObject(originalScene.toString());
        JSONArray originalItems = originalScene.getJSONArray("scene_items");
        JSONArray selectedItems = new JSONArray();
        for (int index = firstRoot; index <= lastRoot; index++) {
            selectedItems.put(new JSONObject(
                originalItems.getJSONObject(index).toString()
            ));
        }
        context.put("scene_items", selectedItems);
        context.put(
            "protect",
            selectProtect(
                originalScene.optJSONArray("protect"),
                selectedItems.toString()
            )
        );
        return context;
    }

    private static JSONArray selectProtect(
        JSONArray originalProtect,
        String selectedItemsJson
    ) throws Exception {
        JSONArray selected = new JSONArray();
        if (originalProtect == null) {
            return selected;
        }
        Set<String> added = new LinkedHashSet<>();
        for (int index = 0; index < originalProtect.length(); index++) {
            JSONObject token = originalProtect.optJSONObject(index);
            if (token == null) {
                continue;
            }
            String label = token.optString("label", "");
            if (!label.isEmpty()
                && selectedItemsJson.contains(label)
                && added.add(label)) {
                selected.put(new JSONObject(token.toString()));
            }
        }
        return selected;
    }
}
