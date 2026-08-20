package com.quarty.housamoembedtrans.translation;

import com.quarty.housamoembedtrans.bridge.SceneSyncWireCodec;
import com.quarty.housamoembedtrans.storage.ConflictStore;
import com.quarty.housamoembedtrans.storage.PendingSceneApplyStore;
import com.quarty.housamoembedtrans.storage.SceneStore;

import android.util.Log;

import java.io.IOException;
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
        HET_APPLIED,
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
                    action.commitIfActive(
                        () -> sceneStore.saveRawSceneSnapshot(game)
                    );
                    try {
                        action.commitIfActive(
                            () -> pendingStore.remove(selectedScene)
                        );
                    } finally {
                        invalidateConflictAndRequestAutoSync(selectedScene);
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
                    if (result == null || !result.success) {
                        throw new IOException(
                            "manual HET apply was not successful"
                        );
                    }
                    try {
                        action.commitIfActive(
                            () -> pendingStore.remove(selectedScene)
                        );
                    } finally {
                        invalidateConflictAndRequestAutoSync(selectedScene);
                    }
                    publishResolvedScene(
                        snapshot,
                        port,
                        selectedScene,
                        applyCoordinator,
                        action
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
                encodedCommand ->
                    applyCoordinator.replaceBlockedScenesBlocking(
                        snapshot.generation,
                        port,
                        encodedCommand
                    )
            )
        );
        if (result[0] == null || !result[0].isPublished()) {
            throw new IOException(
                "manual blocked-list publication did not succeed"
            );
        }
    }

    private void invalidateConflictAndRequestAutoSync(String sceneName) {
        try {
            conflictStore.remove(sceneName);
        } catch (Exception e) {
            Log.w(
                TAG,
                "Could not remove resolved conflict scene=" + sceneName,
                e
            );
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
