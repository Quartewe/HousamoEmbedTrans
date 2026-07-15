package com.quarty.housamoembedtrans;

import android.content.Context;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextUtils;
import android.text.TextWatcher;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.OnBackPressedCallback;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;

import org.json.JSONArray;
import org.json.JSONObject;

import java.text.Collator;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;

/** Searchable editor for the user-owned files/chardict.json override. */
public final class CharacterDictionaryActivity extends AppCompatActivity {

    private ConfigStore configStore;
    private JSONObject dictionary;
    private boolean dirty;
    private boolean userOverride;
    private boolean invalidUserOverride;

    private EditText searchInput;
    private TextView statusView;
    private ListView characterList;
    private Button saveButton;

    private final List<String> allNames = new ArrayList<>();
    private final List<String> visibleNames = new ArrayList<>();
    private CharacterAdapter adapter;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_character_dictionary);

        configStore = new ConfigStore(this);
        searchInput = findViewById(R.id.et_character_search);
        statusView = findViewById(R.id.tv_chardict_status);
        characterList = findViewById(R.id.list_characters);
        saveButton = findViewById(R.id.btn_save_chardict);

        adapter = new CharacterAdapter(this, visibleNames);
        characterList.setAdapter(adapter);
        characterList.setEmptyView(findViewById(R.id.tv_character_empty));
        characterList.setOnItemClickListener((parent, view, position, id) -> {
            editCharacter(visibleNames.get(position));
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
        saveButton.setOnClickListener(view -> saveDictionary());

        getOnBackPressedDispatcher().addCallback(this, new OnBackPressedCallback(true) {
            @Override
            public void handleOnBackPressed() {
                CharacterDictionaryActivity.this.handleBackPressed(this);
            }
        });

        loadDictionary();
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
        } catch (Exception e) {
            dictionary = null;
            statusView.setText(getString(
                R.string.chardict_load_failed,
                safeMessage(e)
            ));
            setEditorEnabled(false);
        }
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

        adapter.notifyDataSetChanged();
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
        EditText jsonInput = editorView.findViewById(R.id.et_character_record_json);
        boolean existing = originalName != null;
        boolean editingMc = "mc".equals(originalName);

        try {
            if (existing) {
                nameInput.setText(originalName);
                jsonInput.setText(dictionary.getJSONObject(originalName).toString(2));
                if (editingMc) {
                    nameInput.setEnabled(false);
                }
            } else {
                jsonInput.setText(newCharacterRecord().toString(2));
            }
        } catch (Exception e) {
            Toast.makeText(
                this,
                getString(R.string.chardict_record_invalid, safeMessage(e)),
                Toast.LENGTH_LONG
            ).show();
            return;
        }

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
            builder.setNeutralButton(R.string.delete_character, null);
        }

        AlertDialog dialog = builder.create();
        dialog.setOnShowListener(ignored -> {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(view -> {
                saveCharacterFromDialog(
                    dialog,
                    originalName,
                    nameInput,
                    jsonInput
                );
            });

            if (existing && !editingMc) {
                dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener(view -> {
                    confirmDelete(dialog, originalName);
                });
            }
        });
        dialog.show();
    }

    private void saveCharacterFromDialog(
        AlertDialog dialog,
        String originalName,
        EditText nameInput,
        EditText jsonInput
    ) {
        String newName = textOf(nameInput);
        if (newName.isEmpty()) {
            nameInput.setError(getString(R.string.error_required));
            nameInput.requestFocus();
            return;
        }

        if (!newName.equals(originalName) && dictionary.has(newName)) {
            nameInput.setError(getString(R.string.character_already_exists));
            nameInput.requestFocus();
            return;
        }

        try {
            JSONObject record = new JSONObject(textOf(jsonInput));
            ConfigStore.validateCharacterRecord(newName, record);

            if (originalName != null && !originalName.equals(newName)) {
                dictionary.remove(originalName);
            }
            dictionary.put(newName, record);

            dirty = true;
            rebuildNames();
            updateStatus();
            dialog.dismiss();
        } catch (Exception e) {
            jsonInput.setError(getString(
                R.string.chardict_record_invalid,
                safeMessage(e)
            ));
            jsonInput.requestFocus();
        }
    }

    private void confirmDelete(AlertDialog editorDialog, String name) {
        new MaterialAlertDialogBuilder(this)
            .setTitle(R.string.delete_character_title)
            .setMessage(getString(R.string.delete_character_message, name))
            .setNegativeButton(R.string.cancel_action, null)
            .setPositiveButton(R.string.delete_character, (dialog, which) -> {
                dictionary.remove(name);
                dirty = true;
                rebuildNames();
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
        saveButton.setEnabled(dirty || !userOverride);
    }

    private void setEditorEnabled(boolean enabled) {
        searchInput.setEnabled(enabled);
        characterList.setEnabled(enabled);
        findViewById(R.id.btn_edit_mc).setEnabled(enabled);
        findViewById(R.id.btn_add_character).setEnabled(enabled);
        findViewById(R.id.btn_restore_chardict).setEnabled(enabled);
        saveButton.setEnabled(enabled);
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

    private static String safeMessage(Throwable throwable) {
        String message = throwable.getMessage();
        return TextUtils.isEmpty(message)
            ? throwable.getClass().getSimpleName()
            : message;
    }

    private final class CharacterAdapter extends ArrayAdapter<String> {
        CharacterAdapter(Context context, List<String> names) {
            super(context, android.R.layout.simple_list_item_2, android.R.id.text1, names);
        }

        @Override
        public View getView(int position, View convertView, ViewGroup parent) {
            View view = super.getView(position, convertView, parent);
            String name = getItem(position);
            TextView title = view.findViewById(android.R.id.text1);
            TextView subtitle = view.findViewById(android.R.id.text2);
            title.setText(name);
            subtitle.setText(characterSubtitle(name));
            return view;
        }

        private String characterSubtitle(String name) {
            JSONObject record = dictionary.optJSONObject(name);
            if (record == null) {
                return getString(R.string.invalid_character_record);
            }

            List<String> parts = new ArrayList<>();
            String zhCn = record.optString("zh-cn", "");
            String en = record.optString("en", "");
            if (!zhCn.isEmpty()) parts.add(zhCn);
            if (!en.isEmpty()) parts.add(en);

            JSONArray aliases = record.optJSONArray("alias");
            if (aliases != null && aliases.length() > 0) {
                parts.add(getString(R.string.alias_count, aliases.length()));
            }

            return parts.isEmpty()
                ? getString(R.string.no_localized_name)
                : TextUtils.join(" · ", parts);
        }
    }
}
