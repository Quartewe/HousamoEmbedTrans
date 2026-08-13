package com.quarty.housamoembedtrans.translation;

import android.os.ParcelFileDescriptor;

/**
 * Connection-scoped Scene mirror port implemented by the game process.
 * Scene bodies and final apply outcomes travel through the supplied pipes,
 * never through a Binder Bundle or translation callback.
 */
interface IGameScenePort {
    ParcelFileDescriptor exportSceneSnapshot();

    boolean applySceneChanges(
        in ParcelFileDescriptor requestReadFd,
        in ParcelFileDescriptor resultWriteFd
    );
}
