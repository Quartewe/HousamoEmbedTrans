package com.quarty.housamoembedtrans.translation;

import android.os.ParcelFileDescriptor;

/**
 * Connection-scoped Scene mirror port implemented by the game process.
 * Scene bodies and final apply outcomes travel through the supplied pipes,
 * never through a Binder Bundle or translation callback.
 */
interface IGameScenePort {
    /** Installs the current TranslationService registration generation. */
    boolean activateSceneSyncGeneration(long generation);

    /** Invalidates one registration generation; stale calls then fail closed. */
    void deactivateSceneSyncGeneration(long generation);

    /**
     * Starts one export for the service port generation that owns the cycle.
     * The generation token lets a late failure reset only its own native hold.
     */
    ParcelFileDescriptor exportSceneSnapshot(long generation);

    /**
     * Idempotent fail-open cleanup for an accepted export whose final policy
     * publication was not consumed.  A stale generation is ignored.
     */
    void resetSceneProductionPolicy(long generation);

    /** Clears the Java-side hold lease after a policy command was consumed. */
    void completeSceneProductionPolicy(long generation);

    /** Cancels the current game-side apply activity for an exact generation. */
    void abortSceneSyncActivity(long generation);

    /** Applies the durable capture pause latch to the hooked game runtime. */
    void setCapturePaused(boolean paused);

    boolean applySceneChanges(
        long generation,
        in ParcelFileDescriptor requestReadFd,
        in ParcelFileDescriptor resultWriteFd
    );
}
