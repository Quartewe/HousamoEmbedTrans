package com.quarty.housamoembedtrans.ui;

import com.quarty.housamoembedtrans.R;
import com.quarty.housamoembedtrans.storage.config.ConfigStore;

import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.net.Uri;
import android.text.Editable;
import android.text.InputType;
import android.text.TextUtils;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.OnBackPressedCallback;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;

import com.google.android.material.appbar.MaterialToolbar;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.checkbox.MaterialCheckBox;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.textfield.TextInputEditText;
import com.google.android.material.textfield.TextInputLayout;

import org.json.JSONArray;
import org.json.JSONObject;

import java.text.Collator;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Searchable editor for the user-owned files/gameterms.json override. */
public final class GameTermsActivity extends AppCompatActivity {

    public static final String EXTRA_CREATE_TERM = "create_term";

    public static final String EXTRA_TERM_NAME =
        "term_name";
    private static final String STATE_MANAGEMENT_LINK_CONSUMED =
        "management_link_consumed";
    private static final String STATE_MANAGEMENT_LINK_FAILURE_NOTIFIED =
        "management_link_failure_notified";
    private static final String STATE_EDITOR_DRAFT =
        "dictionary_editor.term.draft";
    private static final String STATE_EDITOR_KEY =
        "dictionary_editor.term.key";
    private static final String STATE_EDITOR_DIRTY =
        "dictionary_editor.term.dirty";

    private static final String[] TERM_FIELD_ORDER = {
        "zh-cn",
        "zh-tw",
        "en",
        "description"
    };

    private ConfigStore configStore;
    private PendingProcessMoveController pendingProcessMoveController;
    private ManagementBatchController managementBatchController;
    private JSONObject dictionary;
    private boolean dirty;
    private boolean userOverride;
    private boolean invalidUserOverride;
    private boolean importBusy;
    private boolean managementLinkConsumed;
    private boolean managementLinkFailureNotified;
    private ActivityResultLauncher<String[]> importLauncher;
    private final ExecutorService ioExecutor = Executors.newSingleThreadExecutor();

    private EditText searchInput;
    private TextView statusView;
    private ListView termList;
    private Button saveButton;
    private Button importButton;
    private MenuItem managementBatchMenuItem;

    private final List<String> allTerms = new ArrayList<>();
    private final List<String> visibleTerms = new ArrayList<>();
    private GameTermAdapter adapter;
    private boolean batchMode;

    /** The management entry opens this Activity as a full-page draft editor. */
    private boolean editorMode;
    private String editorOriginalKey;
    private boolean editorDirty;
    private boolean editorBusy;
    private boolean stylePreview;
    private JSONObject editorSourceRecord;
    private EditText editorKeyInput;
    private TextInputLayout editorKeyLayout;
    private EditText editorNameDisplay;
    private TextView editorStatusView;
    private MaterialButton editorSaveButton;
    private MaterialButton editorDiscardButton;
    private MaterialButton editorMoveButton;
    private TextView editorMoveHint;
    private List<TermFieldEditor> editorFieldEditors = Collections.emptyList();
    private boolean refreshListAfterEditor;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        stylePreview = StylePreview.isEnabled(getIntent());
        editorMode = hasEditorIntent(getIntent());
        if (editorMode) {
            setupEditorPage(savedInstanceState);
            return;
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
        setContentView(R.layout.activity_game_terms);
        SystemBarInsets.apply(findViewById(R.id.root_game_terms));

        MaterialToolbar toolbar = findViewById(R.id.toolbar_game_terms);
        toolbar.setNavigationOnClickListener(
            view -> getOnBackPressedDispatcher().onBackPressed()
        );
        toolbar.inflateMenu(R.menu.menu_management_batch);
        managementBatchMenuItem = toolbar.getMenu().findItem(
            R.id.action_management_batch
        );
        managementBatchMenuItem.setOnMenuItemClickListener(item -> {
            toggleManagementBatch();
            return true;
        });

        configStore = new ConfigStore(this);
        pendingProcessMoveController = new PendingProcessMoveController(this);
        searchInput = findViewById(R.id.et_gameterms_search);
        statusView = findViewById(R.id.tv_gameterms_status);
        termList = findViewById(R.id.list_game_terms);
        saveButton = findViewById(R.id.btn_save_gameterms);
        importButton = findViewById(R.id.btn_import_gameterms);
        importLauncher = registerForActivityResult(
            new ActivityResultContracts.OpenMultipleDocuments(),
            this::importTermDocuments
        );

        adapter = new GameTermAdapter(this, visibleTerms);
        termList.setAdapter(adapter);
        termList.setEmptyView(findViewById(R.id.tv_game_terms_empty));
        termList.setOnItemClickListener((parent, view, position, id) -> {
            String term = visibleTerms.get(position);
            if (batchMode) {
                String key = ManagementBatchController.KIND_TERM + ":" + term;
                ManagementBatchSelection.set(
                    key,
                    !ManagementBatchSelection.contains(key)
                );
                adapter.notifyDataSetChanged();
                if (managementBatchController != null) {
                    managementBatchController.onHostRowsChanged();
                }
            } else {
                editTerm(term);
            }
        });

        searchInput.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(
                CharSequence value,
                int start,
                int count,
                int after
            ) {
            }

            @Override
            public void onTextChanged(
                CharSequence value,
                int start,
                int before,
                int count
            ) {
                applyFilter(value == null ? "" : value.toString());
            }

            @Override
            public void afterTextChanged(Editable value) {
            }
        });

        findViewById(R.id.btn_add_game_term).setOnClickListener(
            view -> editTerm(null)
        );

        findViewById(R.id.btn_restore_gameterms).setOnClickListener(
            view -> confirmRestore()
        );
        importButton.setOnClickListener(view -> {
            if (dirty || importBusy) {
                Toast.makeText(
                    this,
                    R.string.management_transfer_import_save_first,
                    Toast.LENGTH_LONG
                ).show();
                return;
            }
            setImportBusy(true);
            try {
                importLauncher.launch(new String[] {
                    "application/json",
                    "text/json",
                    "text/plain",
                    "application/octet-stream"
                });
            } catch (RuntimeException error) {
                setImportBusy(false);
                showImportFailure(error);
            }
        });
        saveButton.setOnClickListener(view -> saveDictionary());

        getOnBackPressedDispatcher().addCallback(this, new OnBackPressedCallback(true) {
            @Override
            public void handleOnBackPressed() {
                GameTermsActivity.this.handleBackPressed(this);
            }
        });

        loadDictionary();
        managementBatchController = ManagementBatchController.attach(
            this,
            findViewById(R.id.root_game_terms),
            new TermBatchDataSource(),
            savedInstanceState
        );
    }

    private boolean hasEditorIntent(Intent intent) {
        return intent != null
            && (intent.hasExtra(EXTRA_TERM_NAME)
                || intent.getBooleanExtra(EXTRA_CREATE_TERM, false));
    }

    private void setupEditorPage(Bundle savedInstanceState) {
        setContentView(R.layout.activity_game_term_editor);
        SystemBarInsets.apply(findViewById(R.id.root_game_term_editor));

        MaterialToolbar toolbar = findViewById(R.id.toolbar_game_term_editor);
        toolbar.setNavigationOnClickListener(
            view -> getOnBackPressedDispatcher().onBackPressed()
        );
        editorKeyInput = findViewById(R.id.et_game_term_editor_key);
        editorKeyLayout = findViewById(R.id.til_game_term_editor_key);
        editorNameDisplay = findViewById(R.id.et_game_term_editor_name);
        editorStatusView = findViewById(R.id.tv_game_term_editor_status);
        editorSaveButton = findViewById(R.id.btn_save_game_term_editor);
        editorDiscardButton = findViewById(R.id.btn_discard_game_term_editor);
        editorMoveButton = findViewById(R.id.btn_move_game_term_pending_editor);
        editorMoveHint = findViewById(R.id.tv_move_game_term_pending_editor_hint);

        Intent intent = getIntent();
        editorOriginalKey = intent == null
            ? null
            : intent.getStringExtra(EXTRA_TERM_NAME);
        if (editorOriginalKey != null && editorOriginalKey.trim().isEmpty()) {
            editorOriginalKey = null;
        }
        toolbar.setTitle(
            editorOriginalKey == null
                ? R.string.add_game_term
                : R.string.edit_game_term
        );
        TextView editorHeading = findViewById(R.id.tv_game_term_editor_heading);
        editorHeading.setText(toolbar.getTitle());

        if (!stylePreview) {
            configStore = new ConfigStore(this);
            pendingProcessMoveController = new PendingProcessMoveController(this);
        }
        editorSaveButton.setOnClickListener(view -> saveEditorPage());
        editorDiscardButton.setOnClickListener(view -> handleEditorBackPressed());
        editorMoveButton.setOnClickListener(view -> moveEditorRecordToPending());
        getOnBackPressedDispatcher().addCallback(this, new OnBackPressedCallback(true) {
            @Override
            public void handleOnBackPressed() {
                handleEditorBackPressed();
            }
        });
        setReadOnly(editorNameDisplay);
        loadEditorPage(savedInstanceState);
    }

    private void loadEditorPage(Bundle savedInstanceState) {
        try {
            JSONObject source;
            if (stylePreview) {
                source = StylePreview.payloadOf(getIntent());
                if (source == null) {
                    source = StylePreview.sample(StylePreview.KIND_TERM_EDITOR);
                }
                dictionary = new JSONObject();
                dictionary.put(
                    editorOriginalKey == null
                        ? StylePreview.SAMPLE_TERM_NAME
                        : editorOriginalKey,
                    new JSONObject(source.toString())
                );
                userOverride = false;
                invalidUserOverride = false;
            } else {
                ConfigStore.JsonLoadResult loaded = configStore.loadJson(
                    ConfigStore.GAMETERMS_FILE_NAME
                );
                dictionary = loaded.json;
                userOverride = loaded.userOverride;
                invalidUserOverride = loaded.invalidUserOverride;
                if (editorOriginalKey == null) {
                    source = newTermRecord();
                } else {
                    source = dictionary.optJSONObject(editorOriginalKey);
                    if (source == null) {
                        throw new IllegalStateException(
                            getString(R.string.dictionary_editor_term_missing)
                        );
                    }
                }
            }
            editorSourceRecord = new JSONObject(source.toString());
            String draftJson = savedInstanceState == null
                ? null
                : savedInstanceState.getString(STATE_EDITOR_DRAFT);
            if (draftJson != null && !draftJson.trim().isEmpty()) {
                source = new JSONObject(draftJson);
            }
            String restoredKey = savedInstanceState == null
                ? (editorOriginalKey == null ? "" : editorOriginalKey)
                : savedInstanceState.getString(
                    STATE_EDITOR_KEY,
                    editorOriginalKey == null ? "" : editorOriginalKey
                );
            editorKeyInput.setText(restoredKey);
            editorNameDisplay.setText(restoredKey);
            if (editorOriginalKey != null) {
                setReadOnly(editorKeyInput);
                editorKeyLayout.setHelperText(
                    getString(R.string.game_term_key_read_only)
                );
            } else {
                editorKeyLayout.setHelperText(
                    getString(R.string.game_term_key_new_hint)
                );
            }
            LinearLayout fields = findViewById(
                R.id.container_game_term_editor_fields
            );
            editorFieldEditors = createFieldEditors(
                new JSONObject(source.toString()),
                fields
            );
            attachDraftChangeWatchers(editorFieldEditors, () -> {
                editorDirty = true;
                updateEditorActions();
            });
            editorKeyInput.addTextChangedListener(new TextWatcher() {
                @Override
                public void beforeTextChanged(CharSequence s, int start, int count, int after) {
                }

                @Override
                public void onTextChanged(CharSequence s, int start, int before, int count) {
                    editorNameDisplay.setText(s == null ? "" : s.toString());
                    editorDirty = true;
                    updateEditorActions();
                }

                @Override
                public void afterTextChanged(Editable s) {
                }
            });
            editorDirty = savedInstanceState != null
                && savedInstanceState.getBoolean(STATE_EDITOR_DIRTY, false);
            editorBusy = false;
            editorStatusView.setVisibility(View.GONE);
            boolean canMove = !stylePreview && editorOriginalKey != null;
            editorMoveButton.setVisibility(canMove ? View.VISIBLE : View.GONE);
            editorMoveHint.setVisibility(canMove ? View.VISIBLE : View.GONE);
            updateEditorActions();
        } catch (Exception error) {
            editorStatusView.setVisibility(View.VISIBLE);
            editorStatusView.setText(getString(
                R.string.gameterms_load_failed,
                safeMessage(error)
            ));
            editorSaveButton.setEnabled(false);
            editorMoveButton.setEnabled(false);
        }
    }

    private void updateEditorActions() {
        if (!editorMode || editorSaveButton == null) {
            return;
        }
        boolean enabled = !stylePreview && !editorBusy && dictionary != null;
        editorKeyInput.setEnabled(enabled && editorOriginalKey == null);
        editorSaveButton.setEnabled(enabled);
        editorDiscardButton.setEnabled(!editorBusy);
        if (editorMoveButton != null) {
            editorMoveButton.setEnabled(
                enabled && editorOriginalKey != null && !editorDirty
            );
        }
        if (editorMoveHint != null) {
            editorMoveHint.setText(
                editorDirty
                    ? R.string.dictionary_editor_move_pending_dirty_hint
                    : R.string.dictionary_editor_move_pending_hint
            );
        }
    }

    private void saveEditorPage() {
        if (stylePreview || !editorMode || editorBusy || dictionary == null) {
            return;
        }
        String key = editorOriginalKey == null
            ? textOf(editorKeyInput)
            : editorOriginalKey;
        if (key.isEmpty()) {
            editorKeyInput.setError(getString(R.string.error_required));
            editorKeyInput.requestFocus();
            return;
        }
        if (editorOriginalKey == null && dictionary.has(key)) {
            editorKeyInput.setError(getString(R.string.game_term_already_exists));
            editorKeyInput.requestFocus();
            return;
        }

        JSONObject updatedRecord;
        try {
            updatedRecord = editorSourceRecord == null
                ? new JSONObject()
                : new JSONObject(editorSourceRecord.toString());
        } catch (Exception error) {
            updatedRecord = new JSONObject();
        }
        for (TermFieldEditor fieldEditor : editorFieldEditors) {
            fieldEditor.layout.setError(null);
            if (fieldEditor.originalValue != null
                && formatValue(fieldEditor.originalValue).equals(
                    rawTextOf(fieldEditor.input)
                )) {
                try {
                    updatedRecord.put(fieldEditor.key, fieldEditor.originalValue);
                } catch (Exception error) {
                    showRecordValidationError(editorFieldEditors, error);
                    return;
                }
                continue;
            }
            try {
                updatedRecord.put(
                    fieldEditor.key,
                    parseValue(fieldEditor.originalValue, fieldEditor.input)
                );
            } catch (Exception error) {
                fieldEditor.layout.setError(getString(
                    R.string.gameterms_record_invalid,
                    safeMessage(error)
                ));
                fieldEditor.input.requestFocus();
                return;
            }
        }

        try {
            ConfigStore.validateGameTermRecord(key, updatedRecord);
            JSONObject updatedDictionary = new JSONObject(dictionary.toString());
            updatedDictionary.put(key, updatedRecord);
            ConfigStore.validateGameTermDictionary(updatedDictionary);
            configStore.saveJson(
                ConfigStore.GAMETERMS_FILE_NAME,
                updatedDictionary
            );
            dictionary = updatedDictionary;
            dirty = false;
            editorDirty = false;
            userOverride = true;
            invalidUserOverride = false;
            setResult(RESULT_OK);
            Toast.makeText(
                this,
                R.string.dictionary_editor_save_success,
                Toast.LENGTH_SHORT
            ).show();
            finish();
        } catch (Exception error) {
            showRecordValidationError(editorFieldEditors, error);
        }
    }

    private void moveEditorRecordToPending() {
        if (stylePreview || !editorMode || editorBusy || dictionary == null
            || editorOriginalKey == null || editorDirty
            || pendingProcessMoveController == null) {
            return;
        }
        editorBusy = true;
        updateEditorActions();
        pendingProcessMoveController.confirmMove(
            "term",
            editorOriginalKey,
            editorOriginalKey,
            () -> {
                Intent result = new Intent();
                result.putExtra(
                    DictionaryManagementDetailActivity.EXTRA_EDITOR_MOVED_PENDING,
                    true
                );
                setResult(RESULT_OK, result);
                finish();
            },
            () -> {
                editorBusy = false;
                updateEditorActions();
            }
        );
    }

    private void handleEditorBackPressed() {
        if (!editorMode) {
            return;
        }
        if (!editorDirty) {
            finish();
            return;
        }
        new UiMaterialAlertDialogBuilder(this)
            .setTitle(R.string.unsaved_gameterms_title)
            .setMessage(R.string.unsaved_gameterms_message)
            .setNegativeButton(R.string.keep_editing, null)
            .setPositiveButton(R.string.discard_changes, (dialog, which) -> finish())
            .show();
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (!editorMode && refreshListAfterEditor && !isFinishing()) {
            refreshListAfterEditor = false;
            loadDictionary();
        }
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
        if (editorMode) {
            outState.putBoolean(STATE_EDITOR_DIRTY, editorDirty);
            outState.putString(STATE_EDITOR_KEY, rawTextOf(editorKeyInput));
            try {
                outState.putString(STATE_EDITOR_DRAFT, editorDraftJson().toString());
            } catch (Exception ignored) {
                // The visible inputs remain available to the normal view state.
            }
        }
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

    private JSONObject editorDraftJson() throws Exception {
        JSONObject draft = new JSONObject();
        for (TermFieldEditor fieldEditor : editorFieldEditors) {
            draft.put(fieldEditor.key, rawTextOf(fieldEditor.input));
        }
        return draft;
    }

    private void loadDictionary() {
        try {
            ConfigStore.JsonLoadResult result = configStore.loadJson(
                ConfigStore.GAMETERMS_FILE_NAME
            );
            dictionary = result.json;
            userOverride = result.userOverride;
            invalidUserOverride = result.invalidUserOverride;
            dirty = false;
            rebuildTerms();
            updateStatus();
            setEditorEnabled(true);
            consumeManagementLinkAfterLoad();
        } catch (Exception e) {
            dictionary = null;
            statusView.setText(getString(
                R.string.gameterms_load_failed,
                safeMessage(e)
            ));
            setEditorEnabled(false);
            notifyManagementLinkLoadFailure();
        }
    }

    private void consumeManagementLinkAfterLoad() {
        if (managementLinkConsumed) {
            return;
        }

        Intent intent = getIntent();
        if (intent != null && intent.getBooleanExtra(EXTRA_CREATE_TERM, false)) {
            managementLinkConsumed = true;
            editTerm(null);
            return;
        }
        if (intent == null || !intent.hasExtra(EXTRA_TERM_NAME)) {
            return;
        }

        String term = intent.getStringExtra(EXTRA_TERM_NAME);
        managementLinkConsumed = true;
        if (term == null || term.isEmpty() || !dictionary.has(term)) {
            notifyManagementLinkTargetMissing();
            return;
        }
        editTerm(term);
    }

    private boolean hasManagementLink() {
        Intent intent = getIntent();
        return intent != null && (intent.hasExtra(EXTRA_TERM_NAME)
            || intent.getBooleanExtra(EXTRA_CREATE_TERM, false));
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

    private void rebuildTerms() {
        allTerms.clear();
        Iterator<String> terms = dictionary.keys();
        while (terms.hasNext()) {
            allTerms.add(terms.next());
        }

        Collator collator = Collator.getInstance(Locale.JAPANESE);
        Collections.sort(allTerms, collator::compare);
        applyFilter(textOf(searchInput));
    }

    private void applyFilter(String query) {
        visibleTerms.clear();
        if (dictionary == null) {
            adapter.notifyDataSetChanged();
            return;
        }

        String needle = query.trim().toLowerCase(Locale.ROOT);
        for (String term : allTerms) {
            if (needle.isEmpty() || recordMatches(term, needle)) {
                visibleTerms.add(term);
            }
        }
        adapter.notifyDataSetChanged();
        if (batchMode && managementBatchController != null) {
            managementBatchController.onHostRowsChanged();
        }
    }

    private boolean recordMatches(String term, String needle) {
        if (term.toLowerCase(Locale.ROOT).contains(needle)) {
            return true;
        }

        JSONObject record = dictionary.optJSONObject(term);
        if (record == null) {
            return false;
        }

        for (String field : TERM_FIELD_ORDER) {
            if (record.optString(field, "").toLowerCase(Locale.ROOT).contains(needle)) {
                return true;
            }
        }
        return false;
    }

    private void editTerm(String originalKey) {
        if (dictionary == null || editorMode) {
            return;
        }
        refreshListAfterEditor = true;
        Intent intent = new Intent(this, GameTermsActivity.class);
        if (originalKey == null) {
            intent.putExtra(EXTRA_CREATE_TERM, true);
        } else {
            intent.putExtra(EXTRA_TERM_NAME, originalKey);
        }
        startActivity(intent);
    }

    private void attachDraftChangeWatchers(
        List<TermFieldEditor> editors,
        Runnable onChanged
    ) {
        if (editors == null || onChanged == null) {
            return;
        }
        for (TermFieldEditor editor : editors) {
            editor.input.addTextChangedListener(new TextWatcher() {
                @Override
                public void beforeTextChanged(CharSequence s, int start, int count, int after) {
                }

                @Override
                public void onTextChanged(CharSequence s, int start, int before, int count) {
                    onChanged.run();
                }

                @Override
                public void afterTextChanged(Editable s) {
                }
            });
        }
    }

    private List<TermFieldEditor> createFieldEditors(
        JSONObject record,
        LinearLayout container
    ) throws Exception {
        List<TermFieldEditor> editors = new ArrayList<>();
        for (String key : orderedFieldKeys(record)) {
            Object value = record.has(key) ? record.get(key) : "";
            if (value instanceof JSONArray || value instanceof JSONObject) {
                continue;
            }

            View fieldView = getLayoutInflater().inflate(
                R.layout.item_character_field_editor,
                container,
                false
            );
            TextInputLayout layout = fieldView.findViewById(
                R.id.til_character_field
            );
            TextInputEditText input = fieldView.findViewById(
                R.id.et_character_field_value
            );
            TextView label = fieldView.findViewById(
                R.id.tv_character_field_label
            );

            label.setText(displayFieldName(key));
            layout.setHintEnabled(false);
            layout.setHint(null);
            configureInput(key, input);
            input.setText(formatValue(value));
            container.addView(fieldView);
            editors.add(new TermFieldEditor(key, value, layout, input));
        }
        return editors;
    }

    private List<String> orderedFieldKeys(JSONObject record) {
        Set<String> keys = new LinkedHashSet<>();
        Collections.addAll(keys, TERM_FIELD_ORDER);

        Iterator<String> remaining = record.keys();
        while (remaining.hasNext()) {
            keys.add(remaining.next());
        }
        return new ArrayList<>(keys);
    }

    private void configureInput(String key, TextInputEditText input) {
        if ("description".equals(key)) {
            input.setSingleLine(false);
            input.setInputType(
                InputType.TYPE_CLASS_TEXT
                    | InputType.TYPE_TEXT_FLAG_MULTI_LINE
                    | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            );
            input.setGravity(Gravity.TOP | Gravity.START);
            input.setMinLines(3);
            input.setMaxLines(8);
            input.setHorizontallyScrolling(false);
            input.setVerticalScrollBarEnabled(true);
        } else {
            input.setSingleLine(true);
            input.setInputType(InputType.TYPE_CLASS_TEXT);
        }
    }

    private void saveTermFromDialog(
        AlertDialog dialog,
        String originalKey,
        JSONObject originalRecord,
        EditText keyInput,
        List<TermFieldEditor> fieldEditors
    ) {
        String key = originalKey == null ? textOf(keyInput) : originalKey;
        if (key.isEmpty()) {
            keyInput.setError(getString(R.string.error_required));
            keyInput.requestFocus();
            return;
        }

        if (originalKey == null && dictionary.has(key)) {
            keyInput.setError(getString(R.string.game_term_already_exists));
            keyInput.requestFocus();
            return;
        }

        try {
            JSONObject record = new JSONObject(originalRecord.toString());
            for (TermFieldEditor fieldEditor : fieldEditors) {
                fieldEditor.layout.setError(null);
                if (formatValue(fieldEditor.originalValue).equals(rawTextOf(fieldEditor.input))) {
                    continue;
                }
                try {
                    record.put(
                        fieldEditor.key,
                        parseValue(fieldEditor.originalValue, fieldEditor.input)
                    );
                } catch (Exception e) {
                    fieldEditor.layout.setError(getString(
                        R.string.gameterms_record_invalid,
                        safeMessage(e)
                    ));
                    fieldEditor.input.requestFocus();
                    return;
                }
            }

            ConfigStore.validateGameTermRecord(key, record);
            if (hasManagementLink()) {
                JSONObject saved = new JSONObject(dictionary.toString());
                saved.put(key, record);
                ConfigStore.validateGameTermDictionary(saved);
                configStore.saveJson(ConfigStore.GAMETERMS_FILE_NAME, saved);
                dictionary = saved;
                dirty = false;
                userOverride = true;
                invalidUserOverride = false;
                setResult(RESULT_OK);
                dialog.dismiss();
                finish();
                return;
            }
            dictionary.put(key, record);
            dirty = true;
            rebuildTerms();
            updateStatus();
            dialog.dismiss();
        } catch (Exception e) {
            showRecordValidationError(fieldEditors, e);
        }
    }

    private Object parseValue(Object original, EditText input) throws Exception {
        String raw = rawTextOf(input);
        String trimmed = raw.trim();
        if (original instanceof Boolean) {
            if ("true".equalsIgnoreCase(trimmed)) return true;
            if ("false".equalsIgnoreCase(trimmed)) return false;
            throw new IllegalArgumentException("expected true or false");
        }
        if (original instanceof Integer) return Integer.parseInt(trimmed);
        if (original instanceof Long) return Long.parseLong(trimmed);
        if (original instanceof Number) return Double.parseDouble(trimmed);
        if (original == JSONObject.NULL) {
            if ("null".equals(trimmed)) return JSONObject.NULL;
            throw new IllegalArgumentException("expected null");
        }
        return raw;
    }

    private void showRecordValidationError(
        List<TermFieldEditor> fieldEditors,
        Exception exception
    ) {
        String message = safeMessage(exception);
        for (TermFieldEditor fieldEditor : fieldEditors) {
            if (message.contains("." + fieldEditor.key)) {
                fieldEditor.layout.setError(getString(
                    R.string.gameterms_record_invalid,
                    message
                ));
                fieldEditor.input.requestFocus();
                return;
            }
        }

        Toast.makeText(
            this,
            getString(R.string.gameterms_record_invalid, message),
            Toast.LENGTH_LONG
        ).show();
    }

    private void moveTermToPending(AlertDialog editorDialog, String key) {
        if (dictionary == null || key == null) {
            return;
        }
        pendingProcessMoveController.confirmMove(
            "term",
            key,
            key,
            () -> removeTermAfterPendingMove(editorDialog, key)
        );
    }

    /** Removes only the local in-memory reference after the durable move succeeds. */
    private void removeTermAfterPendingMove(
        AlertDialog editorDialog,
        String key
    ) {
        if (dictionary == null) {
            return;
        }
        dictionary.remove(key);
        userOverride = true;
        invalidUserOverride = false;
        rebuildTerms();
        updateStatus();
        if (editorDialog != null) {
            editorDialog.dismiss();
        }
    }

    private void importTermDocuments(List<Uri> uris) {
        if (isFinishing() || isDestroyed()) {
            return;
        }
        if (dirty) {
            setImportBusy(false);
            showImportFailure(new IllegalStateException(
                getString(R.string.management_transfer_import_save_first)
            ));
            return;
        }
        if (uris == null || uris.isEmpty()) {
            setImportBusy(false);
            return;
        }
        setImportBusy(true);
        ioExecutor.execute(() -> {
            try {
                ManagementDictionaryTransfer.Batch batch =
                    ManagementDictionaryTransfer.readAndValidate(
                        this,
                        uris,
                        ConfigStore.GAMETERMS_FILE_NAME
                    );
                ConfigStore.JsonLoadResult current = configStore.loadJson(
                    ConfigStore.GAMETERMS_FILE_NAME
                );
                Set<String> conflicts = dictionaryConflicts(
                    current.json,
                    batch
                );
                runOnUiThread(() -> showTermImportReview(batch, conflicts));
            } catch (Exception error) {
                runOnUiThread(() -> {
                    if (isFinishing() || isDestroyed()) {
                        return;
                    }
                    setImportBusy(false);
                    showImportFailure(error);
                });
            }
        });
    }

    private Set<String> dictionaryConflicts(
        JSONObject current,
        ManagementDictionaryTransfer.Batch batch
    ) {
        Set<String> conflicts = new LinkedHashSet<>();
        if (current == null || batch == null) {
            return conflicts;
        }
        for (String key : batch.keys) {
            if (current.has(key)) {
                conflicts.add(key);
            }
        }
        return conflicts;
    }

    private void showTermImportReview(
        ManagementDictionaryTransfer.Batch batch,
        Set<String> conflicts
    ) {
        if (isFinishing() || isDestroyed()) {
            return;
        }
        StringBuilder conflictText = new StringBuilder();
        if (conflicts != null) {
            int shown = 0;
            for (String key : conflicts) {
                if (shown++ >= 20) {
                    conflictText.append("\n…");
                    break;
                }
                if (conflictText.length() > 0) {
                    conflictText.append('\n');
                }
                conflictText.append(key);
            }
        }
        Set<String> expected = conflicts == null
            ? Collections.emptySet()
            : new LinkedHashSet<>(conflicts);
        MaterialAlertDialogBuilder dialog = new UiMaterialAlertDialogBuilder(this)
            .setTitle(R.string.management_transfer_import_title)
            .setMessage(getString(
                conflicts == null || conflicts.isEmpty()
                    ? R.string.management_transfer_import_message
                    : R.string.management_transfer_import_conflict_message,
                batch.keys.size(),
                conflictText.toString()
            ))
            .setNegativeButton(
                R.string.management_transfer_import_cancel,
                (d, which) -> setImportBusy(false)
            );
        if (conflicts == null || conflicts.isEmpty()) {
            dialog.setPositiveButton(
                R.string.management_transfer_import_confirm,
                (d, which) -> commitTermImport(
                    batch,
                    Collections.emptySet(),
                    expected
                )
            );
        } else {
            dialog.setNeutralButton(
                R.string.management_transfer_import_keep,
                (d, which) -> commitTermImport(
                    batch,
                    Collections.emptySet(),
                    expected
                )
            );
            dialog.setPositiveButton(
                R.string.management_transfer_import_overwrite,
                (d, which) -> commitTermImport(
                    batch,
                    expected,
                    expected
                )
            );
        }
        dialog.setOnCancelListener(d -> setImportBusy(false));
        dialog.show();
    }

    private void commitTermImport(
        ManagementDictionaryTransfer.Batch batch,
        Set<String> approved,
        Set<String> expected
    ) {
        if (dirty) {
            setImportBusy(false);
            showImportFailure(new IllegalStateException(
                getString(R.string.management_transfer_import_save_first)
            ));
            return;
        }
        setImportBusy(true);
        ioExecutor.execute(() -> {
            try {
                ConfigStore.DictionaryMergeResult result =
                    configStore.mergeImportedDictionary(
                        ConfigStore.GAMETERMS_FILE_NAME,
                        batch.records,
                        approved,
                        expected
                    );
                ConfigStore.JsonLoadResult loaded = configStore.loadJson(
                    ConfigStore.GAMETERMS_FILE_NAME
                );
                runOnUiThread(() -> {
                    if (isFinishing() || isDestroyed()) {
                        return;
                    }
                    dictionary = loaded.json;
                    userOverride = loaded.userOverride;
                    invalidUserOverride = loaded.invalidUserOverride;
                    dirty = false;
                    rebuildTerms();
                    updateStatus();
                    setImportBusy(false);
                    Toast.makeText(
                        this,
                        getString(
                            R.string.management_transfer_import_result,
                            result.imported,
                            result.overwritten,
                            result.skipped
                        ),
                        Toast.LENGTH_LONG
                    ).show();
                });
            } catch (Exception error) {
                runOnUiThread(() -> {
                    if (isFinishing() || isDestroyed()) {
                        return;
                    }
                    setImportBusy(false);
                    showImportFailure(error);
                });
            }
        });
    }

    private void setImportBusy(boolean busy) {
        importBusy = busy;
        boolean editable = !busy && !batchMode && dictionary != null;
        searchInput.setEnabled(!busy && dictionary != null);
        termList.setEnabled(!busy && dictionary != null);
        findViewById(R.id.btn_add_game_term).setEnabled(editable);
        findViewById(R.id.btn_restore_gameterms).setEnabled(editable);
        importButton.setEnabled(editable && !dirty);
        saveButton.setEnabled(editable && (dirty || !userOverride));
        setManagementBatchActionEnabled(editable);
    }

    private void showImportFailure(Throwable error) {
        Toast.makeText(
            this,
            getString(
                R.string.management_transfer_import_failed,
                safeMessage(error)
            ),
            Toast.LENGTH_LONG
        ).show();
    }

    @Override
    protected void onDestroy() {
        if (managementBatchController != null) {
            managementBatchController.close();
            managementBatchController = null;
        }
        if (pendingProcessMoveController != null) {
            pendingProcessMoveController.close();
            pendingProcessMoveController = null;
        }
        ioExecutor.shutdownNow();
        super.onDestroy();
    }

    private void saveDictionary() {
        if (dictionary == null) {
            return;
        }

        try {
            ConfigStore.validateGameTermDictionary(dictionary);
            configStore.saveJson(ConfigStore.GAMETERMS_FILE_NAME, dictionary);
            userOverride = true;
            invalidUserOverride = false;
            dirty = false;
            updateStatus();
            Toast.makeText(this, R.string.gameterms_saved, Toast.LENGTH_SHORT).show();
        } catch (Exception e) {
            Toast.makeText(
                this,
                getString(R.string.gameterms_save_failed, safeMessage(e)),
                Toast.LENGTH_LONG
            ).show();
        }
    }

    private void confirmRestore() {
        new UiMaterialAlertDialogBuilder(this)
            .setTitle(R.string.restore_gameterms_title)
            .setMessage(R.string.restore_gameterms_message)
            .setNegativeButton(R.string.cancel_action, null)
            .setPositiveButton(
                R.string.reset_action,
                (dialog, which) -> restoreBundledDictionary()
            )
            .show();
    }

    private void restoreBundledDictionary() {
        try {
            configStore.deleteUserFile(ConfigStore.GAMETERMS_FILE_NAME);
            dictionary = configStore.loadBundledJson(
                ConfigStore.GAMETERMS_FILE_NAME
            );
            userOverride = false;
            invalidUserOverride = false;
            dirty = false;
            rebuildTerms();
            updateStatus();
            Toast.makeText(this, R.string.gameterms_restored, Toast.LENGTH_SHORT).show();
        } catch (Exception e) {
            Toast.makeText(
                this,
                getString(R.string.gameterms_load_failed, safeMessage(e)),
                Toast.LENGTH_LONG
            ).show();
        }
    }

    private void updateStatus() {
        if (dictionary == null) {
            return;
        }

        int statusId;
        if (invalidUserOverride) {
            statusId = R.string.gameterms_source_invalid;
        } else if (userOverride) {
            statusId = R.string.gameterms_source_user;
        } else {
            statusId = R.string.gameterms_source_default;
        }
        statusView.setText(getString(statusId, dictionary.length()));
        saveButton.setEnabled(!batchMode && (dirty || !userOverride));
        importButton.setEnabled(!batchMode && !importBusy && !dirty);
    }

    private void setEditorEnabled(boolean enabled) {
        boolean editable = enabled && !batchMode && !importBusy;
        searchInput.setEnabled(enabled && !importBusy);
        termList.setEnabled(enabled && !importBusy);
        findViewById(R.id.btn_add_game_term).setEnabled(editable);
        findViewById(R.id.btn_restore_gameterms).setEnabled(editable);
        setManagementBatchActionEnabled(editable);
        importButton.setEnabled(editable && !dirty);
        saveButton.setEnabled(editable && (dirty || !userOverride));
    }

    private void setManagementBatchActionEnabled(boolean enabled) {
        if (managementBatchMenuItem != null) {
            managementBatchMenuItem.setEnabled(enabled);
        }
    }

    private void handleBackPressed(OnBackPressedCallback callback) {
        if (managementBatchController != null
            && managementBatchController.isActive()) {
            // Leaving batch mode must keep this Activity, its current filter,
            // and the host ListView position intact.
            managementBatchController.exit();
            return;
        }
        if (!dirty) {
            callback.setEnabled(false);
            getOnBackPressedDispatcher().onBackPressed();
            return;
        }

        new UiMaterialAlertDialogBuilder(this)
            .setTitle(R.string.unsaved_gameterms_title)
            .setMessage(R.string.unsaved_gameterms_message)
            .setNegativeButton(R.string.keep_editing, null)
            .setPositiveButton(R.string.discard_changes, (dialog, which) -> {
                dirty = false;
                callback.setEnabled(false);
                getOnBackPressedDispatcher().onBackPressed();
            })
            .show();
    }

    private String displayFieldName(String key) {
        switch (key) {
            case "en": return getString(R.string.dictionary_editor_label_english);
            case "zh-tw": return getString(R.string.dictionary_editor_label_traditional_chinese);
            case "zh-cn": return getString(R.string.dictionary_editor_label_simplified_chinese);
            case "description": return getString(R.string.dictionary_editor_label_description);
            default: return key;
        }
    }

    private static JSONObject newTermRecord() throws Exception {
        JSONObject record = new JSONObject();
        record.put("en", "");
        record.put("zh-tw", "");
        record.put("zh-cn", "");
        record.put("description", "");
        return record;
    }

    private static String formatValue(Object value) {
        return value == JSONObject.NULL ? "null" : String.valueOf(value);
    }

    private static void setReadOnly(EditText field) {
        field.setKeyListener(null);
        field.setCursorVisible(false);
        field.setTextIsSelectable(true);
    }

    private static String textOf(EditText field) {
        return field.getText() == null ? "" : field.getText().toString().trim();
    }

    private static String rawTextOf(EditText field) {
        return field.getText() == null ? "" : field.getText().toString();
    }

    private static String safeMessage(Throwable throwable) {
        String message = throwable.getMessage();
        return TextUtils.isEmpty(message)
            ? throwable.getClass().getSimpleName()
            : message;
    }

    private final class TermBatchDataSource
        implements ManagementBatchController.BatchDataSource {
        @Override
        public String initialKind() {
            return ManagementBatchController.KIND_TERM;
        }

        @Override
        public Set<String> ownedKinds() {
            return Collections.singleton(ManagementBatchController.KIND_TERM);
        }

        @Override
        public String currentFilter() {
            return textOf(searchInput);
        }

        @Override
        public List<ManagementBatchController.Item> snapshotItems()
            throws Exception {
            List<ManagementBatchController.Item> output = new ArrayList<>();
            if (dictionary == null) {
                return output;
            }
            for (String term : allTerms) {
                JSONObject record = dictionary.optJSONObject(term);
                if (record == null) {
                    continue;
                }
                JSONObject payload = new JSONObject(record.toString());
                output.add(new ManagementBatchController.Item(
                    ManagementBatchController.KIND_TERM,
                    term,
                    getString(R.string.management_batch_term_label, term),
                    payload
                ));
            }
            return output;
        }

        @Override
        public List<ManagementBatchController.Item> currentVisibleItems()
            throws Exception {
            List<ManagementBatchController.Item> all = snapshotItems();
            Set<String> visible = new LinkedHashSet<>(visibleTerms);
            all.removeIf(item -> !visible.contains(item.canonicalId));
            return all;
        }

        @Override
        public void onBatchModeChanged(boolean enabled) {
            batchMode = enabled;
            findViewById(R.id.btn_add_game_term).setEnabled(!enabled && dictionary != null);
            findViewById(R.id.btn_restore_gameterms).setEnabled(!enabled && dictionary != null);
            saveButton.setEnabled(
                !enabled && !importBusy && dictionary != null
                    && (dirty || !userOverride)
            );
            importButton.setEnabled(
                !enabled && !importBusy && dictionary != null && !dirty
            );
            searchInput.setEnabled(!importBusy && dictionary != null);
            termList.setEnabled(!importBusy && dictionary != null);
            setManagementBatchActionEnabled(!enabled && !importBusy && dictionary != null);
            if (adapter != null) {
                adapter.notifyDataSetChanged();
            }
        }

        @Override
        public void onBatchSelectionChanged() {
            if (adapter != null) {
                adapter.notifyDataSetChanged();
            }
            if (managementBatchController != null) {
                managementBatchController.onHostRowsChanged();
            }
        }

        @Override
        public void onBatchItemsMoved(List<String> succeededKeys) {
            if (dictionary == null || succeededKeys == null) {
                return;
            }
            boolean changed = false;
            String prefix = ManagementBatchController.KIND_TERM + ":";
            for (String key : succeededKeys) {
                if (key == null || !key.startsWith(prefix)) {
                    continue;
                }
                String term = key.substring(prefix.length());
                if (term.isEmpty()) {
                    continue;
                }
                if (dictionary.has(term)) {
                    dictionary.remove(term);
                    changed = true;
                }
            }
            if (changed) {
                // The durable owner already removed the records. Keep the
                // current editor snapshot in sync without reloading and
                // discarding unrelated unsaved edits.
                userOverride = true;
                invalidUserOverride = false;
                rebuildTerms();
                updateStatus();
            }
        }
    }

    private static final class TermFieldEditor {
        final String key;
        final Object originalValue;
        final TextInputLayout layout;
        final EditText input;

        TermFieldEditor(
            String key,
            Object originalValue,
            TextInputLayout layout,
            EditText input
        ) {
            this.key = key;
            this.originalValue = originalValue;
            this.layout = layout;
            this.input = input;
        }
    }

    private final class GameTermAdapter extends ArrayAdapter<String> {
        GameTermAdapter(Context context, List<String> terms) {
            super(context, R.layout.item_dictionary_entry,
                R.id.tv_dictionary_entry_title, terms);
        }

        @Override
        public View getView(int position, View convertView, ViewGroup parent) {
            View view = convertView;
            if (view == null || view.getId() != R.id.row_dictionary_entry) {
                view = LayoutInflater.from(getContext()).inflate(
                    R.layout.item_dictionary_entry,
                    parent,
                    false
                );
            }
            String term = getItem(position);
            if (term == null) {
                term = "";
            }
            TextView title = view.findViewById(R.id.tv_dictionary_entry_title);
            TextView subtitle = view.findViewById(R.id.tv_dictionary_entry_subtitle);
            MaterialCheckBox check = view.findViewById(R.id.check_dictionary_entry);
            title.setText(termTitle(term));
            subtitle.setText(termSubtitle(term));
            check.setVisibility(batchMode ? View.VISIBLE : View.GONE);
            check.setChecked(batchMode && ManagementBatchSelection.contains(
                ManagementBatchController.KIND_TERM + ":" + term
            ));
            check.setContentDescription(getString(
                R.string.management_home_batch_select,
                termTitle(term)
            ));
            return view;
        }

        private String termTitle(String term) {
            JSONObject record = dictionary == null
                ? null
                : dictionary.optJSONObject(term);
            String localized = localizedValue(record);
            return localized.isEmpty() ? term : localized;
        }

        private String termSubtitle(String term) {
            JSONObject record = dictionary == null
                ? null
                : dictionary.optJSONObject(term);
            if (record == null) {
                return getString(R.string.gameterms_record_invalid, term);
            }

            return term.isEmpty()
                ? getString(R.string.no_localized_term)
                : term;
        }

        private String localizedValue(JSONObject record) {
            if (record == null) {
                return "";
            }
            Locale locale = getResources().getConfiguration().locale;
            boolean traditionalChinese = "zh".equals(locale.getLanguage())
                && ("TW".equalsIgnoreCase(locale.getCountry())
                    || "HK".equalsIgnoreCase(locale.getCountry())
                    || "MO".equalsIgnoreCase(locale.getCountry()));
            String preferred = traditionalChinese
                ? record.optString("zh-tw", "")
                : "zh".equals(locale.getLanguage())
                    ? record.optString("zh-cn", "")
                    : "en".equals(locale.getLanguage())
                        ? record.optString("en", "")
                        : "";
            if (!preferred.isEmpty()) {
                return preferred;
            }
            String fallback = record.optString("zh-cn", "");
            if (!fallback.isEmpty()) {
                return fallback;
            }
            fallback = record.optString("en", "");
            return fallback.isEmpty()
                ? record.optString("zh-tw", "")
                : fallback;
        }
    }
}
