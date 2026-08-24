package com.quarty.housamoembedtrans.translation;

import com.quarty.housamoembedtrans.storage.HistoryMapping;
import com.quarty.housamoembedtrans.util.TranslationJobStatus;

import org.json.JSONObject;

/**
 * Pure state-level operations for rewriting a Translation Job's
 * {@code history_mapping}. Keeping this free of Android dependencies lets host
 * JUnit tests cover the queued-only rewrite rule without a runtime.
 */
public final class TranslationJobHistoryMapping {

    private TranslationJobHistoryMapping() {
        throw new AssertionError("No instances");
    }

    /**
     * Rewrites the mapping on a queued Translation Job state. A non-queued
     * state (for example running after the job was claimed) rejects the edit,
     * because that attempt has already frozen its history.
     */
    public static JSONObject rewrite(JSONObject state, Object historyMapping) {
        if (state == null) {
            throw new IllegalArgumentException("state cannot be null");
        }
        if (historyMapping == null) {
            historyMapping = JSONObject.NULL;
        }
        HistoryMapping.requireWritable(historyMapping);
        String status = state.optString("status", "");
        if (!TranslationJobStatus.QUEUED.wireValue().equals(status)) {
            throw new IllegalStateException(
                "cannot rewrite history_mapping for non-queued job status="
                    + status
            );
        }
        HistoryMapping.put(state, historyMapping);
        return state;
    }
}
