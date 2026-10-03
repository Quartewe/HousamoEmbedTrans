package com.quarty.housamoembedtrans.util;

import java.util.Comparator;

/**
 * Compares strings in a stable natural order for embedded decimal numbers.
 *
 * <p>Digit runs are compared by their numeric value without parsing them into
 * a machine-sized integer. Non-digit characters retain their UTF-16 order,
 * which keeps the comparator deterministic for Scene names and other keys
 * that use the same naming convention.</p>
 */
public final class NaturalStringComparator implements Comparator<String> {

    public static final NaturalStringComparator INSTANCE =
        new NaturalStringComparator();

    private NaturalStringComparator() {
    }

    @Override
    public int compare(String left, String right) {
        if (left == right) {
            return 0;
        }
        if (left == null) {
            return -1;
        }
        if (right == null) {
            return 1;
        }

        int leftIndex = 0;
        int rightIndex = 0;
        while (leftIndex < left.length() && rightIndex < right.length()) {
            char leftChar = left.charAt(leftIndex);
            char rightChar = right.charAt(rightIndex);
            if (isAsciiDigit(leftChar) && isAsciiDigit(rightChar)) {
                int leftEnd = leftIndex;
                int rightEnd = rightIndex;
                while (leftEnd < left.length()
                    && isAsciiDigit(left.charAt(leftEnd))) {
                    leftEnd++;
                }
                while (rightEnd < right.length()
                    && isAsciiDigit(right.charAt(rightEnd))) {
                    rightEnd++;
                }

                int leftSignificant = leftIndex;
                int rightSignificant = rightIndex;
                while (leftSignificant < leftEnd
                    && left.charAt(leftSignificant) == '0') {
                    leftSignificant++;
                }
                while (rightSignificant < rightEnd
                    && right.charAt(rightSignificant) == '0') {
                    rightSignificant++;
                }

                int lengthOrder = Integer.compare(
                    leftEnd - leftSignificant,
                    rightEnd - rightSignificant
                );
                if (lengthOrder != 0) {
                    return lengthOrder;
                }
                while (leftSignificant < leftEnd) {
                    int digitOrder = Character.compare(
                        left.charAt(leftSignificant++),
                        right.charAt(rightSignificant++)
                    );
                    if (digitOrder != 0) {
                        return digitOrder;
                    }
                }

                // Numeric values are equal. Continue with the suffix so that
                // a1/a01 remain deterministic when their following text is
                // different.
                leftIndex = leftEnd;
                rightIndex = rightEnd;
            } else {
                int textOrder = Character.compare(leftChar, rightChar);
                if (textOrder != 0) {
                    return textOrder;
                }
                leftIndex++;
                rightIndex++;
            }
        }

        int remainingOrder = Integer.compare(
            left.length() - leftIndex,
            right.length() - rightIndex
        );
        if (remainingOrder != 0) {
            return remainingOrder;
        }

        // Distinguish identifiers that only differ by zero padding.
        int lengthOrder = Integer.compare(left.length(), right.length());
        return lengthOrder != 0 ? lengthOrder : left.compareTo(right);
    }

    private static boolean isAsciiDigit(char value) {
        return value >= '0' && value <= '9';
    }
}
