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
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.provider.DocumentsContract;
import android.text.TextUtils;
import android.view.ContextThemeWrapper;
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
import android.widget.ScrollView;

import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.app.AlertDialog;
import androidx.core.content.ContextCompat;

import com.google.android.material.appbar.MaterialToolbar;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.checkbox.MaterialCheckBox;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.card.MaterialCardView;

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
    public static final String EXTRA_MANAGEMENT_CREATE_KIND = "management_create_kind";
    public static final String EXTRA_MANAGEMENT_SCENE = "management_scene";
    private String annotationScene;
    private JSONObject annotationDraft;
    private String expectedAnnotation;
    private AlertDialog managementEditorDialog;
    private View managementEditorBody;
    private final Map<View, Boolean> editorEnabledStates = new LinkedHashMap<>();
    public static final String EXTRA_MANAGEMENT_CONTEXT_ID =
        "management_context_id";
    public static final String EXTRA_MANAGEMENT_GROUP_ID =
        "management_group_id";
    private static final int REQUEST_IMPORT_CONTEXT_GROUP = 7101;
    private static final int REQUEST_EXPORT_CONTEXT_GROUP = 7102;
    private static final int MAX_TRANSFER_BYTES = 4 * 1024 * 1024;
    private static final String STATE_MANAGEMENT_LINK_CONSUMED =
        "management_link_consumed";
    private static final String STATE_MANAGEMENT_LINK_FAILURE_NOTIFIED =
        "management_link_failure_notified";
    private static final String STATE_MANAGEMENT_EDITOR_SESSION =
        "management_editor_session";
    /** Keep saved-state IPC bounded; large sessions use the non-config carrier. */
    private static final int MAX_MANAGEMENT_EDITOR_BUNDLE_BYTES = 128 * 1024;

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
    private boolean managementLinkConsumed;
    private boolean managementLinkFailureNotified;
    private ManagementEditorSession pendingManagementEditorSession;
    private String activeManagementEditorKind;
    private JSONObject activeManagementEditorDraft;
    private EditText activeManagementEditorNameInput;
    private EditText activeManagementEditorLanguageInput;
    private CheckBox activeManagementEditorImmediateSummaryCheck;
    private Map<String, ManualEditorRow> activeManagementEditorRows;
    private Map<String, CheckBox> activeSceneMemberships;
    private Map<String, EditText> activeSceneSummaryInputs;
    private String activeManagementEditorScene;
    private String activeManagementEditorInitialJson;
    private String activeManagementEditorInitialInputs;
    private Map<String, String> activeManagementEditorInitialImmediateSummaryLanguages;
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
        Object retained = getLastCustomNonConfigurationInstance();
        if (retained instanceof ManagementEditorSession) {
            pendingManagementEditorSession =
                (ManagementEditorSession) retained;
        } else if (savedInstanceState != null) {
            pendingManagementEditorSession =
                readManagementEditorSession(savedInstanceState);
        }
        managementLinkConsumed = savedInstanceState != null
            && savedInstanceState.getBoolean(
                STATE_MANAGEMENT_LINK_CONSUMED,
                false
            );
        managementLinkFailureNotified = savedInstanceState != null
            && savedInstanceState.getBoolean(
                STATE_MANAGEMENT_LINK_FAILURE_NOTIFIED,
                false
            );
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
        saveManagementEditorSession(outState);
        outState.putBoolean(
            STATE_MANAGEMENT_LINK_CONSUMED,
            managementLinkConsumed
        );
        outState.putBoolean(
            STATE_MANAGEMENT_LINK_FAILURE_NOTIFIED,
            managementLinkFailureNotified
        );
        super.onSaveInstanceState(outState);
    }

    @Override
    public Object onRetainCustomNonConfigurationInstance() {
        return captureManagementEditorSession();
    }

    private void saveManagementEditorSession(Bundle outState) {
        ManagementEditorSession session = captureManagementEditorSession();
        if (session == null) {
            return;
        }
        String encoded = session.encode();
        if (encoded.getBytes(StandardCharsets.UTF_8).length
            <= MAX_MANAGEMENT_EDITOR_BUNDLE_BYTES) {
            outState.putString(STATE_MANAGEMENT_EDITOR_SESSION, encoded);
        }
    }

    private static ManagementEditorSession readManagementEditorSession(
        Bundle state
    ) {
        String encoded = state.getString(STATE_MANAGEMENT_EDITOR_SESSION);
        if (TextUtils.isEmpty(encoded)) {
            return null;
        }
        try {
            return ManagementEditorSession.decode(encoded);
        } catch (Exception invalid) {
            return null;
        }
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
                final String sceneTarget = getIntent().getStringExtra(EXTRA_MANAGEMENT_SCENE);
                final JSONObject annotation = sceneTarget == null ? null
                    : new com.quarty.housamoembedtrans.scene.store.SceneAnnotationStore(getFilesDir())
                        .read(sceneTarget);
                if (sceneTarget != null) {
                    // Use the same formal Scene plus management-Pending gate
                    // that the review transaction rechecks before writing.
                    // A Scene can enter PendingProcess after this page was
                    // launched, so a plain read-valid check is insufficient.
                    sceneStore.requireSceneAvailableForManagement(sceneTarget);
                }
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
                    if (annotation != null && !managementLinkConsumed) {
                        annotationScene = sceneTarget;
                        annotationDraft = annotation;
                        expectedAnnotation = annotation.toString();
                    }
                    setBusy(false);
                    if (managementBatchController != null
                        && managementBatchController.isActive()) {
                        managementBatchController.onHostRowsChanged();
                    }
                    if (pendingManagementEditorSession != null) {
                        ManagementEditorSession session =
                            pendingManagementEditorSession;
                        pendingManagementEditorSession = null;
                        restoreManagementEditor(session);
                    } else {
                        consumeManagementLinkAfterRefresh();
                    }
                });
            } catch (Exception e) {
                runOnUiThread(() -> {
                    if (isFinishing() || isDestroyed()) {
                        return;
                    }
                    setBusy(false);
                    notifyManagementLinkLoadFailure();
                    showResult(getString(
                        R.string.scene_context_load_failed,
                        safeMessage(e)
                    ));
                });
            }
        });
    }

    private void restoreManagementEditor(ManagementEditorSession session) {
        if (session == null || session.kind.isEmpty()) {
            return;
        }
        managementLinkConsumed = true;
        immediateSummaryLanguages.clear();
        immediateSummaryLanguages.putAll(session.immediateSummaryLanguages);
        try {
            if ("scene".equals(session.kind)) {
                annotationScene = session.annotationScene;
                annotationDraft = new JSONObject(session.draftJson);
                expectedAnnotation = session.expectedAnnotation;
                showSceneEditor(session);
            } else if ("context".equals(session.kind)) {
                showContextEditor(new JSONObject(session.draftJson), session);
            } else if ("group".equals(session.kind)) {
                showGroupEditor(new JSONObject(session.draftJson), session);
            }
        } catch (Exception invalid) {
            // A malformed state snapshot must not prevent the page itself
            // from opening. The consumed link is retained as before.
            showResult(getString(
                R.string.scene_context_load_failed,
                safeMessage(invalid)
            ));
        }
    }

    private void consumeManagementLinkAfterRefresh() {
        if (managementLinkConsumed) {
            return;
        }

        Intent intent = getIntent();
        if (intent == null) {
            return;
        }
        if (intent.hasExtra(EXTRA_MANAGEMENT_SCENE)) {
            managementLinkConsumed = true;
            if (!reviewMode && annotationDraft != null) showSceneEditor();
            return;
        }
        String contextId = intent.getStringExtra(EXTRA_MANAGEMENT_CONTEXT_ID);
        String groupId = intent.getStringExtra(EXTRA_MANAGEMENT_GROUP_ID);
        String createKind = intent.getStringExtra(EXTRA_MANAGEMENT_CREATE_KIND);
        if ("context".equals(createKind) || "group".equals(createKind)) {
            managementLinkConsumed = true;
            if (!reviewMode) {
                if ("context".equals(createKind)) showContextEditor(newContextDraft());
                else showGroupEditor(newGroupDraft());
            }
            return;
        }
        if (contextId == null && groupId == null) {
            return;
        }

        // A review screen must remain a review screen. Consume the link after
        // this successful load so a later refresh cannot open a dialog.
        managementLinkConsumed = true;
        if (reviewMode) {
            return;
        }

        if (contextId != null) {
            JSONObject context = findById(contexts, contextId);
            if (context == null) {
                notifyManagementLinkTargetMissing();
            } else {
                showContextEditor(copyJson(context));
            }
            return;
        }

        JSONObject group = findById(groups, groupId);
        if (group == null) {
            notifyManagementLinkTargetMissing();
        } else {
            showGroupEditor(copyJson(group));
        }
    }

    private static final class ManagementEditorRowState {
        final String language;
        final String description;
        final String summary;

        ManagementEditorRowState(
            String language,
            String description,
            String summary
        ) {
            this.language = language == null ? "" : language;
            this.description = description == null ? "" : description;
            this.summary = summary == null ? "" : summary;
        }
    }

    /**
     * Configuration-change carrier. It contains values only, so retaining it
     * does not retain an Activity, View, or other Context-owned object.
     */
    private static final class ManagementEditorSession {
        final String kind;
        final String draftJson;
        final String initialJson;
        final String initialInputs;
        final String nameInput;
        final String languageInput;
        final boolean immediateSummaryChecked;
        final String annotationScene;
        final String expectedAnnotation;
        final List<ManagementEditorRowState> rows;
        final List<String> selectedContextIds;
        final Map<String, String> immediateSummaryLanguages;
        final Map<String, String> initialImmediateSummaryLanguages;

        ManagementEditorSession(
            String kind,
            String draftJson,
            String initialJson,
            String initialInputs,
            String nameInput,
            String languageInput,
            boolean immediateSummaryChecked,
            String annotationScene,
            String expectedAnnotation,
            List<ManagementEditorRowState> rows,
            List<String> selectedContextIds,
            Map<String, String> immediateSummaryLanguages,
            Map<String, String> initialImmediateSummaryLanguages
        ) {
            this.kind = kind == null ? "" : kind;
            this.draftJson = draftJson == null ? "" : draftJson;
            this.initialJson = initialJson == null ? "" : initialJson;
            this.initialInputs = initialInputs == null ? "" : initialInputs;
            this.nameInput = nameInput == null ? "" : nameInput;
            this.languageInput = languageInput == null ? "" : languageInput;
            this.immediateSummaryChecked = immediateSummaryChecked;
            this.annotationScene = annotationScene == null ? "" : annotationScene;
            this.expectedAnnotation = expectedAnnotation == null
                ? ""
                : expectedAnnotation;
            this.rows = rows == null
                ? new ArrayList<ManagementEditorRowState>()
                : new ArrayList<>(rows);
            this.selectedContextIds = selectedContextIds == null
                ? new ArrayList<String>()
                : new ArrayList<>(selectedContextIds);
            this.immediateSummaryLanguages = immediateSummaryLanguages == null
                ? new LinkedHashMap<String, String>()
                : new LinkedHashMap<>(immediateSummaryLanguages);
            this.initialImmediateSummaryLanguages =
                initialImmediateSummaryLanguages == null
                    ? new LinkedHashMap<String, String>()
                    : new LinkedHashMap<>(initialImmediateSummaryLanguages);
        }

        String encode() {
            JSONObject value = new JSONObject();
            putJson(value, "kind", kind);
            putJson(value, "draft", draftJson);
            putJson(value, "initial", initialJson);
            putJson(value, "initial_inputs", initialInputs);
            putJson(value, "name_input", nameInput);
            putJson(value, "language_input", languageInput);
            putJson(value, "immediate_summary_checked", immediateSummaryChecked);
            putJson(value, "annotation_scene", annotationScene);
            putJson(value, "expected_annotation", expectedAnnotation);

            JSONArray rowValues = new JSONArray();
            for (ManagementEditorRowState row : rows) {
                JSONObject item = new JSONObject();
                putJson(item, "language", row.language);
                putJson(item, "description", row.description);
                putJson(item, "summary", row.summary);
                putJsonArray(rowValues, item);
            }
            putJson(value, "rows", rowValues);

            JSONArray selected = new JSONArray();
            for (String contextId : selectedContextIds) {
                putJsonArray(selected, contextId);
            }
            putJson(value, "selected_context_ids", selected);

            JSONObject immediate = new JSONObject();
            for (Map.Entry<String, String> entry
                : immediateSummaryLanguages.entrySet()) {
                putJson(immediate, entry.getKey(), entry.getValue());
            }
            putJson(value, "immediate_summary_languages", immediate);
            JSONObject initialImmediate = new JSONObject();
            for (Map.Entry<String, String> entry
                : initialImmediateSummaryLanguages.entrySet()) {
                putJson(initialImmediate, entry.getKey(), entry.getValue());
            }
            putJson(value, "initial_immediate_summary_languages", initialImmediate);
            return value.toString();
        }

        static ManagementEditorSession decode(String encoded)
            throws org.json.JSONException {
            JSONObject value = new JSONObject(encoded);
            List<ManagementEditorRowState> rows =
                new ArrayList<>();
            JSONArray rowValues = value.optJSONArray("rows");
            for (int index = 0;
                rowValues != null && index < rowValues.length();
                index++) {
                JSONObject item = rowValues.optJSONObject(index);
                if (item == null) {
                    continue;
                }
                rows.add(new ManagementEditorRowState(
                    item.optString("language", ""),
                    item.optString("description", ""),
                    item.optString("summary", "")
                ));
            }

            List<String> selectedContextIds = new ArrayList<>();
            JSONArray selected = value.optJSONArray("selected_context_ids");
            for (int index = 0;
                selected != null && index < selected.length();
                index++) {
                String contextId = selected.optString(index, "");
                if (!contextId.isEmpty()) {
                    selectedContextIds.add(contextId);
                }
            }

            Map<String, String> immediate = new LinkedHashMap<>();
            JSONObject immediateValue =
                value.optJSONObject("immediate_summary_languages");
            if (immediateValue != null) {
                Iterator<String> keys = immediateValue.keys();
                while (keys.hasNext()) {
                    String key = keys.next();
                    immediate.put(key, immediateValue.optString(key, ""));
                }
            }
            Map<String, String> initialImmediate = new LinkedHashMap<>();
            JSONObject initialImmediateValue = value.optJSONObject(
                "initial_immediate_summary_languages"
            );
            if (initialImmediateValue != null) {
                Iterator<String> keys = initialImmediateValue.keys();
                while (keys.hasNext()) {
                    String key = keys.next();
                    initialImmediate.put(
                        key,
                        initialImmediateValue.optString(key, "")
                    );
                }
            }
            return new ManagementEditorSession(
                value.optString("kind", ""),
                value.optString("draft", ""),
                value.optString("initial", ""),
                value.optString("initial_inputs", ""),
                value.optString("name_input", ""),
                value.optString("language_input", ""),
                value.optBoolean("immediate_summary_checked", false),
                value.optString("annotation_scene", ""),
                value.optString("expected_annotation", ""),
                rows,
                selectedContextIds,
                immediate,
                initialImmediate
            );
        }
    }

    private void trackManagementEditor(
        String kind,
        JSONObject draft,
        EditText nameInput,
        EditText languageInput,
        CheckBox immediateSummaryCheck,
        Map<String, ManualEditorRow> rows,
        Map<String, CheckBox> memberships,
        Map<String, EditText> summaryInputs,
        String scene
    ) {
        if (!hasManagementLink() || reviewMode) {
            clearManagementEditorTracking();
            return;
        }
        activeManagementEditorKind = kind;
        activeManagementEditorDraft = draft;
        activeManagementEditorNameInput = nameInput;
        activeManagementEditorLanguageInput = languageInput;
        activeManagementEditorImmediateSummaryCheck = immediateSummaryCheck;
        activeManagementEditorRows = rows;
        activeSceneMemberships = memberships;
        activeSceneSummaryInputs = summaryInputs;
        activeManagementEditorScene = scene;
    }

    private void clearManagementEditorTracking() {
        activeManagementEditorKind = null;
        activeManagementEditorDraft = null;
        activeManagementEditorNameInput = null;
        activeManagementEditorLanguageInput = null;
        activeManagementEditorImmediateSummaryCheck = null;
        activeManagementEditorRows = null;
        activeSceneMemberships = null;
        activeSceneSummaryInputs = null;
        activeManagementEditorScene = null;
        activeManagementEditorInitialJson = null;
        activeManagementEditorInitialInputs = null;
        activeManagementEditorInitialImmediateSummaryLanguages = null;
        managementEditorDialog = null;
        managementEditorBody = null;
    }

    private ManagementEditorSession captureManagementEditorSession() {
        // A restored Activity may be waiting for refreshAsync() to rebuild its
        // editor. Carry that value object through another recreation instead
        // of treating the not-yet-attached editor as an empty session.
        if (pendingManagementEditorSession != null) {
            return pendingManagementEditorSession;
        }
        if (managementEditorDialog == null
            || activeManagementEditorKind == null
            || activeManagementEditorDraft == null
            || !hasManagementLink()
            || reviewMode) {
            return null;
        }

        List<ManagementEditorRowState> rows = new ArrayList<>();
        if (activeManagementEditorRows != null) {
            for (ManualEditorRow row : activeManagementEditorRows.values()) {
                if (row == null) {
                    continue;
                }
                String description = row.descriptionInput == null
                    ? ""
                    : row.descriptionInput.getText().toString();
                String summary = row.manualSummaryInput == null
                    ? ""
                    : row.manualSummaryInput.getText().toString();
                rows.add(new ManagementEditorRowState(
                    row.language,
                    description,
                    summary
                ));
            }
        } else if (activeSceneSummaryInputs != null) {
            for (Map.Entry<String, EditText> entry
                : activeSceneSummaryInputs.entrySet()) {
                String summary = entry.getValue() == null
                    ? ""
                    : entry.getValue().getText().toString();
                rows.add(new ManagementEditorRowState(
                    entry.getKey(),
                    "",
                    summary
                ));
            }
        }

        List<String> selectedContextIds = new ArrayList<>();
        if (activeSceneMemberships != null) {
            for (Map.Entry<String, CheckBox> entry
                : activeSceneMemberships.entrySet()) {
                if (entry.getValue() != null && entry.getValue().isChecked()) {
                    selectedContextIds.add(entry.getKey());
                }
            }
        }

        String name = activeManagementEditorNameInput == null
            ? ""
            : activeManagementEditorNameInput.getText().toString();
        String language = activeManagementEditorLanguageInput == null
            ? ""
            : activeManagementEditorLanguageInput.getText().toString();
        boolean immediate = activeManagementEditorImmediateSummaryCheck != null
            && activeManagementEditorImmediateSummaryCheck.isChecked();
        return new ManagementEditorSession(
            activeManagementEditorKind,
            activeManagementEditorDraft.toString(),
            activeManagementEditorInitialJson,
            activeManagementEditorInitialInputs,
            name,
            language,
            immediate,
            activeManagementEditorScene,
            "scene".equals(activeManagementEditorKind)
                ? expectedAnnotation
                : "",
            rows,
            selectedContextIds,
            immediateSummaryLanguages,
            activeManagementEditorInitialImmediateSummaryLanguages
        );
    }

    private static void restoreEditorRows(
        Map<String, ManualEditorRow> rowMap,
        List<ManagementEditorRowState> savedRows
    ) {
        if (rowMap == null || savedRows == null) {
            return;
        }
        for (ManagementEditorRowState saved : savedRows) {
            String key = findLanguageKey(rowMap, saved.language);
            if (key == null) {
                continue;
            }
            ManualEditorRow row = rowMap.get(key);
            if (row.descriptionInput != null) {
                row.descriptionInput.setText(saved.description);
            }
            if (row.manualSummaryInput != null) {
                row.manualSummaryInput.setText(saved.summary);
            }
        }
    }

    private static JSONObject findById(
        List<JSONObject> records,
        String id
    ) {
        if (id == null || id.isEmpty()) {
            return null;
        }
        for (JSONObject record : records) {
            if (id.equals(record.optString("id", ""))) {
                return record;
            }
        }
        return null;
    }

    private boolean hasManagementLink() {
        Intent intent = getIntent();
        return intent != null
            && (intent.hasExtra(EXTRA_MANAGEMENT_CONTEXT_ID)
                || intent.hasExtra(EXTRA_MANAGEMENT_GROUP_ID)
                || intent.hasExtra(EXTRA_MANAGEMENT_CREATE_KIND)
                || intent.hasExtra(EXTRA_MANAGEMENT_SCENE));
    }

    private void notifyManagementLinkLoadFailure() {
        if (!hasManagementLink() || managementLinkFailureNotified) {
            return;
        }
        managementLinkFailureNotified = true;
        Toast.makeText(
            this,
            R.string.management_link_load_failed,
            Toast.LENGTH_LONG
        ).show();
    }

    private void notifyManagementLinkTargetMissing() {
        Toast.makeText(
            this,
            R.string.management_link_target_missing,
            Toast.LENGTH_LONG
        ).show();
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
        row.setPadding(0, dp(8), 0, dp(8));

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
        showContextEditor(draft, null);
    }

    private void showContextEditor(
        final JSONObject draft,
        final ManagementEditorSession restore
    ) {
        final String initialDraftId = draft.optString("id", "");
        final boolean isNew = initialDraftId.isEmpty()
            || initialDraftId.startsWith("new-");
        final LinearLayout body = new LinearLayout(this);
        body.setOrientation(LinearLayout.VERTICAL);
        body.setPadding(dp(16), dp(8), dp(16), 0);

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

        final boolean[] scenesExpanded = {false};
        final MaterialButton scenesHeader = relationHeader(
            R.string.scene_context_scene_members,
            0,
            false
        );
        body.addView(scenesHeader);
        final TextView sceneAvailability = new TextView(this);
        body.addView(sceneAvailability);
        final LinearLayout sceneList = new LinearLayout(this);
        sceneList.setOrientation(LinearLayout.VERTICAL);
        sceneList.setVisibility(View.GONE);
        body.addView(sceneList);

        final Set<String> availableScenes = new LinkedHashSet<>(localScenes);

        final Runnable[] renderScenesHolder = new Runnable[1];
        Runnable renderScenes = () -> {
            renderSceneEntries(
                sceneList,
                draft,
                availableScenes,
                () -> renderScenesHolder[0].run()
            );
            List<String> unavailable = unavailableSceneMembers(
                draft,
                availableScenes
            );
            sceneAvailability.setText(unavailable.isEmpty()
                ? ""
                : getString(
                    R.string.scene_context_unavailable_scene_members,
                    TextUtils.join(", ", unavailable)
                ));
            sceneAvailability.setVisibility(
                unavailable.isEmpty() ? View.GONE : View.VISIBLE
            );
            JSONArray scenes = draft.optJSONArray("scenes");
            updateRelationHeader(
                scenesHeader,
                R.string.scene_context_scene_members,
                scenes == null ? 0 : scenes.length(),
                scenesExpanded[0]
            );
        };
        renderScenesHolder[0] = renderScenes;
        scenesHeader.setOnClickListener(view -> {
            scenesExpanded[0] = !scenesExpanded[0];
            sceneList.setVisibility(
                scenesExpanded[0] ? View.VISIBLE : View.GONE
            );
            renderScenes.run();
        });
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

        final MaterialButton addLanguageButton = new MaterialButton(this);
        addLanguageButton.setText(R.string.scene_context_add_language);
        body.addView(addLanguageButton);

        final LinearLayout summaryRows = new LinearLayout(this);
        summaryRows.setOrientation(LinearLayout.VERTICAL);
        body.addView(summaryRows);
        final Map<String, ManualEditorRow> manualRows = new LinkedHashMap<>();
        List<String> existingLanguages = editorLanguages(draft, true);
        if (restore != null) {
            for (ManagementEditorRowState row : restore.rows) {
                if (!row.language.isEmpty()
                    && !containsLanguage(existingLanguages, row.language)) {
                    existingLanguages.add(row.language);
                }
            }
        }
        if (existingLanguages.isEmpty()) {
            String defaultLanguage = languageInput.getText().toString().trim();
            if (!defaultLanguage.isEmpty()) {
                existingLanguages.add(defaultLanguage);
            }
        }
        for (String language : existingLanguages) {
            addContextManualEditorRow(
                summaryRows,
                draft,
                language,
                manualRows
            );
        }
        if (restore != null) {
            restoreEditorRows(manualRows, restore.rows);
        }
        addLanguageButton.setOnClickListener(view -> {
            String language = languageInput.getText().toString().trim();
            if (language.isEmpty()) {
                languageInput.setError(getString(
                    R.string.scene_context_language_required
                ));
                return;
            }
            String existing = findLanguageKey(manualRows, language);
            if (existing == null) {
                addContextManualEditorRow(
                    summaryRows,
                    draft,
                    language,
                    manualRows
                );
            } else {
                languageInput.setText(existing);
            }
        });

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
        if (restore != null) {
            immediateSummaryCheck.setChecked(restore.immediateSummaryChecked);
        }
        if (restore != null) {
            languageInput.setText(restore.languageInput);
        }
        body.addView(immediateSummaryCheck);

        MaterialButton requestFinalButton = new MaterialButton(this);
        requestFinalButton.setText(R.string.scene_context_request_final_summary);
        body.addView(requestFinalButton);

        if (!isNew) {
            addContextClosureCard(body, draft, languageInput);
        }

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

        if (restore != null) {
            nameInput.setText(restore.nameInput);
        }
        trackManagementEditor(
            "context",
            draft,
            nameInput,
            languageInput,
            immediateSummaryCheck,
            manualRows,
            null,
            null,
            null
        );
        showRecordDraftEditor(body, draft, nameInput, isNew
                ? R.string.scene_context_new_context
                : R.string.scene_context_edit_context, () -> {
                    validateAvailableSceneMembers(
                        draft,
                        new LinkedHashSet<>(listLocalScenes())
                    );
                    applyContextManualEditorRows(draft, manualRows);
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
                    if (draft.optString("id", "").isEmpty()) {
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
                    replaceOrAdd(contexts, copyJson(draft));
                }, restore);
    }

    private void addContextClosureCard(
        LinearLayout body,
        JSONObject draft,
        EditText languageInput
    ) {
        final String contextId = draft.optString("id", "");
        final MaterialCardView card = editorCard();
        final LinearLayout cardColumn = (LinearLayout) card.getTag();
        final MaterialButton header = new MaterialButton(this);
        header.setText(R.string.scene_context_manual_closure_section);
        header.setAllCaps(false);
        cardColumn.addView(header);
        final LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(dp(8), 0, dp(8), dp(8));
        content.setVisibility(View.GONE);
        cardColumn.addView(content);
        final TextView status = new TextView(this);
        status.setText(R.string.scene_context_manual_closure_loading);
        content.addView(status);
        final MaterialButton action = new MaterialButton(this);
        content.addView(action);
        final MaterialButton reopen = new MaterialButton(this);
        reopen.setText(R.string.scene_context_manual_closure_reopen);
        applyDangerText(reopen);
        content.addView(reopen);
        final ContextClosureControls controls =
            new ContextClosureControls(status, action, reopen);
        header.setOnClickListener(view -> {
            content.setVisibility(
                content.getVisibility() == View.VISIBLE
                    ? View.GONE
                    : View.VISIBLE
            );
        });
        body.addView(card);

        action.setOnClickListener(view -> {
            loadContextClosureSnapshot(
                contextId,
                snapshot -> handleContextClosureAction(
                    contextId,
                    languageInput,
                    controls,
                    snapshot
                )
            );
        });
        reopen.setOnClickListener(view -> confirmReopenManualClosure(
            contextId,
            controls
        ));
        refreshContextClosureCard(contextId, languageInput, controls);
    }

    private interface ClosureSnapshotCallback {
        void accept(ContextClosureSnapshot snapshot);
    }

    private void loadContextClosureSnapshot(
        final String contextId,
        final ClosureSnapshotCallback callback
    ) {
        ioExecutor.execute(() -> {
            try {
                SceneContextStore.ManualClosureState state =
                    contextCompressionCoordinator.getManualClosureState(
                        contextId
                    );
                String jobStatus = "";
                if (!state.requestId.isEmpty()
                    && summaryJobStore.hasJob(state.requestId)) {
                    jobStatus = summaryJobStore.readState(state.requestId)
                        .optString("status", "");
                }
                boolean active = contextId.equals(
                    sceneContextStore.getActiveContextId()
                );
                ContextClosureSnapshot snapshot =
                    new ContextClosureSnapshot(state, jobStatus, active);
                runOnUiThread(() -> callback.accept(snapshot));
            } catch (Exception error) {
                runOnUiThread(() -> showResult(getString(
                    R.string.scene_context_manual_closure_failed,
                    safeMessage(error)
                )));
            }
        });
    }

    private void refreshContextClosureCard(
        String contextId,
        EditText languageInput,
        ContextClosureControls controls
    ) {
        loadContextClosureSnapshot(
            contextId,
            snapshot -> renderContextClosureCard(
                contextId,
                languageInput,
                controls,
                snapshot
            )
        );
    }

    private void renderContextClosureCard(
        String contextId,
        EditText languageInput,
        ContextClosureControls controls,
        ContextClosureSnapshot snapshot
    ) {
        SceneContextStore.ManualClosureState state = snapshot.state;
        controls.reopen.setVisibility(
            state.isClosed() ? View.VISIBLE : View.GONE
        );
        controls.action.setEnabled(true);
        if (state.isNone()) {
            controls.status.setText(snapshot.active
                ? R.string.scene_context_manual_closure_ready
                : R.string.scene_context_manual_closure_requires_active);
            controls.action.setText(R.string.scene_context_manual_closure_open);
            controls.action.setEnabled(snapshot.active);
        } else if (state.isOpen()) {
            controls.status.setText(
                R.string.scene_context_manual_closure_open_status
            );
            controls.action.setText(
                R.string.scene_context_manual_closure_end
            );
        } else if (state.completedRequestId != null
            && !state.completedRequestId.isEmpty()) {
            controls.status.setText(
                R.string.scene_context_manual_closure_completed
            );
            controls.action.setVisibility(View.GONE);
        } else if ("queued".equals(snapshot.jobStatus)
            || "running".equals(snapshot.jobStatus)
            || "awaiting_user".equals(snapshot.jobStatus)) {
            controls.status.setText(
                R.string.scene_context_manual_closure_job_active
            );
            controls.action.setVisibility(View.GONE);
        } else if ("failed".equals(snapshot.jobStatus)) {
            controls.status.setText(
                R.string.scene_context_manual_closure_failed_status
            );
            controls.action.setVisibility(View.VISIBLE);
            controls.action.setText(
                R.string.scene_context_manual_closure_retry
            );
        } else {
            controls.status.setText(
                R.string.scene_context_manual_closure_retry_status
            );
            controls.action.setVisibility(View.VISIBLE);
            controls.action.setText(
                R.string.scene_context_manual_closure_retry
            );
        }
        if (!state.isClosed()) {
            controls.action.setVisibility(View.VISIBLE);
        }
    }

    private void handleContextClosureAction(
        String contextId,
        EditText languageInput,
        ContextClosureControls controls,
        ContextClosureSnapshot snapshot
    ) {
        if (snapshot.state.isNone()) {
            runContextClosureOpen(contextId, controls);
        } else if (snapshot.state.isOpen()) {
            confirmEndManualClosure(contextId, languageInput, controls);
        } else if (snapshot.state.isClosed()
            && controls.action.getVisibility() == View.VISIBLE) {
            confirmRetryManualClosure(contextId, languageInput, controls);
        }
    }

    private void runContextClosureOpen(
        String contextId,
        ContextClosureControls controls
    ) {
        ioExecutor.execute(() -> {
            try {
                contextCompressionCoordinator.openManualClosure(contextId);
                runOnUiThread(() -> {
                    Toast.makeText(
                        this,
                        R.string.scene_context_manual_closure_opened,
                        Toast.LENGTH_SHORT
                    ).show();
                    refreshContextClosureCard(
                        contextId,
                        null,
                        controls
                    );
                });
            } catch (Exception error) {
                runOnUiThread(() -> showResult(getString(
                    R.string.scene_context_manual_closure_failed,
                    safeMessage(error)
                )));
            }
        });
    }

    private void confirmEndManualClosure(
        String contextId,
        EditText languageInput,
        ContextClosureControls controls
    ) {
        new MaterialAlertDialogBuilder(this)
            .setTitle(R.string.scene_context_manual_closure_end_title)
            .setMessage(R.string.scene_context_manual_closure_end_message)
            .setNegativeButton(R.string.cancel_action, null)
            .setPositiveButton(
                R.string.scene_context_manual_closure_end,
                (dialog, which) -> runContextClosureEnd(
                    contextId,
                    languageInput.getText().toString().trim(),
                    controls
                )
            )
            .show();
    }

    private void runContextClosureEnd(
        String contextId,
        String targetLang,
        ContextClosureControls controls
    ) {
        ioExecutor.execute(() -> {
            try {
                ContextCompressionCoordinator.Result result =
                    contextCompressionCoordinator.endManualClosure(
                        contextId,
                        targetLang
                    );
                runOnUiThread(() -> {
                    if (result.closeBlockedByActiveJobs) {
                        showSummaryTaskBlock(result);
                    } else if (result.noFacts) {
                        showResult(getString(
                            R.string.scene_context_manual_closure_no_facts
                        ));
                    } else if (result.closureQueued) {
                        Toast.makeText(
                            this,
                            R.string.scene_context_manual_closure_queued,
                            Toast.LENGTH_LONG
                        ).show();
                    } else {
                        showResult(getString(
                            R.string.scene_context_manual_closure_retry_status
                        ));
                    }
                    refreshContextClosureCard(
                        contextId,
                        null,
                        controls
                    );
                });
            } catch (Exception error) {
                runOnUiThread(() -> showResult(getString(
                    R.string.scene_context_manual_closure_failed,
                    safeMessage(error)
                )));
            }
        });
    }

    private void confirmRetryManualClosure(
        String contextId,
        EditText languageInput,
        ContextClosureControls controls
    ) {
        new MaterialAlertDialogBuilder(this)
            .setTitle(R.string.scene_context_manual_closure_retry_title)
            .setMessage(R.string.scene_context_manual_closure_retry_message)
            .setNegativeButton(R.string.cancel_action, null)
            .setPositiveButton(
                R.string.scene_context_manual_closure_retry,
                (dialog, which) -> runContextClosureRetry(
                    contextId,
                    languageInput.getText().toString().trim(),
                    controls
                )
            )
            .show();
    }

    private void runContextClosureRetry(
        String contextId,
        String targetLang,
        ContextClosureControls controls
    ) {
        ioExecutor.execute(() -> {
            try {
                ContextCompressionCoordinator.Result result =
                    contextCompressionCoordinator.retryManualClosure(
                        contextId,
                        targetLang,
                        true
                    );
                runOnUiThread(() -> {
                    if (result.closeBlockedByActiveJobs) {
                        showSummaryTaskBlock(result);
                    } else if (result.closureQueued) {
                        Toast.makeText(
                            this,
                            R.string.scene_context_manual_closure_queued,
                            Toast.LENGTH_LONG
                        ).show();
                    } else if (result.closureCompleted) {
                        showResult(getString(
                            R.string.scene_context_manual_closure_completed
                        ));
                    } else if (result.admissionFailed) {
                        showResult(getString(
                            R.string.scene_context_manual_closure_retry_status
                        ));
                    }
                    refreshContextClosureCard(
                        contextId,
                        null,
                        controls
                    );
                });
            } catch (Exception error) {
                runOnUiThread(() -> showResult(getString(
                    R.string.scene_context_manual_closure_failed,
                    safeMessage(error)
                )));
            }
        });
    }

    private void confirmReopenManualClosure(
        String contextId,
        ContextClosureControls controls
    ) {
        AlertDialog dialog = new MaterialAlertDialogBuilder(this)
            .setTitle(R.string.scene_context_manual_closure_reopen_title)
            .setMessage(R.string.scene_context_manual_closure_reopen_message)
            .setNegativeButton(R.string.cancel_action, null)
            .setPositiveButton(
                R.string.scene_context_manual_closure_reopen,
                (ignored, which) -> ioExecutor.execute(() -> {
                    try {
                        contextCompressionCoordinator.reopenManualClosure(
                            contextId
                        );
                        runOnUiThread(() -> {
                            Toast.makeText(
                                this,
                                R.string.scene_context_manual_closure_reopened,
                                Toast.LENGTH_SHORT
                            ).show();
                            refreshContextClosureCard(
                                contextId,
                                null,
                                controls
                            );
                        });
                    } catch (Exception error) {
                        runOnUiThread(() -> showResult(getString(
                            R.string.scene_context_manual_closure_failed,
                            safeMessage(error)
                        )));
                    }
                })
            )
            .show();
        dialog.getButton(AlertDialog.BUTTON_POSITIVE)
            .setTextColor(dangerColor());
    }

    private void showSummaryTaskBlock(
        ContextCompressionCoordinator.Result result
    ) {
        String detail = getString(
            R.string.scene_context_manual_closure_blocked,
            result.activeSummaryRequestIds.size()
        );
        new MaterialAlertDialogBuilder(this)
            .setTitle(R.string.scene_context_manual_closure_blocked_title)
            .setMessage(detail)
            .setNegativeButton(R.string.cancel_action, null)
            .setPositiveButton(
                R.string.scene_context_manual_closure_open_tasks,
                (dialog, which) -> startActivity(
                    new Intent(this, TranslationQueueActivity.class)
                )
            )
            .show();
    }

    private void addGroupClosureCard(
        LinearLayout body,
        JSONObject draft,
        EditText languageInput
    ) {
        final String groupId = draft.optString("id", "");
        final MaterialCardView card = editorCard();
        final LinearLayout cardColumn = (LinearLayout) card.getTag();
        final MaterialButton header = new MaterialButton(this);
        header.setText(R.string.scene_group_manual_closure_section);
        header.setAllCaps(false);
        cardColumn.addView(header);
        final LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(dp(8), 0, dp(8), dp(8));
        content.setVisibility(View.GONE);
        cardColumn.addView(content);
        final TextView status = new TextView(this);
        status.setText(R.string.scene_group_manual_closure_loading);
        content.addView(status);
        final MaterialButton action = new MaterialButton(this);
        content.addView(action);
        final MaterialButton reopen = new MaterialButton(this);
        reopen.setText(R.string.scene_group_manual_closure_reopen);
        applyDangerText(reopen);
        content.addView(reopen);
        final GroupClosureControls controls =
            new GroupClosureControls(status, action, reopen);
        header.setOnClickListener(view -> {
            content.setVisibility(
                content.getVisibility() == View.VISIBLE
                    ? View.GONE
                    : View.VISIBLE
            );
        });
        body.addView(card);

        action.setOnClickListener(view -> {
            loadGroupClosureSnapshot(
                groupId,
                snapshot -> handleGroupClosureAction(
                    groupId,
                    languageInput,
                    controls,
                    snapshot
                )
            );
        });
        reopen.setOnClickListener(view -> confirmReopenGroupClosure(
            groupId,
            controls
        ));
        refreshGroupClosureCard(groupId, controls, null);
    }

    private interface GroupClosureSnapshotCallback {
        void accept(GroupClosureSnapshot snapshot);
    }

    private void loadGroupClosureSnapshot(
        final String groupId,
        final GroupClosureSnapshotCallback callback
    ) {
        ioExecutor.execute(() -> {
            try {
                SceneContextStore.ManualClosureState state =
                    groupCompressionCoordinator.getManualClosureState(groupId);
                String jobStatus = "";
                if (!state.requestId.isEmpty()
                    && summaryJobStore.hasJob(state.requestId)) {
                    jobStatus = summaryJobStore.readState(state.requestId)
                        .optString("status", "");
                }
                boolean active = groupId.equals(
                    sceneContextStore.getActiveGroupId()
                );
                GroupClosureSnapshot snapshot = new GroupClosureSnapshot(
                    state,
                    jobStatus,
                    active
                );
                runOnUiThread(() -> callback.accept(snapshot));
            } catch (Exception error) {
                runOnUiThread(() -> showResult(getString(
                    R.string.scene_group_manual_closure_failed,
                    safeMessage(error)
                )));
            }
        });
    }

    private void refreshGroupClosureCard(
        String groupId,
        GroupClosureControls controls,
        List<String> missingContextIds
    ) {
        loadGroupClosureSnapshot(
            groupId,
            snapshot -> {
                renderGroupClosureCard(controls, snapshot);
                if (missingContextIds != null && !missingContextIds.isEmpty()) {
                    controls.status.setText(getString(
                        R.string.scene_group_manual_closure_missing_contexts,
                        describeGroupContextIds(missingContextIds)
                    ));
                }
            }
        );
    }

    private void renderGroupClosureCard(
        GroupClosureControls controls,
        GroupClosureSnapshot snapshot
    ) {
        SceneContextStore.ManualClosureState state = snapshot.state;
        controls.reopen.setVisibility(
            state.isClosed() ? View.VISIBLE : View.GONE
        );
        controls.action.setVisibility(View.VISIBLE);
        controls.action.setEnabled(true);
        if (state.isNone()) {
            controls.status.setText(snapshot.active
                ? R.string.scene_group_manual_closure_ready
                : R.string.scene_group_manual_closure_requires_active);
            controls.action.setText(R.string.scene_group_manual_closure_open);
            controls.action.setEnabled(snapshot.active);
        } else if (state.isOpen()) {
            controls.status.setText(
                R.string.scene_group_manual_closure_open_status
            );
            controls.action.setText(R.string.scene_group_manual_closure_end);
        } else if (!TextUtils.isEmpty(state.completedRequestId)) {
            controls.status.setText(
                R.string.scene_group_manual_closure_completed
            );
            controls.action.setVisibility(View.GONE);
        } else if ("queued".equals(snapshot.jobStatus)
            || "running".equals(snapshot.jobStatus)
            || "awaiting_user".equals(snapshot.jobStatus)) {
            controls.status.setText(
                R.string.scene_group_manual_closure_job_active
            );
            controls.action.setVisibility(View.GONE);
        } else if ("failed".equals(snapshot.jobStatus)) {
            controls.status.setText(
                R.string.scene_group_manual_closure_failed_status
            );
            controls.action.setText(R.string.scene_group_manual_closure_retry);
        } else {
            controls.status.setText(
                R.string.scene_group_manual_closure_retry_status
            );
            controls.action.setText(R.string.scene_group_manual_closure_retry);
        }
    }

    private void handleGroupClosureAction(
        String groupId,
        EditText languageInput,
        GroupClosureControls controls,
        GroupClosureSnapshot snapshot
    ) {
        if (snapshot.state.isNone()) {
            runGroupClosureOpen(groupId, controls);
        } else if (snapshot.state.isOpen()) {
            confirmEndGroupClosure(
                groupId,
                closureTargetLanguage(languageInput, snapshot),
                controls
            );
        } else if (snapshot.state.isClosed()
            && controls.action.getVisibility() == View.VISIBLE) {
            beginGroupClosureRetry(
                groupId,
                closureTargetLanguage(languageInput, snapshot),
                controls
            );
        }
    }

    private String closureTargetLanguage(
        EditText languageInput,
        GroupClosureSnapshot snapshot
    ) {
        if (snapshot != null
            && !TextUtils.isEmpty(snapshot.state.targetLang)) {
            return snapshot.state.targetLang;
        }
        return languageInput == null
            ? ""
            : languageInput.getText().toString().trim();
    }

    private void runGroupClosureOpen(
        String groupId,
        GroupClosureControls controls
    ) {
        ioExecutor.execute(() -> {
            try {
                groupCompressionCoordinator.openManualClosure(groupId);
                runOnUiThread(() -> {
                    Toast.makeText(
                        this,
                        R.string.scene_group_manual_closure_opened,
                        Toast.LENGTH_SHORT
                    ).show();
                    refreshGroupClosureCard(groupId, controls, null);
                });
            } catch (Exception error) {
                runOnUiThread(() -> showResult(getString(
                    R.string.scene_group_manual_closure_failed,
                    safeMessage(error)
                )));
            }
        });
    }

    private void confirmEndGroupClosure(
        String groupId,
        String targetLang,
        GroupClosureControls controls
    ) {
        new MaterialAlertDialogBuilder(this)
            .setTitle(R.string.scene_group_manual_closure_end_title)
            .setMessage(R.string.scene_group_manual_closure_end_message)
            .setNegativeButton(R.string.cancel_action, null)
            .setPositiveButton(
                R.string.scene_group_manual_closure_end,
                (dialog, which) -> runGroupClosureEnd(
                    groupId,
                    targetLang,
                    controls
                )
            )
            .show();
    }

    private void runGroupClosureEnd(
        String groupId,
        String targetLang,
        GroupClosureControls controls
    ) {
        ioExecutor.execute(() -> {
            try {
                GroupCompressionCoordinator.Result result =
                    groupCompressionCoordinator.endManualClosure(
                        groupId,
                        targetLang
                    );
                runOnUiThread(() -> {
                    if (result.closeBlockedByActiveJobs) {
                        showGroupSummaryTaskBlock(result);
                    } else if (result.dependenciesMissing) {
                        showGroupMissingContexts(result.missingContextIds);
                    } else if (result.noFacts) {
                        showResult(getString(
                            R.string.scene_group_manual_closure_no_facts
                        ));
                    } else if (result.closureQueued) {
                        Toast.makeText(
                            this,
                            R.string.scene_group_manual_closure_queued,
                            Toast.LENGTH_LONG
                        ).show();
                    } else if (result.admissionFailed
                        || result.closureRetryable) {
                        showGroupClosureError(result);
                    }
                    refreshGroupClosureCard(
                        groupId,
                        controls,
                        result.dependenciesMissing
                            ? result.missingContextIds
                            : null
                    );
                });
            } catch (Exception error) {
                runOnUiThread(() -> showResult(getString(
                    R.string.scene_group_manual_closure_failed,
                    safeMessage(error)
                )));
            }
        });
    }

    private void beginGroupClosureRetry(
        String groupId,
        String targetLang,
        GroupClosureControls controls
    ) {
        runGroupClosureRetry(groupId, targetLang, controls, false);
    }

    private void runGroupClosureRetry(
        String groupId,
        String targetLang,
        GroupClosureControls controls,
        boolean acceptCurrentFacts
    ) {
        ioExecutor.execute(() -> {
            try {
                GroupCompressionCoordinator.Result result =
                    groupCompressionCoordinator.retryManualClosure(
                        groupId,
                        targetLang,
                        acceptCurrentFacts
                    );
                runOnUiThread(() -> {
                    if (result.requiresLatestFactsConfirmation
                        && !acceptCurrentFacts) {
                        confirmGroupRetryWithLatestFacts(
                            groupId,
                            targetLang,
                            controls
                        );
                        return;
                    }
                    showGroupRetryResult(result);
                    refreshGroupClosureCard(
                        groupId,
                        controls,
                        result.dependenciesMissing
                            ? result.missingContextIds
                            : null
                    );
                });
            } catch (Exception error) {
                runOnUiThread(() -> showResult(getString(
                    R.string.scene_group_manual_closure_failed,
                    safeMessage(error)
                )));
            }
        });
    }

    private void showGroupRetryResult(
        GroupCompressionCoordinator.Result result
    ) {
        if (result.closeBlockedByActiveJobs) {
            showGroupSummaryTaskBlock(result);
        } else if (result.dependenciesMissing) {
            showGroupMissingContexts(result.missingContextIds);
        } else if (result.noFacts) {
            showResult(getString(
                R.string.scene_group_manual_closure_no_facts
            ));
        } else if (result.closureQueued) {
            Toast.makeText(
                this,
                R.string.scene_group_manual_closure_queued,
                Toast.LENGTH_LONG
            ).show();
        } else if (result.closureCompleted) {
            showResult(getString(
                R.string.scene_group_manual_closure_completed
            ));
        } else if (result.admissionFailed || result.closureRetryable) {
            showGroupClosureError(result);
        }
    }

    private void confirmGroupRetryWithLatestFacts(
        String groupId,
        String targetLang,
        GroupClosureControls controls
    ) {
        new MaterialAlertDialogBuilder(this)
            .setTitle(R.string.scene_group_manual_closure_latest_facts_title)
            .setMessage(
                R.string.scene_group_manual_closure_latest_facts_message
            )
            .setNegativeButton(R.string.cancel_action, null)
            .setPositiveButton(
                R.string.scene_group_manual_closure_retry,
                (dialog, which) -> runGroupClosureRetry(
                    groupId,
                    targetLang,
                    controls,
                    true
                )
            )
            .show();
    }

    private void confirmReopenGroupClosure(
        String groupId,
        GroupClosureControls controls
    ) {
        AlertDialog dialog = new MaterialAlertDialogBuilder(this)
            .setTitle(R.string.scene_group_manual_closure_reopen_title)
            .setMessage(R.string.scene_group_manual_closure_reopen_message)
            .setNegativeButton(R.string.cancel_action, null)
            .setPositiveButton(
                R.string.scene_group_manual_closure_reopen,
                (ignored, which) -> ioExecutor.execute(() -> {
                    try {
                        groupCompressionCoordinator.reopenManualClosure(
                            groupId
                        );
                        runOnUiThread(() -> {
                            Toast.makeText(
                                this,
                                R.string.scene_group_manual_closure_reopened,
                                Toast.LENGTH_SHORT
                            ).show();
                            refreshGroupClosureCard(groupId, controls, null);
                        });
                    } catch (Exception error) {
                        runOnUiThread(() -> showResult(getString(
                            R.string.scene_group_manual_closure_failed,
                            safeMessage(error)
                        )));
                    }
                })
            )
            .show();
        dialog.getButton(AlertDialog.BUTTON_POSITIVE)
            .setTextColor(dangerColor());
    }

    private void showGroupSummaryTaskBlock(
        GroupCompressionCoordinator.Result result
    ) {
        String detail = getString(
            R.string.scene_group_manual_closure_blocked,
            result.activeSummaryRequestIds.size()
        );
        new MaterialAlertDialogBuilder(this)
            .setTitle(R.string.scene_group_manual_closure_blocked_title)
            .setMessage(detail)
            .setNegativeButton(R.string.cancel_action, null)
            .setPositiveButton(
                R.string.scene_group_manual_closure_open_tasks,
                (dialog, which) -> startActivity(
                    new Intent(this, TranslationQueueActivity.class)
                )
            )
            .show();
    }

    private void showGroupMissingContexts(List<String> missingContextIds) {
        String names = describeGroupContextIds(missingContextIds);
        String message = getString(
            R.string.scene_group_manual_closure_missing_contexts,
            names
        );
        showResult(message);
        new MaterialAlertDialogBuilder(this)
            .setTitle(R.string.scene_group_manual_closure_missing_title)
            .setMessage(message)
            .setNegativeButton(R.string.cancel_action, null)
            .setPositiveButton(
                R.string.scene_group_manual_closure_open_tasks,
                (dialog, which) -> startActivity(
                    new Intent(this, TranslationQueueActivity.class)
                )
            )
            .show();
    }

    private void showGroupClosureError(
        GroupCompressionCoordinator.Result result
    ) {
        String detail = TextUtils.isEmpty(result.closureFailure)
            ? getString(R.string.scene_group_manual_closure_retry_status)
            : getString(
                R.string.scene_group_manual_closure_failed,
                result.closureFailure
            );
        new MaterialAlertDialogBuilder(this)
            .setTitle(R.string.scene_group_manual_closure_failed_title)
            .setMessage(detail)
            .setNegativeButton(R.string.cancel_action, null)
            .setPositiveButton(
                R.string.scene_group_manual_closure_open_tasks,
                (dialog, which) -> startActivity(
                    new Intent(this, TranslationQueueActivity.class)
                )
            )
            .show();
    }

    private String describeGroupContextIds(List<String> contextIds) {
        if (contextIds == null || contextIds.isEmpty()) {
            return NONE_PLACEHOLDER;
        }
        List<String> labels = new ArrayList<>();
        for (String contextId : contextIds) {
            String label = contextId;
            for (JSONObject context : contexts) {
                if (contextId.equals(context.optString("id", ""))) {
                    label = context.optString("display_name", contextId);
                    break;
                }
            }
            labels.add(label);
        }
        return TextUtils.join(", ", labels);
    }

    private void showGroupEditor(final JSONObject draft) {
        showGroupEditor(draft, null);
    }

    private void showGroupEditor(
        final JSONObject draft,
        final ManagementEditorSession restore
    ) {
        final String initialDraftId = draft.optString("id", "");
        final boolean isNew = initialDraftId.isEmpty()
            || initialDraftId.startsWith("new-");
        final LinearLayout body = new LinearLayout(this);
        body.setOrientation(LinearLayout.VERTICAL);
        body.setPadding(dp(16), dp(8), dp(16), 0);

        final EditText nameInput = new EditText(this);
        nameInput.setHint(R.string.scene_context_display_name);
        nameInput.setText(draft.optString("display_name", ""));
        body.addView(nameInput);

        final Spinner contextSpinner = new Spinner(this);
        final Set<String> availableContextIds;
        try {
            availableContextIds = currentAvailableContextIds();
        } catch (IllegalArgumentException unavailable) {
            showResult(safeMessage(unavailable));
            return;
        }
        List<String> localContextIds = new ArrayList<>();
        List<String> localContextLabels = new ArrayList<>();
        for (JSONObject context : contexts) {
            String contextId = context.optString("id", "");
            if (!availableContextIds.contains(contextId)) {
                continue;
            }
            localContextIds.add(contextId);
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

        final boolean[] contextsExpanded = {false};
        final MaterialButton contextsHeader = relationHeader(
            R.string.scene_context_group_members,
            0,
            false
        );
        body.addView(contextsHeader);
        final TextView contextAvailability = new TextView(this);
        body.addView(contextAvailability);
        final LinearLayout contextList = new LinearLayout(this);
        contextList.setOrientation(LinearLayout.VERTICAL);
        contextList.setVisibility(View.GONE);
        body.addView(contextList);

        final Runnable[] renderContextsHolder = new Runnable[1];
        Runnable renderContexts = () -> {
            renderGroupContextEntries(
                contextList,
                draft,
                localContextIds,
                localContextLabels,
                availableContextIds,
                () -> renderContextsHolder[0].run()
            );
            List<String> unavailable = unavailableContextMembers(
                draft,
                availableContextIds
            );
            contextAvailability.setText(unavailable.isEmpty()
                ? ""
                : getString(
                    R.string.scene_context_unavailable_context_members,
                    describeGroupContextIds(unavailable)
                ));
            contextAvailability.setVisibility(
                unavailable.isEmpty() ? View.GONE : View.VISIBLE
            );
            JSONArray contextsArray = draft.optJSONArray("contexts");
            updateRelationHeader(
                contextsHeader,
                R.string.scene_context_group_members,
                contextsArray == null ? 0 : contextsArray.length(),
                contextsExpanded[0]
            );
        };
        renderContextsHolder[0] = renderContexts;
        contextsHeader.setOnClickListener(view -> {
            contextsExpanded[0] = !contextsExpanded[0];
            contextList.setVisibility(
                contextsExpanded[0] ? View.VISIBLE : View.GONE
            );
            renderContexts.run();
        });
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

        final MaterialButton addLanguageButton = new MaterialButton(this);
        addLanguageButton.setText(R.string.scene_context_add_language);
        body.addView(addLanguageButton);

        final LinearLayout summaryRows = new LinearLayout(this);
        summaryRows.setOrientation(LinearLayout.VERTICAL);
        body.addView(summaryRows);
        final Map<String, ManualEditorRow> manualRows = new LinkedHashMap<>();
        List<String> existingLanguages = editorLanguages(draft, false);
        if (restore != null) {
            for (ManagementEditorRowState row : restore.rows) {
                if (!row.language.isEmpty()
                    && !containsLanguage(existingLanguages, row.language)) {
                    existingLanguages.add(row.language);
                }
            }
        }
        if (existingLanguages.isEmpty()) {
            String defaultLanguage = languageInput.getText().toString().trim();
            if (!defaultLanguage.isEmpty()) {
                existingLanguages.add(defaultLanguage);
            }
        }
        for (String language : existingLanguages) {
            addGroupManualEditorRow(
                summaryRows,
                draft,
                language,
                manualRows
            );
        }
        if (restore != null) {
            restoreEditorRows(manualRows, restore.rows);
        }
        addLanguageButton.setOnClickListener(view -> {
            String language = languageInput.getText().toString().trim();
            if (language.isEmpty()) {
                languageInput.setError(getString(
                    R.string.scene_context_language_required
                ));
                return;
            }
            String existing = findLanguageKey(manualRows, language);
            if (existing == null) {
                addGroupManualEditorRow(
                    summaryRows,
                    draft,
                    language,
                    manualRows
                );
            } else {
                languageInput.setText(existing);
            }
        });

        if (!isNew) {
            addGroupClosureCard(body, draft, languageInput);
        }

        if (restore != null) {
            nameInput.setText(restore.nameInput);
            languageInput.setText(restore.languageInput);
        }
        trackManagementEditor(
            "group",
            draft,
            nameInput,
            languageInput,
            null,
            manualRows,
            null,
            null,
            null
        );

        showRecordDraftEditor(body, draft, nameInput, isNew
                ? R.string.scene_context_new_group
                : R.string.scene_context_edit_group, () -> {
                    validateAvailableContextMembers(
                        draft,
                        currentAvailableContextIds()
                    );
                    applyGroupManualEditorRows(draft, manualRows);
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
                    if (draft.optString("id", "").isEmpty()) {
                        putJson(
                            draft,
                            "id",
                            "new-" + UUID.randomUUID().toString()
                        );
                    }
                    replaceOrAdd(groups, copyJson(draft));
                }, restore);
    }

    private void showSceneEditor() {
        showSceneEditor(null);
    }

    private void showSceneEditor(final ManagementEditorSession restore) {
        LinearLayout body = new LinearLayout(this);
        body.setOrientation(LinearLayout.VERTICAL);
        body.setPadding(dp(16), dp(8), dp(16), 0);
        EditText identity = new EditText(this);
        identity.setText(annotationScene);
        identity.setEnabled(false);
        body.addView(identity);
        body.addView(fieldLabel(R.string.scene_annotation_contexts));
        Map<String, CheckBox> memberships = new LinkedHashMap<>();
        for (JSONObject context : contexts) {
            CheckBox choice = new CheckBox(this);
            choice.setText(context.optString("display_name", context.optString("id")));
            String contextId = context.optString("id", "");
            if (restore != null) {
                choice.setChecked(restore.selectedContextIds.contains(contextId));
            } else {
                JSONArray members = context.optJSONArray("scenes");
                for (int i = 0; members != null && i < members.length(); i++) {
                    JSONObject member = members.optJSONObject(i);
                    if (member != null && annotationScene.equals(member.optString("scene"))) choice.setChecked(true);
                }
            }
            memberships.put(contextId, choice);
            body.addView(choice);
        }
        body.addView(fieldLabel(R.string.scene_annotation_summaries));
        Map<String, EditText> summaryInputs = new LinkedHashMap<>();
        JSONObject loadedSummaries = annotationDraft.optJSONObject(
            "manual_summaries"
        );
        JSONObject initialSummaries = loadedSummaries == null
            ? new JSONObject()
            : loadedSummaries;
        LinearLayout rows = new LinearLayout(this);
        rows.setOrientation(LinearLayout.VERTICAL);
        body.addView(rows);
        List<String> summaryLanguages = new ArrayList<>();
        if (restore == null) {
            Iterator<String> keys = initialSummaries.keys();
            while (keys.hasNext()) {
                summaryLanguages.add(keys.next());
            }
        } else {
            // The captured row set is authoritative: it also records rows
            // removed from the dynamic editor before the snapshot.
            for (ManagementEditorRowState row : restore.rows) {
                if (!row.language.isEmpty()
                    && !containsLanguage(summaryLanguages, row.language)) {
                    summaryLanguages.add(row.language);
                }
            }
        }
        for (String language : summaryLanguages) {
            JSONObject record = initialSummaries.optJSONObject(language);
            addSceneSummaryRow(rows, summaryInputs, language,
                record == null ? "" : record.optString("text", ""));
        }
        EditText language = new EditText(this);
        language.setHint(R.string.scene_context_language_code);
        body.addView(language);
        MaterialButton add = new MaterialButton(this);
        add.setText(R.string.scene_annotation_add_language);
        add.setOnClickListener(view -> {
            String key = language.getText().toString().trim();
            if (key.isEmpty() || summaryInputs.containsKey(key)) {
                language.setError(getString(R.string.scene_annotation_invalid_language));
                return;
            }
            addSceneSummaryRow(rows, summaryInputs, key, "");
            language.setText("");
        });
        body.addView(add);
        if (restore != null) {
            language.setText(restore.languageInput);
            for (ManagementEditorRowState row : restore.rows) {
                EditText input = summaryInputs.get(row.language);
                if (input != null) {
                    input.setText(row.summary);
                }
            }
        }
        trackManagementEditor(
            "scene",
            annotationDraft,
            null,
            language,
            null,
            null,
            memberships,
            summaryInputs,
            annotationScene
        );
        showRecordDraftEditor(body, annotationDraft, identity, R.string.scene_annotation_edit, () -> {
            JSONObject summaries = new JSONObject();
            try {
                for (Map.Entry<String, EditText> entry : summaryInputs.entrySet()) {
                    String text = entry.getValue().getText().toString().trim();
                    if (text.isEmpty()) {
                        entry.getValue().setError(getString(R.string.error_required));
                        throw new IllegalArgumentException(getString(R.string.scene_context_manual_requires_language_and_text));
                    }
                    JSONObject previous = initialSummaries.optJSONObject(entry.getKey());
                    summaries.put(entry.getKey(), previous != null && text.equals(previous.optString("text"))
                        ? copyJson(previous) : manualRecord(text, System.currentTimeMillis()));
                }
                annotationDraft.put("manual_summaries", summaries);
            } catch (org.json.JSONException failure) {
                throw new IllegalArgumentException(failure);
            }
            for (int i = 0; i < contexts.size(); i++) {
                JSONObject draft = copyJson(contexts.get(i));
                CheckBox choice = memberships.get(draft.optString("id"));
                if (choice.isChecked()) addSceneToDraft(draft, annotationScene);
                else {
                    JSONArray members = draft.optJSONArray("scenes");
                    for (int j = members == null ? -1 : members.length() - 1; j >= 0; j--) {
                        JSONObject member = members.optJSONObject(j);
                        if (member != null && annotationScene.equals(member.optString("scene"))) {
                            removeArrayIndex(draft, "scenes", j);
                        }
                    }
                }
                contexts.set(i, draft);
            }
        }, restore);
    }

    private void addSceneSummaryRow(LinearLayout rows, Map<String, EditText> inputs,
        String language, String text) {
        MaterialCardView card = editorCard();
        LinearLayout row = (LinearLayout) card.getTag();
        row.setOrientation(LinearLayout.VERTICAL);
        TextView label = new TextView(this);
        label.setText(language);
        row.addView(label);
        EditText input = new EditText(this);
        input.setMinLines(2);
        input.setText(text);
        row.addView(input);
        inputs.put(language, input);
        MaterialButton remove = new MaterialButton(this);
        remove.setText(R.string.scene_context_delete_manual_summary);
        applyDangerText(remove);
        remove.setOnClickListener(view -> { inputs.remove(language); rows.removeView(card); });
        row.addView(remove);
        rows.addView(card);
    }

    /** Management links commit through the existing review transaction. */
    private void showRecordDraftEditor(
        LinearLayout body, JSONObject draft, EditText nameInput,
        int title, Runnable stageDraft
    ) {
        showRecordDraftEditor(body, draft, nameInput, title, stageDraft, null);
    }

    private void showRecordDraftEditor(
        LinearLayout body, JSONObject draft, EditText nameInput,
        int title, Runnable stageDraft, ManagementEditorSession restore
    ) {
        final String initialJson = restore == null
            ? draft.toString()
            : restore.initialJson;
        final String initialInputs = restore == null
            ? draftInputText(body)
            : restore.initialInputs;
        final List<JSONObject> originalContexts = new ArrayList<>(contexts);
        final List<JSONObject> originalGroups = new ArrayList<>(groups);
        final Map<String, String> originalRequests = new LinkedHashMap<>(
            restore == null
                ? immediateSummaryLanguages
                : restore.initialImmediateSummaryLanguages
        );
        final boolean direct = hasManagementLink() && !reviewMode;
        if (direct) {
            activeManagementEditorInitialJson = initialJson;
            activeManagementEditorInitialInputs = initialInputs;
            activeManagementEditorInitialImmediateSummaryLanguages =
                new LinkedHashMap<>(originalRequests);
        }
        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.addView(body);
        AlertDialog editor = new MaterialAlertDialogBuilder(this)
            .setTitle(title).setView(scroll)
            .setNegativeButton(R.string.cancel_action, null)
            .setPositiveButton(R.string.scene_context_save, null).create();
        Runnable discard = () -> {
            if (direct) {
                contexts.clear();
                contexts.addAll(originalContexts);
                groups.clear();
                groups.addAll(originalGroups);
                immediateSummaryLanguages.clear();
                immediateSummaryLanguages.putAll(originalRequests);
                clearManagementEditorTracking();
            }
            editor.dismiss();
            if (direct) finish();
        };
        Runnable back = () -> {
            if (busy) return;
            if (initialJson.equals(draft.toString())
                && initialInputs.equals(draftInputText(body))) {
                discard.run();
                return;
            }
            new MaterialAlertDialogBuilder(this)
                .setTitle(R.string.context_editor_discard_title)
                .setMessage(R.string.context_editor_discard_message)
                .setNegativeButton(R.string.keep_editing, null)
                .setPositiveButton(R.string.discard_changes, (dialog, which) -> discard.run())
                .show();
        };
        editor.setCanceledOnTouchOutside(false);
        editor.setOnKeyListener((dialog, keyCode, event) -> {
            if (keyCode != android.view.KeyEvent.KEYCODE_BACK) return false;
            if (event.getAction() == android.view.KeyEvent.ACTION_UP) back.run();
            return true;
        });
        editor.setOnShowListener(dialog -> {
            editor.getButton(AlertDialog.BUTTON_NEGATIVE).setOnClickListener(view -> back.run());
            editor.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(view -> {
                if (busy) return;
                if (nameInput.getText().toString().trim().isEmpty()) {
                    nameInput.setError(getString(R.string.scene_context_display_name_required));
                    return;
                }
                try {
                    stageDraft.run();
                } catch (IllegalArgumentException invalid) {
                    showResult(safeMessage(invalid));
                    return;
                }
                if (direct) {
                    managementEditorDialog = editor;
                    managementEditorBody = body;
                    saveReview(true);
                } else {
                    editor.dismiss();
                    render(selectedActiveContextId, selectedActiveGroupId);
                }
            });
        });
        if (direct) {
            managementEditorDialog = editor;
            managementEditorBody = body;
        }
        editor.show();
    }

    private static String draftInputText(View view) {
        StringBuilder text = new StringBuilder();
        if (view instanceof EditText) {
            CharSequence value = ((EditText) view).getText();
            text.append(value.length()).append(':').append(value);
        } else if (view instanceof android.widget.CheckBox) {
            text.append(((android.widget.CheckBox) view).isChecked());
        } else if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) {
                text.append(draftInputText(group.getChildAt(i))).append(';');
            }
        }
        return text.toString();
    }

    private static final class ManualEditorRow {
        final String language;
        final EditText descriptionInput;
        final EditText manualSummaryInput;

        ManualEditorRow(
            String language,
            EditText descriptionInput,
            EditText manualSummaryInput
        ) {
            this.language = language;
            this.descriptionInput = descriptionInput;
            this.manualSummaryInput = manualSummaryInput;
        }
    }

    private static List<String> editorLanguages(
        JSONObject draft,
        boolean includeDescriptions
    ) {
        Set<String> languages = new LinkedHashSet<>();
        if (includeDescriptions) {
            addEditorLanguageKeys(
                languages,
                draft.optJSONObject("manual_descriptions")
            );
        }
        addEditorLanguageKeys(languages, draft.optJSONObject("summary"));
        List<String> result = new ArrayList<>(languages);
        Collections.sort(result, String.CASE_INSENSITIVE_ORDER);
        return result;
    }

    private static void addEditorLanguageKeys(
        Set<String> target,
        JSONObject values
    ) {
        if (values == null) {
            return;
        }
        Iterator<String> keys = values.keys();
        while (keys.hasNext()) {
            String language = keys.next();
            if (language != null && !language.trim().isEmpty()) {
                target.add(language);
            }
        }
    }

    private static String findLanguageKey(
        Map<String, ManualEditorRow> rows,
        String language
    ) {
        String candidate = language == null ? "" : language.trim();
        for (String key : rows.keySet()) {
            if (key.equals(candidate)) {
                return key;
            }
        }
        for (String key : rows.keySet()) {
            if (key.trim().equalsIgnoreCase(candidate)) {
                return key;
            }
        }
        return null;
    }

    private static boolean containsLanguage(
        List<String> languages,
        String language
    ) {
        String candidate = language == null ? "" : language.trim();
        for (String existing : languages) {
            if (existing.equals(candidate)
                || existing.trim().equalsIgnoreCase(candidate)) {
                return true;
            }
        }
        return false;
    }

    private static String editorRecordText(
        JSONObject values,
        String language
    ) {
        JSONObject record = values == null
            ? null
            : values.optJSONObject(language);
        return record == null ? "" : record.optString("text", "");
    }

    private void addContextManualEditorRow(
        LinearLayout rows,
        JSONObject draft,
        String language,
        Map<String, ManualEditorRow> rowMap
    ) {
        MaterialCardView card = editorCard();
        LinearLayout row = (LinearLayout) card.getTag();
        row.setOrientation(LinearLayout.VERTICAL);

        TextView languageLabel = new TextView(this);
        languageLabel.setText(language);
        languageLabel.setTextSize(14);
        row.addView(languageLabel);

        JSONObject descriptions = draft.optJSONObject("manual_descriptions");
        EditText descriptionInput = new EditText(this);
        descriptionInput.setHint(R.string.scene_context_manual_description);
        descriptionInput.setMinLines(2);
        descriptionInput.setText(editorRecordText(descriptions, language));
        row.addView(descriptionInput);
        row.addView(textButton(
            R.string.scene_context_save_manual_description,
            view -> {
                String text = descriptionInput.getText().toString();
                if (text.trim().isEmpty()) {
                    descriptionInput.setError(getString(
                        R.string.scene_context_manual_requires_language_and_text
                    ));
                    return;
                }
                putManualDescription(draft, language, text);
                Toast.makeText(
                    this,
                    R.string.scene_context_manual_description_saved,
                    Toast.LENGTH_SHORT
                ).show();
            }
        ));

        JSONObject summary = draft.optJSONObject("summary");
        EditText manualSummaryInput = new EditText(this);
        manualSummaryInput.setHint(R.string.scene_context_manual_summary);
        manualSummaryInput.setMinLines(2);
        manualSummaryInput.setText(editorRecordText(
            summary == null ? null : summary.optJSONObject(language),
            "manual"
        ));
        row.addView(manualSummaryInput);
        LinearLayout summaryButtons = new LinearLayout(this);
        summaryButtons.setOrientation(LinearLayout.HORIZONTAL);
        summaryButtons.addView(textButton(
            R.string.scene_context_save_manual_summary,
            view -> {
                String text = manualSummaryInput.getText().toString();
                if (text.trim().isEmpty()) {
                    manualSummaryInput.setError(getString(
                        R.string.scene_context_manual_requires_language_and_text
                    ));
                    return;
                }
                putManualSummary(draft, language, text);
                Toast.makeText(
                    this,
                    R.string.scene_context_manual_summary_saved,
                    Toast.LENGTH_SHORT
                ).show();
            }
        ));
        MaterialButton deleteSummaryButton = textButton(
            R.string.scene_context_delete_manual_summary,
            view -> {
                removeManualSummary(draft, language);
                manualSummaryInput.setText("");
                Toast.makeText(
                    this,
                    R.string.scene_context_manual_summary_deleted,
                    Toast.LENGTH_SHORT
                ).show();
            }
        );
        applyDangerText(deleteSummaryButton);
        summaryButtons.addView(deleteSummaryButton);
        row.addView(summaryButtons);

        JSONObject languageSummary = summary == null
            ? null
            : summary.optJSONObject(language);
        addEditorSummaryRecord(
            row,
            R.string.context_detail_summary_final,
            languageSummary == null
                ? null
                : languageSummary.optJSONObject("final")
        );
        addEditorSummaryRecord(
            row,
            R.string.context_detail_summary_current,
            languageSummary == null
                ? null
                : languageSummary.optJSONObject("current")
        );
        rowMap.put(language, new ManualEditorRow(
            language,
            descriptionInput,
            manualSummaryInput
        ));
        rows.addView(card);
    }

    private void addGroupManualEditorRow(
        LinearLayout rows,
        JSONObject draft,
        String language,
        Map<String, ManualEditorRow> rowMap
    ) {
        MaterialCardView card = editorCard();
        LinearLayout row = (LinearLayout) card.getTag();
        row.setOrientation(LinearLayout.VERTICAL);

        TextView languageLabel = new TextView(this);
        languageLabel.setText(language);
        languageLabel.setTextSize(14);
        row.addView(languageLabel);

        JSONObject summary = draft.optJSONObject("summary");
        EditText manualSummaryInput = new EditText(this);
        manualSummaryInput.setHint(R.string.scene_context_manual_summary);
        manualSummaryInput.setMinLines(2);
        manualSummaryInput.setText(editorRecordText(
            summary == null ? null : summary.optJSONObject(language),
            "manual"
        ));
        row.addView(manualSummaryInput);
        LinearLayout summaryButtons = new LinearLayout(this);
        summaryButtons.setOrientation(LinearLayout.HORIZONTAL);
        summaryButtons.addView(textButton(
            R.string.scene_context_save_manual_summary,
            view -> {
                String text = manualSummaryInput.getText().toString();
                if (text.trim().isEmpty()) {
                    manualSummaryInput.setError(getString(
                        R.string.scene_context_manual_requires_language_and_text
                    ));
                    return;
                }
                putGroupManualSummary(draft, language, text);
                Toast.makeText(
                    this,
                    R.string.scene_context_manual_summary_saved,
                    Toast.LENGTH_SHORT
                ).show();
            }
        ));
        MaterialButton deleteSummaryButton = textButton(
            R.string.scene_context_delete_manual_summary,
            view -> {
                removeGroupManualSummary(draft, language);
                manualSummaryInput.setText("");
                Toast.makeText(
                    this,
                    R.string.scene_context_manual_summary_deleted,
                    Toast.LENGTH_SHORT
                ).show();
            }
        );
        applyDangerText(deleteSummaryButton);
        summaryButtons.addView(deleteSummaryButton);
        row.addView(summaryButtons);

        JSONObject languageSummary = summary == null
            ? null
            : summary.optJSONObject(language);
        addEditorSummaryRecord(
            row,
            R.string.context_detail_summary_final,
            languageSummary == null
                ? null
                : languageSummary.optJSONObject("final")
        );
        addEditorSummaryRecord(
            row,
            R.string.context_detail_summary_current,
            languageSummary == null
                ? null
                : languageSummary.optJSONObject("current")
        );
        rowMap.put(language, new ManualEditorRow(
            language,
            null,
            manualSummaryInput
        ));
        rows.addView(card);
    }

    private void addEditorSummaryRecord(
        LinearLayout row,
        int labelResource,
        JSONObject record
    ) {
        if (record == null) {
            return;
        }
        String text = record.optString("text", "");
        if (text.trim().isEmpty()) {
            return;
        }
        row.addView(fieldLabel(labelResource));
        TextView value = new TextView(this);
        value.setText(text);
        value.setTextSize(14);
        value.setPadding(0, 0, 0, dp(8));
        row.addView(value);
    }

    private void applyContextManualEditorRows(
        JSONObject draft,
        Map<String, ManualEditorRow> rows
    ) {
        if (rows == null) {
            return;
        }
        for (ManualEditorRow row : rows.values()) {
            String language = row.language.trim();
            String description = row.descriptionInput.getText().toString();
            JSONObject descriptions = draft.optJSONObject("manual_descriptions");
            validateManualEditorValue(
                language,
                row.descriptionInput,
                description,
                editorRecordText(descriptions, language)
            );
            String summaryText = row.manualSummaryInput.getText().toString();
            JSONObject summary = draft.optJSONObject("summary");
            JSONObject languageSummary = summary == null
                ? null
                : summary.optJSONObject(language);
            validateManualEditorValue(
                language,
                row.manualSummaryInput,
                summaryText,
                editorRecordText(languageSummary, "manual")
            );
        }
        for (ManualEditorRow row : rows.values()) {
            String language = row.language.trim();
            String description = row.descriptionInput.getText().toString();
            if (!description.trim().isEmpty()) {
                putManualDescription(draft, language, description);
            }
            String summaryText = row.manualSummaryInput.getText().toString();
            if (!summaryText.trim().isEmpty()) {
                putManualSummary(draft, language, summaryText);
            }
        }
    }

    private void applyGroupManualEditorRows(
        JSONObject draft,
        Map<String, ManualEditorRow> rows
    ) {
        if (rows == null) {
            return;
        }
        for (ManualEditorRow row : rows.values()) {
            String language = row.language.trim();
            String summaryText = row.manualSummaryInput.getText().toString();
            JSONObject summary = draft.optJSONObject("summary");
            JSONObject languageSummary = summary == null
                ? null
                : summary.optJSONObject(language);
            validateManualEditorValue(
                language,
                row.manualSummaryInput,
                summaryText,
                editorRecordText(languageSummary, "manual")
            );
        }
        for (ManualEditorRow row : rows.values()) {
            String language = row.language.trim();
            String summaryText = row.manualSummaryInput.getText().toString();
            if (!summaryText.trim().isEmpty()) {
                putGroupManualSummary(draft, language, summaryText);
            }
        }
    }

    private void validateManualEditorValue(
        String language,
        EditText input,
        String text,
        String existingText
    ) {
        if (language.isEmpty()
            || (text.trim().isEmpty() && !existingText.trim().isEmpty())) {
            input.setError(getString(
                R.string.scene_context_manual_requires_language_and_text
            ));
            throw new IllegalArgumentException(getString(
                R.string.scene_context_manual_requires_language_and_text
            ));
        }
    }

    private void renderSceneEntries(
        LinearLayout container,
        JSONObject draft,
        Set<String> availableScenes,
        Runnable refresh
    ) {
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
            String scene = entry.optString("scene", "");
            String displayedScene = scene.trim().isEmpty() ? "?" : scene;
            label.setText(availableScenes.contains(scene)
                ? scene
                : getString(
                    R.string.scene_context_unavailable_scene_member,
                    displayedScene
                ));
            LinearLayout.LayoutParams labelParams = new LinearLayout.LayoutParams(
                0,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                1f
            );
            label.setLayoutParams(labelParams);
            row.addView(label);
            row.addView(textButton(R.string.scene_context_move_up, view -> {
                moveScene(draft, position, position - 1);
                refresh.run();
            }));
            row.addView(textButton(R.string.scene_context_move_down, view -> {
                moveScene(draft, position, position + 1);
                refresh.run();
            }));
            row.addView(textButton(R.string.scene_context_remove, view -> {
                removeArrayIndex(draft, "scenes", position);
                refresh.run();
            }));
            container.addView(row);
        }
    }

    private void renderGroupContextEntries(
        LinearLayout container,
        JSONObject draft,
        List<String> contextIds,
        List<String> contextLabels,
        Set<String> availableContextIds,
        Runnable refresh
    ) {
        container.removeAllViews();
        JSONArray contexts = draft.optJSONArray("contexts");
        if (contexts == null) {
            return;
        }
        for (int index = 0; index < contexts.length(); index++) {
            final String contextId;
            try {
                contextId = GroupContextEntry.contextIdAt(
                    contexts,
                    index
                );
            } catch (RuntimeException invalidEntry) {
                contextId = "";
            }
            final int position = index;
            String label = contextId == null || contextId.trim().isEmpty()
                ? "?"
                : contextId;
            int labelIndex = contextIds.indexOf(contextId);
            if (labelIndex >= 0 && labelIndex < contextLabels.size()) {
                label = contextLabels.get(labelIndex);
            }
            if (!availableContextIds.contains(contextId)) {
                label = getString(
                    R.string.scene_context_unavailable_context_member,
                    contextId == null || contextId.trim().isEmpty()
                        ? "?"
                        : contextId
                );
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
                refresh.run();
            }));
            row.addView(textButton(R.string.scene_context_move_down, view -> {
                moveArrayObject(draft, "contexts", position, position + 1);
                refresh.run();
            }));
            row.addView(textButton(R.string.scene_context_remove, view -> {
                removeArrayIndex(draft, "contexts", position);
                refresh.run();
            }));
            container.addView(row);
        }
    }

    private static List<String> unavailableSceneMembers(
        JSONObject draft,
        Set<String> availableScenes
    ) {
        Set<String> unavailable = new LinkedHashSet<>();
        JSONArray scenes = draft.optJSONArray("scenes");
        for (int index = 0; scenes != null && index < scenes.length(); index++) {
            JSONObject entry = scenes.optJSONObject(index);
            String scene = entry == null
                ? ""
                : entry.optString("scene", "");
            if (scene.trim().isEmpty() || !availableScenes.contains(scene)) {
                unavailable.add(scene.trim().isEmpty() ? "?" : scene);
            }
        }
        return new ArrayList<>(unavailable);
    }

    private static List<String> unavailableContextMembers(
        JSONObject draft,
        Set<String> availableContextIds
    ) {
        Set<String> unavailable = new LinkedHashSet<>();
        JSONArray contextEntries = draft.optJSONArray("contexts");
        for (
            int index = 0;
            contextEntries != null && index < contextEntries.length();
            index++
        ) {
            String contextId;
            try {
                contextId = GroupContextEntry.contextIdAt(
                    contextEntries,
                    index
                );
            } catch (RuntimeException invalidEntry) {
                contextId = "";
            }
            if (contextId == null
                || contextId.trim().isEmpty()
                || !availableContextIds.contains(contextId)) {
                unavailable.add(
                    contextId == null || contextId.trim().isEmpty()
                        ? "?"
                        : contextId
                );
            }
        }
        return new ArrayList<>(unavailable);
    }

    private void validateAvailableSceneMembers(
        JSONObject draft,
        Set<String> availableScenes
    ) {
        List<String> unavailable = unavailableSceneMembers(
            draft,
            availableScenes
        );
        if (!unavailable.isEmpty()) {
            throw new IllegalArgumentException(getString(
                R.string.scene_context_remove_unavailable_scenes,
                TextUtils.join(", ", unavailable)
            ));
        }
    }

    private void validateAvailableContextMembers(
        JSONObject draft,
        Set<String> availableContextIds
    ) {
        List<String> unavailable = unavailableContextMembers(
            draft,
            availableContextIds
        );
        if (!unavailable.isEmpty()) {
            throw new IllegalArgumentException(getString(
                R.string.scene_context_remove_unavailable_contexts,
                describeGroupContextIds(unavailable)
            ));
        }
    }

    private static final class ContextClosureControls {
        final TextView status;
        final MaterialButton action;
        final MaterialButton reopen;

        ContextClosureControls(
            TextView status,
            MaterialButton action,
            MaterialButton reopen
        ) {
            this.status = status;
            this.action = action;
            this.reopen = reopen;
        }
    }

    private static final class ContextClosureSnapshot {
        final SceneContextStore.ManualClosureState state;
        final String jobStatus;
        final boolean active;

        ContextClosureSnapshot(
            SceneContextStore.ManualClosureState state,
            String jobStatus,
            boolean active
        ) {
            this.state = state;
            this.jobStatus = jobStatus;
            this.active = active;
        }
    }

    private static final class GroupClosureControls {
        final TextView status;
        final MaterialButton action;
        final MaterialButton reopen;

        GroupClosureControls(
            TextView status,
            MaterialButton action,
            MaterialButton reopen
        ) {
            this.status = status;
            this.action = action;
            this.reopen = reopen;
        }
    }

    private static final class GroupClosureSnapshot {
        final SceneContextStore.ManualClosureState state;
        final String jobStatus;
        final boolean active;

        GroupClosureSnapshot(
            SceneContextStore.ManualClosureState state,
            String jobStatus,
            boolean active
        ) {
            this.state = state;
            this.jobStatus = jobStatus;
            this.active = active;
        }
    }

    private MaterialButton relationHeader(
        int titleRes,
        int count,
        boolean expanded
    ) {
        MaterialButton header = new MaterialButton(this);
        header.setAllCaps(false);
        header.setGravity(Gravity.START | Gravity.CENTER_VERTICAL);
        updateRelationHeader(header, titleRes, count, expanded);
        return header;
    }

    private void updateRelationHeader(
        MaterialButton header,
        int titleRes,
        int count,
        boolean expanded
    ) {
        String title = getString(titleRes);
        header.setText(getString(
            R.string.scene_context_members_count,
            title,
            count
        ));
        header.setContentDescription(getString(
            expanded
                ? R.string.scene_context_collapse_members
                : R.string.scene_context_expand_members,
            title
        ));
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
                        !risk.userRequestedUnsentIds.isEmpty(),
                        annotationScene,
                        expectedAnnotation,
                        annotationDraft,
                        sceneStore
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
                    if (managementEditorDialog != null) {
                        managementEditorDialog.dismiss();
                        clearManagementEditorTracking();
                        setResult(RESULT_OK);
                        finish();
                    } else if (save && reviewMode) {
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
                        setBusy(false);
                        showResult(getString(
                            R.string.scene_context_concurrent_changed
                        ));
                        if (managementEditorDialog == null) refreshAsync();
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

    private Set<String> currentAvailableContextIds() {
        try {
            Set<String> available = new LinkedHashSet<>(
                sceneContextStore.listContextIds()
            );
            for (JSONObject context : contexts) {
                String contextId = context.optString("id", "").trim();
                if (contextId.startsWith("new-")
                    && !context.optString("display_name", "")
                        .trim()
                        .isEmpty()) {
                    available.add(contextId);
                }
            }
            return available;
        } catch (Exception error) {
            throw new IllegalArgumentException(getString(
                R.string.scene_context_load_failed,
                safeMessage(error)
            ));
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
            if (index == target && from > target) {
                putJsonArray(reordered, moved);
            }
            putJsonArray(reordered, array.opt(index));
            if (index == target && from < target) {
                putJsonArray(reordered, moved);
            }
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

    private static void putManualDescription(
        JSONObject context,
        String language,
        String text
    ) {
        JSONObject descriptions = context.optJSONObject("manual_descriptions");
        if (descriptions == null) {
            descriptions = new JSONObject();
            putJson(context, "manual_descriptions", descriptions);
        }
        JSONObject previous = descriptions.optJSONObject(language);
        if (previous != null
            && text.equals(previous.optString("text", ""))) {
            return;
        }
        putJson(
            descriptions,
            language,
            manualRecord(text, System.currentTimeMillis())
        );
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
        JSONObject previous = language.optJSONObject("manual");
        if (previous != null
            && text.equals(previous.optString("text", ""))) {
            return;
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
        JSONObject previous = language.optJSONObject("manual");
        if (previous != null
            && text.equals(previous.optString("text", ""))) {
            return;
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
        label.setPadding(0, dp(12), 0, dp(4));
        return label;
    }

    private MaterialCardView editorCard() {
        MaterialCardView card = new MaterialCardView(
            new ContextThemeWrapper(this, R.style.Widget_HET_SectionCard)
        );
        card.setCardElevation(0);
        card.setRadius(dp(14));
        card.setCardBackgroundColor(ContextCompat.getColor(
            this,
            R.color.het_surface_container
        ));
        card.setStrokeColor(ContextCompat.getColor(
            this,
            R.color.het_outline_soft
        ));
        card.setStrokeWidth(dp(1));
        LinearLayout column = new LinearLayout(this);
        column.setOrientation(LinearLayout.VERTICAL);
        column.setPadding(dp(12), dp(10), dp(12), dp(10));
        card.addView(column);
        card.setTag(column);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        );
        params.bottomMargin = dp(8);
        card.setLayoutParams(params);
        return card;
    }

    private void applyDangerText(MaterialButton button) {
        button.setTextColor(dangerColor());
    }

    private int dangerColor() {
        return ContextCompat.getColor(this, R.color.het_error);
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
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
        if (managementEditorDialog != null) {
            if (busy && editorEnabledStates.isEmpty()) {
                disableDraftViews(managementEditorBody);
            } else if (!busy) {
                for (Map.Entry<View, Boolean> entry : editorEnabledStates.entrySet()) {
                    entry.getKey().setEnabled(entry.getValue());
                }
                editorEnabledStates.clear();
            }
            managementEditorDialog.getButton(AlertDialog.BUTTON_POSITIVE).setEnabled(!busy);
            managementEditorDialog.getButton(AlertDialog.BUTTON_NEGATIVE).setEnabled(!busy);
        }
        findViewById(R.id.btn_add_context).setEnabled(!busy && !batchMode);
        findViewById(R.id.btn_add_group).setEnabled(!busy && !batchMode);
        findViewById(R.id.btn_edit_all_scenes).setEnabled(!busy && !batchMode);
        setManagementBatchActionEnabled(!busy && !batchMode);
        findViewById(R.id.btn_import_context_group).setEnabled(!busy && !batchMode);
        findViewById(R.id.btn_export_context_group).setEnabled(!busy && !batchMode);
        activeContextSpinner.setEnabled(!busy && !batchMode);
        activeGroupSpinner.setEnabled(!busy && !batchMode);
    }

    private void disableDraftViews(View view) {
        if (view == null) return;
        editorEnabledStates.put(view, view.isEnabled());
        view.setEnabled(false);
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) {
                disableDraftViews(group.getChildAt(i));
            }
        }
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
