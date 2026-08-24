package com.quarty.housamoembedtrans.context.model;

import org.json.JSONObject;

import java.util.Iterator;

/**
 * Structural model for the {@code state.json.history_mapping} field of a
 * Translation Job.
 *
 * <p>The field is always explicit: {@code null} means the job deliberately has
 * no Scene Context / Context Group history, while a mapping object only carries
 * the route references {@code context_id} and nullable {@code group_id}. A
 * missing, mistyped or otherwise malformed mapping is never treated as "no
 * history"; it resolves to {@link Resolution#USER_ACTION_REQUIRED}.</p>
 */
public final class HistoryMapping {

    public static final String FIELD = "history_mapping";
    public static final String CONTEXT_ID = "context_id";
    public static final String GROUP_ID = "group_id";

    private static final int MAX_ID_LENGTH = 80;
    private static final String ID_PATTERN = "^[A-Za-z0-9][A-Za-z0-9_-]*$";

    /** Structural disposition of one persisted history mapping. */
    public enum Resolution {
        /** Explicit {@code history_mapping: null}. */
        NO_HISTORY,
        /** A well-formed mapping object. */
        VALID,
        /** Missing, mistyped or malformed mapping; user action is required. */
        USER_ACTION_REQUIRED
    }

    private HistoryMapping() {
        throw new AssertionError("No instances");
    }

    /**
     * Builds the JSON value to persist. A null context id produces the
     * explicit no-history {@link JSONObject#NULL} value; otherwise a mapping
     * object is returned with {@code group_id} either a valid id or JSON null.
     */
    public static Object fromActivePointers(String contextId, String groupId) {
        if (contextId == null) {
            return JSONObject.NULL;
        }
        validateId(contextId, CONTEXT_ID);
        JSONObject mapping = new JSONObject();
        try {
            mapping.put(CONTEXT_ID, contextId);
            if (groupId == null) {
                mapping.put(GROUP_ID, JSONObject.NULL);
            } else {
                validateId(groupId, GROUP_ID);
                mapping.put(GROUP_ID, groupId);
            }
        } catch (org.json.JSONException e) {
            throw new IllegalArgumentException(
                "could not encode history mapping",
                e
            );
        }
        return mapping;
    }

    /** Writes an explicit mapping value (JSON null for no history). */
    public static void put(JSONObject state, Object mapping) {
        if (state == null) {
            throw new IllegalArgumentException("state cannot be null");
        }
        try {
            state.put(FIELD, mapping == null ? JSONObject.NULL : mapping);
        } catch (org.json.JSONException e) {
            throw new IllegalArgumentException(
                "could not encode history mapping",
                e
            );
        }
    }

    /** Structural resolution of a persisted {@code state.json}. */
    public static Resolution resolution(JSONObject state) {
        if (state == null || !state.has(FIELD)) {
            return Resolution.USER_ACTION_REQUIRED;
        }
        Object value;
        try {
            value = state.get(FIELD);
        } catch (org.json.JSONException e) {
            return Resolution.USER_ACTION_REQUIRED;
        }
        return resolutionOfValue(value);
    }

    /** Structural resolution of a value that is about to be persisted. */
    public static Resolution resolutionOfValue(Object value) {
        if (value == null || JSONObject.NULL.equals(value)) {
            return Resolution.NO_HISTORY;
        }
        if (!(value instanceof JSONObject)) {
            return Resolution.USER_ACTION_REQUIRED;
        }
        JSONObject mapping = (JSONObject) value;

        Iterator<String> keys = mapping.keys();
        while (keys.hasNext()) {
            String key = keys.next();
            if (!CONTEXT_ID.equals(key) && !GROUP_ID.equals(key)) {
                return Resolution.USER_ACTION_REQUIRED;
            }
        }
        if (!mapping.has(CONTEXT_ID) || mapping.isNull(CONTEXT_ID)) {
            return Resolution.USER_ACTION_REQUIRED;
        }
        Object contextValue = mapping.opt(CONTEXT_ID);
        if (!(contextValue instanceof String)
            || !isValidId((String) contextValue)) {
            return Resolution.USER_ACTION_REQUIRED;
        }
        if (!mapping.has(GROUP_ID)) {
            return Resolution.USER_ACTION_REQUIRED;
        }
        if (!mapping.isNull(GROUP_ID)) {
            Object groupValue = mapping.opt(GROUP_ID);
            if (!(groupValue instanceof String)
                || !isValidId((String) groupValue)) {
                return Resolution.USER_ACTION_REQUIRED;
            }
        }
        return Resolution.VALID;
    }

    /** Throws unless the value is a persistable mapping or explicit null. */
    public static void requireWritable(Object value) {
        if (resolutionOfValue(value) == Resolution.USER_ACTION_REQUIRED) {
            throw new IllegalArgumentException(
                "history_mapping must be null or an object with a non-empty "
                    + "context_id and nullable group_id"
            );
        }
    }

    private static void validateId(String id, String fieldName) {
        if (!isValidId(id)) {
            throw new IllegalArgumentException(
                fieldName + " is not a valid context/group id"
            );
        }
    }

    private static boolean isValidId(String id) {
        return id != null
            && id.length() <= MAX_ID_LENGTH
            && id.matches(ID_PATTERN);
    }
}
