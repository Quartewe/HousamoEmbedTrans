package com.quarty.housamoembedtrans.context.store;
import com.quarty.housamoembedtrans.context.model.GroupContextEntry;
import com.quarty.housamoembedtrans.context.review.ReviewTransactionJournal;
import com.quarty.housamoembedtrans.context.schema.ContextGroupSchemaValidator;
import com.quarty.housamoembedtrans.storage.json.AtomicJsonFileIo;

import android.content.Context;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Coordinating Store for Scene Context entities, Context Group entities and
 * the Scene Context Index.
 *
 * <p>All formal writes are atomic at the file level and additionally journaled
 * as one transaction when more than one file changes (create/delete/Active
 * pointer/Group propagation). A crash mid-transaction is rolled back to the
 * previous consistent state on the next {@link #recover()} call.</p>
 */
public final class SceneContextStore {

    public static final String DIRECTORY_NAME = "scene_contexts";
    public static final String TXN_DIRECTORY_NAME = ".txn";
    public static final int FORMAT_VERSION = 1;
    public static final int DEFAULT_RECENT_PERCENT = 30;
    public static final int DEFAULT_RECENT_LIMIT = 10;
    public static final int MAX_DOCUMENT_BYTES = 4 * 1024 * 1024;
    private static final int TXN_FORMAT_VERSION = 1;
    private static final String ID_PATTERN = "^[A-Za-z0-9][A-Za-z0-9_-]*$";
    private static final int ID_MAX_LENGTH = 80;

    /**
     * Process-wide gate shared by every SceneContextStore facade.  UI Review,
     * import, History resolution, and Summary write-back otherwise construct
     * separate facades and can observe a multi-file transaction between its
     * atomic file replacements.
     */
    public static final Object ROOT_ACCESS_LOCK = new Object();

    @FunctionalInterface
    public interface RootAccess<T> {
        T run() throws Exception;
    }

    public static <T> T withRootAccess(RootAccess<T> access) throws Exception {
        if (access == null) {
            throw new IllegalArgumentException("access is required");
        }
        synchronized (ROOT_ACCESS_LOCK) {
            return access.run();
        }
    }

    public enum FailureKind {
        NOT_FOUND,
        ALREADY_EXISTS,
        CONFLICT,
        INVALID_ARGUMENT,
        INVALID_STATE,
        INVALID_ACTIVE_GROUP,
        IO
    }

    /** Optional observer for Active Context pointer changes. */
    public interface ActiveContextChangeListener {
        void onActiveContextChanged(String previousContextId, String newContextId);
    }

    public static final class StorageException extends IOException {
        private static final long serialVersionUID = 1L;
        public final FailureKind kind;

        public StorageException(FailureKind kind, String message) {
            super(message);
            this.kind = kind;
        }

        public StorageException(FailureKind kind, String message, Throwable cause) {
            super(message, cause);
            this.kind = kind;
        }
    }

    private final File rootDirectory;
    private final ContextGroupSchemaValidator validator;
    private final AtomicJsonFileIo io;
    private final ContextStore contextStore;
    private final GroupStore groupStore;
    private final SceneContextIndexStore indexStore;
    private volatile ActiveContextChangeListener activeContextChangeListener =
        (previous, current) -> { };

    public SceneContextStore(Context context) {
        this(
            new File(
                requireContext(context).getFilesDir(),
                DIRECTORY_NAME
            ),
            ContextGroupSchemaValidator.loadFromAssets(context),
            AtomicJsonFileIo.android()
        );
    }

    /** Explicit directory/validator/IO seam used by service and host tests. */
    public SceneContextStore(
        File rootDirectory,
        ContextGroupSchemaValidator validator,
        AtomicJsonFileIo io
    ) {
        if (rootDirectory == null || validator == null || io == null) {
            throw new IllegalArgumentException(
                "rootDirectory, validator and io are required"
            );
        }
        this.rootDirectory = rootDirectory;
        this.validator = validator;
        this.io = io;
        contextStore = new ContextStore(
            new File(rootDirectory, ContextStore.DIRECTORY_NAME),
            validator,
            io
        );
        groupStore = new GroupStore(
            new File(rootDirectory, GroupStore.DIRECTORY_NAME),
            validator,
            io
        );
        indexStore = new SceneContextIndexStore(
            new File(rootDirectory, SceneContextIndexStore.FILE_NAME),
            validator,
            io
        );
        try {
            synchronized (ROOT_ACCESS_LOCK) {
                ReviewTransactionJournal.recover(
                    rootDirectory.getParentFile()
                );
                recover();
            }
        } catch (StorageException e) {
            throw new IllegalStateException(
                "could not recover scene context storage",
                e
            );
        }
    }

    public File getDirectory() {
        return rootDirectory;
    }

    public ContextStore getContextStore() {
        return contextStore;
    }

    public GroupStore getGroupStore() {
        return groupStore;
    }

    public SceneContextIndexStore getIndexStore() {
        return indexStore;
    }

    /** Conflict policy for a user-selected Context/Group import bundle. */
    public enum ImportConflictPolicy {
        OVERWRITE,
        COPY,
        SKIP
    }

    /** Counts and id remapping produced by one atomic import. */
    public static final class ImportResult {
        public int contextsImported;
        public int contextsOverwritten;
        public int contextsCopied;
        public int contextsSkipped;
        public int groupsImported;
        public int groupsOverwritten;
        public int groupsCopied;
        public int groupsSkipped;
        public int groupsSkippedMissingReferences;
        public final Map<String, String> contextIdMap =
            new LinkedHashMap<>();
        public final Map<String, String> groupIdMap =
            new LinkedHashMap<>();
    }

    /** Result of atomically replacing the complete Review-owned state. */
    public static final class ReviewStateResult {
        public int contextsCreated;
        public int contextsUpdated;
        public int contextsDeleted;
        public int groupsCreated;
        public int groupsUpdated;
        public int groupsDeleted;
        public boolean activePointersChanged;
        public String activeContextId;
        public String activeGroupId;
        public final Map<String, String> contextIdMap =
            new LinkedHashMap<>();
        public final Map<String, String> groupIdMap =
            new LinkedHashMap<>();
        public final List<String> contextIds = new ArrayList<>();
        public final List<String> groupIds = new ArrayList<>();
    }

    /**
     * Read-only result of validating an import bundle before showing any
     * conflict prompts. Missing Context references are reported as skippable
     * Groups; malformed documents, duplicate IDs, duplicate Group entries and
     * invalid entry shapes still fail the preflight.
     */
    public static final class ImportInspection {
        public final List<String> contextIds;
        public final List<String> groupIds;
        public final Set<String> conflictingContextIds;
        public final Set<String> conflictingGroupIds;
        public final int groupsWithMissingReferences;

        private ImportInspection(
            List<String> contextIds,
            List<String> groupIds,
            Set<String> conflictingContextIds,
            Set<String> conflictingGroupIds,
            int groupsWithMissingReferences
        ) {
            this.contextIds = Collections.unmodifiableList(
                new ArrayList<>(contextIds)
            );
            this.groupIds = Collections.unmodifiableList(
                new ArrayList<>(groupIds)
            );
            this.conflictingContextIds = Collections.unmodifiableSet(
                new LinkedHashSet<>(conflictingContextIds)
            );
            this.conflictingGroupIds = Collections.unmodifiableSet(
                new LinkedHashSet<>(conflictingGroupIds)
            );
            this.groupsWithMissingReferences = groupsWithMissingReferences;
        }
    }

    /**
     * Validates every document and reference without writing storage. UI
     * callers should run this before opening per-ID conflict dialogs; the
     * mutating import method repeats the validation under its commit lock.
     */
    public ImportInspection inspectImportBundle(JSONObject bundle)
        throws StorageException {
        try {
            return withRootAccess(() -> inspectImportBundleLocked(bundle));
        } catch (StorageException e) {
            throw e;
        } catch (Exception e) {
            throw new StorageException(
                FailureKind.IO,
                "could not inspect Context/Group bundle",
                e
            );
        }
    }

    private ImportInspection inspectImportBundleLocked(JSONObject bundle)
        throws Exception {
        if (bundle == null || bundle.optInt("version", -1) != FORMAT_VERSION) {
            throw new StorageException(
                FailureKind.INVALID_ARGUMENT,
                "unsupported Context/Group import version"
            );
        }
        JSONArray contextArray = bundle.optJSONArray("contexts");
        JSONArray groupArray = bundle.optJSONArray("groups");
        if (contextArray == null || groupArray == null) {
            throw new StorageException(
                FailureKind.INVALID_ARGUMENT,
                "import bundle requires contexts and groups arrays"
            );
        }

        List<String> contextIds = new ArrayList<>();
        Set<String> importedContextIds = new LinkedHashSet<>();
        for (int i = 0; i < contextArray.length(); i++) {
            JSONObject context = contextArray.optJSONObject(i);
            if (context == null) {
                throw new StorageException(
                    FailureKind.INVALID_ARGUMENT,
                    "contexts[" + i + "] must be an object"
                );
            }
            JSONObject copy = copyJsonForImport(context, "context", i);
            validateContextDocument(copy);
            String id = copy.optString("id", "");
            if (!importedContextIds.add(id)) {
                throw new StorageException(
                    FailureKind.INVALID_ARGUMENT,
                    "duplicate imported Context id: " + id
                );
            }
            contextIds.add(id);
        }

        List<String> groupIds = new ArrayList<>();
        List<JSONObject> importedGroups = new ArrayList<>();
        Set<String> importedGroupIds = new LinkedHashSet<>();
        for (int i = 0; i < groupArray.length(); i++) {
            JSONObject group = groupArray.optJSONObject(i);
            if (group == null) {
                throw new StorageException(
                    FailureKind.INVALID_ARGUMENT,
                    "groups[" + i + "] must be an object"
                );
            }
            JSONObject copy = copyJsonForImport(group, "group", i);
            validateGroupDocument(copy);
            String id = copy.optString("id", "");
            if (!importedGroupIds.add(id)) {
                throw new StorageException(
                    FailureKind.INVALID_ARGUMENT,
                    "duplicate imported Group id: " + id
                );
            }
            groupIds.add(id);
            importedGroups.add(copy);
        }

        Set<String> existingContextIds = new HashSet<>(listContextIds());
        Set<String> existingGroupIds = new HashSet<>(listGroupIds());
        Set<String> availableContextIds = new HashSet<>(existingContextIds);
        availableContextIds.addAll(importedContextIds);
        int groupsWithMissingReferences = 0;
        for (int groupIndex = 0; groupIndex < importedGroups.size(); groupIndex++) {
            JSONArray entries = importedGroups.get(groupIndex)
                .optJSONArray("contexts");
            if (entries == null) {
                throw new StorageException(
                    FailureKind.INVALID_ARGUMENT,
                    "groups[" + groupIndex + "].contexts is missing"
                );
            }
            Set<String> seenContextIds = new HashSet<>();
            Set<String> seenEntryIds = new HashSet<>();
            boolean missing = false;
            for (int entryIndex = 0; entryIndex < entries.length(); entryIndex++) {
                JSONObject entry;
                try {
                    entry = GroupContextEntry.require(entries, entryIndex);
                } catch (IllegalArgumentException e) {
                    throw new StorageException(
                        FailureKind.INVALID_ARGUMENT,
                        e.getMessage(),
                        e
                    );
                }
                String contextId = entry.optString(
                    GroupContextEntry.CONTEXT_ID,
                    ""
                );
                String entryId = entry.optString(
                    GroupContextEntry.ENTRY_ID,
                    ""
                );
                if (!seenContextIds.add(contextId)) {
                    throw new StorageException(
                        FailureKind.INVALID_ARGUMENT,
                        "group contexts must not contain duplicates: "
                            + contextId
                    );
                }
                if (!seenEntryIds.add(entryId)) {
                    throw new StorageException(
                        FailureKind.INVALID_ARGUMENT,
                        "group context_entry_id must be unique: " + entryId
                    );
                }
                if (!availableContextIds.contains(contextId)) {
                    missing = true;
                }
            }
            if (missing) {
                groupsWithMissingReferences++;
            }
        }

        Set<String> conflictingContextIds = new LinkedHashSet<>(contextIds);
        conflictingContextIds.retainAll(existingContextIds);
        Set<String> conflictingGroupIds = new LinkedHashSet<>(groupIds);
        conflictingGroupIds.retainAll(existingGroupIds);
        return new ImportInspection(
            contextIds,
            groupIds,
            conflictingContextIds,
            conflictingGroupIds,
            groupsWithMissingReferences
        );
    }

    /**
     * Imports a complete Context/Group bundle in one transaction.  Every
     * document is schema-validated and every Group reference is resolved
     * before the first file is written.  Active pointers are deliberately
     * taken from the existing index and are never imported.
     */
    public ImportResult importBundle(
        JSONObject bundle,
        ImportConflictPolicy policy
    ) throws StorageException {
        if (policy == null) {
            throw new StorageException(
                FailureKind.INVALID_ARGUMENT,
                "import conflict policy is required"
            );
        }
        return importBundle(
            bundle,
            Collections.emptyMap(),
            Collections.emptyMap(),
            policy
        );
    }

    /**
     * Imports with an independent conflict choice for each source UUID. IDs
     * absent from either map use {@code defaultPolicy}; all choices still
     * share one atomic commit and one full pre-write validation pass.
     */
    public ImportResult importBundle(
        JSONObject bundle,
        Map<String, ImportConflictPolicy> contextPolicies,
        Map<String, ImportConflictPolicy> groupPolicies,
        ImportConflictPolicy defaultPolicy
    ) throws StorageException {
        if (defaultPolicy == null) {
            throw new StorageException(
                FailureKind.INVALID_ARGUMENT,
                "import conflict policy is required"
            );
        }
        try {
            return withRootAccess(() -> importBundleLocked(
                bundle,
                contextPolicies == null
                    ? Collections.emptyMap()
                    : contextPolicies,
                groupPolicies == null
                    ? Collections.emptyMap()
                    : groupPolicies,
                defaultPolicy
            ));
        } catch (StorageException e) {
            throw e;
        } catch (Exception e) {
            throw new StorageException(
                FailureKind.IO,
                "could not import Context/Group bundle",
                e
            );
        }
    }

    /** Exports only Context and Group documents; no active pointers or jobs. */
    public JSONObject exportBundle() throws StorageException {
        try {
            return withRootAccess(() -> {
                JSONObject bundle = new JSONObject();
                put(bundle, "version", FORMAT_VERSION);
                JSONArray exportedContexts = new JSONArray();
                for (JSONObject context : listContexts()) {
                    exportedContexts.put(copyJsonForImport(context, "context", 0));
                }
                JSONArray exportedGroups = new JSONArray();
                for (JSONObject group : listGroups()) {
                    exportedGroups.put(copyJsonForImport(group, "group", 0));
                }
                put(bundle, "contexts", exportedContexts);
                put(bundle, "groups", exportedGroups);
                return bundle;
            });
        } catch (StorageException e) {
            throw e;
        } catch (Exception e) {
            throw new StorageException(
                FailureKind.IO,
                "could not export Context/Group bundle",
                e
            );
        }
    }

    private ImportResult importBundleLocked(
        JSONObject bundle,
        Map<String, ImportConflictPolicy> contextPolicies,
        Map<String, ImportConflictPolicy> groupPolicies,
        ImportConflictPolicy defaultPolicy
    ) throws Exception {
        if (bundle == null || bundle.optInt("version", -1) != FORMAT_VERSION) {
            throw new StorageException(
                FailureKind.INVALID_ARGUMENT,
                "unsupported Context/Group import version"
            );
        }
        JSONArray contextArray = bundle.optJSONArray("contexts");
        JSONArray groupArray = bundle.optJSONArray("groups");
        if (contextArray == null || groupArray == null) {
            throw new StorageException(
                FailureKind.INVALID_ARGUMENT,
                "import bundle requires contexts and groups arrays"
            );
        }

        List<JSONObject> importedContexts = new ArrayList<>();
        Set<String> importedContextIds = new HashSet<>();
        for (int i = 0; i < contextArray.length(); i++) {
            JSONObject context = contextArray.optJSONObject(i);
            if (context == null) {
                throw new StorageException(
                    FailureKind.INVALID_ARGUMENT,
                    "contexts[" + i + "] must be an object"
                );
            }
            JSONObject copy = copyJsonForImport(context, "context", i);
            validateContextDocument(copy);
            String id = copy.optString("id", "");
            if (!importedContextIds.add(id)) {
                throw new StorageException(
                    FailureKind.INVALID_ARGUMENT,
                    "duplicate imported Context id: " + id
                );
            }
            importedContexts.add(copy);
        }

        List<JSONObject> importedGroups = new ArrayList<>();
        Set<String> importedGroupIds = new HashSet<>();
        for (int i = 0; i < groupArray.length(); i++) {
            JSONObject group = groupArray.optJSONObject(i);
            if (group == null) {
                throw new StorageException(
                    FailureKind.INVALID_ARGUMENT,
                    "groups[" + i + "] must be an object"
                );
            }
            JSONObject copy = copyJsonForImport(group, "group", i);
            validateGroupDocument(copy);
            String id = copy.optString("id", "");
            if (!importedGroupIds.add(id)) {
                throw new StorageException(
                    FailureKind.INVALID_ARGUMENT,
                    "duplicate imported Group id: " + id
                );
            }
            importedGroups.add(copy);
        }

        JSONObject index = readIndex();
        JSONObject contextIndex = index.optJSONObject("contexts");
        JSONObject groupIndex = index.optJSONObject("groups");
        if (contextIndex == null || groupIndex == null) {
            throw new StorageException(
                FailureKind.INVALID_STATE,
                "Context/Group index maps are missing"
            );
        }

        Map<String, JSONObject> existingContexts = new LinkedHashMap<>();
        for (JSONObject context : listContexts()) {
            existingContexts.put(context.optString("id", ""), context);
        }
        Map<String, JSONObject> existingGroups = new LinkedHashMap<>();
        for (JSONObject group : listGroups()) {
            existingGroups.put(group.optString("id", ""), group);
        }
        Set<String> usedContextStorage = storageNames(existingContexts);
        Set<String> usedGroupStorage = storageNames(existingGroups);
        ImportResult result = new ImportResult();
        List<Mutation> mutations = new ArrayList<>();
        Set<String> plannedContextIds = new HashSet<>();

        for (JSONObject source : importedContexts) {
            String sourceId = source.optString("id", "");
            JSONObject existing = existingContexts.get(sourceId);
            ImportConflictPolicy policy = contextPolicies.getOrDefault(
                sourceId,
                defaultPolicy
            );
            if (existing != null && policy == ImportConflictPolicy.SKIP) {
                result.contextsSkipped++;
                result.contextIdMap.put(sourceId, sourceId);
                continue;
            }
            boolean copy = existing != null
                && policy == ImportConflictPolicy.COPY;
            String targetId = copy
                ? uniqueImportId(existingContexts.keySet(), plannedContextIds)
                : sourceId;
            String targetStorage = existing != null
                ? stripJsonSuffix(existing.optString("storage_name", ""))
                : uniqueImportStorage(
                    source.optString("storage_name", ""),
                    usedContextStorage,
                    "context"
                );
            if (copy) {
                targetStorage = uniqueImportStorage(
                    source.optString("storage_name", ""),
                    usedContextStorage,
                    "context"
                );
            }
            JSONObject target = copyJsonForImport(source, "context", 0);
            put(target, "id", targetId);
            put(target, "storage_name", targetStorage);
            if (existing != null && !copy) {
                put(target, "revision", existing.optLong("revision", 0L) + 1L);
                put(target, "created_at", existing.optLong("created_at", 0L));
                put(target, "updated_at", System.currentTimeMillis());
                result.contextsOverwritten++;
            } else if (copy) {
                long now = System.currentTimeMillis();
                put(target, "revision", 1L);
                put(target, "created_at", now);
                put(target, "updated_at", now);
                result.contextsCopied++;
            } else {
                result.contextsImported++;
            }
            validateContextDocument(target);
            result.contextIdMap.put(sourceId, targetId);
            plannedContextIds.add(targetId);
            put(contextIndex, targetId, targetStorage + ".json");
            mutations.add(mutation(
                ContextStore.DIRECTORY_NAME + "/" + targetStorage + ".json",
                contextStore.getDirectory(),
                target
            ));
        }

        Set<String> availableContextIds = new HashSet<>(existingContexts.keySet());
        availableContextIds.addAll(plannedContextIds);
        Set<String> plannedGroupIds = new HashSet<>();
        Map<String, JSONObject> finalGroups = new LinkedHashMap<>(
            existingGroups
        );
        for (JSONObject source : importedGroups) {
            String sourceId = source.optString("id", "");
            JSONObject existing = existingGroups.get(sourceId);
            ImportConflictPolicy policy = groupPolicies.getOrDefault(
                sourceId,
                defaultPolicy
            );
            if (existing != null && policy == ImportConflictPolicy.SKIP) {
                result.groupsSkipped++;
                result.groupIdMap.put(sourceId, sourceId);
                continue;
            }
            boolean copy = existing != null
                && policy == ImportConflictPolicy.COPY;
            JSONArray sourceEntries = source.optJSONArray("contexts");
            JSONArray targetEntries = new JSONArray();
            boolean missingReference = false;
            for (int i = 0; i < sourceEntries.length(); i++) {
                JSONObject entry = GroupContextEntry.require(sourceEntries, i);
                String sourceContextId = entry.optString(
                    GroupContextEntry.CONTEXT_ID,
                    ""
                );
                String targetContextId = result.contextIdMap.get(sourceContextId);
                if (targetContextId == null) {
                    targetContextId = sourceContextId;
                }
                if (!availableContextIds.contains(targetContextId)) {
                    missingReference = true;
                    break;
                }
                JSONObject targetEntry = copy
                    ? GroupContextEntry.create(targetContextId)
                    : copyJsonForImport(entry, "group context", i);
                put(
                    targetEntry,
                    GroupContextEntry.CONTEXT_ID,
                    targetContextId
                );
                targetEntries.put(targetEntry);
            }
            if (missingReference) {
                result.groupsSkippedMissingReferences++;
                result.groupsSkipped++;
                continue;
            }
            String targetId = copy
                ? uniqueImportId(existingGroups.keySet(), plannedGroupIds)
                : sourceId;
            String targetStorage = existing != null
                ? stripJsonSuffix(existing.optString("storage_name", ""))
                : uniqueImportStorage(
                    source.optString("storage_name", ""),
                    usedGroupStorage,
                    "group"
                );
            if (copy) {
                targetStorage = uniqueImportStorage(
                    source.optString("storage_name", ""),
                    usedGroupStorage,
                    "group"
                );
            }
            JSONObject target = copyJsonForImport(source, "group", 0);
            put(target, "id", targetId);
            put(target, "storage_name", targetStorage);
            put(target, "contexts", targetEntries);
            if (existing != null && !copy) {
                put(target, "revision", existing.optLong("revision", 0L) + 1L);
                put(target, "created_at", existing.optLong("created_at", 0L));
                put(target, "updated_at", System.currentTimeMillis());
                result.groupsOverwritten++;
            } else if (copy) {
                long now = System.currentTimeMillis();
                put(target, "revision", 1L);
                put(target, "created_at", now);
                put(target, "updated_at", now);
                result.groupsCopied++;
            } else {
                result.groupsImported++;
            }
            validateGroupDocument(target);
            result.groupIdMap.put(sourceId, targetId);
            plannedGroupIds.add(targetId);
            finalGroups.put(targetId, target);
            put(groupIndex, targetId, targetStorage + ".json");
            mutations.add(mutation(
                GroupStore.DIRECTORY_NAME + "/" + targetStorage + ".json",
                groupStore.getDirectory(),
                target
            ));
        }

        String activeContextId = nullableString(index, "active_context_id");
        String activeGroupId = nullableString(index, "active_group_id");
        if (activeContextId != null && activeGroupId != null) {
            JSONObject activeGroup = finalGroups.get(activeGroupId);
            if (activeGroup == null
                || !containsContext(activeGroup, activeContextId)) {
                throw new StorageException(
                    FailureKind.INVALID_ACTIVE_GROUP,
                    "import would leave active group " + activeGroupId
                        + " without active context " + activeContextId
                );
            }
        }

        // The imported bundle never carries active_context_id/active_group_id.
        mutations.add(mutationIndex(index));
        commitMutations(mutations);
        return result;
    }

    /**
     * Registers an observer for Active Context changes. The observer is invoked
     * after the index commit; it must not perform blocking API work itself.
     */
    public void setActiveContextChangeListener(
        ActiveContextChangeListener listener
    ) {
        if (listener == null) {
            throw new IllegalArgumentException("listener is required");
        }
        this.activeContextChangeListener = listener;
    }

    /**
     * Atomically validates and replaces the complete Context/Group Review
     * state. New draft IDs are remapped before any validation; CAS revisions,
     * Group references and both Active pointers are checked against the same
     * final snapshot, then all entity files and index.json share one journal.
     */
    public ReviewStateResult commitReviewState(
        List<JSONObject> contextDrafts,
        List<JSONObject> groupDrafts,
        String requestedActiveContextId,
        String requestedActiveGroupId
    ) throws StorageException {
        final ReviewStateResult result;
        final String previousActiveContextId;
        final ActiveContextChangeListener listener;
        synchronized (ROOT_ACCESS_LOCK) {
            synchronized (this) {
                JSONObject beforeIndex = readIndex();
                previousActiveContextId = nullableString(
                    beforeIndex,
                    "active_context_id"
                );
                result = commitReviewStateLocked(
                    beforeIndex,
                    contextDrafts,
                    groupDrafts,
                    requestedActiveContextId,
                    requestedActiveGroupId
                );
                listener = activeContextChangeListener;
            }
        }
        if (!sameNullable(previousActiveContextId, result.activeContextId)) {
            listener.onActiveContextChanged(
                previousActiveContextId,
                result.activeContextId
            );
        }
        return result;
    }

    private ReviewStateResult commitReviewStateLocked(
        JSONObject beforeIndex,
        List<JSONObject> contextDrafts,
        List<JSONObject> groupDrafts,
        String requestedActiveContextId,
        String requestedActiveGroupId
    ) throws StorageException {
        if (contextDrafts == null || groupDrafts == null) {
            throw new StorageException(
                FailureKind.INVALID_ARGUMENT,
                "Context and Group Review drafts are required"
            );
        }
        final JSONObject finalIndex;
        try {
            finalIndex = new JSONObject(beforeIndex.toString());
        } catch (org.json.JSONException e) {
            throw new StorageException(
                FailureKind.INVALID_STATE,
                "could not copy Context/Group index",
                e
            );
        }
        JSONObject previousContextIndex = beforeIndex.optJSONObject("contexts");
        JSONObject previousGroupIndex = beforeIndex.optJSONObject("groups");
        if (previousContextIndex == null || previousGroupIndex == null) {
            throw new StorageException(
                FailureKind.INVALID_STATE,
                "Context/Group index maps are missing"
            );
        }

        Map<String, JSONObject> existingContexts = new LinkedHashMap<>();
        for (JSONObject context : listContexts()) {
            existingContexts.put(context.optString("id", ""), context);
        }
        Map<String, JSONObject> existingGroups = new LinkedHashMap<>();
        for (JSONObject group : listGroups()) {
            existingGroups.put(group.optString("id", ""), group);
        }

        ReviewStateResult result = new ReviewStateResult();
        List<Mutation> mutations = new ArrayList<>();
        JSONObject finalContextIndex = new JSONObject();
        Map<String, JSONObject> finalContexts = new LinkedHashMap<>();
        Set<String> seenContextDraftIds = new HashSet<>();
        long now = System.currentTimeMillis();

        for (JSONObject sourceDraft : contextDrafts) {
            if (sourceDraft == null) {
                throw new StorageException(
                    FailureKind.INVALID_ARGUMENT,
                    "Context Review draft is null"
                );
            }
            JSONObject draft = copyReviewDraft(sourceDraft, "Context");
            String draftId = draft.optString("id", "");
            if (!draftId.isEmpty() && !seenContextDraftIds.add(draftId)) {
                throw new StorageException(
                    FailureKind.INVALID_ARGUMENT,
                    "duplicate Context Review draft id: " + draftId
                );
            }
            boolean create = draftId.isEmpty() || draftId.startsWith("new-");
            JSONObject existing = create ? null : existingContexts.get(draftId);
            if (!create && existing == null) {
                throw new StorageException(
                    FailureKind.CONFLICT,
                    "Context was deleted while Review was open: " + draftId
                );
            }
            String persistedId = create ? UUID.randomUUID().toString() : draftId;
            String displayName = requireDisplayName(
                draft.optString("display_name", ""),
                "context"
            );
            String storageName = create
                ? allocateStorageName(
                    finalContextIndex,
                    contextStore,
                    displayName,
                    "context"
                )
                : stripJsonSuffix(
                    previousContextIndex.optString(draftId, "")
                );
            if (storageName.isEmpty()) {
                throw new StorageException(
                    FailureKind.INVALID_STATE,
                    "Context index mapping is missing: " + draftId
                );
            }

            JSONObject persisted = create
                ? buildNewReviewContext(
                    draft,
                    persistedId,
                    storageName,
                    displayName,
                    now
                )
                : buildUpdatedReviewContext(
                    draft,
                    existing,
                    persistedId,
                    storageName,
                    now
                );
            validateSceneEntries(persisted.optJSONArray("scenes"));
            validateContextDocument(persisted);
            put(finalContextIndex, persistedId, storageName + ".json");
            finalContexts.put(persistedId, persisted);
            result.contextIds.add(persistedId);
            if (!draftId.isEmpty()) {
                result.contextIdMap.put(draftId, persistedId);
            }
            if (create) {
                result.contextsCreated++;
            } else {
                result.contextsUpdated++;
            }
            mutations.add(mutation(
                ContextStore.DIRECTORY_NAME + "/" + storageName + ".json",
                contextStore.getDirectory(),
                persisted
            ));
        }

        put(finalIndex, "contexts", finalContextIndex);
        JSONObject finalGroupIndex = new JSONObject();
        Map<String, JSONObject> finalGroups = new LinkedHashMap<>();
        Set<String> seenGroupDraftIds = new HashSet<>();
        for (JSONObject sourceDraft : groupDrafts) {
            if (sourceDraft == null) {
                throw new StorageException(
                    FailureKind.INVALID_ARGUMENT,
                    "Group Review draft is null"
                );
            }
            JSONObject draft = copyReviewDraft(sourceDraft, "Group");
            remapReviewGroupContexts(draft, result.contextIdMap);
            String draftId = draft.optString("id", "");
            if (!draftId.isEmpty() && !seenGroupDraftIds.add(draftId)) {
                throw new StorageException(
                    FailureKind.INVALID_ARGUMENT,
                    "duplicate Group Review draft id: " + draftId
                );
            }
            boolean create = draftId.isEmpty() || draftId.startsWith("new-");
            JSONObject existing = create ? null : existingGroups.get(draftId);
            if (!create && existing == null) {
                throw new StorageException(
                    FailureKind.CONFLICT,
                    "Group was deleted while Review was open: " + draftId
                );
            }
            String persistedId = create ? UUID.randomUUID().toString() : draftId;
            String displayName = requireDisplayName(
                draft.optString("display_name", ""),
                "group"
            );
            String storageName = create
                ? allocateStorageName(
                    finalGroupIndex,
                    groupStore,
                    displayName,
                    "group"
                )
                : stripJsonSuffix(previousGroupIndex.optString(draftId, ""));
            if (storageName.isEmpty()) {
                throw new StorageException(
                    FailureKind.INVALID_STATE,
                    "Group index mapping is missing: " + draftId
                );
            }
            JSONArray contexts = draft.optJSONArray("contexts");
            validateGroupContextIds(contexts, finalIndex);
            JSONObject persisted = create
                ? buildNewReviewGroup(
                    draft,
                    persistedId,
                    storageName,
                    displayName,
                    now
                )
                : buildUpdatedReviewGroup(
                    draft,
                    existing,
                    persistedId,
                    storageName,
                    now
                );
            validateGroupDocument(persisted);
            put(finalGroupIndex, persistedId, storageName + ".json");
            finalGroups.put(persistedId, persisted);
            result.groupIds.add(persistedId);
            if (!draftId.isEmpty()) {
                result.groupIdMap.put(draftId, persistedId);
            }
            if (create) {
                result.groupsCreated++;
            } else {
                result.groupsUpdated++;
            }
            mutations.add(mutation(
                GroupStore.DIRECTORY_NAME + "/" + storageName + ".json",
                groupStore.getDirectory(),
                persisted
            ));
        }
        put(finalIndex, "groups", finalGroupIndex);

        for (Map.Entry<String, JSONObject> entry : existingContexts.entrySet()) {
            if (finalContexts.containsKey(entry.getKey())) {
                continue;
            }
            String storageName = stripJsonSuffix(
                previousContextIndex.optString(entry.getKey(), "")
            );
            mutations.add(deletionMutation(
                ContextStore.DIRECTORY_NAME + "/" + storageName + ".json",
                new File(contextStore.getDirectory(), storageName + ".json")
            ));
            result.contextsDeleted++;
        }
        for (Map.Entry<String, JSONObject> entry : existingGroups.entrySet()) {
            if (finalGroups.containsKey(entry.getKey())) {
                continue;
            }
            String storageName = stripJsonSuffix(
                previousGroupIndex.optString(entry.getKey(), "")
            );
            mutations.add(deletionMutation(
                GroupStore.DIRECTORY_NAME + "/" + storageName + ".json",
                new File(groupStore.getDirectory(), storageName + ".json")
            ));
            result.groupsDeleted++;
        }

        String activeContextId = mapReviewId(
            requestedActiveContextId,
            result.contextIdMap
        );
        String activeGroupId = mapReviewId(
            requestedActiveGroupId,
            result.groupIdMap
        );
        validateIdOrNull(activeContextId, "active context id");
        validateIdOrNull(activeGroupId, "active group id");
        if (activeContextId != null && !finalContexts.containsKey(activeContextId)) {
            throw new StorageException(
                FailureKind.INVALID_ARGUMENT,
                "active Context is not in final Review state: " + activeContextId
            );
        }
        if (activeGroupId != null && !finalGroups.containsKey(activeGroupId)) {
            throw new StorageException(
                FailureKind.INVALID_ARGUMENT,
                "active Group is not in final Review state: " + activeGroupId
            );
        }
        if (activeContextId != null && activeGroupId != null
            && !containsContext(finalGroups.get(activeGroupId), activeContextId)) {
            throw new StorageException(
                FailureKind.INVALID_ACTIVE_GROUP,
                "active group " + activeGroupId
                    + " does not contain active context " + activeContextId
            );
        }
        putNullableString(finalIndex, "active_context_id", activeContextId);
        putNullableString(finalIndex, "active_group_id", activeGroupId);
        validateIndexDocument(finalIndex);
        mutations.add(mutationIndex(finalIndex));
        commitMutations(mutations);

        result.activeContextId = activeContextId;
        result.activeGroupId = activeGroupId;
        result.activePointersChanged = !sameNullable(
                nullableString(beforeIndex, "active_context_id"),
                activeContextId
            )
            || !sameNullable(
                nullableString(beforeIndex, "active_group_id"),
                activeGroupId
            );
        return result;
    }

    // ── Index / Active pointers ─────────────────────────────────────────

    public synchronized String getActiveContextId() throws StorageException {
        JSONObject index = readIndex();
        return nullableString(index, "active_context_id");
    }

    public synchronized String getActiveGroupId() throws StorageException {
        JSONObject index = readIndex();
        return nullableString(index, "active_group_id");
    }

    /**
     * Resolves the default History Mapping for a newly created Translation
     * Job. Returns {@code null} when there is no Active Context; otherwise
     * returns a mapping object whose {@code group_id} is JSON null when no
     * Active Group is selected. An explicitly selected Group that no longer
     * contains the Active Context is a persisted-state error and is rejected;
     * it must never be silently downgraded to no Group history.
     */
    public synchronized JSONObject resolveActiveHistoryMapping()
        throws StorageException {
        JSONObject index = readIndex();
        String contextId = nullableString(index, "active_context_id");
        if (contextId == null) {
            return null;
        }
        String groupId = nullableString(index, "active_group_id");
        if (groupId != null && !groupContains(groupId, contextId)) {
            throw new StorageException(
                FailureKind.INVALID_ACTIVE_GROUP,
                "active group " + groupId
                    + " does not contain active context " + contextId
                    + "; clear the active group or choose a containing group"
            );
        }
        JSONObject mapping = new JSONObject();
        put(mapping, "context_id", contextId);
        putNullableString(mapping, "group_id", groupId);
        return mapping;
    }

    public void setActiveContext(String contextId)
        throws StorageException {
        String previousContextId;
        ActiveContextChangeListener listener;
        synchronized (this) {
            JSONObject index = readIndex();
            previousContextId = nullableString(index, "active_context_id");
            validateIdOrNull(contextId, "active context id");
            if (contextId != null) {
                requireIndexEntry(index, "contexts", contextId, "context");
                String activeGroup = nullableString(index, "active_group_id");
                if (activeGroup != null
                    && !groupContains(activeGroup, contextId)) {
                    throw new StorageException(
                        FailureKind.INVALID_ACTIVE_GROUP,
                        "active group " + activeGroup
                            + " does not contain active context " + contextId
                    );
                }
            }
            putNullableString(index, "active_context_id", contextId);
            commitSingleIndex(index);
            listener = activeContextChangeListener;
        }
        if (!sameNullable(previousContextId, contextId)) {
            listener.onActiveContextChanged(previousContextId, contextId);
        }
    }

    public synchronized void setActiveGroup(String groupId)
        throws StorageException {
        JSONObject index = readIndex();
        validateIdOrNull(groupId, "active group id");
        if (groupId != null) {
            requireIndexEntry(index, "groups", groupId, "group");
            String activeContext = nullableString(index, "active_context_id");
            if (activeContext != null
                && !groupContains(groupId, activeContext)) {
                throw new StorageException(
                    FailureKind.INVALID_ACTIVE_GROUP,
                    "active group " + groupId
                        + " does not contain active context " + activeContext
                );
            }
        }
        putNullableString(index, "active_group_id", groupId);
        commitSingleIndex(index);
    }

    public void setActivePointers(
        String contextId,
        String groupId
    ) throws StorageException {
        String previousContextId;
        ActiveContextChangeListener listener;
        synchronized (this) {
            JSONObject index = readIndex();
            previousContextId = nullableString(index, "active_context_id");
            validateIdOrNull(contextId, "active context id");
            validateIdOrNull(groupId, "active group id");
            if (contextId != null) {
                requireIndexEntry(index, "contexts", contextId, "context");
            }
            if (groupId != null) {
                requireIndexEntry(index, "groups", groupId, "group");
            }
            if (contextId != null && groupId != null
                && !groupContains(groupId, contextId)) {
                throw new StorageException(
                    FailureKind.INVALID_ACTIVE_GROUP,
                    "active group " + groupId
                        + " does not contain active context " + contextId
                );
            }
            putNullableString(index, "active_context_id", contextId);
            putNullableString(index, "active_group_id", groupId);
            commitSingleIndex(index);
            listener = activeContextChangeListener;
        }
        if (!sameNullable(previousContextId, contextId)) {
            listener.onActiveContextChanged(previousContextId, contextId);
        }
    }

    // ── Context CRUD ────────────────────────────────────────────────────

    /** Creates a Context from a draft (display_name and optional scenes/summary). */
    public synchronized JSONObject createContext(JSONObject draft)
        throws StorageException {
        if (draft == null) {
            throw new StorageException(
                FailureKind.INVALID_ARGUMENT,
                "context draft is null"
            );
        }
        String displayName = requireDisplayName(
            draft.optString("display_name", ""),
            "context"
        );
        JSONObject index = readIndex();
        String id = UUID.randomUUID().toString();
        String storageName = allocateStorageName(
            index.optJSONObject("contexts"),
            contextStore,
            displayName,
            "context"
        );

        JSONObject context = new JSONObject();
        put(context, "version", FORMAT_VERSION);
        put(context, "id", id);
        put(context, "storage_name", storageName);
        put(context, "display_name", displayName);
        put(context, "revision", 1);
        long now = System.currentTimeMillis();
        put(context, "created_at", now);
        put(context, "updated_at", now);
        if (draft.has("retention")) {
            put(context, "retention", draft.optJSONObject("retention"));
        } else {
            JSONObject retention = new JSONObject();
            put(retention, "inherit_defaults", true);
            put(retention, "recent_percent", DEFAULT_RECENT_PERCENT);
            put(retention, "recent_limit", DEFAULT_RECENT_LIMIT);
            put(context, "retention", retention);
        }
        put(context,
            "manual_descriptions",
            draft.optJSONObject("manual_descriptions") != null
                ? draft.optJSONObject("manual_descriptions")
                : new JSONObject()
        );
        put(context,
            "summary",
            draft.optJSONObject("summary") != null
                ? draft.optJSONObject("summary")
                : new JSONObject()
        );
        JSONArray scenes = draft.optJSONArray("scenes") != null
            ? draft.optJSONArray("scenes")
            : new JSONArray();
        validateSceneEntries(scenes);
        put(context, "scenes", scenes);

        validateContextDocument(context);
        put(index.optJSONObject("contexts"), id, storageName + ".json");
        List<Mutation> mutations = new ArrayList<>();
        mutations.add(mutation(
            "contexts/" + storageName + ".json",
            contextStore.getDirectory(),
            context
        ));
        mutations.add(mutationIndex(index));
        commitMutations(mutations);
        return context;
    }

    /**
     * Appends a bare scene name to a Context only when that scene is not
     * already a member. Existing members are never moved or duplicated; each
     * new member gets its own independent {@code entry_id}.
     *
     * <p>This is the Context/Group admission side of a newly produced Scene:
     * the Scene name has already been validated by the Translation request
     * boundary, so only a non-empty name is required here.</p>
     *
     * @return the updated Context if appended, or the unchanged Context when
     *         the scene was already present.
     */
    public synchronized JSONObject appendSceneIfAbsent(
        String contextId,
        String sceneName
    ) throws StorageException {
        String storageName = requireContextStorageName(contextId);
        try {
            synchronized (EntityStoreLock.forFile(
                new File(contextStore.getDirectory(), storageName + ".json")
            )) {
                return appendSceneIfAbsentLocked(contextId, sceneName);
            }
        } catch (IOException e) {
            throw new StorageException(
                FailureKind.IO,
                "could not acquire context admission lock",
                e
            );
        }
    }

    private JSONObject appendSceneIfAbsentLocked(
        String contextId,
        String sceneName
    ) throws StorageException {
        if (sceneName == null || sceneName.trim().isEmpty()) {
            throw new StorageException(
                FailureKind.INVALID_ARGUMENT,
                "scene name must not be empty"
            );
        }
        JSONObject context = getContext(contextId);
        JSONArray scenes = context.optJSONArray("scenes");
        if (scenes != null) {
            for (int index = 0; index < scenes.length(); index++) {
                JSONObject entry = scenes.optJSONObject(index);
                if (entry != null
                    && sceneName.equals(entry.optString("scene", ""))) {
                    return context;
                }
            }
        }
        JSONObject entry = new JSONObject();
        long now = System.currentTimeMillis();
        try {
            entry.put("entry_id", UUID.randomUUID().toString());
            entry.put("scene", sceneName);
            entry.put("scene_file", sceneName + ".json");
            entry.put("created_at", now);
            entry.put("updated_at", now);
            entry.put("summaries", new JSONObject());
        } catch (org.json.JSONException e) {
            throw new StorageException(
                FailureKind.INVALID_STATE,
                "could not encode scene entry",
                e
            );
        }
        if (scenes == null) {
            scenes = new JSONArray();
        }
        scenes.put(entry);
        final JSONObject draft;
        try {
            draft = new JSONObject(context.toString());
            draft.put("scenes", scenes);
        } catch (org.json.JSONException e) {
            throw new StorageException(
                FailureKind.INVALID_STATE,
                "could not encode updated context scenes",
                e
            );
        }
        return updateContext(
            contextId,
            draft,
            context.optLong("revision", -1L)
        );
    }

    public synchronized JSONObject getContext(String contextId)
        throws StorageException {
        String storageName = requireContextStorageName(contextId);
        try {
            JSONObject context = contextStore.read(storageName);
            if (!contextId.equals(context.optString("id"))) {
                throw new StorageException(
                    FailureKind.INVALID_STATE,
                    "context file id does not match index for " + contextId
                );
            }
            return context;
        } catch (ContextGroupSchemaValidator.ValidationException e) {
            throw new StorageException(
                FailureKind.INVALID_STATE,
                "context file is schema-invalid: " + contextId,
                e
            );
        } catch (IOException e) {
            throw new StorageException(
                FailureKind.INVALID_STATE,
                "context file is missing or unreadable: " + contextId,
                e
            );
        }
    }

    /** Replaces a full Context draft under compare-and-set revision control. */
    public synchronized JSONObject updateContext(
        String contextId,
        JSONObject draft,
        long expectedRevision
    ) throws StorageException {
        String storageName = requireContextStorageName(contextId);
        try {
            synchronized (EntityStoreLock.forFile(
                new File(contextStore.getDirectory(), storageName + ".json")
            )) {
                return updateContextLocked(
                    contextId,
                    storageName,
                    draft,
                    expectedRevision
                );
            }
        } catch (IOException e) {
            throw new StorageException(
                FailureKind.IO,
                "could not acquire context mutation lock",
                e
            );
        }
    }

    private JSONObject updateContextLocked(
        String contextId,
        String storageName,
        JSONObject draft,
        long expectedRevision
    ) throws StorageException {
        JSONObject existing = getContext(contextId);
        if (draft == null) {
            throw new StorageException(
                FailureKind.INVALID_ARGUMENT,
                "context draft is null"
            );
        }
        if (!contextId.equals(draft.optString("id"))) {
            throw new StorageException(
                FailureKind.INVALID_ARGUMENT,
                "context id cannot change"
            );
        }
        if (!storageName.equals(draft.optString("storage_name"))) {
            throw new StorageException(
                FailureKind.INVALID_ARGUMENT,
                "context storage_name is fixed after creation"
            );
        }
        if (draft.optLong("revision", -1L) != expectedRevision) {
            throw new StorageException(
                FailureKind.CONFLICT,
                "context revision does not match expected revision"
            );
        }
        if (existing.optLong("revision", -1L) != expectedRevision) {
            throw new StorageException(
                FailureKind.CONFLICT,
                "context was modified by another writer; refresh before saving"
            );
        }
        validateSceneEntries(draft.optJSONArray("scenes"));
        JSONObject updated = new JSONObject();
        copyRequiredContextFields(updated, draft, existing);
        put(updated, "revision", expectedRevision + 1L);
        put(updated, "updated_at", System.currentTimeMillis());
        validateContextDocument(updated);
        List<Mutation> mutations = new ArrayList<>();
        mutations.add(mutation(
            "contexts/" + storageName + ".json",
            contextStore.getDirectory(),
            updated
        ));
        commitMutations(mutations);
        return updated;
    }

    public synchronized JSONObject reorderContextScenes(
        String contextId,
        List<String> entryIds,
        long expectedRevision
    ) throws StorageException {
        String storageName = requireContextStorageName(contextId);
        try {
            synchronized (EntityStoreLock.forFile(
                new File(contextStore.getDirectory(), storageName + ".json")
            )) {
                return reorderContextScenesLocked(
                    contextId,
                    storageName,
                    entryIds,
                    expectedRevision
                );
            }
        } catch (IOException e) {
            throw new StorageException(
                FailureKind.IO,
                "could not acquire context reorder lock",
                e
            );
        }
    }

    private JSONObject reorderContextScenesLocked(
        String contextId,
        String storageName,
        List<String> entryIds,
        long expectedRevision
    ) throws StorageException {
        if (entryIds == null) {
            throw new StorageException(
                FailureKind.INVALID_ARGUMENT,
                "entryIds is null"
            );
        }
        JSONObject context = getContext(contextId);
        long currentRevision = context.optLong("revision", -1L);
        if (currentRevision != expectedRevision) {
            throw new StorageException(
                FailureKind.CONFLICT,
                "context was modified by another writer; refresh before reordering"
            );
        }
        Map<String, JSONObject> byEntryId = new LinkedHashMap<>();
        JSONArray existingScenes = context.optJSONArray("scenes");
        for (int index = 0; index < existingScenes.length(); index++) {
            JSONObject entry = existingScenes.optJSONObject(index);
            if (entry != null) {
                byEntryId.put(entry.optString("entry_id"), entry);
            }
        }
        if (byEntryId.size() != entryIds.size()) {
            throw new StorageException(
                FailureKind.INVALID_ARGUMENT,
                "reorder list must contain every scene entry exactly once"
            );
        }
        JSONArray reordered = new JSONArray();
        Set<String> seen = new HashSet<>();
        for (String entryId : entryIds) {
            if (!seen.add(entryId)) {
                throw new StorageException(
                    FailureKind.INVALID_ARGUMENT,
                    "reorder list contains duplicate scene entry " + entryId
                );
            }
            JSONObject entry = byEntryId.get(entryId);
            if (entry == null) {
                throw new StorageException(
                    FailureKind.INVALID_ARGUMENT,
                    "reorder list contains unknown scene entry " + entryId
                );
            }
            reordered.put(entry);
        }
        put(context, "scenes", reordered);
        put(context, "revision", expectedRevision + 1L);
        put(context, "updated_at", System.currentTimeMillis());
        validateContextDocument(context);
        List<Mutation> mutations = new ArrayList<>();
        mutations.add(mutation(
            "contexts/" + storageName + ".json",
            contextStore.getDirectory(),
            context
        ));
        commitMutations(mutations);
        return context;
    }

    public synchronized void deleteContext(String contextId)
        throws StorageException {
        while (true) {
            JSONObject snapshotIndex = readIndex();
            JSONObject snapshotContextMap = snapshotIndex.optJSONObject(
                "contexts"
            );
            String storageName = snapshotContextMap == null
                ? null
                : snapshotContextMap.optString(contextId, null);
            if (storageName == null || storageName.isEmpty()) {
                throw new StorageException(
                    FailureKind.NOT_FOUND,
                    "context not found: " + contextId
                );
            }
            requireValidStorageFileName(storageName, "context");
            File contextFile = new File(
                contextStore.getDirectory(),
                storageName
            );
            JSONObject snapshotGroups = snapshotIndex.optJSONObject("groups");
            List<File> lockFiles = new ArrayList<>();
            lockFiles.add(contextFile);
            lockFiles.add(indexStore.getFile());
            for (String groupId : sortedKeys(snapshotGroups)) {
                String groupStorage = snapshotGroups.optString(groupId, null);
                if (groupStorage == null || groupStorage.isEmpty()) {
                    throw new StorageException(
                        FailureKind.INVALID_STATE,
                        "index references missing group file name for " + groupId
                    );
                }
                requireValidStorageFileName(groupStorage, "group");
                lockFiles.add(new File(groupStore.getDirectory(), groupStorage));
            }

            boolean completed = withEntityLocks(lockFiles, () -> {
                // Re-read the complete index while every file that may be
                // touched is locked. A changed target or group mapping means
                // the snapshot lock set is stale; release all locks and retry.
                JSONObject index = readIndex();
                JSONObject contextMap = index.optJSONObject("contexts");
                String currentStorageName = contextMap == null
                    ? null
                    : contextMap.optString(contextId, null);
                if (currentStorageName == null
                    || currentStorageName.isEmpty()) {
                    throw new StorageException(
                        FailureKind.NOT_FOUND,
                        "context not found: " + contextId
                    );
                }
                requireValidStorageFileName(currentStorageName, "context");
                if (!storageName.equals(currentStorageName)
                    || !sameStorageMapping(
                        snapshotGroups,
                        index.optJSONObject("groups")
                    )) {
                    return false;
                }

                List<Mutation> mutations = new ArrayList<>();
                mutations.add(new Mutation(
                    "contexts/" + storageName,
                    contextFile,
                    readBeforeOrNull(contextFile),
                    null
                ));

                JSONObject groups = index.optJSONObject("groups");
                if (groups != null) {
                    List<String> groupIds = sortedKeys(groups);
                    for (String groupId : groupIds) {
                        String groupStorage = groups.optString(groupId, null);
                        if (groupStorage == null || groupStorage.isEmpty()) {
                            throw new StorageException(
                                FailureKind.INVALID_STATE,
                                "index references missing group file name for "
                                    + groupId
                            );
                        }
                        JSONObject group = readGroupByStorage(
                            groupId,
                            groupStorage
                        );
                        JSONArray contexts = group.optJSONArray("contexts");
                        JSONArray updatedContexts = removeContextId(
                            contexts,
                            contextId
                        );
                        if (contexts.length() != updatedContexts.length()) {
                            put(group, "contexts", updatedContexts);
                            put(group, "revision", group.optLong(
                                "revision",
                                0L
                            ) + 1L);
                            put(group, "updated_at", System.currentTimeMillis());
                            validateGroupDocument(group);
                            mutations.add(mutation(
                                "groups/" + groupStorage,
                                groupStore.getDirectory(),
                                group
                            ));
                        }
                    }
                }

                contextMap.remove(contextId);
                if (contextId.equals(nullableString(
                    index,
                    "active_context_id"
                ))) {
                    putNullableString(index, "active_context_id", null);
                }
                mutations.add(mutationIndex(index));
                commitMutations(mutations);
                return true;
            });
            if (completed) {
                return;
            }
        }
    }

    // ── Group CRUD ──────────────────────────────────────────────────────

    public synchronized JSONObject createGroup(JSONObject draft)
        throws StorageException {
        if (draft == null) {
            throw new StorageException(
                FailureKind.INVALID_ARGUMENT,
                "group draft is null"
            );
        }
        String displayName = requireDisplayName(
            draft.optString("display_name", ""),
            "group"
        );
        JSONObject index = readIndex();
        String id = UUID.randomUUID().toString();
        String storageName = allocateStorageName(
            index.optJSONObject("groups"),
            groupStore,
            displayName,
            "group"
        );

        JSONObject group = new JSONObject();
        put(group, "version", FORMAT_VERSION);
        put(group, "id", id);
        put(group, "storage_name", storageName);
        put(group, "display_name", displayName);
        put(group, "revision", 1);
        long now = System.currentTimeMillis();
        put(group, "created_at", now);
        put(group, "updated_at", now);
        JSONArray contexts = draft.optJSONArray("contexts") != null
            ? GroupContextEntry.cloneEntries(draft.optJSONArray("contexts"))
            : new JSONArray();
        validateGroupContextIds(contexts, index);
        put(group, "contexts", contexts);
        put(group,
            "summary",
            draft.optJSONObject("summary") != null
                ? draft.optJSONObject("summary")
                : new JSONObject()
        );

        validateGroupDocument(group);
        put(index.optJSONObject("groups"), id, storageName + ".json");
        List<Mutation> mutations = new ArrayList<>();
        mutations.add(mutation(
            "groups/" + storageName + ".json",
            groupStore.getDirectory(),
            group
        ));
        mutations.add(mutationIndex(index));
        commitMutations(mutations);
        return group;
    }

    public synchronized JSONObject getGroup(String groupId)
        throws StorageException {
        String storageName = requireGroupStorageName(groupId);
        try {
            JSONObject group = groupStore.read(storageName);
            if (!groupId.equals(group.optString("id"))) {
                throw new StorageException(
                    FailureKind.INVALID_STATE,
                    "group file id does not match index for " + groupId
                );
            }
            return group;
        } catch (ContextGroupSchemaValidator.ValidationException e) {
            throw new StorageException(
                FailureKind.INVALID_STATE,
                "group file is schema-invalid: " + groupId,
                e
            );
        } catch (IOException e) {
            throw new StorageException(
                FailureKind.INVALID_STATE,
                "group file is missing or unreadable: " + groupId,
                e
            );
        }
    }

    public synchronized JSONObject updateGroup(
        String groupId,
        JSONObject draft,
        long expectedRevision
    ) throws StorageException {
        String storageName = requireGroupStorageName(groupId);
        JSONObject existing = getGroup(groupId);
        if (draft == null) {
            throw new StorageException(
                FailureKind.INVALID_ARGUMENT,
                "group draft is null"
            );
        }
        if (!groupId.equals(draft.optString("id"))) {
            throw new StorageException(
                FailureKind.INVALID_ARGUMENT,
                "group id cannot change"
            );
        }
        if (!storageName.equals(draft.optString("storage_name"))) {
            throw new StorageException(
                FailureKind.INVALID_ARGUMENT,
                "group storage_name is fixed after creation"
            );
        }
        if (draft.optLong("revision", -1L) != expectedRevision) {
            throw new StorageException(
                FailureKind.CONFLICT,
                "group revision does not match expected revision"
            );
        }
        if (existing.optLong("revision", -1L) != expectedRevision) {
            throw new StorageException(
                FailureKind.CONFLICT,
                "group was modified by another writer; refresh before saving"
            );
        }
        JSONObject index = readIndex();
        JSONArray draftContexts = draft.optJSONArray("contexts");
        validateGroupContextIds(draftContexts, index);
        requireActiveGroupContainsActiveContext(index, groupId, draftContexts);
        JSONObject updated = new JSONObject();
        copyRequiredGroupFields(updated, draft, existing);
        put(updated, "revision", expectedRevision + 1L);
        put(updated, "updated_at", System.currentTimeMillis());
        validateGroupDocument(updated);
        List<Mutation> mutations = new ArrayList<>();
        mutations.add(mutation(
            "groups/" + storageName + ".json",
            groupStore.getDirectory(),
            updated
        ));
        commitMutations(mutations);
        return updated;
    }

    public synchronized JSONObject reorderGroupContexts(
        String groupId,
        List<String> contextIds,
        long expectedRevision
    ) throws StorageException {
        if (contextIds == null) {
            throw new StorageException(
                FailureKind.INVALID_ARGUMENT,
                "contextIds is null"
            );
        }
        JSONObject group = getGroup(groupId);
        long currentRevision = group.optLong("revision", -1L);
        if (currentRevision != expectedRevision) {
            throw new StorageException(
                FailureKind.CONFLICT,
                "group was modified by another writer; refresh before reordering"
            );
        }
        JSONObject activeIndex = readIndex();
        JSONArray existing = group.optJSONArray("contexts");
        Map<String, JSONObject> entriesByContext = new LinkedHashMap<>();
        for (int index = 0; index < existing.length(); index++) {
            JSONObject entry = GroupContextEntry.require(existing, index);
            entriesByContext.put(
                entry.optString(GroupContextEntry.CONTEXT_ID, ""),
                entry
            );
        }
        JSONArray proposedContexts = new JSONArray();
        for (String contextId : contextIds) {
            JSONObject entry = entriesByContext.get(contextId);
            if (entry == null) {
                throw new StorageException(
                    FailureKind.INVALID_ARGUMENT,
                    "reorder list contains unknown context " + contextId
                );
            }
            proposedContexts.put(entry);
        }
        requireActiveGroupContainsActiveContext(
            activeIndex,
            groupId,
            proposedContexts
        );
        Set<String> existingIds = new HashSet<>();
        for (int index = 0; index < existing.length(); index++) {
            existingIds.add(GroupContextEntry.contextIdAt(existing, index));
        }
        if (existingIds.size() != contextIds.size()
            || !existingIds.containsAll(contextIds)) {
            throw new StorageException(
                FailureKind.INVALID_ARGUMENT,
                "reorder list must contain every context exactly once"
            );
        }
        JSONArray reordered = new JSONArray();
        Set<String> seen = new HashSet<>();
        for (String contextId : contextIds) {
            if (!seen.add(contextId)) {
                throw new StorageException(
                    FailureKind.INVALID_ARGUMENT,
                    "reorder list contains duplicate context " + contextId
                );
            }
            reordered.put(entriesByContext.get(contextId));
        }
        put(group, "contexts", reordered);
        put(group, "revision", expectedRevision + 1L);
        put(group, "updated_at", System.currentTimeMillis());
        validateGroupDocument(group);
        String storageName = requireGroupStorageName(groupId);
        List<Mutation> mutations = new ArrayList<>();
        mutations.add(mutation(
            "groups/" + storageName + ".json",
            groupStore.getDirectory(),
            group
        ));
        commitMutations(mutations);
        return group;
    }

    public synchronized void deleteGroup(String groupId)
        throws StorageException {
        while (true) {
            JSONObject snapshotIndex = readIndex();
            JSONObject snapshotGroupMap = snapshotIndex.optJSONObject("groups");
            String storageName = snapshotGroupMap == null
                ? null
                : snapshotGroupMap.optString(groupId, null);
            if (storageName == null || storageName.isEmpty()) {
                throw new StorageException(
                    FailureKind.NOT_FOUND,
                    "group not found: " + groupId
                );
            }
            requireValidStorageFileName(storageName, "group");
            File groupFile = new File(groupStore.getDirectory(), storageName);
            List<File> lockFiles = new ArrayList<>();
            lockFiles.add(groupFile);
            lockFiles.add(indexStore.getFile());
            boolean completed = withEntityLocks(lockFiles, () -> {
                JSONObject index = readIndex();
                JSONObject groupMap = index.optJSONObject("groups");
                String currentStorageName = groupMap == null
                    ? null
                    : groupMap.optString(groupId, null);
                if (currentStorageName == null
                    || currentStorageName.isEmpty()) {
                    throw new StorageException(
                        FailureKind.NOT_FOUND,
                        "group not found: " + groupId
                    );
                }
                requireValidStorageFileName(currentStorageName, "group");
                if (!storageName.equals(currentStorageName)
                    || !sameStorageMapping(
                        snapshotGroupMap,
                        groupMap
                    )) {
                    return false;
                }

                List<Mutation> mutations = new ArrayList<>();
                mutations.add(new Mutation(
                    "groups/" + storageName,
                    groupFile,
                    readBeforeOrNull(groupFile),
                    null
                ));
                groupMap.remove(groupId);
                if (groupId.equals(nullableString(
                    index,
                    "active_group_id"
                ))) {
                    putNullableString(index, "active_group_id", null);
                }
                mutations.add(mutationIndex(index));
                commitMutations(mutations);
                return true;
            });
            if (completed) {
                return;
            }
        }
    }

    // ── Listing ─────────────────────────────────────────────────────────

    public synchronized List<String> listContextIds() throws StorageException {
        JSONObject index = readIndex();
        return sortedKeys(index.optJSONObject("contexts"));
    }

    public synchronized List<String> listGroupIds() throws StorageException {
        JSONObject index = readIndex();
        return sortedKeys(index.optJSONObject("groups"));
    }

    public synchronized List<JSONObject> listContexts() throws StorageException {
        List<JSONObject> result = new ArrayList<>();
        for (String id : listContextIds()) {
            result.add(getContext(id));
        }
        return Collections.unmodifiableList(result);
    }

    public synchronized List<JSONObject> listGroups() throws StorageException {
        List<JSONObject> result = new ArrayList<>();
        for (String id : listGroupIds()) {
            result.add(getGroup(id));
        }
        return Collections.unmodifiableList(result);
    }

    // ── Recovery ────────────────────────────────────────────────────────

    /**
     * Rolls back any interrupted transaction journal. Called automatically by
     * the constructor and safe to call again before a user-facing operation.
     */
    public synchronized void recover() throws StorageException {
        ensureDirectories();
        File txnDirectory = new File(rootDirectory, TXN_DIRECTORY_NAME);
        if (!txnDirectory.isDirectory()) {
            return;
        }
        File[] journals = txnDirectory.listFiles();
        if (journals == null) {
            throw new StorageException(
                FailureKind.IO,
                "could not enumerate transaction journal directory"
            );
        }
        for (File journal : journals) {
            if (!journal.isFile()) {
                throw new StorageException(
                    FailureKind.INVALID_STATE,
                    "unexpected entry in transaction journal directory: "
                        + journal.getName()
                );
            }
            rollbackJournal(journal);
        }
    }

    // ── Internal helpers ────────────────────────────────────────────────

    private static JSONObject copyReviewDraft(JSONObject source, String kind)
        throws StorageException {
        try {
            return new JSONObject(source.toString());
        } catch (org.json.JSONException e) {
            throw new StorageException(
                FailureKind.INVALID_ARGUMENT,
                "could not copy " + kind + " Review draft",
                e
            );
        }
    }

    private static JSONObject buildNewReviewContext(
        JSONObject draft,
        String id,
        String storageName,
        String displayName,
        long now
    ) throws StorageException {
        JSONObject context = new JSONObject();
        put(context, "version", FORMAT_VERSION);
        put(context, "id", id);
        put(context, "storage_name", storageName);
        put(context, "display_name", displayName);
        put(context, "revision", 1L);
        put(context, "created_at", now);
        put(context, "updated_at", now);
        JSONObject retention = draft.optJSONObject("retention");
        if (retention == null) {
            retention = new JSONObject();
            put(retention, "inherit_defaults", true);
            put(retention, "recent_percent", DEFAULT_RECENT_PERCENT);
            put(retention, "recent_limit", DEFAULT_RECENT_LIMIT);
        }
        put(context, "retention", retention);
        put(
            context,
            "manual_descriptions",
            draft.optJSONObject("manual_descriptions") == null
                ? new JSONObject()
                : draft.optJSONObject("manual_descriptions")
        );
        put(
            context,
            "summary",
            draft.optJSONObject("summary") == null
                ? new JSONObject()
                : draft.optJSONObject("summary")
        );
        put(
            context,
            "scenes",
            draft.optJSONArray("scenes") == null
                ? new JSONArray()
                : draft.optJSONArray("scenes")
        );
        return context;
    }

    private static JSONObject buildUpdatedReviewContext(
        JSONObject draft,
        JSONObject existing,
        String id,
        String storageName,
        long now
    ) throws StorageException {
        long expectedRevision = draft.optLong("revision", -1L);
        if (!id.equals(draft.optString("id", ""))
            || !storageName.equals(draft.optString("storage_name", ""))) {
            throw new StorageException(
                FailureKind.INVALID_ARGUMENT,
                "Context identity cannot change during Review: " + id
            );
        }
        if (expectedRevision < 0L
            || existing.optLong("revision", -1L) != expectedRevision) {
            throw new StorageException(
                FailureKind.CONFLICT,
                "Context was modified by another writer; refresh before saving: "
                    + id
            );
        }
        JSONObject updated = new JSONObject();
        copyRequiredContextFields(updated, draft, existing);
        put(updated, "revision", expectedRevision + 1L);
        put(updated, "updated_at", now);
        return updated;
    }

    private static JSONObject buildNewReviewGroup(
        JSONObject draft,
        String id,
        String storageName,
        String displayName,
        long now
    ) throws StorageException {
        JSONObject group = new JSONObject();
        put(group, "version", FORMAT_VERSION);
        put(group, "id", id);
        put(group, "storage_name", storageName);
        put(group, "display_name", displayName);
        put(group, "revision", 1L);
        put(group, "created_at", now);
        put(group, "updated_at", now);
        put(
            group,
            "contexts",
            draft.optJSONArray("contexts") == null
                ? new JSONArray()
                : draft.optJSONArray("contexts")
        );
        put(
            group,
            "summary",
            draft.optJSONObject("summary") == null
                ? new JSONObject()
                : draft.optJSONObject("summary")
        );
        return group;
    }

    private static JSONObject buildUpdatedReviewGroup(
        JSONObject draft,
        JSONObject existing,
        String id,
        String storageName,
        long now
    ) throws StorageException {
        long expectedRevision = draft.optLong("revision", -1L);
        if (!id.equals(draft.optString("id", ""))
            || !storageName.equals(draft.optString("storage_name", ""))) {
            throw new StorageException(
                FailureKind.INVALID_ARGUMENT,
                "Group identity cannot change during Review: " + id
            );
        }
        if (expectedRevision < 0L
            || existing.optLong("revision", -1L) != expectedRevision) {
            throw new StorageException(
                FailureKind.CONFLICT,
                "Group was modified by another writer; refresh before saving: "
                    + id
            );
        }
        JSONObject updated = new JSONObject();
        copyRequiredGroupFields(updated, draft, existing);
        put(updated, "revision", expectedRevision + 1L);
        put(updated, "updated_at", now);
        return updated;
    }

    private static void remapReviewGroupContexts(
        JSONObject group,
        Map<String, String> contextIdMap
    ) throws StorageException {
        JSONArray contexts = group.optJSONArray("contexts");
        if (contexts == null) {
            put(group, "contexts", new JSONArray());
            return;
        }
        JSONArray remapped = new JSONArray();
        for (int index = 0; index < contexts.length(); index++) {
            JSONObject entry;
            try {
                entry = new JSONObject(
                    GroupContextEntry.require(contexts, index).toString()
                );
                String sourceId = entry.optString(
                    GroupContextEntry.CONTEXT_ID,
                    ""
                );
                entry.put(
                    GroupContextEntry.CONTEXT_ID,
                    mapReviewId(sourceId, contextIdMap)
                );
            } catch (Exception e) {
                throw new StorageException(
                    FailureKind.INVALID_ARGUMENT,
                    "could not remap Group Context entry",
                    e
                );
            }
            remapped.put(entry);
        }
        put(group, "contexts", remapped);
    }

    private Mutation deletionMutation(String path, File file)
        throws StorageException {
        if (file == null || path == null || path.trim().isEmpty()) {
            throw new StorageException(
                FailureKind.INVALID_STATE,
                "invalid Review deletion target"
            );
        }
        return new Mutation(path, file, readBeforeOrNull(file), null);
    }

    private static String mapReviewId(
        String id,
        Map<String, String> idMap
    ) {
        if (id == null) {
            return null;
        }
        String mapped = idMap.get(id);
        return mapped == null ? id : mapped;
    }

    private void validateIndexDocument(JSONObject index)
        throws StorageException {
        try {
            validator.validateIndex(index);
        } catch (ContextGroupSchemaValidator.ValidationException e) {
            throw new StorageException(
                FailureKind.INVALID_ARGUMENT,
                "final Context/Group index is invalid: " + e.getMessage(),
                e
            );
        }
    }

    private static final class Mutation {
        final String path;
        final File file;
        final byte[] before;
        final byte[] after;

        Mutation(String path, File file, byte[] before, byte[] after) {
            this.path = path;
            this.file = file;
            this.before = before;
            this.after = after;
        }
    }

    private Mutation mutation(String path, File parent, JSONObject document)
        throws StorageException {
        File file = new File(
            parent,
            path.substring(path.indexOf('/') + 1)
        );
        return new Mutation(
            path,
            file,
            readBeforeOrNull(file),
            document.toString().getBytes(StandardCharsets.UTF_8)
        );
    }

    private Mutation mutationIndex(JSONObject index) throws StorageException {
        File file = indexStore.getFile();
        return new Mutation(
            SceneContextIndexStore.FILE_NAME,
            file,
            readBeforeOrNull(file),
            index.toString().getBytes(StandardCharsets.UTF_8)
        );
    }

    private byte[] readBeforeOrNull(File file) throws StorageException {
        if (!io.exists(file)) {
            return null;
        }
        try {
            return io.read(file);
        } catch (IOException e) {
            throw new StorageException(
                FailureKind.IO,
                "could not read transaction before-image: " + file.getPath(),
                e
            );
        }
    }

    private void commitSingleIndex(JSONObject index) throws StorageException {
        List<Mutation> mutations = new ArrayList<>();
        mutations.add(mutationIndex(index));
        commitMutations(mutations);
    }

    @FunctionalInterface
    private interface EntityLockedAction {
        boolean run() throws StorageException;
    }

    /**
     * Acquires a complete entity lock set in canonical path order. Delete
     * transactions use this before reading any member document so their
     * detached mutations cannot overwrite a concurrent per-entity mutation.
     */
    private boolean withEntityLocks(
        List<File> files,
        EntityLockedAction action
    ) throws StorageException {
        if (files == null || action == null) {
            throw new StorageException(
                FailureKind.INVALID_ARGUMENT,
                "entity lock set and action are required"
            );
        }
        List<File> ordered = new ArrayList<>();
        Set<String> seenPaths = new HashSet<>();
        for (File file : files) {
            if (file == null) {
                throw new StorageException(
                    FailureKind.INVALID_ARGUMENT,
                    "entity lock set contains null file"
                );
            }
            try {
                String path = file.getCanonicalPath();
                if (seenPaths.add(path)) {
                    ordered.add(file);
                }
            } catch (IOException e) {
                throw new StorageException(
                    FailureKind.IO,
                    "could not resolve entity lock path",
                    e
                );
            }
        }
        ordered.sort((left, right) -> {
            try {
                return left.getCanonicalPath().compareTo(
                    right.getCanonicalPath()
                );
            } catch (IOException e) {
                return left.getAbsolutePath().compareTo(
                    right.getAbsolutePath()
                );
            }
        });
        return withEntityLocksLocked(ordered, action, 0);
    }

    private boolean withEntityLocksLocked(
        List<File> files,
        EntityLockedAction action,
        int index
    ) throws StorageException {
        if (index >= files.size()) {
            return action.run();
        }
        try {
            synchronized (EntityStoreLock.forFile(files.get(index))) {
                return withEntityLocksLocked(files, action, index + 1);
            }
        } catch (IOException e) {
            throw new StorageException(
                FailureKind.IO,
                "could not acquire entity lock",
                e
            );
        }
    }

    private static boolean sameStorageMapping(
        JSONObject left,
        JSONObject right
    ) {
        List<String> leftKeys = sortedKeys(left);
        List<String> rightKeys = sortedKeys(right);
        if (!leftKeys.equals(rightKeys)) {
            return false;
        }
        for (String key : leftKeys) {
            String leftValue = left.optString(key, null);
            String rightValue = right.optString(key, null);
            if (leftValue == null
                ? rightValue != null
                : !leftValue.equals(rightValue)) {
                return false;
            }
        }
        return true;
    }

    private synchronized void commitMutations(List<Mutation> mutations)
        throws StorageException {
        if (mutations == null || mutations.isEmpty()) {
            return;
        }
        List<File> files = new ArrayList<>();
        Set<String> seenPaths = new HashSet<>();
        for (Mutation mutation : mutations) {
            try {
                String path = mutation.file.getCanonicalPath();
                if (seenPaths.add(path)) {
                    files.add(mutation.file);
                }
            } catch (IOException e) {
                throw new StorageException(
                    FailureKind.IO,
                    "could not resolve transaction file path",
                    e
                );
            }
        }
        files.sort((left, right) -> {
            try {
                return left.getCanonicalPath().compareTo(
                    right.getCanonicalPath()
                );
            } catch (IOException e) {
                return left.getAbsolutePath().compareTo(
                    right.getAbsolutePath()
                );
            }
        });
        commitMutationsLocked(mutations, files, 0);
    }

    private void commitMutationsLocked(
        List<Mutation> mutations,
        List<File> files,
        int index
    ) throws StorageException {
        if (index < files.size()) {
            try {
                synchronized (EntityStoreLock.forFile(files.get(index))) {
                    commitMutationsLocked(mutations, files, index + 1);
                }
            } catch (IOException e) {
                throw new StorageException(
                    FailureKind.IO,
                    "could not acquire transaction file lock",
                    e
                );
            }
            return;
        }
        commitMutationsUnlocked(mutations);
    }

    private void commitMutationsUnlocked(List<Mutation> mutations)
        throws StorageException {
        ensureDirectories();
        File txnDirectory = new File(rootDirectory, TXN_DIRECTORY_NAME);
        File journal = new File(
            txnDirectory,
            "txn-" + UUID.randomUUID().toString() + ".json"
        );
        try {
            writeJournal(journal, mutations);
        } catch (IOException e) {
            throw new StorageException(
                FailureKind.IO,
                "could not write transaction journal",
                e
            );
        }
        try {
            for (Mutation mutation : mutations) {
                if (mutation.after == null) {
                    io.delete(mutation.file);
                } else {
                    io.write(mutation.file, mutation.after);
                }
            }
            io.delete(journal);
        } catch (IOException e) {
            try {
                rollback(journal, mutations);
            } catch (IOException rollbackError) {
                e.addSuppressed(rollbackError);
            }
            throw new StorageException(
                FailureKind.IO,
                "transaction failed and was rolled back",
                e
            );
        } catch (RuntimeException e) {
            // Simulated/hard crash: leave the journal for next-start recovery.
            throw e;
        }
    }

    private void writeJournal(File journal, List<Mutation> mutations)
        throws IOException {
        JSONObject root = new JSONObject();
        put(root, "format_version", TXN_FORMAT_VERSION);
        JSONArray files = new JSONArray();
        for (Mutation mutation : mutations) {
            JSONObject entry = new JSONObject();
            put(entry, "path", mutation.path);
            put(entry,
                "before",
                mutation.before == null
                    ? JSONObject.NULL
                    : Base64.getEncoder().encodeToString(mutation.before)
            );
            files.put(entry);
        }
        put(root, "files", files);
        io.write(
            journal,
            root.toString().getBytes(StandardCharsets.UTF_8)
        );
    }

    private void rollbackJournal(File journal) throws StorageException {
        JSONObject parsed;
        try {
            if (!io.exists(journal)) {
                return;
            }
            byte[] bytes = io.read(journal);
            parsed = new JSONObject(new String(bytes, StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new StorageException(
                FailureKind.IO,
                "could not read transaction journal " + journal.getName(),
                e
            );
        } catch (org.json.JSONException e) {
            throw new StorageException(
                FailureKind.INVALID_STATE,
                "transaction journal is malformed: " + journal.getName(),
                e
            );
        }
        if (parsed.optInt("format_version", -1) != TXN_FORMAT_VERSION) {
            throw new StorageException(
                FailureKind.INVALID_STATE,
                "transaction journal has unsupported format: " + journal.getName()
            );
        }
        JSONArray files = parsed.optJSONArray("files");
        if (files == null) {
            throw new StorageException(
                FailureKind.INVALID_STATE,
                "transaction journal has no files: " + journal.getName()
            );
        }
        try {
            for (int index = files.length() - 1; index >= 0; index--) {
                JSONObject entry = files.getJSONObject(index);
                String path = entry.getString("path");
                File file = resolveJournalPath(path);
                Object beforeValue = entry.opt("before");
                if (beforeValue == null || beforeValue == JSONObject.NULL) {
                    io.delete(file);
                } else {
                    io.write(
                        file,
                        Base64.getDecoder().decode((String) beforeValue)
                    );
                }
            }
            io.delete(journal);
        } catch (IOException e) {
            throw new StorageException(
                FailureKind.IO,
                "could not roll back transaction journal " + journal.getName(),
                e
            );
        } catch (org.json.JSONException
            | IllegalArgumentException
            | ClassCastException e) {
            throw new StorageException(
                FailureKind.INVALID_STATE,
                "transaction journal is malformed: " + journal.getName(),
                e
            );
        }
    }

    private void rollback(File journal, List<Mutation> mutations)
        throws IOException {
        IOException failure = null;
        for (int index = mutations.size() - 1; index >= 0; index--) {
            Mutation mutation = mutations.get(index);
            try {
                if (mutation.before == null) {
                    io.delete(mutation.file);
                } else {
                    io.write(mutation.file, mutation.before);
                }
            } catch (IOException e) {
                if (failure == null) {
                    failure = e;
                } else {
                    failure.addSuppressed(e);
                }
            }
        }
        try {
            io.delete(journal);
        } catch (IOException e) {
            if (failure == null) {
                failure = e;
            } else {
                failure.addSuppressed(e);
            }
        }
        if (failure != null) {
            throw failure;
        }
    }

    private File resolveJournalPath(String path) throws IOException {
        if (SceneContextIndexStore.FILE_NAME.equals(path)) {
            return indexStore.getFile();
        }
        if (path.startsWith(ContextStore.DIRECTORY_NAME + "/")) {
            String fileName = path.substring(ContextStore.DIRECTORY_NAME.length() + 1);
            requireValidStorageFileName(fileName, "context");
            return new File(contextStore.getDirectory(), fileName);
        }
        if (path.startsWith(GroupStore.DIRECTORY_NAME + "/")) {
            String fileName = path.substring(GroupStore.DIRECTORY_NAME.length() + 1);
            requireValidStorageFileName(fileName, "group");
            return new File(groupStore.getDirectory(), fileName);
        }
        throw new IOException("invalid transaction journal path: " + path);
    }

    private JSONObject readIndex() throws StorageException {
        try {
            return indexStore.readOrCreate();
        } catch (ContextGroupSchemaValidator.ValidationException e) {
            throw new StorageException(
                FailureKind.INVALID_STATE,
                "index is schema-invalid",
                e
            );
        } catch (IOException e) {
            throw new StorageException(
                FailureKind.INVALID_STATE,
                "index is missing or unreadable",
                e
            );
        }
    }

    private String requireContextStorageName(String contextId)
        throws StorageException {
        JSONObject index = readIndex();
        JSONObject contextMap = index.optJSONObject("contexts");
        String storageName = contextMap == null
            ? null
            : contextMap.optString(contextId, null);
        if (storageName == null || storageName.isEmpty()) {
            throw new StorageException(
                FailureKind.NOT_FOUND,
                "context not found: " + contextId
            );
        }
        requireValidStorageFileName(storageName, "context");
        return storageName.substring(0, storageName.length() - 5);
    }

    private String requireGroupStorageName(String groupId)
        throws StorageException {
        JSONObject index = readIndex();
        JSONObject groupMap = index.optJSONObject("groups");
        String storageName = groupMap == null
            ? null
            : groupMap.optString(groupId, null);
        if (storageName == null || storageName.isEmpty()) {
            throw new StorageException(
                FailureKind.NOT_FOUND,
                "group not found: " + groupId
            );
        }
        requireValidStorageFileName(storageName, "group");
        return storageName.substring(0, storageName.length() - 5);
    }

    private boolean groupContains(String groupId, String contextId)
        throws StorageException {
        JSONObject group = getGroup(groupId);
        JSONArray contexts = group.optJSONArray("contexts");
        for (int index = 0; index < contexts.length(); index++) {
            if (contextId.equals(GroupContextEntry.contextIdAt(contexts, index))) {
                return true;
            }
        }
        return false;
    }

    /**
     * Enforces the invariant that when both Active Context and Active Group are
     * set, the Active Group must still contain the Active Context. Called
     * before persisting group edits that can change the group membership.
     */
    private void requireActiveGroupContainsActiveContext(
        JSONObject index,
        String groupId,
        JSONArray contexts
    ) throws StorageException {
        String activeContextId = nullableString(index, "active_context_id");
        String activeGroupId = nullableString(index, "active_group_id");
        if (activeContextId == null
            || activeGroupId == null
            || !activeGroupId.equals(groupId)) {
            return;
        }
        if (!jsonArrayContains(contexts, activeContextId)) {
            throw new StorageException(
                FailureKind.INVALID_ACTIVE_GROUP,
                "active group " + groupId
                    + " does not contain active context " + activeContextId
                    + "; re-add the context, clear the active group, or choose another group"
            );
        }
    }

    private static boolean jsonArrayContains(
        JSONArray values,
        String target
    ) {
        if (values == null || target == null) {
            return false;
        }
        for (int index = 0; index < values.length(); index++) {
            JSONObject entry = values.optJSONObject(index);
            if (entry != null
                && target.equals(entry.optString(GroupContextEntry.CONTEXT_ID, null))) {
                return true;
            }
        }
        return false;
    }

    private JSONObject readGroupByStorage(String groupId, String storageFileName)
        throws StorageException {
        requireValidStorageFileName(storageFileName, "group");
        String storageName = storageFileName.substring(
            0,
            storageFileName.length() - 5
        );
        try {
            JSONObject group = groupStore.read(storageName);
            if (!groupId.equals(group.optString("id"))) {
                throw new StorageException(
                    FailureKind.INVALID_STATE,
                    "group file id does not match index for " + groupId
                );
            }
            return group;
        } catch (ContextGroupSchemaValidator.ValidationException e) {
            throw new StorageException(
                FailureKind.INVALID_STATE,
                "group file is schema-invalid: " + groupId,
                e
            );
        } catch (IOException e) {
            throw new StorageException(
                FailureKind.INVALID_STATE,
                "group file is missing or unreadable: " + groupId,
                e
            );
        }
    }

    private String allocateStorageName(
        JSONObject map,
        ContextStore store,
        String displayName,
        String kind
    ) {
        return allocateStorageName(
            collectUsedStorageNames(map, store.listStorageNames()),
            storageNameFromDisplayName(displayName, kind)
        );
    }

    private String allocateStorageName(
        JSONObject map,
        GroupStore store,
        String displayName,
        String kind
    ) {
        return allocateStorageName(
            collectUsedStorageNames(map, store.listStorageNames()),
            storageNameFromDisplayName(displayName, kind)
        );
    }

    private static Set<String> collectUsedStorageNames(
        JSONObject map,
        List<String> storageNames
    ) {
        Set<String> used = new HashSet<>();
        if (map != null) {
            for (String key : sortedKeys(map)) {
                String fileName = map.optString(key, "");
                if (!fileName.isEmpty() && fileName.endsWith(".json")) {
                    used.add(fileName.substring(0, fileName.length() - 5));
                }
            }
        }
        for (String existing : storageNames) {
            used.add(existing);
        }
        return used;
    }

    private static String allocateStorageName(
        Set<String> used,
        String base
    ) {
        String candidate = base;
        int suffix = 2;
        while (used.contains(candidate)) {
            String suffixText = "_" + suffix;
            int maxBase = 64 - suffixText.length();
            String basePart = base.length() > maxBase
                ? base.substring(0, maxBase)
                : base;
            candidate = basePart + suffixText;
            suffix++;
        }
        return candidate;
    }

    private static String storageNameFromDisplayName(
        String displayName,
        String kind
    ) {
        String defaultValue = "context".equals(kind) ? "context" : "group";
        if (displayName == null || displayName.trim().isEmpty()) {
            return defaultValue;
        }
        StringBuilder sanitized = new StringBuilder();
        for (int index = 0; index < displayName.length(); index++) {
            char c = displayName.charAt(index);
            if ((c >= 'a' && c <= 'z')
                || (c >= 'A' && c <= 'Z')
                || (c >= '0' && c <= '9')
                || c == '_'
                || c == '-') {
                sanitized.append(c);
            } else if (c == ' ' || c == '\t' || c == '\n' || c == '\r') {
                sanitized.append('_');
            }
        }
        String base = sanitized.toString();
        if (base.isEmpty()) {
            return defaultValue;
        }
        if (!Character.isLetter(base.charAt(0))) {
            base = ("context".equals(kind) ? "ctx_" : "grp_") + base;
        }
        if (base.length() > 64) {
            base = base.substring(0, 64);
        }
        if (base.length() == 0 || !Character.isLetter(base.charAt(0))) {
            return defaultValue;
        }
        return base;
    }

    private static String requireDisplayName(String value, String kind)
        throws StorageException {
        if (value == null || value.trim().isEmpty()) {
            throw new StorageException(
                FailureKind.INVALID_ARGUMENT,
                kind + " display_name must not be empty"
            );
        }
        if (value.length() > 200) {
            throw new StorageException(
                FailureKind.INVALID_ARGUMENT,
                kind + " display_name is too long"
            );
        }
        return value;
    }

    private static boolean sameNullable(String left, String right) {
        return left == null ? right == null : left.equals(right);
    }

    private static void validateIdOrNull(String id, String label)
        throws StorageException {
        if (id == null) {
            return;
        }
        if (id.length() > ID_MAX_LENGTH || !id.matches(ID_PATTERN)) {
            throw new StorageException(
                FailureKind.INVALID_ARGUMENT,
                label + " is not a valid UUID/id"
            );
        }
    }

    private static void requireIndexEntry(
        JSONObject index,
        String mapKey,
        String id,
        String kind
    ) throws StorageException {
        JSONObject map = index.optJSONObject(mapKey);
        if (map == null || !map.has(id)) {
            throw new StorageException(
                FailureKind.NOT_FOUND,
                kind + " not found: " + id
            );
        }
    }

    private static void requireValidStorageFileName(
        String fileName,
        String kind
    ) throws StorageException {
        if (fileName == null
            || !fileName.endsWith(".json")
            || !ContextStore.isValidStorageName(
                fileName.substring(0, fileName.length() - 5)
            )) {
            throw new StorageException(
                FailureKind.INVALID_STATE,
                "index references invalid " + kind + " file name: " + fileName
            );
        }
    }

    private static void validateSceneEntries(JSONArray scenes)
        throws StorageException {
        if (scenes == null) {
            throw new StorageException(
                FailureKind.INVALID_ARGUMENT,
                "scenes is null"
            );
        }
        Set<String> sceneNames = new HashSet<>();
        Set<String> entryIds = new HashSet<>();
        for (int index = 0; index < scenes.length(); index++) {
            JSONObject entry = scenes.optJSONObject(index);
            if (entry == null) {
                throw new StorageException(
                    FailureKind.INVALID_ARGUMENT,
                    "scene entry at index " + index + " must be an object"
                );
            }
            String scene = entry.optString("scene", "");
            String entryId = entry.optString("entry_id", "");
            if (scene.isEmpty() || !sceneNames.add(scene)) {
                throw new StorageException(
                    FailureKind.INVALID_ARGUMENT,
                    "same scene can appear at most once in one context: " + scene
                );
            }
            if (entryId.isEmpty() || !entryIds.add(entryId)) {
                throw new StorageException(
                    FailureKind.INVALID_ARGUMENT,
                    "scene entry_id must be unique: " + entryId
                );
            }
        }
    }

    private static void validateGroupContextIds(
        JSONArray contexts,
        JSONObject index
    ) throws StorageException {
        if (contexts == null) {
            throw new StorageException(
                FailureKind.INVALID_ARGUMENT,
                "contexts is null"
            );
        }
        JSONObject contextMap = index.optJSONObject("contexts");
        Set<String> seen = new HashSet<>();
        Set<String> entryIds = new HashSet<>();
        for (int i = 0; i < contexts.length(); i++) {
            JSONObject entry;
            try {
                entry = GroupContextEntry.require(contexts, i);
            } catch (IllegalArgumentException e) {
                throw new StorageException(
                    FailureKind.INVALID_ARGUMENT,
                    e.getMessage(),
                    e
                );
            }
            String contextId = entry.optString(GroupContextEntry.CONTEXT_ID, "");
            String entryId = entry.optString(GroupContextEntry.ENTRY_ID, "");
            if (!seen.add(contextId)) {
                throw new StorageException(
                    FailureKind.INVALID_ARGUMENT,
                    "group contexts must not contain duplicates: " + contextId
                );
            }
            if (!entryIds.add(entryId)) {
                throw new StorageException(
                    FailureKind.INVALID_ARGUMENT,
                    "group context_entry_id must be unique: " + entryId
                );
            }
            if (contextMap == null || !contextMap.has(contextId)) {
                throw new StorageException(
                    FailureKind.INVALID_ARGUMENT,
                    "group references unknown context: " + contextId
                );
            }
        }
    }

    private void validateContextDocument(JSONObject context)
        throws StorageException {
        try {
            validator.validateContext(context);
        } catch (ContextGroupSchemaValidator.ValidationException e) {
            throw new StorageException(
                FailureKind.INVALID_ARGUMENT,
                "context document is invalid: " + e.getMessage(),
                e
            );
        }
    }

    private void validateGroupDocument(JSONObject group)
        throws StorageException {
        try {
            validator.validateGroup(group);
        } catch (ContextGroupSchemaValidator.ValidationException e) {
            throw new StorageException(
                FailureKind.INVALID_ARGUMENT,
                "group document is invalid: " + e.getMessage(),
                e
            );
        }
    }

    private static void copyRequiredContextFields(
        JSONObject target,
        JSONObject source,
        JSONObject existing
    ) throws StorageException {
        put(target, "version", FORMAT_VERSION);
        put(target, "id", source.optString("id"));
        put(target, "storage_name", source.optString("storage_name"));
        put(target, "display_name", source.optString("display_name"));
        put(target, "created_at", existing.optLong("created_at", 0L));
        put(target,
            "retention",
            source.has("retention")
                ? source.optJSONObject("retention")
                : existing.optJSONObject("retention")
        );
        put(target,
            "manual_descriptions",
            source.optJSONObject("manual_descriptions")
        );
        put(target, "summary", source.optJSONObject("summary"));
        put(target, "scenes", source.optJSONArray("scenes"));
    }

    private static void copyRequiredGroupFields(
        JSONObject target,
        JSONObject source,
        JSONObject existing
    ) throws StorageException {
        put(target, "version", FORMAT_VERSION);
        put(target, "id", source.optString("id"));
        put(target, "storage_name", source.optString("storage_name"));
        put(target, "display_name", source.optString("display_name"));
        put(target, "created_at", existing.optLong("created_at", 0L));
        put(target, "contexts", source.optJSONArray("contexts"));
        put(target, "summary", source.optJSONObject("summary"));
    }

    private static JSONArray removeContextId(JSONArray values, String id) {
        JSONArray result = new JSONArray();
        if (values == null) {
            return result;
        }
        for (int index = 0; index < values.length(); index++) {
            JSONObject value = values.optJSONObject(index);
            if (value != null
                && !id.equals(value.optString(GroupContextEntry.CONTEXT_ID, ""))) {
                result.put(value);
            }
        }
        return result;
    }

    private static List<String> sortedKeys(JSONObject map) {
        if (map == null) {
            return Collections.emptyList();
        }
        List<String> keys = new ArrayList<>();
        java.util.Iterator<String> iterator = map.keys();
        while (iterator.hasNext()) {
            keys.add(iterator.next());
        }
        Collections.sort(keys);
        return keys;
    }

    private static String nullableString(JSONObject object, String key) {
        if (!object.has(key) || object.isNull(key)) {
            return null;
        }
        return object.optString(key, null);
    }

    private static JSONObject copyJsonForImport(
        JSONObject source,
        String kind,
        int index
    ) throws StorageException {
        try {
            return new JSONObject(source.toString());
        } catch (org.json.JSONException e) {
            throw new StorageException(
                FailureKind.INVALID_ARGUMENT,
                kind + " document at index " + index + " is not encodable",
                e
            );
        }
    }

    private static Set<String> storageNames(
        Map<String, JSONObject> documents
    ) {
        Set<String> names = new HashSet<>();
        for (JSONObject document : documents.values()) {
            String storage = document.optString("storage_name", "");
            if (!storage.isEmpty()) {
                names.add(storage);
            }
        }
        return names;
    }

    private static boolean containsContext(
        JSONObject group,
        String contextId
    ) {
        JSONArray entries = group == null
            ? null
            : group.optJSONArray("contexts");
        if (entries == null) {
            return false;
        }
        for (int index = 0; index < entries.length(); index++) {
            JSONObject entry = entries.optJSONObject(index);
            if (entry != null
                && contextId.equals(entry.optString(
                    GroupContextEntry.CONTEXT_ID,
                    ""
                ))) {
                return true;
            }
        }
        return false;
    }

    private static String stripJsonSuffix(String value) {
        return value != null && value.endsWith(".json")
            ? value.substring(0, value.length() - 5)
            : value;
    }

    private static String uniqueImportId(
        Set<String> existing,
        Set<String> planned
    ) {
        String id;
        do {
            id = UUID.randomUUID().toString();
        } while (existing.contains(id) || planned.contains(id));
        return id;
    }

    private static String uniqueImportStorage(
        String requested,
        Set<String> used,
        String prefix
    ) throws StorageException {
        String base = stripJsonSuffix(requested);
        if (base == null || base.trim().isEmpty()) {
            base = prefix;
        }
        base = base.replaceAll("[^A-Za-z0-9_-]", "_");
        if (base.isEmpty() || !Character.isLetter(base.charAt(0))) {
            base = prefix + "_" + base;
        }
        if (base.length() > 60) {
            base = base.substring(0, 60);
        }
        String candidate = base;
        int suffix = 1;
        while (used.contains(candidate) || !ContextStore.isValidStorageName(candidate)) {
            String tail = "_" + suffix++;
            int length = Math.min(64 - tail.length(), base.length());
            candidate = base.substring(0, length) + tail;
        }
        used.add(candidate);
        return candidate;
    }

    private static void putNullableString(
        JSONObject object,
        String key,
        String value
    ) throws StorageException {
        put(object, key, value == null ? JSONObject.NULL : value);
    }

    private static void put(JSONObject object, String key, Object value)
        throws StorageException {
        try {
            object.put(key, value);
        } catch (org.json.JSONException e) {
            throw new StorageException(
                FailureKind.INVALID_STATE,
                "could not encode JSON field " + key,
                e
            );
        }
    }

    private static void put(JSONObject object, String key, long value)
        throws StorageException {
        try {
            object.put(key, value);
        } catch (org.json.JSONException e) {
            throw new StorageException(
                FailureKind.INVALID_STATE,
                "could not encode JSON field " + key,
                e
            );
        }
    }

    private static void put(JSONObject object, String key, int value)
        throws StorageException {
        try {
            object.put(key, value);
        } catch (org.json.JSONException e) {
            throw new StorageException(
                FailureKind.INVALID_STATE,
                "could not encode JSON field " + key,
                e
            );
        }
    }

    private static void put(JSONObject object, String key, boolean value)
        throws StorageException {
        try {
            object.put(key, value);
        } catch (org.json.JSONException e) {
            throw new StorageException(
                FailureKind.INVALID_STATE,
                "could not encode JSON field " + key,
                e
            );
        }
    }

    private void ensureDirectories() throws StorageException {
        if (!rootDirectory.isDirectory()
            && !rootDirectory.mkdirs()
            && !rootDirectory.isDirectory()) {
            throw new StorageException(
                FailureKind.IO,
                "could not create scene context root directory"
            );
        }
        File contexts = contextStore.getDirectory();
        File groups = groupStore.getDirectory();
        File txn = new File(rootDirectory, TXN_DIRECTORY_NAME);
        if (!contexts.isDirectory()
            && !contexts.mkdirs()
            && !contexts.isDirectory()) {
            throw new StorageException(
                FailureKind.IO,
                "could not create contexts directory"
            );
        }
        if (!groups.isDirectory()
            && !groups.mkdirs()
            && !groups.isDirectory()) {
            throw new StorageException(
                FailureKind.IO,
                "could not create groups directory"
            );
        }
        if (!txn.isDirectory()
            && !txn.mkdirs()
            && !txn.isDirectory()) {
            throw new StorageException(
                FailureKind.IO,
                "could not create transaction journal directory"
            );
        }
    }

    private static Context requireContext(Context context) {
        if (context == null) {
            throw new IllegalArgumentException("context cannot be null");
        }
        return context;
    }
}
