package com.quarty.housamoembedtrans.storage.json;

import com.quarty.housamoembedtrans.management.transfer.ManagementImportRecoveryGate;
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
        File filesRoot = filesRootForContextTarget(file);
        if (filesRoot == null) {
            IoUtils.writeAtomically(file, bytes);
            return;
        }
        ManagementImportRecoveryGate.forFilesRoot(filesRoot)
            .withContextWrite(
                file,
                () -> {
                    IoUtils.writeAtomically(file, bytes);
                    return null;
                }
            );
    }

    @Override
    public void delete(File file) throws IOException {
        File filesRoot = filesRootForContextTarget(file);
        if (filesRoot == null) {
            new AtomicFile(file).delete();
            return;
        }
        ManagementImportRecoveryGate.forFilesRoot(filesRoot)
            .withContextWrite(
                file,
                () -> {
                    new AtomicFile(file).delete();
                    return null;
                }
            );
    }

    /** Returns the app files root when a target is under files/scene_contexts. */
    private static File filesRootForContextTarget(File target) {
        if (target == null) {
            return null;
        }
        File current = target;
        while (current != null) {
            if ("scene_contexts".equals(current.getName())) {
                return current.getParentFile();
            }
            current = current.getParentFile();
        }
        return null;
    }
}
