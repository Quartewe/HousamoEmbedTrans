package com.quarty.housamoembedtrans.util;

/**
 * The durable execution-status vocabulary for a Translation Job.
 *
 * <p>The enum keeps the persisted JSON spelling in one place.  Callers that
 * read or write {@code state.json} must use {@link #wireValue()} rather than
 * repeating the wire string locally.  Delivery state remains a separate
 * dimension and is intentionally not part of this type.
 */
public enum TranslationJobStatus {
    QUEUED("queued", false),
    RUNNING("running", false),
    RESETTING("resetting", false),
    CANCELED("canceled", true),
    COMPLETED("completed", true),
    FAILED("failed", true),
    DAMAGED("damaged", true);

    private final String wireValue;
    private final boolean terminal;

    TranslationJobStatus(String wireValue, boolean terminal) {
        this.wireValue = wireValue;
        this.terminal = terminal;
    }

    /** Returns the exact value persisted in {@code state.json}. */
    public String wireValue() {
        return wireValue;
    }

    /** Returns whether this status represents a settled job state. */
    public boolean isTerminal() {
        return terminal;
    }

    /** Resolves a persisted status, or {@code null} for an unknown value. */
    public static TranslationJobStatus fromWireValue(String value) {
        if (value == null) {
            return null;
        }
        for (TranslationJobStatus status : values()) {
            if (status.wireValue.equals(value)) {
                return status;
            }
        }
        return null;
    }

    /** Returns whether {@code value} is one of the supported persisted values. */
    public static boolean isValid(String value) {
        return fromWireValue(value) != null;
    }

    /** Returns whether {@code value} is a settled Translation Job status. */
    public static boolean isTerminal(String value) {
        TranslationJobStatus status = fromWireValue(value);
        return status != null && status.isTerminal();
    }
}
