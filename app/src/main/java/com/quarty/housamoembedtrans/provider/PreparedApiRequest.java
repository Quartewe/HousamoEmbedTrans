package com.quarty.housamoembedtrans.provider;
import com.quarty.housamoembedtrans.context.history.HistoryPayload;

import org.json.JSONException;
import org.json.JSONObject;

/**
 * Immutable provider request formed after all pre-send gates have passed.
 *
 * <p>This is the boundary after which settings, recovery decisions and
 * Context/Group edits no longer change the attempt. The provider body, the
 * user content, the ThinkingStrength and the context-length limit are frozen
 * together.</p>
 */
public final class PreparedApiRequest {

    private final TranslationConfig config;
    private final JSONObject providerRequest;
    private final String userContent;
    private final int estimatedTokenCount;
    private final HistoryPayload historyPayload;

    public PreparedApiRequest(
        TranslationConfig config,
        JSONObject providerRequest,
        String userContent,
        HistoryPayload historyPayload
    ) {
        if (config == null || providerRequest == null || userContent == null) {
            throw new IllegalArgumentException(
                "config, providerRequest and userContent are required"
            );
        }
        this.config = config;
        try {
            this.providerRequest = new JSONObject(providerRequest.toString());
        } catch (JSONException e) {
            throw new IllegalArgumentException(
                "invalid provider request JSON",
                e
            );
        }
        this.userContent = userContent;
        this.historyPayload = historyPayload == null
            ? HistoryPayload.empty()
            : historyPayload;
        this.estimatedTokenCount =
            ProviderTokenEstimator.estimate(config.getSystemPrompt())
                + ProviderTokenEstimator.estimate(userContent);
    }

    public JSONObject getProviderRequest() {
        try {
            return new JSONObject(providerRequest.toString());
        } catch (JSONException e) {
            throw new IllegalStateException(
                "frozen provider request became invalid",
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
        return config.getContextLength();
    }

    public ThinkingStrength getThinkingStrength() {
        return config.getThinkingStrength();
    }

    public HistoryPayload getHistoryPayload() {
        return historyPayload;
    }

    public boolean isWithinContextLength() {
        return estimatedTokenCount <= config.getContextLength();
    }
}
