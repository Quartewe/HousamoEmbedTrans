package com.quarty.housamoembedtrans.runtime;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Process-local, Android-free state seam for Scene Sync UI consumers.
 *
 * <p>The active Service owns a {@link Controller}. Its object identity is also
 * the publication token, so callbacks from an older Service instance cannot
 * detach or overwrite the replacement instance. Snapshots contain only
 * presentation-safe values and are immutable after construction.</p>
 */
public final class SceneSyncRuntimeState {
    public enum Phase {
        IDLE,
        FULL_SYNC,
        MANUAL_REFRESH,
        MANUAL_APPLY
    }

    public enum Direction {
        GAME_TO_HET,
        HET_TO_GAME,
        BIDIRECTIONAL,
        LOCAL,
        UNKNOWN
    }

    public enum Status {
        PROCESSED,
        DELETED,
        NEEDS_ATTENTION,
        NOT_PROCESSED
    }

    public enum Action {
        NONE,
        SERVICE_STARTED,
        SERVICE_STOPPED,
        PORT_REGISTERED,
        PORT_UNREGISTERED,
        AUTO_SYNC,
        MANUAL_REFRESH,
        LOCAL_REFRESH,
        CHOOSE_GAME,
        CHOOSE_HET,
        API_ACTIVITY
    }

    public enum Outcome {
        NONE,
        STARTED,
        DEFERRED,
        BUSY,
        LOCAL_ONLY,
        SUCCEEDED,
        NEEDS_ATTENTION,
        FAILED,
        UNAVAILABLE
    }

    /** Immutable, UI-safe summary of one Scene in the latest fixed snapshot. */
    public static final class SceneSummary {
        public final String sceneName;
        public final Direction direction;
        public final Status status;

        public SceneSummary(
            String sceneName,
            Direction direction,
            Status status
        ) {
            if (sceneName == null || sceneName.isEmpty()) {
                throw new IllegalArgumentException("sceneName cannot be empty");
            }
            if (direction == null || status == null) {
                throw new IllegalArgumentException(
                    "direction and status cannot be null"
                );
            }
            this.sceneName = sceneName;
            this.direction = direction;
            this.status = status;
        }

        private SceneSummary(SceneSummary source) {
            this(source.sceneName, source.direction, source.status);
        }
    }

    /** Complete immutable state delivered to Activity/notification observers. */
    public static final class Snapshot {
        public final boolean serviceAvailable;
        public final boolean gamePortAvailable;
        public final Phase phase;
        public final int activeApiJobs;
        public final int pendingConflictCount;
        public final Action lastAction;
        public final Outcome lastOutcome;
        public final List<SceneSummary> sceneSummaries;

        public Snapshot(
            boolean serviceAvailable,
            boolean gamePortAvailable,
            Phase phase,
            int activeApiJobs,
            int pendingConflictCount,
            Action lastAction,
            Outcome lastOutcome,
            List<SceneSummary> sceneSummaries
        ) {
            if (phase == null || lastAction == null || lastOutcome == null) {
                throw new IllegalArgumentException(
                    "phase, lastAction and lastOutcome cannot be null"
                );
            }
            if (activeApiJobs < 0 || pendingConflictCount < 0) {
                throw new IllegalArgumentException(
                    "activity and conflict counts cannot be negative"
                );
            }
            if (sceneSummaries == null) {
                throw new IllegalArgumentException(
                    "sceneSummaries cannot be null"
                );
            }
            ArrayList<SceneSummary> copied = new ArrayList<>(
                sceneSummaries.size()
            );
            for (SceneSummary summary : sceneSummaries) {
                if (summary == null) {
                    throw new IllegalArgumentException(
                        "sceneSummaries cannot contain null"
                    );
                }
                copied.add(new SceneSummary(summary));
            }
            this.serviceAvailable = serviceAvailable;
            this.gamePortAvailable = gamePortAvailable;
            this.phase = phase;
            this.activeApiJobs = activeApiJobs;
            this.pendingConflictCount = pendingConflictCount;
            this.lastAction = lastAction;
            this.lastOutcome = lastOutcome;
            this.sceneSummaries = Collections.unmodifiableList(copied);
        }

        private static Snapshot offline() {
            return new Snapshot(
                false,
                false,
                Phase.IDLE,
                0,
                0,
                Action.NONE,
                Outcome.NONE,
                Collections.emptyList()
            );
        }

        private Snapshot withActionOutcome(Action action, Outcome outcome) {
            return new Snapshot(
                serviceAvailable,
                gamePortAvailable,
                phase,
                activeApiJobs,
                pendingConflictCount,
                action,
                outcome,
                sceneSummaries
            );
        }

        private Snapshot detached() {
            return new Snapshot(
                false,
                false,
                Phase.IDLE,
                0,
                pendingConflictCount,
                Action.SERVICE_STOPPED,
                Outcome.UNAVAILABLE,
                sceneSummaries
            );
        }
    }

    @FunctionalInterface
    public interface Listener {
        void onSnapshotChanged(Snapshot snapshot);
    }

    /** Commands implemented by the currently attached Service instance. */
    public interface Controller {
        Outcome requestRefresh();

        Outcome chooseGame(String sceneName);

        Outcome chooseHet(
            String sceneName,
            boolean overwriteIfGameChanged
        );
    }

    private static final SceneSyncRuntimeState INSTANCE =
        new SceneSyncRuntimeState();

    private final Object lock = new Object();
    private final Object controllerActionLock = new Object();
    private final CopyOnWriteArrayList<Listener> listeners =
        new CopyOnWriteArrayList<>();
    private volatile Snapshot snapshot = Snapshot.offline();
    private Controller controller;

    /** Returns the process-global state used by Service and UI integration. */
    public static SceneSyncRuntimeState getInstance() {
        return INSTANCE;
    }

    /** Public so host fixtures can exercise lifecycle races in isolation. */
    public SceneSyncRuntimeState() {
    }

    public Snapshot getSnapshot() {
        return snapshot;
    }

    /**
     * Installs or replaces the active controller and publishes its initial
     * state. A later stale detach/publish is rejected by identity.
     */
    public void attach(Controller newController, Snapshot initialSnapshot) {
        if (newController == null || initialSnapshot == null) {
            throw new IllegalArgumentException(
                "controller and initialSnapshot cannot be null"
            );
        }
        synchronized (controllerActionLock) {
            synchronized (lock) {
                controller = newController;
                snapshot = initialSnapshot;
            }
        }
        notifyListeners(initialSnapshot);
    }

    /** Detaches only the controller that is still active. */
    public boolean detach(Controller expectedController) {
        if (expectedController == null) {
            return false;
        }
        Snapshot detached;
        synchronized (controllerActionLock) {
            synchronized (lock) {
                if (controller != expectedController) {
                    return false;
                }
                controller = null;
                detached = snapshot.detached();
                snapshot = detached;
            }
        }
        notifyListeners(detached);
        return true;
    }

    /** Publishes state only for the controller that is still active. */
    public boolean publish(
        Controller expectedController,
        Snapshot nextSnapshot
    ) {
        if (expectedController == null || nextSnapshot == null) {
            throw new IllegalArgumentException(
                "controller and nextSnapshot cannot be null"
            );
        }
        synchronized (lock) {
            if (controller != expectedController) {
                return false;
            }
            snapshot = nextSnapshot;
        }
        notifyListeners(nextSnapshot);
        return true;
    }

    /**
     * Registers an observer once and immediately provides the latest state.
     * One broken observer cannot prevent other observers or Service cleanup.
     */
    public void addListener(Listener listener) {
        if (listener == null) {
            throw new IllegalArgumentException("listener cannot be null");
        }
        if (listeners.addIfAbsent(listener)) {
            notifyListener(listener, snapshot);
        }
    }

    public void removeListener(Listener listener) {
        if (listener != null) {
            listeners.remove(listener);
        }
    }

    /**
     * Proxies refresh to the active Service. Without one, callers can still
     * refresh their local presentation and receive the explicit LOCAL_ONLY
     * outcome rather than mistaking it for a game sync.
     */
    public Outcome requestRefresh() {
        synchronized (controllerActionLock) {
            Controller current = currentController();
            if (current == null) {
                return publishUnavailable(
                    Action.LOCAL_REFRESH,
                    Outcome.LOCAL_ONLY
                );
            }
            try {
                return finishControllerAction(
                    current,
                    Action.MANUAL_REFRESH,
                    current.requestRefresh()
                );
            } catch (RuntimeException ignored) {
                publishControllerFailure(current, Action.MANUAL_REFRESH);
                return Outcome.FAILED;
            }
        }
    }

    public Outcome chooseGame(String sceneName) {
        synchronized (controllerActionLock) {
            Controller current = currentController();
            if (current == null) {
                return publishUnavailable(
                    Action.CHOOSE_GAME,
                    Outcome.UNAVAILABLE
                );
            }
            try {
                return finishControllerAction(
                    current,
                    Action.CHOOSE_GAME,
                    current.chooseGame(sceneName)
                );
            } catch (RuntimeException ignored) {
                publishControllerFailure(current, Action.CHOOSE_GAME);
                return Outcome.FAILED;
            }
        }
    }

    public Outcome chooseHet(
        String sceneName,
        boolean overwriteIfGameChanged
    ) {
        synchronized (controllerActionLock) {
            Controller current = currentController();
            if (current == null) {
                return publishUnavailable(
                    Action.CHOOSE_HET,
                    Outcome.UNAVAILABLE
                );
            }
            try {
                return finishControllerAction(
                    current,
                    Action.CHOOSE_HET,
                    current.chooseHet(sceneName, overwriteIfGameChanged)
                );
            } catch (RuntimeException ignored) {
                publishControllerFailure(current, Action.CHOOSE_HET);
                return Outcome.FAILED;
            }
        }
    }

    private Controller currentController() {
        synchronized (lock) {
            return controller;
        }
    }

    private Outcome publishUnavailable(Action action, Outcome outcome) {
        Snapshot changed;
        synchronized (lock) {
            if (controller != null) {
                return outcome;
            }
            changed = snapshot.withActionOutcome(action, outcome);
            snapshot = changed;
        }
        notifyListeners(changed);
        return outcome;
    }

    private Outcome finishControllerAction(
        Controller expectedController,
        Action action,
        Outcome outcome
    ) {
        if (outcome != null) {
            return outcome;
        }
        publishControllerFailure(expectedController, action);
        return Outcome.FAILED;
    }

    private void publishControllerFailure(
        Controller expectedController,
        Action action
    ) {
        Snapshot changed;
        synchronized (lock) {
            if (controller != expectedController) {
                return;
            }
            changed = snapshot.withActionOutcome(action, Outcome.FAILED);
            snapshot = changed;
        }
        notifyListeners(changed);
    }

    private void notifyListeners(Snapshot changed) {
        for (Listener listener : listeners) {
            notifyListener(listener, changed);
        }
    }

    private static void notifyListener(Listener listener, Snapshot changed) {
        try {
            listener.onSnapshotChanged(changed);
        } catch (RuntimeException ignored) {
            // Observers are outside the state machine's lifecycle boundary.
        }
    }
}
