package com.quarty.housamoembedtrans.bridge;

import com.quarty.housamoembedtrans.translation.ITranslationService;
import com.quarty.housamoembedtrans.translation.IGameObbPort;
import com.quarty.housamoembedtrans.translation.IGameLogPort;
import com.quarty.housamoembedtrans.translation.IChoiceBackCallback;
import com.quarty.housamoembedtrans.util.IoUtils;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.os.IBinder;
import android.os.Looper;
import android.os.ParcelFileDescriptor;
import android.os.RemoteException;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.CompletableFuture;

/**
 * HET-app-only Binder client for ordinary Translation Job controls.
 *
 * <p>This client deliberately does not register the game callback or a game
 * Scene port.  The task-management UI only needs the existing cancellation
 * transaction on the HET-owned service.</p>
 */
public final class TranslationJobControlClient implements AutoCloseable {
    private static final int MAX_MANAGEMENT_ENVELOPE_BYTES = 64 * 1024 * 1024;

    /** The client is closed and cannot be used again. */
    public static final class ClientClosedException
        extends IllegalStateException {
        private static final long serialVersionUID = 1L;

        private ClientClosedException() {
            super("TranslationJobControlClient is closed");
        }
    }

    /** The service is not connected or the Binder call lost its connection. */
    public static final class ServiceUnavailableException
        extends IllegalStateException {
        private static final long serialVersionUID = 1L;

        private ServiceUnavailableException(String message, Throwable cause) {
            super(message, cause);
        }
    }

    @FunctionalInterface
    public interface ConnectionListener {
        void onConnectionChanged(boolean connected);
    }

    private final Context context;
    private final ComponentName component;
    private final Object lock = new Object();
    private final Set<ChoiceRequest> choiceRequests = new HashSet<>();
    private final ServiceConnection connection = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName name, IBinder service) {
            ITranslationService connected =
                ITranslationService.Stub.asInterface(service);
            boolean notifyConnected = false;
            ChoiceRequest[] interrupted = new ChoiceRequest[0];
            try {
                if (connected == null
                    || connected.getProtocolVersion()
                        != HetBridgeContract.PROTOCOL_VERSION) {
                    throw new IllegalStateException(
                        "Unsupported TranslationService protocol version"
                    );
                }
                synchronized (lock) {
                    if (closed || !bound) {
                        return;
                    }
                    if (remote != connected) interrupted = detachChoiceRequestsLocked();
                    remote = connected;
                    lock.notifyAll();
                    notifyConnected = true;
                }
            } catch (RemoteException | RuntimeException error) {
                boolean wasBound;
                synchronized (lock) {
                    wasBound = bound;
                    bound = false;
                    remote = null;
                    interrupted = detachChoiceRequestsLocked();
                    lock.notifyAll();
                }
                if (wasBound) {
                    try {
                        context.unbindService(connection);
                    } catch (IllegalArgumentException ignored) {
                        // The system may already have torn down the binding.
                    }
                }
            }
            finishChoiceRequests(interrupted);
            if (notifyConnected) {
                notifyConnectionListener(true);
            } else {
                notifyConnectionListener(false);
            }
        }

        @Override
        public void onServiceDisconnected(ComponentName name) {
            clearRemote();
        }

        @Override
        public void onBindingDied(ComponentName name) {
            boolean wasBound;
            ChoiceRequest[] interrupted;
            synchronized (lock) {
                wasBound = bound;
                bound = false;
                remote = null;
                interrupted = detachChoiceRequestsLocked();
                lock.notifyAll();
            }
            if (wasBound) {
                try {
                    context.unbindService(connection);
                } catch (IllegalArgumentException ignored) {
                    // The system may already have torn down the binding.
                }
            }
            finishChoiceRequests(interrupted);
            notifyConnectionListener(false);
        }

        @Override
        public void onNullBinding(ComponentName name) {
            boolean wasBound;
            ChoiceRequest[] interrupted;
            synchronized (lock) {
                wasBound = bound;
                bound = false;
                remote = null;
                interrupted = detachChoiceRequestsLocked();
                lock.notifyAll();
            }
            if (wasBound) {
                try {
                    context.unbindService(connection);
                } catch (IllegalArgumentException ignored) {
                    // The system may already have torn down the binding.
                }
            }
            finishChoiceRequests(interrupted);
            notifyConnectionListener(false);
        }
    };

    private ITranslationService remote;
    private ConnectionListener connectionListener;
    private boolean bound;
    private boolean closed;

    public TranslationJobControlClient(Context context) {
        if (context == null) {
            throw new IllegalArgumentException("context is required");
        }
        Context applicationContext = context.getApplicationContext();
        Context safeContext = applicationContext != null
            ? applicationContext
            : context;
        if (!HetBridgeContract.MODULE_PACKAGE.equals(
            safeContext.getPackageName()
        )) {
            throw new IllegalArgumentException(
                "TranslationJobControlClient requires the HET application context"
            );
        }
        this.context = safeContext;
        this.component = new ComponentName(
            HetBridgeContract.MODULE_PACKAGE,
            HetBridgeContract.TRANSLATION_SERVICE_CLASS_NAME
        );
    }

    public void setConnectionListener(ConnectionListener listener) {
        boolean connected;
        synchronized (lock) {
            connectionListener = listener;
            connected = remote != null;
        }
        if (listener != null && connected) {
            listener.onConnectionChanged(true);
        }
    }

    /** Starts a binding; callers may invoke this from the Activity thread. */
    public void bind() {
        synchronized (lock) {
            if (closed) {
                throw new ClientClosedException();
            }
            if (bound) {
                return;
            }
            try {
                bound = context.bindService(
                    new Intent().setComponent(component),
                    connection,
                    Context.BIND_AUTO_CREATE
                );
            } catch (IllegalStateException error) {
                throw new ServiceUnavailableException(
                    "Could not bind TranslationService",
                    error
                );
            }
            if (!bound) {
                throw new ServiceUnavailableException(
                    "TranslationService binding was rejected",
                    null
                );
            }
        }
    }

    public void unbind() {
        boolean wasBound;
        ChoiceRequest[] interrupted;
        synchronized (lock) {
            wasBound = bound;
            bound = false;
            remote = null;
            interrupted = detachChoiceRequestsLocked();
            lock.notifyAll();
        }
        if (wasBound) {
            try {
                context.unbindService(connection);
            } catch (IllegalArgumentException ignored) {
                // Already disconnected; there is no binding to release.
            }
        }
        finishChoiceRequests(interrupted);
        if (wasBound) notifyConnectionListener(false);
    }

    public boolean isConnected() {
        synchronized (lock) {
            return !closed && remote != null;
        }
    }

    /** Waits for the asynchronous Binder callback without blocking the UI. */
    public boolean awaitConnected(long timeoutMs) throws InterruptedException {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            throw new IllegalStateException(
                "awaitConnected must run from a background executor"
            );
        }
        if (timeoutMs < 0L) {
            throw new IllegalArgumentException(
                "timeoutMs cannot be negative"
            );
        }
        long deadline = System.currentTimeMillis() + timeoutMs;
        synchronized (lock) {
            while (!closed && bound && remote == null) {
                long remaining = deadline - System.currentTimeMillis();
                if (remaining <= 0L) {
                    break;
                }
                lock.wait(remaining);
            }
            if (closed) {
                throw new ClientClosedException();
            }
            return remote != null;
        }
    }

    /** Fails explicitly when the asynchronous bind did not produce a Binder. */
    public void requireConnected() {
        synchronized (lock) {
            if (closed) {
                throw new ClientClosedException();
            }
            if (remote == null) {
                throw new ServiceUnavailableException(
                    "TranslationService is not connected",
                    null
                );
            }
        }
    }

    /** Resolves the connected game's resource endpoint without registering a game callback. */
    public IGameObbPort getGameObbPort() throws RemoteException {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            throw new IllegalStateException("Game resource operations require a background thread");
        }
        ITranslationService service;
        synchronized (lock) {
            requireConnected();
            service = remote;
        }
        return service.getGameObbPort();
    }

    public IGameLogPort getGameLogPort() throws RemoteException {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            throw new IllegalStateException("Game log export require a background thread");
        }
        ITranslationService service;
        synchronized (lock) {
            requireConnected();
            service = remote;
        }
        return service.getGameLogPort();
    }

    /**
     * Submits one choice-back intent on the existing connection. Safe to call
     * from the UI thread; completion does not imply any UI-thread affinity.
     * Neither cancellation of the future nor reconnection resends the intent.
     */
    public CompletableFuture<Integer> requestChoiceBack() {
        ChoiceRequest request;
        synchronized (lock) {
            if (closed) throw new ClientClosedException();
            if (remote == null) {
                return CompletableFuture.completedFuture(ChoiceBackResult.GAME_NOT_CONNECTED);
            }
            request = new ChoiceRequest(remote);
            choiceRequests.add(request);
        }
        Thread worker = new Thread(request::dispatch, "HET-choice-back-dispatch");
        worker.setDaemon(true);
        try {
            worker.start();
        } catch (RuntimeException error) {
            request.onCompleted(ChoiceBackResult.UNAVAILABLE);
        }
        return request.result;
    }

    private final class ChoiceRequest extends IChoiceBackCallback.Stub {
        private final ITranslationService service;
        private final CompletableFuture<Integer> result = new CompletableFuture<>();
        private final IBinder.DeathRecipient death = this::disconnected;
        private boolean linked;
        private boolean dispatched;
        private int terminalResult;

        ChoiceRequest(ITranslationService service) {
            this.service = service;
        }

        void dispatch() {
            try {
                synchronized (lock) {
                    if (!choiceRequests.contains(this)) return;
                    service.asBinder().linkToDeath(death, 0);
                    linked = true;
                }
                // Old AIDL proxies can read zero from an unsupported empty reply.
                // Probe before sending any non-idempotent operation.
                if (service.getChoiceBackProtocolVersion() != 1) {
                    onCompleted(ChoiceBackResult.UNAVAILABLE);
                    return;
                }
            } catch (RemoteException | RuntimeException error) {
                onCompleted(ChoiceBackResult.UNAVAILABLE);
                return;
            }
            boolean unavailable;
            synchronized (lock) {
                if (!choiceRequests.contains(this)) return;
                unavailable = remote != service || closed;
                if (unavailable) {
                    detachLocked(ChoiceBackResult.GAME_NOT_CONNECTED);
                } else {
                    // Dispatch commitment: a concurrent close reports UNKNOWN.
                    // The committed intent is never retried.
                    dispatched = true;
                }
            }
            if (unavailable) {
                finish();
                return;
            }
            try {
                service.requestChoiceBack(this);
            } catch (RemoteException | RuntimeException error) {
                onCompleted(ChoiceBackResult.UNKNOWN);
            }
        }

        void disconnected() {
            synchronized (lock) {
                if (!detachLocked(dispatched ? ChoiceBackResult.UNKNOWN
                    : ChoiceBackResult.GAME_NOT_CONNECTED)) return;
            }
            finish();
        }

        @Override
        public void onCompleted(int value) {
            synchronized (lock) {
                if (!detachLocked(value)) return;
            }
            finish();
        }

        boolean detachLocked(int value) {
            if (!choiceRequests.remove(this)) return false;
            terminalResult = value;
            if (linked) {
                service.asBinder().unlinkToDeath(death, 0);
                linked = false;
            }
            return true;
        }

        void finish() {
            result.complete(terminalResult);
        }
    }

    /** Called while removing the connection, before a new one can be installed. */
    private ChoiceRequest[] detachChoiceRequestsLocked() {
        ChoiceRequest[] interrupted = choiceRequests.toArray(new ChoiceRequest[0]);
        for (ChoiceRequest request : interrupted) {
            request.detachLocked(request.dispatched ? ChoiceBackResult.UNKNOWN
                : ChoiceBackResult.GAME_NOT_CONNECTED);
        }
        return interrupted;
    }

    private void finishChoiceRequests(ChoiceRequest[] interrupted) {
        for (ChoiceRequest request : interrupted) request.finish();
    }

    /** Calls the existing service cancellation transaction. */
    public int cancelTranslation(String requestId) throws RemoteException {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            throw new IllegalStateException(
                "cancelTranslation must run from a background executor"
            );
        }
        if (requestId == null || requestId.trim().isEmpty()) {
            throw new IllegalArgumentException(
                "requestId cannot be null or empty"
            );
        }
        ITranslationService service;
        synchronized (lock) {
            if (closed) {
                throw new ClientClosedException();
            }
            service = remote;
        }
        if (service == null) {
            throw new ServiceUnavailableException(
                "TranslationService is not connected",
                null
            );
        }
        try {
            return service.cancelTranslation(requestId);
        } catch (RemoteException error) {
            clearRemoteIfCurrent(service);
            throw new ServiceUnavailableException(
                "TranslationService cancellation call failed",
                error
            );
        } catch (RuntimeException error) {
            clearRemoteIfCurrent(service);
            throw new ServiceUnavailableException(
                "TranslationService cancellation call failed",
                error
            );
        }
    }

    /** Reads the Service-owned management snapshot through a Binder pipe. */
    public JSONObject readManagementImportSnapshot() throws Exception {
        return requireManagementObject(
            executeManagement(
                "readManagementImportSnapshot",
                service -> service.readManagementImportSnapshot()
            )
        );
    }

    /** Applies an app-private prepared import session through the Service. */
    public JSONObject applyManagementImport(
        String sessionToken,
        String expectedSnapshotFingerprint
    ) throws Exception {
        if (sessionToken == null || sessionToken.trim().isEmpty()) {
            throw new IllegalArgumentException("sessionToken is required");
        }
        if (expectedSnapshotFingerprint == null
            || expectedSnapshotFingerprint.trim().isEmpty()) {
            throw new IllegalArgumentException(
                "expectedSnapshotFingerprint is required"
            );
        }
        return requireManagementObject(
            executeManagement(
                "applyManagementImport",
                service -> service.applyManagementImport(
                    sessionToken,
                    expectedSnapshotFingerprint
                )
            )
        );
    }

    @FunctionalInterface
    private interface ManagementDescriptorCall {
        ParcelFileDescriptor call(ITranslationService service)
            throws RemoteException;
    }

    private Object executeManagement(
        String operation,
        ManagementDescriptorCall call
    ) throws Exception {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            throw new IllegalStateException(
                operation + " must run from a background executor"
            );
        }
        ITranslationService service;
        synchronized (lock) {
            if (closed) {
                throw new ClientClosedException();
            }
            service = remote;
        }
        if (service == null) {
            throw new ServiceUnavailableException(
                "TranslationService is not connected",
                null
            );
        }
        final ParcelFileDescriptor descriptor;
        try {
            descriptor = call.call(service);
        } catch (RemoteException error) {
            clearRemoteIfCurrent(service);
            throw new ServiceUnavailableException(
                "TranslationService management call failed",
                error
            );
        }
        if (descriptor == null) {
            throw new ServiceUnavailableException(
                "TranslationService returned no management envelope",
                null
            );
        }
        try (InputStream input =
                 new ParcelFileDescriptor.AutoCloseInputStream(descriptor)) {
            byte[] bytes = IoUtils.readAllBytesLimited(
                input,
                MAX_MANAGEMENT_ENVELOPE_BYTES
            );
            JSONObject envelope = new JSONObject(
                new String(bytes, StandardCharsets.UTF_8)
            );
            if (!envelope.optBoolean("ok", false)) {
                String code = envelope.optString("error", "operation_failed");
                String message = envelope.optString("message", code);
                if ("manager_not_ready".equals(code)) {
                    throw new ServiceUnavailableException(message, null);
                }
                throw new IllegalStateException(message);
            }
            Object result = envelope.opt("result");
            return result == JSONObject.NULL ? null : result;
        } catch (JSONException error) {
            throw new IllegalStateException(
                "Could not parse " + operation + " management envelope",
                error
            );
        }
    }

    private static JSONObject requireManagementObject(Object value) {
        if (value instanceof JSONObject) {
            return (JSONObject) value;
        }
        throw new IllegalStateException(
            "TranslationService management result is not an object"
        );
    }

    @Override
    public void close() {
        ChoiceRequest[] interrupted;
        boolean wasBound;
        synchronized (lock) {
            if (closed) {
                return;
            }
            closed = true;
            connectionListener = null;
            remote = null;
            interrupted = detachChoiceRequestsLocked();
            lock.notifyAll();
            wasBound = bound;
            bound = false;
        }
        if (wasBound) {
            try {
                context.unbindService(connection);
            } catch (IllegalArgumentException ignored) {
                // The binding may already have died.
            }
        }
        finishChoiceRequests(interrupted);
    }

    private void clearRemote() {
        boolean changed;
        ChoiceRequest[] interrupted;
        synchronized (lock) {
            changed = remote != null;
            remote = null;
            interrupted = detachChoiceRequestsLocked();
            lock.notifyAll();
        }
        finishChoiceRequests(interrupted);
        if (changed) {
            notifyConnectionListener(false);
        }
    }

    private void clearRemoteIfCurrent(ITranslationService service) {
        boolean changed;
        ChoiceRequest[] interrupted = new ChoiceRequest[0];
        synchronized (lock) {
            changed = remote == service;
            if (changed) {
                remote = null;
                interrupted = detachChoiceRequestsLocked();
            }
            lock.notifyAll();
        }
        finishChoiceRequests(interrupted);
        if (changed) {
            notifyConnectionListener(false);
        }
    }

    private void notifyConnectionListener(boolean connected) {
        ConnectionListener listener;
        synchronized (lock) {
            listener = connectionListener;
        }
        if (listener != null) {
            listener.onConnectionChanged(connected);
        }
    }
}
