package com.quarty.housamoembedtrans.context.history;

import org.json.JSONArray;
import org.json.JSONObject;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;

/**
 * Deterministic semantic hash for Scene Context summary inputs.
 *
 * <p>The hash is per target language. It includes the context id, ordered
 * scene references (optionally up to a {@code cutoff} entry id), the scene
 * summary language actually adopted for that target language and its text, and
 * the effective manual description language and text. Timestamps, revision numbers,
 * derived summary records, Summary Job state, provider/model and other
 * execution configuration are deliberately excluded.</p>
 */
public final class ContextContentHash {

    private static final char[] HEX_DIGITS =
        "0123456789abcdef".toCharArray();

    private ContextContentHash() {
        throw new AssertionError("No instances");
    }

    /** Full-context semantic hash for one target language. */
    public static String compute(JSONObject context, String targetLang) {
        return compute(context, targetLang, null, null);
    }

    /**
     * Hash of the context prefix up to and including {@code cutoffEntryId}.
     * Used as {@code source_hash} for {@code summary.<lang>.current} records.
     */
    public static String computeToCutoff(
        JSONObject context,
        String targetLang,
        String cutoffEntryId
    ) {
        return compute(context, targetLang, cutoffEntryId, null);
    }

    /**
     * Full-context hash while excluding one scene's summaries. Scene membership
     * and entry ids remain part of the input so unrelated membership changes are
     * still detected.
     */
    public static String computeExcludingScene(
        JSONObject context,
        String targetLang,
        String excludedScene
    ) {
        return compute(context, targetLang, null, excludedScene);
    }

    private static String compute(
        JSONObject context,
        String targetLang,
        String cutoffEntryId,
        String excludedScene
    ) {
        if (context == null) {
            throw new IllegalArgumentException("context is required");
        }
        if (targetLang == null || targetLang.trim().isEmpty()) {
            throw new IllegalArgumentException("target_lang is required");
        }
        JSONObject hash;
        StringBuilder canonical = new StringBuilder();
        try {
            hash = hashObject(
                context,
                targetLang,
                cutoffEntryId,
                excludedScene
            );
            appendCanonical(canonical, hash);
        } catch (org.json.JSONException e) {
            throw new IllegalStateException(
                "could not canonicalize context content hash",
                e
            );
        }
        return sha256Hex(
            canonical.toString().getBytes(StandardCharsets.UTF_8)
        );
    }

    private static String sha256Hex(byte[] bytes) {
        if (bytes == null) {
            throw new IllegalArgumentException("bytes cannot be null");
        }

        final byte[] digest;
        try {
            digest = MessageDigest.getInstance("SHA-256").digest(bytes);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(
                "SHA-256 is unavailable",
                e
            );
        }

        char[] encoded = new char[digest.length * 2];
        for (int index = 0; index < digest.length; index++) {
            int value = digest[index] & 0xff;
            encoded[index * 2] = HEX_DIGITS[value >>> 4];
            encoded[index * 2 + 1] = HEX_DIGITS[value & 0x0f];
        }
        return new String(encoded);
    }

    private static JSONObject hashObject(
        JSONObject context,
        String targetLang,
        String cutoffEntryId,
        String excludedScene
    ) throws org.json.JSONException {
        JSONObject hash = new JSONObject();
        hash.put("id", context.optString("id", ""));

        JSONObject hashManualDescriptions = new JSONObject();
        ManualDescriptionResolver.Resolved manual =
            ManualDescriptionResolver.resolve(context, targetLang);
        if (manual != null) {
            hashManualDescriptions.put(
                "language",
                manual.language
            );
            hashManualDescriptions.put("text", manual.text);
        }
        hash.put("manual_descriptions", hashManualDescriptions);

        JSONArray scenes = context.optJSONArray("scenes");
        JSONArray hashScenes = new JSONArray();
        if (scenes != null) {
            int end = scenes.length();
            if (cutoffEntryId != null && !cutoffEntryId.trim().isEmpty()) {
                int cutoffIndex = findEntryIndex(scenes, cutoffEntryId);
                if (cutoffIndex < 0) {
                    throw new IllegalArgumentException(
                        "cutoff entry is not in context scenes: "
                            + cutoffEntryId
                    );
                }
                end = cutoffIndex + 1;
            }
            for (int index = 0; index < end; index++) {
                JSONObject entry = scenes.optJSONObject(index);
                if (entry == null) {
                    continue;
                }
                JSONObject hashEntry = new JSONObject();
                hashEntry.put("entry_id", entry.optString("entry_id", ""));
                hashEntry.put("scene", entry.optString("scene", ""));
                JSONObject summaries = entry.optJSONObject("summaries");
                JSONObject hashSummaries = new JSONObject();
                if (excludedScene == null
                    || !excludedScene.equals(entry.optString("scene", ""))) {
                    if (summaries != null) {
                        SceneSummaryResolver.Resolved resolved =
                            SceneSummaryResolver.resolve(summaries, targetLang);
                        if (resolved != null) {
                            JSONObject hashSummary = new JSONObject();
                            hashSummary.put("text", resolved.text);
                            hashSummaries.put(resolved.language, hashSummary);
                        }
                    }
                }
                hashEntry.put("summaries", hashSummaries);
                hashScenes.put(hashEntry);
            }
        }
        hash.put("scenes", hashScenes);
        return hash;
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

    private static void appendCanonical(StringBuilder out, Object value)
        throws org.json.JSONException {
        if (value == null || value == JSONObject.NULL) {
            out.append("null");
            return;
        }
        if (value instanceof JSONObject) {
            JSONObject object = (JSONObject) value;
            List<String> keys = new ArrayList<>();
            Iterator<String> iterator = object.keys();
            while (iterator.hasNext()) {
                keys.add(iterator.next());
            }
            Collections.sort(keys);
            out.append('{');
            for (int index = 0; index < keys.size(); index++) {
                if (index > 0) {
                    out.append(',');
                }
                quote(out, keys.get(index));
                out.append(':');
                appendCanonical(out, object.opt(keys.get(index)));
            }
            out.append('}');
            return;
        }
        if (value instanceof JSONArray) {
            JSONArray array = (JSONArray) value;
            out.append('[');
            for (int index = 0; index < array.length(); index++) {
                if (index > 0) {
                    out.append(',');
                }
                appendCanonical(out, array.opt(index));
            }
            out.append(']');
            return;
        }
        if (value instanceof String) {
            quote(out, (String) value);
            return;
        }
        if (value instanceof Number || value instanceof Boolean) {
            out.append(value.toString());
            return;
        }
        quote(out, String.valueOf(value));
    }

    private static void quote(StringBuilder out, String value) {
        out.append('"');
        for (int index = 0; index < value.length(); index++) {
            char c = value.charAt(index);
            switch (c) {
                case '"':
                    out.append("\\\"");
                    break;
                case '\\':
                    out.append("\\\\");
                    break;
                case '\n':
                    out.append("\\n");
                    break;
                case '\r':
                    out.append("\\r");
                    break;
                case '\t':
                    out.append("\\t");
                    break;
                default:
                    if (c < 0x20) {
                        out.append(String.format("\\u%04x", (int) c));
                    } else {
                        out.append(c);
                    }
            }
        }
        out.append('"');
    }
}
