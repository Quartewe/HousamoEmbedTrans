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

    /** Internal reason classification used by UI actions without parsing text. */
    public enum ReasonKind {
        OTHER,
        CONTEXT_LENGTH
    }

    private final Status status;
    private final HistoryPayload payload;
    private final String reason;
    private final ReasonKind reasonKind;

    private HistoryResolution(
        Status status,
        HistoryPayload payload,
        String reason,
        ReasonKind reasonKind
    ) {
        this.status = status;
        this.payload = payload;
        this.reason = reason;
        this.reasonKind = reasonKind == null ? ReasonKind.OTHER : reasonKind;
    }

    public static HistoryResolution ready(HistoryPayload payload) {
        return new HistoryResolution(
            Status.READY,
            payload == null ? HistoryPayload.empty() : payload,
            "",
            ReasonKind.OTHER
        );
    }

    public static HistoryResolution waiting(String reason) {
        return new HistoryResolution(
            Status.WAITING,
            null,
            reason,
            ReasonKind.OTHER
        );
    }

    public static HistoryResolution userActionRequired(String reason) {
        return userActionRequired(ReasonKind.OTHER, reason);
    }

    public static HistoryResolution userActionRequired(
        ReasonKind reasonKind,
        String reason
    ) {
        return new HistoryResolution(
            Status.USER_ACTION_REQUIRED,
            null,
            reason,
            reasonKind
        );
    }

    public static HistoryResolution contextLengthExceeded(String reason) {
        return userActionRequired(ReasonKind.CONTEXT_LENGTH, reason);
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

    public ReasonKind getReasonKind() {
        return reasonKind;
    }

    public boolean isReady() {
        return status == Status.READY;
    }
}
