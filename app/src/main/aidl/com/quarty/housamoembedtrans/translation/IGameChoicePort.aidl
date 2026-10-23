package com.quarty.housamoembedtrans.translation;

import com.quarty.housamoembedtrans.translation.IChoiceBackCallback;

oneway interface IGameChoicePort {
    void requestBack(IChoiceBackCallback callback);
}
