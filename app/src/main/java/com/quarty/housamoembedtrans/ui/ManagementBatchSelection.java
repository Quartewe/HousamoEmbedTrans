package com.quarty.housamoembedtrans.ui;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.LinkedHashMap;

import org.json.JSONObject;

/**
 * Process-local selection shared by every real management tab.
 *
 * <p>The durable identity is always the wire-safe {@code kind:id} key.  The
 * store contains only immutable JSON payload copies and no Activity or View
 * references, so a host can be destroyed/recreated while the selection and an
 * in-flight export remain usable.  Candidates are refreshed from the owning
 * stores whenever batch mode starts; stale keys are removed only for the host
 * kind whose complete snapshot was refreshed.</p>
 */
public final class ManagementBatchSelection {
    private static final Session GLOBAL = new Session();

    private ManagementBatchSelection() {
    }

    /** Returns the unchanged process-wide selection used by real pages. */
    public static Session globalSession() {
        return GLOBAL;
    }

    /** Creates an isolated in-memory selection for a style preview. */
    public static Session newSession() {
        return new Session();
    }

    public static boolean contains(String key) {
        return GLOBAL.contains(key);
    }

    public static void set(String key, boolean selected) {
        GLOBAL.set(key, selected);
    }

    public static void register(
        String kind,
        String canonicalId,
        String label,
        JSONObject payload
    ) {
        GLOBAL.register(kind, canonicalId, label, payload);
    }

    public static Entry entry(String key) {
        return GLOBAL.entry(key);
    }

    public static List<Entry> selectedEntries() {
        return GLOBAL.selectedEntries();
    }

    public static void selectAll(Collection<String> keys) {
        GLOBAL.selectAll(keys);
    }

    public static void removeAll(Collection<String> keys) {
        GLOBAL.removeAll(keys);
    }

    public static void clear() {
        GLOBAL.clear();
    }

    public static List<String> snapshot() {
        return GLOBAL.snapshot();
    }

    public static void retainAll(Collection<String> liveKeys) {
        GLOBAL.retainAll(liveKeys);
    }

    public static void retainKindAll(
        String kind,
        Collection<String> liveKeys
    ) {
        GLOBAL.retainKindAll(kind, liveKeys);
    }

    /** Isolated process-local selection with the same semantics as global. */
    public static final class Session {
        private final Object lock = new Object();
        private final LinkedHashSet<String> selected = new LinkedHashSet<>();
        /**
         * A process-local, immutable payload cache for selected rows.  Hosts
         * are routinely recreated while a document picker is open, so the
         * batch transaction cannot depend on a ListView row or Activity.
         */
        private final LinkedHashMap<String, Entry> catalog =
            new LinkedHashMap<>();

        public boolean contains(String key) {
            synchronized (lock) {
                return selected.contains(key);
            }
        }

        public void set(String key, boolean selectedValue) {
            if (key == null || key.trim().isEmpty()) {
                return;
            }
            synchronized (lock) {
                if (selectedValue) {
                    selected.add(key);
                } else {
                    selected.remove(key);
                }
            }
        }

        public void register(
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
            synchronized (lock) {
                catalog.put(entry.key(), entry);
            }
        }

        public Entry entry(String key) {
            synchronized (lock) {
                return catalog.get(key);
            }
        }

        public List<Entry> selectedEntries() {
            synchronized (lock) {
                List<Entry> output = new ArrayList<>();
                for (String key : selected) {
                    Entry entry = catalog.get(key);
                    if (entry != null) {
                        output.add(entry);
                    }
                }
                return output;
            }
        }

        public void selectAll(Collection<String> keys) {
            if (keys == null || keys.isEmpty()) {
                return;
            }
            synchronized (lock) {
                for (String key : keys) {
                    if (key != null && !key.trim().isEmpty()) {
                        selected.add(key);
                    }
                }
            }
        }

        public void removeAll(Collection<String> keys) {
            if (keys == null || keys.isEmpty()) {
                return;
            }
            synchronized (lock) {
                selected.removeAll(keys);
            }
        }

        public void clear() {
            synchronized (lock) {
                selected.clear();
                catalog.clear();
            }
        }

        public List<String> snapshot() {
            synchronized (lock) {
                return new ArrayList<>(selected);
            }
        }

        public void retainAll(Collection<String> liveKeys) {
            synchronized (lock) {
                if (liveKeys == null) {
                    selected.clear();
                } else {
                    selected.retainAll(liveKeys);
                }
            }
        }

        /** Retains only keys present in a complete live candidate snapshot. */
        public void retainKindAll(
            String kind,
            Collection<String> liveKeys
        ) {
            synchronized (lock) {
                if (kind == null || kind.trim().isEmpty()) {
                    return;
                }
                LinkedHashSet<String> live = new LinkedHashSet<>();
                if (liveKeys != null) {
                    live.addAll(liveKeys);
                }
                selected.removeIf(key -> {
                    if (!key.startsWith(kind + ":")) {
                        return false;
                    }
                    return !live.contains(key);
                });
                catalog.entrySet().removeIf(entry ->
                    entry.getKey().startsWith(kind + ":")
                        && !live.contains(entry.getKey())
                );
            }
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
