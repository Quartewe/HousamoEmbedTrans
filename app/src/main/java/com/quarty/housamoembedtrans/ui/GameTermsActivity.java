package com.quarty.housamoembedtrans.ui;

import com.quarty.housamoembedtrans.R;
import com.quarty.housamoembedtrans.storage.config.ConfigStore;

import android.content.Context;
import android.os.Bundle;
import android.text.Editable;
import android.text.InputType;
import android.text.TextUtils;
import android.text.TextWatcher;
import android.view.Gravity;
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
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;

import com.google.android.material.appbar.MaterialToolbar;
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

/** Searchable editor for the user-owned files/gameterms.json override. */
public final class GameTermsActivity extends AppCompatActivity {

    private static final String[] TERM_FIELD_ORDER = {
        "en",
        "zh-tw",
        "zh-cn",
        "description"
    };

    private ConfigStore configStore;
    private JSONObject dictionary;
    private boolean dirty;
    private boolean userOverride;
    private boolean invalidUserOverride;

    private EditText searchInput;
    private TextView statusView;
    private ListView termList;
    private Button saveButton;

    private final List<String> allTerms = new ArrayList<>();
    private final List<String> visibleTerms = new ArrayList<>();
    private GameTermAdapter adapter;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_game_terms);
        SystemBarInsets.apply(findViewById(R.id.root_game_terms));

        MaterialToolbar toolbar = findViewById(R.id.toolbar_game_terms);
        toolbar.setNavigationOnClickListener(
            view -> getOnBackPressedDispatcher().onBackPressed()
        );

        configStore = new ConfigStore(this);
        searchInput = findViewById(R.id.et_gameterms_search);
        statusView = findViewById(R.id.tv_gameterms_status);
        termList = findViewById(R.id.list_game_terms);
        saveButton = findViewById(R.id.btn_save_gameterms);

        adapter = new GameTermAdapter(this, visibleTerms);
        termList.setAdapter(adapter);
        termList.setEmptyView(findViewById(R.id.tv_game_terms_empty));
        termList.setOnItemClickListener((parent, view, position, id) -> {
            editTerm(visibleTerms.get(position));
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
        saveButton.setOnClickListener(view -> saveDictionary());

        getOnBackPressedDispatcher().addCallback(this, new OnBackPressedCallback(true) {
            @Override
            public void handleOnBackPressed() {
                GameTermsActivity.this.handleBackPressed(this);
            }
        });

        loadDictionary();
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
        } catch (Exception e) {
            dictionary = null;
            statusView.setText(getString(
                R.string.gameterms_load_failed,
                safeMessage(e)
            ));
            setEditorEnabled(false);
        }
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
        if (dictionary == null) {
            return;
        }

        View editorView = getLayoutInflater().inflate(
            R.layout.dialog_game_term_editor,
            null,
            false
        );
        EditText keyInput = editorView.findViewById(R.id.et_game_term_key);
        TextInputLayout keyLayout = editorView.findViewById(
            R.id.til_game_term_key
        );
        LinearLayout fieldsContainer = editorView.findViewById(
            R.id.container_game_term_fields
        );
        boolean existing = originalKey != null;
        JSONObject originalRecord;
        List<TermFieldEditor> fieldEditors;

        try {
            if (existing) {
                keyInput.setText(originalKey);
                setReadOnly(keyInput);
                keyLayout.setHelperText(getString(R.string.game_term_key_read_only));
                originalRecord = dictionary.getJSONObject(originalKey);
            } else {
                keyLayout.setHelperText(getString(R.string.game_term_key_new_hint));
                originalRecord = newTermRecord();
            }
            fieldEditors = createFieldEditors(originalRecord, fieldsContainer);
        } catch (Exception e) {
            Toast.makeText(
                this,
                getString(R.string.gameterms_record_invalid, safeMessage(e)),
                Toast.LENGTH_LONG
            ).show();
            return;
        }

        MaterialAlertDialogBuilder builder = new MaterialAlertDialogBuilder(this)
            .setTitle(existing ? R.string.edit_game_term : R.string.add_game_term)
            .setView(editorView)
            .setNegativeButton(R.string.cancel_action, null)
            .setPositiveButton(R.string.save_game_term, null);

        if (existing) {
            builder.setNeutralButton(R.string.delete_game_term, null);
        }

        AlertDialog dialog = builder.create();
        dialog.setOnShowListener(ignored -> {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(view -> {
                saveTermFromDialog(
                    dialog,
                    originalKey,
                    originalRecord,
                    keyInput,
                    fieldEditors
                );
            });

            if (existing) {
                dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener(
                    view -> confirmDelete(dialog, originalKey)
                );
            }
        });
        dialog.show();
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

            layout.setHint(displayFieldName(key));
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

    private void confirmDelete(AlertDialog editorDialog, String key) {
        new MaterialAlertDialogBuilder(this)
            .setTitle(R.string.delete_game_term_title)
            .setMessage(getString(R.string.delete_game_term_message, key))
            .setNegativeButton(R.string.cancel_action, null)
            .setPositiveButton(R.string.delete_game_term, (dialog, which) -> {
                dictionary.remove(key);
                dirty = true;
                rebuildTerms();
                updateStatus();
                editorDialog.dismiss();
            })
            .show();
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
        new MaterialAlertDialogBuilder(this)
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
        saveButton.setEnabled(dirty || !userOverride);
    }

    private void setEditorEnabled(boolean enabled) {
        searchInput.setEnabled(enabled);
        termList.setEnabled(enabled);
        findViewById(R.id.btn_add_game_term).setEnabled(enabled);
        findViewById(R.id.btn_restore_gameterms).setEnabled(enabled);
        saveButton.setEnabled(enabled);
    }

    private void handleBackPressed(OnBackPressedCallback callback) {
        if (!dirty) {
            callback.setEnabled(false);
            getOnBackPressedDispatcher().onBackPressed();
            return;
        }

        new MaterialAlertDialogBuilder(this)
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
            case "en": return getString(R.string.field_en);
            case "zh-tw": return getString(R.string.field_zh_tw);
            case "zh-cn": return getString(R.string.field_zh_cn);
            case "description": return getString(R.string.field_description);
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
            super(context, android.R.layout.simple_list_item_2, android.R.id.text1, terms);
        }

        @Override
        public View getView(int position, View convertView, ViewGroup parent) {
            View view = super.getView(position, convertView, parent);
            String term = getItem(position);
            TextView title = view.findViewById(android.R.id.text1);
            TextView subtitle = view.findViewById(android.R.id.text2);
            title.setText(term);
            subtitle.setText(termSubtitle(term));
            return view;
        }

        private String termSubtitle(String term) {
            JSONObject record = dictionary.optJSONObject(term);
            if (record == null) {
                return getString(R.string.gameterms_record_invalid, term);
            }

            List<String> parts = new ArrayList<>();
            String zhCn = record.optString("zh-cn", "");
            String en = record.optString("en", "");
            if (!zhCn.isEmpty()) parts.add(zhCn);
            if (!en.isEmpty()) parts.add(en);
            return parts.isEmpty()
                ? getString(R.string.no_localized_term)
                : TextUtils.join(" · ", parts);
        }
    }
}
