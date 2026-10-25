package com.quarty.housamoembedtrans.translation;

/** Current playback position, owned by one game-port registration. */
oneway interface IStoryPositionListener {
    // Empty scene means the story ended; null means its identity is unavailable.
    // Empty choiceLabel and -1 mean no recorded choice; null means its identity is unavailable.
    void onPositionChanged(String scene, String choiceLabel, int choicePage);
}
