package com.quarty.housamoembedtrans.translation;

import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Single-flight Scene Sync control plane.
 *
 * <p>The coordinator owns only lifecycle state.  Scene data, Binder calls and
 * worker pools belong to the operation implementations that are scheduled by
 * this class.  All transitions and the API activity count share one lock so a
 * worker can never claim a queued API job after a full or manual refresh
     * reservation.</p>
 */
public final class SceneSyncCoordinator implements AutoCloseable {
    public enum State {
        NONE,
        FULL_SYNC,
        MANUAL_REFRESH,
        MANUAL_APPLY
    }

    /** Distinguishes the only operation that may release startup Wait. */
    public enum SyncOperationKind {
        AUTO_FULL_SYNC,
        MANUAL_REFRESH
    }

    public enum TriggerResult {
        STARTED,
        DEFERRED_ACTIVE_API,
        DEFERRED_BUSY,
        REJECTED_BUSY,
        LOCAL_ONLY,
        CLOSED,
        FAILED
    }

    /** Manual/local action seam with the port captured at the transition. */
    @FunctionalInterface
    public interface SnapshotSyncAction {
        void run(PortSnapshot snapshot) throws Exception;
    }

    /** A manual action whose owned descriptors can be closed by lifecycle. */
    public interface CancelableSnapshotSyncAction extends SnapshotSyncAction {
        void cancel();
    }

    /** Immutable connection identity captured for one FULL_SYNC cycle. */
    public static final class PortSnapshot {
        public final Object port;
        public final Object binder;
        public final long generation;
        public final int sceneWorkerCount;

        private PortSnapshot(
            Object port,
            Object binder,
            long generation,
            int sceneWorkerCount
        ) {
            this.port = port;
            this.binder = binder;
            this.generation = generation;
            this.sceneWorkerCount = sceneWorkerCount;
        }
    }

    @FunctionalInterface
    public interface OperationFinishedListener {
        void onOperationFinished(SyncOperationKind operationKind);
    }

    @FunctionalInterface
    public interface FullSyncAction {
        void run(
            PortSnapshot snapshot,
            SyncOperationKind operationKind
        ) throws Exception;
    }

    /** One-shot completion state for one accepted FULL_SYNC runnable. */
    private static final class OperationCompletionToken {
        private final SyncOperationKind operationKind;
        private final AtomicBoolean signalled = new AtomicBoolean();

        private OperationCompletionToken(SyncOperationKind operationKind) {
            this.operationKind = operationKind;
        }
    }

    private final Object lock = new Object();
    private final Executor coordinatorExecutor;
    private final FullSyncAction fullSyncAction;
    private final SnapshotSyncAction localRefreshAction;
    private PortSnapshot currentPort;
    private PortSnapshot activePort;
    private CancelableSnapshotSyncAction activeManualAction;
    private State state = State.NONE;
    private int activeApiJobs;
    private int apiClaimReservations;
    private boolean pendingAutoSync;
    private boolean closed;
    private long operationGeneration;
    private volatile OperationFinishedListener operationFinishedListener;

    public SceneSyncCoordinator(
        Executor coordinatorExecutor,
        FullSyncAction fullSyncAction,
        SnapshotSyncAction localRefreshAction
    ) {
        if (coordinatorExecutor == null
            || fullSyncAction == null
            || localRefreshAction == null) {
            throw new IllegalArgumentException(
                "executor and Scene Sync actions cannot be null"
            );
        }
        this.coordinatorExecutor = coordinatorExecutor;
        this.fullSyncAction = fullSyncAction;
        this.localRefreshAction = localRefreshAction;
    }

    /**
     * Installs a lightweight wake-up hook for consumers such as the API
     * queue.  It is invoked after a FULL_SYNC or MANUAL_APPLY state transition
     * or MANUAL_REFRESH state transition has been cleaned up, never while the
     * coordinator lock is held.
     */
    public void setOperationFinishedListener(OperationFinishedListener listener) {
        operationFinishedListener = listener;
    }

    /** Registers a port with the connection generation captured by Service. */
    public TriggerResult registerGamePort(
        Object port,
        Object binder,
        long generation,
        int sceneWorkerCount
    ) {
        if (port == null) {
            throw new IllegalArgumentException("game port cannot be null");
        }
        if (binder == null) {
            throw new IllegalArgumentException("game Binder identity cannot be null");
        }
        if (sceneWorkerCount < 1 || sceneWorkerCount > 4) {
            throw new IllegalArgumentException(
                "sceneWorkerCount must be between 1 and 4"
            );
        }
        if (generation <= 0L) {
            throw new IllegalArgumentException(
                "port generation must be positive"
            );
        }
        boolean schedule = false;
        TriggerResult result;
        synchronized (lock) {
            if (closed) {
                return TriggerResult.CLOSED;
            }
            currentPort = new PortSnapshot(
                port,
                binder,
                generation,
                sceneWorkerCount
            );
            if (state == State.FULL_SYNC
                || state == State.MANUAL_REFRESH) {
                // The running cycle remains bound to activePort. The newly
                // installed currentPort must still receive its one automatic
                // cycle after that old reservation reaches finally.
                pendingAutoSync = true;
                return TriggerResult.DEFERRED_BUSY;
            }
            if (state == State.MANUAL_APPLY) {
                pendingAutoSync = true;
                return TriggerResult.DEFERRED_BUSY;
            }
            if (activeApiJobs != 0 || apiClaimReservations != 0) {
                pendingAutoSync = true;
                return TriggerResult.DEFERRED_ACTIVE_API;
            }
            pendingAutoSync = false;
            state = State.FULL_SYNC;
            operationGeneration++;
            activePort = currentPort;
            schedule = true;
            result = TriggerResult.STARTED;
        }
        if (schedule && !enqueueFullSync()) {
            return TriggerResult.FAILED;
        }
        return result;
    }

    /** Requests the same automatic entry point without replacing the port. */
    public TriggerResult requestAutoSync() {
        boolean schedule = false;
        TriggerResult result;
        synchronized (lock) {
            if (closed) {
                return TriggerResult.CLOSED;
            }
            if (currentPort == null) {
                return TriggerResult.LOCAL_ONLY;
            }
            if (state != State.NONE) {
                if (state == State.FULL_SYNC
                    || state == State.MANUAL_REFRESH) {
                    return TriggerResult.REJECTED_BUSY;
                }
                pendingAutoSync = true;
                return TriggerResult.DEFERRED_BUSY;
            }
            if (activeApiJobs != 0 || apiClaimReservations != 0) {
                pendingAutoSync = true;
                return TriggerResult.DEFERRED_ACTIVE_API;
            }
            pendingAutoSync = false;
            state = State.FULL_SYNC;
            operationGeneration++;
            activePort = currentPort;
            schedule = true;
            result = TriggerResult.STARTED;
        }
        if (schedule && !enqueueFullSync()) {
            return TriggerResult.FAILED;
        }
        return result;
    }

    /**
     * Manual refresh shares FULL_SYNC.  With no game port it deliberately only
     * refreshes local HET files and never pretends a game sync succeeded.
     */
    public TriggerResult requestManualRefresh() {
        boolean scheduleFull = false;
        boolean scheduleLocal = false;
        long localGeneration = 0L;
        synchronized (lock) {
            if (closed) {
                return TriggerResult.CLOSED;
            }
            if (state != State.NONE
                || activeApiJobs != 0
                || apiClaimReservations != 0) {
                return TriggerResult.REJECTED_BUSY;
            } else if (currentPort == null) {
                scheduleLocal = true;
            } else {
                state = State.MANUAL_REFRESH;
                operationGeneration++;
                activePort = currentPort;
                scheduleFull = true;
            }
            if (scheduleLocal) {
                state = State.MANUAL_REFRESH;
                localGeneration = ++operationGeneration;
                activePort = null;
            }
        }
        if (scheduleLocal) {
            return enqueueManualSnapshotAction(
                localGeneration,
                null,
                localRefreshAction,
                TriggerResult.LOCAL_ONLY,
                State.MANUAL_REFRESH
            );
        }
        return scheduleFull && enqueueFullSync(
                SyncOperationKind.MANUAL_REFRESH
            )
            ? TriggerResult.STARTED
            : TriggerResult.FAILED;
    }

    /** Starts one serial manual conflict apply operation. */
    public TriggerResult requestManualApply(
        CancelableSnapshotSyncAction action
    ) {
        if (action == null) {
            throw new IllegalArgumentException("manual apply action cannot be null");
        }
        PortSnapshot snapshot;
        synchronized (lock) {
            if (closed) {
                return TriggerResult.CLOSED;
            }
            if (state != State.NONE) {
                return TriggerResult.REJECTED_BUSY;
            }
            state = State.MANUAL_APPLY;
            long generation = ++operationGeneration;
            snapshot = currentPort;
            activePort = snapshot;
            activeManualAction = action;
            try {
                PortSnapshot captured = snapshot;
                coordinatorExecutor.execute(
                    () -> runManualSnapshotAction(
                        action,
                        generation,
                        captured,
                        State.MANUAL_APPLY
                    )
                );
                return TriggerResult.STARTED;
            } catch (RuntimeException e) {
                if (state == State.MANUAL_APPLY
                    && operationGeneration == generation) {
                    state = State.NONE;
                    activePort = null;
                    activeManualAction = null;
                }
                return TriggerResult.FAILED;
            }
        }
    }

    /**
     * Called immediately before an API worker successfully claims a job.
     * MANUAL_APPLY deliberately does not change API admission; a full or
     * manual refresh (or its pending automatic trigger) closes this gate.
     */
    public boolean tryAcquireApiJob() {
        if (!reserveApiJobClaim()) {
            return false;
        }
        commitApiJobClaim();
        return true;
    }

    /** Reserves a claim without counting it as an active API job yet. */
    public boolean reserveApiJobClaim() {
        synchronized (lock) {
            if (closed
                || state == State.FULL_SYNC
                || state == State.MANUAL_REFRESH
                || pendingAutoSync) {
                return false;
            }
            apiClaimReservations++;
            return true;
        }
    }

    /** Converts a successful JobStore claim into one active API job. */
    public void commitApiJobClaim() {
        synchronized (lock) {
            if (apiClaimReservations <= 0) {
                throw new IllegalStateException(
                    "no API claim reservation to commit"
                );
            }
            apiClaimReservations--;
            activeApiJobs++;
        }
    }

    /** Releases an empty/failed claim reservation without creating activity. */
    public void releaseApiJobClaimReservation() {
        boolean schedule;
        synchronized (lock) {
            if (apiClaimReservations <= 0) {
                throw new IllegalStateException(
                    "no API claim reservation to release"
                );
            }
            apiClaimReservations--;
            schedule = shouldStartPendingAutoSyncLocked();
            if (schedule) {
                pendingAutoSync = false;
                state = State.FULL_SYNC;
                operationGeneration++;
                activePort = currentPort;
            }
        }
        if (schedule) {
            enqueueFullSync();
        }
    }

    /** Called exactly once when that claimed API job reaches a terminal state. */
    public void releaseApiJob() {
        boolean schedule = false;
        synchronized (lock) {
            if (activeApiJobs <= 0) {
                throw new IllegalStateException("no active API job to release");
            }
            activeApiJobs--;
            schedule = shouldStartPendingAutoSyncLocked();
            if (schedule) {
                pendingAutoSync = false;
                state = State.FULL_SYNC;
                operationGeneration++;
                activePort = currentPort;
            }
        }
        if (schedule) {
            enqueueFullSync();
        }
    }

    /** Removes the port only when its object identity is still current. */
    public void unregisterGamePort(Object expectedPort) {
        if (expectedPort == null) {
            return;
        }
        CancelableSnapshotSyncAction actionToCancel = null;
        synchronized (lock) {
            if (currentPort != null && currentPort.port == expectedPort) {
                currentPort = null;
                pendingAutoSync = false;
            }
            if (state == State.MANUAL_APPLY
                && activePort != null
                && activePort.port == expectedPort) {
                actionToCancel = activeManualAction;
            }
        }
        if (actionToCancel != null) {
            actionToCancel.cancel();
        }
    }

    public State getState() {
        synchronized (lock) {
            return state;
        }
    }

    public int getActiveApiJobs() {
        synchronized (lock) {
            return activeApiJobs;
        }
    }

    public int getApiClaimReservations() {
        synchronized (lock) {
            return apiClaimReservations;
        }
    }

    public boolean hasGamePort() {
        synchronized (lock) {
            return currentPort != null;
        }
    }

    public boolean isPendingAutoSync() {
        synchronized (lock) {
            return pendingAutoSync;
        }
    }

    @Override
    public void close() {
        CancelableSnapshotSyncAction actionToCancel;
        synchronized (lock) {
            actionToCancel = activeManualAction;
            closed = true;
            currentPort = null;
            activePort = null;
            activeManualAction = null;
            pendingAutoSync = false;
            state = State.NONE;
            operationGeneration++;
        }
        if (actionToCancel != null) {
            actionToCancel.cancel();
        }
    }

    private boolean enqueueFullSync() {
        return enqueueFullSync(SyncOperationKind.AUTO_FULL_SYNC);
    }

    private boolean enqueueFullSync(SyncOperationKind operationKind) {
        long generation;
        PortSnapshot snapshot;
        synchronized (lock) {
            generation = operationGeneration;
            snapshot = activePort;
        }
        return enqueueFullSync(generation, snapshot, operationKind);
    }

    private boolean enqueueFullSync(
        long generation,
        PortSnapshot snapshot,
        SyncOperationKind operationKind
    ) {
        if (snapshot == null || operationKind == null) {
            return false;
        }
        final OperationCompletionToken completion =
            new OperationCompletionToken(operationKind);
        try {
            coordinatorExecutor.execute(() -> {
                boolean accepted;
                synchronized (lock) {
                    accepted = !(closed
                        || operationGeneration != generation
                        || (operationKind == SyncOperationKind.AUTO_FULL_SYNC
                            && state != State.FULL_SYNC)
                        || (operationKind == SyncOperationKind.MANUAL_REFRESH
                            && state != State.MANUAL_REFRESH)
                        || activePort != snapshot);
                }
                if (!accepted) {
                    // The operation was accepted by execute(), even if its
                    // lifecycle state was invalidated before the runnable
                    // started.  Finish only the matching old state (if any),
                    // but still emit one operation-finished wake-up.
                    finishFullSync(
                        generation,
                        snapshot,
                        completion
                    );
                    return;
                }
                try {
                    fullSyncAction.run(snapshot, operationKind);
                } catch (Exception ignored) {
                    // The operation owns diagnostics; lifecycle cleanup is in
                    // finally so a failed data plane cannot wedge admission.
                } finally {
                    finishFullSync(
                        generation,
                        snapshot,
                        completion
                    );
                }
            });
            return true;
        } catch (RuntimeException e) {
            boolean schedulePending = false;
            synchronized (lock) {
                if (((operationKind == SyncOperationKind.AUTO_FULL_SYNC
                        && state == State.FULL_SYNC)
                    || (operationKind == SyncOperationKind.MANUAL_REFRESH
                        && state == State.MANUAL_REFRESH))
                    && operationGeneration == generation
                    && activePort == snapshot) {
                    state = State.NONE;
                    activePort = null;
                    if (shouldStartPendingAutoSyncLocked()) {
                        pendingAutoSync = false;
                        state = State.FULL_SYNC;
                        operationGeneration++;
                        activePort = currentPort;
                        schedulePending = true;
                    }
                }
            }
            if (schedulePending) {
                enqueueFullSync();
            }
            notifyOperationFinishedOnce(completion);
            return false;
        }
    }

    private TriggerResult enqueueManualSnapshotAction(
        long generation,
        PortSnapshot snapshot,
        SnapshotSyncAction action,
        TriggerResult success,
        State expectedState
    ) {
        try {
            coordinatorExecutor.execute(() -> {
                synchronized (lock) {
                    if (closed
                        || operationGeneration != generation
                        || state != expectedState) {
                        return;
                    }
                }
                runManualSnapshotAction(
                    action,
                    generation,
                    snapshot,
                    expectedState
                );
            });
            return success;
        } catch (RuntimeException e) {
            synchronized (lock) {
                if (state == expectedState
                    && operationGeneration == generation) {
                    state = State.NONE;
                    activePort = null;
                }
            }
            return TriggerResult.FAILED;
        }
    }

    private void runManualSnapshotAction(
        SnapshotSyncAction action,
        long generation,
        PortSnapshot snapshot,
        State expectedState
    ) {
        synchronized (lock) {
            if (closed
                || operationGeneration != generation
                || state != expectedState) {
                return;
            }
        }
        try {
            action.run(snapshot);
        } catch (Exception ignored) {
            // The operation owns diagnostics; lifecycle cleanup is in finally.
        } finally {
            boolean schedule = false;
            synchronized (lock) {
                if (state == expectedState
                    && operationGeneration == generation) {
                    state = State.NONE;
                    activePort = null;
                    if (activeManualAction == action) {
                        activeManualAction = null;
                    }
                }
                if (shouldStartPendingAutoSyncLocked()) {
                    pendingAutoSync = false;
                    state = State.FULL_SYNC;
                    operationGeneration++;
                    activePort = currentPort;
                    schedule = true;
                }
            }
            if (schedule) {
                enqueueFullSync();
            }
            notifyOperationFinished(
                expectedState == State.MANUAL_REFRESH
                    ? SyncOperationKind.MANUAL_REFRESH
                    : null
            );
        }
    }

    private void finishFullSync(
        long generation,
        PortSnapshot snapshot,
        OperationCompletionToken completion
    ) {
        boolean schedule = false;
        boolean cleaned = false;
        synchronized (lock) {
            boolean matchingState = completion.operationKind
                == SyncOperationKind.AUTO_FULL_SYNC
                ? state == State.FULL_SYNC
                : state == State.MANUAL_REFRESH;
            if (matchingState
                && operationGeneration == generation
                && activePort == snapshot) {
                state = State.NONE;
                activePort = null;
                cleaned = true;
            }
            if (cleaned && shouldStartPendingAutoSyncLocked()) {
                pendingAutoSync = false;
                state = State.FULL_SYNC;
                operationGeneration++;
                activePort = currentPort;
                schedule = true;
            }
        }
        if (schedule) {
            enqueueFullSync();
        }
        notifyOperationFinishedOnce(completion);
    }

    private void notifyOperationFinishedOnce(
        OperationCompletionToken completion
    ) {
        if (completion != null
            && completion.signalled.compareAndSet(false, true)) {
            notifyOperationFinished(completion.operationKind);
        }
    }

    private void notifyOperationFinished(SyncOperationKind operationKind) {
        OperationFinishedListener listener = operationFinishedListener;
        if (listener == null) {
            return;
        }
        try {
            listener.onOperationFinished(operationKind);
        } catch (RuntimeException ignored) {
            // A wake-up observer must never corrupt coordinator cleanup.
        }
    }

    private boolean shouldStartPendingAutoSyncLocked() {
        return pendingAutoSync
            && state == State.NONE
            && activeApiJobs == 0
            && apiClaimReservations == 0
            && currentPort != null
            && !closed;
    }
}
