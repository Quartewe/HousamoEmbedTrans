package com.quarty.housamoembedtrans.translation;

/**
 * Process-local gate for the optional Context/Group Review startup stage.
 *
 * <p>The gate is intentionally small: it only waits until the user chooses
 * {@code save} or {@code skip}. It does not persist anything itself. The
 * StartupCoordinator calls {@link #awaitDecision()} only when the gate is
 * enabled; the UI calls {@link #complete(boolean)} when the decision is made.</p>
 */
public final class ContextReviewGate {

    private static final ContextReviewGate INSTANCE = new ContextReviewGate();

    private boolean enabled;
    private boolean decided;
    private boolean saveRequested;

    private ContextReviewGate() {
    }

    public static ContextReviewGate get() {
        return INSTANCE;
    }

    /** Prepares one startup cycle. Disabled reviews never block. */
    public synchronized void prepare(boolean enabled) {
        this.enabled = enabled;
        this.decided = false;
        this.saveRequested = false;
        notifyAll();
    }

    public synchronized boolean isEnabled() {
        return enabled;
    }

    public synchronized boolean isPending() {
        return enabled && !decided;
    }

    public synchronized void awaitDecision() throws InterruptedException {
        while (!decided) {
            wait();
        }
    }

    /** Releases the startup gate. {@code saveRequested} is informational. */
    public synchronized void complete(boolean saveRequested) {
        this.saveRequested = saveRequested;
        this.decided = true;
        notifyAll();
    }

    public synchronized boolean wasSaveRequested() {
        return saveRequested;
    }
}
