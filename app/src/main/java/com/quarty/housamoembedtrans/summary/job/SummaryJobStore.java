package com.quarty.housamoembedtrans.summary.job;
import com.quarty.housamoembedtrans.context.model.GroupContextEntry;
import com.quarty.housamoembedtrans.context.store.SceneContextStore;
import com.quarty.housamoembedtrans.management.pending.PendingProcessStore;
import com.quarty.housamoembedtrans.scene.store.SceneStore;
import com.quarty.housamoembedtrans.storage.job.PersistentApiJobStore;
import com.quarty.housamoembedtrans.storage.json.AtomicJsonFileIo;

import com.quarty.housamoembedtrans.util.JobValidator;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * Persistent store for Summary API jobs under
 * {@code files/summary_jobs/<request_id>/}.
 *
 * <p>Summary jobs use {@code request.json} for the immutable semantic input
 * ({@code request_kind/owner_type/owner_id/target_lang/cutoff/source_hash}) and
 * {@code state.json} only for mutable scheduling state
 * ({@code status/created_at/updated_at/rerun_required/notified/user_requested}
 * plus terminal diagnostic fields). The store does not own HTTP/assembly/
 * write-back; that is the Summary request executor's job. It owns the
 * ready-job claim and the one-shot error-notification flag.</p>
 */
public final class SummaryJobStore {

    public static final String DIRECTORY_NAME = "summary_jobs";

    public static final String DISPOSITION_CREATED = "created";
    public static final String DISPOSITION_DUPLICATE_REJECTED =
        "duplicate_rejected";
    public static final String DISPOSITION_ACTIVE_TARGET_REJECTED =
        "active_target_rejected";

    private static final int MAX_REQUEST_BYTES = 64 * 1024;
    private static final int MAX_STATE_BYTES = 64 * 1024;

    private static final String STATUS_QUEUED = "queued";
    private static final String STATUS_RUNNING = "running";
    private static final String STATUS_AWAITING_USER = "awaiting_user";
    private static final String STATUS_FAILED = "failed";
    private static final String STATUS_CANCELED = "canceled";

    /** Durable reason for a job whose Context/Group owner was deleted. */
    public static final String OWNER_DELETED_REASON = "owner_deleted";
    private static final String INVALIDATED_REASON_FIELD =
        "invalidated_reason";
    private static final String PRIOR_STATUS_FIELD = "prior_status";

    private static final Set<String> REQUEST_KINDS =
        Collections.unmodifiableSet(new HashSet<>(Arrays.asList(
            "context_snapshot",
            "context_final",
            "group_snapshot"
        )));

    private static final Set<String> OWNER_TYPES =
        Collections.unmodifiableSet(new HashSet<>(Arrays.asList(
            "context",
            "group"
        )));

    private static final Set<String> REQUEST_FIELDS =
        Collections.unmodifiableSet(new HashSet<>(Arrays.asList(
            "request_kind",
            "owner_type",
            "owner_id",
            "target_lang",
            "cutoff",
            "source_hash"
        )));

    private static final Set<String> ACTIVE_STATUSES =
        Collections.unmodifiableSet(new HashSet<>(Arrays.asList(
            STATUS_QUEUED,
            STATUS_RUNNING,
            STATUS_AWAITING_USER
        )));

    /**
     * Serializes target-slot admission across all SummaryJobStore instances in
     * this process.  Service, queue UI, and task executors may each construct
     * their own store facade over the same files; an instance monitor alone
     * would let two instances both observe an empty target and create
     * competing active jobs.
     */
    private static final Object TARGET_ADMISSION_LOCK = new Object();

    @FunctionalInterface
    private interface StoreMutation {
        void run() throws Exception;
    }

    @FunctionalInterface
    private interface StoreQuery<T> {
        T run() throws Exception;
    }

    /** Stable identity of one derived-summary slot. */
    public static final class SummaryTargetKey {
        private final String requestKind;
        private final String ownerType;
        private final String ownerId;
        private final String targetLang;
        private final String cutoff;

        private SummaryTargetKey(
            String requestKind,
            String ownerType,
            String ownerId,
            String targetLang,
            String cutoff
        ) {
            this.requestKind = requestKind;
            this.ownerType = ownerType;
            this.ownerId = ownerId;
            this.targetLang = targetLang;
            this.cutoff = cutoff;
        }

        public static SummaryTargetKey fromRequest(JSONObject request) {
            if (request == null) {
                throw new IllegalArgumentException("summary request is null");
            }
            return new SummaryTargetKey(
                requireField(request, "request_kind"),
                requireField(request, "owner_type"),
                requireField(request, "owner_id"),
                requireField(request, "target_lang"),
                requireField(request, "cutoff")
            );
        }

        public String getRequestKind() {
            return requestKind;
        }

        public String getOwnerType() {
            return ownerType;
        }

        public String getOwnerId() {
            return ownerId;
        }

        public String getTargetLang() {
            return targetLang;
        }

        public String getCutoff() {
            return cutoff;
        }

        @Override
        public boolean equals(Object other) {
            if (this == other) {
                return true;
            }
            if (!(other instanceof SummaryTargetKey)) {
                return false;
            }
            SummaryTargetKey that = (SummaryTargetKey) other;
            return requestKind.equals(that.requestKind)
                && ownerType.equals(that.ownerType)
                && ownerId.equals(that.ownerId)
                && targetLang.equals(that.targetLang)
                && cutoff.equals(that.cutoff);
        }

        @Override
        public int hashCode() {
            return Objects.hash(
                requestKind,
                ownerType,
                ownerId,
                targetLang,
                cutoff
            );
        }

        @Override
        public String toString() {
            return "SummaryTargetKey{"
                + "requestKind='" + requestKind + '\''
                + ", ownerType='" + ownerType + '\''
                + ", ownerId='" + ownerId + '\''
                + ", targetLang='" + targetLang + '\''
                + ", cutoff='" + cutoff + '\''
                + '}';
        }
    }

    /** Immutable recovery-selection record for one legacy Summary Job. */
    public static final class RecoveryJob {
        private final String requestId;
        private final String requestKind;
        private final String ownerType;
        private final String ownerId;
        private final String targetLang;
        private final String status;
        private final long createdAt;

        private RecoveryJob(
            String requestId,
            String requestKind,
            String ownerType,
            String ownerId,
            String targetLang,
            String status,
            long createdAt
        ) {
            this.requestId = requestId;
            this.requestKind = requestKind;
            this.ownerType = ownerType;
            this.ownerId = ownerId;
            this.targetLang = targetLang;
            this.status = status;
            this.createdAt = createdAt;
        }

        public String getRequestId() {
            return requestId;
        }

        public String getRequestKind() {
            return requestKind;
        }

        public String getOwnerType() {
            return ownerType;
        }

        public String getOwnerId() {
            return ownerId;
        }

        public String getTargetLang() {
            return targetLang;
        }

        public String getStatus() {
            return status;
        }

        public long getCreatedAt() {
            return createdAt;
        }
    }

    /** Listener invoked after a startup recovery decision is committed. */
    public interface RecoveryDecisionListener {
        void onRecoveryDecisionCommitted();
    }

    /** Listener invoked after a Summary Job is durably admitted. */
    public interface AdmissionListener {
        void onSummaryJobAdmitted(String requestId);
    }

    /** Immutable failed-job record used by the failure management UI. */
    public static final class FailedJob {
        private final String requestId;
        private final String requestKind;
        private final String ownerType;
        private final String ownerId;
        private final String targetLang;
        private final String errorMessage;
        private final long updatedAt;

        private FailedJob(
            String requestId,
            String requestKind,
            String ownerType,
            String ownerId,
            String targetLang,
            String errorMessage,
            long updatedAt
        ) {
            this.requestId = requestId;
            this.requestKind = requestKind;
            this.ownerType = ownerType;
            this.ownerId = ownerId;
            this.targetLang = targetLang;
            this.errorMessage = errorMessage;
            this.updatedAt = updatedAt;
        }

        public String getRequestId() {
            return requestId;
        }

        public String getRequestKind() {
            return requestKind;
        }

        public String getOwnerType() {
            return ownerType;
        }

        public String getOwnerId() {
            return ownerId;
        }

        public String getTargetLang() {
            return targetLang;
        }

        public String getErrorMessage() {
            return errorMessage;
        }

        public long getUpdatedAt() {
            return updatedAt;
        }
    }

    /** Structured admission disposition. */
    public static final class AdmissionResult {
        public static final String DISPOSITION_CREATED =
            SummaryJobStore.DISPOSITION_CREATED;
        public static final String DISPOSITION_DUPLICATE_REJECTED =
            SummaryJobStore.DISPOSITION_DUPLICATE_REJECTED;
        public static final String DISPOSITION_ACTIVE_TARGET_REJECTED =
            SummaryJobStore.DISPOSITION_ACTIVE_TARGET_REJECTED;

        public final boolean created;
        public final String requestId;
        public final String disposition;

        private AdmissionResult(
            boolean created,
            String requestId,
            String disposition
        ) {
            this.created = created;
            this.requestId = requestId;
            this.disposition = disposition;
        }
    }

    public static final class AdmissionException extends Exception {
        private static final long serialVersionUID = 1L;
        private final String disposition;

        private AdmissionException(String disposition, String message) {
            super(message);
            this.disposition = disposition;
        }

        public String getDisposition() {
            return disposition;
        }
    }

    private final PersistentApiJobStore store;
    private final PendingProcessStore pendingProcessStore;
    private final SceneContextStore sceneContextStore;
    private boolean preparedForServiceStart;
    private boolean recoveryAutoRecover;
    private boolean recoveryCommitted;
    private boolean recoveryDecisionOpen;
    private final LinkedHashSet<String> startupRecoveryIds = new LinkedHashSet<>();
    private RecoveryDecisionListener recoveryDecisionListener =
        () -> { };
    private AdmissionListener admissionListener =
        requestId -> { };

    private void mutateUnderRoot(StoreMutation mutation) throws Exception {
        SceneContextStore.withRootAccess(() -> {
            synchronized (this) {
                mutation.run();
            }
            return null;
        });
    }

    private <T> T queryUnderRoot(StoreQuery<T> query) throws Exception {
        return SceneContextStore.withRootAccess(() -> {
            synchronized (this) {
                return query.run();
            }
        });
    }

    public SummaryJobStore(File root, AtomicJsonFileIo io) {
        this(new PersistentApiJobStore(
            root,
            PersistentApiJobStore.RequestIdFormat.SHA256_HEX,
            MAX_REQUEST_BYTES,
            MAX_STATE_BYTES,
            io
        ), null, null);
    }

    public SummaryJobStore(PersistentApiJobStore store) {
        this(store, null, null);
    }

    private SummaryJobStore(
        PersistentApiJobStore store,
        PendingProcessStore pendingProcessStore,
        SceneContextStore sceneContextStore
    ) {
        if (store == null) {
            throw new IllegalArgumentException("store is required");
        }
        this.store = store;
        this.pendingProcessStore = pendingProcessStore;
        this.sceneContextStore = sceneContextStore;
    }

    public static SummaryJobStore createForAndroid(File root) {
        return new SummaryJobStore(PersistentApiJobStore.createForAndroid(
            root,
            PersistentApiJobStore.RequestIdFormat.SHA256_HEX,
            MAX_REQUEST_BYTES,
            MAX_STATE_BYTES
        ));
    }

    /**
     * Android factory which also installs the process-wide Service wake path.
     * Every production caller should use this overload so a durable admission
     * cannot silently remain asleep when the Service is not already running.
     */
    public static SummaryJobStore createForAndroid(
        android.content.Context context
    ) {
        if (context == null) {
            throw new IllegalArgumentException("context is required");
        }
        android.content.Context appContext = context.getApplicationContext();
        android.content.Context safeContext = appContext != null
            ? appContext
            : context;
        SummaryJobStore result = new SummaryJobStore(
            PersistentApiJobStore.createForAndroid(
                new File(safeContext.getFilesDir(), DIRECTORY_NAME),
                PersistentApiJobStore.RequestIdFormat.SHA256_HEX,
                MAX_REQUEST_BYTES,
                MAX_STATE_BYTES
            ),
            new PendingProcessStore(safeContext),
            new SceneContextStore(safeContext)
        );
        result.setAdmissionListener(
            requestId -> SummaryJobWakeup.signal(context)
        );
        return result;
    }

    public File getRoot() {
        return store.getRoot();
    }

    public void setRecoveryDecisionListener(
        RecoveryDecisionListener listener
    ) {
        if (listener == null) {
            throw new IllegalArgumentException("listener is required");
        }
        this.recoveryDecisionListener = listener;
    }

    public void setAdmissionListener(AdmissionListener listener) {
        if (listener == null) {
            throw new IllegalArgumentException("listener is required");
        }
        this.admissionListener = listener;
    }

    /**
     * Calculates the concrete Summary Request ID:
     * {@code sha256(SummaryTargetKey + source_hash)}.
     */
    public static String computeRequestId(JSONObject request) {
        validateRequest(request);
        SummaryTargetKey target = SummaryTargetKey.fromRequest(request);
        String sourceHash = requireField(request, "source_hash");
        StringBuilder identity = new StringBuilder();
        identity.append("request_kind=").append(target.requestKind).append('\n');
        identity.append("owner_type=").append(target.ownerType).append('\n');
        identity.append("owner_id=").append(target.ownerId).append('\n');
        identity.append("target_lang=").append(target.targetLang).append('\n');
        identity.append("cutoff=").append(target.cutoff).append('\n');
        identity.append("source_hash=").append(sourceHash);
        return PersistentApiJobStore.sha256Hex(
            identity.toString().getBytes(StandardCharsets.UTF_8)
        );
    }

    /**
     * Admits a Summary Job under one process-local lock. A duplicate requestId
     * and a concurrent active request for the same SummaryTargetKey are both
     * rejected without creating a second directory.
     */
    public AdmissionResult admit(JSONObject request)
        throws Exception {
        return admit(request, false);
    }

    /** Admits or promotes a Summary Job explicitly requested by the user. */
    public AdmissionResult admitUserRequested(JSONObject request)
        throws Exception {
        return admit(request, true);
    }

    private AdmissionResult admit(JSONObject request, boolean userRequested)
        throws Exception {
        synchronized (SceneContextStore.ROOT_ACCESS_LOCK) {
            synchronized (this) {
                return admitLocked(request, userRequested);
            }
        }
    }

    private AdmissionResult admitLocked(
        JSONObject request,
        boolean userRequested
    )
        throws Exception {
        validateRequest(request);
        String requestId = computeRequestId(request);
        AdmissionResult result;
        synchronized (TARGET_ADMISSION_LOCK) {
            store.ensureRoot();

            if (store.jobDirectoryExists(requestId)) {
                if (userRequested) {
                    markUserRequestedLocked(requestId);
                }
                return new AdmissionResult(
                    false,
                    requestId,
                    DISPOSITION_DUPLICATE_REJECTED
                );
            }

            SummaryTargetKey target = SummaryTargetKey.fromRequest(request);
            String activeRequestId = findActiveTargetLocked(target);
            if (activeRequestId != null) {
                if (userRequested) {
                    markUserRequestedLocked(activeRequestId);
                }
                return new AdmissionResult(
                    false,
                    requestId,
                    DISPOSITION_ACTIVE_TARGET_REJECTED
                );
            }

            File directory = store.jobDirectory(requestId);
            if (!directory.mkdir()) {
                throw new IOException(
                    "Failed to create summary job directory: "
                        + directory.getAbsolutePath()
                );
            }

            try {
                byte[] requestBytes = (request.toString(2) + "\n").getBytes(
                    StandardCharsets.UTF_8
                );
                store.writeRequest(directory, requestBytes);
                long now = System.currentTimeMillis();
                JSONObject state = new JSONObject()
                    .put("status", STATUS_QUEUED)
                    .put("created_at", now)
                    .put("updated_at", now)
                    .put("rerun_required", false)
                    .put("notified", false)
                    .put("user_requested", userRequested);
                store.writeState(directory, state);
                result = new AdmissionResult(
                    true,
                    requestId,
                    DISPOSITION_CREATED
                );
            } catch (Exception e) {
                store.deleteJobDirectory(directory);
                throw e;
            }
        }
        // Wake-up is deliberately outside the durable admission lock and is
        // best effort.  A broken Service start must never roll back a job that
        // has already reached disk and therefore the recovery boundary.
        notifyAdmissionListener(result.requestId);
        return result;
    }

    private void notifyAdmissionListener(String requestId) {
        try {
            admissionListener.onSummaryJobAdmitted(requestId);
        } catch (Throwable ignored) {
            // The job is durable; the Service's next start/recovery scan will
            // still find it if this process-local wake path is unavailable.
        }
    }

    /** Convenience wrapper that throws on duplicate/active-target rejection. */
    public String createJob(JSONObject request)
        throws Exception {
        AdmissionResult result = admit(request);
        if (!result.created) {
            throw new AdmissionException(
                result.disposition,
                "Summary job was not admitted requestId=" + result.requestId
            );
        }
        return result.requestId;
    }

    /**
     * Builds the startup recovery snapshot. Only jobs already present at this
     * boundary are included; jobs admitted later are never marked
     * {@code awaiting_user} or {@code canceled} by a recovery decision.
     *
     * <p>The call only captures identities.  Status mutation is deferred until
     * the Review gate has completed, so Review-created jobs are not mixed into
     * the fixed legacy snapshot and skipped Review cannot observe a half
     * committed recovery decision.</p>
     */
    public void prepareForServiceStart(
        boolean autoRecover,
        SceneContextStore sceneContextStore
    )
        throws Exception {
        if (sceneContextStore == null) {
            throw new IllegalArgumentException("sceneContextStore is required");
        }
        mutateUnderRoot(() -> prepareForServiceStartLocked(
            autoRecover,
            sceneContextStore
        ));
    }

    private void prepareForServiceStartLocked(
        boolean autoRecover,
        SceneContextStore sceneContextStore
    )
        throws Exception {
        synchronized (TARGET_ADMISSION_LOCK) {
            store.ensureRoot();
            preparedForServiceStart = false;
            recoveryAutoRecover = autoRecover;
            recoveryCommitted = false;
            recoveryDecisionOpen = false;
            startupRecoveryIds.clear();

            for (File directory : store.listValidJobDirectories()) {
                String requestId = directory.getName();
                JSONObject state = repairStartupDirectoryLocked(
                    directory,
                    sceneContextStore
                );
                if (state == null || !directory.isDirectory()) {
                    continue;
                }
                String status = state.optString("status", "");
                if (ACTIVE_STATUSES.contains(status)) {
                    startupRecoveryIds.add(requestId);
                }
            }
            preparedForServiceStart = true;
        }
    }

    /**
     * Repairs a request-first admission or converges a completed write-back.
     * Null means the directory was safely discarded or already completed.
     */
    private JSONObject repairStartupDirectoryLocked(
        File directory,
        SceneContextStore sceneContextStore
    ) throws Exception {
        PersistentApiJobStore.RequestFirstInspection inspection =
            store.inspectRequestFirst(directory, (requestId, requestBytes) -> {
                JSONObject request = JobValidator.parseJsonObject(
                    requestBytes,
                    MAX_REQUEST_BYTES,
                    "summary request"
                );
                validateRequest(request);
                String computed = computeRequestId(request);
                if (!requestId.equals(computed)) {
                    throw new IllegalArgumentException(
                        "summary request id does not match request.json"
                    );
                }
            });
        if (inspection.status
            == PersistentApiJobStore.RequestFirstStatus.REQUEST_INVALID) {
            deleteJobDirectoryLocked(directory);
            return null;
        }

        JSONObject request = JobValidator.parseJsonObject(
            inspection.requestBytes,
            MAX_REQUEST_BYTES,
            "summary request"
        );
        JSONObject owner = readValidOwner(sceneContextStore, request);
        boolean ownerPending = owner == null
            && hasPendingOwnerReference(request);
        if (owner == null) {
            // A missing owner is normally the durable result of a Context or
            // Group permanent delete.  Keep the request/state directory as
            // history and make it an explicit terminal record.  A hidden
            // owner still has a PendingProcess reference and must remain
            // recoverable when that reference is restored.
            if (ownerPending) {
                if (inspection.state != null) {
                    return inspection.state;
                }
            } else {
                return markOwnerDeletedStateLocked(
                    directory,
                    inspection.state
                );
            }
        }
        if (isOwnerDeletedState(inspection.state)) {
            // Restoring an owner must not resurrect a job that was already
            // invalidated by its previous permanent deletion.
            return inspection.state;
        }
        if (owner != null && !targetStillExists(owner, request)) {
            deleteJobDirectoryLocked(directory);
            return null;
        }
        if (owner != null && derivedSummaryMatches(owner, request)) {
            deleteJobDirectoryLocked(directory);
            return null;
        }
        if (inspection.status
            == PersistentApiJobStore.RequestFirstStatus.STATE_MISSING) {
            long now = System.currentTimeMillis();
            long createdAt = directory.lastModified();
            if (createdAt <= 0L || createdAt > now) {
                createdAt = now;
            }
            // The missing state also lost its admission origin. Conservatively
            // preserve user control instead of silently treating it as auto.
            JSONObject repaired = new JSONObject()
                .put("status", STATUS_QUEUED)
                .put("created_at", createdAt)
                .put("updated_at", now)
                .put("rerun_required", false)
                .put("notified", false)
                .put("user_requested", true);
            store.writeState(directory, repaired);
            return repaired;
        }
        return inspection.state;
    }

    private static JSONObject readValidOwner(
        SceneContextStore sceneContextStore,
        JSONObject request
    ) throws Exception {
        String ownerType = request.optString("owner_type", "");
        String ownerId = request.optString("owner_id", "");
        try {
            return "context".equals(ownerType)
                ? sceneContextStore.getContext(ownerId)
                : sceneContextStore.getGroup(ownerId);
        } catch (SceneContextStore.StorageException e) {
            if (e.kind == SceneContextStore.FailureKind.NOT_FOUND) {
                return null;
            }
            throw e;
        }
    }

    private static boolean targetStillExists(
        JSONObject owner,
        JSONObject request
    ) {
        String kind = request.optString("request_kind", "");
        if ("context_final".equals(kind)) {
            return true;
        }
        String cutoff = request.optString("cutoff", "");
        org.json.JSONArray entries = "context_snapshot".equals(kind)
            ? owner.optJSONArray("scenes")
            : owner.optJSONArray("contexts");
        if (entries == null) {
            return false;
        }
        for (int index = 0; index < entries.length(); index++) {
            JSONObject entry = entries.optJSONObject(index);
            String entryId = entry == null
                ? ""
                : entry.optString(
                    "context_snapshot".equals(kind)
                        ? "entry_id"
                        : GroupContextEntry.ENTRY_ID,
                    ""
                );
            if (cutoff.equals(entryId)) {
                return true;
            }
        }
        return false;
    }

    private static boolean derivedSummaryMatches(
        JSONObject owner,
        JSONObject request
    ) {
        JSONObject summary = owner.optJSONObject("summary");
        JSONObject language = summary == null
            ? null
            : summary.optJSONObject(request.optString("target_lang", ""));
        String kind = request.optString("request_kind", "");
        JSONObject record = language == null
            ? null
            : language.optJSONObject(
                "context_final".equals(kind) ? "final" : "current"
            );
        if (record == null
            || record.optString("text", "").trim().isEmpty()
            || !request.optString("source_hash", "").equals(
                record.optString("source_hash", "")
            )) {
            return false;
        }
        return "context_final".equals(kind)
            || request.optString("cutoff", "").equals(
                record.optString("cutoff", "")
            );
    }

    /** Returns whether a manual Summary recovery decision is still pending. */
    public synchronized boolean isRecoveryPending() {
        return preparedForServiceStart
            && recoveryDecisionOpen
            && !recoveryAutoRecover
            && !recoveryCommitted
            && !startupRecoveryIds.isEmpty();
    }

    /**
     * Returns whether this instance owns an opened startup recovery boundary.
     * Detached stores may read durable failed jobs, but must not be used to
     * impersonate the Service-owned recovery snapshot.
     */
    public synchronized boolean isRecoveryDecisionOpen() {
        return preparedForServiceStart && recoveryDecisionOpen;
    }

    /** Returns whether the automatic policy still needs its post-Review commit. */
    public synchronized boolean isAutomaticRecoveryPending() {
        return preparedForServiceStart
            && recoveryDecisionOpen
            && recoveryAutoRecover
            && !recoveryCommitted;
    }

    /**
     * Opens the unified Recovery Decision phase after Review completes.
     *
     * <p>Manual recovery has a durable intermediate state.  The fixed
     * startup snapshot is moved to {@code awaiting_user} while holding the
     * same root/admission locks used by {@link #admit(JSONObject)}.  A job
     * admitted after this boundary is therefore never accidentally included
     * in (or canceled by) the recovery decision, and a process crash while
     * the UI is open leaves the legacy job visible on the next startup.</p>
     */
    public void openRecoveryDecision() throws Exception {
        final boolean[] opened = new boolean[] { false };
        mutateUnderRoot(() -> {
            synchronized (TARGET_ADMISSION_LOCK) {
                if (!preparedForServiceStart || recoveryDecisionOpen) {
                    return;
                }
                recoveryDecisionOpen = true;
                pruneMissingStartupRecoveryIdsLocked();
                if (!recoveryAutoRecover) {
                    for (String requestId : startupRecoveryIds) {
                        JSONObject state = readState(requestId);
                        String status = state.optString("status", "");
                        if (STATUS_QUEUED.equals(status)
                            || STATUS_RUNNING.equals(status)) {
                            updateStatus(requestId, STATUS_AWAITING_USER);
                        }
                    }
                }
                if (startupRecoveryIds.isEmpty()) {
                    recoveryCommitted = true;
                }
                opened[0] = true;
            }
        });
        if (opened[0]) {
            recoveryDecisionListener.onRecoveryDecisionCommitted();
        }
    }

    /** Returns the immutable startup snapshot for the recovery UI. */
    public synchronized List<RecoveryJob> listRecoveryJobs() throws Exception {
        if (!recoveryDecisionOpen) {
            throw new IllegalStateException(
                "Summary recovery decision is not open"
            );
        }
        List<RecoveryJob> result = new ArrayList<>();
        synchronized (TARGET_ADMISSION_LOCK) {
            pruneMissingStartupRecoveryIdsLocked();
            for (String requestId : startupRecoveryIds) {
                JSONObject state = readState(requestId);
                JSONObject request = readRequest(requestId);
                result.add(new RecoveryJob(
                    requestId,
                    request.optString("request_kind", ""),
                    request.optString("owner_type", ""),
                    request.optString("owner_id", ""),
                    request.optString("target_lang", ""),
                    state.optString("status", ""),
                    state.optLong("created_at", 0L)
                ));
            }
        }
        return result;
    }

    /** Returns all retained failed Summary Jobs for the management UI. */
    public synchronized List<FailedJob> listFailedJobs() throws Exception {
        List<FailedJob> result = new ArrayList<>();
        for (File directory : store.listValidJobDirectories()) {
            String requestId = directory.getName();
            try {
                JSONObject state = store.readState(directory);
                if (state == null
                    || !STATUS_FAILED.equals(state.optString("status", ""))
                    || OWNER_DELETED_REASON.equals(
                        state.optString(INVALIDATED_REASON_FIELD, "")
                    )) {
                    continue;
                }
                JSONObject request = JobValidator.parseJsonObject(
                    store.readRequest(directory),
                    MAX_REQUEST_BYTES,
                    "summary request"
                );
                result.add(new FailedJob(
                    requestId,
                    request.optString("request_kind", ""),
                    request.optString("owner_type", ""),
                    request.optString("owner_id", ""),
                    request.optString("target_lang", ""),
                    state.optString("error", ""),
                    state.optLong("updated_at", 0L)
                ));
            } catch (Exception ignored) {
                // Skip damaged failed-job directories; repair is outside the
                // management UI and must not block the whole list.
            }
        }
        result.sort((left, right) -> Long.compare(
            right.getUpdatedAt(),
            left.getUpdatedAt()
        ));
        return result;
    }

    /** Resets a retained failed Summary Job to queued for a manual rerun. */
    public void retryFailedJob(String requestId)
        throws Exception {
        mutateUnderRoot(() -> retryFailedJobLocked(requestId));
        notifyAdmissionListener(requestId);
    }

    private void retryFailedJobLocked(String requestId) throws Exception {
        synchronized (TARGET_ADMISSION_LOCK) {
            File directory = requireJobDirectory(requestId);
            JSONObject state = readState(requestId);
            if (isOwnerDeletedState(state)) {
                throw new IllegalStateException(
                    "summary job owner was permanently deleted requestId="
                        + requestId
                );
            }
            if (!STATUS_FAILED.equals(state.optString("status", ""))) {
                throw new IllegalStateException(
                    "summary job is not failed requestId=" + requestId
                );
            }
            SummaryTargetKey target = targetOf(requestId);
            String activeRequestId = findActiveTargetLocked(target, requestId);
            if (activeRequestId != null) {
                throw new AdmissionException(
                    DISPOSITION_ACTIVE_TARGET_REJECTED,
                    "Summary target already has an active request: "
                        + activeRequestId
                );
            }
            state.put("status", STATUS_QUEUED);
            state.remove("error");
            state.put("notified", false);
            state.put("user_requested", true);
            state.put("updated_at", System.currentTimeMillis());
            store.writeState(directory, state);
        }
    }

    /**
     * Applies the user's Summary recovery decision. Selected snapshot jobs are
     * restored to {@code queued}; unselected snapshot jobs become terminal
     * {@code canceled}. Jobs admitted after the snapshot boundary are never
     * touched.
     */
    public void applySummaryRecoveryDecision(
        List<String> restoreRequestIds
    ) throws Exception {
        List<String> restoredIds = new ArrayList<>();
        mutateUnderRoot(() -> applySummaryRecoveryDecisionLocked(
            restoreRequestIds,
            restoredIds
        ));
        recoveryDecisionListener.onRecoveryDecisionCommitted();
        for (String requestId : restoredIds) {
            notifyAdmissionListener(requestId);
        }
    }

    private void applySummaryRecoveryDecisionLocked(
        List<String> restoreRequestIds,
        List<String> restoredIds
    ) throws Exception {
        if (restoreRequestIds == null) {
            throw new IllegalArgumentException("restoreRequestIds cannot be null");
        }
        synchronized (TARGET_ADMISSION_LOCK) {
            requireRecoveryPendingLocked();
            Set<String> prunedIds = pruneMissingStartupRecoveryIdsLocked();
            Set<String> restoreIds = new HashSet<>();
            for (String requestId : restoreRequestIds) {
                if (requestId == null || !restoreIds.add(requestId)) {
                    throw new IllegalArgumentException(
                        "Duplicate or empty request in Summary recovery order: "
                            + requestId
                    );
                }
                if (!startupRecoveryIds.contains(requestId)) {
                    if (prunedIds.contains(requestId)) {
                        continue;
                    }
                    throw new IllegalArgumentException(
                        "Request is not in the Summary startup snapshot: "
                            + requestId
                    );
                }
            }

            for (String requestId : startupRecoveryIds) {
                if (restoreIds.contains(requestId)) {
                    updateStatus(requestId, STATUS_QUEUED);
                    restoredIds.add(requestId);
                } else {
                    updateStatus(requestId, STATUS_CANCELED);
                }
            }
            startupRecoveryIds.clear();
            recoveryCommitted = true;
        }
    }

    /** Restores every snapshot job to queued (used by automatic recovery). */
    public void autoRecoverStartupJobs() throws Exception {
        boolean committed;
        List<String> restoredIds = new ArrayList<>();
        committed = queryUnderRoot(() -> {
            if (!preparedForServiceStart || !recoveryDecisionOpen
                || recoveryCommitted) {
                return false;
            }
            synchronized (TARGET_ADMISSION_LOCK) {
                pruneMissingStartupRecoveryIdsLocked();
                for (String requestId : startupRecoveryIds) {
                    updateStatus(requestId, STATUS_QUEUED);
                    restoredIds.add(requestId);
                }
                startupRecoveryIds.clear();
                recoveryCommitted = true;
            }
            return true;
        });
        if (!committed) {
            return;
        }
        recoveryDecisionListener.onRecoveryDecisionCommitted();
        for (String requestId : restoredIds) {
            notifyAdmissionListener(requestId);
        }
    }

    public synchronized boolean hasJob(String requestId) {
        return store.jobDirectoryExists(requestId);
    }

    public synchronized JSONObject readRequest(String requestId)
        throws Exception {
        File directory = requireJobDirectory(requestId);
        byte[] bytes = store.readRequest(directory);
        return JobValidator.parseJsonObject(
            bytes,
            MAX_REQUEST_BYTES,
            "summary request"
        );
    }

    public synchronized JSONObject readState(String requestId)
        throws Exception {
        File directory = requireJobDirectory(requestId);
        JSONObject state = store.readState(directory);
        if (state == null) {
            throw new IllegalStateException(
                "summary state is missing requestId=" + requestId
            );
        }
        return state;
    }

    public synchronized List<String> listRequestIds() throws IOException {
        List<String> ids = new ArrayList<>();
        for (File directory : store.listValidJobDirectories()) {
            ids.add(directory.getName());
        }
        return ids;
    }

    /** Returns the active request id for a target, or null. */
    public synchronized String findActiveRequestId(SummaryTargetKey target)
        throws Exception {
        return findActiveTargetLocked(target);
    }

    public synchronized SummaryTargetKey targetOf(String requestId)
        throws Exception {
        return SummaryTargetKey.fromRequest(readRequest(requestId));
    }

    public void markRunning(String requestId) throws Exception {
        mutateUnderRoot(() -> {
            synchronized (TARGET_ADMISSION_LOCK) {
                if (!isOwnerDeletedState(readState(requestId))) {
                    updateStatus(requestId, STATUS_RUNNING);
                }
            }
        });
    }

    public void markFailed(String requestId) throws Exception {
        mutateUnderRoot(() -> {
            synchronized (TARGET_ADMISSION_LOCK) {
                if (!isOwnerDeletedState(readState(requestId))) {
                    updateStatus(requestId, STATUS_FAILED);
                }
            }
        });
    }

    public boolean failJob(
        String requestId,
        String errorMessage
    ) throws Exception {
        return queryUnderRoot(() -> {
            synchronized (TARGET_ADMISSION_LOCK) {
                File directory = requireJobDirectory(requestId);
                JSONObject state = readState(requestId);
                if (isOwnerDeletedState(state)) {
                    return false;
                }
                state.put("status", STATUS_FAILED);
                state.put("error", errorMessage == null ? "" : errorMessage);
                state.put("updated_at", System.currentTimeMillis());
                store.writeState(directory, state);
                return true;
            }
        });
    }

    /**
     * Cancels an explicitly user-requested Summary Job only while it is still
     * unsent. Running attempts are intentionally left untouched so their
     * prepared result can pass through the ordinary stale-result archive
     * boundary.
     */
    public boolean cancelUserRequestedIfUnsent(String requestId)
        throws Exception {
        return queryUnderRoot(() -> {
            synchronized (TARGET_ADMISSION_LOCK) {
                JSONObject state = readState(requestId);
                if (!state.optBoolean("user_requested", false)) {
                    return false;
                }
                String status = state.optString("status", "");
                if (!STATUS_QUEUED.equals(status)
                    && !STATUS_AWAITING_USER.equals(status)) {
                    return false;
                }
                updateStatus(requestId, STATUS_CANCELED);
                return true;
            }
        });
    }

    public void markRerunRequired(String requestId)
        throws Exception {
        mutateUnderRoot(() -> setRerunRequired(requestId, true));
    }

    /** Persists user ownership without changing immutable request identity. */
    public void markUserRequested(String requestId) throws Exception {
        mutateUnderRoot(() -> {
            synchronized (TARGET_ADMISSION_LOCK) {
                markUserRequestedLocked(requestId);
            }
        });
    }

    public synchronized boolean isUserRequested(String requestId)
        throws Exception {
        return readState(requestId).optBoolean("user_requested", false);
    }

    public void clearRerunRequired(String requestId)
        throws Exception {
        mutateUnderRoot(() -> setRerunRequired(requestId, false));
    }

    public synchronized boolean isRerunRequired(String requestId)
        throws Exception {
        JSONObject state = readState(requestId);
        return state.optBoolean("rerun_required", false);
    }

    public synchronized boolean isErrorNotified(String requestId)
        throws Exception {
        JSONObject state = readState(requestId);
        return state.optBoolean("notified", false);
    }

    public void markErrorNotified(String requestId)
        throws Exception {
        mutateUnderRoot(() -> {
            File directory = requireJobDirectory(requestId);
            JSONObject state = readState(requestId);
            state.put("notified", true);
            state.put("updated_at", System.currentTimeMillis());
            store.writeState(directory, state);
        });
    }

    /** Returns true when any Summary Job is queued and claimable now. */
    public boolean hasPendingJobs() throws IOException {
        try {
            return SceneContextStore.withRootAccess(() -> {
                synchronized (this) {
                    return hasPendingJobsLocked();
                }
            });
        } catch (IOException e) {
            throw e;
        } catch (Exception e) {
            throw new IOException(
                "could not inspect queued Summary Jobs",
                e
            );
        }
    }

    private boolean hasPendingJobsLocked() throws IOException {
        PendingProcessStore.ReferenceSnapshot references =
            pendingProcessStore == null
                ? null
                : pendingProcessStore.snapshotReferences();
        for (File directory : store.listValidJobDirectories()) {
            JSONObject state = store.readState(directory);
            if (state == null) {
                continue;
            }
            if (!STATUS_QUEUED.equals(state.optString("status", ""))) {
                continue;
            }
            try {
                JSONObject request = JobValidator.parseJsonObject(
                    store.readRequest(directory),
                    MAX_REQUEST_BYTES,
                    "summary request"
                );
                if (!isBlockedByManagementPendingLocked(
                    request,
                    references
                )) {
                    return true;
                }
            } catch (Exception e) {
                throw new IOException(
                    "could not inspect queued Summary Job "
                        + directory.getName(),
                    e
                );
            }
        }
        return false;
    }

    /**
     * Atomically claims the next queued Summary Job under the store lock and
     * marks it running. Only durable queued jobs are claimable; awaiting-user
     * jobs require a recovery decision before they become claimable.
     */
    public String claimNextReadyJob() throws Exception {
        return queryUnderRoot(() -> {
            synchronized (TARGET_ADMISSION_LOCK) {
                PendingProcessStore.ReferenceSnapshot references =
                    pendingProcessStore == null
                        ? null
                        : pendingProcessStore.snapshotReferences();
                for (File directory : store.listValidJobDirectories()) {
                    JSONObject state = store.readState(directory);
                    if (state == null) {
                        continue;
                    }
                    if (!STATUS_QUEUED.equals(state.optString("status", ""))) {
                        continue;
                    }
                    JSONObject request = JobValidator.parseJsonObject(
                        store.readRequest(directory),
                        MAX_REQUEST_BYTES,
                        "summary request"
                    );
                    if (isBlockedByManagementPendingLocked(
                        request,
                        references
                    )) {
                        continue;
                    }
                    String requestId = directory.getName();
                    updateStatus(requestId, STATUS_RUNNING);
                    return requestId;
                }
                return null;
            }
        });
    }

    /**
     * Rechecks management Pending references for a claimed Summary request.
     * The read-only check takes the same ROOT_ACCESS_LOCK -> this lock order as
     * claim and write-back callers.  File/host seams have no Context facade and
     * therefore retain direct-owner Pending checks only.
     */
    boolean isBlockedByManagementPending(JSONObject request)
        throws Exception {
        return queryUnderRoot(() -> {
            PendingProcessStore.ReferenceSnapshot references =
                pendingProcessStore == null
                    ? null
                    : pendingProcessStore.snapshotReferences();
            return isBlockedByManagementPendingLocked(request, references);
        });
    }

    /**
     * Returns whether a Summary Job has been durably invalidated because its
     * Context/Group owner was permanently deleted.  This marker is checked by
     * the executor before every prepared-result/write-back step so restoring a
     * same-id owner cannot revive an old request.
     */
    public boolean isOwnerDeleted(String requestId) throws Exception {
        return queryUnderRoot(() -> {
            if (!store.jobDirectoryExists(requestId)) {
                return false;
            }
            return isOwnerDeletedState(readState(requestId));
        });
    }

    /**
     * Marks one job owner-deleted when its live owner is already absent.  The
     * operation is idempotent and deliberately leaves request/error history in
     * place.  A hidden PendingProcess owner is not treated as permanently
     * deleted; its reference still gives restore a chance to release the job.
     *
     * @return true when the job is (or has become) owner-deleted
     */
    public boolean ensureOwnerDeletedIfMissing(String requestId)
        throws Exception {
        return queryUnderRoot(() -> {
            synchronized (TARGET_ADMISSION_LOCK) {
                File directory = requireJobDirectory(requestId);
                JSONObject state = readState(requestId);
                if (isOwnerDeletedState(state)) {
                    return true;
                }
                JSONObject request = JobValidator.parseJsonObject(
                    store.readRequest(directory),
                    MAX_REQUEST_BYTES,
                    "summary request"
                );
                if (hasPendingOwnerReference(request)
                    || sceneContextStore == null) {
                    return false;
                }
                String ownerType = request.optString("owner_type", "");
                String ownerId = request.optString("owner_id", "");
                try {
                    if ("context".equals(ownerType)) {
                        sceneContextStore.getContext(ownerId);
                    } else if ("group".equals(ownerType)) {
                        sceneContextStore.getGroup(ownerId);
                    } else {
                        return false;
                    }
                    return false;
                } catch (SceneContextStore.StorageException e) {
                    if (e.kind != SceneContextStore.FailureKind.NOT_FOUND) {
                        throw e;
                    }
                    markOwnerDeletedStateLocked(directory, state);
                    return true;
                }
            }
        });
    }

    private boolean hasPendingOwnerReference(JSONObject request)
        throws IOException {
        if (pendingProcessStore == null || request == null) {
            return false;
        }
        PendingProcessStore.ReferenceSnapshot references =
            pendingProcessStore.snapshotReferences();
        if (references == null || references.isEmpty()) {
            return false;
        }
        return isPendingReference(
            references,
            request.optString("owner_type", ""),
            request.optString("owner_id", "")
        );
    }

    private boolean isBlockedByManagementPendingLocked(
        JSONObject request,
        PendingProcessStore.ReferenceSnapshot references
    ) throws Exception {
        validateRequest(request);
        String ownerType = request.optString("owner_type", "");
        String ownerId = request.optString("owner_id", "");
        if (references != null
            && !references.isEmpty()
            && isPendingReference(references, ownerType, ownerId)) {
            return true;
        }
        if (sceneContextStore == null) {
            return false;
        }
        String targetLang = request.optString("target_lang", "");
        if ("context".equals(ownerType)) {
            return hasPendingContextScene(
                sceneContextStore.getContext(ownerId),
                targetLang,
                references
            );
        }
        if ("group".equals(ownerType)) {
            return hasPendingGroupScene(
                sceneContextStore.getGroup(ownerId),
                targetLang,
                references
            );
        }
        return false;
    }

    private boolean hasPendingGroupScene(
        JSONObject group,
        String targetLang,
        PendingProcessStore.ReferenceSnapshot references
    ) throws Exception {
        if (group == null) {
            throw new IOException("live Summary Group is missing");
        }
        Object value = group.opt("contexts");
        if (!(value instanceof JSONArray)) {
            throw new IOException("live Summary Group contexts are malformed");
        }
        JSONArray contexts = (JSONArray) value;
        for (int index = 0; index < contexts.length(); index++) {
            String contextId;
            try {
                contextId = GroupContextEntry.contextIdAt(contexts, index);
            } catch (RuntimeException e) {
                throw new IOException(
                    "live Summary Group context reference is malformed",
                    e
                );
            }
            if (contextId == null || contextId.trim().isEmpty()) {
                throw new IOException(
                    "live Summary Group context reference is empty"
                );
            }
            if (isPendingReference(references, "context", contextId)) {
                return true;
            }
            JSONObject context = sceneContextStore.getContext(contextId);
            if (hasPendingContextScene(context, targetLang, references)) {
                return true;
            }
        }
        return false;
    }

    private static boolean hasPendingContextScene(
        JSONObject context,
        String targetLang,
        PendingProcessStore.ReferenceSnapshot references
    ) throws IOException {
        if (context == null) {
            throw new IOException("live Summary Context is missing");
        }
        Object value = context.opt("scenes");
        if (!(value instanceof JSONArray)) {
            throw new IOException("live Summary Context scenes are malformed");
        }
        JSONArray scenes = (JSONArray) value;
        for (int index = 0; index < scenes.length(); index++) {
            JSONObject entry = scenes.optJSONObject(index);
            if (entry == null) {
                throw new IOException(
                    "live Summary Context scene reference is malformed"
                );
            }
            Object sceneValue = entry.opt("scene");
            if (!(sceneValue instanceof String)
                || ((String) sceneValue).trim().isEmpty()) {
                throw new IOException(
                    "live Summary Context scene reference is empty"
                );
            }
            String sceneName = (String) sceneValue;
            if (isPendingReference(references, "scene", sceneName)) {
                return true;
            }
            final String languageId;
            try {
                languageId = SceneStore.languageCanonicalId(
                    sceneName,
                    targetLang
                );
            } catch (IOException e) {
                throw new IOException(
                    "live Summary Context scene language identity is invalid",
                    e
                );
            } catch (RuntimeException e) {
                throw new IOException(
                    "live Summary Context scene identity is invalid",
                    e
                );
            }
            if (isPendingReference(references, "language", languageId)) {
                return true;
            }
        }
        return false;
    }

    private static boolean isPendingReference(
        PendingProcessStore.ReferenceSnapshot references,
        String kind,
        String canonicalId
    ) throws IOException {
        if (references == null) {
            return false;
        }
        try {
            return references.isPending(kind, canonicalId);
        } catch (RuntimeException e) {
            throw new IOException(
                "management Pending reference identity is invalid",
                e
            );
        }
    }

    private static boolean isOwnerDeletedState(JSONObject state) {
        return state != null
            && STATUS_CANCELED.equals(state.optString("status", ""))
            && OWNER_DELETED_REASON.equals(
                state.optString(INVALIDATED_REASON_FIELD, "")
            );
    }

    /** Persists owner invalidation without discarding the request/history. */
    private JSONObject markOwnerDeletedStateLocked(
        File directory,
        JSONObject existingState
    ) throws Exception {
        long now = System.currentTimeMillis();
        JSONObject state = existingState == null
            ? new JSONObject()
            : existingState;
        String previousStatus = state.optString("status", "");
        if (!previousStatus.isEmpty()
            && !STATUS_CANCELED.equals(previousStatus)
            && !state.has(PRIOR_STATUS_FIELD)) {
            state.put(PRIOR_STATUS_FIELD, previousStatus);
        }
        state.put("status", STATUS_CANCELED);
        state.put(INVALIDATED_REASON_FIELD, OWNER_DELETED_REASON);
        if (!state.has("created_at")) {
            long createdAt = directory.lastModified();
            if (createdAt <= 0L || createdAt > now) {
                createdAt = now;
            }
            state.put("created_at", createdAt);
        }
        if (!state.has("rerun_required")) {
            state.put("rerun_required", false);
        }
        if (!state.has("notified")) {
            state.put("notified", false);
        }
        if (!state.has("user_requested")) {
            state.put("user_requested", false);
        }
        state.put("updated_at", now);
        store.writeState(directory, state);
        startupRecoveryIds.remove(directory.getName());
        return state;
    }

    /**
     * Deletes a Summary Job directory after the derived summary record has
     * been atomically written. This is the final step of the success path.
     */
    public void removeCompletedJob(String requestId)
        throws Exception {
        mutateUnderRoot(() -> {
            synchronized (TARGET_ADMISSION_LOCK) {
                File directory = requireJobDirectory(requestId);
                if (!isOwnerDeletedState(readState(requestId))) {
                    deleteJobDirectoryLocked(directory);
                }
            }
        });
    }

    /**
     * Invalidates Summary Jobs owned by deleted Context/Group entities while
     * retaining their request/state directories as history.  Every status is
     * moved to the explicit canceled terminal state and receives an
     * {@code invalidated_reason=owner_deleted} marker; the previous status and
     * any existing error are left in place for diagnostics.  This also covers
     * running jobs so a restart cannot put them back into recovery; the active
     * executor observes the marker at its prepared-result boundary and archives
     * any late provider result instead of writing an orphan summary.
     */
    public int invalidateJobsForOwners(
        Set<String> contextIds,
        Set<String> groupIds
    ) throws Exception {
        return queryUnderRoot(() -> {
            synchronized (TARGET_ADMISSION_LOCK) {
                Set<String> contexts = contextIds == null
                    ? Collections.emptySet()
                    : new HashSet<>(contextIds);
                Set<String> groups = groupIds == null
                    ? Collections.emptySet()
                    : new HashSet<>(groupIds);
                if (contexts.isEmpty() && groups.isEmpty()) {
                    return 0;
                }
                int invalidated = 0;
                for (File directory : store.listValidJobDirectories()) {
                    String requestId = directory.getName();
                    JSONObject request;
                    JSONObject state;
                    try {
                        request = JobValidator.parseJsonObject(
                            store.readRequest(directory),
                            MAX_REQUEST_BYTES,
                            "summary request"
                        );
                        state = store.readState(directory);
                    } catch (Exception ignored) {
                        continue;
                    }
                    String ownerType = request.optString("owner_type", "");
                    String ownerId = request.optString("owner_id", "");
                    boolean owned = ("context".equals(ownerType)
                            && contexts.contains(ownerId))
                        || ("group".equals(ownerType)
                            && groups.contains(ownerId));
                    if (!owned) {
                        continue;
                    }
                    if (!isOwnerDeletedState(state)) {
                        markOwnerDeletedStateLocked(directory, state);
                        invalidated++;
                    }
                }
                return invalidated;
            }
        });
    }

    /**
     * Crash-window convergence: if the derived summary record already carries
     * this request's source_hash, the job was effectively completed before the
     * directory deletion. Delete the leftover directory without re-calling the
     * API and return true; otherwise return false.
     */
    public boolean removeIfSourceHashMatches(
        String requestId,
        String recordedSourceHash
    ) throws Exception {
        return queryUnderRoot(() -> {
            if (recordedSourceHash == null
                || recordedSourceHash.trim().isEmpty()) {
                return false;
            }
            if (!store.jobDirectoryExists(requestId)) {
                return false;
            }
            if (isOwnerDeletedState(readState(requestId))) {
                return false;
            }
            JSONObject request = readRequest(requestId);
            if (!recordedSourceHash.equals(
                request.optString("source_hash", "")
            )) {
                return false;
            }
            deleteJobDirectoryLocked(store.jobDirectory(requestId));
            return true;
        });
    }

    private void deleteJobDirectoryLocked(File directory) throws IOException {
        String requestId = directory.getName();
        store.deleteJobDirectory(directory);
        startupRecoveryIds.remove(requestId);
    }

    /**
     * Drops fixed-boundary identities whose durable directories were removed
     * during Review. The snapshot boundary remains fixed: this only subtracts
     * impossible work and never admits a post-boundary request.
     */
    private Set<String> pruneMissingStartupRecoveryIdsLocked() {
        Set<String> pruned = new HashSet<>();
        Iterator<String> iterator = startupRecoveryIds.iterator();
        while (iterator.hasNext()) {
            String requestId = iterator.next();
            if (!store.jobDirectoryExists(requestId)) {
                iterator.remove();
                pruned.add(requestId);
            }
        }
        return pruned;
    }

    private String findActiveTargetLocked(SummaryTargetKey target)
        throws Exception {
        return findActiveTargetLocked(target, null);
    }

    private String findActiveTargetLocked(
        SummaryTargetKey target,
        String excludedRequestId
    ) throws Exception {
        for (File directory : store.listValidJobDirectories()) {
            if (excludedRequestId != null
                && excludedRequestId.equals(directory.getName())) {
                continue;
            }
            JSONObject state = store.readState(directory);
            if (state == null) {
                continue;
            }
            String status = state.optString("status", "");
            if (!ACTIVE_STATUSES.contains(status)) {
                continue;
            }
            JSONObject request = JobValidator.parseJsonObject(
                store.readRequest(directory),
                MAX_REQUEST_BYTES,
                "summary request"
            );
            if (target.equals(SummaryTargetKey.fromRequest(request))) {
                return directory.getName();
            }
        }
        return null;
    }

    private File requireJobDirectory(String requestId) {
        if (!store.jobDirectoryExists(requestId)) {
            throw new IllegalStateException(
                "summary job does not exist requestId=" + requestId
            );
        }
        return store.jobDirectory(requestId);
    }

    private void requireRecoveryPendingLocked() {
        if (!preparedForServiceStart) {
            throw new IllegalStateException(
                "Summary job store has not been prepared for service start"
            );
        }
        if (!recoveryDecisionOpen) {
            throw new IllegalStateException(
                "Summary recovery decision is not open"
            );
        }
        if (recoveryAutoRecover || recoveryCommitted) {
            throw new IllegalStateException(
                "Summary recovery decision has already been committed"
            );
        }
        if (startupRecoveryIds.isEmpty()) {
            throw new IllegalStateException(
                "Summary recovery snapshot is empty"
            );
        }
    }

    private void updateStatus(String requestId, String status)
        throws Exception {
        File directory = requireJobDirectory(requestId);
        JSONObject state = readState(requestId);
        state.put("status", status);
        state.put("updated_at", System.currentTimeMillis());
        store.writeState(directory, state);
    }

    private void setRerunRequired(String requestId, boolean value)
        throws Exception {
        File directory = requireJobDirectory(requestId);
        JSONObject state = readState(requestId);
        state.put("rerun_required", value);
        state.put("updated_at", System.currentTimeMillis());
        store.writeState(directory, state);
    }

    private void markUserRequestedLocked(String requestId) throws Exception {
        File directory = requireJobDirectory(requestId);
        JSONObject state = store.readState(directory);
        if (state == null || state.optBoolean("user_requested", false)) {
            return;
        }
        state.put("user_requested", true);
        state.put("updated_at", System.currentTimeMillis());
        store.writeState(directory, state);
    }

    private static void validateRequest(JSONObject request) {
        if (request == null) {
            throw new IllegalArgumentException("summary request is null");
        }
        Iterator<String> keys = request.keys();
        while (keys.hasNext()) {
            String key = keys.next();
            if (!REQUEST_FIELDS.contains(key)) {
                throw new IllegalArgumentException(
                    "summary request has unexpected field: " + key
                );
            }
        }

        String requestKind = requireField(request, "request_kind");
        if (!REQUEST_KINDS.contains(requestKind)) {
            throw new IllegalArgumentException(
                "unsupported summary request_kind: " + requestKind
            );
        }
        String ownerType = requireField(request, "owner_type");
        if (!OWNER_TYPES.contains(ownerType)) {
            throw new IllegalArgumentException(
                "unsupported summary owner_type: " + ownerType
            );
        }
        requireField(request, "owner_id");
        requireField(request, "target_lang");
        requireField(request, "cutoff");
        requireField(request, "source_hash");
    }

    private static String requireField(JSONObject request, String field) {
        if (!request.has(field) || request.isNull(field)) {
            throw new IllegalArgumentException(
                "summary request is missing field: " + field
            );
        }
        Object value = request.opt(field);
        if (!(value instanceof String)
            || ((String) value).trim().isEmpty()) {
            throw new IllegalArgumentException(
                "summary request field must be a non-blank string: " + field
            );
        }
        return (String) value;
    }
}
