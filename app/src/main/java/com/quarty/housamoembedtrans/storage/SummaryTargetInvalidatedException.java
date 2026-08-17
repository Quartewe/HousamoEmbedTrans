package com.quarty.housamoembedtrans.storage;

import java.io.IOException;

/**
 * Indicates that a Summary Job's owner or cutoff disappeared before its
 * derived result could be written.  This is not an execution failure.
 */
public class SummaryTargetInvalidatedException extends IOException {
    private static final long serialVersionUID = 1L;

    public SummaryTargetInvalidatedException(String message) {
        super(message);
    }

    public SummaryTargetInvalidatedException(String message, Throwable cause) {
        super(message, cause);
    }
}
