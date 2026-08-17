package com.quarty.housamoembedtrans.storage;

import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;

/** Selects the same effective manual description for every summary consumer. */
public final class ManualDescriptionResolver {
    public static final class Resolved {
        public final String language;
        public final String text;

        private Resolved(String language, String text) {
            this.language = language;
            this.text = text;
        }
    }

    private ManualDescriptionResolver() {
        throw new AssertionError("No instances");
    }

    /**
     * Uses the requested language when it contains non-empty text. If it does
     * not, selects the most recently updated non-empty description, breaking
     * timestamp ties by language name for deterministic hashing.
     */
    public static Resolved resolve(JSONObject context, String targetLang) {
        if (context == null || targetLang == null || targetLang.trim().isEmpty()) {
            return null;
        }
        JSONObject descriptions = context.optJSONObject("manual_descriptions");
        if (descriptions == null) {
            return null;
        }
        JSONObject preferred = descriptions.optJSONObject(targetLang);
        String preferredText = text(preferred);
        if (preferredText != null) {
            return new Resolved(targetLang, preferredText);
        }

        List<String> languages = new ArrayList<>();
        Iterator<String> keys = descriptions.keys();
        while (keys.hasNext()) {
            languages.add(keys.next());
        }
        Collections.sort(languages);
        String bestLanguage = null;
        String bestText = null;
        long bestUpdatedAt = Long.MIN_VALUE;
        for (String language : languages) {
            JSONObject description = descriptions.optJSONObject(language);
            String candidate = text(description);
            if (candidate == null) {
                continue;
            }
            long updatedAt = description.optLong("updated_at", 0L);
            if (bestLanguage == null || updatedAt > bestUpdatedAt) {
                bestLanguage = language;
                bestText = candidate;
                bestUpdatedAt = updatedAt;
            }
        }
        return bestLanguage == null ? null : new Resolved(bestLanguage, bestText);
    }

    public static String resolveText(JSONObject context, String targetLang) {
        Resolved resolved = resolve(context, targetLang);
        return resolved == null ? null : resolved.text;
    }

    private static String text(JSONObject description) {
        if (description == null) {
            return null;
        }
        String value = description.optString("text", "").trim();
        return value.isEmpty() ? null : value;
    }
}
