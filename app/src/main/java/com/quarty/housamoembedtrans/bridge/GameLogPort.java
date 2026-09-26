package com.quarty.housamoembedtrans.bridge;

import android.content.Context;
import android.os.ParcelFileDescriptor;
import com.quarty.housamoembedtrans.logging.DailyLogStore;
import com.quarty.housamoembedtrans.logging.Log;
import com.quarty.housamoembedtrans.translation.IGameLogPort;
import java.io.IOException;
import java.io.OutputStream;

/** Game owns its files. At most one export runs, outside Binder and Unity threads. */
final class GameLogPort extends IGameLogPort.Stub {
    private final Context context;
    private boolean closed;
    private Thread worker;
    private ParcelFileDescriptor writer;

    GameLogPort(Context context) {
        this.context = context;
    }

    @Override
    public synchronized ParcelFileDescriptor exportRecentLogs() {
        CallerVerifier.enforceAllowedCaller(context, HetBridgeContract.MODULE_PACKAGE);
        if (closed || worker != null) throw new IllegalStateException("Game log export unavailable or busy");
        ParcelFileDescriptor[] pipe;
        try {
            pipe = ParcelFileDescriptor.createReliablePipe();
        } catch (IOException error) {
            throw new IllegalStateException("Could not open game log export", error);
        }
        writer = pipe[1];
        worker = new Thread(() -> {
            OutputStream output = new ParcelFileDescriptor.AutoCloseOutputStream(pipe[1]);
            try {
                DailyLogStore.writeSnapshot(Log.snapshotRecent().get(), output);
            } catch (Exception error) {
                if (error instanceof InterruptedException) Thread.currentThread().interrupt();
                try { pipe[1].closeWithError("Game log export failed: " + error); }
                catch (IOException ignored) { }
                Log.w("HousamoTrans", "Game log export failed", error);
            } finally {
                try { output.close(); } catch (IOException ignored) { }
                synchronized (GameLogPort.this) {
                    writer = null;
                    worker = null;
                }
            }
        }, "het-game-log-export");
        worker.setDaemon(true);
        try {
            worker.start();
        } catch (RuntimeException | Error error) {
            try { pipe[0].close(); } catch (IOException ignored) { }
            try { pipe[1].close(); } catch (IOException ignored) { }
            writer = null;
            worker = null;
            throw error;
        }
        return pipe[0];
    }

    synchronized void close() {
        closed = true;
        if (worker != null) worker.interrupt();
        if (writer != null) {
            try { writer.closeWithError("Game log port closed"); }
            catch (IOException ignored) { }
        }
    }
}
