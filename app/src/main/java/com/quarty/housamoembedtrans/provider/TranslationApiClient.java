package com.quarty.housamoembedtrans.provider;

import com.quarty.housamoembedtrans.util.IoUtils;

import android.util.Log;
import android.os.SystemClock;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.SocketException;
import java.net.SocketTimeoutException;
import java.net.URI;
import java.net.UnknownHostException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/** Provider transport. Translation semantics and validation live in the executor. */
public final class TranslationApiClient {
    private static final String TAG = "HET.TranslationApi";
    private static final String ANTHROPIC_EXTENDED_THINKING_BETA =
        "extended-thinking-2025-02-19";
    private static final int CONNECT_TIMEOUT_MS = 30_000;
    private static final int MODEL_READ_TIMEOUT_MS = 60_000;
    private static final int TRANSLATION_READ_TIMEOUT_MS = 300_000;
    private static final int MAX_MODEL_RESPONSE_BYTES = 4 * 1024 * 1024;
    private static final int MAX_ERROR_RESPONSE_BYTES = 4 * 1024 * 1024;
    private static final long RETRY_BASE_DELAY_MS = 1_000L;
    private static final long RETRY_MAX_DELAY_MS = 8_000L;
    private static final long WAIT_LOG_INTERVAL_SECONDS = 30L;
    private static final ScheduledExecutorService WAIT_LOGGER =
        Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread thread = new Thread(
                runnable,
                "HET-api-wait-logger"
            );
            thread.setDaemon(true);
            return thread;
        });

    /**
     * Injectable provider transport boundary. Production uses the real
     * HttpURLConnection implementation; host JUnit tests substitute a fake so
     * the full request/retry/write-back chains can run without Android
     * runtime or network access.
     */
    interface ProviderTransport {
        void streamTranslationAttempt(
            TranslationConfig config,
            String body,
            StreamListener listener,
            int attemptNumber
        ) throws Exception;

        JSONObject sendSummaryRequest(
            TranslationConfig config,
            String body
        ) throws Exception;
    }

    private static final ProviderTransport REAL_TRANSPORT = new ProviderTransport() {
        @Override
        public void streamTranslationAttempt(
            TranslationConfig config,
            String body,
            StreamListener listener,
            int attemptNumber
        ) throws Exception {
            TranslationApiClient.streamTranslationAttemptReal(
                config,
                body,
                listener,
                attemptNumber
            );
        }

        @Override
        public JSONObject sendSummaryRequest(
            TranslationConfig config,
            String body
        ) throws Exception {
            return TranslationApiClient.sendSummaryRequestReal(config, body);
        }
    };

    private static volatile ProviderTransport transport = REAL_TRANSPORT;

    /** Installs a fake transport for host tests; null restores the real one. */
    static void setTransportForTests(ProviderTransport replacement) {
        transport = replacement == null ? REAL_TRANSPORT : replacement;
    }

    public interface StreamListener {
        /** A network retry starts a fresh provider stream and NDJSON fragment. */
        void onAttemptStarted(int attemptNumber) throws Exception;

        void onTextDelta(String text) throws Exception;

        void onStreamCompleted(String stopReason) throws Exception;
    }

    /** A listener/decoder failure; never classify this as a network retry. */
    public static final class ListenerFailure extends Exception {
        private ListenerFailure(Throwable cause) {
            super("provider stream listener failed", cause);
        }
    }

    public static final class HttpStatusException extends IOException {
        private final int statusCode;
        private final String responseBody;

        HttpStatusException(int statusCode, String responseBody) {
            super("HTTP " + statusCode + ": " + truncate(responseBody, 4096));
            this.statusCode = statusCode;
            this.responseBody = responseBody;
        }

        public int getStatusCode() {
            return statusCode;
        }

        public String getResponseBody() {
            return responseBody;
        }
    }

    private static final class ProviderStreamState {
        private boolean terminalEventSeen;
        private String stopReason = "";
    }

    private TranslationApiClient() {
        throw new AssertionError("No instances");
    }

    public static List<String> listModels(
        String protocol,
        String configuredBaseUrl,
        String apiKey
    ) throws Exception {
        String normalizedProtocol = protocol == null
            ? ""
            : protocol.trim().toLowerCase(Locale.ROOT);
        if (!"openai".equals(normalizedProtocol)
            && !"anthropic".equals(normalizedProtocol)) {
            throw new IllegalArgumentException(
                "unsupported API protocol: " + protocol
            );
        }

        String endpoint = resolveModelsEndpoint(
            normalizedProtocol,
            configuredBaseUrl == null ? "" : configuredBaseUrl
        );
        if ("anthropic".equals(normalizedProtocol)) {
            endpoint = appendQuery(endpoint, "limit=1000");
        }
        HttpURLConnection connection = openConnection(endpoint);
        String key = apiKey == null ? "" : apiKey.trim();

        try {
            connection.setRequestMethod("GET");
            connection.setConnectTimeout(CONNECT_TIMEOUT_MS);
            connection.setReadTimeout(MODEL_READ_TIMEOUT_MS);
            connection.setInstanceFollowRedirects(false);
            connection.setRequestProperty("Accept", "application/json");
            connection.setRequestProperty(
                "Content-Type",
                "application/json"
            );
            connection.setRequestProperty(
                "User-Agent",
                "HousamoEmbedTrans/1.0"
            );
            applyAuthentication(
                connection,
                normalizedProtocol,
                key
            );

            int statusCode = connection.getResponseCode();
            InputStream responseStream = statusCode >= 400
                ? connection.getErrorStream()
                : connection.getInputStream();
            String responseBody;
            try (InputStream input = responseStream) {
                responseBody = input == null
                    ? ""
                    : IoUtils.readUtf8Limited(
                        input,
                        MAX_MODEL_RESPONSE_BYTES
                    );
            }
            if (statusCode < 200 || statusCode >= 300) {
                throw new IOException(
                    "HTTP "
                        + statusCode
                        + ": "
                        + truncate(redactSecret(responseBody, key), 4096)
                );
            }
            return parseModelIds(responseBody);
        } finally {
            connection.disconnect();
        }
    }

    /**
     * Executes exactly one provider HTTP stream. Network retry ownership lives
     * in the request coordinator so that every attempt gets an isolated
     * decoder and result state.
     */
    public static void streamTranslationAttempt(
        TranslationConfig config,
        String body,
        StreamListener listener,
        int attemptNumber
    ) throws Exception {
        transport.streamTranslationAttempt(
            config,
            body,
            listener,
            attemptNumber
        );
    }

    private static void streamTranslationAttemptReal(
        TranslationConfig config,
        String body,
        StreamListener listener,
        int attemptNumber
    ) throws Exception {
        if (config == null || body == null || listener == null) {
            throw new IllegalArgumentException(
                "config, body, and listener cannot be null"
            );
        }

        long startedAt = SystemClock.elapsedRealtime();
        ScheduledFuture<?> waitLog = WAIT_LOGGER.scheduleAtFixedRate(
            () -> Log.i(
                TAG,
                "Still waiting for API stream protocol="
                    + config.getProtocol()
                    + " model="
                    + config.getModel()
                    + " elapsed="
                    + formatElapsed(
                        SystemClock.elapsedRealtime() - startedAt
                    )
            ),
            WAIT_LOG_INTERVAL_SECONDS,
            WAIT_LOG_INTERVAL_SECONDS,
            TimeUnit.SECONDS
        );
        try {
            try {
                listener.onAttemptStarted(attemptNumber);
            } catch (Exception e) {
                throw new ListenerFailure(e);
            }
            streamTranslationOnce(config, body, listener);
        } finally {
            waitLog.cancel(false);
            Log.i(
                TAG,
                "API stream attempt ended"
                    + " protocol="
                    + config.getProtocol()
                    + " model="
                    + config.getModel()
                    + " totalWait="
                    + formatElapsed(
                        SystemClock.elapsedRealtime() - startedAt
                    )
            );
        }
    }

    /**
     * Executes one non-streaming Summary Request and returns the parsed
     * provider response envelope. The caller owns retry and schema validation.
     */
    public static JSONObject sendSummaryRequest(
        TranslationConfig config,
        String body
    ) throws Exception {
        return transport.sendSummaryRequest(config, body);
    }

    private static JSONObject sendSummaryRequestReal(
        TranslationConfig config,
        String body
    ) throws Exception {
        if (config == null || body == null) {
            throw new IllegalArgumentException(
                "config and body cannot be null"
            );
        }
        HttpURLConnection connection = openConnection(
            resolveTranslationEndpoint(config)
        );
        try {
            connection.setRequestMethod("POST");
            connection.setConnectTimeout(CONNECT_TIMEOUT_MS);
            connection.setReadTimeout(MODEL_READ_TIMEOUT_MS);
            connection.setInstanceFollowRedirects(false);
            connection.setDoOutput(true);
            connection.setRequestProperty("Accept", "application/json");
            connection.setRequestProperty(
                "Content-Type",
                "application/json; charset=utf-8"
            );
            connection.setRequestProperty(
                "User-Agent",
                "HousamoEmbedTrans/1.0"
            );
            if ("anthropic".equals(config.getProtocol())
                && config.getThinkingStrength().isEnabled()) {
                connection.setRequestProperty(
                    "anthropic-beta",
                    ANTHROPIC_EXTENDED_THINKING_BETA
                );
            }
            applyAuthentication(
                connection,
                config.getProtocol(),
                config.getApiKey()
            );

            byte[] requestBytes = body.getBytes(StandardCharsets.UTF_8);
            connection.setFixedLengthStreamingMode(requestBytes.length);
            try (OutputStream output = connection.getOutputStream()) {
                output.write(requestBytes);
                output.flush();
            }

            int statusCode = connection.getResponseCode();
            if (statusCode < 200 || statusCode >= 300) {
                InputStream errorStream = connection.getErrorStream();
                String errorBody;
                try (InputStream input = errorStream) {
                    errorBody = input == null
                        ? ""
                        : IoUtils.readUtf8Limited(
                            input,
                            MAX_ERROR_RESPONSE_BYTES
                        );
                }
                throw new HttpStatusException(
                    statusCode,
                    redactSecret(errorBody, config.getApiKey())
                );
            }

            String responseBody;
            try (InputStream input = connection.getInputStream()) {
                responseBody = IoUtils.readUtf8Limited(
                    input,
                    MAX_MODEL_RESPONSE_BYTES
                );
            }
            try {
                return new JSONObject(responseBody);
            } catch (org.json.JSONException e) {
                throw new IOException(
                    "summary response is not valid JSON",
                    e
                );
            }
        } finally {
            connection.disconnect();
        }
    }

    private static void streamTranslationOnce(
        TranslationConfig config,
        String body,
        StreamListener listener
    ) throws Exception {
        HttpURLConnection connection = openConnection(
            resolveTranslationEndpoint(config)
        );
        try {
            connection.setRequestMethod("POST");
            connection.setConnectTimeout(CONNECT_TIMEOUT_MS);
            connection.setReadTimeout(TRANSLATION_READ_TIMEOUT_MS);
            connection.setInstanceFollowRedirects(false);
            connection.setDoOutput(true);
            connection.setRequestProperty(
                "Accept",
                "text/event-stream"
            );
            connection.setRequestProperty(
                "Content-Type",
                "application/json; charset=utf-8"
            );
            connection.setRequestProperty(
                "User-Agent",
                "HousamoEmbedTrans/1.0"
            );
            if ("anthropic".equals(config.getProtocol())
                && config.getThinkingStrength().isEnabled()) {
                connection.setRequestProperty(
                    "anthropic-beta",
                    ANTHROPIC_EXTENDED_THINKING_BETA
                );
            }
            applyAuthentication(
                connection,
                config.getProtocol(),
                config.getApiKey()
            );

            byte[] requestBytes = body.getBytes(StandardCharsets.UTF_8);
            connection.setFixedLengthStreamingMode(requestBytes.length);
            try (OutputStream output = connection.getOutputStream()) {
                output.write(requestBytes);
                output.flush();
            }

            int statusCode = connection.getResponseCode();
            if (statusCode < 200 || statusCode >= 300) {
                InputStream errorStream = connection.getErrorStream();
                String errorBody;
                try (InputStream input = errorStream) {
                    errorBody = input == null
                        ? ""
                        : IoUtils.readUtf8Limited(
                            input,
                            MAX_ERROR_RESPONSE_BYTES
                        );
                }
                throw new HttpStatusException(
                    statusCode,
                    redactSecret(errorBody, config.getApiKey())
                );
            }

            ProviderStreamState state = new ProviderStreamState();
            try (InputStream input = connection.getInputStream();
                 BufferedReader reader = new BufferedReader(
                     new InputStreamReader(input, StandardCharsets.UTF_8)
                 )) {
                readSse(
                    reader,
                    (eventName, data) -> dispatchProviderEvent(
                        config.getProtocol(),
                        eventName,
                        data,
                        listener,
                        state
                    )
                );
            }

            if (!state.terminalEventSeen) {
                throw new IOException(
                    "provider stream ended without a terminal event"
                );
            }
            if (!isNormalStopReason(
                    config.getProtocol(),
                    state.stopReason
                )) {
                throw new IllegalArgumentException(
                    "provider generation stopped abnormally: "
                        + (
                            state.stopReason.isEmpty()
                                ? "<missing>"
                                : state.stopReason
                        )
                );
            }
            try {
                listener.onStreamCompleted(state.stopReason);
            } catch (Exception e) {
                throw new ListenerFailure(e);
            }
        } finally {
            connection.disconnect();
        }
    }

    private interface SseEventConsumer {
        void accept(String eventName, String data) throws Exception;
    }

    private static void readSse(
        BufferedReader reader,
        SseEventConsumer consumer
    ) throws Exception {
        String eventName = "";
        StringBuilder data = new StringBuilder();
        String line;
        while ((line = reader.readLine()) != null) {
            if (line.isEmpty()) {
                if (data.length() > 0) {
                    consumer.accept(eventName, data.toString());
                }
                eventName = "";
                data.setLength(0);
                continue;
            }
            if (line.startsWith(":")) {
                continue;
            }
            if (line.startsWith("event:")) {
                eventName = line.substring("event:".length()).trim();
            } else if (line.startsWith("data:")) {
                if (data.length() > 0) {
                    data.append('\n');
                }
                data.append(line.substring("data:".length()).trim());
            }
        }
        if (data.length() > 0) {
            consumer.accept(eventName, data.toString());
        }
    }

    private static void dispatchProviderEvent(
        String protocol,
        String eventName,
        String data,
        StreamListener listener,
        ProviderStreamState state
    ) throws Exception {
        if ("openai".equals(protocol)) {
            dispatchOpenAiEvent(data, listener, state);
        } else {
            dispatchAnthropicEvent(eventName, data, listener, state);
        }
    }

    private static void dispatchOpenAiEvent(
        String data,
        StreamListener listener,
        ProviderStreamState state
    ) throws Exception {
        if ("[DONE]".equals(data)) {
            state.terminalEventSeen = true;
            return;
        }

        JSONObject event = new JSONObject(data);
        JSONObject error = event.optJSONObject("error");
        if (error != null) {
            throw new IllegalArgumentException(
                "provider stream error: "
                    + error.optString("message", error.toString())
            );
        }
        JSONArray choices = event.optJSONArray("choices");
        if (choices == null || choices.length() == 0) {
            return;
        }
        JSONObject choice = choices.optJSONObject(0);
        if (choice == null) {
            return;
        }
        JSONObject delta = choice.optJSONObject("delta");
        if (delta != null) {
            Object content = delta.opt("content");
            if (content instanceof String && !((String) content).isEmpty()) {
                try {
                    listener.onTextDelta((String) content);
                } catch (Exception e) {
                    throw new ListenerFailure(e);
                }
            }
        }
        Object finishValue = choice.opt("finish_reason");
        if (finishValue instanceof String) {
            state.stopReason = (String) finishValue;
            if (!state.stopReason.isEmpty()) {
                state.terminalEventSeen = true;
            }
        }
    }

    private static void dispatchAnthropicEvent(
        String eventName,
        String data,
        StreamListener listener,
        ProviderStreamState state
    ) throws Exception {
        JSONObject event = new JSONObject(data);
        String type = event.optString("type", eventName);
        if ("error".equals(type)) {
            JSONObject error = event.optJSONObject("error");
            throw new IllegalArgumentException(
                "provider stream error: "
                    + (
                        error == null
                            ? event.toString()
                            : error.optString("message", error.toString())
                    )
            );
        }
        if ("content_block_delta".equals(type)) {
            JSONObject delta = event.optJSONObject("delta");
            if (delta != null && "text_delta".equals(
                    delta.optString("type", "")
                )) {
                String text = delta.optString("text", "");
                if (!text.isEmpty()) {
                    try {
                        listener.onTextDelta(text);
                    } catch (Exception e) {
                        throw new ListenerFailure(e);
                    }
                }
            }
            return;
        }
        if ("message_delta".equals(type)) {
            JSONObject delta = event.optJSONObject("delta");
            if (delta != null) {
                state.stopReason = delta.optString(
                    "stop_reason",
                    state.stopReason
                );
            }
            return;
        }
        if ("message_stop".equals(type)) {
            state.terminalEventSeen = true;
        }
    }

    private static boolean isNormalStopReason(
        String protocol,
        String stopReason
    ) {
        if ("openai".equals(protocol)) {
            return "stop".equals(stopReason);
        }
        return "end_turn".equals(stopReason)
            || "stop_sequence".equals(stopReason);
    }

    static String resolveModelsEndpoint(
        String protocol,
        String configuredBaseUrl
    ) {
        String baseUrl = configuredBaseUrl.trim();
        if (baseUrl.isEmpty()) {
            baseUrl = "openai".equals(protocol)
                ? "https://api.openai.com/v1"
                : "https://api.anthropic.com/v1";
        }
        baseUrl = stripTrailingSlashes(baseUrl);
        if ("openai".equals(protocol)
            && baseUrl.endsWith("/chat/completions")) {
            baseUrl = baseUrl.substring(
                0,
                baseUrl.length() - "/chat/completions".length()
            );
        } else if ("anthropic".equals(protocol)
            && baseUrl.endsWith("/messages")) {
            baseUrl = baseUrl.substring(
                0,
                baseUrl.length() - "/messages".length()
            );
        }
        if (baseUrl.endsWith("/models")) {
            return baseUrl;
        }
        return baseUrl.endsWith("/v1")
            ? baseUrl + "/models"
            : baseUrl + "/v1/models";
    }

    static String resolveTranslationEndpoint(TranslationConfig config) {
        String baseUrl = config.getApiUrl().trim();
        if (baseUrl.isEmpty()) {
            baseUrl = "openai".equals(config.getProtocol())
                ? "https://api.openai.com/v1"
                : "https://api.anthropic.com";
        }
        baseUrl = stripTrailingSlashes(baseUrl);
        if ("openai".equals(config.getProtocol())) {
            if (baseUrl.endsWith("/chat/completions")) {
                return baseUrl;
            }
            return baseUrl.endsWith("/v1")
                ? baseUrl + "/chat/completions"
                : baseUrl + "/v1/chat/completions";
        }
        if (baseUrl.endsWith("/v1/messages")) {
            return baseUrl;
        }
        return baseUrl.endsWith("/v1")
            ? baseUrl + "/messages"
            : baseUrl + "/v1/messages";
    }

    private static HttpURLConnection openConnection(String endpoint)
        throws Exception {
        URI uri = new URI(endpoint);
        String scheme = uri.getScheme();
        if (!"https".equalsIgnoreCase(scheme)
            && !"http".equalsIgnoreCase(scheme)) {
            throw new IllegalArgumentException(
                "API URL must use http or https"
            );
        }
        return (HttpURLConnection) uri.toURL().openConnection();
    }

    private static void applyAuthentication(
        HttpURLConnection connection,
        String protocol,
        String apiKey
    ) {
        String key = apiKey == null ? "" : apiKey.trim();
        if ("openai".equals(protocol)) {
            if (!key.isEmpty()) {
                connection.setRequestProperty(
                    "Authorization",
                    "Bearer " + key
                );
            }
        } else {
            if (!key.isEmpty()) {
                connection.setRequestProperty("x-api-key", key);
            }
            connection.setRequestProperty(
                "anthropic-version",
                "2023-06-01"
            );
        }
    }

    private static List<String> parseModelIds(String responseBody)
        throws Exception {
        JSONObject response = new JSONObject(responseBody);
        JSONArray data = response.getJSONArray("data");
        Set<String> uniqueIds = new LinkedHashSet<>();
        for (int index = 0; index < data.length(); index++) {
            JSONObject model = data.optJSONObject(index);
            if (model == null) {
                continue;
            }
            String id = model.optString("id", "").trim();
            if (!id.isEmpty()) {
                uniqueIds.add(id);
            }
        }
        if (uniqueIds.isEmpty()) {
            throw new IllegalArgumentException(
                "model list response contains no model IDs"
            );
        }
        return new ArrayList<>(uniqueIds);
    }

    public static boolean isRetryableNetworkException(Throwable error) {
        Throwable current = error;
        while (current != null) {
            if (current instanceof IOException
                || current instanceof SocketTimeoutException
                || current instanceof SocketException
                || current instanceof UnknownHostException) {
                return true;
            }
            current = current.getCause();
        }
        return false;
    }

    public static boolean isRetryableHttpStatus(int statusCode) {
        return statusCode == 408
            || statusCode == 429
            || (statusCode >= 500 && statusCode <= 599);
    }

    public static void waitBeforeRetry(int retryNumber)
        throws InterruptedException {
        int exponent = Math.min(Math.max(retryNumber - 1, 0), 3);
        long delayMs = Math.min(
            RETRY_BASE_DELAY_MS * (1L << exponent),
            RETRY_MAX_DELAY_MS
        );
        try {
            Thread.sleep(delayMs);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw e;
        }
    }

    public static void logNetworkRetry(
        String reason,
        int retry,
        int maxRetries
    ) {
        Log.w(
            TAG,
            "Retrying translation stream after "
                + reason
                + " (retry "
                + retry
                + "/"
                + maxRetries
                + ")"
        );
    }

    private static String appendQuery(String url, String query) {
        return url + (url.contains("?") ? "&" : "?") + query;
    }

    private static String formatElapsed(long elapsedMs) {
        long totalSeconds = Math.max(0L, elapsedMs) / 1_000L;
        long minutes = totalSeconds / 60L;
        long seconds = totalSeconds % 60L;
        return minutes + "m" + seconds + "s";
    }

    private static String stripTrailingSlashes(String value) {
        String result = value;
        while (result.endsWith("/")) {
            result = result.substring(0, result.length() - 1);
        }
        return result;
    }

    private static String redactSecret(String value, String secret) {
        if (value == null || secret == null || secret.isEmpty()) {
            return value == null ? "" : value;
        }
        return value.replace(secret, "[REDACTED]");
    }

    private static String safeMessage(Throwable error) {
        String message = error.getMessage();
        return message == null || message.trim().isEmpty()
            ? error.getClass().getSimpleName()
            : message;
    }

    private static String truncate(String value, int maxLength) {
        if (value == null) {
            return "";
        }
        return value.length() <= maxLength
            ? value
            : value.substring(0, maxLength);
    }
}
