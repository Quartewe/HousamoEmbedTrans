package com.quarty.housamoembedtrans.translation;

import com.quarty.housamoembedtrans.util.JobValidator;

import org.json.JSONArray;
import org.json.JSONObject;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/** Performs item-level validation and separates final validity from display safety. */
public final class TranslationResultValidator {
    public enum Disposition {
        FINAL_VALID,
        DISPLAYABLE_FORMAT_ERROR,
        BLOCKED
    }

    public enum ErrorCode {
        NONE,
        LINE_BREAK_MISMATCH,
        ESCAPE_SEQUENCE_MISMATCH,
        PROTECTED_LABEL_MISMATCH,
        EMPTY_TEXT,
        MISSING_SEQ,
        UNKNOWN_SEQ,
        DUPLICATE_SEQ,
        INVALID_ENTRY,
        PROTOCOL_ERROR
    }

    public static final class Result {
        private final int seq;
        private final String text;
        private final Disposition disposition;
        private final List<ErrorCode> errorCodes;
        private final String reason;

        private Result(
            int seq,
            String text,
            Disposition disposition,
            List<ErrorCode> errorCodes,
            String reason
        ) {
            this.seq = seq;
            this.text = text;
            this.disposition = disposition;
            this.errorCodes = Collections.unmodifiableList(
                new ArrayList<>(errorCodes)
            );
            this.reason = reason;
        }

        public int getSeq() {
            return seq;
        }

        public String getText() {
            return text;
        }

        public Disposition getDisposition() {
            return disposition;
        }

        public List<ErrorCode> getErrorCodes() {
            return errorCodes;
        }

        public String getReason() {
            return reason;
        }

        public boolean isFinalValid() {
            return disposition == Disposition.FINAL_VALID;
        }

        public boolean isDisplayable() {
            return disposition != Disposition.BLOCKED;
        }
    }

    private static final class LineBreakProfile {
        private final int leading;
        private final List<Integer> internalRuns;
        private final int trailing;

        private LineBreakProfile(
            int leading,
            List<Integer> internalRuns,
            int trailing
        ) {
            this.leading = leading;
            this.internalRuns = internalRuns;
            this.trailing = trailing;
        }

        private boolean sameAs(LineBreakProfile other) {
            return other != null
                && leading == other.leading
                && trailing == other.trailing
                && internalRuns.equals(other.internalRuns);
        }

        private JSONObject toJson() throws Exception {
            JSONArray runs = new JSONArray();
            for (Integer run : internalRuns) {
                runs.put(run);
            }
            return new JSONObject()
                .put("leading", leading)
                .put("internal_runs", runs)
                .put("trailing", trailing);
        }

        @Override
        public String toString() {
            return "{leading="
                + leading
                + ", internal_runs="
                + internalRuns
                + ", trailing="
                + trailing
                + "}";
        }
    }

    private final String expectedTargetLanguage;
    private final Map<Integer, String> sourceTexts;
    private final Set<String> protectedLabels;

    public TranslationResultValidator(
        JSONObject request,
        JobValidator.RequestInfo requestInfo
    ) {
        if (request == null || requestInfo == null) {
            throw new IllegalArgumentException(
                "request and requestInfo cannot be null"
            );
        }

        Map<Integer, String> texts = new LinkedHashMap<>();
        List<Integer> seqs = new ArrayList<>(
            requestInfo.getTextsBySeq().keySet()
        );
        Collections.sort(seqs);
        for (Integer seq : seqs) {
            texts.put(
                seq,
                requestInfo.getTextsBySeq().get(seq).getText()
            );
        }

        expectedTargetLanguage = requestInfo.getTargetLanguage();
        sourceTexts = Collections.unmodifiableMap(texts);
        protectedLabels = Collections.unmodifiableSet(
            collectProtectedLabels(request)
        );
    }

    public Map<Integer, String> getSourceTexts() {
        return sourceTexts;
    }

    public List<Integer> getExpectedSeqs() {
        return new ArrayList<>(sourceTexts.keySet());
    }

    void validateFinalResult(JSONObject result) throws JobValidator.ValidationException {
        if (result == null) {
            throw new JobValidator.ValidationException(
                "result cannot be null",
                null
            );
        }

        if (!expectedTargetLanguage.equals(result.optString("target_lang", ""))) {
            throw new JobValidator.ValidationException(
                "target_lang does not match request; expected="
                    + expectedTargetLanguage
                    + " actual="
                    + result.optString("target_lang", ""),
                null
            );
        }

        Object summaryValue = result.opt("summary");
        if (!(summaryValue instanceof String)
            || ((String) summaryValue).trim().isEmpty()) {
            throw new JobValidator.ValidationException(
                "result.summary must be a non-empty string",
                null
            );
        }

        // context_summary is an independent degradation path. Missing or
        // invalid context_summary must never reject an otherwise valid body
        // translation, so it is intentionally not part of terminal validation.
        // If present it must still be a non-empty string to be persisted by
        // buildFinalResult(); invalid values are archived by the streaming
        // coordinator and omitted from the terminal result.

        Object translationsValue = result.opt("translations");
        if (!(translationsValue instanceof JSONArray)) {
            throw new JobValidator.ValidationException(
                "result.translations must be an array",
                null
            );
        }
        JSONArray translations = (JSONArray) translationsValue;
        if (translations.length() != sourceTexts.size()) {
            throw new JobValidator.ValidationException(
                "translations array has incorrect length",
                null
            );
        }
        Set<Integer> seen = new HashSet<>();
        for (int index = 0; index < translations.length(); index++) {
            Object translationValue = translations.opt(index);
            if (!(translationValue instanceof JSONObject)) {
                throw new JobValidator.ValidationException(
                    "result.translations[" + index + "] must be an object",
                    null
                );
            }
            JSONObject translation = (JSONObject) translationValue;

            Object seqValue = translation.opt("seq");
            if (!(seqValue instanceof Number)) {
                throw new JobValidator.ValidationException(
                    "result.translations[" + index + "].seq must be an integer",
                    null
                );
            }
            long exactSeq;
            try {
                exactSeq = new BigDecimal(seqValue.toString()).longValueExact();
            } catch (ArithmeticException | NumberFormatException e) {
                throw new JobValidator.ValidationException(
                    "result.translations[" + index + "].seq must be a positive integer",
                    e
                );
            }
            if (exactSeq < 1 || exactSeq > Integer.MAX_VALUE) {
                throw new JobValidator.ValidationException(
                    "result.translations[" + index + "].seq must be a positive integer",
                    null
                );
            }
            int seq = (int) exactSeq;
            if (!seen.add(seq)) {
                throw new JobValidator.ValidationException(
                    "result.translations contains duplicate seq=" + seq,
                    null
                );
            }

            Object textValue = translation.opt("text");
            if (!(textValue instanceof String)) {
                throw new JobValidator.ValidationException(
                    "result.translations[" + index + "].text must be a string",
                    null
                );
            }
            Result validation = validate(seq, (String) textValue);
            if (!validation.isFinalValid()) {
                throw new JobValidator.ValidationException(
                    "translation validation failed for seq="
                        + seq
                        + " reason="
                        + validation.getReason(),
                    null
                );
            }
        }

        if (seen.size() != sourceTexts.size()) {
            throw new JobValidator.ValidationException(
                "result.translations is missing some seqs",
                null
            );
        }
    }

    public Result validate(int seq, String translatedText) {
        String source = sourceTexts.get(seq);
        if (source == null) {
            return blocked(
                seq,
                translatedText,
                ErrorCode.UNKNOWN_SEQ,
                "seq was not requested"
            );
        }
        if (translatedText == null || translatedText.trim().isEmpty()) {
            return blocked(
                seq,
                translatedText,
                ErrorCode.EMPTY_TEXT,
                "translated text is empty"
            );
        }

        List<ErrorCode> hardCodes = new ArrayList<>();
        List<String> hardReasons = new ArrayList<>();
        for (String label : protectedLabels) {
            int expected = countOccurrences(source, label);
            int actual = countOccurrences(translatedText, label);
            if (expected != actual) {
                hardCodes.add(ErrorCode.PROTECTED_LABEL_MISMATCH);
                hardReasons.add(
                    "protected label count changed; label="
                        + label
                        + " expected_count="
                        + expected
                        + " actual_count="
                        + actual
                );
            }
        }
        if (!hardCodes.isEmpty()) {
            return new Result(
                seq,
                translatedText,
                Disposition.BLOCKED,
                hardCodes,
                String.join("; ", hardReasons)
            );
        }

        List<ErrorCode> softCodes = new ArrayList<>();
        List<String> softReasons = new ArrayList<>();
        LineBreakProfile expectedLineBreaks = lineBreakProfile(source);
        LineBreakProfile actualLineBreaks = lineBreakProfile(translatedText);
        if (!expectedLineBreaks.sameAs(actualLineBreaks)) {
            softCodes.add(ErrorCode.LINE_BREAK_MISMATCH);
            softReasons.add(
                "line break structure changed; expected="
                    + expectedLineBreaks
                    + " actual="
                    + actualLineBreaks
            );
        }

        Map<String, Integer> expectedEscapes = escapedSequenceCounts(source);
        Map<String, Integer> actualEscapes =
            escapedSequenceCounts(translatedText);
        if (!expectedEscapes.equals(actualEscapes)) {
            softCodes.add(ErrorCode.ESCAPE_SEQUENCE_MISMATCH);
            softReasons.add(
                "escape sequences changed; expected="
                    + expectedEscapes
                    + " actual="
                    + actualEscapes
            );
        }
        if (!softCodes.isEmpty()) {
            return new Result(
                seq,
                translatedText,
                Disposition.DISPLAYABLE_FORMAT_ERROR,
                softCodes,
                String.join("; ", softReasons)
            );
        }

        return new Result(
            seq,
            translatedText,
            Disposition.FINAL_VALID,
            Collections.singletonList(ErrorCode.NONE),
            ""
        );
    }

    public Result missing(int seq) {
        return blocked(
            seq,
            null,
            ErrorCode.MISSING_SEQ,
            "translation is missing from the response"
        );
    }

    public Result protocolFailure(int seq, String reason) {
        return blocked(
            seq,
            null,
            ErrorCode.PROTOCOL_ERROR,
            reason == null ? "translation protocol failed" : reason
        );
    }

    public JSONObject buildFeedback(Result result) throws Exception {
        String source = sourceTexts.get(result.getSeq());
        if (source == null) {
            throw new IllegalArgumentException(
                "feedback seq is not part of the request: "
                    + result.getSeq()
            );
        }

        JSONArray codes = new JSONArray();
        for (ErrorCode code : result.getErrorCodes()) {
            if (code != ErrorCode.NONE) {
                codes.put(code.name().toLowerCase());
            }
        }
        JSONObject feedback = new JSONObject()
            .put("seq", result.getSeq())
            .put("error_codes", codes)
            .put("reason", result.getReason())
            .put(
                "required_line_breaks",
                lineBreakProfile(source).toJson()
            )
            .put(
                "required_escape_sequences",
                countMapToJson(escapedSequenceCounts(source))
            );

        Map<String, Integer> protectedCounts = new TreeMap<>();
        for (String label : protectedLabels) {
            int count = countOccurrences(source, label);
            if (count > 0) {
                protectedCounts.put(label, count);
            }
        }
        if (!protectedCounts.isEmpty()) {
            feedback.put(
                "required_protected_labels",
                countMapToJson(protectedCounts)
            );
        }
        return feedback;
    }

    private static Result blocked(
        int seq,
        String text,
        ErrorCode code,
        String reason
    ) {
        return new Result(
            seq,
            text,
            Disposition.BLOCKED,
            Collections.singletonList(code),
            reason
        );
    }

    private static Set<String> collectProtectedLabels(JSONObject request) {
        LinkedHashSet<String> labels = new LinkedHashSet<>();
        JSONArray protect = request.optJSONArray("protect");
        if (protect == null) {
            return labels;
        }
        for (int index = 0; index < protect.length(); index++) {
            JSONObject token = protect.optJSONObject(index);
            if (token == null) {
                continue;
            }
            String label = token.optString("label", "");
            if (!label.isEmpty()) {
                labels.add(label);
            }
        }
        return labels;
    }

    private static LineBreakProfile lineBreakProfile(String text) {
        String normalized = text == null
            ? ""
            : text.replace("\r\n", "\n").replace("\r", "\n");

        int leading = 0;
        while (leading < normalized.length()
            && normalized.charAt(leading) == '\n') {
            leading++;
        }

        int trailingStart = normalized.length();
        while (trailingStart > leading
            && normalized.charAt(trailingStart - 1) == '\n') {
            trailingStart--;
        }
        int trailing = normalized.length() - trailingStart;

        List<Integer> runs = new ArrayList<>();
        int runLength = 0;
        for (int index = leading; index < trailingStart; index++) {
            if (normalized.charAt(index) == '\n') {
                runLength++;
            } else if (runLength > 0) {
                runs.add(runLength);
                runLength = 0;
            }
        }
        if (runLength > 0) {
            runs.add(runLength);
        }
        return new LineBreakProfile(leading, runs, trailing);
    }

    private static Map<String, Integer> escapedSequenceCounts(String text) {
        Map<String, Integer> counts = new TreeMap<>();
        if (text == null) {
            return counts;
        }
        for (int index = 0; index < text.length(); index++) {
            if (text.charAt(index) != '\\') {
                continue;
            }
            int end = Math.min(index + 2, text.length());
            if (index + 5 < text.length()
                && text.charAt(index + 1) == 'u'
                && isFourDigitHex(text, index + 2)) {
                end = index + 6;
            }
            String sequence = text.substring(index, end);
            counts.put(sequence, counts.getOrDefault(sequence, 0) + 1);
            index = end - 1;
        }
        return counts;
    }

    private static boolean isFourDigitHex(String text, int start) {
        if (start < 0 || start + 4 > text.length()) {
            return false;
        }
        for (int index = start; index < start + 4; index++) {
            char value = text.charAt(index);
            if (!((value >= '0' && value <= '9')
                || (value >= 'a' && value <= 'f')
                || (value >= 'A' && value <= 'F'))) {
                return false;
            }
        }
        return true;
    }

    private static int countOccurrences(String text, String token) {
        if (text == null || token == null || token.isEmpty()) {
            return 0;
        }
        int count = 0;
        int offset = 0;
        while ((offset = text.indexOf(token, offset)) >= 0) {
            count++;
            offset += token.length();
        }
        return count;
    }

    private static JSONObject countMapToJson(
        Map<String, Integer> values
    ) throws Exception {
        JSONObject json = new JSONObject();
        for (Map.Entry<String, Integer> entry : values.entrySet()) {
            json.put(entry.getKey(), entry.getValue());
        }
        return json;
    }
}
