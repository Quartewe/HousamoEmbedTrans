package com.quarty.housamoembedtrans.storage.json;

import java.io.File;
import java.io.IOException;

/**
 * Atomic JSON-file boundary used by the Scene Context storage layer.
 *
 * <p>Production uses {@link AndroidAtomicJsonFileIo} (backed by
 * {@code android.util.AtomicFile}); host tests inject a plain implementation
 * so no Android runtime is required.</p>
 */
public interface AtomicJsonFileIo {

    /** Returns the Android AtomicFile-backed implementation. */
    static AtomicJsonFileIo android() {
        return new AndroidAtomicJsonFileIo();
    }

    /** Returns whether the file exists as a formal or atomic backup file. */
    boolean exists(File file);

    /** Reads the complete file; callers must check {@link #exists} first. */
    byte[] read(File file) throws IOException;

    /** Atomically replaces the file with the supplied bytes. */
    void write(File file, byte[] bytes) throws IOException;

    /** Deletes the file; a missing file is a no-op. */
    void delete(File file) throws IOException;
}
