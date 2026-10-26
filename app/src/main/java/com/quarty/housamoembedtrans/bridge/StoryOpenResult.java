package com.quarty.housamoembedtrans.bridge;

/** Open-story results share the existing one-shot game-operation callback wire codes. */
public final class StoryOpenResult {
    public static final int STARTED = 0;
    public static final int NO_ENTRY = 1;
    public static final int BUSY = 2;
    public static final int UNAVAILABLE = 3;
    public static final int GAME_NOT_CONNECTED = 4;
    public static final int FAILED = 5;
    public static final int UNKNOWN = 6;
    public static final int AMBIGUOUS = 7;

    private StoryOpenResult() {}
}
