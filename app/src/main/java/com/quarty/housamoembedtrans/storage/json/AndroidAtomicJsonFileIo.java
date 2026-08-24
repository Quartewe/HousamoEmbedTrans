package com.quarty.housamoembedtrans.storage.json;

import com.quarty.housamoembedtrans.util.IoUtils;

import android.util.AtomicFile;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;

/**
 * Android {@link AtomicFile}-backed implementation of {@link AtomicJsonFileIo}.
 */
final class AndroidAtomicJsonFileIo implements AtomicJsonFileIo {

    @Override
    public boolean exists(File file) {
        return IoUtils.atomicFileExists(file);
    }

    @Override
    public byte[] read(File file) throws IOException {
        try (InputStream input = new AtomicFile(file).openRead()) {
            return IoUtils.readAllBytesLimited(input, -1);
        }
    }

    @Override
    public void write(File file, byte[] bytes) throws IOException {
        IoUtils.writeAtomically(file, bytes);
    }

    @Override
    public void delete(File file) throws IOException {
        new AtomicFile(file).delete();
    }
}
