package com.quarty.housamoembedtrans.storage;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.IOException;
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

    private final FileEntityStore fileStore;

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
        this.fileStore = new FileEntityStore(
            directory,
            "group",
            validator::validateGroup,
            io
        );
    }

    public File getDirectory() {
        return fileStore.getDirectory();
    }

    public boolean exists(String storageName) {
        return fileStore.exists(storageName);
    }

    /** Reads and schema-validates one Context Group entity. */
    public JSONObject read(String storageName)
        throws IOException, ContextGroupSchemaValidator.ValidationException {
        return fileStore.read(storageName);
    }

    /** Validates and atomically writes one Context Group entity. */
    public void write(String storageName, JSONObject group)
        throws IOException, ContextGroupSchemaValidator.ValidationException {
        fileStore.write(storageName, group);
    }

    /** Atomically reads, mutates and writes one current Group document. */
    public JSONObject mutate(String storageName, Mutation mutation)
        throws IOException,
        ContextGroupSchemaValidator.ValidationException,
        org.json.JSONException {
        FileEntityStore.requireStorageName(storageName);
        if (mutation == null) {
            throw new IllegalArgumentException("mutation is null");
        }
        return fileStore.mutate(storageName, mutation::apply);
    }

    /** Deletes one Context Group entity file; missing file is a no-op. */
    public void delete(String storageName) throws IOException {
        fileStore.delete(storageName);
    }

    /** Returns whether a non-empty Manual Summary exists for one language. */
    public boolean hasManualSummary(
        String storageName,
        String targetLang
    ) throws IOException, ContextGroupSchemaValidator.ValidationException {
        return fileStore.hasManualSummary(storageName, targetLang);
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
        return fileStore.getSummaryRecord(storageName, targetLang, kind);
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
        return fileStore.writeManualSummary(storageName, targetLang, text);
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
        return fileStore.deleteManualSummary(storageName, targetLang);
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
        FileEntityStore.requireStorageName(storageName);
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
        return fileStore.listStorageNames();
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

}
