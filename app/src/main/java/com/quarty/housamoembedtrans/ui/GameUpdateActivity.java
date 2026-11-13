package com.quarty.housamoembedtrans.ui;

import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.provider.Settings;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;

import com.quarty.housamoembedtrans.R;
import com.quarty.housamoembedtrans.bridge.HetBridgeContract;
import com.quarty.housamoembedtrans.update.GameUpdateController;
import com.quarty.housamoembedtrans.update.GameUpdateController.Phase;
import com.quarty.housamoembedtrans.update.GameUpdateController.Snapshot;

/** Dialog-shaped Activity keeps the update reachable across system settings and installer screens. */
public final class GameUpdateActivity extends AppCompatActivity {
    private GameUpdateController controller;
    private AlertDialog dialog;
    private TextView heading, versions, detail, progressLabel;
    private final TextView[] steps = new TextView[4];
    private View versionCard, stepList;
    private ProgressBar progress;
    private boolean resumed, returningFromPermission;
    private int launchedSession = -1;
    private final GameUpdateController.Listener listener = this::render;

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        controller = GameUpdateController.get(this);
        if (state != null) {
            returningFromPermission = state.getBoolean("permission");
            launchedSession = state.getInt("launchedSession", -1);
        }
        View view = getLayoutInflater().inflate(R.layout.dialog_game_update, null);
        heading = view.findViewById(R.id.game_update_heading);
        versions = view.findViewById(R.id.game_update_versions);
        versionCard = view.findViewById(R.id.game_update_version_card);
        detail = view.findViewById(R.id.game_update_detail);
        progress = view.findViewById(R.id.game_update_progress);
        progressLabel = view.findViewById(R.id.game_update_progress_label);
        stepList = view.findViewById(R.id.game_update_steps);
        int[] ids = {R.id.game_update_step_download, R.id.game_update_step_obb,
            R.id.game_update_step_apk, R.id.game_update_step_runtime};
        for (int i = 0; i < ids.length; i++) steps[i] = view.findViewById(ids[i]);
        dialog = new UiMaterialAlertDialogBuilder(this)
            .setTitle(R.string.game_update_title).setView(view)
            .setPositiveButton(R.string.game_update_start, null)
            .setNegativeButton(R.string.game_update_close, null)
            .setNeutralButton(R.string.game_update_recheck, null).create();
        dialog.setCanceledOnTouchOutside(false);
        dialog.setOnCancelListener(ignored -> requestClose());
        dialog.show();
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> primary());
        dialog.getButton(AlertDialog.BUTTON_NEGATIVE).setOnClickListener(v -> requestClose());
        dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener(v -> {
            Snapshot s = controller.snapshot();
            controller.checkAgain(s.phase == Phase.SIGNATURE);
        });
        if (dialog.getWindow() != null) {
            int width = Math.min(dp(440), getResources().getDisplayMetrics().widthPixels - dp(24));
            dialog.getWindow().setLayout(width, ViewGroup.LayoutParams.WRAP_CONTENT);
        }
        // On short/landscape screens the body scrolls, while dialog actions remain accessible.
        view.findViewById(R.id.game_update_scroll).getLayoutParams().height =
            Math.min(dp(420), Math.max(dp(120), getResources().getDisplayMetrics().heightPixels - dp(210)));
        render(controller.snapshot());
    }

    @Override protected void onStart() { super.onStart(); controller.addListener(listener); }
    @Override protected void onResume() {
        super.onResume();
        resumed = true;
        if (returningFromPermission) {
            returningFromPermission = false;
            if (getPackageManager().canRequestPackageInstalls()) controller.continueUpdate();
        } else controller.open();
        render(controller.snapshot());
    }
    @Override protected void onPause() { resumed = false; super.onPause(); }
    @Override protected void onStop() { controller.removeListener(listener); super.onStop(); }
    @Override protected void onDestroy() { if (dialog != null) dialog.dismiss(); super.onDestroy(); }
    @Override protected void onSaveInstanceState(Bundle out) {
        out.putBoolean("permission", returningFromPermission);
        out.putInt("launchedSession", launchedSession);
        super.onSaveInstanceState(out);
    }

    private void render(Snapshot s) {
        if (dialog == null || isFinishing() || isDestroyed()) return;
        int title = R.string.game_update_found;
        switch (s.phase) {
            case CHECKING: title = R.string.game_update_checking; break;
            case READY: title = s.upgrade ? R.string.game_update_found : R.string.game_update_current; break;
            case DOWNLOADING: title = R.string.game_update_preparing; break;
            case WRITING_OBB: title = R.string.game_update_saving_obb; break;
            case INSTALLING: case CONFIRM_INSTALL: case READY_INSTALL: title = R.string.game_update_install; break;
            case RUNTIME: title = R.string.game_update_finishing; break;
            case COMPLETE: title = R.string.game_update_complete; break;
            case NEED_GAME: title = R.string.game_update_connect; break;
            case SIGNATURE: title = R.string.game_update_signature; break;
            case PERMISSION: title = R.string.game_update_permission; break;
            case CANCELED: title = R.string.game_update_canceled; break;
            case ERROR: title = s.runtimePending ? R.string.game_update_runtime_pending : R.string.game_update_incomplete; break;
        }
        heading.setText(title);
        versionCard.setVisibility(s.hasPlan ? View.VISIBLE : View.GONE);
        versions.setText(s.upgrade ? getString(R.string.game_update_versions, s.source, s.target)
            : getString(R.string.game_update_version, s.target));
        detail.setText(s.detail);
        int step = s.phase == Phase.DOWNLOADING ? 0 : s.phase == Phase.WRITING_OBB ? 1
            : s.phase == Phase.INSTALLING || s.phase == Phase.CONFIRM_INSTALL || s.phase == Phase.READY_INSTALL ? 2
            : s.phase == Phase.RUNTIME || s.runtimePending ? 3 : s.phase == Phase.COMPLETE ? 4 : -1;
        stepList.setVisibility(step >= 0 ? View.VISIBLE : View.GONE);
        int[] labels = {R.string.game_update_download_step, R.string.game_update_obb_step,
            R.string.game_update_apk_step, R.string.game_update_runtime_step};
        for (int i = 0; i < steps.length; i++) {
            steps[i].setVisibility(i == 2 && !s.upgrade ? View.GONE : View.VISIBLE);
            steps[i].setText((i < step ? "✓  " : i == step ? "●  " : "○  ") + getString(labels[i]));
            steps[i].setTextColor(getColor(i <= step ? R.color.het_primary : R.color.het_on_surface_muted));
        }
        boolean progressVisible = s.busy && s.phase != Phase.READY && s.phase != Phase.COMPLETE;
        progress.setVisibility(progressVisible ? View.VISIBLE : View.GONE);
        progressLabel.setVisibility(progressVisible && s.total > 0 ? View.VISIBLE : View.GONE);
        progress.setIndeterminate(s.total <= 0);
        if (s.total > 0) {
            int percent = (int) Math.min(100, s.received * 100.0 / s.total);
            progress.setProgress(percent);
            progressLabel.setText(getString(R.string.game_update_progress, s.received / 1_000_000.0, s.total / 1_000_000.0, percent));
        }
        int action = s.phase == Phase.COMPLETE || s.phase == Phase.NEED_GAME ? R.string.game_update_launch
            : s.phase == Phase.PERMISSION ? R.string.game_update_allow
            : s.phase == Phase.CONFIRM_INSTALL ? R.string.game_update_confirm
            : s.phase == Phase.INSTALLING ? R.string.game_update_restart_install
            : s.phase == Phase.READY_INSTALL ? R.string.game_update_continue_install
            : s.runtimePending ? R.string.game_update_retry_runtime
            : s.phase == Phase.ERROR || s.phase == Phase.CANCELED ? R.string.game_update_retry
            : !s.upgrade ? R.string.game_update_repair : R.string.game_update_start;
        TextView positive = dialog.getButton(AlertDialog.BUTTON_POSITIVE);
        positive.setText(s.phase == Phase.READY && s.upgrade
            ? getString(R.string.game_update_start_size, s.downloadSize / 1_000_000.0) : getString(action));
        positive.setEnabled(!s.busy && s.phase != Phase.CHECKING && s.phase != Phase.SIGNATURE);
        TextView negative = dialog.getButton(AlertDialog.BUTTON_NEGATIVE);
        negative.setText(s.busy && s.phase == Phase.DOWNLOADING ? R.string.game_update_cancel_download : R.string.game_update_close);
        TextView neutral = dialog.getButton(AlertDialog.BUTTON_NEUTRAL);
        neutral.setText(s.phase == Phase.SIGNATURE ? R.string.game_update_repair_current : R.string.game_update_recheck);
        neutral.setVisibility(!s.busy && (s.phase == Phase.COMPLETE || s.phase == Phase.ERROR
            || s.phase == Phase.SIGNATURE) ? View.VISIBLE : View.GONE);
        if (resumed && s.phase == Phase.CONFIRM_INSTALL && launchedSession != s.session) openConfirmation();
    }

    private void primary() {
        Snapshot s = controller.snapshot();
        if (s.busy) return;
        if (s.phase == Phase.NEED_GAME || s.phase == Phase.COMPLETE) { launchGame(); return; }
        if (s.phase == Phase.CONFIRM_INSTALL && openConfirmation()) return;
        if (s.phase == Phase.INSTALLING) {
            new UiMaterialAlertDialogBuilder(this).setTitle(R.string.game_update_restart_install)
                .setMessage(R.string.game_update_restart_message)
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton(R.string.game_update_continue_install, (d, w) -> controller.continueUpdate()).show();
            return;
        }
        if (s.hasPlan && s.upgrade && !getPackageManager().canRequestPackageInstalls()
            && s.phase != Phase.ERROR) {
            returningFromPermission = true;
            try { startActivity(new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:" + getPackageName()))); }
            catch (RuntimeException error) { returningFromPermission = false; showError(error); }
            return;
        }
        if (!s.hasPlan) controller.checkAgain(false); else controller.continueUpdate();
    }

    private boolean openConfirmation() {
        Intent intent = controller.takeConfirmation();
        if (intent == null) return false;
        try {
            launchedSession = controller.snapshot().session;
            startActivity(intent);
            return true;
        } catch (RuntimeException error) { showError(error); return false; }
    }

    private void launchGame() {
        Intent intent = getPackageManager().getLaunchIntentForPackage(HetBridgeContract.TARGET_PACKAGE);
        if (intent == null) { Toast.makeText(this, R.string.game_update_game_missing, Toast.LENGTH_LONG).show(); return; }
        try { startActivity(intent); if (controller.snapshot().phase == Phase.COMPLETE) { controller.dismissCompleted(); finish(); } }
        catch (RuntimeException error) { showError(error); }
    }

    private void requestClose() {
        Snapshot s = controller.snapshot();
        if (s.busy && s.phase == Phase.DOWNLOADING) {
            new UiMaterialAlertDialogBuilder(this).setTitle(R.string.game_update_cancel_download)
                .setMessage(R.string.game_update_cancel_message)
                .setNegativeButton(R.string.game_update_keep_downloading, (d, w) -> { if (!dialog.isShowing()) dialog.show(); })
                .setPositiveButton(R.string.game_update_cancel_download, (d, w) -> { controller.cancelDownload(); finish(); }).show();
        } else {
            if (s.phase == Phase.COMPLETE) controller.dismissCompleted();
            finish();
        }
    }

    private void showError(Exception error) { Toast.makeText(this, error.getMessage(), Toast.LENGTH_LONG).show(); }
    private int dp(int value) { return Math.round(value * getResources().getDisplayMetrics().density); }
}
