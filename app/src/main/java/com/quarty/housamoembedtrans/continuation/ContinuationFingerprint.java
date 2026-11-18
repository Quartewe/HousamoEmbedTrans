package com.quarty.housamoembedtrans.continuation;

import com.quarty.housamoembedtrans.scene.store.SceneDigest;
import com.quarty.housamoembedtrans.translation.request.SceneTranslationRequestBuilder;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/** Original and localized identities for one saved Scene, including readable pre-normalization records. */
public final class ContinuationFingerprint {
    final String captured;
    final String original;
    final JSONObject proof;
    private final JSONObject scene;
    private String legacyCaptured;

    ContinuationFingerprint(JSONObject scene) throws Exception {
        this.scene = scene;
        captured = captured(scene, false);
        JSONObject candidate = scene.optJSONObject("continuation_source");
        String view = candidate == null ? "" : candidate.optString("captured_hash");
        proof = candidate != null && candidate.optString("original_hash").matches("[0-9a-f]{64}") && matchesCaptured(view)
            ? candidate : null;
        original = proof == null ? captured : proof.getString("original_hash");
    }

    boolean matchesOriginal(String hash) throws Exception {
        if (original.equals(hash)) return true;
        if (proof == null) return legacyCaptured().equals(hash);
        return hash.equals(proof.optString("legacy_original_hash")) && hash.matches("[0-9a-f]{64}");
    }

    boolean matchesCaptured(String hash) throws Exception {
        return captured.equals(hash) || legacyCaptured().equals(hash);
    }

    private synchronized String legacyCaptured() throws Exception {
        if (legacyCaptured == null) legacyCaptured = captured(scene, true);
        return legacyCaptured;
    }

    static String order(JSONObject position) throws JSONException {
        return position.getInt("label_index") + "/" + position.getInt("page_no") + "/"
            + position.getInt("cmd_index") + "/" + position.getInt("sub_index");
    }

    // The game hashes captured rows directly, even when the saved file carries an older proof.
    public static String captured(JSONObject scene, boolean legacy) throws Exception {
        JSONArray items = new JSONArray(scene.getJSONArray("scene_items").toString());
        JSONArray protect = scene.getJSONArray("protect");
        Map<String, List<JSONObject>> byOrder = new HashMap<>();
        if (!legacy) {
            for (int i = 0; i < protect.length(); i++) {
                JSONObject token = protect.getJSONObject(i);
                String order = order(token.getJSONObject("order"));
                byOrder.computeIfAbsent(order, ignored -> new ArrayList<>()).add(token);
            }
        }
        normalize(items, byOrder);
        JSONObject source = new JSONObject().put("scene_items", items);
        if (legacy) source.put("protect", protect);
        return SceneDigest.lowerHex(SceneDigest.sha256(SceneTranslationRequestBuilder.toCanonicalBytes(source)));
    }

    private static void normalize(Object value, Map<String, List<JSONObject>> byOrder) throws JSONException {
        if (value instanceof JSONArray) {
            JSONArray array = (JSONArray) value;
            for (int i = 0; i < array.length(); i++) normalize(array.get(i), byOrder);
        } else if (value instanceof JSONObject) {
            JSONObject item = (JSONObject) value;
            item.remove("translations");
            if (!byOrder.isEmpty() && item.has("text") && item.has("order")) {
                List<JSONObject> tokens = byOrder.get(order(item.getJSONObject("order")));
                if (tokens != null) item.put("text", restoredText(item.getString("text"), tokens));
            }
            Iterator<String> keys = item.keys();
            while (keys.hasNext()) normalize(item.get(keys.next()), byOrder);
        }
    }

    private static String restoredText(String text, List<JSONObject> tokens) throws JSONException {
        // Locate each tag in the original string. A literal inside an inserted tag is never expanded again.
        TreeMap<Integer, JSONObject> positions = new TreeMap<>();
        for (JSONObject token : tokens) {
            String label = token.getString("label");
            int start = text.indexOf(label);
            if (start >= 0 && (label.isEmpty() || positions.put(start, token) != null))
                throw new JSONException("同一行的保护标记位置重叠");
        }
        StringBuilder restored = new StringBuilder(text.length());
        int from = 0;
        for (Map.Entry<Integer, JSONObject> entry : positions.entrySet()) {
            int start = entry.getKey();
            if (start < from) throw new JSONException("同一行的保护标记位置重叠");
            JSONObject token = entry.getValue();
            restored.append(text, from, start).append(token.getString("origin"));
            from = start + token.getString("label").length();
        }
        return restored.append(text, from, text.length()).toString();
    }
}
