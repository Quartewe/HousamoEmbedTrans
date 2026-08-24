package com.quarty.housamoembedtrans.summary.policy;

import com.quarty.housamoembedtrans.summary.job.SummaryJobStore;

import org.json.JSONObject;

import java.util.HashSet;
import java.util.Set;

/**
 * Package-private durable Summary admission mechanism shared by the typed
 * Context and Group coordinators. Business coordinators map the outcome to
 * their own public result vocabulary.
 */
final class SummaryAdmissionCoordinator {

    enum Outcome {
        CREATED,
        REUSED_ACTIVE,
        MARKED_RERUN,
        REUSED_DUPLICATE
    }

    static final class Decision {
        final Outcome outcome;
        final String requestId;

        private Decision(Outcome outcome, String requestId) {
            this.outcome = outcome;
            this.requestId = requestId;
        }
    }

    private SummaryAdmissionCoordinator() {
        throw new AssertionError("No instances");
    }

    static Decision admit(
        SummaryJobStore store,
        JSONObject request,
        boolean userRequested
    ) throws Exception {
        SummaryJobStore.SummaryTargetKey target =
            SummaryJobStore.SummaryTargetKey.fromRequest(request);
        String desiredId = SummaryJobStore.computeRequestId(request);
        for (int attempt = 0; attempt < 2; attempt++) {
            String activeRequestId = store.findActiveRequestId(target);
            if (activeRequestId != null) {
                try {
                    if (userRequested) {
                        store.markUserRequested(activeRequestId);
                    }
                    JSONObject activeRequest = store.readRequest(activeRequestId);
                    String activeId = SummaryJobStore.computeRequestId(
                        activeRequest
                    );
                    if (!activeId.equals(desiredId)) {
                        store.markRerunRequired(activeRequestId);
                        return new Decision(
                            Outcome.MARKED_RERUN,
                            activeRequestId
                        );
                    }
                    return new Decision(
                        Outcome.REUSED_ACTIVE,
                        activeRequestId
                    );
                } catch (Exception race) {
                    if (!store.hasJob(activeRequestId)) {
                        continue;
                    }
                    throw race;
                }
            }
            if (store.hasJob(desiredId)) {
                if (userRequested) {
                    store.markUserRequested(desiredId);
                }
                return new Decision(Outcome.REUSED_DUPLICATE, desiredId);
            }
            SummaryJobStore.AdmissionResult admission = userRequested
                ? store.admitUserRequested(request)
                : store.admit(request);
            if (admission.created) {
                return new Decision(Outcome.CREATED, admission.requestId);
            }
            if (SummaryJobStore.DISPOSITION_DUPLICATE_REJECTED.equals(
                admission.disposition
            )) {
                return new Decision(
                    Outcome.REUSED_DUPLICATE,
                    admission.requestId
                );
            }
            if (SummaryJobStore.DISPOSITION_ACTIVE_TARGET_REJECTED.equals(
                admission.disposition
            )) {
                String activeId = store.findActiveRequestId(target);
                if (activeId == null) {
                    continue;
                }
                try {
                    if (userRequested) {
                        store.markUserRequested(activeId);
                    }
                    JSONObject activeRequest = store.readRequest(activeId);
                    String activeRequestIdentity =
                        SummaryJobStore.computeRequestId(activeRequest);
                    if (!activeRequestIdentity.equals(desiredId)) {
                        store.markRerunRequired(activeId);
                        return new Decision(Outcome.MARKED_RERUN, activeId);
                    }
                    return new Decision(Outcome.REUSED_ACTIVE, activeId);
                } catch (Exception race) {
                    if (!store.hasJob(activeId)) {
                        continue;
                    }
                    throw race;
                }
            }
            throw new IllegalStateException(
                "unexpected Summary admission disposition: "
                    + admission.disposition
            );
        }
        throw new IllegalStateException(
            "Summary admission reported an active target that disappeared"
        );
    }

    static int removePendingAutomaticJobs(
        SummaryJobStore store,
        String ownerType,
        String ownerId,
        String targetLang,
        Set<String> allowedRequestKinds
    ) throws Exception {
        Set<String> kinds = allowedRequestKinds == null
            ? new HashSet<>()
            : new HashSet<>(allowedRequestKinds);
        int removed = 0;
        for (String requestId : store.listRequestIds()) {
            JSONObject request = store.readRequest(requestId);
            if (!ownerType.equals(request.optString("owner_type", ""))
                || !ownerId.equals(request.optString("owner_id", ""))
                || !targetLang.equals(request.optString("target_lang", ""))
                || !kinds.contains(request.optString("request_kind", ""))) {
                continue;
            }
            String status = store.readState(requestId).optString("status", "");
            if (("queued".equals(status) || "awaiting_user".equals(status))
                && !store.isUserRequested(requestId)) {
                store.removeCompletedJob(requestId);
                removed++;
            }
        }
        return removed;
    }
}
