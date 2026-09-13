package com.quarty.housamoembedtrans.ui;

import android.content.Context;
import android.util.AtomicFile;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.UUID;

/**
 * App-private handoff for a prepared import.  Large Scene documents must not
 * travel through an Activity result Intent; the caller receives only this
 * token and the expected snapshot fingerprint.
 */
public final class ManagementImportSessionStore {
    private static final String DIRECTORY = "management_import_sessions";
    private static final String PENDING_FILE = "pending.json";
    private static final int MAX_BYTES = ManagementTransfer.MAX_DOCUMENT_BYTES;
    private static final int MAX_PENDING_BYTES = 4096;
    private static final Object PENDING_LOCK = new Object();

    private ManagementImportSessionStore() {
    }

    /** The small durable pointer used to resume an uncertain Binder submit. */
    public static final class PendingSubmission {
        public final String sessionToken;
        public final String snapshotFingerprint;

        private PendingSubmission(
            String sessionToken,
            String snapshotFingerprint
        ) {
            this.sessionToken = sessionToken;
            this.snapshotFingerprint = snapshotFingerprint;
        }
    }

    public static String write(Context context, JSONObject preview)
        throws IOException {
        if (context == null || preview == null) {
            throw new IOException("import preview is unavailable");
        }
        String token = UUID.randomUUID().toString();
        File directory = new File(context.getFilesDir(), DIRECTORY);
        if (!directory.isDirectory()
            && !directory.mkdirs()
            && !directory.isDirectory()) {
            throw new IOException("could not create import session directory");
        }
        byte[] bytes = preview.toString().getBytes(StandardCharsets.UTF_8);
        if (bytes.length == 0 || bytes.length > MAX_BYTES) {
            throw new IOException("import preview exceeds size limit");
        }
        AtomicFile file = new AtomicFile(fileFor(context, token));
        FileOutputStream output = file.startWrite();
        try {
            output.write(bytes);
            output.getFD().sync();
            file.finishWrite(output);
            return token;
        } catch (Exception error) {
            file.failWrite(output);
            throw error instanceof IOException
                ? (IOException) error
                : new IOException("could not write import preview", error);
        }
    }

    public static JSONObject read(Context context, String token) throws Exception {
        File target = fileFor(context, token);
        AtomicFile file = new AtomicFile(target);
        byte[] bytes;
        try (InputStream input = file.openRead()) {
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            byte[] buffer = new byte[8192];
            int total = 0;
            int count;
            while ((count = input.read(buffer)) != -1) {
                total += count;
                if (total > MAX_BYTES) {
                    throw new IOException("import preview exceeds size limit");
                }
                output.write(buffer, 0, count);
            }
            bytes = output.toByteArray();
        }
        if (bytes.length == 0) {
            throw new IOException("import preview is empty");
        }
        JSONObject value = new JSONObject(
            new String(bytes, StandardCharsets.UTF_8)
        );
        ManagementImportModel.validatePreviewEnvelope(value);
        return value;
    }

    /**
     * Persists the exact token/fingerprint pair before any Binder submit.
     * There is only one pending Activity handoff; a different token must not
     * replace an unresolved one.
     */
    public static void writePending(
        Context context,
        String sessionToken,
        String snapshotFingerprint
    ) throws IOException {
        synchronized (PENDING_LOCK) {
            validateToken(sessionToken);
            if (snapshotFingerprint == null || snapshotFingerprint.isEmpty()) {
                throw new IOException("invalid import snapshot fingerprint");
            }
            PendingSubmission existing = readPendingLocked(context);
            if (existing != null && !existing.sessionToken.equals(sessionToken)) {
                throw new IOException("another import submission is pending");
            }
            File directory = sessionDirectory(context);
            if (!directory.isDirectory()
                && !directory.mkdirs()
                && !directory.isDirectory()) {
                throw new IOException("could not create import session directory");
            }
            byte[] bytes;
            try {
                bytes = new JSONObject()
                    .put("session_token", sessionToken)
                    .put("snapshot_fingerprint", snapshotFingerprint)
                    .toString()
                    .getBytes(StandardCharsets.UTF_8);
            } catch (Exception error) {
                throw new IOException("could not encode pending import pointer", error);
            }
            if (bytes.length > MAX_PENDING_BYTES) {
                throw new IOException("pending import pointer exceeds size limit");
            }
            AtomicFile file = new AtomicFile(pendingFile(context));
            FileOutputStream output = file.startWrite();
            try {
                output.write(bytes);
                output.getFD().sync();
                file.finishWrite(output);
            } catch (Exception error) {
                file.failWrite(output);
                throw error instanceof IOException
                    ? (IOException) error
                    : new IOException(
                        "could not write pending import pointer",
                        error
                    );
            }
        }
    }

    /** Reads the pending pointer, if one exists; malformed state is retained. */
    public static PendingSubmission readPending(Context context)
        throws IOException {
        synchronized (PENDING_LOCK) {
            return readPendingLocked(context);
        }
    }

    private static PendingSubmission readPendingLocked(Context context)
        throws IOException {
        File target = pendingFile(context);
        AtomicFile file = new AtomicFile(target);
        byte[] bytes;
        try (InputStream input = file.openRead()) {
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            byte[] buffer = new byte[512];
            int total = 0;
            int count;
            while ((count = input.read(buffer)) != -1) {
                total += count;
                if (total > MAX_PENDING_BYTES) {
                    throw new IOException("pending import pointer exceeds size limit");
                }
                output.write(buffer, 0, count);
            }
            bytes = output.toByteArray();
        } catch (FileNotFoundException missing) {
            // AtomicFile.openRead() also promotes a valid .bak file when the
            // base file is absent.
            return null;
        }
        try {
            JSONObject value = new JSONObject(
                new String(bytes, StandardCharsets.UTF_8)
            );
            String sessionToken = value.optString("session_token", "");
            String snapshotFingerprint = value.optString(
                "snapshot_fingerprint",
                ""
            );
            validateToken(sessionToken);
            if (snapshotFingerprint.isEmpty()) {
                throw new IOException("pending import fingerprint is missing");
            }
            return new PendingSubmission(sessionToken, snapshotFingerprint);
        } catch (IOException error) {
            throw error;
        } catch (Exception error) {
            throw new IOException("pending import pointer is invalid", error);
        }
    }

    /** Removes the pointer only when it still belongs to the supplied token. */
    public static boolean deletePending(Context context, String sessionToken) {
        synchronized (PENDING_LOCK) {
            try {
                PendingSubmission pending = readPendingLocked(context);
                if (pending == null) {
                    return true;
                }
                if (!pending.sessionToken.equals(sessionToken)) {
                    return false;
                }
                return deleteAtomicFiles(pendingFile(context));
            } catch (IOException ignored) {
                // Retain malformed or unreadable pending state for safe
                // recovery.  The matching preview must remain available too.
                return false;
            }
        }
    }

    /** True when an app-private prepared session is still available. */
    public static boolean exists(Context context, String token) {
        try {
            File file = fileFor(context, token);
            return file.isFile() || backupFor(file).isFile();
        } catch (IOException ignored) {
            return false;
        }
    }

    public static void delete(Context context, String token) {
        try {
            File file = fileFor(context, token);
            deleteAtomicFiles(file);
        } catch (IOException ignored) {
            // Invalid tokens never name a session file.
        }
    }

    private static boolean deleteAtomicFiles(File file) {
        boolean deleted = true;
        if (file.exists() && !file.delete() && file.exists()) {
            deleted = false;
        }
        File backup = backupFor(file);
        if (backup.exists() && !backup.delete() && backup.exists()) {
            deleted = false;
        }
        return deleted;
    }

    private static File backupFor(File file) {
        return new File(file.getPath() + ".bak");
    }

    private static File fileFor(Context context, String token) throws IOException {
        validateToken(token);
        return new File(
            sessionDirectory(context),
            token + ".json"
        );
    }

    private static File sessionDirectory(Context context) throws IOException {
        if (context == null) {
            throw new IOException("context is unavailable");
        }
        return new File(context.getFilesDir(), DIRECTORY);
    }

    private static File pendingFile(Context context) throws IOException {
        return new File(sessionDirectory(context), PENDING_FILE);
    }

    private static void validateToken(String token) throws IOException {
        if (token == null || !token.matches(
            "[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[1-5][0-9a-fA-F]{3}-[89abAB][0-9a-fA-F]{3}-[0-9a-fA-F]{12}"
        )) {
            throw new IOException("invalid import session token");
        }
    }
}
