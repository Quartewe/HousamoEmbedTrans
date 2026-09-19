package com.quarty.housamoembedtrans.ui;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;

/** Reads the tag of the repository's latest stable GitHub release. */
final class AppUpdateChecker {
    static final String LATEST_RELEASE_API =
        "https://api.github.com/repos/Quartewe/HousamoEmbedTrans/releases/latest";

    private AppUpdateChecker() {
    }

    static String fetchLatestStableTag() throws Exception {
        HttpURLConnection connection = (HttpURLConnection) new URL(
            LATEST_RELEASE_API
        ).openConnection();
        connection.setRequestMethod("GET");
        connection.setConnectTimeout(5000);
        connection.setReadTimeout(5000);
        connection.setRequestProperty("Accept", "application/vnd.github+json");
        connection.setRequestProperty("User-Agent", "HousamoEmbedTrans");
        try {
            int responseCode = connection.getResponseCode();
            if (responseCode < 200 || responseCode >= 300) {
                throw new IOException(
                    "GitHub release request returned HTTP " + responseCode
                );
            }
            String body = readBody(connection.getInputStream());
            JSONObject release = new JSONObject(body);
            if (release.optBoolean("draft", false)
                || release.optBoolean("prerelease", false)) {
                throw new IOException("GitHub latest release is not stable");
            }
            String tag = release.optString("tag_name", "").trim();
            if (tag.isEmpty()) {
                throw new IOException("GitHub latest release has no tag");
            }
            String normalized = normalizeVersion(tag);
            if (normalized.isEmpty()) {
                throw new IOException("GitHub latest release has no usable version");
            }
            return normalized;
        } finally {
            connection.disconnect();
        }
    }

    static String normalizeVersion(String value) {
        String normalized = value == null ? "" : value.trim();
        if (!normalized.isEmpty()
            && (normalized.charAt(0) == 'v' || normalized.charAt(0) == 'V')) {
            normalized = normalized.substring(1).trim();
        }
        return normalized;
    }

    private static String readBody(InputStream input) throws IOException {
        StringBuilder body = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(
            new InputStreamReader(input, StandardCharsets.UTF_8)
        )) {
            char[] buffer = new char[4096];
            int read;
            while ((read = reader.read(buffer)) != -1) {
                body.append(buffer, 0, read);
                if (body.length() > 1024 * 1024) {
                    throw new IOException("GitHub release response is too large");
                }
            }
        }
        return body.toString();
    }
}
