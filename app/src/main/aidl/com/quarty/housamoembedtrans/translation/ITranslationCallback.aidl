package com.quarty.housamoembedtrans.translation;

import android.os.ParcelFileDescriptor;

/**
 * Asynchronous result channel from the HET process back to the game process.
 *
 * Large JSON payloads use file descriptors instead of Binder byte arrays so
 * they are not constrained by Binder's transaction-size limit.
 */
oneway interface ITranslationCallback {
    void onQuestPatch(
        String requestId,
        long patchVersion,
        in ParcelFileDescriptor patchFd
    );

    void onSceneCompleted(
        String requestId,
        String scene,
        String targetLang,
        long connectionGeneration,
        in ParcelFileDescriptor resultFd
    );

    void onTranslationFailed(
        String requestId,
        String errorType,
        String message,
        long connectionGeneration
    );
}
