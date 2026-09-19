package com.quarty.housamoembedtrans.scene.store;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Merges a validated result into a private Scene document, never replacing newer values. */
final class SceneTranslationResultApplier {
    private SceneTranslationResultApplier() { }

    static JSONObject apply(JSONObject scene, JSONObject result) throws Exception {
        JSONObject output = new JSONObject(scene.toString());
        String language = result.getString("target_lang");
        List<JSONObject> texts = new ArrayList<>();
        collect(output.getJSONArray("scene_items"), texts);
        JSONArray mappings = output.getJSONArray("seq_to_order");
        JSONArray translations = result.getJSONArray("translations");
        if (texts.size() != mappings.length() || texts.size() != translations.length()) {
            throw new IllegalArgumentException("Scene/result text count mismatch");
        }
        Map<Integer, String> values = new HashMap<>();
        for (int i = 0; i < translations.length(); i++) {
            JSONObject value = translations.getJSONObject(i);
            int seq = value.getInt("seq");
            if (seq < 1 || seq > texts.size() || values.put(seq, value.getString("text")) != null) {
                throw new IllegalArgumentException("Invalid or duplicate result seq " + seq);
            }
        }
        JSONArray protect = output.getJSONArray("protect");
        boolean complete = output.getJSONObject("translated").optBoolean(language, false);
        for (int i = 0; i < texts.size(); i++) {
            JSONObject item = texts.get(i);
            JSONObject mapping = mappings.getJSONObject(i);
            if (mapping.getInt("seq") != i + 1
                || !sameOrder(mapping.getJSONObject("order"), item.getJSONObject("order"))) {
                throw new IllegalArgumentException("Scene order mismatch at seq " + (i + 1));
            }
            String text = values.get(i + 1);
            // Match the native codec: restore only tokens owned by this text's OrderKey.
            for (int p = 0; p < protect.length(); p++) {
                JSONObject token = protect.getJSONObject(p);
                if (sameOrder(token.getJSONObject("order"), item.getJSONObject("order"))) {
                    String label = token.getString("label");
                    int offset = text.indexOf(label);
                    if (label.isEmpty() || offset < 0) {
                        throw new IllegalArgumentException("Missing protected token at seq " + (i + 1));
                    }
                    text = text.substring(0, offset) + token.getString("origin")
                        + text.substring(offset + label.length());
                }
            }
            mergeValue(item.getJSONObject("translations"), language, text, complete);
        }
        for (String field : new String[] {"summary", "provider", "model"}) {
            mergeValue(output.getJSONObject(field), language, result.getString(field), complete);
        }
        output.getJSONObject("translated").put(language, true);
        output.remove("target_lang");
        return output;
    }

    private static void mergeValue(JSONObject map, String language, String value, boolean complete)
        throws Exception {
        if (value.isEmpty()) {
            throw new IllegalArgumentException("Empty translation value");
        }
        Object old = map.opt(language);
        if ((old != null && !value.equals(old)) || (complete && old == null)) {
            throw new IllegalStateException("Scene target language changed; retained result was not applied");
        }
        map.put(language, value);
    }

    private static boolean sameOrder(JSONObject left, JSONObject right) throws Exception {
        for (String key : new String[] {"label_index", "page_no", "cmd_index", "sub_index"}) {
            if (left.getInt(key) != right.getInt(key)) {
                return false;
            }
        }
        return true;
    }

    private static void collect(JSONArray items, List<JSONObject> texts) throws Exception {
        for (int i = 0; i < items.length(); i++) {
            JSONObject item = items.getJSONObject(i);
            switch (item.getString("type")) {
                case "text":
                    texts.add(item);
                    break;
                case "if":
                    collect(item.getJSONArray("following_text"), texts);
                    break;
                case "choice":
                    JSONArray branches = item.getJSONArray("branches");
                    for (int b = 0; b < branches.length(); b++) {
                        JSONObject branch = branches.getJSONObject(b);
                        collect(branch.getJSONArray("options"), texts);
                        collect(branch.getJSONArray("following_text"), texts);
                    }
                    break;
                default:
                    throw new IllegalArgumentException("Unknown Scene item type");
            }
        }
    }
}
