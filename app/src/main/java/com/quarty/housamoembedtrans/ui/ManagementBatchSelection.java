package com.quarty.housamoembedtrans.ui;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.LinkedHashMap;

import org.json.JSONObject;

/**
 * Process-local selection shared by every management tab.
 *
 * <p>The durable identity is always the wire-safe {@code kind:id} key.  The
 * store contains only immutable JSON payload copies and no Activity or View
 * references, so a host can be destroyed/recreated while the selection and an
 * in-flight export remain usable.  Candidates are refreshed from the owning
 * stores whenever batch mode starts; stale keys are removed only for the host
 * kind whose complete snapshot was refreshed.</p>
 */
public final class ManagementBatchSelection {
    private static final Object LOCK = new Object();
    private static final LinkedHashSet<String> SELECTED =
        new LinkedHashSet<>();
    /**
     * A process-local, immutable payload cache for selected rows.  Activities
     * are routinely recreated while a document picker is open, so the batch
     * transaction cannot depend on a ListView row or an Activity instance.
     */
    private static final LinkedHashMap<String, Entry> CATALOG =
        new LinkedHashMap<>();

    private ManagementBatchSelection() {
    }

    public static boolean contains(String key) {
        synchronized (LOCK) {
            return SELECTED.contains(key);
        }
    }

    public static void set(String key, boolean selected) {
        if (key == null || key.trim().isEmpty()) {
            return;
        }
        synchronized (LOCK) {
            if (selected) {
                SELECTED.add(key);
            } else {
                SELECTED.remove(key);
            }
        }
    }

    public static void register(
        String kind,
        String canonicalId,
        String label,
        JSONObject payload
    ) {
        if (kind == null || canonicalId == null
            || kind.trim().isEmpty() || canonicalId.trim().isEmpty()) {
            return;
        }
        Entry entry = new Entry(kind, canonicalId, label, payload);
        synchronized (LOCK) {
            CATALOG.put(entry.key(), entry);
        }
    }

    public static Entry entry(String key) {
        synchronized (LOCK) {
            return CATALOG.get(key);
        }
    }

    public static List<Entry> selectedEntries() {
        synchronized (LOCK) {
            List<Entry> output = new ArrayList<>();
            for (String key : SELECTED) {
                Entry entry = CATALOG.get(key);
                if (entry != null) {
                    output.add(entry);
                }
            }
            return output;
        }
    }

    public static void selectAll(Collection<String> keys) {
        if (keys == null || keys.isEmpty()) {
            return;
        }
        synchronized (LOCK) {
            for (String key : keys) {
                if (key != null && !key.trim().isEmpty()) {
                    SELECTED.add(key);
                }
            }
        }
    }

    public static void removeAll(Collection<String> keys) {
        if (keys == null || keys.isEmpty()) {
            return;
        }
        synchronized (LOCK) {
            SELECTED.removeAll(keys);
        }
    }

    /** Clears the global selection, including selections from other tabs. */
    public static void clear() {
        synchronized (LOCK) {
            SELECTED.clear();
            CATALOG.clear();
        }
    }

    public static List<String> snapshot() {
        synchronized (LOCK) {
            return new ArrayList<>(SELECTED);
        }
    }

    /** Retains only keys present in a complete live candidate snapshot. */
    public static void retainAll(Collection<String> liveKeys) {
        synchronized (LOCK) {
            if (liveKeys == null) {
                SELECTED.clear();
            } else {
                SELECTED.retainAll(liveKeys);
            }
        }
    }

    /**
     * Prunes only one kind.  A host is allowed to expose its current tab's
     * complete snapshot without deleting selections made in another host.
     */
    public static void retainKindAll(
        String kind,
        Collection<String> liveKeys
    ) {
        synchronized (LOCK) {
            if (kind == null || kind.trim().isEmpty()) {
                return;
            }
            LinkedHashSet<String> live = new LinkedHashSet<>();
            if (liveKeys != null) {
                live.addAll(liveKeys);
            }
            SELECTED.removeIf(key -> {
                if (!key.startsWith(kind + ":")) {
                    return false;
                }
                return !live.contains(key);
            });
            CATALOG.entrySet().removeIf(entry ->
                entry.getKey().startsWith(kind + ":")
                    && !live.contains(entry.getKey())
            );
        }
    }

    /** Immutable row payload retained independently of any Activity. */
    public static final class Entry {
        public final String kind;
        public final String canonicalId;
        public final String label;
        public final JSONObject payload;

        private Entry(
            String kind,
            String canonicalId,
            String label,
            JSONObject payload
        ) {
            this.kind = kind;
            this.canonicalId = canonicalId;
            this.label = label == null || label.trim().isEmpty()
                ? canonicalId
                : label;
            JSONObject copy = payload == null ? new JSONObject() : payload;
            try {
                this.payload = new JSONObject(copy.toString());
            } catch (Exception error) {
                throw new IllegalArgumentException(
                    "batch item payload is not JSON",
                    error
                );
            }
        }

        public String key() {
            return kind + ":" + canonicalId;
        }
    }
}
