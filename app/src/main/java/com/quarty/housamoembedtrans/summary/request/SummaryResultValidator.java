package com.quarty.housamoembedtrans.summary.request;
import com.quarty.housamoembedtrans.storage.json.JsonSchemaValidator;

import com.quarty.housamoembedtrans.util.IoUtils;
import com.quarty.housamoembedtrans.util.JobValidator;

import android.content.Context;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;

/**
 * Validates the model-visible summary result against
 * {@code summary_result_schema.json}.
 *
 * <p>Provider HTTP responses are envelopes: OpenAI uses
 * {@code choices[0].message.content} and Anthropic uses
 * {@code content[type=text].text}. The extracted string must be a single JSON
 * object with exactly one non-empty {@code summary} field.</p>
 */
public final class SummaryResultValidator {

    public static final String SCHEMA_ASSET_PATH = "summary_result_schema.json";

    private static final int MAX_SUMMARY_CONTENT_CHARS = 4 * 1024 * 1024;

    private final JsonSchemaValidator schemaValidator;

    public SummaryResultValidator(JSONObject schema) {
        if (schema == null) {
            throw new IllegalArgumentException("summary schema is required");
        }
        this.schemaValidator = new JsonSchemaValidator(schema);
    }

    public static SummaryResultValidator fromSchema(JSONObject schema) {
        return new SummaryResultValidator(schema);
    }

    public static SummaryResultValidator loadFromAssets(Context context)
        throws Exception {
        Context appContext = context.getApplicationContext();
        Context safeContext = appContext != null ? appContext : context;
        try (InputStream input = safeContext.getAssets().open(SCHEMA_ASSET_PATH)) {
            String schemaText = new String(
                IoUtils.readAllBytesLimited(input, 256 * 1024),
                StandardCharsets.UTF_8
            );
            return new SummaryResultValidator(new JSONObject(schemaText));
        }
    }

    public static final class ValidationException extends Exception {
        public ValidationException(String message) {
            super(message);
        }

        public ValidationException(String message, Throwable cause) {
            super(message, cause);
        }
    }

    /**
     * Extracts the model JSON from the provider envelope, validates it against
     * the schema and returns the non-empty summary text.
     */
    public String validateAndExtract(JSONObject providerResponse, String protocol)
        throws ValidationException {
        if (providerResponse == null) {
            throw new ValidationException("summary provider response is null");
        }
        String content = extractModelContent(providerResponse, protocol);
        if (content == null || content.trim().isEmpty()) {
            throw new ValidationException(
                "summary provider response contains no model content"
            );
        }

        final JSONObject summaryObject;
        try {
            summaryObject = JobValidator.parseJsonObject(
                content,
                "summary provider content"
            );
        } catch (JobValidator.ValidationException e) {
            throw new ValidationException(
                "summary provider content is not valid JSON: " + e.getMessage(),
                e
            );
        }

        try {
            schemaValidator.validate(summaryObject);
        } catch (JsonSchemaValidator.ValidationException e) {
            throw new ValidationException(
                "summary result failed schema validation: " + e.getMessage(),
                e
            );
        }

        String summary = summaryObject.optString("summary", "").trim();
        if (summary.isEmpty()) {
            throw new ValidationException(
                "summary result must contain a non-empty summary field"
            );
        }
        return summary;
    }

    private static String extractModelContent(
        JSONObject response,
        String protocol
    ) throws ValidationException {
        if ("openai".equals(protocol)) {
            JSONArray choices = response.optJSONArray("choices");
            if (choices == null || choices.length() == 0) {
                throw new ValidationException(
                    "OpenAI summary response has no choices"
                );
            }
            JSONObject choice = choices.optJSONObject(0);
            if (choice == null) {
                throw new ValidationException(
                    "OpenAI summary response choice is missing"
                );
            }
            JSONObject message = choice.optJSONObject("message");
            if (message == null) {
                throw new ValidationException(
                    "OpenAI summary response message is missing"
                );
            }
            return message.optString("content", null);
        }
        if ("anthropic".equals(protocol)) {
            JSONArray content = response.optJSONArray("content");
            if (content == null) {
                throw new ValidationException(
                    "Anthropic summary response has no content array"
                );
            }
            for (int index = 0; index < content.length(); index++) {
                JSONObject block = content.optJSONObject(index);
                if (block != null
                    && "text".equals(block.optString("type", ""))) {
                    return block.optString("text", null);
                }
            }
            throw new ValidationException(
                "Anthropic summary response has no text block"
            );
        }
        throw new ValidationException(
            "unsupported summary provider protocol: " + protocol
        );
    }
}
