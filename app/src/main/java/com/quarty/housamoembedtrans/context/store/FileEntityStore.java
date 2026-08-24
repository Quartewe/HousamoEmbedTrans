package com.quarty.housamoembedtrans.context.store;
import com.quarty.housamoembedtrans.context.schema.ContextGroupSchemaValidator;
import com.quarty.housamoembedtrans.storage.json.AtomicJsonFileIo;

import org.json.JSONObject;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Package-private file-entity mechanism shared by the typed Context and Group
 * adapters. It owns locking, validation, UTF-8 JSON and atomic file lifetime;
 * the adapters retain their domain-specific schemas and operations.
 */
final class FileEntityStore {

    @FunctionalInterface
    interface EntityValidator {
        void validate(JSONObject document)
            throws ContextGroupSchemaValidator.ValidationException;
    }

    @FunctionalInterface
    interface Mutation {
        JSONObject apply(JSONObject document)
            throws IOException,
            ContextGroupSchemaValidator.ValidationException,
            org.json.JSONException;
    }

    @FunctionalInterface
    interface LockedFileOperation<T> {
        T apply(File file)
            throws IOException,
            ContextGroupSchemaValidator.ValidationException,
            org.json.JSONException;
    }

    private final File directory;
    private final String kind;
    private final EntityValidator validator;
    private final AtomicJsonFileIo io;

    FileEntityStore(
        File directory,
        String kind,
        EntityValidator validator,
        AtomicJsonFileIo io
    ) {
        if (directory == null || kind == null || kind.trim().isEmpty()
            || validator == null || io == null) {
            throw new IllegalArgumentException(
                "directory, kind, validator and io are required"
            );
        }
        this.directory = directory;
        this.kind = kind;
        this.validator = validator;
        this.io = io;
    }

    File getDirectory() {
        return directory;
    }

    boolean exists(String storageName) {
        return isValidStorageName(storageName)
            && io.exists(fileFor(storageName));
    }

    JSONObject read(String storageName)
        throws IOException, ContextGroupSchemaValidator.ValidationException {
        requireStorageName(storageName);
        File file = fileFor(storageName);
        synchronized (EntityStoreLock.forFile(file)) {
            return readUnlocked(storageName, file);
        }
    }

    void write(String storageName, JSONObject document)
        throws IOException, ContextGroupSchemaValidator.ValidationException {
        requireStorageName(storageName);
        File file = fileFor(storageName);
        synchronized (EntityStoreLock.forFile(file)) {
            writeUnlocked(storageName, file, document);
        }
    }

    JSONObject mutate(String storageName, Mutation mutation)
        throws IOException,
        ContextGroupSchemaValidator.ValidationException,
        org.json.JSONException {
        requireStorageName(storageName);
        if (mutation == null) {
            throw new IllegalArgumentException("mutation is null");
        }
        File file = fileFor(storageName);
        synchronized (EntityStoreLock.forFile(file)) {
            if (!io.exists(file)) {
                throw new SummaryTargetInvalidatedException(
                    kind + " target was deleted: " + storageName
                );
            }
            JSONObject current = readUnlocked(storageName, file);
            JSONObject updated = mutation.apply(current);
            if (updated == null) {
                throw new IOException(kind + " mutation returned null");
            }
            writeUnlocked(storageName, file, updated);
            return updated;
        }
    }

    void delete(String storageName) throws IOException {
        requireStorageName(storageName);
        File file = fileFor(storageName);
        synchronized (EntityStoreLock.forFile(file)) {
            io.delete(file);
        }
    }

    List<String> listStorageNames() {
        if (!directory.isDirectory()) {
            return Collections.emptyList();
        }
        File[] files = directory.listFiles();
        if (files == null) {
            return Collections.emptyList();
        }
        List<String> names = new ArrayList<>();
        for (File file : files) {
            String name = file.getName();
            if (!file.isFile() || !name.endsWith(".json")) {
                continue;
            }
            String storageName = name.substring(0, name.length() - 5);
            if (isValidStorageName(storageName)
                && name.equals(storageName + ".json")) {
                names.add(storageName);
            }
        }
        Collections.sort(names);
        return Collections.unmodifiableList(names);
    }

    <T> T withLockedFile(
        String storageName,
        LockedFileOperation<T> operation
    ) throws IOException,
        ContextGroupSchemaValidator.ValidationException,
        org.json.JSONException {
        requireStorageName(storageName);
        if (operation == null) {
            throw new IllegalArgumentException("locked operation is null");
        }
        File file = fileFor(storageName);
        synchronized (EntityStoreLock.forFile(file)) {
            return operation.apply(file);
        }
    }

    boolean existsFile(File file) {
        return io.exists(file);
    }

    JSONObject readUnlocked(String storageName, File file)
        throws IOException, ContextGroupSchemaValidator.ValidationException {
        if (!io.exists(file)) {
            throw new IOException(kind + " file does not exist: " + storageName);
        }
        JSONObject document = parseJsonObject(
            io.read(file),
            kind + " " + storageName
        );
        String storedName = document.optString("storage_name", "");
        if (!storageName.equals(storedName)) {
            throw new IOException(
                kind + " storage_name does not match file name: " + storageName
            );
        }
        validator.validate(document);
        return document;
    }

    void writeUnlocked(String storageName, File file, JSONObject document)
        throws IOException, ContextGroupSchemaValidator.ValidationException {
        if (document == null) {
            throw new IOException(kind + " is null");
        }
        String storedName = document.optString("storage_name", "");
        if (!storageName.equals(storedName)) {
            throw new IOException(
                kind + " storage_name must match target file name: "
                    + storageName
            );
        }
        validator.validate(document);
        io.write(
            file,
            document.toString().getBytes(StandardCharsets.UTF_8)
        );
    }

    boolean hasManualSummary(String storageName, String targetLang)
        throws IOException, ContextGroupSchemaValidator.ValidationException {
        return hasManualSummary(read(storageName), targetLang);
    }

    JSONObject getSummaryRecord(
        String storageName,
        String targetLang,
        String kind
    ) throws IOException,
        ContextGroupSchemaValidator.ValidationException,
        org.json.JSONException {
        requireNonEmpty(kind, "summary kind");
        JSONObject document = read(storageName);
        JSONObject lang = summaryLanguage(document, targetLang);
        if (lang == null || !lang.has(kind) || lang.isNull(kind)) {
            return null;
        }
        return new JSONObject(lang.getJSONObject(kind).toString());
    }

    JSONObject writeManualSummary(
        String storageName,
        String targetLang,
        String text
    ) throws IOException,
        ContextGroupSchemaValidator.ValidationException,
        org.json.JSONException {
        requireStorageName(storageName);
        requireNonEmpty(targetLang, "target_lang");
        requireNonEmpty(text, "manual summary");
        return mutate(storageName, document -> {
            JSONObject summaryContainer = document.optJSONObject("summary");
            if (summaryContainer == null) {
                summaryContainer = new JSONObject();
                document.put("summary", summaryContainer);
            }
            JSONObject lang = summaryContainer.optJSONObject(targetLang);
            if (lang == null) {
                lang = new JSONObject();
                summaryContainer.put(targetLang, lang);
            }
            lang.put("manual", new JSONObject()
                .put("text", text)
                .put("updated_at", System.currentTimeMillis()));
            document.put("updated_at", System.currentTimeMillis());
            return document;
        });
    }

    JSONObject deleteManualSummary(
        String storageName,
        String targetLang
    ) throws IOException,
        ContextGroupSchemaValidator.ValidationException,
        org.json.JSONException {
        requireStorageName(storageName);
        requireNonEmpty(targetLang, "target_lang");
        return mutate(storageName, document -> {
            JSONObject summaryContainer = document.optJSONObject("summary");
            if (summaryContainer != null) {
                JSONObject lang = summaryContainer.optJSONObject(targetLang);
                if (lang != null && lang.has("manual")) {
                    lang.remove("manual");
                    if (lang.length() == 0) {
                        summaryContainer.remove(targetLang);
                    }
                    document.put("updated_at", System.currentTimeMillis());
                }
            }
            return document;
        });
    }

    static boolean hasManualSummary(JSONObject document, String targetLang) {
        JSONObject lang = summaryLanguage(document, targetLang);
        return lang != null && lang.has("manual") && !lang.isNull("manual");
    }

    private static JSONObject summaryLanguage(
        JSONObject document,
        String targetLang
    ) {
        JSONObject summary = document.optJSONObject("summary");
        return summary == null ? null : summary.optJSONObject(targetLang);
    }

    private File fileFor(String storageName) {
        return new File(directory, storageName + ".json");
    }

    static boolean isValidStorageName(String value) {
        return value != null
            && value.length() <= 64
            && value.matches("^[A-Za-z][A-Za-z0-9_-]*$");
    }

    static String requireStorageName(String value) {
        if (!isValidStorageName(value)) {
            throw new IllegalArgumentException(
                "storage name must match [A-Za-z][A-Za-z0-9_-]{0,63}"
            );
        }
        return value;
    }

    private static void requireNonEmpty(String value, String label) {
        if (value == null || value.trim().isEmpty()) {
            throw new IllegalArgumentException(label + " must not be empty");
        }
    }

    private static JSONObject parseJsonObject(byte[] bytes, String name)
        throws IOException {
        if (bytes == null || bytes.length == 0) {
            throw new IOException(name + " is empty");
        }
        try {
            return new JSONObject(new String(bytes, StandardCharsets.UTF_8));
        } catch (org.json.JSONException e) {
            throw new IOException(name + " is not valid JSON", e);
        }
    }
}
