package com.quarty.housamoembedtrans.context.history;

/**
 * Immutable History Resolution returned by {@link HistoryResolver}.
 *
 * <p>{@code READY} carries the assembled History Payload. {@code WAITING} and
 * {@code USER_ACTION_REQUIRED} never send a provider request and never become
 * a Translation terminal failure.</p>
 */
public final class HistoryResolution {

    public enum Status {
        READY,
        WAITING,
        USER_ACTION_REQUIRED
    }

    private final Status status;
    private final HistoryPayload payload;
    private final String reason;

    private HistoryResolution(
        Status status,
        HistoryPayload payload,
        String reason
    ) {
        this.status = status;
        this.payload = payload;
        this.reason = reason;
    }

    public static HistoryResolution ready(HistoryPayload payload) {
        return new HistoryResolution(
            Status.READY,
            payload == null ? HistoryPayload.empty() : payload,
            ""
        );
    }

    public static HistoryResolution waiting(String reason) {
        return new HistoryResolution(Status.WAITING, null, reason);
    }

    public static HistoryResolution userActionRequired(String reason) {
        return new HistoryResolution(
            Status.USER_ACTION_REQUIRED,
            null,
            reason
        );
    }

    public Status getStatus() {
        return status;
    }

    public HistoryPayload getPayload() {
        if (status != Status.READY) {
            throw new IllegalStateException(
                "History payload is only available for READY resolutions"
            );
        }
        return payload;
    }

    public String getReason() {
        return reason;
    }

    public boolean isReady() {
        return status == Status.READY;
    }
}
