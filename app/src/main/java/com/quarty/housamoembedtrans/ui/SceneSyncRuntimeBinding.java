package com.quarty.housamoembedtrans.ui;

import android.app.Activity;

import com.quarty.housamoembedtrans.runtime.SceneSyncRuntimeState;
import com.quarty.housamoembedtrans.runtime.SceneSyncUiVisibility;
import com.quarty.housamoembedtrans.runtime.TranslationStatusNotification;

/**
 * Composition helper for binding one Activity to runtime snapshots.
 *
 * <p>It owns listener identity, lifecycle generations, visibility and stale
 * callback guards. Pages remain responsible for rendering their own text and
 * actions and provide only a snapshot callback.</p>
 */
final class SceneSyncRuntimeBinding {
    @FunctionalInterface
    interface Dispatcher {
        void post(Runnable action);
    }

    @FunctionalInterface
    interface ActivityGuard {
        boolean isInvalid();
    }

    @FunctionalInterface
    interface NotificationRefresher {
        void refresh();
    }

    @FunctionalInterface
    interface SnapshotRenderer {
        void render(SceneSyncRuntimeState.Snapshot snapshot);
    }

    private final SceneSyncRuntimeState runtimeState;
    private final SceneSyncUiVisibility.ActivityFlag visibilityFlag;
    private final SnapshotRenderer renderer;
    private final Dispatcher dispatcher;
    private final ActivityGuard activityGuard;
    private final NotificationRefresher notificationRefresher;
    private boolean started;
    private long lifecycleGeneration;
    private SceneSyncRuntimeState.Listener runtimeListener;

    SceneSyncRuntimeBinding(
        Activity activity,
        SceneSyncRuntimeState runtimeState,
        SceneSyncUiVisibility.ActivityFlag visibilityFlag,
        SnapshotRenderer renderer
    ) {
        this(
            activity,
            runtimeState,
            visibilityFlag,
            renderer,
            activity == null ? null : activity::runOnUiThread,
            activity == null
                ? null
                : () -> activity.isFinishing() || activity.isDestroyed(),
            activity == null
                ? null
                : () -> TranslationStatusNotification.refresh(activity)
        );
    }

    /** Package-private host seam; production uses the Activity adapter above. */
    SceneSyncRuntimeBinding(
        Activity activity,
        SceneSyncRuntimeState runtimeState,
        SceneSyncUiVisibility.ActivityFlag visibilityFlag,
        SnapshotRenderer renderer,
        Dispatcher dispatcher,
        ActivityGuard activityGuard,
        NotificationRefresher notificationRefresher
    ) {
        if (runtimeState == null
            || visibilityFlag == null
            || renderer == null
            || dispatcher == null
            || activityGuard == null
            || notificationRefresher == null) {
            throw new IllegalArgumentException(
                "Scene Sync runtime binding dependencies are required"
            );
        }
        this.runtimeState = runtimeState;
        this.visibilityFlag = visibilityFlag;
        this.renderer = renderer;
        this.dispatcher = dispatcher;
        this.activityGuard = activityGuard;
        this.notificationRefresher = notificationRefresher;
    }

    void start() {
        if (started) {
            return;
        }
        started = true;
        final long generation = ++lifecycleGeneration;
        SceneSyncRuntimeState.Listener listener = changed ->
            dispatcher.post(() -> {
                if (!started
                    || lifecycleGeneration != generation
                    || activityGuard.isInvalid()) {
                    return;
                }
                renderer.render(changed);
            });
        runtimeListener = listener;
        visibilityFlag.setVisible(true);
        runtimeState.addListener(listener);
    }

    void stop() {
        if (!started && runtimeListener == null) {
            visibilityFlag.setVisible(false);
            notificationRefresher.refresh();
            return;
        }
        started = false;
        ++lifecycleGeneration;
        SceneSyncRuntimeState.Listener listener = runtimeListener;
        runtimeListener = null;
        runtimeState.removeListener(listener);
        visibilityFlag.setVisible(false);
        notificationRefresher.refresh();
    }
}
