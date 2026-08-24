package com.quarty.housamoembedtrans.scene.sync;

import com.quarty.housamoembedtrans.bridge.SceneSyncWireCodec;
import com.quarty.housamoembedtrans.scene.store.SceneStore;
import com.quarty.housamoembedtrans.translation.IGameScenePort;

import android.os.ParcelFileDescriptor;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * HET-side request/result pipe owner for one blocking game Scene apply.
 *
 * <p>Each blocking call owns both local pipe endpoints for exactly one Binder
 * round trip.  The request is written and the result decoded on the caller's
 * worker; no hidden apply executor or asynchronous submission surface exists.
 * </p>
 */
public final class SceneApplyCoordinator implements AutoCloseable {
    private final Object taskLock = new Object();
    private final Set<BlockingCall> activeBlockingCalls = new HashSet<>();
    private final AtomicBoolean closed = new AtomicBoolean();

    /**
     * Applies one Scene synchronously on the caller's thread.
     *
     * <p>Full sync workers use this seam instead of submitting a pipe task to
     * the Scene sync worker that is waiting for the result.  The game side
     * still owns its own apply activity; this method only owns the two
     * local pipe endpoints for the duration of one Binder round trip.</p>
     */
    public SceneSyncWireCodec.ApplyResult applyWriteBlocking(
        long generation,
        IGameScenePort port,
        String sceneName,
        byte[] sceneBytes
    ) throws Exception {
        if (sceneBytes == null) {
            throw new IllegalArgumentException("sceneBytes cannot be null");
        }
        return applyBlocking(
            generation,
            port,
            SceneSyncWireCodec.writeScene(sceneName, sceneBytes)
        );
    }

    /** Applies a bare Scene deletion command synchronously. */
    public SceneSyncWireCodec.ApplyResult applyDeleteBlocking(
        long generation,
        IGameScenePort port,
        String sceneName
    ) throws Exception {
        sceneName = SceneStore.requireSceneName(sceneName);
        return applyBlocking(
            generation,
            port,
            SceneSyncWireCodec.deleteScene(sceneName)
        );
    }

    /** Applies the final blocked-list publication synchronously. */
    public SceneSyncWireCodec.ApplyResult replaceBlockedScenesBlocking(
        long generation,
        IGameScenePort port,
        Collection<String> blockedScenes
    ) throws Exception {
        if (blockedScenes == null) {
            throw new IllegalArgumentException("blockedScenes cannot be null");
        }
        return applyBlocking(
            generation,
            port,
            SceneSyncWireCodec.replaceBlockedScenes(blockedScenes)
        );
    }

    /**
     * Sends one publisher-prepared policy command without re-encoding it.
     * The publisher reuses the same immutable byte target for every retry.
     */
    SceneSyncWireCodec.ApplyResult replaceBlockedScenesBlocking(
        long generation,
        IGameScenePort port,
        byte[] encodedCommand
    ) throws Exception {
        if (encodedCommand == null || encodedCommand.length == 0) {
            throw new IllegalArgumentException(
                "encoded blocked Scene policy cannot be empty"
            );
        }
        return applyBlocking(
            generation,
            port,
            output -> output.write(encodedCommand)
        );
    }

    /**
     * Current-thread pipe implementation used by full-sync workers.  It is
     * deliberately independent of any executor; callers may safely wait for
     * the result while the operation's worker pool remains bounded.
     */
    private SceneSyncWireCodec.ApplyResult applyBlocking(
        long generation,
        IGameScenePort port,
        SceneSyncWireCodec.ApplyCommand command
    ) throws Exception {
        return applyBlocking(
            generation,
            port,
            output -> SceneSyncWireCodec.writeApply(output, command)
        );
    }

    @FunctionalInterface
    private interface RequestWriter {
        void write(OutputStream output) throws IOException;
    }

    private SceneSyncWireCodec.ApplyResult applyBlocking(
        long generation,
        IGameScenePort port,
        RequestWriter writer
    ) throws Exception {
        if (closed.get()) {
            throw new IOException("Scene apply coordinator is closed");
        }
        if (port == null || writer == null) {
            throw new IllegalArgumentException(
                "port and request writer are required"
            );
        }

        ParcelFileDescriptor[] requestPipe =
            ParcelFileDescriptor.createPipe();
        ParcelFileDescriptor[] resultPipe;
        try {
            resultPipe = ParcelFileDescriptor.createPipe();
        } catch (IOException e) {
            closeQuietly(requestPipe[0]);
            closeQuietly(requestPipe[1]);
            throw e;
        }

        BlockingCall blockingCall = new BlockingCall(
            requestPipe[0],
            requestPipe[1],
            resultPipe[1],
            resultPipe[0]
        );
        synchronized (taskLock) {
            if (closed.get()) {
                blockingCall.cancel();
                closeQuietly(requestPipe[0]);
                closeQuietly(resultPipe[1]);
                throw new IOException("Scene apply coordinator is closed");
            }
            activeBlockingCalls.add(blockingCall);
        }

        try {
            if (blockingCall.cancelled.get()) {
                throw new IOException("Scene apply coordinator is closed");
            }

            boolean accepted;
            try {
                accepted = port.applySceneChanges(
                    generation,
                    requestPipe[0],
                    resultPipe[1]
                );
            } finally {
                // The Binder call transfers duped descriptors to the game.
                // These local call-side copies must never remain open while
                // the caller writes/reads the task-owned endpoints below.
                closeQuietly(requestPipe[0]);
                closeQuietly(resultPipe[1]);
            }
            if (!accepted) {
                throw new IOException("game rejected Scene apply");
            }

            Exception writeFailure = null;
            try (OutputStream output =
                     new ParcelFileDescriptor.AutoCloseOutputStream(
                         requestPipe[1]
                     )) {
                writer.write(output);
                output.flush();
            } catch (IOException | RuntimeException e) {
                writeFailure = e;
            }

            SceneSyncWireCodec.ApplyResult result = null;
            Exception resultFailure = null;
            try (InputStream input =
                     new ParcelFileDescriptor.AutoCloseInputStream(
                         resultPipe[0]
                     )) {
                result = SceneSyncWireCodec.decodeApplyResult(input);
            } catch (IOException | RuntimeException e) {
                resultFailure = e;
            }
            if (writeFailure != null) {
                // A locally incomplete request is the primary diagnostic even
                // if the peer also reports EOF or an invalid result.
                if (resultFailure != null) {
                    writeFailure.addSuppressed(resultFailure);
                }
                throw writeFailure;
            }
            if (resultFailure != null) {
                throw resultFailure;
            }
            if (blockingCall.cancelled.get() || closed.get()) {
                throw new IOException("Scene apply was cancelled");
            }
            return result;
        } finally {
            blockingCall.cancel();
            synchronized (taskLock) {
                activeBlockingCalls.remove(blockingCall);
                taskLock.notifyAll();
            }
        }
    }

    @Override
    public void close() {
        requestClose();
        awaitBlockingCalls();
    }

    /**
     * Requests cancellation without waiting for a Binder call to return.
     *
     * <p>The game-side abort must be sent before this method when the caller
     * owns a live Scene-sync generation.  Closing the local pipe descriptors
     * wakes normal stream I/O, but it cannot interrupt an already-entered
     * Binder method.  The full {@link #close()} remains available to the run
     * owner after that remote call has returned.</p>
     */
    public void requestClose() {
        List<BlockingCall> blockingCalls;
        boolean firstClose;
        synchronized (taskLock) {
            firstClose = closed.compareAndSet(false, true);
            blockingCalls = new ArrayList<>(activeBlockingCalls);
        }
        if (firstClose) {
            // Close endpoints before waiting so blocked pipe I/O wakes.
            for (BlockingCall call : blockingCalls) {
                call.cancel();
            }
        }
    }

    private void awaitBlockingCalls() {
        boolean interrupted = false;
        synchronized (taskLock) {
            while (!activeBlockingCalls.isEmpty()) {
                try {
                    taskLock.wait();
                } catch (InterruptedException e) {
                    interrupted = true;
                }
            }
        }
        if (interrupted) {
            Thread.currentThread().interrupt();
        }
    }

    private static final class BlockingCall {
        private final ParcelFileDescriptor requestReadFd;
        private final ParcelFileDescriptor requestWriteFd;
        private final ParcelFileDescriptor resultWriteFd;
        private final ParcelFileDescriptor resultReadFd;
        private final AtomicBoolean cancelled = new AtomicBoolean();

        private BlockingCall(
            ParcelFileDescriptor requestReadFd,
            ParcelFileDescriptor requestWriteFd,
            ParcelFileDescriptor resultWriteFd,
            ParcelFileDescriptor resultReadFd
        ) {
            this.requestReadFd = requestReadFd;
            this.requestWriteFd = requestWriteFd;
            this.resultWriteFd = resultWriteFd;
            this.resultReadFd = resultReadFd;
        }

        private void cancel() {
            if (!cancelled.compareAndSet(false, true)) {
                return;
            }
            closeQuietly(requestReadFd);
            closeQuietly(requestWriteFd);
            closeQuietly(resultWriteFd);
            closeQuietly(resultReadFd);
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
}
