package com.quarty.housamoembedtrans.bridge;

/** Shared Java/JNI result codes; a dispatched Binder call is not RESTORED. */
public final class ChoiceBackResult {
    public static final int RESTORED = 0;
    public static final int NO_TARGET = 1;
    public static final int BUSY = 2;
    public static final int UNAVAILABLE = 3;
    public static final int GAME_NOT_CONNECTED = 4;
    public static final int FAILED = 5;
    public static final int UNKNOWN = 6;

    private ChoiceBackResult() { }
}
