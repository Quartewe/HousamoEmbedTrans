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

    private static final String[] CHARACTER_FIELD_ORDER = {
        "alias",
        "en",
        "zh-tw",
        "zh-cn",
        "school",
        "guild",
        "origin_world",
        "relationships",
        "info",
        "description",
        "speech_style"
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

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
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
        if (dictionary == null) {
            return;
        }

        View editorView = getLayoutInflater().inflate(
            R.layout.dialog_character_editor,
            null,
            false
        );
        EditText nameInput = editorView.findViewById(R.id.et_character_name);
        TextInputLayout nameLayout = editorView.findViewById(R.id.til_character_name);
        LinearLayout fieldsContainer = editorView.findViewById(
            R.id.character_fields_container
        );
        boolean existing = originalName != null;
        boolean editingMc = "mc".equals(originalName);
        List<CharacterFieldEditor> fieldEditors;

        try {
            JSONObject record;
            if (existing) {
                nameInput.setText(originalName);
                setReadOnly(nameInput);
                nameLayout.setHelperText(getString(R.string.character_key_read_only));
                record = dictionary.getJSONObject(originalName);
            } else {
                nameLayout.setHelperText(getString(R.string.character_key_new_hint));
                record = newCharacterRecord();
            }
            fieldEditors = createFieldEditors(record, fieldsContainer);
        } catch (Exception e) {
            Toast.makeText(
                this,
                getString(R.string.chardict_record_invalid, safeMessage(e)),
                Toast.LENGTH_LONG
            ).show();
            return;
        }

        final boolean[] draftDirty = {false};
        attachDraftChangeWatchers(fieldEditors, () -> draftDirty[0] = true);

        MaterialAlertDialogBuilder builder = new MaterialAlertDialogBuilder(this)
            .setTitle(
                editingMc
                    ? R.string.edit_mc
                    : (existing ? R.string.edit_character : R.string.add_character)
            )
            .setView(editorView)
            .setNegativeButton(R.string.cancel_action, null)
            .setPositiveButton(R.string.save_character, null);

        if (existing && !editingMc) {
            builder.setNeutralButton(R.string.pending_process_move, null);
        }

        AlertDialog dialog = builder.create();
        dialog.setCanceledOnTouchOutside(false);
        dialog.setOnKeyListener((ignored, keyCode, event) -> {
            if (keyCode != KeyEvent.KEYCODE_BACK) {
                return false;
            }
            if (event.getAction() == KeyEvent.ACTION_UP) {
                handleCharacterDraftBack(dialog, draftDirty[0]);
            }
            return true;
        });
        dialog.setOnShowListener(ignored -> {
            dialog.getButton(AlertDialog.BUTTON_NEGATIVE).setOnClickListener(
                view -> handleCharacterDraftBack(dialog, draftDirty[0])
            );
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(view -> {
                saveCharacterFromDialog(
                    dialog,
                    originalName,
                    nameInput,
                    fieldEditors
                );
            });

            if (existing && !editingMc) {
                dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener(view -> {
                    moveCharacterToPending(dialog, originalName);
                });
            }
        });
        dialog.show();
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
            Object value = record.get(key);
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
            boolean readOnly = "info".equals(key);

            layout.setHint(displayFieldName(key));
            configureFieldInput(key, value, layout, input, readOnly);
            input.setText(formatFieldValue(value));
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
        container.addView(fieldView);

        for (int index = 0; index < value.length(); index++) {
            editor.addElement(value.get(index), false);
        }
        editor.updateSummary();
        editor.setExpanded(false);
        return editor;
    }

    private List<String> orderedFieldKeys(JSONObject record) {
        Set<String> keys = new LinkedHashSet<>();
        for (String preferred : CHARACTER_FIELD_ORDER) {
            if (record.has(preferred)) {
                keys.add(preferred);
            }
        }

        Iterator<String> remaining = record.keys();
        while (remaining.hasNext()) {
            keys.add(remaining.next());
        }
        return new ArrayList<>(keys);
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
            || "description".equals(key)
            || "speech_style".equals(key);

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
            case "alias": resourceId = R.string.field_alias; break;
            case "name": resourceId = R.string.field_name; break;
            case "en": resourceId = R.string.field_en; break;
            case "zh-tw": resourceId = R.string.field_zh_tw; break;
            case "zh-cn": resourceId = R.string.field_zh_cn; break;
            case "called": resourceId = R.string.field_called; break;
            case "school": resourceId = R.string.field_school; break;
            case "guild": resourceId = R.string.field_guild; break;
            case "origin_world": resourceId = R.string.field_origin_world; break;
            case "relationships": resourceId = R.string.field_relationships; break;
            case "target": resourceId = R.string.field_target; break;
            case "type": resourceId = R.string.field_type; break;
            case "info": resourceId = R.string.field_info; break;
            case "description": resourceId = R.string.field_description; break;
            case "speech_style": resourceId = R.string.field_speech_style; break;
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
        if (value instanceof JSONArray || value instanceof JSONObject) {
            throw new IllegalArgumentException(
                "nested arrays or objects are not supported in " + key
            );
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
        layout.setHint(hint);
        configureFieldInput(key, value, layout, input, false);
        input.setText(formatFieldValue(value));
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
        MaterialAlertDialogBuilder dialog = new MaterialAlertDialogBuilder(this)
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
        new MaterialAlertDialogBuilder(this)
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
            userOverride = true;
            invalidUserOverride = false;
            dirty = false;
            updateStatus();
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
        new MaterialAlertDialogBuilder(this)
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

        new MaterialAlertDialogBuilder(this)
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

            if (value instanceof JSONArray) {
                throw new IllegalArgumentException(
                    "nested arrays are not supported in " + owner.key
                );
            }

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
            for (String key : dictionary.keySet()) {
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
