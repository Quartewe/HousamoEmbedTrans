package com.quarty.housamoembedtrans.management.pending;

import org.json.JSONObject;

/**
 * Complete owner seam used by PendingProcessManager. Preview/prepare are
 * read-only; mutation methods inherit the store's idempotent replay
 * contract.
 */
public interface PendingProcessOwner
    extends PendingProcessStore.OwnerAdapter {

    /** Small, user-facing-safe facts for the confirmation screen. */
    JSONObject previewMove(String canonicalId) throws Exception;

    /** Captures the exact snapshot or external reference before publication. */
    PendingProcessStore.MovePayload prepareMove(
        String canonicalId,
        String reason
    ) throws Exception;
}
