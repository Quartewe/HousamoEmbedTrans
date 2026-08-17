package com.quarty.housamoembedtrans.bridge;

import com.quarty.housamoembedtrans.storage.SceneSyncStartupSnapshot;

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

        void onSceneCompleted(
            String requestId,
            String scene,
            String targetLanguage,
            byte[] resultJson
        );

        void onTranslationFailed(
            String requestId,
            String errorType,
            String message
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
                ParcelFileDescriptor resultFd
            ) {
                readTerminalPayloadAfterPreflight(
                    "scene result",
                    requestId,
                    "completed",
                    resultFd,
                    bytes -> resultSink.onSceneCompleted(
                        requestId,
                        scene,
                        targetLanguage,
                        bytes
                    )
                );
            }

            @Override
            public void onTranslationFailed(
                String requestId,
                String errorType,
                String message
            ) {
                dispatchTerminalFailureAfterPreflight(
                    requestId,
                    errorType,
                    message
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

    /** Synchronous terminal preflight performed before native consumption. */
    public boolean preflightTerminal(
        String requestId,
        String terminalKind
    ) throws RemoteException {
        ITranslationService service = requireRemote();
        return service.preflightTerminal(requestId, terminalKind);
    }

    /** Synchronous, durable and idempotent delivery acknowledgement. */
    public boolean acknowledgeTerminal(
        String requestId,
        String terminalKind
    ) throws RemoteException {
        ITranslationService service = requireRemote();
        return service.acknowledgeTerminal(requestId, terminalKind);
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

    public synchronized void close() {
        if (closed) {
            return;
        }
        closed = true;
        ITranslationService service = remote;
        if (service != null) {
            IGameScenePort port = gameScenePort;
            if (port != null) {
                try {
                    service.unregisterGameScenePort(port);
                } catch (RemoteException ignored) {
                    // Binder death performs identity-checked cleanup.
                }
            }
            try {
                service.unregisterTranslationCallback(callback);
            } catch (RemoteException ignored) {
                // Binder death performs identity-checked cleanup.
            }
        }
        resetSceneProductionPolicy();
        remote = null;
        if (bound) {
            try {
                context.unbindService(connection);
            } catch (IllegalArgumentException ignored) {
                // Binding may already have died.
            }
            bound = false;
        }
        callbackReader.shutdown();
        List<Runnable> pending = new ArrayList<>();
        callbackReader.getQueue().drainTo(pending);
        for (Runnable runnable : pending) {
            if (runnable instanceof DescriptorCallbackTask) {
                ((DescriptorCallbackTask) runnable).discard();
            }
        }
        notifyAll();
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
     * A terminal PFD is not consumed until the durable HET state authorizes
     * this request/kind.  This avoids draining a stale callback body when a
     * rerun or ACK won the race while the game process was disconnected.
     */
    private void readTerminalPayloadAfterPreflight(
        String label,
        String requestId,
        String terminalKind,
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
                    ITranslationService service = remote;
                    if (service == null
                        || !service.preflightTerminal(
                            requestId,
                            terminalKind
                        )) {
                        log(
                            "Rejected terminal PFD before read label="
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
                    consumer.accept(bytes);
                } catch (Exception e) {
                    log(
                        "Could not preflight/read "
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

    private void dispatchTerminalFailureAfterPreflight(
        String requestId,
        String errorType,
        String message
    ) {
        try {
            callbackReader.execute(() -> {
                try {
                    ITranslationService service = remote;
                    if (service == null
                        || !service.preflightTerminal(
                            requestId,
                            "failed"
                        )) {
                        log(
                            "Rejected terminal failure before delivery requestId="
                                + requestId
                        );
                        return;
                    }
                    resultSink.onTranslationFailed(
                        requestId,
                        errorType,
                        message
                    );
                } catch (Exception e) {
                    log(
                        "Could not preflight/deliver translation failure requestId="
                            + requestId
                            + ": "
                            + safeMessage(e)
                    );
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
