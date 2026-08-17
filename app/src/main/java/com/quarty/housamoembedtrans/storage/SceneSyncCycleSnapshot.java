package com.quarty.housamoembedtrans.storage;

/**
 * Immutable conflict decision for one complete Scene Sync cycle.  A cycle
 * captures the mode once; a later settings edit is intentionally invisible to
 * this object and can only be observed by the next cycle's capture.
 */
public final class SceneSyncCycleSnapshot {
    private final String conflictResolutionMode;

    private SceneSyncCycleSnapshot(String conflictResolutionMode) {
        this.conflictResolutionMode =
            SceneSyncSettings.normalizeConflictResolutionMode(
                conflictResolutionMode
            );
    }

    public static SceneSyncCycleSnapshot capture(String conflictResolutionMode) {
        return new SceneSyncCycleSnapshot(conflictResolutionMode);
    }

    public String getConflictResolutionMode() {
        return conflictResolutionMode;
    }
}
