package com.quarty.housamoembedtrans.ui;

import com.quarty.housamoembedtrans.R;
import com.quarty.housamoembedtrans.provider.RejectedApiResultStore;

import android.view.LayoutInflater;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

import com.google.android.material.button.MaterialButton;
import com.google.android.material.card.MaterialCardView;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;

import org.json.JSONObject;

import java.io.File;
import java.text.DateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.concurrent.Executor;

/** Renders archived API results in either task-management surface. */
final class RejectedApiResultsController implements AutoCloseable {

    private final AppCompatActivity activity;
    private final Executor ioExecutor;
    private final RejectedApiResultStore store;
    private final LinearLayout itemContainer;
    private final TextView summary;
    private final TextView emptyMessage;
    private boolean active = true;
    private boolean busy;
    private int refreshGeneration;

    RejectedApiResultsController(
        AppCompatActivity activity,
        View root,
        Executor ioExecutor
    ) {
        this.activity = activity;
        this.ioExecutor = ioExecutor;
        store = RejectedApiResultStore.createForAndroid(
            new File(activity.getFilesDir(), RejectedApiResultStore.DIRECTORY_NAME)
        );
        itemContainer = root.findViewById(R.id.rejected_api_results_items);
        summary = root.findViewById(R.id.tv_rejected_api_results_summary);
        emptyMessage = root.findViewById(R.id.tv_rejected_api_results_empty);
    }

    void setActive(boolean active) {
        this.active = active;
        if (!active) {
            refreshGeneration++;
        }
    }

    void refresh() {
        if (!isActive()) {
            return;
        }
        final int generation = ++refreshGeneration;
        ioExecutor.execute(() -> {
            List<String> recordIds;
            try {
                recordIds = new ArrayList<>(store.listRecordIds());
            } catch (RuntimeException error) {
                activity.runOnUiThread(() -> showFailure(error));
                return;
            }
            final List<JSONObject> records = new ArrayList<>();
            for (String recordId : recordIds) {
                try {
                    records.add(store.read(recordId));
                } catch (Exception ignored) {
                    // Keep the archive manual-only; unreadable records are not
                    // silently deleted.
                }
            }
            activity.runOnUiThread(() -> {
                if (!isActive() || generation != refreshGeneration) {
                    return;
                }
                render(records);
            });
        });
    }

    @Override
    public void close() {
        setActive(false);
    }

    private boolean isActive() {
        return active && !activity.isDestroyed() && !activity.isFinishing();
    }

    private void render(List<JSONObject> records) {
        itemContainer.removeAllViews();
        summary.setText(activity.getString(
            R.string.rejected_api_results_count,
            records.size()
        ));
        summary.setVisibility(records.isEmpty() ? View.GONE : View.VISIBLE);
        emptyMessage.setVisibility(records.isEmpty() ? View.VISIBLE : View.GONE);

        LayoutInflater inflater = LayoutInflater.from(activity);
        DateFormat dateFormat = DateFormat.getDateTimeInstance(
            DateFormat.MEDIUM,
            DateFormat.SHORT
        );
        for (JSONObject record : records) {
            View item = inflater.inflate(
                R.layout.item_rejected_api_result,
                itemContainer,
                false
            );
            MaterialCardView card = item.findViewById(
                R.id.card_rejected_api_result
            );
            TextView kind = item.findViewById(
                R.id.tv_rejected_api_result_kind
            );
            TextView request = item.findViewById(
                R.id.tv_rejected_api_result_request
            );
            TextView reason = item.findViewById(
                R.id.tv_rejected_api_result_reason
            );
            TextView created = item.findViewById(
                R.id.tv_rejected_api_result_created
            );
            MaterialButton viewButton = item.findViewById(
                R.id.btn_view_rejected_api_result
            );
            MaterialButton deleteButton = item.findViewById(
                R.id.btn_delete_rejected_api_result
            );

            kind.setText(activity.getString(
                R.string.rejected_api_result_kind_line,
                record.optString("job_kind", ""),
                record.optString("kind", "")
            ));
            request.setText(activity.getString(
                R.string.rejected_api_result_request_line,
                record.optString("request_id", "")
            ));
            reason.setText(activity.getString(
                R.string.rejected_api_result_reason_line,
                record.optString("reason", "")
            ));
            created.setText(dateFormat.format(new Date(
                record.optLong("created_at", 0L)
            )));
            viewButton.setOnClickListener(view -> showPayload(record));
            deleteButton.setOnClickListener(view -> confirmDelete(record));
            card.setEnabled(!busy);
            viewButton.setEnabled(!busy);
            deleteButton.setEnabled(!busy);
            itemContainer.addView(item);
        }
    }

    private void showPayload(JSONObject record) {
        Object payload = record.opt("payload");
        String text = payload == null
            ? activity.getString(R.string.rejected_api_result_empty_payload)
            : payload.toString();
        if (text.length() > 256 * 1024) {
            text = text.substring(0, 256 * 1024)
                + "\n…\n"
                + activity.getString(R.string.rejected_api_result_truncated);
        }
        new MaterialAlertDialogBuilder(activity)
            .setTitle(R.string.rejected_api_result_view_payload)
            .setMessage(text)
            .setPositiveButton(android.R.string.ok, null)
            .show();
    }

    private void confirmDelete(JSONObject record) {
        final String recordId = record.optString("record_id", "");
        new MaterialAlertDialogBuilder(activity)
            .setTitle(R.string.rejected_api_result_delete_title)
            .setMessage(R.string.rejected_api_result_delete_message)
            .setNegativeButton(R.string.cancel_action, null)
            .setPositiveButton(
                R.string.rejected_api_result_delete,
                (dialog, which) -> deleteRecord(recordId)
            )
            .show();
    }

    private void deleteRecord(String recordId) {
        if (!isActive() || busy) {
            return;
        }
        busy = true;
        ioExecutor.execute(() -> {
            try {
                store.delete(recordId);
                activity.runOnUiThread(() -> {
                    busy = false;
                    if (!isActive()) {
                        return;
                    }
                    Toast.makeText(
                        activity,
                        R.string.rejected_api_result_deleted,
                        Toast.LENGTH_SHORT
                    ).show();
                    refresh();
                });
            } catch (Exception error) {
                activity.runOnUiThread(() -> {
                    busy = false;
                    if (!isActive()) {
                        return;
                    }
                    showFailure(error);
                });
            }
        });
    }

    private void showFailure(Throwable error) {
        if (!isActive()) {
            return;
        }
        Toast.makeText(
            activity,
            activity.getString(
                R.string.rejected_api_results_load_failed,
                safeMessage(error)
            ),
            Toast.LENGTH_LONG
        ).show();
    }

    private static String safeMessage(Throwable error) {
        String message = error.getMessage();
        return message == null || message.trim().isEmpty()
            ? error.getClass().getSimpleName()
            : message;
    }
}
