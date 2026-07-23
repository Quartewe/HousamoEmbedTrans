package com.quarty.housamoembedtrans.translation;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/** Small REST client used by the settings UI to discover provider model IDs. */
public final class TranslationApiClient {

    private static final int CONNECT_TIMEOUT_MS = 30_000;
    private static final int READ_TIMEOUT_MS = 60_000;
    private static final int MAX_RESPONSE_BYTES = 4 * 1024 * 1024;

    private TranslationApiClient() {
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
            throw new IllegalArgumentException("unsupported API protocol: " + protocol);
        }

        String endpoint = resolveModelsEndpoint(
            normalizedProtocol,
            configuredBaseUrl == null ? "" : configuredBaseUrl
        );
        if ("anthropic".equals(normalizedProtocol)) {
            endpoint = appendQuery(endpoint, "limit=1000");
        }

        HttpURLConnection connection = (HttpURLConnection) new URL(endpoint).openConnection();
        try {
            connection.setRequestMethod("GET");
            connection.setConnectTimeout(CONNECT_TIMEOUT_MS);
            connection.setReadTimeout(READ_TIMEOUT_MS);
            connection.setInstanceFollowRedirects(false);
            connection.setRequestProperty("Accept", "application/json");
            connection.setRequestProperty("Content-Type", "application/json");
            connection.setRequestProperty("User-Agent", "HousamoEmbedTrans/1.0");

            String key = apiKey == null ? "" : apiKey.trim();
            if ("openai".equals(normalizedProtocol)) {
                if (!key.isEmpty()) {
                    connection.setRequestProperty("Authorization", "Bearer " + key);
                }
            } else {
                if (!key.isEmpty()) {
                    connection.setRequestProperty("x-api-key", key);
                }
                connection.setRequestProperty("anthropic-version", "2023-06-01");
            }

            int statusCode = connection.getResponseCode();
            InputStream responseStream = statusCode >= 400
                ? connection.getErrorStream()
                : connection.getInputStream();
            String responseBody = responseStream == null
                ? ""
                : readBody(responseStream);

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

    static String resolveModelsEndpoint(String protocol, String configuredBaseUrl) {
        String baseUrl = configuredBaseUrl.trim();
        if (baseUrl.isEmpty()) {
            baseUrl = "openai".equals(protocol)
                ? "https://api.openai.com/v1"
                : "https://api.anthropic.com/v1";
        }

        while (baseUrl.endsWith("/")) {
            baseUrl = baseUrl.substring(0, baseUrl.length() - 1);
        }

        if ("openai".equals(protocol) && baseUrl.endsWith("/chat/completions")) {
            baseUrl = baseUrl.substring(
                0,
                baseUrl.length() - "/chat/completions".length()
            );
        } else if ("anthropic".equals(protocol) && baseUrl.endsWith("/messages")) {
            baseUrl = baseUrl.substring(0, baseUrl.length() - "/messages".length());
        }

        if (baseUrl.endsWith("/models")) {
            return baseUrl;
        }
        return baseUrl.endsWith("/v1")
            ? baseUrl + "/models"
            : baseUrl + "/v1/models";
    }

    private static List<String> parseModelIds(String responseBody) throws Exception {
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
            throw new IllegalArgumentException("model list response contains no model IDs");
        }
        return new ArrayList<>(uniqueIds);
    }

    private static String appendQuery(String url, String query) {
        return url + (url.contains("?") ? "&" : "?") + query;
    }

    private static String readBody(InputStream input) throws IOException {
        try (InputStream source = input;
             ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[8192];
            int total = 0;
            int read;
            while ((read = source.read(buffer)) != -1) {
                total += read;
                if (total > MAX_RESPONSE_BYTES) {
                    throw new IOException("model list response is larger than 4 MiB");
                }
                output.write(buffer, 0, read);
            }
            return output.toString(StandardCharsets.UTF_8.name());
        }
    }

    private static String redactSecret(String value, String secret) {
        if (value == null || secret == null || secret.isEmpty()) {
            return value == null ? "" : value;
        }
        return value.replace(secret, "[REDACTED]");
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
