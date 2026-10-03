package com.quarty.housamoembedtrans.context.history;

import com.quarty.housamoembedtrans.util.NaturalStringComparator;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

/**
 * Builds an in-memory natural-order view of Context Scene entries.
 *
 * <p>The persisted {@code scenes} array is never changed. Callers can choose
 * a physical range first and sort only that range, which keeps existing
 * cutoff coverage and source-hash semantics intact for compression inputs.</p>
 */
public final class SceneEntryOrdering {

    private static final Comparator<IndexedEntry> ENTRY_COMPARATOR =
        (left, right) -> {
            int nameOrder = NaturalStringComparator.INSTANCE.compare(
                sceneName(left.entry),
                sceneName(right.entry)
            );
            return nameOrder != 0
                ? nameOrder
                : Integer.compare(left.originalIndex, right.originalIndex);
        };

    private SceneEntryOrdering() {
        throw new AssertionError("No instances");
    }

    /** Returns all entries in Scene-name natural order without mutating input. */
    public static List<JSONObject> sortedEntries(JSONArray scenes) {
        return sortedEntries(scenes, 0, scenes == null ? 0 : scenes.length());
    }

    /**
     * Returns a copy of the physical half-open range in natural order.
     *
     * <p>The range is deliberately selected before sorting. This is used for
     * existing summary cutoffs, where changing the covered member set would
     * change the persisted summary's meaning.</p>
     */
    public static List<JSONObject> sortedEntries(
        JSONArray scenes,
        int start,
        int endExclusive
    ) {
        if (scenes == null) {
            return new ArrayList<>();
        }
        if (start < 0
            || endExclusive < start
            || endExclusive > scenes.length()) {
            throw new IllegalArgumentException("invalid scene range");
        }
        List<IndexedEntry> indexed = new ArrayList<>();
        for (int index = start; index < endExclusive; index++) {
            indexed.add(new IndexedEntry(scenes.optJSONObject(index), index));
        }
        Collections.sort(indexed, ENTRY_COMPARATOR);
        List<JSONObject> result = new ArrayList<>(indexed.size());
        for (IndexedEntry item : indexed) {
            result.add(item.entry);
        }
        return result;
    }

    public static int indexOfScene(List<JSONObject> scenes, String scene) {
        if (scenes == null || scene == null) {
            return -1;
        }
        for (int index = 0; index < scenes.size(); index++) {
            JSONObject entry = scenes.get(index);
            if (entry != null && scene.equals(sceneName(entry))) {
                return index;
            }
        }
        return -1;
    }

    public static int indexOfEntryId(List<JSONObject> scenes, String entryId) {
        if (scenes == null || entryId == null || entryId.isEmpty()) {
            return -1;
        }
        for (int index = 0; index < scenes.size(); index++) {
            JSONObject entry = scenes.get(index);
            if (entry != null
                && entryId.equals(entry.optString("entry_id", ""))) {
                return index;
            }
        }
        return -1;
    }

    private static String sceneName(JSONObject entry) {
        return entry == null ? "" : entry.optString("scene", "");
    }

    private static final class IndexedEntry {
        private final JSONObject entry;
        private final int originalIndex;

        private IndexedEntry(JSONObject entry, int originalIndex) {
            this.entry = entry;
            this.originalIndex = originalIndex;
        }
    }
}
