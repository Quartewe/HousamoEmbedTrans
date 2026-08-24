package com.quarty.housamoembedtrans.scene.sync;

import com.quarty.housamoembedtrans.bridge.SceneSyncWireCodec;
import com.quarty.housamoembedtrans.scene.store.SceneStore;
import com.quarty.housamoembedtrans.scene.store.PendingSceneApplyStore;
import com.quarty.housamoembedtrans.scene.store.SceneDigest;
import com.quarty.housamoembedtrans.translation.IGameScenePort;

import android.os.ParcelFileDescriptor;
import android.os.RemoteException;
import android.util.Log;

import java.io.IOException;
import java.io.InputStream;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.RejectedExecutionHandler;
import java.util.concurrent.Semaphore;
import java.util.concurrent.SynchronousQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * One bounded full-sync data-plane operation.
 *
 * <p>The export decoder is only a coordinator: it validates framing, reads
 * each SCENE body into one bounded byte array, and hands that array to one of
 * the operation workers.  Workers perform validation, HET reads, conflict
 * resolution, and game applies.  A semaphore limits the number of bodies (or
 * HET-only reads) in flight to the configured worker count.</p>
 */
public final class SceneSyncOperation implements AutoCloseable {
    private static final String TAG = "HET.SceneSyncOperation";

    public enum FailureKind {
        EXPORT_UNAVAILABLE,
        EXPORT_PROTOCOL,
        IDENTITY_LIMIT,
        HET_READ,
        APPLY_REJECTED,
        APPLY_FAILED,
        POLICY_FAILED,
        INTERRUPTED,
        INTERNAL
    }

    public enum Direction {
        GAME_TO_HET,
        HET_TO_GAME,
        BIDIRECTIONAL,
        LOCAL,
        UNKNOWN
    }

    public enum SceneStatus {
        PROCESSED,
        DELETED,
        NEEDS_ATTENTION,
        NOT_PROCESSED
    }

    /** UI-safe row: no hashes, wire offsets, exceptions, or JSON bodies. */
    public static final class SceneSummary {
        public final String sceneName;
        public final Direction direction;
        public final SceneStatus status;

        private SceneSummary(
            String sceneName,
            Direction direction,
            SceneStatus status
        ) {
            this.sceneName = sceneName;
            this.direction = direction;
            this.status = status;
        }
    }

    public static final class Result {
        public final boolean success;
        public final FailureKind failureKind;
        public final Set<String> gameSceneNames;
        public final Set<String> hetOnlySceneNames;
        public final Set<String> deletedSceneNames;
        public final Set<String> rejectedSceneNames;
        public final Set<String> blockedSceneNames;
        public final List<SceneSummary> sceneSummaries;
        private final Map<String, Direction> sceneDirections;

        private Result(
            boolean success,
            FailureKind failureKind,
            Set<String> gameSceneNames,
            Set<String> hetOnlySceneNames,
            Set<String> deletedSceneNames,
            Set<String> rejectedSceneNames,
            Set<String> blockedSceneNames,
            Map<String, Direction> sceneDirections
        ) {
            this.success = success;
            this.failureKind = failureKind;
            this.gameSceneNames = immutableCopy(gameSceneNames);
            this.hetOnlySceneNames = immutableCopy(hetOnlySceneNames);
            this.deletedSceneNames = immutableCopy(deletedSceneNames);
            this.rejectedSceneNames = immutableCopy(rejectedSceneNames);
            this.blockedSceneNames = immutableCopy(blockedSceneNames);
            this.sceneDirections = immutableMapCopy(sceneDirections);
            this.sceneSummaries = buildSceneSummaries();
        }

        private static Set<String> immutableCopy(Set<String> values) {
            synchronized (values) {
                return Collections.unmodifiableSet(new HashSet<>(values));
            }
        }

        private static Map<String, Direction> immutableMapCopy(
            Map<String, Direction> values
        ) {
            synchronized (values) {
                return Collections.unmodifiableMap(new HashMap<>(values));
            }
        }

        private List<SceneSummary> buildSceneSummaries() {
            Set<String> all = new HashSet<>();
            all.addAll(gameSceneNames);
            all.addAll(hetOnlySceneNames);
            all.addAll(deletedSceneNames);
            all.addAll(rejectedSceneNames);
            all.addAll(blockedSceneNames);
            List<String> sorted = new ArrayList<>(all);
            Collections.sort(sorted);
            List<SceneSummary> summaries = new ArrayList<>(sorted.size());
            for (String sceneName : sorted) {
                Direction direction = sceneDirections.get(sceneName);
                if (direction == null) {
                    direction = Direction.UNKNOWN;
                }
                SceneStatus status = deletedSceneNames.contains(sceneName)
                    ? SceneStatus.DELETED
                    : rejectedSceneNames.contains(sceneName)
                        ? SceneStatus.NOT_PROCESSED
                        : blockedSceneNames.contains(sceneName)
                            ? SceneStatus.NEEDS_ATTENTION
                            : SceneStatus.PROCESSED;
                summaries.add(
                    new SceneSummary(sceneName, direction, status)
                );
            }
            return Collections.unmodifiableList(summaries);
        }
    }

    /** Holds mutable sets until every operation worker has stopped. */
    private static final class PendingResult {
        private final boolean success;
        private final FailureKind failureKind;
        private final Set<String> gameSceneNames;
        private final Set<String> hetOnlySceneNames;
        private final Set<String> deletedSceneNames;
        private final Set<String> rejectedSceneNames;
        private final Set<String> blockedSceneNames;
        private final Map<String, Direction> sceneDirections;

        private PendingResult(
            boolean success,
            FailureKind failureKind,
            Set<String> gameSceneNames,
            Set<String> hetOnlySceneNames,
            Set<String> deletedSceneNames,
            Set<String> rejectedSceneNames,
            Set<String> blockedSceneNames,
            Map<String, Direction> sceneDirections
        ) {
            this.success = success;
            this.failureKind = failureKind;
            this.gameSceneNames = gameSceneNames;
            this.hetOnlySceneNames = hetOnlySceneNames;
            this.deletedSceneNames = deletedSceneNames;
            this.rejectedSceneNames = rejectedSceneNames;
            this.blockedSceneNames = blockedSceneNames;
            this.sceneDirections = sceneDirections;
        }

        private Result snapshot() {
            return new Result(
                success,
                failureKind,
                gameSceneNames,
                hetOnlySceneNames,
                deletedSceneNames,
                rejectedSceneNames,
                blockedSceneNames,
                sceneDirections
            );
        }
    }

    private static final class IdentityLimitException extends IOException {
        private static final long serialVersionUID = 1L;
    }

    private static final class OperationInterruptedException extends IOException {
        private static final long serialVersionUID = 1L;

        private OperationInterruptedException(String message, Throwable cause) {
            super(message, cause);
        }
    }

    /**
     * Owns the native sync hold acquired by one non-null export descriptor.
     *
     * <p>The game port generation is part of the lease identity.  Cleanup is
     * intentionally fail-open and idempotent: a successful complete/pre-cycle
     * policy publication consumes the lease, while every other exit resets
     * only the generation that admitted this export.</p>
     */
    private static final class ExportHoldLease implements AutoCloseable {
        private final IGameScenePort gamePort;
        private final long generation;
        private final Object lock = new Object();
        private boolean accepted;
        private boolean publicationInFlight;
        private boolean closeRequested;
        private boolean consumed;

        private ExportHoldLease(IGameScenePort gamePort, long generation) {
            this.gamePort = gamePort;
            this.generation = generation;
        }

        private void accept() {
            boolean resetNow = false;
            synchronized (lock) {
                accepted = true;
                // close() may win the race while Binder is still returning
                // the descriptor.  Late acceptance must still fail open.
                if (closeRequested && !publicationInFlight) {
                    accepted = false;
                    resetNow = true;
                }
            }
            if (resetNow) {
                resetNativeHold();
            }
        }

        /** Reserves the policy publication race against close(). */
        private boolean beginPublication() {
            synchronized (lock) {
                if (closeRequested) {
                    return false;
                }
                publicationInFlight = true;
                return true;
            }
        }

        /**
         * Completes a reserved publication without holding {@link #lock}
         * across the Binder transport.  A successful result wins over a
         * concurrent close and consumes the lease before close can reset it.
         */
        private void finishPublication(boolean published) {
            boolean complete = false;
            synchronized (lock) {
                if (!publicationInFlight) {
                    return;
                }
                if (published && accepted) {
                    accepted = false;
                    consumed = true;
                    complete = true;
                }
                publicationInFlight = false;
                lock.notifyAll();
            }
            if (complete) {
                completeNativeHoldBookkeeping();
            }
        }

        @Override
        public void close() {
            boolean reset = false;
            boolean interrupted = false;
            synchronized (lock) {
                closeRequested = true;
                while (publicationInFlight) {
                    try {
                        lock.wait();
                    } catch (InterruptedException e) {
                        interrupted = true;
                    }
                }
                if (accepted && !consumed) {
                    accepted = false;
                    reset = true;
                }
            }
            if (reset) {
                resetNativeHold();
            }
            if (interrupted) {
                Thread.currentThread().interrupt();
            }
        }

        private void completeNativeHoldBookkeeping() {
            try {
                gamePort.completeSceneProductionPolicy(generation);
            } catch (RemoteException | RuntimeException e) {
                // Native policy publication already cleared the hold.  A
                // failed bookkeeping callback must not turn a successful
                // cycle into a second, potentially stale reset.
                Log.w(
                    TAG,
                    "Could not clear consumed Scene hold lease generation="
                        + generation,
                    e
                );
            }
        }

        private void resetNativeHold() {
            try {
                gamePort.resetSceneProductionPolicy(generation);
            } catch (RemoteException | RuntimeException e) {
                Log.w(
                    TAG,
                    "Could not reset native Scene hold for generation="
                        + generation,
                    e
                );
            }
        }
    }

    /** Broken apply transport invalidates the complete synchronization cycle. */
    static final class GlobalOperationException extends RuntimeException {
        private static final long serialVersionUID = 1L;
        final FailureKind failureKind;

        GlobalOperationException(FailureKind failureKind, Throwable cause) {
            super(cause == null
                ? "global Scene operation failed"
                : cause.getMessage(), cause);
            this.failureKind = failureKind;
        }
    }
    private static final GlobalOperationException WORKER_OOME_FATAL =
        new GlobalOperationException(FailureKind.INTERNAL, null);
    private static final String WORKER_OOME_DIAGNOSTIC =
        "direction=het_internal record=unknown type=SCENE"
            + " scene=unknown offset=-1 expected=bounded worker memory"
            + " actual=OutOfMemoryError stage=scene_worker_allocation"
            + " reason=allocation failed";

    /**
     * A permit is released from a worker's finally block just before the
     * ThreadPoolExecutor worker returns to SynchronousQueue.take().  A plain
     * AbortPolicy would race that handoff and reject the next body even though
     * capacity is available.  Wait briefly for the same worker to become a
     * receiver, while still observing shutdown/cancellation.
     */
    private static final class BlockingHandoffPolicy
        implements RejectedExecutionHandler {
        @Override
        public void rejectedExecution(
            Runnable command,
            ThreadPoolExecutor executor
        ) {
            while (!executor.isShutdown()) {
                try {
                    if (executor.getQueue().offer(
                        command,
                        50L,
                        TimeUnit.MILLISECONDS
                    )) {
                        return;
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new RejectedExecutionException(
                        "interrupted while handing off Scene worker",
                        e
                    );
                }
            }
            throw new RejectedExecutionException(
                "Scene worker executor is closed"
            );
        }
    }

    @FunctionalInterface
    interface SceneWork {
        void run() throws Exception;
    }

    @FunctionalInterface
    interface ApplyCall {
        SceneSyncWireCodec.ApplyResult run() throws Exception;
    }

    @FunctionalInterface
    interface FailureInjector {
        void beforeExportRequest();
    }

    private final SceneStore sceneStore;
    private SceneStore.MutationAdmission.FullSyncLease syncWriteLease;
    private final PendingSceneApplyStore.RecoveryReport initialPendingRecovery;
    private boolean ownsSyncWriteLease;
    private final IGameScenePort gamePort;
    private final SceneConflictResolver conflictResolver;
    private final PendingSceneApplyStore pendingApplyStore;
    private final String conflictMode;
    private final int workerCount;
    private final SceneApplyCoordinator applyCoordinator;
    private final SceneApplyCoordinator policyApplyCoordinator;
    /** Independent fail-open transport kept usable after normal cancellation. */
    private final SceneApplyCoordinator cleanupPolicyApplyCoordinator;
    private final ScenePolicyPublisher policyPublisher;
    private final ScenePolicyPublisher.Target policyTarget;
    private final FailureInjector failureInjector;
    private final ExecutorService workerExecutor;
    private final Semaphore workerSlots;
    private final List<Future<?>> submittedWorkers = new ArrayList<>();
    private final AtomicReference<ParcelFileDescriptor> activeExportFd =
        new AtomicReference<>();
    private final AtomicReference<GlobalOperationException> workerFatalFailure =
        new AtomicReference<>();
    private final ExportHoldLease exportHoldLease;
    private final AtomicBoolean cancelRequested = new AtomicBoolean();
    private final AtomicBoolean sceneWorkStabilized = new AtomicBoolean();
    private final AtomicBoolean runStarted = new AtomicBoolean();
    private final AtomicBoolean gameActivityAbortIssued =
        new AtomicBoolean();
    private final Map<String, Direction> sceneDirections =
        Collections.synchronizedMap(new HashMap<>());
    private final Set<String> completedDeletionSceneNames =
        concurrentSet();
    private volatile boolean acceptingWork = true;

    /**
     * Creates one cycle from its immutable conflict decision.  The resolver
     * owns only local conflict storage; its apply sink is supplied per Scene
     * so no storage monitor is held while Binder work waits.  Callers must
     * pass the cycle snapshot captured by the coordinator; there is no
     * compatibility constructor that reads mutable settings at operation
     * creation time.
     */
    public SceneSyncOperation(
        SceneStore sceneStore,
        IGameScenePort gamePort,
        int workerCount,
        SceneConflictResolver conflictResolver,
        PendingSceneApplyStore pendingApplyStore,
        SceneSyncCycleSnapshot cycleSnapshot,
        ScenePolicyPublisher policyPublisher,
        ScenePolicyPublisher.Target policyTarget
    ) {
        this(
            sceneStore,
            gamePort,
            workerCount,
            conflictResolver,
            pendingApplyStore,
            cycleSnapshot,
            policyPublisher,
            policyTarget,
            () -> {},
            null,
            null
        );
    }

    SceneSyncOperation(
        SceneStore sceneStore,
        IGameScenePort gamePort,
        int workerCount,
        SceneConflictResolver conflictResolver,
        PendingSceneApplyStore pendingApplyStore,
        SceneSyncCycleSnapshot cycleSnapshot,
        ScenePolicyPublisher policyPublisher,
        ScenePolicyPublisher.Target policyTarget,
        FailureInjector failureInjector
    ) {
        this(
            sceneStore,
            gamePort,
            workerCount,
            conflictResolver,
            pendingApplyStore,
            cycleSnapshot,
            policyPublisher,
            policyTarget,
            failureInjector,
            null,
            null
        );
    }

    SceneSyncOperation(
        SceneStore sceneStore,
        IGameScenePort gamePort,
        int workerCount,
        SceneConflictResolver conflictResolver,
        PendingSceneApplyStore pendingApplyStore,
        SceneSyncCycleSnapshot cycleSnapshot,
        ScenePolicyPublisher policyPublisher,
        ScenePolicyPublisher.Target policyTarget,
        FailureInjector failureInjector,
        SceneStore.MutationAdmission.FullSyncLease syncWriteLease,
        PendingSceneApplyStore.RecoveryReport initialPendingRecovery
    ) {
        SceneSyncCycleSnapshot fixedSnapshot =
            requireCycleSnapshot(cycleSnapshot);
        if (sceneStore == null
            || gamePort == null
            || conflictResolver == null
            || pendingApplyStore == null
            || policyPublisher == null
            || policyTarget == null
            || failureInjector == null) {
            throw new IllegalArgumentException(
                "Scene sync dependencies are required"
            );
        }
        if (workerCount < 1 || workerCount > 4) {
            throw new IllegalArgumentException(
                "workerCount must be between 1 and 4"
            );
        }
        this.sceneStore = sceneStore;
        this.syncWriteLease = syncWriteLease;
        this.initialPendingRecovery = initialPendingRecovery;
        this.gamePort = gamePort;
        this.conflictResolver = conflictResolver;
        this.pendingApplyStore = pendingApplyStore;
        this.conflictMode = fixedSnapshot.getConflictResolutionMode();
        this.workerCount = workerCount;
        this.applyCoordinator = new SceneApplyCoordinator();
        this.policyApplyCoordinator = new SceneApplyCoordinator();
        this.cleanupPolicyApplyCoordinator = new SceneApplyCoordinator();
        this.policyPublisher = policyPublisher;
        this.policyTarget = policyTarget;
        this.exportHoldLease = new ExportHoldLease(
            gamePort,
            policyTarget.generation
        );
        this.failureInjector = failureInjector;
        this.workerExecutor = new ThreadPoolExecutor(
            workerCount,
            workerCount,
            0L,
            TimeUnit.MILLISECONDS,
            new SynchronousQueue<>(),
            runnable -> {
                Thread thread = new Thread(
                    runnable,
                    "HET-scene-sync-worker"
                );
                thread.setDaemon(true);
                return thread;
            },
            new BlockingHandoffPolicy()
        );
        this.workerSlots = new Semaphore(workerCount);
    }

    private void saveRawSceneForSync(SceneStore.RawSceneSnapshot snapshot)
        throws IOException {
        if (syncWriteLease == null) {
            throw new IOException("full-sync write lease is required");
        }
        SceneStore.MutationReceipt<Void> receipt =
            syncWriteLease.saveRawSceneSnapshot(sceneStore, snapshot);
        if (receipt == null
            || receipt.disposition
                == SceneStore.MutationDisposition.DEFERRED) {
            throw new DeferredSceneMutationException("HET Scene write");
        }
    }

    private boolean clearMatchingDeletionIntentForSync(
        String sceneName,
        long token
    ) throws IOException {
        if (syncWriteLease == null) {
            throw new IOException("full-sync write lease is required");
        }
        return syncWriteLease.clearMatchingDeletionIntent(
            sceneStore,
            sceneName,
            token
        );
    }

    private static SceneSyncCycleSnapshot requireCycleSnapshot(
        SceneSyncCycleSnapshot cycleSnapshot
    ) {
        if (cycleSnapshot == null) {
            throw new IllegalArgumentException(
                "cycleSnapshot cannot be null"
            );
        }
        return cycleSnapshot;
    }

    /**
     * Runs export, applies HET-only valid Scenes, then publishes the supplied
     * final blocked list only after every accepted apply result succeeded.
     */
    public Result run() {
        runStarted.set(true);
        if (syncWriteLease == null) {
            try {
                syncWriteLease = SceneStore.beginFullSyncAdmission(sceneStore);
                ownsSyncWriteLease = true;
            } catch (IOException e) {
                return failure(
                    FailureKind.INTERNAL,
                    concurrentSet(),
                    concurrentSet(),
                    concurrentSet(),
                    concurrentSet()
                ).snapshot();
            }
        }
        try {
            return runCycle();
        } finally {
            if (ownsSyncWriteLease && syncWriteLease != null) {
                syncWriteLease.close();
                syncWriteLease = null;
                ownsSyncWriteLease = false;
            }
        }
    }

    /**
     * The game accepted a mutation request but HET only durably queued its
     * Scene write.  Treat this as a per-Scene blocked outcome: callers must
     * not clear deletion intents, pending directives, or conflict claims.
     */
    private static final class DeferredSceneMutationException
        extends IOException {
        private static final long serialVersionUID = 1L;

        private DeferredSceneMutationException(String action) {
            super(action + " returned APPLY_DEFERRED");
        }
    }

    private static void requireCommittedApply(
        SceneSyncWireCodec.ApplyResult result,
        String action
    ) throws IOException {
        if (result == null) {
            throw new IOException(action + " returned no APPLY_RESULT");
        }
        if (result.errorCode == SceneSyncWireCodec.APPLY_DEFERRED) {
            throw new DeferredSceneMutationException(action);
        }
        if (!result.success) {
            throw new IOException(action + " was not successful");
        }
    }

    private Result runCycle() {
        ScenePolicyPublisher.CycleSnapshot preCycle = null;
        PendingResult pending;
        try {
        try {
            if (isCancellationRequested()) {
                pending = failure(
                    FailureKind.INTERRUPTED,
                    concurrentSet(),
                    concurrentSet(),
                    concurrentSet(),
                    concurrentSet()
                );
            } else {
                preCycle = policyPublisher.snapshotForCycle(policyTarget);
                pending = runInternal();
            }
        } catch (OutOfMemoryError e) {
            Log.e(
                TAG,
                "direction=het_internal record=unknown type=unknown"
                    + " scene=unknown offset=-1"
                    + " expected=bounded allocation"
                    + " actual=OutOfMemoryError"
                    + " stage=cycle_allocation"
                    + " reason=allocation failed",
                e
            );
            pending = failure(
                FailureKind.INTERNAL,
                concurrentSet(),
                concurrentSet(),
                concurrentSet(),
                concurrentSet()
            );
        } catch (RuntimeException e) {
            logTechnicalFailure(
                "cycle_setup",
                "het_internal",
                "unknown",
                "unknown",
                "unknown",
                -1L,
                "valid operation state",
                e.getClass().getSimpleName(),
                e
            );
            pending = failure(
                FailureKind.INTERNAL,
                concurrentSet(),
                concurrentSet(),
                concurrentSet(),
                concurrentSet()
            );
        }
        } finally {
            stabilizeSceneWork();
        }
        try {
            if (!pending.success
                && pending.failureKind != FailureKind.POLICY_FAILED
                && preCycle != null) {
                publishPreCycleAfterFailure(preCycle, pending.failureKind);
            }
            return pending.snapshot();
        } finally {
            try {
                policyApplyCoordinator.close();
            } finally {
                try {
                    cleanupPolicyApplyCoordinator.close();
                } finally {
                    exportHoldLease.close();
                }
            }
        }
    }

    private PendingResult runInternal() {
        Set<String> gameNames = concurrentSet();
        Set<String> hetOnlyNames = concurrentSet();
        Set<String> rejectedNames = concurrentSet();
        Set<String> blockedNames = concurrentSet();
        if (isCancellationRequested()) {
            return failure(
                FailureKind.INTERRUPTED,
                gameNames,
                hetOnlyNames,
                rejectedNames,
                blockedNames
            );
        }

        // This set is coordinator-owned: export callbacks are serialized and
        // all HET identities are seeded before workers begin.
        Set<String> allSceneNames = new HashSet<>();
        // Freeze offline directives once at operation start.  Their bodies
        // are read only when the matching fixed export body is encountered.
        Set<String> pendingApplyNames = new HashSet<>();
        Map<String, SceneStore.DeletionIntent> deletionIntents =
            sceneStore.snapshotDeletionIntents();
        Set<String> hetNames;
        List<String> formalNames;

        try {
            PendingSceneApplyStore.RecoveryReport pendingRecovery =
                initialPendingRecovery != null
                    ? initialPendingRecovery
                    : pendingApplyStore.recover();
            pendingApplyNames.addAll(pendingRecovery.validSceneNames);
            // Preserve one diagnostic identity per damaged directive for
            // this cycle; do not recreate a Scene or a formal conflict.
            rejectedNames.addAll(pendingRecovery.discardedSceneNames);
            recordDirections(
                pendingRecovery.discardedSceneNames,
                Direction.LOCAL
            );
            if (!addAllSceneNames(
                allSceneNames,
                pendingRecovery.discardedSceneNames
            ) || !addAllSceneNames(allSceneNames, pendingApplyNames)) {
                return failure(
                    FailureKind.IDENTITY_LIMIT,
                    gameNames,
                    hetOnlyNames,
                    rejectedNames,
                    blockedNames
                );
            }
        } catch (Exception e) {
            return failure(
                FailureKind.HET_READ,
                gameNames,
                hetOnlyNames,
                rejectedNames,
                blockedNames
            );
        }
        if (!addAllSceneNames(allSceneNames, deletionIntents.keySet())) {
            return failure(
                FailureKind.IDENTITY_LIMIT,
                gameNames,
                hetOnlyNames,
                rejectedNames,
                blockedNames
            );
        }

        try {
            List<String> claimedConflicts =
                conflictResolver.listClaimedSceneNamesStrict();
            if (claimedConflicts.size() > SceneSyncWireCodec.MAX_SCENES) {
                return failure(
                    FailureKind.IDENTITY_LIMIT,
                    gameNames,
                    hetOnlyNames,
                    rejectedNames,
                    blockedNames
                );
            }
            blockedNames.addAll(claimedConflicts);
            recordDirections(claimedConflicts, Direction.BIDIRECTIONAL);
            if (!addAllSceneNames(allSceneNames, claimedConflicts)) {
                return failure(
                    FailureKind.IDENTITY_LIMIT,
                    gameNames,
                    hetOnlyNames,
                    rejectedNames,
                    blockedNames
                );
            }
        } catch (Exception e) {
            return failure(
                FailureKind.HET_READ,
                gameNames,
                hetOnlyNames,
                rejectedNames,
                blockedNames
            );
        }

        try {
            formalNames = sceneStore.listFormalSceneNamesStrict();
            if (formalNames.size() > SceneSyncWireCodec.MAX_SCENES) {
                return failure(
                    FailureKind.IDENTITY_LIMIT,
                    gameNames,
                    hetOnlyNames,
                    rejectedNames,
                    blockedNames
                );
            }
            hetNames = new HashSet<>(formalNames);
            if (!addAllSceneNames(allSceneNames, formalNames)) {
                return failure(
                    FailureKind.IDENTITY_LIMIT,
                    gameNames,
                    hetOnlyNames,
                    rejectedNames,
                    blockedNames
                );
            }
        } catch (Exception e) {
            return failure(
                FailureKind.HET_READ,
                gameNames,
                hetOnlyNames,
                rejectedNames,
                blockedNames
            );
        }

        if (isCancellationRequested()) {
            return failure(
                FailureKind.INTERRUPTED,
                gameNames,
                hetOnlyNames,
                rejectedNames,
                blockedNames
            );
        }

        failureInjector.beforeExportRequest();
        ParcelFileDescriptor exportFd;
        try {
            exportFd = gamePort.exportSceneSnapshot(policyTarget.generation);
        } catch (RemoteException | RuntimeException e) {
            // Binder can fail after the game has established its native hold
            // but before it returns the descriptor.  Ask the generation-aware
            // port to fail open; a null descriptor path below deliberately
            // does not issue this reset because no hold was admitted.
            try {
                gamePort.resetSceneProductionPolicy(policyTarget.generation);
            } catch (RemoteException | RuntimeException resetFailure) {
                Log.w(
                    TAG,
                    "Could not fail-open Scene hold after export Binder "
                        + "failure",
                    resetFailure
                );
            }
            logTechnicalFailure(
                "export_request",
                "game_to_het/export",
                "unknown",
                "EXPORT_STREAM",
                "unknown",
                -1L,
                "one accepted export descriptor",
                e.getClass().getSimpleName(),
                e
            );
            return failure(
                FailureKind.EXPORT_UNAVAILABLE,
                gameNames,
                hetOnlyNames,
                rejectedNames,
                blockedNames
            );
        }
        if (exportFd == null) {
            if (isCancellationRequested()) {
                return failure(
                    FailureKind.INTERRUPTED,
                    gameNames,
                    hetOnlyNames,
                    rejectedNames,
                    blockedNames
                );
            }
            logTechnicalFailure(
                "export_request",
                "game_to_het/export",
                "unknown",
                "EXPORT_STREAM",
                "unknown",
                -1L,
                "non-null export descriptor",
                "null_descriptor",
                null
            );
            return failure(
                FailureKind.EXPORT_UNAVAILABLE,
                gameNames,
                hetOnlyNames,
                rejectedNames,
                blockedNames
            );
        }
        // A non-null descriptor is the only durable proof that
        // SceneMirrorExportCoordinator established nativeBeginSceneSyncHold.
        exportHoldLease.accept();
        if (isCancellationRequested()) {
            closeQuietly(exportFd);
            return failure(
                FailureKind.INTERRUPTED,
                gameNames,
                hetOnlyNames,
                rejectedNames,
                blockedNames
            );
        }
        if (!activeExportFd.compareAndSet(null, exportFd)) {
            closeQuietly(exportFd);
            return failure(
                isCancellationRequested()
                    ? FailureKind.INTERRUPTED
                    : FailureKind.INTERNAL,
                gameNames,
                hetOnlyNames,
                rejectedNames,
                blockedNames
            );
        }
        if (!acceptingWork || cancelRequested.get()) {
            if (activeExportFd.compareAndSet(exportFd, null)) {
                closeQuietly(exportFd);
            }
            return failure(
                FailureKind.INTERRUPTED,
                gameNames,
                hetOnlyNames,
                rejectedNames,
                blockedNames
            );
        }

        try (InputStream input =
                 new ParcelFileDescriptor.AutoCloseInputStream(exportFd)) {
            final boolean[] identityOverflow = {false};
            SceneSyncWireCodec.decodeExport(
                input,
                new SceneSyncWireCodec.ExportRecordConsumer() {
                    @Override
                    public void onScene(
                        String sceneName,
                        int bodyLength,
                        InputStream body
                    ) throws IOException {
                        allSceneNames.add(sceneName);
                        if (allSceneNames.size()
                            > SceneSyncWireCodec.MAX_SCENES) {
                            throw new IdentityLimitException();
                        }
                        gameNames.add(sceneName);
                        sceneDirections.put(
                            sceneName,
                            directionForExportScene(
                                sceneName,
                                hetNames,
                                pendingApplyNames,
                                deletionIntents
                            )
                        );
                        acquireWorkerSlot();
                        byte[] gameBytes;
                        try {
                            gameBytes = readBody(body, bodyLength);
                        } catch (IOException e) {
                            workerSlots.release();
                            throw e;
                        }
                        if (!submitHeldWorker(
                            sceneName,
                            () -> processExportScene(
                                sceneName,
                                gameBytes,
                                hetNames,
                                pendingApplyNames,
                                deletionIntents,
                                gameNames,
                                rejectedNames,
                                blockedNames
                            ),
                            rejectedNames,
                            blockedNames
                        )) {
                            workerSlots.release();
                            markRejected(
                                sceneName,
                                rejectedNames,
                                blockedNames
                            );
                            throw new IOException(
                                "could not admit Scene operation worker"
                            );
                        }
                    }

                    @Override
                    public void onRejected(String sceneName, int errorCode) {
                        Log.w(
                            TAG,
                            "direction=game_to_het/export"
                                + " record=unknown type=REJECTED"
                                + " scene=" + sceneName
                                + " offset=-1 expected=SCENE"
                                + " actual_error=" + errorCode
                                + " stage=decode_export_scene_rejected"
                                + " reason=game rejected one Scene export"
                        );
                        allSceneNames.add(sceneName);
                        if (allSceneNames.size()
                            > SceneSyncWireCodec.MAX_SCENES) {
                            identityOverflow[0] = true;
                        }
                        gameNames.add(sceneName);
                        sceneDirections.put(sceneName, Direction.GAME_TO_HET);
                        markRejected(
                            sceneName,
                            rejectedNames,
                            blockedNames
                        );
                    }
                }
            );
            if (identityOverflow[0]) {
                return failure(
                    FailureKind.IDENTITY_LIMIT,
                    gameNames,
                    hetOnlyNames,
                    rejectedNames,
                    blockedNames
                );
            }
        } catch (IdentityLimitException e) {
            logTechnicalFailure(
                "decode_export_identity_limit",
                "game_to_het/export",
                "unknown",
                "unknown",
                "unknown",
                -1L,
                "at most MAX_SCENES unique Scene identities",
                e.getClass().getSimpleName(),
                e
            );
            return failure(
                FailureKind.IDENTITY_LIMIT,
                gameNames,
                hetOnlyNames,
                rejectedNames,
                blockedNames
            );
        } catch (OperationInterruptedException e) {
            GlobalOperationException fatal = workerFatalFailure.get();
            logTechnicalFailure(
                "decode_export_cancelled",
                "game_to_het/export",
                "unknown",
                "unknown",
                "unknown",
                -1L,
                "an active export operation",
                e.getClass().getSimpleName(),
                e
            );
            return failure(
                fatal == null
                    ? FailureKind.INTERRUPTED
                    : fatal.failureKind,
                gameNames,
                hetOnlyNames,
                rejectedNames,
                blockedNames
            );
        } catch (IOException e) {
            GlobalOperationException fatal = workerFatalFailure.get();
            logTechnicalFailure(
                "decode_export_protocol",
                "game_to_het/export",
                "unknown",
                "unknown",
                "unknown",
                -1L,
                "one complete valid export stream",
                e.getClass().getSimpleName(),
                e
            );
            return failure(
                fatal == null
                    ? FailureKind.EXPORT_PROTOCOL
                    : fatal.failureKind,
                gameNames,
                hetOnlyNames,
                rejectedNames,
                blockedNames
            );
        } catch (OutOfMemoryError e) {
            Log.e(
                TAG,
                "direction=game_to_het/export record=unknown type=SCENE"
                    + " scene=unknown offset=-1"
                    + " expected=bounded Scene body allocation"
                    + " actual=OutOfMemoryError"
                    + " stage=decode_export_allocation"
                    + " reason=allocation failed",
                e
            );
            return failure(
                FailureKind.INTERNAL,
                gameNames,
                hetOnlyNames,
                rejectedNames,
                blockedNames
            );
        } catch (RuntimeException e) {
            GlobalOperationException fatal = workerFatalFailure.get();
            logTechnicalFailure(
                "decode_export_internal",
                "game_to_het/export",
                "unknown",
                "unknown",
                "unknown",
                -1L,
                "one valid decoder state",
                e.getClass().getSimpleName(),
                e
            );
            return failure(
                fatal == null
                    ? FailureKind.INTERNAL
                    : fatal.failureKind,
                gameNames,
                hetOnlyNames,
                rejectedNames,
                blockedNames
            );
        } finally {
            activeExportFd.compareAndSet(exportFd, null);
        }

        FailureKind workerFailure = awaitSubmittedWorkers();
        if (workerFailure != null) {
            return failure(
                workerFailure,
                gameNames,
                hetOnlyNames,
                rejectedNames,
                blockedNames
            );
        }

        try {
            clearMissingDeletionIntents(
                deletionIntents,
                gameNames,
                rejectedNames
            );
        } catch (Exception e) {
            return failure(
                FailureKind.INTERNAL,
                gameNames,
                hetOnlyNames,
                rejectedNames,
                blockedNames
            );
        }

        // A pending directive whose Scene is absent from the fixed export is
        // handled by the normal HET-only path with its candidate body.
        for (String sceneName : formalNamesSorted(pendingApplyNames)) {
            if (gameNames.contains(sceneName)
                || rejectedNames.contains(sceneName)) {
                continue;
            }
            sceneDirections.put(sceneName, Direction.HET_TO_GAME);
            if (!submitWorker(
                sceneName,
                () -> processPendingMissingScene(
                    sceneName,
                    gameNames,
                    blockedNames
                ),
                rejectedNames,
                blockedNames
            )) {
                markRejected(sceneName, rejectedNames, blockedNames);
                return failure(
                    FailureKind.APPLY_REJECTED,
                    gameNames,
                    hetOnlyNames,
                    rejectedNames,
                    blockedNames
                );
            }
        }

        workerFailure = awaitSubmittedWorkers();
        if (workerFailure != null) {
            return failure(
                workerFailure,
                gameNames,
                hetOnlyNames,
                rejectedNames,
                blockedNames
            );
        }

        // HET-only workers read each formal file exactly once.  They use the
        // same operation pool and permit budget as export workers.
        for (String sceneName : formalNamesSorted(hetNames)) {
            if (gameNames.contains(sceneName)
                || rejectedNames.contains(sceneName)
                || blockedNames.contains(sceneName)) {
                continue;
            }
            hetOnlyNames.add(sceneName);
            sceneDirections.put(sceneName, Direction.HET_TO_GAME);
            if (!submitWorker(
                sceneName,
                () -> processHetOnlyScene(
                    sceneName,
                    gameNames,
                    rejectedNames,
                    blockedNames
                ),
                rejectedNames,
                blockedNames
            )) {
                markRejected(sceneName, rejectedNames, blockedNames);
                return failure(
                    FailureKind.APPLY_REJECTED,
                    gameNames,
                    hetOnlyNames,
                    rejectedNames,
                    blockedNames
                );
            }
        }

        workerFailure = awaitSubmittedWorkers();
        if (workerFailure != null) {
            return failure(
                workerFailure,
                gameNames,
                hetOnlyNames,
                rejectedNames,
                blockedNames
            );
        }

        final ScenePolicyPublisher.PublishResult publication;
        try {
            publication = publishFinalBlockedScenes(blockedNames);
        } catch (Exception e) {
            logTechnicalFailure(
                "policy_prepare",
                "het_to_game/apply_request",
                "0",
                "REPLACE_BLOCKED_SCENES",
                "unknown",
                -1L,
                "valid complete blocked list",
                e.getClass().getSimpleName(),
                e
            );
            return failure(
                FailureKind.POLICY_FAILED,
                gameNames,
                hetOnlyNames,
                rejectedNames,
                blockedNames
            );
        }
        if (!publication.isPublished()) {
            return failure(
                publication.outcome == ScenePolicyPublisher.Outcome.STALE
                    ? FailureKind.INTERRUPTED
                    : FailureKind.POLICY_FAILED,
                gameNames,
                hetOnlyNames,
                rejectedNames,
                blockedNames
            );
        }
        return new PendingResult(
            true,
            null,
            gameNames,
            hetOnlyNames,
            completedDeletionSceneNames,
            rejectedNames,
            blockedNames,
            sceneDirections
        );
    }

    private void processExportScene(
        String sceneName,
        byte[] gameBytes,
        Set<String> hetNames,
        Set<String> pendingApplyNames,
        Map<String, SceneStore.DeletionIntent> deletionIntents,
        Set<String> gameNames,
        Set<String> rejectedNames,
        Set<String> blockedNames
    ) throws Exception {
        if (pendingApplyNames.contains(sceneName)) {
            if (processPendingApply(
                sceneName,
                gameBytes,
                pendingApplyNames,
                gameNames,
                blockedNames
            )) {
                return;
            }
        }
        if (!hetNames.contains(sceneName)) {
            SceneStore.DeletionIntent intent = deletionIntents.get(sceneName);
            if (intent != null) {
                SceneSyncWireCodec.ApplyResult result = applyGlobally(
                    () -> applyCoordinator.applyDeleteBlocking(
                        policyTarget.generation,
                        gamePort,
                        sceneName
                    )
                );
                requireCommittedApply(result, "Scene deletion apply");
                // The HET-side formal view was already deleted.  Remove only
                // the captured in-memory intent after the game acknowledged
                // DELETE.  A terminal DELETED summary is recorded only after
                // the intent and fixed game view have both converged.
                if (!clearMatchingDeletionIntentForSync(
                    sceneName,
                    intent.token
                )) {
                    throw new IOException(
                        "Scene deletion intent changed before completion"
                    );
                }
                if (!gameNames.remove(sceneName)) {
                    throw new IOException(
                        "fixed game view did not contain deleted Scene"
                    );
                }
                completedDeletionSceneNames.add(sceneName);
                return;
            }
            SceneStore.RawSceneSnapshot snapshot =
                sceneStore.validateRawSceneBytes(sceneName, gameBytes);
            saveRawSceneForSync(snapshot);
            return;
        }

        // Validate the exported bytes once in the worker before comparing or
        // applying them.  The same validated byte array is then used for the
        // digest and any game-candidate conflict action.
        SceneStore.RawSceneSnapshot gameScene =
            sceneStore.validateRawSceneBytes(sceneName, gameBytes);
        SceneStore.RawSceneSnapshot hetScene =
            sceneStore.readRawSceneSnapshot(sceneName);
        if (hetScene == null) {
            throw new IOException("HET Scene snapshot is missing");
        }
        byte[] gameHash = SceneDigest.sha256(gameScene.bytes);
        byte[] hetHash = SceneDigest.sha256(hetScene.bytes);
        boolean currentClaimRetained =
            conflictResolver.retainCurrentClaim(
                sceneName,
                gameScene.bytes,
                hetScene.bytes
            );
        if (currentClaimRetained) {
            blockedNames.add(sceneName);
            return;
        }
        blockedNames.remove(sceneName);
        if (MessageDigest.isEqual(gameHash, hetHash)) {
            blockedNames.remove(sceneName);
            return;
        }
        SceneConflictResolver.Decision decision =
            conflictResolver.resolve(
                sceneName,
                gameScene.bytes,
                hetScene.bytes,
                conflictMode,
                new SceneConflictResolver.ApplySink() {
                    @Override
                    public void applyGameCandidate(byte[] bytes)
                        throws Exception {
                        // The snapshot is immutable for this worker; do not
                        // reread or clone it while the resolver is deciding.
                        saveRawSceneForSync(gameScene);
                    }

                    @Override
                    public void applyHetCandidate(byte[] bytes)
                        throws Exception {
                        SceneSyncWireCodec.ApplyResult result = applyGlobally(
                            () -> applyCoordinator.applyWriteBlocking(
                                policyTarget.generation,
                                gamePort,
                                sceneName,
                                bytes
                            )
                        );
                        requireCommittedApply(result, "HET conflict apply");
                    }
                }
            );
        if (decision == null) {
            throw new IOException("Scene conflict resolver returned no decision");
        }
        if (decision.kind == SceneConflictResolver.Kind.MANUAL_RECORDED
            || decision.kind == SceneConflictResolver.Kind.ALREADY_PENDING) {
            // A durable formal conflict is an expected blocked outcome, not a
            // rejected Scene.  Keep it in blockedNames without adding it to
            // rejectedNames so the next cycle can present it again.
            blockedNames.add(sceneName);
            return;
        }
        if (decision.isBlocked()) {
            throw new IOException("Scene conflict remains blocked");
        }
    }

    /** Processes one directive against the fixed export body for its Scene. */
    private boolean processPendingApply(
        String sceneName,
        byte[] gameBytes,
        Set<String> pendingApplyNames,
        Set<String> gameNames,
        Set<String> blockedNames
    ) throws Exception {
        PendingSceneApplyStore.PendingRecord pending =
            pendingApplyStore.read(sceneName);
        SceneStore.RawSceneSnapshot gameScene =
            sceneStore.validateRawSceneBytes(sceneName, gameBytes);
        SceneStore.RawSceneSnapshot currentHet =
            sceneStore.readRawSceneSnapshot(sceneName);
        if (currentHet == null
            || !pending.candidateSha256.equals(
                SceneDigest.lowerHex(SceneDigest.sha256(currentHet.bytes))
            )) {
            pendingApplyStore.remove(sceneName);
            blockedNames.remove(sceneName);
            return false;
        }
        byte[] currentHash = SceneDigest.sha256(gameScene.bytes);
        byte[] candidateHash = SceneDigest.sha256(pending.candidateBytes);
        if (MessageDigest.isEqual(currentHash, candidateHash)) {
            conflictResolver.removeConflict(sceneName);
            pendingApplyStore.remove(sceneName);
            blockedNames.remove(sceneName);
            return true;
        }

        boolean expected = pending.expectedGameSha256.equals(
            SceneDigest.lowerHex(currentHash)
        );
        if (expected || pending.overwriteIfGameChanged) {
            applyPendingCandidate(sceneName, pending.candidateBytes);
            conflictResolver.removeConflict(sceneName);
            pendingApplyStore.remove(sceneName);
            blockedNames.remove(sceneName);
            return true;
        }

        // The user selected HET offline, but the game changed meanwhile.
        // Replace both formal candidates atomically; the Scene remains blocked.
        conflictResolver.replaceOrPersistPending(
            sceneName,
            gameScene.bytes,
            pending.candidateBytes
        );
        pendingApplyStore.remove(sceneName);
        blockedNames.add(sceneName);
        gameNames.add(sceneName);
        return true;
    }

    /** Handles a pending directive when the fixed game export omitted it. */
    private void processPendingMissingScene(
        String sceneName,
        Set<String> gameNames,
        Set<String> blockedNames
    ) throws Exception {
        PendingSceneApplyStore.PendingRecord pending =
            pendingApplyStore.read(sceneName);
        applyPendingCandidate(sceneName, pending.candidateBytes);
        conflictResolver.removeConflict(sceneName);
        pendingApplyStore.remove(sceneName);
        blockedNames.remove(sceneName);
        gameNames.add(sceneName);
    }

    private void applyPendingCandidate(String sceneName, byte[] bytes)
        throws Exception {
        SceneSyncWireCodec.ApplyResult result = applyGlobally(
            () -> applyCoordinator.applyWriteBlocking(
                policyTarget.generation,
                gamePort,
                sceneName,
                bytes
            )
        );
        requireCommittedApply(result, "pending Scene apply");
    }

    private void clearMissingDeletionIntents(
        Map<String, SceneStore.DeletionIntent> deletionIntents,
        Set<String> gameNames,
        Set<String> rejectedNames
    ) throws IOException {
        for (Map.Entry<String, SceneStore.DeletionIntent> entry
            : deletionIntents.entrySet()) {
            String sceneName = entry.getKey();
            if (!gameNames.contains(sceneName)
                && !rejectedNames.contains(sceneName)) {
                SceneStore.DeletionIntent intent = entry.getValue();
                clearMatchingDeletionIntentForSync(
                    sceneName,
                    intent.token
                );
            }
        }
    }

    private void processHetOnlyScene(
        String sceneName,
        Set<String> gameNames,
        Set<String> rejectedNames,
        Set<String> blockedNames
    ) throws Exception {
        SceneStore.RawSceneSnapshot scene =
            sceneStore.readRawSceneSnapshot(sceneName);
        if (scene == null) {
            throw new IOException("HET-only Scene snapshot is missing");
        }
        SceneSyncWireCodec.ApplyResult result = applyGlobally(
            () -> applyCoordinator.applyWriteBlocking(
                policyTarget.generation,
                gamePort,
                sceneName,
                scene.bytes
            )
        );
        requireCommittedApply(result, "HET-only Scene apply");
        // It becomes part of the game's view only after the game has returned
        // a successful atomic APPLY_RESULT.
        gameNames.add(sceneName);
    }

    private ScenePolicyPublisher.PublishResult publishFinalBlockedScenes(
        Set<String> blockedScenes
    ) throws Exception {
        if (!exportHoldLease.beginPublication()) {
            throw new IOException(
                "Scene policy publication was canceled before it started"
            );
        }
        boolean published = false;
        try {
            ScenePolicyPublisher.PublishResult result =
                policyPublisher.publishCycleTarget(
                    policyTarget,
                    snapshotNames(blockedScenes),
                    encodedCommand ->
                        policyApplyCoordinator.replaceBlockedScenesBlocking(
                            policyTarget.generation,
                            gamePort,
                            encodedCommand
                        )
                );
            published = result.isPublished();
            logPolicyPublication("cycle_target", result);
            return result;
        } finally {
            exportHoldLease.finishPublication(published);
        }
    }

    private void publishPreCycleAfterFailure(
        ScenePolicyPublisher.CycleSnapshot snapshot,
        FailureKind failureKind
    ) {
        abortGameSceneActivityOnce();
        boolean publicationStarted = false;
        boolean published = false;
        try {
            publicationStarted = exportHoldLease.beginPublication();
            if (!publicationStarted) {
                throw new IOException(
                    "Scene pre-cycle publication was canceled before it "
                        + "started"
                );
            }
            ScenePolicyPublisher.PublishResult result =
                policyPublisher.publishPreCycleTarget(
                    policyTarget,
                    snapshot,
                    encodedCommand ->
                        cleanupPolicyApplyCoordinator
                            .replaceBlockedScenesBlocking(
                                policyTarget.generation,
                                gamePort,
                                encodedCommand
                            )
                );
            logPolicyPublication(
                "failure_restore_" + failureKind.name(),
                result
            );
            published = result.isPublished();
        } catch (Exception e) {
            logTechnicalFailure(
                "failure_policy_restore",
                "het_to_game/apply_request",
                "0",
                "REPLACE_BLOCKED_SCENES",
                "unknown",
                -1L,
                "pre-cycle complete policy",
                e.getClass().getSimpleName(),
                e
            );
        } finally {
            if (publicationStarted) {
                exportHoldLease.finishPublication(published);
            }
        }
    }

    private void abortGameSceneActivityOnce() {
        if (!gameActivityAbortIssued.compareAndSet(false, true)) {
            return;
        }
        try {
            gamePort.abortSceneSyncActivity(policyTarget.generation);
        } catch (RemoteException | RuntimeException e) {
            Log.w(
                TAG,
                "Could not abort game Scene activity generation="
                    + policyTarget.generation,
                e
            );
        }
    }

    private static void logPolicyPublication(
        String stage,
        ScenePolicyPublisher.PublishResult result
    ) {
        Log.i(
            TAG,
            "direction=het_to_game/apply_request record=0 "
                + "type=REPLACE_BLOCKED_SCENES scene=unknown offset=-1 "
                + "stage=" + stage
                + " attempts=" + result.attempts
                + " outcome=" + result.outcome
                + " expected=success actual_error=" + result.lastErrorCode
                + " target_count=" + result.targetBlockedScenes.size()
        );
    }

    /** Acquires a body permit before reading a framed export body. */
    private void acquireWorkerSlot()
        throws IOException {
        try {
            workerSlots.acquire();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new OperationInterruptedException(
                "interrupted while admitting Scene body",
                e
            );
        }
        if (!acceptingWork || cancelRequested.get()) {
            workerSlots.release();
            throw new OperationInterruptedException(
                "Scene operation was closed while admitting body",
                null
            );
        }
    }

    /** Submits a task after its permit has already been acquired. */
    private boolean submitHeldWorker(
        String sceneName,
        SceneWork work,
        Set<String> rejectedNames,
        Set<String> blockedNames
    ) {
        if (!acceptingWork
            || cancelRequested.get()
            || workerFatalFailure.get() != null
            || work == null) {
            return false;
        }
        try {
            Future<?> future = workerExecutor.submit(() -> {
                try {
                    GlobalOperationException fatal = workerFatalFailure.get();
                    if (fatal != null) {
                        throw fatal;
                    }
                    if (!acceptingWork || cancelRequested.get()) {
                        return;
                    }
                    work.run();
                } catch (OutOfMemoryError e) {
                    if (workerFatalFailure.compareAndSet(
                        null,
                        WORKER_OOME_FATAL
                    )) {
                        acceptingWork = false;
                        closeQuietly(activeExportFd.getAndSet(null));
                        try {
                            Log.e(TAG, WORKER_OOME_DIAGNOSTIC, e);
                        } catch (OutOfMemoryError | RuntimeException ignored) {
                            // Keep the preallocated fatal state authoritative.
                        }
                    }
                    markRejected(
                        sceneName,
                        rejectedNames,
                        blockedNames
                    );
                    throw e;
                } catch (GlobalOperationException e) {
                    if (workerFatalFailure.compareAndSet(null, e)) {
                        acceptingWork = false;
                        closeQuietly(activeExportFd.getAndSet(null));
                        Throwable cause = e.getCause() == null
                            ? e
                            : e.getCause();
                        logTechnicalFailure(
                            "scene_apply_transport",
                            "het_to_game/apply_request_or_game_to_het/apply_result",
                            "unknown",
                            "SCENE",
                            sceneName,
                            -1L,
                            "one complete apply request and result stream",
                            cause.getClass().getSimpleName(),
                            cause
                        );
                    }
                    markRejected(
                        sceneName,
                        rejectedNames,
                        blockedNames
                    );
                    throw e;
                } catch (Exception e) {
                    if (e instanceof InterruptedException) {
                        Thread.currentThread().interrupt();
                    }
                    logTechnicalFailure(
                        "scene_worker",
                        "game_to_het/export_or_het_to_game/apply",
                        "unknown",
                        "SCENE",
                        sceneName,
                        -1L,
                        "one valid atomic Scene operation",
                        e.getClass().getSimpleName(),
                        e
                    );
                    // Per-Scene validation/apply failures are represented by
                    // the final blocked set; they do not abort other Scenes.
                    // The sceneName is always known for these workers.
                    markRejected(
                        sceneName,
                        rejectedNames,
                        blockedNames
                    );
                } finally {
                    workerSlots.release();
                }
            });
            submittedWorkers.add(future);
            return true;
        } catch (RejectedExecutionException e) {
            return false;
        }
    }

    /**
     * Submits a HET-only task, acquiring the same bounded in-flight permit
     * used while the export decoder is reading game bodies.
     */
    private boolean submitWorker(
        String sceneName,
        SceneWork work,
        Set<String> rejectedNames,
        Set<String> blockedNames
    ) {
        try {
            workerSlots.acquire();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
        if (submitHeldWorker(
            sceneName,
            work,
            rejectedNames,
            blockedNames
        )) {
            return true;
        }
        workerSlots.release();
        return false;
    }

    private FailureKind awaitSubmittedWorkers() {
        for (Future<?> future : submittedWorkers) {
            GlobalOperationException fatal = workerFatalFailure.get();
            if (fatal != null) {
                return cancelSubmittedWorkers(fatal.failureKind);
            }
            try {
                future.get();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return cancelSubmittedWorkers(FailureKind.INTERRUPTED);
            } catch (ExecutionException e) {
                fatal = workerFatalFailure.get();
                return cancelSubmittedWorkers(
                    fatal == null
                        ? FailureKind.INTERNAL
                        : fatal.failureKind
                );
            }
        }
        GlobalOperationException fatal = workerFatalFailure.get();
        if (fatal != null) {
            return cancelSubmittedWorkers(fatal.failureKind);
        }
        submittedWorkers.clear();
        return null;
    }

    private FailureKind cancelSubmittedWorkers(FailureKind failureKind) {
        for (Future<?> future : submittedWorkers) {
            if (!future.isDone()) {
                future.cancel(true);
            }
        }
        submittedWorkers.clear();
        return failureKind;
    }

    SceneSyncWireCodec.ApplyResult applyGlobally(ApplyCall call) {
        if (call == null) {
            throw new IllegalArgumentException("apply call cannot be null");
        }
        try {
            SceneSyncWireCodec.ApplyResult result = call.run();
            if (result == null) {
                throw new IOException("game returned no APPLY_RESULT");
            }
            return result;
        } catch (GlobalOperationException e) {
            throw e;
        } catch (Exception e) {
            throw new GlobalOperationException(
                cancelRequested.get()
                    ? FailureKind.INTERRUPTED
                    : FailureKind.APPLY_FAILED,
                e
            );
        }
    }
    private static void markRejected(
        String sceneName,
        Set<String> rejectedNames,
        Set<String> blockedNames
    ) {
        if (sceneName == null) {
            return;
        }
        rejectedNames.add(sceneName);
        blockedNames.add(sceneName);
    }

    private static Set<String> concurrentSet() {
        return Collections.synchronizedSet(new HashSet<>());
    }


    private static Direction directionForExportScene(
        String sceneName,
        Set<String> hetNames,
        Set<String> pendingApplyNames,
        Map<String, SceneStore.DeletionIntent> deletionIntents
    ) {
        if (pendingApplyNames.contains(sceneName)
            || (!hetNames.contains(sceneName)
                && deletionIntents.containsKey(sceneName))) {
            return Direction.HET_TO_GAME;
        }
        return hetNames.contains(sceneName)
            ? Direction.BIDIRECTIONAL
            : Direction.GAME_TO_HET;
    }

    private void recordDirections(
        Collection<String> sceneNames,
        Direction direction
    ) {
        for (String sceneName : sceneNames) {
            sceneDirections.put(sceneName, direction);
        }
    }
    private static List<String> snapshotNames(Set<String> names) {
        List<String> snapshot;
        synchronized (names) {
            snapshot = new ArrayList<>(names);
        }
        Collections.sort(snapshot);
        return snapshot;
    }

    private static List<String> formalNamesSorted(Set<String> names) {
        return snapshotNames(names);
    }

    private static boolean addAllSceneNames(
        Set<String> target,
        Collection<String> names
    ) {
        target.addAll(names);
        return target.size() <= SceneSyncWireCodec.MAX_SCENES;
    }

    private static byte[] readBody(InputStream input, int bodyLength)
        throws IOException {
        byte[] bytes = new byte[bodyLength];
        int offset = 0;
        while (offset < bytes.length) {
            int read = input.read(bytes, offset, bytes.length - offset);
            if (read < 0) {
                throw new IOException("early EOF while reading Scene body");
            }
            if (read == 0) {
                continue;
            }
            offset += read;
        }
        return bytes;
    }

    private PendingResult failure(
        FailureKind kind,
        Set<String> gameNames,
        Set<String> hetOnlyNames,
        Set<String> rejectedNames,
        Set<String> blockedNames
    ) {
        return new PendingResult(
            false,
            kind,
            gameNames,
            hetOnlyNames,
            completedDeletionSceneNames,
            rejectedNames,
            blockedNames,
            sceneDirections
        );
    }

    private boolean isCancellationRequested() {
        return cancelRequested.get() || !acceptingWork;
    }

    /**
     * Requests cancellation of Scene data-plane work without waiting for a
     * remote Binder method to return.  The game-side generation abort is sent
     * by {@link #close()} before this method is reached.
     */
    private void requestSceneWorkStop() {
        acceptingWork = false;
        ParcelFileDescriptor exportFd = activeExportFd.getAndSet(null);
        closeQuietly(exportFd);

        if (sceneWorkStabilized.compareAndSet(false, true)) {
            workerExecutor.shutdownNow();
            applyCoordinator.requestClose();
        }
    }

    /**
     * Stops intake, closes every active Scene data-plane endpoint, and waits
     * until all accepted workers have reached a terminal state.  This is used
     * by the run owner after the remote generation abort has had a chance to
     * release any in-flight game Binder call.
     */
    private void stabilizeSceneWork() {
        requestSceneWorkStop();
        // Closing the pipe endpoints wakes workers blocked in stream I/O.  A
        // Binder method itself is released by the generation abort issued by
        // close(), not by interrupting this thread.
        applyCoordinator.close();
        awaitWorkerTermination();
    }

    private void awaitWorkerTermination() {
        boolean interrupted = false;
        while (!workerExecutor.isTerminated()) {
            try {
                workerExecutor.awaitTermination(100L, TimeUnit.MILLISECONDS);
            } catch (InterruptedException e) {
                interrupted = true;
            }
        }
        if (interrupted) {
            Thread.currentThread().interrupt();
        }
    }

    private static void logTechnicalFailure(
        String stage,
        String direction,
        String record,
        String type,
        String sceneName,
        long offset,
        String expected,
        String actual,
        Throwable error
    ) {
        String reason = error == null
            ? "unknown"
            : error.getClass().getSimpleName() + ": "
                + String.valueOf(error.getMessage());
        Log.e(
            TAG,
            "direction=" + safeDiagnostic(direction)
                + " record=" + safeDiagnostic(record)
                + " type=" + safeDiagnostic(type)
                + " scene=" + safeDiagnostic(sceneName)
                + " offset=" + offset
                + " expected=" + safeDiagnostic(expected)
                + " actual=" + safeDiagnostic(actual)
                + " stage=" + safeDiagnostic(stage)
                + " reason=" + reason,
            error
        );
    }

    private static String safeDiagnostic(String value) {
        return value == null || value.isEmpty() ? "unknown" : value;
    }

    @Override
    public void close() {
        cancelRequested.set(true);
        acceptingWork = false;
        // The remote game activity owns the Binder method that may be
        // blocked waiting for a Scene payload.  Abort that exact generation
        // before closing HET's pipe endpoints or waiting on worker threads.
        abortGameSceneActivityOnce();
        requestSceneWorkStop();
        // Wake a final REPLACE_BLOCKED_SCENES Binder/pipe call immediately.
        // Failure restoration uses the independent cleanup transport and
        // remains available until run() has finished its pre-cycle attempt.
        // An active run is deliberately not awaited here: an in-flight Binder
        // export may be non-cancellable.  SceneSyncCoordinator retains its
        // FULL_SYNC state until run() returns and releases the operation.
        // A close-before-run has no run() owner, so finalize both transports
        // and the lease directly in that case.
        policyApplyCoordinator.requestClose();
        if (!runStarted.get()) {
            cleanupPolicyApplyCoordinator.close();
            exportHoldLease.close();
        }
    }

    private static void closeQuietly(ParcelFileDescriptor descriptor) {
        if (descriptor == null) {
            return;
        }
        try {
            descriptor.close();
        } catch (IOException ignored) {
        }
    }
}
