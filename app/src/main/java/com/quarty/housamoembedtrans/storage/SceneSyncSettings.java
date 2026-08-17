package com.quarty.housamoembedtrans.storage;

/**
 * Pure validation and defaulting rules shared by the settings editor and the
 * game-start snapshot.  JSON-shaped callers pass the value they read from
 * UserSettings; missing values are represented by null.
 */
public final class SceneSyncSettings {
    public static final int DEFAULT_SCENE_WORKER_COUNT = 4;
    public static final int MIN_SCENE_WORKER_COUNT = 1;
    public static final int MAX_SCENE_WORKER_COUNT = 4;
    public static final String DEFAULT_CONFLICT_RESOLUTION_MODE = "manual";
    public static final String CONFLICT_MODE_GAME = "game";
    public static final String CONFLICT_MODE_HET = "het";
    public static final String CONFLICT_MODE_MANUAL = "manual";

    private SceneSyncSettings() {}

    /** Returns the validated worker count, or the default when the value is absent. */
    public static int normalizeWorkerCount(Object raw) {
        if (raw == null) {
            return DEFAULT_SCENE_WORKER_COUNT;
        }
        if (!(raw instanceof Number)) {
            throw new IllegalArgumentException(
                "UserSettings.SceneWorkerCount must be an integer"
            );
        }

        double value = ((Number) raw).doubleValue();
        if (!Double.isFinite(value)
            || value != Math.rint(value)
            || value < MIN_SCENE_WORKER_COUNT
            || value > MAX_SCENE_WORKER_COUNT) {
            throw new IllegalArgumentException(
                "UserSettings.SceneWorkerCount must be an integer from "
                    + MIN_SCENE_WORKER_COUNT
                    + " to "
                    + MAX_SCENE_WORKER_COUNT
            );
        }
        return (int) value;
    }

    /** Returns the validated conflict mode, or manual when the value is absent. */
    public static String normalizeConflictResolutionMode(Object raw) {
        if (raw == null) {
            return DEFAULT_CONFLICT_RESOLUTION_MODE;
        }
        if (!(raw instanceof String)) {
            throw new IllegalArgumentException(
                "UserSettings.SceneSync.ConflictResolutionMode must be a string"
            );
        }

        String mode = (String) raw;
        if (!CONFLICT_MODE_GAME.equals(mode)
            && !CONFLICT_MODE_HET.equals(mode)
            && !CONFLICT_MODE_MANUAL.equals(mode)) {
            throw new IllegalArgumentException(
                "UserSettings.SceneSync.ConflictResolutionMode must be game, het, or manual"
            );
        }
        return mode;
    }
}
