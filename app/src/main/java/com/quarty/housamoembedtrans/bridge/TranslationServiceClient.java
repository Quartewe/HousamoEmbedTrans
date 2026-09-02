package com.quarty.housamoembedtrans.bridge;
import com.quarty.housamoembedtrans.translation.TranslationService;

import com.quarty.housamoembedtrans.scene.sync.SceneSyncStartupSnapshot;

import com.quarty.housamoembedtrans.translation.ITranslationCallback;
import com.quarty.housamoembedtrans.translation.IGameScenePort;
import com.quarty.housamoembedtrans.translation.ITranslationService;
import com.quarty.housamoembedtrans.util.IoUtils;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.os.IBinder;
import android.os.ParcelFileDescriptor;
import android.os.RemoteException;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BiFunction;
import java.util.function.Consumer;

/**
 * Game-process client for the HET app's persistent translation service.
 */
public final class TranslationServiceClient {
    private static final int MAX_CALLBACK_BYTES = 32 * 1024 * 1024;

    /** The client itself was closed and cannot become connected by retrying. */
    public static final class ClientClosedException
        extends IllegalStateException {

        private static final long serialVersionUID = 1L;

        private ClientClosedException() {
            super("TranslationServiceClient is closed");
        }
    }

    /** A known local service start or binding failure that may be retried. */
    public static final class ServiceUnavailableException
        extends IllegalStateException {

        private static final long serialVersionUID = 1L;

        private ServiceUnavailableException(
            String message,
            Throwable cause
        ) {
            super(message, cause);
        }
    }

    public interface ResultSink {
        void onQuestPatch(String requestId, byte[] patchJson);

        boolean onSceneCompleted(
            String requestId,
            String scene,
            String targetLanguage,
            byte[] resultJson,
            String leaseToken,
            long connectionGeneration
        );

        boolean onTranslationFailed(
            String requestId,
            String errorType,
            String message,
            String leaseToken,
            long connectionGeneration
        );
    }

    private final Context context;
    private final Consumer<String> logger;
    private final ResultSink resultSink;
    private final ComponentName component;
    /** Immutable settings snapshot captured before this game connection starts. */
    private final SceneSyncStartupSnapshot sceneSyncSnapshot;
    private final Runnable sceneProductionResetter;
    private final Runnable scenePortAborter;
    private volatile IGameScenePort gameScenePort;
    private final ThreadPoolExecutor callbackReader =
        new ThreadPoolExecutor(
            1,
            1,
            0L,
            TimeUnit.MILLISECONDS,
            new LinkedBlockingQueue<>(),
            runnable -> new Thread(runnable, "HET-callback-reader")
        );
    private volatile ITranslationService remote;
    private volatile SecurityException connectionSecurityFailure;
    private boolean bound;
    private boolean closed;

    @FunctionalInterface
    private interface DescriptorAction {
        void run(InputStream input) throws Exception;
    }

    /**
     * Owns one callback descriptor until either the reader starts it or the
     * client discards it while closing.  The atomic hand-off guarantees that
     * run() and discard() can never close or consume the same descriptor
     * twice.
     */
    private static final class DescriptorCallbackTask implements Runnable {
        private final AtomicReference<ParcelFileDescriptor> descriptor;
        private final DescriptorAction action;

        private DescriptorCallbackTask(
            ParcelFileDescriptor descriptor,
            DescriptorAction action
        ) {
            if (descriptor == null || action == null) {
                throw new IllegalArgumentException(
                    "descriptor and action cannot be null"
                );
            }
            this.descriptor = new AtomicReference<>(descriptor);
            this.action = action;
        }

        @Override
        public void run() {
            ParcelFileDescriptor owned = descriptor.getAndSet(null);
            if (owned == null) {
                return;
            }
            InputStream input = null;
            try {
                input = new ParcelFileDescriptor.AutoCloseInputStream(owned);
                action.run(input);
            } catch (Exception ignored) {
                // The action owns logging.  This catch keeps an unexpected
                // callback exception from killing the serial reader.
            } finally {
                if (input != null) {
                    try {
                        input.close();
                    } catch (IOException ignored) {
                    }
                } else {
                    closeQuietly(owned);
                }
            }
        }

        private void discard() {
            ParcelFileDescriptor owned = descriptor.getAndSet(null);
            if (owned != null) {
                closeQuietly(owned);
            }
        }
    }

    private final ITranslationCallback callback =
        new ITranslationCallback.Stub() {
            @Override
            public void onQuestPatch(
                String requestId,
                long patchVersion,
                ParcelFileDescriptor patchFd
            ) {
                readCallbackPayload(
                    "quest patch version=" + patchVersion,
                    requestId,
                    patchFd,
                    bytes -> resultSink.onQuestPatch(requestId, bytes)
                );
            }

            @Override
            public void onSceneCompleted(
                String requestId,
                String scene,
                String targetLanguage,
                long connectionGeneration,
                ParcelFileDescriptor resultFd
            ) {
                readTerminalPayloadAfterLease(
                    "scene result",
                    requestId,
                    "completed",
                    connectionGeneration,
                    resultFd,
                    (bytes, leaseToken) -> resultSink.onSceneCompleted(
                        requestId,
                        scene,
                        targetLanguage,
                        bytes,
                        leaseToken,
                        connectionGeneration
                    )
                );
            }

            @Override
            public void onTranslationFailed(
                String requestId,
                String errorType,
                String message,
                long connectionGeneration
            ) {
                dispatchTerminalFailureAfterLease(
                    requestId,
                    errorType,
                    message,
                    connectionGeneration
                );
            }
        };

    private final ServiceConnection connection = new ServiceConnection() {
        @Override
        public void onServiceConnected(
            ComponentName name,
            IBinder service
        ) {
            ITranslationService connected =
                ITranslationService.Stub.asInterface(service);
            try {
                int version = connected.getProtocolVersion();
                if (version != HetBridgeContract.PROTOCOL_VERSION) {
                    throw new IllegalStateException(
                        "Unsupported TranslationService protocol version "
                            + version
                            + "; expected "
                            + HetBridgeContract.PROTOCOL_VERSION
                    );
                }
                synchronized (TranslationServiceClient.this) {
                    if (closed) {
                        return;
                    }
                    remote = connected;
                    connectionSecurityFailure = null;
                    registerGameScenePort(connected);
                    connected.registerTranslationCallback(callback);
                    TranslationServiceClient.this.notifyAll();
                }
                log(
                    "Connected to TranslationService protocol version="
                        + version
                );
            } catch (RemoteException e) {
                recoverConnectionInitializationFailure(connected);
                log(
                    "Failed to initialize TranslationService connection: "
                        + e.getClass().getSimpleName()
                        + ": "
                        + safeMessage(e)
                );
            } catch (SecurityException e) {
                recoverConnectionInitializationFailure(connected);
                synchronized (TranslationServiceClient.this) {
                    connectionSecurityFailure = e;
                }
                log(
                    "Permission denied while initializing TranslationService connection: "
                        + e.getClass().getSimpleName()
                        + ": "
                        + safeMessage(e)
                );
            } catch (RuntimeException e) {
                recoverConnectionInitializationFailure(connected);
                log(
                    "Failed to initialize TranslationService connection: "
                        + e.getClass().getSimpleName()
                        + ": "
                        + safeMessage(e)
                );
            }
        }

        @Override
        public void onServiceDisconnected(ComponentName name) {
            synchronized (TranslationServiceClient.this) {
                remote = null;
                TranslationServiceClient.this.notifyAll();
            }
            resetSceneProductionPolicy();
            log("TranslationService disconnected");
        }

        @Override
        public void onBindingDied(ComponentName name) {
            remote = null;
            resetSceneProductionPolicy();
            synchronized (TranslationServiceClient.this) {
                if (closed) {
                    return;
                }
                bound = false;
            }
            log("TranslationService binding died");
            try {
                context.unbindService(connection);
            } catch (IllegalArgumentException ignored) {
            }
            synchronized (TranslationServiceClient.this) {
                if (!closed) {
                    bind();
                }
            }
        }

        @Override
        public void onNullBinding(ComponentName name) {
            synchronized (TranslationServiceClient.this) {
                remote = null;
                TranslationServiceClient.this.notifyAll();
            }
            resetSceneProductionPolicy();
            synchronized (TranslationServiceClient.this) {
                bound = false;
            }
            log("TranslationService returned a null binding");
        }
    };

    public TranslationServiceClient(
        Context context,
        Consumer<String> logger,
        ResultSink resultSink,
        SceneSyncStartupSnapshot sceneSyncSnapshot
    ) {
        this(
            context,
            logger,
            resultSink,
            sceneSyncSnapshot,
            null,
            null,
            null
        );
    }

    public TranslationServiceClient(
        Context context,
        Consumer<String> logger,
        ResultSink resultSink,
        SceneSyncStartupSnapshot sceneSyncSnapshot,
        IGameScenePort gameScenePort
    ) {
        this(
            context,
            logger,
            resultSink,
            sceneSyncSnapshot,
            gameScenePort,
            null,
            null
        );
    }

    public TranslationServiceClient(
        Context context,
        Consumer<String> logger,
        ResultSink resultSink,
        SceneSyncStartupSnapshot sceneSyncSnapshot,
        IGameScenePort gameScenePort,
        Runnable sceneProductionResetter
    ) {
        this(
            context,
            logger,
            resultSink,
            sceneSyncSnapshot,
            gameScenePort,
            sceneProductionResetter,
            null
        );
    }

    public TranslationServiceClient(
        Context context,
        Consumer<String> logger,
        ResultSink resultSink,
        SceneSyncStartupSnapshot sceneSyncSnapshot,
        IGameScenePort gameScenePort,
        Runnable sceneProductionResetter,
        Runnable scenePortAborter
    ) {
        if (context == null || logger == null || resultSink == null) {
            throw new IllegalArgumentException(
                "context, logger, and resultSink cannot be null"
            );
        }
        Context applicationContext = context.getApplicationContext();
        this.context = applicationContext != null
            ? applicationContext
            : context;
        this.logger = logger;
        this.resultSink = resultSink;
        if (sceneSyncSnapshot == null) {
            throw new IllegalArgumentException(
                "sceneSyncSnapshot cannot be null"
            );
        }
        this.sceneSyncSnapshot = sceneSyncSnapshot;
        this.gameScenePort = gameScenePort;
        this.sceneProductionResetter = sceneProductionResetter;
        this.scenePortAborter = scenePortAborter;
        this.component = new ComponentName(
            HetBridgeContract.MODULE_PACKAGE,
            HetBridgeContract.TRANSLATION_SERVICE_CLASS_NAME
        );
    }

    /** Worker bound to this client until the game process disconnects. */
    public int getSceneWorkerCount() {
        return sceneSyncSnapshot.getSceneWorkerCount();
    }

    /**
     * Replaces the game-side connection port.  If already connected, the
     * replacement is registered immediately on the existing Binder; future
     * connections always register the latest port.
     */
    public synchronized void setGameScenePort(IGameScenePort gameScenePort)
        throws RemoteException {
        if (closed) {
            throw new ClientClosedException();
        }
        IGameScenePort previous = this.gameScenePort;
        this.gameScenePort = gameScenePort;
        ITranslationService service = remote;
        if (service == null) {
            return;
        }
        if (previous != null) {
            try {
                service.unregisterGameScenePort(previous);
            } catch (RemoteException e) {
                log(
                    "Could not unregister previous game Scene port: "
                        + safeMessage(e)
                );
            }
        }
        registerGameScenePort(service);
    }

    public boolean enqueue(String requestId, byte[] requestJson)
        throws Exception {
        return enqueue(requestId, requestJson, false);
    }

    public boolean enqueue(
        String requestId,
        byte[] requestJson,
        boolean overwrite
    )
        throws Exception {
        ITranslationService service = remote;
        synchronized (this) {
            if (closed) {
                throw new ClientClosedException();
            }
        }
        if (service == null) {
            throw new ServiceUnavailableException(
                "TranslationService is not connected",
                null
            );
        }
        validateRequest(requestId, requestJson);

        synchronized (this) {
            if (closed) {
                throw new ClientClosedException();
            }
        }

        ParcelFileDescriptor[] pipe = ParcelFileDescriptor.createPipe();
        ParcelFileDescriptor readEnd = pipe[0];
        ParcelFileDescriptor writeEnd = pipe[1];
        AtomicReference<IOException> writerFailure = new AtomicReference<>();

        Thread writer = new Thread(() -> {
            try (OutputStream output =
                     new ParcelFileDescriptor.AutoCloseOutputStream(
                         writeEnd
                     )) {
                output.write(requestJson);
                output.flush();
            } catch (IOException e) {
                writerFailure.set(e);
            }
        }, "HET-request-writer");
        writer.start();

        int enqueueResult;
        try (ParcelFileDescriptor requestFd = readEnd) {
            enqueueResult = service.enqueueTranslation(
                requestId,
                requestFd,
                overwrite
            );
        } catch (Exception e) {
            throw e;
        } finally {
            joinWriter(writer, requestId);
        }

        IOException failure = writerFailure.get();
        if (failure != null) {
            throw new IOException(
                "Could not write translation request " + requestId,
                failure
            );
        }

        final boolean created;
        if (enqueueResult == HetBridgeContract.ENQUEUE_RESULT_CREATED) {
            created = true;
        } else if (enqueueResult
            == HetBridgeContract.ENQUEUE_RESULT_EXISTING) {
            created = false;
        } else if (enqueueResult
            == HetBridgeContract.ENQUEUE_RESULT_RETRYABLE_PERSISTENCE) {
            throw new ServiceUnavailableException(
                "TranslationService could not persist request " + requestId,
                null
            );
        } else if (enqueueResult
            == HetBridgeContract.ENQUEUE_RESULT_DUPLICATE_REJECTED) {
            throw new AdmissionRejectedException(
                "duplicate_rejected",
                "Duplicate request rejected: " + requestId
            );
        } else if (enqueueResult
            == HetBridgeContract.ENQUEUE_RESULT_EXECUTION_NOT_SETTLED) {
            throw new AdmissionRejectedException(
                "execution_not_settled",
                "Translation execution is not settled: " + requestId
            );
        } else if (enqueueResult
            == HetBridgeContract.ENQUEUE_RESULT_USER_ACTION_REQUIRED) {
            throw new AdmissionRejectedException(
                "user_action_required",
                "Translation requires an Active Context/Group correction: "
                    + requestId
            );
        } else if (enqueueResult
            == HetBridgeContract.ENQUEUE_RESULT_MANAGEMENT_PENDING) {
            throw new AdmissionRejectedException(
                "management_pending",
                "Translation is held by PendingProcess: " + requestId
            );
        } else {
            throw new IllegalStateException(
                "TranslationService returned unknown enqueue result "
                    + enqueueResult
            );
        }

        log(
            "Translation request enqueued requestId="
                + requestId
                + " created="
                + created
        );
        return created;
    }

    public static final class AdmissionRejectedException extends Exception {
        private final String disposition;

        public AdmissionRejectedException(
            String disposition,
            String message
        ) {
            super(message);
            this.disposition = disposition;
        }

        public String getDisposition() {
            return disposition;
        }
    }

    /** Requests idempotent cancellation of one ordinary Translation Job. */
    public int cancelTranslation(String requestId) throws RemoteException {
        if (requestId == null || requestId.trim().isEmpty()) {
            throw new IllegalArgumentException(
                "requestId cannot be null or empty"
            );
        }
        return requireRemote().cancelTranslation(requestId);
    }

    /** Grants a terminal delivery lease for this callback generation. */
    public String acquireTerminalDelivery(
        String requestId,
        String terminalKind,
        long connectionGeneration
    ) throws RemoteException {
        return requireRemote().acquireTerminalDelivery(
            requestId,
            terminalKind,
            connectionGeneration
        );
    }

    /** Completes the exact terminal delivery lease. */
    public boolean acknowledgeTerminal(
        String requestId,
        String terminalKind,
        String leaseToken,
        long connectionGeneration
    ) throws RemoteException {
        return requireRemote().acknowledgeTerminal(
            requestId,
            terminalKind,
            leaseToken,
            connectionGeneration
        );
    }

    /** Returns an uncompleted lease to the durable replay queue. */
    public boolean releaseTerminalDelivery(
        String requestId,
        String terminalKind,
        String leaseToken,
        long connectionGeneration
    ) throws RemoteException {
        return requireRemote().releaseTerminalDelivery(
            requestId,
            terminalKind,
            leaseToken,
            connectionGeneration
        );
    }

    /** Best-effort one-way report from the native Scene production hook. */
    public void reportSceneProductionRejected(
        String sceneName,
        int reasonCode
    ) {
        ITranslationService service = remote;
        synchronized (this) {
            if (closed || service == null) {
                return;
            }
        }
        try {
            service.reportSceneProductionRejected(sceneName, reasonCode);
        } catch (RemoteException | RuntimeException e) {
            // Rejection reporting must never make the native capture hook
            // fail or turn a Binder outage into a fatal callback exception.
            log(
                "Could not report Scene production rejection scene="
                    + sceneName
                    + ": "
                    + safeMessage(e)
            );
        }
    }

    public synchronized void bind() {
        if (closed) {
            throw new ClientClosedException();
        }
        if (bound) {
            return;
        }
        Intent intent = new Intent().setComponent(component);
        try {
            bound = context.bindService(
                intent,
                connection,
                Context.BIND_AUTO_CREATE
            );
        } catch (IllegalStateException e) {
            throw new ServiceUnavailableException(
                "Could not bind TranslationService",
                e
            );
        }
        log("bindService to TranslationService accepted=" + bound);
    }

    public boolean start() {
        synchronized (this) {
            if (closed) {
                throw new ClientClosedException();
            }
        }
        Intent intent = new Intent(
            HetBridgeContract.ACTION_START_TRANSLATION_SERVICE
        ).setComponent(component);
        try {
            context.startForegroundService(intent);
            log("TranslationService started");
            return true;
        } catch (SecurityException e) {
            log(
                "Permission denied while starting TranslationService: "
                    + e.getClass().getSimpleName()
                    + ": "
                    + safeMessage(e)
            );
            throw e;
        } catch (IllegalStateException e) {
            log(
                "TranslationService start is temporarily unavailable: "
                    + e.getClass().getSimpleName()
                    + ": "
                    + safeMessage(e)
            );
            throw new ServiceUnavailableException(
                "Could not start TranslationService",
                e
            );
        } catch (RuntimeException e) {
            log(
                "Unexpected TranslationService start failure: "
                    + e.getClass().getSimpleName()
                    + ": "
                    + safeMessage(e)
            );
            throw e;
        }
    }

    public synchronized boolean isConnected() {
        return remote != null;
    }

    public synchronized boolean awaitConnected(long timeoutMs)
        throws InterruptedException {
        if (timeoutMs < 0L) {
            throw new IllegalArgumentException(
                "timeoutMs cannot be negative"
            );
        }
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (!closed && remote == null) {
            if (connectionSecurityFailure != null) {
                throw connectionSecurityFailure;
            }
            long remaining = deadline - System.currentTimeMillis();
            if (remaining <= 0L) {
                break;
            }
            wait(remaining);
        }
        if (connectionSecurityFailure != null) {
            throw connectionSecurityFailure;
        }
        if (closed) {
            throw new ClientClosedException();
        }
        return remote != null;
    }

    public void close() {
        final ITranslationService service;
        final IGameScenePort port;
        final boolean wasBound;
        synchronized (this) {
            if (closed) {
                return;
            }
            closed = true;
            service = remote;
            port = gameScenePort;
            wasBound = bound;

            // Stop accepting callbacks first, then close every descriptor
            // still queued.  A running task is deliberately left alone so
            // its terminal lease can ACK/release through the captured Binder.
            callbackReader.shutdown();
            List<Runnable> pending = new ArrayList<>();
            callbackReader.getQueue().drainTo(pending);
            for (Runnable runnable : pending) {
                if (runnable instanceof DescriptorCallbackTask) {
                    ((DescriptorCallbackTask) runnable).discard();
                }
            }
            // Wake awaitConnected callers without waiting for the reader.
            notifyAll();
        }

        resetSceneProductionPolicy();

        // Explicit callback unregister is rejected while a terminal lease is
        // active.  Finish cleanup off the caller thread so a callback task can
        // acquire this client's monitor for ACK/release without deadlocking
        // close(), while queued PFDs have already been discarded above.
        Thread cleanup = new Thread(() -> {
            boolean interrupted = false;
            for (;;) {
                try {
                    if (callbackReader.awaitTermination(
                        100L,
                        TimeUnit.MILLISECONDS
                    )) {
                        break;
                    }
                } catch (InterruptedException e) {
                    interrupted = true;
                }
            }

            if (service != null) {
                boolean callbackRemoved = false;
                boolean busyLogged = false;
                while (!callbackRemoved) {
                    try {
                        service.unregisterTranslationCallback(callback);
                        callbackRemoved = true;
                    } catch (RemoteException e) {
                        log(
                            "Could not unregister TranslationService callback: "
                                + safeMessage(e)
                        );
                        break;
                    } catch (RuntimeException e) {
                        // A live terminal lease can keep the callback
                        // installed.  Retry from this daemon thread until
                        // the reader's release becomes visible to the Service.
                        String message = e.getMessage();
                        boolean busy = message != null
                            && message.contains(
                                "terminal delivery callback is busy"
                            );
                        if (!busy) {
                            log(
                                "Could not unregister TranslationService "
                                    + "callback: "
                                    + safeMessage(e)
                            );
                            break;
                        }
                        if (!busyLogged) {
                            busyLogged = true;
                            log(
                                "TranslationService callback unregister is "
                                    + "busy; waiting for terminal lease"
                            );
                        }
                        try {
                            Thread.sleep(50L);
                        } catch (InterruptedException interruptedException) {
                            interrupted = true;
                        }
                    }
                }

                if (port != null) {
                    try {
                        service.unregisterGameScenePort(port);
                    } catch (RemoteException | RuntimeException e) {
                        log(
                            "Could not unregister game Scene port: "
                                + safeMessage(e)
                        );
                    }
                }
            }

            if (wasBound) {
                try {
                    context.unbindService(connection);
                } catch (RuntimeException e) {
                    log(
                        "Could not unbind TranslationService: "
                            + safeMessage(e)
                    );
                }
            }

            synchronized (TranslationServiceClient.this) {
                remote = null;
                bound = false;
                notifyAll();
            }
            if (interrupted) {
                Thread.currentThread().interrupt();
            }
        }, "HET-translation-client-close");
        cleanup.setDaemon(true);
        cleanup.start();
    }

    private void registerGameScenePort(ITranslationService service)
        throws RemoteException {
        IGameScenePort port = gameScenePort;
        if (port == null) {
            log("No game Scene port is configured for this connection");
            return;
        }
        service.registerGameScenePort(port);
        log(
            "Registered game Scene port workerCount="
                + sceneSyncSnapshot.getSceneWorkerCount()
        );
    }

    private synchronized ITranslationService requireRemote() {
        if (closed) {
            throw new ClientClosedException();
        }
        ITranslationService service = remote;
        if (service == null) {
            throw new ServiceUnavailableException(
                "TranslationService is not connected",
                null
            );
        }
        return service;
    }

    private void resetSceneProductionPolicy() {
        Runnable aborter = scenePortAborter;
        if (aborter != null) {
            try {
                aborter.run();
            } catch (RuntimeException e) {
                log(
                    "Could not abort active game Scene export: "
                        + safeMessage(e)
                );
            }
        }
        Runnable resetter = sceneProductionResetter;
        if (resetter == null) {
            return;
        }
        try {
            resetter.run();
        } catch (RuntimeException e) {
            log(
                "Could not reset native Scene production policy: "
                    + safeMessage(e)
            );
        }
    }

    /**
     * Rolls back a partially initialized Binder connection and schedules a
     * fresh bind.  Registering the callback can succeed remotely immediately
     * before a later setup call fails; leaving the old ServiceConnection bound
     * would otherwise create a service-side callback that the client can no
     * longer use.
     */
    private void recoverConnectionInitializationFailure(
        ITranslationService connected
    ) {
        if (connected != null) {
            try {
                connected.unregisterTranslationCallback(callback);
            } catch (RemoteException | RuntimeException ignored) {
                // Binder death or an already-replaced callback is safe.
            }
            IGameScenePort port = gameScenePort;
            if (port != null) {
                try {
                    connected.unregisterGameScenePort(port);
                } catch (RemoteException | RuntimeException ignored) {
                    // Best effort rollback for a port that may not be set.
                }
            }
        }

        boolean shouldRebind;
        synchronized (this) {
            remote = null;
            notifyAll();
            shouldRebind = !closed && bound;
            if (shouldRebind) {
                bound = false;
            }
        }
        resetSceneProductionPolicy();
        if (shouldRebind) {
            try {
                context.unbindService(connection);
            } catch (IllegalArgumentException ignored) {
                // The binding may already have died.
            }
            synchronized (this) {
                if (!closed) {
                    bind();
                }
            }
        }
    }

    private void readCallbackPayload(
        String label,
        String requestId,
        ParcelFileDescriptor descriptor,
        Consumer<byte[]> consumer
    ) {
        if (descriptor == null) {
            log(
                "Rejected empty "
                    + label
                    + " descriptor requestId="
                    + requestId
            );
            return;
        }
        DescriptorCallbackTask task = new DescriptorCallbackTask(
            descriptor,
            input -> {
                try {
                    byte[] bytes = IoUtils.readAllBytesLimited(
                        input,
                        MAX_CALLBACK_BYTES
                    );
                    consumer.accept(bytes);
                } catch (Exception e) {
                    log(
                        "Could not read "
                            + label
                            + " requestId="
                            + requestId
                            + ": "
                            + safeMessage(e)
                    );
                }
            }
        );
        try {
            callbackReader.execute(task);
        } catch (RejectedExecutionException e) {
            task.discard();
            log(
                "Callback reader is unavailable for "
                    + label
                    + " requestId="
                    + requestId
            );
        }
    }

    /**
     * A terminal PFD is not consumed until a durable lease authorizes this
     * request/kind.  The callback generation is part of the lease identity;
     * a replaced/dead Binder can therefore never settle a newer connection's
     * attempt.
     */
    private void readTerminalPayloadAfterLease(
        String label,
        String requestId,
        String terminalKind,
        long connectionGeneration,
        ParcelFileDescriptor descriptor,
        BiFunction<byte[], String, Boolean> consumer
    ) {
        if (descriptor == null) {
            log(
                "Rejected empty "
                    + label
                    + " descriptor requestId="
                    + requestId
            );
            return;
        }
        DescriptorCallbackTask task = new DescriptorCallbackTask(
            descriptor,
            input -> {
                ITranslationService service = null;
                String leaseToken = null;
                try {
                    service = remote;
                    if (service == null) {
                        log(
                            "Rejected terminal PFD without service label="
                                + label
                                + " requestId="
                                + requestId
                        );
                        return;
                    }
                    leaseToken = service.acquireTerminalDelivery(
                        requestId,
                        terminalKind,
                        connectionGeneration
                    );
                    if (leaseToken == null || leaseToken.isEmpty()) {
                        log(
                            "Rejected terminal PFD without delivery lease label="
                                + label
                                + " requestId="
                                + requestId
                        );
                        return;
                    }
                    byte[] bytes = IoUtils.readAllBytesLimited(
                        input,
                        MAX_CALLBACK_BYTES
                    );
                    Boolean settled = consumer.apply(bytes, leaseToken);
                    if (Boolean.TRUE.equals(settled)) {
                        leaseToken = null;
                    }
                } catch (Exception e) {
                    log(
                        "Could not acquire/read "
                            + label
                            + " requestId="
                            + requestId
                            + ": "
                            + safeMessage(e)
                    );
                } finally {
                    if (leaseToken != null && service != null) {
                        try {
                            service.releaseTerminalDelivery(
                                requestId,
                                terminalKind,
                                leaseToken,
                                connectionGeneration
                            );
                        } catch (RemoteException | RuntimeException releaseFailure) {
                            log(
                                "Could not release terminal lease requestId="
                                    + requestId
                                    + ": "
                                    + safeMessage(releaseFailure)
                            );
                        }
                    }
                }
            }
        );
        try {
            callbackReader.execute(task);
        } catch (RejectedExecutionException e) {
            task.discard();
            log(
                "Callback reader is unavailable for "
                    + label
                    + " requestId="
                    + requestId
            );
        }
    }

    private void dispatchTerminalFailureAfterLease(
        String requestId,
        String errorType,
        String message,
        long connectionGeneration
    ) {
        try {
            callbackReader.execute(() -> {
                ITranslationService service = null;
                String leaseToken = null;
                try {
                    service = remote;
                    if (service == null) {
                        log(
                            "Rejected terminal failure without service requestId="
                                + requestId
                        );
                        return;
                    }
                    leaseToken = service.acquireTerminalDelivery(
                        requestId,
                        "failed",
                        connectionGeneration
                    );
                    if (leaseToken == null || leaseToken.isEmpty()) {
                        log(
                            "Rejected terminal failure without delivery lease requestId="
                                + requestId
                        );
                        return;
                    }
                    if (resultSink.onTranslationFailed(
                        requestId,
                        errorType,
                        message,
                        leaseToken,
                        connectionGeneration
                    )) {
                        leaseToken = null;
                    }
                } catch (Exception e) {
                    log(
                        "Could not acquire/deliver translation failure requestId="
                            + requestId
                            + ": "
                            + safeMessage(e)
                    );
                } finally {
                    if (leaseToken != null && service != null) {
                        try {
                            service.releaseTerminalDelivery(
                                requestId,
                                "failed",
                                leaseToken,
                                connectionGeneration
                            );
                        } catch (RemoteException | RuntimeException releaseFailure) {
                            log(
                                "Could not release terminal failure lease requestId="
                                    + requestId
                                    + ": "
                                    + safeMessage(releaseFailure)
                            );
                        }
                    }
                }
            });
        } catch (RejectedExecutionException e) {
            log(
                "Callback reader is unavailable for translation failure requestId="
                    + requestId
            );
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

    private static void validateRequest(
        String requestId,
        byte[] requestJson
    ) {
        if (requestId == null || requestId.isEmpty()) {
            throw new IllegalArgumentException(
                "requestId cannot be null or empty"
            );
        }
        if (requestJson == null || requestJson.length == 0) {
            throw new IllegalArgumentException(
                "requestJson cannot be null or empty"
            );
        }
    }

    private static void joinWriter(Thread writer, String requestId)
        throws InterruptedException {
        try {
            writer.join();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new InterruptedException(
                "Interrupted while writing translation request "
                    + requestId
            );
        }
    }

    private void log(String message) {
        logger.accept("[HousamoTrans] " + message);
    }

    private static String safeMessage(Throwable error) {
        String message = error.getMessage();
        return message == null || message.trim().isEmpty()
            ? error.getClass().getSimpleName()
            : message;
    }
}
