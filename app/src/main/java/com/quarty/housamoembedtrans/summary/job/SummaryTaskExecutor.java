package com.quarty.housamoembedtrans.summary.job;
import com.quarty.housamoembedtrans.provider.ApiConcurrencyGate;
import com.quarty.housamoembedtrans.provider.TranslationApiClient;
import com.quarty.housamoembedtrans.provider.TranslationConfig;
import com.quarty.housamoembedtrans.summary.request.SummaryRequestAssembler;

import com.quarty.housamoembedtrans.context.model.GroupContextEntry;

import com.quarty.housamoembedtrans.runtime.TranslationStatusNotification;
import com.quarty.housamoembedtrans.storage.config.ConfigStore;
import com.quarty.housamoembedtrans.context.history.ContextContentHash;
import com.quarty.housamoembedtrans.provider.RejectedApiResultStore;
import com.quarty.housamoembedtrans.context.store.SceneContextStore;
import com.quarty.housamoembedtrans.summary.request.SummaryResultValidator;
import com.quarty.housamoembedtrans.context.store.SummaryTargetInvalidatedException;
import com.quarty.housamoembedtrans.util.IoUtils;

import android.content.Context;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Executes the Summary Request one-shot chain in the HET process.
 *
 * <p>It claims ready jobs from {@link SummaryJobStore}, reassembles the current
 * semantic input, freezes a non-streaming provider request, validates the
 * response against {@code summary_result_schema.json}, atomically writes the
 * derived summary record, and then removes the completed job. Network and
 * result-validation retries share the Context/Group-specific retry count;
 * non-HTTP/assembly/config errors fail immediately. Every failed job is
 * notified at most once.</p>
 */
public final class SummaryTaskExecutor {
    private static final String TAG = "HET.SummaryExecutor";
    private static final int MAX_ASSET_BYTES = 256 * 1024;

    private static final class TargetInvalidatedException extends Exception {
        private TargetInvalidatedException(String message) {
            super(message);
        }

        private TargetInvalidatedException(String message, Throwable cause) {
            super(message, cause);
        }
    }

    private static final class WritebackDecision {
        private final boolean manualSuppressed;
        private final String currentSourceHash;

        private WritebackDecision(
            boolean manualSuppressed,
            String currentSourceHash
        ) {
            this.manualSuppressed = manualSuppressed;
            this.currentSourceHash = currentSourceHash;
        }

        private static WritebackDecision manual() {
            return new WritebackDecision(true, null);
        }

        private static WritebackDecision written(String sourceHash) {
            return new WritebackDecision(false, sourceHash);
        }

        private static WritebackDecision stale(String sourceHash) {
            return new WritebackDecision(false, sourceHash);
        }
    }

    /**
     * Immutable pre-send snapshot.  Target membership, the current source
     * hash, already-written-back state, and the complete provider input are
     * captured while the Scene root gate is held, so Review cannot edit one
     * fact between the validation/hash/input stages.
     */
    private static final class PreparedFacts {
        private final boolean alreadyWrittenBack;
        private final String currentSourceHash;
        private final JSONObject summaryInput;

        private PreparedFacts(
            boolean alreadyWrittenBack,
            String currentSourceHash,
            JSONObject summaryInput
        ) {
            this.alreadyWrittenBack = alreadyWrittenBack;
            this.currentSourceHash = currentSourceHash;
            this.summaryInput = summaryInput;
        }
    }

    /** Read/write boundary for Context/Group derived summary slots. */
    public interface ContextGateway {
        JSONObject getContext(String contextId) throws Exception;

        JSONObject getGroup(String groupId) throws Exception;

        void writeContextSummary(
            String contextId,
            String requestKind,
            String targetLang,
            String cutoff,
            String text,
            String sourceHash
        ) throws Exception;

        void writeGroupSummary(
            String groupId,
            String targetLang,
            String cutoff,
            String text,
            String sourceHash
        ) throws Exception;
    }

    /** Optional downstream notification after a Context Final Summary write. */
    public interface ContextFinalWrittenListener {
        void onContextFinalWritten(String contextId, String targetLang);
    }

    /** Loads the immutable execution snapshot used by one drain pass. */
    public interface SnapshotLoader {
        Snapshot load() throws Exception;
    }

    /** Non-streaming provider transport boundary. */
    public interface Transport {
        JSONObject send(TranslationConfig config, String requestBody)
            throws Exception;
    }

    /** One-shot user-visible failure notification boundary. */
    public interface ErrorNotifier {
        void onSummaryFailed(
            String requestId,
            String ownerType,
            String ownerId,
            String message
        );
    }

    /** Archives and notifies API results that lost write-back eligibility. */
    public interface RejectedResultSink {
        void archiveRejected(
            String requestId,
            String reason,
            String kind,
            Object payload
        ) throws Exception;
    }

    /** Frozen execution settings for a Summary job. */
    public static final class Snapshot {
        public final TranslationConfig config;
        public final String summaryPrompt;
        public final JSONObject summarySchema;
        public final int contextRetryCount;
        public final int groupRetryCount;
        public final boolean continueAfterManual;

        public Snapshot(
            TranslationConfig config,
            String summaryPrompt,
            JSONObject summarySchema,
            int contextRetryCount,
            int groupRetryCount
        ) {
            this(
                config,
                summaryPrompt,
                summarySchema,
                contextRetryCount,
                groupRetryCount,
                false
            );
        }

        public Snapshot(
            TranslationConfig config,
            String summaryPrompt,
            JSONObject summarySchema,
            int contextRetryCount,
            int groupRetryCount,
            boolean continueAfterManual
        ) {
            if (config == null || summaryPrompt == null || summarySchema == null) {
                throw new IllegalArgumentException(
                    "config, summaryPrompt and summarySchema are required"
                );
            }
            this.config = config;
            this.summaryPrompt = summaryPrompt;
            this.summarySchema = summarySchema;
            this.contextRetryCount = contextRetryCount;
            this.groupRetryCount = groupRetryCount;
            this.continueAfterManual = continueAfterManual;
        }
    }

    private final SummaryJobStore store;
    private final ContextGateway gateway;
    private final SnapshotLoader snapshotLoader;
    private final Transport transport;
    private final ErrorNotifier errorNotifier;
    private final RejectedResultSink rejectedSink;
    private final ExecutorService workers;
    private final AtomicBoolean drainScheduled = new AtomicBoolean();
    private final int maxWorkers;
    private ApiConcurrencyGate apiGate;
    private volatile boolean shutdown;
    private volatile ContextFinalWrittenListener contextFinalWrittenListener =
        (contextId, targetLang) -> { };
    private volatile Runnable drainFinishedListener = () -> { };

    public SummaryTaskExecutor(
        SummaryJobStore store,
        ContextGateway gateway,
        SnapshotLoader snapshotLoader,
        Transport transport,
        ErrorNotifier errorNotifier,
        int maxWorkers
    ) {
        this(
            store,
            gateway,
            snapshotLoader,
            transport,
            errorNotifier,
            maxWorkers,
            new ApiConcurrencyGate(maxWorkers),
            null
        );
    }

    public SummaryTaskExecutor(
        SummaryJobStore store,
        ContextGateway gateway,
        SnapshotLoader snapshotLoader,
        Transport transport,
        ErrorNotifier errorNotifier,
        int maxWorkers,
        ApiConcurrencyGate apiGate
    ) {
        this(
            store,
            gateway,
            snapshotLoader,
            transport,
            errorNotifier,
            maxWorkers,
            apiGate,
            null
        );
    }

    public SummaryTaskExecutor(
        SummaryJobStore store,
        ContextGateway gateway,
        SnapshotLoader snapshotLoader,
        Transport transport,
        ErrorNotifier errorNotifier,
        int maxWorkers,
        ApiConcurrencyGate apiGate,
        RejectedResultSink rejectedSink
    ) {
        if (store == null
            || gateway == null
            || snapshotLoader == null
            || transport == null
            || errorNotifier == null) {
            throw new IllegalArgumentException(
                "store, gateway, snapshotLoader, transport and notifier are required"
            );
        }
        this.store = store;
        this.gateway = gateway;
        this.snapshotLoader = snapshotLoader;
        this.transport = transport;
        this.errorNotifier = errorNotifier;
        this.rejectedSink = rejectedSink;
        this.maxWorkers = Math.max(1, maxWorkers);
        this.apiGate = apiGate == null ? new ApiConcurrencyGate(1) : apiGate;
        this.workers = Executors.newFixedThreadPool(
            this.maxWorkers,
            runnable -> {
                Thread thread = new Thread(runnable, "HET-summary-worker");
                thread.setDaemon(true);
                return thread;
            }
        );
    }

    public static SummaryTaskExecutor createForAndroid(
        Context context,
        SummaryJobStore summaryJobStore
    ) {
        SceneContextStore sceneContextStore = new SceneContextStore(context);
        ContextGateway gateway = new ContextGateway() {
            @Override
            public JSONObject getContext(String contextId) throws Exception {
                return SceneContextStore.withRootAccess(
                    () -> sceneContextStore.getContext(contextId)
                );
            }

            @Override
            public JSONObject getGroup(String groupId) throws Exception {
                return SceneContextStore.withRootAccess(
                    () -> sceneContextStore.getGroup(groupId)
                );
            }

            @Override
            public void writeContextSummary(
                String contextId,
                String requestKind,
                String targetLang,
                String cutoff,
                String text,
                String sourceHash
            ) throws Exception {
                SceneContextStore.withRootAccess(() -> {
                    JSONObject context;
                    try {
                        context = sceneContextStore.getContext(contextId);
                    } catch (SceneContextStore.StorageException e) {
                        if (e.kind == SceneContextStore.FailureKind.NOT_FOUND) {
                            throw new SummaryTargetInvalidatedException(
                                "context target was deleted: " + contextId,
                                e
                            );
                        }
                        throw e;
                    }
                    String storageName = context.optString("storage_name", "");
                    if ("context_snapshot".equals(requestKind)) {
                        sceneContextStore.getContextStore()
                            .writeCurrentContextSummaryAtCutoff(
                                storageName,
                                targetLang,
                                cutoff,
                                text,
                                sourceHash
                            );
                    } else if ("context_final".equals(requestKind)) {
                        sceneContextStore.getContextStore().writeFinalSummary(
                            storageName,
                            targetLang,
                            text,
                            sourceHash
                        );
                    } else {
                        throw new IllegalArgumentException(
                            "unsupported context summary request_kind: "
                                + requestKind
                        );
                    }
                    return null;
                });
            }

            @Override
            public void writeGroupSummary(
                String groupId,
                String targetLang,
                String cutoff,
                String text,
                String sourceHash
            ) throws Exception {
                SceneContextStore.withRootAccess(() -> {
                    JSONObject group;
                    try {
                        group = sceneContextStore.getGroup(groupId);
                    } catch (SceneContextStore.StorageException e) {
                        if (e.kind == SceneContextStore.FailureKind.NOT_FOUND) {
                            throw new SummaryTargetInvalidatedException(
                                "group target was deleted: " + groupId,
                                e
                            );
                        }
                        throw e;
                    }
                    sceneContextStore.getGroupStore().writeCurrentGroupSummary(
                        group.optString("storage_name", ""),
                        targetLang,
                        cutoff,
                        text,
                        sourceHash
                    );
                    return null;
                });
            }
        };
        SnapshotLoader snapshotLoader = () -> loadSnapshot(context);
        Transport transport = TranslationApiClient::sendSummaryRequest;
        ErrorNotifier notifier = (requestId, ownerType, ownerId, message) -> {
            logWarn(
                "Summary job failed requestId="
                    + requestId
                    + " ownerType="
                    + ownerType
                    + " ownerId="
                    + ownerId
                    + " message="
                    + message
            );
            TranslationStatusNotification.summaryFailed(
                context,
                requestId,
                ownerType,
                ownerId,
                message
            );
            TranslationStatusNotification.refresh(context);
        };
        RejectedApiResultStore rejectedStore = RejectedApiResultStore.createForAndroid(
            new File(context.getFilesDir(), RejectedApiResultStore.DIRECTORY_NAME)
        );
        RejectedResultSink rejectedSink = (requestId, reason, kind, payload) -> {
            JSONObject record = rejectedStore.archive(
                "summary",
                requestId,
                reason,
                kind,
                payload
            );
            try {
                TranslationStatusNotification.rejectedApiResultArchived(
                    context,
                    record
                );
                TranslationStatusNotification.refresh(context);
            } catch (RuntimeException notificationFailure) {
                logWarn(
                    "Rejected API result notification failed requestId="
                        + requestId,
                    notificationFailure
                );
            }
            return;
        };
        int workerCount = readSummaryWorkerCount(context);
        return new SummaryTaskExecutor(
            summaryJobStore,
            gateway,
            snapshotLoader,
            transport,
            notifier,
            workerCount,
            new ApiConcurrencyGate(workerCount),
            rejectedSink
        );
    }

    public static Snapshot loadSnapshot(Context context) throws Exception {
        ConfigStore configStore = new ConfigStore(context);
        JSONObject userSettings = configStore.load().config.getJSONObject(
            "UserSettings"
        );
        ConfigStore.SummaryRetryCounts retryCounts =
            ConfigStore.getSummaryRetryCounts(userSettings);
        JSONObject contextHistory = userSettings.optJSONObject("ContextHistory");
        boolean continueAfterManual = contextHistory != null
            && contextHistory.optBoolean("ContinueAutoSummaryAfterManual", false);
        TranslationConfig config = TranslationConfig.load(context);
        String summaryPrompt = readAsset(
            context,
            SummaryRequestAssembler.SUMMARY_PROMPT_ASSET
        );
        JSONObject summarySchema = readAssetJson(
            context,
            SummaryRequestAssembler.SUMMARY_RESULT_SCHEMA_ASSET
        );
        return new Snapshot(
            config,
            summaryPrompt,
            summarySchema,
            retryCounts.context,
            retryCounts.group,
            continueAfterManual
        );
    }

    public void scheduleDrain() {
        if (shutdown || !drainScheduled.compareAndSet(false, true)) {
            return;
        }
        for (int index = 0; index < maxWorkers; index++) {
            try {
                workers.execute(this::drainLoop);
            } catch (RejectedExecutionException e) {
                drainScheduled.set(false);
                if (!shutdown) {
                    logError("Could not schedule summary drain", e);
                }
                return;
            }
        }
    }

    /** Replaces the internally created gate with the Service-wide shared gate. */
    public void setApiConcurrencyGate(ApiConcurrencyGate gate) {
        if (gate == null) {
            throw new IllegalArgumentException("gate is required");
        }
        this.apiGate = gate;
    }

    public void setContextFinalWrittenListener(
        ContextFinalWrittenListener listener
    ) {
        if (listener == null) {
            throw new IllegalArgumentException("listener is required");
        }
        this.contextFinalWrittenListener = listener;
    }

    /** Called after a drain observes no queued Summary Job. */
    public void setDrainFinishedListener(Runnable listener) {
        if (listener == null) {
            throw new IllegalArgumentException("listener is required");
        }
        drainFinishedListener = listener;
    }

    public void shutdown() {
        shutdown = true;
        workers.shutdownNow();
    }

    private void drainLoop() {
        try {
            while (!shutdown) {
                ApiConcurrencyGate gate = apiGate;
                ApiConcurrencyGate.Permit permit = null;
                String requestId = null;
                try {
                    permit = gate.acquireSummary();
                    requestId = SceneContextStore.withRootAccess(
                        store::claimNextReadyJob
                    );
                    if (requestId == null) {
                        break;
                    }
                    // Configuration is intentionally read per claimed job, not
                    // once per drain pass, so setting changes are honored as
                    // soon as the next job is claimed.
                    Snapshot snapshot;
                    try {
                        snapshot = snapshotLoader.load();
                    } catch (Exception snapshotFailure) {
                        // claimNextReadyJob has already made this job running;
                        // do not leave it stranded merely because config or an
                        // asset could not be loaded.  fail() also performs the
                        // one-shot user notification.
                        JSONObject request = null;
                        try {
                            request = store.readRequest(requestId);
                        } catch (Exception requestFailure) {
                            logWarn(
                                "Could not read Summary request after loader "
                                    + "failure requestId=" + requestId,
                                requestFailure
                            );
                        }
                        fail(requestId, request, null, snapshotFailure);
                        continue;
                    }
                    executeJob(requestId, snapshot);
                } finally {
                    if (permit != null) {
                        gate.release(permit);
                    }
                }
            }
        } catch (Exception e) {
            logError("Summary drain stopped unexpectedly", e);
        } finally {
            drainScheduled.set(false);
            boolean hasPending;
            try {
                hasPending = !shutdown && store.hasPendingJobs();
            } catch (Exception pendingFailure) {
                logWarn(
                    "Could not re-check pending Summary jobs after drain",
                    pendingFailure
                );
                hasPending = false;
            }
            if (hasPending) {
                scheduleDrain();
            } else {
                try {
                    drainFinishedListener.run();
                } catch (RuntimeException listenerFailure) {
                    logWarn(
                        "Summary drain-finished listener failed",
                        listenerFailure
                    );
                }
            }
        }
    }

    /** Runs one claimed Summary job. Package-private for host tests. */
    void executeJob(String requestId, Snapshot snapshot) {
        JSONObject request = null;
        try {
            store.markRunning(requestId);
            request = store.readRequest(requestId);
            final JSONObject requestSnapshot = request;
            PreparedFacts preparedFacts;
            try {
                preparedFacts = prepareFactsForSend(requestSnapshot);
            } catch (TargetInvalidatedException invalidated) {
                discardInvalidatedJob(requestId, request, invalidated);
                return;
            }
            if (preparedFacts.alreadyWrittenBack) {
                boolean rerunRequired = store.isRerunRequired(requestId);
                boolean userRequested = store.isUserRequested(requestId);
                try {
                    store.removeCompletedJob(requestId);
                } catch (Exception cleanupFailure) {
                    logWarn(
                        "Summary result already written but job cleanup "
                            + "failed requestId=" + requestId,
                        cleanupFailure
                    );
                }
                if (rerunRequired) {
                    handleRerunAfterTermination(
                        requestId,
                        request,
                        snapshot,
                        true,
                        userRequested
                    );
                }
                return;
            }

            String currentSourceHash = preparedFacts.currentSourceHash;
            if (!currentSourceHash.equals(request.optString("source_hash", ""))) {
                // The durable request describes an older input. Do not send it;
                // remove the stale claimed job and create one successor for the
                // latest facts.
                boolean userRequested = store.isUserRequested(requestId);
                removeStaleClaimedJob(requestId, request);
                createSuccessorForCurrentFacts(
                    request,
                    currentSourceHash,
                    userRequested
                );
                logWarn(
                    "Summary job source_hash changed before send requestId="
                        + requestId
                        + " successor_source_hash="
                        + currentSourceHash
                );
                return;
            }

            JSONObject summaryInput = preparedFacts.summaryInput;
            SummaryRequestAssembler.SummaryPreparedRequest prepared =
                SummaryRequestAssembler.assemble(
                    snapshot.config,
                    snapshot.summaryPrompt,
                    request.optString("request_kind", ""),
                    request.optString("target_lang", ""),
                    summaryInput
                );
            if (!prepared.isWithinContextLength()) {
                fail(
                    requestId,
                    request,
                    snapshot,
                    new IllegalArgumentException(
                        "summary input exceeds context_length="
                            + prepared.getContextLength()
                            + " estimated_tokens="
                            + prepared.getEstimatedTokenCount()
                    )
                );
                return;
            }

            int retryLimit = retryLimit(request, snapshot);
            int attemptsUsed = 0;
            String frozenBody = prepared.getProviderRequest().toString();
            while (true) {
                String providerSummary = null;
                try {
                    JSONObject providerResponse = transport.send(
                        snapshot.config,
                        frozenBody
                    );
                    providerSummary = SummaryResultValidator
                        .fromSchema(snapshot.summarySchema)
                        .validateAndExtract(
                            providerResponse,
                            snapshot.config.getProtocol()
                        );
                    WritebackDecision writebackDecision =
                        writeBackIfFactsStillMatch(
                            request,
                            providerSummary,
                            snapshot
                        );
                    if (writebackDecision.manualSuppressed) {
                        archiveRejected(
                            requestId,
                            "manual_summary_active",
                            "legal",
                            new JSONObject().put(
                                "summary",
                                providerSummary
                            )
                        );
                        // The request itself ended normally; it simply lost
                        // write-back eligibility. Manual suppression also means
                        // no automatic successor should be created.
                        try {
                            store.removeCompletedJob(requestId);
                        } catch (Exception cleanupFailure) {
                            logWarn(
                                "Summary result archived but job cleanup failed "
                                    + "requestId=" + requestId,
                                cleanupFailure
                            );
                        }
                        return;
                    }
                    if (!request.optString("source_hash", "").equals(
                        writebackDecision.currentSourceHash
                    )) {
                        boolean userRequested = store.isUserRequested(requestId);
                        archiveRejected(
                            requestId,
                            "context_changed",
                            "legal",
                            new JSONObject()
                                .put("summary", providerSummary)
                                .put(
                                    "current_source_hash",
                                    writebackDecision.currentSourceHash
                                )
                        );
                        removeStaleClaimedJob(requestId, request);
                        createSuccessorForCurrentFacts(
                            request,
                            writebackDecision.currentSourceHash,
                            userRequested
                        );
                        return;
                    }
                    boolean rerunRequired = store.isRerunRequired(requestId);
                    boolean userRequested = store.isUserRequested(requestId);
                    try {
                        store.removeCompletedJob(requestId);
                    } catch (Exception cleanupFailure) {
                        // The derived record is already durable. The leftover
                        // directory is converged on the next claim by
                        // removeIfSourceHashMatches/isAlreadyWrittenBack.
                        logWarn(
                            "Summary result written but job cleanup failed "
                                + "requestId="
                                + requestId,
                            cleanupFailure
                        );
                    }
                    if (rerunRequired) {
                        handleRerunAfterTermination(
                            requestId,
                            request,
                            snapshot,
                            true,
                            userRequested
                        );
                    }
                    return;
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw e;
                } catch (Exception e) {
                    if (isTargetInvalidation(e)) {
                        if (providerSummary != null) {
                            archiveInvalidatedResult(
                                requestId,
                                providerSummary,
                                e
                            );
                        }
                        discardInvalidatedJob(requestId, request, e);
                        return;
                    }
                    if (isRetryableSummaryFailure(e)
                        && attemptsUsed < retryLimit) {
                        attemptsUsed++;
                        logRetry(
                            safeMessage(e),
                            attemptsUsed,
                            retryLimit
                        );
                        TranslationApiClient.waitBeforeRetry(attemptsUsed);
                    } else {
                        fail(requestId, request, snapshot, e);
                        handleRerunAfterTermination(
                            requestId,
                            request,
                            snapshot,
                            false,
                            false
                        );
                        return;
                    }
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (Exception e) {
            if (isTargetInvalidation(e)) {
                discardInvalidatedJob(requestId, request, e);
                return;
            }
            logError("Summary task crashed requestId=" + requestId, e);
            try {
                if (request == null) {
                    request = store.readRequest(requestId);
                }
                fail(
                    requestId,
                    request,
                    snapshot,
                    e
                );
                if (request != null) {
                    handleRerunAfterTermination(
                        requestId,
                        request,
                        snapshot,
                        false,
                        false
                    );
                }
            } catch (Exception ignored) {
                // The store may already be gone; keep the original failure.
            }
        }
    }

    private PreparedFacts prepareFactsForSend(JSONObject request)
        throws Exception {
        return SceneContextStore.withRootAccess(() -> {
            validateTargetBeforeSend(request);
            String currentSourceHash = recomputeSourceHash(request);
            if (!currentSourceHash.equals(
                request.optString("source_hash", "")
            )) {
                return new PreparedFacts(false, currentSourceHash, null);
            }
            // A leftover directory is converged as completed only when the
            // current facts still match the request. Recomputing first avoids
            // treating an old write-back as success after the entity changed
            // before the crash-window cleanup.
            if (isAlreadyWrittenBack(request)) {
                return new PreparedFacts(true, currentSourceHash, null);
            }
            return new PreparedFacts(
                false,
                currentSourceHash,
                buildInput(request)
            );
        });
    }

    private void validateTargetBeforeSend(JSONObject request)
        throws Exception {
        if (request == null) {
            throw new TargetInvalidatedException("summary request is missing");
        }
        String ownerType = request.optString("owner_type", "");
        String ownerId = request.optString("owner_id", "");
        String requestKind = request.optString("request_kind", "");
        String cutoff = request.optString("cutoff", "");
        try {
            if ("context".equals(ownerType)) {
                JSONObject context = gateway.getContext(ownerId);
                if ("context_snapshot".equals(requestKind)
                    && !containsContextEntryId(context, cutoff)) {
                    throw new TargetInvalidatedException(
                        "context cutoff is no longer present: " + cutoff
                    );
                }
                return;
            }
            if ("group".equals(ownerType)) {
                JSONObject group = gateway.getGroup(ownerId);
                if (!containsGroupContextEntryId(group, cutoff)) {
                    throw new TargetInvalidatedException(
                        "group cutoff is no longer present: " + cutoff
                    );
                }
                JSONArray contexts = group.optJSONArray("contexts");
                if (contexts != null) {
                    for (int index = 0; index < contexts.length(); index++) {
                        String contextId = GroupContextEntry.contextIdAt(
                            contexts,
                            index
                        );
                        if (!contextId.isEmpty()) {
                            gateway.getContext(contextId);
                        }
                    }
                }
                return;
            }
            throw new IllegalArgumentException(
                "unsupported summary owner_type: " + ownerType
            );
        } catch (TargetInvalidatedException e) {
            throw e;
        } catch (Exception e) {
            if (isTargetInvalidation(e)) {
                throw new TargetInvalidatedException(
                    "summary target is no longer available: " + ownerId,
                    e
                );
            }
            throw e;
        }
    }

    private static boolean containsContextEntryId(
        JSONObject context,
        String entryId
    ) {
        if (context == null || entryId == null || entryId.isEmpty()) {
            return false;
        }
        JSONArray scenes = context.optJSONArray("scenes");
        if (scenes == null) {
            return false;
        }
        for (int index = 0; index < scenes.length(); index++) {
            JSONObject entry = scenes.optJSONObject(index);
            if (entry != null && entryId.equals(
                entry.optString("entry_id", "")
            )) {
                return true;
            }
        }
        return false;
    }

    private static boolean containsGroupContextEntryId(
        JSONObject group,
        String entryId
    ) {
        if (group == null || entryId == null || entryId.isEmpty()) {
            return false;
        }
        JSONArray contexts = group.optJSONArray("contexts");
        if (contexts == null) {
            return false;
        }
        for (int index = 0; index < contexts.length(); index++) {
            if (entryId.equals(GroupContextEntry.entryIdAt(contexts, index))) {
                return true;
            }
        }
        return false;
    }

    private static boolean isTargetInvalidation(Throwable error) {
        Throwable current = error;
        while (current != null) {
            if (current instanceof TargetInvalidatedException
                || current instanceof SummaryTargetInvalidatedException) {
                return true;
            }
            if (current instanceof SceneContextStore.StorageException
                && ((SceneContextStore.StorageException) current).kind
                    == SceneContextStore.FailureKind.NOT_FOUND) {
                return true;
            }
            current = current.getCause();
        }
        return false;
    }

    private void discardInvalidatedJob(
        String requestId,
        JSONObject request,
        Throwable reason
    ) {
        try {
            store.removeCompletedJob(requestId);
        } catch (Exception cleanupFailure) {
            logWarn(
                "Could not discard invalidated Summary job requestId="
                    + requestId,
                cleanupFailure
            );
        }
    }

    private void archiveInvalidatedResult(
        String requestId,
        String summary,
        Throwable reason
    ) {
        try {
            archiveRejected(
                requestId,
                "target_invalidated",
                "legal",
                new JSONObject()
                    .put("summary", summary)
                    .put("reason", safeMessage(reason))
            );
        } catch (Exception archiveFailure) {
            logWarn(
                "Could not archive invalidated Summary result requestId="
                    + requestId,
                archiveFailure
            );
        }
    }

    private JSONObject buildInput(JSONObject request) throws Exception {
        String ownerType = request.optString("owner_type", "");
        String ownerId = request.optString("owner_id", "");
        JSONObject context = null;
        JSONObject group = null;
        Map<String, JSONObject> contextsById = new HashMap<>();
        if ("context".equals(ownerType)) {
            context = gateway.getContext(ownerId);
            if ("context_snapshot".equals(
                request.optString("request_kind", "")
            ) && !containsContextEntryId(
                context,
                request.optString("cutoff", "")
            )) {
                throw new TargetInvalidatedException(
                    "context summary cutoff is no longer present"
                );
            }
        } else if ("group".equals(ownerType)) {
            group = gateway.getGroup(ownerId);
            if (!containsGroupContextEntryId(
                group,
                request.optString("cutoff", "")
            )) {
                throw new TargetInvalidatedException(
                    "group summary cutoff is no longer present"
                );
            }
            contextsById = loadContextsById(group);
        } else {
            throw new IllegalArgumentException(
                "unsupported summary owner_type: " + ownerType
            );
        }
        return SummaryRequestAssembler.buildSummaryInput(
            request,
            context,
            group,
            contextsById
        );
    }

    private Map<String, JSONObject> loadContextsById(JSONObject group)
        throws Exception {
        Map<String, JSONObject> contextsById = new HashMap<>();
        JSONArray contextIds = group.optJSONArray("contexts");
        if (contextIds != null) {
            for (int index = 0; index < contextIds.length(); index++) {
                String contextId = GroupContextEntry.contextIdAt(
                    contextIds,
                    index
                );
                if (!contextId.isEmpty()
                    && !contextsById.containsKey(contextId)) {
                    try {
                        contextsById.put(
                            contextId,
                            gateway.getContext(contextId)
                        );
                    } catch (Exception missingContext) {
                        if (isTargetInvalidation(missingContext)) {
                            throw new TargetInvalidatedException(
                                "group member context is no longer available: "
                                    + contextId,
                                missingContext
                            );
                        }
                        throw missingContext;
                    }
                }
            }
        }
        return contextsById;
    }

    private String recomputeSourceHash(JSONObject request) throws Exception {
        String ownerType = request.optString("owner_type", "");
        String ownerId = request.optString("owner_id", "");
        String targetLang = request.optString("target_lang", "");
        String requestKind = request.optString("request_kind", "");
        if ("context".equals(ownerType)) {
            JSONObject context = gateway.getContext(ownerId);
            if ("context_snapshot".equals(requestKind)) {
                String cutoff = request.optString("cutoff", "");
                if (!containsContextEntryId(context, cutoff)) {
                    throw new TargetInvalidatedException(
                        "context summary cutoff is no longer present"
                    );
                }
                return ContextContentHash.computeToCutoff(
                    context,
                    targetLang,
                    cutoff
                );
            }
            if ("context_final".equals(requestKind)) {
                return ContextContentHash.compute(context, targetLang);
            }
            throw new IllegalArgumentException(
                "unsupported context summary request_kind: " + requestKind
            );
        }
        if ("group".equals(ownerType)) {
            JSONObject group = gateway.getGroup(ownerId);
            String cutoff = request.optString("cutoff", "");
            if (!containsGroupContextEntryId(group, cutoff)) {
                throw new TargetInvalidatedException(
                    "group summary cutoff is no longer present"
                );
            }
            Map<String, JSONObject> contextsById = loadContextsById(group);
            requireGroupFinalDependencies(
                group,
                contextsById,
                cutoff,
                targetLang
            );
            return SummaryRequestAssembler.computeGroupSnapshotSourceHash(
                group,
                contextsById,
                cutoff,
                targetLang
            );
        }
        throw new IllegalArgumentException(
            "unsupported summary owner_type: " + ownerType
        );
    }

    /**
     * Group snapshots are legal only while every covered Context Final still
     * represents that Context's current facts. A queued Group job can outlive
     * a child edit, so this check must run again at claim and write-back time;
     * the coordinator's admission-time check alone is not sufficient.
     */
    private void requireGroupFinalDependencies(
        JSONObject group,
        Map<String, JSONObject> contextsById,
        String cutoff,
        String targetLang
    ) throws Exception {
        JSONArray entries = group.optJSONArray("contexts");
        int cutoffIndex = GroupContextEntry.indexOfEntryId(entries, cutoff);
        if (cutoffIndex < 0) {
            throw new TargetInvalidatedException(
                "group summary cutoff is no longer present"
            );
        }
        for (int index = 0; index <= cutoffIndex; index++) {
            String contextId = GroupContextEntry.contextIdAt(entries, index);
            JSONObject context = contextsById.get(contextId);
            if (context == null) {
                throw new TargetInvalidatedException(
                    "group summary Context is no longer available: "
                        + contextId
                );
            }
            JSONObject summary = context.optJSONObject("summary");
            JSONObject language = summary == null
                ? null
                : summary.optJSONObject(targetLang);
            JSONObject finalRecord = language == null
                ? null
                : language.optJSONObject("final");
            String finalText = finalRecord == null
                ? ""
                : finalRecord.optString("text", "").trim();
            String recordedHash = finalRecord == null
                ? ""
                : finalRecord.optString("source_hash", "").trim();
            if (finalText.isEmpty()
                || recordedHash.isEmpty()
                || !recordedHash.equals(
                    ContextContentHash.compute(context, targetLang)
                )) {
                throw new TargetInvalidatedException(
                    "group summary Context Final is stale or unavailable: "
                        + contextId
                );
            }
        }
    }

    private boolean isManualSuppressed(JSONObject request, Snapshot snapshot)
        throws Exception {
        if (snapshot.continueAfterManual) {
            return false;
        }
        String ownerType = request.optString("owner_type", "");
        String ownerId = request.optString("owner_id", "");
        String targetLang = request.optString("target_lang", "");
        JSONObject document;
        if ("context".equals(ownerType)) {
            document = gateway.getContext(ownerId);
        } else if ("group".equals(ownerType)) {
            document = gateway.getGroup(ownerId);
        } else {
            return false;
        }
        JSONObject language = languageObject(document, targetLang);
        return language != null
            && language.has("manual")
            && !language.isNull("manual");
    }

    /**
     * Rechecks facts, Manual eligibility, target/cutoff membership, and the
     * derived write-back under one Scene root gate.  The provider response is
     * never written against a context edited after the request was sent.
     */
    private WritebackDecision writeBackIfFactsStillMatch(
        JSONObject request,
        String summaryText,
        Snapshot snapshot
    ) throws Exception {
        return SceneContextStore.withRootAccess(() -> {
            String currentSourceHash = recomputeSourceHash(request);
            if (!request.optString("source_hash", "").equals(
                currentSourceHash
            )) {
                return WritebackDecision.stale(currentSourceHash);
            }
            if (isManualSuppressed(request, snapshot)) {
                return WritebackDecision.manual();
            }
            writeBack(request, summaryText);
            return WritebackDecision.written(currentSourceHash);
        });
    }

    private void archiveRejected(
        String requestId,
        String reason,
        String kind,
        Object payload
    ) throws Exception {
        if (rejectedSink == null) {
            logWarn(
                "Rejected Summary result not archived because no sink is "
                    + "configured requestId=" + requestId
            );
            return;
        }
        rejectedSink.archiveRejected(requestId, reason, kind, payload);
    }

    private void removeStaleClaimedJob(String requestId, JSONObject request) {
        try {
            store.removeCompletedJob(requestId);
        } catch (Exception cleanupFailure) {
            logWarn(
                "Could not delete stale Summary job requestId=" + requestId,
                cleanupFailure
            );
            try {
                store.markFailed(requestId);
            } catch (Exception failFailure) {
                logError(
                    "Could not terminal-fail stale Summary job requestId="
                        + requestId,
                    failFailure
                );
            }
        }
    }

    private String createSuccessorForCurrentFacts(
        JSONObject request,
        String currentSourceHash,
        boolean userRequested
    ) throws Exception {
        JSONObject successor = new JSONObject(request.toString());
        successor.put("source_hash", currentSourceHash);
        SummaryJobStore.AdmissionResult admission = userRequested
            ? store.admitUserRequested(successor)
            : store.admit(successor);
        if (admission.created) {
            logWarn(
                "Created Summary successor requestId="
                    + admission.requestId
                    + " source_hash="
                    + currentSourceHash
            );
            return admission.requestId;
        }
        if (SummaryJobStore.DISPOSITION_DUPLICATE_REJECTED.equals(
            admission.disposition
        )) {
            logWarn(
                "Summary successor already exists requestId="
                    + admission.requestId
            );
            return admission.requestId;
        }
        logWarn(
            "Could not create Summary successor disposition="
                + admission.disposition
                + " requestId="
                + admission.requestId
        );
        return null;
    }

    private void handleRerunAfterTermination(
        String requestId,
        JSONObject completedRequest,
        Snapshot snapshot,
        boolean jobRemoved,
        boolean userRequestedBeforeRemoval
    ) {
        if (completedRequest == null) {
            return;
        }
        try {
            String currentSourceHash = recomputeSourceHash(completedRequest);
            if (currentSourceHash.equals(
                completedRequest.optString("source_hash", "")
            )) {
                // No semantic change since this job started; just consume the
                // merged rerun marker. On the success path the directory is
                // already gone, so there is nothing to clear.
                if (!jobRemoved && store.hasJob(requestId)) {
                    store.clearRerunRequired(requestId);
                }
                return;
            }
            boolean userRequested = userRequestedBeforeRemoval;
            if (!jobRemoved && store.hasJob(requestId)) {
                userRequested = store.isUserRequested(requestId);
            }
            createSuccessorForCurrentFacts(
                completedRequest,
                currentSourceHash,
                userRequested
            );
            if (!jobRemoved && store.hasJob(requestId)) {
                store.clearRerunRequired(requestId);
            }
        } catch (Exception e) {
            logWarn(
                "Could not consume rerun_required after requestId="
                    + requestId,
                e
            );
        }
    }

    private boolean isAlreadyWrittenBack(JSONObject request) throws Exception {
        String sourceHash = request.optString("source_hash", "");
        if (sourceHash.isEmpty()) {
            return false;
        }
        String ownerType = request.optString("owner_type", "");
        String ownerId = request.optString("owner_id", "");
        String targetLang = request.optString("target_lang", "");
        String requestKind = request.optString("request_kind", "");
        if ("context".equals(ownerType)) {
            JSONObject context = gateway.getContext(ownerId);
            JSONObject language = languageObject(context, targetLang);
            JSONObject record = "context_snapshot".equals(requestKind)
                ? language == null ? null : language.optJSONObject("current")
                : language == null ? null : language.optJSONObject("final");
            return record != null
                && sourceHash.equals(record.optString("source_hash", ""));
        }
        if ("group".equals(ownerType)) {
            JSONObject group = gateway.getGroup(ownerId);
            JSONObject language = languageObject(group, targetLang);
            JSONObject record = language == null
                ? null
                : language.optJSONObject("current");
            return record != null
                && sourceHash.equals(record.optString("source_hash", ""));
        }
        return false;
    }

    private void writeBack(JSONObject request, String summaryText)
        throws Exception {
        String ownerType = request.optString("owner_type", "");
        String ownerId = request.optString("owner_id", "");
        String targetLang = request.optString("target_lang", "");
        String requestKind = request.optString("request_kind", "");
        String sourceHash = request.optString("source_hash", "");
        String cutoff = request.optString("cutoff", "");

        if ("context".equals(ownerType)) {
            gateway.writeContextSummary(
                ownerId,
                requestKind,
                targetLang,
                cutoff,
                summaryText,
                sourceHash
            );
            if ("context_final".equals(requestKind)) {
                try {
                    contextFinalWrittenListener.onContextFinalWritten(
                        ownerId,
                        targetLang
                    );
                } catch (RuntimeException listenerFailure) {
                    logWarn(
                        "Context final listener failed after durable write "
                            + "contextId=" + ownerId,
                        listenerFailure
                    );
                }
            }
            return;
        }
        if ("group".equals(ownerType)) {
            if (!"group_snapshot".equals(requestKind)) {
                throw new IllegalArgumentException(
                    "unsupported group summary request_kind: " + requestKind
                );
            }
            gateway.writeGroupSummary(
                ownerId,
                targetLang,
                cutoff,
                summaryText,
                sourceHash
            );
            return;
        }
        throw new IllegalArgumentException(
            "unsupported summary owner_type: " + ownerType
        );
    }

    private void fail(
        String requestId,
        JSONObject request,
        Snapshot snapshot,
        Exception error
    ) {
        try {
            boolean notified = store.isErrorNotified(requestId);
            store.failJob(requestId, safeMessage(error));
            if (!notified) {
                errorNotifier.onSummaryFailed(
                    requestId,
                    request == null ? "" : request.optString("owner_type", ""),
                    request == null ? "" : request.optString("owner_id", ""),
                    safeMessage(error)
                );
                store.markErrorNotified(requestId);
            }
        } catch (Exception persistFailure) {
            logError(
                "Could not persist summary failure requestId=" + requestId,
                persistFailure
            );
        }
    }

    private static int retryLimit(JSONObject request, Snapshot snapshot) {
        return "group".equals(request.optString("owner_type", ""))
            ? snapshot.groupRetryCount
            : snapshot.contextRetryCount;
    }

    static boolean isRetryableSummaryFailure(Throwable error) {
        if (error == null) {
            return false;
        }
        if (error instanceof SummaryResultValidator.ValidationException) {
            return true;
        }
        if (error instanceof TranslationApiClient.HttpStatusException) {
            return TranslationApiClient.isRetryableHttpStatus(
                ((TranslationApiClient.HttpStatusException) error)
                    .getStatusCode()
            );
        }
        Throwable current = error;
        while (current != null) {
            if (current instanceof InterruptedException) {
                return false;
            }
            if (current instanceof IOException) {
                return TranslationApiClient.isRetryableNetworkException(current);
            }
            current = current.getCause();
        }
        return false;
    }

    private static JSONObject languageObject(JSONObject document, String lang) {
        JSONObject summary = document.optJSONObject("summary");
        return summary == null ? null : summary.optJSONObject(lang);
    }

    /**
     * Returns the global API concurrency limit. The shared gate itself keeps
     * at least one Translation channel, so the worker pool may be sized to the
     * full limit; Summary workers that cannot get a shared permit simply wait.
     */
    private static int readSummaryWorkerCount(Context context) {
        try {
            JSONObject userSettings = new ConfigStore(context)
                .load()
                .config
                .getJSONObject("UserSettings");
            return Math.max(1, ConfigStore.getApiConcurrency(userSettings));
        } catch (Exception e) {
            logWarn("Could not read summary worker count; using 1", e);
            return 1;
        }
    }

    private static String readAsset(Context context, String path)
        throws Exception {
        try (InputStream input = context.getAssets().open(path)) {
            return IoUtils.readUtf8Limited(input, MAX_ASSET_BYTES);
        }
    }

    private static JSONObject readAssetJson(Context context, String path)
        throws Exception {
        try (InputStream input = context.getAssets().open(path)) {
            return new JSONObject(
                IoUtils.readUtf8Limited(input, MAX_ASSET_BYTES)
            );
        }
    }

    private static void logError(String message, Throwable error) {
        try {
            Log.e(TAG, message, error);
        } catch (RuntimeException ignored) {
            // Host JUnit tests run without Android Log; logging is best-effort.
        }
    }

    private static void logWarn(String message) {
        try {
            Log.w(TAG, message);
        } catch (RuntimeException ignored) {
        }
    }

    private static void logWarn(String message, Throwable error) {
        try {
            Log.w(TAG, message, error);
        } catch (RuntimeException ignored) {
        }
    }

    private static void logRetry(String reason, int retry, int maxRetries) {
        try {
            Log.w(
                TAG,
                "Retrying summary request after "
                    + reason
                    + " (retry "
                    + retry
                    + "/"
                    + maxRetries
                    + ")"
            );
        } catch (RuntimeException ignored) {
        }
    }

    private static String safeMessage(Throwable error) {
        String message = error.getMessage();
        return message == null || message.trim().isEmpty()
            ? error.getClass().getSimpleName()
            : message;
    }
}
