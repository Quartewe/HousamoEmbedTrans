package com.quarty.housamoembedtrans.storage;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * File-level store for Context Group entities under
 * {@code files/scene_contexts/groups/}.
 *
 * <p>This class deliberately knows nothing about the Index, Active pointers or
 * Context members. Cross-entity transactions are coordinated by
 * {@link SceneContextStore}.</p>
 */
public final class GroupStore {

    public static final String DIRECTORY_NAME = "groups";

    private final File directory;
    private final ContextGroupSchemaValidator validator;
    private final AtomicJsonFileIo io;

    @FunctionalInterface
    public interface Mutation {
        JSONObject apply(JSONObject group)
            throws IOException,
            ContextGroupSchemaValidator.ValidationException,
            org.json.JSONException;
    }

    public GroupStore(
        File directory,
        ContextGroupSchemaValidator validator,
        AtomicJsonFileIo io
    ) {
        if (directory == null || validator == null || io == null) {
            throw new IllegalArgumentException(
                "directory, validator and io are required"
            );
        }
        this.directory = directory;
        this.validator = validator;
        this.io = io;
    }

    public File getDirectory() {
        return directory;
    }

    public boolean exists(String storageName) {
        return ContextStore.isValidStorageName(storageName)
            && io.exists(fileFor(storageName));
    }

    /** Reads and schema-validates one Context Group entity. */
    public JSONObject read(String storageName)
        throws IOException, ContextGroupSchemaValidator.ValidationException {
        ContextStore.requireStorageName(storageName);
        File file = fileFor(storageName);
        synchronized (EntityStoreLock.forFile(file)) {
            return readUnlocked(storageName, file);
        }
    }

    private JSONObject readUnlocked(String storageName, File file)
        throws IOException, ContextGroupSchemaValidator.ValidationException {
        if (!io.exists(file)) {
            throw new IOException("group file does not exist: " + storageName);
        }
        JSONObject group = parseJsonObject(
            io.read(file),
            "group " + storageName
        );
        String storedName = group.optString("storage_name", "");
        if (!storageName.equals(storedName)) {
            throw new IOException(
                "group storage_name does not match file name: " + storageName
            );
        }
        validator.validateGroup(group);
        return group;
    }

    /** Validates and atomically writes one Context Group entity. */
    public void write(String storageName, JSONObject group)
        throws IOException, ContextGroupSchemaValidator.ValidationException {
        ContextStore.requireStorageName(storageName);
        File file = fileFor(storageName);
        synchronized (EntityStoreLock.forFile(file)) {
            writeUnlocked(storageName, file, group);
        }
    }

    private void writeUnlocked(
        String storageName,
        File file,
        JSONObject group
    ) throws IOException, ContextGroupSchemaValidator.ValidationException {
        if (group == null) {
            throw new IOException("group is null");
        }
        String storedName = group.optString("storage_name", "");
        if (!storageName.equals(storedName)) {
            throw new IOException(
                "group storage_name must match target file name: " + storageName
            );
        }
        validator.validateGroup(group);
        io.write(
            file,
            group.toString().getBytes(StandardCharsets.UTF_8)
        );
    }

    /** Atomically reads, mutates and writes one current Group document. */
    public JSONObject mutate(String storageName, Mutation mutation)
        throws IOException,
        ContextGroupSchemaValidator.ValidationException,
        org.json.JSONException {
        ContextStore.requireStorageName(storageName);
        if (mutation == null) {
            throw new IllegalArgumentException("mutation is null");
        }
        File file = fileFor(storageName);
        synchronized (EntityStoreLock.forFile(file)) {
            if (!io.exists(file)) {
                throw new SummaryTargetInvalidatedException(
                    "group target was deleted: " + storageName
                );
            }
            JSONObject current = readUnlocked(storageName, file);
            JSONObject updated = mutation.apply(current);
            if (updated == null) {
                throw new IOException("group mutation returned null");
            }
            writeUnlocked(storageName, file, updated);
            return updated;
        }
    }

    /** Deletes one Context Group entity file; missing file is a no-op. */
    public void delete(String storageName) throws IOException {
        ContextStore.requireStorageName(storageName);
        File file = fileFor(storageName);
        synchronized (EntityStoreLock.forFile(file)) {
            io.delete(file);
        }
    }

    /** Returns whether a non-empty Manual Summary exists for one language. */
    public boolean hasManualSummary(
        String storageName,
        String targetLang
    ) throws IOException, ContextGroupSchemaValidator.ValidationException {
        JSONObject group = read(storageName);
        JSONObject lang = summaryLanguage(group, targetLang);
        return lang != null && lang.has("manual") && !lang.isNull("manual");
    }

    /**
     * Returns a copy of one summary record for a language, or null when the
     * kind is absent. {@code kind} is one of {@code final}, {@code current},
     * {@code manual}.
     */
    public JSONObject getSummaryRecord(
        String storageName,
        String targetLang,
        String kind
    ) throws IOException,
        ContextGroupSchemaValidator.ValidationException,
        org.json.JSONException {
        requireNonEmpty(kind, "summary kind");
        JSONObject group = read(storageName);
        JSONObject lang = summaryLanguage(group, targetLang);
        if (lang == null || !lang.has(kind) || lang.isNull(kind)) {
            return null;
        }
        return new JSONObject(lang.getJSONObject(kind).toString());
    }

    /**
     * Writes or replaces the user's Manual Summary
     * ({@code summary.<target_lang>.manual}). Automatic records in the same
     * language object are preserved.
     */
    public JSONObject writeManualSummary(
        String storageName,
        String targetLang,
        String text
    ) throws IOException,
        ContextGroupSchemaValidator.ValidationException,
        org.json.JSONException {
        ContextStore.requireStorageName(storageName);
        requireNonEmpty(targetLang, "target_lang");
        requireNonEmpty(text, "manual summary");
        return mutate(storageName, group -> {
            JSONObject summaryContainer = group.optJSONObject("summary");
            if (summaryContainer == null) {
                summaryContainer = new JSONObject();
                group.put("summary", summaryContainer);
            }
            JSONObject lang = summaryContainer.optJSONObject(targetLang);
            if (lang == null) {
                lang = new JSONObject();
                summaryContainer.put(targetLang, lang);
            }
            lang.put("manual", new JSONObject()
                .put("text", text)
                .put("updated_at", System.currentTimeMillis()));
            group.put("updated_at", System.currentTimeMillis());
            return group;
        });
    }

    /**
     * Removes the Manual Summary for one language. If the language object no
     * longer contains any summary record it is removed from the summary
     * container, keeping the schema invariant that language objects are
     * non-empty.
     */
    public JSONObject deleteManualSummary(
        String storageName,
        String targetLang
    ) throws IOException,
        ContextGroupSchemaValidator.ValidationException,
        org.json.JSONException {
        ContextStore.requireStorageName(storageName);
        requireNonEmpty(targetLang, "target_lang");
        return mutate(storageName, group -> {
            JSONObject summaryContainer = group.optJSONObject("summary");
            if (summaryContainer != null) {
                JSONObject lang = summaryContainer.optJSONObject(targetLang);
                if (lang != null && lang.has("manual")) {
                    lang.remove("manual");
                    if (lang.length() == 0) {
                        summaryContainer.remove(targetLang);
                    }
                    group.put("updated_at", System.currentTimeMillis());
                }
            }
            return group;
        });
    }

    /** Writes a Group current snapshot at an explicit context-entry cutoff. */
    public JSONObject writeCurrentGroupSummary(
        String storageName,
        String targetLang,
        String cutoffEntryId,
        String text,
        String sourceHash
    ) throws IOException,
        ContextGroupSchemaValidator.ValidationException,
        org.json.JSONException {
        ContextStore.requireStorageName(storageName);
        requireNonEmpty(targetLang, "target_lang");
        requireNonEmpty(cutoffEntryId, "cutoff_entry_id");
        requireNonEmpty(text, "group summary");
        requireNonEmpty(sourceHash, "source_hash");
        return mutate(storageName, group -> {
            JSONArray contexts = group.optJSONArray("contexts");
            if (!containsContextEntryId(contexts, cutoffEntryId)) {
                throw new SummaryTargetInvalidatedException(
                    "group cutoff entry is no longer a member: "
                        + cutoffEntryId
                );
            }
            JSONObject summaryContainer = group.optJSONObject("summary");
            if (summaryContainer == null) {
                summaryContainer = new JSONObject();
                group.put("summary", summaryContainer);
            }
            JSONObject lang = summaryContainer.optJSONObject(targetLang);
            if (lang == null) {
                lang = new JSONObject();
                summaryContainer.put(targetLang, lang);
            }
            long now = System.currentTimeMillis();
            lang.put("current", new JSONObject()
                .put("text", text)
                .put("source_hash", sourceHash)
                .put("updated_at", now)
                .put("cutoff", cutoffEntryId));
            group.put("updated_at", now);
            return group;
        });
    }

    /** Lists storage names without reading entity bodies. */
    public List<String> listStorageNames() {
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
            if (ContextStore.isValidStorageName(storageName)
                && name.equals(storageName + ".json")) {
                names.add(storageName);
            }
        }
        Collections.sort(names);
        return Collections.unmodifiableList(names);
    }

    private File fileFor(String storageName) {
        return new File(directory, storageName + ".json");
    }

    private static JSONObject summaryLanguage(
        JSONObject group,
        String targetLang
    ) {
        JSONObject summary = group.optJSONObject("summary");
        return summary == null ? null : summary.optJSONObject(targetLang);
    }

    private static boolean containsContextEntryId(
        JSONArray contexts,
        String entryId
    ) {
        if (contexts == null) {
            return false;
        }
        for (int index = 0; index < contexts.length(); index++) {
            if (entryId.equals(GroupContextEntry.entryIdAt(contexts, index))) {
                return true;
            }
        }
        return false;
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
