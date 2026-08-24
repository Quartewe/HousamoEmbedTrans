package com.quarty.housamoembedtrans.scene.sync;

/**
 * Immutable settings captured before a game/HET connection is opened.  The
 * worker count is fixed for the lifetime of that connection.  Conflict mode
 * is deliberately not stored here: HET captures it at each cycle boundary so
 * a setting edit is visible to the next cycle without hot-updating workers.
 */
public final class SceneSyncStartupSnapshot {
    private final int sceneWorkerCount;

    private SceneSyncStartupSnapshot(int sceneWorkerCount) {
        this.sceneWorkerCount = SceneSyncSettings.normalizeWorkerCount(
            sceneWorkerCount
        );
    }

    public static SceneSyncStartupSnapshot of(int sceneWorkerCount) {
        return new SceneSyncStartupSnapshot(sceneWorkerCount);
    }

    public int getSceneWorkerCount() {
        return sceneWorkerCount;
    }
}
