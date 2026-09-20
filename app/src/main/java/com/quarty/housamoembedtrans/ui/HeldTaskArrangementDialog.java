package com.quarty.housamoembedtrans.ui;

import android.app.Activity;
import android.content.Intent;
import android.view.Gravity;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;
import androidx.appcompat.app.AlertDialog;
import androidx.core.content.ContextCompat;
import com.google.android.material.button.MaterialButton;
import com.quarty.housamoembedtrans.R;
import com.quarty.housamoembedtrans.bridge.HetBridgeContract;
import com.quarty.housamoembedtrans.runtime.TranslationStatusNotification;
import com.quarty.housamoembedtrans.translation.TranslationService;
import com.quarty.housamoembedtrans.translation.job.TranslationJobStore;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.concurrent.Executor;

/** Shared Scene-order popup for the home entry and held-task page. */
final class HeldTaskArrangementDialog {
    private HeldTaskArrangementDialog() { }

    static LinkedHashMap<String, List<String>> scenes(Activity activity) {
        LinkedHashMap<String, List<String>> result = new LinkedHashMap<>();
        for (TranslationJobStore.HeldQueuedJob job :
                TranslationJobStore.getInstance(activity).getHeldQueuedJobs()) {
            List<String> ids = result.get(job.getScene());
            if (ids == null) {
                ids = new ArrayList<>();
                result.put(job.getScene(), ids);
            }
            ids.add(job.getRequestId());
        }
        return result;
    }

    static void submitScene(Activity activity, Executor executor, String scene,
            Runnable finished) {
        List<String> ids = scenes(activity).get(scene);
        submit(activity, executor, ids == null ? Collections.emptyList() : ids, finished);
    }

    static void show(Activity activity, Executor executor, Runnable changed) {
        LinkedHashMap<String, List<String>> scenes = scenes(activity);
        ArrayList<String> order = new ArrayList<>(scenes.keySet());
        if (order.isEmpty()) {
            Toast.makeText(activity, R.string.held_arrangement_empty, Toast.LENGTH_SHORT).show();
            changed.run();
            return;
        }
        LinearLayout rows = new LinearLayout(activity);
        rows.setOrientation(LinearLayout.VERTICAL);
        int padding = (int) (16 * activity.getResources().getDisplayMetrics().density);
        rows.setPadding(padding, 0, padding, 0);
        ScrollView scroll = new ScrollView(activity);
        scroll.addView(rows);
        AlertDialog dialog = new UiMaterialAlertDialogBuilder(activity)
            .setTitle(R.string.held_arrangement_title)
            .setView(scroll)
            .setNegativeButton(R.string.cancel_action, null)
            .setPositiveButton(R.string.held_arrangement_submit_all, null)
            .create();
        Runnable render = new Runnable() {
            @Override public void run() {
                rows.removeAllViews();
                for (int index = 0; index < order.size(); index++) {
                    final int position = index;
                    LinearLayout row = new LinearLayout(activity);
                    row.setGravity(Gravity.CENTER_VERTICAL);
                    TextView name = new TextView(activity);
                    name.setText(order.get(index));
                    name.setTextSize(16);
                    name.setTextColor(ContextCompat.getColor(activity, R.color.het_on_surface));
                    row.addView(name, new LinearLayout.LayoutParams(0,
                        ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
                    for (int direction : new int[] {-1, 1}) {
                        MaterialButton move = new MaterialButton(activity, null,
                            com.google.android.material.R.attr.materialButtonOutlinedStyle);
                        move.setText(direction < 0 ? "↑" : "↓");
                        move.setContentDescription(activity.getString(direction < 0
                            ? R.string.held_arrangement_up : R.string.held_arrangement_down,
                            order.get(index)));
                        move.setMinWidth(0);
                        move.setMinimumWidth(0);
                        move.setEnabled(position + direction >= 0
                            && position + direction < order.size());
                        move.setOnClickListener(view -> {
                            Collections.swap(order, position, position + direction);
                            run();
                        });
                        row.addView(move);
                    }
                    rows.addView(row);
                }
            }
        };
        render.run();
        dialog.setOnShowListener(ignored -> dialog.getButton(AlertDialog.BUTTON_POSITIVE)
            .setOnClickListener(view -> {
                ArrayList<String> ids = new ArrayList<>();
                for (String scene : order) ids.addAll(scenes.get(scene));
                dialog.setCancelable(false);
                dialog.getButton(AlertDialog.BUTTON_POSITIVE).setEnabled(false);
                dialog.getButton(AlertDialog.BUTTON_NEGATIVE).setEnabled(false);
                submit(activity, executor, ids, () -> {
                    dialog.dismiss();
                    changed.run();
                });
            }));
        dialog.show();
    }

    private static void submit(Activity activity, Executor executor, List<String> ids,
            Runnable finished) {
        ArrayList<String> selected = new ArrayList<>(ids);
        TranslationJobStore store = TranslationJobStore.getInstance(activity);
        executor.execute(() -> {
            String failure = null;
            try {
                store.submitHeldQueueOrder(selected);
            } catch (Exception error) {
                failure = error.getMessage();
                if (failure == null) failure = error.getClass().getSimpleName();
            }
            final String error = failure;
            activity.runOnUiThread(() -> {
                if (activity.isFinishing() || activity.isDestroyed()) return;
                if (error == null) {
                    try {
                        ContextCompat.startForegroundService(activity,
                            new Intent(activity, TranslationService.class)
                                .setPackage(activity.getPackageName())
                                .setAction(HetBridgeContract.ACTION_START_TRANSLATION_SERVICE));
                        Toast.makeText(activity, R.string.held_arrangement_submitted,
                            Toast.LENGTH_SHORT).show();
                    } catch (RuntimeException unavailable) {
                        Toast.makeText(activity, R.string.translation_job_service_unavailable,
                            Toast.LENGTH_LONG).show();
                    }
                    TranslationStatusNotification.refresh(activity);
                } else {
                    Toast.makeText(activity, activity.getString(
                        R.string.held_arrangement_failed, error), Toast.LENGTH_LONG).show();
                }
                finished.run();
            });
        });
    }
}
