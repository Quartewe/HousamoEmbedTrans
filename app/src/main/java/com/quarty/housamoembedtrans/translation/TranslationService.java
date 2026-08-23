package com.quarty.housamoembedtrans.translation;

import com.quarty.housamoembedtrans.bridge.CallerVerifier;
import com.quarty.housamoembedtrans.bridge.HetBridgeContract;
import com.quarty.housamoembedtrans.runtime.SceneSyncRuntimeState;
import com.quarty.housamoembedtrans.runtime.TranslationStatusNotification;
import com.quarty.housamoembedtrans.runtime.RuntimeControlStore;
import com.quarty.housamoembedtrans.storage.ConfigStore;
import com.quarty.housamoembedtrans.storage.ConflictStore;
import com.quarty.housamoembedtrans.storage.HistoryMapping;
import com.quarty.housamoembedtrans.storage.PendingSceneApplyStore;
import com.quarty.housamoembedtrans.storage.SceneContextStore;
import com.quarty.housamoembedtrans.storage.SceneStore;
import com.quarty.housamoembedtrans.storage.SceneSyncCycleSnapshot;
import com.quarty.housamoembedtrans.storage.SummaryJobStore;
import com.quarty.housamoembedtrans.storage.SummaryJobWakeup;
import com.quarty.housamoembedtrans.util.IoUtils;
import com.quarty.housamoembedtrans.util.JobValidator;

import android.app.Notification;
import android.app.Service;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.os.Binder;
import android.os.Build;
import android.os.IBinder;
import android.os.ParcelFileDescriptor;
import android.os.Process;
import android.os.RemoteException;
import android.util.Log;

import org.json.JSONObject;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.ByteArrayInputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Foreground owner of the persistent translation queue and provider workers.
 */
public final class TranslationService extends Service {
    private static final String TAG = "HET.TranslationService";

    private static long newScenePortGeneration() {
        long token = UUID.randomUUID().getLeastSignificantBits()
            & Long.MAX_VALUE;
        return token == 0L ? 1L : token;
    }

    /** Process-local accessor for the active executor used by local UI retry. */
    private static volatile TranslationTaskExecutor activeTaskExecutor;

    public static TranslationTaskExecutor getActiveTaskExecutor() {
        return activeTaskExecutor;
    }
    private static final long STARTUP_REPAIR_RETRY_DELAY_MS = 5_000L;

    /** Host-test seam: the foreground notification must precede startup. */
    @FunctionalInterface
    interface ForegroundPromoter {
        void promote();
    }

    /** Host-test seam: starts the background startup coordinator. */
    @FunctionalInterface
    interface StartupCoordinatorStarter {
        void start();
    }

    /** Host-test seam: drains API work only after the startup gate opens. */
    @FunctionalInterface
    interface ApiWorkDrainer {
        void drainIfOpen();
    }

    /** Host-test seam: user-visible startup failure notification. */
    @FunctionalInterface
    interface StartupFailureNotifier {
        void onStartupFailed(String message);
    }

    /** Host-test seam for the early-admission Context membership backfill. */
    interface EarlyAdmissionJobStore {
        Object resolveHistoryMapping() throws Exception;

        void rewriteHistoryMapping(
            String requestId,
            Object historyMapping
        ) throws Exception;

        String readScene(String requestId) throws Exception;
    }

    /**
     * The exact onStartCommand ordering contract used by the service.
     * Foreground promotion happens before the background coordinator is
     * started, and API drains are only attempted after that.
     */
    static void runOnStartCommandSequence(
        ForegroundPromoter foregroundPromoter,
        StartupCoordinatorStarter coordinatorStarter,
        ApiWorkDrainer apiWorkDrainer
    ) {
        foregroundPromoter.promote();
        coordinatorStarter.start();
        apiWorkDrainer.drainIfOpen();
    }

    /** Host-test seam for the failure-notification path. */
    static void handleStartupCoordinatorFailure(
        Throwable error,
        StartupFailureNotifier notifier
    ) {
        if (notifier == null) {
            return;
        }
        notifier.onStartupFailed(safeStartupMessage(error));
    }

    /** API work may be scheduled only after the startup gate has opened. */
    static boolean shouldScheduleApiWorkDrain(
        boolean apiWorkOpen,
        boolean foregroundStarted
    ) {
        return apiWorkOpen && foregroundStarted;
    }
    public static final int SCENE_REJECTED_SYNC_WORKER_HOLD = 1;
    public static final int SCENE_REJECTED_SCENE_BLOCKED = 2;

    private static final class CallbackRecord {
        private final ITranslationCallback callback;
        private final IBinder binder;
        private final IBinder.DeathRecipient deathRecipient;

        private CallbackRecord(
            ITranslationCallback callback,
            IBinder binder,
            IBinder.DeathRecipient deathRecipient
        ) {
            this.callback = callback;
            this.binder = binder;
            this.deathRecipient = deathRecipient;
        }
    }

    /** Owns the write end of one in-flight callback payload pipe. */
    private static final class PayloadWriter {
        private final ParcelFileDescriptor descriptor;

        private PayloadWriter(ParcelFileDescriptor descriptor) {
            this.descriptor = descriptor;
        }
    }

    private static final class ScenePortRecord {
        private final IGameScenePort port;
        private final IBinder binder;
        private final IBinder.DeathRecipient deathRecipient;
        private final int sceneWorkerCount;
        private final long generation;
        private volatile boolean registeredWithCoordinator;

        private ScenePortRecord(
            IGameScenePort port,
            IBinder binder,
            IBinder.DeathRecipient deathRecipient,
            int sceneWorkerCount,
            long generation
        ) {
            this.port = port;
            this.binder = binder;
            this.deathRecipient = deathRecipient;
            this.sceneWorkerCount = sceneWorkerCount;
            this.generation = generation;
        }
    }

    /** Atomically cancellable FULL_SYNC data-plane activity. */
    private static final class ActiveOperation {
        private final IGameScenePort port;
        private final IBinder binder;
        private final long generation;
        private final SceneSyncOperation operation;

        private ActiveOperation(
            IGameScenePort port,
            IBinder binder,
            long generation,
            SceneSyncOperation operation
        ) {
            this.port = port;
            this.binder = binder;
            this.generation = generation;
            this.operation = operation;
        }

        private void close() {
            operation.close();
        }
    }

    /** Detaches Scene runtime resources while the lifecycle generation lock is held. */
    private static final class SceneRuntimeDetach {
        private final ActiveOperation activeOperation;
        private final SceneSyncCoordinator coordinator;

        private SceneRuntimeDetach(
            ActiveOperation activeOperation,
            SceneSyncCoordinator coordinator
        ) {
            this.activeOperation = activeOperation;
            this.coordinator = coordinator;
        }
    }

    @FunctionalInterface
    private interface DescriptorSender {
        void send(ParcelFileDescriptor descriptor) throws RemoteException;
    }

    private final Object callbackLock = new Object();
    private final Object contextStoreLock = new Object();
    private CallbackRecord currentCallback;
    private final ExecutorService callbackIoExecutor =
        Executors.newCachedThreadPool(runnable ->
            new Thread(runnable, "HET-callback-io")
        );
    private final Object payloadWriterLock = new Object();
    private final Map<String, PayloadWriter> activePayloadWriters =
        new HashMap<>();
    private final ScheduledExecutorService startupRepairExecutor =
        Executors.newSingleThreadScheduledExecutor(runnable ->
            new Thread(runnable, "HET-startup-repair")
        );
    private final ExecutorService startupCoordinatorExecutor =
        Executors.newSingleThreadExecutor(runnable ->
            new Thread(runnable, "HET-startup-coordinator")
        );
    private final AtomicBoolean startupRepairScheduled =
        new AtomicBoolean();
    private final Object scenePortLock = new Object();
    /** Serializes remote generation activation with Binder death/unregister. */
    private final Object scenePortRegistrationLock = new Object();
    private final ExecutorService sceneSyncTriggerExecutor =
        Executors.newSingleThreadExecutor(runnable ->
            new Thread(runnable, "HET-scene-sync-trigger")
        );
    private final ExecutorService sceneSyncOperationExecutor =
        Executors.newSingleThreadExecutor(runnable ->
            new Thread(runnable, "HET-scene-sync-operation")
        );

    private volatile TranslationJobStore jobStore;
    private volatile SummaryJobStore summaryJobStore;
    private volatile SummaryTaskExecutor summaryTaskExecutor;
    private volatile ContextCompressionCoordinator contextCompressionCoordinator;
    private volatile GroupCompressionCoordinator groupCompressionCoordinator;
    private volatile SceneContextStore sceneContextStore;
    private volatile TerminalDeliveryCoordinator terminalDelivery;
    private volatile TranslationTaskExecutor taskExecutor;
    private volatile SceneSyncCoordinator sceneSyncCoordinator;
    private volatile SceneStore sceneStore;
    private volatile ConflictStore conflictStore;
    private volatile PendingSceneApplyStore pendingSceneApplyStore;
    private volatile SceneManualConflictController manualConflictController;

    private final SceneSyncRuntimeState runtimeState =
        SceneSyncRuntimeState.getInstance();
    private final SceneSyncRuntimeState.Controller runtimeStateController =
        new RuntimeController();
    private final Object runtimeSnapshotLock = new Object();
    private List<SceneSyncRuntimeState.SceneSummary> runtimeSceneSummaries =
        Collections.emptyList();
    private int runtimePendingConflictCount;
    private SceneSyncRuntimeState.Action runtimeLastAction =
        SceneSyncRuntimeState.Action.NONE;
    private SceneSyncRuntimeState.Outcome runtimeLastOutcome =
        SceneSyncRuntimeState.Outcome.NONE;

    private final ScenePolicyPublisher scenePolicyPublisher =
        new ScenePolicyPublisher();
    private ScenePortRecord currentScenePort;
    private final AtomicReference<ActiveOperation> activeSceneOperation =
        new AtomicReference<>();
    private final Object sceneOperationLifecycleLock = new Object();
    private volatile boolean sceneOperationLifecycleOpen;
    private volatile long sceneOperationLifecycleGeneration;
    /** Opaque process epoch; numeric ordering is never used by the game port. */
    private long scenePortGeneration = newScenePortGeneration();
    private volatile long startupRepairGeneration;
    private volatile boolean foregroundStarted;
    private volatile boolean apiWorkOpen;
    private volatile boolean acceptingSummaryWake;
    private volatile boolean startupPreparationComplete;
    private volatile boolean summaryStartupPrepared;
    private volatile Boolean capturePausedRequest;
    private final SummaryJobWakeup.ServiceWakeCallback summaryWakeCallback =
        this::wakeSummaryWork;
    private final Object startupLock = new Object();
    private volatile boolean startupStarted;
    private StartupCoordinator startupCoordinator;
    private ApiConcurrencyGate apiConcurrencyGate;

    private final TranslationJobStore.QueueListener queueListener =
        (hasPendingJobs, heldQueuedJobCount, repairingStartupJobs) -> {
            notifyStartupWaiters();
            TranslationStatusNotification.refresh(this);
            if (repairingStartupJobs) {
                // A failed pre-boundary admission preflight leaves the same
                // repair generation open.  Wake its owner even when no
                // ordinary API queue item is claimable yet.
                scheduleStartupRepair();
            } else if (hasPendingJobs) {
                scheduleApiWorkDrain();
            }
        };

    private final TranslationTaskExecutor.ResultListener resultListener =
        new TranslationTaskExecutor.ResultListener() {
            @Override
            public void onStarted(String requestId, String scene) {
                try {
                    TranslationStatusNotification.translationStarted(
                        TranslationService.this,
                        scene
                    );
                } catch (RuntimeException notificationFailure) {
                    // Notification state is observability only.  A failure
                    // here must never escape executeJob() and turn a running
                    // API task into a persisted terminal failure.
                    Log.w(
                        TAG,
                        "Start status notification failed requestId="
                            + requestId,
                        notificationFailure
                    );
                }
            }

            @Override
            public void onQuestPatch(
                String requestId,
                JSONObject patch
            ) {
                byte[] bytes = patch.toString().getBytes(
                    StandardCharsets.UTF_8
                );
                long patchVersion = patch.optLong(
                    "patch_version",
                    0L
                );
                deliverPayload(
                    requestId,
                    bytes,
                    callback -> descriptor ->
                        callback.onQuestPatch(
                            requestId,
                            patchVersion,
                            descriptor
                        )
                );
            }

            @Override
            public void onCompleted(
                String requestId,
                String scene,
                String targetLanguage,
                byte[] resultJson
            ) {
                TerminalDeliveryCoordinator coordinator = terminalDelivery;
                if (coordinator != null) {
                    coordinator.onTerminalPersisted(requestId);
                }
                try {
                    TranslationStatusNotification.translationSucceeded(
                        TranslationService.this,
                        scene
                    );
                } catch (RuntimeException notificationFailure) {
                    Log.w(
                        TAG,
                        "Completion status notification failed requestId="
                            + requestId,
                        notificationFailure
                    );
                }
            }

            @Override
            public void onFailed(
                String requestId,
                String scene,
                JSONObject error
            ) {
                TerminalDeliveryCoordinator coordinator = terminalDelivery;
                if (coordinator != null) {
                    coordinator.onTerminalPersisted(requestId);
                }
                try {
                    TranslationStatusNotification.translationFailed(
                        TranslationService.this,
                        scene
                    );
                    TranslationStatusNotification.translationFailedDetails(
                        TranslationService.this,
                        requestId,
                        scene,
                        error.optString(
                            "message",
                            error.optString("error", "")
                        )
                    );
                } catch (RuntimeException notificationFailure) {
                    Log.w(
                        TAG,
                        "Failure status notification failed requestId="
                            + requestId,
                        notificationFailure
                    );
                }
            }

            @Override
            public void onTranslationNeedsUserAction(
                String requestId,
                String scene,
                String reason
            ) {
                try {
                    TranslationStatusNotification.translationNeedsUserAction(
                        TranslationService.this,
                        scene,
                        reason
                    );
                } catch (RuntimeException notificationFailure) {
                    Log.w(
                        TAG,
                        "User-action notification failed requestId="
                            + requestId,
                        notificationFailure
                    );
                }
            }
        };

    @FunctionalInterface
    private interface SenderFactory {
        DescriptorSender create(ITranslationCallback callback);
    }

    /** Connection registration hook consumed by the Scene Sync coordinator. */
    public interface SceneSyncTrigger {
        void onPortRegistered(
            IGameScenePort port,
            int sceneWorkerCount,
            long generation
        );

        void onPortUnregistered(
            IGameScenePort port,
            long generation
        );
    }

    private volatile SceneSyncTrigger sceneSyncTrigger;

    private final ITranslationService.Stub binder =
        new ITranslationService.Stub() {
            @Override
            public int getProtocolVersion() {
                enforceAllowedCaller();
                Log.i(
                    TAG,
                    "getProtocolVersion callerPid="
                        + Binder.getCallingPid()
                        + " callerUid="
                        + Binder.getCallingUid()
                );
                return HetBridgeContract.PROTOCOL_VERSION;
            }

            @Override
            public int enqueueTranslation(
                String requestId,
                ParcelFileDescriptor requestFd,
                boolean overwrite
            ) {
                enforceAllowedCaller();
                validateRequestId(requestId);
                if (requestFd == null) {
                    throw new IllegalArgumentException(
                        "requestFd cannot be null"
                    );
                }

                try (InputStream input =
                         new ParcelFileDescriptor.AutoCloseInputStream(
                             requestFd
                         )) {
                    // Read the bounded Binder payload before taking the
                    // process-wide Scene/Job admission gate.  The critical
                    // section below may wait on storage locks, but it must
                    // never hold ROOT_ACCESS_LOCK while a game process is
                    // still streaming request bytes through the pipe.
                    byte[] requestBytes = IoUtils.readAllBytesLimited(
                        input,
                        TranslationJobStore.MAX_REQUEST_BYTES
                    );
                    ByteArrayInputStream bufferedInput =
                        new ByteArrayInputStream(requestBytes);
                    synchronized (SceneContextStore.ROOT_ACCESS_LOCK) {
                    ensureTranslationJobStore();
                    SceneContextStore admissionContextStore =
                        ensureSceneContextStoreForAdmission();
                    if (admissionContextStore == null) {
                        return HetBridgeContract
                            .ENQUEUE_RESULT_RETRYABLE_PERSISTENCE;
                    }
                    boolean membershipPendingBefore =
                        jobStore.isHistoryMembershipPending(requestId);
                    Object historyMapping;
                    if (membershipPendingBefore) {
                        // A marked job already has a frozen route.  Do not
                        // consult the current Active pointer: it may have
                        // moved (or be temporarily unreadable) while this
                        // cross-store admission is being retried.
                        Object persistedMapping = jobStore.readHistoryMapping(
                            requestId
                        );
                        if (HistoryMapping.resolutionOfValue(persistedMapping)
                            == HistoryMapping.Resolution.VALID) {
                            historyMapping = persistedMapping;
                        } else {
                            return HetBridgeContract
                                .ENQUEUE_RESULT_RETRYABLE_PERSISTENCE;
                        }
                    } else {
                        try {
                            historyMapping = admissionContextStore
                                .resolveActiveHistoryMapping();
                        } catch (SceneContextStore.StorageException e) {
                            if (e.kind
                                == SceneContextStore.FailureKind.INVALID_ACTIVE_GROUP) {
                                return notifyAdmissionUserActionRequired(
                                    requestId,
                                    e.getMessage()
                                );
                            }
                            throw e;
                        }
                        if (historyMapping == null) {
                            historyMapping = JSONObject.NULL;
                        }
                    }
                    boolean created;
                    try {
                        created = jobStore.createQueuedJob(
                            requestId,
                            bufferedInput,
                            overwrite,
                            historyMapping,
                            HistoryMapping.resolutionOfValue(historyMapping)
                                == HistoryMapping.Resolution.VALID
                        );
                    } catch (TranslationJobStore.AdmissionException e) {
                        // A duplicate with the same immutable payload may be
                        // the durable half of a previously interrupted
                        // Context append.  Let the compensation path below
                        // finish it instead of converting it to a permanent
                        // duplicate rejection.
                        if (!membershipPendingBefore
                            || !"duplicate_rejected".equals(
                                e.getDisposition()
                            )) {
                            throw e;
                        }
                        created = false;
                    }
                    boolean membershipPending = jobStore
                        .isHistoryMembershipPending(requestId);
                    if (membershipPending) {
                        try {
                            // A concurrent Binder may have won the durable
                            // admission with a different Active pointer.  The
                            // state file is the linearization point; always
                            // reload its frozen mapping before touching the
                            // Context store, even when this call did not see
                            // the marker before createQueuedJob().
                            Object persistedMapping = jobStore
                                .readHistoryMapping(requestId);
                            if (HistoryMapping.resolutionOfValue(
                                persistedMapping
                            ) == HistoryMapping.Resolution.USER_ACTION_REQUIRED) {
                                return HetBridgeContract
                                    .ENQUEUE_RESULT_RETRYABLE_PERSISTENCE;
                            }
                            historyMapping = persistedMapping;
                            JSONObject state = jobStore.readState(requestId);
                            String scene = state.optString("scene", "");
                            Object resolvedMapping = historyMapping;
                            SceneContextStore.withRootAccess(() -> {
                                if (!appendSceneToHistoryContext(
                                    admissionContextStore,
                                    resolvedMapping,
                                    scene
                                )) {
                                    throw new IllegalStateException(
                                        "Context membership append did not "
                                            + "complete requestId=" + requestId
                                    );
                                }
                                jobStore.completeHistoryMembershipAdmission(
                                    requestId,
                                    resolvedMapping
                                );
                                return null;
                            });
                        } catch (SceneContextStore.StorageException e) {
                            if (e.kind
                                == SceneContextStore.FailureKind.INVALID_ACTIVE_GROUP) {
                                return notifyAdmissionUserActionRequired(
                                    requestId,
                                    e.getMessage()
                                );
                            }
                            Log.w(
                                TAG,
                                "Could not append Scene to Active Context "
                                    + "requestId=" + requestId,
                                e
                            );
                            return HetBridgeContract
                                .ENQUEUE_RESULT_RETRYABLE_PERSISTENCE;
                        } catch (Exception e) {
                            Log.w(
                                TAG,
                                "Could not append Scene to Active Context "
                                    + "requestId=" + requestId,
                                e
                            );
                            return HetBridgeContract
                                .ENQUEUE_RESULT_RETRYABLE_PERSISTENCE;
                        }
                    }
                    Log.i(
                        TAG,
                        "enqueueTranslation requestId="
                            + requestId
                            + " created="
                            + created
                    );
                    return created
                        ? HetBridgeContract.ENQUEUE_RESULT_CREATED
                        : HetBridgeContract.ENQUEUE_RESULT_EXISTING;
                    }
                } catch (IllegalArgumentException
                    | JobValidator.ValidationException
                    | IoUtils.InputLimitExceededException e) {
                    Log.w(
                        TAG,
                        "Rejected translation requestId=" + requestId,
                        e
                    );
                    throw new IllegalArgumentException(
                        "Rejected translation request " + requestId,
                        e
                    );
                } catch (IOException e) {
                    Log.w(
                        TAG,
                        "Temporary persistence failure requestId="
                            + requestId,
                        e
                    );
                    return HetBridgeContract
                        .ENQUEUE_RESULT_RETRYABLE_PERSISTENCE;
                } catch (TranslationJobStore.AdmissionException e) {
                    Log.w(
                        TAG,
                        "Rejected duplicate admission requestId="
                            + requestId
                            + " disposition="
                            + e.getDisposition()
                    );
                    return "execution_not_settled".equals(
                        e.getDisposition()
                    )
                        ? HetBridgeContract
                            .ENQUEUE_RESULT_EXECUTION_NOT_SETTLED
                        : HetBridgeContract
                            .ENQUEUE_RESULT_DUPLICATE_REJECTED;
                } catch (Exception e) {
                    Log.e(
                        TAG,
                        "Could not persist translation requestId="
                            + requestId,
                        e
                    );
                    throw new IllegalStateException(
                        "Could not persist translation request "
                            + requestId,
                        e
                    );
                }
            }

            @Override
            public void registerTranslationCallback(
                ITranslationCallback callback
            ) {
                enforceAllowedCaller();
                if (callback == null) {
                    throw new IllegalArgumentException(
                        "callback cannot be null"
                    );
                }
                registerCallback(callback);
            }

            @Override
            public void unregisterTranslationCallback(
                ITranslationCallback callback
            ) {
                enforceAllowedCaller();
                if (callback == null) {
                    return;
                }
                removeCallback(callback.asBinder());
            }

            @Override
            public boolean preflightTerminal(
                String requestId,
                String terminalKind
            ) {
                enforceAllowedCaller();
                TerminalOutcome.Kind kind =
                    TerminalOutcome.Kind.fromWireValue(terminalKind);
                if (kind == null || jobStore == null) {
                    return false;
                }
                try {
                    return jobStore.preflightTerminal(requestId, kind);
                } catch (Exception e) {
                    Log.w(
                        TAG,
                        "Terminal preflight failed requestId=" + requestId,
                        e
                    );
                    return false;
                }
            }

            @Override
            public boolean acknowledgeTerminal(
                String requestId,
                String terminalKind
            ) {
                enforceAllowedCaller();
                TerminalOutcome.Kind kind =
                    TerminalOutcome.Kind.fromWireValue(terminalKind);
                if (kind == null || jobStore == null) {
                    return false;
                }
                try {
                    boolean acknowledged = jobStore.acknowledgeTerminal(
                        requestId,
                        kind
                    );
                    if (acknowledged) {
                        TerminalDeliveryCoordinator coordinator =
                            terminalDelivery;
                        if (coordinator != null) {
                            coordinator.onAcknowledged(requestId);
                        }
                        Log.i(
                            TAG,
                            "Acknowledged terminal requestId="
                                + requestId
                                + " kind="
                                + terminalKind
                        );
                    }
                    return acknowledged;
                } catch (Exception e) {
                    Log.w(
                        TAG,
                        "Terminal acknowledgement failed requestId="
                            + requestId,
                        e
                    );
                    return false;
                }
            }

            @Override
            public void registerGameScenePort(IGameScenePort port) {
                enforceAllowedCaller();
                registerScenePort(port);
            }

            @Override
            public void unregisterGameScenePort(IGameScenePort port) {
                enforceAllowedCaller();
                unregisterScenePort(port);
            }

            @Override
            public void reportSceneProductionRejected(
                String sceneName,
                int reasonCode
            ) {
                enforceAllowedCaller();
                reportSceneProductionRejectedInternal(sceneName, reasonCode);
            }
        };

    @Override
    public void onCreate() {
        super.onCreate();
        acceptingSummaryWake = true;
        SummaryJobWakeup.setServiceWakeCallback(summaryWakeCallback);
        capturePausedRequest = RuntimeControlStore.isCapturePaused(this);
        attachRuntimeState();

        setSceneSyncTrigger(new SceneSyncTrigger() {
            @Override
            public void onPortRegistered(
                IGameScenePort port,
                int sceneWorkerCount,
                long generation
            ) {
                publishRuntimeState(
                    SceneSyncRuntimeState.Action.PORT_REGISTERED,
                    SceneSyncRuntimeState.Outcome.STARTED
                );
                try {
                    SceneSyncCoordinator coordinator = sceneSyncCoordinator;
                    scenePolicyPublisher.activate(
                        port,
                        port.asBinder(),
                        generation
                    );
                    if (coordinator == null) {
                        publishRuntimeState(
                            SceneSyncRuntimeState.Action.PORT_REGISTERED,
                            SceneSyncRuntimeState.Outcome.FAILED
                        );
                        return;
                    }
                    registerScenePortWithCoordinator(
                        port,
                        port.asBinder(),
                        generation,
                        sceneWorkerCount
                    );
                } catch (RuntimeException e) {
                    publishRuntimeState(
                        SceneSyncRuntimeState.Action.PORT_REGISTERED,
                        SceneSyncRuntimeState.Outcome.FAILED
                    );
                    throw e;
                }
            }

            @Override
            public void onPortUnregistered(
                IGameScenePort port,
                long generation
            ) {
                SceneSyncRuntimeState.Outcome outcome =
                    SceneSyncRuntimeState.Outcome.SUCCEEDED;
                try {
                    scenePolicyPublisher.deactivate(
                        port, port.asBinder(), generation
                    );
                    abortActiveSceneOperation(port);
                    SceneSyncCoordinator coordinator = sceneSyncCoordinator;
                    if (coordinator != null) {
                        coordinator.unregisterGamePort(port);
                    }
                } catch (RuntimeException e) {
                    outcome = SceneSyncRuntimeState.Outcome.FAILED;
                    throw e;
                } finally {
                    publishRuntimeState(
                        SceneSyncRuntimeState.Action.PORT_UNREGISTERED,
                        outcome
                    );
                }
            }
        });
        Log.i(
            TAG,
            "Service created pid="
                + Process.myPid()
                + " uid="
                + Process.myUid()
        );
    }

    /**
     * Captures the conflict mode for one FULL_SYNC cycle.  A coordinator must
     * call this once at the cycle boundary and retain the returned immutable
     * object until that cycle completes; calling it again starts the next
     * cycle and observes any newly saved setting.
     */
    public SceneSyncCycleSnapshot captureSceneSyncCycleSnapshot()
        throws Exception {
        JSONObject userSettings = new ConfigStore(this)
            .load()
            .config
            .getJSONObject("UserSettings");
        return SceneSyncCycleSnapshot.capture(
            ConfigStore.getConflictResolutionMode(userSettings)
        );
    }

    private ContextCompressionCoordinator.Options
        loadContextCompressionOptions() throws Exception {
        JSONObject userSettings = new ConfigStore(this)
            .load()
            .config
            .getJSONObject("UserSettings");
        JSONObject contextHistory = userSettings.optJSONObject(
            "ContextHistory"
        );
        ContextCompressionCoordinator.Options options =
            new ContextCompressionCoordinator.Options();
        options.autoCompression = contextHistory != null
            && contextHistory.optBoolean("EnableAutoCompression", false);
        options.continueAfterManual = contextHistory != null
            && contextHistory.optBoolean(
                "ContinueAutoSummaryAfterManual",
                false
            );
        return options;
    }

    private boolean isStartupReviewEnabled() {
        try {
            JSONObject userSettings = new ConfigStore(this)
                .load()
                .config
                .getJSONObject("UserSettings");
            JSONObject contextHistory = userSettings.optJSONObject(
                "ContextHistory"
            );
            return contextHistory != null
                && contextHistory.optBoolean("EnableStartupReview", false);
        } catch (Exception e) {
            Log.w(
                TAG,
                "Could not read startup Review setting; using disabled",
                e
            );
            return false;
        }
    }

    private GroupCompressionCoordinator.Options
        loadGroupCompressionOptions() throws Exception {
        JSONObject userSettings = new ConfigStore(this)
            .load()
            .config
            .getJSONObject("UserSettings");
        JSONObject contextHistory = userSettings.optJSONObject(
            "ContextHistory"
        );
        GroupCompressionCoordinator.Options options =
            new GroupCompressionCoordinator.Options();
        options.autoCompression = contextHistory != null
            && contextHistory.optBoolean("EnableAutoCompression", false);
        options.continueAfterManual = contextHistory != null
            && contextHistory.optBoolean(
                "ContinueAutoSummaryAfterManual",
                false
            );
        return options;
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        String action = intent == null ? null : intent.getAction();
        boolean captureControl =
            HetBridgeContract.ACTION_SET_CAPTURE_PAUSED.equals(action);
        if (intent != null
            && !HetBridgeContract.ACTION_START_TRANSLATION_SERVICE.equals(
                action
            )
            && !captureControl) {
            Log.w(
                TAG,
                "Rejected unknown start action=" + action
            );
            stopSelfResult(startId);
            return START_NOT_STICKY;
        }

        if (captureControl) {
            boolean paused = intent.getBooleanExtra(
                HetBridgeContract.EXTRA_CAPTURE_PAUSED,
                RuntimeControlStore.isCapturePaused(this)
            );
            capturePausedRequest = RuntimeControlStore.setCapturePaused(
                this,
                paused
            );
        }

        runOnStartCommandSequence(
            this::promoteToForeground,
            () -> {
                foregroundStarted = true;
                startStartupCoordinator();
                applyCapturePauseToCurrentPort();
            },
            () -> {
                if (shouldScheduleApiWorkDrain(
                    apiWorkOpen,
                    foregroundStarted
                )) {
                    scheduleApiWorkDrain();
                }
            }
        );
        Log.i(
            TAG,
            "Service started pid="
                + Process.myPid()
                + " uid="
                + Process.myUid()
        );
        return START_STICKY;
    }

    @Override
    public IBinder onBind(Intent intent) {
        Log.i(TAG, "Client bound to translation service");
        return binder;
    }

    @Override
    public boolean onUnbind(Intent intent) {
        Log.i(TAG, "Client unbound from translation service");
        return false;
    }

    @Override
    public void onTimeout(int startId, int foregroundServiceType) {
        Log.e(
            TAG,
            "Service timeout startId="
                + startId
                + " type="
                + foregroundServiceType
        );
        stopForeground(STOP_FOREGROUND_REMOVE);
        stopSelf();
    }

    @Override
    public void onDestroy() {
        // Publish the non-accepting state before shutting down either API
        // executor. A concurrent durable admission that already captured the
        // old callback will observe the non-accepting state instead of being
        // submitted to a closing executor; SummaryJobWakeup also keeps an
        // explicit Service-start safety net for that admission.
        acceptingSummaryWake = false;
        foregroundStarted = false;
        apiWorkOpen = false;
        SummaryJobWakeup.clearServiceWakeCallback(summaryWakeCallback);
        clearScenePort();
        scenePolicyPublisher.close();
        SceneRuntimeDetach detachedSceneRuntime =
            invalidateSceneOperationRuntime();
        if (detachedSceneRuntime.activeOperation != null) {
            detachedSceneRuntime.activeOperation.close();
        }
        if (detachedSceneRuntime.coordinator != null) {
            detachedSceneRuntime.coordinator.close();
        }
        ApiConcurrencyGate gate = apiConcurrencyGate;
        apiConcurrencyGate = null;
        if (gate != null) {
            gate.close();
        }
        if (jobStore != null) {
            jobStore.clearQueueListener(queueListener);
        }
        if (taskExecutor != null) {
            taskExecutor.setActivityListener(null);
            taskExecutor.shutdown();
        }
        activeTaskExecutor = null;
        TranslationStatusNotification.setJobStore(null);
        if (summaryTaskExecutor != null) {
            summaryTaskExecutor.shutdown();
        }
        StartupCoordinator coordinator = startupCoordinator;
        if (coordinator != null) {
            coordinator.close();
            startupCoordinator = null;
        }
        clearCallbacks();
        if (terminalDelivery != null) {
            terminalDelivery.close();
        }
        closeActivePayloadWriters();
        callbackIoExecutor.shutdownNow();
        sceneSyncTriggerExecutor.shutdownNow();
        sceneSyncOperationExecutor.shutdownNow();
        startupRepairExecutor.shutdownNow();
        startupCoordinatorExecutor.shutdownNow();
        runtimeState.detach(runtimeStateController);
        Log.i(TAG, "Service destroyed");
        super.onDestroy();
    }

    private final class RuntimeController
        implements SceneSyncRuntimeState.Controller {
        @Override
        public SceneSyncRuntimeState.Outcome requestRefresh() {
            return requestRuntimeRefresh();
        }

        @Override
        public SceneSyncRuntimeState.Outcome chooseGame(String sceneName) {
            return requestManualConflict(
                SceneSyncRuntimeState.Action.CHOOSE_GAME,
                sceneName,
                false
            );
        }

        @Override
        public SceneSyncRuntimeState.Outcome chooseHet(
            String sceneName,
            boolean overwriteIfGameChanged
        ) {
            return requestManualConflict(
                SceneSyncRuntimeState.Action.CHOOSE_HET,
                sceneName,
                overwriteIfGameChanged
            );
        }
    }

    private void attachRuntimeState() {
        SceneSyncRuntimeState.Snapshot previous =
            runtimeState.getSnapshot();
        SceneSyncRuntimeState.Snapshot initial;
        synchronized (runtimeSnapshotLock) {
            runtimeSceneSummaries = previous.sceneSummaries;
            runtimePendingConflictCount =
                previous.pendingConflictCount;
            runtimeLastAction =
                SceneSyncRuntimeState.Action.SERVICE_STARTED;
            runtimeLastOutcome =
                SceneSyncRuntimeState.Outcome.SUCCEEDED;
            initial = new SceneSyncRuntimeState.Snapshot(
                true,
                false,
                SceneSyncRuntimeState.Phase.IDLE,
                0,
                runtimePendingConflictCount,
                runtimeLastAction,
                runtimeLastOutcome,
                runtimeSceneSummaries
            );
        }
        runtimeState.attach(runtimeStateController, initial);
    }

    private SceneSyncRuntimeState.Outcome requestRuntimeRefresh() {
        SceneSyncCoordinator coordinator = sceneSyncCoordinator;
        if (coordinator == null) {
            publishRuntimeState(
                SceneSyncRuntimeState.Action.MANUAL_REFRESH,
                SceneSyncRuntimeState.Outcome.UNAVAILABLE
            );
            return SceneSyncRuntimeState.Outcome.UNAVAILABLE;
        }
        SceneSyncCoordinator.TriggerResult result =
            coordinator.requestManualRefresh();
        SceneSyncRuntimeState.Outcome outcome = mapTriggerResult(result);
        SceneSyncRuntimeState.Action action =
            result == SceneSyncCoordinator.TriggerResult.LOCAL_ONLY
                ? SceneSyncRuntimeState.Action.LOCAL_REFRESH
                : SceneSyncRuntimeState.Action.MANUAL_REFRESH;
        publishRuntimeState(action, outcome);
        return outcome;
    }

    private void publishApiActivity() {
        SceneSyncCoordinator coordinator = sceneSyncCoordinator;
        int activeApiJobs = coordinator == null
            ? 0
            : coordinator.getActiveApiJobs();
        publishRuntimeState(
            SceneSyncRuntimeState.Action.API_ACTIVITY,
            coordinator == null
                ? SceneSyncRuntimeState.Outcome.UNAVAILABLE
                : activeApiJobs > 0
                    ? SceneSyncRuntimeState.Outcome.STARTED
                    : SceneSyncRuntimeState.Outcome.SUCCEEDED
        );
    }

    private void republishRuntimeState() {
        List<SceneSyncRuntimeState.SceneSummary> summaries;
        int pendingConflictCount;
        SceneSyncRuntimeState.Action action;
        SceneSyncRuntimeState.Outcome outcome;
        synchronized (runtimeSnapshotLock) {
            summaries = runtimeSceneSummaries;
            pendingConflictCount = runtimePendingConflictCount;
            action = runtimeLastAction;
            outcome = runtimeLastOutcome;
        }
        publishRuntimeSnapshot(
            action,
            outcome,
            summaries,
            pendingConflictCount
        );
    }

    private void publishRuntimeState(
        SceneSyncRuntimeState.Action action,
        SceneSyncRuntimeState.Outcome outcome
    ) {
        List<SceneSyncRuntimeState.SceneSummary> summaries;
        int pendingConflictCount;
        synchronized (runtimeSnapshotLock) {
            runtimeLastAction = action;
            runtimeLastOutcome = outcome;
            summaries = runtimeSceneSummaries;
            pendingConflictCount = runtimePendingConflictCount;
        }
        publishRuntimeSnapshot(
            action,
            outcome,
            summaries,
            pendingConflictCount
        );
    }

    private boolean publishRuntimeStateIfCurrent(
        long sceneRuntimeGeneration,
        SceneSyncRuntimeState.Action action,
        SceneSyncRuntimeState.Outcome outcome
    ) {
        synchronized (sceneOperationLifecycleLock) {
            if (!isSceneOperationGenerationCurrent(
                sceneOperationLifecycleOpen,
                sceneOperationLifecycleGeneration,
                sceneRuntimeGeneration
            )) {
                return false;
            }
            publishRuntimeState(action, outcome);
            return true;
        }
    }

    private boolean publishRuntimeSceneStateIfCurrent(
        long sceneRuntimeGeneration,
        List<SceneSyncRuntimeState.SceneSummary> summaries,
        int pendingConflictCount,
        SceneSyncRuntimeState.Action action,
        SceneSyncRuntimeState.Outcome outcome
    ) {
        synchronized (sceneOperationLifecycleLock) {
            if (!isSceneOperationGenerationCurrent(
                sceneOperationLifecycleOpen,
                sceneOperationLifecycleGeneration,
                sceneRuntimeGeneration
            )) {
                return false;
            }
            publishRuntimeSceneState(
                summaries,
                pendingConflictCount,
                action,
                outcome
            );
            return true;
        }
    }

    private void publishRuntimeSceneState(
        List<SceneSyncRuntimeState.SceneSummary> summaries,
        int pendingConflictCount,
        SceneSyncRuntimeState.Action action,
        SceneSyncRuntimeState.Outcome outcome
    ) {
        ArrayList<SceneSyncRuntimeState.SceneSummary> sorted =
            new ArrayList<>(summaries);
        Collections.sort(
            sorted,
            (left, right) -> left.sceneName.compareTo(right.sceneName)
        );
        List<SceneSyncRuntimeState.SceneSummary> immutable =
            Collections.unmodifiableList(sorted);
        synchronized (runtimeSnapshotLock) {
            runtimeSceneSummaries = immutable;
            runtimePendingConflictCount = pendingConflictCount;
            runtimeLastAction = action;
            runtimeLastOutcome = outcome;
        }
        publishRuntimeSnapshot(
            action,
            outcome,
            immutable,
            pendingConflictCount
        );
    }

    private void publishRuntimeSnapshot(
        SceneSyncRuntimeState.Action action,
        SceneSyncRuntimeState.Outcome outcome,
        List<SceneSyncRuntimeState.SceneSummary> summaries,
        int pendingConflictCount
    ) {
        SceneSyncCoordinator coordinator = sceneSyncCoordinator;
        SceneSyncCoordinator.State coordinatorState = coordinator == null
            ? SceneSyncCoordinator.State.NONE
            : coordinator.getState();
        boolean gamePortAvailable = coordinator != null
            && coordinator.hasGamePort();
        int activeApiJobs = coordinator == null
            ? 0
            : coordinator.getActiveApiJobs();
        boolean published = runtimeState.publish(
            runtimeStateController,
            new SceneSyncRuntimeState.Snapshot(
                true,
                gamePortAvailable,
                mapRuntimePhase(coordinatorState),
                activeApiJobs,
                pendingConflictCount,
                action,
                outcome,
                summaries
            )
        );
        if (published) {
            TranslationStatusNotification.refresh(this);
        }
    }

    private static SceneSyncRuntimeState.Phase mapRuntimePhase(
        SceneSyncCoordinator.State state
    ) {
        if (state == null) {
            return SceneSyncRuntimeState.Phase.IDLE;
        }
        switch (state) {
            case FULL_SYNC:
                return SceneSyncRuntimeState.Phase.FULL_SYNC;
            case MANUAL_REFRESH:
                return SceneSyncRuntimeState.Phase.MANUAL_REFRESH;
            case MANUAL_APPLY:
                return SceneSyncRuntimeState.Phase.MANUAL_APPLY;
            case NONE:
            default:
                return SceneSyncRuntimeState.Phase.IDLE;
        }
    }

    private SceneSyncRuntimeState.Outcome requestManualConflict(
        SceneSyncRuntimeState.Action action,
        String sceneName,
        boolean overwriteIfGameChanged
    ) {
        SceneManualConflictController controller = manualConflictController;
        if (controller == null) {
            publishRuntimeState(
                action,
                SceneSyncRuntimeState.Outcome.UNAVAILABLE
            );
            return SceneSyncRuntimeState.Outcome.UNAVAILABLE;
        }

        Object publicationOrder = new Object();
        boolean[] completed = {false};
        SceneManualConflictController.OutcomeListener listener = outcome -> {
            synchronized (publicationOrder) {
                completed[0] = true;
                publishManualCompletion(action, outcome);
            }
        };
        SceneSyncCoordinator.TriggerResult result =
            action == SceneSyncRuntimeState.Action.CHOOSE_GAME
                ? controller.chooseGame(sceneName, listener)
                : controller.chooseHet(
                    sceneName,
                    overwriteIfGameChanged,
                    listener
                );
        SceneSyncRuntimeState.Outcome runtimeOutcome =
            mapTriggerResult(result);
        synchronized (publicationOrder) {
            if (!completed[0]) {
                publishRuntimeState(action, runtimeOutcome);
            }
        }
        return runtimeOutcome;
    }

    private void publishManualCompletion(
        SceneSyncRuntimeState.Action action,
        SceneManualConflictController.Outcome outcome
    ) {
        if (outcome == null || outcome.kind == null) {
            publishRuntimeState(
                action,
                SceneSyncRuntimeState.Outcome.FAILED
            );
            return;
        }
        try {
            List<String> conflictNames = conflictStore == null
                ? Collections.emptyList()
                : conflictStore.listCompleteSceneNames();
            publishRuntimeSceneState(
                mergeManualSummary(outcome, conflictNames),
                conflictNames.size(),
                action,
                mapManualOutcome(outcome.kind)
            );
        } catch (RuntimeException e) {
            Log.e(
                TAG,
                "Could not refresh manual Scene state action="
                    + action
                    + " scene="
                    + String.valueOf(outcome.sceneName),
                e
            );
            publishRuntimeState(
                action,
                SceneSyncRuntimeState.Outcome.FAILED
            );
        }
    }

    private List<SceneSyncRuntimeState.SceneSummary> mergeManualSummary(
        SceneManualConflictController.Outcome outcome,
        List<String> conflictNames
    ) {
        Map<String, SceneSyncRuntimeState.SceneSummary> byName =
            new java.util.TreeMap<>();
        synchronized (runtimeSnapshotLock) {
            for (SceneSyncRuntimeState.SceneSummary summary
                : runtimeSceneSummaries) {
                byName.put(summary.sceneName, summary);
            }
        }

        SceneSyncRuntimeState.SceneSummary previous =
            byName.get(outcome.sceneName);
        SceneSyncRuntimeState.Direction direction;
        switch (outcome.kind) {
            case GAME_APPLIED:
                direction = SceneSyncRuntimeState.Direction.GAME_TO_HET;
                break;
            case HET_APPLIED:
            case HET_PENDING_OFFLINE:
                direction = SceneSyncRuntimeState.Direction.HET_TO_GAME;
                break;
            case FAILED:
            default:
                direction = previous == null
                    ? SceneSyncRuntimeState.Direction.UNKNOWN
                    : previous.direction;
                break;
        }
        boolean stillConflicted = conflictNames.contains(outcome.sceneName);
        SceneSyncRuntimeState.Status status =
            stillConflicted
                ? SceneSyncRuntimeState.Status.NEEDS_ATTENTION
                : outcome.kind
                    == SceneManualConflictController.OutcomeKind.FAILED
                        ? SceneSyncRuntimeState.Status.NOT_PROCESSED
                        : SceneSyncRuntimeState.Status.PROCESSED;
        byName.put(
            outcome.sceneName,
            new SceneSyncRuntimeState.SceneSummary(
                outcome.sceneName,
                direction,
                status
            )
        );

        for (String conflictName : conflictNames) {
            SceneSyncRuntimeState.SceneSummary existing =
                byName.get(conflictName);
            byName.put(
                conflictName,
                new SceneSyncRuntimeState.SceneSummary(
                    conflictName,
                    existing == null
                        ? SceneSyncRuntimeState.Direction.LOCAL
                        : existing.direction,
                    SceneSyncRuntimeState.Status.NEEDS_ATTENTION
                )
            );
        }
        return new ArrayList<>(byName.values());
    }

    private static SceneSyncRuntimeState.Outcome mapManualOutcome(
        SceneManualConflictController.OutcomeKind kind
    ) {
        switch (kind) {
            case GAME_APPLIED:
            case HET_APPLIED:
                return SceneSyncRuntimeState.Outcome.SUCCEEDED;
            case HET_PENDING_OFFLINE:
                return SceneSyncRuntimeState.Outcome.NEEDS_ATTENTION;
            case FAILED:
            default:
                return SceneSyncRuntimeState.Outcome.FAILED;
        }
    }

    private static SceneSyncRuntimeState.Outcome mapTriggerResult(
        SceneSyncCoordinator.TriggerResult result
    ) {
        if (result == null) {
            return SceneSyncRuntimeState.Outcome.FAILED;
        }
        switch (result) {
            case STARTED:
                return SceneSyncRuntimeState.Outcome.STARTED;
            case DEFERRED_ACTIVE_API:
            case DEFERRED_BUSY:
                return SceneSyncRuntimeState.Outcome.DEFERRED;
            case REJECTED_BUSY:
                return SceneSyncRuntimeState.Outcome.BUSY;
            case LOCAL_ONLY:
                return SceneSyncRuntimeState.Outcome.LOCAL_ONLY;
            case CLOSED:
                return SceneSyncRuntimeState.Outcome.UNAVAILABLE;
            case FAILED:
            default:
                return SceneSyncRuntimeState.Outcome.FAILED;
        }
    }

    private void enforceAllowedCaller() {
        try {
            CallerVerifier.enforceAllowedCaller(
                this,
                HetBridgeContract.TARGET_PACKAGE
            );
        } catch (SecurityException e) {
            Log.w(TAG, e.getMessage());
            throw e;
        }
    }

    private void abortActiveSceneOperation(IGameScenePort port) {
        if (port == null) {
            return;
        }
        IBinder binder = port.asBinder();
        ActiveOperation active = activeSceneOperation.get();
        if (active == null
            || (active.port != port && active.binder != binder)) {
            return;
        }
        if (activeSceneOperation.compareAndSet(active, null)) {
            active.close();
        }
    }

    private void promoteToForeground() {
        Notification notification =
            TranslationStatusNotification.buildForeground(this);
        int notificationId =
            TranslationStatusNotification.NOTIFICATION_ID;

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                notificationId,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
            );
        } else {
            startForeground(notificationId, notification);
        }
    }

    /**
     * Unified Service-level API work signal. It only tells both independent
     * schedulers that work may exist; the global concurrency permit decides
     * who can claim.
     */
    private void scheduleApiWorkDrain() {
        if (!shouldScheduleApiWorkDrain(apiWorkOpen, foregroundStarted)) {
            return;
        }
        SummaryTaskExecutor executor = summaryTaskExecutor;
        boolean summaryPending = false;
        SummaryJobStore summaries = summaryJobStore;
        if (executor != null && summaries != null) {
            try {
                summaryPending = summaries.hasPendingJobs();
            } catch (Exception e) {
                Log.w(TAG, "Could not inspect pending Summary work", e);
            }
        }
        if (executor != null) {
            executor.scheduleDrain();
        }
        // With a single API permit, do not even schedule Translation until
        // the Summary drain observes an empty queue. This is a scheduling
        // boundary, not a best-effort ordering of two executor submissions.
        if (shouldDeferTranslationDrain(summaryPending, apiConcurrencyGate)) {
            return;
        }
        scheduleTranslationDrain();
    }

    /** Package seam for proving the single-permit Summary scheduling boundary. */
    static boolean shouldDeferTranslationDrain(
        boolean summaryPending,
        ApiConcurrencyGate gate
    ) {
        return summaryPending
            && (gate == null || gate.getGlobalLimit() == 1);
    }

    /**
     * Handles a durable Summary admission while this Service instance is
     * already running.  A bound-only instance has executed onCreate/onBind
     * but has not reached the foreground/startup sequence; report that state
     * to the wake helper so it also sends the explicit start action instead of
     * silently dropping the wake signal.
     */
    private void wakeSummaryWork() {
        SummaryTaskExecutor executor = summaryTaskExecutor;
        if (shouldOwnSummaryWake(
            acceptingSummaryWake,
            foregroundStarted,
            startupStarted,
            executor != null
        )) {
            scheduleApiWorkDrain();
        }
    }

    /** Package seam for the durable Summary wake ownership contract. */
    static boolean shouldOwnSummaryWake(
        boolean accepting,
        boolean foregroundStarted,
        boolean startupStarted,
        boolean executorAvailable
    ) {
        return accepting
            && foregroundStarted
            && startupStarted
            && executorAvailable;
    }

    /** Package seam for the Binder Scene-port lifecycle admission check. */
    static boolean isScenePortAdmissionAllowed(boolean accepting) {
        return accepting;
    }

    /** Package seam for preserving a successful Summary recovery boundary. */
    static boolean shouldPrepareSummaryStore(boolean startupPrepared) {
        return !startupPrepared;
    }

    /** Package seam for retaining a store whose recovery boundary is fixed. */
    static boolean shouldRetainSummaryStore(
        boolean storeExists,
        boolean startupPrepared
    ) {
        return storeExists && startupPrepared;
    }

    /** Package seam for rejecting callbacks from an invalidated runtime. */
    static boolean isSceneOperationGenerationCurrent(
        boolean lifecycleOpen,
        long currentGeneration,
        long callbackGeneration
    ) {
        return lifecycleOpen && currentGeneration == callbackGeneration;
    }

    private void scheduleTranslationDrain() {
        TranslationTaskExecutor executor = taskExecutor;
        if (!foregroundStarted
            || executor == null
            || jobStore == null
            || !jobStore.hasPendingJobs()) {
            return;
        }
        executor.scheduleDrain();
    }

    private static List<SceneSyncRuntimeState.SceneSummary>
        mapOperationSceneSummaries(
            List<SceneSyncOperation.SceneSummary> summaries
        ) {
        ArrayList<SceneSyncRuntimeState.SceneSummary> mapped =
            new ArrayList<>(summaries.size());
        for (SceneSyncOperation.SceneSummary summary : summaries) {
            mapped.add(
                new SceneSyncRuntimeState.SceneSummary(
                    summary.sceneName,
                    mapRuntimeDirection(summary.direction),
                    mapRuntimeStatus(summary.status)
                )
            );
        }
        return mapped;
    }

    private static SceneSyncRuntimeState.Direction mapRuntimeDirection(
        SceneSyncOperation.Direction direction
    ) {
        if (direction == null) {
            return SceneSyncRuntimeState.Direction.UNKNOWN;
        }
        switch (direction) {
            case GAME_TO_HET:
                return SceneSyncRuntimeState.Direction.GAME_TO_HET;
            case HET_TO_GAME:
                return SceneSyncRuntimeState.Direction.HET_TO_GAME;
            case BIDIRECTIONAL:
                return SceneSyncRuntimeState.Direction.BIDIRECTIONAL;
            case LOCAL:
                return SceneSyncRuntimeState.Direction.LOCAL;
            case UNKNOWN:
            default:
                return SceneSyncRuntimeState.Direction.UNKNOWN;
        }
    }

    private static SceneSyncRuntimeState.Status mapRuntimeStatus(
        SceneSyncOperation.SceneStatus status
    ) {
        if (status == null) {
            return SceneSyncRuntimeState.Status.NOT_PROCESSED;
        }
        switch (status) {
            case PROCESSED:
                return SceneSyncRuntimeState.Status.PROCESSED;
            case DELETED:
                return SceneSyncRuntimeState.Status.DELETED;
            case NEEDS_ATTENTION:
                return SceneSyncRuntimeState.Status.NEEDS_ATTENTION;
            case NOT_PROCESSED:
            default:
                return SceneSyncRuntimeState.Status.NOT_PROCESSED;
        }
    }

    private static boolean containsNeedsAttention(
        List<SceneSyncRuntimeState.SceneSummary> summaries
    ) {
        for (SceneSyncRuntimeState.SceneSummary summary : summaries) {
            if (summary.status
                == SceneSyncRuntimeState.Status.NEEDS_ATTENTION) {
                return true;
            }
        }
        return false;
    }

    /** Runs one cycle against the immutable coordinator-captured port tuple. */
    private void runFullSceneSync(
        SceneSyncCoordinator.PortSnapshot portSnapshot,
        SceneSyncCoordinator.SyncOperationKind operationKind,
        long sceneRuntimeGeneration
    ) throws Exception {
        SceneSyncRuntimeState.Action runtimeAction =
            operationKind
                == SceneSyncCoordinator.SyncOperationKind.MANUAL_REFRESH
                ? SceneSyncRuntimeState.Action.MANUAL_REFRESH
                : SceneSyncRuntimeState.Action.AUTO_SYNC;
        boolean terminalStatePublished = false;
        SceneStore.MutationAdmission.FullSyncLease fullSyncLease = null;
        try {
            if (portSnapshot == null
                || !(portSnapshot.port instanceof IGameScenePort)
                || operationKind == null) {
                throw new IOException("FULL_SYNC port snapshot is invalid");
            }
            SceneStore sceneStoreSnapshot;
            ConflictStore conflictStoreSnapshot;
            PendingSceneApplyStore pendingSceneApplyStoreSnapshot;
            synchronized (sceneOperationLifecycleLock) {
                if (!isSceneOperationGenerationCurrent(
                    sceneOperationLifecycleOpen,
                    sceneOperationLifecycleGeneration,
                    sceneRuntimeGeneration
                )) {
                    throw new IOException(
                        "FULL_SYNC Scene runtime is no longer active"
                    );
                }
                sceneStoreSnapshot = sceneStore;
                conflictStoreSnapshot = conflictStore;
                pendingSceneApplyStoreSnapshot = pendingSceneApplyStore;
                if (sceneStoreSnapshot == null
                    || conflictStoreSnapshot == null
                    || pendingSceneApplyStoreSnapshot == null) {
                    throw new IOException(
                        "FULL_SYNC Scene stores are unavailable"
                    );
                }
            }
            IGameScenePort port = (IGameScenePort) portSnapshot.port;
            if (port.asBinder() != portSnapshot.binder) {
                throw new IOException(
                    "FULL_SYNC port Binder identity changed"
                );
            }

            fullSyncLease = SceneStore.beginFullSyncAdmission();
            ConflictStore.RecoveryReport conflictRecovery =
                conflictStoreSnapshot.recover();
            PendingSceneApplyStore.RecoveryReport pendingRecovery =
                pendingSceneApplyStoreSnapshot.recover();
            Log.i(
                TAG,
                "FULL_SYNC pre-snapshot recovery conflicts removed="
                    + conflictRecovery.removedTemporaryDirectories.size()
                    + " invalid="
                    + conflictRecovery.invalidFormalDirectories.size()
                    + " pending valid="
                    + pendingRecovery.validSceneNames.size()
                    + " discarded="
                    + pendingRecovery.discardedSceneNames.size()
            );

            if (!publishRuntimeStateIfCurrent(
                sceneRuntimeGeneration,
                runtimeAction,
                SceneSyncRuntimeState.Outcome.STARTED
            )) {
                return;
            }

            ScenePolicyPublisher.Target policyTarget =
                scenePolicyPublisher.capture(
                    port,
                    portSnapshot.binder,
                    portSnapshot.generation
                );
            if (policyTarget == null) {
                throw new IOException("FULL_SYNC policy target is stale");
            }

            SceneSyncCycleSnapshot cycleSnapshot =
                captureSceneSyncCycleSnapshot();
            SceneSyncOperation operation = new SceneSyncOperation(
                sceneStoreSnapshot,
                port,
                portSnapshot.sceneWorkerCount,
                new SceneConflictResolver(conflictStoreSnapshot),
                pendingSceneApplyStoreSnapshot,
                cycleSnapshot,
                scenePolicyPublisher,
                policyTarget,
                () -> {},
                fullSyncLease,
                pendingRecovery
            );
            ActiveOperation active = new ActiveOperation(
                port,
                portSnapshot.binder instanceof IBinder
                    ? (IBinder) portSnapshot.binder
                    : port.asBinder(),
                portSnapshot.generation,
                operation
            );
            boolean accepted;
            synchronized (sceneOperationLifecycleLock) {
                accepted = isSceneOperationGenerationCurrent(
                    sceneOperationLifecycleOpen,
                    sceneOperationLifecycleGeneration,
                    sceneRuntimeGeneration
                ) && activeSceneOperation.compareAndSet(null, active);
            }
            if (!accepted) {
                operation.close();
                throw new IOException(
                    "FULL_SYNC Scene runtime is no longer active"
                );
            }
            boolean operationStarted = true;
            long repairGeneration = startupRepairGeneration;
            try {
                SceneSyncOperation.Result result = operation.run();
                List<SceneSyncRuntimeState.SceneSummary> summaries =
                    mapOperationSceneSummaries(result.sceneSummaries);
                int pendingConflictCount =
                    conflictStoreSnapshot.listCompleteSceneNames().size();
                SceneSyncRuntimeState.Outcome terminalOutcome;
                if (!result.success) {
                    terminalOutcome = SceneSyncRuntimeState.Outcome.FAILED;
                } else if (pendingConflictCount > 0
                    || containsNeedsAttention(summaries)) {
                    terminalOutcome =
                        SceneSyncRuntimeState.Outcome.NEEDS_ATTENTION;
                } else {
                    terminalOutcome =
                        SceneSyncRuntimeState.Outcome.SUCCEEDED;
                }
                terminalStatePublished =
                    publishRuntimeSceneStateIfCurrent(
                    sceneRuntimeGeneration,
                    summaries,
                    pendingConflictCount,
                    runtimeAction,
                    terminalOutcome
                );
                if (!result.success) {
                    throw new IOException(
                        "FULL_SYNC failed: " + result.failureKind
                            + " blocked="
                            + result.blockedSceneNames.size()
                            + " rejected="
                            + result.rejectedSceneNames.size()
                    );
                }
            } finally {
                synchronized (sceneOperationLifecycleLock) {
                    activeSceneOperation.compareAndSet(active, null);
                }
                if (operationStarted
                    && operationKind
                        == SceneSyncCoordinator.SyncOperationKind
                            .AUTO_FULL_SYNC
                    && isSceneOperationGenerationCurrent(
                        sceneOperationLifecycleOpen,
                        sceneOperationLifecycleGeneration,
                        sceneRuntimeGeneration
                    )) {
                    scheduleInitialAutoSyncBarrier(repairGeneration);
                }
            }
        } catch (Exception e) {
            if (!terminalStatePublished) {
                publishRuntimeStateIfCurrent(
                    sceneRuntimeGeneration,
                    runtimeAction,
                    SceneSyncRuntimeState.Outcome.FAILED
                );
            }
            throw e;
        } finally {
            if (fullSyncLease != null) {
                fullSyncLease.close();
            }
        }
    }

    /** Signals the JobStore only after the first real AUTO cycle is cleaned up. */
    private void scheduleInitialAutoSyncBarrier(long repairGeneration) {
        TranslationJobStore store = jobStore;
        if (store == null) {
            return;
        }
        try {
            startupRepairExecutor.execute(() -> {
                try {
                    boolean retryRequired = store.signalFirstAutoSyncFinished(
                        repairGeneration
                    );
                    if (retryRequired && !startupRepairExecutor.isShutdown()) {
                        scheduleStartupRepair(0L);
                    }
                } catch (Exception e) {
                    Log.w(
                        TAG,
                        "Could not signal first AUTO Scene Sync barrier "
                            + "generation="
                        + repairGeneration,
                        e
                    );
                } finally {
                    requestTerminalReplayScan();
                    notifyStartupWaiters();
                }
            });
        } catch (RejectedExecutionException e) {
            Log.w(
                TAG,
                "Could not enqueue first AUTO Scene Sync barrier "
                    + "generation="
                    + repairGeneration,
                e
            );
        }
    }

    private void runLocalSceneRefresh(
        SceneSyncCoordinator.PortSnapshot snapshot,
        long sceneRuntimeGeneration
    ) {
        SceneSyncRuntimeState.Action action =
            SceneSyncRuntimeState.Action.LOCAL_REFRESH;
        try {
            ConflictStore conflictStoreSnapshot;
            SceneStore sceneStoreSnapshot;
            synchronized (sceneOperationLifecycleLock) {
                if (!isSceneOperationGenerationCurrent(
                    sceneOperationLifecycleOpen,
                    sceneOperationLifecycleGeneration,
                    sceneRuntimeGeneration
                )) {
                    return;
                }
                conflictStoreSnapshot = conflictStore;
                sceneStoreSnapshot = sceneStore;
                if (conflictStoreSnapshot == null
                    || sceneStoreSnapshot == null) {
                    throw new IOException("Local Scene stores are unavailable");
                }
            }
            if (!publishRuntimeStateIfCurrent(
                sceneRuntimeGeneration,
                action,
                SceneSyncRuntimeState.Outcome.STARTED
            )) {
                return;
            }
            ConflictStore.RecoveryReport conflictRecovery =
                conflictStoreSnapshot.recover();
            Log.i(
                TAG,
                "Local Scene conflict recovery removed="
                    + conflictRecovery.removedTemporaryDirectories.size()
                    + " invalid="
                    + conflictRecovery.invalidFormalDirectories.size()
            );

            Map<String, SceneSyncRuntimeState.SceneSummary> byName =
                new java.util.TreeMap<>();
            List<SceneStore.ValidatedScene> validScenes =
                sceneStoreSnapshot.listValidScenes();
            for (SceneStore.ValidatedScene scene : validScenes) {
                byName.put(
                    scene.sceneName,
                    new SceneSyncRuntimeState.SceneSummary(
                        scene.sceneName,
                        SceneSyncRuntimeState.Direction.LOCAL,
                        SceneSyncRuntimeState.Status.PROCESSED
                    )
                );
            }

            List<String> conflictNames = conflictRecovery.completeSceneNames;
            for (String sceneName : conflictNames) {
                byName.put(
                    sceneName,
                    new SceneSyncRuntimeState.SceneSummary(
                        sceneName,
                        SceneSyncRuntimeState.Direction.LOCAL,
                        SceneSyncRuntimeState.Status.NEEDS_ATTENTION
                    )
                );
            }
            publishRuntimeSceneStateIfCurrent(
                sceneRuntimeGeneration,
                new ArrayList<>(byName.values()),
                conflictNames.size(),
                action,
                SceneSyncRuntimeState.Outcome.LOCAL_ONLY
            );
            Log.i(
                TAG,
                "Local Scene refresh completed valid="
                    + validScenes.size()
                    + " conflicts="
                    + conflictNames.size()
                    + "; invalid Store candidates were skipped"
            );
        } catch (IOException | RuntimeException e) {
            Log.e(TAG, "Local Scene refresh failed", e);
            publishRuntimeStateIfCurrent(
                sceneRuntimeGeneration,
                action,
                SceneSyncRuntimeState.Outcome.FAILED
            );
        }
    }

    /**
     * Starts the linear startup coordinator. This is idempotent and runs after
     * the foreground notification so persistent scanning never happens before
     * {@code startForeground()}.
     */
    private void startStartupCoordinator() {
        synchronized (startupLock) {
            if (startupStarted) {
                return;
            }
            startupStarted = true;
        }
        try {
            StartupCoordinator coordinator = new StartupCoordinator(
                startupCoordinatorExecutor,
                this::prepareStartupStores,
                this::awaitSceneSyncRelease,
                () -> {
                    TerminalDeliveryCoordinator delivery = terminalDelivery;
                    if (delivery != null) {
                        delivery.release();
                    }
                },
                new StartupCoordinator.ReviewController() {
                    @Override
                    public boolean isEnabled() {
                        return ContextReviewGate.get().isEnabled();
                    }

                    @Override
                    public void awaitDecision() throws Exception {
                        ContextReviewGate.get().awaitDecision();
                    }
                },
                this::awaitRecoveryDecisions,
                this::openApiWork,
                this::onStartupCoordinatorFailed
            );
            startupCoordinator = coordinator;
            coordinator.start();
        } catch (RuntimeException e) {
            Log.e(TAG, "Could not start startup coordinator", e);
            onStartupCoordinatorFailed(e);
        }
    }

    private void prepareStartupStores() throws Exception {
        if (startupPreparationComplete) {
            return;
        }
        try {
            prepareStartupStoresOnce();
            startupPreparationComplete = true;
        } catch (Exception | Error failure) {
            closeIncompleteStartupRuntime();
            throw failure;
        }
    }

    private void prepareStartupStoresOnce() throws Exception {
        ensureTranslationJobStore();
        ContextReviewGate.get().prepare(isStartupReviewEnabled());

        // All persistent Stores/Executors/Coordinators are built on the
        // background startup coordinator after startForeground(). onCreate()
        // intentionally stays lightweight; Binder admission can still persist
        // through ensureTranslationJobStore() and is reconciled below.
        sceneStore = new SceneStore(this);
        conflictStore = new ConflictStore(this);
        pendingSceneApplyStore = new PendingSceneApplyStore(
            new File(getFilesDir(), PendingSceneApplyStore.DIRECTORY_NAME),
            sceneStore
        );

        SceneSyncCoordinator preparedSceneSyncCoordinator;
        long sceneRuntimeGeneration;
        synchronized (sceneOperationLifecycleLock) {
            sceneRuntimeGeneration = openSceneOperationRuntime();
            preparedSceneSyncCoordinator = new SceneSyncCoordinator(
                sceneSyncOperationExecutor,
                (snapshot, operationKind) -> runFullSceneSync(
                    snapshot,
                    operationKind,
                    sceneRuntimeGeneration
                ),
                snapshot -> runLocalSceneRefresh(
                    snapshot,
                    sceneRuntimeGeneration
                )
            );
            if (!isSceneOperationGenerationCurrent(
                sceneOperationLifecycleOpen,
                sceneOperationLifecycleGeneration,
                sceneRuntimeGeneration
            )) {
                preparedSceneSyncCoordinator.close();
                throw new IOException(
                    "Scene runtime was invalidated during startup"
                );
            }
            preparedSceneSyncCoordinator.setOperationFinishedListener(
                () -> onSceneOperationFinished(sceneRuntimeGeneration)
            );
            sceneSyncCoordinator = preparedSceneSyncCoordinator;
        }
        replayCurrentScenePortToCoordinator();
        manualConflictController = new SceneManualConflictController(
            preparedSceneSyncCoordinator,
            sceneStore,
            conflictStore,
            pendingSceneApplyStore,
            scenePolicyPublisher
        );

        ensureSceneContextStoreForAdmission();
        backfillPendingHistoryAdmissions();

        SummaryJobStore preparedSummaryStore = summaryJobStore;
        if (preparedSummaryStore == null) {
            preparedSummaryStore = SummaryJobStore.createForAndroid(this);
            summaryJobStore = preparedSummaryStore;
        }
        preparedSummaryStore.setRecoveryDecisionListener(
            this::notifyStartupWaiters
        );
        summaryTaskExecutor = SummaryTaskExecutor.createForAndroid(
            this,
            summaryJobStore
        );
        summaryTaskExecutor.setDrainFinishedListener(
            this::scheduleTranslationDrain
        );
        contextCompressionCoordinator = new ContextCompressionCoordinator(
            sceneContextStore,
            summaryJobStore
        );
        groupCompressionCoordinator = new GroupCompressionCoordinator(
            sceneContextStore,
            summaryJobStore,
            contextCompressionCoordinator
        );
        summaryTaskExecutor.setContextFinalWrittenListener(
            (contextId, targetLang) -> {
                try {
                    GroupCompressionCoordinator.Result result =
                        groupCompressionCoordinator.onContextFinalWritten(
                            contextId,
                            targetLang,
                            loadGroupCompressionOptions()
                        );
                    if (result.groupJobCreated || result.finalJobsRequested) {
                        scheduleApiWorkDrain();
                    }
                } catch (Exception e) {
                    Log.w(
                        TAG,
                        "Could not reconcile group snapshot after context "
                            + "final summary contextId=" + contextId,
                        e
                    );
                }
            }
        );
        sceneContextStore.setActiveContextChangeListener(
            (previousContextId, newContextId) -> {
                try {
                    ContextCompressionCoordinator.Options contextOptions =
                        loadContextCompressionOptions();
                    contextCompressionCoordinator.onActiveContextChanged(
                        previousContextId,
                        contextOptions
                    );
                    GroupCompressionCoordinator.Options groupOptions =
                        loadGroupCompressionOptions();
                    GroupCompressionCoordinator.Result groupResult =
                        groupCompressionCoordinator.onActiveContextChanged(
                            previousContextId,
                            newContextId,
                            groupOptions
                        );
                    if (groupResult.groupJobCreated
                        || groupResult.finalJobsRequested) {
                        scheduleApiWorkDrain();
                    }
                } catch (Exception e) {
                    Log.w(
                        TAG,
                        "Could not reconcile active context/group after "
                            + "switch previous=" + previousContextId
                            + " new=" + newContextId,
                        e
                    );
                }
            }
        );
        terminalDelivery = new TerminalDeliveryCoordinator(
            new TerminalDeliveryCoordinator.Store() {
                @Override
                public List<TranslationJobStore.TerminalJob>
                    listPendingTerminalJobs() throws Exception {
                    return jobStore.listPendingTerminalJobs();
                }

                @Override
                public TranslationJobStore.TerminalJob
                    readPendingTerminalJob(String requestId) throws Exception {
                    return jobStore.readPendingTerminalJob(requestId);
                }

                @Override
                public byte[] readCompletedResult(String requestId)
                    throws Exception {
                    return jobStore.readCompletedResult(requestId);
                }

                @Override
                public byte[] readFailedError(String requestId)
                    throws Exception {
                    return jobStore.readFailedError(requestId);
                }
            }
        );
        bindCurrentCallbackToTerminalDelivery();

        JSONObject userSettings = new ConfigStore(this)
            .load()
            .config
            .getJSONObject("UserSettings");
        JSONObject queueSettings = userSettings.getJSONObject(
            "TranslationQueue"
        );
        boolean autoRecover = queueSettings.getBoolean(
            "AutoRecoverPreviousJobs"
        );
        TranslationJobStore.RecoverySortOrder sortOrder =
            TranslationJobStore.RecoverySortOrder.fromConfigValue(
                queueSettings.getString("RecoverySortOrder")
            );

        startupRepairGeneration = jobStore.prepareForServiceStart(
            autoRecover,
            sortOrder
        );
        if (shouldPrepareSummaryStore(summaryStartupPrepared)) {
            preparedSummaryStore.prepareForServiceStart(
                ConfigStore.getSummaryAutoRecoverPreviousJobs(userSettings),
                sceneContextStore
            );
            summaryStartupPrepared = true;
        }
        TranslationStatusNotification.setJobStore(jobStore);

        int globalLimit = ConfigStore.getApiConcurrency(userSettings);
        apiConcurrencyGate = new ApiConcurrencyGate(globalLimit);
        taskExecutor = new TranslationTaskExecutor(
            this,
            jobStore,
            resultListener,
            globalLimit
        );
        activeTaskExecutor = taskExecutor;
        taskExecutor.setSceneSyncCoordinator(sceneSyncCoordinator);
        taskExecutor.setActivityListener(this::publishApiActivity);
        taskExecutor.setApiConcurrencyGate(apiConcurrencyGate);
        summaryTaskExecutor.setApiConcurrencyGate(apiConcurrencyGate);
        jobStore.setQueueListener(queueListener);

        requireSceneRuntimeCurrent(sceneRuntimeGeneration);
        scheduleStartupRepair();
        requireSceneRuntimeCurrent(sceneRuntimeGeneration);
    }

    /** Releases only components which a failed preparation may have created. */
    private void closeIncompleteStartupRuntime() {
        SceneRuntimeDetach detachedSceneRuntime =
            invalidateSceneOperationRuntime();
        if (detachedSceneRuntime.activeOperation != null) {
            detachedSceneRuntime.activeOperation.close();
        }
        TranslationTaskExecutor translationExecutor = taskExecutor;
        taskExecutor = null;
        activeTaskExecutor = null;
        if (translationExecutor != null) {
            translationExecutor.setActivityListener(null);
            translationExecutor.shutdown();
        }
        SummaryTaskExecutor summaryExecutor = summaryTaskExecutor;
        summaryTaskExecutor = null;
        if (summaryExecutor != null) {
            summaryExecutor.shutdown();
        }
        TerminalDeliveryCoordinator delivery = terminalDelivery;
        terminalDelivery = null;
        if (delivery != null) {
            delivery.close();
        }
        if (detachedSceneRuntime.coordinator != null) {
            detachedSceneRuntime.coordinator.close();
        }
        synchronized (scenePortLock) {
            resetScenePortRegistration(currentScenePort);
        }
        ApiConcurrencyGate gate = apiConcurrencyGate;
        apiConcurrencyGate = null;
        if (gate != null) {
            gate.close();
        }
        if (jobStore != null) {
            jobStore.clearQueueListener(queueListener);
        }
        SceneContextStore contexts = sceneContextStore;
        if (contexts != null) {
            contexts.setActiveContextChangeListener((previous, current) -> { });
        }
        TranslationStatusNotification.setJobStore(null);
        contextCompressionCoordinator = null;
        groupCompressionCoordinator = null;
        manualConflictController = null;
        SummaryJobStore summaries = summaryJobStore;
        if (shouldRetainSummaryStore(
            summaries != null,
            summaryStartupPrepared
        )) {
            summaries.setRecoveryDecisionListener(() -> { });
        } else {
            summaryJobStore = null;
        }
        pendingSceneApplyStore = null;
        conflictStore = null;
        sceneStore = null;
    }

    private static void resetScenePortRegistration(ScenePortRecord record) {
        if (record != null) {
            record.registeredWithCoordinator = false;
        }
    }

    private long openSceneOperationRuntime() throws IOException {
        synchronized (sceneOperationLifecycleLock) {
            if (!acceptingSummaryWake) {
                throw new IOException(
                    "TranslationService is no longer accepting startup work"
                );
            }
            sceneOperationLifecycleGeneration++;
            sceneOperationLifecycleOpen = true;
            return sceneOperationLifecycleGeneration;
        }
    }

    private void onSceneOperationFinished(long sceneRuntimeGeneration) {
        synchronized (sceneOperationLifecycleLock) {
            if (!isSceneOperationGenerationCurrent(
                sceneOperationLifecycleOpen,
                sceneOperationLifecycleGeneration,
                sceneRuntimeGeneration
            )) {
                return;
            }
            republishRuntimeState();
            scheduleApiWorkDrain();
        }
    }

    private void requireSceneRuntimeCurrent(long sceneRuntimeGeneration)
        throws IOException {
        synchronized (sceneOperationLifecycleLock) {
            if (!isSceneOperationGenerationCurrent(
                    sceneOperationLifecycleOpen,
                    sceneOperationLifecycleGeneration,
                    sceneRuntimeGeneration
                )
                || !acceptingSummaryWake) {
                throw new IOException(
                    "TranslationService Scene runtime is no longer active"
                );
            }
        }
    }

    private SceneRuntimeDetach invalidateSceneOperationRuntime() {
        synchronized (sceneOperationLifecycleLock) {
            sceneOperationLifecycleOpen = false;
            sceneOperationLifecycleGeneration++;
            ActiveOperation active = activeSceneOperation.getAndSet(null);
            SceneSyncCoordinator coordinator = sceneSyncCoordinator;
            if (coordinator != null) {
                coordinator.setOperationFinishedListener(() -> { });
            }
            sceneSyncCoordinator = null;
            return new SceneRuntimeDetach(active, coordinator);
        }
    }

    /**
     * Ensures the Translation job store exists for Binder admission. This is
     * intentionally the smallest store needed to persist an incoming job
     * before the full background runtime has been built; it does not scan.
     */
    private synchronized void ensureTranslationJobStore() {
        if (jobStore == null) {
            jobStore = TranslationJobStore.getInstance(this);
            jobStore.beginServiceStart();
        }
    }

    /** Lazily builds the Context store so Binder admission never treats an
     * unconstructed store as an explicit no-history mapping. */
    private SceneContextStore ensureSceneContextStoreForAdmission() {
        synchronized (contextStoreLock) {
            if (sceneContextStore != null) {
                return sceneContextStore;
            }
            try {
                sceneContextStore = new SceneContextStore(this);
                return sceneContextStore;
            } catch (RuntimeException e) {
                Log.w(
                    TAG,
                    "Could not lazily initialize SceneContextStore; "
                        + "admission will retain a durable compensation",
                    e
                );
                return null;
            }
        }
    }

    private Object resolveAdmissionHistoryMapping() throws Exception {
        SceneContextStore store = ensureSceneContextStoreForAdmission();
        return store == null ? null : store.resolveActiveHistoryMapping();
    }

    /** Replays every durable Context membership compensation without a
     * volatile request-id list.  Failed entries remain marked for retry. */
    private void backfillPendingHistoryAdmissions() {
        SceneContextStore store = sceneContextStore;
        if (store == null || jobStore == null) {
            return;
        }
        final List<String> requestIds;
        try {
            requestIds = jobStore.listHistoryMembershipPendingRequestIds();
        } catch (Exception e) {
            Log.w(TAG, "Could not list pending history admissions", e);
            return;
        }
        for (String requestId : requestIds) {
            try {
                Object mapping = jobStore.readHistoryMapping(requestId);
                if (HistoryMapping.resolutionOfValue(mapping)
                    != HistoryMapping.Resolution.VALID) {
                    // A marked Job without a frozen route is not safe to
                    // guess from a later Active pointer.  Keep it isolated
                    // for explicit repair instead of changing its meaning.
                    continue;
                }
                JSONObject state = jobStore.readState(requestId);
                String scene = state.optString("scene", "");
                SceneContextStore.withRootAccess(() -> {
                    if (!appendSceneToHistoryContext(store, mapping, scene)) {
                        throw new IllegalStateException(
                            "Context membership append did not complete "
                                + "requestId=" + requestId
                        );
                    }
                    jobStore.completeHistoryMembershipAdmission(
                        requestId,
                        mapping
                    );
                    return null;
                });
            } catch (Exception e) {
                Log.w(
                    TAG,
                    "Could not backfill history membership requestId="
                        + requestId,
                    e
                );
            }
        }
    }

    /**
     * Adds a newly persisted Translation Job's Scene to the Context selected by
     * its History Mapping. Explicit no-history mappings and a not-yet-built
     * store are no-ops; malformed mappings or storage failures are reported as
     * failure so the Binder admission does not claim success.
     */
    static boolean appendSceneToHistoryContext(
        SceneContextStore store,
        Object historyMapping,
        String sceneName
    ) throws Exception {
        return SceneContextStore.withRootAccess(() ->
            appendSceneToHistoryContextLocked(store, historyMapping, sceneName)
        );
    }

    private static boolean appendSceneToHistoryContextLocked(
        SceneContextStore store,
        Object historyMapping,
        String sceneName
    ) throws Exception {
        if (historyMapping == null
            || JSONObject.NULL.equals(historyMapping)) {
            return true;
        }
        if (store == null) {
            return false;
        }
        if (!(historyMapping instanceof JSONObject)) {
            return false;
        }
        JSONObject mapping = (JSONObject) historyMapping;
        if (HistoryMapping.resolutionOfValue(mapping)
            != HistoryMapping.Resolution.VALID) {
            return false;
        }
        String contextId = mapping.getString(HistoryMapping.CONTEXT_ID);
        if (contextId == null || contextId.trim().isEmpty()) {
            return false;
        }
        store.appendSceneIfAbsent(contextId, sceneName);
        return true;
    }

    /**
     * Converts an invalid Active Group invariant into a stable Binder
     * disposition and one actionable HET notification.  This is a persisted
     * state correction, not a transient storage outage, so callers must not
     * retry it blindly.
     */
    private int notifyAdmissionUserActionRequired(
        String requestId,
        String message
    ) {
        try {
            TranslationStatusNotification.translationNeedsUserAction(
                this,
                requestId,
                message == null || message.trim().isEmpty()
                    ? "Choose an Active Group containing the Active Context, "
                        + "or clear the Active Group before retrying."
                    : message
            );
        } catch (RuntimeException notificationFailure) {
            Log.w(
                TAG,
                "Could not show Active Group correction notification "
                    + "requestId=" + requestId,
                notificationFailure
            );
        }
        return HetBridgeContract.ENQUEUE_RESULT_USER_ACTION_REQUIRED;
    }

    /**
     * Backfills one early admission: persists the resolved mapping and appends
     * the same Scene membership that a normal admission would have appended.
     */
    static void backfillEarlyAdmissionHistoryMapping(
        SceneContextStore store,
        EarlyAdmissionJobStore jobs,
        String requestId
    ) throws Exception {
        if (store == null || jobs == null || requestId == null) {
            return;
        }
        Object mapping = jobs.resolveHistoryMapping();
        String scene = jobs.readScene(requestId);
        if (!appendSceneToHistoryContext(store, mapping, scene)) {
            throw new IllegalStateException(
                "Context membership append did not complete requestId="
                    + requestId
            );
        }
        jobs.rewriteHistoryMapping(
            requestId,
            mapping == null ? JSONObject.NULL : mapping
        );
    }

    /**
     * Binds a callback that was registered before the terminal delivery
     * coordinator existed. Normal later registrations are bound directly by
     * {@link #registerCallback(ITranslationCallback)}.
     */
    private void bindCurrentCallbackToTerminalDelivery() {
        synchronized (callbackLock) {
            TerminalDeliveryCoordinator coordinator = terminalDelivery;
            CallbackRecord record = currentCallback;
            if (coordinator == null || record == null) {
                return;
            }
            try {
                coordinator.bind(createTerminalDeliveryCallback(record));
            } catch (RuntimeException e) {
                Log.w(
                    TAG,
                    "Could not bind existing callback to terminal delivery",
                    e
                );
            }
        }
    }

    private void awaitSceneSyncRelease() throws InterruptedException {
        synchronized (startupLock) {
            while (!isSceneSyncSatisfiedLocked()) {
                startupLock.wait();
            }
        }
    }

    private boolean isSceneSyncSatisfiedLocked() {
        if (jobStore == null) {
            return false;
        }
        if (jobStore.isInitialAutoSyncFinished()) {
            return true;
        }
        SceneSyncCoordinator coordinator = sceneSyncCoordinator;
        boolean hasGamePort = coordinator != null && coordinator.hasGamePort();
        // With no game port, a settled local startup repair is the best
        // available Scene Sync boundary and must not block API recovery.
        return !hasGamePort && !jobStore.isRepairingStartupJobs();
    }

    private void awaitRecoveryDecisions() throws Exception {
        // Summary recovery is committed at this point, after Scene Sync,
        // terminal release, and the optional Review gate.  The preparation
        // phase only captured directory identities and never changed state.
        SummaryJobStore summaries = summaryJobStore;
        if (jobStore != null) {
            jobStore.openRecoveryDecision();
        }
        if (summaries != null) {
            summaries.openRecoveryDecision();
        }
        if (summaries != null && summaries.isAutomaticRecoveryPending()) {
            summaries.autoRecoverStartupJobs();
        }
        synchronized (startupLock) {
            while (translationRecoveryPending()
                || summaryRecoveryPending()) {
                startupLock.wait();
            }
        }
    }

    private boolean translationRecoveryPending() {
        return jobStore != null
            && jobStore.isManualRecoveryDecisionPending();
    }

    private boolean summaryRecoveryPending() {
        return summaryJobStore != null
            && summaryJobStore.isRecoveryPending();
    }

    private void openApiWork() {
        synchronized (startupLock) {
            apiWorkOpen = true;
        }
        scheduleApiWorkDrain();
    }

    private void notifyStartupWaiters() {
        synchronized (startupLock) {
            startupLock.notifyAll();
        }
        StartupCoordinator coordinator = startupCoordinator;
        if (coordinator != null) {
            coordinator.onStateChanged();
        }
    }

    private void onStartupCoordinatorFailed(Throwable error) {
        Log.e(TAG, "Startup coordinator failed", error);
        synchronized (startupLock) {
            StartupCoordinator failedCoordinator = startupCoordinator;
            startupCoordinator = null;
            startupStarted = false;
            if (failedCoordinator != null) {
                failedCoordinator.close();
            }
            apiWorkOpen = false;
            startupLock.notifyAll();
        }
        handleStartupCoordinatorFailure(error, message -> {
            try {
                TranslationStatusNotification.startupFailed(this, message);
            } catch (RuntimeException notificationFailure) {
                Log.w(
                    TAG,
                    "Could not show startup failure notification",
                    notificationFailure
                );
            }
        });
        try {
            TranslationStatusNotification.refresh(this);
        } catch (RuntimeException notificationFailure) {
            Log.w(
                TAG,
                "Could not refresh notification after startup failure",
                notificationFailure
            );
        }
    }

    private static String safeStartupMessage(Throwable error) {
        String message = error.getMessage();
        return message == null || message.trim().isEmpty()
            ? error.getClass().getSimpleName()
            : message;
    }

    private void scheduleStartupRepair() {
        scheduleStartupRepair(0L);
    }

    private void scheduleStartupRepair(long delayMs) {
        if (jobStore == null
            || !jobStore.isRepairingStartupJobs()
            || !startupRepairScheduled.compareAndSet(false, true)) {
            return;
        }

        final long repairGeneration = startupRepairGeneration;
        try {
            startupRepairExecutor.schedule(() -> {
                boolean retryRequired = false;
                try {
                    retryRequired = jobStore.repairDamagedStartupJobs(
                        repairGeneration
                    );
                } catch (RuntimeException e) {
                    Log.e(
                        TAG,
                        "Startup translation-job repair failed "
                            + "generation="
                            + repairGeneration,
                        e
                    );
                    retryRequired = true;
                } finally {
                    startupRepairScheduled.set(false);
                    requestTerminalReplayScan();
                    notifyStartupWaiters();
                    if (retryRequired
                        && !startupRepairExecutor.isShutdown()) {
                        scheduleStartupRepair(
                            STARTUP_REPAIR_RETRY_DELAY_MS
                        );
                    }
                }
            }, delayMs, TimeUnit.MILLISECONDS);
        } catch (RejectedExecutionException e) {
            startupRepairScheduled.set(false);
            if (!startupRepairExecutor.isShutdown()) {
                Log.e(
                    TAG,
                    "Could not schedule startup translation-job repair",
                    e
                );
            }
        }
    }

    /**
     * Installs the coordinator callback.  Registration itself remains a
     * Binder-fast operation; the callback is dispatched on a background
     * executor after the port/death recipient swap is complete.
     */
    public void setSceneSyncTrigger(SceneSyncTrigger trigger) {
        sceneSyncTrigger = trigger;
    }

    /**
     * Registers the current Scene port with the coordinator exactly once.
     * The scenePortLock serializes normal trigger registration and the
     * post-startup replay of a port that connected before the coordinator was
     * built, so the coordinator cannot be overwritten by a duplicate call.
     */
    private void registerScenePortWithCoordinator(
        IGameScenePort port,
        IBinder binder,
        long generation,
        int sceneWorkerCount
    ) {
        SceneSyncCoordinator coordinator = sceneSyncCoordinator;
        if (coordinator == null) {
            return;
        }
        SceneSyncCoordinator.TriggerResult result;
        RuntimeException failure;
        synchronized (scenePortLock) {
            ScenePortRecord record = currentScenePort;
            if (record == null
                || record.generation != generation
                || record.binder != binder
                || record.registeredWithCoordinator) {
                return;
            }
            record.registeredWithCoordinator = true;
            try {
                result = coordinator.registerGamePort(
                    port,
                    binder,
                    generation,
                    sceneWorkerCount
                );
                if (result == SceneSyncCoordinator.TriggerResult.CLOSED
                    || result == SceneSyncCoordinator.TriggerResult.FAILED) {
                    record.registeredWithCoordinator = false;
                }
                failure = null;
            } catch (RuntimeException e) {
                record.registeredWithCoordinator = false;
                failure = e;
                result = SceneSyncCoordinator.TriggerResult.FAILED;
            }
        }
        if (failure != null) {
            publishRuntimeState(
                SceneSyncRuntimeState.Action.PORT_REGISTERED,
                SceneSyncRuntimeState.Outcome.FAILED
            );
            throw failure;
        }
        publishRuntimeState(
            SceneSyncRuntimeState.Action.PORT_REGISTERED,
            mapTriggerResult(result)
        );
        Log.i(
            TAG,
            "Game Scene port registered generation="
                + generation
                + " workerCount="
                + sceneWorkerCount
                + " result="
                + result
        );
    }

    /**
     * Replays a game Scene port that connected before the background startup
     * coordinator existed. The trigger path already activates Scene policy and
     * records the port; this completes coordinator registration once.
     */
    private void replayCurrentScenePortToCoordinator() {
        ScenePortRecord record;
        synchronized (scenePortLock) {
            record = currentScenePort;
        }
        if (record == null) {
            return;
        }
        try {
            registerScenePortWithCoordinator(
                record.port,
                record.binder,
                record.generation,
                record.sceneWorkerCount
            );
        } catch (RuntimeException e) {
            Log.e(
                TAG,
                "Could not replay pre-startup Scene port generation="
                    + record.generation,
                e
            );
        }
    }

    private void registerScenePort(IGameScenePort port) {
        // A registration is a two-party generation handshake: the service
        // must not activate a candidate token while an older death/unregister
        // path is concurrently clearing the current token.  Keep the remote
        // activation serialized with publication; no Binder call is made
        // while scenePortLock itself is held.
        synchronized (scenePortRegistrationLock) {
            registerScenePortSerialized(port);
        }
    }

    private void registerScenePortSerialized(IGameScenePort port) {
        if (!isScenePortAdmissionAllowed(acceptingSummaryWake)) {
            throw new IllegalStateException(
                "TranslationService is shutting down"
            );
        }
        if (port == null || port.asBinder() == null) {
            throw new IllegalArgumentException("game Scene port cannot be null");
        }
        final IBinder binder = port.asBinder();
        final int sceneWorkerCount = readSceneWorkerCountForConnection();
        // Replay the persisted capture latch before publishing this Binder as
        // the current port.  Otherwise a Scene callback can start production
        // in the small window between registration and the old post-register
        // applyCapturePauseToCurrentPort() call.
        final boolean paused = capturePausedRequest != null
            ? capturePausedRequest
            : RuntimeControlStore.isCapturePaused(this);
        final IBinder.DeathRecipient deathRecipient = () ->
            removeScenePortIfCurrent(binder);
        final ScenePortRecord replaced;
        final long generation;
        synchronized (scenePortLock) {
            if (!isScenePortAdmissionAllowed(acceptingSummaryWake)) {
                throw new IllegalStateException(
                    "TranslationService is shutting down"
                );
            }
            replaced = currentScenePort;
            if (replaced != null && replaced.binder == binder) {
                // Binder registration is idempotent.  Re-activating the same
                // port with a new token would tear down an in-flight hold and
                // make a failed duplicate registration destructive.
                return;
            }
            generation = newScenePortGeneration();
            scenePortGeneration = generation;
        }

        boolean linked = false;
        boolean activated = false;
        try {
            binder.linkToDeath(deathRecipient, 0);
            linked = true;
            if (!port.activateSceneSyncGeneration(generation)) {
                throw new IllegalStateException(
                    "game Scene port rejected generation activation"
                );
            }
            activated = true;
            port.setCapturePaused(paused);
        } catch (RemoteException e) {
            if (activated) {
                deactivateScenePortGeneration(port, generation);
            }
            if (linked) {
                unlinkScenePort(binder, deathRecipient);
            }
            throw new IllegalStateException(
                "game Scene port could not activate or apply persisted capture state",
                e
            );
        } catch (RuntimeException e) {
            if (activated) {
                deactivateScenePortGeneration(port, generation);
            }
            if (linked) {
                unlinkScenePort(binder, deathRecipient);
            }
            throw e;
        }

        boolean rejectedAfterRemoteWork = false;
        ScenePortRecord published = null;
        synchronized (scenePortLock) {
            if (!isScenePortAdmissionAllowed(acceptingSummaryWake)
                || currentScenePort != replaced
                || scenePortGeneration != generation) {
                rejectedAfterRemoteWork = true;
            } else {
                published = new ScenePortRecord(
                    port,
                    binder,
                    deathRecipient,
                    sceneWorkerCount,
                    generation
                );
                currentScenePort = published;
                // Queue only lightweight lifecycle events while holding the
                // identity lock.  This preserves swap order without
                // performing any remote Binder call under scenePortLock.
                if (replaced != null) {
                    dispatchScenePortUnregisteredLocked(replaced);
                }
                dispatchScenePortRegisteredLocked(published);
            }
        }
        if (rejectedAfterRemoteWork) {
            deactivateScenePortGeneration(port, generation);
            if (linked) {
                unlinkScenePort(binder, deathRecipient);
            }
            throw new IllegalStateException(
                "TranslationService stopped while registering game Scene port"
            );
        }

        // The candidate is now visible.  The old port is deactivated only
        // after publication, and its generation check makes this safe when
        // both records happen to refer to a reconnecting Binder.
        deactivateScenePortGeneration(replaced);
        unlinkScenePort(replaced);
        Log.i(
            TAG,
            "Registered game Scene port workerCount=" + sceneWorkerCount
                + " generation=" + generation
        );
        Log.i(TAG, "Replayed native capture pause paused=" + paused);
    }

    private void applyCapturePauseToCurrentPort() {
        IGameScenePort port;
        synchronized (scenePortLock) {
            ScenePortRecord record = currentScenePort;
            port = record == null ? null : record.port;
        }
        if (port == null) {
            return;
        }
        Boolean requested = capturePausedRequest;
        boolean paused = requested != null
            ? requested
            : RuntimeControlStore.isCapturePaused(this);
        try {
            port.setCapturePaused(paused);
            Log.i(TAG, "Applied native capture pause paused=" + paused);
        } catch (RemoteException | RuntimeException e) {
            Log.w(TAG, "Could not apply native capture pause", e);
        }
    }

    private void unregisterScenePort(IGameScenePort port) {
        if (port == null || port.asBinder() == null) {
            throw new IllegalArgumentException("game Scene port cannot be null");
        }
        removeScenePortIfCurrent(port.asBinder());
    }

    private void removeScenePortIfCurrent(IBinder expectedBinder) {
        ScenePortRecord removed = null;
        synchronized (scenePortRegistrationLock) {
            synchronized (scenePortLock) {
                if (currentScenePort != null
                    && currentScenePort.binder == expectedBinder) {
                    removed = currentScenePort;
                    currentScenePort = null;
                    scenePortGeneration = newScenePortGeneration();
                    dispatchScenePortUnregisteredLocked(removed);
                }
            }
            deactivateScenePortGeneration(removed);
            unlinkScenePort(removed);
        }
        if (removed != null) {
            Log.i(TAG, "Game Scene port detached");
        }
    }

    private void clearScenePort() {
        ScenePortRecord removed;
        synchronized (scenePortRegistrationLock) {
            synchronized (scenePortLock) {
                removed = currentScenePort;
                currentScenePort = null;
                if (removed != null) {
                    scenePortGeneration = newScenePortGeneration();
                    dispatchScenePortUnregisteredLocked(removed);
                }
            }
            deactivateScenePortGeneration(removed);
            unlinkScenePort(removed);
        }
    }

    private void dispatchScenePortRegisteredLocked(ScenePortRecord record) {
        SceneSyncTrigger trigger = sceneSyncTrigger;
        if (trigger == null) {
            Log.i(TAG, "Game Scene port registered without a sync coordinator");
            return;
        }
        try {
            sceneSyncTriggerExecutor.execute(() -> {
                synchronized (scenePortLock) {
                    ScenePortRecord current = currentScenePort;
                    if (current == null
                        || current.generation != record.generation
                        || current.binder != record.binder) {
                        // A slower registration event must never re-install an
                        // older port after a newer Binder swap won the lock.
                        return;
                    }
                }
                try {
                    trigger.onPortRegistered(
                        record.port,
                        record.sceneWorkerCount,
                        record.generation
                    );
                } catch (RuntimeException e) {
                    Log.e(TAG, "Scene Sync registration trigger failed", e);
                }
            });
        } catch (RejectedExecutionException e) {
            Log.w(TAG, "Scene Sync registration trigger executor is closed", e);
        }
    }

    private void dispatchScenePortUnregisteredLocked(ScenePortRecord record) {
        SceneSyncTrigger trigger = sceneSyncTrigger;
        if (trigger == null) {
            return;
        }
        try {
            sceneSyncTriggerExecutor.execute(() -> {
                try {
                    trigger.onPortUnregistered(
                        record.port,
                        record.generation
                    );
                } catch (RuntimeException e) {
                    Log.e(TAG, "Scene Sync detach trigger failed", e);
                }
            });
        } catch (RejectedExecutionException e) {
            Log.w(TAG, "Scene Sync detach trigger executor is closed", e);
        }
    }

    private int readSceneWorkerCountForConnection() {
        try {
            JSONObject userSettings = new ConfigStore(this)
                .load()
                .config
                .getJSONObject("UserSettings");
            return ConfigStore.getSceneWorkerCount(userSettings);
        } catch (Exception e) {
            throw new IllegalStateException(
                "Could not capture SceneWorkerCount for game connection",
                e
            );
        }
    }

    private void reportSceneProductionRejectedInternal(
        String sceneName,
        int reasonCode
    ) {
        if (!SceneStore.isValidSceneName(sceneName)) {
            Log.w(TAG, "Rejected invalid Scene production report scene=" + sceneName);
            return;
        }
        if (reasonCode != SCENE_REJECTED_SYNC_WORKER_HOLD
            && reasonCode != SCENE_REJECTED_SCENE_BLOCKED) {
            Log.w(
                TAG,
                "Rejected unknown Scene production reason scene="
                    + sceneName
                    + " code="
                    + reasonCode
            );
            return;
        }
        synchronized (scenePortLock) {
            if (currentScenePort == null) {
                Log.w(
                    TAG,
                    "Scene production rejection without a registered port scene="
                        + sceneName
                );
                return;
            }
        }
        Log.i(
            TAG,
            "Scene production rejected scene="
                + sceneName
                + " reason="
                + reasonCode
        );
    }

    private static void unlinkScenePort(ScenePortRecord record) {
        if (record == null) {
            return;
        }
        record.binder.unlinkToDeath(record.deathRecipient, 0);
    }

    private static void deactivateScenePortGeneration(
        ScenePortRecord record
    ) {
        if (record == null) {
            return;
        }
        deactivateScenePortGeneration(record.port, record.generation);
    }

    private static void deactivateScenePortGeneration(
        IGameScenePort port,
        long generation
    ) {
        if (port == null || generation <= 0L) {
            return;
        }
        try {
            port.deactivateSceneSyncGeneration(generation);
        } catch (RemoteException | RuntimeException e) {
            Log.w(
                TAG,
                "Could not deactivate game Scene generation=" + generation,
                e
            );
        }
    }

    private static void unlinkScenePort(
        IBinder binder,
        IBinder.DeathRecipient deathRecipient
    ) {
        if (binder == null || deathRecipient == null) {
            return;
        }
        try {
            binder.unlinkToDeath(deathRecipient, 0);
        } catch (RuntimeException ignored) {
            // Binder may already be dead; the record was never published.
        }
    }

    private void registerCallback(ITranslationCallback callback) {
        IBinder callbackBinder = callback.asBinder();
        if (callbackBinder == null) {
            throw new IllegalArgumentException(
                "callback Binder cannot be null"
            );
        }
        AtomicReference<IBinder.DeathRecipient> deathRef =
            new AtomicReference<>();
        IBinder.DeathRecipient deathRecipient = () ->
            removeCallback(callbackBinder, deathRef.get());
        deathRef.set(deathRecipient);
        try {
            callbackBinder.linkToDeath(deathRecipient, 0);
        } catch (RemoteException e) {
            throw new IllegalStateException(
                "Translation callback is already dead",
                e
            );
        }

        CallbackRecord replaced;
        CallbackRecord installed = new CallbackRecord(
            callback,
            callbackBinder,
            deathRecipient
        );
        RuntimeException bindFailure = null;
        synchronized (callbackLock) {
            replaced = currentCallback;
            currentCallback = installed;
            if (replaced != null) {
                // Invalidate old callback pipes before the new generation can
                // start replaying.  sendPayload uses the same lock when it
                // registers a writer, so an old delivery cannot register
                // after this cleanup.
                closeActivePayloadWriters();
            }
            TerminalDeliveryCoordinator coordinator = terminalDelivery;
            if (coordinator != null) {
                try {
                    // The Binder record and the coordinator generation must
                    // change as one critical section.  Otherwise an old
                    // unregister/death can unbind a newly installed record.
                    coordinator.bind(createTerminalDeliveryCallback(installed));
                } catch (RuntimeException e) {
                    if (currentCallback == installed) {
                        currentCallback = null;
                    }
                    coordinator.unbind();
                    bindFailure = e;
                }
            }
        }
        if (bindFailure != null) {
            unlinkCallback(installed);
            unlinkCallback(replaced);
            throw new IllegalStateException(
                "Could not bind terminal delivery callback",
                bindFailure
            );
        }
        if (replaced != null) {
            unlinkCallback(replaced);
            Log.i(TAG, "Replaced connection-scoped translation callback");
        }

        // linkToDeath can race with installation: the death callback may run
        // before currentCallback is visible.  Check the exact record after
        // installation and remove only that record if the Binder is already
        // dead.  A later death callback is still guarded by identity.
        if (!callbackBinder.isBinderAlive()) {
            removeCallback(callbackBinder, deathRecipient);
        }
    }

    /**
     * Replays terminal outcomes that became stable after one startup repair
     * pass.  Ordinary queue notifications intentionally do not call this:
     * replay scans read the durable job directory and must stay off the hot
     * enqueue/claim/cancel path.
     */
    private void requestTerminalReplayScan() {
        TerminalDeliveryCoordinator coordinator = terminalDelivery;
        if (coordinator != null) {
            coordinator.onStoreStateChanged();
        }
    }

    private void removeCallback(IBinder expectedBinder) {
        removeCallback(expectedBinder, null);
    }

    private void removeCallback(
        IBinder expectedBinder,
        IBinder.DeathRecipient expectedDeathRecipient
    ) {
        CallbackRecord removed = null;
        synchronized (callbackLock) {
            CallbackRecord current = currentCallback;
            if (current != null
                && (expectedBinder == null
                    || (current.binder == expectedBinder
                        && (expectedDeathRecipient == null
                            || current.deathRecipient
                                == expectedDeathRecipient)))) {
                removed = currentCallback;
                currentCallback = null;
                closeActivePayloadWriters();
                TerminalDeliveryCoordinator coordinator = terminalDelivery;
                if (coordinator != null) {
                    // Keep unbind in the same critical section as clearing
                    // currentCallback, so an old remove cannot invalidate a
                    // replacement that has already installed its generation.
                    coordinator.unbind();
                }
            }
        }
        unlinkCallback(removed);
    }

    private CallbackRecord getCallback() {
        synchronized (callbackLock) {
            return currentCallback;
        }
    }

    private void clearCallbacks() {
        removeCallback(null);
    }

    private static void unlinkCallback(CallbackRecord record) {
        if (record == null) {
            return;
        }
        record.binder.unlinkToDeath(record.deathRecipient, 0);
    }

    private TerminalDeliveryCoordinator.Callback
        createTerminalDeliveryCallback(CallbackRecord boundRecord) {
        return new TerminalDeliveryCoordinator.Callback() {
            @Override
            public void sendCompleted(
                String requestId,
                String scene,
                String targetLanguage,
                byte[] resultJson
            ) throws Exception {
                if (!sendPayload(
                    requestId,
                    resultJson,
                    boundRecord,
                    descriptor -> boundRecord.callback.onSceneCompleted(
                        requestId,
                        scene,
                        targetLanguage,
                        descriptor
                    )
                )) {
                    throw new IOException("could not send completion callback");
                }
            }

            @Override
            public void sendFailed(
                String requestId,
                String errorType,
                String message
            ) throws Exception {
                try {
                    boundRecord.callback.onTranslationFailed(
                        requestId,
                        errorType,
                        truncate(message, 4096)
                    );
                } catch (RemoteException | RuntimeException e) {
                    throw e;
                }
            }
        };
    }

    private void deliverPayload(
        String requestId,
        byte[] payload,
        SenderFactory senderFactory
    ) {
        CallbackRecord record = getCallback();
        if (record == null) {
            Log.i(
                TAG,
                "No connection callback; persisted payload remains available "
                    + "requestId="
                    + requestId
            );
            return;
        }
        boolean delivered = sendPayload(
            requestId,
            payload,
            record,
            senderFactory.create(record.callback)
        );
        if (!delivered) {
            removeCallback(record.binder, record.deathRecipient);
        }
    }

    private boolean sendPayload(
        String requestId,
        byte[] payload,
        CallbackRecord expectedRecord,
        DescriptorSender sender
    ) {
        if (payload == null) {
            return false;
        }

        final ParcelFileDescriptor[] pipe;
        try {
            pipe = ParcelFileDescriptor.createPipe();
        } catch (IOException e) {
            Log.e(
                TAG,
                "Could not create callback pipe requestId=" + requestId,
                e
            );
            return false;
        }

        ParcelFileDescriptor readEnd = pipe[0];
        PayloadWriter writer = new PayloadWriter(pipe[1]);
        if (!registerActivePayloadWriter(requestId, expectedRecord, writer)) {
            closeQuietly(readEnd);
            closeQuietly(writer.descriptor);
            return false;
        }
        try {
            callbackIoExecutor.execute(() -> {
                try (OutputStream output =
                         new ParcelFileDescriptor.AutoCloseOutputStream(
                             writer.descriptor
                         )) {
                    output.write(payload);
                    output.flush();
                } catch (IOException e) {
                    Log.w(
                        TAG,
                        "Could not write callback payload requestId="
                        + requestId,
                        e
                    );
                } finally {
                    removeActivePayloadWriter(requestId, writer);
                }
            });
        } catch (RejectedExecutionException e) {
            closeQuietly(readEnd);
            removeActivePayloadWriter(requestId, writer);
            closeQuietly(writer.descriptor);
            return false;
        }

        try (ParcelFileDescriptor descriptor = readEnd) {
            sender.send(descriptor);
            return true;
        } catch (RemoteException | IOException | RuntimeException e) {
            Log.w(
                TAG,
                "Could not deliver callback requestId=" + requestId,
                e
            );
            removeActivePayloadWriter(requestId, writer);
            closeQuietly(writer.descriptor);
            return false;
        }
    }

    private boolean registerActivePayloadWriter(
        String requestId,
        CallbackRecord expectedRecord,
        PayloadWriter replacement
    ) {
        PayloadWriter previous;
        synchronized (callbackLock) {
            if (currentCallback != expectedRecord) {
                return false;
            }
            synchronized (payloadWriterLock) {
                previous = activePayloadWriters.put(requestId, replacement);
            }
            if (previous != null) {
                // A retry or a newer Quest patch owns this requestId now.
                // Close the old write end while callbackLock is held so a
                // callback replacement cannot race this ownership change.
                closeQuietly(previous.descriptor);
            }
        }
        return true;
    }

    private void removeActivePayloadWriter(
        String requestId,
        PayloadWriter writer
    ) {
        synchronized (payloadWriterLock) {
            if (activePayloadWriters.get(requestId) == writer) {
                activePayloadWriters.remove(requestId);
            }
        }
    }

    private void closeActivePayloadWriters() {
        List<PayloadWriter> writers;
        synchronized (payloadWriterLock) {
            writers = new ArrayList<>(activePayloadWriters.values());
            activePayloadWriters.clear();
        }
        for (PayloadWriter writer : writers) {
            closeQuietly(writer.descriptor);
        }
    }

    private static void closeQuietly(ParcelFileDescriptor descriptor) {
        if (descriptor == null) {
            return;
        }
        try {
            descriptor.close();
        } catch (IOException ignored) {
        }
    }

    private static void validateRequestId(String requestId) {
        if (requestId == null || requestId.isEmpty()) {
            throw new IllegalArgumentException(
                "requestId cannot be null or empty"
            );
        }
        try {
            UUID parsed = UUID.fromString(requestId);
            if (!parsed.toString().equals(requestId)) {
                throw new IllegalArgumentException(
                    "requestId is not a canonical UUID"
                );
            }
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException(
                "requestId is not a valid UUID",
                e
            );
        }
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
