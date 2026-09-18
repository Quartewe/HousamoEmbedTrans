package com.quarty.housamoembedtrans.ui;

import com.quarty.housamoembedtrans.R;
import com.quarty.housamoembedtrans.storage.config.PromptStore;

import android.os.Bundle;
import android.content.res.ColorStateList;
import android.text.Editable;
import android.text.TextUtils;
import android.text.TextWatcher;
import android.widget.EditText;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

import com.google.android.material.appbar.MaterialToolbar;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;

import java.io.IOException;
import java.util.HashMap;
import java.util.Map;

/** Edits the two independent prompt drafts while keeping the contract fixed. */
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

    private static final class DraftValues {
        String role;
        String style;
        String context;

        DraftValues(String role, String style, String context) {
            this.role = role == null ? "" : role;
            this.style = style == null ? "" : style;
            this.context = context == null ? "" : context;
        }

        DraftValues copy() {
            return new DraftValues(role, style, context);
        }
    }

    private PromptStore promptStore;
    private final Map<String, DraftValues> committed = new HashMap<>();
    private final Map<String, DraftValues> drafts = new HashMap<>();
    private String activeKind = PromptStore.TRANSLATION;
    private EditText role;
    private EditText style;
    private EditText context;
    private android.widget.TextView kindTitle;
    private android.widget.TextView kindHelper;
    private MaterialButton translationTab;
    private MaterialButton summaryTab;
    private TextViewHandle readonly;
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
        kindTitle = findViewById(R.id.tv_prompt_kind_title);
        kindHelper = findViewById(R.id.tv_prompt_kind_helper);
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

        MaterialToolbar toolbar = findViewById(R.id.toolbar_prompt_editor);
        toolbar.setNavigationOnClickListener(view -> onBackPressed());
        translationTab = findViewById(R.id.btn_prompt_translation);
        summaryTab = findViewById(R.id.btn_prompt_summary);
        findViewById(R.id.btn_prompt_discard).setOnClickListener(
            view -> discardActiveDraft()
        );
        findViewById(R.id.btn_prompt_reset).setOnClickListener(
            view -> confirmReset()
        );
        saveButton.setOnClickListener(view -> saveActiveDraft());

        restoreDraftState(savedInstanceState);
        translationTab.setOnClickListener(
            view -> selectPromptKind(PromptStore.TRANSLATION)
        );
        summaryTab.setOnClickListener(
            view -> selectPromptKind(PromptStore.SUMMARY)
        );
        showActiveDraft();
    }

    @Override
    protected void onSaveInstanceState(Bundle outState) {
        captureActiveDraft();
        outState.putString(STATE_ACTIVE_KIND, activeKind);
        outState.putBundle(STATE_DRAFTS, saveDraftValues(drafts));
        outState.putBundle(STATE_COMMITTED, saveDraftValues(committed));
        super.onSaveInstanceState(outState);
    }

    @Override
    public void onBackPressed() {
        captureActiveDraft();
        if (isDirty(PromptStore.TRANSLATION) || isDirty(PromptStore.SUMMARY)) {
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
            for (String kind : new String[] {
                PromptStore.TRANSLATION,
                PromptStore.SUMMARY
            }) {
                PromptStore.PromptDocument document = promptStore.load(kind);
                DraftValues values = new DraftValues(
                    document.getRole(),
                    document.getStyle(),
                    document.getContext()
                );
                committed.put(kind, values.copy());
                drafts.put(kind, values);
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
        for (String kind : new String[] {
            PromptStore.TRANSLATION,
            PromptStore.SUMMARY
        }) {
            DraftValues committedValues = readDraftValues(restoredCommitted, kind);
            if (committedValues != null) {
                committed.put(kind, committedValues);
            }
            DraftValues draftValues = readDraftValues(restoredDrafts, kind);
            if (draftValues != null) {
                drafts.put(kind, draftValues);
            }
        }
        String restoredKind = savedInstanceState.getString(STATE_ACTIVE_KIND);
        if (PromptStore.TRANSLATION.equals(restoredKind)
            || PromptStore.SUMMARY.equals(restoredKind)) {
            activeKind = restoredKind;
        }
    }

    private Bundle saveDraftValues(Map<String, DraftValues> values) {
        Bundle result = new Bundle();
        for (String kind : new String[] {
            PromptStore.TRANSLATION,
            PromptStore.SUMMARY
        }) {
            DraftValues draft = values.get(kind);
            if (draft == null) {
                continue;
            }
            Bundle saved = new Bundle();
            saved.putString(STATE_ROLE, draft.role);
            saved.putString(STATE_STYLE, draft.style);
            saved.putString(STATE_CONTEXT, draft.context);
            result.putBundle(kind, saved);
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
            saved.getString(STATE_CONTEXT)
        );
    }

    private void showActiveDraft() {
        DraftValues values = drafts.get(activeKind);
        if (values == null) {
            return;
        }
        suppressDraftWatchers = true;
        role.setText(values.role);
        style.setText(values.style);
        context.setText(values.context);
        suppressDraftWatchers = false;
        boolean summary = PromptStore.SUMMARY.equals(activeKind);
        kindTitle.setText(summary
            ? R.string.settings_rebuild_prompt_summary_title
            : R.string.settings_rebuild_prompt_translation_title);
        kindHelper.setText(R.string.settings_prompt_subtitle);
        TextViewHandle readOnly = new TextViewHandle(
            findViewById(R.id.tv_prompt_readonly)
        );
        readOnly.setText(summary
            ? R.string.settings_rebuild_prompt_summary_constraints
            : R.string.settings_rebuild_prompt_translation_constraints);
        saveButton.setText(
            isDirty(activeKind)
                ? R.string.settings_prompt_save
                : R.string.settings_prompt_saved
        );
        updatePromptTabs();
    }

    private void selectPromptKind(String kind) {
        if (kind == null || kind.equals(activeKind)) {
            return;
        }
        captureActiveDraft();
        activeKind = kind;
        showActiveDraft();
    }

    private void updatePromptTabs() {
        if (translationTab == null || summaryTab == null) {
            return;
        }
        setPromptTabState(
            translationTab,
            PromptStore.TRANSLATION.equals(activeKind)
        );
        setPromptTabState(
            summaryTab,
            PromptStore.SUMMARY.equals(activeKind)
        );
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
        drafts.put(activeKind, new DraftValues(
            textOf(role),
            textOf(style),
            textOf(context)
        ));
        if (saveButton != null) {
            saveButton.setText(
                isDirty(activeKind)
                    ? R.string.settings_prompt_save
                    : R.string.settings_prompt_saved
            );
        }
    }

    private void saveActiveDraft() {
        captureActiveDraft();
        DraftValues values = drafts.get(activeKind);
        try {
            PromptStore.PromptDocument document = toDocument(values);
            promptStore.save(activeKind, document);
            committed.put(activeKind, values.copy());
            drafts.put(activeKind, values.copy());
            saveButton.setText(R.string.settings_prompt_saved);
            Toast.makeText(
                this,
                getString(
                    R.string.settings_prompt_saved_message,
                    promptLabel(activeKind)
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
        if (!isDirty(activeKind)) {
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
                    drafts.put(activeKind, committed.get(activeKind).copy());
                    showActiveDraft();
                    Toast.makeText(
                        this,
                        getString(
                            R.string.settings_prompt_discarded_message,
                            promptLabel(activeKind)
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
            PromptStore.PromptDocument document = promptStore.loadDefaults(activeKind);
            promptStore.save(activeKind, document);
            DraftValues values = new DraftValues(
                document.getRole(),
                document.getStyle(),
                document.getContext()
            );
            committed.put(activeKind, values.copy());
            drafts.put(activeKind, values);
            showActiveDraft();
            Toast.makeText(
                this,
                getString(
                    R.string.settings_prompt_reset_message_done,
                    promptLabel(activeKind)
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
        for (String kind : new String[] {
            PromptStore.TRANSLATION,
            PromptStore.SUMMARY
        }) {
            drafts.put(kind, committed.get(kind).copy());
        }
    }

    private boolean isDirty(String kind) {
        DraftValues draft = drafts.get(kind);
        DraftValues saved = committed.get(kind);
        return draft != null && saved != null
            && (!draft.role.equals(saved.role)
                || !draft.style.equals(saved.style)
                || !draft.context.equals(saved.context));
    }

    private PromptStore.PromptDocument toDocument(DraftValues values) {
        if (values == null) {
            throw new IllegalArgumentException("prompt draft is missing");
        }
        return new PromptStore.PromptDocument(
            values.role,
            values.style,
            values.context
        );
    }

    private void showPromptError(IllegalArgumentException error) {
        Toast.makeText(
            this,
            getString(R.string.settings_error_required),
            Toast.LENGTH_LONG
        ).show();
    }

    private String promptLabel(String kind) {
        return PromptStore.SUMMARY.equals(kind)
            ? getString(R.string.settings_prompt_summary_tab)
            : getString(R.string.settings_prompt_translation_tab);
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

    private static final class TextViewHandle {
        private final android.widget.TextView view;

        TextViewHandle(android.widget.TextView view) {
            this.view = view;
        }

        void setText(int resourceId) {
            view.setText(resourceId);
        }
    }
}
