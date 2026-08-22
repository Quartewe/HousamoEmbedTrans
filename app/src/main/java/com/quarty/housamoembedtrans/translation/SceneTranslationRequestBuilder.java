package com.quarty.housamoembedtrans.translation;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

/**
 * Pure-Java reconstruction of the native Translation request payload from a
 * synchronized Scene document.
 *
 * <p>The native pipeline builds the persisted {@code request.json} from the
 * Scene file, strips local metadata, assigns traversal {@code seq} numbers,
 * and canonicalizes object keys before hashing the request ID. This class
 * mirrors that conversion so the "Edit all Scenes" page can create the same
 * request identity without the native bridge.</p>
 */
public final class SceneTranslationRequestBuilder {

    private SceneTranslationRequestBuilder() {
        throw new AssertionError("No instances");
    }

    /** Builds the canonical request bytes for a Scene and target language. */
    public static byte[] buildRequest(
        JSONObject scene,
        String targetLanguage
    ) throws Exception {
        if (scene == null) {
            throw new IllegalArgumentException("scene is null");
        }
        if (targetLanguage == null || targetLanguage.trim().isEmpty()) {
            throw new IllegalArgumentException("target_lang is required");
        }

        JSONObject request = new JSONObject();
        request.put("scene", requireNonBlankString(scene, "scene"));
        request.put("game_version", requireNonBlankString(scene, "game_version"));
        request.put("raw_lang", requireNonBlankString(scene, "raw_lang"));
        copyRequiredMember(scene, request, "character", JSONObject.class);
        copyRequiredMember(scene, request, "mentioned_characters", JSONArray.class);
        copyRequiredMember(scene, request, "game_terms", JSONArray.class);

        request.put("protect", buildProtect(scene));
        request.put("target_lang", targetLanguage);
        request.put(
            "scene_items",
            convertItems(
                requireArray(scene, "scene_items"),
                new SeqCursor(requireArray(scene, "seq_to_order").length())
            )
        );
        return toCanonicalBytes(request);
    }

    /** Deterministic UUID v3-style request ID used by the Translation Job store. */
    public static String buildRequestId(byte[] requestJson) {
        if (requestJson == null || requestJson.length == 0) {
            throw new IllegalArgumentException("requestJson is empty");
        }
        return UUID.nameUUIDFromBytes(requestJson).toString();
    }

    private static JSONArray buildProtect(JSONObject scene) throws Exception {
        JSONArray source = scene.optJSONArray("protect");
        JSONArray output = new JSONArray();
        if (source == null) {
            return output;
        }
        for (int index = 0; index < source.length(); index++) {
            JSONObject token = source.optJSONObject(index);
            if (token == null) {
                throw new IllegalArgumentException(
                    "protect token at index " + index + " must be an object"
                );
            }
            String label = requireNonBlankString(token, "label");
            String origin = requireNonBlankString(token, "origin");
            if (!token.has("order") || token.isNull("order")
                || !(token.opt("order") instanceof JSONObject)) {
                throw new IllegalArgumentException(
                    "protect token " + label + " must have an order object"
                );
            }
            output.put(new JSONObject()
                .put("label", label)
                .put("origin", origin));
        }
        return output;
    }

    private static JSONArray convertItems(
        JSONArray source,
        SeqCursor cursor
    ) throws Exception {
        JSONArray output = new JSONArray();
        for (int index = 0; index < source.length(); index++) {
            JSONObject item = source.optJSONObject(index);
            if (item == null) {
                throw new IllegalArgumentException(
                    "scene_items[" + index + "] must be an object"
                );
            }
            String type = requireNonBlankString(item, "type");
            switch (type) {
                case "text":
                    output.put(convertText(item, cursor));
                    break;
                case "choice":
                    output.put(convertChoice(item, cursor));
                    break;
                case "if":
                    output.put(convertIf(item, cursor));
                    break;
                default:
                    throw new IllegalArgumentException(
                        "unknown scene item type " + type
                    );
            }
        }
        if (cursor.value != cursor.expected) {
            throw new IllegalArgumentException(
                "scene item text count " + cursor.value
                    + " does not match seq_to_order count " + cursor.expected
            );
        }
        return output;
    }

    private static JSONObject convertText(
        JSONObject source,
        SeqCursor cursor
    ) throws Exception {
        requireOrder(source);
        if (!(source.opt("translations") instanceof JSONObject)) {
            throw new IllegalArgumentException(
                "text item must have a translations object"
            );
        }
        JSONObject output = new JSONObject();
        output.put("type", "text");
        output.put("seq", cursor.next());
        output.put("speaker", requireString(source, "speaker"));
        output.put("text", requireNonBlankString(source, "text"));
        return output;
    }

    private static JSONObject convertChoice(
        JSONObject source,
        SeqCursor cursor
    ) throws Exception {
        requireOrder(source);
        String mergeLabel = requireNonBlankString(source, "merge_label");
        JSONArray sourceBranches = requireArray(source, "branches");
        if (sourceBranches.length() == 0) {
            throw new IllegalArgumentException("choice has no branches");
        }

        JSONArray outputBranches = new JSONArray();
        for (int index = 0; index < sourceBranches.length(); index++) {
            JSONObject branch = sourceBranches.optJSONObject(index);
            if (branch == null) {
                throw new IllegalArgumentException(
                    "choice branch at index " + index + " must be an object"
                );
            }
            JSONObject outputBranch = new JSONObject();
            outputBranch.put(
                "target_label",
                requireNonBlankString(branch, "target_label")
            );
            JSONArray sourceOptions = requireArray(branch, "options");
            if (sourceOptions.length() == 0) {
                throw new IllegalArgumentException(
                    "choice branch at index " + index + " has no options"
                );
            }
            JSONArray outputOptions = new JSONArray();
            for (int optionIndex = 0;
                 optionIndex < sourceOptions.length();
                 optionIndex++) {
                JSONObject option = sourceOptions.optJSONObject(optionIndex);
                if (option == null
                    || !"text".equals(option.optString("type", ""))) {
                    throw new IllegalArgumentException(
                        "choice option must be a text item"
                    );
                }
                outputOptions.put(convertText(option, cursor));
            }
            outputBranch.put("options", outputOptions);
            outputBranch.put(
                "following_text",
                convertItems(requireArray(branch, "following_text"), cursor)
            );
            outputBranches.put(outputBranch);
        }

        JSONObject output = new JSONObject();
        output.put("type", "choice");
        output.put("merge_label", mergeLabel);
        output.put("branches", outputBranches);
        return output;
    }

    private static JSONObject convertIf(
        JSONObject source,
        SeqCursor cursor
    ) throws Exception {
        requireOrder(source);
        JSONObject output = new JSONObject();
        output.put("type", "if");
        output.put("condition", requireNonBlankString(source, "condition"));
        output.put("target_label", requireNonBlankString(source, "target_label"));
        output.put("merge_label", requireNonBlankString(source, "merge_label"));
        output.put(
            "following_text",
            convertItems(requireArray(source, "following_text"), cursor)
        );
        return output;
    }

    private static void requireOrder(JSONObject item) throws Exception {
        Object order = item.opt("order");
        if (!(order instanceof JSONObject)) {
            throw new IllegalArgumentException(
                "scene item must have an order object"
            );
        }
    }

    private static void copyRequiredMember(
        JSONObject source,
        JSONObject target,
        String name,
        Class<?> expectedType
    ) throws Exception {
        Object value = source.opt(name);
        if (value == null || !expectedType.isInstance(value)) {
            throw new IllegalArgumentException(
                "scene field " + name + " is required and must be "
                    + expectedType.getSimpleName()
            );
        }
        target.put(name, value);
    }

    private static String requireString(JSONObject object, String name)
        throws Exception {
        Object value = object.opt(name);
        if (!(value instanceof String)) {
            throw new IllegalArgumentException(
                "scene field " + name + " is required and must be a string"
            );
        }
        return (String) value;
    }

    private static String requireNonBlankString(JSONObject object, String name)
        throws Exception {
        String value = requireString(object, name);
        if (value.trim().isEmpty()) {
            throw new IllegalArgumentException(
                "scene field " + name + " is required"
            );
        }
        return value;
    }

    private static JSONArray requireArray(JSONObject object, String name)
        throws Exception {
        Object value = object.opt(name);
        if (!(value instanceof JSONArray)) {
            throw new IllegalArgumentException(
                "scene field " + name + " must be an array"
            );
        }
        return (JSONArray) value;
    }

    // ── Canonical compact JSON (lexicographic object keys) ─────────────

    private static byte[] toCanonicalBytes(JSONObject root) throws Exception {
        return toCanonicalString(root).getBytes(StandardCharsets.UTF_8);
    }

    private static String toCanonicalString(Object value) throws JSONException {
        if (value == null || value == JSONObject.NULL) {
            return "null";
        }
        if (value instanceof JSONObject) {
            JSONObject object = (JSONObject) value;
            List<String> keys = new ArrayList<>();
            java.util.Iterator<String> iterator = object.keys();
            while (iterator.hasNext()) {
                keys.add(iterator.next());
            }
            Collections.sort(keys);
            StringBuilder builder = new StringBuilder("{");
            for (int index = 0; index < keys.size(); index++) {
                if (index > 0) {
                    builder.append(',');
                }
                builder.append(quote(keys.get(index)));
                builder.append(':');
                builder.append(toCanonicalString(object.opt(keys.get(index))));
            }
            builder.append('}');
            return builder.toString();
        }
        if (value instanceof JSONArray) {
            JSONArray array = (JSONArray) value;
            StringBuilder builder = new StringBuilder("[");
            for (int index = 0; index < array.length(); index++) {
                if (index > 0) {
                    builder.append(',');
                }
                builder.append(toCanonicalString(array.opt(index)));
            }
            builder.append(']');
            return builder.toString();
        }
        if (value instanceof String) {
            return quote((String) value);
        }
        if (value instanceof Boolean || value instanceof Number) {
            return value.toString();
        }
        throw new JSONException(
            "unsupported JSON value " + value.getClass().getName()
        );
    }

    private static String quote(String value) {
        StringBuilder builder = new StringBuilder(value.length() + 2);
        builder.append('"');
        for (int index = 0; index < value.length(); index++) {
            char character = value.charAt(index);
            switch (character) {
                case '"':
                    builder.append("\\\"");
                    break;
                case '\\':
                    builder.append("\\\\");
                    break;
                case '\b':
                    builder.append("\\b");
                    break;
                case '\t':
                    builder.append("\\t");
                    break;
                case '\n':
                    builder.append("\\n");
                    break;
                case '\f':
                    builder.append("\\f");
                    break;
                case '\r':
                    builder.append("\\r");
                    break;
                default:
                    if (character < 0x20) {
                        builder.append("\\u");
                        String hex = Integer.toHexString(character).toUpperCase();
                        for (int padding = hex.length(); padding < 4; padding++) {
                            builder.append('0');
                        }
                        builder.append(hex);
                    } else {
                        builder.append(character);
                    }
            }
        }
        builder.append('"');
        return builder.toString();
    }

    private static final class SeqCursor {
        private final int expected;
        private int value;

        private SeqCursor(int expected) {
            this.expected = expected;
        }

        private int next() {
            return ++value;
        }
    }
}
