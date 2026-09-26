package com.quarty.housamoembedtrans.logging;

import android.content.Context;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.List;
import java.util.concurrent.Future;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/** One ordered file writer per process, shared by Java and native logs. */
public final class Log {
    private static final ThreadPoolExecutor WRITER = new ThreadPoolExecutor(
        1, 1, 0L, TimeUnit.MILLISECONDS, new LinkedBlockingQueue<>(1024),
        task -> {
            Thread thread = new Thread(task, "het-log-writer");
            thread.setDaemon(true);
            return thread;
        },
        (task, executor) -> {
            // Backpressure only when disk writing falls behind; never silently
            // discard API body chunks or grow an unbounded memory queue.
            boolean interrupted = false;
            for (;;) {
                try {
                    executor.getQueue().put(task);
                    break;
                } catch (InterruptedException error) {
                    interrupted = true;
                }
            }
            if (interrupted) {
                Thread.currentThread().interrupt();
            }
        });
    private static volatile DailyLogStore store;
    private static IOException writeFailure;

    private Log() {}

    /** Called before HET providers start. */
    public static synchronized void initialize(Context context) {
        initializeDirectory(new File(context.getFilesDir(), "logs"));
    }

    public static synchronized void initializeGame(Context context) {
        initializeDirectory(new File(context.getFilesDir(), "housamo_embed_trans/logs"));
    }

    private static void initializeDirectory(File directory) {
        if (store == null) {
            store = new DailyLogStore(directory);
            i("HET.Log", "Logging started pid=" + android.os.Process.myPid());
        }
    }

    public static void game(String message) {
        i("HousamoTrans", message);
    }

    /** Called by JNI; native already emitted this record to logcat. */
    public static void persistNative(int priority, byte[] utf8) {
        persist(priority, "HousamoTrans", new String(utf8, StandardCharsets.UTF_8));
    }

    public static Future<List<DailyLogStore.Entry>> snapshotRecent() {
        final LocalDate today = LocalDate.now();
        return WRITER.submit(() -> {
            if (store == null) {
                throw new IOException("File logging is not initialized");
            }
            if (writeFailure != null) {
                throw new IOException("Some logs could not be saved", writeFailure);
            }
            return store.snapshot(today, DailyLogStore.DEFAULT_EXPORT_DAYS);
        });
    }

    public static int i(String tag, String message) {
        return write(android.util.Log.INFO, tag, message, null);
    }

    public static int w(String tag, String message) {
        return write(android.util.Log.WARN, tag, message, null);
    }

    public static int w(String tag, String message, Throwable error) {
        return write(android.util.Log.WARN, tag, message, error);
    }

    public static int e(String tag, String message) {
        return write(android.util.Log.ERROR, tag, message, null);
    }

    public static int e(String tag, String message, Throwable error) {
        return write(android.util.Log.ERROR, tag, message, error);
    }

    private static int write(int priority, String tag, String message, Throwable error) {
        String text = String.valueOf(message);
        if (error != null) {
            text += "\n" + android.util.Log.getStackTraceString(error);
        }
        int result = android.util.Log.println(priority, tag, text);
        persist(priority, tag, text);
        return result;
    }

    private static void persist(int priority, String tag, String text) {
        DailyLogStore target = store;
        if (target != null) {
            final String record = "pid=" + android.os.Process.myPid()
                + " tid=" + android.os.Process.myTid() + " " + text;
            final long time = System.currentTimeMillis();
            final String level = priority == android.util.Log.ERROR ? "E"
                : priority == android.util.Log.WARN ? "W"
                : priority == android.util.Log.DEBUG ? "D" : "I";
            WRITER.execute(() -> {
                try {
                    target.append(time, level, tag, record);
                } catch (IOException failure) {
                    writeFailure = failure;
                    android.util.Log.e("HET.Log", "Could not persist log", failure);
                }
            });
        }
    }
}
