package com.quarty.housamoembedtrans.translation;

import com.quarty.housamoembedtrans.storage.HistoryMapping;
import com.quarty.housamoembedtrans.storage.PersistentApiJobStore;
import com.quarty.housamoembedtrans.storage.SceneStore;
import com.quarty.housamoembedtrans.storage.SceneContextStore;
import com.quarty.housamoembedtrans.util.IoUtils;
import com.quarty.housamoembedtrans.util.JobValidator;
import com.quarty.housamoembedtrans.storage.TranslationSchemaValidator;

import android.content.Context;
import android.util.AtomicFile;
import android.util.Log;

import org.json.JSONObject;
import org.json.JSONArray;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.Set;

/**
 * Owns the persistent translation-job state and its process-local dispatch
 * indexes. The HET service and HET activities run in the same process and
 * therefore share one store instance; no queue-management method is exposed
 * through the cross-process AIDL interface.
 */
public final class TranslationJobStore {

    public static final String DIRECTORY_NAME = "translation_jobs";
    private static final String TAG = "TranslationJobStore";

    public enum RecoverySortOrder {
        CREATED_ASC("created_asc"),
        CREATED_DESC("created_desc"),
        STARTED_ASC("started_asc"),
        STARTED_DESC("started_desc");

        private final String configValue;

        RecoverySortOrder(String configValue) {
            this.configValue = configValue;
        }

        public String getConfigValue() {
            return configValue;
        }

        public static RecoverySortOrder fromConfigValue(String value) {
            for (RecoverySortOrder order : values()) {
                if (order.configValue.equals(value)) {
                    return order;
                }
            }
            throw new IllegalArgumentException(
                "Unknown translation recovery sort order: " + value
            );
        }
    }

    public interface QueueListener {
        void onQueueChanged(
            boolean hasPendingJobs,
            int heldQueuedJobCount,
            boolean repairingStartupJobs
        );
    }

    /** Observable state of the process-local Scene Validation Wait set. */
    public enum SceneValidationWaitState {
        NONE,
        WAITING,
        RECHECKING
    }

    public static final class HeldQueuedJob {
        private final String requestId;
        private final String scene;
        private final String targetLanguage;
        private final long createdAt;
        private final long startedAt;

        private HeldQueuedJob(
            String requestId,
            String scene,
            String targetLanguage,
            long createdAt,
            long startedAt
        ) {
            this.requestId = requestId;
            this.scene = scene;
            this.targetLanguage = targetLanguage;
            this.createdAt = createdAt;
            this.startedAt = startedAt;
        }

        public String getRequestId() {
            return requestId;
        }

        public String getScene() {
            return scene;
        }

        public String getTargetLanguage() {
            return targetLanguage;
        }

        public long getCreatedAt() {
            return createdAt;
        }

        public long getStartedAt() {
            return startedAt;
        }
    }

    public static final class ClaimedJob {
        private final String requestId;
        private final byte[] requestJson;

        public ClaimedJob(String requestId, byte[] requestJson) {
            this.requestId = requestId;
            this.requestJson = requestJson;
        }

        public String getRequestId() {
            return requestId;
        }

        public byte[] getRequestJson() {
            return requestJson;
        }
    }

    /** Durable terminal record exposed to the callback/recovery layers. */
    public static final class TerminalJob {
        private final String requestId;
        private final String scene;
        private final String targetLanguage;
        private final String status;
        private final TerminalOutcome.DeliveryState deliveryState;
        private final long updatedAt;
        private final String errorType;
        private final String errorMessage;

        TerminalJob(
            String requestId,
            String scene,
            String targetLanguage,
            String status,
            TerminalOutcome.DeliveryState deliveryState,
            long updatedAt,
            String errorType,
            String errorMessage
        ) {
            this.requestId = requestId;
            this.scene = scene;
            this.targetLanguage = targetLanguage;
            this.status = status;
            this.deliveryState = deliveryState;
            this.updatedAt = updatedAt;
            this.errorType = errorType;
            this.errorMessage = errorMessage;
        }

        public String getRequestId() { return requestId; }
        public String getScene() { return scene; }
        public String getTargetLanguage() { return targetLanguage; }
        public String getStatus() { return status; }
        public TerminalOutcome.DeliveryState getDeliveryState() {
            return deliveryState;
        }
        public long getUpdatedAt() { return updatedAt; }
        public String getErrorType() { return errorType; }
        public String getErrorMessage() { return errorMessage; }

        public TerminalOutcome.Kind getKind() {
            return TerminalOutcome.Kind.fromWireValue(status);
        }

        public boolean requiresDelivery() {
            return deliveryState == TerminalOutcome.DeliveryState.PENDING;
        }

        public boolean isSceneValidationFailure() {
            return "failed".equals(status) && "scene_validation".equals(
                errorType
            );
        }
    }

    /** Lightweight snapshot used by Context/Group Review and in-flight checks. */
    public static final class ReviewJob {
        private final String requestId;
        private final String scene;
        private final String status;
        private final String contextId;
        private final String groupId;

        ReviewJob(
            String requestId,
            String scene,
            String status,
            String contextId,
            String groupId
        ) {
            this.requestId = requestId;
            this.scene = scene;
            this.status = status;
            this.contextId = contextId;
            this.groupId = groupId;
        }

        public String getRequestId() {
            return requestId;
        }

        public String getScene() {
            return scene;
        }

        public String getStatus() {
            return status;
        }

        public String getContextId() {
            return contextId;
        }

        public String getGroupId() {
            return groupId;
        }
    }

    /** Structured duplicate-admission disposition. */
    public static final class AdmissionException extends Exception {
        private static final long serialVersionUID = 1L;
        private final String disposition;

        private AdmissionException(String disposition, String message) {
            super(message);
            this.disposition = disposition;
        }

        public String getDisposition() { return disposition; }
    }

    private static final int FORMAT_VERSION = 1;
    public static final int MAX_REQUEST_BYTES = 32 * 1024 * 1024;
    private static final int MAX_STATE_BYTES = 64 * 1024;
    private static final int MAX_PROGRESS_BYTES = 32 * 1024 * 1024;
    private static final int MAX_RESULT_BYTES = 32 * 1024 * 1024;

    private static final String REQUEST_FILE_NAME = "request.json";
    private static final String STATE_FILE_NAME = "state.json";
    private static final String PROGRESS_FILE_NAME = "progress.json";
    private static final String RESULT_FILE_NAME = "result.json";
    private static final String ERROR_FILE_NAME = "error.json";
    private static final String STATUS_QUEUED = "queued";
    private static final String STATUS_RUNNING = "running";
    private static final String STATUS_RESETTING = "resetting";
    private static final String STATUS_CANCELED = "canceled";
    private static final String STATUS_COMPLETED = "completed";
    private static final String STATUS_FAILED = "failed";
    private static final String STATUS_DAMAGED = "damaged";
    /** Durable cross-store admission marker.  A marked job is never claimable. */
    private static final String HISTORY_MEMBERSHIP_PENDING_FIELD =
        "history_membership_pending";
    /** Durable hold used until an outer Review journal has committed. */
    private static final String REVIEW_PUBLICATION_PENDING_FIELD =
        "review_publication_pending";

    private static TranslationJobStore instance;

    private static final class QueuedJobRef {
        private final String requestId;
        private final long queueSequence;

        private QueuedJobRef(String requestId, long queueSequence) {
            this.requestId = requestId;
            this.queueSequence = queueSequence;
        }
    }

    /** A Wait entry stores only identity and disk location, never JSON bytes. */
    private static final class SceneValidationWait {
        private final String requestId;
        private final File jobDirectory;

        private SceneValidationWait(String requestId, File jobDirectory) {
            this.requestId = requestId;
            this.jobDirectory = jobDirectory;
        }
    }

    private static final class StartupJob {
        private final File directory;
        private final JSONObject state;
        private final HeldQueuedJob info;

        private StartupJob(
            File directory,
            JSONObject state,
            HeldQueuedJob info
        ) {
            this.directory = directory;
            this.state = state;
            this.info = info;
        }
    }

    private final File jobRoot;
    private final PersistentApiJobStore jobStore;
    private final SceneStore sceneStore;
    private final TranslationSchemaValidator resultSchemaValidator;
    private final Set<String> pendingRequestIds = new HashSet<>();
    private final Set<QueueListener> queueListeners = new HashSet<>();
    private final LinkedHashMap<String, HeldQueuedJob> heldQueuedJobs =
        new LinkedHashMap<>();
    /** In-memory count published by durable mutations/startup repair. */
    private final Set<String> manualRerunCandidateIds = new HashSet<>();
    /** Failed jobs that belong to the fixed legacy startup snapshot. */
    private final Set<String> startupManualCandidateIds =
        new LinkedHashSet<>();
    private final List<File> startupRepairCandidates = new ArrayList<>();
    private final LinkedHashMap<String, StartupJob> startupReadyJobs =
        new LinkedHashMap<>();
    /**
     * Requests admitted before the unified recovery decision.  The map is
     * deliberately process-local and contains identity plus arrival order
     * only; the request/state files are the durable admission record.
     */
    private final LinkedHashMap<String, Long> startupAdmissionOrder =
        new LinkedHashMap<>();
    /** Durable jobs waiting for the Context membership side of admission. */
    private final Set<String> historyMembershipPendingIds = new HashSet<>();
    /** Durable Review admissions not publishable before the outer commit. */
    private final Set<String> reviewPublicationPendingIds = new HashSet<>();
    private final LinkedHashMap<String, SceneValidationWait>
        sceneValidationWaits = new LinkedHashMap<>();
    private final PriorityQueue<QueuedJobRef> pendingQueue =
        new PriorityQueue<>((left, right) -> {
            int sequenceResult = Long.compare(
                left.queueSequence,
                right.queueSequence
            );
            if (sequenceResult != 0) {
                return sequenceResult;
            }
            return left.requestId.compareTo(right.requestId);
        });

    private long nextQueueSequence = 1L;
    private long startupRepairGeneration;
    private boolean preparedForServiceStart;
    private boolean startupAutoRecover;
    private boolean recoveryDecisionOpen;
    private boolean startupRecoveryCommitted;
    private RecoverySortOrder startupRecoverySortOrder =
        RecoverySortOrder.CREATED_ASC;
    private boolean repairingStartupJobs;
    private boolean initialAutoSyncFinished;
    private boolean sceneValidationWaitRecheckInProgress;

    public static synchronized TranslationJobStore getInstance(Context context) {
        if (instance == null) {
            instance = new TranslationJobStore(context);
        }
        return instance;
    }

    /** Root directory used by the Review outer before-image journal. */
    public File getRootDirectory() {
        return jobRoot;
    }

    private TranslationJobStore(Context context) {
        Context appContext = context.getApplicationContext();
        Context safeContext = appContext != null ? appContext : context;
        jobRoot = new File(safeContext.getFilesDir(), DIRECTORY_NAME);
        jobStore = PersistentApiJobStore.createForAndroid(
            jobRoot,
            PersistentApiJobStore.RequestIdFormat.UUID,
            MAX_REQUEST_BYTES,
            MAX_STATE_BYTES
        );
        sceneStore = new SceneStore(safeContext);
        resultSchemaValidator = new TranslationSchemaValidator(safeContext);
    }

    /**
     * Starts a new in-process service epoch without scanning durable jobs.
     * Android may recreate the Service while this singleton remains alive;
     * admissions observed before the next prepare pass must therefore enter
     * the new startup submission prefix rather than inheriting the previous
     * committed queue state.
     */
    public synchronized void beginServiceStart() {
        preparedForServiceStart = false;
        recoveryDecisionOpen = false;
        startupRecoveryCommitted = false;
        repairingStartupJobs = false;
        initialAutoSyncFinished = false;
        pendingQueue.clear();
        pendingRequestIds.clear();
        heldQueuedJobs.clear();
        startupRepairCandidates.clear();
        startupReadyJobs.clear();
        sceneValidationWaits.clear();
        historyMembershipPendingIds.clear();
        reviewPublicationPendingIds.clear();
        manualRerunCandidateIds.clear();
        startupManualCandidateIds.clear();
        startupAdmissionOrder.clear();
        sceneValidationWaitRecheckInProgress = false;
    }

    /**
     * Performs the one-time reconciliation for this HET process.
     *
     * Existing running jobs are verified in the background before they are
     * made queued again, because the synchronized scene may already contain
     * their completed target language. In manual mode, startup jobs remain
     * queued on disk but are held out of the dispatch heap until the user
     * submits an order.
     *
     * @return the generation token that must be supplied to the matching
     * background repair worker
     */
    public long prepareForServiceStart(
        boolean autoRecover,
        RecoverySortOrder sortOrder
    ) throws Exception {
        return SceneContextStore.withRootAccess(() ->
            prepareForServiceStartLocked(autoRecover, sortOrder)
        );
    }

    private long prepareForServiceStartLocked(
        boolean autoRecover,
        RecoverySortOrder sortOrder
    ) throws Exception {
        if (sortOrder == null) {
            throw new IllegalArgumentException("sortOrder cannot be null");
        }

        final long repairGeneration;
        synchronized (this) {
            startupRepairGeneration =
                startupRepairGeneration == Long.MAX_VALUE
                    ? 1L
                    : startupRepairGeneration + 1L;
            repairGeneration = startupRepairGeneration;
            preparedForServiceStart = false;
            recoveryDecisionOpen = false;
            startupRecoveryCommitted = false;
            jobStore.ensureRoot();
            pendingQueue.clear();
            pendingRequestIds.clear();
            heldQueuedJobs.clear();
            manualRerunCandidateIds.clear();
            startupManualCandidateIds.clear();
            historyMembershipPendingIds.clear();
            reviewPublicationPendingIds.clear();
            startupRepairCandidates.clear();
            startupReadyJobs.clear();
            sceneValidationWaits.clear();
            sceneValidationWaitRecheckInProgress = false;
            initialAutoSyncFinished = false;
            startupAutoRecover = autoRecover;
            startupRecoverySortOrder = sortOrder;
            repairingStartupJobs = false;

            List<File> jobDirectories = jobStore.listValidJobDirectories();

            long maximumSequence = 0L;
            for (File jobDirectory : jobDirectories) {
                String requestId = jobDirectory.getName();

                // These directories were admitted by this process before the
                // startup snapshot boundary.  They are durable jobs, but not
                // legacy recovery candidates and must be ordered before the
                // selected/automatically recovered batch at commit.
                if (startupAdmissionOrder.containsKey(requestId)) {
                    try {
                        JSONObject admittedState = readState(jobDirectory);
                        releaseRecoveredReviewPublicationHoldLocked(
                            jobDirectory,
                            requestId,
                            admittedState
                        );
                        syncHistoryMembershipPendingLocked(
                            requestId,
                            admittedState
                        );
                    } catch (Exception e) {
                        // Keep the identity in the startup submission list;
                        // the normal durable repair path will reconcile it
                        // after the recovery boundary.
                        Log.w(
                            TAG,
                            "Could not inspect pre-startup admission "
                                + "requestId=" + requestId,
                            e
                        );
                    }
                    continue;
                }

                final JSONObject state;
                try {
                    state = readState(jobDirectory);
                } catch (Exception e) {
                    startupRepairCandidates.add(jobDirectory);
                    Log.w(
                        TAG,
                        "Deferred damaged translation state to background "
                            + "repair requestId="
                            + requestId,
                        e
                    );
                    continue;
                }

                if (state == null) {
                    startupRepairCandidates.add(jobDirectory);
                    Log.w(
                        TAG,
                        "Deferred translation job with missing state to "
                            + "background repair requestId="
                            + requestId
                    );
                    continue;
                }

                releaseRecoveredReviewPublicationHoldLocked(
                    jobDirectory,
                    requestId,
                    state
                );

                String status = state.optString("status", "");
                final long queueSequence;
                try {
                    queueSequence = readOptionalQueueSequence(
                        state,
                        requestId
                    );
                    maximumSequence = Math.max(
                        maximumSequence,
                        queueSequence
                    );
                } catch (Exception e) {
                    if (isTerminalStatus(status)) {
                        startupRepairCandidates.add(jobDirectory);
                        Log.w(
                            TAG,
                            "Deferred terminal state with an invalid queue "
                                + "sequence requestId="
                                + requestId,
                            e
                        );
                        continue;
                    }
                    startupRepairCandidates.add(jobDirectory);
                    Log.w(
                        TAG,
                        "Deferred invalid translation sequence to "
                            + "background repair requestId="
                            + requestId,
                        e
                    );
                    continue;
                }

                if (isTerminalStatus(status)) {
                    // Terminal payloads are checked by the same background
                    // reconciliation path as interrupted jobs.  This keeps
                    // damaged/conflicting payloads out of replay without
                    // ever rebuilding a terminal outcome as queued.
                    startupRepairCandidates.add(jobDirectory);
                    continue;
                }

                // The service-start thread deliberately does not validate a
                // request against Scene, or trust a seemingly healthy queued
                // state.  Every recognizable non-terminal identity is sent
                // through the background STARTUP_RECOVERY path so a missing
                // Mirror can become a Scene Validation Wait instead of
                // poisoning the whole startup batch.
                startupRepairCandidates.add(jobDirectory);
            }

            if (maximumSequence == Long.MAX_VALUE) {
                throw new IllegalStateException(
                    "Translation queue sequence is exhausted"
                );
            }
            nextQueueSequence = maximumSequence + 1L;

            repairingStartupJobs = !startupRepairCandidates.isEmpty();
            preparedForServiceStart = true;
            if (!repairingStartupJobs) {
                try {
                    finalizeStartupJobsLocked();
                } catch (Exception e) {
                    repairingStartupJobs = true;
                    Log.w(
                        TAG,
                        "Deferred startup queue finalization to background "
                            + "repair generation="
                            + repairGeneration,
                        e
                    );
                }
            }
        }

        notifyQueueListener();
        return repairGeneration;
    }

    public synchronized List<HeldQueuedJob> getHeldQueuedJobs() {
        return new ArrayList<>(heldQueuedJobs.values());
    }

    /** Lightweight startup snapshot used by notifications without a disk scan. */
    public synchronized int getManualRerunCandidateCount() {
        return manualRerunCandidateIds.size();
    }

    public synchronized int getHeldQueuedJobCount() {
        return heldQueuedJobs.size();
    }

    /** Whether the fixed startup snapshot still needs a manual decision. */
    public synchronized boolean isManualRecoveryDecisionPending() {
        return recoveryDecisionOpen
            && !startupRecoveryCommitted
            && (!heldQueuedJobs.isEmpty()
                || !startupManualCandidateIds.isEmpty()
                || repairingStartupJobs);
    }

    public synchronized boolean isRepairingStartupJobs() {
        return repairingStartupJobs;
    }

    /** Opens Translation recovery selection after the Review gate. */
    public synchronized void openRecoveryDecision() {
        if (preparedForServiceStart) {
            recoveryDecisionOpen = true;
            notifyQueueListener();
        }
    }

    /** Returns the number of process-local Scene Validation Wait entries. */
    public synchronized int getSceneValidationWaitCount() {
        return sceneValidationWaits.size();
    }

    /**
     * Small diagnostic seam for the Service/UI.  Wait is intentionally not
     * represented as a pending API queue count or a persisted status.
     */
    public synchronized SceneValidationWaitState
        getSceneValidationWaitState() {
        if (sceneValidationWaitRecheckInProgress) {
            return SceneValidationWaitState.RECHECKING;
        }
        return sceneValidationWaits.isEmpty()
            ? SceneValidationWaitState.NONE
            : SceneValidationWaitState.WAITING;
    }

    public synchronized boolean isSceneValidationWaiting(String requestId) {
        return requestId != null && sceneValidationWaits.containsKey(requestId);
    }

    public synchronized boolean isInitialAutoSyncFinished() {
        return initialAutoSyncFinished;
    }

    public synchronized boolean isManualStartupRepairInProgress() {
        return repairingStartupJobs && !startupAutoRecover;
    }

    public synchronized boolean hasPendingJobs() {
        return !pendingQueue.isEmpty();
    }

    /**
     * Returns whether a queued/running Translation Job is the producer for a
     * particular missing Scene Summary.  The route and target language are
     * part of the key; unrelated work must not make History Resolution wait.
     * The caller supplies the current request id so a job cannot qualify
     * itself after it has been claimed and marked active.
     */
    public synchronized boolean hasQueuedOrRunningSceneSummaryProducer(
        String contextId,
        String scene,
        String targetLang,
        String excludedRequestId
    ) {
        if (contextId == null || contextId.isEmpty()
            || scene == null || scene.isEmpty()
            || targetLang == null || targetLang.isEmpty()) {
            return false;
        }
        try {
            for (File directory : jobStore.listValidJobDirectories()) {
                String requestId = directory.getName();
                if (requestId.equals(excludedRequestId)) {
                    continue;
                }
                JSONObject state = readState(directory);
                if (state == null) {
                    continue;
                }
                String status = state.optString("status", "");
                if (!STATUS_QUEUED.equals(status)
                    && !STATUS_RUNNING.equals(status)) {
                    continue;
                }
                if (!scene.equals(state.optString("scene", ""))
                    || !targetLang.equals(
                        state.optString("target_lang", "")
                    )) {
                    continue;
                }
                Object mapping = state.opt(HistoryMapping.FIELD);
                if (HistoryMapping.resolutionOfValue(mapping)
                    != HistoryMapping.Resolution.VALID) {
                    continue;
                }
                JSONObject mappingObject = (JSONObject) mapping;
                if (contextId.equals(mappingObject.optString(
                    HistoryMapping.CONTEXT_ID,
                    ""
                ))) {
                    return true;
                }
            }
        } catch (Exception e) {
            Log.w(
                TAG,
                "Could not inspect keyed Scene Summary producers",
                e
            );
        }
        return false;
    }

    /**
     * Repairs startup jobs whose state could not be trusted by the lightweight
     * service-start scan. This method performs disk work and must run on a
     * background thread.
     *
     * @return true when the same generation still has retained work and
     * should be retried
     */
    public boolean repairDamagedStartupJobs(long repairGeneration) {
        final List<File> candidates;
        final List<SceneValidationWait> waits;
        final boolean autoRecover;
        synchronized (this) {
            requirePreparedLocked();
            if (repairGeneration != startupRepairGeneration
                || !repairingStartupJobs) {
                return false;
            }
            candidates = new ArrayList<>(startupRepairCandidates);
            waits = initialAutoSyncFinished
                && !sceneValidationWaitRecheckInProgress
                ? new ArrayList<>(sceneValidationWaits.values())
                : new ArrayList<>();
            autoRecover = startupAutoRecover;
        }

        int recoveredCount = 0;
        int completedCount = 0;
        int removedCount = 0;
        int retainedCount = 0;
        int failedCount = 0;
        Set<String> resolvedRequestIds = new HashSet<>();
        Set<String> resolvedWaitIds = new HashSet<>();

        for (int candidateIndex = 0;
            candidateIndex < candidates.size();
            candidateIndex++) {
            File jobDirectory = candidates.get(candidateIndex);
            if (Thread.currentThread().isInterrupted()) {
                retainedCount += candidates.size() - candidateIndex;
                break;
            }

            try {
                synchronized (this) {
                    if (repairGeneration != startupRepairGeneration) {
                        return false;
                    }

                    ProcessResult result = processIncompleteJob(
                        jobDirectory,
                        ValidationMode.STARTUP_RECOVERY,
                        false
                    );
                    if (result == ProcessResult.SCENE_MISSING) {
                        if (initialAutoSyncFinished) {
                            result = processIncompleteJob(
                                jobDirectory,
                                ValidationMode.SCENE_WAIT_RECHECK,
                                false
                            );
                        } else {
                            sceneValidationWaits.put(
                                jobDirectory.getName(),
                                new SceneValidationWait(
                                    jobDirectory.getName(),
                                    jobDirectory
                                )
                            );
                            resolvedRequestIds.add(
                                jobDirectory.getName()
                            );
                            continue;
                        }
                    }
                    switch (result) {
                        case VALID:
                        case REPAIRED: {
                            queueStartupReadyLocked(jobDirectory);
                            resolvedRequestIds.add(
                                jobDirectory.getName()
                            );
                            recoveredCount++;
                            break;
                        }
                        case COMPLETED:
                            resolvedRequestIds.add(
                                jobDirectory.getName()
                            );
                            completedCount++;
                            break;
                        case FAILED:
                            resolvedRequestIds.add(
                                jobDirectory.getName()
                            );
                            startupManualCandidateIds.add(
                                jobDirectory.getName()
                            );
                            failedCount++;
                            break;
                        case DAMAGED:
                            resolvedRequestIds.add(
                                jobDirectory.getName()
                            );
                            retainedCount++;
                            break;
                        case REMOVED:
                            resolvedRequestIds.add(
                                jobDirectory.getName()
                            );
                            removedCount++;
                            break;
                        case TERMINAL:
                            resolvedRequestIds.add(
                                jobDirectory.getName()
                            );
                            if (manualRerunCandidateIds.contains(
                                jobDirectory.getName()
                            )) {
                                startupManualCandidateIds.add(
                                    jobDirectory.getName()
                                );
                            }
                            break;
                        case SCENE_MISSING:
                            throw new IllegalStateException(
                                "Scene wait recheck did not resolve missing "
                                    + "Scene requestId="
                                    + jobDirectory.getName()
                            );
                    }
                }
            } catch (Exception e) {
                retainedCount++;
                Log.w(
                    TAG,
                    "Kept translation job after background repair failed "
                        + "requestId="
                        + jobDirectory.getName(),
                    e
                );
            }
        }

        for (SceneValidationWait wait : waits) {
            if (Thread.currentThread().isInterrupted()) {
                retainedCount += waits.size() - resolvedWaitIds.size();
                break;
            }

            synchronized (this) {
                if (repairGeneration != startupRepairGeneration) {
                    return false;
                }
                SceneValidationWait current = sceneValidationWaits.get(
                    wait.requestId
                );
                if (current != wait) {
                    continue;
                }
                try {
                    ProcessResult result = processIncompleteJob(
                        wait.jobDirectory,
                        ValidationMode.SCENE_WAIT_RECHECK,
                        false
                    );
                    switch (result) {
                        case VALID:
                        case REPAIRED:
                            queueStartupReadyLocked(wait.jobDirectory);
                            resolvedWaitIds.add(wait.requestId);
                            recoveredCount++;
                            break;
                        case COMPLETED:
                            resolvedWaitIds.add(wait.requestId);
                            completedCount++;
                            break;
                        case FAILED:
                            resolvedWaitIds.add(wait.requestId);
                            startupManualCandidateIds.add(wait.requestId);
                            failedCount++;
                            break;
                        case DAMAGED:
                            resolvedWaitIds.add(wait.requestId);
                            retainedCount++;
                            break;
                        case REMOVED:
                            resolvedWaitIds.add(wait.requestId);
                            removedCount++;
                            break;
                        case TERMINAL:
                            resolvedWaitIds.add(wait.requestId);
                            if (manualRerunCandidateIds.contains(
                                wait.requestId
                            )) {
                                startupManualCandidateIds.add(
                                    wait.requestId
                                );
                            }
                            break;
                        case SCENE_MISSING:
                            throw new IllegalStateException(
                                "Scene wait recheck returned missing "
                                    + "without a failed state requestId="
                                    + wait.requestId
                            );
                    }
                } catch (Exception e) {
                    retainedCount++;
                    Log.w(
                        TAG,
                        "Kept Scene Validation Wait after recheck failed "
                            + "requestId="
                            + wait.requestId,
                        e
                    );
                }
            }
        }

        final boolean retryRequired;
        synchronized (this) {
            if (repairGeneration != startupRepairGeneration) {
                return false;
            }

            if (!resolvedRequestIds.isEmpty()) {
                startupRepairCandidates.removeIf(candidate ->
                resolvedRequestIds.contains(candidate.getName())
                );
            }

            for (String requestId : resolvedWaitIds) {
                sceneValidationWaits.remove(requestId);
            }

            if (canFinalizeStartupJobsLocked()) {
                try {
                    finalizeStartupJobsLocked();
                } catch (Exception e) {
                    retainedCount++;
                    repairingStartupJobs = true;
                    Log.w(
                        TAG,
                        "Kept repaired startup batch after queue "
                            + "finalization failed generation="
                            + repairGeneration,
                        e
                    );
                }
            }
            repairingStartupJobs = !startupRepairCandidates.isEmpty()
                || (initialAutoSyncFinished
                    && !sceneValidationWaits.isEmpty())
                || !startupReadyJobs.isEmpty();
            retryRequired = repairingStartupJobs;
        }

        Log.i(
            TAG,
            "Startup repair pass finished generation="
                + repairGeneration
                + " recovered="
                + recoveredCount
                + " completed="
                + completedCount
                + " failed="
                + failedCount
                + " removed="
                + removedCount
                + " retained="
                + retainedCount
                + " retryRequired="
                + retryRequired
                + " autoRecover="
                + autoRecover
        );
        notifyQueueListener();
        return retryRequired;
    }

    /** Publishes stable startup-ready jobs without waiting for one bad entry. */
    private void queueStartupReadyLocked(File jobDirectory) throws Exception {
        JSONObject state = readState(jobDirectory);
        if (state != null
            && STATUS_RUNNING.equals(state.optString("status", ""))) {
            state.put("status", STATUS_QUEUED);
            state.put("updated_at", System.currentTimeMillis());
            // Do not remove started_at: it is the recovery sort key and the
            // diagnostic timestamp for an interrupted running job.
            writeState(jobDirectory, state);
        }
        if (state == null
            || !STATUS_QUEUED.equals(state.optString("status", ""))) {
            throw new IllegalStateException(
                "Repaired startup job did not become queued requestId="
                    + jobDirectory.getName()
            );
        }

        syncHistoryMembershipPendingLocked(jobDirectory.getName(), state);

        long queueSequence = readOptionalQueueSequence(
            state,
            jobDirectory.getName()
        );
        observeQueueSequenceLocked(queueSequence);
        startupReadyJobs.put(
            jobDirectory.getName(),
            new StartupJob(
                jobDirectory,
                state,
                heldJobFromState(state, jobDirectory.getName())
            )
        );
    }

    /**
     * Returns true only at the atomic startup-recovery convergence point. A
     * ready subset is not sufficient while repair candidates remain. Before
     * the first full Scene Sync, however, a Scene Validation Wait is an
     * isolated entry waiting for that port and must not block valid Mirror
     * jobs (or an otherwise empty recovery batch) from committing. Once the
     * first sync has been signalled, every Wait must converge before the
     * boundary can open.
     */
    private boolean canFinalizeStartupJobsLocked() {
        return startupRepairCandidates.isEmpty()
            && !sceneValidationWaitRecheckInProgress
            && (!initialAutoSyncFinished || sceneValidationWaits.isEmpty());
    }

    /**
     * Completes the one per-Service AUTO_FULL_SYNC barrier.  The caller may
     * be a Scene Sync worker, but the Wait traversal itself is deliberately a
     * background operation and uses one immutable snapshot of the entries
     * that existed at the signal boundary.
     *
     * @return true when retained transient Wait work should be retried
     */
    public boolean signalFirstAutoSyncFinished(long repairGeneration) {
        final List<SceneValidationWait> waits;
        synchronized (this) {
            requirePreparedLocked();
            if (repairGeneration != startupRepairGeneration
                || initialAutoSyncFinished) {
                return false;
            }
            initialAutoSyncFinished = true;
            waits = new ArrayList<>(sceneValidationWaits.values());
            sceneValidationWaitRecheckInProgress = !waits.isEmpty();
        }

        Set<String> resolvedWaitIds = new HashSet<>();
        for (SceneValidationWait wait : waits) {
            if (Thread.currentThread().isInterrupted()) {
                break;
            }
            synchronized (this) {
                if (repairGeneration != startupRepairGeneration) {
                    return false;
                }
                if (sceneValidationWaits.get(wait.requestId) != wait) {
                    continue;
                }
                try {
                    ProcessResult result = processIncompleteJob(
                        wait.jobDirectory,
                        ValidationMode.SCENE_WAIT_RECHECK,
                        false
                    );
                    switch (result) {
                        case VALID:
                        case REPAIRED:
                            queueStartupReadyLocked(wait.jobDirectory);
                            resolvedWaitIds.add(wait.requestId);
                            break;
                        case COMPLETED:
                        case DAMAGED:
                        case REMOVED:
                        case TERMINAL:
                            resolvedWaitIds.add(wait.requestId);
                            if (manualRerunCandidateIds.contains(
                                wait.requestId
                            )) {
                                startupManualCandidateIds.add(wait.requestId);
                            }
                            break;
                        case FAILED:
                            startupManualCandidateIds.add(wait.requestId);
                            resolvedWaitIds.add(wait.requestId);
                            break;
                        case SCENE_MISSING:
                            throw new IllegalStateException(
                                "Scene Validation Wait remained missing "
                                    + "after AUTO_FULL_SYNC requestId="
                                    + wait.requestId
                            );
                    }
                } catch (Exception e) {
                    // Temporary SceneAccess/I/O failures retain the Wait and
                    // are retried by the existing startup-repair scheduler.
                    Log.w(
                        TAG,
                        "Retained Scene Validation Wait after auto-sync "
                            + "recheck requestId="
                            + wait.requestId,
                        e
                    );
                }
            }
        }

        final boolean retryRequired;
        synchronized (this) {
            if (repairGeneration != startupRepairGeneration) {
                return false;
            }
            for (String requestId : resolvedWaitIds) {
                sceneValidationWaits.remove(requestId);
            }
            // The final Wait recheck is itself part of the startup recovery
            // transaction.  Close that latch before testing the same
            // convergence predicate used by the ordinary repair pass.
            sceneValidationWaitRecheckInProgress = false;
            if (canFinalizeStartupJobsLocked()) {
                try {
                    finalizeStartupJobsLocked();
                } catch (Exception e) {
                    Log.w(
                        TAG,
                        "Could not publish startup jobs after auto-sync "
                            + "Wait recheck generation="
                            + repairGeneration,
                        e
                    );
                }
            }
            repairingStartupJobs = !startupRepairCandidates.isEmpty()
                || (initialAutoSyncFinished
                    && !sceneValidationWaits.isEmpty())
                || !startupReadyJobs.isEmpty();
            retryRequired = repairingStartupJobs;
        }
        notifyQueueListener();
        return retryRequired;
    }

    /**
     * Publishes the complete startup batch only after every repair candidate
     * has reached a stable state. Jobs created during this service lifetime
     * are already in the normal queue and therefore remain ahead of this
     * recovered batch.
     */
    private void finalizeStartupJobsLocked() throws Exception {
        List<StartupJob> startupJobs =
            new ArrayList<>(startupReadyJobs.values());
        startupJobs.sort(recoveryComparator(
            startupAutoRecover
                ? startupRecoverySortOrder
                : RecoverySortOrder.CREATED_ASC
        ));

        if (startupAutoRecover) {
            LinkedHashMap<File, JSONObject> originalStates =
                new LinkedHashMap<>();
            LinkedHashMap<File, JSONObject> updatedStates =
                new LinkedHashMap<>();
            LinkedHashMap<String, Long> recoveredSequences =
                new LinkedHashMap<>();
            long now = System.currentTimeMillis();

            // The process-local submissions are the prefix of the unified
            // queue.  Persist their positions before any legacy recovery job
            // receives a sequence number.
            publishStartupAdmissionSequencesLocked(true);

            for (StartupJob job : startupJobs) {
                JSONObject original = new JSONObject(
                    job.state.toString()
                );
                JSONObject updated = new JSONObject(
                    job.state.toString()
                );
                long queueSequence = allocateQueueSequenceLocked();
                updated.put("queue_sequence", queueSequence);
                updated.put("updated_at", now);
                originalStates.put(job.directory, original);
                updatedStates.put(job.directory, updated);
                recoveredSequences.put(
                    job.info.getRequestId(),
                    queueSequence
                );
            }

            writeStateBatch(originalStates, updatedStates);
            for (Map.Entry<String, Long> entry
                : recoveredSequences.entrySet()) {
                addPendingJobLocked(entry.getKey(), entry.getValue());
            }
        } else {
            for (StartupJob job : startupJobs) {
                heldQueuedJobs.put(
                    job.info.getRequestId(),
                    job.info
                );
            }
            if (startupJobs.isEmpty() && startupManualCandidateIds.isEmpty()) {
                // There is no legacy decision to wait for.  The atomic
                // boundary is still published so pre-startup submissions can
                // become ordinary queued work.
                publishStartupAdmissionSequencesLocked(true);
            }
        }

        startupReadyJobs.clear();
        startupRecoveryCommitted = startupAutoRecover
            || (startupJobs.isEmpty() && startupManualCandidateIds.isEmpty());
        if (startupRecoveryCommitted) {
            startupAdmissionOrder.clear();
        }
        repairingStartupJobs = false;
    }

    /**
     * Assigns durable queue positions to submissions held before the recovery
     * boundary.  A partially written batch is safe to retry: an already
     * persisted position is reused, while no in-memory queue entry is exposed
     * until the caller publishes the boundary.
     */
    private void publishStartupAdmissionSequencesLocked(boolean enqueueNow)
        throws Exception {
        List<String> requestIds = new ArrayList<>(
            startupAdmissionOrder.keySet()
        );
        for (String requestId : requestIds) {
            File directory = jobStore.jobDirectory(requestId);
            JSONObject state = readState(directory);
            if (state == null) {
                continue;
            }
            syncHistoryMembershipPendingLocked(requestId, state);
            if (!STATUS_QUEUED.equals(state.optString("status", ""))) {
                continue;
            }
            long sequence = readOptionalQueueSequence(state, requestId);
            if (sequence <= 0L) {
                sequence = allocateQueueSequenceLocked();
                state.put("queue_sequence", sequence);
                state.put("updated_at", System.currentTimeMillis());
                writeState(directory, state);
            } else {
                observeQueueSequenceLocked(sequence);
            }
            if (enqueueNow) {
                addPendingJobLocked(requestId, sequence);
            }
        }
    }

    /**
     * Applies one user-defined order to the held startup queue and explicit
     * manual-rerun candidates.  Failed candidates are reset one at a time,
     * with the queue sequence allocated from the same counter as held jobs;
     * this makes the mixed list a single dispatch queue rather than two
     * competing batches.
     */
    public void applyManualRecoveryOrder(
        List<String> orderedRequestIds
    ) throws Exception {
        if (orderedRequestIds == null) {
            throw new IllegalArgumentException(
                "orderedRequestIds cannot be null"
            );
        }

        try {
            synchronized (this) {
                requirePreparedLocked();
                requireRecoveryDecisionOpenLocked();
                requireStartupRepairCompleteLocked();

            Set<String> selectedIds = new HashSet<>();
            Set<String> selectedHeldIds = new HashSet<>();
            Set<String> selectedFailedIds = new HashSet<>();
            for (String requestId : orderedRequestIds) {
                if (requestId == null || !selectedIds.add(requestId)) {
                    throw new IllegalArgumentException(
                        "Duplicate or empty request in manual recovery order: "
                            + requestId
                    );
                }
                if (heldQueuedJobs.containsKey(requestId)) {
                    selectedHeldIds.add(requestId);
                    continue;
                }

                File directory = jobStore.jobDirectory(requestId);
                JSONObject state = readState(directory);
                if (state == null
                    || !STATUS_FAILED.equals(
                        state.optString("status", "")
                    )
                    || !startupManualCandidateIds.contains(requestId)) {
                    throw new IllegalArgumentException(
                        "Request is not in the Translation startup recovery "
                            + "snapshot: "
                            + requestId
                    );
                }
                if (isSceneValidationErrorLocked(directory)) {
                    throw new IllegalArgumentException(
                        "Scene validation must be repaired before rerun: "
                            + requestId
                    );
                }
                selectedFailedIds.add(requestId);
            }

            LinkedHashMap<File, JSONObject> originalStates =
                new LinkedHashMap<>();
            LinkedHashMap<File, JSONObject> updatedStates =
                new LinkedHashMap<>();
            LinkedHashMap<String, Long> selectedHeldSequences =
                new LinkedHashMap<>();
            LinkedHashMap<String, Long> selectedSequences =
                new LinkedHashMap<>();
            long now = System.currentTimeMillis();

            // Manual recovery uses the user's submission as the commit
            // boundary.  Requests admitted before that boundary must be
            // persisted/numbered first, while snapshot jobs remain governed
            // by the submitted order below.
            publishStartupAdmissionSequencesLocked(false);

            // Allocate all selected positions in the user-visible order,
            // before any state transition is published.
            for (String requestId : orderedRequestIds) {
                selectedSequences.put(
                    requestId,
                    allocateQueueSequenceLocked()
                );
            }

            // Validate and prepare the held queue transitions first.  Their
            // state writes are batched so an ordinary queue-only submission
            // retains the previous all-or-nothing behavior.
            for (String requestId : heldQueuedJobs.keySet()) {
                File directory = jobStore.jobDirectory(requestId);
                JSONObject state = requireHeldQueuedState(
                    directory,
                    requestId
                );
                originalStates.put(
                    directory,
                    new JSONObject(state.toString())
                );
                if (selectedHeldIds.contains(requestId)) {
                    long sequence = selectedSequences.get(requestId);
                    state.put("queue_sequence", sequence);
                    state.put("updated_at", now);
                    updatedStates.put(directory, state);
                    selectedHeldSequences.put(requestId, sequence);
                } else {
                    state.put("status", STATUS_CANCELED);
                    state.put("updated_at", now);
                    updatedStates.put(directory, state);
                }
            }
            writeStateBatch(originalStates, updatedStates);

            // Publish the held-batch result to the in-memory indexes before
            // touching any failed candidate.  If a later reset fails, the
            // already-written held states are still represented accurately:
            // selected entries are pending and unselected entries are gone.
            heldQueuedJobs.clear();
            for (Map.Entry<String, Long> entry
                : selectedHeldSequences.entrySet()) {
                addPendingJobLocked(entry.getKey(), entry.getValue());
            }

            // Reset selected failures in the exact order supplied by the
            // user.  The local manual-rerun primitive deliberately abandons
            // pending failure delivery without fabricating a game ACK, then
            // publishes resetting before destructive cleanup.
            for (String requestId : orderedRequestIds) {
                if (!selectedFailedIds.contains(requestId)) {
                    continue;
                }
                File directory = jobStore.jobDirectory(requestId);
                JSONObject state = readState(directory);
                if (state == null
                    || !STATUS_FAILED.equals(
                        state.optString("status", "")
                    )) {
                    throw new IllegalStateException(
                        "Failed candidate changed before reset: "
                            + requestId
                    );
                }
                long sequence = selectedSequences.get(requestId);
                rerunManualCandidateLocked(
                    directory,
                    requestId,
                    state,
                    sequence
                );
            }

            startupRecoveryCommitted = true;
            startupManualCandidateIds.clear();
            for (String requestId : startupAdmissionOrder.keySet()) {
                JSONObject state = readState(requestId);
                long sequence = readOptionalQueueSequence(state, requestId);
                if (sequence > 0L) {
                    addPendingJobLocked(requestId, sequence);
                }
            }
            startupAdmissionOrder.clear();

            }
        } finally {
            // A partial held-batch or resetting transition is already
            // durable and must be reflected immediately; startup repair will
            // finish any resetting marker after a crash or I/O failure.
            notifyQueueListener();
        }
    }

    public void applyManualQueueOrder(
        List<String> orderedRequestIds
    ) throws Exception {
        // Keep one manual recovery commit path.  The former queue-only
        // implementation could publish held jobs without numbering startup
        // admissions or opening the unified recovery boundary.
        applyManualRecoveryOrder(orderedRequestIds);
    }

    public void cancelHeldQueuedJobs() throws Exception {
        synchronized (this) {
            requirePreparedLocked();
            requireRecoveryDecisionOpenLocked();
            requireStartupRepairCompleteLocked();
            if (heldQueuedJobs.isEmpty()
                && (startupRecoveryCommitted
                    || startupManualCandidateIds.isEmpty())) {
                return;
            }

            LinkedHashMap<File, JSONObject> originalStates =
                new LinkedHashMap<>();
            LinkedHashMap<File, JSONObject> updatedStates =
                new LinkedHashMap<>();
            long now = System.currentTimeMillis();

            for (String requestId : heldQueuedJobs.keySet()) {
                File jobDirectory = jobStore.jobDirectory(requestId);
                JSONObject state = requireHeldQueuedState(
                    jobDirectory,
                    requestId
                );
                originalStates.put(
                    jobDirectory,
                    new JSONObject(state.toString())
                );
                state.put("status", STATUS_CANCELED);
                state.put("updated_at", now);
                updatedStates.put(jobDirectory, state);
            }

            writeStateBatch(originalStates, updatedStates);
            heldQueuedJobs.clear();
            if (!startupRecoveryCommitted) {
                publishStartupAdmissionSequencesLocked(false);
                startupRecoveryCommitted = true;
                startupManualCandidateIds.clear();
                for (String requestId : startupAdmissionOrder.keySet()) {
                    JSONObject state = readState(requestId);
                    long sequence = readOptionalQueueSequence(state, requestId);
                    if (sequence > 0L) {
                        addPendingJobLocked(requestId, sequence);
                    }
                }
                startupAdmissionOrder.clear();
            }
        }

        notifyQueueListener();
    }

    public ClaimedJob claimNextQueuedJob() throws Exception {
        ClaimedJob claimedJob = SceneContextStore.withRootAccess(() -> {
            synchronized (this) {
                requirePreparedLocked();
                if (!startupRecoveryCommitted) {
                    return null;
                }
                return claimNextQueuedJobLocked();
            }
        });
        notifyQueueListener();
        return claimedJob;
    }

    /** Reads the full persisted state file for one Translation Job. */
    public synchronized JSONObject readState(String requestId)
        throws Exception {
        validateRequestId(requestId);
        File jobDirectory = requireJobDirectoryLocked(requestId);
        return readState(jobDirectory);
    }

    public synchronized boolean isErrorNotified(String requestId)
        throws Exception {
        JSONObject state = readState(requestId);
        return state.optBoolean("notified", false);
    }

    public synchronized void markErrorNotified(String requestId)
        throws Exception {
        validateRequestId(requestId);
        File jobDirectory = requireJobDirectoryLocked(requestId);
        JSONObject state = readState(jobDirectory);
        state.put("notified", true);
        state.put("updated_at", System.currentTimeMillis());
        writeState(jobDirectory, state);
    }

    public synchronized JSONObject readProgress(String requestId)
        throws Exception {
        validateRequestId(requestId);
        File jobDirectory = requireJobDirectoryLocked(requestId);
        File progressFile = new File(jobDirectory, PROGRESS_FILE_NAME);
        if (!IoUtils.atomicFileExists(progressFile)) {
            return null;
        }
        byte[] bytes;
        AtomicFile atomicFile = new AtomicFile(progressFile);
        try (InputStream input = atomicFile.openRead()) {
            bytes = IoUtils.readAllBytesLimited(input, MAX_PROGRESS_BYTES);
        }
        return JobValidator.parseJsonObject(
            bytes,
            MAX_PROGRESS_BYTES,
            "progress"
        );
    }

    public synchronized void writeProgress(
        String requestId,
        JSONObject progress
    ) throws Exception {
        validateRequestId(requestId);
        if (progress == null) {
            throw new IllegalArgumentException("progress cannot be null");
        }
        File jobDirectory = requireJobDirectoryLocked(requestId);
        JSONObject state = readState(jobDirectory);
        if (state == null) {
            throw new IllegalStateException(
                "translation state is missing requestId=" + requestId
            );
        }
        String status = state.optString("status", "");
        if (!STATUS_RUNNING.equals(status) && !STATUS_QUEUED.equals(status)) {
            throw new IllegalStateException(
                "cannot write progress for terminal job requestId="
                    + requestId
                    + " status="
                    + status
            );
        }
        byte[] bytes = (progress.toString(2) + "\n").getBytes(
            StandardCharsets.UTF_8
        );
        if (bytes.length > MAX_PROGRESS_BYTES) {
            throw new IllegalArgumentException(
                "translation progress exceeds byte limit requestId="
                    + requestId
            );
        }
        IoUtils.writeAtomically(
            new File(jobDirectory, PROGRESS_FILE_NAME),
            bytes
        );
    }

    public void completeRunningJob(
        String requestId,
        byte[] resultJson
    ) throws Exception {
        synchronized (SceneContextStore.ROOT_ACCESS_LOCK) {
            if (resultJson == null || resultJson.length == 0) {
                throw new IllegalArgumentException(
                    "translation result cannot be empty"
                );
            }
            JSONObject result = JobValidator.parseJsonObject(
                resultJson,
                MAX_RESULT_BYTES,
                "result"
            );

            synchronized (this) {
                validateRequestId(requestId);
                File jobDirectory = requireJobDirectoryLocked(requestId);
                JSONObject state = requireRunningStateLocked(
                    jobDirectory,
                    requestId
                );

            byte[] requestBytes = readRequest(jobDirectory);
            JSONObject requestJson = JobValidator.parseJsonObject(
                requestBytes,
                MAX_REQUEST_BYTES,
                "request"
            );
            JobValidator.RequestInfo requestInfo = JobValidator.validateRequest(requestJson);
            validateCompletedResult(requestJson, requestInfo, result);

            IoUtils.writeAtomically(
                new File(jobDirectory, RESULT_FILE_NAME),
                resultJson
            );
            state.put("status", STATUS_COMPLETED);
            state.put(
                "delivery_state",
                TerminalOutcome.DeliveryState.PENDING.wireValue()
            );
            state.put("updated_at", System.currentTimeMillis());
            writeState(jobDirectory, state);
            removeJobFromIndexesLocked(requestId);
                manualRerunCandidateIds.remove(requestId);
            }
        }
        notifyQueueListener();
    }

    public void failRunningJob(
        String requestId,
        byte[] errorJson
    ) throws Exception {
        synchronized (SceneContextStore.ROOT_ACCESS_LOCK) {
            if (errorJson == null || errorJson.length == 0) {
                throw new IllegalArgumentException(
                    "translation error cannot be empty"
                );
            }
            JobValidator.parseJsonObject(
                errorJson,
                MAX_RESULT_BYTES,
                "translation error"
            );

            synchronized (this) {
                validateRequestId(requestId);
                File jobDirectory = requireJobDirectoryLocked(requestId);
                JSONObject state = requireRunningStateLocked(
                    jobDirectory,
                    requestId
                );

            IoUtils.writeAtomically(
                new File(jobDirectory, ERROR_FILE_NAME),
                errorJson
            );
            state.put("status", STATUS_FAILED);
            JSONObject error = new JSONObject(
                new String(errorJson, StandardCharsets.UTF_8)
            );
            boolean sceneValidation = "scene_validation".equals(
                error.optString("kind", "")
            ) || "scene_validation".equals(
                error.optJSONObject("error") == null
                    ? ""
                    : error.optJSONObject("error").optString("type", "")
            );
            state.put(
                "delivery_state",
                sceneValidation
                    ? TerminalOutcome.DeliveryState.NOT_REQUIRED.wireValue()
                    : TerminalOutcome.DeliveryState.PENDING.wireValue()
            );
            state.put("updated_at", System.currentTimeMillis());
            writeState(jobDirectory, state);
            removeJobFromIndexesLocked(requestId);
                if (sceneValidation) {
                    manualRerunCandidateIds.remove(requestId);
                } else {
                    manualRerunCandidateIds.add(requestId);
                }
            }
        }
        notifyQueueListener();
    }

    public synchronized byte[] readCompletedResult(String requestId)
        throws Exception {
        validateRequestId(requestId);
        File jobDirectory = requireJobDirectoryLocked(requestId);
        JSONObject state = readState(jobDirectory);
        if (state == null
            || !STATUS_COMPLETED.equals(state.optString("status", ""))) {
            return null;
        }
        File resultFile = new File(jobDirectory, RESULT_FILE_NAME);
        if (!IoUtils.atomicFileExists(resultFile)) {
            return null;
        }
        AtomicFile atomicFile = new AtomicFile(resultFile);
        try (InputStream input = atomicFile.openRead()) {
            return IoUtils.readAllBytesLimited(input, MAX_RESULT_BYTES);
        }
    }

    public synchronized byte[] readFailedError(String requestId)
        throws Exception {
        validateRequestId(requestId);
        File jobDirectory = requireJobDirectoryLocked(requestId);
        JSONObject state = readState(jobDirectory);
        if (state == null
            || !STATUS_FAILED.equals(state.optString("status", ""))) {
            return null;
        }

        File errorFile = new File(jobDirectory, ERROR_FILE_NAME);
        if (!IoUtils.atomicFileExists(errorFile)) {
            return null;
        }
        AtomicFile atomicFile = new AtomicFile(errorFile);
        try (InputStream input = atomicFile.openRead()) {
            return IoUtils.readAllBytesLimited(input, MAX_RESULT_BYTES);
        }
    }

    /**
     * Returns a durable terminal snapshot, or null for a non-terminal job.
     * Legacy terminal state files are normalized from their retained payload
     * so startup can safely migrate them before replay.
     */
    public synchronized TerminalJob readTerminalJob(String requestId)
        throws Exception {
        validateRequestId(requestId);
        File jobDirectory = requireJobDirectoryLocked(requestId);
        JSONObject state = readState(jobDirectory);
        if (state == null) {
            return null;
        }
        String status = state.optString("status", "");
        if (!isTerminalStatus(status)) {
            return null;
        }
        TerminalOutcome.DeliveryState delivery =
            normalizeDeliveryStateLocked(jobDirectory, state);
        JSONObject error = readErrorObjectIfPresent(jobDirectory);
        JSONObject nestedError = error == null
            ? null
            : error.optJSONObject("error");
        String errorType = error == null
            ? ""
            : error.optString(
                "kind",
                nestedError == null
                    ? ""
                    : nestedError.optString("type", "")
            );
        String errorMessage = nestedError == null
            ? error == null ? "" : error.optString("message", "")
            : nestedError.optString("message", "");
        if (STATUS_FAILED.equals(status)) {
            if (isSceneValidationErrorLocked(jobDirectory)) {
                manualRerunCandidateIds.remove(requestId);
            } else {
                manualRerunCandidateIds.add(requestId);
            }
        } else {
            manualRerunCandidateIds.remove(requestId);
        }
        return new TerminalJob(
            requestId,
            state.optString("scene", ""),
            state.optString("target_lang", ""),
            status,
            delivery,
            state.optLong("updated_at", 0L),
            errorType,
            errorMessage
        );
    }

    /**
     * Reads one pending terminal outcome without scanning the job root.
     * This is used by delivery retries after the initial generation scan;
     * startup-repair candidates remain hidden until the stable boundary.
     */
    public synchronized TerminalJob readPendingTerminalJob(String requestId)
        throws Exception {
        validateRequestId(requestId);
        if (!isTerminalDeliveryStableLocked(requestId)) {
            return null;
        }
        TerminalJob job = readTerminalJob(requestId);
        return job != null && job.requiresDelivery() ? job : null;
    }

    /** Lists all durable terminal jobs requiring game acknowledgement. */
    public synchronized List<TerminalJob> listPendingTerminalJobs()
        throws Exception {
        List<File> directories = jobStore.listValidJobDirectories();
        List<TerminalJob> result = new ArrayList<>();
        for (File directory : directories) {
            // Service startup first performs a lightweight scan and then
            // validates terminal payloads in the background.  Do not expose a
            // terminal directory to callback replay until that candidate has
            // left every startup-repair/Scene-Wait staging set.  This keeps a
            // malformed error.json from being treated as a trusted failure
            // merely because state.json still says failed + pending.
            if (!isTerminalDeliveryStableLocked(directory.getName())) {
                continue;
            }
            try {
                TerminalJob job = readTerminalJob(directory.getName());
                if (job != null && job.requiresDelivery()) {
                    result.add(job);
                }
            } catch (IllegalArgumentException ignored) {
                // Invalid directory names are not Translation Jobs.
            }
        }
        result.sort((left, right) -> {
            int updated = Long.compare(
                left.getUpdatedAt(),
                right.getUpdatedAt()
            );
            return updated != 0
                ? updated
                : left.getRequestId().compareTo(right.getRequestId());
        });
        return result;
    }

    /**
     * Enumerates retained failed jobs directly from the durable job
     * directories.  This is intentionally independent of the startup repair
     * snapshot so the settings entry can be used at any time.  Scene
     * validation failures are retained in the result for diagnostics, but
     * callers must use {@link TerminalJob#isSceneValidationFailure()} to keep
     * them out of the manual rerun candidate list.
     */
    public synchronized List<TerminalJob> listRetainedFailedJobs()
        throws Exception {
        List<File> directories = jobStore.listValidJobDirectories();
        List<TerminalJob> result = new ArrayList<>();
        for (File directory : directories) {
            try {
                TerminalJob job = readTerminalJob(directory.getName());
                if (job != null && STATUS_FAILED.equals(job.getStatus())) {
                    result.add(job);
                }
            } catch (IllegalArgumentException ignored) {
                // Invalid directory names are not Translation Jobs.
            }
        }
        result.sort((left, right) -> {
            int updated = Long.compare(
                right.getUpdatedAt(),
                left.getUpdatedAt()
            );
            return updated != 0
                ? updated
                : left.getRequestId().compareTo(right.getRequestId());
        });
        return result;
    }

    /**
     * Returns the failed jobs that can be explicitly selected for a manual
     * rerun.  Scene-validation failures remain visible through
     * {@link #listRetainedFailedJobs()} but are deliberately excluded.
     */
    public synchronized List<TerminalJob> listManualRerunCandidates()
        throws Exception {
        List<TerminalJob> result = new ArrayList<>();
        for (TerminalJob job : listRetainedFailedJobs()) {
            if (!job.isSceneValidationFailure()) {
                result.add(job);
            }
        }
        return result;
    }

    /**
     * Lists active (queued/running) Translation Jobs with their route
     * references. This is used by Context/Group Review to detect in-flight
     * requests and to rewrite only queued mappings.
     */
    public synchronized List<ReviewJob> listReviewJobs() throws Exception {
        List<ReviewJob> result = new ArrayList<>();
        for (File directory : jobStore.listValidJobDirectories()) {
            try {
                JSONObject state = readState(directory);
                if (state == null) {
                    continue;
                }
                String status = state.optString("status", "");
                if (!STATUS_QUEUED.equals(status)
                    && !STATUS_RUNNING.equals(status)) {
                    continue;
                }
                String contextId = null;
                String groupId = null;
                Object mapping = state.opt(HistoryMapping.FIELD);
                if (HistoryMapping.resolutionOfValue(mapping)
                    == HistoryMapping.Resolution.VALID) {
                    JSONObject mappingObject = (JSONObject) mapping;
                    contextId = mappingObject.optString(
                        HistoryMapping.CONTEXT_ID,
                        null
                    );
                    if (!mappingObject.isNull(HistoryMapping.GROUP_ID)) {
                        groupId = mappingObject.optString(
                            HistoryMapping.GROUP_ID,
                            null
                        );
                    }
                }
                result.add(new ReviewJob(
                    directory.getName(),
                    state.optString("scene", ""),
                    status,
                    contextId,
                    groupId
                ));
            } catch (IllegalArgumentException ignored) {
                // Invalid directory names are not Translation Jobs.
            }
        }
        result.sort((left, right) -> left.getRequestId().compareTo(
            right.getRequestId()
        ));
        return result;
    }

    /**
     * Returns whether durable work for {@code scene} is already queued,
     * running, or waiting for terminal delivery as completed work.
     *
     * <p>The check participates in the shared Scene/Job root lock so callers
     * can use it immediately before admission without a check-then-create
     * race. Failed, canceled, and damaged records deliberately do not occupy
     * the per-Scene slot.</p>
     */
    public boolean hasActiveOrCompletedJobForScene(String scene)
        throws Exception {
        if (scene == null || scene.trim().isEmpty()) {
            throw new IllegalArgumentException("scene is required");
        }
        return SceneContextStore.withRootAccess(() -> {
            synchronized (this) {
                for (File directory : jobStore.listValidJobDirectories()) {
                    JSONObject state = readState(directory);
                    if (state == null
                        || !scene.equals(state.optString("scene", ""))) {
                        continue;
                    }
                    String status = state.optString("status", "");
                    if (STATUS_QUEUED.equals(status)
                        || STATUS_RUNNING.equals(status)
                        || STATUS_COMPLETED.equals(status)) {
                        return true;
                    }
                }
                return false;
            }
        });
    }

    /** Synchronous terminal preflight used immediately before native input. */
    public synchronized boolean preflightTerminal(
        String requestId,
        TerminalOutcome.Kind kind
    ) throws Exception {
        if (kind == null) {
            return false;
        }
        validateRequestId(requestId);
        File directory = requireJobDirectoryLocked(requestId);
        JSONObject state = readState(directory);
        if (state == null
            || !kind.wireValue().equals(state.optString("status", ""))) {
            return false;
        }
        TerminalOutcome.DeliveryState delivery =
            TerminalOutcome.DeliveryState.fromWireValue(
                state.optString("delivery_state", "")
            );
        return delivery == TerminalOutcome.DeliveryState.PENDING;
    }

    /**
     * Synchronous, idempotent acknowledgement.  A matching pending outcome
     * is atomically marked acknowledged; an already acknowledged outcome is
     * a successful duplicate ACK, while different kinds are rejected.
     */
    public synchronized boolean acknowledgeTerminal(
        String requestId,
        TerminalOutcome.Kind kind
    ) throws Exception {
        if (kind == null) {
            return false;
        }
        validateRequestId(requestId);
        File directory = requireJobDirectoryLocked(requestId);
        JSONObject state = readState(directory);
        if (state == null || !kind.wireValue().equals(
            state.optString("status", "")
        )) {
            return false;
        }
        TerminalOutcome.DeliveryState current =
            normalizeDeliveryStateLocked(directory, state);
        if (current == TerminalOutcome.DeliveryState.ACKNOWLEDGED) {
            return true;
        }
        if (current != TerminalOutcome.DeliveryState.PENDING) {
            return false;
        }
        state.put(
            "delivery_state",
            TerminalOutcome.DeliveryState.ACKNOWLEDGED.wireValue()
        );
        state.put("updated_at", System.currentTimeMillis());
        writeState(directory, state);
        return true;
    }

    /**
     * Starts a crash-safe local rerun from retained request.json.  The
     * incoming duplicate payload is deliberately ignored by this method.
     */
    public boolean rerunRetainedJob(String requestId) throws Exception {
        synchronized (this) {
            validateRequestId(requestId);
            File directory = requireJobDirectoryLocked(requestId);
            JSONObject state = readState(directory);
            if (state == null) {
                throw new IllegalStateException("translation state is missing");
            }
            rerunRetainedJobLocked(directory, requestId, state);
        }
        notifyQueueListener();
        return true;
    }

    /**
     * Explicit local user action: abandon a pending failed delivery and rerun
     * the retained request in one store lock.  Binder duplicate admission may
     * not use this exception path.
     */
    public boolean rerunManualCandidate(String requestId) throws Exception {
        try {
            synchronized (this) {
                validateRequestId(requestId);
                if (!preparedForServiceStart) {
                    refreshQueueSequenceFromDiskLocked();
                }
                File directory = requireJobDirectoryLocked(requestId);
                JSONObject state = readState(directory);
                if (state == null
                    || !STATUS_FAILED.equals(state.optString("status", ""))
                    || isSceneValidationErrorLocked(directory)) {
                    throw new AdmissionException(
                        "scene_validation_requires_repair",
                        "Manual rerun candidate is not eligible: " + requestId
                    );
                }
                rerunManualCandidateLocked(
                    directory,
                    requestId,
                    state,
                    null,
                    preparedForServiceStart
                );
            }
        } finally {
            notifyQueueListener();
        }
        return true;
    }

    private void rerunRetainedJobLocked(
        File directory,
        String requestId,
        JSONObject state
    ) throws Exception {
        rerunRetainedJobLocked(
            directory,
            requestId,
            state,
            false,
            null,
            true
        );
    }

    private void rerunRetainedJobLocked(
        File directory,
        String requestId,
        JSONObject state,
        boolean allowPendingFailure
    ) throws Exception {
        rerunRetainedJobLocked(
            directory,
            requestId,
            state,
            allowPendingFailure,
            null,
            true
        );
    }

    private void rerunManualCandidateLocked(
        File directory,
        String requestId,
        JSONObject state,
        Long requestedSequence
    ) throws Exception {
        rerunManualCandidateLocked(
            directory,
            requestId,
            state,
            requestedSequence,
            true
        );
    }

    private void rerunManualCandidateLocked(
        File directory,
        String requestId,
        JSONObject state,
        Long requestedSequence,
        boolean enqueueNow
    ) throws Exception {
        String status = state.optString("status", "");
        if (!STATUS_FAILED.equals(status)
            || isSceneValidationErrorLocked(directory)) {
            throw new AdmissionException(
                "scene_validation_requires_repair",
                "Manual rerun candidate is not eligible: " + requestId
            );
        }
        rerunRetainedJobLocked(
            directory,
            requestId,
            state,
            true,
            requestedSequence,
            enqueueNow
        );
    }

    private void rerunRetainedJobLocked(
        File directory,
        String requestId,
        JSONObject state,
        boolean allowPendingFailure,
        Long requestedSequence,
        boolean enqueueNow
    ) throws Exception {
        String status = state.optString("status", "");
        TerminalOutcome.DeliveryState delivery = isTerminalStatus(status)
            ? normalizeDeliveryStateLocked(directory, state)
            : null;
        if (STATUS_QUEUED.equals(status)
            || STATUS_RUNNING.equals(status)
            || STATUS_RESETTING.equals(status)
            || (!allowPendingFailure && isTerminalStatus(status)
                && delivery == TerminalOutcome.DeliveryState.PENDING)) {
            throw new AdmissionException(
                "execution_not_settled",
                "Translation execution is not settled requestId=" + requestId
            );
        }
        if (!STATUS_COMPLETED.equals(status)
            && !STATUS_FAILED.equals(status)
            && !STATUS_CANCELED.equals(status)) {
            throw new AdmissionException(
                "duplicate_rejected",
                "Translation request cannot be rerun requestId=" + requestId
            );
        }
        if (STATUS_FAILED.equals(status)
            && isSceneValidationErrorLocked(directory)) {
            throw new AdmissionException(
                "scene_validation_requires_repair",
                "Scene validation must be repaired before rerun requestId="
                    + requestId
            );
        }

        // Publish resetting before destructive cleanup.  A crash leaves an
        // unclaimable marker that startup recovery can finish later.
        state.put("status", STATUS_RESETTING);
        state.put(
            "delivery_state",
            TerminalOutcome.DeliveryState.NOT_REQUIRED.wireValue()
        );
        state.remove("notified");
        state.put("updated_at", System.currentTimeMillis());
        writeState(directory, state);
        manualRerunCandidateIds.remove(requestId);

        deletePayloadFiles(directory);
        byte[] requestBytes = readRequest(directory);
        JSONObject request = JobValidator.parseJsonObject(
            requestBytes,
            MAX_REQUEST_BYTES,
            "request"
        );
        JobValidator.RequestInfo info = JobValidator.validateRequest(request);
        finishResettingLocked(
            directory,
            requestId,
            state,
            info,
            requestBytes,
            enqueueNow,
            requestedSequence
        );
    }

    enum ProcessResult {
        VALID,
        REPAIRED,
        COMPLETED,
        FAILED,
        DAMAGED,
        TERMINAL,
        REMOVED,
        SCENE_MISSING
    }

    private enum ValidationMode {
        RUNTIME_SELF_ONLY,
        STARTUP_RECOVERY,
        SCENE_WAIT_RECHECK
    }

    private void validateCompletedResult(
        JSONObject request,
        JobValidator.RequestInfo requestInfo,
        JSONObject result
    ) throws Exception {
        resultSchemaValidator.validate(result);

        TranslationResultValidator semanticValidator =
            new TranslationResultValidator(request, requestInfo);

        semanticValidator.validateFinalResult(result);
    }

    /**
     * Reconciles one persisted job. The caller must hold this store's monitor
     * because repair may allocate a queue sequence and update memory indexes.
     */
    private ProcessResult processIncompleteJob(
        File jobDirectory,
        ValidationMode validationMode,
        boolean indexRepairedJob
    ) throws Exception {
        if (validationMode == null) {
            throw new IllegalArgumentException(
                "validationMode cannot be null"
            );
        }
        JSONObject state;
        Exception stateReadFailure;
        try {
            state = readState(jobDirectory);
            stateReadFailure = null;
        } catch (Exception e) {
            if (hasTransientIOExceptionCause(e)) {
                Log.w(
                    TAG,
                    "Kept translation job after state I/O failure: "
                        + jobDirectory.getAbsolutePath(),
                    e
                );
                throw e;
            }
            state = null;
            stateReadFailure = e;
        }

        final String existingStatus = state == null
            ? ""
            : state.optString("status", "");
        syncHistoryMembershipPendingLocked(jobDirectory.getName(), state);
        final boolean historyMembershipPending = state != null
            && STATUS_QUEUED.equals(existingStatus)
            && state.optBoolean(HISTORY_MEMBERSHIP_PENDING_FIELD, false);
        if (STATUS_DAMAGED.equals(existingStatus)) {
            removeJobFromIndexesLocked(jobDirectory.getName());
            return ProcessResult.DAMAGED;
        }

        final byte[] requestBytes;
        JobValidator.RequestInfo requestInfo;
        final String requestSha256;
        final JSONObject request;

        try {
            requestBytes = readRequest(jobDirectory);
        } catch (Exception e) {
            if (hasTransientIOExceptionCause(e)) {
                Log.w(
                    TAG,
                    "Kept translation job after request I/O failure: "
                        + jobDirectory.getAbsolutePath(),
                    e
                );
                throw e;
            }

            removeJobFromIndexesLocked(jobDirectory.getName());
            deleteIncompleteJobDirectory(jobDirectory);
            Log.w(
                TAG,
                "Removed job whose request file is missing or exceeds "
                    + "the size limit: "
                    + jobDirectory.getAbsolutePath(),
                e
            );
            return ProcessResult.REMOVED;
        }

        try {
            request = JobValidator.parseJsonObject(
                requestBytes,
                MAX_REQUEST_BYTES,
                "request"
            );
            requestSha256 = JobValidator.sha256Hex(requestBytes);
        } catch (JobValidator.ValidationException e) {
            removeJobFromIndexesLocked(jobDirectory.getName());
            deleteIncompleteJobDirectory(jobDirectory);
            Log.w(
                TAG,
                "Removed job with an invalid request: "
                    + jobDirectory.getAbsolutePath(),
                e
            );
            return ProcessResult.REMOVED;
        }

        JobValidator.RequestInfo basicRequestInfo;
        try {
            basicRequestInfo = JobValidator.validateRequest(request);
        } catch (JobValidator.ValidationException e) {
            removeJobFromIndexesLocked(jobDirectory.getName());
            deleteIncompleteJobDirectory(jobDirectory);
            Log.w(
                TAG,
                "Removed job with an invalid request: "
                    + jobDirectory.getAbsolutePath(),
                e
            );
            return ProcessResult.REMOVED;
        }

        if (state != null
            && isTerminalStatus(existingStatus)
            && hasInvalidDeliveryState(state)) {
            return markDamagedStateLocked(
                jobDirectory,
                basicRequestInfo,
                requestSha256,
                state,
                "terminal state has an invalid delivery_state"
            );
        }

        if (state != null
            && STATUS_RESETTING.equals(state.optString("status", ""))) {
            // A resetting job is never claimable or deliverable.  Startup can
            // safely finish the old payload cleanup and publish a fresh queue
            // entry using the retained request bytes.
            deletePayloadFiles(jobDirectory);
            finishResettingLocked(
                jobDirectory,
                jobDirectory.getName(),
                state,
                basicRequestInfo,
                requestBytes,
                false
            );
        }

        // Reconcile terminal payloads only after resetting has completed its
        // cleanup transaction.  A crash between resetting-state publication
        // and payload deletion must never resurrect the old outcome.
        // A queued job with an outstanding Context membership marker is not
        // yet an execution candidate.  Leave any terminal-looking payloads
        // untouched until the cross-store admission converges; otherwise
        // startup Scene validation could turn the marker into a terminal
        // failure that the compensation path can no longer clear.
        ProcessResult terminalPayloadResult = historyMembershipPending
            ? null
            : reconcileTerminalPayloadLocked(
                jobDirectory,
                state,
                STATUS_RESETTING.equals(existingStatus)
                    ? STATUS_QUEUED
                    : existingStatus,
                request,
                basicRequestInfo,
                requestSha256,
                validationMode == ValidationMode.RUNTIME_SELF_ONLY
                    || isTerminalStatus(existingStatus)
            );
        if (terminalPayloadResult != null) {
            return terminalPayloadResult;
        }

        if (validationMode == ValidationMode.RUNTIME_SELF_ONLY
            || historyMembershipPending) {
            // Runtime admission and claim are intentionally independent from
            // the Scene Mirror.  A request/state/hash-consistent job can be
            // queued and claimed while the game is offline or before its
            // first mirror export.  A pending Context membership uses the
            // same self-only check but remains isolated by its marker.
            requestInfo = basicRequestInfo;
        } else {
            try {
                requestInfo = JobValidator.validateRequestAgainstScene(
                    request,
                    sceneStore
                );
            } catch (JobValidator.SceneMissingException e) {
                if (validationMode == ValidationMode.STARTUP_RECOVERY) {
                    return ProcessResult.SCENE_MISSING;
                }
                // A complete result is authoritative terminal data.  If the
                // Scene Mirror is currently absent, retain it as completed
                // and pending instead of writing an error payload alongside
                // result.json; the next replay can be ACKed after the Scene
                // becomes available again.
                if (hasValidCompletedResult(
                    jobDirectory,
                    request,
                    basicRequestInfo
                )) {
                    return rebuildCompletedStateFromRequest(
                        jobDirectory,
                        basicRequestInfo,
                        requestSha256,
                        state,
                        e
                    );
                }
                return rebuildFailedStateFromRequest(
                    jobDirectory,
                    basicRequestInfo,
                    requestSha256,
                    state,
                    e
                );
            } catch (JobValidator.SceneInvalidException
                | JobValidator.ValidationException e) {
                if (hasValidCompletedResult(
                    jobDirectory,
                    request,
                    basicRequestInfo
                )) {
                    return rebuildCompletedStateFromRequest(
                        jobDirectory,
                        basicRequestInfo,
                        requestSha256,
                        state,
                        e
                    );
                }
                return rebuildFailedStateFromRequest(
                    jobDirectory,
                    basicRequestInfo,
                    requestSha256,
                    state,
                    e
                );
            } catch (JobValidator.SceneAccessException e) {
                Log.w(
                    TAG,
                    "Kept translation job because its synchronized scene is "
                        + "temporarily unavailable: "
                        + jobDirectory.getAbsolutePath(),
                    e
                );
                throw e;
            }

            // Terminal payloads were already reconciled against the basic
            // request above.  No payload means normal interrupted recovery;
            // a malformed payload has already been isolated as damaged.  A
            // valid completion waits until this authoritative Scene check so
            // the delivery state can be `not_required` when the target was
            // already applied.
            if (hasValidCompletedResult(jobDirectory, request, requestInfo)) {
                return rebuildCompletedStateFromRequest(
                    jobDirectory,
                    requestInfo,
                    requestSha256,
                    state,
                    null
                );
            }
        }

        if (stateReadFailure != null) {
            if (requestInfo.isTargetAlreadyTranslated()) {
                return rebuildCompletedStateFromRequest(
                    jobDirectory,
                    requestInfo,
                    requestSha256,
                    null,
                    stateReadFailure
                );
            }
            return rebuildStateFromRequest(
                jobDirectory,
                requestInfo,
                requestSha256,
                null,
                stateReadFailure,
                indexRepairedJob
            );
        }
        if (state == null) {
            if (requestInfo.isTargetAlreadyTranslated()) {
                return rebuildCompletedStateFromRequest(
                    jobDirectory,
                    requestInfo,
                    requestSha256,
                    null,
                    null
                );
            }
            return rebuildStateFromRequest(
                jobDirectory,
                requestInfo,
                requestSha256,
                null,
                null,
                indexRepairedJob
            );
        }

        if (requestInfo.isTargetAlreadyTranslated()) {
            return rebuildCompletedStateFromRequest(
                jobDirectory,
                requestInfo,
                requestSha256,
                state,
                null
            );
        }

        final JobValidator.StateRequestCheck check;
        try {
            check = JobValidator.validateStateAgainstRequest(
                state,
                requestInfo,
                requestSha256
            );
        } catch (JobValidator.ValidationException e) {
            return rebuildStateFromRequest(
                jobDirectory,
                requestInfo,
                requestSha256,
                state,
                e,
                indexRepairedJob
            );
        }

        if (check == JobValidator.StateRequestCheck.HASH_MISMATCH) {
            removeJobFromIndexesLocked(jobDirectory.getName());
            deleteIncompleteJobDirectory(jobDirectory);
            Log.w(
                TAG,
                "Removed job whose request hash no longer matches state: "
                    + jobDirectory.getAbsolutePath()
            );
            return ProcessResult.REMOVED;
        }

        boolean stateChanged = false;
        if (check == JobValidator.StateRequestCheck.HASH_MISSING) {
            state.put(
                JobValidator.REQUEST_SHA256_FIELD,
                requestSha256
            );
            stateChanged = true;
        }

        long queueSequence = readOptionalQueueSequence(
            state,
            jobDirectory.getName()
        );
        if (queueSequence <= 0L && indexRepairedJob) {
            queueSequence = allocateQueueSequenceLocked();
            state.put("queue_sequence", queueSequence);
            stateChanged = true;
        }

        if (stateChanged) {
            state.put("updated_at", System.currentTimeMillis());
            writeState(jobDirectory, state);
        }
        boolean indexChanged = false;
        if (indexRepairedJob) {
            indexChanged = ensureQueuedJobIndexedLocked(
                jobDirectory.getName(),
                state,
                queueSequence
            );
        }

        if (stateChanged || indexChanged) {
            Log.i(
                TAG,
                "Reconciled translation job: "
                    + jobDirectory.getAbsolutePath()
            );
            return ProcessResult.REPAIRED;
        }
        return ProcessResult.VALID;
    }

    public boolean createQueuedJob(
        String requestId,
        InputStream requestInput
    ) throws Exception {
        return createQueuedJob(requestId, requestInput, false, JSONObject.NULL);
    }

    /**
     * Admits a request using one durable duplicate policy.  Overwrite mode is
     * intentionally explicit; the duplicate input is never used to replace
     * the retained request.json of an existing job.
     */
    public boolean createQueuedJob(
        String requestId,
        InputStream requestInput,
        boolean overwrite
    ) throws Exception {
        return createQueuedJob(
            requestId,
            requestInput,
            overwrite,
            JSONObject.NULL
        );
    }

    /**
     * Admits a request and persists its explicit History Mapping before the
     * Binder admission returns success. {@code historyMapping} may be JSON
     * null (explicit no-history) or a mapping object; a malformed value is
     * rejected instead of being silently downgraded.
     */
    public boolean createQueuedJob(
        String requestId,
        InputStream requestInput,
        boolean overwrite,
        Object historyMapping
    ) throws Exception {
        return createQueuedJob(
            requestId,
            requestInput,
            overwrite,
            historyMapping,
            false
        );
    }

    /**
     * Admission variant used when the caller still owes the cross-store
     * Context membership append.  The explicit durable marker keeps the Job
     * out of the dispatch index until that append has succeeded.  A valid
     * mapping alone never implies this state: callers that already committed
     * the Context side must use the ordinary four-argument overload.
     */
    public boolean createQueuedJob(
        String requestId,
        InputStream requestInput,
        boolean overwrite,
        Object historyMapping,
        boolean historyMembershipPending
    ) throws Exception {
        return SceneContextStore.withRootAccess(() ->
            createQueuedJobUnderRoot(
                requestId,
                requestInput,
                overwrite,
                historyMapping,
                historyMembershipPending
            )
        );
    }

    /**
     * Persists a queued Job for an outer Review transaction without publishing
     * it to the in-memory dispatch queue. The caller must invoke
     * {@link #publishReviewTransactionAdmissions(List)} only after its durable
     * journal has committed.
     */
    public boolean createQueuedJobForReviewTransaction(
        String requestId,
        InputStream requestInput,
        Object historyMapping
    ) throws Exception {
        return SceneContextStore.withRootAccess(() ->
            createQueuedJobUnderRoot(
                requestId,
                requestInput,
                false,
                historyMapping,
                false,
                true
            )
        );
    }

    private boolean createQueuedJobUnderRoot(
        String requestId,
        InputStream requestInput,
        boolean overwrite,
        Object historyMapping,
        boolean historyMembershipPending
    ) throws Exception {
        return createQueuedJobUnderRoot(
            requestId,
            requestInput,
            overwrite,
            historyMapping,
            historyMembershipPending,
            false
        );
    }

    private boolean createQueuedJobUnderRoot(
        String requestId,
        InputStream requestInput,
        boolean overwrite,
        Object historyMapping,
        boolean historyMembershipPending,
        boolean deferReviewPublication
    ) throws Exception {
        if (requestInput == null) {
            throw new IllegalArgumentException("Request input cannot be null");
        }
        validateRequestId(requestId);

        byte[] requestBytes = IoUtils.readAllBytesLimited(
            requestInput,
            MAX_REQUEST_BYTES
        );
        if (requestBytes.length == 0) {
            throw new IllegalArgumentException("translation request is empty");
        }

        JSONObject request = JobValidator.parseJsonObject(
            requestBytes,
            MAX_REQUEST_BYTES,
            "request"
        );
        JobValidator.RequestInfo requestInfo =
            JobValidator.validateRequest(request);
        String requestSha256 = JobValidator.sha256Hex(requestBytes);
        if (historyMapping == null) {
            historyMapping = JSONObject.NULL;
        }
        HistoryMapping.requireWritable(historyMapping);
        if (historyMembershipPending
            && HistoryMapping.resolutionOfValue(historyMapping)
                != HistoryMapping.Resolution.VALID) {
            throw new IllegalArgumentException(
                "history membership admission requires a valid mapping"
            );
        }

        boolean created = false;
        boolean repaired = false;
        Exception payloadConflict = null;
        synchronized (this) {
            jobStore.ensureRoot();

            File jobDirectory = jobStore.jobDirectory(requestId);
            boolean createNewJob = true;
            if (jobDirectory.exists()) {
                ProcessResult result = processIncompleteJob(
                    jobDirectory,
                    ValidationMode.RUNTIME_SELF_ONLY,
                    preparedForServiceStart && startupRecoveryCommitted
                );
                if (result != ProcessResult.REMOVED) {
                    createNewJob = false;
                    JSONObject existingState = readState(jobDirectory);
                    String existingStatus = existingState == null
                        ? ""
                        : existingState.optString("status", "");
                    if (isTerminalStatus(existingStatus)) {
                        TerminalOutcome.DeliveryState existingDelivery =
                            normalizeDeliveryStateLocked(
                                jobDirectory,
                                existingState
                            );
                        if (!overwrite) {
                            payloadConflict = new AdmissionException(
                                "duplicate_rejected",
                                "Translation request already exists: "
                                    + requestId
                            );
                        } else if (
                            existingDelivery
                                == TerminalOutcome.DeliveryState.PENDING
                        ) {
                            payloadConflict = new AdmissionException(
                                "execution_not_settled",
                                "Translation execution is not settled: "
                                    + requestId
                            );
                        } else {
                            // Ignore the duplicate bytes and rerun retained
                            // request.json as one resetting transaction.
                            rerunRetainedJobLocked(
                                jobDirectory,
                                requestId,
                                existingState
                            );
                            repaired = true;
                        }
                    }
                    if (result == ProcessResult.VALID
                        || result == ProcessResult.REPAIRED) {
                        repaired = result == ProcessResult.REPAIRED;
                        existingState = readState(jobDirectory);
                        String existingRequestSha256 =
                            existingState.getString(
                                JobValidator.REQUEST_SHA256_FIELD
                            );
                        if (!requestSha256.equals(existingRequestSha256)) {
                            payloadConflict = new IllegalArgumentException(
                                "Request ID already belongs to a different "
                                    + "translation payload: "
                                    + requestId
                            );
                        } else if (existingState.optBoolean(
                            HISTORY_MEMBERSHIP_PENDING_FIELD,
                            false
                        )) {
                            // Same immutable request with an incomplete
                            // Context membership is an idempotent admission;
                            // the Service may retry the append/clear step.
                            syncHistoryMembershipPendingLocked(
                                requestId,
                                existingState
                            );
                        } else if (overwrite) {
                            payloadConflict = new AdmissionException(
                                "execution_not_settled",
                                "Translation execution is not settled: "
                                    + requestId
                            );
                        } else {
                            payloadConflict = new AdmissionException(
                                "duplicate_rejected",
                                "Translation execution is already active: "
                                    + requestId
                            );
                        }
                    }
                }
            }

            if (createNewJob) {
                if (!jobDirectory.mkdir()) {
                    throw new IllegalStateException(
                        "Failed to create job directory: "
                            + jobDirectory.getAbsolutePath()
                    );
                }

                try {
                    File requestFile = new File(
                        jobDirectory,
                        REQUEST_FILE_NAME
                    );
                    IoUtils.writeAtomically(requestFile, requestBytes);

                    long now = System.currentTimeMillis();
                    boolean membershipPending = historyMembershipPending;
                    JSONObject state = new JSONObject()
                        .put("scene", requestInfo.getScene())
                        .put(
                            "target_lang",
                            requestInfo.getTargetLanguage()
                        )
                        .put("version", FORMAT_VERSION)
                        .put("status", STATUS_QUEUED)
                        .put("created_at", now)
                        .put("updated_at", now)
                        .put(
                            JobValidator.REQUEST_SHA256_FIELD,
                            requestSha256
                        )
                        .put("history_mapping", historyMapping);

                    if (membershipPending) {
                        state.put(HISTORY_MEMBERSHIP_PENDING_FIELD, true);
                    }
                    if (deferReviewPublication) {
                        state.put(REVIEW_PUBLICATION_PENDING_FIELD, true);
                    }

                    boolean queueCommitted = preparedForServiceStart
                        && startupRecoveryCommitted;
                    long queueSequence = 0L;
                    if (queueCommitted) {
                        queueSequence = allocateQueueSequenceLocked();
                        state.put("queue_sequence", queueSequence);
                    }

                    writeState(jobDirectory, state);
                    syncHistoryMembershipPendingLocked(requestId, state);
                    syncReviewPublicationPendingLocked(requestId, state);
                    if (queueCommitted
                        && !membershipPending
                        && !deferReviewPublication) {
                        addPendingJobLocked(requestId, queueSequence);
                    } else if (!queueCommitted) {
                        startupAdmissionOrder.put(
                            requestId,
                            (long) startupAdmissionOrder.size()
                        );
                    }
                    created = true;
                } catch (Exception e) {
                    removeJobFromIndexesLocked(requestId);
                    deleteIncompleteJobDirectory(jobDirectory);
                    throw e;
                }
            }
        }

        if ((created || repaired) && !deferReviewPublication) {
            notifyQueueListener();
        }
        if (payloadConflict != null) {
            throw payloadConflict;
        }
        return created;
    }

    /**
     * Publishes Jobs admitted under an outer Review transaction after that
     * transaction's journal has committed. Failures keep the durable hold for
     * startup recovery and are returned to the caller; already-published ids
     * are idempotent successes.
     */
    public List<String> publishReviewTransactionAdmissions(
        List<String> requestIds
    ) throws Exception {
        List<String> failures = new ArrayList<>();
        final boolean[] changed = new boolean[] { false };
        SceneContextStore.withRootAccess(() -> {
            synchronized (this) {
                for (String requestId : requestIds == null
                    ? Collections.<String>emptyList()
                    : requestIds) {
                    try {
                        validateRequestId(requestId);
                        File directory = requireJobDirectoryLocked(requestId);
                        JSONObject state = readState(directory);
                        if (state == null) {
                            throw new IllegalStateException(
                                "translation state is missing requestId="
                                    + requestId
                            );
                        }
                        syncReviewPublicationPendingLocked(requestId, state);
                        if (!state.optBoolean(
                            REVIEW_PUBLICATION_PENDING_FIELD,
                            false
                        )) {
                            continue;
                        }
                        state.remove(REVIEW_PUBLICATION_PENDING_FIELD);
                        state.put("updated_at", System.currentTimeMillis());
                        long sequence = readOptionalQueueSequence(
                            state,
                            requestId
                        );
                        if (STATUS_QUEUED.equals(
                            state.optString("status", "")
                        )) {
                            if (preparedForServiceStart
                                && startupRecoveryCommitted) {
                                if (sequence <= 0L) {
                                    sequence = allocateQueueSequenceLocked();
                                    state.put("queue_sequence", sequence);
                                }
                            } else {
                                if (!startupAdmissionOrder.containsKey(
                                    requestId
                                )) {
                                    startupAdmissionOrder.put(
                                        requestId,
                                        (long) startupAdmissionOrder.size()
                                    );
                                }
                            }
                        }
                        writeState(directory, state);
                        syncReviewPublicationPendingLocked(requestId, state);
                        if (STATUS_QUEUED.equals(
                                state.optString("status", "")
                            )
                            && preparedForServiceStart
                            && startupRecoveryCommitted) {
                            addPendingJobLocked(requestId, sequence);
                        }
                        changed[0] = true;
                    } catch (Exception failure) {
                        failures.add(requestId);
                    }
                }
            }
            return null;
        });
        if (changed[0]) {
            notifyQueueListener();
        }
        return failures;
    }

    /**
     * Drops only process-local indexes for Review admissions whose outer
     * journal was successfully rolled back. The journal owns directory
     * deletion/restoration; this method must never mutate durable files.
     */
    public void discardRolledBackReviewAdmissions(List<String> requestIds)
        throws Exception {
        SceneContextStore.withRootAccess(() -> {
            synchronized (this) {
                for (String requestId : requestIds == null
                    ? Collections.<String>emptyList()
                    : requestIds) {
                    validateRequestId(requestId);
                    if (jobStore.jobDirectory(requestId).exists()) {
                        throw new IllegalStateException(
                            "rolled-back Translation admission still exists: "
                                + requestId
                        );
                    }
                    removeJobFromIndexesLocked(requestId);
                }
            }
            return null;
        });
    }

    /**
     * Structural check used by the Context/Group boundary. The persistence
     * layer never turns a damaged mapping into a terminal Translation outcome;
     * it reports {@code USER_ACTION_REQUIRED} and leaves the job intact.
     */
    public synchronized HistoryMapping.Resolution resolveHistoryMapping(
        String requestId
    ) throws Exception {
        validateRequestId(requestId);
        File jobDirectory = requireJobDirectoryLocked(requestId);
        JSONObject state = readState(jobDirectory);
        return HistoryMapping.resolution(state);
    }

    /** Returns whether the durable Context membership side is incomplete. */
    public synchronized boolean isHistoryMembershipPending(String requestId)
        throws Exception {
        validateRequestId(requestId);
        File directory = jobStore.jobDirectory(requestId);
        if (!directory.isDirectory()) {
            return false;
        }
        JSONObject state = readState(directory);
        boolean pending = state != null
            && state.optBoolean(HISTORY_MEMBERSHIP_PENDING_FIELD, false);
        syncHistoryMembershipPendingLocked(requestId, state);
        return pending;
    }

    /** Returns a defensive copy of the mapping frozen in one Job state. */
    public synchronized Object readHistoryMapping(String requestId)
        throws Exception {
        validateRequestId(requestId);
        JSONObject state = readState(requestId);
        if (state == null || !state.has(HistoryMapping.FIELD)) {
            return JSONObject.NULL;
        }
        Object value = state.opt(HistoryMapping.FIELD);
        if (value instanceof JSONObject) {
            return new JSONObject(value.toString());
        }
        return value == null ? JSONObject.NULL : value;
    }

    /** Lists durable Context membership compensations in stable directory order. */
    public synchronized List<String> listHistoryMembershipPendingRequestIds()
        throws Exception {
        List<String> result = new ArrayList<>();
        for (File directory : jobStore.listValidJobDirectories()) {
            JSONObject state = readState(directory);
            if (state != null
                && state.optBoolean(HISTORY_MEMBERSHIP_PENDING_FIELD, false)) {
                result.add(directory.getName());
                historyMembershipPendingIds.add(directory.getName());
            }
        }
        return result;
    }

    /**
     * Clears a successful Context membership append and publishes the Job to
     * the unified dispatch queue when its recovery boundary is open.
     */
    public void completeHistoryMembershipAdmission(
        String requestId,
        Object historyMapping
    ) throws Exception {
        SceneContextStore.withRootAccess(() -> {
            synchronized (this) {
                completeHistoryMembershipAdmissionLocked(
                    requestId,
                    historyMapping
                );
            }
            return null;
        });
        notifyQueueListener();
    }

    private void completeHistoryMembershipAdmissionLocked(
        String requestId,
        Object historyMapping
    ) throws Exception {
        validateRequestId(requestId);
        if (historyMapping == null) {
            historyMapping = JSONObject.NULL;
        }
        HistoryMapping.requireWritable(historyMapping);
        File directory = jobStore.jobDirectory(requestId);
        JSONObject state = readState(directory);
        if (state == null) {
            throw new IllegalStateException(
                "translation state is missing requestId=" + requestId
            );
        }
        if (!state.optBoolean(HISTORY_MEMBERSHIP_PENDING_FIELD, false)) {
            // Idempotent retry after another Binder/backfill already cleared
            // the durable marker.  Never rewrite a route that has since been
            // structurally edited.
            return;
        }
        String status = state.optString("status", "");
        Object persistedMapping = state.opt(HistoryMapping.FIELD);
        if (HistoryMapping.resolutionOfValue(persistedMapping)
            == HistoryMapping.Resolution.VALID) {
            if (historyMapping != null
                && !JSONObject.NULL.equals(historyMapping)
                && !sameHistoryMapping(persistedMapping, historyMapping)) {
                throw new IllegalArgumentException(
                    "history mapping changed during compensation requestId="
                        + requestId
                );
            }
            historyMapping = persistedMapping;
        } else if (HistoryMapping.resolutionOfValue(historyMapping)
            != HistoryMapping.Resolution.VALID) {
            throw new IllegalStateException(
                "marked translation job has no valid frozen history mapping "
                    + "requestId=" + requestId
            );
        }
        // The Context append may race with a user cancellation or a legacy
        // worker that already claimed the request.  The marker itself is the
        // durable compensation record; clear it for every stable status, but
        // only queue a still-queued job after the recovery boundary is open.
        HistoryMapping.put(state, historyMapping);
        state.remove(HISTORY_MEMBERSHIP_PENDING_FIELD);
        state.put("updated_at", System.currentTimeMillis());
        writeState(directory, state);
        historyMembershipPendingIds.remove(requestId);

        long sequence = readOptionalQueueSequence(state, requestId);
        if (STATUS_QUEUED.equals(status)
            && preparedForServiceStart
            && startupRecoveryCommitted) {
            if (sequence <= 0L) {
                sequence = allocateQueueSequenceLocked();
                state.put("queue_sequence", sequence);
                state.put("updated_at", System.currentTimeMillis());
                writeState(directory, state);
            } else {
                observeQueueSequenceLocked(sequence);
            }
            addPendingJobLocked(requestId, sequence);
        }
    }

    private static boolean sameHistoryMapping(Object left, Object right) {
        if (HistoryMapping.resolutionOfValue(left)
                != HistoryMapping.Resolution.VALID
            || HistoryMapping.resolutionOfValue(right)
                != HistoryMapping.Resolution.VALID) {
            return false;
        }
        JSONObject leftObject = (JSONObject) left;
        JSONObject rightObject = (JSONObject) right;
        return leftObject.optString(HistoryMapping.CONTEXT_ID, "")
                .equals(rightObject.optString(HistoryMapping.CONTEXT_ID, ""))
            && leftObject.optString(HistoryMapping.GROUP_ID, "")
                .equals(rightObject.optString(HistoryMapping.GROUP_ID, ""))
            && leftObject.isNull(HistoryMapping.GROUP_ID)
                == rightObject.isNull(HistoryMapping.GROUP_ID);
    }

    /**
     * Applies a Context Review structure edit to a Translation Job that has
     * not yet been sent. Only queued jobs are writable; a claimed/running job
     * keeps the mapping frozen with the attempt that already read it.
     * {@code request.json} is never touched by this operation.
     */
    public void rewriteHistoryMapping(
        String requestId,
        Object historyMapping
    ) throws Exception {
        SceneContextStore.withRootAccess(() -> {
            synchronized (this) {
                rewriteHistoryMappingLocked(requestId, historyMapping);
            }
            return null;
        });
    }

    private void rewriteHistoryMappingLocked(
        String requestId,
        Object historyMapping
    ) throws Exception {
        validateRequestId(requestId);
        File jobDirectory = requireJobDirectoryLocked(requestId);
        JSONObject state = readState(jobDirectory);
        if (state == null) {
            throw new IllegalStateException(
                "translation state is missing requestId=" + requestId
            );
        }
        if (state.optBoolean(HISTORY_MEMBERSHIP_PENDING_FIELD, false)) {
            throw new AdmissionException(
                "history_membership_pending",
                "Translation mapping is frozen until Context membership "
                    + "compensation completes: "
                    + requestId
            );
        }
        TranslationJobHistoryMapping.rewrite(state, historyMapping);
        state.put("updated_at", System.currentTimeMillis());
        writeState(jobDirectory, state);
    }

    private boolean requeueQueuedJobAtTailLocked(
        File jobDirectory,
        String requestId,
        JSONObject state
    ) throws Exception {
        if (!STATUS_QUEUED.equals(state.optString("status", ""))) {
            return false;
        }

        long queueSequence = allocateQueueSequenceLocked();
        state.put("queue_sequence", queueSequence);
        state.put("updated_at", System.currentTimeMillis());
        state.remove("started_at");
        writeState(jobDirectory, state);

        removeJobFromIndexesLocked(requestId);
        syncHistoryMembershipPendingLocked(requestId, state);
        if (!historyMembershipPendingIds.contains(requestId)) {
            addPendingJobLocked(requestId, queueSequence);
        }
        Log.i(
            TAG,
            "Requeued duplicate translation request at queue tail "
                + "requestId="
                + requestId
                + " queueSequence="
                + queueSequence
        );
        return true;
    }

    private ProcessResult rebuildStateFromRequest(
        File jobDirectory,
        JobValidator.RequestInfo requestInfo,
        String requestSha256,
        JSONObject existingState,
        Exception reason,
        boolean indexRepairedJob
    ) throws Exception {
        long now = System.currentTimeMillis();
        JSONObject repairedState = new JSONObject()
            .put("scene", requestInfo.getScene())
            .put("target_lang", requestInfo.getTargetLanguage())
            .put("version", FORMAT_VERSION)
            .put("status", STATUS_QUEUED)
            .put(
                "created_at",
                readTolerantTimestamp(existingState, "created_at", now)
            )
            .put("updated_at", now)
            .put(
                JobValidator.REQUEST_SHA256_FIELD,
                requestSha256
            );

        copyTolerantTimestamp(
            existingState,
            repairedState,
            "started_at"
        );
        copyHistoryMapping(existingState, repairedState);

        long queueSequence;
        if (indexRepairedJob) {
            queueSequence = allocateQueueSequenceLocked();
            repairedState.put("queue_sequence", queueSequence);
        } else {
            queueSequence = readTolerantTimestamp(
                existingState,
                "queue_sequence",
                0L
            );
            if (queueSequence > 0L) {
                repairedState.put("queue_sequence", queueSequence);
                observeQueueSequenceLocked(queueSequence);
            }
        }

        writeState(jobDirectory, repairedState);
        syncHistoryMembershipPendingLocked(
            jobDirectory.getName(),
            repairedState
        );
        if (indexRepairedJob) {
            removeJobFromIndexesLocked(jobDirectory.getName());
            syncHistoryMembershipPendingLocked(
                jobDirectory.getName(),
                repairedState
            );
            if (!historyMembershipPendingIds.contains(
                jobDirectory.getName()
            )) {
                addPendingJobLocked(jobDirectory.getName(), queueSequence);
            }
        }

        String message =
            "Rebuilt translation job state from request: "
                + jobDirectory.getAbsolutePath();
        if (reason == null) {
            Log.i(TAG, message);
        } else {
            Log.w(TAG, message, reason);
        }
        return ProcessResult.REPAIRED;
    }

    /** Completes the resetting transaction with a fresh queue position. */
    private void finishResettingLocked(
        File directory,
        String requestId,
        JSONObject resettingState,
        JobValidator.RequestInfo requestInfo,
        byte[] requestBytes,
        boolean enqueueNow
    ) throws Exception {
        finishResettingLocked(
            directory,
            requestId,
            resettingState,
            requestInfo,
            requestBytes,
            enqueueNow,
            null
        );
    }

    /**
     * Completes resetting with an optional caller-supplied queue position.
     * The mixed startup recovery page uses the supplied sequence so a failed
     * candidate and a held queued job share exactly one user-defined order.
     */
    private void finishResettingLocked(
        File directory,
        String requestId,
        JSONObject resettingState,
        JobValidator.RequestInfo requestInfo,
        byte[] requestBytes,
        boolean enqueueNow,
        Long requestedSequence
    ) throws Exception {
        long sequence = requestedSequence == null
            ? allocateQueueSequenceLocked()
            : requestedSequence;
        if (sequence <= 0L || sequence == Long.MAX_VALUE) {
            throw new IllegalStateException(
                "Translation queue sequence is exhausted"
            );
        }
        observeQueueSequenceLocked(sequence);
        long now = System.currentTimeMillis();
        JSONObject queued = new JSONObject()
            .put("scene", requestInfo.getScene())
            .put("target_lang", requestInfo.getTargetLanguage())
            .put("version", FORMAT_VERSION)
            .put("status", STATUS_QUEUED)
            .put("queue_sequence", sequence)
            .put(
                "created_at",
                readTolerantTimestamp(resettingState, "created_at", now)
            )
            .put("updated_at", now)
            .put(
                JobValidator.REQUEST_SHA256_FIELD,
                JobValidator.sha256Hex(requestBytes)
            );
        copyHistoryMapping(resettingState, queued);
        writeState(directory, queued);
        removeJobFromIndexesLocked(requestId);
        syncHistoryMembershipPendingLocked(requestId, queued);
        manualRerunCandidateIds.remove(requestId);
        if (enqueueNow
            && !historyMembershipPendingIds.contains(requestId)) {
            addPendingJobLocked(requestId, sequence);
        }
    }

    /**
     * Reconciles durable terminal payloads without consulting Scene.  The
     * payload itself is the only evidence allowed to promote an interrupted
     * job; malformed, competing, or incomplete payloads become a retained
     * damaged job and are never requeued or replayed automatically.
     */
    private ProcessResult reconcileTerminalPayloadLocked(
        File jobDirectory,
        JSONObject state,
        String status,
        JSONObject request,
        JobValidator.RequestInfo requestInfo,
        String requestSha256,
        boolean allowCompletionRepair
    ) throws Exception {
        boolean hasResult = IoUtils.atomicFileExists(
            new File(jobDirectory, RESULT_FILE_NAME)
        );
        boolean hasError = IoUtils.atomicFileExists(
            new File(jobDirectory, ERROR_FILE_NAME)
        );

        if (hasResult && hasError) {
            return markDamagedStateLocked(
                jobDirectory,
                requestInfo,
                requestSha256,
                state,
                "conflicting terminal payloads"
            );
        }

        boolean resultValid = false;
        boolean errorValid = false;
        if (hasResult) {
            resultValid = hasValidCompletedResult(
                jobDirectory,
                request,
                requestInfo
            );
            if (!resultValid) {
                return markDamagedStateLocked(
                    jobDirectory,
                    requestInfo,
                    requestSha256,
                    state,
                    "invalid completed result payload"
                );
            }
        }
        if (hasError) {
            errorValid = hasValidFailureError(jobDirectory);
            if (!errorValid) {
                return markDamagedStateLocked(
                    jobDirectory,
                    requestInfo,
                    requestSha256,
                    state,
                    "invalid terminal error payload"
                );
            }
        }

        if (isTerminalStatus(status)) {
            if (STATUS_CANCELED.equals(status)) {
                if (hasResult || hasError) {
                    return markDamagedStateLocked(
                        jobDirectory,
                        requestInfo,
                        requestSha256,
                        state,
                        "canceled job contains terminal payload"
                    );
                }
                normalizeDeliveryStateLocked(jobDirectory, state);
                removeJobFromIndexesLocked(jobDirectory.getName());
                return ProcessResult.TERMINAL;
            }
            if (STATUS_COMPLETED.equals(status) && resultValid) {
                normalizeDeliveryStateLocked(jobDirectory, state);
                removeJobFromIndexesLocked(jobDirectory.getName());
                return ProcessResult.TERMINAL;
            }
            if (STATUS_FAILED.equals(status) && errorValid) {
                normalizeDeliveryStateLocked(jobDirectory, state);
                removeJobFromIndexesLocked(jobDirectory.getName());
                if (isSceneValidationErrorLocked(jobDirectory)) {
                    manualRerunCandidateIds.remove(jobDirectory.getName());
                } else {
                    manualRerunCandidateIds.add(jobDirectory.getName());
                }
                return ProcessResult.TERMINAL;
            }

            // A terminal state without its matching payload is not safe to
            // replay.  Keep it visible for explicit damaged-job repair.
            return markDamagedStateLocked(
                jobDirectory,
                requestInfo,
                requestSha256,
                state,
                "terminal state has no matching payload"
            );
        }

        if (resultValid && allowCompletionRepair) {
            return rebuildCompletedStateFromRequest(
                jobDirectory,
                requestInfo,
                requestSha256,
                state,
                null
            );
        }
        if (errorValid) {
            if (isSceneValidationErrorLocked(jobDirectory)) {
                JSONObject persistedError = readErrorObjectIfPresent(
                    jobDirectory
                );
                Exception sceneReason = persistedError != null
                    && "scene_missing".equals(
                        persistedError.optString("reason", "")
                    )
                    ? new JobValidator.SceneMissingException(
                        "persisted scene validation failure"
                    )
                    : new JobValidator.SceneInvalidException(
                        "persisted scene validation failure",
                        null
                    );
                return rebuildFailedStateFromRequest(
                    jobDirectory,
                    requestInfo,
                    requestSha256,
                    state,
                    sceneReason
                );
            }
            return rebuildApiFailureStateFromExistingError(
                jobDirectory,
                requestInfo,
                requestSha256,
                state
            );
        }
        return null;
    }

    private ProcessResult markDamagedStateLocked(
        File jobDirectory,
        JobValidator.RequestInfo requestInfo,
        String requestSha256,
        JSONObject existingState,
        String reason
    ) throws Exception {
        long now = System.currentTimeMillis();
        JSONObject damaged = new JSONObject()
            .put("scene", requestInfo.getScene())
            .put("target_lang", requestInfo.getTargetLanguage())
            .put("version", FORMAT_VERSION)
            .put("status", STATUS_DAMAGED)
            .put(
                "delivery_state",
                TerminalOutcome.DeliveryState.NOT_REQUIRED.wireValue()
            )
            .put(
                "created_at",
                readTolerantTimestamp(existingState, "created_at", now)
            )
            .put("updated_at", now)
            .put(JobValidator.REQUEST_SHA256_FIELD, requestSha256)
            .put("damage_reason", reason == null ? "unknown" : reason);
        copyTolerantTimestamp(existingState, damaged, "started_at");
        copyTolerantPositiveLong(existingState, damaged, "queue_sequence");
        copyHistoryMapping(existingState, damaged);
        writeState(jobDirectory, damaged);
        removeJobFromIndexesLocked(jobDirectory.getName());
        syncHistoryMembershipPendingLocked(
            jobDirectory.getName(),
            damaged
        );
        manualRerunCandidateIds.remove(jobDirectory.getName());
        Log.w(
            TAG,
            "Retained damaged translation job requestId="
                + jobDirectory.getName()
                + " reason="
                + reason
        );
        return ProcessResult.DAMAGED;
    }

    /** Rebuilds an API failure while preserving the authoritative error.json. */
    private ProcessResult rebuildApiFailureStateFromExistingError(
        File jobDirectory,
        JobValidator.RequestInfo requestInfo,
        String requestSha256,
        JSONObject existingState
    ) throws Exception {
        long now = System.currentTimeMillis();
        JSONObject failedState = new JSONObject()
            .put("scene", requestInfo.getScene())
            .put("target_lang", requestInfo.getTargetLanguage())
            .put("version", FORMAT_VERSION)
            .put("status", STATUS_FAILED)
            .put(
                "delivery_state",
                TerminalOutcome.DeliveryState.PENDING.wireValue()
            )
            .put(
                "created_at",
                readTolerantTimestamp(existingState, "created_at", now)
            )
            .put("updated_at", now)
            .put(JobValidator.REQUEST_SHA256_FIELD, requestSha256);
        copyTolerantTimestamp(existingState, failedState, "started_at");
        copyTolerantPositiveLong(existingState, failedState, "queue_sequence");
        copyHistoryMapping(existingState, failedState);
        writeState(jobDirectory, failedState);
        removeJobFromIndexesLocked(jobDirectory.getName());
        syncHistoryMembershipPendingLocked(
            jobDirectory.getName(),
            failedState
        );
        manualRerunCandidateIds.add(jobDirectory.getName());
        Log.i(
            TAG,
            "Marked translation job failed from persisted API error requestId="
                + jobDirectory.getName()
        );
        return ProcessResult.FAILED;
    }

    private ProcessResult rebuildFailedStateFromRequest(
        File jobDirectory,
        JobValidator.RequestInfo requestInfo,
        String requestSha256,
        JSONObject existingState,
        Exception reason
    ) throws Exception {
        long now = System.currentTimeMillis();
        JSONObject failedState = new JSONObject()
            .put("scene", requestInfo.getScene())
            .put("target_lang", requestInfo.getTargetLanguage())
            .put("version", FORMAT_VERSION)
            .put("status", STATUS_FAILED)
            .put(
                "delivery_state",
                TerminalOutcome.DeliveryState.NOT_REQUIRED.wireValue()
            )
            .put(
                "created_at",
                readTolerantTimestamp(existingState, "created_at", now)
            )
            .put("updated_at", now)
            .put(
                JobValidator.REQUEST_SHA256_FIELD,
                requestSha256
            );

        copyTolerantTimestamp(
            existingState,
            failedState,
            "started_at"
        );
        copyTolerantPositiveLong(
            existingState,
            failedState,
            "queue_sequence"
        );
        copyHistoryMapping(existingState, failedState);

        writeSceneValidationError(
            jobDirectory,
            requestInfo.getScene(),
            reason,
            now
        );
        writeState(jobDirectory, failedState);
        removeJobFromIndexesLocked(jobDirectory.getName());
        syncHistoryMembershipPendingLocked(
            jobDirectory.getName(),
            failedState
        );
        manualRerunCandidateIds.remove(jobDirectory.getName());
        Log.w(
            TAG,
            "Marked translation job failed because its synchronized "
                + "scene is permanently unavailable requestId="
                + jobDirectory.getName()
                + " scene="
                + requestInfo.getScene(),
            reason
        );
        return ProcessResult.FAILED;
    }

    private void writeSceneValidationError(
        File jobDirectory,
        String sceneName,
        Exception reason,
        long timestamp
    ) throws Exception {
        String reasonCode = reason instanceof JobValidator.SceneMissingException
            ? "scene_missing"
            : "scene_invalid";
        JSONObject diagnostic = new JSONObject()
            .put("kind", "scene_validation")
            .put("reason", reasonCode)
            .put("scene", sceneName)
            .put("updated_at", timestamp)
            .put(
                "message",
                "The synchronized Scene cannot validate this translation job"
            );
        IoUtils.writeAtomically(
            new File(jobDirectory, ERROR_FILE_NAME),
            (diagnostic.toString(2) + "\n").getBytes(StandardCharsets.UTF_8)
        );
    }

    private ProcessResult rebuildCompletedStateFromRequest(
        File jobDirectory,
        JobValidator.RequestInfo requestInfo,
        String requestSha256,
        JSONObject existingState,
        Exception reason
    ) throws Exception {
        long now = System.currentTimeMillis();
        JSONObject completedState = new JSONObject()
            .put("scene", requestInfo.getScene())
            .put("target_lang", requestInfo.getTargetLanguage())
            .put("version", FORMAT_VERSION)
            .put("status", STATUS_COMPLETED)
            .put(
                "delivery_state",
                requestInfo.isTargetAlreadyTranslated()
                    ? TerminalOutcome.DeliveryState.NOT_REQUIRED.wireValue()
                    : TerminalOutcome.DeliveryState.PENDING.wireValue()
            )
            .put(
                "created_at",
                readTolerantTimestamp(existingState, "created_at", now)
            )
            .put("updated_at", now)
            .put(
                JobValidator.REQUEST_SHA256_FIELD,
                requestSha256
            );

        copyTolerantTimestamp(
            existingState,
            completedState,
            "started_at"
        );
        copyTolerantPositiveLong(
            existingState,
            completedState,
            "queue_sequence"
        );
        copyHistoryMapping(existingState, completedState);

        writeState(jobDirectory, completedState);
        removeJobFromIndexesLocked(jobDirectory.getName());
        syncHistoryMembershipPendingLocked(
            jobDirectory.getName(),
            completedState
        );
        manualRerunCandidateIds.remove(jobDirectory.getName());

        String message =
            "Marked translation job completed from synchronized scene "
                + "requestId="
                + jobDirectory.getName()
                + " targetLanguage="
                + requestInfo.getTargetLanguage();
        if (reason == null) {
            Log.i(TAG, message);
        } else {
            Log.w(TAG, message, reason);
        }
        return ProcessResult.COMPLETED;
    }

    private boolean ensureQueuedJobIndexedLocked(
        String requestId,
        JSONObject state,
        long queueSequence
    ) {
        syncHistoryMembershipPendingLocked(requestId, state);
        if (!STATUS_QUEUED.equals(state.optString("status", ""))
            || pendingRequestIds.contains(requestId)
            || heldQueuedJobs.containsKey(requestId)
            || startupReadyJobs.containsKey(requestId)
            || isStartupRepairCandidateLocked(requestId)
            || historyMembershipPendingIds.contains(requestId)
            || reviewPublicationPendingIds.contains(requestId)) {
            return false;
        }
        addPendingJobLocked(requestId, queueSequence);
        return true;
    }

    private void syncHistoryMembershipPendingLocked(
        String requestId,
        JSONObject state
    ) {
        if (state != null
            && state.optBoolean(HISTORY_MEMBERSHIP_PENDING_FIELD, false)) {
            historyMembershipPendingIds.add(requestId);
        } else {
            historyMembershipPendingIds.remove(requestId);
        }
    }

    private void syncReviewPublicationPendingLocked(
        String requestId,
        JSONObject state
    ) {
        if (state != null
            && state.optBoolean(REVIEW_PUBLICATION_PENDING_FIELD, false)) {
            reviewPublicationPendingIds.add(requestId);
        } else {
            reviewPublicationPendingIds.remove(requestId);
        }
    }

    /**
     * Startup runs after SceneContextStore has resolved the outer Review
     * journal. A surviving publication marker therefore belongs to a
     * committed Job whose process-local publication was interrupted.
     */
    private void releaseRecoveredReviewPublicationHoldLocked(
        File jobDirectory,
        String requestId,
        JSONObject state
    ) throws Exception {
        syncReviewPublicationPendingLocked(requestId, state);
        if (state == null || !state.optBoolean(
            REVIEW_PUBLICATION_PENDING_FIELD,
            false
        )) {
            return;
        }
        state.remove(REVIEW_PUBLICATION_PENDING_FIELD);
        state.put("updated_at", System.currentTimeMillis());
        try {
            writeState(jobDirectory, state);
        } catch (Exception failure) {
            state.put(REVIEW_PUBLICATION_PENDING_FIELD, true);
            syncReviewPublicationPendingLocked(requestId, state);
            throw failure;
        }
        syncReviewPublicationPendingLocked(requestId, state);
    }

    private boolean isStartupRepairCandidateLocked(String requestId) {
        for (File candidate : startupRepairCandidates) {
            if (requestId.equals(candidate.getName())) {
                return true;
            }
        }
        return false;
    }

    private boolean isTerminalDeliveryStableLocked(String requestId) {
        if (!preparedForServiceStart) {
            return false;
        }
        if (isStartupRepairCandidateLocked(requestId)
            || sceneValidationWaits.containsKey(requestId)
            || startupReadyJobs.containsKey(requestId)) {
            return false;
        }
        return true;
    }

    private void removeJobFromIndexesLocked(String requestId) {
        Iterator<QueuedJobRef> iterator = pendingQueue.iterator();
        while (iterator.hasNext()) {
            if (requestId.equals(iterator.next().requestId)) {
                iterator.remove();
            }
        }
        pendingRequestIds.remove(requestId);
        heldQueuedJobs.remove(requestId);
        startupReadyJobs.remove(requestId);
        sceneValidationWaits.remove(requestId);
        manualRerunCandidateIds.remove(requestId);
        historyMembershipPendingIds.remove(requestId);
        reviewPublicationPendingIds.remove(requestId);
        startupManualCandidateIds.remove(requestId);
        startupAdmissionOrder.remove(requestId);
        startupRepairCandidates.removeIf(candidate ->
            requestId.equals(candidate.getName())
        );
    }

    public void setQueueListener(QueueListener listener) {
        if (listener == null) {
            throw new IllegalArgumentException("listener cannot be null");
        }
        synchronized (this) {
            queueListeners.add(listener);
        }
        notifyQueueListener();
    }

    public synchronized void clearQueueListener(QueueListener listener) {
        queueListeners.remove(listener);
    }

    private ClaimedJob claimNextQueuedJobLocked() throws Exception {
        while (true) {
            QueuedJobRef job = pendingQueue.peek();
            if (job == null) {
                return null;
            }

            File jobDirectory = jobStore.jobDirectory(job.requestId);
            ProcessResult processResult = processIncompleteJob(
                jobDirectory,
                ValidationMode.RUNTIME_SELF_ONLY,
                true
            );
            if (processResult == ProcessResult.REMOVED
                || processResult == ProcessResult.REPAIRED
                || processResult == ProcessResult.COMPLETED
                || processResult == ProcessResult.FAILED
                || processResult == ProcessResult.DAMAGED
                || processResult == ProcessResult.TERMINAL) {
                continue;
            }

            JSONObject state = readState(jobDirectory);
            if (state == null
                || !STATUS_QUEUED.equals(
                    state.optString("status", "")
                )) {
                removePendingJobLocked(job);
                continue;
            }

            byte[] requestJson = readRequest(jobDirectory);
            if (requestJson.length == 0) {
                throw new IllegalStateException(
                    "Stored translation request is empty requestId="
                        + job.requestId
                );
            }

            JSONObject request = JobValidator.parseJsonObject(
                requestJson,
                MAX_REQUEST_BYTES,
                "request"
            );
            requireRequestString(request, "scene", job.requestId);
            requireRequestString(
                request,
                "target_lang",
                job.requestId
            );

            long now = System.currentTimeMillis();
            state.put("status", STATUS_RUNNING);
            state.put("started_at", now);
            state.put("updated_at", now);
            writeState(jobDirectory, state);

            // Remove the in-memory entry only after running was persisted.
            removePendingJobLocked(job);
            return new ClaimedJob(job.requestId, requestJson);
        }
    }

    private JSONObject requireHeldQueuedState(
        File jobDirectory,
        String requestId
    ) throws Exception {
        JSONObject state = readState(jobDirectory);
        if (state == null
            || !STATUS_QUEUED.equals(state.optString("status", ""))) {
            throw new IllegalStateException(
                "Held translation job is no longer queued requestId="
                    + requestId
            );
        }
        validateStateVersion(state, requestId);
        return state;
    }

    private boolean hasValidCompletedResult(
        File jobDirectory,
        JSONObject request,
        JobValidator.RequestInfo requestInfo
    ) throws Exception {
        File resultFile = new File(jobDirectory, RESULT_FILE_NAME);
        if (!IoUtils.atomicFileExists(resultFile)) {
            return false;
        }

        final JSONObject result;
        try {
            AtomicFile atomicFile = new AtomicFile(resultFile);
            byte[] bytes;
            try (InputStream input = atomicFile.openRead()) {
                bytes = IoUtils.readAllBytesLimited(
                    input,
                    MAX_RESULT_BYTES
                );
            }
            result = JobValidator.parseJsonObject(
                bytes,
                MAX_RESULT_BYTES,
                "result"
            );
        } catch (IOException e) {
            throw e;
        } catch (Exception e) {
            Log.w(
                TAG,
                "Ignored invalid completed result requestId="
                    + jobDirectory.getName(),
                e
            );
            return false;
        }

        try {
            validateCompletedResult(request, requestInfo, result);
            return true;
        } catch (Exception e) {
            Log.w(
                TAG,
                "Ignored incompatible completed result requestId="
                    + jobDirectory.getName(),
                e
            );
            return false;
        }
    }

    private boolean hasValidFailureError(File jobDirectory) throws Exception {
        JSONObject error;
        try {
            error = readErrorObjectIfPresent(jobDirectory);
        } catch (IOException e) {
            throw e;
        } catch (Exception e) {
            Log.w(
                TAG,
                "Ignored invalid terminal error requestId="
                    + jobDirectory.getName(),
                e
            );
            return false;
        }
        if (error == null) {
            return false;
        }
        if ("scene_validation".equals(error.optString("kind", ""))) {
            return true;
        }
        JSONObject nested = error.optJSONObject("error");
        if (nested == null) {
            return false;
        }
        String type = nested.optString("type", "").trim();
        String message = nested.optString("message", "").trim();
        return !type.isEmpty() && !message.isEmpty();
    }

    private void writeStateBatch(
        LinkedHashMap<File, JSONObject> originalStates,
        LinkedHashMap<File, JSONObject> updatedStates
    ) throws Exception {
        List<File> writtenDirectories = new ArrayList<>();
        try {
            for (Map.Entry<File, JSONObject> entry
                : updatedStates.entrySet()) {
                writeState(entry.getKey(), entry.getValue());
                writtenDirectories.add(entry.getKey());
            }
        } catch (Exception writeFailure) {
            for (int index = writtenDirectories.size() - 1;
                index >= 0;
                index--) {
                File directory = writtenDirectories.get(index);
                JSONObject original = originalStates.get(directory);
                if (original == null) {
                    continue;
                }
                try {
                    writeState(directory, original);
                } catch (Exception rollbackFailure) {
                    writeFailure.addSuppressed(rollbackFailure);
                }
            }
            throw writeFailure;
        }
    }

    private void addPendingJobLocked(
        String requestId,
        long queueSequence
    ) {
        if (historyMembershipPendingIds.contains(requestId)
            || reviewPublicationPendingIds.contains(requestId)) {
            return;
        }
        if (!pendingRequestIds.add(requestId)) {
            return;
        }
        pendingQueue.offer(new QueuedJobRef(
            requestId,
            queueSequence
        ));
    }

    private void removePendingJobLocked(QueuedJobRef job) {
        QueuedJobRef removed = pendingQueue.poll();
        if (removed != job) {
            throw new IllegalStateException(
                "Translation pending queue head changed unexpectedly"
            );
        }
        pendingRequestIds.remove(job.requestId);
    }

    private long allocateQueueSequenceLocked() {
        if (nextQueueSequence <= 0L
            || nextQueueSequence == Long.MAX_VALUE) {
            throw new IllegalStateException(
                "Translation queue sequence is exhausted"
            );
        }
        return nextQueueSequence++;
    }

    /**
     * Initializes the sequence allocator for a local rerun made before the
     * Service has performed its normal startup scan.  The rerun is persisted
     * as queued but not indexed in memory; the subsequent prepare pass will
     * discover it and allocate/retain the durable position normally.
     */
    private void refreshQueueSequenceFromDiskLocked() throws Exception {
        List<File> directories = jobStore.listValidJobDirectories();
        long maximum = 0L;
        for (File directory : directories) {
            try {
                JSONObject state = readState(directory);
                if (state == null || !state.has("queue_sequence")) {
                    continue;
                }
                long sequence = readOptionalQueueSequence(
                    state,
                    directory.getName()
                );
                maximum = Math.max(maximum, sequence);
            } catch (Exception e) {
                // Startup repair will isolate the damaged directory.  It
                // must not prevent a valid local rerun from obtaining a
                // sequence greater than every readable durable entry.
                Log.w(
                    TAG,
                    "Ignored unreadable queue sequence during local rerun "
                        + "requestId="
                        + directory.getName(),
                    e
                );
            }
        }
        if (maximum == Long.MAX_VALUE) {
            throw new IllegalStateException(
                "Translation queue sequence is exhausted"
            );
        }
        nextQueueSequence = Math.max(nextQueueSequence, maximum + 1L);
    }

    private void observeQueueSequenceLocked(long queueSequence) {
        if (queueSequence <= 0L || queueSequence < nextQueueSequence) {
            return;
        }
        if (queueSequence == Long.MAX_VALUE) {
            throw new IllegalStateException(
                "Translation queue sequence is exhausted"
            );
        }
        nextQueueSequence = queueSequence + 1L;
    }

    private void requirePreparedLocked() {
        if (!preparedForServiceStart) {
            throw new IllegalStateException(
                "Translation job store has not been prepared"
            );
        }
    }

    private void requireRecoveryDecisionOpenLocked() {
        if (!recoveryDecisionOpen) {
            throw new IllegalStateException(
                "Translation recovery decision is not open"
            );
        }
    }

    private void requireStartupRepairCompleteLocked() {
        // A stable startup subset may already be published while unrelated
        // transient candidates or Scene Validation Wait entries are retained.
        // Only an unpublished ready batch blocks manual queue operations.
        if (!startupReadyJobs.isEmpty()) {
            throw new IllegalStateException(
                "Startup-ready jobs are still being published"
            );
        }
    }

    private static boolean isTerminalStatus(String status) {
        String normalized = status == null ? "" : status.trim();
        return STATUS_CANCELED.equals(normalized)
            || STATUS_COMPLETED.equals(normalized)
            || STATUS_FAILED.equals(normalized)
            || STATUS_DAMAGED.equals(normalized);
    }

    private TerminalOutcome.DeliveryState normalizeDeliveryStateLocked(
        File jobDirectory,
        JSONObject state
    ) throws Exception {
        String status = state.optString("status", "");
        if (!isTerminalStatus(status)) {
            return null;
        }
        if (state.has("delivery_state") && !state.isNull("delivery_state")) {
            Object rawValue = state.opt("delivery_state");
            if (!(rawValue instanceof String)) {
                throw new JobValidator.ValidationException(
                    "$state.delivery_state must be a string"
                );
            }
            TerminalOutcome.DeliveryState parsed =
                TerminalOutcome.DeliveryState.fromWireValue((String) rawValue);
            if (parsed == null) {
                throw new JobValidator.ValidationException(
                    "$state.delivery_state has unsupported value"
                );
            }
            return parsed;
        }
        TerminalOutcome.DeliveryState parsed;
        if (STATUS_CANCELED.equals(status)) {
            parsed = TerminalOutcome.DeliveryState.NOT_REQUIRED;
        } else if (STATUS_DAMAGED.equals(status)) {
            parsed = TerminalOutcome.DeliveryState.NOT_REQUIRED;
        } else if (STATUS_FAILED.equals(status)
            && isSceneValidationErrorLocked(jobDirectory)) {
            parsed = TerminalOutcome.DeliveryState.NOT_REQUIRED;
        } else {
            // A legacy terminal payload is still a durable outcome.  Treat
            // it as pending so reconnect recovery cannot silently lose it.
            parsed = TerminalOutcome.DeliveryState.PENDING;
        }
        state.put("delivery_state", parsed.wireValue());
        state.put("updated_at", System.currentTimeMillis());
        writeState(jobDirectory, state);
        return parsed;
    }

    private static boolean hasInvalidDeliveryState(JSONObject state) {
        if (state == null
            || !state.has("delivery_state")
            || state.isNull("delivery_state")) {
            return false;
        }
        Object rawValue = state.opt("delivery_state");
        return !(rawValue instanceof String)
            || TerminalOutcome.DeliveryState.fromWireValue(
                (String) rawValue
            ) == null;
    }

    private boolean isSceneValidationErrorLocked(File jobDirectory)
        throws Exception {
        JSONObject error = readErrorObjectIfPresent(jobDirectory);
        if (error == null) {
            return false;
        }
        if ("scene_validation".equals(error.optString("kind", ""))) {
            return true;
        }
        JSONObject nested = error.optJSONObject("error");
        return nested != null
            && "scene_validation".equals(
                nested.optString("type", "")
            );
    }

    private JSONObject readErrorObjectIfPresent(File jobDirectory)
        throws Exception {
        File errorFile = new File(jobDirectory, ERROR_FILE_NAME);
        if (!IoUtils.atomicFileExists(errorFile)) {
            return null;
        }
        try (InputStream input = new AtomicFile(errorFile).openRead()) {
            byte[] bytes = IoUtils.readAllBytesLimited(
                input,
                MAX_RESULT_BYTES
            );
            return JobValidator.parseJsonObject(
                bytes,
                MAX_RESULT_BYTES,
                "error"
            );
        }
    }

    private static void deletePayloadFiles(File jobDirectory) throws IOException {
        File[] payloads = {
            new File(jobDirectory, RESULT_FILE_NAME),
            new File(jobDirectory, ERROR_FILE_NAME),
            new File(jobDirectory, PROGRESS_FILE_NAME)
        };
        for (File payload : payloads) {
            new AtomicFile(payload).delete();
            if (payload.exists()) {
                throw new IOException(
                    "Could not remove resetting payload: "
                        + payload.getAbsolutePath()
                );
            }
        }
    }

    private static boolean hasTransientIOExceptionCause(Throwable error) {
        Throwable current = error;
        while (current != null) {
            if (current instanceof IoUtils.InputLimitExceededException) {
                return false;
            }
            if (current instanceof IOException) {
                return true;
            }
            current = current.getCause();
        }
        return false;
    }

    private void notifyQueueListener() {
        List<QueueListener> listeners;
        boolean hasPending;
        int heldCount;
        boolean repairing;
        synchronized (this) {
            listeners = new ArrayList<>(queueListeners);
            hasPending = !pendingQueue.isEmpty();
            repairing = repairingStartupJobs;
            heldCount = repairing ? 0 : heldQueuedJobs.size();
        }
        for (QueueListener listener : listeners) {
            try {
                listener.onQueueChanged(
                    hasPending,
                    heldCount,
                    repairing
                );
            } catch (RuntimeException e) {
                Log.w(TAG, "Translation queue listener failed", e);
            }
        }
    }

    private JSONObject readState(File jobDirectory) throws Exception {
        File stateFile = new File(jobDirectory, STATE_FILE_NAME);
        try {
            return jobStore.readState(jobDirectory);
        } catch (IOException e) {
            throw new IllegalStateException(
                "Failed to read state file: "
                    + stateFile.getAbsolutePath(),
                e
            );
        }
    }

    private File requireJobDirectoryLocked(String requestId) {
        File jobDirectory = jobStore.jobDirectory(requestId);
        if (!jobDirectory.isDirectory()) {
            throw new IllegalStateException(
                "translation job does not exist requestId=" + requestId
            );
        }
        return jobDirectory;
    }

    private JSONObject requireRunningStateLocked(
        File jobDirectory,
        String requestId
    ) throws Exception {
        JSONObject state = readState(jobDirectory);
        if (state == null) {
            throw new IllegalStateException(
                "translation state is missing requestId=" + requestId
            );
        }
        String status = state.optString("status", "");
        if (!STATUS_RUNNING.equals(status)) {
            throw new IllegalStateException(
                "translation job is not running requestId="
                    + requestId
                    + " status="
                    + status
            );
        }
        return state;
    }

    private byte[] readRequest(File jobDirectory) throws Exception {
        File requestFile = new File(jobDirectory, REQUEST_FILE_NAME);
        try {
            return jobStore.readRequest(jobDirectory);
        } catch (IOException e) {
            throw new IllegalStateException(
                "Failed to read request file: "
                    + requestFile.getAbsolutePath(),
                e
            );
        }
    }

    private void writeState(
        File jobDirectory,
        JSONObject state
    ) throws Exception {
        jobStore.writeState(jobDirectory, state);
    }

    private static String requireRequestString(
        JSONObject request,
        String field,
        String requestId
    ) throws Exception {
        String value = request.getString(field).trim();
        if (value.isEmpty()) {
            throw new IllegalArgumentException(
                "Stored request has empty "
                    + field
                    + " requestId="
                    + requestId
            );
        }
        return value;
    }

    private static void validateStateVersion(
        JSONObject state,
        String requestId
    ) throws Exception {
        int version = state.getInt("version");
        if (version != FORMAT_VERSION) {
            throw new IllegalStateException(
                "Unsupported translation job version="
                    + version
                    + " requestId="
                    + requestId
            );
        }
    }

    private static long readOptionalQueueSequence(
        JSONObject state,
        String requestId
    ) throws Exception {
        if (!state.has("queue_sequence")
            || state.isNull("queue_sequence")) {
            return 0L;
        }
        Object value = state.get("queue_sequence");
        if (!(value instanceof Number)) {
            throw new IllegalStateException(
                "queue_sequence is not a number requestId=" + requestId
            );
        }
        double numericSequence = ((Number) value).doubleValue();
        long sequence = ((Number) value).longValue();
        if (!Double.isFinite(numericSequence)
            || numericSequence != Math.rint(numericSequence)
            || numericSequence > Long.MAX_VALUE
            || sequence <= 0L) {
            throw new IllegalStateException(
                "Invalid queue_sequence="
                    + value
                    + " requestId="
                    + requestId
            );
        }
        return sequence;
    }

    private static long readRequiredTimestamp(
        JSONObject state,
        String field,
        String requestId
    ) throws Exception {
        long value = state.getLong(field);
        if (value < 0L) {
            throw new IllegalStateException(
                "Invalid "
                    + field
                    + "="
                    + value
                    + " requestId="
                    + requestId
            );
        }
        return value;
    }

    private static long readOptionalTimestamp(
        JSONObject state,
        String field,
        String requestId
    ) throws Exception {
        if (!state.has(field) || state.isNull(field)) {
            return -1L;
        }
        return readRequiredTimestamp(state, field, requestId);
    }

    private static HeldQueuedJob heldJobFromState(
        JSONObject state,
        String requestId
    ) throws Exception {
        return new HeldQueuedJob(
            requestId,
            requireRequestString(state, "scene", requestId),
            requireRequestString(state, "target_lang", requestId),
            readRequiredTimestamp(
                state,
                "created_at",
                requestId
            ),
            readOptionalTimestamp(
                state,
                "started_at",
                requestId
            )
        );
    }

    private static long readTolerantTimestamp(
        JSONObject source,
        String field,
        long fallback
    ) {
        if (source == null
            || !source.has(field)
            || source.isNull(field)) {
            return fallback;
        }
        Object value = source.opt(field);
        if (!(value instanceof Number)) {
            return fallback;
        }
        double numericValue = ((Number) value).doubleValue();
        long longValue = ((Number) value).longValue();
        return Double.isFinite(numericValue)
            && numericValue == Math.rint(numericValue)
            && numericValue <= Long.MAX_VALUE
            && longValue >= 0L
                ? longValue
                : fallback;
    }

    private static void copyTolerantTimestamp(
        JSONObject source,
        JSONObject destination,
        String field
    ) throws Exception {
        long value = readTolerantTimestamp(source, field, -1L);
        if (value >= 0L) {
            destination.put(field, value);
        }
    }

    private static void copyTolerantPositiveLong(
        JSONObject source,
        JSONObject destination,
        String field
    ) throws Exception {
        long value = readTolerantTimestamp(source, field, 0L);
        if (value > 0L) {
            destination.put(field, value);
        }
    }

    private static void copyHistoryMapping(
        JSONObject source,
        JSONObject destination
    ) throws Exception {
        if (source != null && source.has(HistoryMapping.FIELD)) {
            destination.put(
                HistoryMapping.FIELD,
                source.get(HistoryMapping.FIELD)
            );
        }
        if (source != null
            && source.has(HISTORY_MEMBERSHIP_PENDING_FIELD)) {
            destination.put(
                HISTORY_MEMBERSHIP_PENDING_FIELD,
                source.get(HISTORY_MEMBERSHIP_PENDING_FIELD)
            );
        }
    }

    private static Comparator<StartupJob> recoveryComparator(
        RecoverySortOrder order
    ) {
        return (left, right) -> {
            long leftValue;
            long rightValue;
            boolean descending;

            switch (order) {
                case CREATED_DESC:
                    leftValue = left.info.getCreatedAt();
                    rightValue = right.info.getCreatedAt();
                    descending = true;
                    break;
                case STARTED_ASC:
                    leftValue = recoveryStartedAt(left.info);
                    rightValue = recoveryStartedAt(right.info);
                    descending = false;
                    break;
                case STARTED_DESC:
                    leftValue = recoveryStartedAt(left.info);
                    rightValue = recoveryStartedAt(right.info);
                    descending = true;
                    break;
                case CREATED_ASC:
                default:
                    leftValue = left.info.getCreatedAt();
                    rightValue = right.info.getCreatedAt();
                    descending = false;
                    break;
            }

            int valueResult = descending
                ? Long.compare(rightValue, leftValue)
                : Long.compare(leftValue, rightValue);
            if (valueResult != 0) {
                return valueResult;
            }
            return left.info.getRequestId().compareTo(
                right.info.getRequestId()
            );
        };
    }

    private static long recoveryStartedAt(HeldQueuedJob job) {
        return job.getStartedAt() >= 0L
            ? job.getStartedAt()
            : job.getCreatedAt();
    }

    private static void validateRequestId(String requestId) {
        PersistentApiJobStore.validateRequestId(
            requestId,
            PersistentApiJobStore.RequestIdFormat.UUID
        );
    }

    private void deleteIncompleteJobDirectory(File jobDirectory) {
        try {
            jobStore.deleteJobDirectory(jobDirectory);
        } catch (IOException e) {
            // A later startup scan safely ignores incomplete directories.
            Log.w(
                TAG,
                "Could not fully delete incomplete translation job directory: "
                    + jobDirectory.getAbsolutePath(),
                e
            );
        }
    }
}
