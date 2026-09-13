package com.quarty.housamoembedtrans.ui;

import com.quarty.housamoembedtrans.R;
import com.quarty.housamoembedtrans.runtime.TranslationStatusNotification;
import com.quarty.housamoembedtrans.storage.config.ConfigStore;

import android.Manifest;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.view.Gravity;
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

/** Settings home. Each card opens an independent category editor. */
public final class SettingsActivity extends AppCompatActivity {
    private static final int REQUEST_NOTIFICATION_PERMISSION = 1001;

    private ConfigStore configStore;
    private TextView configStatus;
    private LinearLayout categoryList;
    private LinearLayout categoryListAfterPrompt;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_settings);
        SystemBarInsets.apply(findViewById(R.id.root_settings));

        configStore = new ConfigStore(this);
        configStatus = findViewById(R.id.tv_config_status);
        categoryList = findViewById(R.id.settings_category_list);
        categoryListAfterPrompt = findViewById(
            R.id.settings_category_list_after_prompt
        );
        PrimaryNavigation.attach(
            this,
            findViewById(R.id.primary_navigation),
            PrimaryNavigation.Destination.SETTINGS
        );

        MaterialToolbar toolbar = findViewById(R.id.toolbar_settings);
        toolbar.setTitle(R.string.settings_title);
        findViewById(R.id.card_prompt_editor).setOnClickListener(
            view -> startActivity(new Intent(this, PromptEditorActivity.class))
        );
        findViewById(R.id.btn_reset_defaults).setOnClickListener(
            view -> confirmResetDefaults()
        );
        ensureNotificationPermission();
        renderHome();
    }

    @Override
    protected void onResume() {
        super.onResume();
        renderHome();
    }

    private void renderHome() {
        if (configStore == null || categoryList == null) {
            return;
        }
        try {
            ConfigStore.LoadResult result = configStore.load();
            configStatus.setText(
                result.invalidUserOverride
                    ? R.string.config_source_invalid
                    : result.userOverride
                        ? R.string.config_source_saved
                        : R.string.config_source_default
            );
            JSONObject userSettings = result.config.getJSONObject("UserSettings");
            String apiKey = configStore.loadApiKey();
            categoryList.removeAllViews();
            categoryListAfterPrompt.removeAllViews();
            SettingsCategory.Definition[] definitions =
                SettingsCategory.definitions();
            for (int index = 0; index < definitions.length; index++) {
                LinearLayout destination = index < 3
                    ? categoryList
                    : categoryListAfterPrompt;
                destination.addView(createCategoryCard(
                    definitions[index],
                    userSettings,
                    apiKey
                ));
            }
        } catch (Exception error) {
            configStatus.setText(getString(
                R.string.config_load_failed,
                safeMessage(error)
            ));
            categoryList.removeAllViews();
            categoryListAfterPrompt.removeAllViews();
        }
    }

    private View createCategoryCard(
        SettingsCategory.Definition definition,
        JSONObject userSettings,
        String apiKey
    ) {
        MaterialCardView card = new MaterialCardView(this);
        LinearLayout.LayoutParams cardParams = new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        );
        cardParams.topMargin = dp(8);
        card.setLayoutParams(cardParams);
        card.setClickable(true);
        card.setFocusable(true);
        card.setMinimumHeight(dp(88));

        LinearLayout body = new LinearLayout(this);
        body.setOrientation(LinearLayout.HORIZONTAL);
        body.setGravity(Gravity.CENTER_VERTICAL);
        body.setPadding(dp(16), dp(14), dp(12), dp(14));
        card.addView(body);

        LinearLayout copy = new LinearLayout(this);
        copy.setOrientation(LinearLayout.VERTICAL);
        copy.setLayoutParams(new LinearLayout.LayoutParams(
            0,
            LinearLayout.LayoutParams.WRAP_CONTENT,
            1f
        ));
        body.addView(copy);

        TextView title = new TextView(this);
        title.setText(definition.title);
        title.setTextAppearance(
            com.google.android.material.R.style.TextAppearance_MaterialComponents_Subtitle1
        );
        title.setTypeface(title.getTypeface(), android.graphics.Typeface.BOLD);
        copy.addView(title);

        TextView description = new TextView(this);
        description.setText(definition.description);
        description.setTextAppearance(
            com.google.android.material.R.style.TextAppearance_MaterialComponents_Body2
        );
        description.setTextColor(getColor(R.color.het_on_surface_muted));
        copy.addView(description);

        TextView summary = new TextView(this);
        summary.setText(SettingsCategory.summary(
            definition,
            userSettings,
            apiKey
        ));
        summary.setTextAppearance(
            com.google.android.material.R.style.TextAppearance_MaterialComponents_Caption
        );
        summary.setPadding(0, dp(5), 0, 0);
        copy.addView(summary);

        TextView arrow = new TextView(this);
        arrow.setText("›");
        arrow.setTextSize(28f);
        arrow.setTextColor(getColor(R.color.het_primary));
        body.addView(arrow, new LinearLayout.LayoutParams(
            dp(32),
            LinearLayout.LayoutParams.WRAP_CONTENT
        ));

        card.setContentDescription(getString(definition.title));
        card.setOnClickListener(view -> startActivity(
            new Intent(this, SettingsCategoryActivity.class)
                .putExtra(SettingsCategoryActivity.EXTRA_CATEGORY, definition.id)
        ));
        return card;
    }

    private void confirmResetDefaults() {
        new MaterialAlertDialogBuilder(this)
            .setTitle(R.string.settings_home_reset_defaults_title)
            .setMessage(R.string.settings_home_reset_defaults_message)
            .setNegativeButton(R.string.cancel_action, null)
            .setPositiveButton(
                R.string.settings_home_reset_defaults,
                (dialog, which) -> resetDefaults()
            )
            .show();
    }

    private void resetDefaults() {
        try {
            configStore.save(configStore.loadBundledDefault());
            configStore.saveApiKey("");
            Toast.makeText(
                this,
                R.string.defaults_loaded,
                Toast.LENGTH_SHORT
            ).show();
            renderHome();
        } catch (Exception error) {
            Toast.makeText(
                this,
                getString(R.string.config_save_failed, safeMessage(error)),
                Toast.LENGTH_LONG
            ).show();
        }
    }

    private void ensureNotificationPermission() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU
            || checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
                == PackageManager.PERMISSION_GRANTED) {
            TranslationStatusNotification.refresh(this);
            return;
        }
        requestPermissions(
            new String[] {Manifest.permission.POST_NOTIFICATIONS},
            REQUEST_NOTIFICATION_PERMISSION
        );
    }

    @Override
    public void onRequestPermissionsResult(
        int requestCode,
        String[] permissions,
        int[] grantResults
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQUEST_NOTIFICATION_PERMISSION
            && grantResults.length > 0
            && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
            TranslationStatusNotification.refresh(this);
        }
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    private static String safeMessage(Throwable error) {
        String message = error.getMessage();
        return message == null || message.trim().isEmpty()
            ? error.getClass().getSimpleName()
            : message;
    }
}
