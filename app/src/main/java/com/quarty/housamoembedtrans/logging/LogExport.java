package com.quarty.housamoembedtrans.logging;

import android.content.Context;
import android.os.ParcelFileDescriptor;
import com.quarty.housamoembedtrans.bridge.TranslationJobControlClient;
import com.quarty.housamoembedtrans.translation.IGameLogPort;
import java.io.IOException;
import java.io.InputStream;
import java.io.InterruptedIOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/** One cancellable export. Owns the service binding and the incoming game pipe. */
public final class LogExport implements AutoCloseable {
    private final TranslationJobControlClient client;
    private boolean closed;
    private ParcelFileDescriptor gamePipe;
    private OutputStream destination;
    private boolean gameIncluded;

    public LogExport(Context context) {
        client = new TranslationJobControlClient(context);
    }

    /** Runs on the export worker; takes ownership of output even if already canceled. */
    public int write(OutputStream output) throws Exception {
        synchronized (this) {
            if (closed) {
                output.close();
                throw new InterruptedIOException("Log export canceled");
            }
            destination = output;
        }
        try (ZipOutputStream zip = new ZipOutputStream(output)) {
            client.bind();
            if (!client.awaitConnected(10_000)) throw new IOException("HET service connection timed out");
            IGameLogPort port = client.getGameLogPort();
            // Take the HET snapshot after connection so connection logs are included.
            List<DailyLogStore.Entry> entries = Log.snapshotRecent().get();
            DailyLogStore.appendZip(entries, zip, "het/");
            int count = entries.size();
            if (port == null) {
                zip.putNextEntry(new ZipEntry("game-unavailable.txt"));
                zip.write("Game logs were not included: game is not connected to HET.\n"
                    .getBytes(StandardCharsets.UTF_8));
                zip.closeEntry();
            } else {
                ParcelFileDescriptor pipe = port.exportRecentLogs();
                if (pipe == null) throw new IOException("Game returned no log stream");
                synchronized (this) {
                    if (closed) {
                        pipe.close();
                        throw new InterruptedIOException("Log export canceled");
                    }
                    gamePipe = pipe;
                }
                try (InputStream input = new ParcelFileDescriptor.AutoCloseInputStream(pipe)) {
                    count += DailyLogStore.appendSnapshotZip(input, zip, "game/");
                    pipe.checkError();
                    gameIncluded = true;
                } finally {
                    synchronized (this) { gamePipe = null; }
                }
            }
            return count;
        }
    }

    public boolean includesGame() {
        return gameIncluded;
    }

    @Override
    public void close() {
        ParcelFileDescriptor pipe;
        OutputStream output;
        synchronized (this) {
            if (closed) return;
            closed = true;
            pipe = gamePipe;
            output = destination;
        }
        // Closing descriptors also wakes blocking I/O, unlike Future.cancel alone.
        if (pipe != null) try { pipe.close(); } catch (IOException ignored) { }
        if (output != null) try { output.close(); } catch (IOException ignored) { }
        client.close();
    }
}
