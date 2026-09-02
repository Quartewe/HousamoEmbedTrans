package com.quarty.housamoembedtrans.scene.sync;
import com.quarty.housamoembedtrans.scene.store.ConflictStore;

import com.quarty.housamoembedtrans.bridge.SceneSyncWireCodec;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

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
        /** The peer accepted the command into the durable mutation pool. */
        DEFERRED,
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
        private final List<String> nonManagementBlockedScenes;
        private final List<String> managementPendingScenes;

        private CycleSnapshot(
            List<String> blockedScenes,
            List<String> nonManagementBlockedScenes,
            List<String> managementPendingScenes
        ) {
            this.blockedScenes = blockedScenes;
            this.nonManagementBlockedScenes = nonManagementBlockedScenes;
            this.managementPendingScenes = managementPendingScenes;
        }

        public List<String> getBlockedScenes() {
            return blockedScenes;
        }

        public List<String> getNonManagementBlockedScenes() {
            return nonManagementBlockedScenes;
        }

        public List<String> getManagementPendingScenes() {
            return managementPendingScenes;
        }

        /** Returns a failure-restore snapshot augmented with policy-only names. */
        public CycleSnapshot withAdditionalBlockedScenes(
            Collection<String> additionalScenes
        ) {
            if (additionalScenes == null) {
                throw new IllegalArgumentException(
                    "additional blocked Scenes cannot be null"
                );
            }
            Set<String> mergedManagement = new HashSet<>(
                managementPendingScenes
            );
            mergedManagement.addAll(additionalScenes);
            List<String> management = new ArrayList<>(mergedManagement);
            Collections.sort(management);
            Set<String> merged = new HashSet<>(nonManagementBlockedScenes);
            merged.addAll(management);
            List<String> sorted = new ArrayList<>(merged);
            Collections.sort(sorted);
            return new CycleSnapshot(
                Collections.unmodifiableList(sorted),
                nonManagementBlockedScenes,
                Collections.unmodifiableList(management)
            );
        }

        /** Replaces the management overlay while retaining the base policy. */
        public CycleSnapshot withManagementPendingScenes(
            Collection<String> managementScenes
        ) {
            if (managementScenes == null) {
                throw new IllegalArgumentException(
                    "management blocked Scenes cannot be null"
                );
            }
            List<String> management = new ArrayList<>(
                new HashSet<>(managementScenes)
            );
            Collections.sort(management);
            Set<String> merged = new HashSet<>(nonManagementBlockedScenes);
            merged.addAll(management);
            List<String> sorted = new ArrayList<>(merged);
            Collections.sort(sorted);
            return new CycleSnapshot(
                Collections.unmodifiableList(sorted),
                nonManagementBlockedScenes,
                Collections.unmodifiableList(management)
            );
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
        private final List<String> nonManagementBlockedScenes;
        private final List<String> managementPendingScenes;
        private final byte[] encodedCommand;

        private PreparedTarget(
            List<String> blockedScenes,
            List<String> nonManagementBlockedScenes,
            List<String> managementPendingScenes,
            byte[] encodedCommand
        ) {
            this.blockedScenes = blockedScenes;
            this.nonManagementBlockedScenes = nonManagementBlockedScenes;
            this.managementPendingScenes = managementPendingScenes;
            this.encodedCommand = encodedCommand;
        }
    }

    private final Object lock = new Object();
    private Target activeTarget;
    private List<String> lastSuccessfulBlockedScenes =
        Collections.emptyList();
    private List<String> lastSuccessfulNonManagementBlockedScenes =
        Collections.emptyList();
    private List<String> lastSuccessfulManagementPendingScenes =
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
            lastSuccessfulNonManagementBlockedScenes = Collections.emptyList();
            lastSuccessfulManagementPendingScenes = Collections.emptyList();
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
            return new CycleSnapshot(
                lastSuccessfulBlockedScenes,
                lastSuccessfulNonManagementBlockedScenes,
                lastSuccessfulManagementPendingScenes
            );
        }
    }

    public List<String> getLastSuccessfulBlockedScenes() {
        synchronized (lock) {
            return lastSuccessfulBlockedScenes;
        }
    }

    public List<String> getLastSuccessfulNonManagementBlockedScenes() {
        synchronized (lock) {
            return lastSuccessfulNonManagementBlockedScenes;
        }
    }

    public List<String> getLastSuccessfulManagementPendingScenes() {
        synchronized (lock) {
            return lastSuccessfulManagementPendingScenes;
        }
    }

    /** Publishes the complete policy produced by a successful full cycle. */
    public PublishResult publishCycleTarget(
        Target target,
        Collection<String> blockedScenes,
        PublishTransport transport
    ) throws Exception {
        return publishPrepared(
            target,
            prepare(blockedScenes, Collections.emptySet()),
            transport
        );
    }

    /** Publishes a full-sync base policy plus the current management overlay. */
    public PublishResult publishCycleTargetWithManagementOverlay(
        Target target,
        Collection<String> nonManagementBlockedScenes,
        Collection<String> managementPendingScenes,
        PublishTransport transport
    ) throws Exception {
        return publishPrepared(
            target,
            prepare(nonManagementBlockedScenes, managementPendingScenes),
            transport
        );
    }

    /** Refreshes only the management overlay while preserving the base set. */
    public PublishResult publishManagementOverlay(
        Target target,
        Collection<String> managementPendingScenes,
        PublishTransport transport
    ) throws Exception {
        final List<String> base;
        synchronized (lock) {
            requireCurrentLocked(target);
            base = lastSuccessfulNonManagementBlockedScenes;
        }
        return publishPrepared(
            target,
            prepare(base, managementPendingScenes),
            transport
        );
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
            prepare(
                snapshot.nonManagementBlockedScenes,
                snapshot.managementPendingScenes
            ),
            transport
        );
    }

    /**
     * Removes one resolved Scene from the cached base list and republishes it
     * with the caller's fresh management overlay.
     */
    public PublishResult removeAndPublish(
        Target target,
        String resolvedSceneName,
        Collection<String> managementPendingScenes,
        PublishTransport transport
    ) throws Exception {
        if (resolvedSceneName == null || resolvedSceneName.isEmpty()) {
            throw new IllegalArgumentException("resolved SceneName is required");
        }
        if (managementPendingScenes == null) {
            throw new IllegalArgumentException(
                "management pending Scenes are required"
            );
        }
        final List<String> nextBase;
        synchronized (lock) {
            requireCurrentLocked(target);
            nextBase = new ArrayList<>(lastSuccessfulNonManagementBlockedScenes);
        }
        nextBase.remove(resolvedSceneName);
        // The resolved Scene is removed only from the non-management base;
        // an active management hold remains in the caller-supplied overlay.
        return publishPrepared(
            target,
            prepare(nextBase, managementPendingScenes),
            transport
        );
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
                if (result != null
                    && result.errorCode == SceneSyncWireCodec.APPLY_DEFERRED) {
                    // APPLY_DEFERRED is an accepted durable receipt, not a
                    // formal game-policy commit.  Do not update the HET cache
                    // or report PUBLISHED; the caller must keep the policy
                    // claim retryable until the pool drains.
                    lastErrorCode = result.errorCode;
                    return new PublishResult(
                        Outcome.DEFERRED,
                        attempts,
                        lastErrorCode,
                        null,
                        prepared.blockedScenes
                    );
                }
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
                        lastSuccessfulNonManagementBlockedScenes =
                            prepared.nonManagementBlockedScenes;
                        lastSuccessfulManagementPendingScenes =
                            prepared.managementPendingScenes;
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
            // Native has already failed open on every rejected complete
            // replacement.  Drop the HET cache as well so a later refresh
            // cannot resurrect the old list or imply that the hold is still
            // active.  The same target bytes remain in this result for the
            // caller's bounded, externally-triggered retry decision.
            lastSuccessfulBlockedScenes = Collections.emptyList();
            lastSuccessfulNonManagementBlockedScenes =
                Collections.emptyList();
            lastSuccessfulManagementPendingScenes =
                Collections.emptyList();
        }
        return new PublishResult(
            Outcome.FAILED_OPEN,
            attempts,
            lastErrorCode,
            lastFailure,
            prepared.blockedScenes
        );
    }

    private static PreparedTarget prepare(
        Collection<String> nonManagementBlockedScenes,
        Collection<String> managementPendingScenes
    )
        throws SceneSyncWireCodec.ProtocolException {
        if (nonManagementBlockedScenes == null
            || managementPendingScenes == null) {
            throw new IllegalArgumentException(
                "blocked Scene collections cannot be null"
            );
        }
        List<String> base = new ArrayList<>(
            new HashSet<>(nonManagementBlockedScenes)
        );
        Collections.sort(base);
        List<String> management = new ArrayList<>(
            new HashSet<>(managementPendingScenes)
        );
        Collections.sort(management);
        Set<String> merged = new HashSet<>(base);
        merged.addAll(management);
        List<String> sorted = new ArrayList<>(merged);
        Collections.sort(sorted);
        List<String> immutableBase = Collections.unmodifiableList(base);
        List<String> immutableManagement = Collections.unmodifiableList(
            management
        );
        List<String> immutable = Collections.unmodifiableList(sorted);
        byte[] encoded = SceneSyncWireCodec.encodeReplaceBlockedScenes(
            immutable
        );
        return new PreparedTarget(
            immutable,
            immutableBase,
            immutableManagement,
            encoded
        );
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
            lastSuccessfulNonManagementBlockedScenes = Collections.emptyList();
            lastSuccessfulManagementPendingScenes = Collections.emptyList();
        }
    }
}
