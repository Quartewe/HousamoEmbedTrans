package com.quarty.housamoembedtrans.util;

import android.util.AtomicFile;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;

public final class IoUtils {
    public static final class InputLimitExceededException
        extends IOException {

        public InputLimitExceededException(int maxBytes) {
            super("input exceeds " + maxBytes + " bytes");
        }
    }

    public static byte[] readAllBytesLimited(InputStream input, int max) throws IOException {
        try (ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            if (max != -1 && max <= 0) {
                throw new IOException("maxBytes must be positive or -1 as unlimited");
            }

            byte[] buffer = new byte[8192];
            int read;
            long total = 0;

            while ((read = input.read(buffer)) != -1) {
                total += read;
                if (max != -1 && total > max) {
                    throw new InputLimitExceededException(max);
                }
                output.write(buffer, 0, read);
            }
            return output.toByteArray();
        }
    }

    public static String readUtf8Limited(InputStream input, int max) throws IOException {
        byte[] bytes = readAllBytesLimited(input, max);
        return new String(bytes, StandardCharsets.UTF_8);
    }

    public static void writeAtomically(File file, byte[] bytes) throws IOException {
        AtomicFile atomicFile = new AtomicFile(file);
        FileOutputStream output = null;
        try {
            output = atomicFile.startWrite();
            output.write(bytes);
            atomicFile.finishWrite(output);
        } catch (IOException e) {
            if (output != null) {
                atomicFile.failWrite(output);
            }
            throw e;
        }
    }

    public static boolean atomicFileExists(File file) {
        return file.isFile()
            || new File(file.getPath() + ".bak").isFile();
    }

    public static void ensureDirectory(File directory) throws IOException {
        if (directory.isDirectory()) {
            return;
        }

        if (!directory.mkdirs() && !directory.isDirectory()) {
            throw new IOException(
                "could not create directory " + directory.getAbsolutePath()
            );
        }
    }

    private IoUtils() {
        // Private constructor to prevent instantiation
    }
}
