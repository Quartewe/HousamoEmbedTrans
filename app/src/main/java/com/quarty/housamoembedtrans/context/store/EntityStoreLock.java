package com.quarty.housamoembedtrans.context.store;

import java.io.File;
import java.io.IOException;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Process-wide locks for one persisted Context or Group entity.
 *
 * <p>Store facades are intentionally short-lived in a few service paths, so a
 * lock owned by a facade instance cannot protect a read-modify-write against
 * another facade.  The canonical file path is the stable identity instead.</p>
 */
final class EntityStoreLock {
    private static final ConcurrentHashMap<String, Object> LOCKS =
        new ConcurrentHashMap<>();

    private EntityStoreLock() {
        throw new AssertionError("No instances");
    }

    static Object forFile(File file) throws IOException {
        if (file == null) {
            throw new IOException("entity file is null");
        }
        String key = file.getCanonicalPath();
        return LOCKS.computeIfAbsent(key, ignored -> new Object());
    }
}
