package com.quarty.housamoembedtrans.context.schema;
import com.quarty.housamoembedtrans.context.store.ContextStore;
import com.quarty.housamoembedtrans.context.store.GroupStore;
import com.quarty.housamoembedtrans.storage.json.JsonSchemaValidator;

import com.quarty.housamoembedtrans.util.IoUtils;

import android.content.Context;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Set;

/**
 * Strict validator for the new Scene Context / Context Group / Index shapes.
 *
 * <p>This is intentionally the reusable schema seam for ContextStore, GroupStore,
 * import/export and startup recovery. Old flat-schema fields are rejected with a
 * migration error; this ticket never rewrites old documents into the new shape.</p>
 */
public final class ContextGroupSchemaValidator {

    public static final String CONTEXT_SCHEMA_ASSET_PATH =
        "schema/scene_context_schema.json";
    public static final String GROUP_SCHEMA_ASSET_PATH =
        "schema/scene_context_group_schema.json";
    public static final String INDEX_SCHEMA_ASSET_PATH =
        "schema/scene_context_index_schema.json";

    private static final int MAX_SCHEMA_BYTES = 256 * 1024;

    private static final Set<String> CONTEXT_LEGACY_ROOT_KEYS =
        Collections.unmodifiableSet(new HashSet<>(Arrays.asList(
            "state",
            "auto_compress",
            "final_summaries",
            "final_summary",
            "current_summary",
            "manual_summary",
            "inflight_requests",
            "pending_group_id"
        )));

    private static final Set<String> GROUP_LEGACY_ROOT_KEYS =
        Collections.unmodifiableSet(new HashSet<>(Arrays.asList(
            "auto_compress",
            "cumulative_snapshots",
            "final_summaries",
            "final_summary",
            "current_summary",
            "manual_summary"
        )));

    private static final Set<String> INDEX_LEGACY_ROOT_KEYS =
        Collections.unmodifiableSet(new HashSet<>(Arrays.asList(
            "primary_group_id"
        )));

    public static final class ValidationException extends Exception {
        ValidationException(String message) {
            super(message);
        }

        ValidationException(String message, Throwable cause) {
            super(message, cause);
        }
    }

    private final JsonSchemaValidator contextValidator;
    private final JsonSchemaValidator groupValidator;
    private final JsonSchemaValidator indexValidator;

    public ContextGroupSchemaValidator(
        JSONObject contextSchema,
        JSONObject groupSchema,
        JSONObject indexSchema
    ) {
        if (contextSchema == null || groupSchema == null || indexSchema == null) {
            throw new IllegalArgumentException(
                "context/group/index schema cannot be null"
            );
        }
        contextValidator = new JsonSchemaValidator(contextSchema);
        groupValidator = new JsonSchemaValidator(groupSchema);
        indexValidator = new JsonSchemaValidator(indexSchema);
    }

    public static ContextGroupSchemaValidator loadFromAssets(Context context) {
        Context appContext = context.getApplicationContext();
        Context safeContext = appContext != null ? appContext : context;
        try {
            return new ContextGroupSchemaValidator(
                loadSchema(safeContext, CONTEXT_SCHEMA_ASSET_PATH),
                loadSchema(safeContext, GROUP_SCHEMA_ASSET_PATH),
                loadSchema(safeContext, INDEX_SCHEMA_ASSET_PATH)
            );
        } catch (Exception e) {
            throw new IllegalStateException(
                "could not load scene context/group/index schemas",
                e
            );
        }
    }

    public void validateContext(JSONObject context) throws ValidationException {
        validate(
            contextValidator,
            context,
            "context",
            CONTEXT_LEGACY_ROOT_KEYS
        );
    }

    public void validateGroup(JSONObject group) throws ValidationException {
        validate(
            groupValidator,
            group,
            "group",
            GROUP_LEGACY_ROOT_KEYS
        );
    }

    public void validateIndex(JSONObject index) throws ValidationException {
        validate(
            indexValidator,
            index,
            "index",
            INDEX_LEGACY_ROOT_KEYS
        );
    }

    private static JSONObject loadSchema(Context context, String assetPath)
        throws Exception {
        try (InputStream input = context.getAssets().open(assetPath)) {
            String schemaText = new String(
                IoUtils.readAllBytesLimited(input, MAX_SCHEMA_BYTES),
                StandardCharsets.UTF_8
            );
            return new JSONObject(schemaText);
        }
    }

    private static void validate(
        JsonSchemaValidator validator,
        JSONObject document,
        String kind,
        Set<String> legacyRootKeys
    ) throws ValidationException {
        if (document == null) {
            throw new IllegalArgumentException(kind + " document is null");
        }

        rejectLegacyRootFields(document, kind, legacyRootKeys);
        try {
            rejectLegacyNestedFields(document, kind);
        } catch (org.json.JSONException e) {
            throw new ValidationException(
                "invalid " + kind + ": malformed JSON",
                e
            );
        }

        try {
            validator.validate(document);
        } catch (JsonSchemaValidator.ValidationException e) {
            throw new ValidationException("invalid " + kind + ": " + e.getMessage(), e);
        }
    }

    private static void rejectLegacyRootFields(
        JSONObject document,
        String kind,
        Set<String> legacyRootKeys
    ) throws ValidationException {
        for (String field : legacyRootKeys) {
            if (document.has(field)) {
                throw legacyError(kind, field);
            }
        }
    }

    private static void rejectLegacyNestedFields(
        Object value,
        String kind
    ) throws ValidationException, org.json.JSONException {
        if (value instanceof JSONObject) {
            JSONObject object = (JSONObject) value;
            if (object.has("cumulative_snapshots")) {
                throw legacyError(kind, "cumulative_snapshots");
            }
            if (object.has("text")
                && (object.has("status") || object.has("source_revision"))) {
                String field = object.has("source_revision")
                    ? "source_revision"
                    : "status";
                throw legacyError(kind, field);
            }
            Iterator<String> keys = object.keys();
            while (keys.hasNext()) {
                rejectLegacyNestedFields(object.get(keys.next()), kind);
            }
        } else if (value instanceof JSONArray) {
            JSONArray array = (JSONArray) value;
            for (int index = 0; index < array.length(); index++) {
                rejectLegacyNestedFields(array.get(index), kind);
            }
        }
    }

    private static ValidationException legacyError(String kind, String field) {
        return new ValidationException(
            kind + " uses legacy schema field '" + field
                + "'; migrate to the new summary.<target_lang>.{final,current,manual} "
                + "shape. Old documents are rejected and are never migrated automatically"
        );
    }
}
