package com.quarty.housamoembedtrans.translation;

import com.quarty.housamoembedtrans.storage.HistoryMapping;
import com.quarty.housamoembedtrans.storage.ConfigStore;
import com.quarty.housamoembedtrans.storage.RejectedApiResultStore;
import com.quarty.housamoembedtrans.storage.SceneContextStore;
import com.quarty.housamoembedtrans.storage.SceneStore;
import com.quarty.housamoembedtrans.storage.SummaryJobStore;
import com.quarty.housamoembedtrans.util.IoUtils;
import com.quarty.housamoembedtrans.util.JobValidator;

import android.content.Context;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Owns API execution in the HET process.
 *
 * <p>One main lane drains persistent jobs. A second lane is reserved for the
 * active scene's repair requests, so the main provider stream can continue.
 */
public final class TranslationTaskExecutor {
    private static final String TAG = "HET.TranslationExecutor";
    private static final double MERGE_ERROR_RATE = 0.02d;
    private static final int MAX_CAPTURED_PROVIDER_CHARS =
        8 * 1024 * 1024;
    private static final long[] CLAIM_RETRY_DELAYS_MILLIS = {
        1000L,
        2000L,
        5000L,
        10000L,
        30000L
    };
    private static final long WAITING_RETRY_DELAY_MILLIS = 5_000L;

    public interface ResultListener {
        void onStarted(String requestId, String scene);

        void onQuestPatch(String requestId, JSONObject patch);

        void onCompleted(
            String requestId,
            String scene,
            String targetLanguage,
            byte[] resultJson
        );

        void onFailed(
            String requestId,
            String scene,
            JSONObject error
        );

        /** Notification seam for rejected API results archived by this ticket. */
        default void onRejectedApiResultArchived(JSONObject record) {
        }

        /**
         * Notification seam for a Translation Job that cannot be sent until the
         * user acts (damaged mapping, damaged history, or context-length
         * overflow). The job is intentionally not a terminal failure.
         */
        default void onTranslationNeedsUserAction(
            String requestId,
            String scene,
            String reason
        ) {
        }
    }

    @FunctionalInterface
    public interface ActivityListener {
        void onActiveApiJobsChanged();
    }

    interface ScheduledHandle {
        void cancel();
    }

    interface DrainScheduler {
        void execute(Runnable runnable);

        ScheduledHandle schedule(
            Runnable runnable,
            long delay,
            TimeUnit unit
        );

        void shutdownNow();
    }

    interface ClaimSource {
        TranslationJobStore.ClaimedJob claim() throws Exception;

        boolean hasPendingJobs();
    }

    /** Read-only snapshot of a job held for a user action. */
    public static final class BlockedJob {
        private final String requestId;
        private final String scene;
        private final String reason;

        private BlockedJob(String requestId, String scene, String reason) {
            this.requestId = requestId;
            this.scene = scene == null ? "" : scene;
            this.reason = reason == null ? "" : reason;
        }

        public String getRequestId() {
            return requestId;
        }

        public String getScene() {
            return scene;
        }

        public String getReason() {
            return reason;
        }
    }

    /** In-memory delayed retry entry for a WAITING History Resolution. */
    private static final class DeferredJob {
        private final String requestId;
        private final byte[] requestJson;
        private final long retryAtMillis;

        private DeferredJob(
            String requestId,
            byte[] requestJson,
            long retryAtMillis
        ) {
            this.requestId = requestId;
            this.requestJson = requestJson;
            this.retryAtMillis = retryAtMillis;
        }
    }

    /** Minimal pre-send gate used by both the executor and host tests. */
    enum SendDecision {
        SEND,
        WAITING,
        USER_ACTION_REQUIRED
    }

    static final class PreflightResult {
        private final SendDecision decision;
        private final String reason;

        private PreflightResult(SendDecision decision, String reason) {
            this.decision = decision;
            this.reason = reason == null ? "" : reason;
        }

        SendDecision getDecision() {
            return decision;
        }

        String getReason() {
            return reason;
        }

        boolean isBlocked() {
            return decision != SendDecision.SEND;
        }
    }

    /**
     * Pure pre-send classification shared by the executor and host tests.
     *
     * <p>Explicit {@code history_mapping: null} remains sendable unless the
     * assembled provider input exceeds {@code context_length}. A malformed
     * mapping and every non-READY History Resolution are hard blocks: no API
     * request and no Translation terminal failure.</p>
     */
    static PreflightResult preflight(
        com.quarty.housamoembedtrans.storage.HistoryMapping.Resolution mapping,
        HistoryResolution historyResolution,
        PreparedApiRequest prepared
    ) {
        if (mapping
            == com.quarty.housamoembedtrans.storage.HistoryMapping.Resolution
                .USER_ACTION_REQUIRED) {
            return new PreflightResult(
                SendDecision.USER_ACTION_REQUIRED,
                "history_mapping is missing, malformed, or uses an invalid id; "
                    + "fix the mapping before sending this job"
            );
        }
        if (mapping
            == com.quarty.housamoembedtrans.storage.HistoryMapping.Resolution
                .VALID) {
            if (historyResolution == null) {
                return new PreflightResult(
                    SendDecision.USER_ACTION_REQUIRED,
                    "history resolution is missing for a valid history mapping"
                );
            }
            switch (historyResolution.getStatus()) {
                case WAITING:
                    return new PreflightResult(
                        SendDecision.WAITING,
                        historyResolution.getReason()
                    );
                case USER_ACTION_REQUIRED:
                    return new PreflightResult(
                        SendDecision.USER_ACTION_REQUIRED,
                        historyResolution.getReason()
                    );
                case READY:
                    break;
                default:
                    return new PreflightResult(
                        SendDecision.USER_ACTION_REQUIRED,
                        "unknown history resolution status"
                    );
            }
        }
        if (prepared != null) {
            HistoryResolution lengthCheck =
                HistoryResolver.checkContextLength(prepared);
            if (!lengthCheck.isReady()) {
                return new PreflightResult(
                    SendDecision.USER_ACTION_REQUIRED,
                    lengthCheck.getReason()
                );
            }
        }
        return new PreflightResult(SendDecision.SEND, "");
    }

    private static final class ExecutorDrainScheduler
        implements DrainScheduler {
        private final ScheduledExecutorService executor;

        private ExecutorDrainScheduler(ScheduledExecutorService executor) {
            this.executor = executor;
        }

        @Override
        public void execute(Runnable runnable) {
            executor.execute(runnable);
        }

        @Override
        public ScheduledHandle schedule(
            Runnable runnable,
            long delay,
            TimeUnit unit
        ) {
            java.util.concurrent.ScheduledFuture<?> future =
                executor.schedule(runnable, delay, unit);
            return () -> future.cancel(false);
        }

        @Override
        public void shutdownNow() {
            executor.shutdownNow();
        }
    }

    private enum ItemStatus {
        PENDING,
        PROVISIONAL,
        FINAL_VALID,
        BLOCKED
    }

    private static final class ItemProgress {
        private final int seq;
        private ItemStatus status = ItemStatus.PENDING;
        private String displayText;
        private String finalText;
        private TranslationResultValidator.Result failure;
        private int repairAttempts;
        private String deliveredText;
        private boolean deliveredProvisional;

        private ItemProgress(int seq) {
            this.seq = seq;
        }

        private boolean isFinalValid() {
            return status == ItemStatus.FINAL_VALID;
        }

        private boolean isDisplayable() {
            return status == ItemStatus.FINAL_VALID
                || status == ItemStatus.PROVISIONAL;
        }

        private String currentDisplayText() {
            return status == ItemStatus.FINAL_VALID
                ? finalText
                : displayText;
        }
    }

    private final Context context;
    private final TranslationJobStore jobStore;
    private final ResultListener resultListener;
    private final ContextSummaryCoordinator contextSummaryCoordinator;
    private final ContextHistoryPreparer contextHistoryPreparer;
    private final DrainScheduler mainExecutor;
    private final ClaimSource claimSource;
    private final ExecutorService repairExecutor;
    private final int maxWorkers;
    private final Object drainScheduleLock = new Object();
    private int activeDrainWorkers;
    private ScheduledHandle claimRetryHandle;
    private final AtomicInteger claimFailureCount = new AtomicInteger();
    private final Object blockedLock = new Object();
    private final PriorityQueue<DeferredJob> deferredJobs = new PriorityQueue<>(
        (left, right) -> Long.compare(
            left.retryAtMillis,
            right.retryAtMillis
        )
    );
    private final Map<String, byte[]> userActionRequiredRequests =
        new LinkedHashMap<>();
    private final Map<String, String> userActionRequiredScenes =
        new LinkedHashMap<>();
    private final Map<String, String> userActionRequiredReasons =
        new LinkedHashMap<>();
    private final Set<String> userActionNotified = new LinkedHashSet<>();
    private final AtomicBoolean deferredRetryScheduled = new AtomicBoolean();
    private volatile SceneSyncCoordinator sceneSyncCoordinator;
    private volatile ActivityListener activityListener;
    private volatile ApiConcurrencyGate apiGate;
    private volatile boolean shutdown;
    private final ThreadLocal<PreparedJob> preparedExecution =
        new ThreadLocal<>();

    public TranslationTaskExecutor(
        Context context,
        TranslationJobStore jobStore,
        ResultListener resultListener
    ) {
        this(
            context,
            jobStore,
            resultListener,
            readApiConcurrency(context)
        );
    }

    /**
     * Builds the Translation worker pool from the API concurrency setting.
     * SceneWorkerCount controls Scene Sync only and must not serialize API
     * requests when the shared API limit permits parallel work.
     */
    public TranslationTaskExecutor(
        Context context,
        TranslationJobStore jobStore,
        ResultListener resultListener,
        int maxWorkers
    ) {
        if (context == null || jobStore == null || resultListener == null) {
            throw new IllegalArgumentException(
                "context, jobStore, and resultListener cannot be null"
            );
        }
        Context appContext = context.getApplicationContext();
        this.context = appContext != null ? appContext : context;
        this.jobStore = jobStore;
        this.resultListener = resultListener;
        this.maxWorkers = workerCountForApiConcurrency(maxWorkers);
        this.mainExecutor = new ExecutorDrainScheduler(
            Executors.newScheduledThreadPool(
                this.maxWorkers,
                runnable ->
                    new Thread(runnable, "HET-translation-worker")
            )
        );
        this.claimSource = new ClaimSource() {
            @Override
            public TranslationJobStore.ClaimedJob claim() throws Exception {
                return jobStore.claimNextQueuedJob();
            }

            @Override
            public boolean hasPendingJobs() {
                return jobStore.hasPendingJobs();
            }
        };
        this.repairExecutor = Executors.newSingleThreadExecutor(runnable ->
            new Thread(runnable, "HET-translation-repair")
        );
        SceneContextStore sceneContextStore = new SceneContextStore(this.context);
        SummaryJobStore summaryJobStore =
            SummaryJobStore.createForAndroid(this.context);
        this.contextSummaryCoordinator = new ContextSummaryCoordinator(
            sceneContextStore,
            summaryJobStore,
            RejectedApiResultStore.createForAndroid(
                new File(
                    this.context.getFilesDir(),
                    RejectedApiResultStore.DIRECTORY_NAME
                )
            ),
            new ContextSummaryCoordinator.ContextSummaryReleaseGate(),
            resultListener::onRejectedApiResultArchived
        );
        this.contextHistoryPreparer = new ContextHistoryPreparer(
            sceneContextStore
        );
    }

    private TranslationTaskExecutor(
        DrainScheduler mainExecutor,
        ClaimSource claimSource
    ) {
        this(mainExecutor, claimSource, null);
    }

    private TranslationTaskExecutor(
        DrainScheduler mainExecutor,
        ClaimSource claimSource,
        ResultListener resultListener
    ) {
        if (mainExecutor == null || claimSource == null) {
            throw new IllegalArgumentException(
                "mainExecutor and claimSource cannot be null"
            );
        }
        context = null;
        jobStore = null;
        this.resultListener = resultListener;
        contextSummaryCoordinator = null;
        contextHistoryPreparer = null;
        this.mainExecutor = mainExecutor;
        this.claimSource = claimSource;
        repairExecutor = null;
        maxWorkers = 1;
    }

    static TranslationTaskExecutor forClaimDrainTest(
        DrainScheduler mainExecutor,
        ClaimSource claimSource
    ) {
        return new TranslationTaskExecutor(mainExecutor, claimSource);
    }

    static TranslationTaskExecutor forBlockingTest(
        DrainScheduler mainExecutor,
        ClaimSource claimSource,
        ResultListener resultListener
    ) {
        return new TranslationTaskExecutor(
            mainExecutor,
            claimSource,
            resultListener
        );
    }

    public void scheduleDrain() {
        ScheduledHandle retryToCancel;
        int workersToStart;
        synchronized (drainScheduleLock) {
            if (shutdown) {
                return;
            }
            retryToCancel = claimRetryHandle;
            claimRetryHandle = null;
            workersToStart = Math.max(0, maxWorkers - activeDrainWorkers);
            activeDrainWorkers += workersToStart;
        }
        if (retryToCancel != null) {
            retryToCancel.cancel();
        }
        if (workersToStart == 0) {
            return;
        }
        int submitted = 0;
        try {
            for (; submitted < workersToStart; submitted++) {
                mainExecutor.execute(this::drainLoop);
            }
        } catch (RejectedExecutionException e) {
            synchronized (drainScheduleLock) {
                activeDrainWorkers -= workersToStart - submitted;
            }
            if (!shutdown) {
                Log.e(TAG, "Could not schedule translation drain", e);
            }
        }
    }

    /**
     * Requeues an in-memory user-action-required job for a fresh pre-send
     * attempt. This is the manual retry path after the user fixes the mapping
     * or context configuration; the executor never polls for it automatically.
     */
    public boolean retryUserActionRequiredJob(String requestId) {
        if (requestId == null) {
            return false;
        }
        byte[] requestJson;
        synchronized (blockedLock) {
            requestJson = userActionRequiredRequests.remove(requestId);
            if (requestJson == null) {
                return false;
            }
            userActionRequiredScenes.remove(requestId);
            userActionRequiredReasons.remove(requestId);
            userActionNotified.remove(requestId);
        }
        deferJob(new TranslationJobStore.ClaimedJob(requestId, requestJson), 0L);
        return true;
    }

    /** Returns the current in-memory jobs blocked for a user action. */
    public List<BlockedJob> listUserActionRequiredJobs() {
        synchronized (blockedLock) {
            List<BlockedJob> result = new ArrayList<>(
                userActionRequiredRequests.size()
            );
            for (Map.Entry<String, byte[]> entry
                : userActionRequiredRequests.entrySet()) {
                String requestId = entry.getKey();
                result.add(new BlockedJob(
                    requestId,
                    userActionRequiredScenes.get(requestId),
                    userActionRequiredReasons.get(requestId)
                ));
            }
            return result;
        }
    }

    void deferJob(
        TranslationJobStore.ClaimedJob job,
        long delayMillis
    ) {
        long retryAt = System.currentTimeMillis() + Math.max(0L, delayMillis);
        synchronized (blockedLock) {
            deferredJobs.removeIf(candidate ->
                candidate.requestId.equals(job.getRequestId())
            );
            deferredJobs.offer(new DeferredJob(
                job.getRequestId(),
                job.getRequestJson(),
                retryAt
            ));
        }
        scheduleDeferredRetry();
    }

    private void scheduleDeferredRetry() {
        if (!deferredRetryScheduled.compareAndSet(false, true)) {
            return;
        }
        final long delayMillis;
        synchronized (blockedLock) {
            DeferredJob head = deferredJobs.peek();
            if (head == null) {
                deferredRetryScheduled.set(false);
                return;
            }
            delayMillis = Math.max(
                0L,
                head.retryAtMillis - System.currentTimeMillis()
            );
        }
        try {
            mainExecutor.schedule(() -> {
                deferredRetryScheduled.set(false);
                scheduleDrain();
                scheduleDeferredRetry();
            }, delayMillis, TimeUnit.MILLISECONDS);
        } catch (RejectedExecutionException e) {
            deferredRetryScheduled.set(false);
            if (!shutdown) {
                Log.e(TAG, "Could not schedule deferred translation retry", e);
            }
        }
    }

    TranslationJobStore.ClaimedJob pollDueDeferredJob() {
        synchronized (blockedLock) {
            DeferredJob head = deferredJobs.peek();
            if (head == null
                || head.retryAtMillis > System.currentTimeMillis()) {
                return null;
            }
            deferredJobs.poll();
            return new TranslationJobStore.ClaimedJob(
                head.requestId,
                head.requestJson
            );
        }
    }

    private boolean hasDueDeferredJobs() {
        synchronized (blockedLock) {
            DeferredJob head = deferredJobs.peek();
            return head != null
                && head.retryAtMillis <= System.currentTimeMillis();
        }
    }

    private boolean hasImmediateWork() {
        if (claimSource.hasPendingJobs()) {
            return true;
        }
        return hasDueDeferredJobs();
    }

    private void notifyFailureOnce(
        String requestId,
        String scene,
        JSONObject error
    ) {
        if (jobStore == null || resultListener == null) {
            return;
        }
        try {
            if (jobStore.isErrorNotified(requestId)) {
                return;
            }
            resultListener.onFailed(requestId, scene, error);
            jobStore.markErrorNotified(requestId);
        } catch (RuntimeException notificationFailure) {
            Log.w(
                TAG,
                "Failure notification failed after terminal persistence "
                    + "requestId="
                    + requestId,
                notificationFailure
            );
        } catch (Exception persistFailure) {
            Log.w(
                TAG,
                "Could not mark translation failure notified requestId="
                    + requestId,
                persistFailure
            );
        }
    }

    void registerUserActionRequired(
        TranslationJobStore.ClaimedJob job,
        String scene,
        String reason
    ) {
        boolean shouldNotify;
        synchronized (blockedLock) {
            userActionRequiredRequests.put(
                job.getRequestId(),
                job.getRequestJson()
            );
            userActionRequiredScenes.put(job.getRequestId(), scene);
            userActionRequiredReasons.put(job.getRequestId(), reason);
            shouldNotify = userActionNotified.add(job.getRequestId());
        }
        if (shouldNotify && resultListener != null) {
            try {
                resultListener.onTranslationNeedsUserAction(
                    job.getRequestId(),
                    scene,
                    reason
                );
            } catch (RuntimeException notificationFailure) {
                Log.w(
                    TAG,
                    "User-action notification failed requestId="
                        + job.getRequestId(),
                    notificationFailure
                );
            }
        }
    }

    /**
     * Installs the Service-owned API admission gate.  The executor keeps this
     * setter lightweight so service startup can wire the gate before the
     * first drain is scheduled.
     */
    public void setSceneSyncCoordinator(SceneSyncCoordinator coordinator) {
        sceneSyncCoordinator = coordinator;
    }

    /** Installs the shared API concurrency gate before the first drain. */
    public void setApiConcurrencyGate(ApiConcurrencyGate gate) {
        apiGate = gate;
    }

    /** Installs a lightweight observer for committed API activity changes. */
    public void setActivityListener(ActivityListener listener) {
        activityListener = listener;
    }

    public void shutdown() {
        ScheduledHandle retryToCancel;
        synchronized (drainScheduleLock) {
            shutdown = true;
            retryToCancel = claimRetryHandle;
            claimRetryHandle = null;
        }
        if (retryToCancel != null) {
            retryToCancel.cancel();
        }
        mainExecutor.shutdownNow();
        if (repairExecutor != null) {
            repairExecutor.shutdownNow();
        }
    }

    /** Immutable claim-time snapshot prepared under the Scene root gate. */
    private final class PreparedJob {
        private final TranslationJobStore.ClaimedJob claimedJob;
        private final JSONObject request;
        private final JobValidator.RequestInfo requestInfo;
        private final TranslationConfig config;
        private final JSONObject state;
        private final JobCoordinator coordinator;

        private PreparedJob(
            TranslationJobStore.ClaimedJob claimedJob,
            JSONObject request,
            JobValidator.RequestInfo requestInfo,
            TranslationConfig config,
            JSONObject state,
            JobCoordinator coordinator
        ) {
            this.claimedJob = claimedJob;
            this.request = request;
            this.requestInfo = requestInfo;
            this.config = config;
            this.state = state;
            this.coordinator = coordinator;
        }
    }

    private final class ClaimPreparation {
        private final TranslationJobStore.ClaimedJob claimedJob;
        private final PreparedJob preparedJob;
        private final Exception preparationFailure;

        private ClaimPreparation(
            TranslationJobStore.ClaimedJob claimedJob,
            PreparedJob preparedJob,
            Exception preparationFailure
        ) {
            this.claimedJob = claimedJob;
            this.preparedJob = preparedJob;
            this.preparationFailure = preparationFailure;
        }
    }

    private ClaimPreparation claimAndPrepareUnderRoot() throws Exception {
        TranslationJobStore.ClaimedJob claimed = claimSource.claim();
        if (claimed == null) {
            claimed = pollDueDeferredJob();
        }
        if (claimed == null) {
            return null;
        }
        try {
            JSONObject request = JobValidator.parseJsonObject(
                claimed.getRequestJson(),
                32 * 1024 * 1024,
                "request"
            );
            JobValidator.RequestInfo requestInfo =
                JobValidator.validateRequest(request);
            TranslationConfig config = TranslationConfig.load(context);
            JSONObject state = jobStore.readState(claimed.getRequestId());
            JobCoordinator coordinator = new JobCoordinator(
                claimed.getRequestId(),
                request,
                requestInfo,
                config,
                jobStore.readProgress(claimed.getRequestId()),
                state,
                contextSummaryCoordinator
            );
            return new ClaimPreparation(
                claimed,
                new PreparedJob(
                    claimed,
                    request,
                    requestInfo,
                    config,
                    state,
                    coordinator
                ),
                null
            );
        } catch (Exception preparationFailure) {
            return new ClaimPreparation(
                claimed,
                null,
                preparationFailure
            );
        }
    }

    private void drainLoop() {
        boolean blockedBySceneSync = false;
        boolean claimFailed = false;
        boolean interrupted = false;
        long retryDelayMillis = 0L;
        try {
            while (!shutdown) {
                SceneSyncCoordinator coordinator = sceneSyncCoordinator;
                ApiConcurrencyGate gate = apiGate;
                ApiConcurrencyGate.Permit apiPermit = null;
                boolean apiPermitTransferred = false;
                boolean reservationHeld = false;
                boolean activeClaim = false;
                try {
                    if (gate != null) {
                        apiPermit = gate.acquireTranslation();
                    }
                    // Reserve under the coordinator lock, then perform the
                    // potentially blocking JobStore claim outside that lock.
                    // A pending automatic sync closes the gate before this
                    // worker can claim another job.
                    if (coordinator != null) {
                        if (!coordinator.reserveApiJobClaim()) {
                            blockedBySceneSync = true;
                            return;
                        }
                        reservationHeld = true;
                    }

                    ClaimPreparation preparation;
                    try {
                        // Claim and freeze the history route under the same
                        // process-wide Scene root gate used by Review and
                        // Summary facts.  A mapping rewrite cannot land
                        // between the durable running transition and the
                        // context/group snapshot used for this attempt.
                        preparation = SceneContextStore.withRootAccess(
                            this::claimAndPrepareUnderRoot
                        );
                        claimFailureCount.set(0);
                    } catch (InterruptedException e) {
                        throw e;
                    } catch (Exception e) {
                        int failureCount = claimFailureCount.updateAndGet(
                            current -> current == Integer.MAX_VALUE
                                ? current
                                : current + 1
                        );
                        retryDelayMillis = claimRetryDelayMillis(
                            failureCount
                        );
                        claimFailed = true;
                        Log.e(
                            TAG,
                            "Could not claim queued translation job; retrying in "
                                + retryDelayMillis
                                + " ms",
                            e
                        );
                        return;
                    }
                    if (preparation == null) {
                        return;
                    }
                    TranslationJobStore.ClaimedJob job =
                        preparation.claimedJob;
                    if (job == null) {
                        job = pollDueDeferredJob();
                    }
                    if (job == null) {
                        return;
                    }

                    if (coordinator != null) {
                        coordinator.commitApiJobClaim();
                        reservationHeld = false;
                        activeClaim = true;
                        notifyActivityChanged();
                    }

                    try {
                        if (preparation.preparedJob != null) {
                            preparedExecution.set(preparation.preparedJob);
                        }
                        apiPermitTransferred = true;
                        executeJob(job, gate, apiPermit);
                    } finally {
                        preparedExecution.remove();
                        // executeJob only returns after final persistence and
                        // callback delivery (or its terminal failure path).
                        // Release exactly once after that boundary.
                        if (activeClaim) {
                            coordinator.releaseApiJob();
                            activeClaim = false;
                            notifyActivityChanged();
                        }
                    }
                } finally {
                    if (reservationHeld && coordinator != null) {
                        coordinator.releaseApiJobClaimReservation();
                        if (coordinator.getState()
                            != SceneSyncCoordinator.State.NONE) {
                            blockedBySceneSync = true;
                        }
                    }
                    if (apiPermit != null && !apiPermitTransferred) {
                        gate.release(apiPermit);
                    }
                }
            }
        } catch (InterruptedException e) {
            interrupted = true;
            Thread.currentThread().interrupt();
        } catch (Exception e) {
            Log.e(TAG, "Translation drain stopped unexpectedly", e);
        } finally {
            synchronized (drainScheduleLock) {
                activeDrainWorkers = Math.max(0, activeDrainWorkers - 1);
            }
            // A Scene Sync gate owns the next wake-up.  Self-rescheduling here
            // would spin while FULL_SYNC/pending_auto_sync remains active.
            if (!shutdown
                && !interrupted
                && !blockedBySceneSync
                && hasImmediateWork()) {
                if (claimFailed) {
                    scheduleClaimRetry(retryDelayMillis);
                } else {
                    scheduleDrain();
                }
            }
        }
    }

    /**
     * Lifecycle identity for one provider attempt. Body state remains owned
     * by the coordinator, but this token gates streaming repairs and prevents
     * a replaced attempt from publishing late work.
     */
    private final class MainAttemptState {
        private final long token;
        private boolean active = true;
        private boolean accepted;

        private MainAttemptState(long token) {
            this.token = token;
        }

        private void invalidate() {
            active = false;
            accepted = false;
        }
    }

    private void scheduleClaimRetry(long delayMillis) {
        synchronized (drainScheduleLock) {
            if (shutdown
                || activeDrainWorkers > 0
                || claimRetryHandle != null) {
                return;
            }
            try {
                claimRetryHandle = mainExecutor.schedule(
                    this::runClaimRetry,
                    delayMillis,
                    TimeUnit.MILLISECONDS
                );
            } catch (RejectedExecutionException e) {
                if (!shutdown) {
                    Log.e(TAG, "Could not schedule claim retry", e);
                }
            }
        }
    }

    private void runClaimRetry() {
        synchronized (drainScheduleLock) {
            claimRetryHandle = null;
            if (shutdown) {
                return;
            }
        }
        scheduleDrain();
    }

    private static long claimRetryDelayMillis(int failureCount) {
        int index = Math.min(
            Math.max(failureCount, 1) - 1,
            CLAIM_RETRY_DELAYS_MILLIS.length - 1
        );
        return CLAIM_RETRY_DELAYS_MILLIS[index];
    }

    private static int readApiConcurrency(Context context) {
        if (context == null) {
            return 1;
        }
        try {
            JSONObject settings = new ConfigStore(context)
                .load()
                .config
                .getJSONObject("UserSettings");
            return ConfigStore.getApiConcurrency(settings);
        } catch (Exception ignored) {
            return 1;
        }
    }

    static int workerCountForApiConcurrency(int apiConcurrency) {
        return Math.max(1, apiConcurrency);
    }

    private void notifyActivityChanged() {
        ActivityListener listener = activityListener;
        if (listener == null) {
            return;
        }
        try {
            listener.onActiveApiJobsChanged();
        } catch (RuntimeException ignored) {
            // Service/UI observers cannot corrupt API claim accounting.
        }
    }

    private void executeJob(
        TranslationJobStore.ClaimedJob job,
        ApiConcurrencyGate permitGate,
        ApiConcurrencyGate.Permit initialPermit
    ) throws InterruptedException {
        String requestId = job.getRequestId();
        String scene = "";
        boolean permitAdopted = false;
        try {
            PreparedJob prepared = preparedExecution.get();
            JSONObject request;
            JobValidator.RequestInfo requestInfo;
            TranslationConfig config;
            JSONObject state;
            JobCoordinator coordinator;
            if (prepared != null && prepared.claimedJob == job) {
                request = prepared.request;
                requestInfo = prepared.requestInfo;
                config = prepared.config;
                state = prepared.state;
                coordinator = prepared.coordinator;
            } else {
                request = JobValidator.parseJsonObject(
                    job.getRequestJson(),
                    32 * 1024 * 1024,
                    "request"
                );
                requestInfo = JobValidator.validateRequest(request);
                config = TranslationConfig.load(context);
                state = jobStore.readState(requestId);
                final JSONObject requestForCoordinator = request;
                final JobValidator.RequestInfo infoForCoordinator = requestInfo;
                final TranslationConfig configForCoordinator = config;
                final JSONObject stateForCoordinator = state;
                coordinator = SceneContextStore.withRootAccess(
                    () -> new JobCoordinator(
                        requestId,
                        requestForCoordinator,
                        infoForCoordinator,
                        configForCoordinator,
                        jobStore.readProgress(requestId),
                        stateForCoordinator,
                        contextSummaryCoordinator
                    )
                );
            }
            scene = requestInfo.getScene();
            resultListener.onStarted(
                requestId,
                scene
            );

            // Adopt only after the observer callback has returned.  From this
            // point the coordinator's run/finally owns the permit; an
            // observer exception therefore remains covered by executeJob's
            // release path.
            coordinator.adoptInitialMainPermit(permitGate, initialPermit);
            permitAdopted = true;
            coordinator.run();
            if (coordinator.isBlocked()) {
                HistoryResolution.Status blockedStatus =
                    coordinator.getBlockedStatus();
                String blockedReason = coordinator.getBlockedReason();
                if (blockedStatus
                    == HistoryResolution.Status.WAITING) {
                    Log.i(
                        TAG,
                        "Translation deferred while history is WAITING "
                            + "requestId="
                            + requestId
                            + " reason="
                            + blockedReason
                    );
                    deferJob(job, WAITING_RETRY_DELAY_MILLIS);
                } else {
                    Log.w(
                        TAG,
                        "Translation held for user action requestId="
                            + requestId
                            + " reason="
                            + blockedReason
                    );
                    registerUserActionRequired(job, scene, blockedReason);
                }
                return;
            }
            if (coordinator.isSuccessful()) {
                byte[] resultBytes = coordinator.buildFinalResult()
                    .toString()
                    .getBytes(StandardCharsets.UTF_8);
                try {
                    jobStore.completeRunningJob(requestId, resultBytes);
                } catch (Exception persistFailure) {
                    // result.json may already be durable when state.json
                    // fails.  Do not create an opposing error payload; the
                    // startup reconciliation path owns this recovery.
                    Log.e(
                        TAG,
                        "Could not persist completed terminal state requestId="
                            + requestId,
                        persistFailure
                    );
                    return;
                }
                try {
                    coordinator.deliverCompletionQuestPatch();
                } catch (Exception patchFailure) {
                    Log.w(
                        TAG,
                        "Completion Quest patch failed after terminal persistence "
                            + "requestId="
                            + requestId,
                        patchFailure
                    );
                }
                try {
                    resultListener.onCompleted(
                        requestId,
                        scene,
                        requestInfo.getTargetLanguage(),
                        resultBytes
                    );
                } catch (RuntimeException notificationFailure) {
                    Log.w(
                        TAG,
                        "Completion notification failed after terminal persistence "
                            + "requestId="
                            + requestId,
                        notificationFailure
                    );
                }
                Log.i(
                    TAG,
                    "Translation completed requestId="
                        + requestId
                        + " items="
                        + requestInfo.getTextCount()
                );
            } else {
                JSONObject error = coordinator.buildFailure();
                byte[] errorBytes = error.toString()
                    .getBytes(StandardCharsets.UTF_8);
                try {
                    jobStore.failRunningJob(requestId, errorBytes);
                } catch (Exception persistFailure) {
                    // Preserve the authoritative error payload and let
                    // startup reconciliation repair state.json; never write
                    // a second terminal kind from this catch path.
                    Log.e(
                        TAG,
                        "Could not persist failed terminal state requestId="
                            + requestId,
                        persistFailure
                    );
                    return;
                }
                try {
                    if (config.shouldDumpFailedApiResponse()) {
                        dumpFailedApiResponse(
                            requestInfo.getScene(),
                            coordinator.buildDebugDump(error)
                        );
                    }
                } catch (Exception dumpFailure) {
                    Log.w(
                        TAG,
                        "Failed API response dump failed after terminal persistence "
                            + "requestId="
                            + requestId,
                        dumpFailure
                    );
                }
                notifyFailureOnce(requestId, scene, error);
                Log.e(
                    TAG,
                    "Translation failed requestId="
                        + requestId
                        + " reason="
                        + coordinator.getFailureMessage()
                );
            }
        } catch (InterruptedException e) {
            throw e;
        } catch (Exception e) {
            Log.e(
                TAG,
                "Translation task crashed requestId=" + requestId,
                e
            );
            try {
                JSONObject error = errorObject(
                    "client",
                    safeMessage(e),
                    Collections.emptySet()
                );
                jobStore.failRunningJob(
                    requestId,
                    error.toString().getBytes(StandardCharsets.UTF_8)
                );
                notifyFailureOnce(requestId, scene, error);
            } catch (Exception persistFailure) {
                Log.e(
                    TAG,
                    "Could not persist task failure requestId=" + requestId,
                    persistFailure
                );
            }
        } finally {
            if (!permitAdopted
                && initialPermit != null
                && permitGate != null) {
                permitGate.release(initialPermit);
            }
        }
    }

    private ContextHistoryPreparer.HistoryPreparation resolveHistoryContext(
        String requestId,
        JSONObject state,
        JobValidator.RequestInfo requestInfo,
        TranslationConfig config
    ) {
        if (state == null) {
            return ContextHistoryPreparer.HistoryPreparation.blocked(
                HistoryResolution.userActionRequired(
                "translation state is missing; cannot read history_mapping"
                )
            );
        }
        HistoryMapping.Resolution mapping = HistoryMapping.resolution(state);
        if (mapping == HistoryMapping.Resolution.USER_ACTION_REQUIRED) {
            return ContextHistoryPreparer.HistoryPreparation.blocked(
                HistoryResolution.userActionRequired(
                "history_mapping is missing, malformed, or uses an invalid id; "
                    + "fix the mapping before sending this job"
                )
            );
        }
        if (mapping == HistoryMapping.Resolution.NO_HISTORY) {
            return ContextHistoryPreparer.HistoryPreparation.noHistory();
        }
        if (contextHistoryPreparer == null) {
            return ContextHistoryPreparer.HistoryPreparation.blocked(
                HistoryResolution.userActionRequired(
                    "history preparation is unavailable for a valid mapping"
                )
            );
        }
        JSONObject mappingObject = state.optJSONObject(HistoryMapping.FIELD);
        if (mappingObject == null) {
            return ContextHistoryPreparer.HistoryPreparation.blocked(
                HistoryResolution.userActionRequired(
                    "history_mapping object is missing"
                )
            );
        }
        String scene = requestInfo.getScene();
        String targetLang = requestInfo.getTargetLanguage();
        ContextHistoryPreparer.HistoryPreparation preparation =
            contextHistoryPreparer.prepare(
                mappingObject,
                requestId,
                scene,
                targetLang,
                config,
                (missingContext, missingScene, missingTargetLang) ->
                    jobStore != null
                        && jobStore.hasQueuedOrRunningSceneSummaryProducer(
                            missingContext == null
                                ? ""
                                : missingContext.optString("id", ""),
                            missingScene,
                            missingTargetLang,
                            requestId
                        )
            );
        if (preparation.resolution == null
            || !preparation.resolution.isReady()) {
            return ContextHistoryPreparer.HistoryPreparation.blocked(
                preparation.resolution == null
                    ? HistoryResolution.userActionRequired(
                        "history preparation returned no resolution"
                    )
                    : preparation.resolution
            );
        }
        return preparation;
    }

    private final class JobCoordinator {
        private final String requestId;
        private final JSONObject request;
        private final JobValidator.RequestInfo requestInfo;
        private final TranslationConfig config;
        private final ContextSummaryCoordinator contextSummaryCoordinator;
        private final Object historyMapping;
        private final HistoryMapping.Resolution mappingResolution;
        private final HistoryResolution historyBlockResolution;
        private final String contextId;
        private final String contextStorageName;
        private final String capturedSourceHashExcludingScene;
        private final boolean requestContextSummary;
        private final HistoryPayload historyPayload;
        private HistoryResolution.Status blockedStatus;
        private String blockedReason = "";
        private final ContextSummaryCoordinator.Options contextSummaryOptions;
        private final TranslationResultValidator validator;
        private final List<TranslationGradientPlanner.Block> blocks;
        private final Map<Integer, ItemProgress> items =
            new LinkedHashMap<>();
        private final Map<Integer, Integer> repairAttemptsConsumed =
            new LinkedHashMap<>();
        private final LinkedHashSet<Integer> repairQueue =
            new LinkedHashSet<>();
        private final LinkedHashSet<Integer> carriedFailures =
            new LinkedHashSet<>();
        private final LinkedHashSet<Integer> activeRepairSeqs =
            new LinkedHashSet<>();
        private final boolean[] closedBlocks;
        private boolean streamingRepairEnabled;
        private boolean useFullSceneForRepair;
        private int gradientCount;
        private String summary;
        private String contextSummary;
        private int highestMainSeqSeen;
        private int mainResultRestarts;
        private int sceneRepairRounds;
        private long patchVersion;
        private boolean mainFinished;
        private boolean repairRunning;
        private boolean terminal;
        private boolean successful;
        private boolean fatalProviderFailure;
        private String failureMessage = "";
        private JSONObject completionQuestPatch;
        private final StringBuilder mainAttemptOutput =
            new StringBuilder();
        private final StringBuilder repairAttemptOutput =
            new StringBuilder();
        private String lastProviderError = "";
        private boolean mainOutputTruncated;
        private boolean repairOutputTruncated;
        private long attemptTokenCounter;
        private MainAttemptState currentAttempt;
        private ApiConcurrencyGate mainPermitGate;
        private ApiConcurrencyGate.Permit mainPermit;

        private void adoptInitialMainPermit(
            ApiConcurrencyGate permitGate,
            ApiConcurrencyGate.Permit permit
        ) {
            if (permit == null) {
                return;
            }
            if (permitGate == null) {
                throw new IllegalArgumentException(
                    "permit gate is required when adopting a permit"
                );
            }
            if (mainPermit != null) {
                throw new IllegalStateException(
                    "main API permit was already adopted"
                );
            }
            mainPermitGate = permitGate;
            mainPermit = permit;
        }

        private void releaseMainPermit() {
            ApiConcurrencyGate gate = mainPermitGate;
            ApiConcurrencyGate.Permit permit = mainPermit;
            if (gate == null || permit == null) {
                return;
            }
            mainPermitGate = null;
            mainPermit = null;
            gate.release(permit);
        }

        private JobCoordinator(
            String requestId,
            JSONObject request,
            JobValidator.RequestInfo requestInfo,
            TranslationConfig config,
            JSONObject savedProgress,
            JSONObject state,
            ContextSummaryCoordinator contextSummaryCoordinator
        ) {
            this.requestId = requestId;
            this.request = request;
            this.requestInfo = requestInfo;
            this.config = config;
            this.contextSummaryCoordinator = contextSummaryCoordinator;
            this.historyMapping = state == null
                ? JSONObject.NULL
                : state.opt(HistoryMapping.FIELD);
            ContextHistoryPreparer.HistoryPreparation historyPreparation =
                resolveHistoryContext(
                    requestId,
                    state,
                    requestInfo,
                    config
                );
            this.mappingResolution = HistoryMapping.resolution(state);
            this.historyBlockResolution = historyPreparation.resolution != null
                && !historyPreparation.resolution.isReady()
                ? historyPreparation.resolution
                : null;
            if (historyBlockResolution != null) {
                blockedStatus = historyBlockResolution.getStatus();
                blockedReason = historyBlockResolution.getReason();
            }
            this.contextId = historyPreparation.contextId;
            this.contextStorageName = historyPreparation.storageName;
            this.capturedSourceHashExcludingScene =
                historyPreparation.capturedSourceHashExcludingScene;
            this.requestContextSummary = historyPreparation.requestContextSummary;
            this.historyPayload = historyPreparation.payload;
            this.contextSummaryOptions = new ContextSummaryCoordinator.Options();
            this.contextSummaryOptions.autoCompression =
                config.isContextAutoCompressionEnabled();
            this.contextSummaryOptions.continueAfterManual =
                config.isContinueAutoSummaryAfterManual();
            this.validator = new TranslationResultValidator(
                request,
                requestInfo
            );

            streamingRepairEnabled = savedProgress == null
                ? config.isStreamingRepairEnabled()
                : savedProgress.optBoolean(
                    "streaming_repair_enabled",
                    config.isStreamingRepairEnabled()
                );
            useFullSceneForRepair = savedProgress == null
                ? config.shouldUseFullSceneForRepair()
                : savedProgress.optBoolean(
                    "use_full_scene_for_repair",
                    config.shouldUseFullSceneForRepair()
                );
            gradientCount = savedProgress == null
                ? config.getRepairGradientCount()
                : savedProgress.optInt(
                    "repair_gradient_count",
                    config.getRepairGradientCount()
                );

            blocks = TranslationGradientPlanner.plan(
                request,
                streamingRepairEnabled ? gradientCount : 1
            );
            closedBlocks = new boolean[blocks.size()];
            for (Integer seq : validator.getExpectedSeqs()) {
                items.put(seq, new ItemProgress(seq));
                repairAttemptsConsumed.put(seq, 0);
            }
            currentAttempt = new MainAttemptState(++attemptTokenCounter);
            if (savedProgress != null) {
                restore(savedProgress);
            }
        }

        private void run() throws Exception {
            try {
                synchronized (this) {
                    if (blockedStatus != null) {
                        return;
                    }
                    if (mainFinished) {
                        releaseMainPermit();
                        finishMainLocked();
                    }
                }

                if (!mainFinished) {
                    runMainStream();
                    synchronized (this) {
                        if (blockedStatus != null) {
                            return;
                        }
                        if (!terminal) {
                            mainFinished = true;
                            finishMainLocked();
                        }
                    }
                }

                synchronized (this) {
                    while (!terminal) {
                        if (blockedStatus != null) {
                            return;
                        }
                        evaluateTerminalLocked();
                        if (!terminal) {
                            wait();
                        }
                    }
                }
            } finally {
                releaseMainPermit();
            }
        }

        private void runMainStream() throws Exception {
            JSONObject requestScene = request;
            if (requestContextSummary) {
                requestScene = new JSONObject(request.toString());
                requestScene.put("request_context_summary", true);
            }
            PreparedApiRequest prepared = TranslationRequestFactory
                .buildMainRequest(
                    config,
                    requestScene,
                    historyPayload
                );
            PreflightResult preflightResult = preflight(
                mappingResolution,
                historyBlockResolution,
                prepared
            );
            if (preflightResult.isBlocked()) {
                applyPreflightBlock(preflightResult);
                releaseMainPermit();
                return;
            }
            JSONObject apiRequest = prepared.getProviderRequest();
            final String frozenBody = apiRequest.toString();
            int networkRetriesUsed = 0;
            try {
                while (true) {
                final MainAttemptState attempt;
                synchronized (this) {
                    currentAttempt.invalidate();
                    currentAttempt = new MainAttemptState(++attemptTokenCounter);
                    resetMainAttemptStateLocked();
                    attempt = currentAttempt;
                }
                final TranslationEventDecoder[] decoder =
                    new TranslationEventDecoder[1];
                try {
                    TranslationApiClient.streamTranslationAttempt(
                        config,
                        frozenBody,
                        new TranslationApiClient.StreamListener() {
                            @Override
                            public void onAttemptStarted(
                                int attemptNumber
                            ) {
                                synchronized (JobCoordinator.this) {
                                    mainAttemptOutput.setLength(0);
                                    mainOutputTruncated = false;
                                }
                                decoder[0] = new TranslationEventDecoder(
                                    false,
                                    requestContextSummary,
                                    new MainEventListener()
                                );
                                Log.i(
                                    TAG,
                                    "Main stream networkAttempt="
                                    + attemptNumber
                                        + " resultRestart="
                                        + mainResultRestarts
                                        + " requestId="
                                        + requestId
                                );
                            }

                            @Override
                            public void onTextDelta(String text)
                                throws Exception {
                                synchronized (JobCoordinator.this) {
                                    mainOutputTruncated |= appendLimited(
                                        mainAttemptOutput,
                                        text
                                    );
                                }
                                decoder[0].accept(text);
                            }

                            @Override
                            public void onStreamCompleted(
                                String stopReason
                            ) throws Exception {
                                decoder[0].finish();
                            }
                        },
                        networkRetriesUsed + 1
                    );
                    synchronized (this) {
                        if (currentAttempt != attempt
                            || attempt.token <= 0L) {
                            throw new IllegalStateException(
                                "main attempt was replaced before commit"
                            );
                        }
                        attempt.accepted = true;
                    }
                    return;
                } catch (Exception e) {
                    boolean retryResult = false;
                    synchronized (this) {
                        lastProviderError = providerFailureDetail(e);
                        attempt.invalidate();
                        if (isRetryableNetworkAttemptFailure(e)
                            && networkRetriesUsed
                                < config.getNetworkRetryCount()) {
                            networkRetriesUsed++;
                            TranslationApiClient.logNetworkRetry(
                                safeMessage(e),
                                networkRetriesUsed,
                                config.getNetworkRetryCount()
                            );
                            retryResult = true;
                        } else if (isRepairableMainResultFailure(e)
                            && canRestartMainResultLocked()) {
                            // A result restart is a new provider request. Its
                            // network retry budget is independent of the
                            // previous request's network attempts.
                            networkRetriesUsed = 0;
                            mainResultRestarts++;
                            if (!streamingRepairEnabled) {
                                sceneRepairRounds++;
                            }
                            checkpointLocked();
                            retryResult = true;
                            Log.w(
                                TAG,
                                "Retrying malformed main result requestId="
                                    + requestId
                                    + " resultRestart="
                                    + mainResultRestarts
                                    + "/"
                                    + config.getResultRepairCount(),
                                e
                            );
                        } else {
                            fatalProviderFailure =
                                isTransportFailure(e);
                            terminal = true;
                            failureMessage = fatalProviderFailure
                                ? "main stream exhausted network retries: "
                                    + safeMessage(e)
                                : "main stream result remained invalid after "
                                    + mainResultRestarts
                                    + " restart(s): "
                                    + safeMessage(e);
                            checkpointLocked();
                            notifyAll();
                            return;
                        }
                    }
                    if (!retryResult) {
                        return;
                    }
                    if (isRetryableNetworkAttemptFailure(e)) {
                        TranslationApiClient.waitBeforeRetry(
                            networkRetriesUsed
                        );
                    }
                }
                }
            } finally {
                // The permit covers the main provider request and its
                // synchronous network retries only.  Streaming repair may
                // now acquire the same gate, which is essential at limit=1.
                releaseMainPermit();
            }
        }

        private boolean canRestartMainResultLocked() {
            int used = streamingRepairEnabled
                ? mainResultRestarts
                : sceneRepairRounds;
            return used < config.getResultRepairCount();
        }

        private void resetMainAttemptStateLocked() {
            items.clear();
            for (Integer seq : validator.getExpectedSeqs()) {
                ItemProgress item = new ItemProgress(seq);
                item.repairAttempts = repairAttemptsConsumed.getOrDefault(
                    seq,
                    0
                );
                items.put(seq, item);
            }
            repairQueue.clear();
            carriedFailures.clear();
            activeRepairSeqs.clear();
            java.util.Arrays.fill(closedBlocks, false);
            highestMainSeqSeen = 0;
            repairRunning = false;
            fatalProviderFailure = false;
            failureMessage = "";
            lastProviderError = "";
            completionQuestPatch = null;
            mainAttemptOutput.setLength(0);
            repairAttemptOutput.setLength(0);
            mainOutputTruncated = false;
            repairOutputTruncated = false;
        }

        private final class MainEventListener
            implements TranslationEventDecoder.Listener {
            @Override
            public void onSummary(
                String incomingSummary,
                String incomingContextSummary,
                String invalidContextSummary
            ) throws Exception {
                synchronized (JobCoordinator.this) {
                    summary = incomingSummary;
                    if (incomingContextSummary != null) {
                        contextSummary = incomingContextSummary;
                    }
                    checkpointLocked();
                    // Body repairs are not eligible until this attempt has
                    // passed decoder.finish() and been accepted.
                    maybeStartRepairLocked();
                }
                if (contextSummaryCoordinator != null && contextId != null) {
                    try {
                        contextSummaryCoordinator.acceptFirstSummary(
                            requestId,
                            contextId,
                            requestInfo.getScene(),
                            requestInfo.getTargetLanguage(),
                            incomingSummary,
                            incomingContextSummary,
                            invalidContextSummary,
                            capturedSourceHashExcludingScene,
                            contextSummaryOptions
                        );
                    } catch (Exception e) {
                        Log.w(
                            TAG,
                            "Context Summary observation failed without "
                                + "blocking translation requestId="
                                + requestId,
                            e
                        );
                    }
                }
            }

            @Override
            public void onTranslation(int seq, String text)
                throws Exception {
                synchronized (JobCoordinator.this) {
                    if (!items.containsKey(seq)) {
                        Log.w(
                            TAG,
                            "Rejected unrequested main seq="
                                + seq
                                + " requestId="
                                + requestId
                        );
                        return;
                    }

                    if (seq > highestMainSeqSeen + 1) {
                        for (int missing = highestMainSeqSeen + 1;
                             missing < seq;
                             missing++) {
                            recordFailureLocked(
                                validator.missing(missing),
                                true
                            );
                        }
                    }
                    highestMainSeqSeen = Math.max(highestMainSeqSeen, seq);
                    applyValidationLocked(
                        validator.validate(seq, text),
                        true
                    );
                    closeReachedBlocksLocked();
                }
            }

            @Override
            public void onComplete() {
                // Provider termination is checked after the NDJSON decoder.
            }
        }

        private void finishMainLocked() throws Exception {
            for (Integer seq : validator.getExpectedSeqs()) {
                if (!items.get(seq).isFinalValid()
                    && items.get(seq).failure == null) {
                    recordFailureLocked(validator.missing(seq), true);
                }
            }
            for (int index = 0; index < blocks.size(); index++) {
                closeBlockLocked(index);
            }

            if (streamingRepairEnabled && currentAttempt.accepted) {
                emitChangedClosedBlockPatchesLocked();
            }

            if (!streamingRepairEnabled) {
                queueAllUnresolvedLocked();
            } else if (!carriedFailures.isEmpty()) {
                enqueueRepairLocked(carriedFailures);
                carriedFailures.clear();
            }
            checkpointLocked();
            maybeStartRepairLocked();
            evaluateTerminalLocked();
        }

        private void closeReachedBlocksLocked() throws Exception {
            for (int index = 0; index < blocks.size(); index++) {
                if (!closedBlocks[index]
                    && highestMainSeqSeen >= blocks.get(index).getLastSeq()) {
                    closeBlockLocked(index);
                }
            }
        }

        private void closeBlockLocked(int blockIndex) throws Exception {
            if (!currentAttempt.active) {
                return;
            }
            if (closedBlocks[blockIndex]) {
                return;
            }
            closedBlocks[blockIndex] = true;
            TranslationGradientPlanner.Block block = blocks.get(blockIndex);

            if (streamingRepairEnabled) {
                checkpointLocked();
                if (blockIndex == 0) {
                    enqueueRepairLocked(unresolvedInBlock(block));
                    if (currentAttempt.accepted
                        && isInitialBlockReadyLocked()) {
                        emitBlockPatchLocked(block);
                    }
                } else {
                    if (currentAttempt.accepted) {
                        emitBlockPatchLocked(block);
                    }
                    scheduleClosedGradientLocked(blockIndex);
                }
            }
        }

        private void scheduleClosedGradientLocked(int blockIndex) {
            TranslationGradientPlanner.Block block = blocks.get(blockIndex);
            LinkedHashSet<Integer> unresolved = unresolvedInBlock(block);
            if (!carriedFailures.isEmpty()) {
                carriedFailures.addAll(unresolved);
                enqueueRepairLocked(carriedFailures);
                carriedFailures.clear();
                return;
            }
            if (unresolved.isEmpty()) {
                return;
            }

            double errorRate =
                unresolved.size() / (double) Math.max(1, block.size());
            boolean hasNext = blockIndex + 1 < blocks.size();
            if (errorRate < MERGE_ERROR_RATE && hasNext) {
                carriedFailures.addAll(unresolved);
                Log.i(
                    TAG,
                    "Carried low-error gradient requestId="
                        + requestId
                        + " block="
                        + blockIndex
                        + " errorRate="
                        + errorRate
                );
            } else {
                enqueueRepairLocked(unresolved);
            }
        }

        private void applyValidationLocked(
            TranslationResultValidator.Result result,
            boolean fromMain
        ) throws Exception {
            ItemProgress item = items.get(result.getSeq());
            if (item == null || item.isFinalValid()) {
                return;
            }
            if (result.isFinalValid()) {
                item.status = ItemStatus.FINAL_VALID;
                item.finalText = result.getText();
                item.displayText = null;
                item.failure = null;
                repairQueue.remove(result.getSeq());
                carriedFailures.remove(result.getSeq());
            } else {
                recordFailureLocked(result, fromMain);
            }
        }

        private void recordFailureLocked(
            TranslationResultValidator.Result result,
            boolean fromMain
        ) throws Exception {
            ItemProgress item = items.get(result.getSeq());
            if (item == null || item.isFinalValid()) {
                return;
            }
            item.failure = result;
            if (result.isDisplayable()) {
                item.status = ItemStatus.PROVISIONAL;
                item.displayText = result.getText();
            } else {
                item.status = ItemStatus.BLOCKED;
                item.displayText = null;
            }

            // Streaming repairs are scheduled when a complete root block
            // closes. That keeps one malformed item from creating a tiny
            // request while the rest of the same block is still arriving.
        }

        private void enqueueRepairLocked(Iterable<Integer> seqs) {
            if (fatalProviderFailure) {
                return;
            }
            for (Integer seq : seqs) {
                ItemProgress item = items.get(seq);
                if (item == null || item.isFinalValid()) {
                    continue;
                }
                if (streamingRepairEnabled
                    && item.repairAttempts
                        >= config.getResultRepairCount()) {
                    continue;
                }
                repairQueue.add(seq);
            }
            maybeStartRepairLocked();
        }

        private void queueAllUnresolvedLocked() {
            for (ItemProgress item : items.values()) {
                if (!item.isFinalValid()) {
                    repairQueue.add(item.seq);
                }
            }
        }

        private void maybeStartRepairLocked() {
            if (shutdown
                || terminal
                || fatalProviderFailure
                || blockedStatus != null
                || !currentAttempt.active
                || repairRunning
                || summary == null
                || repairQueue.isEmpty()) {
                return;
            }

            LinkedHashSet<Integer> selected = new LinkedHashSet<>();
            if (streamingRepairEnabled) {
                for (Integer seq : new ArrayList<>(repairQueue)) {
                    ItemProgress item = items.get(seq);
                    repairQueue.remove(seq);
                    if (item != null
                        && !item.isFinalValid()
                        && item.repairAttempts
                            < config.getResultRepairCount()) {
                        selected.add(seq);
                    }
                }
            } else {
                if (sceneRepairRounds >= config.getResultRepairCount()) {
                    repairQueue.clear();
                    evaluateTerminalLocked();
                    return;
                }
                sceneRepairRounds++;
                for (Integer seq : new ArrayList<>(repairQueue)) {
                    repairQueue.remove(seq);
                    ItemProgress item = items.get(seq);
                    if (item != null && !item.isFinalValid()) {
                        selected.add(seq);
                    }
                }
            }

            if (selected.isEmpty()) {
                evaluateTerminalLocked();
                return;
            }
            repairRunning = true;
            activeRepairSeqs.clear();
            activeRepairSeqs.addAll(selected);
            MainAttemptState ownerAttempt = currentAttempt;
            try {
                checkpointLocked();
                for (Integer seq : selected) {
                    ItemProgress item = items.get(seq);
                    if (item != null) {
                        item.repairAttempts++;
                        repairAttemptsConsumed.put(
                            seq,
                            item.repairAttempts
                        );
                    }
                }
                repairExecutor.execute(
                    () -> runRepair(selected, ownerAttempt)
                );
            } catch (Exception e) {
                for (Integer seq : selected) {
                    ItemProgress item = items.get(seq);
                    if (item != null && item.repairAttempts > 0) {
                        item.repairAttempts--;
                        repairAttemptsConsumed.put(
                            seq,
                            item.repairAttempts
                        );
                    }
                }
                repairRunning = false;
                activeRepairSeqs.clear();
                for (Integer seq : selected) {
                    repairQueue.add(seq);
                }
                if (!shutdown) {
                    failureMessage =
                        "could not schedule repair: " + safeMessage(e);
                    evaluateTerminalLocked();
                }
            }
        }

        private void runRepair(
            Set<Integer> requestedSeqs,
            MainAttemptState ownerAttempt
        ) {
            Map<Integer, TranslationResultValidator.Result> expectedFailures =
                new LinkedHashMap<>();
            synchronized (this) {
                if (currentAttempt != ownerAttempt
                    || !ownerAttempt.active) {
                    Log.i(
                        TAG,
                        "Discarding stale repair result requestId="
                            + requestId
                            + " attemptToken="
                            + ownerAttempt.token
                    );
                    return;
                }
                for (Integer seq : requestedSeqs) {
                    ItemProgress item = items.get(seq);
                    expectedFailures.put(
                        seq,
                        item == null || item.failure == null
                            ? validator.missing(seq)
                            : item.failure
                    );
                }
            }

            Map<Integer, TranslationResultValidator.Result> returned =
                new LinkedHashMap<>();
            Exception requestFailure = null;
            String localProviderError = "";
            String localFailureMessage = "";
            boolean localFatalProviderFailure = false;
            StringBuilder localRepairOutput = new StringBuilder();
            boolean[] localRepairOutputTruncated = {false};
            try {
                PreparedApiRequest prepared =
                    TranslationRequestFactory.buildRepairRequest(
                        config,
                        request,
                        blocksForSeqs(requestedSeqs),
                        expectedFailures,
                        validator,
                        summary,
                        useFullSceneForRepair,
                        historyPayload
                    );
                PreflightResult preflightResult = preflight(
                    mappingResolution,
                    historyBlockResolution,
                    prepared
                );
                if (preflightResult.isBlocked()) {
                    synchronized (JobCoordinator.this) {
                        if (currentAttempt == ownerAttempt
                            && ownerAttempt.active) {
                            applyPreflightBlock(preflightResult);
                            repairRunning = false;
                            activeRepairSeqs.clear();
                            notifyAll();
                        }
                    }
                    return;
                }
                JSONObject apiRequest = prepared.getProviderRequest();
                final TranslationEventDecoder[] decoder =
                    new TranslationEventDecoder[1];
                TranslationApiClient.StreamListener repairListener =
                    new TranslationApiClient.StreamListener() {
                        @Override
                        public void onAttemptStarted(int attemptNumber) {
                            returned.clear();
                            localRepairOutput.setLength(0);
                            localRepairOutputTruncated[0] = false;
                            decoder[0] = new TranslationEventDecoder(
                                true,
                                false,
                                new TranslationEventDecoder.Listener() {
                                    @Override
                                    public void onSummary(
                                        String ignoredSummary,
                                        String ignoredContext,
                                        String ignoredInvalidContext
                                    ) {
                                    }

                                    @Override
                                    public void onTranslation(
                                        int seq,
                                        String text
                                    ) throws Exception {
                                        if (!requestedSeqs.contains(seq)) {
                                            throw new TranslationEventDecoder
                                                .ProtocolException(
                                                    "repair stream returned "
                                                        + "unrequested seq="
                                                        + seq
                                                );
                                        }
                                        returned.put(
                                            seq,
                                            validator.validate(seq, text)
                                        );
                                    }

                                    @Override
                                    public void onComplete() {
                                    }
                                }
                            );
                            Log.i(
                                TAG,
                                "Repair stream attempt="
                                    + attemptNumber
                                    + " requestId="
                                    + requestId
                                    + " seqs="
                                    + requestedSeqs
                            );
                        }

                        @Override
                        public void onTextDelta(String text)
                            throws Exception {
                            localRepairOutputTruncated[0] |= appendLimited(
                                localRepairOutput,
                                text
                            );
                            decoder[0].accept(text);
                        }

                        @Override
                        public void onStreamCompleted(String stopReason)
                            throws Exception {
                            decoder[0].finish();
                        }
                    };
                String frozenRepairBody = apiRequest.toString();
                int networkRetriesUsed = 0;
                ApiConcurrencyGate repairGate = apiGate;
                ApiConcurrencyGate.Permit repairPermit = null;
                try {
                    if (repairGate != null) {
                        repairPermit = repairGate.acquireTranslation();
                    }
                    while (true) {
                        try {
                            TranslationApiClient.streamTranslationAttempt(
                                config,
                                frozenRepairBody,
                                repairListener,
                                networkRetriesUsed + 1
                            );
                            break;
                        } catch (Exception e) {
                            if (!isRetryableNetworkAttemptFailure(e)
                                || networkRetriesUsed
                                    >= config.getNetworkRetryCount()) {
                                throw e;
                            }
                            networkRetriesUsed++;
                            TranslationApiClient.logNetworkRetry(
                                "repair: " + safeMessage(e),
                                networkRetriesUsed,
                                config.getNetworkRetryCount()
                            );
                            TranslationApiClient.waitBeforeRetry(
                                networkRetriesUsed
                            );
                        }
                    }
                } finally {
                    if (repairPermit != null && repairGate != null) {
                        repairGate.release(repairPermit);
                    }
                }
            } catch (InterruptedException e) {
                // Executor shutdown is a control-flow interruption, not a
                // provider protocol failure.  Restore the flag and release
                // this repair's ownership without manufacturing a retryable
                // API error.
                Thread.currentThread().interrupt();
                finishInterruptedRepair(requestedSeqs, ownerAttempt);
                return;
            } catch (Exception e) {
                requestFailure = e;
                localProviderError = providerFailureDetail(e);
                if (isTransportFailure(e)) {
                    localFatalProviderFailure = true;
                    localFailureMessage =
                        "repair request exhausted network retries: "
                            + safeMessage(e);
                }
            }

            synchronized (this) {
                if (currentAttempt != ownerAttempt
                    || !ownerAttempt.active) {
                    Log.i(
                        TAG,
                        "Discarding stale repair result before merge "
                            + "requestId="
                            + requestId
                            + " attemptToken="
                            + ownerAttempt.token
                    );
                    return;
                }
                lastProviderError = localProviderError;
                repairAttemptOutput.setLength(0);
                repairAttemptOutput.append(localRepairOutput);
                repairOutputTruncated = localRepairOutputTruncated[0];
                if (localFatalProviderFailure) {
                    fatalProviderFailure = true;
                    failureMessage = localFailureMessage;
                }
                for (Integer seq : requestedSeqs) {
                    TranslationResultValidator.Result result =
                        returned.get(seq);
                    if (result == null) {
                        result = requestFailure == null
                            ? validator.missing(seq)
                            : validator.protocolFailure(
                                seq,
                                safeMessage(requestFailure)
                            );
                    }
                    try {
                        applyValidationLocked(result, false);
                    } catch (Exception e) {
                        failureMessage =
                            "could not merge repair result: "
                                + safeMessage(e);
                    }
                }

                repairRunning = false;
                activeRepairSeqs.clear();
                if (fatalProviderFailure) {
                    repairQueue.clear();
                    carriedFailures.clear();
                } else if (streamingRepairEnabled) {
                    for (Integer seq : requestedSeqs) {
                        ItemProgress item = items.get(seq);
                        if (item != null
                            && !item.isFinalValid()
                            && item.repairAttempts
                                < config.getResultRepairCount()) {
                            repairQueue.add(seq);
                        }
                    }
                } else {
                    queueAllUnresolvedLocked();
                }

                try {
                    checkpointLocked();
                    if (streamingRepairEnabled) {
                        emitChangedClosedBlockPatchesLocked();
                    }
                } catch (Exception e) {
                    failureMessage =
                        "could not persist repaired progress: "
                            + safeMessage(e);
                }
                maybeStartRepairLocked();
                evaluateTerminalLocked();
                notifyAll();
            }
        }

        private void finishInterruptedRepair(
            Set<Integer> requestedSeqs,
            MainAttemptState ownerAttempt
        ) {
            synchronized (this) {
                if (currentAttempt != ownerAttempt || !ownerAttempt.active) {
                    return;
                }
                repairRunning = false;
                activeRepairSeqs.clear();
                if (!shutdown) {
                    repairQueue.addAll(requestedSeqs);
                    maybeStartRepairLocked();
                    evaluateTerminalLocked();
                }
                notifyAll();
            }
        }

        private void evaluateTerminalLocked() {
            if (terminal || blockedStatus != null || !mainFinished || repairRunning) {
                return;
            }
            if (fatalProviderFailure) {
                repairQueue.clear();
                carriedFailures.clear();
                terminal = true;
                successful = false;
                return;
            }
            repairQueue.removeIf(seq -> items.get(seq).isFinalValid());
            if (!repairQueue.isEmpty()) {
                maybeStartRepairLocked();
                return;
            }

            Set<Integer> unresolved = unresolvedSeqsLocked();
            if (unresolved.isEmpty()) {
                try {
                    if (!streamingRepairEnabled) {
                        completionQuestPatch =
                            buildCompleteQuestPatchLocked();
                    } else {
                        emitChangedClosedBlockPatchesLocked();
                    }
                    checkpointLocked();
                    successful = true;
                    terminal = true;
                } catch (Exception e) {
                    failureMessage =
                        "could not finalize translation: " + safeMessage(e);
                    terminal = true;
                }
                return;
            }

            boolean canRetry = false;
            if (streamingRepairEnabled) {
                for (Integer seq : unresolved) {
                    if (items.get(seq).repairAttempts
                        < config.getResultRepairCount()) {
                        repairQueue.add(seq);
                        canRetry = true;
                    }
                }
            } else if (sceneRepairRounds < config.getResultRepairCount()) {
                repairQueue.addAll(unresolved);
                canRetry = true;
            }
            if (canRetry) {
                maybeStartRepairLocked();
                return;
            }

            terminal = true;
            successful = false;
            if (failureMessage.isEmpty()) {
                failureMessage =
                    "translation validation failed for "
                        + unresolved.size()
                        + " seqs";
            }
        }

        private LinkedHashSet<Integer> unresolvedInBlock(
            TranslationGradientPlanner.Block block
        ) {
            LinkedHashSet<Integer> unresolved = new LinkedHashSet<>();
            for (Integer seq : block.getSeqs()) {
                if (!items.get(seq).isFinalValid()) {
                    unresolved.add(seq);
                }
            }
            return unresolved;
        }

        private Set<Integer> unresolvedSeqsLocked() {
            LinkedHashSet<Integer> unresolved = new LinkedHashSet<>();
            for (ItemProgress item : items.values()) {
                if (!item.isFinalValid()) {
                    unresolved.add(item.seq);
                }
            }
            return unresolved;
        }

        private List<TranslationGradientPlanner.Block> blocksForSeqs(
            Set<Integer> seqs
        ) {
            List<TranslationGradientPlanner.Block> selected =
                new ArrayList<>();
            for (TranslationGradientPlanner.Block block : blocks) {
                for (Integer seq : seqs) {
                    if (block.containsSeq(seq)) {
                        selected.add(block);
                        break;
                    }
                }
            }
            return selected;
        }

        private void emitChangedClosedBlockPatchesLocked()
            throws Exception {
            if (!currentAttempt.accepted) {
                return;
            }
            for (int index = 0; index < blocks.size(); index++) {
                if (closedBlocks[index]
                    && (index != 0 || isInitialBlockReadyLocked())) {
                    emitBlockPatchLocked(blocks.get(index));
                }
            }
        }

        private boolean isInitialBlockReadyLocked() {
            if (blocks.isEmpty() || !closedBlocks[0]) {
                return false;
            }
            for (Integer seq : blocks.get(0).getSeqs()) {
                ItemProgress item = items.get(seq);
                if (item == null || item.isFinalValid()) {
                    continue;
                }
                if (activeRepairSeqs.contains(seq)
                    || repairQueue.contains(seq)
                    || item.repairAttempts
                        < config.getResultRepairCount()) {
                    return false;
                }
            }
            return true;
        }

        private void emitBlockPatchLocked(
            TranslationGradientPlanner.Block block
        ) throws Exception {
            if (!currentAttempt.accepted) {
                return;
            }
            JSONArray updates = new JSONArray();
            List<ItemProgress> deliveredItems = new ArrayList<>();
            for (Integer seq : block.getSeqs()) {
                ItemProgress item = items.get(seq);
                if (!item.isDisplayable()) {
                    continue;
                }
                String text = item.currentDisplayText();
                boolean provisional =
                    item.status == ItemStatus.PROVISIONAL;
                if (text != null
                    && text.equals(item.deliveredText)
                    && provisional == item.deliveredProvisional) {
                    continue;
                }
                updates.put(new JSONObject()
                    .put("seq", seq)
                    .put("text", text)
                    .put("provisional", provisional));
                deliveredItems.add(item);
            }
            if (updates.length() == 0) {
                return;
            }

            patchVersion++;
            JSONObject patch = new JSONObject()
                .put("request_id", requestId)
                .put("patch_version", patchVersion)
                .put("block_index", block.getIndex())
                .put("updates", updates);

            for (ItemProgress item : deliveredItems) {
                item.deliveredText = item.currentDisplayText();
                item.deliveredProvisional =
                    item.status == ItemStatus.PROVISIONAL;
            }
            checkpointLocked();
            resultListener.onQuestPatch(requestId, patch);
        }

        private JSONObject buildCompleteQuestPatchLocked()
            throws Exception {
            JSONArray updates = new JSONArray();
            for (ItemProgress item : items.values()) {
                updates.put(new JSONObject()
                    .put("seq", item.seq)
                    .put("text", item.finalText)
                    .put("provisional", false));
                item.deliveredText = item.finalText;
                item.deliveredProvisional = false;
            }
            patchVersion++;
            JSONObject patch = new JSONObject()
                .put("request_id", requestId)
                .put("patch_version", patchVersion)
                .put("block_index", 0)
                .put("updates", updates);
            checkpointLocked();
            return patch;
        }

        private void checkpointLocked() throws Exception {
            JSONObject progress = new JSONObject()
                .put("version", 1)
                .put(
                    "streaming_repair_enabled",
                    streamingRepairEnabled
                )
                .put(
                    "use_full_scene_for_repair",
                    useFullSceneForRepair
                )
                .put("repair_gradient_count", gradientCount)
                .put("main_complete", mainFinished)
                .put("highest_main_seq", highestMainSeqSeen)
                .put("main_result_restarts", mainResultRestarts)
                .put("scene_repair_rounds", sceneRepairRounds)
                .put("patch_version", patchVersion);
            if (summary != null) {
                progress.put("summary", summary);
            }
            if (contextSummary != null) {
                progress.put("context_summary", contextSummary);
            }

            // During an unaccepted HTTP attempt only request-level data is
            // durable.  Body candidates, block closure, seq progress and
            // repair output belong to the attempt and are discarded on retry.
            if (!currentAttempt.accepted) {
                jobStore.writeProgress(requestId, progress);
                return;
            }

            JSONArray blockArray = new JSONArray();
            for (int index = 0; index < blocks.size(); index++) {
                TranslationGradientPlanner.Block block = blocks.get(index);
                blockArray.put(new JSONObject()
                    .put("index", index)
                    .put("first_seq", block.getFirstSeq())
                    .put("last_seq", block.getLastSeq())
                    .put("closed", closedBlocks[index]));
            }
            progress.put("blocks", blockArray);

            JSONArray itemArray = new JSONArray();
            for (ItemProgress item : items.values()) {
                JSONObject itemJson = new JSONObject()
                    .put("seq", item.seq)
                    .put("status", item.status.name().toLowerCase())
                    .put("repair_attempts", item.repairAttempts)
                    .put(
                        "delivered_provisional",
                        item.deliveredProvisional
                    );
                if (item.displayText != null) {
                    itemJson.put("display_text", item.displayText);
                }
                if (item.finalText != null) {
                    itemJson.put("final_text", item.finalText);
                }
                if (item.deliveredText != null) {
                    itemJson.put("delivered_text", item.deliveredText);
                }
                if (item.failure != null) {
                    itemJson.put("failure_reason", item.failure.getReason());
                }
                itemArray.put(itemJson);
            }
            progress.put("items", itemArray);
            jobStore.writeProgress(requestId, progress);
        }

        private void restore(JSONObject progress) {
            summary = nullableString(progress, "summary");
            contextSummary = nullableString(progress, "context_summary");
            mainFinished = progress.optBoolean("main_complete", false);
            currentAttempt.accepted = mainFinished;
            mainResultRestarts = progress.optInt(
                "main_result_restarts",
                0
            );
            sceneRepairRounds = progress.optInt("scene_repair_rounds", 0);
            patchVersion = progress.optLong("patch_version", 0L);
            if (!mainFinished) {
                // Ignore body candidates from an incomplete attempt. Only
                // request-level summaries and retry counters survive restart.
                return;
            }
            highestMainSeqSeen = progress.optInt("highest_main_seq", 0);

            JSONArray savedBlocks = progress.optJSONArray("blocks");
            if (savedBlocks != null) {
                for (int index = 0;
                     index < Math.min(savedBlocks.length(), blocks.size());
                     index++) {
                    JSONObject saved = savedBlocks.optJSONObject(index);
                    closedBlocks[index] = saved != null
                        && saved.optBoolean("closed", false);
                }
            }

            JSONArray savedItems = progress.optJSONArray("items");
            if (savedItems == null) {
                return;
            }
            for (int index = 0; index < savedItems.length(); index++) {
                JSONObject saved = savedItems.optJSONObject(index);
                if (saved == null) {
                    continue;
                }
                int seq = saved.optInt("seq", 0);
                ItemProgress item = items.get(seq);
                if (item == null) {
                    continue;
                }
                item.repairAttempts = Math.max(
                    0,
                    saved.optInt("repair_attempts", 0)
                );
                repairAttemptsConsumed.put(
                    seq,
                    Math.max(
                        repairAttemptsConsumed.getOrDefault(seq, 0),
                        item.repairAttempts
                    )
                );
                item.displayText = nullableString(saved, "display_text");
                item.finalText = nullableString(saved, "final_text");
                item.deliveredText = nullableString(
                    saved,
                    "delivered_text"
                );
                item.deliveredProvisional = saved.optBoolean(
                    "delivered_provisional",
                    false
                );
                if (item.finalText != null) {
                    TranslationResultValidator.Result validation =
                        validator.validate(seq, item.finalText);
                    if (validation.isFinalValid()) {
                        item.status = ItemStatus.FINAL_VALID;
                        continue;
                    }
                }
                if (item.displayText != null) {
                    TranslationResultValidator.Result validation =
                        validator.validate(seq, item.displayText);
                    item.failure = validation;
                    item.status = validation.isDisplayable()
                        ? ItemStatus.PROVISIONAL
                        : ItemStatus.BLOCKED;
                }
            }
        }

        private boolean isBlocked() {
            return blockedStatus != null;
        }

        private HistoryResolution.Status getBlockedStatus() {
            return blockedStatus;
        }

        private String getBlockedReason() {
            return blockedReason;
        }

        private void applyPreflightBlock(PreflightResult result) {
            synchronized (this) {
                if (blockedStatus != null) {
                    return;
                }
                blockedStatus = result.getDecision()
                    == SendDecision.WAITING
                        ? HistoryResolution.Status.WAITING
                        : HistoryResolution.Status.USER_ACTION_REQUIRED;
                blockedReason = result.getReason();
                notifyAll();
            }
        }

        private boolean isSuccessful() {
            return successful;
        }

        private String getFailureMessage() {
            return failureMessage;
        }

        private JSONObject buildFinalResult() throws Exception {
            Map<Integer, String> translations = new LinkedHashMap<>();
            for (ItemProgress item : items.values()) {
                if (!item.isFinalValid()) {
                    throw new IllegalStateException(
                        "cannot build final result with unresolved seq="
                            + item.seq
                    );
                }
                translations.put(item.seq, item.finalText);
            }
            return TranslationRequestFactory.buildFinalResult(
                config,
                requestInfo.getTargetLanguage(),
                summary,
                contextSummary,
                translations
            );
        }

        private JSONObject buildFailure() throws Exception {
            return errorObject(
                fatalProviderFailure ? "network" : "validation",
                failureMessage,
                unresolvedSeqsLocked()
            );
        }

        private synchronized JSONObject buildDebugDump(JSONObject error)
            throws Exception {
            JSONArray unresolved = new JSONArray();
            for (Integer seq : unresolvedSeqsLocked()) {
                unresolved.put(seq);
            }
            return new JSONObject()
                .put("request_id", requestId)
                .put("scene", requestInfo.getScene())
                .put("target_lang", requestInfo.getTargetLanguage())
                .put("error", error)
                .put("unresolved_seqs", unresolved)
                .put("main_output", mainAttemptOutput.toString())
                .put("main_output_truncated", mainOutputTruncated)
                .put("repair_output", repairAttemptOutput.toString())
                .put("repair_output_truncated", repairOutputTruncated)
                .put("provider_error", lastProviderError)
                .put("saved_at", System.currentTimeMillis());
        }

        private void deliverCompletionQuestPatch() {
            JSONObject patch;
            synchronized (this) {
                patch = completionQuestPatch;
                completionQuestPatch = null;
            }
            if (patch != null) {
                resultListener.onQuestPatch(requestId, patch);
            }
        }
    }

    private static JSONObject errorObject(
        String type,
        String message,
        Set<Integer> unresolved
    ) throws Exception {
        JSONArray seqs = new JSONArray();
        for (Integer seq : unresolved) {
            seqs.put(seq);
        }
        return new JSONObject()
            .put(
                "error",
                new JSONObject()
                    .put("type", type)
                    .put("status", 0)
                    .put("message", truncate(message, 4096))
            )
            .put("unresolved_seqs", seqs);
    }

    private void dumpFailedApiResponse(
        String scene,
        JSONObject debugDump
    ) {
        try {
            File failedDirectory = new File(
                new File(
                    context.getFilesDir(),
                    SceneStore.DIRECTORY_NAME
                ),
                "failed"
            );
            IoUtils.ensureDirectory(failedDirectory);
            File output = new File(
                failedDirectory,
                "api_failed_" + SceneStore.fileNameForScene(scene)
            );
            byte[] bytes = (debugDump.toString(2) + "\n").getBytes(
                StandardCharsets.UTF_8
            );
            IoUtils.writeAtomically(output, bytes);
            Log.i(
                TAG,
                "Failed API response saved path="
                    + output.getAbsolutePath()
                    + " bytes="
                    + bytes.length
            );
        } catch (Exception e) {
            Log.e(
                TAG,
                "Could not save failed API response scene=" + scene,
                e
            );
        }
    }

    private static boolean appendLimited(
        StringBuilder target,
        String value
    ) {
        if (value == null || value.isEmpty()) {
            return false;
        }
        int available = MAX_CAPTURED_PROVIDER_CHARS - target.length();
        if (available <= 0) {
            return true;
        }
        if (value.length() <= available) {
            target.append(value);
            return false;
        }
        target.append(value, 0, available);
        return true;
    }

    private static String providerFailureDetail(Throwable error) {
        if (error instanceof TranslationApiClient.HttpStatusException) {
            TranslationApiClient.HttpStatusException statusError =
                (TranslationApiClient.HttpStatusException) error;
            return "HTTP "
                + statusError.getStatusCode()
                + ": "
                + truncate(statusError.getResponseBody(), 64 * 1024);
        }
        return error.getClass().getSimpleName()
            + ": "
            + truncate(safeMessage(error), 64 * 1024);
    }

    private static boolean isRetryableNetworkAttemptFailure(
        Throwable error
    ) {
        if (error instanceof TranslationApiClient.ListenerFailure) {
            return false;
        }
        if (error instanceof TranslationApiClient.HttpStatusException) {
            return TranslationApiClient.isRetryableHttpStatus(
                ((TranslationApiClient.HttpStatusException) error)
                    .getStatusCode()
            );
        }
        return error instanceof IOException
            && TranslationApiClient.isRetryableNetworkException(error);
    }

    private static boolean isRepairableMainResultFailure(
        Throwable error
    ) {
        Throwable current = error;
        while (current != null) {
            if (current instanceof InterruptedException
                || current instanceof IOException) {
                return false;
            }
            current = current.getCause();
        }
        return true;
    }

    private static boolean isTransportFailure(Throwable error) {
        if (error instanceof TranslationApiClient.ListenerFailure) {
            return false;
        }
        Throwable current = error;
        while (current != null) {
            if (current instanceof InterruptedException
                || current instanceof IOException) {
                return true;
            }
            current = current.getCause();
        }
        return false;
    }

    private static String nullableString(JSONObject object, String key) {
        Object value = object.opt(key);
        return value instanceof String ? (String) value : null;
    }

    private static String safeMessage(Throwable error) {
        String message = error.getMessage();
        return message == null || message.trim().isEmpty()
            ? error.getClass().getSimpleName()
            : message;
    }

    private static String truncate(String value, int maxLength) {
        if (value == null) {
            return "";
        }
        return value.length() <= maxLength
            ? value
            : value.substring(0, maxLength);
    }
}
