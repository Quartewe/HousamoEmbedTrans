package com.quarty.housamoembedtrans.management.transfer;

import com.quarty.housamoembedtrans.util.IoUtils;

import android.util.AtomicFile;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Durable process-wide admission gate for a management import recovery.
 *
 * <p>The marker is intentionally a small file beside the app's private
 * stores.  It is loaded once per files root and checked under a short lock;
 * the gate never holds that lock while waiting for a store lock or doing a
 * cross-store operation.</p>
 */
public final class ManagementImportRecoveryGate {
    private static final int MARKER_VERSION = 1;
    private static final int MAX_MARKER_BYTES = 8 * 1024;
    private static final String MARKER_FILE_NAME =
        ".management_import_recovery.json";
    private static final String MARKER_STATE_ACTIVE = "ACTIVE";
    private static final ConcurrentHashMap<String, RootGate> GATES =
        new ConcurrentHashMap<>();

    private final RootGate rootGate;

    private ManagementImportRecoveryGate(RootGate rootGate) {
        this.rootGate = rootGate;
    }

    /** Returns the gate for one app-private files root. */
    public static ManagementImportRecoveryGate forFilesRoot(File filesRoot) {
        if (filesRoot == null) {
            throw new IllegalArgumentException("filesRoot is required");
        }
        String key;
        try {
            key = filesRoot.getCanonicalPath();
        } catch (IOException e) {
            key = filesRoot.getAbsolutePath();
        }
        final String rootKey = key;
        RootGate root = GATES.computeIfAbsent(
            rootKey,
            ignored -> new RootGate(new File(rootKey))
        );
        return new ManagementImportRecoveryGate(root);
    }

    /** Convenience check used by Context/Group atomic file writes. */
    public static void checkContextWrite(File filesRoot, File target)
        throws IOException {
        forFilesRoot(filesRoot).checkContextWrite(target);
    }

    /** Returns whether a recovery marker is durable, ignoring the owner. */
    public static boolean isBlockedForFilesRoot(File filesRoot) {
        return forFilesRoot(filesRoot).isBlocked();
    }

    /** Returns whether the current thread owns the recovery permit. */
    public static boolean isRecoveryOwnerForFilesRoot(File filesRoot) {
        return forFilesRoot(filesRoot).rootGate.isOwner();
    }

    /** Short operation run under the gate's target admission lock. */
    public interface WriteOperation<T> {
        T run() throws IOException;
    }

    /** Called by SceneStore after a gate clear to resume deferred writes. */
    public static void notifyCleared(File filesRoot) {
        // The SceneStore callback is kept out of this class so the gate lock
        // can never be held while a Scene admission or drain is attempted.
        com.quarty.housamoembedtrans.scene.store.SceneStore
            .resumeDeferredMutationDrain(filesRoot);
    }

    /** Publishes the durable marker before COMMITTING is written. */
    public void activate(String transactionId) throws IOException {
        rootGate.activate(transactionId);
    }

    /** Returns the durable transaction id, or null when no marker exists. */
    public String activeTransactionId() {
        return rootGate.activeTransactionId();
    }

    /** Returns whether the marker is active, including a damaged marker. */
    public boolean isBlocked() {
        return rootGate.isBlocked();
    }

    /** Acquires a thread-scoped permit for the active transaction. */
    public OwnerPermit acquireOwnerPermit(String transactionId)
        throws IOException {
        return rootGate.acquireOwnerPermit(transactionId);
    }

    /** Clears the marker only through the matching owner permit. */
    public void clear(String transactionId) throws IOException {
        rootGate.clear(transactionId);
    }

    /** Guards one Context/Group root file; unrelated stores remain writable. */
    public void checkContextWrite(File target) throws IOException {
        if (target == null) {
            throw new IOException("Context storage target is null");
        }
        File contextRoot = new File(rootGate.filesRoot, "scene_contexts");
        if (isWithin(contextRoot, target)) {
            rootGate.checkWriteAllowed();
        }
    }

    /**
     * Linearizes the Context/Group admission check with the actual atomic
     * write/delete operation.  The callback must only perform that low-level
     * operation and must not acquire a store or entity lock.
     */
    public <T> T withContextWrite(File target, WriteOperation<T> operation)
        throws IOException {
        if (target == null || operation == null) {
            throw new IOException("Context write target and operation are required");
        }
        if (!isContextTarget(target)) {
            return operation.run();
        }
        synchronized (rootGate.lock) {
            rootGate.checkWriteAllowedLocked();
            return operation.run();
        }
    }

    /** Guards dictionary files only; config.json and API keys are unaffected. */
    public void checkDictionaryWrite(String name, File target)
        throws IOException {
        if (!"chardict.json".equals(name)
            && !"gameterms.json".equals(name)) {
            return;
        }
        if (target == null) {
            throw new IOException("dictionary target is null");
        }
        File expected = new File(rootGate.filesRoot, name);
        if (sameFile(expected, target)) {
            rootGate.checkWriteAllowed();
        }
    }

    /**
     * Linearizes dictionary admission with the actual atomic write/delete.
     * Only chardict.json and gameterms.json are protected by this method.
     */
    public <T> T withDictionaryWrite(
        String name,
        File target,
        WriteOperation<T> operation
    ) throws IOException {
        if (operation == null) {
            throw new IOException("dictionary write operation is required");
        }
        if (!isDictionaryTarget(name, target)) {
            return operation.run();
        }
        synchronized (rootGate.lock) {
            rootGate.checkWriteAllowedLocked();
            return operation.run();
        }
    }

    private boolean isContextTarget(File target) {
        return isWithin(
            new File(rootGate.filesRoot, "scene_contexts"),
            target
        );
    }

    private boolean isDictionaryTarget(String name, File target) {
        return ("chardict.json".equals(name)
                || "gameterms.json".equals(name))
            && target != null
            && sameFile(new File(rootGate.filesRoot, name), target);
    }

    /** A permit that is valid only on the thread that acquired it. */
    public static final class OwnerPermit implements AutoCloseable {
        private final RootGate owner;
        private final String transactionId;
        private boolean closed;

        private OwnerPermit(RootGate owner, String transactionId) {
            this.owner = owner;
            this.transactionId = transactionId;
        }

        @Override
        public void close() {
            synchronized (owner.lock) {
                if (closed) {
                    return;
                }
                closed = true;
                if (owner.ownerPermit.get() == this) {
                    owner.ownerPermit.remove();
                }
            }
        }

        private boolean matches(String expected) {
            return !closed
                && transactionId.equals(expected)
                && owner.ownerPermit.get() == this;
        }
    }

    private static final class RootGate {
        private final Object lock = new Object();
        private final File filesRoot;
        private final File markerFile;
        private final ThreadLocal<OwnerPermit> ownerPermit =
            new ThreadLocal<>();
        private boolean blocked;
        private boolean damaged;
        private String transactionId;

        private RootGate(File filesRoot) {
            this.filesRoot = filesRoot;
            markerFile = new File(filesRoot, MARKER_FILE_NAME);
            synchronized (lock) {
                loadMarkerLocked();
            }
        }

        private boolean isBlocked() {
            synchronized (lock) {
                return blocked;
            }
        }

        private boolean isOwner() {
            synchronized (lock) {
                return ownerPermit.get() != null
                    && ownerPermit.get().matches(transactionId);
            }
        }

        private String activeTransactionId() {
            synchronized (lock) {
                return transactionId;
            }
        }

        private void loadMarkerLocked() {
            if (!IoUtils.atomicFileExists(markerFile)) {
                blocked = false;
                damaged = false;
                transactionId = null;
                return;
            }
            try {
                JSONObject marker = readMarker();
                if (marker.optInt("version", -1) != MARKER_VERSION
                    || !MARKER_STATE_ACTIVE.equals(
                        marker.optString("state", "")
                    )
                    || marker.optString("transaction_id", "")
                        .trim().isEmpty()) {
                    throw new IOException("invalid management recovery marker");
                }
                transactionId = marker.getString("transaction_id").trim();
                blocked = true;
                damaged = false;
            } catch (Exception e) {
                // A marker which cannot be understood is a fail-closed
                // recovery boundary.  No ordinary writer may pass it.
                transactionId = null;
                blocked = true;
                damaged = true;
            }
        }

        private void activate(String requestedTransactionId)
            throws IOException {
            String tx = requireTransactionId(requestedTransactionId);
            synchronized (lock) {
                if (blocked) {
                    if (damaged || !tx.equals(transactionId)) {
                        throw new IOException(
                            "management recovery marker is already active"
                        );
                    }
                    return;
                }
                try {
                    JSONObject marker = new JSONObject()
                        .put("version", MARKER_VERSION)
                        .put("state", MARKER_STATE_ACTIVE)
                        .put("transaction_id", tx)
                        .put("created_at", System.currentTimeMillis());
                    writeMarker(marker);
                    transactionId = tx;
                    damaged = false;
                    blocked = true;
                } catch (Exception failure) {
                    try {
                        deleteMarkerLocked();
                        blocked = false;
                        damaged = false;
                        transactionId = null;
                    } catch (IOException cleanupFailure) {
                        blocked = true;
                        damaged = true;
                        transactionId = null;
                        failure.addSuppressed(cleanupFailure);
                    }
                    throw failure instanceof IOException
                        ? (IOException) failure
                        : new IOException(
                            "could not publish management recovery marker",
                            failure
                        );
                }
            }
        }

        private OwnerPermit acquireOwnerPermit(String requestedTransactionId)
            throws IOException {
            String tx = requireTransactionId(requestedTransactionId);
            synchronized (lock) {
                if (!blocked || damaged || !tx.equals(transactionId)) {
                    throw new IOException(
                        "management recovery marker is unavailable"
                    );
                }
                OwnerPermit current = ownerPermit.get();
                if (current != null && current.matches(tx)) {
                    throw new IOException(
                        "management recovery owner permit is already held"
                    );
                }
                OwnerPermit permit = new OwnerPermit(this, tx);
                ownerPermit.set(permit);
                return permit;
            }
        }

        private void clear(String requestedTransactionId) throws IOException {
            String tx = requireTransactionId(requestedTransactionId);
            synchronized (lock) {
                OwnerPermit permit = ownerPermit.get();
                if (permit == null || !permit.matches(tx)) {
                    throw new IOException(
                        "management recovery owner permit is required"
                    );
                }
                if (!blocked) {
                    return;
                }
                if (damaged || !tx.equals(transactionId)) {
                    throw new IOException(
                        "management recovery marker is damaged"
                    );
                }
                deleteMarkerLocked();
                blocked = false;
                damaged = false;
                transactionId = null;
            }
        }

        private void checkWriteAllowed() throws IOException {
            synchronized (lock) {
                checkWriteAllowedLocked();
            }
        }

        private void checkWriteAllowedLocked() throws IOException {
            if (!blocked) {
                return;
            }
            OwnerPermit permit = ownerPermit.get();
            if (permit != null && permit.matches(transactionId)) {
                return;
            }
            throw new IOException(
                "management import recovery is active; write deferred"
            );
        }

        private JSONObject readMarker() throws Exception {
            byte[] bytes;
            try (InputStream input = new AtomicFile(markerFile).openRead()) {
                ByteArrayOutputStream output = new ByteArrayOutputStream();
                byte[] buffer = new byte[1024];
                int total = 0;
                int count;
                while ((count = input.read(buffer)) != -1) {
                    total += count;
                    if (total > MAX_MARKER_BYTES) {
                        throw new IOException("management recovery marker is too large");
                    }
                    output.write(buffer, 0, count);
                }
                bytes = output.toByteArray();
            }
            return new JSONObject(new String(bytes, StandardCharsets.UTF_8));
        }

        private void writeMarker(JSONObject marker) throws IOException {
            byte[] bytes = marker.toString().getBytes(StandardCharsets.UTF_8);
            if (bytes.length == 0 || bytes.length > MAX_MARKER_BYTES) {
                throw new IOException("management recovery marker is too large");
            }
            if (!filesRoot.isDirectory()
                && !filesRoot.mkdirs()
                && !filesRoot.isDirectory()) {
                throw new IOException("could not create app files directory");
            }
            AtomicFile file = new AtomicFile(markerFile);
            FileOutputStream output = file.startWrite();
            try {
                output.write(bytes);
                output.getFD().sync();
                file.finishWrite(output);
            } catch (Exception failure) {
                file.failWrite(output);
                throw failure instanceof IOException
                    ? (IOException) failure
                    : new IOException(
                        "could not write management recovery marker",
                        failure
                    );
            }
        }

        private void deleteMarkerLocked() throws IOException {
            new AtomicFile(markerFile).delete();
            if (IoUtils.atomicFileExists(markerFile)) {
                throw new IOException(
                    "management recovery marker was not deleted"
                );
            }
        }
    }

    private static String requireTransactionId(String value)
        throws IOException {
        if (value == null || value.trim().isEmpty()) {
            throw new IOException("management recovery transaction id is empty");
        }
        return value.trim();
    }

    private static boolean sameFile(File left, File right) {
        try {
            return left.getCanonicalFile().equals(right.getCanonicalFile());
        } catch (IOException e) {
            return left.getAbsoluteFile().equals(right.getAbsoluteFile());
        }
    }

    private static boolean isWithin(File root, File target) {
        try {
            String rootPath = root.getCanonicalPath();
            String targetPath = target.getCanonicalPath();
            if (rootPath.equals(targetPath)) {
                return true;
            }
            if (!rootPath.endsWith(File.separator)) {
                rootPath += File.separator;
            }
            return targetPath.startsWith(rootPath);
        } catch (IOException e) {
            String rootPath = root.getAbsolutePath();
            String targetPath = target.getAbsolutePath();
            if (!rootPath.endsWith(File.separator)) {
                rootPath += File.separator;
            }
            return targetPath.startsWith(rootPath);
        }
    }

    private ManagementImportRecoveryGate() {
        throw new AssertionError("no instances");
    }
}
