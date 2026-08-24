package com.quarty.housamoembedtrans.context.history;

import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;

/** Resolves the one Scene Summary language used by hashes and API inputs. */
public final class SceneSummaryResolver {
    public static final class Resolved {
        public final String language;
        public final String text;
        public final long updatedAt;

        private Resolved(String language, String text, long updatedAt) {
            this.language = language;
            this.text = text;
            this.updatedAt = updatedAt;
        }
    }

    private SceneSummaryResolver() {
        throw new AssertionError("No instances");
    }

    /** Target language wins when it has non-empty text; otherwise latest text. */
    public static Resolved resolve(
        JSONObject summaries,
        String targetLang
    ) {
        if (summaries == null || targetLang == null || targetLang.trim().isEmpty()) {
            return null;
        }
        JSONObject preferred = summaries.optJSONObject(targetLang);
        Resolved preferredResult = from(targetLang, preferred);
        if (preferredResult != null) {
            return preferredResult;
        }
        List<String> languages = new ArrayList<>();
        Iterator<String> keys = summaries.keys();
        while (keys.hasNext()) {
            languages.add(keys.next());
        }
        Collections.sort(languages);
        Resolved best = null;
        for (String language : languages) {
            Resolved candidate = from(
                language,
                summaries.optJSONObject(language)
            );
            if (candidate == null) {
                continue;
            }
            if (best == null
                || candidate.updatedAt > best.updatedAt
                || (candidate.updatedAt == best.updatedAt
                    && candidate.language.compareTo(best.language) < 0)) {
                best = candidate;
            }
        }
        return best;
    }

    private static Resolved from(String language, JSONObject value) {
        if (value == null) {
            return null;
        }
        String text = value.optString("text", "").trim();
        return text.isEmpty()
            ? null
            : new Resolved(
                language,
                text,
                value.optLong("updated_at", -1L)
            );
    }
}
