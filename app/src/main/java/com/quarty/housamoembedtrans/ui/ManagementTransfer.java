package com.quarty.housamoembedtrans.ui;

import com.quarty.housamoembedtrans.storage.json.JsonSchemaValidator;

import android.content.ContentResolver;
import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.provider.DocumentsContract;
import android.provider.OpenableColumns;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Small SAF helper for the independent management transfer files. */
public final class ManagementTransfer {
    public static final int MAX_DOCUMENT_BYTES = 64 * 1024 * 1024;

    private ManagementTransfer() {
    }

    /** Stable display name used by the import list; URI strings are fallback. */
    public static String displayName(ContentResolver resolver, Uri uri) {
        if (resolver == null || uri == null) {
            return "document.json";
        }
        try (Cursor cursor = resolver.query(
            uri,
            new String[] {OpenableColumns.DISPLAY_NAME},
            null,
            null,
            null
        )) {
            if (cursor != null
                && cursor.moveToFirst()
                && cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME) >= 0) {
                String name = cursor.getString(
                    cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                );
                if (name != null && !name.trim().isEmpty()) {
                    return name.trim();
                }
            }
        } catch (RuntimeException ignored) {
            // Some document providers do not expose metadata until opened.
        }
        String value = uri.toString();
        int slash = value.lastIndexOf('/');
        return slash >= 0 && slash + 1 < value.length()
            ? value.substring(slash + 1)
            : "document.json";
    }

    /** Bounded SAF read used by the per-file import rows. */
    public static byte[] readBytes(
        ContentResolver resolver,
        Uri uri
    ) throws IOException {
        if (resolver == null || uri == null) {
            throw new IOException("import document is unavailable");
        }
        try (InputStream input = resolver.openInputStream(uri)) {
            if (input == null) {
                throw new IOException("could not open import document");
            }
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            byte[] buffer = new byte[8192];
            int total = 0;
            int count;
            while ((count = input.read(buffer)) != -1) {
                total += count;
                if (total > MAX_DOCUMENT_BYTES) {
                    throw new IOException("import document exceeds size limit");
                }
                output.write(buffer, 0, count);
            }
            if (total == 0) {
                throw new IOException("import document is empty");
            }
            return output.toByteArray();
        } catch (SecurityException error) {
            throw new IOException("document permission was revoked", error);
        }
    }

    /** Reads and formally identifies one selected JSON document. */
    public static ManagementImportModel.Document readDocument(
        ContentResolver resolver,
        Uri uri,
        String sourceName
    ) throws IOException {
        return readDocument(resolver, uri, sourceName, null);
    }

    /** Reads one document with the caller's existing Scene schema validator. */
    public static ManagementImportModel.Document readDocument(
        ContentResolver resolver,
        Uri uri,
        String sourceName,
        JsonSchemaValidator sceneSchemaValidator
    ) throws IOException {
        try {
            return ManagementImportModel.parseDocument(
                sourceName,
                readBytes(resolver, uri),
                sceneSchemaValidator
            );
        } catch (IllegalArgumentException error) {
            throw new IOException(error.getMessage(), error);
        }
    }

    /** Reads every URI in a dictionary batch through the same hard limit. */
    public static List<byte[]> readBytes(
        ContentResolver resolver,
        List<Uri> uris
    ) throws IOException {
        if (uris == null || uris.isEmpty()) {
            throw new IOException("no import documents selected");
        }
        List<byte[]> output = new ArrayList<>();
        for (Uri uri : uris) {
            output.add(readBytes(resolver, uri));
        }
        return output;
    }

    public static final class FileSpec {
        public final String directory;
        public final String requestedName;
        public final byte[] bytes;
        public final String label;

        public FileSpec(
            String directory,
            String requestedName,
            byte[] bytes,
            String label
        ) {
            this.directory = directory == null ? "" : directory;
            this.requestedName = requestedName == null
                ? "document.json"
                : requestedName;
            this.bytes = bytes == null ? new byte[0] : bytes.clone();
            this.label = label == null || label.trim().isEmpty()
                ? this.requestedName
                : label;
        }
    }

    public static final class WriteResult {
        public final int succeeded;
        public final List<String> failures;

        private WriteResult(int succeeded, List<String> failures) {
            this.succeeded = succeeded;
            this.failures = failures;
        }
    }

    /**
     * Writes every file independently below the selected SAF directory.
     * Existing documents are never opened for replacement; a suffix is used
     * when the requested name is already present.  A failure in one file does
     * not claim that the remaining files were committed atomically.
     */
    public static WriteResult writeFiles(
        ContentResolver resolver,
        Uri treeUri,
        List<FileSpec> files
    ) throws IOException {
        if (resolver == null || treeUri == null) {
            throw new IOException("export directory is unavailable");
        }
        if (files == null || files.isEmpty()) {
            throw new IOException("export file plan is empty");
        }

        Map<String, Uri> directories = new HashMap<>();
        Map<String, Set<String>> usedNames = new HashMap<>();
        int succeeded = 0;
        java.util.ArrayList<String> failures = new java.util.ArrayList<>();
        for (FileSpec file : files) {
            if (file == null) {
                failures.add("document: export file plan is invalid");
                continue;
            }
            Uri createdDocument = null;
            try {
                Uri directory = findOrCreateDirectory(
                    resolver,
                    treeUri,
                    file.directory,
                    directories
                );
                String directoryKey = file.directory == null
                    ? ""
                    : file.directory;
                Set<String> names = usedNames.get(directoryKey);
                if (names == null) {
                    names = existingDocumentNames(resolver, directory);
                    usedNames.put(directoryKey, names);
                }
                String fileName = uniqueName(file.requestedName, names);
                if (file.bytes.length > MAX_DOCUMENT_BYTES) {
                    throw new IOException(
                        "transfer document exceeds size limit"
                    );
                }
                createdDocument = DocumentsContract.createDocument(
                    resolver,
                    directory,
                    "application/json",
                    fileName
                );
                if (createdDocument == null) {
                    throw new IOException(
                        "document provider refused " + fileName
                    );
                }
                try (OutputStream output = resolver.openOutputStream(
                    createdDocument,
                    "w"
                )) {
                    if (output == null) {
                        throw new IOException(
                            "could not open " + fileName + " for writing"
                        );
                    }
                    output.write(file.bytes);
                    output.flush();
                }
                succeeded++;
            } catch (Exception error) {
                String detail = safeMessage(error);
                if (createdDocument != null) {
                    try {
                        if (!DocumentsContract.deleteDocument(
                            resolver,
                            createdDocument
                        )) {
                            detail += "; cleanup of partial document failed";
                        }
                    } catch (Exception cleanupError) {
                        detail += "; cleanup failed: "
                            + safeMessage(cleanupError);
                    }
                }
                failures.add(file.label + ": " + detail);
            }
        }
        return new WriteResult(succeeded, failures);
    }

    public static String jsonFileName(String value) {
        String name = sanitizeFileName(value);
        if (name.length() > 115) {
            name = name.substring(0, 115);
        }
        return name + ".json";
    }

    private static Uri findOrCreateDirectory(
        ContentResolver resolver,
        Uri treeUri,
        String name,
        Map<String, Uri> cache
    ) throws IOException {
        String directoryName = name == null ? "" : name.trim();
        Uri root = DocumentsContract.buildDocumentUriUsingTree(
            treeUri,
            DocumentsContract.getTreeDocumentId(treeUri)
        );
        if (directoryName.isEmpty()) {
            return root;
        }
        Uri cached = cache.get(directoryName);
        if (cached != null) {
            return cached;
        }
        Uri existing = findChildDirectory(resolver, treeUri, root, directoryName);
        if (existing == null) {
            existing = DocumentsContract.createDocument(
                resolver,
                root,
                DocumentsContract.Document.MIME_TYPE_DIR,
                directoryName
            );
        }
        if (existing == null) {
            throw new IOException(
                "could not create export directory: " + directoryName
            );
        }
        cache.put(directoryName, existing);
        return existing;
    }

    private static Uri findChildDirectory(
        ContentResolver resolver,
        Uri treeUri,
        Uri parent,
        String name
    ) throws IOException {
        String parentId = DocumentsContract.getDocumentId(parent);
        Uri children = DocumentsContract.buildChildDocumentsUriUsingTree(
            treeUri,
            parentId
        );
        String[] projection = {
            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_MIME_TYPE
        };
        try (Cursor cursor = resolver.query(
            children,
            projection,
            null,
            null,
            null
        )) {
            if (cursor == null) {
                throw new IOException("document provider returned no listing");
            }
            int idColumn = cursor.getColumnIndex(
                DocumentsContract.Document.COLUMN_DOCUMENT_ID
            );
            int nameColumn = cursor.getColumnIndex(
                DocumentsContract.Document.COLUMN_DISPLAY_NAME
            );
            int mimeColumn = cursor.getColumnIndex(
                DocumentsContract.Document.COLUMN_MIME_TYPE
            );
            while (cursor.moveToNext()) {
                if (name.equals(cursor.getString(nameColumn))
                    && DocumentsContract.Document.MIME_TYPE_DIR.equals(
                        cursor.getString(mimeColumn)
                    )) {
                    return DocumentsContract.buildDocumentUriUsingTree(
                        treeUri,
                        cursor.getString(idColumn)
                    );
                }
            }
        } catch (RuntimeException error) {
            throw new IOException("could not inspect export directory", error);
        }
        return null;
    }

    private static Set<String> existingDocumentNames(
        ContentResolver resolver,
        Uri directory
    ) throws IOException {
        Set<String> names = new HashSet<>();
        String id = DocumentsContract.getDocumentId(directory);
        Uri children = DocumentsContract.buildChildDocumentsUriUsingTree(
            directory,
            id
        );
        try (Cursor cursor = resolver.query(
            children,
            new String[] {DocumentsContract.Document.COLUMN_DISPLAY_NAME},
            null,
            null,
            null
        )) {
            if (cursor == null) {
                throw new IOException("document provider returned no listing");
            }
            int nameColumn = cursor.getColumnIndex(
                DocumentsContract.Document.COLUMN_DISPLAY_NAME
            );
            while (cursor.moveToNext()) {
                names.add(cursor.getString(nameColumn));
            }
        } catch (RuntimeException error) {
            throw new IOException("could not inspect export files", error);
        }
        return names;
    }

    private static String uniqueName(String requestedName, Set<String> used) {
        String safe = safeFileName(requestedName);
        String base = safe;
        String extension = "";
        int dot = safe.lastIndexOf('.');
        if (dot > 0) {
            base = safe.substring(0, dot);
            extension = safe.substring(dot);
        }
        String candidate = safe;
        int suffix = 2;
        while (!used.add(candidate)) {
            candidate = base + "_" + suffix++ + extension;
        }
        return candidate;
    }

    private static String safeFileName(String value) {
        String name = sanitizeFileName(value);
        if (name.length() <= 120) {
            return name;
        }
        int dot = name.lastIndexOf('.');
        if (dot > 0 && dot < name.length() - 1) {
            String extension = name.substring(dot);
            int baseLength = Math.max(1, 120 - extension.length());
            return name.substring(0, baseLength) + extension;
        }
        return name.substring(0, 120);
    }

    private static String sanitizeFileName(String value) {
        String name = value == null ? "document" : value.trim();
        name = name.replaceAll("[\\\\/:*?\"<>|]", "_");
        name = name.replaceAll("\\s+", " ").trim();
        if (name.isEmpty() || ".".equals(name) || "..".equals(name)) {
            name = "document";
        }
        return name;
    }

    private static String safeMessage(Throwable error) {
        if (error == null || error.getMessage() == null
            || error.getMessage().trim().isEmpty()) {
            return error == null ? "operation_failed" : error.getClass().getSimpleName();
        }
        return error.getMessage();
    }
}
