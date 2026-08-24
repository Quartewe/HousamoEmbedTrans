package com.quarty.housamoembedtrans.scene.sync;

import com.quarty.housamoembedtrans.bridge.SceneSyncWireCodec;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Game-side export operation for one fixed Scene snapshot.
 *
 * <p>The source callback is synchronous: returning from {@code scene(...)}
 * means that item's bytes have already been emitted to the wire.  This gives
 * callers a bounded per-Scene lifetime without retaining the complete export
 * in memory.</p>
 */
public final class SceneMirrorExportCoordinator {
    public interface ProductionGate {
        boolean beginHold();

        void waitForActiveZero();
    }

    @FunctionalInterface
    public interface SceneSource {
        void stream(SceneConsumer consumer) throws Exception;
    }

    public interface SceneConsumer {
        void scene(String sceneName, byte[] sceneBytes) throws Exception;

        void rejected(String sceneName, int errorCode) throws Exception;
    }

    private final ProductionGate productionGate;
    private final SceneSource sceneSource;
    private final AtomicBoolean inFlight = new AtomicBoolean();

    public SceneMirrorExportCoordinator(
        ProductionGate productionGate,
        SceneSource sceneSource
    ) {
        if (productionGate == null || sceneSource == null) {
            throw new IllegalArgumentException(
                "productionGate and sceneSource cannot be null"
            );
        }
        this.productionGate = productionGate;
        this.sceneSource = sceneSource;
    }

    /**
     * Accepts one export and schedules its writer.  The writer is submitted
     * before native hold establishment but cannot start until the method has
     * successfully established the hold and opens the start gate.  A null
     * return means the export was not accepted and no policy was changed.
     */
    public ExportSession acceptExport(Executor writerExecutor, OutputStream output) {
        if (writerExecutor == null
            || output == null
            || !inFlight.compareAndSet(false, true)) {
            return null;
        }

        ExportSession session = new ExportSession(output);
        try {
            writerExecutor.execute(session::run);
        } catch (RuntimeException e) {
            session.cancelBeforeStartWithoutWriter();
            return null;
        }

        boolean holdEstablished;
        try {
            holdEstablished = productionGate.beginHold();
        } catch (RuntimeException e) {
            holdEstablished = false;
        }
        if (!holdEstablished) {
            session.cancelBeforeStart();
            return null;
        }
        session.startGate.countDown();
        return session;
    }

    public final class ExportSession implements AutoCloseable {
        private final OutputStream output;
        private final CountDownLatch startGate = new CountDownLatch(1);
        private final AtomicBoolean cancelRequested = new AtomicBoolean();
        private final AtomicBoolean outputClosed = new AtomicBoolean();
        private final AtomicBoolean finished = new AtomicBoolean();
        private final AtomicBoolean completionNotified = new AtomicBoolean();
        private volatile Runnable completionListener;
        private volatile boolean successful;

        private ExportSession(OutputStream output) {
            this.output = output;
        }

        private void run() {
            try {
                startGate.await();
                if (cancelRequested.get()) {
                    return;
                }
                SceneSyncWireCodec.StreamingExportWriter writer =
                    SceneSyncWireCodec.beginStreamingExport(output);
                try {
                    productionGate.waitForActiveZero();
                    if (cancelRequested.get()) {
                        return;
                    }
                    writerSource(writer);
                    if (cancelRequested.get()) {
                        return;
                    }
                    writer.finish();
                    output.flush();
                    successful = true;
                } finally {
                    writer.close();
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                abort();
            } catch (Exception e) {
                // The peer observes a missing END and performs the cycle's
                // normal failure cleanup.  The writer itself must not reset
                // the native hold or blocked list in a finally path.
                abort();
            } finally {
                closeOutput();
                // The writer is the sole owner of the single-flight release.
                // External cancellation only requests termination and closes
                // the pipe; it must not admit a second export while this
                // writer can still be blocked in waitForActiveZero().
                inFlight.set(false);
                finished.set(true);
                notifyCompletionListener();
            }
        }

        /** Installs a normal/abort completion callback for the owning port. */
        public void setCompletionListener(Runnable listener) {
            completionListener = listener;
            if (finished.get()) {
                notifyCompletionListener();
            }
        }

        private void notifyCompletionListener() {
            Runnable listener = completionListener;
            if (listener == null) {
                return;
            }
            if (!completionNotified.compareAndSet(false, true)) {
                return;
            }
            try {
                listener.run();
            } catch (RuntimeException ignored) {
                // A lifecycle observer must not affect stream cleanup.
            }
        }

        private void writerSource(
            SceneSyncWireCodec.StreamingExportWriter writer
        ) throws Exception {
            sceneSource.stream(new SceneConsumer() {
                @Override
                public void scene(String sceneName, byte[] sceneBytes)
                    throws IOException {
                    ensureNotCancelled();
                    if (sceneBytes == null) {
                        throw new IllegalArgumentException(
                            "Scene body cannot be null"
                        );
                    }
                    writer.writeScene(
                        sceneName,
                        new ByteArrayInputStream(sceneBytes),
                        sceneBytes.length
                    );
                }

                @Override
                public void rejected(String sceneName, int errorCode)
                    throws IOException {
                    ensureNotCancelled();
                    writer.writeRejected(sceneName, errorCode);
                }
            });
        }

        private void ensureNotCancelled() throws IOException {
            if (cancelRequested.get()) {
                throw new IOException("Scene export cancelled");
            }
        }

        private void closeOutput() {
            if (!outputClosed.compareAndSet(false, true)) {
                return;
            }
            try {
                output.close();
            } catch (IOException ignored) {
                // The peer still observes the stream termination.
            }
        }

        public boolean isSuccessful() {
            return successful;
        }

        public void abort() {
            cancelRequested.set(true);
            closeOutput();
        }

        private void cancelBeforeStart() {
            cancelRequested.set(true);
            startGate.countDown();
            closeOutput();
        }

        private void cancelBeforeStartWithoutWriter() {
            cancelBeforeStart();
            // No writer finally can run after executor rejection, so this is
            // the one pre-start path allowed to release the single-flight
            // gate itself.
            inFlight.set(false);
        }

        @Override
        public void close() {
            if (!successful) {
                abort();
            }
        }
    }
}
