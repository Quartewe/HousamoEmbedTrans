package com.quarty.housamoembedtrans.translation;

import org.json.JSONException;
import org.json.JSONObject;

/**
 * Immutable, read-only {@code previous_context} payload produced by History
 * Expansion and frozen into one PreparedApiRequest.
 */
public final class HistoryPayload {

    private final JSONObject json;

    private HistoryPayload(JSONObject json) {
        this.json = json == null ? new JSONObject() : json;
    }

    public static HistoryPayload of(JSONObject json) {
        if (json == null) {
            return empty();
        }
        try {
            return new HistoryPayload(new JSONObject(json.toString()));
        } catch (JSONException e) {
            throw new IllegalArgumentException(
                "invalid history payload JSON",
                e
            );
        }
    }

    public static HistoryPayload empty() {
        return new HistoryPayload(new JSONObject());
    }

    public JSONObject toJson() {
        try {
            // Defensive copy so callers cannot mutate the frozen payload.
            return new JSONObject(json.toString());
        } catch (JSONException e) {
            throw new IllegalStateException(
                "frozen history payload became invalid",
                e
            );
        }
    }

    public boolean isEmpty() {
        return json.length() == 0;
    }
}
