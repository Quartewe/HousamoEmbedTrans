package com.quarty.housamoembedtrans.translation;

import android.os.ParcelFileDescriptor;
import com.quarty.housamoembedtrans.translation.IGameScenePort;
import com.quarty.housamoembedtrans.translation.ITranslationCallback;

interface ITranslationService {
    int getProtocolVersion();

    int enqueueTranslation(
        String requestId,
        in ParcelFileDescriptor requestFd,
        boolean overwrite
    );

    /** Requests idempotent user cancellation of one ordinary Translation Job. */
    int cancelTranslation(String requestId);

    /** Registers the one terminal callback owned by the current connection. */
    void registerTranslationCallback(ITranslationCallback callback);

    /** Removes the current terminal callback when this connection closes. */
    void unregisterTranslationCallback(ITranslationCallback callback);

    /** True only while the durable terminal outcome still requires delivery. */
    boolean preflightTerminal(String requestId, String terminalKind);

    /** Synchronous, idempotent delivery acknowledgement. */
    boolean acknowledgeTerminal(String requestId, String terminalKind);

    void registerGameScenePort(IGameScenePort port);

    void unregisterGameScenePort(IGameScenePort port);

    oneway void reportSceneProductionRejected(String sceneName, int reasonCode);
}
