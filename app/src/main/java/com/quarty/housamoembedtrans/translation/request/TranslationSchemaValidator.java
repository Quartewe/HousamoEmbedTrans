package com.quarty.housamoembedtrans.translation.request;
import com.quarty.housamoembedtrans.storage.json.JsonSchemaValidator;

import com.quarty.housamoembedtrans.util.IoUtils;

import android.content.Context;

import org.json.JSONObject;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;

public final class TranslationSchemaValidator {
    private static final String SCHEMA_ASSET_PATH =
        "schema/translation_result_schema.json";
    private static final int MAX_SCHEMA_BYTES = 256 * 1024;

    public static final class ValidationException extends Exception {
        public ValidationException(String message, Throwable cause) {
            super(message, cause);
        }
    }

    private final JsonSchemaValidator delegate;

    public TranslationSchemaValidator(Context context) {
        Context appContext = context.getApplicationContext();
        Context safeContext = appContext != null ? appContext : context;

        try (InputStream input =
                 safeContext.getAssets().open(SCHEMA_ASSET_PATH)) {
            String schemaText = new String(
                IoUtils.readAllBytesLimited(input, MAX_SCHEMA_BYTES),
                StandardCharsets.UTF_8
            );
            delegate = new JsonSchemaValidator(
                new JSONObject(schemaText)
            );
        } catch (Exception e) {
            throw new IllegalStateException(
                "could not load translation result schema",
                e
            );
        }
    }

    public void validate(JSONObject result)
        throws ValidationException {
        try {
            delegate.validate(result);
        } catch (JsonSchemaValidator.ValidationException e) {
            throw new ValidationException(e.getMessage(), e);
        }
    }
}