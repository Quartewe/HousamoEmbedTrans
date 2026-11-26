package com.quarty.housamoembedtrans;

import android.app.Application;
import android.os.Handler;
import android.os.Looper;

import com.quarty.housamoembedtrans.runtime.GameEntry;

import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;

/** Called by the bootstrap's attached worker, never by the traced main thread. */
public final class RootEntry {
    private RootEntry() {}

    public static int start(Application application, String modulePath) throws Exception {
        FutureTask<Integer> entry = new FutureTask<>(() -> {
            int disposition = GameEntry.rootDisposition();
            if (disposition != 0) return disposition;
            MainHook.start(application, modulePath, "root", true);
            return GameEntry.rootDisposition() == 4 ? 4 : 2;
            // Java handoff only; existing game ports report actual readiness.
        });
        if (!new Handler(Looper.getMainLooper()).post(entry)) {
            throw new IllegalStateException("Game main Looper is exiting");
        }
        try {
            return entry.get(15, TimeUnit.SECONDS);
        } catch (Exception failure) {
            entry.cancel(false);
            throw failure;
        }
    }
}
