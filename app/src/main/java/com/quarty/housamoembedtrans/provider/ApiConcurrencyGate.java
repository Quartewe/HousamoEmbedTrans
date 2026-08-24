package com.quarty.housamoembedtrans.provider;

/**
 * Shared API concurrency gate for Translation and Summary requests.
 *
 * <p>The gate implements the spec rule: when the global limit is greater than
 * one, at least one Translation channel is dedicated and can never be used by
 * Summary; the remaining shared channels are offered to waiting Summary
 * requests first. When the limit is one, the single channel is shared but
 * Summary wins, giving the startup order Summary → Translation serially.</p>
 */
public final class ApiConcurrencyGate {

    /**
     * One-shot token owned by the gate that created it.
     *
     * <p>The owner and channel are immutable; {@code released} is guarded by
     * the owner's lock and prevents a token from returning capacity twice.</p>
     */
    public static final class Permit {
        private enum Channel {
            DEDICATED,
            SHARED
        }

        private final ApiConcurrencyGate owner;
        private final Channel channel;
        private boolean released;

        private Permit(ApiConcurrencyGate owner, Channel channel) {
            this.owner = owner;
            this.channel = channel;
        }
    }

    private final int globalLimit;
    private final int dedicatedPermits;
    private final int sharedPermits;
    private final Object lock = new Object();
    private int dedicatedAvailable;
    private int sharedAvailable;
    private int waitingSummaries;
    private boolean closed;

    public ApiConcurrencyGate(int globalLimit) {
        if (globalLimit < 1) {
            throw new IllegalArgumentException("globalLimit must be positive");
        }
        this.globalLimit = globalLimit;
        this.dedicatedPermits = globalLimit > 1 ? 1 : 0;
        this.sharedPermits = Math.max(1, globalLimit - dedicatedPermits);
        this.dedicatedAvailable = dedicatedPermits;
        this.sharedAvailable = sharedPermits;
    }

    public int getGlobalLimit() {
        return globalLimit;
    }

    /** Acquires a Summary permit; Summary always uses a shared channel. */
    public Permit acquireSummary() throws InterruptedException {
        synchronized (lock) {
            if (closed) {
                throw new IllegalStateException("API concurrency gate is closed");
            }
            waitingSummaries++;
            try {
                while (sharedAvailable == 0) {
                    lock.wait();
                    if (closed) {
                        throw new IllegalStateException(
                            "API concurrency gate is closed"
                        );
                    }
                }
                sharedAvailable--;
                return new Permit(this, Permit.Channel.SHARED);
            } finally {
                waitingSummaries--;
                lock.notifyAll();
            }
        }
    }

    /**
     * Acquires a Translation permit. A dedicated channel is used when
     * available; otherwise a shared channel is used only when no Summary is
     * waiting, preserving Summary priority.
     */
    public Permit acquireTranslation() throws InterruptedException {
        synchronized (lock) {
            if (closed) {
                throw new IllegalStateException("API concurrency gate is closed");
            }
            while (true) {
                if (dedicatedAvailable > 0) {
                    dedicatedAvailable--;
                    return new Permit(this, Permit.Channel.DEDICATED);
                }
                if (sharedAvailable > 0 && waitingSummaries == 0) {
                    sharedAvailable--;
                    return new Permit(this, Permit.Channel.SHARED);
                }
                lock.wait();
                if (closed) {
                    throw new IllegalStateException(
                        "API concurrency gate is closed"
                    );
                }
            }
        }
    }

    /**
     * Releases a permit acquired from this gate.
     *
     * <p>A permit from another gate, or a permit that has already been
     * released, is rejected without changing capacity.</p>
     *
     * @return {@code true} when this call returned capacity to the gate
     */
    public boolean release(Permit permit) {
        if (permit == null) {
            throw new IllegalArgumentException("permit cannot be null");
        }
        if (permit.owner != this) {
            return false;
        }
        synchronized (lock) {
            if (permit.released) {
                return false;
            }
            if (permit.channel == Permit.Channel.DEDICATED) {
                if (dedicatedAvailable >= dedicatedPermits) {
                    return false;
                }
                dedicatedAvailable++;
            } else {
                if (sharedAvailable >= sharedPermits) {
                    return false;
                }
                sharedAvailable++;
            }
            permit.released = true;
            lock.notifyAll();
            return true;
        }
    }

    /** For diagnostics/tests: whether any Summary request is waiting. */
    public boolean hasWaitingSummaries() {
        synchronized (lock) {
            return waitingSummaries > 0;
        }
    }

    public void close() {
        synchronized (lock) {
            closed = true;
            lock.notifyAll();
        }
    }
}
