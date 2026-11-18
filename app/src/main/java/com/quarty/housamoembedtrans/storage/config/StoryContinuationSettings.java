package com.quarty.housamoembedtrans.storage.config;

import android.net.Uri;
import com.quarty.housamoembedtrans.bridge.HetBridgeContract;
import org.json.JSONException;
import org.json.JSONObject;

/** Immutable content permissions for one story continuation submission. */
public final class StoryContinuationSettings {
    public static Uri configUri() {
        return new Uri.Builder().scheme("content").authority(HetBridgeContract.USER_FILES_AUTHORITY)
            .appendPath(ConfigStore.CONFIG_FILE_NAME).build();
    }
    public static final int DEFAULT_TARGET = 20;
    public final boolean enabled, allowCharacter, allowBackground, allowChoices;
    public final int dialogueTarget;

    private StoryContinuationSettings(JSONObject values) throws JSONException {
        enabled = flag(values, "Enabled");
        allowCharacter = flag(values, "AllowCharacter");
        allowBackground = flag(values, "AllowBackground");
        allowChoices = flag(values, "AllowChoices");
        Object target = values.opt("DialogueTarget");
        if (target == null) {
            dialogueTarget = DEFAULT_TARGET;
        } else {
            if (!(target instanceof Number)) throw new JSONException("DialogueTarget must be numeric");
            double number = ((Number) target).doubleValue();
            if (number < 0 || number > 100 || number % 5 != 0)
                throw new JSONException("DialogueTarget must be 0 through 100 in steps of 5");
            dialogueTarget = (int) number;
        }
    }

    private static boolean flag(JSONObject values, String key) throws JSONException {
        Object value = values.opt(key);
        if (value == null) return false;
        if (value instanceof Boolean) return (Boolean) value;
        throw new JSONException("StoryContinuation." + key + " must be boolean");
    }

    public static StoryContinuationSettings read(JSONObject config) throws JSONException {
        JSONObject settings = config.getJSONObject("UserSettings");
        return new StoryContinuationSettings(settings.has("StoryContinuation")
            ? settings.getJSONObject("StoryContinuation") : new JSONObject());
    }
}
