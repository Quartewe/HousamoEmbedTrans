package com.quarty.housamoembedtrans.ui;

import com.quarty.housamoembedtrans.R;
import com.quarty.housamoembedtrans.storage.config.ConfigStore;

import android.content.Context;
import android.content.Intent;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Bundle;
import android.text.Editable;
import android.text.InputType;
import android.text.TextUtils;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.KeyEvent;
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
import org.json.JSONTokener;

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

/** Searchable editor for the user-owned files/chardict.json override. */
public final class CharacterDictionaryActivity extends AppCompatActivity {

    public static final String EXTRA_CHARACTER_NAME =
        "character_name";
    /** Opens the existing dictionary page with the new-character draft shown. */
    public static final String EXTRA_CREATE_CHARACTER =
        "create_character";
    private static final String STATE_MANAGEMENT_LINK_CONSUMED =
        "management_link_consumed";
    private static final String STATE_MANAGEMENT_LINK_FAILURE_NOTIFIED =
        "management_link_failure_notified";
    private static final String STATE_EDITOR_RAW_DRAFT =
        "dictionary_editor.character.raw_draft";
    private static final String STATE_EDITOR_NAME =
        "dictionary_editor.character.name";
    private static final String STATE_EDITOR_DIRTY =
        "dictionary_editor.character.dirty";
    private static final String DRAFT_FIELD_PREFIX = "field.";
    private static final String DRAFT_ARRAY_PREFIX = "array.";
    private static final String DRAFT_ARRAY_COUNT = "count";
    private static final String DRAFT_ARRAY_EXPANDED = "expanded";
    private static final String DRAFT_ELEMENT_PREFIX = "element.";
    private static final String DRAFT_ELEMENT_SEED = "seed";
    private static final String DRAFT_ELEMENT_KEYS = "keys";
    private static final String DRAFT_ELEMENT_VALUES = "values";

    private static final String[] CHARACTER_FIELD_ORDER = {
        "zh-cn",
        "zh-tw",
        "en",
        "speech_style",
        "description",
        "alias",
        "school",
        "guild",
        "origin_world",
        "relationships",
    };

    private static final String[] ALIAS_FIELD_ORDER = {
        "name",
        "en",
        "zh-tw",
        "zh-cn",
        "called"
    };

    private static final String[] RELATIONSHIP_FIELD_ORDER = {
        "target",
        "type"
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
    private ListView characterList;
    private Button saveButton;
    private Button importButton;
    private MenuItem managementBatchMenuItem;

    private final List<String> allNames = new ArrayList<>();
    private final List<String> visibleNames = new ArrayList<>();
    private CharacterAdapter adapter;
    private boolean batchMode;

    /** The management entry opens this Activity as a full-page draft editor. */
    private boolean editorMode;
    private String editorOriginalName;
    private boolean editorEditingMc;
    private boolean editorDirty;
    private boolean editorBusy;
    private boolean stylePreview;
    private JSONObject editorSourceRecord;
    private EditText editorNameInput;
    private TextInputLayout editorNameLayout;
    private TextView editorStatusView;
    private MaterialButton editorSaveButton;
    private MaterialButton editorDiscardButton;
    private MaterialButton editorMoveButton;
    private TextView editorMoveHint;
    private List<CharacterFieldEditor> editorFieldEditors = Collections.emptyList();
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
        setContentView(R.layout.activity_character_dictionary);
        SystemBarInsets.apply(findViewById(R.id.root_character_dictionary));
        MaterialToolbar toolbar = findViewById(R.id.toolbar_character_dictionary);
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
        searchInput = findViewById(R.id.et_character_search);
        statusView = findViewById(R.id.tv_chardict_status);
        characterList = findViewById(R.id.list_characters);
        saveButton = findViewById(R.id.btn_save_chardict);
        importButton = findViewById(R.id.btn_import_chardict);
        importLauncher = registerForActivityResult(
            new ActivityResultContracts.OpenMultipleDocuments(),
            this::importCharacterDocuments
        );

        adapter = new CharacterAdapter(this, visibleNames);
        characterList.setAdapter(adapter);
        characterList.setEmptyView(findViewById(R.id.tv_character_empty));
        characterList.setOnItemClickListener((parent, view, position, id) -> {
            String name = visibleNames.get(position);
            if (batchMode) {
                ManagementBatchSelection.set(
                    ManagementBatchController.KIND_CHARACTER + ":" + name,
                    !ManagementBatchSelection.contains(
                        ManagementBatchController.KIND_CHARACTER + ":" + name
                    )
                );
                adapter.notifyDataSetChanged();
                if (managementBatchController != null) {
                    managementBatchController.onHostRowsChanged();
                }
            } else {
                editCharacter(name);
            }
        });

        searchInput.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence value, int start, int count, int after) {
            }

            @Override
            public void onTextChanged(CharSequence value, int start, int before, int count) {
                applyFilter(value == null ? "" : value.toString());
            }

            @Override
            public void afterTextChanged(Editable value) {
            }
        });

        findViewById(R.id.btn_add_character).setOnClickListener(view -> editCharacter(null));
        findViewById(R.id.btn_edit_mc).setOnClickListener(view -> editCharacter("mc"));
        findViewById(R.id.btn_restore_chardict).setOnClickListener(view -> confirmRestore());
        findViewById(R.id.btn_chardict_conflicts).setOnClickListener(view -> {
            if (stylePreview || dirty || importBusy || batchMode) return;
            refreshListAfterEditor = true;
            startActivity(new Intent(this, SceneConflictsActivity.class)
                .putExtra(SceneConflictsActivity.EXTRA_CHARACTER_DICTIONARY, true));
        });
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
                CharacterDictionaryActivity.this.handleBackPressed(this);
            }
        });

        loadDictionary();
        managementBatchController = ManagementBatchController.attach(
            this,
            findViewById(R.id.root_character_dictionary),
            new CharacterBatchDataSource(),
            savedInstanceState
        );
    }

    private boolean hasEditorIntent(Intent intent) {
        return intent != null
            && (intent.hasExtra(EXTRA_CHARACTER_NAME)
                || intent.getBooleanExtra(EXTRA_CREATE_CHARACTER, false));
    }

    private void setupEditorPage(Bundle savedInstanceState) {
        setContentView(R.layout.activity_character_editor);
        SystemBarInsets.apply(findViewById(R.id.root_character_editor));

        MaterialToolbar toolbar = findViewById(R.id.toolbar_character_editor);
        toolbar.setNavigationOnClickListener(
            view -> getOnBackPressedDispatcher().onBackPressed()
        );
        editorNameInput = findViewById(R.id.et_character_editor_name);
        editorNameLayout = findViewById(R.id.til_character_editor_name);
        editorStatusView = findViewById(R.id.tv_character_editor_status);
        editorSaveButton = findViewById(R.id.btn_save_character_editor);
        editorDiscardButton = findViewById(R.id.btn_discard_character_editor);
        editorMoveButton = findViewById(R.id.btn_move_character_pending_editor);
        editorMoveHint = findViewById(R.id.tv_move_character_pending_editor_hint);

        Intent intent = getIntent();
        editorOriginalName = intent == null
            ? null
            : intent.getStringExtra(EXTRA_CHARACTER_NAME);
        if (editorOriginalName != null && editorOriginalName.trim().isEmpty()) {
            editorOriginalName = null;
        }
        editorEditingMc = "mc".equals(editorOriginalName);
        toolbar.setTitle(editorEditingMc
            ? R.string.edit_mc
            : editorOriginalName == null
                ? R.string.add_character
                : R.string.edit_character);
        TextView editorHeading = findViewById(R.id.tv_character_editor_heading);
        editorHeading.setText(toolbar.getTitle());
        if (editorEditingMc) {
            findViewById(R.id.character_editor_name_field)
                .setVisibility(View.GONE);
            editorNameLayout.setVisibility(View.GONE);
        }

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
        loadEditorPage(savedInstanceState);
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (!editorMode && refreshListAfterEditor && !isFinishing()) {
            refreshListAfterEditor = false;
            loadDictionary();
        }
    }

    private void loadEditorPage(Bundle savedInstanceState) {
        try {
            JSONObject source;
            if (stylePreview) {
                source = StylePreview.payloadOf(getIntent());
                if (source == null) {
                    source = StylePreview.sample(StylePreview.KIND_CHARACTER_EDITOR);
                }
                dictionary = new JSONObject();
                dictionary.put(
                    editorOriginalName == null
                        ? StylePreview.SAMPLE_CHARACTER_NAME
                        : editorOriginalName,
                    new JSONObject(source.toString())
                );
                userOverride = false;
                invalidUserOverride = false;
            } else {
                ConfigStore.JsonLoadResult loaded = configStore.loadJson(
                    ConfigStore.CHARDICT_FILE_NAME
                );
                dictionary = loaded.json;
                userOverride = loaded.userOverride;
                invalidUserOverride = loaded.invalidUserOverride;
                if (editorOriginalName == null) {
                    source = newCharacterRecord();
                } else {
                    source = dictionary.optJSONObject(editorOriginalName);
                    if (source == null) {
                        throw new IllegalStateException(
                            getString(R.string.dictionary_editor_character_missing)
                        );
                    }
                }
            }
            editorSourceRecord = new JSONObject(source.toString());
            Bundle draftState = savedInstanceState == null
                ? null
                : savedInstanceState.getBundle(STATE_EDITOR_RAW_DRAFT);
            editorNameInput.setText(
                savedInstanceState == null
                    ? (editorOriginalName == null ? "" : editorOriginalName)
                    : savedInstanceState.getString(
                        STATE_EDITOR_NAME,
                        editorOriginalName == null ? "" : editorOriginalName
                    )
            );
            if (editorOriginalName != null) {
                setReadOnly(editorNameInput);
                editorNameLayout.setHelperText(
                    getString(R.string.character_key_read_only)
                );
            } else {
                editorNameLayout.setHelperText(
                    getString(R.string.character_key_new_hint)
                );
            }
            LinearLayout fields = findViewById(
                R.id.container_character_editor_fields
            );
            editorFieldEditors = createFieldEditors(
                new JSONObject(source.toString()),
                fields
            );
            restoreEditorDraftState(draftState);
            TextView infoValue = findViewById(R.id.tv_character_editor_info_value);
            String info = editorSourceRecord == null
                ? source.optString("info", "")
                : editorSourceRecord.optString("info", "");
            infoValue.setText(TextUtils.isEmpty(info.trim())
                ? getString(R.string.dictionary_editor_info_empty)
                : info);
            attachDraftChangeWatchers(editorFieldEditors, () -> {
                editorDirty = true;
                updateEditorActions();
            });
            editorNameInput.addTextChangedListener(new TextWatcher() {
                @Override
                public void beforeTextChanged(CharSequence s, int start, int count, int after) {
                }

                @Override
                public void onTextChanged(CharSequence s, int start, int before, int count) {
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
            boolean canMove = !stylePreview
                && editorOriginalName != null
                && !editorEditingMc;
            editorMoveButton.setVisibility(canMove ? View.VISIBLE : View.GONE);
            editorMoveHint.setVisibility(canMove ? View.VISIBLE : View.GONE);
            updateEditorActions();
        } catch (Exception error) {
            editorStatusView.setVisibility(View.VISIBLE);
            editorStatusView.setText(getString(
                R.string.chardict_load_failed,
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
        editorNameInput.setEnabled(enabled && editorOriginalName == null);
        editorSaveButton.setEnabled(enabled);
        editorDiscardButton.setEnabled(!editorBusy);
        if (editorMoveButton != null) {
            editorMoveButton.setEnabled(
                enabled
                    && editorOriginalName != null
                    && !editorEditingMc
                    && !editorDirty
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
        String name = editorOriginalName == null
            ? textOf(editorNameInput)
            : editorOriginalName;
        if (name.isEmpty()) {
            editorNameInput.setError(getString(R.string.error_required));
            editorNameInput.requestFocus();
            return;
        }
        if (editorOriginalName == null && dictionary.has(name)) {
            editorNameInput.setError(getString(R.string.character_already_exists));
            editorNameInput.requestFocus();
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
        for (CharacterFieldEditor fieldEditor : editorFieldEditors) {
            clearFieldError(fieldEditor);
            try {
                updatedRecord.put(
                    fieldEditor.key,
                    parseFieldValue(fieldEditor)
                );
            } catch (Exception error) {
                showFieldError(fieldEditor, getString(
                    R.string.character_field_invalid,
                    displayFieldName(fieldEditor.key),
                    safeMessage(error)
                ));
                return;
            }
        }

        try {
            ConfigStore.validateCharacterRecord(name, updatedRecord);
            JSONObject updatedDictionary = new JSONObject(dictionary.toString());
            updatedDictionary.put(name, updatedRecord);
            ConfigStore.validateCharacterDictionary(updatedDictionary);
            configStore.saveJson(
                ConfigStore.CHARDICT_FILE_NAME,
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
            || editorOriginalName == null || editorEditingMc || editorDirty
            || pendingProcessMoveController == null) {
            return;
        }
        editorBusy = true;
        updateEditorActions();
        pendingProcessMoveController.confirmMove(
            "character",
            editorOriginalName,
            editorOriginalName,
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
            .setTitle(R.string.unsaved_character_draft_title)
            .setMessage(R.string.unsaved_character_draft_message)
            .setNegativeButton(R.string.keep_editing, null)
            .setPositiveButton(R.string.discard_changes, (dialog, which) -> finish())
            .show();
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
            outState.putString(STATE_EDITOR_NAME, rawTextOf(editorNameInput));
            outState.putBundle(STATE_EDITOR_RAW_DRAFT, editorDraftState());
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

    private Bundle editorDraftState() {
        Bundle draft = new Bundle();
        for (CharacterFieldEditor fieldEditor : editorFieldEditors) {
            if (fieldEditor.arrayEditor != null) {
                draft.putBundle(
                    DRAFT_ARRAY_PREFIX + fieldEditor.key,
                    fieldEditor.arrayEditor.draftState()
                );
            } else if (fieldEditor.input != null) {
                draft.putString(
                    DRAFT_FIELD_PREFIX + fieldEditor.key,
                    rawTextOf(fieldEditor.input)
                );
            }
        }
        return draft;
    }

    private void restoreEditorDraftState(Bundle draft) {
        if (draft == null) {
            return;
        }
        for (CharacterFieldEditor fieldEditor : editorFieldEditors) {
            if (fieldEditor.arrayEditor != null) {
                Bundle arrayState = draft.getBundle(
                    DRAFT_ARRAY_PREFIX + fieldEditor.key
                );
                if (arrayState != null) {
                    fieldEditor.arrayEditor.restoreDraftState(arrayState);
                }
            } else if (fieldEditor.input != null) {
                String key = DRAFT_FIELD_PREFIX + fieldEditor.key;
                if (draft.containsKey(key)) {
                    fieldEditor.input.setText(draft.getString(key, ""));
                }
            }
        }
    }

    private void loadDictionary() {
        try {
            ConfigStore.JsonLoadResult result = configStore.loadJson(
                ConfigStore.CHARDICT_FILE_NAME
            );
            dictionary = result.json;
            userOverride = result.userOverride;
            invalidUserOverride = result.invalidUserOverride;
            dirty = false;
            ensureMcRecord();
            rebuildNames();
            updateStatus();
            setEditorEnabled(true);
            consumeManagementLinkAfterLoad();
        } catch (Exception e) {
            dictionary = null;
            statusView.setText(getString(
                R.string.chardict_load_failed,
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
        if (intent == null) {
            return;
        }

        if (intent.getBooleanExtra(EXTRA_CREATE_CHARACTER, false)) {
            managementLinkConsumed = true;
            editCharacter(null);
            return;
        }
        if (!intent.hasExtra(EXTRA_CHARACTER_NAME)) {
            return;
        }

        String name = intent.getStringExtra(EXTRA_CHARACTER_NAME);
        managementLinkConsumed = true;
        if (name == null || name.isEmpty() || !dictionary.has(name)) {
            notifyManagementLinkTargetMissing();
            return;
        }
        editCharacter(name);
    }

    private boolean hasManagementLink() {
        Intent intent = getIntent();
        return intent != null
            && (intent.hasExtra(EXTRA_CHARACTER_NAME)
                || intent.getBooleanExtra(EXTRA_CREATE_CHARACTER, false));
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

    private void rebuildNames() {
        allNames.clear();
        Iterator<String> names = dictionary.keys();
        while (names.hasNext()) {
            String name = names.next();
            if (!"mc".equals(name)) {
                allNames.add(name);
            }
        }

        Collator collator = Collator.getInstance(Locale.JAPANESE);
        Collections.sort(allNames, collator::compare);

        applyFilter(textOf(searchInput));
    }

    private void applyFilter(String query) {
        visibleNames.clear();
        if (dictionary == null) {
            adapter.notifyDataSetChanged();
            return;
        }

        String needle = query.trim().toLowerCase(Locale.ROOT);
        for (String name : allNames) {
            if (needle.isEmpty() || recordMatches(name, needle)) {
                visibleNames.add(name);
            }
        }
        if (batchMode && dictionary.has("mc")
            && (needle.isEmpty() || recordMatches("mc", needle))) {
            visibleNames.add(0, "mc");
        }

        adapter.notifyDataSetChanged();
        if (batchMode && managementBatchController != null) {
            managementBatchController.onHostRowsChanged();
        }
    }

    private boolean recordMatches(String name, String needle) {
        if (name.toLowerCase(Locale.ROOT).contains(needle)) {
            return true;
        }

        JSONObject record = dictionary.optJSONObject(name);
        if (record == null) {
            return false;
        }

        String[] localizedFields = {"en", "zh-tw", "zh-cn"};
        for (String field : localizedFields) {
            if (record.optString(field, "").toLowerCase(Locale.ROOT).contains(needle)) {
                return true;
            }
        }

        JSONArray aliases = record.optJSONArray("alias");
        if (aliases != null) {
            for (int index = 0; index < aliases.length(); index++) {
                JSONObject alias = aliases.optJSONObject(index);
                if (alias == null) continue;

                if (alias.optString("name", "").toLowerCase(Locale.ROOT).contains(needle)
                    || alias.optString("called", "").toLowerCase(Locale.ROOT).contains(needle)) {
                    return true;
                }
            }
        }

        return false;
    }

    private void editCharacter(String originalName) {
        if (dictionary == null || editorMode) {
            return;
        }
        refreshListAfterEditor = true;
        Intent intent = new Intent(this, CharacterDictionaryActivity.class);
        if (originalName == null) {
            intent.putExtra(EXTRA_CREATE_CHARACTER, true);
        } else {
            intent.putExtra(EXTRA_CHARACTER_NAME, originalName);
        }
        startActivity(intent);
    }

    private void saveCharacterFromDialog(
        AlertDialog dialog,
        String originalName,
        EditText nameInput,
        List<CharacterFieldEditor> fieldEditors
    ) {
        String newName = originalName == null ? textOf(nameInput) : originalName;
        if (newName.isEmpty()) {
            nameInput.setError(getString(R.string.error_required));
            nameInput.requestFocus();
            return;
        }

        if (originalName == null && dictionary.has(newName)) {
            nameInput.setError(getString(R.string.character_already_exists));
            nameInput.requestFocus();
            return;
        }

        JSONObject record = new JSONObject();
        for (CharacterFieldEditor fieldEditor : fieldEditors) {
            clearFieldError(fieldEditor);
            try {
                record.put(
                    fieldEditor.key,
                    parseFieldValue(fieldEditor)
                );
            } catch (Exception e) {
                showFieldError(fieldEditor, getString(
                    R.string.character_field_invalid,
                    displayFieldName(fieldEditor.key),
                    safeMessage(e)
                ));
                return;
            }
        }

        try {
            ConfigStore.validateCharacterRecord(newName, record);
            dictionary.put(newName, record);

            dirty = true;
            rebuildNames();
            updateStatus();
            dialog.dismiss();
        } catch (Exception e) {
            showRecordValidationError(fieldEditors, e);
        }
    }

    private List<CharacterFieldEditor> createFieldEditors(
        JSONObject record,
        LinearLayout container
    ) throws Exception {
        List<CharacterFieldEditor> editors = new ArrayList<>();
        for (String key : orderedFieldKeys(record)) {
            Object value = record.has(key)
                ? record.get(key)
                : defaultCharacterFieldValue(key);
            if (value instanceof JSONArray) {
                ArrayFieldEditor arrayEditor = createArrayFieldEditor(
                    key,
                    (JSONArray) value,
                    container
                );
                editors.add(new CharacterFieldEditor(key, value, arrayEditor));
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
            boolean readOnly = "info".equals(key);

            label.setText(displayFieldName(key));
            layout.setHintEnabled(false);
            layout.setHint(null);
            configureFieldInput(key, value, layout, input, readOnly);
            input.setText(formatFieldValue(value));
            disableAutomaticViewState(fieldView);
            container.addView(fieldView);
            editors.add(new CharacterFieldEditor(
                key,
                value,
                layout,
                input,
                readOnly
            ));
        }
        return editors;
    }

    private ArrayFieldEditor createArrayFieldEditor(
        String key,
        JSONArray value,
        LinearLayout container
    ) throws Exception {
        View fieldView = getLayoutInflater().inflate(
            R.layout.item_character_array_editor,
            container,
            false
        );
        ArrayFieldEditor editor = new ArrayFieldEditor(key, fieldView);
        disableAutomaticViewState(fieldView);
        container.addView(fieldView);

        for (int index = 0; index < value.length(); index++) {
            editor.addElement(value.get(index), false);
        }
        editor.updateSummary();
        editor.setExpanded("alias".equals(key));
        return editor;
    }

    private List<String> orderedFieldKeys(JSONObject record) {
        Set<String> keys = new LinkedHashSet<>();
        for (String preferred : CHARACTER_FIELD_ORDER) {
            if (editorMode && editorEditingMc
                && ("zh-cn".equals(preferred)
                    || "zh-tw".equals(preferred)
                    || "en".equals(preferred))) {
                continue;
            }
            // Keep the editor schema visible even when an optional field is
            // absent from a user-owned record.  Saving the page then writes a
            // complete record while preserving any unknown extension keys.
            keys.add(preferred);
        }

        Iterator<String> remaining = record.keys();
        while (remaining.hasNext()) {
            String key = remaining.next();
            if (editorMode && "info".equals(key)) {
                continue;
            }
            if (editorMode && editorEditingMc
                && ("zh-cn".equals(key)
                    || "zh-tw".equals(key)
                    || "en".equals(key))) {
                continue;
            }
            keys.add(key);
        }
        return new ArrayList<>(keys);
    }

    private Object defaultCharacterFieldValue(String key) {
        if ("alias".equals(key)
            || "school".equals(key)
            || "guild".equals(key)
            || "origin_world".equals(key)
            || "relationships".equals(key)) {
            return new JSONArray();
        }
        return "";
    }

    private void configureFieldInput(
        String key,
        Object value,
        TextInputLayout layout,
        TextInputEditText input,
        boolean readOnly
    ) {
        boolean structured = value instanceof JSONArray || value instanceof JSONObject;
        boolean longText = structured
            || "info".equals(key)
            || "description".equals(key);

        if (longText) {
            input.setSingleLine(false);
            input.setInputType(
                InputType.TYPE_CLASS_TEXT
                    | InputType.TYPE_TEXT_FLAG_MULTI_LINE
                    | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            );
            input.setGravity(Gravity.TOP | Gravity.START);
            input.setMinLines(structured ? 4 : 3);
            input.setMaxLines(8);
            input.setHorizontallyScrolling(false);
            input.setVerticalScrollBarEnabled(true);
        } else {
            input.setSingleLine(true);
            input.setInputType(InputType.TYPE_CLASS_TEXT);
        }

        if (structured) {
            input.setTypeface(Typeface.MONOSPACE);
            layout.setHelperText(getString(R.string.character_json_value_hint));
        }

        if (readOnly) {
            layout.setHelperText(getString(R.string.character_info_read_only));
            setReadOnly(input);
        }
    }

    private Object parseFieldValue(CharacterFieldEditor fieldEditor) throws Exception {
        if (fieldEditor.arrayEditor != null) {
            return fieldEditor.arrayEditor.toJson();
        }
        if (fieldEditor.readOnly) {
            return fieldEditor.originalValue;
        }
        return parseTypedValue(fieldEditor.originalValue, fieldEditor.input);
    }

    private Object parseTypedValue(Object original, EditText input) throws Exception {
        String raw = rawTextOf(input);
        String trimmed = raw.trim();

        if (original instanceof JSONObject) {
            return new JSONObject(trimmed);
        }
        if (original instanceof JSONArray) {
            return new JSONArray(trimmed);
        }
        if (original instanceof Boolean) {
            if ("true".equalsIgnoreCase(trimmed)) return true;
            if ("false".equalsIgnoreCase(trimmed)) return false;
            throw new IllegalArgumentException("expected true or false");
        }
        if (original instanceof Integer) {
            return Integer.parseInt(trimmed);
        }
        if (original instanceof Long) {
            return Long.parseLong(trimmed);
        }
        if (original instanceof Number) {
            return Double.parseDouble(trimmed);
        }
        if (original == JSONObject.NULL) {
            if ("null".equals(trimmed)) return JSONObject.NULL;
            throw new IllegalArgumentException("expected null");
        }
        return raw;
    }

    private void showRecordValidationError(
        List<CharacterFieldEditor> fieldEditors,
        Exception exception
    ) {
        String message = safeMessage(exception);
        for (CharacterFieldEditor fieldEditor : fieldEditors) {
            if (message.contains("." + fieldEditor.key)) {
                showFieldValidationError(fieldEditor, getString(
                    R.string.chardict_record_invalid,
                    message
                ));
                return;
            }
        }

        Toast.makeText(
            this,
            getString(R.string.chardict_record_invalid, message),
            Toast.LENGTH_LONG
        ).show();
    }

    private void clearFieldError(CharacterFieldEditor fieldEditor) {
        if (fieldEditor.arrayEditor != null) {
            fieldEditor.arrayEditor.clearErrors();
        } else {
            fieldEditor.layout.setError(null);
        }
    }

    private void showFieldError(CharacterFieldEditor fieldEditor, String message) {
        if (fieldEditor.arrayEditor != null) {
            fieldEditor.arrayEditor.showError(message);
        } else {
            fieldEditor.layout.setError(message);
            fieldEditor.input.requestFocus();
        }
    }

    private void showFieldValidationError(
        CharacterFieldEditor fieldEditor,
        String message
    ) {
        if (fieldEditor.arrayEditor != null) {
            fieldEditor.arrayEditor.showValidationError(message);
        } else {
            showFieldError(fieldEditor, message);
        }
    }

    private String displayFieldName(String key) {
        int resourceId;
        switch (key) {
            case "alias": resourceId = R.string.dictionary_editor_label_alias; break;
            case "name": resourceId = R.string.dictionary_editor_label_original_name; break;
            case "en": resourceId = R.string.dictionary_editor_label_english; break;
            case "zh-tw": resourceId = R.string.dictionary_editor_label_traditional_chinese; break;
            case "zh-cn": resourceId = R.string.dictionary_editor_label_simplified_chinese; break;
            case "called": resourceId = R.string.dictionary_editor_label_called; break;
            case "school": resourceId = R.string.dictionary_editor_label_school; break;
            case "guild": resourceId = R.string.dictionary_editor_label_guild; break;
            case "origin_world": resourceId = R.string.dictionary_editor_label_origin_world; break;
            case "relationships": resourceId = R.string.dictionary_editor_label_relationships; break;
            case "target": resourceId = R.string.dictionary_editor_label_target; break;
            case "type": resourceId = R.string.dictionary_editor_label_relation_type; break;
            case "info": resourceId = R.string.dictionary_editor_label_info; break;
            case "description": resourceId = R.string.dictionary_editor_label_description; break;
            case "speech_style": resourceId = R.string.dictionary_editor_label_speech_style; break;
            default: return key;
        }
        return getString(resourceId);
    }

    private List<String> orderedArrayObjectKeys(
        String arrayKey,
        JSONObject object
    ) {
        Set<String> keys = new LinkedHashSet<>();
        String[] preferred = null;
        if ("alias".equals(arrayKey)) {
            preferred = ALIAS_FIELD_ORDER;
        } else if ("relationships".equals(arrayKey)) {
            preferred = RELATIONSHIP_FIELD_ORDER;
        }

        if (preferred != null) {
            Collections.addAll(keys, preferred);
        }

        Iterator<String> remaining = object.keys();
        while (remaining.hasNext()) {
            keys.add(remaining.next());
        }
        return new ArrayList<>(keys);
    }

    private ElementValueEditor createElementValueEditor(
        String key,
        Object value,
        String hint,
        LinearLayout container,
        boolean omitWhenEmpty
    ) throws Exception {
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
        label.setText(hint);
        layout.setHintEnabled(false);
        layout.setHint(null);
        configureFieldInput(key, value, layout, input, false);
        input.setText(formatFieldValue(value));
        disableAutomaticViewState(fieldView);
        container.addView(fieldView);
        return new ElementValueEditor(
            key,
            value,
            layout,
            input,
            omitWhenEmpty
        );
    }

    private Object emptyValueFor(Object value) {
        if (value instanceof JSONArray) return new JSONArray();
        if (value instanceof JSONObject) return new JSONObject();
        if (value instanceof Boolean) return false;
        if (value instanceof Integer) return 0;
        if (value instanceof Long) return 0L;
        if (value instanceof Number) return 0.0;
        if (value == JSONObject.NULL) return JSONObject.NULL;
        return "";
    }

    private static String formatFieldValue(Object value) throws Exception {
        if (value == JSONObject.NULL) {
            return "null";
        }
        if (value instanceof JSONObject) {
            return ((JSONObject) value).toString(2);
        }
        if (value instanceof JSONArray) {
            return ((JSONArray) value).toString(2);
        }
        return String.valueOf(value);
    }

    private static String jsonSeedValue(Object value) {
        if (value == null || value == JSONObject.NULL) {
            return "null";
        }
        if (value instanceof JSONObject || value instanceof JSONArray) {
            return value.toString();
        }
        if (value instanceof String || value instanceof Character) {
            return JSONObject.quote(String.valueOf(value));
        }
        if (value instanceof Boolean) {
            return String.valueOf(value);
        }
        if (value instanceof Number) {
            try {
                return JSONObject.numberToString((Number) value);
            } catch (Exception ignored) {
                return "null";
            }
        }
        return JSONObject.quote(String.valueOf(value));
    }

    private static void setReadOnly(EditText field) {
        field.setKeyListener(null);
        field.setCursorVisible(false);
        field.setTextIsSelectable(true);
    }

    private void moveCharacterToPending(AlertDialog editorDialog, String name) {
        if (dictionary == null || name == null || "mc".equals(name)) {
            return;
        }
        pendingProcessMoveController.confirmMove(
            "character",
            name,
            name,
            () -> removeCharacterAfterPendingMove(editorDialog, name)
        );
    }

    /** Removes only the local in-memory reference after the durable move succeeds. */
    private void removeCharacterAfterPendingMove(
        AlertDialog editorDialog,
        String name
    ) {
        if (dictionary == null || "mc".equals(name)) {
            return;
        }
        dictionary.remove(name);
        userOverride = true;
        invalidUserOverride = false;
        rebuildNames();
        updateStatus();
        if (editorDialog != null) {
            editorDialog.dismiss();
        }
    }

    private void importCharacterDocuments(List<Uri> uris) {
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
                        ConfigStore.CHARDICT_FILE_NAME
                    );
                ConfigStore.JsonLoadResult current = configStore.loadJson(
                    ConfigStore.CHARDICT_FILE_NAME
                );
                Set<String> conflicts = dictionaryConflicts(
                    current.json,
                    batch
                );
                runOnUiThread(() -> showCharacterImportReview(
                    batch,
                    conflicts
                ));
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

    private void showCharacterImportReview(
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
                (d, which) -> commitCharacterImport(
                    batch,
                    Collections.emptySet(),
                    expected
                )
            );
        } else {
            dialog.setNeutralButton(
                R.string.management_transfer_import_keep,
                (d, which) -> commitCharacterImport(
                    batch,
                    Collections.emptySet(),
                    expected
                )
            );
            dialog.setPositiveButton(
                R.string.management_transfer_import_overwrite,
                (d, which) -> commitCharacterImport(
                    batch,
                    expected,
                    expected
                )
            );
        }
        dialog.setOnCancelListener(d -> setImportBusy(false));
        dialog.show();
    }

    private void attachDraftChangeWatchers(
        List<CharacterFieldEditor> fieldEditors,
        Runnable onChanged
    ) {
        if (fieldEditors == null || onChanged == null) {
            return;
        }
        for (CharacterFieldEditor fieldEditor : fieldEditors) {
            if (fieldEditor.arrayEditor != null) {
                fieldEditor.arrayEditor.setDraftChangeListener(onChanged);
                continue;
            }
            if (fieldEditor.input != null && !fieldEditor.readOnly) {
                fieldEditor.input.addTextChangedListener(new TextWatcher() {
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
                    }

                    @Override
                    public void afterTextChanged(Editable value) {
                        onChanged.run();
                    }
                });
            }
        }
    }

    private void handleCharacterDraftBack(
        AlertDialog dialog,
        boolean draftDirty
    ) {
        if (!draftDirty) {
            dialog.dismiss();
            return;
        }
        new UiMaterialAlertDialogBuilder(this)
            .setTitle(R.string.unsaved_character_draft_title)
            .setMessage(R.string.unsaved_character_draft_message)
            .setNegativeButton(R.string.keep_editing, null)
            .setPositiveButton(
                R.string.discard_changes,
                (ignored, which) -> dialog.dismiss()
            )
            .show();
    }

    private void commitCharacterImport(
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
                        ConfigStore.CHARDICT_FILE_NAME,
                        batch.records,
                        approved,
                        expected
                    );
                ConfigStore.JsonLoadResult loaded = configStore.loadJson(
                    ConfigStore.CHARDICT_FILE_NAME
                );
                runOnUiThread(() -> {
                    if (isFinishing() || isDestroyed()) {
                        return;
                    }
                    dictionary = loaded.json;
                    userOverride = loaded.userOverride;
                    invalidUserOverride = loaded.invalidUserOverride;
                    dirty = false;
                    rebuildNames();
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
        characterList.setEnabled(!busy && dictionary != null);
        findViewById(R.id.btn_edit_mc).setEnabled(editable);
        findViewById(R.id.btn_add_character).setEnabled(editable);
        findViewById(R.id.btn_restore_chardict).setEnabled(editable);
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
            ConfigStore.validateCharacterDictionary(dictionary);
            configStore.saveJson(ConfigStore.CHARDICT_FILE_NAME, dictionary);
            loadDictionary();
            Toast.makeText(this, R.string.chardict_saved, Toast.LENGTH_SHORT).show();
        } catch (Exception e) {
            Toast.makeText(
                this,
                getString(R.string.chardict_save_failed, safeMessage(e)),
                Toast.LENGTH_LONG
            ).show();
        }
    }

    private void confirmRestore() {
        new UiMaterialAlertDialogBuilder(this)
            .setTitle(R.string.restore_chardict_title)
            .setMessage(R.string.restore_chardict_message)
            .setNegativeButton(R.string.cancel_action, null)
            .setPositiveButton(R.string.reset_action, (dialog, which) -> restoreBundledDictionary())
            .show();
    }

    private void restoreBundledDictionary() {
        try {
            configStore.deleteUserFile(ConfigStore.CHARDICT_FILE_NAME);
            dictionary = configStore.loadBundledJson(ConfigStore.CHARDICT_FILE_NAME);
            userOverride = false;
            invalidUserOverride = false;
            dirty = false;
            rebuildNames();
            updateStatus();
            Toast.makeText(this, R.string.chardict_restored, Toast.LENGTH_SHORT).show();
        } catch (Exception e) {
            Toast.makeText(
                this,
                getString(R.string.chardict_load_failed, safeMessage(e)),
                Toast.LENGTH_LONG
            ).show();
        }
    }

    private void updateStatus() {
        findViewById(R.id.btn_chardict_conflicts).setEnabled(
            !stylePreview && !dirty && !importBusy && !batchMode && dictionary != null);
        if (dictionary == null) {
            return;
        }

        int statusId;
        if (invalidUserOverride) {
            statusId = R.string.chardict_source_invalid;
        } else if (userOverride) {
            statusId = R.string.chardict_source_user;
        } else {
            statusId = R.string.chardict_source_default;
        }

        statusView.setText(getString(statusId, characterCount()));
        saveButton.setEnabled(!batchMode && (dirty || !userOverride));
        importButton.setEnabled(
            !batchMode && !importBusy && !dirty
        );
    }

    private void setEditorEnabled(boolean enabled) {
        boolean editable = enabled && !batchMode && !importBusy;
        searchInput.setEnabled(enabled && !importBusy);
        characterList.setEnabled(enabled && !importBusy);
        findViewById(R.id.btn_edit_mc).setEnabled(editable);
        findViewById(R.id.btn_add_character).setEnabled(editable);
        findViewById(R.id.btn_restore_chardict).setEnabled(editable);
        setManagementBatchActionEnabled(editable);
        importButton.setEnabled(editable && !dirty);
        saveButton.setEnabled(editable && (dirty || !userOverride));
    }

    private void setManagementBatchActionEnabled(boolean enabled) {
        if (managementBatchMenuItem != null) {
            managementBatchMenuItem.setEnabled(enabled);
        }
    }

    private static JSONObject newCharacterRecord() throws Exception {
        JSONObject record = new JSONObject();
        record.put("alias", new JSONArray());
        record.put("en", "");
        record.put("zh-tw", "");
        record.put("zh-cn", "");
        record.put("school", new JSONArray());
        record.put("guild", new JSONArray());
        record.put("origin_world", new JSONArray());
        record.put("relationships", new JSONArray());
        record.put("info", "");
        record.put("description", "");
        record.put("speech_style", "");
        return record;
    }

    private void ensureMcRecord() throws Exception {
        if (dictionary.optJSONObject("mc") != null) {
            return;
        }

        JSONObject bundled = configStore.loadBundledJson(ConfigStore.CHARDICT_FILE_NAME);
        dictionary.put(
            "mc",
            new JSONObject(bundled.getJSONObject("mc").toString())
        );
        dirty = true;
    }

    private int characterCount() {
        return Math.max(0, dictionary.length() - (dictionary.has("mc") ? 1 : 0));
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
            .setTitle(R.string.unsaved_chardict_title)
            .setMessage(R.string.unsaved_chardict_message)
            .setNegativeButton(R.string.keep_editing, null)
            .setPositiveButton(R.string.discard_changes, (dialog, which) -> {
                dirty = false;
                callback.setEnabled(false);
                getOnBackPressedDispatcher().onBackPressed();
            })
            .show();
    }

    private static String textOf(EditText field) {
        return field.getText() == null ? "" : field.getText().toString().trim();
    }

    private static String rawTextOf(EditText field) {
        return field.getText() == null ? "" : field.getText().toString();
    }

    /**
     * Dynamic editor rows reuse XML ids, so Android's automatic view-state
     * restore can otherwise copy one row's text into every matching row.
     * Draft state is captured explicitly in the editor Bundle instead.
     */
    private static void disableAutomaticViewState(View view) {
        view.setSaveEnabled(false);
        view.setSaveFromParentEnabled(false);
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int index = 0; index < group.getChildCount(); index++) {
                disableAutomaticViewState(group.getChildAt(index));
            }
        }
    }

    private static String safeMessage(Throwable throwable) {
        String message = throwable.getMessage();
        return TextUtils.isEmpty(message)
            ? throwable.getClass().getSimpleName()
            : message;
    }

    private final class CharacterBatchDataSource
        implements ManagementBatchController.BatchDataSource {
        @Override
        public String initialKind() {
            return ManagementBatchController.KIND_CHARACTER;
        }

        @Override
        public Set<String> ownedKinds() {
            return Collections.singleton(ManagementBatchController.KIND_CHARACTER);
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
            List<String> names = new ArrayList<>(allNames);
            if (dictionary.has("mc")) {
                names.add(0, "mc");
            }
            for (String name : names) {
                JSONObject record = dictionary.optJSONObject(name);
                if (record == null) {
                    continue;
                }
                JSONObject payload = new JSONObject(record.toString());
                String label = "mc".equals(name)
                    ? getString(R.string.management_batch_main_character_label)
                    : getString(
                        R.string.management_batch_character_label,
                        name
                    );
                output.add(new ManagementBatchController.Item(
                    ManagementBatchController.KIND_CHARACTER,
                    name,
                    label,
                    payload
                ));
            }
            return output;
        }

        @Override
        public List<ManagementBatchController.Item> currentVisibleItems()
            throws Exception {
            List<ManagementBatchController.Item> all = snapshotItems();
            Set<String> visible = new LinkedHashSet<>(visibleNames);
            all.removeIf(item -> !visible.contains(item.canonicalId));
            return all;
        }

        @Override
        public void onBatchModeChanged(boolean enabled) {
            batchMode = enabled;
            applyFilter(textOf(searchInput));
            findViewById(R.id.btn_edit_mc).setEnabled(!enabled && dictionary != null);
            findViewById(R.id.btn_add_character).setEnabled(!enabled && dictionary != null);
            findViewById(R.id.btn_restore_chardict).setEnabled(!enabled && dictionary != null);
            saveButton.setEnabled(
                !enabled && !importBusy && dictionary != null
                    && (dirty || !userOverride)
            );
            importButton.setEnabled(
                !enabled && !importBusy && dictionary != null && !dirty
            );
            setManagementBatchActionEnabled(!enabled && dictionary != null);
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
            String prefix = ManagementBatchController.KIND_CHARACTER + ":";
            for (String key : succeededKeys) {
                if (key == null || !key.startsWith(prefix)) {
                    continue;
                }
                String name = key.substring(prefix.length());
                if (name.isEmpty() || "mc".equals(name)) {
                    continue;
                }
                if (dictionary.has(name)) {
                    dictionary.remove(name);
                    changed = true;
                }
            }
            if (changed) {
                // The durable owner already removed the records. Keep the
                // current editor snapshot in sync without reloading and
                // discarding unrelated unsaved edits.
                userOverride = true;
                invalidUserOverride = false;
                rebuildNames();
                updateStatus();
            }
        }
    }

    private final class ArrayFieldEditor {
        final String key;
        final View root;
        final View header;
        final View body;
        final TextView countView;
        final TextView indicatorView;
        final TextView errorView;
        final TextView emptyView;
        final LinearLayout itemsContainer;
        final List<ArrayElementEditor> elements = new ArrayList<>();
        Runnable draftChangeListener;
        boolean expanded;

        ArrayFieldEditor(String key, View root) {
            this.key = key;
            this.root = root;
            header = root.findViewById(R.id.header_character_array);
            body = root.findViewById(R.id.body_character_array);
            countView = root.findViewById(R.id.tv_character_array_count);
            indicatorView = root.findViewById(R.id.tv_character_array_indicator);
            errorView = root.findViewById(R.id.tv_character_array_error);
            emptyView = root.findViewById(R.id.tv_character_array_empty);
            itemsContainer = root.findViewById(R.id.container_character_array_items);

            TextView titleView = root.findViewById(R.id.tv_character_array_title);
            MaterialButton addButton = root.findViewById(
                R.id.btn_add_character_array_item
            );
            String displayName = displayFieldName(key);
            titleView.setText(displayName);
            addButton.setText(getString(
                R.string.add_array_item,
                displayName
            ));
            addButton.setContentDescription(getString(
                R.string.add_array_item,
                displayName
            ));

            header.setOnClickListener(view -> setExpanded(!expanded));
            addButton.setOnClickListener(view -> {
                try {
                    addElement(newElementValue(), true);
                } catch (Exception e) {
                    showError(getString(
                        R.string.character_field_invalid,
                        displayName,
                        safeMessage(e)
                    ));
                }
            });
        }

        private Object newElementValue() throws Exception {
            if ("alias".equals(key)) {
                JSONObject alias = new JSONObject();
                for (String field : ALIAS_FIELD_ORDER) {
                    if (!"called".equals(field)) {
                        alias.put(field, "");
                    }
                }
                return alias;
            }
            if ("relationships".equals(key)) {
                JSONObject relationship = new JSONObject();
                for (String field : RELATIONSHIP_FIELD_ORDER) {
                    relationship.put(field, "");
                }
                return relationship;
            }
            if (!elements.isEmpty()) {
                return elements.get(0).blankValue();
            }
            return "";
        }

        void addElement(Object value, boolean focus) throws Exception {
            View elementView = getLayoutInflater().inflate(
                R.layout.item_character_array_element,
                itemsContainer,
                false
            );
            ArrayElementEditor element = new ArrayElementEditor(
                this,
                value,
                elementView
            );
            disableAutomaticViewState(elementView);
            elements.add(element);
            itemsContainer.addView(elementView);
            element.deleteButton.setOnClickListener(view -> removeElement(element));
            updateSummary();
            markDraftChanged();

            if (focus) {
                setExpanded(true);
                element.focus();
            }
        }

        private void removeElement(ArrayElementEditor element) {
            elements.remove(element);
            itemsContainer.removeView(element.root);
            updateSummary();
            markDraftChanged();
        }

        void setDraftChangeListener(Runnable listener) {
            draftChangeListener = listener;
        }

        void markDraftChanged() {
            if (draftChangeListener != null) {
                draftChangeListener.run();
            }
        }

        void updateSummary() {
            countView.setText(getString(R.string.array_item_count, elements.size()));
            emptyView.setVisibility(elements.isEmpty() ? View.VISIBLE : View.GONE);
            for (int index = 0; index < elements.size(); index++) {
                elements.get(index).setPosition(index + 1);
                elements.get(index).updateSummary();
            }
        }

        Bundle draftState() {
            Bundle state = new Bundle();
            state.putInt(DRAFT_ARRAY_COUNT, elements.size());
            state.putBoolean(DRAFT_ARRAY_EXPANDED, expanded);
            for (int index = 0; index < elements.size(); index++) {
                ArrayElementEditor element = elements.get(index);
                Bundle elementState = new Bundle();
                ArrayList<String> keys = new ArrayList<>();
                ArrayList<String> values = new ArrayList<>();
                elementState.putBoolean(DRAFT_ARRAY_EXPANDED, element.expanded);
                for (ElementValueEditor valueEditor : element.valueEditors) {
                    keys.add(valueEditor.key);
                    values.add(rawTextOf(valueEditor.input));
                }
                elementState.putString(
                    DRAFT_ELEMENT_SEED,
                    element.draftSeed()
                );
                elementState.putStringArrayList(DRAFT_ELEMENT_KEYS, keys);
                elementState.putStringArrayList(DRAFT_ELEMENT_VALUES, values);
                state.putBundle(
                    DRAFT_ELEMENT_PREFIX + index,
                    elementState
                );
            }
            return state;
        }

        void restoreDraftState(Bundle state) {
            int targetCount = Math.max(
                0,
                state.getInt(DRAFT_ARRAY_COUNT, elements.size())
            );
            List<Object> seeds = draftSeeds(state, targetCount);
            if (seeds != null) {
                while (!elements.isEmpty()) {
                    removeElement(elements.get(elements.size() - 1));
                }
                for (Object seed : seeds) {
                    try {
                        addElement(seed, false);
                    } catch (Exception ignored) {
                        // Seeds are produced from the already parsed JSON
                        // model.  Keep a usable editor if an older Bundle
                        // contains a value this version cannot render.
                        try {
                            addElement(newElementValue(), false);
                        } catch (Exception ignoredAgain) {
                            break;
                        }
                    }
                }
            } else {
                while (elements.size() > targetCount) {
                    removeElement(elements.get(elements.size() - 1));
                }
                while (elements.size() < targetCount) {
                    try {
                        addElement(newElementValue(), false);
                    } catch (Exception ignored) {
                        break;
                    }
                }
            }
            for (int index = 0; index < elements.size(); index++) {
                Bundle elementState = state.getBundle(
                    DRAFT_ELEMENT_PREFIX + index
                );
                if (elementState == null) {
                    continue;
                }
                ArrayList<String> keys = elementState.getStringArrayList(
                    DRAFT_ELEMENT_KEYS
                );
                ArrayList<String> values = elementState.getStringArrayList(
                    DRAFT_ELEMENT_VALUES
                );
                if (values != null) {
                    ArrayElementEditor element = elements.get(index);
                    for (int valueIndex = 0;
                        valueIndex < element.valueEditors.size();
                        valueIndex++) {
                        ElementValueEditor valueEditor =
                            element.valueEditors.get(valueIndex);
                        int savedIndex = valueIndex;
                        if (keys != null) {
                            savedIndex = keys.indexOf(valueEditor.key);
                        }
                        if (savedIndex >= 0 && savedIndex < values.size()) {
                            valueEditor.input.setText(values.get(savedIndex));
                        }
                    }
                }
                elements.get(index).setExpanded(
                    elementState.getBoolean(DRAFT_ARRAY_EXPANDED, false)
                );
            }
            setExpanded(state.getBoolean(DRAFT_ARRAY_EXPANDED, expanded));
            updateSummary();
        }

        private List<Object> draftSeeds(Bundle state, int targetCount) {
            List<Object> seeds = new ArrayList<>();
            for (int index = 0; index < targetCount; index++) {
                Bundle elementState = state.getBundle(
                    DRAFT_ELEMENT_PREFIX + index
                );
                if (elementState == null
                    || !elementState.containsKey(DRAFT_ELEMENT_SEED)) {
                    return null;
                }
                String seedText = elementState.getString(
                    DRAFT_ELEMENT_SEED
                );
                if (TextUtils.isEmpty(seedText)) {
                    return null;
                }
                try {
                    Object seed = new JSONTokener(seedText).nextValue();
                    seeds.add(seed == null ? JSONObject.NULL : seed);
                } catch (Exception ignored) {
                    return null;
                }
            }
            return seeds;
        }

        void setExpanded(boolean expanded) {
            this.expanded = expanded;
            body.setVisibility(expanded ? View.VISIBLE : View.GONE);
            indicatorView.setText(
                expanded
                    ? R.string.array_indicator_expanded
                    : R.string.array_indicator_collapsed
            );
            header.setContentDescription(getString(
                expanded ? R.string.collapse_section : R.string.expand_section,
                displayFieldName(key)
            ));
            header.setSelected(expanded);
        }

        JSONArray toJson() throws Exception {
            JSONArray value = new JSONArray();
            for (ArrayElementEditor element : elements) {
                value.put(element.toJsonValue());
            }
            return value;
        }

        void clearErrors() {
            errorView.setText(null);
            errorView.setVisibility(View.GONE);
            for (ArrayElementEditor element : elements) {
                element.clearErrors();
            }
        }

        void showError(String message) {
            errorView.setText(message);
            errorView.setVisibility(View.VISIBLE);
            setExpanded(true);
        }

        void showValidationError(String message) {
            showError(message);

            String marker = "." + key + "[";
            int markerStart = message.indexOf(marker);
            if (markerStart < 0) return;

            int indexStart = markerStart + marker.length();
            int indexEnd = message.indexOf(']', indexStart);
            if (indexEnd < 0) return;

            try {
                int index = Integer.parseInt(message.substring(indexStart, indexEnd));
                if (index < 0 || index >= elements.size()) return;

                String property = null;
                int propertyStart = indexEnd + 1;
                if (propertyStart < message.length()
                    && message.charAt(propertyStart) == '.') {
                    propertyStart++;
                    int propertyEnd = propertyStart;
                    while (propertyEnd < message.length()) {
                        char current = message.charAt(propertyEnd);
                        if (!(Character.isLetterOrDigit(current)
                            || current == '-'
                            || current == '_')) {
                            break;
                        }
                        propertyEnd++;
                    }
                    property = message.substring(propertyStart, propertyEnd);
                }
                elements.get(index).showPropertyError(property, message);
            } catch (NumberFormatException ignored) {
                // The section-level validation message remains visible.
            }
        }
    }

    private final class ArrayElementEditor {
        final ArrayFieldEditor owner;
        final View root;
        final View header;
        final View body;
        final TextView titleView;
        final TextView summaryView;
        final TextView indicatorView;
        final MaterialButton deleteButton;
        final List<ElementValueEditor> valueEditors = new ArrayList<>();
        final boolean objectElement;
        boolean expanded;

        ArrayElementEditor(ArrayFieldEditor owner, Object value, View root)
            throws Exception {
            this.owner = owner;
            this.root = root;
            header = root.findViewById(R.id.header_character_array_item);
            body = root.findViewById(R.id.body_character_array_item);
            titleView = root.findViewById(R.id.tv_character_array_item_title);
            summaryView = root.findViewById(R.id.tv_character_array_item_summary);
            indicatorView = root.findViewById(
                R.id.tv_character_array_item_indicator
            );
            deleteButton = root.findViewById(R.id.btn_delete_character_array_item);
            LinearLayout fieldsContainer = root.findViewById(
                R.id.container_character_array_item_fields
            );

            header.setOnClickListener(view -> setExpanded(!expanded));

            objectElement = value instanceof JSONObject;
            if (objectElement) {
                JSONObject object = (JSONObject) value;
                for (String key : orderedArrayObjectKeys(owner.key, object)) {
                    Object fieldValue = object.has(key) ? object.get(key) : "";
                    ElementValueEditor editor = createElementValueEditor(
                        key,
                        fieldValue,
                        displayFieldName(key),
                        fieldsContainer,
                        "called".equals(key) && !object.has(key)
                    );
                    if ("relationships".equals(owner.key)
                        && "target".equals(key)
                        && fieldValue instanceof String) {
                        editor.input.setText(displayRelationshipTarget(
                            (String) fieldValue
                        ));
                    }
                    valueEditors.add(editor);
                }
            } else {
                valueEditors.add(createElementValueEditor(
                    owner.key,
                    value,
                    getString(R.string.array_item_value),
                    fieldsContainer,
                    false
                ));
            }

            for (ElementValueEditor editor : valueEditors) {
                editor.input.addTextChangedListener(new TextWatcher() {
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
                    }

                    @Override
                    public void afterTextChanged(Editable value) {
                        owner.markDraftChanged();
                        updateSummary();
                    }
                });
            }
            setExpanded(false);
            updateSummary();
        }

        Object toJsonValue() throws Exception {
            if (objectElement) {
                JSONObject object = new JSONObject();
                for (ElementValueEditor editor : valueEditors) {
                    Object value = readValue(editor);
                    if (editor.omitWhenEmpty
                        && value instanceof String
                        && ((String) value).isEmpty()) {
                        continue;
                    }
                    object.put(editor.key, value);
                }
                return object;
            }
            return readValue(valueEditors.get(0));
        }

        private Object readValue(ElementValueEditor editor) throws Exception {
            try {
                Object parsed = parseTypedValue(editor.originalValue, editor.input);
                if (objectElement
                    && "relationships".equals(owner.key)
                    && "target".equals(editor.key)
                    && parsed instanceof String) {
                    return storageRelationshipTarget((String) parsed);
                }
                return parsed;
            } catch (Exception e) {
                String label = objectElement
                    ? displayFieldName(editor.key)
                    : getString(R.string.array_item_value);
                editor.layout.setError(getString(
                    R.string.character_field_invalid,
                    label,
                    safeMessage(e)
                ));
                owner.setExpanded(true);
                setExpanded(true);
                editor.input.requestFocus();
                throw e;
            }
        }

        Object blankValue() throws Exception {
            if (objectElement) {
                JSONObject object = new JSONObject();
                for (ElementValueEditor editor : valueEditors) {
                    if (editor.omitWhenEmpty) {
                        continue;
                    }
                    object.put(editor.key, emptyValueFor(editor.originalValue));
                }
                return object;
            }
            return emptyValueFor(valueEditors.get(0).originalValue);
        }

        void setPosition(int position) {
            titleView.setText(getString(R.string.array_item_title, position));
            updateHeaderContentDescription();
        }

        private String displayRelationshipTarget(String value) {
            String target = value == null ? "" : value.trim();
            if (target.isEmpty()) {
                return "";
            }
            if ("mc".equals(target)) {
                return getString(R.string.management_batch_main_character_label);
            }
            if (dictionary.has(target)) {
                return target;
            }
            java.util.Iterator<String> keys = dictionary.keys();
            while (keys.hasNext()) {
                String key = keys.next();
                JSONObject record = dictionary.optJSONObject(key);
                if (record != null && target.equals(record.optString("key", ""))) {
                    return key;
                }
            }
            return target;
        }

        private String storageRelationshipTarget(String value) {
            String target = value == null ? "" : value.trim();
            if (target.equals(getString(R.string.management_batch_main_character_label))) {
                return "mc";
            }
            return value == null ? "" : value;
        }

        void focus() {
            if (!valueEditors.isEmpty()) {
                setExpanded(true);
                valueEditors.get(0).input.requestFocus();
            }
        }

        void updateSummary() {
            List<String> parts = new ArrayList<>();
            for (ElementValueEditor editor : valueEditors) {
                String value = rawTextOf(editor.input)
                    .trim()
                    .replace('\r', ' ')
                    .replace('\n', ' ');
                if (value.isEmpty()) {
                    continue;
                }
                parts.add(value);
                if (parts.size() == 2) {
                    break;
                }
            }
            if (parts.isEmpty()) {
                summaryView.setText(
                    getString(R.string.character_array_item_summary_empty)
                );
            } else {
                summaryView.setText(TextUtils.join(" · ", parts));
            }
            updateHeaderContentDescription();
        }

        void setExpanded(boolean expanded) {
            this.expanded = expanded;
            body.setVisibility(expanded ? View.VISIBLE : View.GONE);
            indicatorView.setText(
                expanded
                    ? R.string.array_indicator_expanded
                    : R.string.array_indicator_collapsed
            );
            updateHeaderContentDescription();
            header.setSelected(expanded);
        }

        private void updateHeaderContentDescription() {
            String title = titleView.getText() == null
                ? ""
                : titleView.getText().toString();
            String summary = summaryView.getText() == null
                ? ""
                : summaryView.getText().toString();
            header.setContentDescription(getString(
                expanded
                    ? R.string.collapse_character_array_item
                    : R.string.expand_character_array_item,
                title,
                summary
            ));
        }

        String draftSeed() {
            if (!objectElement) {
                return jsonSeedValue(
                    valueEditors.get(0).originalValue
                );
            }
            JSONObject seed = new JSONObject();
            for (ElementValueEditor editor : valueEditors) {
                if (editor.omitWhenEmpty) {
                    continue;
                }
                try {
                    seed.put(editor.key, editor.originalValue);
                } catch (Exception ignored) {
                    // Parsed JSON values are supported by JSONObject.  Keep
                    // raw draft text independent of this structural seed.
                }
            }
            return seed.toString();
        }

        void clearErrors() {
            for (ElementValueEditor editor : valueEditors) {
                editor.layout.setError(null);
            }
        }

        void showPropertyError(String property, String message) {
            owner.setExpanded(true);
            setExpanded(true);
            if (property != null) {
                for (ElementValueEditor editor : valueEditors) {
                    if (property.equals(editor.key)) {
                        editor.layout.setError(message);
                        editor.input.requestFocus();
                        return;
                    }
                }
            }
            focus();
        }
    }

    private static final class ElementValueEditor {
        final String key;
        final Object originalValue;
        final TextInputLayout layout;
        final EditText input;
        final boolean omitWhenEmpty;

        ElementValueEditor(
            String key,
            Object originalValue,
            TextInputLayout layout,
            EditText input,
            boolean omitWhenEmpty
        ) {
            this.key = key;
            this.originalValue = originalValue;
            this.layout = layout;
            this.input = input;
            this.omitWhenEmpty = omitWhenEmpty;
        }
    }

    private final class CharacterFieldEditor {
        final String key;
        final Object originalValue;
        final TextInputLayout layout;
        final EditText input;
        final boolean readOnly;
        final ArrayFieldEditor arrayEditor;

        CharacterFieldEditor(
            String key,
            Object originalValue,
            TextInputLayout layout,
            EditText input,
            boolean readOnly
        ) {
            this.key = key;
            this.originalValue = originalValue;
            this.layout = layout;
            this.input = input;
            this.readOnly = readOnly;
            this.arrayEditor = null;
        }

        CharacterFieldEditor(
            String key,
            Object originalValue,
            ArrayFieldEditor arrayEditor
        ) {
            this.key = key;
            this.originalValue = originalValue;
            this.layout = null;
            this.input = null;
            this.readOnly = false;
            this.arrayEditor = arrayEditor;
        }
    }

    private final class CharacterAdapter extends ArrayAdapter<String> {
        CharacterAdapter(Context context, List<String> names) {
            super(context, R.layout.item_dictionary_entry,
                R.id.tv_dictionary_entry_title, names);
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
            String name = getItem(position);
            if (name == null) {
                name = "";
            }
            TextView title = view.findViewById(R.id.tv_dictionary_entry_title);
            TextView subtitle = view.findViewById(R.id.tv_dictionary_entry_subtitle);
            MaterialCheckBox check = view.findViewById(R.id.check_dictionary_entry);
            title.setText(characterTitle(name));
            subtitle.setText(characterSubtitle(name));
            check.setVisibility(batchMode ? View.VISIBLE : View.GONE);
            check.setChecked(batchMode && ManagementBatchSelection.contains(
                ManagementBatchController.KIND_CHARACTER + ":" + name
            ));
            check.setContentDescription(getString(
                R.string.management_home_batch_select,
                characterTitle(name)
            ));
            return view;
        }

        private String characterTitle(String name) {
            if ("mc".equals(name)) {
                return getString(R.string.management_batch_main_character_label);
            }
            JSONObject record = dictionary == null
                ? null
                : dictionary.optJSONObject(name);
            String localized = localizedValue(record);
            return localized.isEmpty() ? name : localized;
        }

        private String characterSubtitle(String name) {
            JSONObject record = dictionary == null
                ? null
                : dictionary.optJSONObject(name);
            if (record == null) {
                return getString(R.string.invalid_character_record);
            }

            List<String> parts = new ArrayList<>();
            if ("mc".equals(name)) {
                parts.add(getString(R.string.mc_settings));
            } else if (!name.isEmpty()) {
                parts.add(name);
            }

            JSONArray aliases = record.optJSONArray("alias");
            if (aliases != null && aliases.length() > 0) {
                parts.add(getString(R.string.alias_count, aliases.length()));
            }

            return parts.isEmpty()
                ? getString(R.string.no_localized_name)
                : TextUtils.join(" · ", parts);
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
