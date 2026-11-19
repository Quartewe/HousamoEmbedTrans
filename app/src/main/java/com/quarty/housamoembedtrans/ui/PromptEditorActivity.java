package com.quarty.housamoembedtrans.ui;

import com.quarty.housamoembedtrans.R;
import com.quarty.housamoembedtrans.storage.config.PromptStore;

import android.os.Bundle;
import android.content.res.ColorStateList;
import android.text.Editable;
import android.text.TextUtils;
import android.text.TextWatcher;
import android.view.View;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

import com.google.android.material.appbar.MaterialToolbar;
import com.google.android.material.button.MaterialButton;

import java.io.IOException;
import java.util.EnumMap;
import java.util.Map;

/** Edits independent prompt drafts while keeping each output contract fixed. */
public final class PromptEditorActivity extends AppCompatActivity {
    private static final String STATE_ACTIVE_KIND =
        "prompt_editor_state_active_kind";
    private static final String STATE_DRAFTS =
        "prompt_editor_state_drafts";
    private static final String STATE_COMMITTED =
        "prompt_editor_state_committed";
    private static final String STATE_ROLE = "role";
    private static final String STATE_STYLE = "style";
    private static final String STATE_CONTEXT = "context";
    private static final String STATE_CUSTOM_PROMPT = "custom_prompt";

    private enum Tab {
        TRANSLATION(PromptStore.TRANSLATION, R.id.btn_prompt_translation,
            R.string.settings_prompt_translation_tab, R.string.settings_rebuild_prompt_translation_constraints),
        SUMMARY(PromptStore.SUMMARY, R.id.btn_prompt_summary,
            R.string.settings_prompt_summary_tab, R.string.settings_rebuild_prompt_summary_constraints),
        CONTINUATION(PromptStore.CONTINUATION, R.id.btn_prompt_continuation,
            R.string.settings_prompt_continuation_tab, R.string.settings_rebuild_prompt_continuation_constraints);

        final String kind;
        final int buttonId;
        final int label;
        final int constraints;

        Tab(String kind, int buttonId, int label, int constraints) {
            this.kind = kind;
            this.buttonId = buttonId;
            this.label = label;
            this.constraints = constraints;
        }
    }

    private static final class DraftValues {
        String role;
        String style;
        String context;
        String customPrompt;

        DraftValues(String role, String style, String context, String customPrompt) {
            this.role = role == null ? "" : role;
            this.style = style == null ? "" : style;
            this.context = context == null ? "" : context;
            this.customPrompt = customPrompt == null ? "" : customPrompt;
        }

        DraftValues(PromptStore.PromptDocument document) {
            this(document.getRole(), document.getStyle(), document.getContext(), document.getCustomPrompt());
        }

        DraftValues copy() {
            return new DraftValues(role, style, context, customPrompt);
        }
    }

    private PromptStore promptStore;
    private final Map<Tab, DraftValues> committed = new EnumMap<>(Tab.class);
    private final Map<Tab, DraftValues> drafts = new EnumMap<>(Tab.class);
    private Tab activeTab = Tab.TRANSLATION;
    private EditText role;
    private EditText style;
    private EditText context;
    private EditText customPrompt;
    private View customPromptSection;
    private TextView kindTitle;
    private TextView readOnly;
    private MaterialButton saveButton;
    private boolean suppressDraftWatchers;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_prompt_editor);
        SystemBarInsets.apply(findViewById(R.id.root_prompt_editor));

        promptStore = new PromptStore(this);
        if (!loadDocuments()) {
            finish();
            return;
        }
        role = findViewById(R.id.et_prompt_role);
        style = findViewById(R.id.et_prompt_style);
        context = findViewById(R.id.et_prompt_context);
        customPrompt = findViewById(R.id.et_prompt_custom);
        customPromptSection = findViewById(R.id.prompt_custom_section);
        kindTitle = findViewById(R.id.tv_prompt_kind_title);
        readOnly = findViewById(R.id.tv_prompt_readonly);
        saveButton = findViewById(R.id.btn_prompt_save);
        TextWatcher draftWatcher = new TextWatcher() {
            @Override
            public void beforeTextChanged(
                CharSequence source,
                int start,
                int count,
                int after
            ) {
            }

            @Override
            public void onTextChanged(
                CharSequence source,
                int start,
                int before,
                int count
            ) {
                if (!suppressDraftWatchers) {
                    captureActiveDraft();
                }
            }

            @Override
            public void afterTextChanged(Editable editable) {
            }
        };
        role.addTextChangedListener(draftWatcher);
        style.addTextChangedListener(draftWatcher);
        context.addTextChangedListener(draftWatcher);
        customPrompt.addTextChangedListener(draftWatcher);

        MaterialToolbar toolbar = findViewById(R.id.toolbar_prompt_editor);
        toolbar.setNavigationOnClickListener(view -> onBackPressed());
        findViewById(R.id.btn_prompt_discard).setOnClickListener(
            view -> discardActiveDraft()
        );
        findViewById(R.id.btn_prompt_reset).setOnClickListener(
            view -> confirmReset()
        );
        saveButton.setOnClickListener(view -> saveActiveDraft());

        restoreDraftState(savedInstanceState);
        for (Tab tab : Tab.values()) {
            findViewById(tab.buttonId).setOnClickListener(view -> selectTab(tab));
        }
        showActiveDraft();
    }

    @Override
    protected void onSaveInstanceState(Bundle outState) {
        captureActiveDraft();
        outState.putString(STATE_ACTIVE_KIND, activeTab.kind);
        outState.putBundle(STATE_DRAFTS, saveDraftValues(drafts));
        outState.putBundle(STATE_COMMITTED, saveDraftValues(committed));
        super.onSaveInstanceState(outState);
    }

    @Override
    public void onBackPressed() {
        captureActiveDraft();
        if (drafts.keySet().stream().anyMatch(this::isDirty)) {
            new UiMaterialAlertDialogBuilder(this)
                .setTitle(R.string.settings_prompt_discard_title)
                .setMessage(R.string.settings_prompt_discard_message)
                .setNegativeButton(R.string.cancel_action, null)
                .setPositiveButton(
                    R.string.settings_prompt_discard,
                    (dialog, which) -> {
                        discardAllDrafts();
                        finish();
                    }
                )
                .show();
            return;
        }
        super.onBackPressed();
    }

    private boolean loadDocuments() {
        try {
            for (Tab tab : Tab.values()) {
                PromptStore.PromptDocument document = promptStore.load(tab.kind);
                DraftValues values = new DraftValues(document);
                committed.put(tab, values.copy());
                drafts.put(tab, values);
            }
            return true;
        } catch (IOException | IllegalArgumentException error) {
            Toast.makeText(
                this,
                getString(R.string.config_load_failed, safeMessage(error)),
                Toast.LENGTH_LONG
            ).show();
            return false;
        }
    }

    /** Restores both in-memory drafts and the comparison baseline only. */
    private void restoreDraftState(Bundle savedInstanceState) {
        if (savedInstanceState == null) {
            return;
        }
        Bundle restoredCommitted = savedInstanceState.getBundle(STATE_COMMITTED);
        Bundle restoredDrafts = savedInstanceState.getBundle(STATE_DRAFTS);
        String restoredKind = savedInstanceState.getString(STATE_ACTIVE_KIND);
        for (Tab tab : Tab.values()) {
            DraftValues committedValues = readDraftValues(restoredCommitted, tab.kind);
            if (committedValues != null) {
                committed.put(tab, committedValues);
            }
            DraftValues draftValues = readDraftValues(restoredDrafts, tab.kind);
            if (draftValues != null) {
                drafts.put(tab, draftValues);
            }
            if (tab.kind.equals(restoredKind)) activeTab = tab;
        }
    }

    private Bundle saveDraftValues(Map<Tab, DraftValues> values) {
        Bundle result = new Bundle();
        for (Tab tab : Tab.values()) {
            DraftValues draft = values.get(tab);
            if (draft == null) {
                continue;
            }
            Bundle saved = new Bundle();
            saved.putString(STATE_ROLE, draft.role);
            saved.putString(STATE_STYLE, draft.style);
            saved.putString(STATE_CONTEXT, draft.context);
            if (tab == Tab.CONTINUATION) saved.putString(STATE_CUSTOM_PROMPT, draft.customPrompt);
            result.putBundle(tab.kind, saved);
        }
        return result;
    }

    private DraftValues readDraftValues(Bundle values, String kind) {
        if (values == null) {
            return null;
        }
        Bundle saved = values.getBundle(kind);
        if (saved == null) {
            return null;
        }
        return new DraftValues(
            saved.getString(STATE_ROLE),
            saved.getString(STATE_STYLE),
            saved.getString(STATE_CONTEXT),
            saved.getString(STATE_CUSTOM_PROMPT)
        );
    }

    private void showActiveDraft() {
        DraftValues values = drafts.get(activeTab);
        if (values == null) {
            return;
        }
        suppressDraftWatchers = true;
        role.setText(values.role);
        style.setText(values.style);
        context.setText(values.context);
        boolean showCustomPrompt = activeTab == Tab.CONTINUATION
            && getResources().getBoolean(R.bool.show_continuation_custom_prompt_controls);
        customPromptSection.setVisibility(showCustomPrompt ? View.VISIBLE : View.GONE);
        customPrompt.setText(values.customPrompt);
        suppressDraftWatchers = false;
        kindTitle.setText(activeTab.label);
        readOnly.setText(activeTab.constraints);
        saveButton.setText(
            isDirty(activeTab)
                ? R.string.settings_prompt_save
                : R.string.settings_prompt_saved
        );
        for (Tab tab : Tab.values()) {
            setPromptTabState(findViewById(tab.buttonId), tab == activeTab);
        }
    }

    private void selectTab(Tab tab) {
        if (tab == activeTab) {
            return;
        }
        captureActiveDraft();
        activeTab = tab;
        showActiveDraft();
    }

    private void setPromptTabState(MaterialButton tab, boolean selected) {
        tab.setBackgroundTintList(ColorStateList.valueOf(getColor(
            selected
                ? R.color.het_primary_container
                : android.R.color.transparent
        )));
        tab.setTextColor(getColor(
            selected
                ? R.color.het_on_primary_container
                : R.color.het_on_surface_muted
        ));
        tab.setStrokeWidth(0);
    }

    private void captureActiveDraft() {
        if (role == null || style == null || context == null) {
            return;
        }
        drafts.put(activeTab, new DraftValues(
            textOf(role),
            textOf(style),
            textOf(context),
            activeTab == Tab.CONTINUATION ? textOf(customPrompt) : ""
        ));
        if (saveButton != null) {
            saveButton.setText(
                isDirty(activeTab)
                    ? R.string.settings_prompt_save
                    : R.string.settings_prompt_saved
            );
        }
    }

    private void saveActiveDraft() {
        captureActiveDraft();
        DraftValues values = drafts.get(activeTab);
        try {
            PromptStore.PromptDocument document = toDocument(values);
            promptStore.save(activeTab.kind, document);
            committed.put(activeTab, values.copy());
            drafts.put(activeTab, values.copy());
            saveButton.setText(R.string.settings_prompt_saved);
            Toast.makeText(
                this,
                getString(
                    R.string.settings_prompt_saved_message,
                    getString(activeTab.label)
                ),
                Toast.LENGTH_SHORT
            ).show();
        } catch (IllegalArgumentException error) {
            showPromptError(error);
        } catch (IOException error) {
            Toast.makeText(
                this,
                getString(R.string.config_save_failed, safeMessage(error)),
                Toast.LENGTH_LONG
            ).show();
        }
    }

    private void discardActiveDraft() {
        captureActiveDraft();
        if (!isDirty(activeTab)) {
            Toast.makeText(
                this,
                R.string.settings_prompt_no_changes,
                Toast.LENGTH_SHORT
            ).show();
            return;
        }
        new UiMaterialAlertDialogBuilder(this)
            .setTitle(R.string.settings_prompt_discard_title)
            .setMessage(R.string.settings_prompt_discard_message)
            .setNegativeButton(R.string.cancel_action, null)
            .setPositiveButton(
                R.string.settings_prompt_discard,
                (dialog, which) -> {
                    drafts.put(activeTab, committed.get(activeTab).copy());
                    showActiveDraft();
                    Toast.makeText(
                        this,
                        getString(
                            R.string.settings_prompt_discarded_message,
                            getString(activeTab.label)
                        ),
                        Toast.LENGTH_SHORT
                    ).show();
                }
            )
            .show();
    }

    private void confirmReset() {
        new UiMaterialAlertDialogBuilder(this)
            .setTitle(R.string.settings_prompt_reset_title)
            .setMessage(R.string.settings_prompt_reset_message)
            .setNegativeButton(R.string.cancel_action, null)
            .setPositiveButton(
                R.string.settings_prompt_reset,
                (dialog, which) -> resetActivePrompt()
            )
            .show();
    }

    private void resetActivePrompt() {
        try {
            PromptStore.PromptDocument document = promptStore.loadDefaults(activeTab.kind);
            promptStore.save(activeTab.kind, document);
            DraftValues values = new DraftValues(document);
            committed.put(activeTab, values.copy());
            drafts.put(activeTab, values);
            showActiveDraft();
            Toast.makeText(
                this,
                getString(
                    R.string.settings_prompt_reset_message_done,
                    getString(activeTab.label)
                ),
                Toast.LENGTH_SHORT
            ).show();
        } catch (IOException | IllegalArgumentException error) {
            Toast.makeText(
                this,
                getString(R.string.config_save_failed, safeMessage(error)),
                Toast.LENGTH_LONG
            ).show();
        }
    }

    private void discardAllDrafts() {
        for (Tab tab : Tab.values()) {
            drafts.put(tab, committed.get(tab).copy());
        }
    }

    private boolean isDirty(Tab tab) {
        DraftValues draft = drafts.get(tab);
        DraftValues saved = committed.get(tab);
        return draft != null && saved != null
            && (!draft.role.equals(saved.role)
                || !draft.style.equals(saved.style)
                || !draft.context.equals(saved.context)
                || !draft.customPrompt.equals(saved.customPrompt));
    }

    private PromptStore.PromptDocument toDocument(DraftValues values) {
        if (values == null) {
            throw new IllegalArgumentException("prompt draft is missing");
        }
        return new PromptStore.PromptDocument(
            values.role,
            values.style,
            values.context,
            values.customPrompt
        );
    }

    private void showPromptError(IllegalArgumentException error) {
        Toast.makeText(
            this,
            getString(R.string.settings_error_required),
            Toast.LENGTH_LONG
        ).show();
    }

    private static String textOf(EditText field) {
        return field == null || field.getText() == null
            ? ""
            : field.getText().toString();
    }

    private static String safeMessage(Throwable error) {
        String message = error.getMessage();
        return TextUtils.isEmpty(message)
            ? error.getClass().getSimpleName()
            : message;
    }

}
