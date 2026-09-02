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

    /** Grants one durable terminal delivery attempt and returns its lease token. */
    String acquireTerminalDelivery(
        String requestId,
        String terminalKind,
        long connectionGeneration
    );

    /** Completes the exact delivery attempt identified by its lease token. */
    boolean acknowledgeTerminal(
        String requestId,
        String terminalKind,
        String leaseToken,
        long connectionGeneration
    );

    /** Revokes an uncompleted delivery attempt so it can be retried. */
    boolean releaseTerminalDelivery(
        String requestId,
        String terminalKind,
        String leaseToken,
        long connectionGeneration
    );

    void registerGameScenePort(IGameScenePort port);

    void unregisterGameScenePort(IGameScenePort port);

    oneway void reportSceneProductionRejected(String sceneName, int reasonCode);

    /** Returns a bounded JSON envelope through a pipe; never a Binder String. */
    ParcelFileDescriptor previewPendingMove(String kind, String canonicalId);

    /** Publishes and hides one owner, returning the entry envelope through a pipe. */
    ParcelFileDescriptor movePendingProcess(
        String kind,
        String canonicalId,
        String reason
    );

    /** Returns the metadata-only pending list through a pipe. */
    ParcelFileDescriptor listPendingProcesses();

    /** Returns one complete pending entry through a pipe. */
    ParcelFileDescriptor readPendingProcess(String pendingKey);

    /** Restores one pending owner and returns its result envelope through a pipe. */
    ParcelFileDescriptor restorePendingProcess(String pendingKey);

    /** Permanently deletes one pending owner and returns its result envelope through a pipe. */
    ParcelFileDescriptor permanentlyDeletePendingProcess(String pendingKey);
}
