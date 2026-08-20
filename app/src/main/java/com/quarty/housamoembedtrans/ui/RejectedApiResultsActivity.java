package com.quarty.housamoembedtrans.ui;

import com.quarty.housamoembedtrans.R;
import com.quarty.housamoembedtrans.storage.RejectedApiResultStore;

import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

import com.google.android.material.appbar.MaterialToolbar;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.card.MaterialCardView;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;

import org.json.JSONObject;

import java.io.File;
import java.text.DateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Lists archived API results that lost write-back eligibility. Manual delete only. */
public final class RejectedApiResultsActivity extends AppCompatActivity {

    private final ExecutorService ioExecutor =
        Executors.newSingleThreadExecutor();
    private RejectedApiResultStore store;
    private LinearLayout itemContainer;
    private TextView summary;
    private TextView emptyMessage;
    private boolean busy;
    private int refreshGeneration;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_rejected_api_results);
        SystemBarInsets.apply(findViewById(R.id.root_rejected_api_results));

        store = RejectedApiResultStore.createForAndroid(
            new File(getFilesDir(), RejectedApiResultStore.DIRECTORY_NAME)
        );
        itemContainer = findViewById(R.id.rejected_api_results_items);
        summary = findViewById(R.id.tv_rejected_api_results_summary);
        emptyMessage = findViewById(R.id.tv_rejected_api_results_empty);

        MaterialToolbar toolbar = findViewById(
            R.id.toolbar_rejected_api_results
        );
        toolbar.setNavigationOnClickListener(view -> finish());

        refreshRecords();
    }

    @Override
    protected void onResume() {
        super.onResume();
        refreshRecords();
    }

    @Override
    protected void onDestroy() {
        ioExecutor.shutdownNow();
        super.onDestroy();
    }

    private void refreshRecords() {
        final int generation = ++refreshGeneration;
        ioExecutor.execute(() -> {
            List<String> recordIds;
            try {
                recordIds = new ArrayList<>(store.listRecordIds());
            } catch (RuntimeException error) {
                runOnUiThread(() -> showFailure(error));
                return;
            }
            final List<JSONObject> records = new ArrayList<>();
            for (String recordId : recordIds) {
                try {
                    records.add(store.read(recordId));
                } catch (Exception ignored) {
                    // Skip unreadable records; deletion is manual and the
                    // store is intentionally not auto-pruned.
                }
            }
            runOnUiThread(() -> {
                if (isDestroyed() || isFinishing()
                    || generation != refreshGeneration) {
                    return;
                }
                render(records);
            });
        });
    }

    private void render(List<JSONObject> records) {
        itemContainer.removeAllViews();
        summary.setText(getString(
            R.string.rejected_api_results_count,
            records.size()
        ));
        summary.setVisibility(records.isEmpty() ? View.GONE : View.VISIBLE);
        emptyMessage.setVisibility(records.isEmpty() ? View.VISIBLE : View.GONE);

        LayoutInflater inflater = LayoutInflater.from(this);
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

            kind.setText(getString(
                R.string.rejected_api_result_kind_line,
                record.optString("job_kind", ""),
                record.optString("kind", "")
            ));
            request.setText(getString(
                R.string.rejected_api_result_request_line,
                record.optString("request_id", "")
            ));
            reason.setText(getString(
                R.string.rejected_api_result_reason_line,
                record.optString("reason", "")
            ));
            created.setText(dateFormat.format(new Date(
                record.optLong("created_at", 0L)
            )));
            viewButton.setOnClickListener(view ->
                showPayload(record)
            );
            deleteButton.setOnClickListener(view ->
                confirmDelete(record)
            );
            card.setEnabled(!busy);
            viewButton.setEnabled(!busy);
            deleteButton.setEnabled(!busy);
            itemContainer.addView(item);
        }
    }

    private void showPayload(JSONObject record) {
        Object payload = record.opt("payload");
        String text = payload == null
            ? getString(R.string.rejected_api_result_empty_payload)
            : payload.toString();
        if (text.length() > 256 * 1024) {
            text = text.substring(0, 256 * 1024)
                + "\n…\n"
                + getString(R.string.rejected_api_result_truncated);
        }
        new MaterialAlertDialogBuilder(this)
            .setTitle(R.string.rejected_api_result_view_payload)
            .setMessage(text)
            .setPositiveButton(android.R.string.ok, null)
            .show();
    }

    private void confirmDelete(JSONObject record) {
        final String recordId = record.optString("record_id", "");
        new MaterialAlertDialogBuilder(this)
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
        if (busy) {
            return;
        }
        busy = true;
        ioExecutor.execute(() -> {
            try {
                store.delete(recordId);
                runOnUiThread(() -> {
                    if (isDestroyed()) {
                        return;
                    }
                    busy = false;
                    Toast.makeText(
                        this,
                        R.string.rejected_api_result_deleted,
                        Toast.LENGTH_SHORT
                    ).show();
                    refreshRecords();
                });
            } catch (Exception error) {
                runOnUiThread(() -> {
                    if (isDestroyed()) {
                        return;
                    }
                    busy = false;
                    showFailure(error);
                });
            }
        });
    }

    private void showFailure(Throwable error) {
        if (isDestroyed() || isFinishing()) {
            return;
        }
        Toast.makeText(
            this,
            getString(
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
