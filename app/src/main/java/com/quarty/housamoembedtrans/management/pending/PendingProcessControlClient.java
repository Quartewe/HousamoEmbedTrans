package com.quarty.housamoembedtrans.management.pending;

import com.quarty.housamoembedtrans.bridge.HetBridgeContract;
import com.quarty.housamoembedtrans.translation.ITranslationService;
import com.quarty.housamoembedtrans.util.IoUtils;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.os.IBinder;
import android.os.Looper;
import android.os.ParcelFileDescriptor;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

/**
 * HET-app-only client for the PendingProcess management control plane.
 *
 * <p>This class deliberately does not share the game-process
 * {@code TranslationServiceClient}.  The explicit HET package check keeps a
 * game-process context from accidentally becoming a management caller, while
 * the Service performs the authoritative same-UID check on every transaction.
 * Binder calls and pipe reads are synchronous; callers must invoke the six
 * operation methods from a background executor.</p>
 */
public final class PendingProcessControlClient implements AutoCloseable {

    /** Complete entries are bounded by PendingProcessStore.MAX_ENTRY_BYTES. */
    public static final int MAX_ENVELOPE_BYTES =
        PendingProcessStore.MAX_ENTRY_BYTES;

    /** Receives connection changes; callbacks never run while client is locked. */
    @FunctionalInterface
    public interface ConnectionListener {
        void onConnectionChanged(boolean connected);
    }

    /** A management operation returned an error envelope. */
    public static class ControlException extends IOException {
        private static final long serialVersionUID = 1L;
        private final String code;

        private ControlException(String code, String message) {
            super(message == null || message.trim().isEmpty() ? code : message);
            this.code = code == null || code.trim().isEmpty()
                ? "operation_failed"
                : code;
        }

        public String getCode() {
            return code;
        }
    }

    /** The service is bound, but its PendingProcess manager is not ready yet. */
    public static final class ServiceNotReadyException
        extends ControlException {
        private static final long serialVersionUID = 1L;

        private ServiceNotReadyException(String message) {
            super("manager_not_ready", message);
        }
    }

    /** The Binder connection is absent or was lost before an operation. */
    public static final class ServiceUnavailableException
        extends IllegalStateException {
        private static final long serialVersionUID = 1L;

        private ServiceUnavailableException(String message, Throwable cause) {
            super(message, cause);
        }
    }

    @FunctionalInterface
    private interface DescriptorCall {
        ParcelFileDescriptor call(ITranslationService service)
            throws Exception;
    }

    private final Context context;
    private final ComponentName component;
    private final Object lock = new Object();
    private final ServiceConnection connection = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName name, IBinder service) {
            ITranslationService connected =
                ITranslationService.Stub.asInterface(service);
            boolean notify;
            synchronized (lock) {
                if (closed || !bound) {
                    return;
                }
                remote = connected;
                notify = connected != null;
                lock.notifyAll();
            }
            if (notify) {
                notifyConnectionListener(true);
            }
        }

        @Override
        public void onServiceDisconnected(ComponentName name) {
            clearRemote();
        }

        @Override
        public void onBindingDied(ComponentName name) {
            boolean wasBound;
            synchronized (lock) {
                wasBound = bound;
                bound = false;
                remote = null;
                lock.notifyAll();
            }
            if (wasBound) {
                try {
                    context.unbindService(connection);
                } catch (IllegalArgumentException ignored) {
                    // The system may already have torn down the binding.
                }
            }
            notifyConnectionListener(false);
        }

        @Override
        public void onNullBinding(ComponentName name) {
            synchronized (lock) {
                bound = false;
                remote = null;
                lock.notifyAll();
            }
            notifyConnectionListener(false);
        }
    };

    private ITranslationService remote;
    private ConnectionListener connectionListener;
    private boolean bound;
    private boolean closed;

    public PendingProcessControlClient(Context context) {
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
                "PendingProcessControlClient requires the HET application context"
            );
        }
        this.context = safeContext;
        this.component = new ComponentName(
            HetBridgeContract.MODULE_PACKAGE,
            HetBridgeContract.TRANSLATION_SERVICE_CLASS_NAME
        );
    }

    /** Installs a lifecycle listener; it is safe to clear it in onStop. */
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

    /** Starts an explicit HET-app binding.  This method is main-thread safe. */
    public void bind() {
        synchronized (lock) {
            if (closed) {
                throw new IllegalStateException(
                    "PendingProcessControlClient is closed"
                );
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

    /** Stops the current binding while allowing a later Activity start. */
    public void unbind() {
        boolean wasBound;
        synchronized (lock) {
            wasBound = bound;
            bound = false;
            remote = null;
            lock.notifyAll();
        }
        if (wasBound) {
            try {
                context.unbindService(connection);
            } catch (IllegalArgumentException ignored) {
                // Already disconnected; there is no binding to release.
            }
            notifyConnectionListener(false);
        }
    }

    /** Permanently closes this client and releases any remaining binding. */
    @Override
    public void close() {
        synchronized (lock) {
            if (closed) {
                return;
            }
            closed = true;
        }
        unbind();
        setConnectionListener(null);
    }

    public boolean isConnected() {
        synchronized (lock) {
            return !closed && remote != null;
        }
    }

    /** Lists metadata only; call from a background executor. */
    public JSONArray listPendingProcesses() throws Exception {
        return requireArray(
            execute("listPendingProcesses", ITranslationService::listPendingProcesses)
        );
    }

    /** Reads one complete entry; call from a background executor. */
    public JSONObject readPendingProcess(String pendingKey) throws Exception {
        return requireObject(
            execute(
                "readPendingProcess",
                service -> service.readPendingProcess(pendingKey)
            )
        );
    }

    /** Returns owner-specific impact metadata without changing state. */
    public JSONObject previewPendingMove(
        String kind,
        String canonicalId
    ) throws Exception {
        return requireObject(
            execute(
                "previewPendingMove",
                service -> service.previewPendingMove(kind, canonicalId)
            )
        );
    }

    /** Moves one owner into PendingProcess; call from a background executor. */
    public JSONObject movePendingProcess(
        String kind,
        String canonicalId,
        String reason
    ) throws Exception {
        return requireObject(
            execute(
                "movePendingProcess",
                service -> service.movePendingProcess(kind, canonicalId, reason)
            )
        );
    }

    /** Restores one snapshot owner; call from a background executor. */
    public JSONObject restorePendingProcess(String pendingKey)
        throws Exception {
        return requireObject(
            execute(
                "restorePendingProcess",
                service -> service.restorePendingProcess(pendingKey)
            )
        );
    }

    /** Permanently deletes one owner; call from a background executor. */
    public JSONObject permanentlyDeletePendingProcess(String pendingKey)
        throws Exception {
        return requireObject(
            execute(
                "permanentlyDeletePendingProcess",
                service -> service.permanentlyDeletePendingProcess(pendingKey)
            )
        );
    }

    private Object execute(String operation, DescriptorCall call)
        throws Exception {
        ensureBackground(operation);
        ITranslationService service;
        synchronized (lock) {
            if (closed) {
                throw new ServiceUnavailableException(
                    "PendingProcessControlClient is closed",
                    null
                );
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
        } catch (android.os.RemoteException error) {
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
                MAX_ENVELOPE_BYTES
            );
            JSONObject envelope = new JSONObject(
                new String(bytes, StandardCharsets.UTF_8)
            );
            if (!envelope.has("ok")) {
                throw new ControlException(
                    "invalid_envelope",
                    operation + " returned an envelope without ok"
                );
            }
            if (!envelope.optBoolean("ok", false)) {
                String code = envelope.optString(
                    "error",
                    "operation_failed"
                );
                String message = envelope.optString("message", code);
                if ("manager_not_ready".equals(code)) {
                    throw new ServiceNotReadyException(message);
                }
                throw new ControlException(code, message);
            }
            if (!envelope.has("result")) {
                throw new ControlException(
                    "invalid_envelope",
                    operation + " returned an envelope without result"
                );
            }
            Object result = envelope.opt("result");
            return result == JSONObject.NULL ? null : result;
        } catch (JSONException error) {
            throw new ControlException(
                "invalid_envelope",
                "Could not parse " + operation + " management envelope"
            );
        }
    }

    private static JSONArray requireArray(Object result) throws IOException {
        if (result instanceof JSONArray) {
            return (JSONArray) result;
        }
        throw new ControlException(
            "invalid_envelope",
            "PendingProcess list result is not an array"
        );
    }

    private static JSONObject requireObject(Object result) throws IOException {
        if (result instanceof JSONObject) {
            return (JSONObject) result;
        }
        throw new ControlException(
            "invalid_envelope",
            "PendingProcess operation result is not an object"
        );
    }

    private void clearRemote() {
        boolean changed;
        synchronized (lock) {
            changed = remote != null;
            remote = null;
            lock.notifyAll();
        }
        if (changed) {
            notifyConnectionListener(false);
        }
    }

    private void clearRemoteIfCurrent(ITranslationService service) {
        boolean changed;
        synchronized (lock) {
            changed = remote == service;
            if (changed) {
                remote = null;
            }
            lock.notifyAll();
        }
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

    private static void ensureBackground(String operation) {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            throw new IllegalStateException(
                operation + " must run from a background executor"
            );
        }
    }

}
