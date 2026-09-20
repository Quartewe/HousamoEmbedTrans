package com.quarty.housamoembedtrans.ui;

import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;
import androidx.appcompat.app.AppCompatActivity;
import com.google.android.material.appbar.MaterialToolbar;
import com.google.android.material.button.MaterialButton;
import com.quarty.housamoembedtrans.R;
import com.quarty.housamoembedtrans.storage.config.ConfigStore;
import com.quarty.housamoembedtrans.storage.config.CharacterDictionaryUpdates;
import org.json.JSONObject;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Dictionary mode of the existing two-sided Scene conflict screen; no game service. */
final class CharacterDictionaryConflictScreen {
    private final AppCompatActivity activity;
    private final ConfigStore store;
    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private boolean closed;
    private boolean busy;

    CharacterDictionaryConflictScreen(AppCompatActivity activity) {
        this.activity = activity;
        store = new ConfigStore(activity);
        MaterialToolbar toolbar = activity.findViewById(R.id.toolbar_scene_conflicts);
        toolbar.setTitle(R.string.chardict_conflicts_title);
        toolbar.setNavigationOnClickListener(v -> activity.getOnBackPressedDispatcher().onBackPressed());
        ((TextView) activity.findViewById(R.id.tv_scene_conflicts_intro))
            .setText(R.string.chardict_conflicts_intro);
        activity.findViewById(R.id.tv_scene_conflicts_runtime_status).setVisibility(View.GONE);
        reload();
    }

    private void reload() {
        busy = true;
        ((TextView) activity.findViewById(R.id.tv_scene_conflicts_summary))
            .setText(R.string.scene_conflicts_loading);
        io.execute(() -> {
            try {
                List<CharacterDictionaryUpdates.Conflict> rows = store.characterDictionaryConflicts();
                activity.runOnUiThread(() -> { if (!closed) render(rows); });
            } catch (Exception e) {
                activity.runOnUiThread(() -> { if (!closed) showFailure(); });
            }
        });
    }

    private void render(List<CharacterDictionaryUpdates.Conflict> rows) {
        busy = false;
        ((TextView) activity.findViewById(R.id.tv_scene_conflicts_summary)).setText(
            activity.getString(R.string.chardict_conflicts_count, rows.size()));
        TextView empty = activity.findViewById(R.id.tv_scene_conflicts_empty);
        empty.setText(R.string.chardict_conflicts_empty);
        empty.setVisibility(rows.isEmpty() ? View.VISIBLE : View.GONE);
        LinearLayout container = activity.findViewById(R.id.container_scene_conflicts);
        container.removeAllViews();
        for (CharacterDictionaryUpdates.Conflict row : rows) {
            View card = activity.getLayoutInflater().inflate(R.layout.item_scene_conflict, container, false);
            ((TextView) card.findViewById(R.id.tv_scene_conflict_name)).setText(row.name);
            ((TextView) card.findViewById(R.id.tv_scene_conflict_changes)).setText(
                activity.getString(R.string.chardict_conflict_fields, android.text.TextUtils.join(", ", row.fields)));
            ((TextView) card.findViewById(R.id.tv_scene_conflict_game_summary))
                .setText(R.string.chardict_conflict_bundled);
            ((TextView) card.findViewById(R.id.tv_scene_conflict_het_summary))
                .setText(R.string.chardict_conflict_user);
            card.findViewById(R.id.tv_scene_conflict_error).setVisibility(View.GONE);
            TextView details = card.findViewById(R.id.tv_scene_conflict_details);
            details.setText(activity.getString(R.string.chardict_conflict_bundled) + "\n"
                + format(row.bundled) + "\n\n" + activity.getString(R.string.chardict_conflict_user)
                + "\n" + format(row.user));
            details.setVisibility(View.GONE);
            MaterialButton toggle = card.findViewById(R.id.btn_scene_conflict_toggle);
            toggle.setText(R.string.scene_conflict_expand);
            View.OnClickListener expand = v -> {
                boolean open = details.getVisibility() != View.VISIBLE;
                details.setVisibility(open ? View.VISIBLE : View.GONE);
                toggle.setText(open ? R.string.scene_conflict_collapse : R.string.scene_conflict_expand);
            };
            toggle.setOnClickListener(expand);
            card.setOnClickListener(expand);
            MaterialButton bundled = card.findViewById(R.id.btn_scene_conflict_choose_game);
            MaterialButton user = card.findViewById(R.id.btn_scene_conflict_choose_het);
            bundled.setText(R.string.chardict_conflict_choose_bundled);
            user.setText(R.string.chardict_conflict_choose_user);
            bundled.setOnClickListener(v -> choose(row, true));
            user.setOnClickListener(v -> choose(row, false));
            container.addView(card);
        }
    }

    private static String format(JSONObject value) {
        if (value == null) return "∅";
        try { return value.toString(2); } catch (Exception e) { return value.toString(); }
    }

    private void choose(CharacterDictionaryUpdates.Conflict row, boolean bundled) {
        if (busy) return;
        busy = true;
        io.execute(() -> {
            try {
                store.resolveCharacterDictionaryConflict(row, bundled);
                activity.runOnUiThread(() -> { if (!closed) reload(); });
            } catch (Exception e) {
                activity.runOnUiThread(() -> {
                    if (!closed) {
                        Toast.makeText(activity, R.string.chardict_conflict_failed, Toast.LENGTH_LONG).show();
                        reload();
                    }
                });
            }
        });
    }

    private void showFailure() {
        busy = true;
        ((LinearLayout) activity.findViewById(R.id.container_scene_conflicts)).removeAllViews();
        ((TextView) activity.findViewById(R.id.tv_scene_conflicts_summary))
            .setText(R.string.chardict_conflict_failed);
        activity.findViewById(R.id.tv_scene_conflicts_empty).setVisibility(View.GONE);
    }

    void close() { closed = true; io.shutdown(); }
}
