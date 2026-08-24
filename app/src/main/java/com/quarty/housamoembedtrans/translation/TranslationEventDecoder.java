package com.quarty.housamoembedtrans.translation;

import org.json.JSONObject;

import java.math.BigDecimal;

/**
 * Decodes model-generated NDJSON after provider SSE framing has been removed.
 */
public final class TranslationEventDecoder {
    public interface Listener {
        void onSummary(
            String summary,
            String contextSummary,
            String invalidContextSummary
        ) throws Exception;

        void onTranslation(int seq, String text) throws Exception;

        void onComplete() throws Exception;
    }

    public static final class ProtocolException extends Exception {
        public ProtocolException(String message) {
            super(message);
        }

        public ProtocolException(String message, Throwable cause) {
            super(message, cause);
        }
    }

    private final boolean repair;
    private final boolean requireContextSummary;
    private final Listener listener;
    private final StringBuilder pending = new StringBuilder();
    private boolean summaryReceived;
    private boolean completeReceived;
    private int lastSeq;

    public TranslationEventDecoder(
        boolean repair,
        boolean requireContextSummary,
        Listener listener
    ) {
        if (listener == null) {
            throw new IllegalArgumentException("listener cannot be null");
        }
        this.repair = repair;
        this.requireContextSummary = requireContextSummary;
        this.listener = listener;
    }

    public void accept(String delta) throws Exception {
        if (delta == null || delta.isEmpty()) {
            return;
        }
        pending.append(delta);
        int newline;
        while ((newline = indexOfNewline(pending)) >= 0) {
            String line = pending.substring(0, newline);
            int removeLength = newline + 1;
            if (newline > 0 && pending.charAt(newline - 1) == '\r') {
                line = pending.substring(0, newline - 1);
            }
            pending.delete(0, removeLength);
            consumeLine(line);
        }
    }

    public void finish() throws Exception {
        String remaining = pending.toString().trim();
        pending.setLength(0);
        if (!remaining.isEmpty()) {
            consumeLine(remaining);
        }
        if (!repair && !summaryReceived) {
            throw new ProtocolException(
                "main translation stream did not provide summary first"
            );
        }
        if (!completeReceived) {
            throw new ProtocolException(
                "translation stream ended without complete event"
            );
        }
    }

    private void consumeLine(String rawLine) throws Exception {
        String line = rawLine.trim();
        if (line.isEmpty()) {
            return;
        }
        if (completeReceived) {
            throw new ProtocolException(
                "content appeared after complete event"
            );
        }

        final JSONObject event;
        try {
            event = new JSONObject(line);
        } catch (Exception e) {
            throw new ProtocolException(
                "invalid NDJSON event: " + truncate(line, 512),
                e
            );
        }

        Object typeValue = event.opt("type");
        if (!(typeValue instanceof String)) {
            throw new ProtocolException("event type must be a string");
        }
        String type = (String) typeValue;
        switch (type) {
            case "summary":
                consumeSummary(event);
                break;
            case "translation":
                consumeTranslation(event);
                break;
            case "complete":
                consumeComplete(event);
                break;
            default:
                throw new ProtocolException(
                    "unsupported translation event type: " + type
                );
        }
    }

    private void consumeSummary(JSONObject event) throws Exception {
        if (repair) {
            throw new ProtocolException(
                "repair stream must not emit a summary event"
            );
        }
        if (summaryReceived || lastSeq != 0) {
            throw new ProtocolException(
                "summary must appear exactly once before translations"
            );
        }
        if (!event.has("type") || !event.has("summary")) {
            throw new ProtocolException(
                "summary event fields must include type and summary"
            );
        }
        if (requireContextSummary) {
            java.util.Iterator<String> keys = event.keys();
            while (keys.hasNext()) {
                String key = keys.next();
                if (!"type".equals(key)
                    && !"summary".equals(key)
                    && !"context_summary".equals(key)) {
                    throw new ProtocolException(
                        "summary event contains unexpected field: " + key
                    );
                }
            }
        } else {
            if (event.length() != 2 || !event.has("summary")) {
                throw new ProtocolException(
                    "summary event fields must be exactly [type, summary]"
                );
            }
        }

        Object summaryValue = event.opt("summary");
        if (!(summaryValue instanceof String)) {
            throw new ProtocolException(
                "translation summary must be a string"
            );
        }
        String summary = (String) summaryValue;
        if (summary.trim().isEmpty()) {
            throw new ProtocolException("translation summary is empty");
        }

        Object contextValue = event.opt("context_summary");
        String contextSummary = null;
        String invalidContextSummary = null;
        if (requireContextSummary) {
            if (contextValue == null || contextValue == JSONObject.NULL) {
                // Missing context_summary is a degradation, not a protocol
                // failure: the body translation must still be accepted.
                contextSummary = null;
            } else if (contextValue instanceof String
                && !((String) contextValue).trim().isEmpty()) {
                contextSummary = (String) contextValue;
            } else {
                invalidContextSummary = String.valueOf(contextValue);
            }
        } else if (contextValue != null
            && contextValue != JSONObject.NULL) {
            throw new ProtocolException(
                "context_summary must be omitted when it was not requested"
            );
        }
        summaryReceived = true;
        listener.onSummary(summary, contextSummary, invalidContextSummary);
    }

    private void consumeTranslation(JSONObject event) throws Exception {
        if (!repair && !summaryReceived) {
            throw new ProtocolException(
                "translation appeared before summary"
            );
        }
        if (event.length() != 3
            || !event.has("type")
            || !event.has("seq")
            || !event.has("text")) {
            throw new ProtocolException(
                "translation event fields must be exactly "
                    + "[type, seq, text]"
            );
        }
        Object seqValue = event.opt("seq");
        if (!(seqValue instanceof Number)) {
            throw new ProtocolException("translation seq must be an integer");
        }
        long exactSeq;
        try {
            exactSeq = new BigDecimal(seqValue.toString()).longValueExact();
        } catch (ArithmeticException | NumberFormatException e) {
            throw new ProtocolException(
                "translation seq must be a positive integer",
                e
            );
        }
        if (exactSeq < 1 || exactSeq > Integer.MAX_VALUE) {
            throw new ProtocolException(
                "translation seq must be a positive integer"
            );
        }
        int seq = (int) exactSeq;
        if (seq <= lastSeq) {
            throw new ProtocolException(
                "translation seqs must be strictly increasing; previous="
                    + lastSeq
                    + " actual="
                    + seq
            );
        }
        Object textValue = event.opt("text");
        if (!(textValue instanceof String)) {
            throw new ProtocolException(
                "translation text must be a string"
            );
        }
        lastSeq = seq;
        listener.onTranslation(seq, (String) textValue);
    }

    private void consumeComplete(JSONObject event) throws Exception {
        if (!repair && !summaryReceived) {
            throw new ProtocolException(
                "complete appeared before summary"
            );
        }
        if (event.length() != 1 || !event.has("type")) {
            throw new ProtocolException(
                "complete event must contain only type"
            );
        }
        completeReceived = true;
        listener.onComplete();
    }

    private static int indexOfNewline(StringBuilder builder) {
        for (int index = 0; index < builder.length(); index++) {
            if (builder.charAt(index) == '\n') {
                return index;
            }
        }
        return -1;
    }

    private static String truncate(String value, int maxLength) {
        return value.length() <= maxLength
            ? value
            : value.substring(0, maxLength);
    }
}
