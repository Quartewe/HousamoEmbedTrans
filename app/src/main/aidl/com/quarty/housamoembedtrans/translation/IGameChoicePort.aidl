package com.quarty.housamoembedtrans.translation;

import com.quarty.housamoembedtrans.translation.IChoiceBackCallback;
import com.quarty.housamoembedtrans.translation.IStoryPositionListener;

interface IGameChoicePort {
    oneway void requestBack(IChoiceBackCallback callback);
    /** Sends the current value followed by changes; replaces the previous subscription. */
    oneway void subscribePosition(IStoryPositionListener listener);
    /** Uses the same one-shot callback envelope, with StoryOpenResult values. */
    oneway void requestOpenStory(String questLabel, IChoiceBackCallback callback);
    /** Registration-time capability probe; never waits for Unity. */
    int getStoryOpenProtocolVersion();
}
