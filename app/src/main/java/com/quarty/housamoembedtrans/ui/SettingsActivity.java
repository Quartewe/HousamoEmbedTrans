package com.quarty.housamoembedtrans.ui;

import com.quarty.housamoembedtrans.R;
import com.quarty.housamoembedtrans.logging.LogExport;
import com.quarty.housamoembedtrans.runtime.TranslationStatusNotification;
import com.quarty.housamoembedtrans.storage.config.ConfigStore;

import android.Manifest;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Menu;
import android.view.MenuItem;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;

import com.google.android.material.appbar.MaterialToolbar;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.card.MaterialCardView;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;

import org.json.JSONObject;

import java.io.IOException;
import java.io.OutputStream;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

/** Settings home. Each card opens an independent category editor. */
public final class SettingsActivity extends AppCompatActivity {
    private static final int REQUEST_NOTIFICATION_PERMISSION = 1001;
    private static final int REQUEST_EXPORT_LOGS = 1002;

    private ConfigStore configStore;
    private TextView configStatus;
    private LinearLayout categoryList;
    private LinearLayout categoryListAfterPrompt;
    private TextView themeValue;
    private ObbResourceDialog obbResourceDialog;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final ExecutorService logExportExecutor =
        Executors.newSingleThreadExecutor();
    private Future<?> logExportTask;
    private LogExport logExport;
    private volatile boolean logExportInProgress;
    private volatile int logExportGeneration;

    private static final int MENU_CONFIG_SOURCE = 2001;
    private static final int MENU_RESET_DEFAULTS = 2002;

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
    }

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
        toolbar.setTitle(R.string.settings_rebuild_toolbar_title);
        toolbar.setNavigationIcon(R.drawable.ic_settings_rebuild_export_log);
        toolbar.setNavigationContentDescription(
            R.string.settings_rebuild_export_logs
        );
        toolbar.setNavigationOnClickListener(
            view -> showLogExportDialog()
        );
        installToolbarMenu(toolbar);
        installThemePanel();
        findViewById(R.id.btn_obb_resources).setOnClickListener(view -> {
            if (obbResourceDialog != null) obbResourceDialog.close();
            obbResourceDialog = new ObbResourceDialog(this);
            obbResourceDialog.show();
        });
        findViewById(R.id.card_prompt_editor).setOnClickListener(
            view -> startActivity(new Intent(this, PromptEditorActivity.class))
        );
        ensureNotificationPermission();
        renderHome();
    }

    @Override
    protected void onResume() {
        super.onResume();
        PrimaryNavigation.animateContentOnResume(this);
        refreshThemePanel();
        renderHome();
    }

    @Override
    protected void onDestroy() {
        if (obbResourceDialog != null) obbResourceDialog.close();
        logExportGeneration++;
        cancelLogExport();
        logExportExecutor.shutdownNow();
        super.onDestroy();
    }

    private void installToolbarMenu(MaterialToolbar toolbar) {
        Menu menu = toolbar.getMenu();
        menu.clear();
        MenuItem more = menu.add(
            Menu.NONE,
            Menu.NONE,
            Menu.NONE,
            R.string.settings_rebuild_more
        );
        more.setIcon(R.drawable.ic_settings_rebuild_more);
        more.setIconTintList(ContextCompat.getColorStateList(
            this,
            R.color.het_primary
        ));
        more.setShowAsAction(MenuItem.SHOW_AS_ACTION_ALWAYS);
        toolbar.setOnMenuItemClickListener(item -> {
            showMoreMenu(toolbar);
            return true;
        });
    }

    private void showMoreMenu(View anchor) {
        StyledPopupMenu popup = new StyledPopupMenu(this, anchor, Gravity.END);
        popup.setTitle(getString(R.string.settings_rebuild_more_title));
        popup.getMenu().add(
            Menu.NONE,
            MENU_CONFIG_SOURCE,
            Menu.NONE,
            R.string.settings_rebuild_config_source_info
        );
        popup.getMenu().add(
            Menu.NONE,
            MENU_RESET_DEFAULTS,
            Menu.NONE,
            R.string.settings_home_reset_defaults
        );
        popup.setOnMenuItemClickListener(item -> {
            if (item.getItemId() == MENU_CONFIG_SOURCE) {
                showConfigSourceInfo();
            } else if (item.getItemId() == MENU_RESET_DEFAULTS) {
                confirmResetDefaults();
            }
            return true;
        });
        popup.show();
    }

    private void showConfigSourceInfo() {
        CharSequence message = configStatus == null
            ? getString(R.string.settings_rebuild_config_source_unknown)
            : configStatus.getText();
        new UiMaterialAlertDialogBuilder(this)
            .setTitle(R.string.settings_rebuild_config_source_info)
            .setMessage(message)
            .setPositiveButton(R.string.settings_rebuild_close, null)
            .show();
    }

    private void showLogExportDialog() {
        if (logExportInProgress) {
            Toast.makeText(
                this,
                R.string.settings_rebuild_export_logs_in_progress,
                Toast.LENGTH_SHORT
            ).show();
            return;
        }
        new UiMaterialAlertDialogBuilder(this)
            .setTitle(R.string.settings_rebuild_export_logs)
            .setMessage(R.string.settings_rebuild_export_logs_description)
            .setNegativeButton(R.string.cancel_action, null)
            .setPositiveButton(
                R.string.settings_rebuild_export_logs_choose,
                (dialog, which) -> launchLogExportPicker()
            )
            .show();
    }

    private void launchLogExportPicker() {
        Intent intent = new Intent(Intent.ACTION_CREATE_DOCUMENT)
            .setType("application/zip")
            .putExtra(
                Intent.EXTRA_TITLE,
                "het_" + new SimpleDateFormat("yy_MM_dd_HH_mm_ss", Locale.ROOT)
                    .format(new Date()) + ".zip"
            )
            .addCategory(Intent.CATEGORY_OPENABLE)
            .addFlags(
                Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                    | Intent.FLAG_GRANT_READ_URI_PERMISSION
            );
        try {
            startActivityForResult(intent, REQUEST_EXPORT_LOGS);
        } catch (RuntimeException error) {
            showLogExportFailure(safeMessage(error));
        }
    }

    @Override
    protected void onActivityResult(
        int requestCode,
        int resultCode,
        Intent data
    ) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != REQUEST_EXPORT_LOGS) {
            return;
        }
        if (resultCode != RESULT_OK || data == null || data.getData() == null) {
            return;
        }
        if (logExportInProgress) {
            Toast.makeText(
                this,
                R.string.settings_rebuild_export_logs_in_progress,
                Toast.LENGTH_SHORT
            ).show();
            return;
        }
        startLogExport(data.getData());
    }

    private void startLogExport(Uri destination) {
        final int generation = ++logExportGeneration;
        logExportInProgress = true;
        Toast.makeText(
            this,
            R.string.settings_rebuild_export_logs_in_progress,
            Toast.LENGTH_SHORT
        ).show();
        final LogExport operation = new LogExport(getApplicationContext());
        logExport = operation;
        logExportTask = logExportExecutor.submit(() -> {
            try (LogExport ignored = operation) {
                ensureLogExportActive(generation);
                OutputStream output = getContentResolver().openOutputStream(destination, "w");
                if (output == null) throw new IOException("document provider returned no output");
                int count = operation.write(output);
                postLogExportSuccess(generation, count, operation.includesGame());
            } catch (InterruptedException error) {
                Thread.currentThread().interrupt();
            } catch (Exception error) {
                postLogExportFailure(generation, safeMessage(error));
            }
        });
    }

    private void cancelLogExport() {
        if (logExport != null) {
            logExport.close();
            logExport = null;
        }
        Future<?> task = logExportTask;
        if (task != null) {
            task.cancel(true);
        }
        logExportInProgress = false;
    }

    private void ensureLogExportActive(int generation)
        throws InterruptedException {
        if (Thread.currentThread().isInterrupted()
            || generation != logExportGeneration) {
            throw new InterruptedException();
        }
    }

    private void postLogExportSuccess(int generation, int fileCount, boolean gameIncluded) {
        mainHandler.post(() -> {
            if (generation != logExportGeneration) {
                return;
            }
            logExportInProgress = false;
            logExportTask = null;
            if (!isFinishing() && !isDestroyed()) {
                Toast.makeText(
                    this,
                    getString(
                        gameIncluded ? R.string.settings_rebuild_export_logs_success
                            : R.string.settings_rebuild_export_logs_partial,
                        fileCount
                    ),
                    Toast.LENGTH_LONG
                ).show();
            }
        });
    }

    private void postLogExportFailure(int generation, String message) {
        mainHandler.post(() -> {
            if (generation != logExportGeneration) {
                return;
            }
            logExportInProgress = false;
            logExportTask = null;
            if (!isFinishing() && !isDestroyed()) {
                showLogExportFailure(message);
            }
        });
    }

    private void showLogExportFailure(String message) {
        new UiMaterialAlertDialogBuilder(this)
            .setTitle(R.string.settings_rebuild_export_logs)
            .setMessage(getString(
                R.string.settings_rebuild_export_logs_failed,
                message
            ))
            .setPositiveButton(R.string.settings_rebuild_close, null)
            .show();
    }

    private void installThemePanel() {
        View effect = findViewById(R.id.card_settings_effect);
        if (!(effect.getParent() instanceof ViewGroup)) {
            return;
        }
        ViewGroup parent = (ViewGroup) effect.getParent();
        if (themeValue != null) {
            return;
        }
        MaterialCardView card = new MaterialCardView(this);
        card.setCardBackgroundColor(getColor(R.color.het_surface_container));
        card.setCardElevation(0);
        card.setRadius(dp(14));
        card.setStrokeColor(getColor(R.color.het_outline_soft));
        card.setStrokeWidth(dp(1));
        LinearLayout.LayoutParams cardParams = new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        );
        cardParams.topMargin = dp(8);
        card.setLayoutParams(cardParams);

        LinearLayout body = new LinearLayout(this);
        body.setGravity(android.view.Gravity.CENTER_VERTICAL);
        body.setPadding(dp(12), dp(10), dp(12), dp(10));
        card.addView(body);
        LinearLayout copy = new LinearLayout(this);
        copy.setOrientation(LinearLayout.VERTICAL);
        body.addView(copy, new LinearLayout.LayoutParams(
            0,
            LinearLayout.LayoutParams.WRAP_CONTENT,
            1f
        ));
        TextView title = new TextView(this);
        title.setText(R.string.settings_rebuild_theme_title);
        title.setTextColor(getColor(R.color.het_on_surface));
        title.setTextSize(15);
        title.setTypeface(null, android.graphics.Typeface.BOLD);
        copy.addView(title);
        TextView hint = new TextView(this);
        hint.setText(R.string.settings_rebuild_theme_hint);
        hint.setTextColor(getColor(R.color.het_on_surface_muted));
        hint.setTextSize(12);
        hint.setPadding(0, dp(4), 0, 0);
        copy.addView(hint);
        themeValue = new TextView(this);
        themeValue.setTextSize(13);
        themeValue.setTextColor(getColor(R.color.het_primary_strong));
        themeValue.setGravity(android.view.Gravity.CENTER_VERTICAL | android.view.Gravity.END);
        themeValue.setMinHeight(dp(44));
        themeValue.setMinWidth(dp(48));
        themeValue.setPadding(dp(8), 0, 0, 0);
        card.setClickable(true);
        card.setFocusable(true);
        card.setOnClickListener(view -> showThemePicker());
        body.addView(themeValue, new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ));
        parent.addView(card, parent.indexOfChild(effect));
        refreshThemePanel();
    }

    private void refreshThemePanel() {
        if (themeValue != null) {
            themeValue.setText(UiThemePreference.label(
                UiThemePreference.read(this)
            ));
        }
    }

    private void showThemePicker() {
        String[] modes = {
            UiThemePreference.SYSTEM,
            UiThemePreference.LIGHT,
            UiThemePreference.DARK
        };
        int selected = 0;
        String current = UiThemePreference.read(this);
        if (UiThemePreference.LIGHT.equals(current)) {
            selected = 1;
        } else if (UiThemePreference.DARK.equals(current)) {
            selected = 2;
        }
        String[] labels = {
            getString(UiThemePreference.label(modes[0])),
            getString(UiThemePreference.label(modes[1])),
            getString(UiThemePreference.label(modes[2]))
        };
        new UiMaterialAlertDialogBuilder(this)
            .setTitle(R.string.settings_rebuild_theme_title)
            .setSingleChoiceItems(labels, selected, (dialog, which) -> {
                UiThemePreference.saveAndApply(this, modes[which]);
                dialog.dismiss();
            })
            .setNegativeButton(R.string.cancel_action, null)
            .show();
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
            configStatus.setVisibility(
                result.invalidUserOverride ? View.VISIBLE : View.GONE
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
                    destination,
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
            configStatus.setVisibility(View.VISIBLE);
            categoryList.removeAllViews();
            categoryListAfterPrompt.removeAllViews();
        }
    }

    private View createCategoryCard(
        LinearLayout destination,
        SettingsCategory.Definition definition,
        JSONObject userSettings,
        String apiKey
    ) {
        MaterialCardView card = (MaterialCardView) getLayoutInflater().inflate(
            R.layout.item_settings_category,
            destination,
            false
        );
        LinearLayout.LayoutParams cardParams =
            (LinearLayout.LayoutParams) card.getLayoutParams();
        cardParams.topMargin = destination.getChildCount() == 0 ? 0 : dp(8);
        card.setLayoutParams(cardParams);

        TextView title = card.findViewById(
            R.id.tv_settings_category_title
        );
        title.setText(definition.title);
        TextView description = card.findViewById(
            R.id.tv_settings_category_description
        );
        description.setText(definition.description);
        TextView summary = card.findViewById(
            R.id.tv_settings_category_summary
        );
        summary.setText(SettingsCategory.summary(
            definition,
            userSettings,
            apiKey
        ));

        card.setContentDescription(getString(definition.title));
        card.setOnClickListener(view -> startActivity(
            new Intent(this, SettingsCategoryActivity.class)
                .putExtra(SettingsCategoryActivity.EXTRA_CATEGORY, definition.id)
        ));
        return card;
    }

    private void confirmResetDefaults() {
        AlertDialog resetDialog = new UiMaterialAlertDialogBuilder(this)
            .setTitle(R.string.settings_home_reset_defaults_title)
            .setMessage(R.string.settings_home_reset_defaults_message)
            .setNegativeButton(R.string.cancel_action, null)
            .setPositiveButton(
                R.string.settings_home_reset_defaults,
                (dialog, which) -> resetDefaults()
            )
            .show();
        UiDialogPresentation.styleDangerousPositive(resetDialog);
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
