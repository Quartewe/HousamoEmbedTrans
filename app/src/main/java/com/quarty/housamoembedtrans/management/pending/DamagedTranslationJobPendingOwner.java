package com.quarty.housamoembedtrans.management.pending;

import com.quarty.housamoembedtrans.translation.job.TranslationJobStore;

import org.json.JSONObject;

import java.io.IOException;

/** Delete-only PendingProcess owner for quarantined Translation Jobs. */
public final class DamagedTranslationJobPendingOwner
    implements PendingProcessOwner {

    public static final String KIND = "damaged_translation_job";
    public static final String OWNER = "translation_job";

    private final TranslationJobStore jobStore;

    public DamagedTranslationJobPendingOwner(TranslationJobStore jobStore) {
        if (jobStore == null) {
            throw new IllegalArgumentException("jobStore is required");
        }
        this.jobStore = jobStore;
    }

    @Override
    public String kind() {
        return KIND;
    }

    /** Returns only trustworthy identity/status fields for confirmation UI. */
    public JSONObject previewMove(String requestId) throws Exception {
        return jobStore.describeDamagedJobForManagement(requestId);
    }

    /** Builds a reference after startup repair has quarantined the Job. */
    public PendingProcessStore.MovePayload prepareMove(
        String requestId,
        String reason
    ) throws Exception {
        jobStore.describeDamagedJobForManagement(requestId);
        return PendingProcessStore.MovePayload.externalReference(
            KIND,
            OWNER,
            requestId,
            reason,
            null,
            null
        );
    }

    @Override
    public void hide(String canonicalId, JSONObject pendingEntry)
        throws Exception {
        String requestId = requireReference(canonicalId, pendingEntry);
        // Damaged Jobs are already excluded from every claimable queue. This
        // check prevents publication of a dangling or repaired Job reference.
        jobStore.describeDamagedJobForManagement(requestId);
    }

    @Override
    public void restore(String canonicalId, JSONObject pendingEntry)
        throws IOException {
        requireReference(canonicalId, pendingEntry);
        throw new IOException("damaged translation jobs are delete-only");
    }

    @Override
    public void permanentlyDelete(String canonicalId, JSONObject pendingEntry)
        throws Exception {
        jobStore.deleteDamagedJobForManagement(
            requireReference(canonicalId, pendingEntry)
        );
    }

    private static String requireReference(
        String canonicalId,
        JSONObject entry
    ) throws IOException {
        if (entry == null
            || !KIND.equals(entry.optString("kind", ""))
            || !canonicalId.equals(entry.optString("canonical_id", ""))) {
            throw new IOException("damaged Job pending identity does not match");
        }
        JSONObject restore = entry.optJSONObject("restore");
        JSONObject payload = entry.optJSONObject("payload");
        if (restore == null
            || !PendingProcessStore.RESTORE_MODE_DELETE_ONLY.equals(
                restore.optString("mode", "")
            )
            || payload == null
            || !"external_reference".equals(payload.optString("type", ""))
            || !OWNER.equals(payload.optString("owner", ""))) {
            throw new IOException("damaged Job pending reference is invalid");
        }
        String requestId = payload.optString("id", "");
        if (!canonicalId.equals(requestId)) {
            throw new IOException("damaged Job pending reference id does not match");
        }
        return requestId;
    }
}
