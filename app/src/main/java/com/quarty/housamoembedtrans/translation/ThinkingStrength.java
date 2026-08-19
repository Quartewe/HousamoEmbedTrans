package com.quarty.housamoembedtrans.translation;

import java.util.Locale;

/**
 * Fixed ThinkingStrength levels shared by Translation and Summary requests.
 *
 * <p>{@code none} disables thinking and must not produce any thinking field.
 * Non-{@code none} levels map to the same named {@code reasoning_effort} for
 * OpenAI-compatible providers and to an Anthropic thinking budget.</p>
 */
public enum ThinkingStrength {
    NONE("none", false, 0),
    MINIMAL("minimal", true, 1024),
    LOW("low", true, 2048),
    MEDIUM("medium", true, 4096),
    HIGH("high", true, 8192),
    XHIGH("xhigh", true, 16384),
    MAX("max", true, 32000);

    private final String configValue;
    private final boolean enabled;
    private final int anthropicBudgetTokens;

    ThinkingStrength(
        String configValue,
        boolean enabled,
        int anthropicBudgetTokens
    ) {
        this.configValue = configValue;
        this.enabled = enabled;
        this.anthropicBudgetTokens = anthropicBudgetTokens;
    }

    public String getConfigValue() {
        return configValue;
    }

    public boolean isEnabled() {
        return enabled;
    }

    public int getAnthropicBudgetTokens() {
        return anthropicBudgetTokens;
    }

    public static ThinkingStrength fromConfigValue(String value) {
        if (value == null) {
            throw new IllegalArgumentException(
                "ThinkingStrength must not be null"
            );
        }
        String normalized = value.trim().toLowerCase(Locale.ROOT);
        for (ThinkingStrength strength : values()) {
            if (strength.configValue.equals(normalized)) {
                return strength;
            }
        }
        throw new IllegalArgumentException(
            "Unknown ThinkingStrength: " + value
                + " (expected none, minimal, low, medium, high, xhigh, max)"
        );
    }
}
