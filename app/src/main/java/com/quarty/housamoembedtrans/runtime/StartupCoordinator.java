package com.quarty.housamoembedtrans.runtime;

import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;

/**
 * Linear service-startup coordinator.
 *
 * <p>It runs the fixed order
 * {@code preparation/Scene Sync → Terminal Redelivery Release → optional Review
 * → Unified Recovery Decision → API Work}. Each stage blocks until its external
 * owner signals completion; a failure in any stage moves the coordinator to
 * {@link Phase#FAILED} and notifies one listener.</p>
 */
public final class StartupCoordinator implements AutoCloseable {

    /** Observable phase of the startup sequence. */
    public enum Phase {
        SCENE_SYNC,
        TERMINAL_RELEASE,
        REVIEW,
        RECOVERY,
        API_WORK,
        FAILED,
        CLOSED
    }

    /** Scans/persists both job stores before the Scene Sync stage. */
    public interface StartupPreparation {
        void prepare() throws Exception;
    }

    /** Blocks until the Scene Sync stage is released. */
    public interface SceneSyncWaiter {
        void awaitReleased() throws Exception;
    }

    /** Releases terminal redelivery after Scene Sync. */
    public interface TerminalReleaseAction {
        void release();
    }

    /** Optional Context/Group review gate. Disabled reviews do not wait. */
    public interface ReviewController {
        boolean isEnabled();

        void awaitDecision() throws Exception;

        static ReviewController disabled() {
            return new ReviewController() {
                @Override
                public boolean isEnabled() {
                    return false;
                }

                @Override
                public void awaitDecision() {
                    // Disabled by definition: nothing to wait for.
                }
            };
        }
    }

    /** Blocks until both independent recovery decisions are settled. */
    public interface RecoveryDecisionWaiter {
        void awaitDecisions() throws Exception;
    }

    /** Opens API work once the startup gate has fully passed. */
    public interface ApiWorkOpener {
        void open();
    }

    /** Receives one terminal failure notification. */
    public interface FailureListener {
        void onFailure(Throwable error);
    }

    private final Executor executor;
    private final StartupPreparation preparation;
    private final SceneSyncWaiter sceneSyncWaiter;
    private final TerminalReleaseAction terminalReleaseAction;
    private final ReviewController reviewController;
    private final RecoveryDecisionWaiter recoveryDecisionWaiter;
    private final ApiWorkOpener apiWorkOpener;
    private final FailureListener failureListener;
    private final Object phaseLock = new Object();
    private volatile Phase phase = Phase.SCENE_SYNC;
    private volatile boolean started;

    public StartupCoordinator(
        Executor executor,
        StartupPreparation preparation,
        SceneSyncWaiter sceneSyncWaiter,
        TerminalReleaseAction terminalReleaseAction,
        ReviewController reviewController,
        RecoveryDecisionWaiter recoveryDecisionWaiter,
        ApiWorkOpener apiWorkOpener,
        FailureListener failureListener
    ) {
        if (executor == null
            || preparation == null
            || sceneSyncWaiter == null
            || terminalReleaseAction == null
            || reviewController == null
            || recoveryDecisionWaiter == null
            || apiWorkOpener == null
            || failureListener == null) {
            throw new IllegalArgumentException(
                "all StartupCoordinator collaborators are required"
            );
        }
        this.executor = executor;
        this.preparation = preparation;
        this.sceneSyncWaiter = sceneSyncWaiter;
        this.terminalReleaseAction = terminalReleaseAction;
        this.reviewController = reviewController;
        this.recoveryDecisionWaiter = recoveryDecisionWaiter;
        this.apiWorkOpener = apiWorkOpener;
        this.failureListener = failureListener;
    }

    /** Starts the coordinator once on its executor. */
    public void start() {
        synchronized (phaseLock) {
            if (started || phase == Phase.CLOSED || phase == Phase.FAILED) {
                return;
            }
            started = true;
        }
        try {
            executor.execute(this::run);
        } catch (RejectedExecutionException e) {
            synchronized (phaseLock) {
                phase = Phase.FAILED;
            }
            failureListener.onFailure(e);
        }
    }

    /** Wakes any blocking stage after an external state change. */
    public void onStateChanged() {
        synchronized (phaseLock) {
            phaseLock.notifyAll();
        }
    }

    public Phase getPhase() {
        return phase;
    }

    private void run() {
        try {
            setPhase(Phase.SCENE_SYNC);
            preparation.prepare();
            sceneSyncWaiter.awaitReleased();

            setPhase(Phase.TERMINAL_RELEASE);
            terminalReleaseAction.release();

            setPhase(Phase.REVIEW);
            if (reviewController.isEnabled()) {
                reviewController.awaitDecision();
            }

            setPhase(Phase.RECOVERY);
            recoveryDecisionWaiter.awaitDecisions();

            setPhase(Phase.API_WORK);
            apiWorkOpener.open();
        } catch (Exception e) {
            setPhase(Phase.FAILED);
            failureListener.onFailure(e);
        } catch (Throwable e) {
            setPhase(Phase.FAILED);
            failureListener.onFailure(e);
        }
    }

    private void setPhase(Phase newPhase) {
        synchronized (phaseLock) {
            if (phase == Phase.CLOSED) {
                return;
            }
            phase = newPhase;
            phaseLock.notifyAll();
        }
    }

    @Override
    public void close() {
        synchronized (phaseLock) {
            phase = Phase.CLOSED;
            phaseLock.notifyAll();
        }
    }
}
