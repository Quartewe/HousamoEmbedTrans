package com.quarty.housamoembedtrans.context.model;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

/**
 * Canonical accessors for the ordered Context references stored by a Group.
 * Each reference has an identity independent from the referenced Context so
 * reordering does not change Summary cutoffs or target keys.
 */
public final class GroupContextEntry {
    public static final String ENTRY_ID = "context_entry_id";
    public static final String CONTEXT_ID = "context_id";

    private GroupContextEntry() {
        throw new AssertionError("No instances");
    }

    public static JSONObject create(String contextId) {
        if (contextId == null || contextId.trim().isEmpty()) {
            throw new IllegalArgumentException("context_id must not be empty");
        }
        try {
            return new JSONObject()
                .put(ENTRY_ID, UUID.randomUUID().toString())
                .put(CONTEXT_ID, contextId);
        } catch (org.json.JSONException e) {
            throw new IllegalStateException("could not encode group entry", e);
        }
    }

    public static JSONObject require(JSONArray entries, int index) {
        JSONObject entry = entries == null ? null : entries.optJSONObject(index);
        if (entry == null) {
            throw new IllegalArgumentException(
                "group contexts[" + index + "] must be an object"
            );
        }
        String entryId = entry.optString(ENTRY_ID, "").trim();
        String contextId = entry.optString(CONTEXT_ID, "").trim();
        if (entryId.isEmpty() || contextId.isEmpty()) {
            throw new IllegalArgumentException(
                "group context entries require context_entry_id and context_id"
            );
        }
        return entry;
    }

    public static String entryIdAt(JSONArray entries, int index) {
        return require(entries, index).optString(ENTRY_ID, "");
    }

    public static String contextIdAt(JSONArray entries, int index) {
        return require(entries, index).optString(CONTEXT_ID, "");
    }

    public static int indexOfContextId(JSONArray entries, String contextId) {
        if (entries == null || contextId == null || contextId.isEmpty()) {
            return -1;
        }
        for (int index = 0; index < entries.length(); index++) {
            JSONObject entry = entries.optJSONObject(index);
            if (entry != null && contextId.equals(entry.optString(CONTEXT_ID, ""))) {
                return index;
            }
        }
        return -1;
    }

    public static int indexOfEntryId(JSONArray entries, String entryId) {
        if (entries == null || entryId == null || entryId.isEmpty()) {
            return -1;
        }
        for (int index = 0; index < entries.length(); index++) {
            JSONObject entry = entries.optJSONObject(index);
            if (entry != null && entryId.equals(entry.optString(ENTRY_ID, ""))) {
                return index;
            }
        }
        return -1;
    }

    public static List<String> contextIds(JSONArray entries) {
        if (entries == null) {
            return Collections.emptyList();
        }
        List<String> result = new ArrayList<>();
        for (int index = 0; index < entries.length(); index++) {
            result.add(contextIdAt(entries, index));
        }
        return Collections.unmodifiableList(result);
    }

    public static JSONArray cloneEntries(JSONArray entries) {
        JSONArray result = new JSONArray();
        if (entries == null) {
            return result;
        }
        for (int index = 0; index < entries.length(); index++) {
            JSONObject entry = require(entries, index);
            try {
                result.put(new JSONObject(entry.toString()));
            } catch (org.json.JSONException e) {
                throw new IllegalArgumentException("invalid group context entry", e);
            }
        }
        return result;
    }
}
