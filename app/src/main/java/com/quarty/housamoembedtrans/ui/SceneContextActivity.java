package com.quarty.housamoembedtrans.ui;

import com.quarty.housamoembedtrans.R;
import com.quarty.housamoembedtrans.storage.config.ConfigStore;
import com.quarty.housamoembedtrans.context.model.GroupContextEntry;
import com.quarty.housamoembedtrans.context.store.SceneContextStore;
import com.quarty.housamoembedtrans.scene.store.SceneStore;
import com.quarty.housamoembedtrans.summary.job.SummaryJobStore;
import com.quarty.housamoembedtrans.summary.policy.ContextCompressionCoordinator;
import com.quarty.housamoembedtrans.context.review.ContextReviewCoordinator;
import com.quarty.housamoembedtrans.context.review.ContextReviewGate;
import com.quarty.housamoembedtrans.context.review.ContextReviewPlanner;
import com.quarty.housamoembedtrans.summary.policy.GroupCompressionCoordinator;
import com.quarty.housamoembedtrans.translation.job.TranslationJobStore;

import android.content.Intent;
import android.content.ClipData;
import android.content.ContentResolver;
import android.database.Cursor;
import android.net.Uri;
import android.os.Bundle;
import android.provider.DocumentsContract;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

import com.google.android.material.appbar.MaterialToolbar;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.checkbox.MaterialCheckBox;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.io.InputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.LinkedHashSet;

/**
 * Scene Context / Context Group management page. In review mode it also serves
 * as the optional startup Context/Group Review stage with save-and-continue and
 * skip semantics.
 */
public final class SceneContextActivity extends AppCompatActivity {

    public static final String EXTRA_REVIEW_MODE = "review_mode";
    private static final int REQUEST_IMPORT_CONTEXT_GROUP = 7101;
    private static final int REQUEST_EXPORT_CONTEXT_GROUP = 7102;
    private static final int MAX_TRANSFER_BYTES = 4 * 1024 * 1024;

    private static final String NONE_PLACEHOLDER = "—";

    private final ExecutorService ioExecutor = Executors.newSingleThreadExecutor();

    private SceneContextStore sceneContextStore;
    private TranslationJobStore translationJobStore;
    private SummaryJobStore summaryJobStore;
    private ContextCompressionCoordinator contextCompressionCoordinator;
    private GroupCompressionCoordinator groupCompressionCoordinator;
    private ContextReviewCoordinator contextReviewCoordinator;
    private SceneStore sceneStore;
    private PendingProcessMoveController pendingMoveController;
    private ManagementBatchController managementBatchController;

    private final List<JSONObject> contexts = new ArrayList<>();
    private final List<JSONObject> groups = new ArrayList<>();
    /** Immediate-summary choices belong to the unsaved draft, never storage. */
    private final Map<String, String> immediateSummaryLanguages =
        new LinkedHashMap<>();
    private final List<String> activeContextIds = new ArrayList<>();
    private final List<String> activeGroupIds = new ArrayList<>();
    private final List<String> contextLabels = new ArrayList<>();
    private final List<String> groupLabels = new ArrayList<>();

    private Spinner activeContextSpinner;
    private Spinner activeGroupSpinner;
    private LinearLayout contextContainer;
    private LinearLayout groupContainer;
    private MenuItem managementBatchMenuItem;
    private TextView resultView;
    private boolean reviewMode;
    private boolean busy;
    private boolean batchMode;
    private String selectedActiveContextId;
    private String selectedActiveGroupId;
    private ArrayAdapter<String> activeContextAdapter;
    private ArrayAdapter<String> activeGroupAdapter;

    private static final class ImmediateSummaryOutcome {
        int requested;
        int created;
        int skipped;
        int failed;
        String firstFailure;
    }

    private static final class ImportConflictItem {
        final boolean group;
        final String id;
        final String label;

        ImportConflictItem(boolean group, String id, String label) {
            this.group = group;
            this.id = id;
            this.label = label;
        }
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_scene_context);
        SystemBarInsets.apply(findViewById(R.id.root_scene_context));
        MaterialToolbar toolbar = findViewById(R.id.toolbar_scene_context);
        toolbar.setNavigationOnClickListener(
            view -> onBackPressed()
        );
        toolbar.inflateMenu(R.menu.menu_management_batch);
        managementBatchMenuItem = toolbar.getMenu().findItem(
            R.id.action_management_batch
        );
        managementBatchMenuItem.setOnMenuItemClickListener(item -> {
            toggleManagementBatch();
            return true;
        });

        reviewMode = ContextReviewGate.get().isPending()
            || (getIntent() != null
                && getIntent().getBooleanExtra(EXTRA_REVIEW_MODE, false));
        managementBatchMenuItem.setVisible(!reviewMode);

        sceneContextStore = new SceneContextStore(this);
        translationJobStore = TranslationJobStore.getInstance(this);
        summaryJobStore = SummaryJobStore.createForAndroid(this);
        contextCompressionCoordinator = new ContextCompressionCoordinator(
            sceneContextStore,
            summaryJobStore
        );
        groupCompressionCoordinator = new GroupCompressionCoordinator(
            sceneContextStore,
            summaryJobStore,
            contextCompressionCoordinator
        );
        contextReviewCoordinator = new ContextReviewCoordinator(
            sceneContextStore,
            translationJobStore,
            summaryJobStore,
            contextCompressionCoordinator,
            groupCompressionCoordinator
        );
        sceneStore = new SceneStore(this);
        pendingMoveController = new PendingProcessMoveController(this);

        bindViews();
        refreshAsync();
        managementBatchController = ManagementBatchController.attach(
            this,
            findViewById(R.id.root_scene_context),
            new ContextBatchDataSource(),
            savedInstanceState
        );
    }

    private void toggleManagementBatch() {
        if (managementBatchController == null) {
            return;
        }
        if (managementBatchController.isActive()) {
            managementBatchController.exit();
        } else {
            managementBatchController.enter();
        }
    }

    @Override
    protected void onStart() {
        super.onStart();
        if (managementBatchController != null) {
            managementBatchController.onStart();
        }
    }

    @Override
    protected void onStop() {
        if (managementBatchController != null) {
            managementBatchController.onStop();
        }
        super.onStop();
    }

    @Override
    protected void onSaveInstanceState(Bundle outState) {
        if (managementBatchController != null) {
            managementBatchController.saveState(outState);
        }
        super.onSaveInstanceState(outState);
    }

    private void bindViews() {
        activeContextSpinner = findViewById(R.id.spinner_active_context);
        activeGroupSpinner = findViewById(R.id.spinner_active_group);
        contextContainer = findViewById(R.id.container_contexts);
        groupContainer = findViewById(R.id.container_groups);
        resultView = findViewById(R.id.tv_scene_context_result);

        activeContextAdapter = new ArrayAdapter<>(
            this,
            android.R.layout.simple_spinner_item,
            contextLabels
        );
        activeContextAdapter.setDropDownViewResource(
            android.R.layout.simple_spinner_dropdown_item
        );
        activeContextSpinner.setAdapter(activeContextAdapter);
        activeContextSpinner.setOnItemSelectedListener(
            new AdapterView.OnItemSelectedListener() {
                @Override
                public void onItemSelected(
                    AdapterView<?> parent,
                    View view,
                    int position,
                    long id
                ) {
                    selectedActiveContextId = position > 0
                        && position <= activeContextIds.size()
                        ? activeContextIds.get(position - 1)
                        : null;
                }

                @Override
                public void onNothingSelected(AdapterView<?> parent) {
                    selectedActiveContextId = null;
                }
            }
        );

        activeGroupAdapter = new ArrayAdapter<>(
            this,
            android.R.layout.simple_spinner_item,
            groupLabels
        );
        activeGroupAdapter.setDropDownViewResource(
            android.R.layout.simple_spinner_dropdown_item
        );
        activeGroupSpinner.setAdapter(activeGroupAdapter);
        activeGroupSpinner.setOnItemSelectedListener(
            new AdapterView.OnItemSelectedListener() {
                @Override
                public void onItemSelected(
                    AdapterView<?> parent,
                    View view,
                    int position,
                    long id
                ) {
                    selectedActiveGroupId = position > 0
                        && position <= activeGroupIds.size()
                        ? activeGroupIds.get(position - 1)
                        : null;
                }

                @Override
                public void onNothingSelected(AdapterView<?> parent) {
                    selectedActiveGroupId = null;
                }
            }
        );

        findViewById(R.id.btn_add_context).setOnClickListener(
            view -> showContextEditor(newContextDraft())
        );
        findViewById(R.id.btn_add_group).setOnClickListener(
            view -> showGroupEditor(newGroupDraft())
        );
        View batchEditButton = findViewById(R.id.btn_edit_all_scenes);
        batchEditButton.setVisibility(reviewMode ? View.GONE : View.VISIBLE);
        batchEditButton.setOnClickListener(
            view -> startActivity(
                new Intent(this, SceneBatchEditActivity.class)
            )
        );

        findViewById(R.id.btn_import_context_group).setOnClickListener(
            view -> chooseContextGroupImport()
        );
        findViewById(R.id.btn_export_context_group).setOnClickListener(
            view -> chooseContextGroupExport()
        );

        View reviewButtons = findViewById(R.id.review_buttons);
        reviewButtons.setVisibility(View.VISIBLE);
        MaterialButton reviewSaveButton = findViewById(R.id.btn_review_save);
        MaterialButton reviewSkipButton = findViewById(R.id.btn_review_skip);
        if (reviewMode) {
            reviewSkipButton.setVisibility(View.VISIBLE);
            reviewSaveButton.setText(R.string.scene_context_review_save);
            reviewSaveButton.setOnClickListener(view -> saveReview(true));
            reviewSkipButton.setOnClickListener(view -> {
                ContextReviewGate.get().complete(false);
                finish();
            });
        } else {
            reviewSkipButton.setVisibility(View.GONE);
            reviewSaveButton.setText(R.string.scene_context_save_changes);
            reviewSaveButton.setOnClickListener(view -> saveReview(false));
        }
    }

    @Override
    public void onBackPressed() {
        if (managementBatchController != null
            && managementBatchController.isActive()) {
            // Batch mode is an inline editing state; back first restores the
            // existing context/group rows instead of finishing the Activity.
            managementBatchController.exit();
            return;
        }
        if (reviewMode && ContextReviewGate.get().isPending()) {
            new MaterialAlertDialogBuilder(this)
                .setTitle(R.string.scene_context_review_skip_title)
                .setMessage(R.string.scene_context_review_skip_message)
                .setNegativeButton(R.string.cancel_action, null)
                .setPositiveButton(
                    R.string.scene_context_review_skip,
                    (dialog, which) -> {
                        ContextReviewGate.get().complete(false);
                        finish();
                    }
                )
                .show();
            return;
        }
        super.onBackPressed();
    }

    @Override
    protected void onDestroy() {
        if (managementBatchController != null) {
            managementBatchController.close();
            managementBatchController = null;
        }
        if (pendingMoveController != null) {
            pendingMoveController.close();
            pendingMoveController = null;
        }
        ioExecutor.shutdownNow();
        super.onDestroy();
    }

    private void chooseContextGroupImport() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("application/json");
        intent.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
        startActivityForResult(intent, REQUEST_IMPORT_CONTEXT_GROUP);
    }

    private void chooseContextGroupExport() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE);
        intent.addFlags(
            Intent.FLAG_GRANT_READ_URI_PERMISSION
                | Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION
        );
        startActivityForResult(intent, REQUEST_EXPORT_CONTEXT_GROUP);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (resultCode != RESULT_OK || data == null) {
            return;
        }
        if (requestCode == REQUEST_EXPORT_CONTEXT_GROUP) {
            Uri tree = data.getData();
            if (tree == null) {
                return;
            }
            try {
                getContentResolver().takePersistableUriPermission(
                    tree,
                    data.getFlags()
                        & (Intent.FLAG_GRANT_READ_URI_PERMISSION
                            | Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
                );
            } catch (SecurityException ignored) {
                // A provider may grant only transient permission; the current
                // export still proceeds while the Activity is alive.
            }
            setBusy(true);
            ioExecutor.execute(() -> exportContextGroups(tree));
            return;
        }
        if (requestCode == REQUEST_IMPORT_CONTEXT_GROUP) {
            List<Uri> uris = new ArrayList<>();
            ClipData clipData = data.getClipData();
            if (clipData != null) {
                for (int i = 0; i < clipData.getItemCount(); i++) {
                    Uri uri = clipData.getItemAt(i).getUri();
                    if (uri != null) {
                        uris.add(uri);
                    }
                }
            } else if (data.getData() != null) {
                uris.add(data.getData());
            }
            if (uris.isEmpty()) {
                return;
            }
            setBusy(true);
            ioExecutor.execute(() -> readContextGroupImport(uris));
        }
    }

    private void exportContextGroups(Uri treeUri) {
        try {
            JSONObject bundle = sceneContextStore.exportBundle();
            ContentResolver resolver = getContentResolver();
            Uri contextsDirectory = findOrCreateDirectory(
                resolver,
                treeUri,
                "contexts"
            );
            Uri groupsDirectory = findOrCreateDirectory(
                resolver,
                treeUri,
                "groups"
            );
            int exported = 0;
            JSONArray exportedContexts = bundle.optJSONArray("contexts");
            if (exportedContexts != null) {
                exported += writeTransferDocuments(
                    resolver,
                    contextsDirectory,
                    exportedContexts,
                    "context"
                );
            }
            JSONArray exportedGroups = bundle.optJSONArray("groups");
            if (exportedGroups != null) {
                exported += writeTransferDocuments(
                    resolver,
                    groupsDirectory,
                    exportedGroups,
                    "group"
                );
            }
            final int count = exported;
            runOnUiThread(() -> {
                setBusy(false);
                showResult(getString(
                    R.string.scene_context_export_result,
                    count
                ));
            });
        } catch (Exception e) {
            runOnUiThread(() -> {
                setBusy(false);
                showResult(getString(
                    R.string.scene_context_transfer_failed,
                    safeMessage(e)
                ));
            });
        }
    }

    private void readContextGroupImport(List<Uri> uris) {
        try {
            JSONObject bundle = new JSONObject()
                .put("version", SceneContextStore.FORMAT_VERSION)
                .put("contexts", new JSONArray())
                .put("groups", new JSONArray());
            for (Uri uri : uris) {
                String json = readTransferDocument(uri);
                JSONObject document = new JSONObject(json);
                JSONArray nestedContexts = document.optJSONArray("contexts");
                JSONArray nestedGroups = document.optJSONArray("groups");
                boolean envelope = document.has("version")
                    && nestedContexts != null
                    && nestedGroups != null;
                if (!envelope
                    && nestedContexts != null
                    && nestedGroups != null) {
                    throw new IllegalArgumentException(
                        "Context/Group bundle is missing version"
                    );
                }
                if (envelope) {
                    if (document.optInt("version", -1)
                        != SceneContextStore.FORMAT_VERSION) {
                        throw new IllegalArgumentException(
                            "unsupported Context/Group bundle version"
                        );
                    }
                    appendDocuments(
                        bundle.optJSONArray("contexts"),
                        nestedContexts
                    );
                    appendDocuments(
                        bundle.optJSONArray("groups"),
                        nestedGroups
                    );
                } else if (document.has("scenes")) {
                    bundle.optJSONArray("contexts").put(document);
                } else if (document.has("contexts")) {
                    bundle.optJSONArray("groups").put(document);
                } else {
                    throw new IllegalArgumentException(
                        "file is neither a Context nor a Group document"
                    );
                }
            }
            // Validate the complete bundle before opening any per-ID conflict
            // dialog. The mutating import repeats this check under its
            // transaction lock, but malformed documents must not leave the
            // user halfway through a conflict flow.
            SceneContextStore.ImportInspection inspection =
                sceneContextStore.inspectImportBundle(bundle);
            runOnUiThread(() -> {
                setBusy(false);
                showImportConflictDialog(bundle, inspection);
            });
        } catch (Exception e) {
            runOnUiThread(() -> {
                setBusy(false);
                showResult(getString(
                    R.string.scene_context_transfer_failed,
                    safeMessage(e)
                ));
            });
        }
    }

    private void showImportConflictDialog(
        JSONObject bundle,
        SceneContextStore.ImportInspection inspection
    ) {
        try {
            List<ImportConflictItem> conflicts = new ArrayList<>();
            JSONArray contexts = bundle.optJSONArray("contexts");
            if (contexts != null) {
                for (int i = 0; i < contexts.length(); i++) {
                    JSONObject context = contexts.optJSONObject(i);
                    if (context != null) {
                        String id = context.optString("id", "");
                        if (inspection.conflictingContextIds.contains(id)) {
                            conflicts.add(new ImportConflictItem(
                                false,
                                id,
                                context.optString("display_name", id)
                            ));
                        }
                    }
                }
            }
            JSONArray groups = bundle.optJSONArray("groups");
            if (groups != null) {
                for (int i = 0; i < groups.length(); i++) {
                    JSONObject group = groups.optJSONObject(i);
                    if (group != null) {
                        String id = group.optString("id", "");
                        if (inspection.conflictingGroupIds.contains(id)) {
                            conflicts.add(new ImportConflictItem(
                                true,
                                id,
                                group.optString("display_name", id)
                            ));
                        }
                    }
                }
            }
            chooseNextImportConflict(
                bundle,
                conflicts,
                0,
                new LinkedHashMap<>(),
                new LinkedHashMap<>()
            );
        } catch (Exception e) {
            showResult(getString(
                R.string.scene_context_transfer_failed,
                safeMessage(e)
            ));
        }
    }

    private void chooseNextImportConflict(
        JSONObject bundle,
        List<ImportConflictItem> conflicts,
        int index,
        Map<String, SceneContextStore.ImportConflictPolicy> contextPolicies,
        Map<String, SceneContextStore.ImportConflictPolicy> groupPolicies
    ) {
        if (index >= conflicts.size()) {
            setBusy(true);
            ioExecutor.execute(() -> importContextGroups(
                bundle,
                contextPolicies,
                groupPolicies,
                SceneContextStore.ImportConflictPolicy.COPY
            ));
            return;
        }
        ImportConflictItem conflict = conflicts.get(index);
        String title = getString(
            R.string.scene_context_import_conflict_item,
            getString(
                conflict.group
                    ? R.string.scene_context_kind_group
                    : R.string.scene_context_kind_context
            ),
            conflict.label
        );
        MaterialAlertDialogBuilder dialog = new MaterialAlertDialogBuilder(this)
            .setTitle(title)
            .setMessage(R.string.scene_context_import_conflict_message)
            .setNegativeButton(
                R.string.scene_context_import_skip,
                (d, which) -> {
                    putImportPolicy(
                        conflict,
                        SceneContextStore.ImportConflictPolicy.SKIP,
                        contextPolicies,
                        groupPolicies
                    );
                    chooseNextImportConflict(
                        bundle,
                        conflicts,
                        index + 1,
                        contextPolicies,
                        groupPolicies
                    );
                }
            )
            .setNeutralButton(
                R.string.scene_context_import_copy,
                (d, which) -> {
                    putImportPolicy(
                        conflict,
                        SceneContextStore.ImportConflictPolicy.COPY,
                        contextPolicies,
                        groupPolicies
                    );
                    chooseNextImportConflict(
                        bundle,
                        conflicts,
                        index + 1,
                        contextPolicies,
                        groupPolicies
                    );
                }
            )
            .setPositiveButton(
                R.string.scene_context_import_overwrite,
                (d, which) -> {
                    putImportPolicy(
                        conflict,
                        SceneContextStore.ImportConflictPolicy.OVERWRITE,
                        contextPolicies,
                        groupPolicies
                    );
                    chooseNextImportConflict(
                        bundle,
                        conflicts,
                        index + 1,
                        contextPolicies,
                        groupPolicies
                    );
                }
            );
        dialog.setOnCancelListener(d -> { });
        dialog.show();
    }

    private static void putImportPolicy(
        ImportConflictItem conflict,
        SceneContextStore.ImportConflictPolicy policy,
        Map<String, SceneContextStore.ImportConflictPolicy> contextPolicies,
        Map<String, SceneContextStore.ImportConflictPolicy> groupPolicies
    ) {
        if (conflict.group) {
            groupPolicies.put(conflict.id, policy);
        } else {
            contextPolicies.put(conflict.id, policy);
        }
    }

    private void importContextGroups(
        JSONObject bundle,
        Map<String, SceneContextStore.ImportConflictPolicy> contextPolicies,
        Map<String, SceneContextStore.ImportConflictPolicy> groupPolicies,
        SceneContextStore.ImportConflictPolicy defaultPolicy
    ) {
        try {
            SceneContextStore.ImportResult result =
                sceneContextStore.importBundle(
                    bundle,
                    contextPolicies,
                    groupPolicies,
                    defaultPolicy
                );
            runOnUiThread(() -> {
                setBusy(false);
                showResult(getString(
                    R.string.scene_context_import_result,
                    result.contextsImported,
                    result.contextsCopied,
                    result.contextsOverwritten,
                    result.contextsSkipped,
                    result.groupsImported,
                    result.groupsCopied,
                    result.groupsOverwritten,
                    result.groupsSkipped,
                    result.groupsSkippedMissingReferences
                ));
                refreshAsync();
            });
        } catch (Exception e) {
            runOnUiThread(() -> {
                setBusy(false);
                showResult(getString(
                    R.string.scene_context_transfer_failed,
                    safeMessage(e)
                ));
            });
        }
    }

    private static void appendDocuments(JSONArray target, JSONArray source) {
        if (target == null || source == null) {
            return;
        }
        for (int i = 0; i < source.length(); i++) {
            JSONObject document = source.optJSONObject(i);
            if (document == null) {
                throw new IllegalArgumentException(
                    "Context/Group bundle contains a non-object document"
                );
            }
            target.put(document);
        }
    }

    private String readTransferDocument(Uri uri) throws Exception {
        if (uri == null) {
            throw new IOException("empty import URI");
        }
        try (InputStream input = getContentResolver().openInputStream(uri)) {
            if (input == null) {
                throw new IOException("could not open import document");
            }
            byte[] bytes = readBounded(input, MAX_TRANSFER_BYTES);
            return new String(bytes, StandardCharsets.UTF_8);
        }
    }

    private static Uri findOrCreateDirectory(
        ContentResolver resolver,
        Uri treeUri,
        String name
    ) throws IOException {
        String treeId = DocumentsContract.getTreeDocumentId(treeUri);
        Uri parent = DocumentsContract.buildDocumentUriUsingTree(treeUri, treeId);
        Uri children = DocumentsContract.buildChildDocumentsUriUsingTree(
            treeUri,
            treeId
        );
        try (Cursor cursor = resolver.query(
            children,
            new String[] {
                DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                DocumentsContract.Document.COLUMN_MIME_TYPE
            },
            null,
            null,
            null
        )) {
            if (cursor != null) {
                int idColumn = cursor.getColumnIndex(
                    DocumentsContract.Document.COLUMN_DOCUMENT_ID
                );
                int nameColumn = cursor.getColumnIndex(
                    DocumentsContract.Document.COLUMN_DISPLAY_NAME
                );
                int mimeColumn = cursor.getColumnIndex(
                    DocumentsContract.Document.COLUMN_MIME_TYPE
                );
                while (cursor.moveToNext()) {
                    if (name.equals(cursor.getString(nameColumn))
                        && DocumentsContract.Document.MIME_TYPE_DIR.equals(
                            cursor.getString(mimeColumn)
                        )) {
                        return DocumentsContract.buildDocumentUriUsingTree(
                            treeUri,
                            cursor.getString(idColumn)
                        );
                    }
                }
            }
        } catch (RuntimeException e) {
            throw new IOException("could not inspect export directory", e);
        }
        Uri created = DocumentsContract.createDocument(
            resolver,
            parent,
            DocumentsContract.Document.MIME_TYPE_DIR,
            name
        );
        if (created == null) {
            throw new IOException("could not create export directory: " + name);
        }
        return created;
    }

    private static int writeTransferDocuments(
        ContentResolver resolver,
        Uri directory,
        JSONArray documents,
        String kind
    ) throws IOException {
        if (directory == null || documents == null) {
            return 0;
        }
        Set<String> usedNames = existingDocumentNames(resolver, directory);
        int count = 0;
        for (int i = 0; i < documents.length(); i++) {
            JSONObject document = documents.optJSONObject(i);
            if (document == null) {
                continue;
            }
            String base = safeTransferFileName(
                document.optString("display_name", kind)
            );
            String fileName = base + ".json";
            int suffix = 2;
            while (!usedNames.add(fileName)) {
                fileName = base + "_" + suffix++ + ".json";
            }
            Uri file = DocumentsContract.createDocument(
                resolver,
                directory,
                "application/json",
                fileName
            );
            if (file == null) {
                throw new IOException("could not create export file: " + fileName);
            }
            byte[] bytes = document.toString().getBytes(StandardCharsets.UTF_8);
            try (OutputStream output = resolver.openOutputStream(file, "w")) {
                if (output == null) {
                    throw new IOException("could not open export file: " + fileName);
                }
                output.write(bytes);
            }
            count++;
        }
        return count;
    }

    private static Set<String> existingDocumentNames(
        ContentResolver resolver,
        Uri directory
    ) throws IOException {
        Set<String> names = new HashSet<>();
        String id = DocumentsContract.getDocumentId(directory);
        Uri children = DocumentsContract.buildChildDocumentsUriUsingTree(
            directory,
            id
        );
        try (Cursor cursor = resolver.query(
            children,
            new String[] {DocumentsContract.Document.COLUMN_DISPLAY_NAME},
            null,
            null,
            null
        )) {
            if (cursor != null) {
                int nameColumn = cursor.getColumnIndex(
                    DocumentsContract.Document.COLUMN_DISPLAY_NAME
                );
                while (cursor.moveToNext()) {
                    names.add(cursor.getString(nameColumn));
                }
            }
        } catch (RuntimeException e) {
            throw new IOException("could not inspect export files", e);
        }
        return names;
    }

    private static String safeTransferFileName(String value) {
        String name = value == null ? "document" : value.trim();
        name = name.replaceAll("[\\\\/:*?\"<>|]", "_");
        name = name.replaceAll("\\s+", " ").trim();
        if (name.isEmpty() || ".".equals(name) || "..".equals(name)) {
            name = "document";
        }
        return name.length() > 120 ? name.substring(0, 120) : name;
    }

    private static byte[] readBounded(InputStream input, int maxBytes)
        throws IOException {
        java.io.ByteArrayOutputStream output =
            new java.io.ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        int total = 0;
        int read;
        while ((read = input.read(buffer)) != -1) {
            total += read;
            if (total > maxBytes) {
                throw new IOException("transfer document exceeds size limit");
            }
            output.write(buffer, 0, read);
        }
        return output.toByteArray();
    }

    private void refreshAsync() {
        setBusy(true);
        ioExecutor.execute(() -> {
            try {
                final List<JSONObject> loadedContexts =
                    new ArrayList<>(sceneContextStore.listContexts());
                final List<JSONObject> loadedGroups =
                    new ArrayList<>(sceneContextStore.listGroups());
                final String activeContextId =
                    sceneContextStore.getActiveContextId();
                final String activeGroupId =
                    sceneContextStore.getActiveGroupId();
                runOnUiThread(() -> {
                    if (isFinishing() || isDestroyed()) {
                        return;
                    }
                    contexts.clear();
                    for (JSONObject context : loadedContexts) {
                        contexts.add(copyJson(context));
                    }
                    groups.clear();
                    for (JSONObject group : loadedGroups) {
                        groups.add(copyJson(group));
                    }
                    render(activeContextId, activeGroupId);
                    setBusy(false);
                    if (managementBatchController != null
                        && managementBatchController.isActive()) {
                        managementBatchController.onHostRowsChanged();
                    }
                });
            } catch (Exception e) {
                runOnUiThread(() -> {
                    if (isFinishing() || isDestroyed()) {
                        return;
                    }
                    setBusy(false);
                    showResult(getString(
                        R.string.scene_context_load_failed,
                        safeMessage(e)
                    ));
                });
            }
        });
    }

    private void render(String activeContextId, String activeGroupId) {
        rebuildActiveSpinners(activeContextId, activeGroupId);
        renderContextRows();
        renderGroupRows();
        if (managementBatchController != null
            && managementBatchController.isActive()) {
            managementBatchController.refreshHostCatalog();
            managementBatchController.onHostRowsChanged();
        }
    }

    private void rebuildActiveSpinners(
        String activeContextId,
        String activeGroupId
    ) {
        activeContextIds.clear();
        contextLabels.clear();
        contextLabels.add(NONE_PLACEHOLDER);
        for (JSONObject context : contexts) {
            activeContextIds.add(context.optString("id", ""));
            contextLabels.add(context.optString("display_name", "?"));
        }
        activeContextAdapter.notifyDataSetChanged();
        int contextIndex = activeContextIds.indexOf(activeContextId);
        activeContextSpinner.setSelection(contextIndex < 0 ? 0 : contextIndex + 1);

        activeGroupIds.clear();
        groupLabels.clear();
        groupLabels.add(NONE_PLACEHOLDER);
        for (JSONObject group : groups) {
            activeGroupIds.add(group.optString("id", ""));
            groupLabels.add(group.optString("display_name", "?"));
        }
        activeGroupAdapter.notifyDataSetChanged();
        int groupIndex = activeGroupIds.indexOf(activeGroupId);
        activeGroupSpinner.setSelection(groupIndex < 0 ? 0 : groupIndex + 1);
    }

    private void renderContextRows() {
        contextContainer.removeAllViews();
        for (int index = 0; index < contexts.size(); index++) {
            final JSONObject context = contexts.get(index);
            String id = context.optString("id", "");
            String name = context.optString("display_name", "?");
            int sceneCount = context.optJSONArray("scenes") == null
                ? 0
                : context.optJSONArray("scenes").length();
            boolean active = id.equals(selectedActiveContextId);
            contextContainer.addView(buildRow(
                ManagementBatchController.KIND_CONTEXT,
                id,
                context,
                getString(
                    R.string.scene_context_row_summary,
                    name,
                    sceneCount,
                    active ? getString(R.string.scene_context_active_marker) : ""
                ),
                view -> showContextEditor(copyJson(context)),
                view -> setActiveContext(id),
                view -> moveContextToPending(context)
            ));
        }
    }

    private void renderGroupRows() {
        groupContainer.removeAllViews();
        for (int index = 0; index < groups.size(); index++) {
            final JSONObject group = groups.get(index);
            String id = group.optString("id", "");
            String name = group.optString("display_name", "?");
            int contextCount = group.optJSONArray("contexts") == null
                ? 0
                : group.optJSONArray("contexts").length();
            boolean active = id.equals(selectedActiveGroupId);
            groupContainer.addView(buildRow(
                ManagementBatchController.KIND_GROUP,
                id,
                group,
                getString(
                    R.string.scene_context_group_row_summary,
                    name,
                    contextCount,
                    active ? getString(R.string.scene_context_active_marker) : ""
                ),
                view -> showGroupEditor(copyJson(group)),
                view -> setActiveGroup(id),
                view -> moveGroupToPending(group)
            ));
        }
    }

    private LinearLayout buildRow(
        String kind,
        String canonicalId,
        JSONObject payload,
        String text,
        View.OnClickListener editListener,
        View.OnClickListener activateListener,
        View.OnClickListener deleteListener
    ) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(0, 8, 0, 8);

        if (batchMode) {
            ManagementBatchSelection.register(kind, canonicalId, text, payload);
            MaterialCheckBox check = new MaterialCheckBox(this);
            String key = kind + ":" + canonicalId;
            check.setChecked(ManagementBatchSelection.contains(key));
            check.setContentDescription(text);
            check.setOnCheckedChangeListener((button, checked) -> {
                ManagementBatchSelection.set(key, checked);
                if (managementBatchController != null) {
                    managementBatchController.onHostRowsChanged();
                }
            });
            row.addView(check);
        }

        TextView label = new TextView(this);
        label.setText(text);
        label.setTextSize(14);
        LinearLayout.LayoutParams labelParams = new LinearLayout.LayoutParams(
            0,
            ViewGroup.LayoutParams.WRAP_CONTENT,
            1f
        );
        label.setLayoutParams(labelParams);
        row.addView(label);

        if (!batchMode) {
            row.addView(textButton(R.string.scene_context_edit, editListener));
            row.addView(textButton(R.string.scene_context_activate, activateListener));
            row.addView(textButton(R.string.pending_process_move, deleteListener));
        }
        return row;
    }

    private MaterialButton textButton(
        int textRes,
        View.OnClickListener listener
    ) {
        MaterialButton button = new MaterialButton(this);
        button.setText(textRes);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        );
        button.setLayoutParams(params);
        button.setOnClickListener(listener);
        return button;
    }

    private void setActiveContext(String id) {
        selectedActiveContextId = id;
        int index = activeContextIds.indexOf(id);
        if (index >= 0) {
            activeContextSpinner.setSelection(index + 1);
        }
        renderContextRows();
    }

    private void setActiveGroup(String id) {
        selectedActiveGroupId = id;
        int index = activeGroupIds.indexOf(id);
        if (index >= 0) {
            activeGroupSpinner.setSelection(index + 1);
        }
        renderGroupRows();
    }

    private void moveContextToPending(JSONObject context) {
        final String id = context.optString("id", "");
        pendingMoveController.confirmMove(
            "context",
            id,
            context.optString("display_name", id),
            () -> {
                contexts.removeIf(candidate ->
                    id.equals(candidate.optString("id", ""))
                );
                for (JSONObject group : groups) {
                    removeGroupContext(group, id);
                }
                if (id.equals(selectedActiveContextId)) {
                    selectedActiveContextId = null;
                    selectedActiveGroupId = null;
                }
                render(selectedActiveContextId, selectedActiveGroupId);
            }
        );
    }

    private void moveGroupToPending(JSONObject group) {
        final String id = group.optString("id", "");
        pendingMoveController.confirmMove(
            "group",
            id,
            group.optString("display_name", id),
            () -> {
                groups.removeIf(candidate ->
                    id.equals(candidate.optString("id", ""))
                );
                if (id.equals(selectedActiveGroupId)) {
                    selectedActiveGroupId = null;
                }
                render(selectedActiveContextId, selectedActiveGroupId);
            }
        );
    }

    private void showContextEditor(final JSONObject draft) {
        final String initialDraftId = draft.optString("id", "");
        final boolean isNew = initialDraftId.isEmpty()
            || initialDraftId.startsWith("new-");
        final LinearLayout body = new LinearLayout(this);
        body.setOrientation(LinearLayout.VERTICAL);
        body.setPadding(24, 8, 24, 0);

        final EditText nameInput = new EditText(this);
        nameInput.setHint(R.string.scene_context_display_name);
        nameInput.setText(draft.optString("display_name", ""));
        body.addView(nameInput);

        final Spinner sceneSpinner = new Spinner(this);
        List<String> localScenes = listLocalScenes();
        List<String> sceneLabels = new ArrayList<>(localScenes);
        ArrayAdapter<String> sceneAdapter = new ArrayAdapter<>(
            this,
            android.R.layout.simple_spinner_item,
            sceneLabels
        );
        sceneAdapter.setDropDownViewResource(
            android.R.layout.simple_spinner_dropdown_item
        );
        sceneSpinner.setAdapter(sceneAdapter);
        body.addView(fieldLabel(R.string.scene_context_scene_add));
        body.addView(sceneSpinner);

        MaterialButton addSceneButton = new MaterialButton(this);
        addSceneButton.setText(R.string.scene_context_add_scene);
        body.addView(addSceneButton);

        final LinearLayout sceneList = new LinearLayout(this);
        sceneList.setOrientation(LinearLayout.VERTICAL);
        body.addView(sceneList);

        Runnable renderScenes = () -> renderSceneEntries(sceneList, draft);
        renderScenes.run();
        addSceneButton.setOnClickListener(view -> {
            int position = sceneSpinner.getSelectedItemPosition();
            if (position < 0 || position >= localScenes.size()) {
                return;
            }
            addSceneToDraft(draft, localScenes.get(position));
            renderScenes.run();
        });

        body.addView(fieldLabel(R.string.scene_context_manual_section));
        final EditText languageInput = new EditText(this);
        languageInput.setHint(R.string.scene_context_language_code);
        languageInput.setText(defaultTargetLanguage());
        body.addView(languageInput);

        final CheckBox immediateSummaryCheck = new CheckBox(this);
        immediateSummaryCheck.setText(
            R.string.scene_context_immediate_summary
        );
        immediateSummaryCheck.setVisibility(isNew ? View.VISIBLE : View.GONE);
        immediateSummaryCheck.setChecked(
            isNew
                && !initialDraftId.isEmpty()
                && immediateSummaryLanguages.containsKey(initialDraftId)
        );
        body.addView(immediateSummaryCheck);

        final EditText descriptionInput = new EditText(this);
        descriptionInput.setHint(R.string.scene_context_manual_description);
        body.addView(descriptionInput);

        MaterialButton saveDescriptionButton = new MaterialButton(this);
        saveDescriptionButton.setText(R.string.scene_context_save_manual_description);
        body.addView(saveDescriptionButton);

        final EditText manualSummaryInput = new EditText(this);
        manualSummaryInput.setHint(R.string.scene_context_manual_summary);
        body.addView(manualSummaryInput);

        LinearLayout summaryButtons = new LinearLayout(this);
        summaryButtons.setOrientation(LinearLayout.HORIZONTAL);
        MaterialButton saveSummaryButton = new MaterialButton(this);
        saveSummaryButton.setText(R.string.scene_context_save_manual_summary);
        MaterialButton deleteSummaryButton = new MaterialButton(this);
        deleteSummaryButton.setText(R.string.scene_context_delete_manual_summary);
        summaryButtons.addView(saveSummaryButton);
        summaryButtons.addView(deleteSummaryButton);
        body.addView(summaryButtons);

        MaterialButton requestFinalButton = new MaterialButton(this);
        requestFinalButton.setText(R.string.scene_context_request_final_summary);
        body.addView(requestFinalButton);

        saveDescriptionButton.setOnClickListener(view -> {
            String lang = languageInput.getText().toString().trim();
            String text = descriptionInput.getText().toString().trim();
            if (lang.isEmpty() || text.isEmpty()) {
                Toast.makeText(
                    this,
                    R.string.scene_context_manual_requires_language_and_text,
                    Toast.LENGTH_LONG
                ).show();
                return;
            }
            JSONObject descriptions = draft.optJSONObject("manual_descriptions");
            if (descriptions == null) {
                descriptions = new JSONObject();
                putJson(draft, "manual_descriptions", descriptions);
            }
            JSONObject record = manualRecord(
                text,
                System.currentTimeMillis()
            );
            putJson(descriptions, lang, record);
            Toast.makeText(this, R.string.scene_context_manual_description_saved,
                Toast.LENGTH_SHORT).show();
        });

        saveSummaryButton.setOnClickListener(view -> {
            String lang = languageInput.getText().toString().trim();
            String text = manualSummaryInput.getText().toString().trim();
            if (lang.isEmpty() || text.isEmpty()) {
                Toast.makeText(
                    this,
                    R.string.scene_context_manual_requires_language_and_text,
                    Toast.LENGTH_LONG
                ).show();
                return;
            }
            putManualSummary(draft, lang, text);
            Toast.makeText(this, R.string.scene_context_manual_summary_saved,
                Toast.LENGTH_SHORT).show();
        });

        deleteSummaryButton.setOnClickListener(view -> {
            String lang = languageInput.getText().toString().trim();
            if (lang.isEmpty()) {
                return;
            }
            removeManualSummary(draft, lang);
            Toast.makeText(this, R.string.scene_context_manual_summary_deleted,
                Toast.LENGTH_SHORT).show();
        });

        requestFinalButton.setOnClickListener(view -> {
            String id = draft.optString("id", "");
            if (id.isEmpty() || id.startsWith("new-")) {
                Toast.makeText(
                    this,
                    R.string.scene_context_save_before_final_summary,
                    Toast.LENGTH_LONG
                ).show();
                return;
            }
            String lang = languageInput.getText().toString().trim();
            if (lang.isEmpty()) {
                return;
            }
            requestFinalSummaryAsync(id, lang);
        });

        new MaterialAlertDialogBuilder(this)
            .setTitle(isNew
                ? R.string.scene_context_new_context
                : R.string.scene_context_edit_context)
            .setView(body)
            .setNegativeButton(R.string.cancel_action, null)
            .setPositiveButton(
                R.string.scene_context_save,
                (dialog, which) -> {
                    String name = nameInput.getText().toString().trim();
                    if (name.isEmpty()) {
                        Toast.makeText(
                            this,
                            R.string.scene_context_display_name_required,
                            Toast.LENGTH_LONG
                        ).show();
                        return;
                    }
                    putJson(draft, "display_name", name);
                    if (initialDraftId.isEmpty()) {
                        putJson(
                            draft,
                            "id",
                            "new-" + UUID.randomUUID().toString()
                        );
                    }
                    if (isNew) {
                        String draftId = draft.optString("id", "");
                        String summaryLang = languageInput.getText()
                            .toString()
                            .trim();
                        if (immediateSummaryCheck.isChecked()
                            && !summaryLang.isEmpty()) {
                            immediateSummaryLanguages.put(
                                draftId,
                                summaryLang
                            );
                        } else {
                            immediateSummaryLanguages.remove(draftId);
                        }
                    }
                    replaceOrAdd(contexts, draft);
                    render(selectedActiveContextId, selectedActiveGroupId);
                }
            )
            .show();
    }

    private void showGroupEditor(final JSONObject draft) {
        final boolean isNew = draft.optString("id", "").isEmpty();
        final LinearLayout body = new LinearLayout(this);
        body.setOrientation(LinearLayout.VERTICAL);
        body.setPadding(24, 8, 24, 0);

        final EditText nameInput = new EditText(this);
        nameInput.setHint(R.string.scene_context_display_name);
        nameInput.setText(draft.optString("display_name", ""));
        body.addView(nameInput);

        final Spinner contextSpinner = new Spinner(this);
        List<String> localContextIds = new ArrayList<>();
        List<String> localContextLabels = new ArrayList<>();
        for (JSONObject context : contexts) {
            localContextIds.add(context.optString("id", ""));
            localContextLabels.add(context.optString("display_name", "?"));
        }
        ArrayAdapter<String> contextAdapter = new ArrayAdapter<>(
            this,
            android.R.layout.simple_spinner_item,
            localContextLabels
        );
        contextAdapter.setDropDownViewResource(
            android.R.layout.simple_spinner_dropdown_item
        );
        contextSpinner.setAdapter(contextAdapter);
        body.addView(fieldLabel(R.string.scene_context_context_add));
        body.addView(contextSpinner);

        MaterialButton addContextButton = new MaterialButton(this);
        addContextButton.setText(R.string.scene_context_add_context_member);
        body.addView(addContextButton);

        final LinearLayout contextList = new LinearLayout(this);
        contextList.setOrientation(LinearLayout.VERTICAL);
        body.addView(contextList);

        Runnable renderContexts = () -> renderGroupContextEntries(
            contextList,
            draft,
            localContextIds,
            localContextLabels
        );
        renderContexts.run();
        addContextButton.setOnClickListener(view -> {
            int position = contextSpinner.getSelectedItemPosition();
            if (position < 0 || position >= localContextIds.size()) {
                return;
            }
            addContextToDraft(draft, localContextIds.get(position));
            renderContexts.run();
        });

        body.addView(fieldLabel(R.string.scene_context_group_manual_section));
        final EditText languageInput = new EditText(this);
        languageInput.setHint(R.string.scene_context_language_code);
        languageInput.setText(defaultTargetLanguage());
        body.addView(languageInput);

        final EditText manualSummaryInput = new EditText(this);
        manualSummaryInput.setHint(R.string.scene_context_manual_summary);
        body.addView(manualSummaryInput);

        LinearLayout summaryButtons = new LinearLayout(this);
        summaryButtons.setOrientation(LinearLayout.HORIZONTAL);
        MaterialButton saveSummaryButton = new MaterialButton(this);
        saveSummaryButton.setText(R.string.scene_context_save_manual_summary);
        MaterialButton deleteSummaryButton = new MaterialButton(this);
        deleteSummaryButton.setText(R.string.scene_context_delete_manual_summary);
        summaryButtons.addView(saveSummaryButton);
        summaryButtons.addView(deleteSummaryButton);
        body.addView(summaryButtons);

        saveSummaryButton.setOnClickListener(view -> {
            String lang = languageInput.getText().toString().trim();
            String text = manualSummaryInput.getText().toString().trim();
            if (lang.isEmpty() || text.isEmpty()) {
                Toast.makeText(
                    this,
                    R.string.scene_context_manual_requires_language_and_text,
                    Toast.LENGTH_LONG
                ).show();
                return;
            }
            putGroupManualSummary(draft, lang, text);
            Toast.makeText(this, R.string.scene_context_manual_summary_saved,
                Toast.LENGTH_SHORT).show();
        });

        deleteSummaryButton.setOnClickListener(view -> {
            String lang = languageInput.getText().toString().trim();
            if (lang.isEmpty()) {
                return;
            }
            removeGroupManualSummary(draft, lang);
            Toast.makeText(this, R.string.scene_context_manual_summary_deleted,
                Toast.LENGTH_SHORT).show();
        });

        new MaterialAlertDialogBuilder(this)
            .setTitle(isNew
                ? R.string.scene_context_new_group
                : R.string.scene_context_edit_group)
            .setView(body)
            .setNegativeButton(R.string.cancel_action, null)
            .setPositiveButton(
                R.string.scene_context_save,
                (dialog, which) -> {
                    String name = nameInput.getText().toString().trim();
                    if (name.isEmpty()) {
                        Toast.makeText(
                            this,
                            R.string.scene_context_display_name_required,
                            Toast.LENGTH_LONG
                        ).show();
                        return;
                    }
                    putJson(draft, "display_name", name);
                    if (isNew) {
                        putJson(
                            draft,
                            "id",
                            "new-" + UUID.randomUUID().toString()
                        );
                    }
                    replaceOrAdd(groups, draft);
                    render(selectedActiveContextId, selectedActiveGroupId);
                }
            )
            .show();
    }

    private void renderSceneEntries(LinearLayout container, JSONObject draft) {
        container.removeAllViews();
        JSONArray scenes = draft.optJSONArray("scenes");
        if (scenes == null) {
            return;
        }
        for (int index = 0; index < scenes.length(); index++) {
            final JSONObject entry = scenes.optJSONObject(index);
            if (entry == null) {
                continue;
            }
            final int position = index;
            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER_VERTICAL);
            TextView label = new TextView(this);
            label.setText(entry.optString("scene", "?"));
            LinearLayout.LayoutParams labelParams = new LinearLayout.LayoutParams(
                0,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                1f
            );
            label.setLayoutParams(labelParams);
            row.addView(label);
            row.addView(textButton(R.string.scene_context_move_up, view -> {
                moveScene(draft, position, position - 1);
                renderSceneEntries(container, draft);
            }));
            row.addView(textButton(R.string.scene_context_move_down, view -> {
                moveScene(draft, position, position + 1);
                renderSceneEntries(container, draft);
            }));
            row.addView(textButton(R.string.scene_context_remove, view -> {
                removeArrayIndex(draft, "scenes", position);
                renderSceneEntries(container, draft);
            }));
            container.addView(row);
        }
    }

    private void renderGroupContextEntries(
        LinearLayout container,
        JSONObject draft,
        List<String> contextIds,
        List<String> contextLabels
    ) {
        container.removeAllViews();
        JSONArray contexts = draft.optJSONArray("contexts");
        if (contexts == null) {
            return;
        }
        for (int index = 0; index < contexts.length(); index++) {
            final String contextId = GroupContextEntry.contextIdAt(
                contexts,
                index
            );
            final int position = index;
            String label = contextId;
            int labelIndex = contextIds.indexOf(contextId);
            if (labelIndex >= 0 && labelIndex < contextLabels.size()) {
                label = contextLabels.get(labelIndex);
            }
            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER_VERTICAL);
            TextView text = new TextView(this);
            text.setText(label);
            LinearLayout.LayoutParams textParams = new LinearLayout.LayoutParams(
                0,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                1f
            );
            text.setLayoutParams(textParams);
            row.addView(text);
            row.addView(textButton(R.string.scene_context_move_up, view -> {
                moveArrayObject(draft, "contexts", position, position - 1);
                renderGroupContextEntries(container, draft, contextIds, contextLabels);
            }));
            row.addView(textButton(R.string.scene_context_move_down, view -> {
                moveArrayObject(draft, "contexts", position, position + 1);
                renderGroupContextEntries(container, draft, contextIds, contextLabels);
            }));
            row.addView(textButton(R.string.scene_context_remove, view -> {
                removeArrayIndex(draft, "contexts", position);
                renderGroupContextEntries(container, draft, contextIds, contextLabels);
            }));
            container.addView(row);
        }
    }

    private void saveReview(final boolean save) {
        if (busy) {
            return;
        }
        String validationError = validateActiveSelection();
        if (validationError != null) {
            Toast.makeText(this, validationError, Toast.LENGTH_LONG).show();
            return;
        }

        setBusy(true);
        ioExecutor.execute(() -> {
            try {
                ContextReviewCoordinator.EditRisk risk =
                    contextReviewCoordinator.assessReview(
                        new ArrayList<>(contexts),
                        new ArrayList<>(groups)
                    );
                runOnUiThread(() -> {
                    if (isFinishing() || isDestroyed()) {
                        return;
                    }
                    showReviewRisk(save, risk);
                });
            } catch (Exception e) {
                runOnUiThread(() -> {
                    if (isFinishing() || isDestroyed()) {
                        return;
                    }
                    setBusy(false);
                    showResult(getString(
                        R.string.scene_context_save_failed,
                        safeMessage(e)
                    ));
                });
            }
        });
    }

    private void showReviewRisk(
        boolean save,
        ContextReviewCoordinator.EditRisk risk
    ) {
        int unsent = risk.userRequestedUnsentIds.size();
        int running = risk.userRequestedRunningIds.size();
        if (unsent == 0 && running == 0) {
            showReviewGenericWarning(save, risk);
            return;
        }
        int positive = unsent > 0
            ? R.string.scene_batch_edit_discard_summary_continue
            : R.string.scene_batch_edit_continue_running_summary;
        new MaterialAlertDialogBuilder(this)
            .setTitle(R.string.scene_batch_edit_manual_summary_title)
            .setMessage(getString(
                R.string.scene_batch_edit_manual_summary_message,
                unsent,
                running
            ))
            .setNegativeButton(
                R.string.scene_batch_edit_cancel_edit,
                (dialog, which) -> setBusy(false)
            )
            .setPositiveButton(
                positive,
                (dialog, which) -> showReviewGenericWarning(save, risk)
            )
            .setOnCancelListener(dialog -> setBusy(false))
            .show();
    }

    private void showReviewGenericWarning(
        boolean save,
        ContextReviewCoordinator.EditRisk risk
    ) {
        if (risk.affectedWork <= 0) {
            saveReviewConfirmed(save, risk);
            return;
        }
        new MaterialAlertDialogBuilder(this)
            .setTitle(R.string.scene_context_inflight_title)
            .setMessage(getString(
                R.string.scene_context_inflight_message,
                risk.affectedWork
            ))
            .setNegativeButton(
                R.string.cancel_action,
                (dialog, which) -> setBusy(false)
            )
            .setPositiveButton(
                R.string.scene_context_save_anyway,
                (dialog, which) -> saveReviewConfirmed(save, risk)
            )
            .setOnCancelListener(dialog -> setBusy(false))
            .show();
    }

    private void saveReviewConfirmed(
        final boolean save,
        final ContextReviewCoordinator.EditRisk risk
    ) {
        final Map<String, String> immediateRequests =
            new LinkedHashMap<>(immediateSummaryLanguages);
        setBusy(true);
        ioExecutor.execute(() -> {
            try {
                ContextReviewCoordinator.Options options = loadOptions();
                ContextReviewCoordinator.SaveResult result =
                    contextReviewCoordinator.save(
                        new ArrayList<>(contexts),
                        new ArrayList<>(groups),
                        selectedActiveContextId,
                        selectedActiveGroupId,
                        options,
                        risk,
                        !risk.userRequestedUnsentIds.isEmpty()
                    );
                final ImmediateSummaryOutcome immediate =
                    requestImmediateSummaries(immediateRequests, result);
                runOnUiThread(() -> {
                    if (isFinishing() || isDestroyed()) {
                        return;
                    }
                    setBusy(false);
                    showResult(getString(
                        R.string.scene_context_save_result,
                        result.contextsCreated,
                        result.contextsUpdated,
                        result.contextsDeleted,
                        result.groupsCreated,
                        result.groupsUpdated,
                        result.groupsDeleted,
                        result.mappingsRewritten,
                        result.userRequestedJobsCanceled
                    ));
                    immediateSummaryLanguages.keySet().removeAll(
                        immediateRequests.keySet()
                    );
                    if (immediate.requested > 0) {
                        Toast.makeText(
                            this,
                            getString(
                                R.string.scene_context_immediate_summary_result,
                                immediate.created,
                                immediate.skipped,
                                immediate.failed
                            )
                                + (immediate.firstFailure == null
                                    ? ""
                                    : " " + immediate.firstFailure),
                            Toast.LENGTH_LONG
                        ).show();
                    }
                    if (save && reviewMode) {
                        ContextReviewGate.get().complete(true);
                        finish();
                    } else {
                        refreshAsync();
                    }
                });
            } catch (Exception e) {
                runOnUiThread(() -> {
                    if (isFinishing() || isDestroyed()) {
                        return;
                    }
                    if (e instanceof
                        ContextReviewCoordinator.ConcurrentEditException) {
                        showResult(getString(
                            R.string.scene_context_concurrent_changed
                        ));
                        refreshAsync();
                        return;
                    }
                    setBusy(false);
                    showResult(getString(
                        R.string.scene_context_save_failed,
                        safeMessage(e)
                    ));
                });
            }
        });
    }

    /** Requests are made only after the Review transaction has committed. */
    private ImmediateSummaryOutcome requestImmediateSummaries(
        Map<String, String> requests,
        ContextReviewCoordinator.SaveResult saveResult
    ) {
        ImmediateSummaryOutcome outcome = new ImmediateSummaryOutcome();
        if (requests == null || requests.isEmpty() || saveResult == null) {
            return outcome;
        }
        ContextCompressionCoordinator.Options options =
            new ContextCompressionCoordinator.Options();
        try {
            ContextReviewCoordinator.Options reviewOptions = loadOptions();
            options.autoCompression = reviewOptions.autoCompression;
            options.continueAfterManual = reviewOptions.continueAfterManual;
        } catch (Exception e) {
            outcome.failed = requests.size();
            outcome.requested = requests.size();
            outcome.firstFailure = safeMessage(e);
            return outcome;
        }
        for (Map.Entry<String, String> request : requests.entrySet()) {
            String persistedId = persistedContextIdForImmediateRequest(
                saveResult,
                request.getKey()
            );
            String lang = request.getValue();
            if (persistedId == null || persistedId.trim().isEmpty()
                || lang == null || lang.trim().isEmpty()) {
                continue;
            }
            outcome.requested++;
            try {
                ContextCompressionCoordinator.Result result =
                    contextCompressionCoordinator.requestFinalSummary(
                        persistedId,
                        lang.trim(),
                        options
                    );
                if (result.finalJobCreated) {
                    outcome.created++;
                } else {
                    // Includes the explicit no-facts no-op and an already
                    // current/active request. Neither needs another admission.
                    outcome.skipped++;
                }
            } catch (Exception e) {
                outcome.failed++;
                if (outcome.firstFailure == null) {
                    outcome.firstFailure = safeMessage(e);
                }
            }
        }
        return outcome;
    }

    static String persistedContextIdForImmediateRequest(
        ContextReviewCoordinator.SaveResult saveResult,
        String draftId
    ) {
        if (saveResult == null || draftId == null) {
            return null;
        }
        return saveResult.contextIdMap.get(draftId);
    }

    private String validateActiveSelection() {
        List<ContextReviewPlanner.ContextSnapshot> contextSnapshots =
            new ArrayList<>();
        for (JSONObject context : contexts) {
            List<String> scenes = new ArrayList<>();
            JSONArray array = context.optJSONArray("scenes");
            if (array != null) {
                for (int index = 0; index < array.length(); index++) {
                    JSONObject entry = array.optJSONObject(index);
                    if (entry != null) {
                        scenes.add(entry.optString("scene", ""));
                    }
                }
            }
            contextSnapshots.add(new ContextReviewPlanner.ContextSnapshot(
                context.optString("id", ""),
                scenes
            ));
        }
        List<ContextReviewPlanner.GroupSnapshot> groupSnapshots =
            new ArrayList<>();
        for (JSONObject group : groups) {
            List<String> contextIds = new ArrayList<>();
            JSONArray array = group.optJSONArray("contexts");
            if (array != null) {
                for (int index = 0; index < array.length(); index++) {
                    contextIds.add(GroupContextEntry.contextIdAt(array, index));
                }
            }
            groupSnapshots.add(new ContextReviewPlanner.GroupSnapshot(
                group.optString("id", ""),
                contextIds
            ));
        }
        String error = ContextReviewPlanner.validateActiveGroup(
            contextSnapshots,
            groupSnapshots,
            selectedActiveContextId,
            selectedActiveGroupId
        );
        return error == null
            ? null
            : getString(R.string.scene_context_active_group_error, error);
    }

    private ContextReviewCoordinator.Options loadOptions() throws Exception {
        JSONObject userSettings = new ConfigStore(this)
            .load()
            .config
            .getJSONObject("UserSettings");
        JSONObject contextHistory = userSettings.optJSONObject("ContextHistory");
        ContextReviewCoordinator.Options options =
            new ContextReviewCoordinator.Options();
        options.autoCompression = contextHistory != null
            && contextHistory.optBoolean("EnableAutoCompression", false);
        options.continueAfterManual = contextHistory != null
            && contextHistory.optBoolean("ContinueAutoSummaryAfterManual", false);
        return options;
    }

    private void requestFinalSummaryAsync(
        final String contextId,
        final String lang
    ) {
        ioExecutor.execute(() -> {
            try {
                ContextCompressionCoordinator.Options options =
                    new ContextCompressionCoordinator.Options();
                ContextReviewCoordinator.Options reviewOptions = loadOptions();
                options.autoCompression = reviewOptions.autoCompression;
                options.continueAfterManual = reviewOptions.continueAfterManual;
                ContextCompressionCoordinator.Result result =
                    contextCompressionCoordinator.requestFinalSummary(
                        contextId,
                        lang,
                        options
                    );
                runOnUiThread(() -> Toast.makeText(
                    this,
                    result.finalJobCreated
                        ? R.string.scene_context_final_summary_created
                        : result.finalJobActive
                            ? R.string.scene_context_final_summary_active
                            : R.string.scene_context_final_summary_noop,
                    Toast.LENGTH_LONG
                ).show());
            } catch (Exception e) {
                runOnUiThread(() -> Toast.makeText(
                    this,
                    getString(
                        R.string.scene_context_final_summary_failed,
                        safeMessage(e)
                    ),
                    Toast.LENGTH_LONG
                ).show());
            }
        });
    }

    private List<String> listLocalScenes() {
        try {
            List<SceneStore.SceneInfo> infos = sceneStore.listSceneInfos();
            List<String> names = new ArrayList<>();
            for (SceneStore.SceneInfo info : infos) {
                names.add(info.sceneName);
            }
            Collections.sort(names);
            return names;
        } catch (RuntimeException e) {
            return new ArrayList<>();
        }
    }

    private static JSONObject newContextDraft() {
        JSONObject draft = new JSONObject();
        putJson(draft, "display_name", "");
        putJson(draft, "scenes", new JSONArray());
        putJson(draft, "manual_descriptions", new JSONObject());
        putJson(draft, "summary", new JSONObject());
        return draft;
    }

    private static JSONObject newGroupDraft() {
        JSONObject draft = new JSONObject();
        putJson(draft, "display_name", "");
        putJson(draft, "contexts", new JSONArray());
        putJson(draft, "summary", new JSONObject());
        return draft;
    }

    private static void addSceneToDraft(JSONObject draft, String scene) {
        JSONArray scenes = draft.optJSONArray("scenes");
        if (scenes == null) {
            scenes = new JSONArray();
            putJson(draft, "scenes", scenes);
        }
        for (int index = 0; index < scenes.length(); index++) {
            JSONObject entry = scenes.optJSONObject(index);
            if (entry != null && scene.equals(entry.optString("scene", ""))) {
                return;
            }
        }
        putJsonArray(scenes, sceneEntry(scene, System.currentTimeMillis()));
    }

    private static void moveScene(JSONObject draft, int from, int to) {
        moveArrayObject(draft, "scenes", from, to);
    }

    private static void moveArrayObject(JSONObject draft, String key, int from, int to) {
        JSONArray array = draft.optJSONArray(key);
        if (array == null || from < 0 || from >= array.length()) {
            return;
        }
        int target = Math.max(0, Math.min(array.length() - 1, to));
        if (from == target) {
            return;
        }
        JSONObject moved = array.optJSONObject(from);
        JSONArray reordered = new JSONArray();
        for (int index = 0; index < array.length(); index++) {
            if (index == from) {
                continue;
            }
            if (index == target) {
                putJsonArray(reordered, moved);
            }
            putJsonArray(reordered, array.opt(index));
        }
        if (target == array.length() - 1 && from != array.length() - 1) {
            putJsonArray(reordered, moved);
        }
        putJson(draft, key, reordered);
    }

    private static void removeArrayIndex(JSONObject draft, String key, int index) {
        JSONArray array = draft.optJSONArray(key);
        if (array == null || index < 0 || index >= array.length()) {
            return;
        }
        JSONArray updated = new JSONArray();
        for (int i = 0; i < array.length(); i++) {
            if (i != index) {
                putJsonArray(updated, array.opt(i));
            }
        }
        putJson(draft, key, updated);
    }

    private static void removeGroupContext(JSONObject draft, String contextId) {
        JSONArray array = draft.optJSONArray("contexts");
        if (array == null) {
            return;
        }
        JSONArray updated = new JSONArray();
        for (int index = 0; index < array.length(); index++) {
            JSONObject entry = array.optJSONObject(index);
            if (entry != null
                && !contextId.equals(entry.optString(GroupContextEntry.CONTEXT_ID, ""))) {
                putJsonArray(updated, entry);
            }
        }
        putJson(draft, "contexts", updated);
    }

    private static void addContextToDraft(JSONObject draft, String contextId) {
        JSONArray contexts = draft.optJSONArray("contexts");
        if (contexts == null) {
            contexts = new JSONArray();
            putJson(draft, "contexts", contexts);
        }
        for (int index = 0; index < contexts.length(); index++) {
            if (contextId.equals(GroupContextEntry.contextIdAt(contexts, index))) {
                return;
            }
        }
        putJsonArray(contexts, GroupContextEntry.create(contextId));
    }

    private static void putManualSummary(JSONObject context, String lang, String text) {
        JSONObject summary = context.optJSONObject("summary");
        if (summary == null) {
            summary = new JSONObject();
            putJson(context, "summary", summary);
        }
        JSONObject language = summary.optJSONObject(lang);
        if (language == null) {
            language = new JSONObject();
            putJson(summary, lang, language);
        }
        putJson(
            language,
            "manual",
            manualRecord(text, System.currentTimeMillis())
        );
    }

    private static void removeManualSummary(JSONObject context, String lang) {
        JSONObject summary = context.optJSONObject("summary");
        if (summary == null) {
            return;
        }
        JSONObject language = summary.optJSONObject(lang);
        if (language != null && language.has("manual")) {
            language.remove("manual");
            if (language.length() == 0) {
                summary.remove(lang);
            }
        }
    }

    private static void putGroupManualSummary(JSONObject group, String lang, String text) {
        JSONObject summary = group.optJSONObject("summary");
        if (summary == null) {
            summary = new JSONObject();
            putJson(group, "summary", summary);
        }
        JSONObject language = summary.optJSONObject(lang);
        if (language == null) {
            language = new JSONObject();
            putJson(summary, lang, language);
        }
        putJson(
            language,
            "manual",
            manualRecord(text, System.currentTimeMillis())
        );
    }

    private static void removeGroupManualSummary(JSONObject group, String lang) {
        removeManualSummary(group, lang);
    }

    private static void replaceOrAdd(List<JSONObject> items, JSONObject item) {
        String id = item.optString("id", "");
        for (int index = 0; index < items.size(); index++) {
            if (id.equals(items.get(index).optString("id", ""))) {
                items.set(index, item);
                return;
            }
        }
        items.add(item);
    }

    private TextView fieldLabel(int textRes) {
        TextView label = new TextView(this);
        label.setText(textRes);
        label.setTextSize(12);
        label.setPadding(0, 12, 0, 4);
        return label;
    }

    private String defaultTargetLanguage() {
        try {
            return new ConfigStore(this)
                .load()
                .config
                .getJSONObject("UserSettings")
                .optString("TargetLanguage", "zh-cn");
        } catch (Exception e) {
            return "zh-cn";
        }
    }

    private final class ContextBatchDataSource
        implements ManagementBatchController.BatchDataSource {
        @Override
        public String initialKind() {
            return ManagementBatchController.KIND_CONTEXT;
        }

        @Override
        public Set<String> ownedKinds() {
            return new LinkedHashSet<>(Arrays.asList(
                ManagementBatchController.KIND_CONTEXT,
                ManagementBatchController.KIND_GROUP
            ));
        }

        @Override
        public String currentFilter() {
            return "";
        }

        @Override
        public List<ManagementBatchController.Item> snapshotItems()
            throws Exception {
            List<ManagementBatchController.Item> output = new ArrayList<>();
            // Read a complete immutable store snapshot.  The UI lists are
            // rebuilt on the main thread and must never be traversed by the
            // controller's background catalog refresh.
            for (JSONObject context : sceneContextStore.listContexts()) {
                addItem(
                    output,
                    ManagementBatchController.KIND_CONTEXT,
                    context,
                    R.string.management_batch_context_label
                );
            }
            for (JSONObject group : sceneContextStore.listGroups()) {
                addItem(
                    output,
                    ManagementBatchController.KIND_GROUP,
                    group,
                    R.string.management_batch_group_label
                );
            }
            return output;
        }

        @Override
        public List<ManagementBatchController.Item> currentVisibleItems()
            throws Exception {
            List<ManagementBatchController.Item> output = new ArrayList<>();
            for (JSONObject context : contexts) {
                addItem(
                    output,
                    ManagementBatchController.KIND_CONTEXT,
                    context,
                    R.string.management_batch_context_label
                );
            }
            for (JSONObject group : groups) {
                addItem(
                    output,
                    ManagementBatchController.KIND_GROUP,
                    group,
                    R.string.management_batch_group_label
                );
            }
            return output;
        }

        private void addItem(
            List<ManagementBatchController.Item> output,
            String kind,
            JSONObject source,
            int labelRes
        ) throws Exception {
            if (source == null) {
                return;
            }
            String id = source.optString("id", "").trim();
            if (id.isEmpty()) {
                return;
            }
            JSONObject payload = new JSONObject(source.toString());
            payload.put("id", id);
            payload.put("key", id);
            output.add(new ManagementBatchController.Item(
                kind,
                id,
                getString(labelRes, source.optString("display_name", id)),
                payload
            ));
        }

        @Override
        public void onBatchModeChanged(boolean enabled) {
            batchMode = enabled;
            View root = findViewById(R.id.root_scene_context);
            int scrollY = root == null ? 0 : root.getScrollY();
            findViewById(R.id.btn_add_context).setEnabled(!enabled && !busy);
            findViewById(R.id.btn_add_group).setEnabled(!enabled && !busy);
            findViewById(R.id.btn_edit_all_scenes).setEnabled(!enabled && !busy);
            findViewById(R.id.btn_import_context_group).setEnabled(!enabled && !busy);
            findViewById(R.id.btn_export_context_group).setEnabled(!enabled && !busy);
            findViewById(R.id.spinner_active_context).setEnabled(!enabled && !busy);
            findViewById(R.id.spinner_active_group).setEnabled(!enabled && !busy);
            setManagementBatchActionEnabled(!enabled && !busy);
            renderContextRows();
            renderGroupRows();
            if (root != null) {
                root.post(() -> root.scrollTo(0, scrollY));
            }
        }

        @Override
        public void onBatchSelectionChanged() {
            View root = findViewById(R.id.root_scene_context);
            int scrollY = root == null ? 0 : root.getScrollY();
            renderContextRows();
            renderGroupRows();
            if (root != null) {
                root.post(() -> root.scrollTo(0, scrollY));
            }
            if (managementBatchController != null) {
                managementBatchController.onHostRowsChanged();
            }
        }

        @Override
        public void onBatchItemsMoved(List<String> succeededKeys) {
            // Context/group owners update several related files and indexes;
            // reload through the existing host path so every relation and
            // current selection is refreshed consistently.
            refreshAsync();
        }
    }

    private void setBusy(boolean busy) {
        this.busy = busy;
        findViewById(R.id.btn_add_context).setEnabled(!busy && !batchMode);
        findViewById(R.id.btn_add_group).setEnabled(!busy && !batchMode);
        findViewById(R.id.btn_edit_all_scenes).setEnabled(!busy && !batchMode);
        setManagementBatchActionEnabled(!busy && !batchMode);
        findViewById(R.id.btn_import_context_group).setEnabled(!busy && !batchMode);
        findViewById(R.id.btn_export_context_group).setEnabled(!busy && !batchMode);
        activeContextSpinner.setEnabled(!busy && !batchMode);
        activeGroupSpinner.setEnabled(!busy && !batchMode);
    }

    private void setManagementBatchActionEnabled(boolean enabled) {
        if (managementBatchMenuItem != null) {
            managementBatchMenuItem.setVisible(!reviewMode);
            managementBatchMenuItem.setEnabled(enabled && !reviewMode);
        }
    }

    private void showResult(String message) {
        resultView.setText(message);
        Toast.makeText(this, message, Toast.LENGTH_LONG).show();
    }

    private static String safeMessage(Throwable throwable) {
        String message = throwable.getMessage();
        return TextUtils.isEmpty(message)
            ? throwable.getClass().getSimpleName()
            : message;
    }

    private static JSONObject copyJson(JSONObject source) {
        try {
            return new JSONObject(source.toString());
        } catch (org.json.JSONException e) {
            throw new RuntimeException("could not copy JSON object", e);
        }
    }

    private static void putJson(JSONObject object, String key, Object value) {
        try {
            object.put(key, value);
        } catch (org.json.JSONException e) {
            throw new RuntimeException("could not write JSON field " + key, e);
        }
    }

    private static void putJsonArray(JSONArray array, Object value) {
        array.put(value);
    }

    private static JSONObject manualRecord(String text, long updatedAt) {
        try {
            return new JSONObject()
                .put("text", text)
                .put("updated_at", updatedAt);
        } catch (org.json.JSONException e) {
            throw new RuntimeException("could not build manual record", e);
        }
    }

    private static JSONObject sceneEntry(String scene, long now) {
        try {
            return new JSONObject()
                .put("entry_id", UUID.randomUUID().toString())
                .put("scene", scene)
                .put("scene_file", SceneStore.fileNameForScene(scene))
                .put("created_at", now)
                .put("updated_at", now)
                .put("summaries", new JSONObject());
        } catch (org.json.JSONException e) {
            throw new RuntimeException("could not build scene entry", e);
        }
    }
}
