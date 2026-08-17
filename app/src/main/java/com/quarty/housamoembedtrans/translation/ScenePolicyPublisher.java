package com.quarty.housamoembedtrans.translation;

import com.quarty.housamoembedtrans.bridge.SceneSyncWireCodec;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;

/**
 * Service-generation owner of the last successfully published Scene policy.
 *
 * <p>The cache is never reconstructed from ConflictStore.  One publication
 * prepares a complete, sorted target and one encoded command, then reuses
 * those exact bytes for the initial attempt and at most three retries.</p>
 * Manual remove-and-publish callers rely on SceneSyncCoordinator's
 * single-flight MANUAL_APPLY state; no concurrent cache read/modify/write is
 * admitted by the production control plane.
 */
public final class ScenePolicyPublisher implements AutoCloseable {
    public static final int MAX_ATTEMPTS = 4;

    public enum Outcome {
        PUBLISHED,
        STALE,
        FAILED_OPEN
    }

    /** Immutable identity gate for one registered game port generation. */
    public static final class Target {
        public final Object port;
        public final Object binder;
        public final long generation;

        private Target(Object port, Object binder, long generation) {
            this.port = port;
            this.binder = binder;
            this.generation = generation;
        }
    }

    /** Immutable pre-cycle cache used only by that cycle's failure exit. */
    public static final class CycleSnapshot {
        private final List<String> blockedScenes;

        private CycleSnapshot(List<String> blockedScenes) {
            this.blockedScenes = blockedScenes;
        }

        public List<String> getBlockedScenes() {
            return blockedScenes;
        }
    }

    public static final class PublishResult {
        public final Outcome outcome;
        public final int attempts;
        public final int lastErrorCode;
        public final Exception lastFailure;
        public final List<String> targetBlockedScenes;

        private PublishResult(
            Outcome outcome,
            int attempts,
            int lastErrorCode,
            Exception lastFailure,
            List<String> targetBlockedScenes
        ) {
            this.outcome = outcome;
            this.attempts = attempts;
            this.lastErrorCode = lastErrorCode;
            this.lastFailure = lastFailure;
            this.targetBlockedScenes = targetBlockedScenes;
        }

        public boolean isPublished() {
            return outcome == Outcome.PUBLISHED;
        }
    }

    @FunctionalInterface
    public interface PublishTransport {
        SceneSyncWireCodec.ApplyResult publish(byte[] encodedCommand)
            throws Exception;
    }

    private static final class PreparedTarget {
        private final List<String> blockedScenes;
        private final byte[] encodedCommand;

        private PreparedTarget(
            List<String> blockedScenes,
            byte[] encodedCommand
        ) {
            this.blockedScenes = blockedScenes;
            this.encodedCommand = encodedCommand;
        }
    }

    private final Object lock = new Object();
    private Target activeTarget;
    private List<String> lastSuccessfulBlockedScenes =
        Collections.emptyList();
    private boolean closed;

    /** Installs the only target allowed to update the service-level cache. */
    public Target activate(Object port, Object binder, long generation) {
        if (port == null || binder == null || generation <= 0L) {
            throw new IllegalArgumentException(
                "port, binder, and positive generation are required"
            );
        }
        synchronized (lock) {
            if (closed) {
                throw new IllegalStateException("Scene policy publisher is closed");
            }
            activeTarget = new Target(port, binder, generation);
            lastSuccessfulBlockedScenes = Collections.emptyList();
            return activeTarget;
        }
    }

    /** Returns the active token only when every captured identity still matches. */
    public Target capture(Object port, Object binder, long generation) {
        synchronized (lock) {
            Target current = activeTarget;
            return !closed
                    && current != null
                    && current.port == port
                    && current.binder == binder
                    && current.generation == generation
                ? current
                : null;
        }
    }

    /** Invalidates only the matching old target; a newer generation survives. */
    public void deactivate(Object port, Object binder, long generation) {
        synchronized (lock) {
            Target current = activeTarget;
            if (current != null
                && current.port == port
                && current.binder == binder
                && current.generation == generation) {
                activeTarget = null;
            }
        }
    }

    public CycleSnapshot snapshotForCycle(Target target) {
        synchronized (lock) {
            requireCurrentLocked(target);
            return new CycleSnapshot(lastSuccessfulBlockedScenes);
        }
    }

    public List<String> getLastSuccessfulBlockedScenes() {
        synchronized (lock) {
            return lastSuccessfulBlockedScenes;
        }
    }

    /** Publishes the complete policy produced by a successful full cycle. */
    public PublishResult publishCycleTarget(
        Target target,
        Collection<String> blockedScenes,
        PublishTransport transport
    ) throws Exception {
        return publishPrepared(target, prepare(blockedScenes), transport);
    }

    /** Restores exactly the cache captured before a communicable cycle failure. */
    public PublishResult publishPreCycleTarget(
        Target target,
        CycleSnapshot snapshot,
        PublishTransport transport
    ) throws Exception {
        if (snapshot == null) {
            throw new IllegalArgumentException("cycle snapshot cannot be null");
        }
        return publishPrepared(
            target,
            prepare(snapshot.blockedScenes),
            transport
        );
    }

    /** Removes one resolved Scene from the cached full list and republishes it. */
    public PublishResult removeAndPublish(
        Target target,
        String resolvedSceneName,
        PublishTransport transport
    ) throws Exception {
        if (resolvedSceneName == null || resolvedSceneName.isEmpty()) {
            throw new IllegalArgumentException("resolved SceneName is required");
        }
        final List<String> next;
        synchronized (lock) {
            requireCurrentLocked(target);
            next = new ArrayList<>(lastSuccessfulBlockedScenes);
        }
        next.remove(resolvedSceneName);
        return publishPrepared(target, prepare(next), transport);
    }

    private PublishResult publishPrepared(
        Target target,
        PreparedTarget prepared,
        PublishTransport transport
    ) {
        if (transport == null) {
            throw new IllegalArgumentException("policy transport cannot be null");
        }

        int attempts = 0;
        int lastErrorCode = SceneSyncWireCodec.APPLY_NONE;
        Exception lastFailure = null;
        for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
            if (!isCurrent(target)) {
                return new PublishResult(
                    Outcome.STALE,
                    attempts,
                    lastErrorCode,
                    lastFailure,
                    prepared.blockedScenes
                );
            }
            attempts = attempt;
            try {
                SceneSyncWireCodec.ApplyResult result = transport.publish(
                    prepared.encodedCommand
                );
                if (result != null && result.success) {
                    synchronized (lock) {
                        if (!isCurrentLocked(target)) {
                            return new PublishResult(
                                Outcome.STALE,
                                attempts,
                                lastErrorCode,
                                lastFailure,
                                prepared.blockedScenes
                            );
                        }
                        lastSuccessfulBlockedScenes = prepared.blockedScenes;
                    }
                    return new PublishResult(
                        Outcome.PUBLISHED,
                        attempts,
                        SceneSyncWireCodec.APPLY_NONE,
                        null,
                        prepared.blockedScenes
                    );
                }
                lastErrorCode = result == null
                    ? SceneSyncWireCodec.APPLY_INTERNAL_FAILURE
                    : result.errorCode;
            } catch (Exception e) {
                lastFailure = e;
                lastErrorCode = SceneSyncWireCodec.APPLY_REQUEST_STREAM_FAILED;
            }
        }

        synchronized (lock) {
            if (!isCurrentLocked(target)) {
                return new PublishResult(
                    Outcome.STALE,
                    attempts,
                    lastErrorCode,
                    lastFailure,
                    prepared.blockedScenes
                );
            }
            // Native policy replacement is fail-open on every failed attempt;
            // keep the HET runtime cache converged with that empty state.
            lastSuccessfulBlockedScenes = Collections.emptyList();
        }
        return new PublishResult(
            Outcome.FAILED_OPEN,
            attempts,
            lastErrorCode,
            lastFailure,
            prepared.blockedScenes
        );
    }

    private static PreparedTarget prepare(Collection<String> blockedScenes)
        throws SceneSyncWireCodec.ProtocolException {
        if (blockedScenes == null) {
            throw new IllegalArgumentException("blockedScenes cannot be null");
        }
        List<String> sorted = new ArrayList<>(blockedScenes);
        Collections.sort(sorted);
        List<String> immutable = Collections.unmodifiableList(sorted);
        byte[] encoded = SceneSyncWireCodec.encodeReplaceBlockedScenes(
            immutable
        );
        return new PreparedTarget(immutable, encoded);
    }

    private boolean isCurrent(Target target) {
        synchronized (lock) {
            return isCurrentLocked(target);
        }
    }

    private boolean isCurrentLocked(Target target) {
        return !closed && target != null && activeTarget == target;
    }

    private void requireCurrentLocked(Target target) {
        if (!isCurrentLocked(target)) {
            throw new IllegalStateException(
                "Scene policy target is stale or unavailable"
            );
        }
    }

    @Override
    public void close() {
        synchronized (lock) {
            closed = true;
            activeTarget = null;
            lastSuccessfulBlockedScenes = Collections.emptyList();
        }
    }
}
