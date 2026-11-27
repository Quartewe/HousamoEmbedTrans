package com.quarty.housamoembedtrans.runtime;

import com.quarty.housamoembedtrans.bridge.HetBridgeContract;

/** VM-owned entry claim shared even when LSP and Root use different ClassLoaders. */
public final class GameEntry {
    private static final String KEY = HetBridgeContract.MODULE_PACKAGE + ".gameEntry";

    private GameEntry() {}

    // Only boot-classpath values cross the loader boundary; System.class is the
    // same lock for every entry ClassLoader in this VM.
    public static boolean claim(String selected, String source, Class<?> owner) {
        synchronized (System.class) {
            Object[] entry = (Object[]) System.getProperties().get(KEY);
            if (entry == null) {
                entry = new Object[] {selected, null};
                System.getProperties().put(KEY, entry);
            }
            if (!source.equals(entry[0])) return false;
            if (entry[1] == null) entry[1] = owner;
            return entry[1] == owner;
        }
    }

    /** Root bootstrap result: zero allows entry, 3 means attached, 4 needs restart. */
    public static int rootDisposition() {
        synchronized (System.class) {
            Object[] entry = (Object[]) System.getProperties().get(KEY);
            if (entry == null) return 0;
            // Both routes share the runtime. An existing owner keeps handling this game.
            if (entry[1] != null) return 3;
            return "root".equals(entry[0]) ? 0 : 4;
        }
    }
}
