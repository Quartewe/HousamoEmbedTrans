package com.quarty.housamoembedtrans.translation;

import com.quarty.housamoembedtrans.bridge.SceneSyncWireCodec;
import com.quarty.housamoembedtrans.storage.SceneStore;

import android.os.ParcelFileDescriptor;
import android.os.RemoteException;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.SynchronousQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * HET-side request/result pipe owner for one or more game Scene applies.
 *
 * <p>A local writer task is admitted before the Binder call and waits on a
 * start gate.  This makes a rejected Binder call close both local endpoints
 * without starting a producer, while a successful call returns immediately
 * after the game has accepted both transferred descriptors.  The task then
 * writes one complete request and decodes the dedicated result stream.</p>
 */
public final class SceneApplyCoordinator implements AutoCloseable {
    public static final class Operation {
        public final SceneSyncWireCodec.RecordType type;
        /** Null for REPLACE_BLOCKED_SCENES, which has no Scene identity. */
        public final String sceneName;

        private Operation(
            SceneSyncWireCodec.RecordType type,
            String sceneName
        ) {
            this.type = type;
            this.sceneName = sceneName;
        }
    }

    public interface ResultListener {
        void onResult(
            Operation operation,
            SceneSyncWireCodec.ApplyResult result
        );

        void onStreamFailure(Operation operation, Exception error);
    }

    public final class Submission implements AutoCloseable {
        private final Operation operation;
        private final ApplyTask task;
        private final boolean accepted;

        private Submission(
            Operation operation,
            ApplyTask task,
            boolean accepted
        ) {
            this.operation = operation;
            this.task = task;
            this.accepted = accepted;
        }

        public boolean isAccepted() {
            return accepted;
        }

        public String getSceneName() {
            return operation.sceneName;
        }

        public Operation getOperation() {
            return operation;
        }

        public Future<?> getTask() {
            return task == null ? null : task.future;
        }

        @Override
        public void close() {
            if (task != null) {
                task.cancel();
            }
        }
    }

    private final ExecutorService executor;
    private final boolean ownsExecutor;
    private final Object taskLock = new Object();
    private final Set<ApplyTask> activeTasks =
        ConcurrentHashMap.newKeySet();
    private final Set<BlockingCall> activeBlockingCalls = new HashSet<>();
    private final AtomicBoolean closed = new AtomicBoolean();

    /** Creates an operation-owned two-thread pipe executor. */
    public SceneApplyCoordinator() {
        this(createOwnedExecutor(), true);
    }

    private static ExecutorService createOwnedExecutor() {
        return new ThreadPoolExecutor(
            2,
            2,
            0L,
            TimeUnit.MILLISECONDS,
            new SynchronousQueue<>(),
            runnable -> {
                Thread thread = new Thread(runnable, "HET-scene-apply");
                thread.setDaemon(true);
                return thread;
            },
            new ThreadPoolExecutor.AbortPolicy()
        );
    }

    /** Injects an operation executor for tests or the full-sync owner. */
    public SceneApplyCoordinator(ExecutorService executor) {
        this(executor, false);
    }

    private SceneApplyCoordinator(ExecutorService executor, boolean ownsExecutor) {
        if (executor == null) {
            throw new IllegalArgumentException("executor cannot be null");
        }
        this.executor = executor;
        this.ownsExecutor = ownsExecutor;
    }

    /**
     * Submits one HET-owned Scene snapshot.  The body is the already-read
     * fixed snapshot; the game side receives it only as a bounded wire body.
     */
    public Submission submitWrite(
        long generation,
        IGameScenePort port,
        String sceneName,
        byte[] sceneBytes,
        ResultListener listener
    ) {
        if (sceneBytes == null) {
            throw new IllegalArgumentException("sceneBytes cannot be null");
        }
        return submitCommand(
            generation,
            port,
            new Operation(
                SceneSyncWireCodec.RecordType.WRITE_SCENE,
                sceneName
            ),
            SceneSyncWireCodec.writeScene(sceneName, sceneBytes),
            listener
        );
    }

    /**
     * Applies one Scene synchronously on the caller's thread.
     *
     * <p>Full sync workers use this seam instead of submitting a pipe task to
     * the same bounded executor that is waiting for the result.  The game
     * side still owns its own apply activity; this method only owns the two
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
            new Operation(
                SceneSyncWireCodec.RecordType.WRITE_SCENE,
                sceneName
            ),
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
            new Operation(
                SceneSyncWireCodec.RecordType.DELETE_SCENE,
                sceneName
            ),
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
            new Operation(
                SceneSyncWireCodec.RecordType.REPLACE_BLOCKED_SCENES,
                null
            ),
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
            new Operation(
                SceneSyncWireCodec.RecordType.REPLACE_BLOCKED_SCENES,
                null
            ),
            output -> output.write(encodedCommand)
        );
    }

    /** Sends the final immutable blocked-list publication command. */
    public Submission submitReplaceBlockedScenes(
        long generation,
        IGameScenePort port,
        Collection<String> blockedScenes,
        ResultListener listener
    ) {
        if (blockedScenes == null) {
            throw new IllegalArgumentException("blockedScenes cannot be null");
        }
        return submitCommand(
            generation,
            port,
            new Operation(
                SceneSyncWireCodec.RecordType.REPLACE_BLOCKED_SCENES,
                null
            ),
            SceneSyncWireCodec.replaceBlockedScenes(blockedScenes),
            listener
        );
    }

    private Submission submitCommand(
        long generation,
        IGameScenePort port,
        Operation operation,
        SceneSyncWireCodec.ApplyCommand command,
        ResultListener listener
    ) {
        if (closed.get()) {
            return new Submission(operation, null, false);
        }
        if (port == null || operation == null || command == null) {
            throw new IllegalArgumentException(
                "port, operation, and command are required"
            );
        }
        if (listener == null) {
            throw new IllegalArgumentException("listener cannot be null");
        }

        ParcelFileDescriptor[] requestPipe;
        try {
            requestPipe = ParcelFileDescriptor.createPipe();
        } catch (IOException e) {
            notifyFailure(listener, operation, e);
            return new Submission(operation, null, false);
        }
        ParcelFileDescriptor[] resultPipe;
        try {
            resultPipe = ParcelFileDescriptor.createPipe();
        } catch (IOException e) {
            closeQuietly(requestPipe[0]);
            closeQuietly(requestPipe[1]);
            notifyFailure(listener, operation, e);
            return new Submission(operation, null, false);
        }

        ApplyTask task = new ApplyTask(
            operation,
            command,
            requestPipe[1],
            resultPipe[0],
            listener
        );
        synchronized (taskLock) {
            if (closed.get()) {
                task.cancelBeforeStart();
                closeQuietly(requestPipe[0]);
                closeQuietly(resultPipe[1]);
                return new Submission(operation, null, false);
            }
            activeTasks.add(task);
            try {
                task.future = executor.submit(task);
            } catch (RejectedExecutionException e) {
                activeTasks.remove(task);
                task.cancelBeforeStart();
                notifyFailure(listener, operation, e);
                closeQuietly(requestPipe[0]);
                closeQuietly(resultPipe[1]);
                return new Submission(operation, null, false);
            }
        }

        if (closed.get()) {
            task.cancelBeforeStart();
            closeQuietly(requestPipe[0]);
            closeQuietly(resultPipe[1]);
            return new Submission(operation, null, false);
        }

        boolean accepted = false;
        try {
            accepted = port.applySceneChanges(
                generation,
                requestPipe[0],
                resultPipe[1]
            );
        } catch (RemoteException | RuntimeException e) {
            notifyFailure(listener, operation, e);
        } finally {
            // The Binder call transferred duped descriptors to the game.  HET
            // closes its call-side copies immediately; the task owns the
            // request writer/result reader endpoints.
            closeQuietly(requestPipe[0]);
            closeQuietly(resultPipe[1]);
        }
        if (!accepted) {
            task.cancelBeforeStart();
            return new Submission(operation, null, false);
        }
        task.startGate.countDown();
        return new Submission(operation, task, true);
    }

    /**
     * Current-thread pipe implementation used by full-sync workers.  It is
     * deliberately independent of {@link #executor}; callers may safely wait
     * for the result while the operation's worker pool remains bounded.
     */
    private SceneSyncWireCodec.ApplyResult applyBlocking(
        long generation,
        IGameScenePort port,
        Operation operation,
        SceneSyncWireCodec.ApplyCommand command
    ) throws Exception {
        return applyBlocking(
            generation,
            port,
            operation,
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
        Operation operation,
        RequestWriter writer
    ) throws Exception {
        if (closed.get()) {
            throw new IOException("Scene apply coordinator is closed");
        }
        if (port == null || operation == null || writer == null) {
            throw new IllegalArgumentException(
                "port, operation, and request writer are required"
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

    private static void notifyFailure(
        ResultListener listener,
        Operation operation,
        Exception error
    ) {
        try {
            listener.onStreamFailure(operation, error);
        } catch (RuntimeException ignored) {
            // A result observer cannot be allowed to kill pipe cleanup.
        }
    }

    @Override
    public void close() {
        requestClose();
        awaitBlockingCalls();
        if (ownsExecutor) {
            awaitExecutorTermination();
        }
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
        List<ApplyTask> tasks;
        boolean firstClose;
        synchronized (taskLock) {
            firstClose = closed.compareAndSet(false, true);
            blockingCalls = new ArrayList<>(activeBlockingCalls);
            tasks = new ArrayList<>(activeTasks);
            activeTasks.clear();
        }
        if (firstClose) {
            // Close endpoints before waiting so blocked pipe I/O wakes.
            for (BlockingCall call : blockingCalls) {
                call.cancel();
            }
            for (ApplyTask task : tasks) {
                task.cancel();
            }
            if (ownsExecutor) {
                executor.shutdownNow();
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

    private void awaitExecutorTermination() {
        boolean interrupted = false;
        while (!executor.isTerminated()) {
            try {
                executor.awaitTermination(100L, TimeUnit.MILLISECONDS);
            } catch (InterruptedException e) {
                interrupted = true;
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

    private final class ApplyTask implements Runnable {
        private final Operation operation;
        private final SceneSyncWireCodec.ApplyCommand command;
        private final ParcelFileDescriptor requestWriteFd;
        private final ParcelFileDescriptor resultReadFd;
        private final ResultListener listener;
        private final CountDownLatch startGate = new CountDownLatch(1);
        private final AtomicBoolean cancelled = new AtomicBoolean();
        private Future<?> future;

        private ApplyTask(
            Operation operation,
            SceneSyncWireCodec.ApplyCommand command,
            ParcelFileDescriptor requestWriteFd,
            ParcelFileDescriptor resultReadFd,
            ResultListener listener
        ) {
            this.operation = operation;
            this.command = command;
            this.requestWriteFd = requestWriteFd;
            this.resultReadFd = resultReadFd;
            this.listener = listener;
        }

        @Override
        public void run() {
            try {
                startGate.await();
                if (cancelled.get()) {
                    return;
                }
                writeRequestAndReadResult();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                notifyFailure(listener, operation, e);
            } finally {
                closeQuietly(requestWriteFd);
                closeQuietly(resultReadFd);
                synchronized (taskLock) {
                    activeTasks.remove(this);
                    taskLock.notifyAll();
                }
            }
        }

        private void writeRequestAndReadResult() {
            Exception writeFailure = null;
            try (OutputStream output =
                     new ParcelFileDescriptor.AutoCloseOutputStream(
                         requestWriteFd
                     )) {
                SceneSyncWireCodec.writeApply(output, command);
                output.flush();
            } catch (IOException | RuntimeException e) {
                writeFailure = e;
            }

            try (InputStream input =
                     new ParcelFileDescriptor.AutoCloseInputStream(
                         resultReadFd
                     )) {
                SceneSyncWireCodec.ApplyResult result =
                    SceneSyncWireCodec.decodeApplyResult(input);
                if (writeFailure != null) {
                    // A locally incomplete request is never considered a
                    // successful apply, even if the peer happened to emit a
                    // structurally valid result before noticing EOF.
                    notifyFailure(listener, operation, writeFailure);
                } else if (!cancelled.get() && !closed.get()) {
                    try {
                        listener.onResult(operation, result);
                    } catch (RuntimeException ignored) {
                        // The transport has completed even if the observer fails.
                    }
                }
            } catch (IOException | RuntimeException e) {
                if (writeFailure != null) {
                    notifyFailure(listener, operation, writeFailure);
                } else {
                    notifyFailure(listener, operation, e);
                }
            }
        }

        private void cancelBeforeStart() {
            cancelled.set(true);
            startGate.countDown();
            closeQuietly(requestWriteFd);
            closeQuietly(resultReadFd);
        }

        private void cancel() {
            cancelled.set(true);
            startGate.countDown();
            Future<?> pending = future;
            if (pending != null) {
                pending.cancel(true);
            }
            closeQuietly(requestWriteFd);
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
