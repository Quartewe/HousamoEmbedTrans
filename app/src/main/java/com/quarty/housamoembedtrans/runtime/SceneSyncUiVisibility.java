package com.quarty.housamoembedtrans.runtime;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/** Process-local visibility counters for the two Scene Sync UI surfaces. */
public final class SceneSyncUiVisibility {
    private static final AtomicInteger SCENE_FILES_VISIBLE =
        new AtomicInteger();
    private static final AtomicInteger SCENE_CONFLICTS_VISIBLE =
        new AtomicInteger();

    private SceneSyncUiVisibility() {
        throw new AssertionError("No instances");
    }

    public static ActivityFlag newSceneFilesFlag() {
        return new ActivityFlag(SCENE_FILES_VISIBLE);
    }

    public static ActivityFlag newSceneConflictsFlag() {
        return new ActivityFlag(SCENE_CONFLICTS_VISIBLE);
    }

    public static boolean isSceneSyncUiVisible() {
        return SCENE_FILES_VISIBLE.get() > 0
            || SCENE_CONFLICTS_VISIBLE.get() > 0;
    }

    public static int getSceneFilesVisibleCount() {
        return SCENE_FILES_VISIBLE.get();
    }

    public static int getSceneConflictsVisibleCount() {
        return SCENE_CONFLICTS_VISIBLE.get();
    }

    /**
     * One idempotent flag per Activity instance. Repeated lifecycle callbacks
     * cannot double-increment or underflow the shared process counter.
     */
    public static final class ActivityFlag implements AutoCloseable {
        private final AtomicInteger counter;
        private final AtomicBoolean visible = new AtomicBoolean();

        private ActivityFlag(AtomicInteger counter) {
            this.counter = counter;
        }

        public void setVisible(boolean nextVisible) {
            if (nextVisible) {
                if (visible.compareAndSet(false, true)) {
                    counter.incrementAndGet();
                }
            } else if (visible.compareAndSet(true, false)) {
                counter.decrementAndGet();
            }
        }

        public boolean isVisible() {
            return visible.get();
        }

        @Override
        public void close() {
            setVisible(false);
        }
    }
}
