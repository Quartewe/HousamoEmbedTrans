package com.quarty.housamoembedtrans.ui;

import android.content.Intent;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;

import com.google.android.material.appbar.MaterialToolbar;
import com.google.android.material.card.MaterialCardView;

import com.quarty.housamoembedtrans.R;

/** Selects a real HET page and opens it with an in-memory style sample. */
public final class StylePreviewActivity extends AppCompatActivity {
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_style_preview);
        SystemBarInsets.apply(findViewById(R.id.root_style_preview));

        MaterialToolbar toolbar = findViewById(R.id.toolbar_style_preview);
        toolbar.setTitle(R.string.style_preview_toolbar_title);
        toolbar.setNavigationOnClickListener(view -> finish());

        LinearLayout options = findViewById(R.id.style_preview_options);
        for (String kind : StylePreview.kinds()) {
            options.addView(createOption(kind));
        }
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
            Intent intent = StylePreview.intentFor(this, kind);
            startActivity(intent);
        });
        return card;
    }

    private int labelFor(String kind) {
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
