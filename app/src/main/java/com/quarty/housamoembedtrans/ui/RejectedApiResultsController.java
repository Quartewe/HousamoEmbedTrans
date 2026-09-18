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
import java.util.Locale;
import java.util.concurrent.Executor;

/** Renders archived API results in either task-management surface. */
final class RejectedApiResultsController implements AutoCloseable {

    interface DetailListener {
        void onDetailRequested(JSONObject record);

        void onDetailUnavailable(String recordId);

        void onRecordDeleted(String recordId);

        void onRecordsChanged(int visibleCount, int totalCount);
    }

    private final AppCompatActivity activity;
    private final Executor ioExecutor;
    private final RejectedApiResultStore store;
    private final LinearLayout itemContainer;
    private final TextView summary;
    private final TextView emptyMessage;
    private final DetailListener detailListener;
    private boolean active = true;
    private boolean busy;
    private boolean unifiedPresentation;
    private boolean hasLoadedSnapshot;
    private int refreshGeneration;
    private String searchQuery = "";
    private String pendingOpenRecordId;
    private List<JSONObject> lastRecords = new ArrayList<>();

    RejectedApiResultsController(
        AppCompatActivity activity,
        View root,
        Executor ioExecutor,
        DetailListener detailListener
    ) {
        this.activity = activity;
        this.ioExecutor = ioExecutor;
        this.detailListener = detailListener;
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
                lastRecords = records;
                hasLoadedSnapshot = true;
                render(records);
                if (pendingOpenRecordId != null) {
                    String recordId = pendingOpenRecordId;
                    pendingOpenRecordId = null;
                    boolean found = false;
                    for (JSONObject record : records) {
                        if (recordId.equals(record.optString("record_id", ""))) {
                            found = true;
                            if (detailListener != null) {
                                detailListener.onDetailRequested(record);
                            }
                            break;
                        }
                    }
                    if (!found && detailListener != null) {
                        detailListener.onDetailUnavailable(recordId);
                    }
                }
            });
        });
    }

    boolean hasLoadedSnapshot() {
        return hasLoadedSnapshot;
    }

    void openRecordWhenLoaded(String recordId) {
        if (recordId == null || recordId.trim().isEmpty() || !isActive()) {
            return;
        }
        for (JSONObject record : lastRecords) {
            if (recordId.equals(record.optString("record_id", ""))) {
                if (detailListener != null) {
                    detailListener.onDetailRequested(record);
                }
                return;
            }
        }
        pendingOpenRecordId = recordId;
        refresh();
    }

    void setSearchQuery(String query) {
        searchQuery = query == null ? "" : query.trim();
        if (active) {
            render(lastRecords);
        }
    }

    void setUnifiedPresentation(boolean unified) {
        unifiedPresentation = unified;
        if (unified) {
            summary.setVisibility(View.GONE);
            emptyMessage.setVisibility(View.GONE);
        }
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
        List<JSONObject> visible = new ArrayList<>();
        String normalized = searchQuery.toLowerCase(Locale.ROOT);
        for (JSONObject record : records) {
            String haystack = record.optString("job_kind", "") + " "
                + record.optString("request_id", "") + " "
                + record.optString("reason", "") + " "
                + record.optString("kind", "") + " "
                + record.optString("scene", "") + " "
                + record.optString("scene_name", "") + " "
                + record.optString("name", "") + " "
                + record.optString("subject", "") + " "
                + record.optString("target_language", "") + " "
                + displaySubject(record) + " "
                + displayTargetLanguage(record) + " "
                + String.valueOf(record.opt("payload"));
            if (normalized.isEmpty()
                || haystack.toLowerCase(Locale.ROOT).contains(normalized)) {
                visible.add(record);
            }
        }
        summary.setText(activity.getString(
            R.string.rejected_api_results_count,
            visible.size()
        ));
        if (detailListener != null) {
            detailListener.onRecordsChanged(visible.size(), records.size());
        }
        summary.setVisibility(
            unifiedPresentation || visible.isEmpty() ? View.GONE : View.VISIBLE
        );
        emptyMessage.setText(
            records.isEmpty()
                ? R.string.rejected_api_results_empty
                : R.string.task_waiting_no_results
        );
        emptyMessage.setVisibility(
            unifiedPresentation
                ? View.GONE
                : visible.isEmpty() ? View.VISIBLE : View.GONE
        );

        LayoutInflater inflater = LayoutInflater.from(activity);
        DateFormat dateFormat = DateFormat.getDateTimeInstance(
            DateFormat.MEDIUM,
            DateFormat.SHORT
        );
        for (JSONObject record : visible) {
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

            kind.setText(displaySubject(record));
            request.setText(
                activity.getString(R.string.task_waiting_result_type)
                    + " · " + displayResultKind(record)
                    + " · " + displayJobKind(record)
                    + " · " + displayTargetLanguage(record)
            );
            String reasonValue = record.optString("reason", "").trim();
            reason.setText(reasonValue.isEmpty()
                ? activity.getString(R.string.task_value_unrecorded)
                : reasonValue);
            created.setText(dateFormat.format(new Date(
                record.optLong("created_at", 0L)
            )));
            viewButton.setOnClickListener(view -> {
                if (detailListener != null) {
                    detailListener.onDetailRequested(record);
                }
            });
            card.setOnClickListener(view -> {
                if (detailListener != null) {
                    detailListener.onDetailRequested(record);
                }
            });
            deleteButton.setOnClickListener(view -> confirmDelete(record));
            viewButton.setVisibility(View.GONE);
            deleteButton.setVisibility(View.GONE);
            card.setEnabled(!busy);
            itemContainer.addView(item);
        }
    }

    private String displayJobKind(JSONObject record) {
        String jobKind = record.optString("job_kind", "");
        if ("translation".equals(jobKind)) {
            return activity.getString(R.string.task_tab_translation);
        }
        if ("summary".equals(jobKind)) {
            return activity.getString(R.string.task_tab_summary);
        }
        return activity.getString(R.string.task_value_unrecorded);
    }

    private String displayResultKind(JSONObject record) {
        String kind = record.optString("kind", "").trim();
        if ("legal".equalsIgnoreCase(kind)) {
            return activity.getString(R.string.task_waiting_result_legal);
        }
        if ("illegal".equalsIgnoreCase(kind)) {
            return activity.getString(R.string.task_waiting_result_illegal);
        }
        return kind.isEmpty()
            ? activity.getString(R.string.task_value_unrecorded)
            : kind;
    }

    private String displaySubject(JSONObject record) {
        String subject = firstNonEmpty(
            record.optString("subject", ""),
            record.optString("scene", ""),
            record.optString("scene_name", ""),
            record.optString("name", "")
        );
        JSONObject payload = record.optJSONObject("payload");
        if (payload != null) {
            subject = firstNonEmpty(
                subject,
                payload.optString("subject", ""),
                payload.optString("scene", ""),
                payload.optString("scene_name", ""),
                payload.optString("name", "")
            );
        }
        return subject.isEmpty()
            ? activity.getString(R.string.task_waiting_related_unavailable)
            : subject;
    }

    private String firstNonEmpty(String... values) {
        for (String value : values) {
            if (value != null && !value.trim().isEmpty()) {
                return value.trim();
            }
        }
        return "";
    }

    private String displayTargetLanguage(JSONObject record) {
        String language = record.optString("target_language", "").trim();
        JSONObject payload = record.optJSONObject("payload");
        if (language.isEmpty() && payload != null) {
            language = payload.optString("target_language", "").trim();
        }
        if (language.isEmpty() && payload != null) {
            language = payload.optString("language", "").trim();
        }
        return language.isEmpty()
            ? activity.getString(R.string.task_value_unrecorded)
            : language;
    }

    void deleteRecordFromDetail(JSONObject record) {
        if (record != null) {
            confirmDelete(record);
        }
    }

    private void confirmDelete(JSONObject record) {
        final String recordId = record.optString("record_id", "");
        new UiMaterialAlertDialogBuilder(activity)
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
                    if (detailListener != null) {
                        detailListener.onRecordDeleted(recordId);
                    }
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
