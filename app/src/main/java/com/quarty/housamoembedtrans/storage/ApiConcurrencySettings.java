package com.quarty.housamoembedtrans.storage;

/** Validation/defaults for the API-wide Translation + Summary concurrency. */
public final class ApiConcurrencySettings {
    public static final int DEFAULT_API_CONCURRENCY = 2;
    public static final int MIN_API_CONCURRENCY = 1;
    public static final int MAX_API_CONCURRENCY = 8;

    private ApiConcurrencySettings() {
        throw new AssertionError("No instances");
    }

    public static int normalize(Object raw) {
        if (raw == null) {
            return DEFAULT_API_CONCURRENCY;
        }
        if (!(raw instanceof Number)) {
            throw new IllegalArgumentException(
                "UserSettings.Api.max_concurrent_requests must be an integer"
            );
        }
        double value = ((Number) raw).doubleValue();
        if (!Double.isFinite(value)
            || value != Math.rint(value)
            || value < MIN_API_CONCURRENCY
            || value > MAX_API_CONCURRENCY) {
            throw new IllegalArgumentException(
                "UserSettings.Api.max_concurrent_requests must be an integer from "
                    + MIN_API_CONCURRENCY + " to " + MAX_API_CONCURRENCY
            );
        }
        return (int) value;
    }
}
