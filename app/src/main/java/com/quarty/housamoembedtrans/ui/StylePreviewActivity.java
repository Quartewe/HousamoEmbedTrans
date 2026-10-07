package com.quarty.housamoembedtrans.ui;

import android.Manifest;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.view.Gravity;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;

import com.google.android.material.appbar.MaterialToolbar;
import com.google.android.material.card.MaterialCardView;

import com.quarty.housamoembedtrans.R;
import com.quarty.housamoembedtrans.runtime.NotificationStylePreview;

import java.util.List;

/** Selects a real HET page and opens it with an in-memory style sample. */
public final class StylePreviewActivity extends AppCompatActivity {
    public static final String EXTRA_NOTIFICATION_PREVIEW = "notification_style_preview";
    public static final String EXTRA_NOTIFICATION_PROPOSED = "notification_style_proposed";
    private static final String KIND_NOTIFICATIONS = "notifications";
    private static final int NOTIFICATION_PERMISSION_REQUEST = 100;
    private AlertDialog notificationDialog;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_style_preview);
        SystemBarInsets.apply(findViewById(R.id.root_style_preview));

        MaterialToolbar toolbar = findViewById(R.id.toolbar_style_preview);
        toolbar.setTitle(R.string.style_preview_toolbar_title);
        toolbar.setNavigationOnClickListener(view -> finish());

        LinearLayout options = findViewById(R.id.style_preview_options);
        options.addView(createOption(KIND_NOTIFICATIONS));
        for (String kind : StylePreview.kinds()) {
            options.addView(createOption(kind));
        }
        openNotificationIntent(getIntent());
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        openNotificationIntent(intent);
    }

    private void openNotificationIntent(Intent intent) {
        if (intent != null && intent.getBooleanExtra(EXTRA_NOTIFICATION_PREVIEW, false)) {
            showNotificationSamples(intent.getBooleanExtra(EXTRA_NOTIFICATION_PROPOSED, false));
        }
    }

    private void showNotificationGroups() {
        new UiMaterialAlertDialogBuilder(this)
            .setTitle(R.string.notification_preview_title)
            .setItems(new CharSequence[] {
                getString(R.string.notification_preview_existing),
                getString(R.string.notification_preview_proposed)
            }, (dialog, which) -> showNotificationSamples(which == 1))
            .setNegativeButton(android.R.string.cancel, null)
            .show();
    }

    private void showNotificationSamples(boolean proposed) {
        if (notificationDialog != null) notificationDialog.dismiss();
        List<NotificationStylePreview.Sample> samples = NotificationStylePreview.samples(proposed);
        CharSequence[] labels = new CharSequence[samples.size()];
        for (int index = 0; index < samples.size(); index++) {
            labels[index] = getString(samples.get(index).label);
        }
        notificationDialog = new UiMaterialAlertDialogBuilder(this)
            .setTitle(proposed ? R.string.notification_preview_proposed_title
                : R.string.notification_preview_existing_title)
            .setSingleChoiceItems(labels, -1, (dialog, which) -> postNotificationSample(samples.get(which)))
            .setNegativeButton(R.string.notification_preview_close, null)
            .setNeutralButton(R.string.notification_preview_clear, null)
            .setPositiveButton(R.string.notification_preview_settings, (dialog, which) ->
                startActivity(new Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                    .putExtra(Settings.EXTRA_APP_PACKAGE, getPackageName())))
            .create();
        notificationDialog.setOnShowListener(dialog ->
            notificationDialog.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener(view -> {
                NotificationStylePreview.clear(this);
                Toast.makeText(this, R.string.notification_preview_cleared, Toast.LENGTH_SHORT).show();
            }));
        notificationDialog.show();
    }

    private void postNotificationSample(NotificationStylePreview.Sample sample) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
            && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[] {Manifest.permission.POST_NOTIFICATIONS}, NOTIFICATION_PERMISSION_REQUEST);
            Toast.makeText(this, R.string.notification_preview_grant_then_retry, Toast.LENGTH_LONG).show();
            return;
        }
        try {
            NotificationStylePreview.post(this, sample);
            Toast.makeText(this, getString(R.string.notification_preview_posted, getString(sample.label)),
                Toast.LENGTH_SHORT).show();
        } catch (RuntimeException error) {
            new UiMaterialAlertDialogBuilder(this)
                .setTitle(R.string.notification_preview_post_failed)
                .setMessage(error.getMessage())
                .setPositiveButton(android.R.string.ok, null)
                .show();
        }
    }

    @Override
    protected void onDestroy() {
        if (notificationDialog != null) notificationDialog.dismiss();
        super.onDestroy();
    }

    private View createOption(String kind) {
        MaterialCardView card = new MaterialCardView(this);
        card.setCardBackgroundColor(getColor(R.color.het_surface_container));
        card.setCardElevation(0f);
        card.setRadius(dp(16));
        card.setStrokeColor(getColor(R.color.het_outline_soft));
        card.setStrokeWidth(dp(1));
        LinearLayout.LayoutParams cardParams = new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        );
        cardParams.bottomMargin = dp(8);
        card.setLayoutParams(cardParams);
        card.setClickable(true);
        card.setFocusable(true);
        card.setContentDescription(getString(labelFor(kind)));

        LinearLayout body = new LinearLayout(this);
        body.setOrientation(LinearLayout.HORIZONTAL);
        body.setGravity(Gravity.CENTER_VERTICAL);
        body.setPadding(dp(14), dp(12), dp(10), dp(12));
        card.addView(body);

        LinearLayout copy = new LinearLayout(this);
        copy.setOrientation(LinearLayout.VERTICAL);
        body.addView(copy, new LinearLayout.LayoutParams(
            0,
            LinearLayout.LayoutParams.WRAP_CONTENT,
            1f
        ));

        TextView title = new TextView(this);
        title.setText(labelFor(kind));
        title.setTextColor(getColor(R.color.het_on_surface));
        title.setTextSize(14f);
        title.setTypeface(null, android.graphics.Typeface.BOLD);
        title.setIncludeFontPadding(false);
        copy.addView(title);

        TextView description = new TextView(this);
        description.setText(descriptionFor(kind));
        description.setTextColor(getColor(R.color.het_on_surface_muted));
        description.setTextSize(11f);
        description.setLineSpacing(0f, 1.4f);
        description.setIncludeFontPadding(false);
        LinearLayout.LayoutParams descriptionParams = new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        );
        descriptionParams.topMargin = dp(4);
        copy.addView(description, descriptionParams);

        TextView arrow = new TextView(this);
        arrow.setText("›");
        arrow.setTextColor(getColor(R.color.het_primary_strong));
        arrow.setTextSize(24f);
        arrow.setGravity(Gravity.CENTER);
        body.addView(arrow, new LinearLayout.LayoutParams(dp(30), dp(42)));

        card.setOnClickListener(view -> {
            if (KIND_NOTIFICATIONS.equals(kind)) {
                showNotificationGroups();
                return;
            }
            if (StylePreview.KIND_HELD_ARRANGEMENT.equals(kind)) {
                HeldTaskArrangementDialog.showPreview(this);
                return;
            }
            Intent intent = StylePreview.intentFor(this, kind);
            startActivity(intent);
        });
        return card;
    }

    private int labelFor(String kind) {
        if (KIND_NOTIFICATIONS.equals(kind)) return R.string.notification_preview_title;
        if (StylePreview.KIND_HELD_ARRANGEMENT.equals(kind)) return R.string.style_preview_held_arrangement;
        if (StylePreview.KIND_HOME.equals(kind)) return R.string.style_preview_home;
        if (StylePreview.KIND_TASKS.equals(kind)) return R.string.style_preview_tasks;
        if (StylePreview.KIND_MANAGEMENT_HOME.equals(kind)) return R.string.style_preview_management_home;
        if (StylePreview.KIND_SCENE_SYNC.equals(kind)) return R.string.style_preview_scene_sync;
        if (StylePreview.KIND_MANAGEMENT_EXPORT.equals(kind)) return R.string.style_preview_management_export;
        if (StylePreview.KIND_SCENE_DETAIL.equals(kind)) return R.string.style_preview_scene_detail;
        if (StylePreview.KIND_SCENE_EDITOR.equals(kind)) return R.string.style_preview_scene_editor;
        if (StylePreview.KIND_CONTEXT_DETAIL.equals(kind)) return R.string.style_preview_context_detail;
        if (StylePreview.KIND_GROUP_DETAIL.equals(kind)) return R.string.style_preview_group_detail;
        if (StylePreview.KIND_CONTEXT_EDITOR.equals(kind)) return R.string.style_preview_context_editor;
        if (StylePreview.KIND_GROUP_EDITOR.equals(kind)) return R.string.style_preview_group_editor;
        if (StylePreview.KIND_CHARACTER_DETAIL.equals(kind)) return R.string.style_preview_character_detail;
        if (StylePreview.KIND_TERM_DETAIL.equals(kind)) return R.string.style_preview_term_detail;
        if (StylePreview.KIND_CHARACTER_EDITOR.equals(kind)) return R.string.style_preview_character_editor;
        if (StylePreview.KIND_TERM_EDITOR.equals(kind)) return R.string.style_preview_term_editor;
        if (StylePreview.KIND_MANAGEMENT_IMPORT.equals(kind)) return R.string.style_preview_management_import;
        return R.string.style_preview_unknown;
    }

    private int descriptionFor(String kind) {
        if (KIND_NOTIFICATIONS.equals(kind)) return R.string.notification_preview_description;
        if (StylePreview.KIND_HELD_ARRANGEMENT.equals(kind)) return R.string.style_preview_held_arrangement_description;
        if (StylePreview.KIND_HOME.equals(kind)) return R.string.style_preview_home_description;
        if (StylePreview.KIND_TASKS.equals(kind)) return R.string.style_preview_tasks_description;
        if (StylePreview.KIND_MANAGEMENT_HOME.equals(kind)) return R.string.style_preview_management_home_description;
        if (StylePreview.KIND_SCENE_SYNC.equals(kind)) return R.string.style_preview_scene_sync_description;
        if (StylePreview.KIND_MANAGEMENT_EXPORT.equals(kind)) return R.string.style_preview_management_export_description;
        if (StylePreview.KIND_SCENE_DETAIL.equals(kind)) return R.string.style_preview_scene_detail_description;
        if (StylePreview.KIND_SCENE_EDITOR.equals(kind)) return R.string.style_preview_scene_editor_description;
        if (StylePreview.KIND_CONTEXT_DETAIL.equals(kind)) return R.string.style_preview_context_detail_description;
        if (StylePreview.KIND_GROUP_DETAIL.equals(kind)) return R.string.style_preview_group_detail_description;
        if (StylePreview.KIND_CONTEXT_EDITOR.equals(kind)) return R.string.style_preview_context_editor_description;
        if (StylePreview.KIND_GROUP_EDITOR.equals(kind)) return R.string.style_preview_group_editor_description;
        if (StylePreview.KIND_CHARACTER_DETAIL.equals(kind)) return R.string.style_preview_character_detail_description;
        if (StylePreview.KIND_TERM_DETAIL.equals(kind)) return R.string.style_preview_term_detail_description;
        if (StylePreview.KIND_CHARACTER_EDITOR.equals(kind)) return R.string.style_preview_character_editor_description;
        if (StylePreview.KIND_TERM_EDITOR.equals(kind)) return R.string.style_preview_term_editor_description;
        if (StylePreview.KIND_MANAGEMENT_IMPORT.equals(kind)) return R.string.style_preview_management_import_description;
        return R.string.style_preview_unknown_description;
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
