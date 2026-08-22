package com.quarty.housamoembedtrans.storage;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.Set;

/** Collects every language represented by one Context's semantic facts. */
public final class ContextFactLanguages {

    private ContextFactLanguages() {
        throw new AssertionError("No instances");
    }

    /**
     * Returns languages from Scene summaries, manual descriptions and existing
     * derived/manual Context summaries.  The result is detached and stable in
     * first-observed order.
     */
    public static Set<String> collect(JSONObject context) {
        Set<String> languages = new LinkedHashSet<>();
        if (context == null) {
            return languages;
        }
        addKeys(languages, context.optJSONObject("manual_descriptions"));
        addKeys(languages, context.optJSONObject("summary"));
        JSONArray scenes = context.optJSONArray("scenes");
        if (scenes == null) {
            return languages;
        }
        for (int index = 0; index < scenes.length(); index++) {
            JSONObject entry = scenes.optJSONObject(index);
            if (entry != null) {
                addKeys(languages, entry.optJSONObject("summaries"));
            }
        }
        return languages;
    }

    private static void addKeys(Set<String> target, JSONObject object) {
        if (object == null) {
            return;
        }
        Iterator<String> keys = object.keys();
        while (keys.hasNext()) {
            String language = keys.next();
            if (language != null && !language.trim().isEmpty()) {
                target.add(language);
            }
        }
    }
}
