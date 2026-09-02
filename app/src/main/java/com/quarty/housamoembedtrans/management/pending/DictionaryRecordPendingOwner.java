package com.quarty.housamoembedtrans.management.pending;

import com.quarty.housamoembedtrans.storage.config.ConfigStore;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/**
 * Pending owner for one record in the Java character or game-term dictionary.
 *
 * <p>The dictionaries are aggregate JSON documents, therefore this adapter
 * never moves a whole dictionary into PendingProcess.  Its snapshot is the
 * exact pair {@code {"name": canonicalId, "record": record}}.  Every owner
 * action re-reads the current key and compares the complete JSON value before
 * calling ConfigStore, so an edit made while the entry is pending cannot be
 * silently overwritten.</p>
 */
public final class DictionaryRecordPendingOwner
    implements PendingProcessOwner {

    public static final String KIND_CHARACTER = "character";
    public static final String KIND_TERM = "term";
    public static final String MAIN_CHARACTER_KEY = "mc";

    private final ConfigStore configStore;
    private final String kind;

    private DictionaryRecordPendingOwner(ConfigStore configStore, String kind) {
        if (configStore == null || kind == null) {
            throw new IllegalArgumentException(
                "configStore and kind are required"
            );
        }
        this.configStore = configStore;
        this.kind = kind;
    }

    /** Creates the owner for one character dictionary record. */
    public static DictionaryRecordPendingOwner forCharacters(
        ConfigStore configStore
    ) {
        return new DictionaryRecordPendingOwner(configStore, KIND_CHARACTER);
    }

    /** Creates the owner for one game-term dictionary record. */
    public static DictionaryRecordPendingOwner forTerms(
        ConfigStore configStore
    ) {
        return new DictionaryRecordPendingOwner(configStore, KIND_TERM);
    }

    @Override
    public String kind() {
        return kind;
    }

    /**
     * Returns a defensive snapshot preview without changing either store.
     */
    @Override
    public JSONObject previewMove(String canonicalId) throws Exception {
        requireCanonicalId(canonicalId);
        JSONObject record = readRecord(canonicalId);
        if (record == null) {
            throw new IllegalStateException(
                "dictionary record does not exist: " + canonicalId
            );
        }
        return snapshot(canonicalId, record);
    }

    /** Builds the store payload for one exact dictionary record. */
    @Override
    public PendingProcessStore.MovePayload prepareMove(
        String canonicalId,
        String reason
    ) throws Exception {
        JSONObject snapshot = previewMove(canonicalId);
        return PendingProcessStore.MovePayload.snapshot(
            kind,
            snapshot,
            reason,
            null,
            null
        );
    }

    /** Hides the exact dictionary key represented by the pending snapshot. */
    @Override
    public void hide(String canonicalId, JSONObject pendingEntry)
        throws Exception {
        JSONObject expected = expectedRecord(canonicalId, pendingEntry);
        JSONObject current = readRecord(canonicalId);
        if (current == null) {
            // The first hide may have committed before a process crash.
            return;
        }
        requireExactRecord(current, expected, canonicalId);
        if (KIND_CHARACTER.equals(kind)) {
            configStore.removeCharacterRecordForManagement(
                canonicalId,
                PendingProcessStore.copyJsonValueObject(expected)
            );
        } else {
            configStore.removeGameTermRecordForManagement(
                canonicalId,
                PendingProcessStore.copyJsonValueObject(expected)
            );
        }
    }

    /** Restores only when the key is free or already contains this snapshot. */
    @Override
    public void restore(String canonicalId, JSONObject pendingEntry)
        throws Exception {
        JSONObject expected = expectedRecord(canonicalId, pendingEntry);
        JSONObject current = readRecord(canonicalId);
        if (current != null) {
            requireExactRecord(current, expected, canonicalId);
            return;
        }
        if (KIND_CHARACTER.equals(kind)) {
            configStore.restoreCharacterRecordForManagement(
                canonicalId,
                PendingProcessStore.copyJsonValueObject(expected)
            );
        } else {
            configStore.restoreGameTermRecordForManagement(
                canonicalId,
                PendingProcessStore.copyJsonValueObject(expected)
            );
        }
    }

    /** Deletes only the exact value, tolerating an already-free key on replay. */
    @Override
    public void permanentlyDelete(String canonicalId, JSONObject pendingEntry)
        throws Exception {
        JSONObject expected = expectedRecord(canonicalId, pendingEntry);
        JSONObject current = readRecord(canonicalId);
        if (current == null) {
            return;
        }
        requireExactRecord(current, expected, canonicalId);
        if (KIND_CHARACTER.equals(kind)) {
            configStore.removeCharacterRecordForManagement(
                canonicalId,
                PendingProcessStore.copyJsonValueObject(expected)
            );
        } else {
            configStore.removeGameTermRecordForManagement(
                canonicalId,
                PendingProcessStore.copyJsonValueObject(expected)
            );
        }
    }

    private JSONObject readRecord(String canonicalId) throws Exception {
        if (KIND_CHARACTER.equals(kind)) {
            return PendingProcessStore.copyJsonOrNull(
                configStore.readCharacterRecordForManagement(canonicalId)
            );
        }
        return PendingProcessStore.copyJsonOrNull(
            configStore.readGameTermRecordForManagement(canonicalId)
        );
    }

    private JSONObject expectedRecord(
        String canonicalId,
        JSONObject pendingEntry
    ) throws PendingProcessStore.PendingProcessException {
        requireCanonicalId(canonicalId);
        if (pendingEntry == null
            || !kind.equals(pendingEntry.optString("kind", ""))
            || !canonicalId.equals(
                pendingEntry.optString("canonical_id", "")
            )) {
            throw new PendingProcessStore.PendingProcessException(
                PendingProcessStore.FailureKind.INVALID_STATE,
                "dictionary pending entry identity does not match owner"
            );
        }
        JSONObject payload = pendingEntry.optJSONObject("payload");
        if (payload == null
            || !"snapshot".equals(payload.optString("type", ""))) {
            throw new PendingProcessStore.PendingProcessException(
                PendingProcessStore.FailureKind.INVALID_STATE,
                "dictionary pending entry is not a snapshot"
            );
        }
        JSONObject snapshot = payload.optJSONObject("snapshot");
        if (snapshot == null
            || !PendingProcessStore.hasExactlyKeys(snapshot, "name", "record")) {
            throw new PendingProcessStore.PendingProcessException(
                PendingProcessStore.FailureKind.INVALID_STATE,
                "dictionary snapshot must contain name and record"
            );
        }
        if (!canonicalId.equals(snapshot.optString("name", ""))) {
            throw new PendingProcessStore.PendingProcessException(
                PendingProcessStore.FailureKind.INVALID_STATE,
                "dictionary snapshot name does not match owner key"
            );
        }
        JSONObject record = snapshot.optJSONObject("record");
        if (record == null) {
            throw new PendingProcessStore.PendingProcessException(
                PendingProcessStore.FailureKind.INVALID_STATE,
                "dictionary snapshot record is missing"
            );
        }
        return PendingProcessStore.copyJsonValueObject(record);
    }

    private static JSONObject snapshot(String canonicalId, JSONObject record)
        throws JSONException {
        return new JSONObject()
            .put("name", canonicalId)
            .put("record", PendingProcessStore.copyJsonValueObject(record));
    }

    private static void requireExactRecord(
        JSONObject current,
        JSONObject expected,
        String canonicalId
    ) throws PendingProcessStore.PendingProcessException {
        if (!PendingProcessStore.jsonEquals(current, expected)) {
            throw new PendingProcessStore.PendingProcessException(
                PendingProcessStore.FailureKind.CONFLICT,
                "dictionary key changed while PendingProcess was active: "
                    + canonicalId
            );
        }
    }

    private void requireCanonicalId(String canonicalId)
        throws PendingProcessStore.PendingProcessException {
        if (canonicalId == null || canonicalId.trim().isEmpty()) {
            throw new PendingProcessStore.PendingProcessException(
                PendingProcessStore.FailureKind.INVALID_ARGUMENT,
                "dictionary canonical id is required"
            );
        }
        if (KIND_CHARACTER.equals(kind)
            && MAIN_CHARACTER_KEY.equals(canonicalId)) {
            throw new PendingProcessStore.PendingProcessException(
                PendingProcessStore.FailureKind.INVALID_ARGUMENT,
                "the main character record cannot be moved to PendingProcess"
            );
        }
    }

}
