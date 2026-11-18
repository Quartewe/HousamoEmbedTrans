package com.quarty.housamoembedtrans.ui;

import android.content.Context;
import com.quarty.housamoembedtrans.R;
import org.json.JSONArray;
import org.json.JSONObject;
import java.text.DateFormat;
import java.util.Date;
import java.util.Iterator;

/** Read-only presentation of every branch stored in a Scene's continuation records. */
final class ContinuationRecordDialogs {
    private ContinuationRecordDialogs() {}

    static void show(Context context, JSONArray records) {
        UiMaterialAlertDialogBuilder list = new UiMaterialAlertDialogBuilder(context);
        list.setTitle(R.string.continuation_records);
        if (records.length() == 0) {
            list.setMessage(R.string.continuation_no_records);
        } else {
            String[] labels = new String[records.length()];
            for (int i = 0; i < labels.length; i++) {
                JSONObject record = records.optJSONObject(records.length() - 1 - i);
                labels[i] = record.optString("input") + "\n" + timestamp(record);
            }
            list.setItems(labels, (dialog, index) -> detail(context, records,
                records.optJSONObject(records.length() - 1 - index)));
        }
        list.setNegativeButton(android.R.string.cancel, null).show();
    }

    private static String timestamp(JSONObject record) {
        return DateFormat.getDateTimeInstance().format(new Date(record.optLong("created_at")));
    }

    private static void detail(Context context, JSONArray records, JSONObject record) {
        JSONObject generation = record.optJSONObject("generation");
        JSONObject source = record.optJSONObject("source");
        StringBuilder body = new StringBuilder();
        if (source != null) {
            Object position = "original".equals(source.optString("type"))
                ? source.opt("order") : source.optString("record_id") + "  " + source.optString("node_path");
            field(context, body, R.string.continuation_record_source, position);
        }
        field(context, body, R.string.continuation_time, timestamp(record));
        if (generation != null) {
            field(context, body, R.string.continuation_model, generation.optString("model"));
            permission(context, body, generation, "allow_character", R.string.continuation_character);
            permission(context, body, generation, "allow_background", R.string.continuation_background);
            permission(context, body, generation, "allow_choices", R.string.continuation_choices);
            int target = generation.optInt("dialogue_target");
            field(context, body, R.string.continuation_target,
                target == 0 ? context.getString(R.string.continuation_unlimited) : target);
        }
        body.append('\n').append(context.getString(R.string.continuation_input)).append('\n')
            .append(record.optString("input")).append("\n\n")
            .append(context.getString(R.string.continuation_response)).append('\n');
        appendSteps(context, body, record.optJSONArray("steps"), "");
        new UiMaterialAlertDialogBuilder(context)
            .setTitle(R.string.continuation_records).setMessage(body)
            .setNegativeButton(R.string.continuation_records, (d, w) -> show(context, records))
            .setPositiveButton(android.R.string.ok, null).show();
    }

    private static void field(Context context, StringBuilder body, int label, Object value) {
        body.append(context.getString(label)).append(" · ").append(value).append('\n');
    }

    private static void permission(Context context, StringBuilder body, JSONObject generation,
                                   String key, int label) {
        field(context, body, label, context.getString(generation.optBoolean(key)
            ? R.string.settings_summary_on : R.string.settings_summary_off));
    }

    private static void appendSteps(Context context, StringBuilder body, JSONArray steps, String indent) {
        for (int i = 0; steps != null && i < steps.length(); i++) {
            JSONObject step = steps.optJSONObject(i);
            switch (step.optString("type")) {
                case "dialogue":
                    String speaker = step.optString("speaker", "");
                    body.append(indent).append(speaker.isEmpty()
                        ? context.getString(R.string.continuation_narration) : speaker)
                        .append(" · ").append(step.optString("text")).append('\n');
                    break;
                case "stage":
                    body.append(indent).append('[').append(step.optString("action"));
                    Iterator<String> fields = step.keys();
                    while (fields.hasNext()) {
                        String key = fields.next();
                        if (!"type".equals(key) && !"action".equals(key))
                            body.append("  ").append(key).append('=').append(step.opt(key));
                    }
                    body.append("]\n");
                    break;
                case "choice":
                    JSONArray options = step.optJSONArray("options");
                    for (int n = 0; options != null && n < options.length(); n++) {
                        JSONObject option = options.optJSONObject(n);
                        body.append(indent).append(context.getString(R.string.continuation_option, n + 1))
                            .append(" · ").append(option.optString("text")).append('\n');
                        appendSteps(context, body, option.optJSONArray("steps"), indent + "    ");
                    }
                    break;
            }
        }
    }
}
