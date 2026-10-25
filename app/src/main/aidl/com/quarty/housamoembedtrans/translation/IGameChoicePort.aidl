package com.quarty.housamoembedtrans.translation;

import com.quarty.housamoembedtrans.translation.IChoiceBackCallback;
import com.quarty.housamoembedtrans.translation.IStoryPositionListener;

oneway interface IGameChoicePort {
    void requestBack(IChoiceBackCallback callback);
    /** Sends the current value followed by changes; replaces the previous subscription. */
    void subscribePosition(IStoryPositionListener listener);
}
