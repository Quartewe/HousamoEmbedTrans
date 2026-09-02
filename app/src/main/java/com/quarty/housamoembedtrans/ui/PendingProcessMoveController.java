package com.quarty.housamoembedtrans.ui;

import com.quarty.housamoembedtrans.R;
import com.quarty.housamoembedtrans.bridge.HetBridgeContract;
import com.quarty.housamoembedtrans.management.pending.PendingProcessControlClient;
import com.quarty.housamoembedtrans.translation.TranslationService;

import android.content.Intent;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;

import org.json.JSONObject;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/** Shared structured entry for moving a known management object to Pending. */
public final class PendingProcessMoveController implements AutoCloseable {
    public static final String REASON_USER_REQUESTED = "user_requested";

    private final AppCompatActivity activity;
    private final PendingProcessControlClient client;
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final AtomicBoolean busy = new AtomicBoolean();
    private final AtomicBoolean closed = new AtomicBoolean();

    public PendingProcessMoveController(AppCompatActivity activity) {
        if (activity == null) {
            throw new IllegalArgumentException("activity is required");
        }
        this.activity = activity;
        this.client = new PendingProcessControlClient(activity);
        try {
            Intent intent = new Intent(activity, TranslationService.class)
                .setPackage(activity.getPackageName())
                .setAction(
                    HetBridgeContract.ACTION_START_TRANSLATION_SERVICE
                );
            ContextCompat.startForegroundService(activity, intent);
            client.bind();
        } catch (RuntimeException failure) {
            showFailure(failure);
        }
    }

    /**
     * Previews and confirms one exact kind/id pair. The callback runs on the UI
     * thread after the Service has completed the durable move.
     */
    public void confirmMove(
        String kind,
        String canonicalId,
        String displayName,
        Runnable onMoved
    ) {
        confirmMove(kind, canonicalId, displayName, onMoved, null);
    }

    /**
     * Variant for callers that have a page-level busy/lifecycle flag.  The
     * completion callback runs on the UI thread for success, failure, cancel,
     * and a preview that cannot be shown, so a caller never remains locked
     * after this controller's single-flight operation ends.
     */
    public void confirmMove(
        String kind,
        String canonicalId,
        String displayName,
        Runnable onMoved,
        Runnable onFinished
    ) {
        confirmMove(
            kind,
            canonicalId,
            displayName,
            REASON_USER_REQUESTED,
            onMoved,
            onFinished
        );
    }

    /**
     * Variant that preserves a stable source reason (for example
     * scene_missing/scene_invalid) in the durable Pending entry.
     */
    public void confirmMove(
        String kind,
        String canonicalId,
        String displayName,
        String reason,
        Runnable onMoved,
        Runnable onFinished
    ) {
        if (closed.get() || activity.isFinishing() || activity.isDestroyed()) {
            return;
        }
        if (kind == null || kind.isEmpty()
            || canonicalId == null || canonicalId.isEmpty()) {
            showFailure(new IllegalArgumentException(
                "PendingProcess target identity is missing"
            ));
            notifyFinished(onFinished);
            return;
        }
        if (!busy.compareAndSet(false, true)) {
            return;
        }
        if (!client.isConnected()) {
            busy.set(false);
            showFailure(new IllegalStateException(
                "TranslationService is not connected"
            ));
            notifyFinished(onFinished);
            return;
        }
        executor.execute(() -> {
            try {
                JSONObject preview = client.previewPendingMove(
                    kind,
                    canonicalId
                );
                String impact;
                try {
                    impact = preview.toString(2);
                } catch (Exception ignored) {
                    impact = preview.toString();
                }
                showConfirmation(
                    kind,
                    canonicalId,
                    normalizedLabel(displayName, canonicalId),
                    impact,
                    reason,
                    onMoved,
                    onFinished
                );
            } catch (Exception failure) {
                busy.set(false);
                showFailure(failure);
                notifyFinished(onFinished);
            }
        });
    }

    private void showConfirmation(
        String kind,
        String canonicalId,
        String displayName,
        String impact,
        String reason,
        Runnable onMoved,
        Runnable onFinished
    ) {
        activity.runOnUiThread(() -> {
            if (!isUiActive()) {
                busy.set(false);
                notifyFinished(onFinished);
                return;
            }
            new MaterialAlertDialogBuilder(activity)
                .setTitle(activity.getString(
                    R.string.pending_process_move_object_title,
                    displayName
                ))
                .setMessage(activity.getString(
                    R.string.pending_process_move_object_message,
                    impact
                ))
                .setNegativeButton(
                    R.string.cancel_action,
                    (dialog, which) -> {
                        busy.set(false);
                        notifyFinished(onFinished);
                    }
                )
                .setPositiveButton(
                    R.string.pending_process_move,
                    (dialog, which) -> performMove(
                        kind,
                        canonicalId,
                        reason,
                        onMoved,
                        onFinished
                    )
                )
                .setOnCancelListener(dialog -> {
                    busy.set(false);
                    notifyFinished(onFinished);
                })
                .show();
        });
    }

    private void performMove(
        String kind,
        String canonicalId,
        String reason,
        Runnable onMoved,
        Runnable onFinished
    ) {
        executor.execute(() -> {
            try {
                client.movePendingProcess(
                    kind,
                    canonicalId,
                    reason == null || reason.trim().isEmpty()
                        ? REASON_USER_REQUESTED
                        : reason
                );
                busy.set(false);
                activity.runOnUiThread(() -> {
                    if (!isUiActive()) {
                        return;
                    }
                    Toast.makeText(
                        activity,
                        R.string.pending_process_moved,
                        Toast.LENGTH_SHORT
                    ).show();
                     if (onMoved != null) {
                         onMoved.run();
                     }
                     notifyFinished(onFinished);
                 });
            } catch (Exception failure) {
                busy.set(false);
                showFailure(failure);
                notifyFinished(onFinished);
            }
        });
    }

    private void notifyFinished(Runnable onFinished) {
        if (onFinished == null) {
            return;
        }
        activity.runOnUiThread(onFinished);
    }

    private void showFailure(Throwable failure) {
        activity.runOnUiThread(() -> {
            if (!isUiActive()) {
                return;
            }
            Toast.makeText(
                activity,
                activity.getString(
                    R.string.pending_process_operation_failed,
                    safeMessage(failure)
                ),
                Toast.LENGTH_LONG
            ).show();
        });
    }

    private boolean isUiActive() {
        return !closed.get()
            && !activity.isFinishing()
            && !activity.isDestroyed();
    }

    private static String normalizedLabel(String label, String fallback) {
        return label == null || label.trim().isEmpty() ? fallback : label;
    }

    private static String safeMessage(Throwable failure) {
        if (failure == null) {
            return "operation_failed";
        }
        String message = failure.getMessage();
        return message == null || message.trim().isEmpty()
            ? failure.getClass().getSimpleName()
            : message;
    }

    @Override
    public void close() {
        if (!closed.compareAndSet(false, true)) {
            return;
        }
        client.close();
        executor.shutdownNow();
    }
}
