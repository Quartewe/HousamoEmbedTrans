package com.quarty.housamoembedtrans.translation.delivery;

/**
 * Stable vocabulary shared by the durable job store, Binder service and game
 * callback receiver.  Execution status and delivery state intentionally live
 * in separate fields; a terminal job can therefore remain pending until the
 * game has acknowledged the outcome.
 */
public final class TerminalOutcome {
    private TerminalOutcome() {
        throw new AssertionError("No instances");
    }

    public enum Kind {
        COMPLETED("completed"),
        FAILED("failed");

        private final String wireValue;

        Kind(String wireValue) {
            this.wireValue = wireValue;
        }

        public String wireValue() {
            return wireValue;
        }

        public static Kind fromWireValue(String value) {
            for (Kind kind : values()) {
                if (kind.wireValue.equals(value)) {
                    return kind;
                }
            }
            return null;
        }
    }

    public enum DeliveryState {
        PENDING("pending"),
        ACKNOWLEDGED("acknowledged"),
        NOT_REQUIRED("not_required");

        private final String wireValue;

        DeliveryState(String wireValue) {
            this.wireValue = wireValue;
        }

        public String wireValue() {
            return wireValue;
        }

        public static DeliveryState fromWireValue(String value) {
            for (DeliveryState state : values()) {
                if (state.wireValue.equals(value)) {
                    return state;
                }
            }
            return null;
        }
    }

    /** Immutable identity used by callback preflight and ACK. */
    public static final class Identity {
        public final String requestId;
        public final Kind kind;

        public Identity(String requestId, Kind kind) {
            if (requestId == null || requestId.isEmpty() || kind == null) {
                throw new IllegalArgumentException(
                    "requestId and terminal kind are required"
                );
            }
            this.requestId = requestId;
            this.kind = kind;
        }

        @Override
        public boolean equals(Object other) {
            if (!(other instanceof Identity)) {
                return false;
            }
            Identity value = (Identity) other;
            return requestId.equals(value.requestId) && kind == value.kind;
        }

        @Override
        public int hashCode() {
            return 31 * requestId.hashCode() + kind.hashCode();
        }
    }
}
