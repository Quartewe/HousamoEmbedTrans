package com.quarty.housamoembedtrans.ui;

import android.os.Handler;
import android.os.Looper;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ProgressBar;
import android.widget.TextView;

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
    private final TextView statusView;
    private final ProgressBar progressBar;
    private final TextView progressLabel;
    private AlertDialog exitConfirmation;
    private boolean running;
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
        View content = activity.getLayoutInflater().inflate(R.layout.dialog_obb_resource, null);
        statusView = content.findViewById(R.id.obb_status);
        progressBar = content.findViewById(R.id.obb_progress);
        progressLabel = content.findViewById(R.id.obb_progress_label);
        float density = activity.getResources().getDisplayMetrics().density;
        // Keep status scrollable and leave space for actions on short screens.
        content.findViewById(R.id.obb_status_scroll).getLayoutParams().height =
            Math.min(Math.round(240 * density),
                activity.getResources().getDisplayMetrics().heightPixels / 3);
        dialog = new UiMaterialAlertDialogBuilder(activity)
            .setTitle(R.string.obb_resource_title)
            .setView(content)
            .setPositiveButton(R.string.obb_resource_action, null)
            .setNegativeButton(R.string.obb_resource_close, null)
            .create();
        dialog.setCancelable(false);
        dialog.setCanceledOnTouchOutside(false);
        dialog.setOnKeyListener((ignored, keyCode, event) -> {
            if (keyCode != KeyEvent.KEYCODE_BACK) return false;
            if (event.getAction() == KeyEvent.ACTION_UP && !event.isCanceled()) requestClose();
            return true;
        });
        if (dialog.getWindow() != null) {
            int width = Math.min(Math.round(560 * density),
                activity.getResources().getDisplayMetrics().widthPixels - Math.round(32 * density));
            dialog.getWindow().setLayout(width, ViewGroup.LayoutParams.WRAP_CONTENT);
        }
        dialog.setOnDismissListener(ignored -> close());
    }

    void show() {
        dialog.show();
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(view -> start());
        dialog.getButton(AlertDialog.BUTTON_NEGATIVE).setOnClickListener(view -> requestClose());
    }

    private void requestClose() {
        if (closed) return;
        if (!running) {
            close();
            return;
        }
        if (exitConfirmation != null) return;
        exitConfirmation = new UiMaterialAlertDialogBuilder(activity)
            .setTitle(R.string.obb_resource_exit_title)
            .setMessage(R.string.obb_resource_exit_message)
            .setNegativeButton(R.string.obb_resource_continue, null)
            .setPositiveButton(R.string.obb_resource_exit, (ignored, which) -> close())
            .create();
        exitConfirmation.setOnDismissListener(ignored -> exitConfirmation = null);
        exitConfirmation.show();
    }

    private void showProgress(String message, long received, long total) {
        if (closed) return;
        statusView.setText(message);
        progressBar.setVisibility(View.VISIBLE);
        progressLabel.setVisibility(View.VISIBLE);
        progressBar.setIndeterminate(total <= 0);
        if (total > 0) {
            int percent = (int) Math.min(100, Math.max(0, received * 100.0 / total));
            progressBar.setProgress(percent);
            progressLabel.setText(activity.getString(R.string.obb_resource_percent, percent));
        } else {
            progressLabel.setText(R.string.obb_resource_working);
        }
    }

    private void start() {
        if (closed || running) return;
        running = true;
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setEnabled(false);
        showProgress("正在连接 HET 服务…", 0, -1);
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
                message = operation.run(client.getGameObbPort(), (status, received, total) ->
                    main.post(() -> showProgress(status, received, total)));
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
        running = false;
        if (exitConfirmation != null) exitConfirmation.dismiss();
        statusView.setText(message);
        progressBar.setVisibility(View.GONE);
        progressLabel.setVisibility(View.GONE);
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setEnabled(true);
    }

    @Override
    public void close() {
        if (closed) return;
        closed = true;
        running = false;
        if (exitConfirmation != null) exitConfirmation.dismiss();
        if (download != null) download.close();
        worker.shutdownNow();
        client.close();
        main.removeCallbacksAndMessages(null);
        if (dialog.isShowing()) dialog.dismiss();
    }
}
