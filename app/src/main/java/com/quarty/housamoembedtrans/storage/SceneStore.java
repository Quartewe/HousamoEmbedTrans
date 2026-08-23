package com.quarty.housamoembedtrans.storage;

import com.quarty.housamoembedtrans.util.IoUtils;

import android.content.Context;
import android.util.AtomicFile;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.ByteArrayOutputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** Stores schema-valid scene JSON files under the module app's files/scenes directory. */
public final class SceneStore {

    private static final String TAG = "HET.SceneStore";

    public static final String DIRECTORY_NAME = "scenes";
    public static final String SCHEMA_ASSET_PATH = "schema/scene_schema.json";
    public static final int MAX_SCENE_BYTES = 32 * 1024 * 1024;
    public static final int MAX_SCENE_NAME_BYTES = 235;
    public static final String MUTATION_POOL_DIRECTORY_NAME =
        "scene_mutation_pool";
    private static final String MUTATION_POOL_META_NAME = "meta.json";
    private static final String MUTATION_POOL_DIAGNOSTIC_NAME =
        "diagnostic.json";
    private static final String MUTATION_POOL_ENTRIES_NAME = "entries";
    private static final String MUTATION_POOL_TEMP_PREFIX = ".incoming-";
    private static final int MAX_MUTATION_POOL_ENTRIES = 1024;
    private static final long MAX_MUTATION_POOL_BYTES = 256L * 1024L * 1024L;
    private static final int MAX_FILE_NAME_BYTES = 240;
    /** Process-local delete intents; intentionally never persisted. */
    private static final SceneDeletionIntentRegistry PROCESS_DELETION_INTENTS =
        new SceneDeletionIntentRegistry();
    private static final MutationAdmission MUTATION_ADMISSION =
        new MutationAdmission();
    private static final Object MUTATION_POOL_SNAPSHOT_LOCK = new Object();
    private static final Map<String, MutationPoolSnapshot>
        MUTATION_POOL_SNAPSHOTS = new HashMap<>();
    /**
     * Process-local temp-entry ownership.  A second SceneStore instance in
     * this process may scan the same pool while the first one is writing; it
     * must not mistake that live temp directory for crash residue.  The
     * registry is intentionally not durable, so a temp left by a dead process
     * is cleaned on the next startup.
     */
    private static final Object MUTATION_POOL_TEMP_LOCK = new Object();
    private static final Set<String> ACTIVE_MUTATION_POOL_TEMPS =
        new HashSet<>();

    private static final class MutationPoolSnapshot {
        private final int count;
        private final String diagnostic;

        private MutationPoolSnapshot(int count, String diagnostic) {
            this.count = count;
            this.diagnostic = diagnostic == null ? "" : diagnostic;
        }
    }

    public static final class MutationAdmission {
        private final Object lock = new Object();
        private int activeExternalMutations;
        private int activeInternalMutations;
        private int pendingDeferredAdmissions;
        private boolean fullSyncActive;
        private boolean draining;
        private SceneStore drainingStore;
        private boolean drainerActive;
        private long drainGeneration;
        private long stableDrainGeneration = -1L;
        /** Changes whenever a writer reserves a deferred append. */
        private long deferredAdmissionVersion;

        private static final class ExternalAdmission {
            private final MutationLease lease;
            private final long deferredSequence;
            private final boolean deferred;

            private ExternalAdmission(
                MutationLease lease,
                long deferredSequence,
                boolean deferred
            ) {
                this.lease = lease;
                this.deferredSequence = deferredSequence;
                this.deferred = deferred;
            }
        }

        private MutationAdmission() {}

        private static boolean sameMutationPool(
            SceneStore left,
            SceneStore right
        ) {
            return left == right
                || (left != null
                    && right != null
                    && left.mutationPoolSnapshotKey.equals(
                        right.mutationPoolSnapshotKey
                    ));
        }

        private long beginDrainingLocked(SceneStore store) {
            if (store == null) {
                throw new IllegalStateException(
                    "a full-sync owner is required to drain mutations"
                );
            }
            if (drainingStore != null
                && !sameMutationPool(drainingStore, store)) {
                throw new IllegalStateException(
                    "drain owner does not match mutation pool"
                );
            }
            if (!draining) {
                draining = true;
                drainGeneration++;
                stableDrainGeneration = -1L;
            }
            drainingStore = store;
            return drainGeneration;
        }

        private boolean waitForDrainStable(
            long generation,
            SceneStore store
        ) {
            boolean interrupted = false;
            synchronized (lock) {
                while (stableDrainGeneration < generation) {
                    try {
                        lock.wait();
                    } catch (InterruptedException e) {
                        interrupted = true;
                    }
                }
            }
            return interrupted;
        }

        private void markDrainStableLocked() {
            stableDrainGeneration = drainGeneration;
            lock.notifyAll();
        }

        public final class ExternalMutationRejectedException extends IOException {
            ExternalMutationRejectedException() {
                super("scene mutation rejected while full sync is active");
            }
        }

        public final class MutationLease implements AutoCloseable {
            private boolean closed;
            private final boolean deferredReservation;
            private final SceneStore store;

            private MutationLease(
                boolean deferredReservation,
                SceneStore store
            ) {
                this.deferredReservation = deferredReservation;
                this.store = store;
            }

            @Override
            public void close() {
                boolean wakeDrainer = false;
                synchronized (lock) {
                    if (closed) return;
                    closed = true;
                    if (deferredReservation) {
                        pendingDeferredAdmissions--;
                        wakeDrainer = pendingDeferredAdmissions == 0
                            && draining
                            && store != null;
                    } else {
                        activeExternalMutations--;
                        wakeDrainer = activeExternalMutations == 0
                            && draining
                            && store != null;
                    }
                    lock.notifyAll();
                }
                if (wakeDrainer) {
                    ownerStartDrain(store);
                }
            }

            private void ownerStartDrain(SceneStore store) {
                MutationAdmission.this.startDrainIfNeeded(store);
            }
        }

        public final class FullSyncLease implements AutoCloseable {
            private final MutationAdmission owner = MutationAdmission.this;
            private boolean closed;

            public void attachStore(SceneStore store) {
                synchronized (lock) {
                    if (closed) {
                        throw new IllegalStateException(
                            "full-sync write lease is closed"
                        );
                    }
                    if (store == null) {
                        throw new IllegalArgumentException(
                            "SceneStore is null"
                        );
                    }
                    if (drainingStore != null
                        && !sameMutationPool(drainingStore, store)) {
                        throw new IllegalStateException(
                            "full-sync owner does not match mutation pool"
                        );
                    }
                    drainingStore = store;
                }
            }

            private FullSyncLease() {}

            public MutationReceipt<Void> saveRawSceneSnapshot(
                SceneStore store,
                RawSceneSnapshot snapshot
            ) throws IOException {
                owner.enterInternal(this);
                try {
                    if (store == null) {
                        throw new IllegalArgumentException("SceneStore is null");
                    }
                    store.saveRawSceneSnapshotInternal(snapshot);
                    return MutationReceipt.committed(
                        snapshot == null ? null : snapshot.sceneName,
                        null
                    );
                } finally {
                    owner.leaveInternal();
                }
            }

            public boolean clearMatchingDeletionIntent(
                SceneStore store,
                String sceneName,
                long token
            ) throws IOException {
                owner.enterInternal(this);
                try {
                    if (store == null) {
                        throw new IllegalArgumentException("SceneStore is null");
                    }
                    return store.clearMatchingDeletionIntentInternal(
                        sceneName,
                        token
                    );
                } finally {
                    owner.leaveInternal();
                }
            }

            @Override
            public void close() {
                SceneStore storeToDrain;
                long generation;
                boolean interrupted = false;
                synchronized (lock) {
                    if (closed) return;
                    closed = true;
                    while (activeInternalMutations != 0) {
                        try {
                            lock.wait();
                        } catch (InterruptedException e) {
                            interrupted = true;
                        }
                    }
                    fullSyncActive = false;
                    storeToDrain = drainingStore;
                    generation = beginDrainingLocked(storeToDrain);
                    lock.notifyAll();
                }
                if (storeToDrain != null) {
                    startDrainIfNeeded(storeToDrain);
                    interrupted |= waitForDrainStable(
                        generation,
                        storeToDrain
                    );
                }
                if (interrupted) Thread.currentThread().interrupt();
            }
        }

        private void enterInternal(FullSyncLease lease) throws IOException {
            synchronized (lock) {
                if (lease == null
                    || lease.owner != this
                    || lease.closed
                    || !fullSyncActive) {
                    throw new IOException("full-sync write lease is closed");
                }
                activeInternalMutations++;
            }
        }

        private void leaveInternal() {
            synchronized (lock) {
                activeInternalMutations--;
                lock.notifyAll();
            }
        }

        private ExternalAdmission beginExternalMutation(
            SceneStore store,
            DeferredMutation mutation
        ) throws IOException {
            synchronized (lock) {
                if (fullSyncActive || draining) {
                    if (store == null) {
                        throw new IllegalArgumentException("SceneStore is null");
                    }
                    if (drainingStore != null
                        && !sameMutationPool(drainingStore, store)) {
                        throw new IOException(
                            "Scene mutation uses a different pool root"
                        );
                    }
                    if (drainingStore == null) {
                        drainingStore = store;
                    }
                    pendingDeferredAdmissions++;
                    deferredAdmissionVersion++;
                    return new ExternalAdmission(
                        new MutationLease(true, store),
                        -1L,
                        true
                    );
                }
                activeExternalMutations++;
                return new ExternalAdmission(
                    new MutationLease(false, store),
                    -1L,
                    false
                );
            }
        }

        private boolean beginStartupDrain(SceneStore store) {
            synchronized (lock) {
                if (!fullSyncActive) {
                    if (drainingStore != null
                        && !sameMutationPool(drainingStore, store)
                        && drainerActive) {
                        return false;
                    }
                    beginDrainingLocked(store);
                    return claimDrainerLocked(store);
                }
                return false;
            }
        }

        private boolean claimDrainerLocked(SceneStore store) {
            if (!sameMutationPool(drainingStore, store)
                || !draining
                || drainerActive) {
                return false;
            }
            drainerActive = true;
            return true;
        }

        private void startDrainIfNeeded(SceneStore store) {
            boolean start;
            synchronized (lock) {
                start = !fullSyncActive
                    && draining
                    && sameMutationPool(drainingStore, store)
                    && pendingDeferredAdmissions == 0
                    && activeExternalMutations == 0
                    && claimDrainerLocked(store);
            }
            if (start) {
                store.drainDeferredMutations();
            }
        }

        private long persistDeferredMutationWithSequence(
            SceneStore store,
            DeferredMutation mutation
        ) throws IOException {
            // Durable append is serialized under the same global admission
            // lock for every SceneStore instance.  Each instance still owns
            // its injected pool directory, but shared production roots cannot
            // race sequence/meta allocation or capacity accounting.
            synchronized (lock) {
                return store.deferMutation(mutation);
            }
        }

        private boolean requestDrain(SceneStore store) {
            synchronized (lock) {
                if (store == null || fullSyncActive) return false;
                if (drainingStore != null
                    && !sameMutationPool(drainingStore, store)
                    && drainerActive) {
                    return false;
                }
                beginDrainingLocked(store);
                return claimDrainerLocked(store);
            }
        }

        private void finishDrainHeadFailure(
            SceneStore store
        ) {
            synchronized (lock) {
                if (sameMutationPool(drainingStore, store)) {
                    // A failed head is a stable result of this drain pass
                    // even when neither the entry state nor the pool-level
                    // diagnostic could be persisted.  The entry remains the
                    // ordered head in DRAINING; an explicit retry can make a
                    // later attempt after the underlying I/O recovers.
                    markDrainBlockedStableLocked();
                }
            }
        }

        /**
         * Marks a drain pass as stably blocked while preserving DRAINING.
         * Requires {@link #lock} to be held.
         */
        private void markDrainBlockedStableLocked() {
            drainerActive = false;
            markDrainStableLocked();
        }

        private void finishDrainIfEmpty(SceneStore store) {
            boolean retry = false;
            boolean poolEmpty = false;
            Exception poolCheckFailure = null;
            long observedAdmissionVersion;
            synchronized (lock) {
                if (!sameMutationPool(drainingStore, store) || fullSyncActive) {
                    drainerActive = false;
                    return;
                }
                if (pendingDeferredAdmissions != 0) {
                    drainerActive = false;
                    lock.notifyAll();
                    return;
                }
                if (activeExternalMutations != 0) {
                    drainerActive = false;
                    lock.notifyAll();
                    return;
                }
                observedAdmissionVersion = deferredAdmissionVersion;
            }

            // Never hold the global admission lock while touching a pool
            // directory.  A concurrent deferred append is linearized by the
            // pending reservation re-check below.
            try {
                poolEmpty = store.isMutationPoolEmpty();
            } catch (Exception e) {
                poolCheckFailure = e;
                store.persistMutationPoolDiagnostic(e);
            }

            synchronized (lock) {
                if (!sameMutationPool(drainingStore, store) || fullSyncActive) {
                    drainerActive = false;
                    lock.notifyAll();
                    return;
                }
                if (pendingDeferredAdmissions != 0) {
                    drainerActive = false;
                    lock.notifyAll();
                    return;
                }
                if (activeExternalMutations != 0) {
                    drainerActive = false;
                    lock.notifyAll();
                    return;
                }
                if (deferredAdmissionVersion != observedAdmissionVersion) {
                    // A deferred writer reserved and released an append while
                    // the directory check was outside the admission lock.  Its
                    // earlier poolEmpty=true observation is stale; run a new
                    // drain pass before allowing IDLE.
                    drainerActive = false;
                    retry = true;
                    lock.notifyAll();
                } else if (poolCheckFailure != null) {
                    // Retain DRAINING and allow the explicit retry seam to
                    // recover after the underlying I/O failure.
                    markDrainBlockedStableLocked();
                    return;
                } else if (!poolEmpty) {
                    drainerActive = false;
                    retry = true;
                    lock.notifyAll();
                } else {
                    draining = false;
                    drainingStore = null;
                    drainerActive = false;
                    markDrainStableLocked();
                }
            }
            if (retry) {
                startDrainIfNeeded(store);
            }
        }

        private FullSyncLease beginFullSync(SceneStore ownerStore)
            throws IOException {
            if (ownerStore == null) {
                throw new IllegalArgumentException("SceneStore is null");
            }
            SceneStore storeToDrain = null;
            IOException interruptedFailure = null;
            FullSyncLease lease = null;
            boolean interrupted = false;
            long interruptedDrainGeneration = -1L;
            synchronized (lock) {
                if (fullSyncActive) {
                    throw new IOException("another full sync is already active");
                }
                if (draining) {
                    throw new IOException("deferred Scene mutations are draining");
                }
                fullSyncActive = true;
                drainingStore = ownerStore;
                while (activeExternalMutations != 0) {
                    try {
                        lock.wait();
                    } catch (InterruptedException e) {
                        fullSyncActive = false;
                        interrupted = true;
                        storeToDrain = ownerStore;
                        interruptedDrainGeneration = beginDrainingLocked(
                            storeToDrain
                        );
                        lock.notifyAll();
                        interruptedFailure = new IOException(e);
                        break;
                    }
                }
                if (interruptedFailure == null) {
                    lease = new FullSyncLease();
                }
            }
            if (storeToDrain != null) {
                startDrainIfNeeded(storeToDrain);
            }
            if (interruptedFailure != null) {
                if (storeToDrain != null) {
                    interrupted |= waitForDrainStable(
                        interruptedDrainGeneration,
                        storeToDrain
                    );
                }
                if (interrupted) Thread.currentThread().interrupt();
                throw interruptedFailure;
            }
            return lease;
        }
    }

    public static MutationAdmission.FullSyncLease beginFullSyncAdmission(
        SceneStore store
    ) throws IOException {
        MutationAdmission.FullSyncLease lease = MUTATION_ADMISSION.beginFullSync(
            store
        );
        try {
            lease.attachStore(store);
            return lease;
        } catch (RuntimeException e) {
            lease.close();
            throw e;
        }
    }

    public static final class ValidatedScene {
        public final String sceneName;
        public final byte[] bytes;
        public final List<String> languages;

        ValidatedScene(String sceneName, byte[] bytes, List<String> languages) {
            this.sceneName = sceneName;
            this.bytes = bytes;
            this.languages = Collections.unmodifiableList(
                new ArrayList<>(languages)
            );
        }
    }

    public static final class SceneInfo {
        public final String sceneName;
        public final List<String> languages;

        SceneInfo(ValidatedScene scene) {
            sceneName = scene.sceneName;
            languages = scene.languages;
        }
    }

    /** Immutable token captured by one full-sync operation. */
    public static final class DeletionIntent {
        public final String sceneName;
        public final long token;

        private DeletionIntent(String sceneName, long token) {
            this.sceneName = sceneName;
            this.token = token;
        }
    }

    /** Raw bytes from one formal candidate, retained for mirror export. */
    public static final class RawSceneSnapshot {
        public final String sceneName;
        public final byte[] bytes;
        public final List<String> languages;

        RawSceneSnapshot(String sceneName, byte[] bytes, List<String> languages) {
            this.sceneName = sceneName;
            this.bytes = bytes;
            this.languages = Collections.unmodifiableList(
                new ArrayList<>(languages)
            );
        }
    }

    public enum RawSceneFailureKind {
        READ,
        EMPTY,
        TOO_LARGE,
        INVALID_UTF8,
        INVALID_JSON,
        SCHEMA_INVALID,
        IDENTITY_MISMATCH,
        INTERNAL
    }

    public static final class RawSceneFailure extends Exception {
        private static final long serialVersionUID = 1L;
        public final RawSceneFailureKind kind;

        RawSceneFailure(RawSceneFailureKind kind, Exception cause) {
            super(cause == null ? null : cause.getMessage(), cause);
            this.kind = kind;
        }

        RawSceneFailure(RawSceneFailureKind kind) {
            this(kind, null);
        }
    }

    public enum RawSceneWriteFailureKind {
        START,
        COPY,
        COMMIT
    }

    /** Stable storage-stage failure for the unparsed game apply path. */
    public static final class RawSceneWriteFailure extends IOException {
        private static final long serialVersionUID = 1L;
        public final RawSceneWriteFailureKind kind;

        RawSceneWriteFailure(
            RawSceneWriteFailureKind kind,
            IOException cause
        ) {
            super(cause == null ? null : cause.getMessage(), cause);
            this.kind = kind;
        }
    }

    /**
     * Stages one raw game-side Scene body in AtomicFile's temporary file.
     * The caller must commit only after the complete wire request has passed
     * validation; close/abort leaves the previous formal file untouched.
     */
    public final class RawSceneWriteSession implements AutoCloseable {
        private final String sceneName;
        private final AtomicFile atomicFile;
        private final boolean deferred;
        private final ByteArrayOutputStream deferredOutput;
        private FileOutputStream output;
        private boolean committed;
        private boolean closed;
        private MutationAdmission.MutationLease admissionLease;

        private RawSceneWriteSession(
            String sceneName,
            MutationAdmission.MutationLease admissionLease,
            boolean deferred
        ) throws IOException {
            this.sceneName = requireSceneName(sceneName);
            this.admissionLease = admissionLease;
            this.deferred = deferred;
            if (deferred) {
                atomicFile = null;
                deferredOutput = new ByteArrayOutputStream();
            } else {
                deferredOutput = null;
                atomicFile = new AtomicFile(
                    new File(sceneDirectory, fileNameForScene(this.sceneName))
                );
                try {
                    ensureDirectories();
                    output = atomicFile.startWrite();
                } catch (IOException e) {
                    releaseAdmission();
                    throw new RawSceneWriteFailure(
                        RawSceneWriteFailureKind.START,
                        e
                    );
                }
            }
        }

        /** Copies exactly one bounded body using a fixed-size buffer. */
        public synchronized void copyFrom(InputStream input, int bodyLength)
            throws IOException {
            if (closed || committed) {
                throw new IOException("raw Scene write session is closed");
            }
            if (input == null
                || bodyLength < 1
                || bodyLength > MAX_SCENE_BYTES) {
                throw new RawSceneWriteFailure(
                    RawSceneWriteFailureKind.COPY,
                    new IOException("invalid raw Scene body length")
                );
            }
            byte[] buffer = new byte[8192];
            int remaining = bodyLength;
            while (remaining > 0) {
                int read = input.read(
                    buffer,
                    0,
                    Math.min(buffer.length, remaining)
                );
                if (read < 0) {
                    throw new IOException("early EOF while writing raw Scene");
                }
                if (read == 0) {
                    continue;
                }
                try {
                    if (deferred) {
                        deferredOutput.write(buffer, 0, read);
                    } else {
                        output.write(buffer, 0, read);
                    }
                } catch (IOException e) {
                    throw new RawSceneWriteFailure(
                        RawSceneWriteFailureKind.COPY,
                        e
                    );
                }
                remaining -= read;
            }
            if (input.read() != -1) {
                throw new IOException("raw Scene body exceeded declared length");
            }
        }

        /** Publishes the staged file after the surrounding wire request passed. */
        public synchronized MutationReceipt<Void> commit() throws IOException {
            if (closed || committed) {
                throw new IOException("raw Scene write session is closed");
            }
            try {
                if (deferred) {
                    long sequence = MUTATION_ADMISSION
                        .persistDeferredMutationWithSequence(
                        SceneStore.this,
                        DeferredMutation.put(
                            sceneName,
                            deferredOutput.toByteArray()
                        )
                    );
                    committed = true;
                    closed = true;
                    releaseAdmission();
                    return MutationReceipt.deferred(sceneName, sequence);
                }
                atomicFile.finishWrite(output);
                output = null;
                committed = true;
                closed = true;
                releaseAdmission();
                return MutationReceipt.committed(sceneName, null);
            } catch (RuntimeException e) {
                throw new RawSceneWriteFailure(
                    RawSceneWriteFailureKind.COMMIT,
                    new IOException("could not commit raw Scene", e)
                );
            } finally {
                if (!committed) {
                    releaseAdmission();
                }
            }
        }

        /** Discards the temporary file and retains the previous formal file. */
        public synchronized void abort() {
            if (closed) {
                return;
            }
            closed = true;
            try {
                if (output != null) {
                    atomicFile.failWrite(output);
                    output = null;
                }
            } finally {
                releaseAdmission();
            }
        }

        private synchronized void releaseAdmission() {
            if (admissionLease != null) {
                admissionLease.close();
                admissionLease = null;
            }
        }

        public synchronized String getSceneName() {
            return sceneName;
        }

        @Override
        public synchronized void close() {
            if (!committed) {
                abort();
            }
        }
    }

    /** Opens a raw staging session without parsing Scene JSON or schema. */
    public RawSceneWriteSession beginRawSceneWrite(String sceneName)
        throws IOException {
        MutationAdmission.ExternalAdmission admission =
            MUTATION_ADMISSION.beginExternalMutation(this, null);
        try {
            return new RawSceneWriteSession(
                requireSceneName(sceneName),
                admission.lease,
                admission.deferred
            );
        } catch (IOException e) {
            if (admission.lease != null) {
                admission.lease.close();
            }
            throw e;
        } catch (RuntimeException e) {
            if (admission.lease != null) {
                admission.lease.close();
            }
            throw e;
        }
    }

    /** Convenience single-file raw write for callers with an already bounded body. */
    public MutationReceipt<Void> writeRawSceneAtomically(
        String sceneName,
        InputStream input,
        int bodyLength
    ) throws IOException {
        try (RawSceneWriteSession session = beginRawSceneWrite(sceneName)) {
            session.copyFrom(input, bodyLength);
            return session.commit();
        }
    }

    @FunctionalInterface
    public interface RawSceneCandidateConsumer {
        void onCandidate(
            String sceneName,
            RawSceneSnapshot snapshot,
            Exception validationError
        ) throws Exception;
    }

    @FunctionalInterface
    public interface SceneCandidateConsumer {
        /**
         * Receives each formal candidate exactly once.  A null scene means
         * the candidate was readable but invalid; the original exception is
         * supplied for stable REJECTED reason mapping.
         */
        void onCandidate(
            String sceneName,
            ValidatedScene scene,
            Exception validationError
        ) throws Exception;
    }

    private final Context context;
    private final File sceneDirectory;
    private final File incomingDirectory;
    private final File mutationPoolRoot;
    private final File mutationPoolEntries;
    private final String mutationPoolSnapshotKey;
    private final Object mutationPoolLock = new Object();
    private volatile int deferredMutationCount;
    private volatile String deferredMutationDiagnostic = "";
    private boolean mutationPoolEnumerationFailed;
    private final JsonSchemaValidator schemaValidator;

    public SceneStore(Context context) {
        this(
            context,
            new File(
                requireContext(context).getFilesDir(),
                DIRECTORY_NAME
            ),
            new File(
                requireContext(context).getFilesDir(),
                MUTATION_POOL_DIRECTORY_NAME
            ),
            loadSchema(requireContext(context))
        );
    }

    /** Explicit directory/schema seam for the game-process Scene mirror. */
    public SceneStore(
        Context context,
        File sceneDirectory,
        JSONObject schema
    ) {
        this(
            context,
            sceneDirectory,
            new File(
                parentOrSelf(sceneDirectory),
                MUTATION_POOL_DIRECTORY_NAME
            ),
            schema
        );
    }

    private static File parentOrSelf(File directory) {
        if (directory == null) {
            throw new IllegalArgumentException("sceneDirectory is null");
        }
        File parent = directory.getParentFile();
        return parent == null ? directory : parent;
    }

    private static String mutationPoolSnapshotKey(File root) {
        try {
            return root.getCanonicalPath();
        } catch (IOException e) {
            return root.getAbsolutePath();
        }
    }

    private static String mutationPoolTempKey(File temp) {
        try {
            return temp.getCanonicalPath();
        } catch (IOException e) {
            return temp.getAbsolutePath();
        }
    }

    /** Explicit Scene and deferred-pool roots used by host fixtures. */
    public SceneStore(
        Context context,
        File sceneDirectory,
        File mutationPoolRoot,
        JSONObject schema
    ) {
        Context applicationContext = requireContext(context).getApplicationContext();
        this.context = applicationContext != null
            ? applicationContext
            : context;
        if (sceneDirectory == null || mutationPoolRoot == null || schema == null) {
            throw new IllegalArgumentException(
                "sceneDirectory, mutationPoolRoot, and schema cannot be null"
            );
        }
        this.sceneDirectory = sceneDirectory;
        incomingDirectory = new File(sceneDirectory, ".incoming");
        this.mutationPoolRoot = mutationPoolRoot;
        mutationPoolSnapshotKey = mutationPoolSnapshotKey(mutationPoolRoot);
        mutationPoolEntries = new File(
            mutationPoolRoot,
            MUTATION_POOL_ENTRIES_NAME
        );
        schemaValidator = new JsonSchemaValidator(schema);
        initializeMutationPool();
    }

    public File getMutationPoolRoot() {
        return mutationPoolRoot;
    }

    public int getDeferredMutationCount() {
        synchronized (mutationPoolLock) {
            refreshMutationPoolSnapshotLocked();
            return deferredMutationCount;
        }
    }

    /** O(1) snapshot suitable for the foreground notification thread. */
    public int getDeferredMutationCountSnapshot() {
        synchronized (MUTATION_POOL_SNAPSHOT_LOCK) {
            MutationPoolSnapshot snapshot = MUTATION_POOL_SNAPSHOTS.get(
                mutationPoolSnapshotKey
            );
            return snapshot == null ? deferredMutationCount : snapshot.count;
        }
    }

    public String getDeferredMutationDiagnosticSnapshot() {
        synchronized (MUTATION_POOL_SNAPSHOT_LOCK) {
            MutationPoolSnapshot snapshot = MUTATION_POOL_SNAPSHOTS.get(
                mutationPoolSnapshotKey
            );
            return snapshot == null
                ? deferredMutationDiagnostic
                : snapshot.diagnostic;
        }
    }

    /** Explicit recovery seam for a head failure or a newly available disk. */
    public void retryDeferredMutations() {
        if (MUTATION_ADMISSION.requestDrain(this)) {
            drainDeferredMutations();
        }
    }

    public enum MutationStatus {
        COMMITTED,
        DEFERRED,
        UNKNOWN
    }

    /**
     * Query seam for the ContentProvider writer.  A matching durable pool
     * entry wins over the current formal file, so a caller cannot mistake a
     * pre-existing Scene for a newly committed write.
     */
    public MutationStatus getMutationStatus(String sceneName) {
        sceneName = requireSceneName(sceneName);
        synchronized (mutationPoolLock) {
            try {
                List<File> entries = mutationEntriesStrict();
                refreshMutationPoolSnapshotLocked();
                for (File entry : entries) {
                    // A matching scene name is not sufficient evidence of a
                    // durable receipt.  Validate the complete entry shape,
                    // sequence, payload and operation before reporting
                    // DEFERRED; damaged pool contents are UNKNOWN.
                    JSONObject state = readAndValidateMutationState(entry);
                    if (sceneName.equals(state.optString("scene", ""))) {
                        return MutationStatus.DEFERRED;
                    }
                }
            } catch (Exception e) {
                // An unreadable pool cannot prove that this scene's intent
                // was durably admitted.  DEFERRED is reserved for a durable
                // receipt (or a matching entry); callers without that receipt
                // must observe UNKNOWN rather than a false admission.
                return MutationStatus.UNKNOWN;
            }
        }
        return getValidSceneFileByName(sceneName) == null
            ? MutationStatus.UNKNOWN
            : MutationStatus.COMMITTED;
    }

    private boolean isMutationPoolEmpty() throws IOException {
        synchronized (mutationPoolLock) {
            ensureMutationPoolDirectories();
            refreshMutationPoolSnapshotLocked();
            return mutationEntriesStrict().isEmpty();
        }
    }

    public String getDeferredMutationDiagnostic() {
        synchronized (mutationPoolLock) {
            refreshMutationPoolSnapshotLocked();
            File[] entries = mutationEntries().toArray(new File[0]);
            if (mutationPoolEnumerationFailed) {
                return "mutation pool enumeration unavailable";
            }
            if (entries.length == 0) {
                return "";
            }
            try {
                JSONObject state = readMutationState(entries[0]);
                return state.optString("scene", "")
                    + " " + state.optString("operation", "")
                    + " " + state.optString("last_error", "");
            } catch (Exception e) {
                return "mutation pool head is damaged: " + e.getMessage();
            }
        }
    }

    private void initializeMutationPool() {
        boolean hasEntries = false;
        // Pool recovery writes meta.json.  Serialize that write with the
        // global append seam so constructing a second SceneStore cannot
        // rewind metadata while another instance is publishing an entry.
        synchronized (MUTATION_ADMISSION.lock) {
            try {
                synchronized (mutationPoolLock) {
                    ensureMutationPoolDirectories();
                    recoverMutationPoolMetaLocked();
                    hasEntries = !mutationEntriesStrict().isEmpty();
                    refreshMutationPoolSnapshotLocked();
                }
            } catch (Exception e) {
                // Keep external admission in DRAINING until a later retry can
                // inspect the pool; never fall back to direct writes after an
                // uncertain recovery scan.
                hasEntries = true;
            }
        }
        if (hasEntries && MUTATION_ADMISSION.beginStartupDrain(this)) {
            drainDeferredMutations();
        }
    }

    private void refreshMutationPoolSnapshotLocked() {
        List<File> entries = mutationEntries();
        if (mutationPoolEnumerationFailed) {
            deferredMutationDiagnostic =
                "mutation pool enumeration unavailable";
            publishMutationPoolSnapshotLocked();
            return;
        }
        deferredMutationCount = entries.size();
        if (entries.isEmpty()) {
            deferredMutationDiagnostic = "";
            publishMutationPoolSnapshotLocked();
            return;
        }
        try {
            JSONObject state = readMutationState(entries.get(0));
            deferredMutationDiagnostic = state.optString("scene", "")
                + " " + state.optString("operation", "")
                + " " + state.optString("last_error", "");
        } catch (Exception e) {
            deferredMutationDiagnostic =
                "mutation pool head is damaged: " + safeError(e);
        }
        publishMutationPoolSnapshotLocked();
    }

    private void publishMutationPoolSnapshotLocked() {
        synchronized (MUTATION_POOL_SNAPSHOT_LOCK) {
            MUTATION_POOL_SNAPSHOTS.put(
                mutationPoolSnapshotKey,
                new MutationPoolSnapshot(
                    deferredMutationCount,
                    deferredMutationDiagnostic
                )
            );
        }
    }

    private void ensureMutationPoolDirectories() throws IOException {
        IoUtils.ensureDirectory(mutationPoolRoot);
        IoUtils.ensureDirectory(mutationPoolEntries);
    }

    private void recoverMutationPoolMetaLocked() throws IOException {
        long maxSequence = 0L;
        for (File entry : mutationEntriesStrict()) {
            try {
                JSONObject state = readAndValidateMutationState(entry);
                maxSequence = Math.max(
                    maxSequence,
                    state.getLong("sequence")
                );
            } catch (Exception e) {
                // Do not rewrite metadata from an entry whose sequence cannot
                // be proved.  The entry remains the durable head diagnostic.
                throw new IOException(
                    "deferred mutation entry cannot prove its sequence",
                    e
                );
            }
        }
        long nextSequence = readMutationPoolNextSequenceLocked();
        if (maxSequence == Long.MAX_VALUE) {
            throw new IOException("deferred mutation sequence is exhausted");
        }
        nextSequence = Math.max(nextSequence, maxSequence + 1L);
        writeMutationPoolMetaLocked(nextSequence);
    }

    private List<File> mutationEntries() {
        File[] files = mutationPoolEntries.listFiles();
        if (files == null) {
            mutationPoolEnumerationFailed = true;
            return new ArrayList<>();
        }
        mutationPoolEnumerationFailed = false;
        List<File> result = new ArrayList<>();
        for (File file : files) {
            if (file.isDirectory() && !file.getName().startsWith(".")) {
                // Keep malformed names in the ordered view.  They are
                // durable corruption and must block the head rather than be
                // mistaken for an empty pool.  Only dot-prefixed temporary
                // directories are ignored.
                result.add(file);
            }
        }
        result.sort((left, right) -> compareMutationEntries(left, right));
        return result;
    }

    private List<File> mutationEntriesStrict() throws IOException {
        File[] files = mutationPoolEntries.listFiles();
        if (files == null) {
            throw new IOException(
                "could not enumerate deferred mutation pool entries"
            );
        }
        for (File file : files) {
            String name = file.getName();
            if (file.isDirectory() && name.startsWith(MUTATION_POOL_TEMP_PREFIX)) {
                // A process can die after creating the write-ahead temporary
                // directory but before publishing it.  It is explicitly
                // recoverable scratch state, unlike every other artifact. A
                // live writer in this process owns the path through the
                // registry; leave it alone and let its reservation keep the
                // admission state out of IDLE.
                boolean active;
                synchronized (MUTATION_POOL_TEMP_LOCK) {
                    active = ACTIVE_MUTATION_POOL_TEMPS.contains(
                        mutationPoolTempKey(file)
                    );
                    if (!active && !deleteRecursively(file)) {
                        throw new IOException(
                            "could not remove temporary mutation entry " + name
                        );
                    }
                }
                continue;
            }
            if (!file.isDirectory()) {
                throw new IOException(
                    "unexpected deferred mutation pool artifact " + name
                );
            }
            if (name.startsWith(".")) {
                throw new IOException(
                    "unexpected deferred mutation pool directory " + name
                );
            }
        }
        files = mutationPoolEntries.listFiles();
        if (files == null) {
            throw new IOException(
                "could not enumerate deferred mutation pool entries"
            );
        }
        List<File> result = new ArrayList<>();
        for (File file : files) {
            if (file.isDirectory()
                && !file.getName().startsWith(MUTATION_POOL_TEMP_PREFIX)) {
                result.add(file);
            }
        }
        result.sort((left, right) -> compareMutationEntries(left, right));
        return result;
    }

    private static int compareMutationEntries(File left, File right) {
        Long leftSequence = trySequenceOf(left);
        Long rightSequence = trySequenceOf(right);
        if (leftSequence == null && rightSequence == null) return 0;
        if (leftSequence == null) return -1;
        if (rightSequence == null) return 1;
        return Long.compare(leftSequence, rightSequence);
    }

    private static Long trySequenceOf(File entry) {
        try {
            return sequenceOf(entry);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private static long sequenceOf(File entry) {
        String name = entry.getName();
        int separator = name.indexOf('-');
        if (separator <= 0) {
            throw new IllegalArgumentException("invalid mutation entry name");
        }
        return Long.parseLong(name.substring(0, separator));
    }

    private static long validatedMutationEntrySequence(File entry)
        throws IOException {
        String name = entry.getName();
        // 20 decimal digits, a separator, and a canonical UUID suffix.
        if (name.length() != 57 || name.charAt(20) != '-') {
            throw new IOException("deferred mutation entry name is invalid");
        }
        String sequenceText = name.substring(0, 20);
        for (int index = 0; index < sequenceText.length(); index++) {
            if (!Character.isDigit(sequenceText.charAt(index))) {
                throw new IOException(
                    "deferred mutation entry sequence is invalid"
                );
            }
        }
        final long sequence;
        try {
            sequence = Long.parseLong(sequenceText);
            UUID uuid = UUID.fromString(name.substring(21));
            if (!uuid.toString().equals(name.substring(21))) {
                throw new IOException(
                    "deferred mutation entry UUID is not canonical"
                );
            }
        } catch (IllegalArgumentException e) {
            throw new IOException("deferred mutation entry name is invalid", e);
        }
        if (sequence <= 0L
            || !String.format(
                java.util.Locale.ROOT,
                "%020d",
                sequence
            ).equals(sequenceText)) {
            throw new IOException("deferred mutation entry sequence is invalid");
        }
        return sequence;
    }

    private JSONObject readMutationState(File entry) throws Exception {
        File stateFile = new File(entry, "state.json");
        if (!IoUtils.atomicFileExists(stateFile)) {
            throw new IOException("deferred mutation state is missing");
        }
        AtomicFile atomicFile = new AtomicFile(stateFile);
        try (InputStream input = atomicFile.openRead()) {
            return new JSONObject(new String(
                IoUtils.readAllBytesLimited(input, 64 * 1024),
                StandardCharsets.UTF_8
            ));
        }
    }

    private JSONObject readAndValidateMutationState(File entry)
        throws Exception {
        if (entry == null || !entry.isDirectory()) {
            throw new IOException("deferred mutation entry is not a directory");
        }
        long directorySequence = validatedMutationEntrySequence(entry);
        JSONObject state = readMutationState(entry);
        long version = requiredMutationLong(state, "version");
        if (version != 1L) {
            throw new IOException("unsupported deferred mutation entry version");
        }
        long stateSequence = requiredMutationLong(state, "sequence");
        if (stateSequence <= 0L || stateSequence != directorySequence) {
            throw new IOException(
                "deferred mutation state sequence does not match entry"
            );
        }
        long createdAt = requiredMutationLong(state, "created_at");
        long attemptCount = requiredMutationLong(state, "attempt_count");
        if (createdAt < 0L || attemptCount < 0L) {
            throw new IOException(
                "deferred mutation state counters are negative"
            );
        }
        requiredMutationString(state, "last_error");
        DeferredMutationOperation operation;
        try {
            operation = DeferredMutationOperation.valueOf(
                requiredMutationString(state, "operation")
            );
        } catch (Exception e) {
            throw new IOException("deferred mutation operation is invalid", e);
        }
        String sceneName = requireSceneName(
            requiredMutationString(state, "scene")
        );
        String language = "";
        if (state.has("language")) {
            language = requiredMutationString(state, "language");
        }
        File payload = new File(entry, "scene.json");
        if (operation == DeferredMutationOperation.REMOVE_LANGUAGE) {
            if (language.isEmpty()) {
                throw new IOException("deferred language mutation is missing language");
            }
            if (IoUtils.atomicFileExists(payload)) {
                throw new IOException(
                    "language mutation unexpectedly carries a Scene payload"
                );
            }
        } else {
            if (!language.isEmpty() || state.has("language")) {
                throw new IOException(
                    "deferred non-language mutation carries language"
                );
            }
            if (operation == DeferredMutationOperation.PUT_SCENE) {
                if (!IoUtils.atomicFileExists(payload)) {
                    throw new IOException("deferred Scene payload is missing");
                }
                // Read through AtomicFile so a base/.bak pair follows the
                // same recovery semantics as formal Scene files.
                try (InputStream input = new AtomicFile(payload).openRead()) {
                    byte[] bytes = IoUtils.readAllBytesLimited(
                        input,
                        MAX_SCENE_BYTES
                    );
                    if (bytes.length < 1) {
                        throw new IOException("deferred Scene payload is empty");
                    }
                }
            } else if (IoUtils.atomicFileExists(payload)) {
                throw new IOException(
                    "delete mutation unexpectedly carries a Scene payload"
                );
            }
        }
        // Keep the local variable in the validation path: requiring a valid
        // scene identity is part of the durable entry contract.
        if (!sceneName.equals(requiredMutationString(state, "scene"))) {
            throw new IOException("deferred Scene identity is not canonical");
        }
        return state;
    }

    private static long requiredMutationLong(JSONObject state, String key)
        throws Exception {
        if (state == null || !state.has(key)) {
            throw new IOException("deferred mutation state is missing " + key);
        }
        Object value = state.get(key);
        // The on-disk schema uses JSON integers.  Do not accept a floating
        // point spelling such as 1.0 merely because it happens to round to a
        // long; a damaged state must block the head rather than be replayed.
        if (!(value instanceof Integer) && !(value instanceof Long)) {
            throw new IOException(
                "deferred mutation state field is not an integer: " + key
            );
        }
        return ((Number) value).longValue();
    }

    private static String requiredMutationString(JSONObject state, String key)
        throws Exception {
        if (state == null || !state.has(key)) {
            throw new IOException("deferred mutation state is missing " + key);
        }
        Object value = state.get(key);
        if (!(value instanceof String)) {
            throw new IOException(
                "deferred mutation state field is not text: " + key
            );
        }
        return (String) value;
    }

    private static JSONObject readAtomicJson(File file, int limit)
        throws Exception {
        try (InputStream input = new AtomicFile(file).openRead()) {
            return new JSONObject(new String(
                IoUtils.readAllBytesLimited(input, limit),
                StandardCharsets.UTF_8
            ));
        }
    }

    /** Reads the durable sequence cursor with strict schema/type checks. */
    private long readMutationPoolNextSequenceLocked() throws IOException {
        File meta = new File(mutationPoolRoot, MUTATION_POOL_META_NAME);
        if (!IoUtils.atomicFileExists(meta)) {
            return 1L;
        }
        try {
            JSONObject json = readAtomicJson(meta, 64 * 1024);
            long version = requiredMutationMetaLong(json, "version");
            if (version != 1L) {
                throw new IOException(
                    "unsupported deferred mutation metadata version"
                );
            }
            long nextSequence = requiredMutationMetaLong(
                json,
                "next_sequence"
            );
            if (nextSequence <= 0L) {
                throw new IOException(
                    "deferred mutation metadata sequence is invalid"
                );
            }
            return nextSequence;
        } catch (Exception e) {
            throw new IOException(
                "deferred mutation metadata is damaged",
                e
            );
        }
    }

    private static long requiredMutationMetaLong(
        JSONObject state,
        String key
    ) throws Exception {
        if (state == null || !state.has(key)) {
            throw new IOException("deferred mutation metadata is missing " + key);
        }
        Object value = state.get(key);
        // org.json represents JSON integers as Integer/Long.  Reject
        // floating-point spellings (including 1.0) so a damaged meta file
        // cannot be silently normalized by getLong/longValue.
        if (!(value instanceof Integer) && !(value instanceof Long)) {
            throw new IOException(
                "deferred mutation metadata field is not an integer: " + key
            );
        }
        return ((Number) value).longValue();
    }

    private long deferMutation(DeferredMutation mutation) throws IOException {
        if (mutation == null || mutation.sceneName == null) {
            throw new IllegalArgumentException("deferred mutation is invalid");
        }
        synchronized (mutationPoolLock) {
            ensureMutationPoolDirectories();
            List<File> entries = mutationEntriesStrict();
            long payloadBytes = 0L;
            for (File entry : entries) {
                File payload = new File(entry, "scene.json");
                if (payload.isFile()) {
                    payloadBytes += payload.length();
                }
            }
            long newBytes = mutation.payload == null ? 0L : mutation.payload.length;
            if (entries.size() >= MAX_MUTATION_POOL_ENTRIES
                || payloadBytes + newBytes > MAX_MUTATION_POOL_BYTES) {
                throw new DeferredMutationPoolFullException(
                    "Scene mutation pool is full entries=" + entries.size()
                        + " payload_bytes=" + payloadBytes
                );
            }
            long sequence = nextMutationSequenceLocked(entries);
            if (sequence == Long.MAX_VALUE) {
                throw new IOException(
                    "deferred mutation sequence is exhausted"
                );
            }
            String directoryName = String.format(
                java.util.Locale.ROOT,
                "%020d-%s",
                sequence,
                UUID.randomUUID().toString()
            );
            File temporary = new File(
                mutationPoolEntries,
                ".incoming-" + UUID.randomUUID().toString()
            );
            boolean tempRegistered = false;
            synchronized (MUTATION_POOL_TEMP_LOCK) {
                if (!temporary.mkdir()) {
                    throw new IOException(
                        "could not create deferred mutation entry"
                    );
                }
                ACTIVE_MUTATION_POOL_TEMPS.add(
                    mutationPoolTempKey(temporary)
                );
                tempRegistered = true;
            }
            boolean published = false;
            try {
                long now = System.currentTimeMillis();
                JSONObject state = new JSONObject()
                    .put("version", 1)
                    .put("sequence", sequence)
                    .put("operation", mutation.operation.name())
                    .put("scene", requireSceneName(mutation.sceneName))
                    .put("created_at", now)
                    .put("attempt_count", 0)
                    .put("last_error", "");
                if (mutation.language != null) {
                    state.put("language", mutation.language);
                }
                IoUtils.writeAtomically(
                    new File(temporary, "state.json"),
                    state.toString().getBytes(StandardCharsets.UTF_8)
                );
                if (mutation.operation == DeferredMutationOperation.PUT_SCENE) {
                    if (mutation.payload == null
                        || mutation.payload.length < 1
                        || mutation.payload.length > MAX_SCENE_BYTES) {
                        throw new IOException("deferred Scene payload is invalid");
                    }
                    IoUtils.writeAtomically(
                        new File(temporary, "scene.json"),
                        mutation.payload
                    );
                }
                // Write-ahead monotonicity: once this metadata is durable,
                // sequence is consumed even if the entry rename crashes.
                // Never publish an entry whose next_sequence fact failed.
                writeMutationPoolMetaLocked(sequence + 1L);
                File publishedEntry = new File(mutationPoolEntries, directoryName);
                if (!temporary.renameTo(publishedEntry)) {
                    throw new IOException("could not publish deferred mutation entry");
                }
                published = true;
                refreshMutationPoolSnapshotLocked();
                return sequence;
            } finally {
                if (!published && temporary.exists()) {
                    deleteRecursively(temporary);
                }
                if (tempRegistered) {
                    synchronized (MUTATION_POOL_TEMP_LOCK) {
                        ACTIVE_MUTATION_POOL_TEMPS.remove(
                            mutationPoolTempKey(temporary)
                        );
                    }
                }
            }
        }
    }

    private long nextMutationSequenceLocked(List<File> entries) throws IOException {
        long next = readMutationPoolNextSequenceLocked();
        for (File entry : entries) {
            long sequence = sequenceOf(entry);
            if (sequence == Long.MAX_VALUE) {
                throw new IOException(
                    "deferred mutation sequence is exhausted"
                );
            }
            next = Math.max(next, sequence + 1L);
        }
        return next;
    }

    private void writeMutationPoolMetaLocked(long nextSequence)
        throws IOException {
        if (nextSequence <= 0L) {
            throw new IOException("invalid deferred mutation sequence");
        }
        IoUtils.writeAtomically(
            new File(mutationPoolRoot, MUTATION_POOL_META_NAME),
            new JSONObject()
                .put("version", 1)
                .put("next_sequence", nextSequence)
                .toString()
                .getBytes(StandardCharsets.UTF_8)
        );
    }

    private void persistMutationPoolDiagnostic(Exception failure) {
        synchronized (mutationPoolLock) {
            String message = safeError(failure);
            try {
                ensureMutationPoolDirectories();
                IoUtils.writeAtomically(
                    new File(
                        mutationPoolRoot,
                        MUTATION_POOL_DIAGNOSTIC_NAME
                    ),
                    new JSONObject()
                        .put("version", 1)
                        .put("kind", "pool_io_failure")
                        .put("last_error", message)
                        .put("updated_at", System.currentTimeMillis())
                        .toString()
                        .getBytes(StandardCharsets.UTF_8)
                );
                deferredMutationDiagnostic =
                    "mutation pool I/O failure: " + message;
                publishMutationPoolSnapshotLocked();
            } catch (Exception ignored) {
                // The pool itself may be unavailable (for example, a read-
                // only or disconnected volume).  Keep the failure visible to
                // this process even though no durable diagnostic can be
                // written, so callers still get a bounded stable head result.
                deferredMutationDiagnostic =
                    "mutation pool I/O failure: " + message;
                publishMutationPoolSnapshotLocked();
            }
        }
    }

    private MutationReceipt<Void> persistDeferredAdmission(
        MutationAdmission.ExternalAdmission admission,
        DeferredMutation mutation
    ) throws IOException {
        if (admission == null || !admission.deferred || admission.lease == null) {
            throw new IllegalArgumentException("mutation is not deferred");
        }
        try {
            long sequence = MUTATION_ADMISSION
                .persistDeferredMutationWithSequence(this, mutation);
            return MutationReceipt.deferred(mutation.sceneName, sequence);
        } finally {
            // Releasing the reservation may wake the sole drainer.  It is
            // deliberately outside deferMutation's pool lock.
            admission.lease.close();
        }
    }

    private void drainDeferredMutations() {
        try {
            while (true) {
                File head;
                List<File> entries;
                synchronized (MUTATION_ADMISSION.lock) {
                    synchronized (mutationPoolLock) {
                        ensureMutationPoolDirectories();
                        // Re-establish the monotonic sequence fact before any
                        // entry can be applied or removed.  Startup may have
                        // entered DRAINING after a failed recovery scan; that
                        // state must remain fail-closed until meta and entries
                        // are reconciled successfully.
                        recoverMutationPoolMetaLocked();
                        entries = mutationEntriesStrict();
                        refreshMutationPoolSnapshotLocked();
                    }
                }
                if (entries.isEmpty()) {
                    // The pool lock is released before changing admission
                    // state.  The pending-deferred reservation prevents a
                    // writer that began before this observation from crossing
                    // the IDLE transition.
                    MUTATION_ADMISSION.finishDrainIfEmpty(this);
                    return;
                }
                boolean validOrder;
                synchronized (mutationPoolLock) {
                    validOrder = validateMutationEntryOrder(entries);
                }
                if (!validOrder) {
                    MUTATION_ADMISSION.finishDrainHeadFailure(this);
                    return;
                }
                head = entries.get(0);
                try {
                    applyDeferredMutation(head);
                    synchronized (mutationPoolLock) {
                        if (head.exists() && !deleteRecursively(head)) {
                            throw new IOException(
                                "could not remove committed mutation entry"
                            );
                        }
                        refreshMutationPoolSnapshotLocked();
                    }
                } catch (Exception e) {
                    synchronized (mutationPoolLock) {
                        try {
                            JSONObject state = readMutationState(head);
                            state.put(
                                "attempt_count",
                                state.optInt("attempt_count", 0) + 1
                            );
                            state.put("last_error", safeError(e));
                            IoUtils.writeAtomically(
                                new File(head, "state.json"),
                                state.toString().getBytes(StandardCharsets.UTF_8)
                            );
                            refreshMutationPoolSnapshotLocked();
                        } catch (Exception ignored) {
                            // Preserve the entry even if its diagnostic is
                            // itself temporarily unwritable.  Use the
                            // pool-level diagnostic when possible; the helper
                            // also publishes a process-local snapshot when
                            // that fallback is unavailable.
                            persistMutationPoolDiagnostic(e);
                        }
                    }
                    MUTATION_ADMISSION.finishDrainHeadFailure(this);
                    return;
                }
            }
        } catch (Exception e) {
            // An enumeration/metadata failure has no entry state file to
            // annotate.  Persist a pool-level diagnostic when possible; the
            // helper publishes a process-local fallback when it is not, so a
            // FullSyncLease can return at a stable DRAINING/head-failure
            // boundary instead of waiting forever on drainerActive.
            persistMutationPoolDiagnostic(e);
            MUTATION_ADMISSION.finishDrainHeadFailure(this);
            return;
        }
    }

    private boolean validateMutationEntryOrder(List<File> entries) {
        long previous = -1L;
        for (File entry : entries) {
            Long sequence = trySequenceOf(entry);
            if (sequence == null) {
                recordMutationHeadFailure(
                    entry,
                    new IOException("malformed deferred mutation entry name")
                );
                return false;
            }
            try {
                readAndValidateMutationState(entry);
            } catch (Exception e) {
                recordMutationHeadFailure(entry, e);
                return false;
            }
            if (sequence <= previous) {
                recordMutationHeadFailure(
                    entry,
                    new IOException("duplicate or out-of-order mutation sequence")
                );
                return false;
            }
            previous = sequence;
        }
        return true;
    }

    private void recordMutationHeadFailure(File entry, Exception failure) {
        try {
            JSONObject state = readMutationState(entry);
            state.put(
                "attempt_count",
                state.optInt("attempt_count", 0) + 1
            );
            state.put("last_error", safeError(failure));
            IoUtils.writeAtomically(
                new File(entry, "state.json"),
                state.toString().getBytes(StandardCharsets.UTF_8)
            );
        } catch (Exception ignored) {
            // Keep the damaged entry in place.  If its state file cannot be
            // rewritten, fall back to a durable pool-level diagnostic when
            // possible.  The fallback publishes a process-local snapshot as
            // well, so the owning FullSyncLease still has a stable boundary.
            persistMutationPoolDiagnostic(failure);
        }
    }

    private void applyDeferredMutation(File entry) throws Exception {
        JSONObject state = readAndValidateMutationState(entry);
        DeferredMutationOperation operation =
            DeferredMutationOperation.valueOf(state.getString("operation"));
        String sceneName = requireSceneName(state.getString("scene"));
        synchronized (this) {
            switch (operation) {
                case PUT_SCENE:
                    byte[] bytes;
                    try (InputStream input = new AtomicFile(
                        new File(entry, "scene.json")
                    ).openRead()) {
                        bytes = IoUtils.readAllBytesLimited(input, MAX_SCENE_BYTES);
                    }
                    saveRawSceneSnapshotInternal(
                        validateRawSceneBytes(sceneName, bytes)
                    );
                    break;
                case DELETE_SCENE:
                    try {
                        deleteSceneInternal(sceneName);
                    } catch (IOException e) {
                        String message = e.getMessage();
                        if (message == null || !message.contains("does not exist")) {
                            throw e;
                        }
                        PROCESS_DELETION_INTENTS.record(sceneName);
                    }
                    break;
                case DELETE_SCENE_FOR_SYNC:
                    deleteSceneForSyncInternal(sceneName);
                    break;
                case REMOVE_LANGUAGE:
                    try {
                        removeLanguageInternal(
                            sceneName,
                            state.getString("language")
                        );
                    } catch (IllegalArgumentException e) {
                        String message = e.getMessage();
                        if (message == null
                            || !message.contains("does not contain language")) {
                            throw e;
                        }
                        // The formal commit may have succeeded before the
                        // process died while deleting this entry.  Removing
                        // an already absent language is idempotent.
                    }
                    break;
                default:
                    throw new IOException("unknown deferred mutation operation");
            }
        }
    }

    private static String safeError(Exception e) {
        String message = e.getMessage();
        return e.getClass().getSimpleName()
            + (message == null ? "" : ": " + message);
    }

    public MutationReceipt<ValidatedScene> importScene(InputStream input)
        throws Exception {
        ValidatedScene scene = validate(
            IoUtils.readAllBytesLimited(input, MAX_SCENE_BYTES)
        );
        MutationAdmission.ExternalAdmission admission =
            MUTATION_ADMISSION.beginExternalMutation(
                this,
                null
            );
        if (admission.deferred) {
            MutationReceipt<Void> deferred = persistDeferredAdmission(
                admission,
                DeferredMutation.put(scene.sceneName, scene.bytes)
            );
            return new MutationReceipt<>(
                deferred.disposition,
                deferred.sceneName,
                deferred.sequence,
                null
            );
        }
        try {
            saveInternal(scene);
            return MutationReceipt.committed(scene.sceneName, scene);
        } finally {
            admission.lease.close();
        }
    }

    /**
     * Enumerates formal Scene candidates in deterministic order.  Each file
     * is opened and read exactly once; the callback must consume or retain the
     * supplied snapshot before returning.  Invalid candidates remain visible
     * as a rejected callback rather than disappearing from the export.
     */
    public void forEachRawSceneCandidate(RawSceneCandidateConsumer consumer)
        throws Exception {
        if (consumer == null) {
            throw new IllegalArgumentException("consumer is null");
        }
        List<String> names = listFormalSceneNamesStrict();
        for (String sceneName : names) {
            RawSceneSnapshot snapshot = null;
            Exception validationError = null;
            try {
                snapshot = readRawSceneSnapshot(sceneName);
            } catch (RawSceneFailure e) {
                validationError = e;
            }
            // Consumer exceptions are intentionally outside the catch above:
            // a pipe/write failure must terminate the whole export, not turn
            // into a second REJECTED record for this file.
            consumer.onCandidate(sceneName, snapshot, validationError);
        }
    }

    public List<String> listFormalSceneNames() {
        List<String> names = new ArrayList<>();
        for (String fileName : candidateFileNames()) {
            try {
                names.add(sceneNameForFileName(fileName));
            } catch (IllegalArgumentException ignored) {
                // candidateFileNames already filters this; keep the storage
                // boundary defensive against a concurrent rename.
            }
        }
        Collections.sort(names);
        return names;
    }

    /**
     * Strict enumeration seam for sync/export.  Missing storage is an empty
     * mirror; an existing non-directory or failed listing is a typed I/O
     * failure, never a silently empty snapshot.
     */
    public List<String> listFormalSceneNamesStrict()
        throws RawSceneFailure {
        if (!sceneDirectory.exists()) {
            return Collections.emptyList();
        }
        if (!sceneDirectory.isDirectory()) {
            throw new RawSceneFailure(
                RawSceneFailureKind.READ,
                new IOException("Scene directory is not a directory")
            );
        }
        File[] files = sceneDirectory.listFiles();
        if (files == null) {
            throw new RawSceneFailure(
                RawSceneFailureKind.READ,
                new IOException("could not enumerate Scene directory")
            );
        }
        List<String> names = new ArrayList<>();
        for (File file : files) {
            if (!file.isFile() || !isSceneFileName(file.getName())) {
                continue;
            }
            try {
                names.add(sceneNameForFileName(file.getName()));
            } catch (IllegalArgumentException ignored) {
                // Invalid candidate names are not formal identities.
            }
        }
        Collections.sort(names);
        return names;
    }

    /** Reads and validates one candidate while preserving its original bytes. */
    public RawSceneSnapshot readRawSceneSnapshot(String sceneName)
        throws RawSceneFailure {
        sceneName = requireSceneName(sceneName);
        File file = new File(sceneDirectory, fileNameForScene(sceneName));
        if (!IoUtils.atomicFileExists(file)) {
            throw new RawSceneFailure(
                RawSceneFailureKind.READ,
                new IOException("scene file does not exist")
            );
        }
        byte[] raw;
        try {
            try (InputStream input = new AtomicFile(file).openRead()) {
                raw = IoUtils.readAllBytesLimited(input, MAX_SCENE_BYTES);
            }
        } catch (IoUtils.InputLimitExceededException e) {
            throw new RawSceneFailure(RawSceneFailureKind.TOO_LARGE, e);
        } catch (IOException e) {
            throw new RawSceneFailure(RawSceneFailureKind.READ, e);
        }
        return validateRawSnapshot(sceneName, raw);
    }

    /** Validates one bounded external body while preserving its exact bytes. */
    public RawSceneSnapshot validateRawSceneBytes(
        String expectedSceneName,
        byte[] rawBytes
    ) throws RawSceneFailure {
        expectedSceneName = requireSceneName(expectedSceneName);
        return validateRawSnapshot(expectedSceneName, rawBytes);
    }

    /** Atomically publishes a previously validated raw snapshot without normalization. */
    public synchronized MutationReceipt<Void> saveRawSceneSnapshot(
        RawSceneSnapshot snapshot
    )
        throws IOException {
        if (snapshot == null) {
            throw new IllegalArgumentException("snapshot is null");
        }
        String sceneName = requireSceneName(snapshot.sceneName);
        if (snapshot.bytes == null
            || snapshot.bytes.length < 1
            || snapshot.bytes.length > MAX_SCENE_BYTES) {
            throw new IOException("raw Scene body length is outside the limit");
        }
        MutationAdmission.ExternalAdmission admission =
            MUTATION_ADMISSION.beginExternalMutation(this, null);
        if (admission.deferred) {
            return persistDeferredAdmission(
                admission,
                DeferredMutation.put(sceneName, snapshot.bytes)
            );
        }
        try {
            saveRawSceneSnapshotInternal(snapshot);
            return MutationReceipt.committed(sceneName, null);
        } finally {
            admission.lease.close();
        }
    }

    public enum MutationDisposition {
        COMMITTED,
        DEFERRED
    }

    /** Explicit result for every external Scene mutation. */
    public static final class MutationReceipt<T> {
        public final MutationDisposition disposition;
        public final String sceneName;
        public final Long sequence;
        public final T value;

        private MutationReceipt(
            MutationDisposition disposition,
            String sceneName,
            Long sequence,
            T value
        ) {
            this.disposition = disposition;
            this.sceneName = sceneName;
            this.sequence = sequence;
            this.value = value;
        }

        private static <T> MutationReceipt<T> committed(
            String sceneName,
            T value
        ) {
            return new MutationReceipt<>(
                MutationDisposition.COMMITTED,
                sceneName,
                null,
                value
            );
        }

        private static <T> MutationReceipt<T> deferred(
            String sceneName,
            long sequence
        ) {
            return new MutationReceipt<>(
                MutationDisposition.DEFERRED,
                sceneName,
                sequence,
                null
            );
        }
    }

    public static final class DeferredMutationPoolFullException
        extends IOException {
        private static final long serialVersionUID = 1L;

        DeferredMutationPoolFullException(String message) {
            super(message);
        }
    }

    private enum DeferredMutationOperation {
        PUT_SCENE,
        DELETE_SCENE,
        DELETE_SCENE_FOR_SYNC,
        REMOVE_LANGUAGE
    }

    private static final class DeferredMutation {
        private final DeferredMutationOperation operation;
        private final String sceneName;
        private final String language;
        private final byte[] payload;

        private DeferredMutation(
            DeferredMutationOperation operation,
            String sceneName,
            String language,
            byte[] payload
        ) {
            this.operation = operation;
            this.sceneName = sceneName;
            this.language = language;
            this.payload = payload;
        }

        private static DeferredMutation put(
            String sceneName,
            byte[] payload
        ) {
            return new DeferredMutation(
                DeferredMutationOperation.PUT_SCENE,
                sceneName,
                null,
                payload
            );
        }

        private static DeferredMutation delete(String sceneName) {
            return new DeferredMutation(
                DeferredMutationOperation.DELETE_SCENE,
                sceneName,
                null,
                null
            );
        }

        private static DeferredMutation deleteForSync(String sceneName) {
            return new DeferredMutation(
                DeferredMutationOperation.DELETE_SCENE_FOR_SYNC,
                sceneName,
                null,
                null
            );
        }

        private static DeferredMutation removeLanguage(
            String sceneName,
            String language
        ) {
            return new DeferredMutation(
                DeferredMutationOperation.REMOVE_LANGUAGE,
                sceneName,
                language,
                null
            );
        }
    }

    private synchronized void saveRawSceneSnapshotInternal(
        RawSceneSnapshot snapshot
    ) throws IOException {
        if (snapshot == null) {
            throw new IllegalArgumentException("snapshot is null");
        }
        String sceneName = requireSceneName(snapshot.sceneName);
        if (snapshot.bytes == null
            || snapshot.bytes.length < 1
            || snapshot.bytes.length > MAX_SCENE_BYTES) {
            throw new IOException("raw Scene body length is outside the limit");
        }
        ensureDirectories();
        IoUtils.writeAtomically(
            new File(sceneDirectory, fileNameForScene(sceneName)),
            snapshot.bytes
        );
        clearSceneDeletionIntent(sceneName);
    }

    public ValidatedScene validate(byte[] sourceBytes) throws Exception {
        if (sourceBytes == null || sourceBytes.length == 0) {
            throw new IllegalArgumentException("scene file is empty");
        }
        if (sourceBytes.length > MAX_SCENE_BYTES) {
            throw new IllegalArgumentException("scene file exceeds 32 MiB");
        }

        String source = decodeStrictUtf8(sourceBytes);
        if (!source.isEmpty() && source.charAt(0) == '\uFEFF') {
            source = source.substring(1);
        }

        JSONObject json = new JSONObject(source);
        schemaValidator.validate(json);

        String sceneName = requireSceneName(json.getString("scene"));
        byte[] normalized = serializeScene(json);
        if (normalized.length > MAX_SCENE_BYTES) {
            throw new IllegalArgumentException("normalized scene file exceeds 32 MiB");
        }
        return new ValidatedScene(
            sceneName,
            normalized,
            collectLanguages(json)
        );
    }

    private RawSceneSnapshot validateRawSnapshot(
        String expectedSceneName,
        byte[] rawBytes
    ) throws RawSceneFailure {
        if (rawBytes == null || rawBytes.length == 0) {
            throw new RawSceneFailure(RawSceneFailureKind.EMPTY);
        }
        if (rawBytes.length > MAX_SCENE_BYTES) {
            throw new RawSceneFailure(RawSceneFailureKind.TOO_LARGE);
        }
        String source;
        try {
            source = decodeStrictUtf8(rawBytes);
        } catch (CharacterCodingException e) {
            throw new RawSceneFailure(RawSceneFailureKind.INVALID_UTF8, e);
        }
        if (!source.isEmpty() && source.charAt(0) == '\uFEFF') {
            source = source.substring(1);
        }
        JSONObject json;
        try {
            json = new JSONObject(source);
        } catch (org.json.JSONException e) {
            throw new RawSceneFailure(RawSceneFailureKind.INVALID_JSON, e);
        }
        try {
            schemaValidator.validate(json);
        } catch (JsonSchemaValidator.ValidationException e) {
            throw new RawSceneFailure(RawSceneFailureKind.SCHEMA_INVALID, e);
        }
        String sceneName;
        try {
            sceneName = requireSceneName(json.getString("scene"));
        } catch (org.json.JSONException e) {
            throw new RawSceneFailure(RawSceneFailureKind.INVALID_JSON, e);
        } catch (IllegalArgumentException e) {
            throw new RawSceneFailure(RawSceneFailureKind.IDENTITY_MISMATCH, e);
        }
        if (!expectedSceneName.equals(sceneName)) {
            throw new RawSceneFailure(
                RawSceneFailureKind.IDENTITY_MISMATCH,
                new IllegalArgumentException(
                    "scene field does not match requested SceneName"
                )
            );
        }
        return new RawSceneSnapshot(
            sceneName,
            rawBytes,
            collectLanguages(json)
        );
    }

    private static String decodeStrictUtf8(byte[] bytes)
        throws CharacterCodingException {
        return StandardCharsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(bytes))
            .toString();
    }

    public synchronized MutationReceipt<Void> save(ValidatedScene scene)
        throws IOException {
        if (scene == null) {
            throw new IllegalArgumentException("scene is null");
        }
        String sceneName = requireSceneName(scene.sceneName);
        if (scene.bytes == null
            || scene.bytes.length < 1
            || scene.bytes.length > MAX_SCENE_BYTES) {
            throw new IOException("Scene body length is outside the limit");
        }
        MutationAdmission.ExternalAdmission admission =
            MUTATION_ADMISSION.beginExternalMutation(this, null);
        if (admission.deferred) {
            return persistDeferredAdmission(
                admission,
                DeferredMutation.put(sceneName, scene.bytes.clone())
            );
        }
        try {
            saveInternal(scene);
            return MutationReceipt.committed(sceneName, null);
        } finally {
            admission.lease.close();
        }
    }

    private synchronized void saveInternal(ValidatedScene scene)
        throws IOException {
        if (scene == null) {
            throw new IllegalArgumentException("scene is null");
        }
        ensureDirectories();
        String sceneName = requireSceneName(scene.sceneName);
        String fileName = fileNameForScene(sceneName);
        ValidatedScene existing = null;
        try {
            existing = readValidScene(fileName);
        } catch (Exception ignored) {
            // A newly validated import is allowed to repair a corrupt file.
        }
        if (existing != null && !existing.sceneName.equals(sceneName)) {
            throw new IOException(
                "scene name collides with existing " + existing.sceneName
                    + " at " + fileName
            );
        }
        IoUtils.writeAtomically(new File(sceneDirectory, fileName), scene.bytes);
        clearSceneDeletionIntent(sceneName);
    }

    public synchronized MutationReceipt<ValidatedScene> removeLanguage(
        String sceneName,
        String language
    )
        throws Exception {
        sceneName = requireSceneName(sceneName);
        if (language == null || language.isEmpty()) {
            throw new IllegalArgumentException("language key is empty");
        }
        MutationAdmission.ExternalAdmission admission =
            MUTATION_ADMISSION.beginExternalMutation(this, null);
        if (admission.deferred) {
            MutationReceipt<Void> deferred = persistDeferredAdmission(
                admission,
                DeferredMutation.removeLanguage(sceneName, language)
            );
            return new MutationReceipt<>(
                deferred.disposition,
                deferred.sceneName,
                deferred.sequence,
                null
            );
        }
        try {
            ValidatedScene updated = removeLanguageInternal(sceneName, language);
            return MutationReceipt.committed(sceneName, updated);
        } finally {
            admission.lease.close();
        }
    }

    private synchronized ValidatedScene removeLanguageInternal(
        String sceneName,
        String language
    )
        throws Exception {
        sceneName = requireSceneName(sceneName);
        if (language == null || language.isEmpty()) {
            throw new IllegalArgumentException("language key is empty");
        }

        ValidatedScene scene = readValidSceneByName(sceneName);
        if (scene == null || isSceneDeleted(sceneName)) {
            throw new IOException("scene file no longer exists");
        }

        JSONObject json = new JSONObject(new String(
            scene.bytes,
            StandardCharsets.UTF_8
        ));
        boolean removed = false;
        removed |= removeObjectKey(json.optJSONObject("translated"), language);
        removed |= removeObjectKey(json.optJSONObject("provider"), language);
        removed |= removeObjectKey(json.optJSONObject("model"), language);
        removed |= removeObjectKey(json.optJSONObject("summary"), language);
        removed |= removeTranslationLanguage(json.optJSONArray("scene_items"), language);
        if (!removed) {
            throw new IllegalArgumentException(
                "scene does not contain language key " + language
            );
        }

        ValidatedScene updated = validate(serializeScene(json));
        if (!updated.sceneName.equals(sceneName)) {
            throw new IOException("scene name changed while removing language");
        }
        saveInternal(updated);
        return updated;
    }

    public synchronized MutationReceipt<Void> deleteScene(String sceneName)
        throws IOException {
        sceneName = requireSceneName(sceneName);
        MutationAdmission.ExternalAdmission admission =
            MUTATION_ADMISSION.beginExternalMutation(this, null);
        if (admission.deferred) {
            return persistDeferredAdmission(
                admission,
                DeferredMutation.delete(sceneName)
            );
        }
        try {
            deleteSceneInternal(sceneName);
            return MutationReceipt.committed(sceneName, null);
        } finally {
            admission.lease.close();
        }
    }

    private synchronized void deleteSceneInternal(String sceneName)
        throws IOException {
        sceneName = requireSceneName(sceneName);
        File file = new File(sceneDirectory, fileNameForScene(sceneName));
        if (PROCESS_DELETION_INTENTS.contains(sceneName)
            && !IoUtils.atomicFileExists(file)) {
            return;
        }
        if (!IoUtils.atomicFileExists(file)) {
            throw new IOException("scene file does not exist");
        }
        new AtomicFile(file).delete();
        if (IoUtils.atomicFileExists(file)) {
            throw new IOException("could not delete scene file");
        }
        PROCESS_DELETION_INTENTS.record(sceneName);
    }

    /** Deletes a game-side mirror without creating an HET delete intent. */
    public synchronized MutationReceipt<Void> deleteSceneForSync(String sceneName)
        throws IOException {
        sceneName = requireSceneName(sceneName);
        MutationAdmission.ExternalAdmission admission =
            MUTATION_ADMISSION.beginExternalMutation(this, null);
        if (admission.deferred) {
            return persistDeferredAdmission(
                admission,
                DeferredMutation.deleteForSync(sceneName)
            );
        }
        try {
            deleteSceneForSyncInternal(sceneName);
            return MutationReceipt.committed(sceneName, null);
        } finally {
            admission.lease.close();
        }
    }

    private synchronized void deleteSceneForSyncInternal(String sceneName)
        throws IOException {
        sceneName = requireSceneName(sceneName);
        File file = new File(sceneDirectory, fileNameForScene(sceneName));
        if (!IoUtils.atomicFileExists(file)) {
            return;
        }
        new AtomicFile(file).delete();
        if (IoUtils.atomicFileExists(file)) {
            throw new IOException("could not delete scene file");
        }
    }

    public synchronized MutationReceipt<Void> acceptIncoming(
        File temporaryFile,
        String expectedSceneName
    )
        throws Exception {
        if (temporaryFile == null) {
            throw new IllegalArgumentException("temporary Scene file is null");
        }
        ValidatedScene scene;
        try (InputStream input = new java.io.FileInputStream(temporaryFile)) {
            scene = validate(IoUtils.readAllBytesLimited(input, MAX_SCENE_BYTES));
        }
        expectedSceneName = requireSceneName(expectedSceneName);
        if (!scene.sceneName.equals(expectedSceneName)) {
            throw new IllegalArgumentException(
                "scene field maps to " + scene.sceneName
                    + ", not requested SceneName " + expectedSceneName
            );
        }
        MutationAdmission.ExternalAdmission admission =
            MUTATION_ADMISSION.beginExternalMutation(this, null);
        MutationReceipt<Void> receipt;
        if (admission.deferred) {
            receipt = persistDeferredAdmission(
                admission,
                DeferredMutation.put(scene.sceneName, scene.bytes)
            );
        } else {
            try {
                saveInternal(scene);
                receipt = MutationReceipt.committed(scene.sceneName, null);
            } finally {
                admission.lease.close();
            }
        }
        // The source is disposable only after either the formal write or the
        // durable pool entry has succeeded.
        if (!temporaryFile.delete() && temporaryFile.exists()) {
            // The receipt above is authoritative.  A cleanup failure must not
            // turn a committed/deferred mutation into UNKNOWN and cause the
            // Provider to retry it as a second write.
            Log.w(
                TAG,
                "Could not delete accepted temporary Scene " + temporaryFile
            );
        }
        return receipt;
    }

    public File createIncomingFile() throws IOException {
        ensureDirectories();
        return File.createTempFile("scene-", ".json.tmp", incomingDirectory);
    }

    public List<ValidatedScene> listValidScenes() {
        Set<String> names = candidateFileNames();
        List<String> sortedNames = new ArrayList<>(names);
        Collections.sort(sortedNames);

        List<ValidatedScene> scenes = new ArrayList<>();
        for (String name : sortedNames) {
            try {
                ValidatedScene scene = readValidScene(name);
                if (scene != null) {
                    scenes.add(scene);
                }
            } catch (Exception ignored) {
                // Invalid files are deliberately not exposed or exported.
            }
        }
        return scenes;
    }

    public List<SceneInfo> listSceneInfos() {
        Set<String> names = candidateFileNames();
        List<String> sortedNames = new ArrayList<>(names);
        Collections.sort(sortedNames);

        List<SceneInfo> scenes = new ArrayList<>();
        for (String name : sortedNames) {
            try {
                ValidatedScene scene = readValidScene(name);
                if (scene != null) {
                    scenes.add(new SceneInfo(scene));
                }
            } catch (Exception ignored) {
                // Invalid files are deliberately not exposed in the UI.
            }
        }
        return scenes;
    }

    public List<String> listValidSceneNames() {
        List<SceneInfo> scenes = listSceneInfos();
        List<String> names = new ArrayList<>(scenes.size());
        for (SceneInfo scene : scenes) {
            names.add(scene.sceneName);
        }
        return names;
    }

    /** Returns process-local delete intents; no filesystem marker is used. */
    public List<String> listDeletedSceneNames() {
        return PROCESS_DELETION_INTENTS.names();
    }

    /** Captures name/token pairs once at a sync-cycle boundary. */
    public Map<String, DeletionIntent> snapshotDeletionIntents() {
        Map<String, DeletionIntent> snapshot = new HashMap<>();
        for (Map.Entry<String, SceneDeletionIntentRegistry.Intent> entry
            : PROCESS_DELETION_INTENTS.snapshot().entrySet()) {
            snapshot.put(
                entry.getKey(),
                new DeletionIntent(entry.getKey(), entry.getValue().token)
            );
        }
        return Collections.unmodifiableMap(snapshot);
    }

    /** Returns whether the current HET process still owns a delete intent. */
    public boolean hasSceneDeletionIntent(String sceneName) {
        sceneName = requireSceneName(sceneName);
        return PROCESS_DELETION_INTENTS.contains(sceneName);
    }

    /** Clears an intent only after the peer has acknowledged DELETE_SCENE. */
    private void clearSceneDeletionIntent(String sceneName) {
        sceneName = requireSceneName(sceneName);
        PROCESS_DELETION_INTENTS.clear(sceneName);
    }

    /** Clears only the intent token captured by a completed operation. */
    private boolean clearMatchingDeletionIntentInternal(
        String sceneName,
        long token
    ) {
        sceneName = requireSceneName(sceneName);
        return PROCESS_DELETION_INTENTS.clearMatching(sceneName, token);
    }

    public synchronized ValidatedScene readValidSceneByName(
        String sceneName
    ) throws Exception {
        sceneName = requireSceneName(sceneName);

        String fileName = fileNameForScene(sceneName);
        if (isSceneDeleted(sceneName)) {
            return null;
        }

        ValidatedScene scene = readValidScene(fileName);
        if (scene == null || !sceneName.equals(scene.sceneName)) {
            return null;
        }
        return scene;
    }

    public File getValidSceneFileByName(String sceneName) {
        sceneName = requireSceneName(sceneName);
        if (isSceneDeleted(sceneName)) {
            return null;
        }
        try {
            ValidatedScene scene = readValidSceneByName(sceneName);
            return scene == null
                ? null
                : new File(sceneDirectory, fileNameForScene(sceneName));
        } catch (Exception ignored) {
            return null;
        }
    }

    private ValidatedScene readValidScene(String fileName) throws Exception {
        if (!isSceneFileName(fileName)) {
            return null;
        }

        File file = new File(sceneDirectory, fileName);
        AtomicFile atomicFile = new AtomicFile(file);
        if (!IoUtils.atomicFileExists(file)) {
            return null;
        }

        ValidatedScene scene;
        try (InputStream input = atomicFile.openRead()) {
            scene = validate(IoUtils.readAllBytesLimited(input, MAX_SCENE_BYTES));
        }
        return scene.sceneName.equals(sceneNameForFileName(fileName)) ? scene : null;
    }

    private Set<String> candidateFileNames() {
        Set<String> names = new HashSet<>();
        File[] files = sceneDirectory.listFiles();
        if (files == null) {
            return names;
        }

        for (File file : files) {
            String name = file.getName();
            if (file.isFile() && isSceneFileName(name)) {
                names.add(name);
            }
        }
        for (String deletedSceneName : listDeletedSceneNames()) {
            names.remove(fileNameForScene(deletedSceneName));
        }
        return names;
    }

    private boolean isSceneDeleted(String sceneName) {
        return PROCESS_DELETION_INTENTS.contains(sceneName);
    }

    private static List<String> collectLanguages(JSONObject json) {
        Set<String> languages = new HashSet<>();
        addObjectKeys(json.optJSONObject("translated"), languages);
        addObjectKeys(json.optJSONObject("provider"), languages);
        addObjectKeys(json.optJSONObject("model"), languages);
        addObjectKeys(json.optJSONObject("summary"), languages);
        collectTranslationLanguages(json.optJSONArray("scene_items"), languages);

        List<String> sorted = new ArrayList<>(languages);
        Collections.sort(sorted);
        return sorted;
    }

    private static void addObjectKeys(JSONObject object, Set<String> output) {
        if (object == null) {
            return;
        }
        Iterator<String> keys = object.keys();
        while (keys.hasNext()) {
            String key = keys.next();
            if (!key.isEmpty()) {
                output.add(key);
            }
        }
    }

    private static void collectTranslationLanguages(
        Object value,
        Set<String> output
    ) {
        if (value instanceof JSONObject) {
            JSONObject object = (JSONObject) value;
            addObjectKeys(object.optJSONObject("translations"), output);
            Iterator<String> keys = object.keys();
            while (keys.hasNext()) {
                collectTranslationLanguages(object.opt(keys.next()), output);
            }
        } else if (value instanceof JSONArray) {
            JSONArray array = (JSONArray) value;
            for (int index = 0; index < array.length(); index++) {
                collectTranslationLanguages(array.opt(index), output);
            }
        }
    }

    private static boolean removeObjectKey(JSONObject object, String key) {
        return object != null && object.remove(key) != null;
    }

    private static boolean removeTranslationLanguage(Object value, String language) {
        boolean removed = false;
        if (value instanceof JSONObject) {
            JSONObject object = (JSONObject) value;
            removed |= removeObjectKey(
                object.optJSONObject("translations"),
                language
            );
            Iterator<String> keys = object.keys();
            while (keys.hasNext()) {
                removed |= removeTranslationLanguage(
                    object.opt(keys.next()),
                    language
                );
            }
        } else if (value instanceof JSONArray) {
            JSONArray array = (JSONArray) value;
            for (int index = 0; index < array.length(); index++) {
                removed |= removeTranslationLanguage(array.opt(index), language);
            }
        }
        return removed;
    }

    private void ensureDirectories() throws IOException {
        IoUtils.ensureDirectory(sceneDirectory);
        IoUtils.ensureDirectory(incomingDirectory);
    }

    private static Context requireContext(Context context) {
        if (context == null) {
            throw new IllegalArgumentException("context cannot be null");
        }
        return context;
    }

    private static JSONObject loadSchema(Context context) {
        try (InputStream schemaInput = context.getAssets().open(SCHEMA_ASSET_PATH)) {
            return new JSONObject(new String(
                IoUtils.readAllBytesLimited(schemaInput, MAX_SCENE_BYTES),
                StandardCharsets.UTF_8
            ));
        } catch (Exception e) {
            throw new IllegalStateException("could not load scene schema", e);
        }
    }

    /** Serializes a Scene using the native PrettyWriter contract. */
    public static byte[] serializeScene(JSONObject scene) {
        if (scene == null) {
            throw new IllegalArgumentException("scene is null");
        }
        StringBuilder output = new StringBuilder();
        appendJsonValue(scene, output, 0);
        return output.toString().getBytes(StandardCharsets.UTF_8);
    }

    private static void appendJsonValue(
        Object value,
        StringBuilder output,
        int depth
    ) {
        if (value == null || value == JSONObject.NULL) {
            output.append("null");
        } else if (value instanceof JSONObject) {
            appendJsonObject((JSONObject) value, output, depth);
        } else if (value instanceof JSONArray) {
            appendJsonArray((JSONArray) value, output, depth);
        } else if (value instanceof String || value instanceof Character) {
            appendJsonString(value.toString(), output);
        } else if (value instanceof Boolean || value instanceof Number) {
            output.append(value.toString());
        } else {
            throw new IllegalArgumentException(
                "unsupported Scene JSON value: " + value.getClass().getName()
            );
        }
    }

    private static void appendJsonObject(
        JSONObject object,
        StringBuilder output,
        int depth
    ) {
        List<String> keys = new ArrayList<>();
        Iterator<String> iterator = object.keys();
        while (iterator.hasNext()) {
            keys.add(iterator.next());
        }
        if (keys.isEmpty()) {
            output.append("{}");
            return;
        }
        output.append("{\n");
        for (int index = 0; index < keys.size(); index++) {
            if (index > 0) {
                output.append(",\n");
            }
            appendIndent(output, depth + 1);
            appendJsonString(keys.get(index), output);
            output.append(": ");
            appendJsonValue(object.opt(keys.get(index)), output, depth + 1);
        }
        output.append('\n');
        appendIndent(output, depth);
        output.append('}');
    }

    private static void appendJsonArray(
        JSONArray array,
        StringBuilder output,
        int depth
    ) {
        if (array.length() == 0) {
            output.append("[]");
            return;
        }
        output.append("[\n");
        for (int index = 0; index < array.length(); index++) {
            if (index > 0) {
                output.append(",\n");
            }
            appendIndent(output, depth + 1);
            appendJsonValue(array.opt(index), output, depth + 1);
        }
        output.append('\n');
        appendIndent(output, depth);
        output.append(']');
    }

    private static void appendIndent(StringBuilder output, int depth) {
        for (int index = 0; index < depth * 4; index++) {
            output.append(' ');
        }
    }

    private static void appendJsonString(String value, StringBuilder output) {
        output.append('"');
        for (int index = 0; index < value.length(); index++) {
            char character = value.charAt(index);
            switch (character) {
                case '"':
                    output.append("\\\"");
                    break;
                case '\\':
                    output.append("\\\\");
                    break;
                case '\b':
                    output.append("\\b");
                    break;
                case '\t':
                    output.append("\\t");
                    break;
                case '\n':
                    output.append("\\n");
                    break;
                case '\f':
                    output.append("\\f");
                    break;
                case '\r':
                    output.append("\\r");
                    break;
                default:
                    if (character < 0x20) {
                        output.append("\\u");
                        String hex = Integer.toHexString(character).toUpperCase();
                        for (int padding = hex.length(); padding < 4; padding++) {
                            output.append('0');
                        }
                        output.append(hex);
                    } else {
                        output.append(character);
                    }
            }
        }
        output.append('"');
    }

    public static String requireSceneName(String sceneName) {
        if (!isValidSceneName(sceneName)) {
            throw new IllegalArgumentException(
                "scene name must be a bare ASCII identity without a suffix"
            );
        }
        return sceneName;
    }

    public static boolean isValidSceneName(String sceneName) {
        if (sceneName == null || sceneName.isEmpty()) {
            return false;
        }
        byte[] utf8 = sceneName.getBytes(StandardCharsets.UTF_8);
        if (utf8.length > MAX_SCENE_NAME_BYTES) {
            return false;
        }
        for (int index = 0; index < sceneName.length(); index++) {
            char value = sceneName.charAt(index);
            boolean allowed = (value >= 'a' && value <= 'z')
                || (value >= 'A' && value <= 'Z')
                || (value >= '0' && value <= '9')
                || value == '_'
                || value == '-';
            if (!allowed) {
                return false;
            }
        }
        return true;
    }

    private static boolean deleteRecursively(File file) {
        if (file == null || !file.exists()) {
            return true;
        }
        if (file.isDirectory()) {
            File[] children = file.listFiles();
            if (children == null) {
                return false;
            }
            for (File child : children) {
                if (!deleteRecursively(child)) {
                    return false;
                }
            }
        }
        return !file.exists() || file.delete();
    }

    public static String fileNameForScene(String sceneName) {
        requireSceneName(sceneName);
        String fileName = sceneName + ".json";
        if (fileName.getBytes(StandardCharsets.UTF_8).length > MAX_FILE_NAME_BYTES) {
            throw new IllegalArgumentException("scene-derived file name is too long");
        }
        return fileName;
    }

    public static String sceneNameForFileName(String fileName) {
        if (!isSceneFileName(fileName)) {
            throw new IllegalArgumentException("invalid scene file name");
        }
        return requireSceneName(fileName.substring(0, fileName.length() - 5));
    }

    /** Validates a formal on-disk filename at a filesystem/provider boundary. */
    public static boolean isSceneFileName(String fileName) {
        if (fileName == null
            || !fileName.endsWith(".json")
            || fileName.length() > MAX_FILE_NAME_BYTES) {
            return false;
        }
        String sceneName = fileName.substring(0, fileName.length() - 5);
        return isValidSceneName(sceneName)
            && fileName.equals(fileNameForScene(sceneName));
    }
}
