package com.quarty.housamoembedtrans.scene.sync;

import com.quarty.housamoembedtrans.bridge.SceneSyncWireCodec;
import com.quarty.housamoembedtrans.management.pending.PendingProcessManager;
import com.quarty.housamoembedtrans.scene.store.ConflictStore;
import com.quarty.housamoembedtrans.scene.store.PendingSceneApplyStore;
import com.quarty.housamoembedtrans.scene.store.SceneStore;
import com.quarty.housamoembedtrans.translation.IGameScenePort;

import android.util.Log;

import java.io.IOException;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Single-action controller for one formal conflict.
 *
 * <p>The coordinator's single-flight MANUAL_APPLY state is the concurrency
 * premise for reading and replacing the shared policy cache.  This controller
 * never reconstructs that complete policy from ConflictStore.  Each accepted
 * action owns one cancellable pipe coordinator so port death and Service
 * teardown can close all active descriptors before any later storage or
 * policy commit.</p>
 */
public final class SceneManualConflictController {
    private static final String TAG = "HET.ManualConflict";

    public enum OutcomeKind {
        GAME_APPLIED,
        GAME_DEFERRED,
        HET_APPLIED,
        HET_DEFERRED,
        HET_PENDING_OFFLINE,
        FAILED
    }

    public static final class Outcome {
        public final String sceneName;
        public final OutcomeKind kind;

        private Outcome(String sceneName, OutcomeKind kind) {
            this.sceneName = sceneName;
            this.kind = kind;
        }
    }

    @FunctionalInterface
    public interface OutcomeListener {
        void onComplete(Outcome outcome);
    }

    @FunctionalInterface
    public interface ApplyCoordinatorFactory {
        SceneApplyCoordinator create();
    }

    @FunctionalInterface
    private interface ManualWork {
        OutcomeKind run(
            SceneSyncCoordinator.PortSnapshot snapshot,
            SceneApplyCoordinator applyCoordinator,
            ManualConflictAction action
        ) throws Exception;
    }

    @FunctionalInterface
    private interface DurableCommit {
        void run() throws Exception;
    }

    private final SceneSyncCoordinator coordinator;
    private final SceneStore sceneStore;
    private final ConflictStore conflictStore;
    private final PendingSceneApplyStore pendingStore;
    private final ScenePolicyPublisher policyPublisher;
    private final ApplyCoordinatorFactory applyCoordinatorFactory;

    public SceneManualConflictController(
        SceneSyncCoordinator coordinator,
        SceneStore sceneStore,
        ConflictStore conflictStore,
        PendingSceneApplyStore pendingStore,
        ScenePolicyPublisher policyPublisher
    ) {
        this(
            coordinator,
            sceneStore,
            conflictStore,
            pendingStore,
            policyPublisher,
            SceneApplyCoordinator::new
        );
    }

    public SceneManualConflictController(
        SceneSyncCoordinator coordinator,
        SceneStore sceneStore,
        ConflictStore conflictStore,
        PendingSceneApplyStore pendingStore,
        ScenePolicyPublisher policyPublisher,
        ApplyCoordinatorFactory applyCoordinatorFactory
    ) {
        if (coordinator == null
            || sceneStore == null
            || conflictStore == null
            || pendingStore == null
            || policyPublisher == null
            || applyCoordinatorFactory == null) {
            throw new IllegalArgumentException(
                "manual conflict dependencies cannot be null"
            );
        }
        this.coordinator = coordinator;
        this.sceneStore = sceneStore;
        this.conflictStore = conflictStore;
        this.pendingStore = pendingStore;
        this.policyPublisher = policyPublisher;
        this.applyCoordinatorFactory = applyCoordinatorFactory;
    }

    /** Chooses the durable game candidate and converges HET locally. */
    public SceneSyncCoordinator.TriggerResult chooseGame(
        String sceneName,
        OutcomeListener listener
    ) {
        final String selectedScene =
            SceneStore.requireSceneName(sceneName);
        return coordinator.requestManualApply(
            new ManualConflictAction(
                selectedScene,
                "game_to_het/local",
                listener,
                (snapshot, applyCoordinator, action) -> {
                    ConflictStore.ConflictRecord conflict =
                        conflictStore.read(selectedScene);
                    SceneStore.RawSceneSnapshot game =
                        sceneStore.validateRawSceneBytes(
                            selectedScene,
                            conflict.gameBytes
                        );
                    final SceneStore.MutationDisposition[] disposition = {
                        SceneStore.MutationDisposition.COMMITTED
                    };
                    action.commitIfActive(
                        () -> disposition[0] = sceneStore
                            .saveRawSceneSnapshot(game)
                            .disposition
                    );
                    if (disposition[0]
                        == SceneStore.MutationDisposition.DEFERRED) {
                        // The HET Scene is only in the durable mutation pool;
                        // retain pending/conflict state until replay commits
                        // the formal local view.
                        return OutcomeKind.GAME_DEFERRED;
                    }
                    if (snapshot != null) {
                        IGameScenePort port = checkedPort(snapshot);
                        publishResolvedScene(
                            snapshot,
                            port,
                            selectedScene,
                            applyCoordinator,
                            action
                        );
                    }
                    action.commitIfActive(
                        () -> pendingStore.remove(selectedScene)
                    );
                    action.commitIfActive(
                        () -> completeResolvedConflict(selectedScene)
                    );
                    return OutcomeKind.GAME_APPLIED;
                }
            )
        );
    }

    /**
     * Chooses the HET candidate.  With no captured port it writes one durable
     * pending directive and keeps both the formal conflict and blocked Scene.
     */
    public SceneSyncCoordinator.TriggerResult chooseHet(
        String sceneName,
        boolean overwriteIfGameChanged,
        OutcomeListener listener
    ) {
        final String selectedScene =
            SceneStore.requireSceneName(sceneName);
        return coordinator.requestManualApply(
            new ManualConflictAction(
                selectedScene,
                "het_to_game/apply",
                listener,
                (snapshot, applyCoordinator, action) -> {
                    ConflictStore.ConflictRecord conflict =
                        conflictStore.read(selectedScene);
                    if (snapshot == null) {
                        action.commitIfActive(
                            () -> pendingStore.save(
                                selectedScene,
                                conflict.hetBytes,
                                conflict.gameSha256,
                                overwriteIfGameChanged
                            )
                        );
                        return OutcomeKind.HET_PENDING_OFFLINE;
                    }

                    IGameScenePort port = checkedPort(snapshot);
                    action.ensureActive();
                    SceneSyncWireCodec.ApplyResult result =
                        applyCoordinator.applyWriteBlocking(
                            snapshot.generation,
                            port,
                            selectedScene,
                            conflict.hetBytes
                        );
                    if (result != null
                        && result.errorCode
                            == SceneSyncWireCodec.APPLY_DEFERRED) {
                        return OutcomeKind.HET_DEFERRED;
                    }
                    if (result == null || !result.success) {
                        throw new IOException(
                            "manual HET apply was not successful"
                        );
                    }
                    publishResolvedScene(
                        snapshot,
                        port,
                        selectedScene,
                        applyCoordinator,
                        action
                    );
                    action.commitIfActive(
                        () -> pendingStore.remove(selectedScene)
                    );
                    action.commitIfActive(
                        () -> completeResolvedConflict(selectedScene)
                    );
                    return OutcomeKind.HET_APPLIED;
                }
            )
        );
    }

    private void publishResolvedScene(
        SceneSyncCoordinator.PortSnapshot snapshot,
        IGameScenePort port,
        String sceneName,
        SceneApplyCoordinator applyCoordinator,
        ManualConflictAction action
    ) throws Exception {
        AtomicBoolean policyTransportAttempted = new AtomicBoolean();
        try {
            synchronized (PendingProcessManager.POLICY_PUBLICATION_LOCK) {
            // Capture the complete management overlay while holding the same
            // lock as PendingProcessManager mutations.  The initial action
            // check is only an early rejection; this snapshot closes the
            // publication race and is passed through to the publisher.
            Set<String> freshManagement =
                sceneStore.snapshotManagementPendingSceneNames();
            if (freshManagement.contains(sceneName)) {
                throw new IOException(
                    "manual conflict publication rejected: management pending "
                        + sceneName
                );
            }
            ScenePolicyPublisher.Target target = policyPublisher.capture(
                port,
                snapshot.binder,
                snapshot.generation
            );
            if (target == null) {
                throw new IOException("manual policy target is stale");
            }
            final ScenePolicyPublisher.PublishResult[] result = {null};
            action.commitIfActive(() -> result[0] =
                policyPublisher.removeAndPublish(
                    target,
                    sceneName,
                    freshManagement,
                    encodedCommand ->
                        {
                            // A preflight rejection (stale target, management
                            // overlay race, or cancellation before the
                            // publisher enters its transport callback) must
                            // not reset a full-sync hold that this action did
                            // not acquire or exercise.
                            policyTransportAttempted.set(true);
                            return applyCoordinator.replaceBlockedScenesBlocking(
                                snapshot.generation,
                                port,
                                encodedCommand
                            );
                        }
                )
            );
            if (result[0] != null && result[0].isPublished()) {
                // APPLY_RESULT delivery is not the HET acknowledgement.  Keep
                // the game-side generation marker until this explicit callback
                // confirms that the publisher's PUBLISHED result was observed.
                try {
                    port.completeSceneProductionPolicy(snapshot.generation);
                } catch (android.os.RemoteException | RuntimeException ackFailure) {
                    // Native policy publication already succeeded; a failed
                    // bookkeeping callback must not reset a newer/valid policy.
                    Log.w(
                        TAG,
                        "Could not acknowledge manual Scene policy generation="
                            + snapshot.generation,
                        ackFailure
                    );
                }
                return;
            }
            if (result[0] == null || !result[0].isPublished()) {
                throw new IOException(
                    "manual blocked-list publication did not succeed"
                );
            }
            }
        } catch (Exception failure) {
            // Every path that did not obtain a PUBLISHED result (including
            // cancellation, stale targets, transport errors, and exceptions)
            // must fail open only this generation.  The port performs the
            // generation check, so a late cleanup cannot clear a newer hold.
            if (policyTransportAttempted.get()) {
                try {
                    port.resetSceneProductionPolicy(snapshot.generation);
                } catch (android.os.RemoteException | RuntimeException resetFailure) {
                    failure.addSuppressed(resetFailure);
                    Log.w(
                        TAG,
                        "Could not fail-open manual Scene policy generation="
                            + snapshot.generation,
                        failure
                    );
                }
            }
            throw failure;
        }
    }

    private void completeResolvedConflict(String sceneName) throws IOException {
        completeResolvedConflict(conflictStore, coordinator, sceneName);
    }

    /**
     * Package-private durable batch-end seam used by host fixtures.  The
     * instance controller delegates to this exact implementation so tests do
     * not reimplement claim deletion or strict backlog classification.
     */
    static void completeResolvedConflict(
        ConflictStore conflictStore,
        SceneSyncCoordinator coordinator,
        String sceneName
    ) throws IOException {
        completeResolvedConflict(
            conflictStore,
            coordinator,
            sceneName,
            conflictStore::listClaimedSceneNamesStrict
        );
    }

    @FunctionalInterface
    interface StrictClaimEnumerator {
        List<String> list() throws IOException;
    }

    static void completeResolvedConflict(
        ConflictStore conflictStore,
        SceneSyncCoordinator coordinator,
        String sceneName,
        StrictClaimEnumerator claimEnumerator
    ) throws IOException {
        if (conflictStore == null || coordinator == null
            || claimEnumerator == null) {
            throw new IllegalArgumentException(
                "conflictStore and coordinator cannot be null"
            );
        }
        // The formal claim is the durable completion boundary.  Never remove
        // it before pending cleanup and (when online) policy publication have
        // both succeeded, otherwise a failed retry would lose the conflict.
        conflictStore.remove(sceneName);
        final boolean backlogEmpty;
        try {
            // The convenience complete-name view hides damaged claims;
            // only strict formal enumeration can prove that the durable
            // conflict backlog is empty and therefore end this batch.
            backlogEmpty = claimEnumerator.list().isEmpty();
        } catch (IOException e) {
            Log.w(
                TAG,
                "Could not verify conflict backlog after scene=" + sceneName,
                e
            );
            return;
        }
        if (!backlogEmpty) {
            return;
        }
        SceneSyncCoordinator.TriggerResult trigger =
            coordinator.requestAutoSync();
        if (trigger == SceneSyncCoordinator.TriggerResult.FAILED
            || trigger == SceneSyncCoordinator.TriggerResult.CLOSED) {
            Log.w(
                TAG,
                "Could not schedule reconciliation scene="
                    + sceneName
                    + " result="
                    + trigger
            );
        }
    }

    private final class ManualConflictAction
        implements SceneSyncCoordinator.CancelableSnapshotSyncAction {
        private final String sceneName;
        private final String direction;
        private final OutcomeListener listener;
        private final ManualWork work;
        private final AtomicBoolean cancelled = new AtomicBoolean();
        private final AtomicReference<SceneApplyCoordinator> activeApply =
            new AtomicReference<>();
        private final Object commitLock = new Object();

        private ManualConflictAction(
            String sceneName,
            String direction,
            OutcomeListener listener,
            ManualWork work
        ) {
            this.sceneName = sceneName;
            this.direction = direction;
            this.listener = listener;
            this.work = work;
        }

        @Override
        public void run(SceneSyncCoordinator.PortSnapshot snapshot) {
            SceneApplyCoordinator applyCoordinator = null;
            Outcome outcome;
            try {
                ensureActive();
                if (sceneStore.snapshotManagementPendingSceneNames()
                    .contains(sceneName)) {
                    throw new IOException(
                        "manual conflict action rejected: management pending "
                            + sceneName
                    );
                }
                applyCoordinator = applyCoordinatorFactory.create();
                if (applyCoordinator == null) {
                    throw new IOException(
                        "manual apply coordinator is unavailable"
                    );
                }
                if (!activeApply.compareAndSet(null, applyCoordinator)) {
                    throw new IOException(
                        "manual apply coordinator is already active"
                    );
                }
                ensureActive();
                OutcomeKind kind = work.run(
                    snapshot,
                    applyCoordinator,
                    this
                );
                outcome = new Outcome(sceneName, kind);
            } catch (Exception e) {
                logManualFailure(direction, sceneName, e);
                outcome = new Outcome(sceneName, OutcomeKind.FAILED);
            } finally {
                SceneApplyCoordinator active =
                    activeApply.getAndSet(null);
                if (active != null) {
                    active.close();
                } else if (applyCoordinator != null) {
                    applyCoordinator.close();
                }
            }
            notifyOutcome(listener, outcome);
        }

        @Override
        public void cancel() {
            cancelled.set(true);
            SceneApplyCoordinator active = activeApply.get();
            if (active != null) {
                active.close();
            }
            // Wait for any atomic local commit that entered before cancel.
            // Once this barrier returns, later commits observe cancelled.
            synchronized (commitLock) {
                // Barrier only.
            }
        }

        private void ensureActive() throws IOException {
            if (cancelled.get()) {
                throw new IOException("manual conflict action was cancelled");
            }
        }

        private void commitIfActive(DurableCommit commit)
            throws Exception {
            synchronized (commitLock) {
                ensureActive();
                commit.run();
            }
        }
    }


    private static void logManualFailure(
        String direction,
        String sceneName,
        Exception error
    ) {
        Log.e(
            TAG,
            "direction=" + direction
                + " record=unknown type=MANUAL_CONFLICT"
                + " scene=" + sceneName
                + " offset=-1 expected=successful manual resolution"
                + " actual=" + error.getClass().getSimpleName()
                + " stage=manual_conflict"
                + " reason=" + String.valueOf(error.getMessage()),
            error
        );
    }
    private static IGameScenePort checkedPort(
        SceneSyncCoordinator.PortSnapshot snapshot
    ) throws IOException {
        if (!(snapshot.port instanceof IGameScenePort)) {
            throw new IOException("manual port snapshot is invalid");
        }
        IGameScenePort port = (IGameScenePort) snapshot.port;
        if (port.asBinder() != snapshot.binder) {
            throw new IOException("manual port Binder identity changed");
        }
        return port;
    }

    private static void notifyOutcome(
        OutcomeListener listener,
        Outcome outcome
    ) {
        if (listener == null) {
            return;
        }
        try {
            listener.onComplete(outcome);
        } catch (RuntimeException ignored) {
            // UI observers cannot corrupt the coordinator transition.
        }
    }
}
