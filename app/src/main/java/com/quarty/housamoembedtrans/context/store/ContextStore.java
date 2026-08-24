package com.quarty.housamoembedtrans.context.store;
import com.quarty.housamoembedtrans.context.history.ContextContentHash;
import com.quarty.housamoembedtrans.context.schema.ContextGroupSchemaValidator;
import com.quarty.housamoembedtrans.storage.json.AtomicJsonFileIo;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.IOException;
import java.util.List;

/**
 * File-level store for Scene Context entities under
 * {@code files/scene_contexts/contexts/}.
 *
 * <p>This class deliberately knows nothing about the Index, Active pointers or
 * Group references. Cross-entity transactions are coordinated by
 * {@link SceneContextStore}.</p>
 */
public final class ContextStore {

    public static final String DIRECTORY_NAME = "contexts";

    private final FileEntityStore fileStore;

    @FunctionalInterface
    public interface Mutation {
        JSONObject apply(JSONObject context)
            throws IOException,
            ContextGroupSchemaValidator.ValidationException,
            org.json.JSONException;
    }

    public ContextStore(
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
            "context",
            validator::validateContext,
            io
        );
    }

    public File getDirectory() {
        return fileStore.getDirectory();
    }

    public boolean exists(String storageName) {
        return fileStore.exists(storageName);
    }

    /** Reads and schema-validates one Context entity. */
    public JSONObject read(String storageName)
        throws IOException, ContextGroupSchemaValidator.ValidationException {
        return fileStore.read(storageName);
    }

    /** Validates and atomically writes one Context entity. */
    public void write(String storageName, JSONObject context)
        throws IOException, ContextGroupSchemaValidator.ValidationException {
        fileStore.write(storageName, context);
    }

    /** Atomically reads, mutates and writes one current Context document. */
    public JSONObject mutate(String storageName, Mutation mutation)
        throws IOException,
        ContextGroupSchemaValidator.ValidationException,
        org.json.JSONException {
        requireStorageName(storageName);
        if (mutation == null) {
            throw new IllegalArgumentException("mutation is null");
        }
        return fileStore.mutate(storageName, mutation::apply);
    }

    /** Deletes one Context entity file; missing file is a no-op. */
    public void delete(String storageName) throws IOException {
        fileStore.delete(storageName);
    }

    /** Lists storage names without reading entity bodies. */
    public List<String> listStorageNames() {
        return fileStore.listStorageNames();
    }

    /** Returns the {@code entry_id} of a scene inside a Context, or null. */
    public String findSceneEntryId(String storageName, String scene)
        throws IOException, ContextGroupSchemaValidator.ValidationException {
        requireStorageName(storageName);
        if (scene == null || scene.trim().isEmpty()) {
            return null;
        }
        JSONObject context = read(storageName);
        JSONArray scenes = context.optJSONArray("scenes");
        if (scenes == null) {
            return null;
        }
        for (int index = 0; index < scenes.length(); index++) {
            JSONObject entry = scenes.optJSONObject(index);
            if (entry != null && scene.equals(entry.optString("scene", ""))) {
                return entry.optString("entry_id", null);
            }
        }
        return null;
    }

    /** Returns whether a non-empty Context Summary exists for one language. */
    public boolean hasCurrentSummary(
        String storageName,
        String targetLang
    ) throws IOException, ContextGroupSchemaValidator.ValidationException {
        JSONObject context = read(storageName);
        JSONObject lang = summaryLanguage(context, targetLang);
        return lang != null && lang.has("current") && !lang.isNull("current");
    }

    /** Returns whether a non-empty Manual Summary exists for one language. */
    public boolean hasManualSummary(
        String storageName,
        String targetLang
    ) throws IOException, ContextGroupSchemaValidator.ValidationException {
        return fileStore.hasManualSummary(storageName, targetLang);
    }

    /** Returns whether a non-empty Final Summary exists for one language. */
    public boolean hasFinalSummary(
        String storageName,
        String targetLang
    ) throws IOException, ContextGroupSchemaValidator.ValidationException {
        JSONObject context = read(storageName);
        JSONObject lang = summaryLanguage(context, targetLang);
        return lang != null && lang.has("final") && !lang.isNull("final");
    }

    /**
     * Returns a copy of one derived/manual summary record for a language, or
     * null when the kind is absent. {@code kind} is one of
     * {@code final}, {@code current}, {@code manual}.
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
     * Persists one Scene Summary into the Context member for the target
     * language. The Scene Summary is independent from the derived Context
     * Summary record and may be written before the whole translation body is
     * accepted.
     */
    public JSONObject writeSceneSummary(
        String storageName,
        String scene,
        String targetLang,
        String text
    ) throws IOException,
        ContextGroupSchemaValidator.ValidationException,
        org.json.JSONException {
        requireStorageName(storageName);
        requireNonEmpty(scene, "scene");
        requireNonEmpty(targetLang, "target_lang");
        requireNonEmpty(text, "scene summary");
        return mutate(storageName, context -> {
            JSONObject entry = requireSceneEntry(context, scene);
            JSONObject summaries = entry.optJSONObject("summaries");
            if (summaries == null) {
                summaries = new JSONObject();
                entry.put("summaries", summaries);
            }
            JSONObject languageSummary = summaries.optJSONObject(targetLang);
            if (languageSummary == null) {
                languageSummary = new JSONObject();
            }
            languageSummary.put("text", text);
            languageSummary.put("updated_at", System.currentTimeMillis());
            summaries.put(targetLang, languageSummary);
            entry.put("updated_at", System.currentTimeMillis());
            context.put("updated_at", System.currentTimeMillis());
            return context;
        });
    }

    /**
     * Writes the derived Context Summary ({@code current}) for one language.
     * The cutoff is taken from the current scene's {@code entry_id}; the
     * caller is responsible for eligibility checks such as manual suppression
     * and source-hash stability.
     */
    public JSONObject writeCurrentContextSummary(
        String storageName,
        String scene,
        String targetLang,
        String text,
        String sourceHash
    ) throws IOException,
        ContextGroupSchemaValidator.ValidationException,
        org.json.JSONException {
        requireStorageName(storageName);
        requireNonEmpty(scene, "scene");
        requireNonEmpty(targetLang, "target_lang");
        requireNonEmpty(text, "context summary");
        requireNonEmpty(sourceHash, "source_hash");
        return mutate(storageName, context -> {
            JSONObject entry = requireSceneEntry(context, scene);
            String entryId = entry.optString("entry_id", "");
            if (entryId.isEmpty()) {
                throw new IOException("scene entry_id is empty: " + scene);
            }

            JSONObject summaryContainer = context.optJSONObject("summary");
            if (summaryContainer == null) {
                summaryContainer = new JSONObject();
                context.put("summary", summaryContainer);
            }
            JSONObject lang = summaryContainer.optJSONObject(targetLang);
            if (lang == null) {
                lang = new JSONObject();
                summaryContainer.put(targetLang, lang);
            }
            long now = System.currentTimeMillis();
            JSONObject current = new JSONObject()
                .put("text", text)
                .put("source_hash", sourceHash)
                .put("updated_at", now)
                .put("cutoff", entryId);
            lang.put("current", current);
            context.put("updated_at", now);
            return context;
        });
    }

    /**
     * Atomically validates the facts observed by a Context Summary response and
     * writes {@code summary.<target_lang>.current} only when they are still
     * current.  The caller supplies the hash captured before producing the
     * response (excluding the response scene) and the full cutoff hash computed
     * after that Scene Summary was persisted.  Both checks, plus Manual Summary
     * suppression and cutoff lookup, run under the same entity lock as Context
     * edits, reorders and manual-summary writes.
     */
    public CurrentSummaryWriteResult writeCurrentContextSummaryIfFactsMatch(
        String storageName,
        String scene,
        String targetLang,
        String text,
        String capturedSourceHashExcludingScene,
        String expectedCurrentSourceHash,
        boolean continueAfterManual
    ) throws IOException,
        ContextGroupSchemaValidator.ValidationException,
        org.json.JSONException {
        requireStorageName(storageName);
        requireNonEmpty(scene, "scene");
        requireNonEmpty(targetLang, "target_lang");
        requireNonEmpty(text, "context summary");
        requireNonEmpty(expectedCurrentSourceHash, "expected source_hash");

        return fileStore.withLockedFile(storageName, file -> {
            if (!fileStore.existsFile(file)) {
                throw new SummaryTargetInvalidatedException(
                    "context target was deleted: " + storageName
                );
            }
            JSONObject context = fileStore.readUnlocked(storageName, file);
            JSONObject entry = findSceneEntry(context, scene);
            if (entry == null) {
                return CurrentSummaryWriteResult.contextChanged();
            }
            String entryId = entry.optString("entry_id", "");
            if (entryId.isEmpty()) {
                return CurrentSummaryWriteResult.contextChanged();
            }

            String currentHashExcludingScene =
                ContextContentHash.computeExcludingScene(
                    context,
                    targetLang,
                    scene
                );
            if (capturedSourceHashExcludingScene != null
                && !capturedSourceHashExcludingScene.equals(
                    currentHashExcludingScene
                )) {
                return CurrentSummaryWriteResult.contextChanged();
            }

            if (!continueAfterManual
                && FileEntityStore.hasManualSummary(context, targetLang)) {
                return CurrentSummaryWriteResult.manualSummaryActive();
            }

            String currentSourceHash = ContextContentHash.computeToCutoff(
                context,
                targetLang,
                entryId
            );
            if (!expectedCurrentSourceHash.equals(currentSourceHash)) {
                return CurrentSummaryWriteResult.contextChanged();
            }

            JSONObject summaryContainer = context.optJSONObject("summary");
            if (summaryContainer == null) {
                summaryContainer = new JSONObject();
                context.put("summary", summaryContainer);
            }
            JSONObject lang = summaryContainer.optJSONObject(targetLang);
            if (lang == null) {
                lang = new JSONObject();
                summaryContainer.put(targetLang, lang);
            }
            long now = System.currentTimeMillis();
            lang.put("current", new JSONObject()
                .put("text", text)
                .put("source_hash", currentSourceHash)
                .put("updated_at", now)
                .put("cutoff", entryId));
            context.put("updated_at", now);
            fileStore.writeUnlocked(storageName, file, context);
            return CurrentSummaryWriteResult.written(
                entryId,
                currentSourceHash
            );
        });
    }

    /** Outcome of one atomic current-summary write attempt. */
    public enum CurrentSummaryWriteStatus {
        WRITTEN,
        CONTEXT_CHANGED,
        MANUAL_SUMMARY_ACTIVE
    }

    /** Immutable outcome carrying the facts confirmed by a successful write. */
    public static final class CurrentSummaryWriteResult {
        public final CurrentSummaryWriteStatus status;
        public final String entryId;
        public final String sourceHash;

        private CurrentSummaryWriteResult(
            CurrentSummaryWriteStatus status,
            String entryId,
            String sourceHash
        ) {
            this.status = status;
            this.entryId = entryId;
            this.sourceHash = sourceHash;
        }

        private static CurrentSummaryWriteResult written(
            String entryId,
            String sourceHash
        ) {
            return new CurrentSummaryWriteResult(
                CurrentSummaryWriteStatus.WRITTEN,
                entryId,
                sourceHash
            );
        }

        private static CurrentSummaryWriteResult contextChanged() {
            return new CurrentSummaryWriteResult(
                CurrentSummaryWriteStatus.CONTEXT_CHANGED,
                null,
                null
            );
        }

        private static CurrentSummaryWriteResult manualSummaryActive() {
            return new CurrentSummaryWriteResult(
                CurrentSummaryWriteStatus.MANUAL_SUMMARY_ACTIVE,
                null,
                null
            );
        }
    }

    /**
     * Writes a Context current snapshot at an explicit entry cutoff.  Summary
     * Jobs persist the entry id rather than a scene name, so the cutoff is
     * revalidated inside the same entity mutation that writes the record.
     */
    public JSONObject writeCurrentContextSummaryAtCutoff(
        String storageName,
        String targetLang,
        String cutoffEntryId,
        String text,
        String sourceHash
    ) throws IOException,
        ContextGroupSchemaValidator.ValidationException,
        org.json.JSONException {
        requireStorageName(storageName);
        requireNonEmpty(targetLang, "target_lang");
        requireNonEmpty(cutoffEntryId, "cutoff_entry_id");
        requireNonEmpty(text, "context summary");
        requireNonEmpty(sourceHash, "source_hash");
        return mutate(storageName, context -> {
            if (!containsEntryId(context, cutoffEntryId)) {
                throw new SummaryTargetInvalidatedException(
                    "context cutoff entry is no longer a member: "
                        + cutoffEntryId
                );
            }
            JSONObject summaryContainer = context.optJSONObject("summary");
            if (summaryContainer == null) {
                summaryContainer = new JSONObject();
                context.put("summary", summaryContainer);
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
            context.put("updated_at", now);
            return context;
        });
    }

    /**
     * Writes the derived Final Summary ({@code summary.<lang>.final}) for one
     * language. The caller is responsible for eligibility checks such as manual
     * suppression and source-hash stability.
     */
    public JSONObject writeFinalSummary(
        String storageName,
        String targetLang,
        String text,
        String sourceHash
    ) throws IOException,
        ContextGroupSchemaValidator.ValidationException,
        org.json.JSONException {
        requireStorageName(storageName);
        requireNonEmpty(targetLang, "target_lang");
        requireNonEmpty(text, "final summary");
        requireNonEmpty(sourceHash, "source_hash");
        return mutate(storageName, context -> {
            JSONObject summaryContainer = context.optJSONObject("summary");
            if (summaryContainer == null) {
                summaryContainer = new JSONObject();
                context.put("summary", summaryContainer);
            }
            JSONObject lang = summaryContainer.optJSONObject(targetLang);
            if (lang == null) {
                lang = new JSONObject();
                summaryContainer.put(targetLang, lang);
            }
            long now = System.currentTimeMillis();
            JSONObject finalRecord = new JSONObject()
                .put("text", text)
                .put("source_hash", sourceHash)
                .put("updated_at", now);
            lang.put("final", finalRecord);
            context.put("updated_at", now);
            return context;
        });
    }

    /**
     * Writes or replaces the user's Manual Summary
     * ({@code summary.<lang>.manual}). Automatic records in the same language
     * object are preserved.
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

    /**
     * Computes the Context Content Hash for one target language. The hash is
     * the deterministic semantic input used as {@code source_hash} on derived
     * Context Summary records. It includes ordered scene references, the
     * adopted scene-summary language/text, and manual descriptions, while
     * excluding timestamps, revision, and all derived summary records.
     */
    public String computeContextSourceHash(String storageName, String targetLang)
        throws IOException,
        ContextGroupSchemaValidator.ValidationException,
        org.json.JSONException {
        return ContextContentHash.compute(read(storageName), targetLang);
    }

    /**
     * Computes the Context Content Hash while deliberately excluding one
     * scene's summaries. This is used to detect unrelated fact changes while a
     * Translation Request is producing the excluded scene's own Scene Summary.
     */
    public String computeContextSourceHashExcludingScene(
        String storageName,
        String scene,
        String targetLang
    ) throws IOException,
        ContextGroupSchemaValidator.ValidationException,
        org.json.JSONException {
        requireNonEmpty(scene, "scene");
        return ContextContentHash.computeExcludingScene(
            read(storageName),
            targetLang,
            scene
        );
    }

    /**
     * Computes the Context Content Hash for the prefix up to and including one
     * scene entry. This is the {@code source_hash} used by
     * {@code summary.<target_lang>.current}, whose semantic input only covers
     * the context through its own {@code cutoff}.
     */
    public String computeContextSourceHashToCutoff(
        String storageName,
        String targetLang,
        String cutoffEntryId
    ) throws IOException,
        ContextGroupSchemaValidator.ValidationException,
        org.json.JSONException {
        requireStorageName(storageName);
        requireNonEmpty(targetLang, "target_lang");
        requireNonEmpty(cutoffEntryId, "cutoff_entry_id");
        return ContextContentHash.computeToCutoff(
            read(storageName),
            targetLang,
            cutoffEntryId
        );
    }

    private static JSONObject summaryLanguage(
        JSONObject context,
        String targetLang
    ) {
        JSONObject summary = context.optJSONObject("summary");
        return summary == null ? null : summary.optJSONObject(targetLang);
    }

    private static JSONObject findSceneEntry(JSONObject context, String scene) {
        JSONArray scenes = context.optJSONArray("scenes");
        if (scenes == null) {
            return null;
        }
        for (int index = 0; index < scenes.length(); index++) {
            JSONObject entry = scenes.optJSONObject(index);
            if (entry != null && scene.equals(entry.optString("scene", ""))) {
                return entry;
            }
        }
        return null;
    }

    private static JSONObject requireSceneEntry(JSONObject context, String scene)
        throws IOException {
        JSONObject entry = findSceneEntry(context, scene);
        if (entry != null) {
            return entry;
        }
        throw new IOException("scene is not a member of this context: " + scene);
    }

    private static boolean containsEntryId(
        JSONObject context,
        String entryId
    ) {
        JSONArray scenes = context.optJSONArray("scenes");
        if (scenes == null) {
            return false;
        }
        for (int index = 0; index < scenes.length(); index++) {
            JSONObject entry = scenes.optJSONObject(index);
            if (entry != null && entryId.equals(
                entry.optString("entry_id", "")
            )) {
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

    static boolean isValidStorageName(String value) {
        return FileEntityStore.isValidStorageName(value);
    }

    static String requireStorageName(String value) {
        return FileEntityStore.requireStorageName(value);
    }
}
