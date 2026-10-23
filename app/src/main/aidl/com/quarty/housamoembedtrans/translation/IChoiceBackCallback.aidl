package com.quarty.housamoembedtrans.translation;

/** One terminal result for one choice-back intent. */
oneway interface IChoiceBackCallback {
    void onCompleted(int result);
}
