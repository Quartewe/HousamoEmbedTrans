package com.quarty.housamoembedtrans.translation;

import android.util.Log;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * Connection-scoped, at-least-once terminal delivery coordinator.
 *
 * <p>The coordinator deliberately owns no durable state.  It rereads the
 * immutable result/error payload for every attempt and stores only generation
 * and timer bookkeeping in the process.  A callback replacement invalidates
 * all old timers and immediately replays the durable pending set.</p>
 */
public final class TerminalDeliveryCoordinator implements AutoCloseable {
    private static final String TAG = "HET-TerminalDelivery";
    public interface Store {
        List<TranslationJobStore.TerminalJob> listPendingTerminalJobs()
            throws Exception;

        TranslationJobStore.TerminalJob readPendingTerminalJob(
            String requestId
        ) throws Exception;

        byte[] readCompletedResult(String requestId) throws Exception;

        byte[] readFailedError(String requestId) throws Exception;
    }

    public interface Callback {
        void sendCompleted(
            String requestId,
            String scene,
            String targetLanguage,
            byte[] resultJson
        ) throws Exception;

        void sendFailed(
            String requestId,
            String errorType,
            String message
        ) throws Exception;
    }

    private static final long[] RETRY_DELAYS_MS = {
        15_000L,
        30_000L,
        60_000L
    };

    private final Store store;
    private final ScheduledExecutorService scheduler;
    private final boolean ownsScheduler;
    private final Object lock = new Object();
    private final Map<String, Attempt> attempts = new HashMap<>();
    private Callback callback;
    private long generation;
    private boolean closed;
    private boolean released;
    private boolean replayScanScheduled;
    private ScheduledFuture<?> replayScanTimer;
    private int replayScanDelayIndex;

    private static final class Attempt {
        private final long generation;
        private final TerminalOutcome.Kind kind;
        private int delayIndex;
        private ScheduledFuture<?> timer;

        private Attempt(long generation, TerminalOutcome.Kind kind) {
            this.generation = generation;
            this.kind = kind;
        }
    }

    public TerminalDeliveryCoordinator(Store store) {
        this(
            store,
            Executors.newSingleThreadScheduledExecutor(runnable -> {
                Thread thread = new Thread(runnable, "HET-terminal-delivery");
                thread.setDaemon(true);
                return thread;
            }),
            true
        );
    }

    public TerminalDeliveryCoordinator(
        Store store,
        ScheduledExecutorService scheduler
    ) {
        this(store, scheduler, false);
    }

    private TerminalDeliveryCoordinator(
        Store store,
        ScheduledExecutorService scheduler,
        boolean ownsScheduler
    ) {
        if (store == null || scheduler == null) {
            throw new IllegalArgumentException("store and scheduler required");
        }
        this.store = store;
        this.scheduler = scheduler;
        this.ownsScheduler = ownsScheduler;
    }

    /** Installs one callback for the current connection generation. */
    public void bind(Callback callback) {
        if (callback == null) {
            throw new IllegalArgumentException("callback cannot be null");
        }
        synchronized (lock) {
            ensureOpenLocked();
            generation++;
            cancelAttemptsLocked();
            cancelReplayScanLocked();
            this.callback = callback;
        }
        if (released) {
            scheduleReplay();
        }
    }

    /**
     * Releases the Scene-Sync startup gate. Before this call the coordinator
     * may accept a callback and terminal persistence events, but it never
     * scans or dispatches durable terminal outcomes. After release, pending
     * work and any later {@code onStoreStateChanged()} are replayed normally.
     */
    public void release() {
        synchronized (lock) {
            if (closed || released) {
                return;
            }
            released = true;
        }
        scheduleReplay();
    }

    /** Returns whether terminal redelivery has been released by Scene Sync. */
    public boolean isReleased() {
        synchronized (lock) {
            return released;
        }
    }

    /** Invalidates the callback and every timer for the old Binder. */
    public void unbind() {
        synchronized (lock) {
            generation++;
            cancelAttemptsLocked();
            cancelReplayScanLocked();
            callback = null;
        }
    }

    /** Schedules newly persisted terminal work for the active connection. */
    public void onTerminalPersisted(String requestId) {
        if (requestId == null || requestId.isEmpty()) {
            return;
        }
        final long expectedGeneration;
        synchronized (lock) {
            if (closed || callback == null || !released) {
                return;
            }
            expectedGeneration = generation;
        }
        try {
            scheduler.execute(() -> scheduleExact(requestId, expectedGeneration));
        } catch (RuntimeException ignored) {
            // A closing scheduler leaves the durable outcome for the next
            // callback generation/startup scan.
        }
    }

    /**
     * Re-scans durable pending outcomes after Store startup reconciliation
     * changes which directories are stable.  This is intentionally separate
     * from {@link #onTerminalPersisted(String)}: a terminal payload may have
     * existed before the callback connection was installed, but must not be
     * replayed until the background repair pass has validated it.
     */
    public void onStoreStateChanged() {
        synchronized (lock) {
            if (!released) {
                return;
            }
        }
        scheduleReplay();
    }

    /** Called by the service after the Binder ACK is durably committed. */
    public void onAcknowledged(String requestId) {
        synchronized (lock) {
            Attempt attempt = attempts.remove(requestId);
            if (attempt != null && attempt.timer != null) {
                attempt.timer.cancel(false);
            }
        }
    }

    private void scheduleReplay() {
        final long replayGeneration;
        synchronized (lock) {
            if (closed || callback == null) {
                return;
            }
            if (replayScanScheduled || replayScanTimer != null) {
                return;
            }
            replayScanScheduled = true;
            replayGeneration = generation;
        }
        try {
            scheduler.execute(() -> replayPending(replayGeneration));
        } catch (RuntimeException ignored) {
            // A closing scheduler simply leaves the durable pending outcome
            // for the next callback generation/startup scan.
            synchronized (lock) {
                if (generation == replayGeneration) {
                    replayScanScheduled = false;
                }
            }
        }
    }

    private void replayPending(long replayGeneration) {
        synchronized (lock) {
            if (closed || callback == null || generation != replayGeneration) {
                return;
            }
            replayScanScheduled = false;
        }
        try {
            for (TranslationJobStore.TerminalJob job
                : store.listPendingTerminalJobs()) {
                scheduleOne(job, replayGeneration);
            }
            synchronized (lock) {
                if (generation == replayGeneration) {
                    if (replayScanTimer != null) {
                        replayScanTimer.cancel(false);
                    }
                    replayScanDelayIndex = 0;
                    replayScanTimer = null;
                }
            }
        } catch (Exception e) {
            Log.w(
                TAG,
                "pending terminal scan failed generation="
                    + replayGeneration,
                e
            );
            synchronized (lock) {
                if (!closed && callback != null
                    && generation == replayGeneration) {
                    scheduleReplayScanRetryLocked(replayGeneration);
                }
            }
        }
    }

    private void scheduleExact(String requestId, long expectedGeneration) {
        synchronized (lock) {
            if (closed || callback == null || generation != expectedGeneration) {
                return;
            }
        }
        try {
            TranslationJobStore.TerminalJob job =
                store.readPendingTerminalJob(requestId);
            if (job != null) {
                scheduleOne(job, expectedGeneration);
            }
        } catch (Exception e) {
            Log.w(
                TAG,
                "exact terminal scan failed requestId=" + requestId
                    + " generation=" + expectedGeneration,
                e
            );
            // A transient exact-read error still gets one generation-level
            // full scan, rather than an unbounded per-request retry loop.
            scheduleReplay();
        }
    }

    private void scheduleOne(
        TranslationJobStore.TerminalJob job,
        long expectedGeneration
    ) {
        if (job == null || !job.requiresDelivery()) {
            return;
        }
        TerminalOutcome.Kind kind = job.getKind();
        if (kind == null) {
            return;
        }
        final Callback callbackSnapshot;
        final Attempt attempt;
        synchronized (lock) {
            if (closed || callback == null || generation != expectedGeneration) {
                return;
            }
            Attempt existing = attempts.get(job.getRequestId());
            if (existing != null && existing.generation == generation
                && existing.kind == kind) {
                return;
            }
            if (existing != null) {
                if (existing.timer != null) {
                    existing.timer.cancel(false);
                }
                attempts.remove(job.getRequestId());
            }
            attempt = new Attempt(generation, kind);
            attempts.put(job.getRequestId(), attempt);
            callbackSnapshot = callback;
        }
        // Store reads and Binder callbacks can re-enter this service.  Never
        // hold coordinator.lock while invoking either external boundary.
        dispatchAttempt(job, attempt, callbackSnapshot);
    }

    private void dispatchAttempt(
        TranslationJobStore.TerminalJob job,
        Attempt attempt,
        Callback callbackSnapshot
    ) {
        synchronized (lock) {
            if (closed || callback != callbackSnapshot
                || attempt.generation != generation
                || attempts.get(job.getRequestId()) != attempt) {
                return;
            }
        }
        try {
            if (attempt.kind == TerminalOutcome.Kind.COMPLETED) {
                byte[] result = store.readCompletedResult(job.getRequestId());
                if (result == null || result.length == 0) {
                    throw new IllegalStateException("missing result payload");
                }
                callbackSnapshot.sendCompleted(
                    job.getRequestId(),
                    job.getScene(),
                    job.getTargetLanguage(),
                    result
                );
            } else {
                byte[] error = store.readFailedError(job.getRequestId());
                if (error == null || error.length == 0) {
                    throw new IllegalStateException("missing error payload");
                }
                String type = job.getErrorType().isEmpty()
                    ? "translation"
                    : job.getErrorType();
                String message = job.getErrorMessage().isEmpty()
                    ? new String(error, java.nio.charset.StandardCharsets.UTF_8)
                    : job.getErrorMessage();
                callbackSnapshot.sendFailed(job.getRequestId(), type, message);
            }
        } catch (Exception e) {
            // Keep pending responsibility and use the same bounded schedule.
            Log.w(
                TAG,
                "terminal callback attempt failed requestId="
                    + job.getRequestId()
                    + " kind="
                    + attempt.kind
                    + " generation="
                    + attempt.generation,
                e
            );
        }
        synchronized (lock) {
            if (closed || callback != callbackSnapshot
                || attempt.generation != generation
                || attempts.get(job.getRequestId()) != attempt) {
                return;
            }
            scheduleRetryLocked(job, attempt);
        }
    }

    private void retryAttempt(
        TranslationJobStore.TerminalJob original,
        Attempt attempt,
        long expectedGeneration
    ) {
        final Callback callbackSnapshot;
        synchronized (lock) {
            if (closed || callback == null || generation != expectedGeneration
                || attempts.get(original.getRequestId()) != attempt) {
                return;
            }
            callbackSnapshot = callback;
        }

        TranslationJobStore.TerminalJob current = null;
        try {
            current = store.readPendingTerminalJob(original.getRequestId());
        } catch (Exception e) {
            Log.w(
                TAG,
                "terminal retry scan failed requestId="
                    + original.getRequestId()
                    + " kind="
                    + attempt.kind
                    + " generation="
                    + expectedGeneration,
                e
            );
            synchronized (lock) {
                if (!closed && callback == callbackSnapshot
                    && generation == expectedGeneration
                    && attempts.get(original.getRequestId()) == attempt) {
                    scheduleRetryLocked(original, attempt);
                }
            }
            return;
        }

        if (current == null) {
            synchronized (lock) {
                if (attempts.get(original.getRequestId()) == attempt) {
                    attempts.remove(original.getRequestId());
                }
            }
            return;
        }
        if (current.getKind() != attempt.kind) {
            synchronized (lock) {
                if (attempts.get(original.getRequestId()) == attempt) {
                    attempts.remove(original.getRequestId());
                }
            }
            scheduleOne(current, expectedGeneration);
            return;
        }
        dispatchAttempt(current, attempt, callbackSnapshot);
    }

    private void scheduleRetryLocked(
        TranslationJobStore.TerminalJob job,
        Attempt attempt
    ) {
        if (closed || callback == null || attempt.generation != generation) {
            return;
        }
        long delay = RETRY_DELAYS_MS[
            Math.min(attempt.delayIndex, RETRY_DELAYS_MS.length - 1)
        ];
        if (attempt.delayIndex < RETRY_DELAYS_MS.length - 1) {
            attempt.delayIndex++;
        }
        long expectedGeneration = generation;
        attempt.timer = scheduler.schedule(
            () -> {
                retryAttempt(job, attempt, expectedGeneration);
            },
            delay,
            TimeUnit.MILLISECONDS
        );
    }

    private void cancelAttemptsLocked() {
        for (Attempt attempt : attempts.values()) {
            if (attempt.timer != null) {
                attempt.timer.cancel(false);
            }
        }
        attempts.clear();
    }

    private void scheduleReplayScanRetryLocked(long expectedGeneration) {
        if (replayScanTimer != null || closed || callback == null
            || generation != expectedGeneration) {
            return;
        }
        long delay = RETRY_DELAYS_MS[
            Math.min(replayScanDelayIndex, RETRY_DELAYS_MS.length - 1)
        ];
        if (replayScanDelayIndex < RETRY_DELAYS_MS.length - 1) {
            replayScanDelayIndex++;
        }
        try {
            replayScanTimer = scheduler.schedule(() -> {
                synchronized (lock) {
                    if (generation != expectedGeneration) {
                        return;
                    }
                    replayScanTimer = null;
                    replayScanScheduled = true;
                }
                replayPending(expectedGeneration);
            }, delay, TimeUnit.MILLISECONDS);
        } catch (RuntimeException ignored) {
            replayScanTimer = null;
        }
    }

    private void cancelReplayScanLocked() {
        replayScanScheduled = false;
        replayScanDelayIndex = 0;
        if (replayScanTimer != null) {
            replayScanTimer.cancel(false);
            replayScanTimer = null;
        }
    }

    private void ensureOpenLocked() {
        if (closed) {
            throw new IllegalStateException("terminal delivery is closed");
        }
    }

    @Override
    public void close() {
        synchronized (lock) {
            if (closed) {
                return;
            }
            closed = true;
            generation++;
            cancelAttemptsLocked();
            cancelReplayScanLocked();
            callback = null;
        }
        if (ownsScheduler) {
            scheduler.shutdownNow();
        }
    }
}
