package com.quarty.housamoembedtrans.translation;

/**
 * Local conservative token estimator used for the {@code context_length}
 * preflight. It never calls a provider counting endpoint.
 *
 * <p>Each CJK/East-Asian character counts as one token; every other character
 * contributes {@code ceil(characters / 4)} tokens. The two groups are summed.
 * This intentionally over-estimates mixed Japanese/Chinese text for safety.</p>
 */
public final class ProviderTokenEstimator {

    private ProviderTokenEstimator() {
        throw new AssertionError("No instances");
    }

    public static int estimate(String text) {
        if (text == null || text.isEmpty()) {
            return 0;
        }
        long cjkCount = 0;
        long otherCount = 0;
        for (int offset = 0; offset < text.length(); ) {
            int codePoint = text.codePointAt(offset);
            if (isEastAsianCodePoint(codePoint)) {
                cjkCount++;
            } else {
                otherCount++;
            }
            offset += Character.charCount(codePoint);
        }

        long estimate = cjkCount + ceilDiv(otherCount, 4);
        return estimate > Integer.MAX_VALUE
            ? Integer.MAX_VALUE
            : (int) estimate;
    }

    private static boolean isEastAsianCodePoint(int codePoint) {
        return (codePoint >= 0x3400 && codePoint <= 0x4DBF)   // CJK Ext A
            || (codePoint >= 0x4E00 && codePoint <= 0x9FFF)   // CJK Unified
            || (codePoint >= 0x3040 && codePoint <= 0x30FF)   // Kana
            || (codePoint >= 0x31F0 && codePoint <= 0x31FF)   // Katakana ext
            || (codePoint >= 0xF900 && codePoint <= 0xFAFF)   // Compat ideographs
            || (codePoint >= 0xAC00 && codePoint <= 0xD7AF);  // Hangul
    }

    private static long ceilDiv(long value, int divisor) {
        return value == 0 ? 0 : 1 + ((value - 1) / divisor);
    }
}
