package com.quarty.housamoembedtrans.management.pending;

import com.quarty.housamoembedtrans.storage.json.AtomicJsonFileIo;
import com.quarty.housamoembedtrans.storage.json.JsonSchemaValidator;
import com.quarty.housamoembedtrans.util.IoUtils;

import android.content.Context;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.math.BigDecimal;
import java.io.File;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Pattern;

/**
 * Durable storage for the unified "pending process" list.
 *
 * <p>The index is the only publication and visibility boundary.  Entry files
 * are content-addressed by a durable, never-reused decimal number, while the
 * index carries the canonical key and the small amount of metadata required
 * to enumerate them.  Mutations use a roll-forward journal so a process crash
 * cannot publish a half-created or half-removed entry.</p>
 *
 * <p>A {@code File} constructor treats its argument as the
 * {@code files/pending_process} directory.  The Context constructor derives
 * that directory from the application's files directory.  The schema and IO
 * overloads are deliberately public seams for host fixtures.</p>
 */
public final class PendingProcessStore {

    /** Immutable, metadata-only reference table for hot-path consumers. */
    public static final class ReferenceSnapshot {
        private final Set<String> pendingKeys;
        private final Map<String, Set<String>> canonicalIdsByKind;

        private ReferenceSnapshot(Set<String> pendingKeys) {
            this.pendingKeys = Collections.unmodifiableSet(
                new HashSet<>(pendingKeys)
            );
            Map<String, Set<String>> byKind = new HashMap<>();
            for (String pendingKey : this.pendingKeys) {
                int separator = pendingKey.indexOf(':');
                if (separator <= 0 || separator >= pendingKey.length() - 1) {
                    throw new IllegalArgumentException(
                        "pending key identity is invalid"
                    );
                }
                String kind = pendingKey.substring(0, separator);
                String canonicalId = pendingKey.substring(separator + 1);
                Set<String> ids = byKind.get(kind);
                if (ids == null) {
                    ids = new HashSet<>();
                    byKind.put(kind, ids);
                }
                ids.add(canonicalId);
            }
            Map<String, Set<String>> immutableByKind = new HashMap<>();
            for (Map.Entry<String, Set<String>> entry : byKind.entrySet()) {
                immutableByKind.put(
                    entry.getKey(),
                    Collections.unmodifiableSet(new HashSet<>(entry.getValue()))
                );
            }
            this.canonicalIdsByKind = Collections.unmodifiableMap(
                immutableByKind
            );
        }

        public boolean isPending(String kind, String canonicalId) {
            return pendingKeys.contains(pendingKeyFor(kind, canonicalId));
        }

        public boolean isEmpty() {
            return pendingKeys.isEmpty();
        }

        public Set<String> pendingKeys() {
            return pendingKeys;
        }

        /** Returns the immutable canonical IDs referenced for one kind. */
        public Set<String> canonicalIdsForKind(String kind) {
            requireKind(kind);
            Set<String> ids = canonicalIdsByKind.get(kind);
            return ids == null ? Collections.emptySet() : ids;
        }
    }

    /** Failure categories shared by storage and owner lifecycle operations. */
    public enum FailureKind {
        INVALID_ARGUMENT,
        INVALID_STATE,
        NOT_FOUND,
        CONFLICT,
        DELETE_ONLY,
        IO
    }

    /** Typed failure for a pending lifecycle operation or journal replay. */
    public static class PendingProcessException extends IOException {
        private static final long serialVersionUID = 1L;
        public final FailureKind kind;

        public PendingProcessException(FailureKind kind, String message) {
            super(message);
            this.kind = kind;
        }

        public PendingProcessException(
            FailureKind kind,
            String message,
            Throwable cause
        ) {
            super(message, cause);
            this.kind = kind;
        }
    }

    /** Owner boundary used by the store's idempotent lifecycle protocol. */
    public interface OwnerAdapter {
        String kind();

        void hide(String canonicalId, JSONObject pendingEntry) throws Exception;

        void restore(String canonicalId, JSONObject pendingEntry)
            throws Exception;

        void permanentlyDelete(String canonicalId, JSONObject pendingEntry)
            throws Exception;
    }

    /** Publishes a newly indexed Scene-family reference before owner hiding. */
    @FunctionalInterface
    public interface MovePublicationBarrier {
        void beforeOwnerHide(
            String kind,
            String canonicalId,
            JSONObject pendingEntry
        ) throws Exception;
    }

    /** Immutable snapshot/reference request persisted in a move journal. */
    public static final class MovePayload {
        public final String kind;
        public final Object snapshot;
        public final String externalOwner;
        public final String externalId;
        public final String reason;
        public final JSONObject restoreMetadata;
        public final JSONObject syncMetadata;

        private MovePayload(
            String kind,
            Object snapshot,
            String externalOwner,
            String externalId,
            String reason,
            JSONObject restoreMetadata,
            JSONObject syncMetadata
        ) {
            this.kind = kind;
            this.snapshot = copyJsonValue(snapshot);
            this.externalOwner = externalOwner;
            this.externalId = externalId;
            this.reason = reason;
            this.restoreMetadata = copyJsonOrNull(restoreMetadata);
            this.syncMetadata = copyJsonOrNull(syncMetadata);
        }

        public static MovePayload snapshot(
            String kind,
            Object snapshot,
            String reason,
            JSONObject restoreMetadata,
            JSONObject syncMetadata
        ) {
            if (snapshot == null || snapshot == JSONObject.NULL) {
                throw new IllegalArgumentException("snapshot is required");
            }
            return new MovePayload(
                kind,
                snapshot,
                null,
                null,
                reason,
                restoreMetadata,
                syncMetadata
            );
        }

        public static MovePayload externalReference(
            String kind,
            String owner,
            String stableId,
            String reason,
            JSONObject restoreMetadata,
            JSONObject syncMetadata
        ) {
            return new MovePayload(
                kind,
                null,
                owner,
                stableId,
                reason,
                restoreMetadata,
                syncMetadata
            );
        }

        private boolean isSnapshot() {
            return snapshot != null && snapshot != JSONObject.NULL;
        }
    }

    public static final String DIRECTORY_NAME = "pending_process";
    public static final String INDEX_FILE_NAME = "index.json";
    public static final String ENTRIES_DIRECTORY_NAME = "entries";
    public static final String TRANSACTION_DIRECTORY_NAME = ".txn";
    public static final String TRANSACTION_FILE_NAME = "transaction.json";

    public static final String INDEX_SCHEMA_ASSET_PATH =
        "schema/pending_process_index_schema.json";
    public static final String ENTRY_SCHEMA_ASSET_PATH =
        "schema/pending_process_entry_schema.json";

    public static final int FORMAT_VERSION = 1;

    public static final String RESTORE_MODE_SNAPSHOT = "snapshot";
    public static final String RESTORE_MODE_DELETE_ONLY = "delete_only";
    public static final String SYNC_NONE = "none";
    public static final String SYNC_DELETE_SCENE_ON_NEXT_SYNC =
        "delete_scene_on_next_sync";

    private static final String OP_CREATE = "create";
    private static final String OP_REMOVE = "remove";
    private static final String OP_MOVE = "move";
    private static final String OP_RESTORE = "restore";
    private static final String OP_PERMANENT_DELETE = "permanent_delete";
    private static final String PHASE_PREPARED = "prepared";
    private static final String PHASE_PENDING_PUBLISHED = "pending_published";
    private static final String PHASE_OWNER_APPLIED = "owner_applied";
    private static final String PHASE_ROLLBACK_PENDING = "rollback_pending";

    /** A JSON index is metadata only; this also bounds recovery memory. */
    public static final int MAX_INDEX_BYTES = 8 * 1024 * 1024;
    /** Complete snapshots/results may be large, but never unbounded. */
    public static final int MAX_ENTRY_BYTES = 64 * 1024 * 1024;
    /** The journal contains an entry plus two indexes. */
    public static final int MAX_TRANSACTION_BYTES = 96 * 1024 * 1024;
    private static final int MAX_SCHEMA_BYTES = 256 * 1024;
    private static final int MAX_INDEX_ENTRIES = 100_000;
    private static final int MAX_PENDING_KEY_LENGTH = 512;
    private static final int MAX_CANONICAL_ID_LENGTH = 384;
    private static final int MAX_REASON_LENGTH = 64;
    private static final int MAX_JSON_DEPTH = 256;
    private static final long MAX_SAFE_INTEGER = 9_007_199_254_740_991L;
    // Keep one representable value for next_entry_number after the final
    // allocatable number.  JSON parsing remains within the safe integer range.
    private static final long MAX_ENTRY_NUMBER = MAX_SAFE_INTEGER - 1L;

    private static final Pattern ENTRY_FILE_PATTERN = Pattern.compile(
        "^pending_process_[1-9][0-9]*\\.json$"
    );
    private static final Pattern REASON_PATTERN = Pattern.compile(
        "^[a-z][a-z0-9_]{0,63}$"
    );
    private static final Pattern SAFE_EXTERNAL_ID_PATTERN = Pattern.compile(
        "^[A-Za-z0-9][A-Za-z0-9._:@+\\-]{0,255}$"
    );
    private static final Set<String> PENDING_KINDS;
    private static final Set<String> EXTERNAL_OWNERS;
    private static final Map<String, Object> ROOT_LOCKS =
        new ConcurrentHashMap<>();
    /** A flag reserves owner lifecycle work without locking across callbacks. */
    private static final Map<String, AtomicBoolean> ROOT_OPERATION_GUARDS =
        new ConcurrentHashMap<>();

    static {
        Set<String> kinds = new HashSet<>();
        kinds.add("scene");
        kinds.add("language");
        kinds.add("context");
        kinds.add("group");
        kinds.add("character");
        kinds.add("term");
        kinds.add("damaged_translation_job");
        PENDING_KINDS = Collections.unmodifiableSet(kinds);

        Set<String> owners = new HashSet<>();
        owners.add("translation_job");
        EXTERNAL_OWNERS = Collections.unmodifiableSet(owners);
    }

    private final File rootDirectory;
    private final File indexFile;
    private final File entriesDirectory;
    private final File transactionDirectory;
    private final File transactionFile;
    private final JsonSchemaValidator indexSchemaValidator;
    private final JsonSchemaValidator entrySchemaValidator;
    private final AtomicJsonFileIo io;
    private final Object processLock;
    private final AtomicBoolean ownerOperationActive;

    /** Creates a store under {@code files/pending_process}. */
    public PendingProcessStore(Context context) {
        this(
            new File(requireContext(context).getFilesDir(), DIRECTORY_NAME),
            loadSchema(context, INDEX_SCHEMA_ASSET_PATH),
            loadSchema(context, ENTRY_SCHEMA_ASSET_PATH),
            AtomicJsonFileIo.android()
        );
    }

    /**
     * Explicit directory/schema/IO seam.  The directory is the pending root,
     * not the parent files directory.
     */
    public PendingProcessStore(
        File rootDirectory,
        JSONObject indexSchema,
        JSONObject entrySchema,
        AtomicJsonFileIo io
    ) {
        this(
            rootDirectory,
            new JsonSchemaValidator(requireSchema(indexSchema, "index")),
            new JsonSchemaValidator(requireSchema(entrySchema, "entry")),
            io
        );
    }

    /** Explicit validator/IO seam for fixtures that already loaded schemas. */
    public PendingProcessStore(
        File rootDirectory,
        JsonSchemaValidator indexSchemaValidator,
        JsonSchemaValidator entrySchemaValidator,
        AtomicJsonFileIo io
    ) {
        if (rootDirectory == null
            || indexSchemaValidator == null
            || entrySchemaValidator == null
            || io == null) {
            throw new IllegalArgumentException(
                "rootDirectory, schemas and io are required"
            );
        }
        this.rootDirectory = rootDirectory;
        this.indexFile = new File(rootDirectory, INDEX_FILE_NAME);
        this.entriesDirectory = new File(
            rootDirectory,
            ENTRIES_DIRECTORY_NAME
        );
        this.transactionDirectory = new File(
            rootDirectory,
            TRANSACTION_DIRECTORY_NAME
        );
        this.transactionFile = new File(
            transactionDirectory,
            TRANSACTION_FILE_NAME
        );
        this.indexSchemaValidator = indexSchemaValidator;
        this.entrySchemaValidator = entrySchemaValidator;
        this.io = io;
        this.processLock = lockForRoot(rootDirectory);
        this.ownerOperationActive = operationGuardForRoot(rootDirectory);
    }

    /**
     * Returns the validated index entries.  Each object is parsed from a new
     * JSON string, so mutating the returned array cannot mutate store state.
     */
    public JSONArray listPending() throws IOException {
        synchronized (processLock) {
            JSONObject index = prepare();
            return copyArray(index.optJSONArray("entries"));
        }
    }

    /**
     * Loads the reference index without opening any per-item snapshot file.
     * Consumers use this boundary to exclude pending owners from claims,
     * export, import-derived work and write-back while keeping their core
     * traversal algorithms unchanged.
     */
    public ReferenceSnapshot snapshotReferences()
        throws IOException {
        synchronized (processLock) {
            JSONObject index = prepare();
            JSONArray entries = index.optJSONArray("entries");
            Set<String> keys = new HashSet<>();
            for (int position = 0; position < entries.length(); position++) {
                JSONObject metadata = entries.optJSONObject(position);
                if (metadata == null) {
                    throw new IOException(
                        "pending index metadata is invalid at " + position
                    );
                }
                String pendingKey = metadata.optString("pending_key", "");
                requirePendingKey(pendingKey);
                if (!keys.add(pendingKey)) {
                    throw new IOException(
                        "pending index contains duplicate key: " + pendingKey
                    );
                }
            }
            return new ReferenceSnapshot(keys);
        }
    }

    /** Reads one complete pending entry by its canonical pending key. */
    public JSONObject readPending(String pendingKey)
        throws IOException {
        JSONObject entry = readPendingIfPresent(pendingKey);
        if (entry == null) {
            throw new IOException("pending key does not exist: " + pendingKey);
        }
        return entry;
    }

    /**
     * Reads one complete pending entry, or returns {@code null} only when the
     * validated index does not publish that key. Corrupt indexes and entry
     * files remain hard failures and are never collapsed into absence.
     */
    public JSONObject readPendingIfPresent(String pendingKey)
        throws IOException {
        synchronized (processLock) {
            requirePendingKey(pendingKey);
            JSONObject index = prepare();
            JSONObject metadata = findMetadata(index, pendingKey);
            if (metadata == null) {
                return null;
            }
            JSONObject entry = readEntryForMetadata(metadata);
            return copyObject(entry);
        }
    }

    private JSONObject removePendingIfPresentInternal(
        String pendingKey,
        boolean journal
    ) throws IOException {
        synchronized (processLock) {
            requirePendingKey(pendingKey);
            JSONObject index = prepare();
            JSONArray entries = index.optJSONArray("entries");
            int removeAt = -1;
            JSONObject metadata = null;
            for (int indexPosition = 0; indexPosition < entries.length(); indexPosition++) {
                JSONObject candidate = entries.optJSONObject(indexPosition);
                if (candidate != null
                    && pendingKey.equals(candidate.optString("pending_key", ""))) {
                    removeAt = indexPosition;
                    metadata = candidate;
                    break;
                }
            }
            if (removeAt < 0 || metadata == null) {
                return null;
            }
            JSONObject removed = readEntryForMetadata(metadata);
            JSONObject nextIndex = copyObject(index);
            JSONArray nextEntries = nextIndex.optJSONArray("entries");
            nextEntries.remove(removeAt);
            JSONObject target = metadataForEntry(removed);
            JSONObject transaction = transactionForRemove(index, nextIndex, target);
            if (journal) {
                if (isOwnerTransactionPending()) {
                    throw new PendingProcessException(
                        FailureKind.CONFLICT,
                        "another PendingProcess operation is in progress"
                    );
                }
                writeTransaction(transaction);
            }
            try {
                writeIndex(nextIndex);
                File entryFile = fileForMetadata(metadata);
                if (io.exists(entryFile)) {
                    io.delete(entryFile);
                }
                if (journal) {
                    clearTransaction();
                }
            } catch (IOException failure) {
                // The durable journal is intentionally retained for next access.
                if (!journal) {
                    throw failure;
                }
                throw failure;
            }
            return copyObject(removed);
        }
    }

    /** Canonical public key builder shared by coordinating owner operations. */
    public static String pendingKeyFor(String kind, String canonicalId) {
        return canonicalPendingKey(kind, canonicalId);
    }

    /** Publishes a snapshot/reference entry, then applies the owner hide. */
    public JSONObject move(
        OwnerAdapter owner,
        String canonicalId,
        MovePayload payload
    ) throws IOException {
        return move(owner, canonicalId, payload, null);
    }

    /**
     * Publishes the durable entry, crosses the caller-supplied policy barrier,
     * and only then invokes the idempotent owner hide.
     */
    public JSONObject move(
        OwnerAdapter owner,
        String canonicalId,
        MovePayload payload,
        MovePublicationBarrier publicationBarrier
    ) throws IOException {
        requireOwner(owner);
        requireCanonicalIdForOwner(canonicalId);
        requirePayload(owner, payload);
        beginOwnerOperation();
        try {
            synchronized (processLock) {
                prepare();
                if (hasTransaction()) {
                    throw new PendingProcessException(
                        FailureKind.CONFLICT,
                        "another PendingProcess operation is in progress"
                    );
                }
                String pendingKey;
                try {
                    pendingKey = canonicalPendingKey(payload.kind, canonicalId);
                } catch (IllegalArgumentException e) {
                    throw new PendingProcessException(
                        FailureKind.INVALID_ARGUMENT,
                        "PendingProcess identity is invalid",
                        e
                    );
                }
                if (readPendingIfPresent(pendingKey) != null) {
                    throw new PendingProcessException(
                        FailureKind.CONFLICT,
                        "PendingProcess entry already exists: " + pendingKey
                    );
                }
                JSONObject transaction = object();
                put(transaction, "version", FORMAT_VERSION);
                put(transaction, "operation", OP_MOVE);
                put(transaction, "phase", PHASE_PREPARED);
                put(transaction, "pending_key", pendingKey);
                put(transaction, "kind", payload.kind);
                put(transaction, "canonical_id", canonicalId);
                put(transaction, "request", requestJson(payload));
                put(transaction, "entry", JSONObject.NULL);
                writeTransaction(transaction);
            }
            return replayOwner(owner, publicationBarrier);
        } finally {
            endOwnerOperation();
        }
    }

    /** Restores one pending owner and removes its published entry. */
    public JSONObject restore(
        OwnerAdapter owner,
        String pendingKey
    ) throws IOException {
        return finishOwnerAction(owner, pendingKey, OP_RESTORE);
    }

    /** Permanently deletes one pending owner and removes its published entry. */
    public JSONObject permanentlyDelete(
        OwnerAdapter owner,
        String pendingKey
    ) throws IOException {
        return finishOwnerAction(owner, pendingKey, OP_PERMANENT_DELETE);
    }

    /** Returns the owner kind required to replay the current journal. */
    public String getRecoveryOwnerKind() throws IOException {
        synchronized (processLock) {
            prepare();
            JSONObject transaction = readTransaction();
            if (transaction == null || !isOwnerOperation(transaction)) {
                return null;
            }
            validateOwnerTransaction(transaction);
            return transaction.optString("kind", "");
        }
    }

    /** Replays a pending cross-owner journal with its matching adapter. */
    public void recover(OwnerAdapter owner) throws IOException {
        requireOwner(owner);
        beginOwnerOperation();
        try {
            boolean replay;
            synchronized (processLock) {
                prepare();
                replay = hasTransaction();
            }
            if (replay) {
                replayOwner(owner, null);
            }
        } finally {
            endOwnerOperation();
        }
    }

    private JSONObject finishOwnerAction(
        OwnerAdapter owner,
        String pendingKey,
        String operation
    ) throws IOException {
        requireOwner(owner);
        requirePendingKeyForOwner(pendingKey);
        beginOwnerOperation();
        try {
            synchronized (processLock) {
                prepare();
                if (hasTransaction()) {
                    throw new PendingProcessException(
                        FailureKind.CONFLICT,
                        "another PendingProcess operation is in progress"
                    );
                }
                JSONObject pending = readPendingOrFail(pendingKey);
                ensureOwnerKind(owner, pending.optString("kind", ""));
                if (OP_RESTORE.equals(operation) && isDeleteOnly(pending)) {
                    throw new PendingProcessException(
                        FailureKind.DELETE_ONLY,
                        "external-reference pending entry cannot be restored"
                    );
                }
                JSONObject transaction = object();
                put(transaction, "version", FORMAT_VERSION);
                put(transaction, "operation", operation);
                put(transaction, "phase", PHASE_PREPARED);
                put(transaction, "pending_key", pendingKey);
                put(transaction, "kind", pending.getString("kind"));
                put(transaction, "canonical_id", pending.getString("canonical_id"));
                put(transaction, "request", JSONObject.NULL);
                put(transaction, "entry", copyObject(pending));
                writeTransaction(transaction);
            }
            return replayOwner(owner, null);
        } finally {
            endOwnerOperation();
        }
    }

    /** Replays the owner journal without holding either store lock in callbacks. */
    private JSONObject replayOwner(
        OwnerAdapter owner,
        MovePublicationBarrier publicationBarrier
    ) throws IOException {
        final JSONObject transaction;
        synchronized (processLock) {
            transaction = readTransaction();
        }
        if (transaction == null) {
            return null;
        }
        validateOwnerTransaction(transaction);
        String kind = transaction.optString("kind", "");
        ensureOwnerKind(owner, kind);
        String operation = transaction.optString("operation", "");
        String phase = transaction.optString("phase", "");
        String canonicalId = transaction.optString("canonical_id", "");
        String pendingKey = transaction.optString("pending_key", "");
        JSONObject entry = transaction.optJSONObject("entry");

        if (OP_MOVE.equals(operation)) {
            if (PHASE_ROLLBACK_PENDING.equals(phase)) {
                synchronized (processLock) {
                    removePendingIfPresentInternal(pendingKey, false);
                    clearTransaction();
                }
                return entry == null ? null : copyObject(entry);
            }
            synchronized (processLock) {
                entry = ensureMoveEntry(transaction, entry);
                writeOwnerPhase(transaction, PHASE_PENDING_PUBLISHED, entry);
            }
            if (publicationBarrier != null) {
                try {
                    publicationBarrier.beforeOwnerHide(
                        kind,
                        canonicalId,
                        copyObject(entry)
                    );
                } catch (Exception barrierFailure) {
                    try {
                        synchronized (processLock) {
                            writeOwnerPhase(
                                transaction,
                                PHASE_ROLLBACK_PENDING,
                                entry
                            );
                            removePendingIfPresentInternal(pendingKey, false);
                            clearTransaction();
                        }
                    } catch (IOException cleanupError) {
                        cleanupError.addSuppressed(barrierFailure);
                        throw new PendingProcessException(
                            FailureKind.IO,
                            "could not roll back rejected PendingProcess policy barrier "
                                + pendingKey,
                            cleanupError
                        );
                    }
                    if (barrierFailure instanceof PendingProcessException) {
                        throw (PendingProcessException) barrierFailure;
                    }
                    throw new PendingProcessException(
                        FailureKind.IO,
                        "PendingProcess policy barrier failed " + pendingKey,
                        barrierFailure
                    );
                }
            }
            try {
                owner.hide(canonicalId, copyObject(entry));
            } catch (PendingProcessException e) {
                if (isDeterministicOwnerFailure(e)) {
                    try {
                        synchronized (processLock) {
                            writeOwnerPhase(
                                transaction,
                                PHASE_ROLLBACK_PENDING,
                                entry
                            );
                            removePendingIfPresentInternal(pendingKey, false);
                            clearTransaction();
                        }
                    } catch (IOException cleanupError) {
                        cleanupError.addSuppressed(e);
                        throw new PendingProcessException(
                            FailureKind.IO,
                            "could not roll back rejected PendingProcess move "
                                + pendingKey,
                            cleanupError
                        );
                    }
                }
                throw e;
            } catch (Exception e) {
                throw new PendingProcessException(
                    FailureKind.IO,
                    "owner hide failed for PendingProcess " + pendingKey,
                    e
                );
            }
            synchronized (processLock) {
                writeOwnerPhase(transaction, PHASE_OWNER_APPLIED, entry);
                clearTransaction();
            }
            return copyObject(entry);
        }

        synchronized (processLock) {
            if (entry == null) {
                entry = readPendingOrFail(pendingKey);
            }
            ensureEntryIdentity(entry, pendingKey, kind, canonicalId);
        }
        try {
            if (OP_RESTORE.equals(operation) && isDeleteOnly(entry)) {
                throw new PendingProcessException(
                    FailureKind.DELETE_ONLY,
                    "external-reference pending entry cannot be restored"
                );
            } else if (OP_RESTORE.equals(operation)) {
                owner.restore(canonicalId, copyObject(entry));
            } else if (OP_PERMANENT_DELETE.equals(operation)) {
                owner.permanentlyDelete(canonicalId, copyObject(entry));
            } else {
                throw new PendingProcessException(
                    FailureKind.INVALID_STATE,
                    "unknown PendingProcess operation"
                );
            }
        } catch (PendingProcessException e) {
            if (isDeterministicOwnerFailure(e)) {
                try {
                    synchronized (processLock) {
                        clearTransaction();
                    }
                } catch (IOException cleanupError) {
                    cleanupError.addSuppressed(e);
                    throw new PendingProcessException(
                        FailureKind.IO,
                        "could not clear rejected PendingProcess owner journal "
                            + pendingKey,
                        cleanupError
                    );
                }
            }
            throw e;
        } catch (Exception e) {
            throw new PendingProcessException(
                FailureKind.IO,
                "owner action failed for PendingProcess " + pendingKey,
                e
            );
        }
        synchronized (processLock) {
            writeOwnerPhase(transaction, PHASE_OWNER_APPLIED, entry);
            removePendingIfPresentInternal(pendingKey, false);
            clearTransaction();
        }
        return copyObject(entry);
    }

    private JSONObject ensureMoveEntry(
        JSONObject transaction,
        JSONObject journalEntry
    ) throws IOException {
        String pendingKey = transaction.optString("pending_key", "");
        JSONObject request = transaction.optJSONObject("request");
        if (journalEntry != null) {
            ensureEntryIdentity(
                journalEntry,
                pendingKey,
                transaction.optString("kind", ""),
                transaction.optString("canonical_id", "")
            );
            return copyObject(journalEntry);
        }
        if (request == null) {
            throw new PendingProcessException(
                FailureKind.INVALID_STATE,
                "move journal has neither entry nor request payload"
            );
        }
        JSONObject existing = readPendingIfPresent(pendingKey);
        if (existing != null) {
            ensureEntryIdentity(
                existing,
                pendingKey,
                transaction.optString("kind", ""),
                transaction.optString("canonical_id", "")
            );
            ensureMoveEntryMatchesRequest(existing, request);
            return copyObject(existing);
        }
        String kind = transaction.optString("kind", "");
        String canonicalId = transaction.optString("canonical_id", "");
        JSONObject restore = request.optJSONObject("restore_metadata");
        JSONObject sync = request.optJSONObject("sync_metadata");
        String reason = request.optString("reason", "user_requested");
        JSONObject created;
        String mode = request.optString("mode", "");
        if ("snapshot".equals(mode)) {
            try {
                created = createInternal(
                    pendingKey,
                    kind,
                    canonicalId,
                    snapshotPayload(request.get("snapshot")),
                    reason,
                    copyJsonOrNull(restore),
                    copyJsonOrNull(sync),
                    false
                );
            } catch (PendingProcessException e) {
                throw e;
            } catch (Exception e) {
                throw new PendingProcessException(
                    FailureKind.IO,
                    "could not republish PendingProcess entry",
                    e
                );
            }
        } else if ("external_reference".equals(mode)) {
            try {
                created = createInternal(
                    pendingKey,
                    kind,
                    canonicalId,
                    externalPayload(
                        request.getString("owner"),
                        request.getString("id")
                    ),
                    reason,
                    copyJsonOrNull(restore),
                    copyJsonOrNull(sync),
                    false
                );
            } catch (PendingProcessException e) {
                throw e;
            } catch (Exception e) {
                throw new PendingProcessException(
                    FailureKind.IO,
                    "could not republish PendingProcess entry",
                    e
                );
            }
        } else {
            throw new PendingProcessException(
                FailureKind.INVALID_STATE,
                "move journal payload mode is invalid"
            );
        }
        ensureEntryIdentity(created, pendingKey, kind, canonicalId);
        ensureMoveEntryMatchesRequest(created, request);
        return copyObject(created);
    }

    private JSONObject requestJson(MovePayload payload)
        throws PendingProcessException {
        JSONObject request = object();
        String reason = defaultReason(payload);
        try {
            request.put("mode", payload.isSnapshot() ? "snapshot" : "external_reference");
            request.put("reason", reason);
            request.put(
                "restore_metadata",
                payload.restoreMetadata == null
                    ? JSONObject.NULL
                    : copyObject(payload.restoreMetadata)
            );
            request.put(
                "sync_metadata",
                payload.syncMetadata == null
                    ? JSONObject.NULL
                    : copyObject(payload.syncMetadata)
            );
            if (payload.isSnapshot()) {
                request.put("snapshot", copyJsonValue(payload.snapshot));
            } else {
                request.put("owner", payload.externalOwner);
                request.put("id", payload.externalId);
            }
            return request;
        } catch (JSONException | IOException e) {
            throw new PendingProcessException(
                FailureKind.INVALID_ARGUMENT,
                "move payload is not JSON serializable",
                e
            );
        }
    }

    private void writeOwnerPhase(
        JSONObject transaction,
        String phase,
        JSONObject entry
    ) throws IOException {
        put(transaction, "phase", phase);
        put(transaction, "entry", entry == null ? JSONObject.NULL : copyObject(entry));
        if (OP_MOVE.equals(transaction.optString("operation", ""))
            && !PHASE_PREPARED.equals(phase)) {
            put(transaction, "request", JSONObject.NULL);
        }
        writeTransaction(transaction);
    }

    private JSONObject readPendingOrFail(String pendingKey) throws IOException {
        JSONObject entry = readPendingIfPresent(pendingKey);
        if (entry == null) {
            throw new PendingProcessException(
                FailureKind.NOT_FOUND,
                "PendingProcess entry does not exist: " + pendingKey
            );
        }
        return entry;
    }

    private static JSONObject snapshotPayload(Object snapshot) throws IOException {
        JSONObject payload = object();
        put(payload, "type", "snapshot");
        put(payload, "snapshot", snapshot);
        return payload;
    }

    private static JSONObject externalPayload(String owner, String id)
        throws IOException {
        JSONObject payload = object();
        put(payload, "type", "external_reference");
        put(payload, "owner", owner);
        put(payload, "id", id);
        return payload;
    }

    private static boolean isDeleteOnly(JSONObject entry) {
        JSONObject restore = entry.optJSONObject("restore");
        return restore != null
            && RESTORE_MODE_DELETE_ONLY.equals(restore.optString("mode", ""));
    }

    private static boolean isDeterministicOwnerFailure(PendingProcessException error) {
        return error.kind == FailureKind.CONFLICT
            || error.kind == FailureKind.INVALID_ARGUMENT
            || error.kind == FailureKind.INVALID_STATE
            || error.kind == FailureKind.NOT_FOUND
            || error.kind == FailureKind.DELETE_ONLY;
    }

    private static void requireOwner(OwnerAdapter owner)
        throws PendingProcessException {
        if (owner == null || owner.kind() == null || owner.kind().isEmpty()) {
            throw new PendingProcessException(
                FailureKind.INVALID_ARGUMENT,
                "owner adapter and owner kind are required"
            );
        }
    }

    private static void ensureOwnerKind(OwnerAdapter owner, String kind)
        throws PendingProcessException {
        if (!owner.kind().equals(kind)) {
            throw new PendingProcessException(
                FailureKind.CONFLICT,
                "owner adapter kind does not match PendingProcess kind"
            );
        }
    }

    private static void requireCanonicalIdForOwner(String canonicalId)
        throws PendingProcessException {
        if (canonicalId == null || canonicalId.trim().isEmpty()) {
            throw new PendingProcessException(
                FailureKind.INVALID_ARGUMENT,
                "canonical id is required"
            );
        }
    }

    private static void requirePendingKeyForOwner(String pendingKey)
        throws PendingProcessException {
        if (pendingKey == null || pendingKey.trim().isEmpty()) {
            throw new PendingProcessException(
                FailureKind.INVALID_ARGUMENT,
                "pending key is required"
            );
        }
    }

    private static void requirePayload(
        OwnerAdapter owner,
        MovePayload payload
    ) throws PendingProcessException {
        if (payload == null || payload.kind == null || payload.kind.isEmpty()) {
            throw new PendingProcessException(
                FailureKind.INVALID_ARGUMENT,
                "move payload and kind are required"
            );
        }
        ensureOwnerKind(owner, payload.kind);
        if (payload.isSnapshot()) {
            if (!(payload.snapshot instanceof JSONObject)
                && !(payload.snapshot instanceof JSONArray)
                && !(payload.snapshot instanceof String)
                && !(payload.snapshot instanceof Number)
                && !(payload.snapshot instanceof Boolean)) {
                throw new PendingProcessException(
                    FailureKind.INVALID_ARGUMENT,
                    "snapshot payload is not a JSON value"
                );
            }
        } else if (payload.externalOwner == null
            || payload.externalOwner.trim().isEmpty()
            || payload.externalId == null
            || payload.externalId.trim().isEmpty()) {
            throw new PendingProcessException(
                FailureKind.INVALID_ARGUMENT,
                "external owner and stable id are required"
            );
        }
    }

    private static String defaultReason(MovePayload payload) {
        if (payload.reason != null) {
            return payload.reason;
        }
        if ("damaged_translation_job".equals(payload.kind)) {
            return "task_record_damaged";
        }
        return "user_requested";
    }

    /**
     * Re-runs journal recovery and index reconciliation explicitly.  The same
     * operation is automatically performed before every public read/mutation.
     */
    public void recover() throws IOException {
        synchronized (processLock) {
            prepare();
        }
    }

    private JSONObject createInternal(
        String pendingKey,
        String kind,
        String canonicalId,
        JSONObject payload,
        String reason,
        JSONObject restoreMetadata,
        JSONObject syncMetadata
    ) throws IOException {
        return createInternal(
            pendingKey,
            kind,
            canonicalId,
            payload,
            reason,
            restoreMetadata,
            syncMetadata,
            true
        );
    }

    private JSONObject createInternal(
        String pendingKey,
        String kind,
        String canonicalId,
        JSONObject payload,
        String reason,
        JSONObject restoreMetadata,
        JSONObject syncMetadata,
        boolean journal
    ) throws IOException {
        JSONObject index = prepare();
        if (journal && isOwnerTransactionPending()) {
            throw new PendingProcessException(
                FailureKind.CONFLICT,
                "another PendingProcess operation is in progress"
            );
        }
        if (findMetadata(index, pendingKey) != null) {
            throw new IOException("pending key already exists: " + pendingKey);
        }

        long entryNumber = parseNextEntryNumber(
            index.opt("next_entry_number"),
            "index next_entry_number"
        );
        if (entryNumber > MAX_ENTRY_NUMBER) {
            throw new IOException("pending entry number space is exhausted");
        }
        long nextNumber = entryNumber + 1L;
        if (nextNumber <= entryNumber || nextNumber > MAX_SAFE_INTEGER) {
            throw new IOException("pending entry number space is exhausted");
        }

        String filename = filenameFor(entryNumber);
        requireReason(reason);
        JSONObject restore = restoreMetadata == null
            ? defaultRestoreMetadata(kind, canonicalId, pendingKey, null)
            : copyObject(restoreMetadata);
        JSONObject sync = syncMetadata == null
            ? defaultSyncMetadata(kind)
            : copyObject(syncMetadata);
        validateRestoreMetadata(restore, kind, canonicalId, pendingKey);
        validateSyncMetadata(sync, kind);
        long createdAt = System.currentTimeMillis();
        JSONObject metadata = object();
        put(metadata, "pending_key", pendingKey);
        put(metadata, "entry_number", entryNumber);
        put(metadata, "filename", filename);
        put(metadata, "kind", kind);
        put(metadata, "canonical_id", canonicalId);
        put(metadata, "created_at", createdAt);
        put(metadata, "reason", reason);
        put(metadata, "restore_mode", restore.optString("mode", ""));
        put(
            metadata,
            "sync_on_permanent_delete",
            sync.optString("on_permanent_delete", "")
        );

        JSONObject entry = object();
        put(entry, "version", FORMAT_VERSION);
        put(entry, "entry_number", entryNumber);
        put(entry, "filename", filename);
        put(entry, "pending_key", pendingKey);
        put(entry, "kind", kind);
        put(entry, "canonical_id", canonicalId);
        put(entry, "created_at", createdAt);
        put(entry, "reason", reason);
        put(entry, "restore", restore);
        put(entry, "sync", sync);
        put(entry, "payload", payload);
        validateEntry(entry, "new pending entry");

        JSONObject nextIndex = copyObject(index);
        put(nextIndex, "next_entry_number", nextNumber);
        nextIndex.optJSONArray("entries").put(metadata);
        validateIndex(nextIndex, "new pending index");

        JSONObject transaction = transactionForCreate(index, nextIndex, entry);
        ensureSerializedSize(entry, MAX_ENTRY_BYTES, "pending entry");
        ensureSerializedSize(nextIndex, MAX_INDEX_BYTES, "pending index");
        ensureSerializedSize(
            transaction,
            MAX_TRANSACTION_BYTES,
            "pending transaction journal"
        );
        if (journal) {
            writeTransaction(transaction);
        }
        try {
            writeEntry(entry);
            writeIndex(nextIndex);
            if (journal) {
                clearTransaction();
            }
        } catch (IOException failure) {
            // Recovery will complete the operation from the full journal.
            throw failure;
        }
        return copyObject(entry);
    }

    /** Ensures layout, recovers a journal, validates the index and cleans orphans. */
    private JSONObject prepare() throws IOException {
        ensureLayout();
        if (io.exists(transactionFile)) {
            recoverTransaction();
        }
        return loadIndexAndReconcile();
    }

    private void ensureLayout() throws IOException {
        if (rootDirectory.exists() && !rootDirectory.isDirectory()) {
            throw new IOException("pending process root is not a directory");
        }
        IoUtils.ensureDirectory(rootDirectory);
        if (entriesDirectory.exists() && !entriesDirectory.isDirectory()) {
            throw new IOException("pending entries path is not a directory");
        }
        if (transactionDirectory.exists()
            && !transactionDirectory.isDirectory()) {
            throw new IOException("pending transaction path is not a directory");
        }
        IoUtils.ensureDirectory(entriesDirectory);
        IoUtils.ensureDirectory(transactionDirectory);
        if (indexFile.exists() && !indexFile.isFile()) {
            throw new IOException("pending index path is not a file");
        }
        if (transactionFile.exists() && !transactionFile.isFile()) {
            throw new IOException("pending transaction path is not a file");
        }
    }

    private JSONObject loadIndexAndReconcile() throws IOException {
        if (!io.exists(indexFile)) {
            if (hasAnyExactEntryFile()) {
                throw new IOException(
                    "pending index is missing while entry files exist"
                );
            }
            JSONObject empty = defaultIndex();
            writeIndex(empty);
            return empty;
        }

        JSONObject index = readObject(indexFile, MAX_INDEX_BYTES, "pending index");
        validateIndex(index, "pending index");
        JSONArray entries = index.optJSONArray("entries");
        Set<String> referenced = new HashSet<>();
        for (int indexPosition = 0; indexPosition < entries.length(); indexPosition++) {
            JSONObject metadata = entries.optJSONObject(indexPosition);
            if (metadata == null) {
                throw new IOException("pending index entry is not an object");
            }
            String filename = metadata.optString("filename", "");
            referenced.add(filename);
            File entryFile = fileForMetadata(metadata);
            if (!io.exists(entryFile)) {
                throw new IOException("pending entry file is missing: " + filename);
            }
        }
        cleanupOrphanEntries(referenced);
        return index;
    }

    private void cleanupOrphanEntries(Set<String> referenced)
        throws IOException {
        File[] files = entriesDirectory.listFiles();
        if (files == null) {
            throw new IOException("could not enumerate pending entries");
        }
        for (File file : files) {
            String name = file.getName();
            if (!isExactEntryFilename(name)) {
                continue;
            }
            if (!file.isFile()) {
                throw new IOException("pending entry path is not a file: " + name);
            }
            if (!referenced.contains(name)) {
                // Deliberately delete only an exact generated entry filename.
                io.delete(file);
            }
        }
    }

    private boolean hasAnyExactEntryFile() throws IOException {
        File[] files = entriesDirectory.listFiles();
        if (files == null) {
            throw new IOException("could not enumerate pending entries");
        }
        for (File file : files) {
            String name = file.getName();
            if (isExactEntryFilename(name)
                || (name.endsWith(".bak")
                    && isExactEntryFilename(
                        name.substring(0, name.length() - 4)
                    ))) {
                return true;
            }
        }
        return false;
    }

    private void recoverTransaction() throws IOException {
        JSONObject journal = readObject(
            transactionFile,
            MAX_TRANSACTION_BYTES,
            "pending transaction journal"
        );
        if (isOwnerOperation(journal)) {
            // Owner journals are replayed only when the matching adapter is
            // supplied. Reads may still observe the last published index.
            validateOwnerTransaction(journal);
            return;
        }
        validateJournalEnvelope(journal);
        String operation = journal.optString("operation", "");
        JSONObject previous = journal.optJSONObject("previous_index");
        JSONObject next = journal.optJSONObject("next_index");
        if (previous == null || next == null) {
            throw new IOException("pending transaction indexes are missing");
        }
        validateIndex(previous, "pending transaction previous index");
        validateIndex(next, "pending transaction next index");
        JSONObject current = null;
        if (io.exists(indexFile)) {
            current = readObject(
                indexFile,
                MAX_INDEX_BYTES,
                "pending index during recovery"
            );
            validateIndex(current, "pending index during recovery");
        }

        if (OP_CREATE.equals(operation)) {
            JSONObject entry = journal.optJSONObject("entry");
            if (entry == null) {
                throw new IOException("create journal entry is missing");
            }
            validateEntry(entry, "pending transaction entry");
            validateCreateTransition(previous, next, entry);
            if (current == null) {
                throw new IOException(
                    "pending create journal has no published previous index"
                );
            }
            if (!jsonEquals(current, previous) && !jsonEquals(current, next)) {
                throw new IOException(
                    "pending create journal index is neither previous nor next"
                );
            }
            // The journal owns this generated filename; rewrite it if a crash
            // left a partial file or if index publication happened first.
            writeEntry(entry);
            if (jsonEquals(current, previous)) {
                writeIndex(next);
            }
            clearTransaction();
            return;
        }

        if (OP_REMOVE.equals(operation)) {
            JSONObject target = journal.optJSONObject("target");
            if (target == null) {
                throw new IOException("remove journal target is missing");
            }
            validateMetadata(target, "pending transaction target");
            validateRemoveTransition(previous, next, target);
            if (current == null) {
                throw new IOException(
                    "pending remove journal has no published previous index"
                );
            }
            if (!jsonEquals(current, previous) && !jsonEquals(current, next)) {
                throw new IOException(
                    "pending remove journal index is neither previous nor next"
                );
            }
            if (jsonEquals(current, previous)) {
                writeIndex(next);
            }
            File entryFile = fileForMetadata(target);
            if (io.exists(entryFile)) {
                io.delete(entryFile);
            }
            clearTransaction();
            return;
        }
        throw new IOException("unknown pending transaction operation");
    }

    private JSONObject transactionForCreate(
        JSONObject previous,
        JSONObject next,
        JSONObject entry
    ) throws IOException {
        JSONObject journal = object();
        put(journal, "version", FORMAT_VERSION);
        put(journal, "operation", "create");
        put(journal, "previous_index", previous);
        put(journal, "next_index", next);
        put(journal, "entry", entry);
        return journal;
    }

    private JSONObject transactionForRemove(
        JSONObject previous,
        JSONObject next,
        JSONObject target
    ) throws IOException {
        JSONObject journal = object();
        put(journal, "version", FORMAT_VERSION);
        put(journal, "operation", "remove");
        put(journal, "previous_index", previous);
        put(journal, "next_index", next);
        put(journal, "target", target);
        return journal;
    }

    private void writeTransaction(JSONObject journal) throws IOException {
        writeJson(
            transactionFile,
            journal,
            MAX_TRANSACTION_BYTES,
            "pending transaction journal"
        );
    }

    private boolean hasTransaction() {
        return io.exists(transactionFile);
    }

    private boolean isOwnerTransactionPending() throws IOException {
        JSONObject transaction = readTransaction();
        return transaction != null && isOwnerOperation(transaction);
    }

    private JSONObject readTransaction() throws IOException {
        if (!io.exists(transactionFile)) {
            return null;
        }
        return readObject(
            transactionFile,
            MAX_TRANSACTION_BYTES,
            "pending transaction journal"
        );
    }

    private void clearTransaction() throws IOException {
        if (io.exists(transactionFile)) {
            io.delete(transactionFile);
        }
    }

    private void writeIndex(JSONObject index) throws IOException {
        validateIndex(index, "pending index to write");
        writeJson(indexFile, index, MAX_INDEX_BYTES, "pending index");
    }

    private void writeEntry(JSONObject entry) throws IOException {
        validateEntry(entry, "pending entry to write");
        File file = new File(
            entriesDirectory,
            entry.optString("filename", "")
        );
        writeJson(file, entry, MAX_ENTRY_BYTES, "pending entry");
    }

    private JSONObject readEntryForMetadata(JSONObject metadata)
        throws IOException {
        JSONObject entry = readObject(
            fileForMetadata(metadata),
            MAX_ENTRY_BYTES,
            "pending entry " + metadata.optString("filename", "")
        );
        validateEntry(entry, "pending entry");
        ensureMetadataMatchesEntry(metadata, entry);
        return entry;
    }

    private File fileForMetadata(JSONObject metadata) {
        return new File(
            entriesDirectory,
            metadata.optString("filename", "")
        );
    }

    private static JSONObject defaultIndex() throws IOException {
        JSONObject index = object();
        put(index, "version", FORMAT_VERSION);
        put(index, "next_entry_number", 1L);
        put(index, "entries", new JSONArray());
        return index;
    }

    private static JSONObject defaultRestoreMetadata(
        String kind,
        String canonicalId,
        String pendingKey,
        Object relations
    ) throws IOException {
        // Identity arguments are intentionally accepted here so a future
        // caller can add relation checks without changing the public shape.
        requireKind(kind);
        requireCanonicalId(canonicalId, "canonical_id");
        requirePendingKey(pendingKey);
        JSONObject restore = object();
        put(restore, "mode", RESTORE_MODE_SNAPSHOT);
        if (relations != null) {
            validateJsonValue(relations, "restore relations");
            put(restore, "relations", relations);
        }
        return restore;
    }

    private static JSONObject defaultDeleteOnlyRestoreMetadata(
        String kind,
        String canonicalId,
        String pendingKey
    ) throws IOException {
        requireKind(kind);
        requireCanonicalId(canonicalId, "canonical_id");
        requirePendingKey(pendingKey);
        JSONObject restore = object();
        put(restore, "mode", RESTORE_MODE_DELETE_ONLY);
        return restore;
    }

    private static JSONObject defaultSyncMetadata(String kind)
        throws IOException {
        requireKind(kind);
        JSONObject sync = object();
        put(
            sync,
            "on_permanent_delete",
            "scene".equals(kind)
                ? SYNC_DELETE_SCENE_ON_NEXT_SYNC
                : SYNC_NONE
        );
        return sync;
    }

    private static void validateRestoreMetadata(
        JSONObject restore,
        String kind,
        String canonicalId,
        String pendingKey
    ) throws IOException {
        ensureAllowedKeys(restore, "mode", "relations");
        String mode = restore.optString("mode", "");
        if (!(RESTORE_MODE_SNAPSHOT.equals(mode)
            || RESTORE_MODE_DELETE_ONLY.equals(mode))) {
            throw new IOException("restore mode is invalid");
        }
        if (RESTORE_MODE_DELETE_ONLY.equals(mode) && restore.has("relations")) {
            throw new IOException("delete_only restore cannot contain relations");
        }
        if (restore.has("relations")) {
            try {
                validateJsonValue(restore.get("relations"), "restore relations");
            } catch (JSONException e) {
                throw new IOException("restore relations are invalid", e);
            }
        }
        // Keep the relation's target identity tied to this pending record even
        // though the metadata object itself stores only mode/relations.
        requireKind(kind);
        requireCanonicalId(canonicalId, "canonical_id");
        requirePendingKey(pendingKey);
    }

    private static void validateSyncMetadata(JSONObject sync, String kind)
        throws IOException {
        ensureAllowedKeys(sync, "on_permanent_delete");
        String intent = sync.optString("on_permanent_delete", "");
        if (!(SYNC_NONE.equals(intent)
            || SYNC_DELETE_SCENE_ON_NEXT_SYNC.equals(intent))) {
            throw new IOException("sync on_permanent_delete is invalid");
        }
        String expected = "scene".equals(kind)
            ? SYNC_DELETE_SCENE_ON_NEXT_SYNC
            : SYNC_NONE;
        if (!expected.equals(intent)) {
            throw new IOException(
                "sync on_permanent_delete does not match pending kind"
            );
        }
        requireKind(kind);
    }

    private static void validateModePair(
        String kind,
        String restoreMode,
        String syncIntent,
        String label
    ) throws IOException {
        boolean externalKind = "damaged_translation_job".equals(kind)
            ;
        if (externalKind && !RESTORE_MODE_DELETE_ONLY.equals(restoreMode)) {
            throw new IOException(label + " external pending record must be delete_only");
        }
        if (!externalKind && !RESTORE_MODE_SNAPSHOT.equals(restoreMode)) {
            throw new IOException(label + " management snapshot must be restorable");
        }
        String expectedSync = "scene".equals(kind)
            ? SYNC_DELETE_SCENE_ON_NEXT_SYNC
            : SYNC_NONE;
        if (!expectedSync.equals(syncIntent)) {
            throw new IOException(
                label + " sync on_permanent_delete does not match pending kind"
            );
        }
    }

    private static void ensureAllowedKeys(JSONObject object, String... allowed)
        throws IOException {
        if (object == null) {
            throw new IOException("pending metadata object is missing");
        }
        Set<String> keys = new HashSet<>();
        Collections.addAll(keys, allowed);
        Iterator<String> iterator = object.keys();
        while (iterator.hasNext()) {
            String key = iterator.next();
            if (!keys.contains(key)) {
                throw new IOException("unexpected pending metadata field: " + key);
            }
        }
    }

    private static JSONObject metadataForEntry(JSONObject entry)
        throws IOException {
        JSONObject metadata = object();
        put(metadata, "pending_key", entry.optString("pending_key", ""));
        put(metadata, "entry_number", entry.opt("entry_number"));
        put(metadata, "filename", entry.optString("filename", ""));
        put(metadata, "kind", entry.optString("kind", ""));
        put(metadata, "canonical_id", entry.optString("canonical_id", ""));
        put(metadata, "created_at", entry.opt("created_at"));
        put(metadata, "reason", entry.optString("reason", ""));
        JSONObject restore = entry.optJSONObject("restore");
        JSONObject sync = entry.optJSONObject("sync");
        put(
            metadata,
            "restore_mode",
            restore == null ? "" : restore.optString("mode", "")
        );
        put(
            metadata,
            "sync_on_permanent_delete",
            sync == null ? "" : sync.optString("on_permanent_delete", "")
        );
        return metadata;
    }

    private static JSONObject findMetadata(JSONObject index, String pendingKey) {
        JSONArray entries = index.optJSONArray("entries");
        if (entries == null) {
            return null;
        }
        for (int indexPosition = 0; indexPosition < entries.length(); indexPosition++) {
            JSONObject metadata = entries.optJSONObject(indexPosition);
            if (metadata != null
                && pendingKey.equals(metadata.optString("pending_key", ""))) {
                return metadata;
            }
        }
        return null;
    }

    private void validateIndex(JSONObject index, String label)
        throws IOException {
        try {
            indexSchemaValidator.validate(index);
        } catch (JsonSchemaValidator.ValidationException e) {
            throw new IOException(label + " failed schema validation", e);
        }
        if (index.optInt("version", -1) != FORMAT_VERSION) {
            throw new IOException(label + " has an unsupported version");
        }
        parseNextEntryNumber(
            index.opt("next_entry_number"),
            label + " next_entry_number"
        );
        JSONArray entries = index.optJSONArray("entries");
        if (entries == null || entries.length() > MAX_INDEX_ENTRIES) {
            throw new IOException(label + " has too many entries");
        }
        Set<String> keys = new HashSet<>();
        Set<String> filenames = new HashSet<>();
        Set<Long> numbers = new HashSet<>();
        long nextNumber = parseNextEntryNumber(
            index.opt("next_entry_number"),
            label + " next_entry_number"
        );
        for (int indexPosition = 0; indexPosition < entries.length(); indexPosition++) {
            JSONObject metadata = entries.optJSONObject(indexPosition);
            if (metadata == null) {
                throw new IOException(label + " entry is not an object");
            }
            validateMetadata(metadata, label + " entry");
            String key = metadata.optString("pending_key", "");
            String filename = metadata.optString("filename", "");
            long number = parseEntryNumber(
                metadata.opt("entry_number"),
                label + " entry_number"
            );
            if (!keys.add(key) || !filenames.add(filename) || !numbers.add(number)) {
                throw new IOException(label + " contains duplicate entry identity");
            }
            if (number >= nextNumber) {
                throw new IOException(
                    label + " next_entry_number does not follow entry numbers"
                );
            }
        }
    }

    private void validateEntry(JSONObject entry, String label)
        throws IOException {
        try {
            entrySchemaValidator.validate(entry);
        } catch (JsonSchemaValidator.ValidationException e) {
            throw new IOException(label + " failed schema validation", e);
        }
        if (entry.optInt("version", -1) != FORMAT_VERSION) {
            throw new IOException(label + " has an unsupported version");
        }
        String kind = entry.optString("kind", "");
        String canonicalId = entry.optString("canonical_id", "");
        String pendingKey = entry.optString("pending_key", "");
        try {
            requireKind(kind);
            requireCanonicalId(canonicalId, "canonical_id");
            requirePendingKey(pendingKey);
        } catch (IllegalArgumentException e) {
            throw new IOException(label + " identity is invalid", e);
        }
        if (!canonicalPendingKey(kind, canonicalId).equals(pendingKey)) {
            throw new IOException(label + " pending_key does not match identity");
        }
        long number = parseEntryNumber(entry.opt("entry_number"), label + " entry_number");
        String filename = entry.optString("filename", "");
        if (!filenameFor(number).equals(filename)) {
            throw new IOException(label + " filename does not match entry_number");
        }
        Object createdAt = entry.opt("created_at");
        long created = parseLong(createdAt, label + " created_at");
        if (created < 0L || created > MAX_SAFE_INTEGER) {
            throw new IOException(label + " created_at is out of range");
        }
        try {
            requireReason(entry.optString("reason", ""));
        } catch (IllegalArgumentException e) {
            throw new IOException(label + " reason is invalid", e);
        }
        JSONObject restore = entry.optJSONObject("restore");
        JSONObject sync = entry.optJSONObject("sync");
        validateRestoreMetadata(restore, kind, canonicalId, pendingKey);
        validateSyncMetadata(sync, kind);
        String restoreMode = restore.optString("mode", "");
        if ("scene".equals(kind) || "language".equals(kind)
            || "context".equals(kind) || "group".equals(kind)
            || "character".equals(kind) || "term".equals(kind)) {
            if (!RESTORE_MODE_SNAPSHOT.equals(restoreMode)) {
                throw new IOException(label + " management snapshot must be restorable");
            }
        } else if (!RESTORE_MODE_DELETE_ONLY.equals(restoreMode)) {
            throw new IOException(label + " external pending record must be delete_only");
        }
        JSONObject payload = entry.optJSONObject("payload");
        if (payload == null) {
            throw new IOException(label + " payload is missing");
        }
        String payloadType = payload.optString("type", "");
        if ("snapshot".equals(payloadType)) {
            if (!payload.has("snapshot")) {
                throw new IOException(label + " snapshot payload is missing");
            }
            if (!RESTORE_MODE_SNAPSHOT.equals(restoreMode)) {
                throw new IOException(label + " snapshot payload requires snapshot restore mode");
            }
            try {
                validateJsonValue(payload.get("snapshot"), label + " snapshot");
            } catch (JSONException e) {
                throw new IOException(label + " snapshot payload is invalid", e);
            }
            if ("damaged_translation_job".equals(kind)) {
                throw new IOException(label + " external pending kind requires external_reference payload");
            }
        } else if ("external_reference".equals(payloadType)) {
            try {
                requireExternalOwner(payload.optString("owner", ""));
                requireExternalStableId(
                    payload.optString("id", ""),
                    "external reference id"
                );
            } catch (IllegalArgumentException e) {
                throw new IOException(label + " external reference is invalid", e);
            }
            if (!RESTORE_MODE_DELETE_ONLY.equals(restoreMode)) {
                throw new IOException(label + " external_reference requires delete_only restore mode");
            }
            String owner = payload.optString("owner", "");
            if ("damaged_translation_job".equals(kind)
                && !"translation_job".equals(owner)) {
                throw new IOException(
                    label + " damaged_translation_job must reference translation_job"
                );
            }
            if (!"damaged_translation_job".equals(kind)) {
                throw new IOException(label + " management kind cannot use external_reference payload");
            }
        } else {
            throw new IOException(label + " payload type is invalid");
        }
    }

    private static void validateMetadata(JSONObject metadata, String label)
        throws IOException {
        String kind = metadata.optString("kind", "");
        String canonicalId = metadata.optString("canonical_id", "");
        String pendingKey = metadata.optString("pending_key", "");
        try {
            requireKind(kind);
            requireCanonicalId(canonicalId, label + " canonical_id");
            requirePendingKey(pendingKey);
        } catch (IllegalArgumentException e) {
            throw new IOException(label + " identity is invalid", e);
        }
        if (!canonicalPendingKey(kind, canonicalId).equals(pendingKey)) {
            throw new IOException(label + " pending_key does not match identity");
        }
        long number = parseEntryNumber(metadata.opt("entry_number"), label + " entry_number");
        if (!filenameFor(number).equals(metadata.optString("filename", ""))) {
            throw new IOException(label + " filename does not match entry_number");
        }
        long createdAt = parseLong(metadata.opt("created_at"), label + " created_at");
        if (createdAt < 0L || createdAt > MAX_SAFE_INTEGER) {
            throw new IOException(label + " created_at is out of range");
        }
        try {
            requireReason(metadata.optString("reason", ""));
        } catch (IllegalArgumentException e) {
            throw new IOException(label + " reason is invalid", e);
        }
        String restoreMode = metadata.optString("restore_mode", "");
        String syncIntent = metadata.optString("sync_on_permanent_delete", "");
        validateModePair(kind, restoreMode, syncIntent, label);
    }

    private static void ensureMetadataMatchesEntry(
        JSONObject metadata,
        JSONObject entry
    ) throws IOException {
        String[] fields = {
            "pending_key",
            "entry_number",
            "filename",
            "kind",
            "canonical_id",
            "created_at",
            "reason"
        };
        for (String field : fields) {
            if (!jsonEquals(metadata.opt(field), entry.opt(field))) {
                throw new IOException(
                    "pending index metadata does not match entry " + field
                );
            }
        }
        JSONObject restore = entry.optJSONObject("restore");
        JSONObject sync = entry.optJSONObject("sync");
        if (restore == null
            || sync == null
            || !jsonEquals(
                metadata.opt("restore_mode"),
                restore.opt("mode")
            )
            || !jsonEquals(
                metadata.opt("sync_on_permanent_delete"),
                sync.opt("on_permanent_delete")
            )) {
            throw new IOException(
                "pending index metadata does not match entry restore/sync metadata"
            );
        }
    }

    private static void validateCreateTransition(
        JSONObject previous,
        JSONObject next,
        JSONObject entry
    ) throws IOException {
        JSONObject metadata = metadataForEntry(entry);
        if (findMetadata(previous, metadata.optString("pending_key", "")) != null) {
            throw new IOException("create journal previous index already contains key");
        }
        if (findMetadata(next, metadata.optString("pending_key", "")) == null) {
            throw new IOException("create journal next index omits entry");
        }
        JSONObject nextMetadata = findMetadata(
            next,
            metadata.optString("pending_key", "")
        );
        ensureMetadataMatchesEntry(nextMetadata, entry);
        long previousNext = parseNextEntryNumber(
            previous.opt("next_entry_number"),
            "create journal previous next_entry_number"
        );
        long number = parseEntryNumber(
            entry.opt("entry_number"),
            "create journal entry_number"
        );
        if (number != previousNext
            || parseNextEntryNumber(
                next.opt("next_entry_number"),
                "create journal next next_entry_number"
            )
                != number + 1L) {
            throw new IOException("create journal entry number transition is invalid");
        }
        JSONObject expectedNext = copyObject(previous);
        put(expectedNext, "next_entry_number", number + 1L);
        expectedNext.optJSONArray("entries").put(metadata);
        if (!jsonEquals(expectedNext, next)) {
            throw new IOException("create journal next index changed unexpectedly");
        }
    }

    private static void validateRemoveTransition(
        JSONObject previous,
        JSONObject next,
        JSONObject target
    ) throws IOException {
        String key = target.optString("pending_key", "");
        JSONObject previousMetadata = findMetadata(previous, key);
        if (previousMetadata == null
            || !jsonEquals(previousMetadata, target)
            || findMetadata(next, key) != null
            || parseNextEntryNumber(
                previous.opt("next_entry_number"),
                "remove journal previous next_entry_number"
            ) != parseNextEntryNumber(
                next.opt("next_entry_number"),
                "remove journal next next_entry_number"
            )) {
            throw new IOException("remove journal target transition is invalid");
        }
        JSONArray previousEntries = previous.optJSONArray("entries");
        int targetPosition = -1;
        for (int index = 0; index < previousEntries.length(); index++) {
            if (jsonEquals(previousEntries.opt(index), target)) {
                targetPosition = index;
                break;
            }
        }
        if (targetPosition < 0) {
            throw new IOException("remove journal target is not in previous index");
        }
        JSONObject expectedNext = copyObject(previous);
        expectedNext.optJSONArray("entries").remove(targetPosition);
        if (!jsonEquals(expectedNext, next)) {
            throw new IOException("remove journal next index changed unexpectedly");
        }
    }

    private static void validateJournalEnvelope(JSONObject journal)
        throws IOException {
        ensureExactKeys(
            journal,
            "version",
            "operation",
            "previous_index",
            "next_index",
            journal.has("entry") ? "entry" : "target"
        );
        if (journal.optInt("version", -1) != FORMAT_VERSION) {
            throw new IOException("pending transaction has an unsupported version");
        }
        String operation = journal.optString("operation", "");
        if (!(OP_CREATE.equals(operation) || OP_REMOVE.equals(operation))) {
            throw new IOException("pending transaction operation is invalid");
        }
        if ((OP_CREATE.equals(operation) && !journal.has("entry"))
            || (OP_REMOVE.equals(operation) && !journal.has("target"))) {
            throw new IOException("pending transaction payload does not match operation");
        }
    }

    private static boolean isOwnerOperation(JSONObject transaction) {
        String operation = transaction == null
            ? ""
            : transaction.optString("operation", "");
        return OP_MOVE.equals(operation)
            || OP_RESTORE.equals(operation)
            || OP_PERMANENT_DELETE.equals(operation);
    }

    private static void validateOwnerTransaction(JSONObject transaction)
        throws PendingProcessException {
        Set<String> required = new HashSet<>();
        Collections.addAll(
            required,
            "version",
            "operation",
            "phase",
            "pending_key",
            "kind",
            "canonical_id",
            "request",
            "entry"
        );
        if (!hasExactlyKeys(transaction, required)) {
            throw new PendingProcessException(
                FailureKind.INVALID_STATE,
                "PendingProcess transaction has unexpected fields"
            );
        }
        if (transaction.optInt("version", -1) != FORMAT_VERSION) {
            throw new PendingProcessException(
                FailureKind.INVALID_STATE,
                "unsupported PendingProcess transaction format"
            );
        }
        String operation = transaction.optString("operation", "");
        if (!isOwnerOperation(transaction)) {
            throw new PendingProcessException(
                FailureKind.INVALID_STATE,
                "PendingProcess transaction operation is invalid"
            );
        }
        String phase = transaction.optString("phase", "");
        if (!PHASE_PREPARED.equals(phase)
            && !PHASE_PENDING_PUBLISHED.equals(phase)
            && !PHASE_OWNER_APPLIED.equals(phase)
            && !PHASE_ROLLBACK_PENDING.equals(phase)) {
            throw new PendingProcessException(
                FailureKind.INVALID_STATE,
                "PendingProcess transaction phase is invalid"
            );
        }
        if (PHASE_ROLLBACK_PENDING.equals(phase) && !OP_MOVE.equals(operation)) {
            throw new PendingProcessException(
                FailureKind.INVALID_STATE,
                "rollback_pending is only valid for move transactions"
            );
        }
        requireTransactionText(transaction, "pending_key");
        requireTransactionText(transaction, "kind");
        requireTransactionText(transaction, "canonical_id");
        String pendingKey = transaction.optString("pending_key", "");
        String kind = transaction.optString("kind", "");
        String canonicalId = transaction.optString("canonical_id", "");
        final String expectedPendingKey;
        try {
            expectedPendingKey = canonicalPendingKey(kind, canonicalId);
        } catch (IllegalArgumentException e) {
            throw new PendingProcessException(
                FailureKind.INVALID_STATE,
                "PendingProcess transaction identity is invalid",
                e
            );
        }
        if (!expectedPendingKey.equals(pendingKey)) {
            throw new PendingProcessException(
                FailureKind.INVALID_STATE,
                "PendingProcess transaction key does not match identity"
            );
        }
        Object requestValue = transaction.opt("request");
        Object entryValue = transaction.opt("entry");
        if (OP_MOVE.equals(operation)) {
            if (PHASE_PREPARED.equals(phase)) {
                if (!(requestValue instanceof JSONObject)
                    || (entryValue != null && entryValue != JSONObject.NULL)) {
                    throw new PendingProcessException(
                        FailureKind.INVALID_STATE,
                        "prepared move transaction has invalid payload state"
                    );
                }
                validateMoveRequest((JSONObject) requestValue);
            } else if (!(entryValue instanceof JSONObject)
                || (requestValue != null && requestValue != JSONObject.NULL)) {
                throw new PendingProcessException(
                    FailureKind.INVALID_STATE,
                    "published move transaction has invalid payload state"
                );
            }
        } else if (PHASE_PENDING_PUBLISHED.equals(phase)
            || !(entryValue instanceof JSONObject)
            || (requestValue != null && requestValue != JSONObject.NULL)) {
            throw new PendingProcessException(
                FailureKind.INVALID_STATE,
                "owner transaction has invalid payload state"
            );
        }
        if (entryValue instanceof JSONObject) {
            ensureEntryIdentity(
                (JSONObject) entryValue,
                pendingKey,
                kind,
                canonicalId
            );
        }
    }

    private static void validateMoveRequest(JSONObject request)
        throws PendingProcessException {
        String mode = request.optString("mode", "");
        Set<String> expected = new HashSet<>();
        Collections.addAll(
            expected,
            "mode",
            "reason",
            "restore_metadata",
            "sync_metadata"
        );
        if ("snapshot".equals(mode)) {
            expected.add("snapshot");
            Object snapshot = request.opt("snapshot");
            if (snapshot == null || snapshot == JSONObject.NULL) {
                throw new PendingProcessException(
                    FailureKind.INVALID_STATE,
                    "move snapshot request is missing its snapshot"
                );
            }
        } else if ("external_reference".equals(mode)) {
            expected.add("owner");
            expected.add("id");
            requireTransactionText(request, "owner");
            requireTransactionText(request, "id");
        } else {
            throw new PendingProcessException(
                FailureKind.INVALID_STATE,
                "move transaction payload mode is invalid"
            );
        }
        if (!hasExactlyKeys(request, expected)) {
            throw new PendingProcessException(
                FailureKind.INVALID_STATE,
                "move transaction request has unexpected fields"
            );
        }
        requireTransactionText(request, "reason");
        Object restore = request.opt("restore_metadata");
        Object sync = request.opt("sync_metadata");
        if ((restore != null && restore != JSONObject.NULL
                && !(restore instanceof JSONObject))
            || (sync != null && sync != JSONObject.NULL
                && !(sync instanceof JSONObject))) {
            throw new PendingProcessException(
                FailureKind.INVALID_STATE,
                "move transaction metadata is invalid"
            );
        }
    }

    private static void ensureEntryIdentity(
        JSONObject entry,
        String pendingKey,
        String kind,
        String canonicalId
    ) throws PendingProcessException {
        if (entry == null
            || !pendingKey.equals(entry.optString("pending_key", ""))
            || !kind.equals(entry.optString("kind", ""))
            || !canonicalId.equals(entry.optString("canonical_id", ""))) {
            throw new PendingProcessException(
                FailureKind.INVALID_STATE,
                "PendingProcess journal entry identity does not match"
            );
        }
    }

    private static void ensureMoveEntryMatchesRequest(
        JSONObject entry,
        JSONObject request
    ) throws PendingProcessException {
        if (!request.optString("reason", "").equals(
                entry.optString("reason", ""))) {
            throw new PendingProcessException(
                FailureKind.CONFLICT,
                "existing PendingProcess entry has a different reason"
            );
        }
        JSONObject payload = entry.optJSONObject("payload");
        if (payload == null) {
            throw new PendingProcessException(
                FailureKind.INVALID_STATE,
                "existing PendingProcess entry has no payload"
            );
        }
        String mode = request.optString("mode", "");
        if ("snapshot".equals(mode)) {
            if (!"snapshot".equals(payload.optString("type", ""))
                || !jsonEquals(
                    request.opt("snapshot"),
                    payload.opt("snapshot")
                )) {
                throw new PendingProcessException(
                    FailureKind.CONFLICT,
                    "existing PendingProcess snapshot differs from move request"
                );
            }
        } else if ("external_reference".equals(mode)) {
            if (!"external_reference".equals(payload.optString("type", ""))
                || !request.optString("owner", "").equals(
                    payload.optString("owner", ""))
                || !request.optString("id", "").equals(
                    payload.optString("id", ""))) {
                throw new PendingProcessException(
                    FailureKind.CONFLICT,
                    "existing PendingProcess reference differs from move request"
                );
            }
        } else {
            throw new PendingProcessException(
                FailureKind.INVALID_STATE,
                "move transaction payload mode is invalid"
            );
        }
        JSONObject requestedRestore = request.optJSONObject("restore_metadata");
        JSONObject requestedSync = request.optJSONObject("sync_metadata");
        if ((requestedRestore != null
                && !jsonEquals(requestedRestore, entry.opt("restore")))
            || (requestedSync != null
                && !jsonEquals(requestedSync, entry.opt("sync")))) {
            throw new PendingProcessException(
                FailureKind.CONFLICT,
                "existing PendingProcess metadata differs from move request"
            );
        }
    }

    private static void requireTransactionText(JSONObject object, String key)
        throws PendingProcessException {
        if (!(object.opt(key) instanceof String)
            || object.optString(key, "").isEmpty()) {
            throw new PendingProcessException(
                FailureKind.INVALID_STATE,
                "PendingProcess transaction field is missing: " + key
            );
        }
    }

    static boolean hasExactlyKeys(
        JSONObject object,
        Set<String> expected
    ) {
        if (object.length() != expected.size()) {
            return false;
        }
        Iterator<String> keys = object.keys();
        while (keys.hasNext()) {
            if (!expected.contains(keys.next())) {
                return false;
            }
        }
        return true;
    }

    static boolean hasExactlyKeys(JSONObject object, String... keys) {
        if (object.length() != keys.length) {
            return false;
        }
        Set<String> expected = new HashSet<>();
        Collections.addAll(expected, keys);
        Iterator<String> iterator = object.keys();
        while (iterator.hasNext()) {
            if (!expected.contains(iterator.next())) {
                return false;
            }
        }
        return true;
    }

    private static void ensureExactKeys(JSONObject object, String... allowed)
        throws IOException {
        Set<String> expected = new HashSet<>();
        Collections.addAll(expected, allowed);
        Iterator<String> keys = object.keys();
        while (keys.hasNext()) {
            String key = keys.next();
            if (!expected.contains(key)) {
                throw new IOException("unexpected pending JSON field: " + key);
            }
        }
        for (String key : allowed) {
            if (!object.has(key)) {
                throw new IOException("missing pending JSON field: " + key);
            }
        }
    }

    private static void validateJsonValue(Object value, String label)
        throws IOException {
        validateJsonValue(value, label, 0);
    }

    private static void validateJsonValue(
        Object value,
        String label,
        int depth
    ) throws IOException {
        if (depth > MAX_JSON_DEPTH) {
            throw new IOException(label + " exceeds JSON nesting depth");
        }
        if (value == null || value == JSONObject.NULL || value instanceof String
            || value instanceof Boolean) {
            return;
        }
        if (value instanceof Number) {
            if (!Double.isFinite(((Number) value).doubleValue())) {
                throw new IOException(label + " contains a non-finite number");
            }
            return;
        }
        if (value instanceof JSONObject) {
            JSONObject object = (JSONObject) value;
            Iterator<String> keys = object.keys();
            while (keys.hasNext()) {
                String key = keys.next();
                if (key == null || key.indexOf('\u0000') >= 0) {
                    throw new IOException(label + " contains an invalid object key");
                }
                try {
                    validateJsonValue(object.get(key), label + "." + key, depth + 1);
                } catch (JSONException e) {
                    throw new IOException(label + " contains an invalid object value", e);
                }
            }
            return;
        }
        if (value instanceof JSONArray) {
            JSONArray array = (JSONArray) value;
            for (int index = 0; index < array.length(); index++) {
                try {
                    validateJsonValue(
                        array.get(index),
                        label + "[" + index + "]",
                        depth + 1
                    );
                } catch (JSONException e) {
                    throw new IOException(label + " contains an invalid array value", e);
                }
            }
            return;
        }
        throw new IOException(label + " contains a non-JSON value");
    }

    private static JSONObject object() throws IOException {
        return new JSONObject();
    }

    private static void put(JSONObject object, String key, Object value)
        throws IOException {
        try {
            object.put(key, value);
        } catch (JSONException e) {
            throw new IOException("could not construct pending JSON", e);
        }
    }

    private static JSONObject copyObject(JSONObject object) throws IOException {
        if (object == null) {
            throw new IOException("pending object is null");
        }
        try {
            return new JSONObject(object.toString());
        } catch (JSONException e) {
            throw new IOException("could not copy pending object", e);
        }
    }

    private static JSONArray copyArray(JSONArray array) throws IOException {
        if (array == null) {
            throw new IOException("pending array is missing");
        }
        try {
            return new JSONArray(array.toString());
        } catch (JSONException e) {
            throw new IOException("could not copy pending array", e);
        }
    }

    static JSONObject copyJsonOrNull(JSONObject value) {
        return value == null ? null : copyJsonValueObject(value);
    }

    static JSONObject copyJsonValueObject(JSONObject value) {
        if (value == null) {
            throw new IllegalArgumentException("JSON object is required");
        }
        try {
            return new JSONObject(value.toString());
        } catch (JSONException e) {
            throw new IllegalArgumentException("JSON value cannot be copied", e);
        }
    }

    private static Object copyJsonValue(Object value) {
        if (value instanceof JSONObject) {
            return copyJsonValueObject((JSONObject) value);
        }
        if (value instanceof JSONArray) {
            try {
                return new JSONArray(value.toString());
            } catch (JSONException e) {
                throw new IllegalArgumentException("JSON array cannot be copied", e);
            }
        }
        return value;
    }

    private static void ensureSerializedSize(
        Object value,
        int maxBytes,
        String label
    ) throws IOException {
        if (value == null) {
            throw new IOException(label + " is missing");
        }
        byte[] bytes = encodeStrictUtf8(value.toString() + "\n", label);
        if (bytes.length > maxBytes) {
            throw new IOException(label + " exceeds byte limit");
        }
    }

    private void writeJson(
        File file,
        Object value,
        int maxBytes,
        String label
    ) throws IOException {
        byte[] bytes = encodeStrictUtf8(value.toString() + "\n", label);
        if (bytes.length > maxBytes) {
            throw new IOException(label + " exceeds byte limit");
        }
        IoUtils.ensureDirectory(file.getParentFile());
        io.write(file, bytes);
    }

    private JSONObject readObject(File file, int maxBytes, String label)
        throws IOException {
        if (!io.exists(file)) {
            throw new IOException(label + " does not exist");
        }
        byte[] bytes = io.read(file);
        if (bytes == null || bytes.length == 0) {
            throw new IOException(label + " is empty");
        }
        if (bytes.length > maxBytes) {
            throw new IOException(label + " exceeds byte limit");
        }
        try {
            return new JSONObject(decodeStrictUtf8(bytes));
        } catch (JSONException | CharacterCodingException e) {
            throw new IOException(label + " is not valid UTF-8 JSON", e);
        }
    }

    private static String decodeStrictUtf8(byte[] bytes)
        throws CharacterCodingException {
        return StandardCharsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(bytes))
            .toString();
    }

    private static byte[] encodeStrictUtf8(String value, String label)
        throws IOException {
        try {
            ByteBuffer encoded = StandardCharsets.UTF_8.newEncoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .encode(java.nio.CharBuffer.wrap(value));
            byte[] bytes = new byte[encoded.remaining()];
            encoded.get(bytes);
            return bytes;
        } catch (CharacterCodingException e) {
            throw new IOException(label + " contains invalid Unicode", e);
        }
    }

    private static long parseEntryNumber(Object value, String label)
        throws IOException {
        long number = parseLong(value, label);
        requireEntryNumber(number);
        return number;
    }

    private static long parseNextEntryNumber(Object value, String label)
        throws IOException {
        long number = parseLong(value, label);
        if (number < 1L || number > MAX_SAFE_INTEGER) {
            throw new IOException(label + " is out of range");
        }
        return number;
    }

    private static long parseLong(Object value, String label) throws IOException {
        if (!(value instanceof Byte)
            && !(value instanceof Short)
            && !(value instanceof Integer)
            && !(value instanceof Long)) {
            throw new IOException(label + " must be an integer");
        }
        return ((Number) value).longValue();
    }

    private static String filenameFor(long entryNumber) throws IOException {
        requireEntryNumber(entryNumber);
        return "pending_process_" + Long.toString(entryNumber) + ".json";
    }

    private static boolean isExactEntryFilename(String filename) {
        // The decimal shape is the cleanup boundary.  Numeric range is
        // checked when an index/entry references the name, but an orphan with
        // an oversized decimal suffix is still an exact generated filename.
        return filename != null
            && ENTRY_FILE_PATTERN.matcher(filename).matches();
    }

    private static String canonicalPendingKey(String kind, String canonicalId) {
        requireKind(kind);
        requireCanonicalId(canonicalId, "canonical_id");
        String key = kind + ":" + canonicalId;
        requirePendingKey(key);
        return key;
    }

    private static void requireKind(String kind) {
        if (kind == null || !PENDING_KINDS.contains(kind)) {
            throw new IllegalArgumentException("unsupported pending kind: " + kind);
        }
    }

    private static void requireExternalOwner(String owner) {
        if (owner == null || !EXTERNAL_OWNERS.contains(owner)) {
            throw new IllegalArgumentException("unsupported external reference owner: " + owner);
        }
    }

    private static void requireReason(String reason) {
        if (reason == null
            || reason.length() > MAX_REASON_LENGTH
            || !REASON_PATTERN.matcher(reason).matches()) {
            throw new IllegalArgumentException("reason must be lower_snake_case");
        }
    }

    private static void requirePendingKey(String key) {
        requirePrintableIdentifier(key, MAX_PENDING_KEY_LENGTH, "pending key", 3);
    }

    private static void requireCanonicalId(String id, String label) {
        requirePrintableIdentifier(id, MAX_CANONICAL_ID_LENGTH, label, 1);
    }

    private static void requireExternalStableId(String id, String label) {
        if (id == null
            || !SAFE_EXTERNAL_ID_PATTERN.matcher(id).matches()
            || id.indexOf("..") >= 0) {
            throw new IllegalArgumentException("invalid " + label);
        }
    }

    private static void requirePrintableIdentifier(
        String value,
        int maxCodePoints,
        String label,
        int minCodePoints
    ) {
        if (value == null) {
            throw new IllegalArgumentException("invalid " + label);
        }
        int codePoints = value.codePointCount(0, value.length());
        if (codePoints < minCodePoints || codePoints > maxCodePoints) {
            throw new IllegalArgumentException("invalid " + label);
        }
        int first = value.codePointAt(0);
        int last = value.codePointBefore(value.length());
        if (isBoundaryWhitespace(first) || isBoundaryWhitespace(last)) {
            throw new IllegalArgumentException("invalid " + label);
        }
        for (int offset = 0; offset < value.length();) {
            int codePoint = value.codePointAt(offset);
            if ((codePoint >= Character.MIN_SURROGATE
                && codePoint <= Character.MAX_SURROGATE)
                || Character.isISOControl(codePoint)
                || codePoint == '/'
                || codePoint == '\\') {
                throw new IllegalArgumentException("invalid " + label);
            }
            offset += Character.charCount(codePoint);
        }
        if (value.indexOf("..") >= 0) {
            throw new IllegalArgumentException("invalid " + label);
        }
    }

    private static boolean isBoundaryWhitespace(int codePoint) {
        return Character.isWhitespace(codePoint)
            || Character.isSpaceChar(codePoint);
    }

    private static void requireEntryNumber(long number) throws IOException {
        if (number < 1L || number > MAX_ENTRY_NUMBER) {
            throw new IOException("pending entry number is out of range");
        }
    }

    static boolean jsonEquals(Object left, Object right) {
        if (left == right) {
            return true;
        }
        if (left == null || right == null) {
            return false;
        }
        if (left instanceof Number && right instanceof Number) {
            try {
                return new BigDecimal(left.toString()).compareTo(
                    new BigDecimal(right.toString())
                ) == 0;
            } catch (NumberFormatException ignored) {
                return left.toString().equals(right.toString());
            }
        }
        if (left instanceof JSONObject && right instanceof JSONObject) {
            JSONObject a = (JSONObject) left;
            JSONObject b = (JSONObject) right;
            if (a.length() != b.length()) {
                return false;
            }
            Iterator<String> keys = a.keys();
            while (keys.hasNext()) {
                String key = keys.next();
                if (!b.has(key) || !jsonEquals(a.opt(key), b.opt(key))) {
                    return false;
                }
            }
            return true;
        }
        if (left instanceof JSONArray && right instanceof JSONArray) {
            JSONArray a = (JSONArray) left;
            JSONArray b = (JSONArray) right;
            if (a.length() != b.length()) {
                return false;
            }
            for (int index = 0; index < a.length(); index++) {
                if (!jsonEquals(a.opt(index), b.opt(index))) {
                    return false;
                }
            }
            return true;
        }
        return left.equals(right);
    }

    private static JSONObject requireSchema(JSONObject schema, String label) {
        if (schema == null) {
            throw new IllegalArgumentException(label + " schema is required");
        }
        return schema;
    }

    private static JSONObject loadSchema(Context context, String path) {
        Context safeContext = requireContext(context);
        Context appContext = safeContext.getApplicationContext();
        if (appContext != null) {
            safeContext = appContext;
        }
        try (java.io.InputStream input = safeContext.getAssets().open(path)) {
            byte[] bytes = IoUtils.readAllBytesLimited(input, MAX_SCHEMA_BYTES);
            return new JSONObject(decodeStrictUtf8(bytes));
        } catch (Exception e) {
            throw new IllegalStateException("could not load pending schema: " + path, e);
        }
    }

    private static Context requireContext(Context context) {
        if (context == null) {
            throw new IllegalArgumentException("context cannot be null");
        }
        return context;
    }

    private void beginOwnerOperation() throws PendingProcessException {
        if (!ownerOperationActive.compareAndSet(false, true)) {
            throw new PendingProcessException(
                FailureKind.CONFLICT,
                "another PendingProcess operation is in progress"
            );
        }
    }

    private void endOwnerOperation() {
        ownerOperationActive.set(false);
    }

    private static Object lockForRoot(File rootDirectory) {
        String key;
        try {
            key = rootDirectory.getCanonicalPath();
        } catch (IOException | SecurityException ignored) {
            key = rootDirectory.getAbsoluteFile().toString();
        }
        Object fresh = new Object();
        Object existing = ROOT_LOCKS.putIfAbsent(key, fresh);
        return existing == null ? fresh : existing;
    }

    private static AtomicBoolean operationGuardForRoot(File rootDirectory) {
        String key;
        try {
            key = rootDirectory.getCanonicalPath();
        } catch (IOException | SecurityException ignored) {
            key = rootDirectory.getAbsoluteFile().toString();
        }
        AtomicBoolean fresh = new AtomicBoolean(false);
        AtomicBoolean existing = ROOT_OPERATION_GUARDS.putIfAbsent(key, fresh);
        return existing == null ? fresh : existing;
    }
}
