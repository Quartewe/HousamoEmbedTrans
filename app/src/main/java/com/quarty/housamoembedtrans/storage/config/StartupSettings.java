package com.quarty.housamoembedtrans.storage.config;

import com.quarty.housamoembedtrans.scene.sync.SceneSyncSettings;

import org.json.JSONObject;

/**
 * Pure user-setting rules shared by the app store and the game startup snapshot.
 * Keep this class independent of ConfigStore: the game uses these rules without
 * loading app-only file management and dictionary validation.
 */
public final class StartupSettings {
    private StartupSettings() {}

    public static boolean isRubyTranslationEnabled(JSONObject userSettings) throws Exception {
        if (userSettings.has("EnableRubyTranslation")
            && !(userSettings.get("EnableRubyTranslation") instanceof Boolean)) {
            throw new IllegalArgumentException(
                "UserSettings.EnableRubyTranslation must be a boolean"
            );
        }
        return userSettings.optBoolean("EnableRubyTranslation", false);
    }

    /**
     * Applies the Scene Sync defaults in-place while rejecting malformed
     * values.  Unknown UserSettings members remain untouched.
     */
    public static void normalizeSceneSyncSettings(JSONObject userSettings)
        throws Exception {
        if (userSettings == null) {
            throw new IllegalArgumentException("UserSettings must be an object");
        }

        if (!userSettings.has("SceneWorkerCount")) {
            userSettings.put(
                "SceneWorkerCount",
                SceneSyncSettings.DEFAULT_SCENE_WORKER_COUNT
            );
        } else {
            userSettings.put(
                "SceneWorkerCount",
                SceneSyncSettings.normalizeWorkerCount(
                    userSettings.get("SceneWorkerCount")
                )
            );
        }

        JSONObject sceneSync;
        if (!userSettings.has("SceneSync")) {
            sceneSync = new JSONObject();
            userSettings.put("SceneSync", sceneSync);
        } else {
            Object rawSceneSync = userSettings.get("SceneSync");
            if (!(rawSceneSync instanceof JSONObject)) {
                throw new IllegalArgumentException(
                    "UserSettings.SceneSync must be an object"
                );
            }
            sceneSync = (JSONObject) rawSceneSync;
        }

        if (!sceneSync.has("AutoSyncOnNewTranslation")) {
            sceneSync.put("AutoSyncOnNewTranslation", true);
        }
        requireBoolean(sceneSync, "AutoSyncOnNewTranslation", "UserSettings.SceneSync");

        if (!sceneSync.has("ConflictResolutionMode")) {
            sceneSync.put(
                "ConflictResolutionMode",
                SceneSyncSettings.DEFAULT_CONFLICT_RESOLUTION_MODE
            );
        } else {
            sceneSync.put(
                "ConflictResolutionMode",
                SceneSyncSettings.normalizeConflictResolutionMode(
                    sceneSync.get("ConflictResolutionMode")
                )
            );
        }
    }

    /** Returns the validated worker count from a startup settings snapshot. */
    public static int getSceneWorkerCount(JSONObject userSettings)
        throws Exception {
        return userSettings != null && userSettings.has("SceneWorkerCount")
            ? SceneSyncSettings.normalizeWorkerCount(
                userSettings.get("SceneWorkerCount")
            )
            : SceneSyncSettings.DEFAULT_SCENE_WORKER_COUNT;
    }

    static boolean requireBoolean(
        JSONObject object,
        String key,
        String path
    ) throws Exception {
        Object value = object.get(key);
        if (!(value instanceof Boolean)) {
            throw new IllegalArgumentException(
                path + "." + key + " must be a boolean"
            );
        }
        return (Boolean) value;
    }

}
