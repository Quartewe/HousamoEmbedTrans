package com.quarty.housamoembedtrans.ui;

import com.quarty.housamoembedtrans.R;
import com.quarty.housamoembedtrans.bridge.HetBridgeContract;
import com.quarty.housamoembedtrans.management.pending.PendingProcessManager;
import com.quarty.housamoembedtrans.management.pending.PendingProcessControlClient;
import com.quarty.housamoembedtrans.management.pending.PendingProcessStore;
import com.quarty.housamoembedtrans.scene.store.SceneStore;
import com.quarty.housamoembedtrans.translation.TranslationService;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.util.AtomicFile;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.activity.OnBackPressedCallback;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;

import com.google.android.material.appbar.MaterialToolbar;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.IOException;
import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Embedded batch mode for a management page.  The host page remains the
 * navigation owner; this controller only adds a temporary selection panel to
 * its existing content container and consumes the host's current adapter
 * snapshot/filter.  Selection identity is shared through
 * {@link ManagementBatchSelection}.
 */
public final class ManagementBatchController implements AutoCloseable {
    public static final String KIND_SCENE = "scene";
    public static final String KIND_CONTEXT = "context";
    public static final String KIND_GROUP = "group";
    public static final String KIND_CHARACTER = "character";
    public static final String KIND_TERM = "term";
    public static final String KIND_LANGUAGE = "language";

    private static final String STATE_ACTIVE = "management_batch.active";
    private static final String STATE_KIND = "management_batch.kind";
    private static final String STATE_SELECTED = "management_batch.selected";
    private static final String STATE_EXPORT_TOKEN =
        "management_batch.export_token";
    private static final String STATE_EXPORT_PICKER_STARTED =
        "management_batch.export_picker_started";
    private static final String EXPORT_DIRECTORY =
        "management_batch_exports";
    private static final int MAX_EXPORT_BYTES =
        ManagementTransfer.MAX_DOCUMENT_BYTES;

    private final AppCompatActivity activity;
    private final ViewGroup content;
    private final BatchDataSource dataSource;
    private final ExecutorService executor =
        Executors.newSingleThreadExecutor();
    private final PendingProcessControlClient pendingClient;
    private final Set<String> ownedKinds;
    private final ActivityResultLauncher<Intent> exportLauncher;
    private final OnBackPressedCallback backCallback;
    private View panel;
    private TextView summaryView;
    private MaterialButton selectAllButton;
    private MaterialButton invertButton;
    private MaterialButton clearButton;
    private MaterialButton exportButton;
    private MaterialButton moveButton;
    private MaterialButton exitButton;
    private String activeKind;
    private volatile boolean active;
    private volatile boolean lifecycleStarted;
    private boolean busy;
    private String pendingExportToken;
    private boolean exportPickerStarted;

    /** Immutable adapter row consumed by the shared batch surface. */
    public static final class Item {
        public final String kind;
        public final String canonicalId;
        public final String label;
        public final JSONObject payload;

        public Item(
            String kind,
            String canonicalId,
            String label,
            JSONObject payload
        ) {
            this.kind = kind == null ? "" : kind;
            this.canonicalId = canonicalId == null ? "" : canonicalId;
            this.label = label == null ? this.canonicalId : label;
            JSONObject copy = payload == null ? new JSONObject() : payload;
            try {
                this.payload = new JSONObject(copy.toString());
            } catch (Exception ignored) {
                throw new IllegalArgumentException(
                    "batch item payload is not JSON",
                    ignored
                );
            }
        }

        public String key() {
            return kind + ":" + canonicalId;
        }
    }

    /**
     * Adapter-facing seam implemented by each current management tab.  It
     * deliberately returns the same live rows used by that tab, rather than
     * making the controller reopen a second top-level management page.
     */
    public interface BatchDataSource {
        String initialKind();

        /**
         * Kinds for which this host returns a complete snapshot.  An empty
         * result for one of these kinds is still a successful snapshot and
         * must prune that kind's stale selection identities.
         */
        Set<String> ownedKinds();

        String currentFilter();

        /**
         * Complete host snapshot used only to register immutable payloads.
         * The controller never renders this list or applies a second filter.
         */
        List<Item> snapshotItems() throws Exception;

        /**
         * Exactly the rows currently visible in the host adapter/list.  The
         * host owns filtering, ordering and scroll position.
         */
        default List<Item> currentVisibleItems() throws Exception {
            return snapshotItems();
        }

        void onBatchModeChanged(boolean enabled);

        /** Called after toolbar selection mutates the shared identity set. */
        default void onBatchSelectionChanged() {
        }

        /**
         * Called on the host UI thread after the service durably moved the
         * listed identities. Failed identities remain selected for retry.
         */
        default void onBatchItemsMoved(List<String> succeededKeys) {
        }
    }

    public static ManagementBatchController attach(
        AppCompatActivity activity,
        View root,
        BatchDataSource dataSource,
        Bundle savedState
    ) {
        if (activity == null || root == null || dataSource == null) {
            throw new IllegalArgumentException(
                "activity, root and dataSource are required"
            );
        }
        if (!(root instanceof ViewGroup)) {
            throw new IllegalArgumentException("batch root must be a ViewGroup");
        }
        ViewGroup rootGroup = (ViewGroup) root;
        ViewGroup content = rootGroup;
        if (rootGroup.getChildCount() == 1
            && rootGroup.getChildAt(0) instanceof ViewGroup) {
            content = (ViewGroup) rootGroup.getChildAt(0);
        }
        return new ManagementBatchController(
            activity,
            content,
            dataSource,
            savedState
        );
    }

    private ManagementBatchController(
        AppCompatActivity activity,
        ViewGroup content,
        BatchDataSource dataSource,
        Bundle savedState
    ) {
        this.activity = activity;
        this.content = content;
        this.dataSource = dataSource;
        activeKind = normalizeKind(dataSource.initialKind());
        ownedKinds = normalizeOwnedKinds(dataSource.ownedKinds());
        if (!ownedKinds.contains(activeKind)) {
            throw new IllegalArgumentException(
                "initial kind must be declared as owned"
            );
        }
        pendingClient = new PendingProcessControlClient(activity);
        backCallback = new OnBackPressedCallback(false) {
            @Override
            public void handleOnBackPressed() {
                exit();
            }
        };
        activity.getOnBackPressedDispatcher().addCallback(
            activity,
            backCallback
        );
        exportLauncher = activity.registerForActivityResult(
            new ActivityResultContracts.StartActivityForResult(),
            result -> onExportResult(result.getResultCode(), result.getData())
        );
        if (savedState != null) {
            restoreState(savedState);
        }
        boolean resume = active;
        active = false;
        if (resume) {
            enter();
        }
    }

    public void onStart() {
        lifecycleStarted = true;
        pendingClient.setConnectionListener(
            this::onPendingConnectionChanged
        );
        try {
            Intent intent = new Intent(activity, TranslationService.class)
                .setPackage(activity.getPackageName())
                .setAction(HetBridgeContract.ACTION_START_TRANSLATION_SERVICE);
            ContextCompat.startForegroundService(activity, intent);
            pendingClient.bind();
        } catch (RuntimeException ignored) {
            // Move will report a disconnected Service while export remains
            // usable from the current tab.
        }
        // onStop clears the listener before unbinding, so there may be no
        // callback to repaint a restored panel on the next start.  Recompute
        // action availability from the current connection state without
        // touching the shared selection.
        if (active && isUiActive()) {
            updateActions();
        }
    }

    public void onStop() {
        lifecycleStarted = false;
        pendingClient.setConnectionListener(null);
        pendingClient.unbind();
    }

    /** Binder callbacks can arrive after onStop; only refresh a live panel. */
    private void onPendingConnectionChanged(boolean connected) {
        if (!lifecycleStarted || !active || !isUiActive()) {
            return;
        }
        try {
            activity.runOnUiThread(() -> {
                if (!lifecycleStarted || !active || !isUiActive()) {
                    return;
                }
                // Connection state only affects action availability.  Do not
                // touch the process-wide selection or host rows here.
                updateActions();
            });
        } catch (RuntimeException ignored) {
            // Activity teardown raced the Binder callback.
        }
    }

    /** Host calls this after its adapter/list has been re-rendered. */
    public void onHostRowsChanged() {
        if (active && isUiActive()) {
            refreshSummary();
        }
    }

    /** Re-reads the host store after an asynchronous host render completed. */
    public void refreshHostCatalog() {
        if (active && isUiActive()) {
            refreshCatalog();
        }
    }

    public boolean isActive() {
        return active;
    }

    public void saveState(Bundle outState) {
        if (outState == null) {
            return;
        }
        outState.putBoolean(STATE_ACTIVE, active);
        outState.putString(STATE_KIND, activeKind);
        outState.putStringArrayList(
            STATE_SELECTED,
            new ArrayList<>(ManagementBatchSelection.snapshot())
        );
        // A token prepared before the picker is launched cannot receive a
        // result after recreation.  Only the picker-owned phase is restored;
        // preparation is safely retried by the user on the new Activity.
        outState.putString(
            STATE_EXPORT_TOKEN,
            exportPickerStarted ? pendingExportToken : null
        );
        outState.putBoolean(
            STATE_EXPORT_PICKER_STARTED,
            exportPickerStarted
        );
    }

    public void enter() {
        if (active || activity.isFinishing() || activity.isDestroyed()) {
            return;
        }
        active = true;
        backCallback.setEnabled(true);
        panel = buildPanel();
        content.addView(panel, panelInsertIndex());
        dataSource.onBatchModeChanged(true);
        refreshCatalog();
        refreshSummary();
    }

    /** Keep the temporary batch surface below the host toolbar. */
    private int panelInsertIndex() {
        for (int index = 0; index < content.getChildCount(); index++) {
            if (content.getChildAt(index) instanceof MaterialToolbar) {
                return index + 1;
            }
        }
        return 0;
    }

    public void exit() {
        if (!active) {
            return;
        }
        active = false;
        backCallback.setEnabled(false);
        busy = false;
        if (panel != null) {
            content.removeView(panel);
            panel = null;
        }
        dataSource.onBatchModeChanged(false);
    }

    private View buildPanel() {
        LinearLayout root = new LinearLayout(activity);
        root.setOrientation(LinearLayout.VERTICAL);
        int padding = Math.round(
            16 * activity.getResources().getDisplayMetrics().density
        );
        root.setPadding(padding, padding, padding, padding);

        LinearLayout heading = new LinearLayout(activity);
        heading.setOrientation(LinearLayout.HORIZONTAL);
        TextView title = new TextView(activity);
        title.setText(R.string.management_batch_title);
        title.setTextAppearance(
            activity,
            com.google.android.material.R.style.TextAppearance_MaterialComponents_Headline6
        );
        heading.addView(title, new LinearLayout.LayoutParams(
            0,
            LinearLayout.LayoutParams.WRAP_CONTENT,
            1
        ));
        exitButton = new MaterialButton(activity);
        exitButton.setText(R.string.back_action);
        exitButton.setOnClickListener(view -> exit());
        heading.addView(exitButton);
        root.addView(heading);

        TextView hint = new TextView(activity);
        hint.setText(R.string.management_batch_hint);
        root.addView(hint);

        summaryView = new TextView(activity);
        root.addView(summaryView);

        LinearLayout actions = new LinearLayout(activity);
        actions.setOrientation(LinearLayout.HORIZONTAL);
        selectAllButton = actionButton(
            R.string.management_batch_select_all,
            view -> selectVisible(true)
        );
        invertButton = actionButton(
            R.string.management_batch_invert,
            view -> invertVisible()
        );
        clearButton = actionButton(
            R.string.management_batch_clear,
            view -> {
                if (!busy) {
                    ManagementBatchSelection.clear();
                    dataSource.onBatchSelectionChanged();
                    refreshSummary();
                }
            }
        );
        actions.addView(selectAllButton);
        actions.addView(invertButton);
        actions.addView(clearButton);
        root.addView(actions);

        LinearLayout transfers = new LinearLayout(activity);
        transfers.setOrientation(LinearLayout.HORIZONTAL);
        exportButton = actionButton(
            R.string.management_batch_export,
            view -> beginExport()
        );
        moveButton = actionButton(
            R.string.pending_process_move,
            view -> beginMove()
        );
        transfers.addView(exportButton, new LinearLayout.LayoutParams(
            0,
            LinearLayout.LayoutParams.WRAP_CONTENT,
            1
        ));
        transfers.addView(moveButton, new LinearLayout.LayoutParams(
            0,
            LinearLayout.LayoutParams.WRAP_CONTENT,
            1
        ));
        root.addView(transfers);
        return root;
    }

    private MaterialButton actionButton(int textId, View.OnClickListener listener) {
        MaterialButton button = new MaterialButton(activity);
        button.setText(textId);
        button.setAllCaps(false);
        button.setOnClickListener(listener);
        return button;
    }

    /** Refresh only the immutable payload catalog; host rows remain untouched. */
    private void refreshCatalog() {
        if (!active) {
            return;
        }
        setBusy(true);
        executor.execute(() -> {
            try {
                List<Item> loaded = dataSource.snapshotItems();
                PendingProcessStore.ReferenceSnapshot pending =
                    new PendingProcessStore(activity).snapshotReferences();
                ArrayList<String> live = new ArrayList<>();
                if (loaded != null) {
                    validateItems(loaded);
                    for (Item item : loaded) {
                        if (pending.isPending(
                            item.kind,
                            item.canonicalId
                        )) {
                            continue;
                        }
                        ManagementBatchSelection.register(
                            item.kind,
                            item.canonicalId,
                            item.label,
                            item.payload
                        );
                        live.add(item.key());
                    }
                }
                activity.runOnUiThread(() -> {
                    if (!active || !isUiActive()) {
                        return;
                    }
                    for (String kind : ownedKinds) {
                        ManagementBatchSelection.retainKindAll(kind, live);
                    }
                    setBusy(false);
                    refreshSummary();
                    dataSource.onBatchSelectionChanged();
                });
            } catch (Exception error) {
                activity.runOnUiThread(() -> {
                    if (!active || !isUiActive()) {
                        return;
                    }
                    setBusy(false);
                    showFailure(activity.getString(
                        R.string.management_batch_failed,
                        0,
                        1
                    ));
                });
            }
        });
    }

    /** Reads the host's filtered adapter rows; no controller-side filtering. */
    private List<Item> visibleItems() throws Exception {
        List<Item> visible = dataSource.currentVisibleItems();
        if (visible == null) {
            return Collections.emptyList();
        }
        validateItems(visible);
        for (Item item : visible) {
            ManagementBatchSelection.register(
                item.kind,
                item.canonicalId,
                item.label,
                item.payload
            );
        }
        return visible;
    }

    private void validateItems(List<Item> items) throws IOException {
        if (items == null) {
            return;
        }
        for (Item item : items) {
            if (item == null) {
                throw new IOException("batch snapshot contains a null item");
            }
            if (!ownedKinds.contains(item.kind)) {
                throw new IOException(
                    "batch snapshot returned an unowned kind: " + item.kind
                );
            }
        }
    }

    private void selectVisible(boolean selected) {
        if (busy) {
            return;
        }
        try {
            List<String> keys = new ArrayList<>();
            for (Item item : visibleItems()) {
                if (item != null) {
                    keys.add(item.key());
                }
            }
            if (selected) {
                ManagementBatchSelection.selectAll(keys);
            } else {
                ManagementBatchSelection.removeAll(keys);
            }
            dataSource.onBatchSelectionChanged();
            refreshSummary();
        } catch (Exception error) {
            showFailure(safeMessage(error));
        }
    }

    private void invertVisible() {
        if (busy) {
            return;
        }
        try {
            for (Item item : visibleItems()) {
                if (item != null) {
                    ManagementBatchSelection.set(
                        item.key(),
                        !ManagementBatchSelection.contains(item.key())
                    );
                }
            }
            dataSource.onBatchSelectionChanged();
            refreshSummary();
        } catch (Exception error) {
            showFailure(safeMessage(error));
        }
    }

    private List<Item> selectedItems() {
        List<Item> output = new ArrayList<>();
        for (ManagementBatchSelection.Entry entry
            : ManagementBatchSelection.selectedEntries()) {
            output.add(new Item(
                entry.kind,
                entry.canonicalId,
                entry.label,
                entry.payload
            ));
        }
        return output;
    }

    private int selectedKeyCount() {
        return ManagementBatchSelection.snapshot().size();
    }

    private int visibleCount() {
        try {
            return visibleItems().size();
        } catch (Exception ignored) {
            return 0;
        }
    }

    private void refreshSummary() {
        if (summaryView == null) {
            return;
        }
        int selected = selectedKeyCount();
        int visible = visibleCount();
        summaryView.setText(activity.getString(
            R.string.management_batch_summary,
            activeKind,
            visible,
            selected
        ));
        updateActions(selected, visible);
    }

    private void updateActions() {
        refreshSummary();
    }

    private void updateActions(int selected, int visible) {
        if (selectAllButton == null) {
            return;
        }
        boolean enabled = !busy;
        selectAllButton.setEnabled(enabled && visible > 0);
        invertButton.setEnabled(enabled && visible > 0);
        clearButton.setEnabled(enabled && selected > 0);
        boolean exportable = false;
        for (Item item : selectedItems()) {
            exportable |= isExportable(item.kind);
        }
        exportButton.setEnabled(enabled && exportable);
        moveButton.setEnabled(
            enabled && selected > 0 && pendingClient.isConnected()
        );
        exitButton.setEnabled(enabled);
    }

    private void beginMove() {
        if (busy) {
            return;
        }
        List<Item> selected = selectedItems();
        if (selected.isEmpty()) {
            if (selectedKeyCount() > 0) {
                showFailure("selection payload is unavailable; refresh the host list");
            }
            return;
        }
        if (selected.size() != selectedKeyCount()) {
            showFailure("selection payload is unavailable; refresh the host list");
            return;
        }
        selected.sort(ManagementBatchController::comparePendingMoveItems);
        for (Item item : selected) {
            if (KIND_CHARACTER.equals(item.kind)
                && "mc".equals(item.canonicalId)) {
                new MaterialAlertDialogBuilder(activity)
                    .setMessage(R.string.management_batch_main_character_rejected)
                    .setPositiveButton(android.R.string.ok, null)
                    .show();
                return;
            }
        }
        if (!pendingClient.isConnected()) {
            showFailure("TranslationService is not connected");
            return;
        }
        setBusy(true);
        executor.execute(() -> {
            List<Item> previewed = new ArrayList<>();
            List<String> previewFailures = new ArrayList<>();
            for (Item item : selected) {
                try {
                    pendingClient.previewPendingMove(
                        item.kind,
                        item.canonicalId
                    );
                    previewed.add(item);
                } catch (Exception error) {
                    previewFailures.add(
                        item.label + ": " + safeMessage(error)
                    );
                }
            }
            activity.runOnUiThread(() -> {
                if (!isUiActive()) {
                    return;
                }
                if (previewed.isEmpty()) {
                    setBusy(false);
                    showFailureLines(previewFailures, 0);
                    return;
                }
                showMoveConfirmation(previewed, previewFailures);
            });
        });
    }

    private static int comparePendingMoveItems(Item left, Item right) {
        int priority = Integer.compare(
            pendingMovePriority(left.kind),
            pendingMovePriority(right.kind)
        );
        return priority != 0
            ? priority
            : left.key().compareToIgnoreCase(right.key());
    }

    private static int pendingMovePriority(String kind) {
        if (KIND_LANGUAGE.equals(kind) || KIND_CONTEXT.equals(kind)) {
            return 0;
        }
        if (KIND_SCENE.equals(kind) || KIND_GROUP.equals(kind)) {
            return 2;
        }
        return 1;
    }

    private void showMoveConfirmation(
        List<Item> items,
        List<String> previewFailures
    ) {
        StringBuilder message = new StringBuilder();
        for (Item item : items) {
            if (message.length() > 0) {
                message.append('\n');
            }
            message.append("• ").append(item.label);
        }
        if (!previewFailures.isEmpty()) {
            message.append('\n').append(activity.getString(
                R.string.management_batch_preview_failures,
                previewFailures.size()
            ));
        }
        new MaterialAlertDialogBuilder(activity)
            .setTitle(R.string.management_batch_move_title)
            .setMessage(activity.getString(
                R.string.management_batch_move_message,
                items.size(),
                message.toString()
            ))
            .setNegativeButton(
                R.string.cancel_action,
                (dialog, which) -> setBusy(false)
            )
            .setPositiveButton(
                R.string.pending_process_move,
                (dialog, which) -> executeMove(items)
            )
            .setOnCancelListener(dialog -> setBusy(false))
            .show();
    }

    private void executeMove(List<Item> selected) {
        executor.execute(() -> {
            List<String> moved = new ArrayList<>();
            List<String> failures = new ArrayList<>();
            for (Item item : selected) {
                try {
                    pendingClient.movePendingProcess(
                        item.kind,
                        item.canonicalId,
                        PendingProcessMoveController.REASON_USER_REQUESTED
                    );
                    moved.add(item.key());
                } catch (Exception error) {
                    failures.add(item.label + ": " + safeMessage(error));
                }
            }
            activity.runOnUiThread(() -> {
                if (!active || !isUiActive()) {
                    return;
                }
                ManagementBatchSelection.removeAll(moved);
                setBusy(false);
                dataSource.onBatchItemsMoved(moved);
                refreshCatalog();
                dataSource.onBatchSelectionChanged();
                if (failures.isEmpty()) {
                    android.widget.Toast.makeText(
                        activity,
                        activity.getString(
                            R.string.management_batch_moved,
                            moved.size()
                        ),
                        android.widget.Toast.LENGTH_SHORT
                    ).show();
                } else {
                    showFailureLines(failures, moved.size());
                }
            });
        });
    }

    private void beginExport() {
        if (busy || pendingExportToken != null) {
            return;
        }
        List<ManagementBatchSelection.Entry> selected =
            ManagementBatchSelection.selectedEntries();
        if (selected.isEmpty()) {
            showFailure(selectedKeyCount() == 0
                ? "cannot export an empty selection snapshot"
                : "selection payload is unavailable; refresh the host list");
            return;
        }
        if (selected.size() != selectedKeyCount()) {
            showFailure("selection payload is unavailable; refresh the host list");
            return;
        }
        for (ManagementBatchSelection.Entry item : selected) {
            if (!isExportable(item.kind)) {
                showFailure("selection contains a non-exportable item");
                return;
            }
        }
        final String token = UUID.randomUUID().toString();
        pendingExportToken = token;
        exportPickerStarted = false;
        setBusy(true);
        // JSON serialization and app-private persistence happen on the
        // worker.  The exact selected Entry objects are immutable snapshots;
        // Activity recreation cannot replace them while the picker is open.
        executor.execute(() -> {
            try {
                JSONObject frozen = buildExportManifest(selected);
                writeExportTransaction(token, frozen);
                List<ManagementTransfer.FileSpec> files =
                    buildExportFiles(frozen);
                activity.runOnUiThread(() ->
                    showExportPreview(token, files)
                );
            } catch (Exception error) {
                deleteExportTransaction(token);
                activity.runOnUiThread(() -> {
                    if (!isUiActive()) {
                        return;
                    }
                    pendingExportToken = null;
                    setBusy(false);
                    showFailure(safeMessage(error));
                });
            }
        });
    }

    /** Shows every independent JSON file before the user chooses a SAF tree. */
    private void showExportPreview(
        String token,
        List<ManagementTransfer.FileSpec> files
    ) {
        if (!active || !isUiActive() || !token.equals(pendingExportToken)) {
            deleteExportTransaction(token);
            return;
        }
        StringBuilder preview = new StringBuilder();
        for (ManagementTransfer.FileSpec file : files) {
            if (preview.length() > 0) {
                preview.append("\n\n");
            }
            preview.append("===== ")
                .append(exportDisplayPath(file))
                .append(" =====\n")
                .append(file.label)
                .append('\n')
                .append(new String(file.bytes, StandardCharsets.UTF_8));
        }
        TextView body = new TextView(activity);
        body.setText(preview.toString());
        body.setTextIsSelectable(true);
        int padding = (int) (activity.getResources().getDisplayMetrics().density * 16);
        body.setPadding(padding, padding, padding, padding);
        ScrollView scroll = new ScrollView(activity);
        scroll.addView(body);
        new MaterialAlertDialogBuilder(activity)
            .setTitle(R.string.management_transfer_export_preview_title)
            .setView(scroll)
            .setNegativeButton(
                R.string.management_transfer_export_preview_cancel,
                (dialog, which) -> {
                    deleteExportTransaction(token);
                    pendingExportToken = null;
                    setBusy(false);
                }
            )
            .setPositiveButton(
                R.string.management_transfer_export_preview_continue,
                (dialog, which) -> launchExportPicker(token)
            )
            .setOnCancelListener(dialog -> {
                deleteExportTransaction(token);
                pendingExportToken = null;
                setBusy(false);
            })
            .show();
    }

    private void launchExportPicker(String token) {
        if (!active || !isUiActive() || !token.equals(pendingExportToken)) {
            deleteExportTransaction(token);
            return;
        }
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE)
            .addFlags(
                Intent.FLAG_GRANT_READ_URI_PERMISSION
                    | Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                    | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION
            );
        try {
            exportPickerStarted = true;
            exportLauncher.launch(intent);
        } catch (RuntimeException error) {
            exportPickerStarted = false;
            deleteExportTransaction(token);
            pendingExportToken = null;
            setBusy(false);
            showFailure(safeMessage(error));
        }
    }

    private void onExportResult(int resultCode, Intent data) {
        if (resultCode != Activity.RESULT_OK || data == null
            || data.getData() == null) {
            deleteExportTransaction(pendingExportToken);
            pendingExportToken = null;
            exportPickerStarted = false;
            setBusy(false);
            return;
        }
        final String token = pendingExportToken;
        pendingExportToken = null;
        exportPickerStarted = false;
        if (token == null || token.trim().isEmpty()) {
            deleteExportTransaction(token);
            setBusy(false);
            showFailure("export transaction snapshot is unavailable");
            return;
        }
        Uri destination = data.getData();
        try {
            activity.getContentResolver().takePersistableUriPermission(
                destination,
                data.getFlags()
                    & (Intent.FLAG_GRANT_READ_URI_PERMISSION
                        | Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
            );
        } catch (SecurityException ignored) {
            // A provider may grant only a transient permission for this write.
        }
        setBusy(true);
        executor.execute(() -> {
            try {
                JSONObject frozen = readExportTransaction(token);
                ManagementTransfer.WriteResult result =
                    ManagementTransfer.writeFiles(
                        activity.getContentResolver(),
                        destination,
                        buildExportFiles(frozen)
                    );
                activity.runOnUiThread(() -> {
                    if (!active || !isUiActive()) {
                        return;
                    }
                    setBusy(false);
                    showExportResult(result);
                });
            } catch (Exception error) {
                activity.runOnUiThread(() -> {
                    if (!active || !isUiActive()) {
                        return;
                    }
                    setBusy(false);
                    showFailure(safeMessage(error));
                });
            } finally {
                deleteExportTransaction(token);
            }
        });
    }

    private void showExportResult(ManagementTransfer.WriteResult result) {
        if (result == null) {
            showFailure("export result is unavailable");
            return;
        }
        StringBuilder message = new StringBuilder(activity.getString(
            R.string.management_transfer_export_result,
            result.succeeded,
            result.failures.size()
        ));
        for (String failure : result.failures) {
            message.append('\n').append("• ").append(failure);
        }
        android.widget.Toast.makeText(
            activity,
            message.toString(),
            android.widget.Toast.LENGTH_LONG
        ).show();
    }

    private JSONObject buildExportManifest(
        List<ManagementBatchSelection.Entry> selected
    ) throws Exception {
        if (selected == null || selected.isEmpty()) {
            throw new IllegalArgumentException(
                "cannot export an empty selection snapshot"
            );
        }
        Context snapshotContext = activity.getApplicationContext() != null
            ? activity.getApplicationContext()
            : activity;
        JSONArray items;
        synchronized (PendingProcessManager.POLICY_PUBLICATION_LOCK) {
            PendingProcessStore.ReferenceSnapshot pending =
                new PendingProcessStore(snapshotContext).snapshotReferences();
            List<Item> current = ManagementHomeBatchDataSource.snapshotItems(
                snapshotContext,
                pending
            );
            Map<String, Item> currentByKey = new LinkedHashMap<>();
            for (Item item : current) {
                if (item != null) {
                    currentByKey.put(item.key(), item);
                }
            }
            items = new JSONArray();
            for (ManagementBatchSelection.Entry selectedItem : selected) {
                if (selectedItem == null
                    || !isExportable(selectedItem.kind)) {
                    throw new IllegalArgumentException(
                        "selection contains a non-exportable item"
                    );
                }
                String key = selectedItem.key();
                if (pending.isPending(
                    selectedItem.kind,
                    selectedItem.canonicalId
                )) {
                    throw new IOException(
                        "selected export item is pending: " + key
                    );
                }
                Item currentItem = currentByKey.get(key);
                if (currentItem == null) {
                    throw new IOException(
                        "selected export item is no longer available: " + key
                    );
                }
                JSONObject payload = exportPayload(
                    currentItem,
                    currentByKey,
                    pending
                );
                items.put(new JSONObject()
                    .put("kind", currentItem.kind)
                    .put("canonicalId", currentItem.canonicalId)
                    .put("label", currentItem.label)
                    .put("payload", payload));
            }
        }
        if (items.length() == 0) {
            throw new IllegalArgumentException(
                "cannot export an empty selection snapshot"
            );
        }
        JSONObject output = new JSONObject()
            .put("format", "het-management-transfer")
            .put("version", 2)
            .put("items", items);
        return output;
    }

    /**
     * Projects current store payloads into the consumer files without
     * mutating the source records.  The live catalog contains every kind, so
     * this also refreshes selections made on another management host.
     */
    private JSONObject exportPayload(
        Item current,
        Map<String, Item> currentByKey,
        PendingProcessStore.ReferenceSnapshot pending
    ) throws Exception {
        JSONObject payload = new JSONObject(current.payload.toString());
        if (KIND_SCENE.equals(current.kind)) {
            return SceneStore.filterPendingLanguagesForManagementExport(
                current.canonicalId,
                payload,
                pending
            );
        }
        if (KIND_CONTEXT.equals(current.kind)) {
            return filterContextExportRelations(payload, currentByKey);
        }
        if (KIND_GROUP.equals(current.kind)) {
            return filterGroupExportRelations(payload, currentByKey);
        }
        return payload;
    }

    private JSONObject filterContextExportRelations(
        JSONObject context,
        Map<String, Item> currentByKey
    ) throws Exception {
        JSONArray source = context.optJSONArray("scenes");
        if (source == null) {
            return context;
        }
        JSONArray filtered = new JSONArray();
        for (int index = 0; index < source.length(); index++) {
            JSONObject entry = source.optJSONObject(index);
            if (entry == null) {
                continue;
            }
            String sceneName = entry.optString("scene", "").trim();
            if (sceneName.isEmpty()
                || !currentByKey.containsKey(KIND_SCENE + ":" + sceneName)) {
                continue;
            }
            filtered.put(new JSONObject(entry.toString()));
        }
        context.put("scenes", filtered);
        return context;
    }

    private JSONObject filterGroupExportRelations(
        JSONObject group,
        Map<String, Item> currentByKey
    ) throws Exception {
        JSONArray source = group.optJSONArray("contexts");
        if (source == null) {
            return group;
        }
        JSONArray filtered = new JSONArray();
        for (int index = 0; index < source.length(); index++) {
            JSONObject entry = source.optJSONObject(index);
            if (entry == null) {
                continue;
            }
            String contextId = entry.optString("context_id", "").trim();
            if (contextId.isEmpty()
                || !currentByKey.containsKey(KIND_CONTEXT + ":" + contextId)) {
                continue;
            }
            filtered.put(new JSONObject(entry.toString()));
        }
        group.put("contexts", filtered);
        return group;
    }

    private List<ManagementTransfer.FileSpec> buildExportFiles(
        JSONObject frozen
    ) throws Exception {
        JSONArray items = frozen == null
            ? null
            : frozen.optJSONArray("items");
        if (items == null || items.length() == 0
            || !"het-management-transfer".equals(
                frozen.optString("format", "")
            )
            || frozen.optInt("version", -1) != 2) {
            throw new IOException("export transaction snapshot is invalid");
        }
        List<ManagementTransfer.FileSpec> files = new ArrayList<>();
        LinkedHashMap<String, JSONObject> characters = new LinkedHashMap<>();
        LinkedHashMap<String, JSONObject> terms = new LinkedHashMap<>();
        for (int index = 0; index < items.length(); index++) {
            JSONObject item = items.optJSONObject(index);
            if (item == null) {
                throw new IOException("export transaction item is invalid");
            }
            String kind = item.optString("kind", "");
            String id = item.optString("canonicalId", "").trim();
            JSONObject payload = item.optJSONObject("payload");
            String label = item.optString("label", id);
            if (!isExportable(kind) || id.isEmpty() || payload == null) {
                throw new IOException("export transaction item is invalid");
            }
            JSONObject rawPayload = new JSONObject(payload.toString());
            if (KIND_SCENE.equals(kind)) {
                files.add(new ManagementTransfer.FileSpec(
                    "",
                    ManagementTransfer.jsonFileName(id),
                    jsonBytes(rawPayload),
                    label
                ));
            } else if (KIND_CONTEXT.equals(kind)) {
                String displayName = rawPayload.optString(
                    "display_name",
                    ""
                ).trim();
                if (displayName.isEmpty()) {
                    throw new IOException("context display_name is empty");
                }
                files.add(new ManagementTransfer.FileSpec(
                    "contexts",
                    ManagementTransfer.jsonFileName(displayName),
                    jsonBytes(rawPayload),
                    label
                ));
            } else if (KIND_GROUP.equals(kind)) {
                String displayName = rawPayload.optString(
                    "display_name",
                    ""
                ).trim();
                if (displayName.isEmpty()) {
                    throw new IOException("group display_name is empty");
                }
                files.add(new ManagementTransfer.FileSpec(
                    "groups",
                    ManagementTransfer.jsonFileName(displayName),
                    jsonBytes(rawPayload),
                    label
                ));
            } else if (KIND_CHARACTER.equals(kind)) {
                characters.put(id, rawPayload);
            } else if (KIND_TERM.equals(kind)) {
                terms.put(id, rawPayload);
            } else {
                throw new IOException("unsupported export kind: " + kind);
            }
        }
        if (!characters.isEmpty()) {
            JSONObject dictionary = new JSONObject();
            for (Map.Entry<String, JSONObject> entry : characters.entrySet()) {
                dictionary.put(
                    entry.getKey(),
                    new JSONObject(entry.getValue().toString())
                );
            }
            files.add(new ManagementTransfer.FileSpec(
                "",
                "chardict.json",
                jsonBytes(dictionary),
                "chardict.json"
            ));
        }
        if (!terms.isEmpty()) {
            JSONObject dictionary = new JSONObject();
            for (Map.Entry<String, JSONObject> entry : terms.entrySet()) {
                dictionary.put(
                    entry.getKey(),
                    new JSONObject(entry.getValue().toString())
                );
            }
            files.add(new ManagementTransfer.FileSpec(
                "",
                "gameterms.json",
                jsonBytes(dictionary),
                "gameterms.json"
            ));
        }
        if (files.isEmpty()) {
            throw new IOException("export file plan is empty");
        }
        return files;
    }

    private static String exportDisplayPath(ManagementTransfer.FileSpec file) {
        if (file == null) {
            return "document.json";
        }
        String directory = file.directory == null
            ? ""
            : file.directory.trim();
        String requestedName = file.requestedName == null
            ? "document.json"
            : file.requestedName.trim();
        return directory.isEmpty()
            ? requestedName
            : directory + "/" + requestedName;
    }

    private static byte[] jsonBytes(JSONObject value) throws Exception {
        byte[] bytes = (value.toString(2) + "\n")
            .getBytes(StandardCharsets.UTF_8);
        if (bytes.length > MAX_EXPORT_BYTES) {
            throw new IOException("export document exceeds size limit");
        }
        return bytes;
    }

    private void setBusy(boolean value) {
        busy = value;
        if (panel == null) {
            return;
        }
        updateActions();
    }

    private void restoreState(Bundle state) {
        active = state.getBoolean(STATE_ACTIVE, false);
        activeKind = normalizeKind(state.getString(
            STATE_KIND,
            dataSource.initialKind()
        ));
        if (!ownedKinds.contains(activeKind)) {
            // A saved bundle can outlive the tab that created it.  Keep the
            // restored toolbar kind inside this host's declared ownership;
            // the initial kind was validated by the constructor.
            activeKind = normalizeKind(dataSource.initialKind());
        }
        ArrayList<String> selected = state.getStringArrayList(STATE_SELECTED);
        if (selected != null) {
            ManagementBatchSelection.selectAll(selected);
        }
        exportPickerStarted = state.getBoolean(
            STATE_EXPORT_PICKER_STARTED,
            false
        );
        pendingExportToken = state.getString(STATE_EXPORT_TOKEN);
        if (!exportPickerStarted) {
            deleteExportTransaction(pendingExportToken);
            pendingExportToken = null;
        }
    }

    private File exportTransactionDirectory() {
        return new File(activity.getFilesDir(), EXPORT_DIRECTORY);
    }

    private File exportTransactionFile(String token) throws IOException {
        if (token == null || !token.matches(
            "[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[1-5][0-9a-fA-F]{3}-[89abAB][0-9a-fA-F]{3}-[0-9a-fA-F]{12}"
        )) {
            throw new IOException("invalid export transaction token");
        }
        return new File(exportTransactionDirectory(), token + ".json");
    }

    private void writeExportTransaction(String token, JSONObject frozen)
        throws Exception {
        if (frozen == null || frozen.optJSONArray("items") == null
            || frozen.optJSONArray("items").length() == 0) {
            throw new IOException("export transaction snapshot is empty");
        }
        byte[] bytes = frozen.toString().getBytes(StandardCharsets.UTF_8);
        if (bytes.length == 0 || bytes.length > MAX_EXPORT_BYTES) {
            throw new IOException("export transaction snapshot is too large");
        }
        File directory = exportTransactionDirectory();
        if (!directory.exists() && !directory.mkdirs() && !directory.isDirectory()) {
            throw new IOException("could not create export transaction directory");
        }
        AtomicFile file = new AtomicFile(exportTransactionFile(token));
        FileOutputStream output = file.startWrite();
        try {
            output.write(bytes);
            output.getFD().sync();
            file.finishWrite(output);
        } catch (Exception error) {
            file.failWrite(output);
            throw error;
        }
    }

    private JSONObject readExportTransaction(String token) throws Exception {
        AtomicFile file = new AtomicFile(exportTransactionFile(token));
        byte[] bytes;
        try (java.io.InputStream input = file.openRead()) {
            java.io.ByteArrayOutputStream output =
                new java.io.ByteArrayOutputStream();
            byte[] buffer = new byte[8192];
            int total = 0;
            int count;
            while ((count = input.read(buffer)) != -1) {
                total += count;
                if (total > MAX_EXPORT_BYTES) {
                    throw new IOException("export transaction snapshot is too large");
                }
                output.write(buffer, 0, count);
            }
            bytes = output.toByteArray();
        }
        if (bytes.length == 0) {
            throw new IOException("export transaction snapshot is empty");
        }
        JSONObject frozen = new JSONObject(
            new String(bytes, StandardCharsets.UTF_8)
        );
        JSONArray items = frozen.optJSONArray("items");
        if (items == null || items.length() == 0
            || !"het-management-transfer".equals(
                frozen.optString("format", "")
            )
            || frozen.optInt("version", -1) != 2) {
            throw new IOException("export transaction snapshot is invalid");
        }
        // Re-run the export contract before touching the user-selected URI.
        for (int index = 0; index < items.length(); index++) {
            JSONObject item = items.optJSONObject(index);
            if (item == null) {
                throw new IOException("export transaction item is invalid");
            }
            String kind = item.optString("kind", "");
            String id = item.optString("canonicalId", "");
            if (!isExportable(kind) || id.trim().isEmpty()
                || item.optJSONObject("payload") == null) {
                throw new IOException("export transaction item is invalid");
            }
        }
        return frozen;
    }

    private void deleteExportTransaction(String token) {
        try {
            File file = exportTransactionFile(token);
            if (file.exists() && !file.delete() && file.exists()) {
                // Cleanup is best-effort; the token remains unreferenced after
                // the callback and does not affect the exported document.
            }
        } catch (IOException ignored) {
            // An invalid/missing token is already an unavailable transaction.
        }
    }

    private void showFailureLines(List<String> failures, int succeeded) {
        StringBuilder details = new StringBuilder();
        if (failures != null) {
            for (String failure : failures) {
                if (details.length() > 0) {
                    details.append('\n');
                }
                details.append("• ").append(failure);
            }
        }
        String message = activity.getString(
            R.string.management_batch_failed,
            succeeded,
            failures == null ? 0 : failures.size()
        );
        if (details.length() > 0) {
            message += "\n" + details;
        }
        android.widget.Toast.makeText(
            activity,
            message,
            android.widget.Toast.LENGTH_LONG
        ).show();
    }

    private void showFailure(String detail) {
        android.widget.Toast.makeText(
            activity,
            detail == null || detail.trim().isEmpty()
                ? activity.getString(R.string.management_batch_failed, 0, 1)
                : detail,
            android.widget.Toast.LENGTH_LONG
        ).show();
    }

    private static String normalizeKind(String kind) {
        if (isManagedKind(kind)) {
            return kind;
        }
        return KIND_SCENE;
    }

    private static boolean isManagedKind(String kind) {
        return KIND_SCENE.equals(kind) || KIND_LANGUAGE.equals(kind)
            || KIND_CONTEXT.equals(kind) || KIND_GROUP.equals(kind)
            || KIND_CHARACTER.equals(kind) || KIND_TERM.equals(kind);
    }

    private static boolean isExportable(String kind) {
        return KIND_SCENE.equals(kind) || KIND_CONTEXT.equals(kind)
            || KIND_GROUP.equals(kind) || KIND_CHARACTER.equals(kind)
            || KIND_TERM.equals(kind);
    }

    private static String safeMessage(Throwable error) {
        if (error == null) {
            return "operation_failed";
        }
        String message = error.getMessage();
        return message == null || message.trim().isEmpty()
            ? error.getClass().getSimpleName()
            : message;
    }

    private boolean isUiActive() {
        return !activity.isFinishing() && !activity.isDestroyed();
    }

    @Override
    public void close() {
        lifecycleStarted = false;
        pendingClient.setConnectionListener(null);
        backCallback.remove();
        pendingClient.close();
        executor.shutdownNow();
        exit();
    }

    private static Set<String> normalizeOwnedKinds(Set<String> declared) {
        if (declared == null || declared.isEmpty()) {
            throw new IllegalArgumentException(
                "BatchDataSource must declare at least one owned kind"
            );
        }
        LinkedHashSet<String> normalized = new LinkedHashSet<>();
        for (String kind : declared) {
            if (kind == null || kind.trim().isEmpty() || !isManagedKind(kind)) {
                throw new IllegalArgumentException(
                    "BatchDataSource declared an invalid owned kind"
                );
            }
            normalized.add(kind);
        }
        if (normalized.isEmpty()) {
            throw new IllegalArgumentException(
                "BatchDataSource must declare at least one owned kind"
            );
        }
        return Collections.unmodifiableSet(normalized);
    }

}
