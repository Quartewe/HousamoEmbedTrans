package com.quarty.housamoembedtrans.translation.request;

import org.json.JSONObject;

import java.math.BigDecimal;

/**
 * Decodes a stream of JSON event objects after provider SSE framing is removed.
 * Both NDJSON and objects formatted across multiple lines are accepted.
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
    private int objectDepth;
    private boolean inString;
    private boolean escaped;
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
        for (int index = 0; index < delta.length(); index++) {
            char value = delta.charAt(index);
            if (pending.length() == 0) {
                if (value == ' ' || value == '\t' || value == '\r' || value == '\n') {
                    continue;
                }
                if (completeReceived) {
                    throw new ProtocolException("content appeared after complete event");
                }
                if (value != '{') {
                    throw new ProtocolException("translation event must start with a JSON object");
                }
            }
            pending.append(value);
            if (inString) {
                if (value < 0x20) {
                    throw new ProtocolException("unescaped control character in JSON string");
                }
                if (escaped) {
                    escaped = false;
                } else if (value == '\\') {
                    escaped = true;
                } else if (value == '"') {
                    inString = false;
                }
            } else if (value == '"') {
                inString = true;
            } else if (value == '{') {
                objectDepth++;
            } else if (value == '}') {
                objectDepth--;
                if (objectDepth == 0) {
                    String event = pending.toString();
                    pending.setLength(0);
                    consumeEvent(event);
                }
            }
        }
    }

    public void finish() throws Exception {
        if (pending.length() != 0) {
            throw new ProtocolException("translation stream ended with an incomplete JSON event");
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

    private void consumeEvent(String json) throws Exception {
        if (completeReceived) {
            throw new ProtocolException(
                "content appeared after complete event"
            );
        }

        final JSONObject event;
        try {
            event = new JSONObject(json);
        } catch (Exception e) {
            throw new ProtocolException(
                "invalid JSON event: " + truncate(json, 512),
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

    private static String truncate(String value, int maxLength) {
        return value.length() <= maxLength
            ? value
            : value.substring(0, maxLength);
    }
}
