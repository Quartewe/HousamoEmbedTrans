package com.quarty.housamoembedtrans.ui;

import com.quarty.housamoembedtrans.R;
import com.quarty.housamoembedtrans.HousamoApplication;
import com.quarty.housamoembedtrans.runtime.SceneSyncRuntimeState;
import com.quarty.housamoembedtrans.runtime.TranslationStatusNotification;
import com.quarty.housamoembedtrans.storage.config.ConfigStore;
import com.quarty.housamoembedtrans.translation.job.TranslationJobStore;

import android.Manifest;
import android.content.Intent;
import android.graphics.PorterDuff;
import android.net.Uri;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import com.quarty.housamoembedtrans.logging.Log;
import android.view.View;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;

import com.google.android.material.card.MaterialCardView;

import org.json.JSONObject;

import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** The top-level home page and the entry point for the product navigation. */
public final class HomeActivity extends AppCompatActivity {
    private static final String TAG = "HET.Home";
    private static final String GITHUB_URL =
        "https://github.com/Quartewe/HousamoEmbedTrans";
    private static final String GITHUB_RELEASE_URL =
        "https://github.com/Quartewe/HousamoEmbedTrans/releases/latest";

    private final SceneSyncRuntimeState runtimeState =
        SceneSyncRuntimeState.getInstance();
    private final SceneSyncRuntimeState.Listener runtimeListener =
        this::dispatchRuntimeSnapshot;
    private final Handler runtimeRefreshHandler = new Handler(Looper.getMainLooper());
    private final Runnable runtimeRefresh = new Runnable() {
        @Override public void run() {
            if (stylePreview || !runtimeListening || isFinishing() || isDestroyed()) return;
            acceptRuntimeSnapshot(runtimeState.getSnapshot());
            runtimeRefreshHandler.postDelayed(this, 1000L);
        }
    };

    private TranslationJobStore arrangementStore;
    private final TranslationJobStore.QueueListener
        arrangementListener = (pending, held, repairing) -> runOnUiThread(() -> {
            if (arrangementStore != null && !isFinishing() && !isDestroyed()) {
                renderArrangementEntry();
            }
        });

    private MaterialCardView statusCard;
    private MaterialCardView updateCard;
    private ImageView statusIcon;
    private TextView stateView;
    private TextView connectionView;
    private TextView updateStatusView;
    private TextView conflictsView;
    private TextView appVersionView;
    private TextView resourceVersionView;
    private TextView gameVersionView;
    private TextView targetLanguageView;

    private SceneSyncRuntimeState.Snapshot runtimeSnapshot;
    private ExecutorService ioExecutor;
    private boolean stylePreview;
    private boolean runtimeListening;
    private boolean appUpdateRequested;
    private boolean appUpdateInProgress;
    private boolean appUpdateChecked;
    private boolean appUpdateAvailable;
    private long workGeneration;

    private String currentAppVersion = "";
    private String latestAppVersion = "";
    private String resourceVersion = "";
    private boolean resourceUpdateAvailable;
    private String gameVersion = "";
    private String targetLanguage = "";

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
    }

    @Override
    protected void onResume() {
        super.onResume();
        PrimaryNavigation.animateContentOnResume(this);
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        stylePreview = StylePreview.isEnabled(this);
        setContentView(R.layout.activity_home);
        SystemBarInsets.apply(findViewById(R.id.root_home));
        bindViews();
        installCardActions();

        currentAppVersion = installedVersionOf(getPackageName());
        runtimeSnapshot = runtimeState.getSnapshot();
        PrimaryNavigation.attach(
            this,
            findViewById(R.id.primary_navigation),
            PrimaryNavigation.Destination.HOME
        );

        if (stylePreview) {
            installPreviewNavigation();
            loadStylePreview();
        } else {
            ioExecutor = Executors.newSingleThreadExecutor();
            renderRuntimeSnapshot(runtimeSnapshot);
            renderMetadata();
            ensureNotificationPermission();
        }
    }

    @Override
    protected void onStart() {
        super.onStart();
        if (stylePreview) {
            return;
        }
        arrangementStore = TranslationJobStore.getInstance(this);
        arrangementStore.setQueueListener(arrangementListener);
        startRuntimeObservation();
        loadMetadataAsync();
        if (!appUpdateRequested) {
            requestAppUpdateCheck();
        }
    }

    @Override
    protected void onStop() {
        if (arrangementStore != null) {
            arrangementStore.clearQueueListener(arrangementListener);
            arrangementStore = null;
        }
        stopRuntimeObservation();
        super.onStop();
    }

    @Override
    protected void onDestroy() {
        stopRuntimeObservation();
        workGeneration++;
        if (ioExecutor != null) {
            ioExecutor.shutdownNow();
            ioExecutor = null;
        }
        super.onDestroy();
    }

    private void renderArrangementEntry() {
        if (stylePreview) return;
        int count = HeldTaskArrangementDialog.scenes(this).size();
        View entry = findViewById(R.id.card_home_arrangement);
        entry.setVisibility(count > 1 ? View.VISIBLE : View.GONE);
        ((TextView) findViewById(R.id.tv_home_arrangement_count)).setText(
            getString(R.string.held_arrangement_count, count));
        entry.setOnClickListener(view -> {
            if (ioExecutor != null) {
                HeldTaskArrangementDialog.show(this, ioExecutor, this::renderArrangementEntry);
            }
        });
    }

    private void bindViews() {
        statusCard = findViewById(R.id.card_home_status);
        updateCard = findViewById(R.id.card_home_update);
        statusIcon = findViewById(R.id.iv_home_status);
        stateView = findViewById(R.id.tv_home_state);
        connectionView = findViewById(R.id.tv_home_connection);
        updateStatusView = findViewById(R.id.tv_home_update_status);
        conflictsView = findViewById(R.id.tv_home_conflicts);
        appVersionView = findViewById(R.id.tv_home_app_version);
        resourceVersionView = findViewById(R.id.tv_home_resource_version);
        gameVersionView = findViewById(R.id.tv_home_game_version);
        targetLanguageView = findViewById(R.id.tv_home_target_language);
    }

    private void installCardActions() {
        View resourceEntry = (View) resourceVersionView.getParent();
        resourceEntry.setClickable(true);
        resourceEntry.setFocusable(true);
        resourceEntry.setOnClickListener(view -> {
            if (stylePreview) return;
            resourceEntry.setEnabled(false);
            Toast.makeText(this, "正在校验 RVA 资源版本…", Toast.LENGTH_SHORT).show();
            ((HousamoApplication) getApplication()).getRuntimeResourceUpdater()
                .requestCheck(message -> {
                    if (isFinishing() || isDestroyed()) return;
                    resourceEntry.setEnabled(true);
                    loadMetadataAsync();
                    new UiMaterialAlertDialogBuilder(this)
                        .setTitle(R.string.home_info_resource_version)
                        .setMessage(message)
                        .setPositiveButton(R.string.settings_rebuild_close, null)
                        .show();
                });
        });
        findViewById(R.id.card_home_tasks).setOnClickListener(
            view -> openTasks()
        );
        findViewById(R.id.card_home_management).setOnClickListener(
            view -> openManagement()
        );
        findViewById(R.id.card_home_sync).setOnClickListener(
            view -> openSceneSync()
        );
        updateCard.setOnClickListener(view -> {
            if (stylePreview) {
                return;
            }
            if (appUpdateAvailable) {
                openExternal(GITHUB_RELEASE_URL);
            } else {
                appUpdateRequested = false;
                requestAppUpdateCheck();
            }
        });
        findViewById(R.id.card_home_github).setOnClickListener(view -> {
            if (!stylePreview) {
                openExternal(GITHUB_URL);
            }
        });
    }

    private void openTasks() {
        if (stylePreview) {
            startActivity(StylePreview.intentFor(this, StylePreview.KIND_TASKS));
            finish();
            return;
        }
        if (!clickPrimaryDestination(R.id.nav_tasks)) {
            startActivity(new Intent(this, TranslationQueueActivity.class));
        }
    }

    private void openManagement() {
        if (stylePreview) {
            startActivity(StylePreview.intentFor(
                this,
                StylePreview.KIND_MANAGEMENT_HOME
            ));
            finish();
            return;
        }
        if (!clickPrimaryDestination(R.id.nav_management)) {
            startActivity(new Intent(this, ManagementHomeActivity.class));
        }
    }

    private void openSceneSync() {
        if (stylePreview) {
            startActivity(StylePreview.intentFor(
                this,
                StylePreview.KIND_SCENE_SYNC
            ));
            finish();
            return;
        }
        startActivity(new Intent(this, SceneFilesActivity.class));
    }

    private void installPreviewNavigation() {
        View navigation = findViewById(R.id.primary_navigation);
        if (navigation == null) {
            return;
        }
        View home = navigation.findViewById(R.id.nav_home);
        if (home != null) {
            home.setOnClickListener(view -> { });
        }
        View tasks = navigation.findViewById(R.id.nav_tasks);
        if (tasks != null) {
            tasks.setOnClickListener(view -> openTasks());
        }
        View management = navigation.findViewById(R.id.nav_management);
        if (management != null) {
            management.setOnClickListener(view -> openManagement());
        }
        View settings = navigation.findViewById(R.id.nav_settings);
        if (settings != null) {
            settings.setOnClickListener(view -> startActivity(
                new Intent(this, StylePreviewActivity.class).addFlags(
                    Intent.FLAG_ACTIVITY_CLEAR_TOP
                        | Intent.FLAG_ACTIVITY_SINGLE_TOP
                )
            ));
        }
    }

    private void loadStylePreview() {
        JSONObject payload = StylePreview.payloadOf(getIntent());
        if (payload == null) {
            payload = StylePreview.sample(StylePreview.KIND_HOME);
        }
        currentAppVersion = AppUpdateChecker.normalizeVersion(
            payload.optString("app_version", installedVersionOf(getPackageName()))
        );
        latestAppVersion = AppUpdateChecker.normalizeVersion(
            payload.optString("latest_app_version", "")
        );
        appUpdateChecked = !latestAppVersion.isEmpty();
        appUpdateAvailable = payload.has("latest_app_version")
            && !currentAppVersion.isEmpty()
            && !latestAppVersion.isEmpty()
            && !latestAppVersion.equals(currentAppVersion);
        resourceVersion = payload.optString("resource_version", "").trim();
        resourceUpdateAvailable = payload.optBoolean(
            "resource_update_available",
            false
        );
        gameVersion = payload.optString("game_version", "").trim();
        targetLanguage = payload.optString("target_language", "").trim();
        runtimeSnapshot = previewRuntimeSnapshot(payload.optJSONObject("runtime"));
        renderRuntimeSnapshot(runtimeSnapshot);
        renderMetadata();
        renderUpdateStatus();
    }

    private SceneSyncRuntimeState.Snapshot previewRuntimeSnapshot(
        JSONObject value
    ) {
        JSONObject runtime = value == null ? new JSONObject() : value;
        return new SceneSyncRuntimeState.Snapshot(
            runtime.optBoolean("service_available", false),
            runtime.optBoolean("game_port_available", false),
            enumValue(
                SceneSyncRuntimeState.Phase.class,
                runtime.optString("phase", "IDLE"),
                SceneSyncRuntimeState.Phase.IDLE
            ),
            Math.max(0, runtime.optInt("active_api_jobs", 0)),
            Math.max(0, runtime.optInt("pending_conflict_count", 0)),
            enumValue(
                SceneSyncRuntimeState.Action.class,
                runtime.optString("last_action", "NONE"),
                SceneSyncRuntimeState.Action.NONE
            ),
            enumValue(
                SceneSyncRuntimeState.Outcome.class,
                runtime.optString("last_outcome", "NONE"),
                SceneSyncRuntimeState.Outcome.NONE
            ),
            java.util.Collections.emptyList()
        );
    }

    private void startRuntimeObservation() {
        if (runtimeListening) {
            return;
        }
        runtimeListening = true;
        runtimeState.addListener(runtimeListener);
        runtimeRefreshHandler.removeCallbacks(runtimeRefresh);
        runtimeRefreshHandler.postDelayed(runtimeRefresh, 1000L);
    }

    private void stopRuntimeObservation() {
        runtimeRefreshHandler.removeCallbacks(runtimeRefresh);
        if (!runtimeListening) {
            return;
        }
        runtimeListening = false;
        runtimeState.removeListener(runtimeListener);
    }

    private void dispatchRuntimeSnapshot(
        SceneSyncRuntimeState.Snapshot changed
    ) {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            acceptRuntimeSnapshot(runtimeState.getSnapshot());
        } else {
            runOnUiThread(() -> acceptRuntimeSnapshot(runtimeState.getSnapshot()));
        }
    }

    private void acceptRuntimeSnapshot(
        SceneSyncRuntimeState.Snapshot changed
    ) {
        if (stylePreview || !runtimeListening || changed == null
            || isFinishing() || isDestroyed()) {
            return;
        }
        runtimeSnapshot = changed;
        renderRuntimeSnapshot(changed);
    }

    private void renderRuntimeSnapshot(
        SceneSyncRuntimeState.Snapshot snapshot
    ) {
        if (snapshot == null || statusCard == null) {
            return;
        }
        boolean syncing = snapshot.phase != SceneSyncRuntimeState.Phase.IDLE;
        boolean connected = snapshot.serviceAvailable
            && snapshot.gamePortAvailable;
        boolean serviceReady = snapshot.serviceAvailable;
        int background;
        int foreground;
        int icon;
        if (syncing) {
            background = R.color.het_primary_container;
            foreground = R.color.het_on_primary_container;
            icon = R.drawable.ic_home_syncing;
        } else if (connected) {
            background = R.color.het_good_container;
            foreground = R.color.het_good;
            icon = R.drawable.ic_home_status;
        } else if (serviceReady) {
            background = R.color.het_warning_container;
            foreground = R.color.het_warning;
            icon = R.drawable.ic_home_waiting_game;
        } else {
            background = R.color.het_surface_container_high;
            foreground = R.color.het_on_surface_muted;
            icon = R.drawable.ic_home_waiting_service;
        }
        int cardColor = ContextCompat.getColor(this, background);
        int textColor = ContextCompat.getColor(this, foreground);
        statusIcon.setImageResource(icon);
        statusCard.setCardBackgroundColor(cardColor);
        stateView.setText(
            syncing
                ? R.string.home_state_syncing
                : connected
                    ? R.string.home_state_connected
                    : serviceReady
                        ? R.string.home_state_waiting_game
                        : R.string.home_state_waiting_service
        );
        connectionView.setText(
            syncing
                ? R.string.home_connection_syncing
                : connected
                    ? R.string.home_connection_ready
                    : serviceReady
                        ? R.string.home_connection_waiting_game
                        : R.string.home_connection_waiting_service
        );
        stateView.setTextColor(textColor);
        connectionView.setTextColor(textColor);
        statusIcon.setColorFilter(textColor, PorterDuff.Mode.SRC_IN);

        int pending = snapshot.pendingConflictCount;
        conflictsView.setText(
            pending > 0
                ? getString(R.string.home_conflicts_count, pending)
                : getString(R.string.home_conflicts_none)
        );
    }

    private void loadMetadataAsync() {
        if (ioExecutor == null || stylePreview) {
            return;
        }
        final long generation = workGeneration;
        ioExecutor.execute(() -> {
            HomeMetadata metadata;
            try {
                ConfigStore store = new ConfigStore(getApplicationContext());
                ConfigStore.LoadResult config = store.load();
                JSONObject userSettings = config.config.optJSONObject(
                    "UserSettings"
                );
                metadata = new HomeMetadata(
                    installedVersionOf("jp.co.lifewonders.housamo"),
                    store.loadJson(ConfigStore.RUNTIME_FILE_NAME).json
                        .getString("GameVersion").trim(),
                    userSettings == null
                        ? ""
                        : userSettings.optString("TargetLanguage", "").trim()
                );
            } catch (Exception error) {
                Log.w(TAG, "Could not load home version metadata", error);
                metadata = new HomeMetadata("", "", "");
            }
            HomeMetadata loaded = metadata;
            runOnUiThread(() -> {
                if (generation != workGeneration || isFinishing() || isDestroyed()) {
                    return;
                }
                gameVersion = loaded.gameVersion;
                resourceVersion = loaded.resourceVersion;
                targetLanguage = loaded.targetLanguage;
                renderMetadata();
            });
        });
    }

    private void renderMetadata() {
        if (appVersionView == null) {
            return;
        }
        appVersionView.setText(
            appUpdateAvailable
                ? getString(
                    R.string.home_app_version_pair,
                    displayVersion(currentAppVersion),
                    displayVersion(latestAppVersion)
                )
                : displayVersionOrUnknown(currentAppVersion)
        );
        appVersionView.setTextColor(ContextCompat.getColor(
            this,
            appUpdateAvailable ? R.color.het_error : R.color.het_on_surface_muted
        ));

        if (stylePreview && !resourceVersion.isEmpty()) {
            resourceVersionView.setText(
                resourceUpdateAvailable
                    ? getString(
                        R.string.home_resource_version_preview_update,
                        AppUpdateChecker.normalizeVersion(resourceVersion)
                    )
                    : getString(
                        R.string.home_resource_version_preview_current,
                        AppUpdateChecker.normalizeVersion(resourceVersion)
                    )
            );
            resourceVersionView.setTextColor(ContextCompat.getColor(
                this,
                resourceUpdateAvailable
                    ? R.color.het_error
                    : R.color.het_on_surface_muted
            ));
        } else {
            resourceVersionView.setText(resourceVersion.isEmpty()
                ? getString(R.string.home_resource_version_unknown)
                : displayVersion(resourceVersion));
            resourceVersionView.setTextColor(ContextCompat.getColor(
                this,
                R.color.het_on_surface_muted
            ));
        }

        gameVersionView.setText(displayVersionOrUnknown(gameVersion));
        targetLanguageView.setText(displayTargetLanguage(targetLanguage));
        gameVersionView.setTextColor(ContextCompat.getColor(
            this,
            R.color.het_on_surface_muted
        ));
        targetLanguageView.setTextColor(ContextCompat.getColor(
            this,
            R.color.het_on_surface_muted
        ));
    }

    private void requestAppUpdateCheck() {
        if (ioExecutor == null || stylePreview || appUpdateInProgress) {
            return;
        }
        appUpdateRequested = true;
        appUpdateInProgress = true;
        appUpdateChecked = false;
        renderUpdateStatus();
        final long generation = workGeneration;
        ioExecutor.execute(() -> {
            String latest = "";
            boolean failed = false;
            try {
                latest = AppUpdateChecker.fetchLatestStableTag();
            } catch (Exception error) {
                failed = true;
                Log.w(TAG, "Could not check the latest GitHub release", error);
            }
            String loadedLatest = latest;
            boolean requestFailed = failed;
            runOnUiThread(() -> {
                if (generation != workGeneration || isFinishing() || isDestroyed()) {
                    return;
                }
                appUpdateInProgress = false;
                appUpdateChecked = true;
                latestAppVersion = loadedLatest;
                appUpdateAvailable = !requestFailed
                    && !currentAppVersion.isEmpty()
                    && !latestAppVersion.isEmpty()
                    && !latestAppVersion.equals(currentAppVersion);
                renderMetadata();
                renderUpdateStatus();
            });
        });
    }

    private void renderUpdateStatus() {
        if (updateStatusView == null) {
            return;
        }
        if (stylePreview) {
            if (appUpdateAvailable) {
                updateStatusView.setText(getString(
                    R.string.home_update_available,
                    displayVersion(latestAppVersion)
                ));
            } else if (latestAppVersion.isEmpty()) {
                updateStatusView.setText(R.string.home_update_not_checked);
            } else {
                updateStatusView.setText(R.string.home_update_latest);
            }
        } else if (appUpdateInProgress) {
            updateStatusView.setText(R.string.home_update_checking);
        } else if (!appUpdateChecked) {
            updateStatusView.setText(R.string.home_update_not_checked);
        } else if (appUpdateAvailable) {
            updateStatusView.setText(getString(
                R.string.home_update_available,
                displayVersion(latestAppVersion)
            ));
        } else if (latestAppVersion.isEmpty()) {
            updateStatusView.setText(R.string.home_update_failed);
        } else {
            updateStatusView.setText(R.string.home_update_latest);
        }
        updateStatusView.setTextColor(ContextCompat.getColor(
            this,
            appUpdateAvailable
                ? R.color.het_error
                : R.color.het_on_primary_container
        ));
    }

    private void openExternal(String url) {
        try {
            startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url)));
        } catch (RuntimeException error) {
            Toast.makeText(
                this,
                R.string.home_update_open_failed,
                Toast.LENGTH_LONG
            ).show();
        }
    }

    private boolean clickPrimaryDestination(int destinationId) {
        View navigation = findViewById(R.id.primary_navigation);
        View destination = navigation == null
            ? null
            : navigation.findViewById(destinationId);
        return destination != null && destination.performClick();
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
            1001
        );
    }

    @Override
    public void onRequestPermissionsResult(
        int requestCode,
        String[] permissions,
        int[] grantResults
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == 1001
            && grantResults.length > 0
            && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
            TranslationStatusNotification.refresh(this);
        }
    }

    private String installedVersionOf(String packageName) {
        try {
            String version = getPackageManager().getPackageInfo(
                packageName,
                0
            ).versionName;
            return AppUpdateChecker.normalizeVersion(version);
        } catch (PackageManager.NameNotFoundException error) {
            return "";
        }
    }

    private String displayTargetLanguage(String value) {
        String normalized = value == null
            ? ""
            : value.trim().toLowerCase(Locale.ROOT).replace('_', '-');
        if ("zh-cn".equals(normalized)) {
            return getString(R.string.settings_option_zh_cn);
        }
        if ("zh-tw".equals(normalized)) {
            return getString(R.string.settings_option_zh_tw);
        }
        if ("en".equals(normalized)) {
            return getString(R.string.settings_option_en);
        }
        return value == null || value.trim().isEmpty()
            ? getString(R.string.home_unknown)
            : value.trim();
    }

    private String displayVersionOrUnknown(String value) {
        String normalized = AppUpdateChecker.normalizeVersion(value);
        return normalized.isEmpty()
            ? getString(R.string.home_unknown)
            : displayVersion(normalized);
    }

    private String displayVersion(String value) {
        String normalized = AppUpdateChecker.normalizeVersion(value);
        return normalized.isEmpty() ? getString(R.string.home_unknown) : "v" + normalized;
    }

    private static <T extends Enum<T>> T enumValue(
        Class<T> type,
        String value,
        T fallback
    ) {
        if (value == null) {
            return fallback;
        }
        try {
            return Enum.valueOf(
                type,
                value.trim().toUpperCase(Locale.ROOT)
            );
        } catch (IllegalArgumentException error) {
            return fallback;
        }
    }

    private static final class HomeMetadata {
        final String gameVersion;
        final String resourceVersion;
        final String targetLanguage;

        HomeMetadata(String gameVersion, String resourceVersion, String targetLanguage) {
            this.gameVersion = gameVersion == null ? "" : gameVersion;
            this.resourceVersion = resourceVersion == null ? "" : resourceVersion;
            this.targetLanguage = targetLanguage == null ? "" : targetLanguage;
        }
    }
}
