package com.quarty.housamoembedtrans.ui;

import android.os.Handler;
import android.os.Looper;

import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;

import com.quarty.housamoembedtrans.R;
import com.quarty.housamoembedtrans.bridge.ObbResourceDownload;
import com.quarty.housamoembedtrans.bridge.TranslationJobControlClient;
import com.quarty.housamoembedtrans.logging.Log;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Activity-owned, user-triggered resource repair; never occupies the translation queue. */
final class ObbResourceDialog implements AutoCloseable {
    private final Handler main = new Handler(Looper.getMainLooper());
    private final AppCompatActivity activity;
    private final AlertDialog dialog;
    private final TranslationJobControlClient client;
    private final ExecutorService worker = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "HET-obb-download");
        thread.setDaemon(true);
        return thread;
    });
    private ObbResourceDownload download;
    private boolean closed;

    ObbResourceDialog(AppCompatActivity activity) {
        this.activity = activity;
        client = new TranslationJobControlClient(activity);
        dialog = new UiMaterialAlertDialogBuilder(activity)
            .setTitle(R.string.obb_resource_title)
            .setMessage(R.string.obb_resource_description)
            .setPositiveButton(R.string.obb_resource_action, null)
            .setNegativeButton(R.string.settings_rebuild_close, null)
            .create();
        dialog.setOnDismissListener(ignored -> close());
    }

    void show() {
        dialog.show();
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(view -> start());
    }

    private void start() {
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setEnabled(false);
        dialog.setMessage("正在连接 HET 服务…");
        download = new ObbResourceDownload(activity);
        final ObbResourceDownload operation = download;
        try {
            client.bind();
        } catch (RuntimeException error) {
            operation.close();
            complete("无法连接 HET 服务：" + error.getMessage());
            return;
        }
        worker.execute(() -> {
            String message;
            try {
                if (!client.awaitConnected(10_000)) {
                    throw new IllegalStateException("连接 HET 服务超时，请重试");
                }
                message = operation.run(client.getGameObbPort(), status -> main.post(() -> {
                    if (!closed) dialog.setMessage(status);
                }));
            } catch (Exception error) {
                Log.w("HET-OBB", "Resource check/download failed", error);
                message = "资源检查未完成：\n" + (error.getMessage() == null
                    ? error.getClass().getSimpleName() : error.getMessage());
            } finally {
                operation.close();
            }
            final String result = message;
            main.post(() -> complete(result));
        });
    }

    private void complete(String message) {
        if (closed) return;
        dialog.setMessage(message);
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setEnabled(true);
    }

    @Override
    public void close() {
        if (closed) return;
        closed = true;
        if (download != null) download.close();
        worker.shutdownNow();
        client.close();
        main.removeCallbacksAndMessages(null);
        if (dialog.isShowing()) dialog.dismiss();
    }
}
