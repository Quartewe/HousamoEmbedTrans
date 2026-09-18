package com.quarty.housamoembedtrans.ui;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;

/**
 * UI-only projection and draft holder for translated Scene text.
 *
 * <p>The editor never mutates the source Scene JSON.  Each patch keeps the
 * JSON path and original value so a persistence layer can perform its own
 * expected-value check later.</p>
 */
final class SceneTranslationEditor {
    private static final String KEY_SEPARATOR = "\u0000";

    private final JSONObject originalScene;
    private final List<Card> cards;
    private final LinkedHashMap<String, Translation> translations =
        new LinkedHashMap<>();
    private final LinkedHashMap<String, String> drafts = new LinkedHashMap<>();
    private final List<Patch> restoreConflicts = new ArrayList<>();

    SceneTranslationEditor(
        JSONObject source,
        Map<String, Integer> sequenceByOrder
    ) {
        try {
            originalScene = source == null
                ? new JSONObject()
                : new JSONObject(source.toString());
        } catch (JSONException error) {
            throw new IllegalArgumentException("Scene JSON cannot be copied", error);
        }
        List<Card> roots = new ArrayList<>();
        int[] fallbackCounter = new int[] {0};
        appendItems(
            originalScene.optJSONArray("scene_items"),
            "scene_items",
            roots,
            fallbackCounter,
            sequenceByOrder
        );
        cards = Collections.unmodifiableList(roots);
    }

    List<Card> cards() {
        return cards;
    }

    List<String> languages() {
        LinkedHashSet<String> result = new LinkedHashSet<>();
        for (Translation translation : translations.values()) {
            result.add(translation.language);
        }
        return Collections.unmodifiableList(new ArrayList<>(result));
    }

    String draft(String path, String language) {
        Translation translation = translations.get(key(path, language));
        if (translation == null) return null;
        String draft = drafts.get(key(path, language));
        return draft == null ? translation.value : draft;
    }

    boolean hasTranslation(String path, String language) {
        return translations.containsKey(key(path, language));
    }

    boolean setDraft(String path, String language, String value) {
        String normalizedPath = path == null ? "" : path;
        String normalizedLanguage = language == null ? "" : language;
        Translation translation = translations.get(key(normalizedPath, normalizedLanguage));
        if (translation == null) return false;
        String next = value == null ? "" : value;
        String identity = key(normalizedPath, normalizedLanguage);
        if (translation.value.equals(next)) {
            drafts.remove(identity);
        } else {
            drafts.put(identity, next);
        }
        for (int index = restoreConflicts.size() - 1; index >= 0; index--) {
            Patch conflict = restoreConflicts.get(index);
            if (identity.equals(key(conflict.path, conflict.language))) {
                restoreConflicts.remove(index);
            }
        }
        return true;
    }

    boolean isDirty() {
        return !drafts.isEmpty() || !restoreConflicts.isEmpty();
    }

    void reset() {
        drafts.clear();
        restoreConflicts.clear();
    }

    List<Patch> restoreConflicts() {
        return Collections.unmodifiableList(new ArrayList<>(restoreConflicts));
    }

    List<Patch> changedPatches() {
        List<Patch> result = new ArrayList<>();
        for (Map.Entry<String, Translation> entry : translations.entrySet()) {
            String draft = drafts.get(entry.getKey());
            if (draft == null) continue;
            Translation translation = entry.getValue();
            result.add(new Patch(
                translation.path,
                translation.language,
                translation.value,
                translation.sourceExpected,
                translation.orderIdentity,
                draft
            ));
        }
        return Collections.unmodifiableList(result);
    }

    /** Serializes only local drafts; the source snapshot is not serialized. */
    String saveState() {
        JSONArray values = new JSONArray();
        List<Patch> pending = new ArrayList<>(changedPatches());
        pending.addAll(restoreConflicts);
        for (Patch patch : pending) {
            JSONObject value = new JSONObject();
            try {
                value.put("path", patch.path);
                value.put("language", patch.language);
                value.put("expected", patch.expected);
                value.put("source", patch.sourceExpected);
                value.put("order", patch.orderIdentity);
                value.put("value", patch.value);
                values.put(value);
            } catch (JSONException impossible) {
                // JSONObject.put only rejects an invalid key, which constants
                // and the validated patch fields cannot produce.
            }
        }
        return values.toString();
    }

    /** Restores only patches that still match this Scene snapshot. */
    void restoreState(String encoded) {
        drafts.clear();
        restoreConflicts.clear();
        if (encoded == null || encoded.trim().isEmpty()) return;
        try {
            JSONArray values = new JSONArray(encoded);
            for (int index = 0; index < values.length(); index++) {
                JSONObject value = values.optJSONObject(index);
                if (value == null
                    || !value.has("path")
                    || !value.has("language")
                    || !value.has("expected")
                    || !value.has("value")) {
                    continue;
                }
                String path = value.optString("path", "");
                String language = value.optString("language", "");
                String expected = value.optString("expected", "");
                String sourceExpected = value.optString("source", "");
                String orderIdentity = value.optString("order", "");
                String draft = value.optString("value", "");
                boolean hasSourceExpected = value.has("source");
                boolean hasOrderLabel = value.has("order");
                Translation translation = translations.get(key(path, language));
                if (translation == null
                    || !translation.value.equals(expected)
                    || (hasSourceExpected
                        && !translation.sourceExpected.equals(sourceExpected))
                    || (hasOrderLabel
                        && !translation.orderIdentity.equals(orderIdentity))) {
                    restoreConflicts.add(new Patch(
                        path,
                        language,
                        expected,
                        sourceExpected,
                        orderIdentity,
                        draft
                    ));
                    continue;
                }
                if (!translation.value.equals(draft)) {
                    drafts.put(key(path, language), draft);
                }
            }
        } catch (JSONException ignored) {
            // A stale or malformed rotation bundle must not break the editor.
        }
    }

    private void appendItems(
        JSONArray items,
        String basePath,
        List<Card> output,
        int[] fallbackCounter,
        Map<String, Integer> sequenceByOrder
    ) {
        for (int index = 0; items != null && index < items.length(); index++) {
            JSONObject item = items.optJSONObject(index);
            if (item == null) continue;
            appendItem(
                item,
                basePath + "[" + index + "]",
                output,
                fallbackCounter,
                sequenceByOrder
            );
        }
    }

    private void appendItem(
        JSONObject item,
        String path,
        List<Card> output,
        int[] fallbackCounter,
        Map<String, Integer> sequenceByOrder
    ) {
        String type = item.optString("type", "text").trim();
        String orderLabel = SceneManagementDetailData.orderLabel(
            item,
            fallbackCounter[0]++,
            sequenceByOrder
        );
        if ("choice".equals(type)) {
            List<Card> children = new ArrayList<>();
            appendDirectOptions(
                item.optJSONArray("options"),
                path + ".options",
                children,
                fallbackCounter,
                sequenceByOrder
            );
            JSONArray branches = item.optJSONArray("branches");
            for (int branchIndex = 0;
                 branches != null && branchIndex < branches.length();
                 branchIndex++) {
                JSONObject branch = branches.optJSONObject(branchIndex);
                if (branch == null) continue;
                List<Card> branchChildren = new ArrayList<>();
                String branchPath = path + ".branches[" + branchIndex + "]";
                appendDirectOptions(
                    branch.optJSONArray("options"),
                    branchPath + ".options",
                    branchChildren,
                    fallbackCounter,
                    sequenceByOrder
                );
                appendItems(
                    branch.optJSONArray("following_text"),
                    branchPath + ".following_text",
                    branchChildren,
                    fallbackCounter,
                    sequenceByOrder
                );
                children.add(new Card(
                    "branch",
                    branchPath,
                    "",
                    "",
                    "",
                    "",
                    branch.optString("target_label", ""),
                    "",
                    Collections.<Translation>emptyList(),
                    branchChildren
                ));
            }
            output.add(new Card(
                "choice",
                path,
                orderLabel,
                "",
                "",
                "",
                "",
                item.optString("merge_label", ""),
                Collections.<Translation>emptyList(),
                children
            ));
            return;
        }
        if ("if".equals(type)) {
            List<Card> children = new ArrayList<>();
            appendItems(
                item.optJSONArray("following_text"),
                path + ".following_text",
                children,
                fallbackCounter,
                sequenceByOrder
            );
            output.add(new Card(
                "if",
                path,
                orderLabel,
                "",
                "",
                item.optString("condition", ""),
                item.optString("target_label", ""),
                item.optString("merge_label", ""),
                Collections.<Translation>emptyList(),
                children
            ));
            return;
        }
        output.add(textCard(
            item,
            path,
            orderLabel,
            "text",
            fallbackCounter,
            sequenceByOrder
        ));
    }

    private void appendDirectOptions(
        JSONArray options,
        String basePath,
        List<Card> output,
        int[] fallbackCounter,
        Map<String, Integer> sequenceByOrder
    ) {
        for (int index = 0; options != null && index < options.length(); index++) {
            JSONObject option = options.optJSONObject(index);
            if (option == null) continue;
            output.add(textCard(
                option,
                basePath + "[" + index + "]",
                SceneManagementDetailData.orderLabel(
                    option,
                    fallbackCounter[0]++,
                    sequenceByOrder
                ),
                "option",
                fallbackCounter,
                sequenceByOrder
            ));
        }
    }

    private Card textCard(
        JSONObject item,
        String path,
        String orderLabel,
        String type,
        int[] fallbackCounter,
        Map<String, Integer> sequenceByOrder
    ) {
        List<Translation> values = new ArrayList<>();
        JSONObject translationsObject = item.optJSONObject("translations");
        if (translationsObject != null) {
            Iterator<String> keys = translationsObject.keys();
            while (keys.hasNext()) {
                String language = keys.next();
                String value = translationsObject.optString(language, "");
                Translation translation = new Translation(
                    path,
                    language,
                    value,
                    item.optString("text", ""),
                    orderIdentity(item)
                );
                values.add(translation);
                translations.put(key(path, language), translation);
            }
        }
        return new Card(
            type,
            path,
            orderLabel,
            item.optString("speaker", ""),
            item.optString("text", ""),
            "",
            "",
            "",
            values,
            Collections.<Card>emptyList()
        );
    }

    private static String key(String path, String language) {
        return (path == null ? "" : path)
            + KEY_SEPARATOR
            + (language == null ? "" : language);
    }

    private static String orderIdentity(JSONObject item) {
        JSONObject order = item == null ? null : item.optJSONObject("order");
        if (order == null) {
            Object value = item == null ? null : item.opt("order");
            return value == null || value == JSONObject.NULL
                ? ""
                : String.valueOf(value);
        }
        return orderPart(order, "label_index")
            + "\u0001"
            + orderPart(order, "page_no")
            + "\u0001"
            + orderPart(order, "cmd_index")
            + "\u0001"
            + orderPart(order, "sub_index");
    }

    private static String orderPart(JSONObject order, String key) {
        return order.has(key) ? String.valueOf(order.opt(key)) : "<missing>";
    }

    static final class Card {
        final String type;
        final String path;
        final String orderLabel;
        final String speaker;
        final String sourceText;
        final String condition;
        final String targetLabel;
        final String mergeLabel;
        final List<Translation> translations;
        final List<Card> children;

        private Card(
            String type,
            String path,
            String orderLabel,
            String speaker,
            String sourceText,
            String condition,
            String targetLabel,
            String mergeLabel,
            List<Translation> translations,
            List<Card> children
        ) {
            this.type = type;
            this.path = path;
            this.orderLabel = orderLabel;
            this.speaker = speaker;
            this.sourceText = sourceText;
            this.condition = condition;
            this.targetLabel = targetLabel;
            this.mergeLabel = mergeLabel;
            this.translations = Collections.unmodifiableList(
                new ArrayList<>(translations)
            );
            this.children = Collections.unmodifiableList(new ArrayList<>(children));
        }

        boolean isText() {
            return "text".equals(type) || "option".equals(type);
        }
    }

    static final class Translation {
        final String path;
        final String language;
        final String value;
        final String sourceExpected;
        final String orderIdentity;

        private Translation(
            String path,
            String language,
            String value,
            String sourceExpected,
            String orderIdentity
        ) {
            this.path = path;
            this.language = language;
            this.value = value;
            this.sourceExpected = sourceExpected;
            this.orderIdentity = orderIdentity;
        }
    }

    static final class Patch {
        final String path;
        final String language;
        final String expected;
        final String sourceExpected;
        final String orderIdentity;
        final String value;

        Patch(
            String path,
            String language,
            String expected,
            String sourceExpected,
            String orderIdentity,
            String value
        ) {
            this.path = path;
            this.language = language;
            this.expected = expected;
            this.sourceExpected = sourceExpected;
            this.orderIdentity = orderIdentity;
            this.value = value;
        }

        String getPath() {
            return path;
        }

        String getLanguage() {
            return language;
        }

        String getExpected() {
            return expected;
        }

        String getSourceExpected() {
            return sourceExpected;
        }

        String getOrderIdentity() {
            return orderIdentity;
        }

        String getValue() {
            return value;
        }
    }
}
