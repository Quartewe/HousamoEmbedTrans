package com.quarty.housamoembedtrans.ui;

import com.google.android.material.appbar.MaterialToolbar;
import com.google.android.material.button.MaterialButton;

import com.quarty.housamoembedtrans.R;
import com.quarty.housamoembedtrans.scene.store.SceneStore;
import com.quarty.housamoembedtrans.storage.config.ConfigStore;

import android.content.Intent;
import android.content.res.ColorStateList;
import android.graphics.Typeface;
import android.os.Bundle;
import android.os.Parcelable;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.ContextThemeWrapper;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;
import androidx.activity.OnBackPressedCallback;
import androidx.core.content.ContextCompat;
import androidx.core.widget.NestedScrollView;

import com.google.android.material.card.MaterialCardView;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Read-only detail surface for one persisted Scene document. */
public final class SceneManagementDetailActivity extends AppCompatActivity {
    public static final String EXTRA_SCENE_NAME =
        "com.quarty.housamoembedtrans.ui.EXTRA_SCENE_NAME";
    public static final String EXTRA_CHARACTER_ROLE =
        "com.quarty.housamoembedtrans.ui.EXTRA_CHARACTER_ROLE";
    public static final String EXTRA_CHARACTER_NAME =
        "com.quarty.housamoembedtrans.ui.EXTRA_CHARACTER_NAME";
    public static final String EXTRA_TERM_NAME =
        "com.quarty.housamoembedtrans.ui.EXTRA_TERM_NAME";

    private static final String STATE_CURRENT_PAGE =
        "scene_detail.current_page";
    private static final String STATE_HISTORY = "scene_detail.history";
    private static final String STATE_PAGE = "scene_detail.page";
    private static final String STATE_DETAIL_KIND = "scene_detail.detail_kind";
    private static final String STATE_DETAIL_ROLE = "scene_detail.detail_role";
    private static final String STATE_DETAIL_NAME = "scene_detail.detail_name";
    private static final String STATE_DETAIL_TERM = "scene_detail.detail_term";
    private static final String STATE_SCROLL_Y = "scene_detail.scroll_y";
    private static final String STATE_PAGE_SCROLL_Y =
        "scene_detail.page_scroll_y";
    private static final String DETAIL_CHARACTER = "character";
    private static final String DETAIL_MENTIONED_CHARACTER = "mentioned";
    private static final String DETAIL_TERM = "term";
    private static final String CHARACTER_SECTION_MC = "mc";
    private static final String CHARACTER_SECTION_HIGH = "high_weight";
    private static final String CHARACTER_SECTION_LOW = "low_weight";
    private static final String CHARACTER_SECTION_MENTIONED = "mentioned";

    private final ExecutorService ioExecutor = Executors.newSingleThreadExecutor();
    private final List<View> actionViews = new ArrayList<>();

    private LinearLayout content;
    private NestedScrollView scrollView;
    private TextView status;
    private MaterialToolbar toolbar;
    private PendingProcessMoveController pendingMoveController;
    private String sceneName;
    private String targetCharacterRole;
    private String targetCharacterName;
    private String targetTermName;
    private SceneManagementDetailData.SceneData sceneData;
    private JSONObject characterDictionary;
    private boolean deepLinkApplied;
    private boolean restoredPageState;
    private boolean restoredPageStateResolved;
    private boolean restoredTargetUnavailable;
    private Bundle restoredCurrentPage;
    private final List<Bundle> restoredHistory = new ArrayList<>();
    private Bundle refreshCurrentPage;
    private final List<Bundle> refreshHistory = new ArrayList<>();
    private int page = PAGE_SUMMARY;
    private Object selectedDetail;
    private final List<PageState> history = new ArrayList<>();
    private boolean loadInFlight;
    private JSONObject manualSummaries = new JSONObject();
    private boolean hasResumed;
    private boolean resumed;
    private boolean operationBusy;
    private boolean reloadAfterPendingMove;
    private int savedScrollY = -1;
    private boolean restoreScrollPending;
    private int pendingScrollY = -1;
    private long renderGeneration;

    private static final int PAGE_SUMMARY = 0;
    private static final int PAGE_SOURCE = 1;
    private static final int PAGE_CHARACTERS = 2;
    private static final int PAGE_TERMS = 3;
    private static final int PAGE_CHARACTER_DETAIL = 4;
    private static final int PAGE_TERM_DETAIL = 5;
    private static final int PAGE_MENTIONED_CHARACTER_DETAIL = 6;

    private static final class PageState {
        final int page;
        final Object detail;
        final int scrollY;

        PageState(int page, Object detail, int scrollY) {
            this.page = page;
            this.detail = detail;
            this.scrollY = scrollY;
        }
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_scene_management_detail);
        SystemBarInsets.apply(findViewById(R.id.root_scene_management_detail));

        toolbar = findViewById(R.id.toolbar_scene_management_detail);
        toolbar.setNavigationOnClickListener(
            view -> getOnBackPressedDispatcher().onBackPressed()
        );
        getOnBackPressedDispatcher().addCallback(this, new OnBackPressedCallback(true) {
            @Override
            public void handleOnBackPressed() {
                if (!history.isEmpty()) {
                    PageState previous = history.remove(history.size() - 1);
                    page = previous.page;
                    selectedDetail = previous.detail;
                    setPendingScroll(previous.scrollY);
                    if (scrollView != null) {
                        scrollView.scrollTo(0, 0);
                    }
                    renderPage();
                    return;
                }
                setEnabled(false);
                getOnBackPressedDispatcher().onBackPressed();
            }
        });
        status = findViewById(R.id.tv_scene_management_detail_status);
        content = findViewById(R.id.container_scene_management_detail);
        scrollView = findViewById(R.id.scroll_scene_management_detail);
        if (savedInstanceState != null) {
            savedScrollY = savedInstanceState.getInt(STATE_SCROLL_Y, -1);
            setPendingScroll(savedScrollY);
        }
        Intent intent = getIntent();
        sceneName = intent.getStringExtra(EXTRA_SCENE_NAME);
        targetCharacterRole = intent.getStringExtra(EXTRA_CHARACTER_ROLE);
        targetCharacterName = intent.getStringExtra(EXTRA_CHARACTER_NAME);
        targetTermName = intent.getStringExtra(EXTRA_TERM_NAME);
        restoredPageState = readSavedPageState(savedInstanceState);
        if (TextUtils.isEmpty(sceneName)) {
            status.setText(R.string.scene_detail_unavailable);
            return;
        }
        pendingMoveController = new PendingProcessMoveController(this);
        status.setText(R.string.scene_detail_loading);
        loadAsync();
    }

    private void loadAsync() {
        if (loadInFlight || TextUtils.isEmpty(sceneName)) {
            return;
        }
        loadInFlight = true;
        updateActionState();
        if (sceneData == null) {
            status.setText(R.string.scene_detail_loading);
        } else {
            capturePageStateForRefresh();
            status.setText(R.string.scene_detail_refreshing);
        }
        status.setVisibility(View.VISIBLE);
        ioExecutor.execute(() -> {
            try {
                SceneStore sceneStore = new SceneStore(this);
                SceneStore.ValidatedScene scene =
                    sceneStore.readValidSceneByName(sceneName);
                if (scene == null) {
                    throw new IllegalStateException(
                        getString(R.string.scene_detail_missing)
                    );
                }
                ConfigStore configStore = new ConfigStore(this);
                final JSONObject annotations = new com.quarty.housamoembedtrans.scene.store.SceneAnnotationStore(
                    getFilesDir()).read(sceneName);
                JSONObject characters = configStore.loadJson(
                    ConfigStore.CHARDICT_FILE_NAME
                ).json;
                JSONObject terms = configStore.loadJson(
                    ConfigStore.GAMETERMS_FILE_NAME
                ).json;
                SceneManagementDetailData.SceneData data =
                    SceneManagementDetailData.read(scene, characters, terms);
                runOnUiThread(() -> {
                    loadInFlight = false;
                    if (!isActivityAlive()) {
                        return;
                    }
                    JSONObject loadedManualSummaries = annotations.optJSONObject(
                        "manual_summaries"
                    );
                    manualSummaries = loadedManualSummaries == null
                        ? new JSONObject()
                        : loadedManualSummaries;
                    render(data, characters);
                });
            } catch (Exception error) {
                runOnUiThread(() -> {
                    loadInFlight = false;
                    if (isFinishing() || isDestroyed()) {
                        return;
                    }
                    sceneData = null;
                    selectedDetail = null;
                    history.clear();
                    refreshCurrentPage = null;
                    refreshHistory.clear();
                    actionViews.clear();
                    updateActionState();
                    content.removeAllViews();
                    status.setText(getString(
                        R.string.scene_detail_load_failed,
                        safeMessage(error)
                    ));
                });
            }
        });
    }

    private void render(
        SceneManagementDetailData.SceneData data,
        JSONObject characterDictionary
    ) {
        if (isFinishing() || isDestroyed()) {
            return;
        }
        status.setVisibility(View.GONE);
        sceneData = data;
        this.characterDictionary = characterDictionary;
        if (refreshCurrentPage != null) {
            restoreRefreshedPageState();
            refreshCurrentPage = null;
            refreshHistory.clear();
            deepLinkApplied = true;
            renderPage();
            return;
        }
        if (restoredPageState && !restoredPageStateResolved) {
            restoreSavedPageState();
            restoredPageStateResolved = true;
            deepLinkApplied = true;
            renderPage();
            if (restoredTargetUnavailable) {
                status.setVisibility(View.VISIBLE);
                status.setText(R.string.scene_detail_target_unavailable);
            }
            return;
        }
        if (!deepLinkApplied && !restoredPageState) {
            deepLinkApplied = true;
            if (applyDeepLink()) {
                return;
            }
        }
        deepLinkApplied = true;
        renderPage();
    }

    private void capturePageStateForRefresh() {
        rememberScroll();
        refreshCurrentPage = encodePageState(page, selectedDetail, savedScrollY);
        refreshHistory.clear();
        for (PageState state : history) {
            refreshHistory.add(encodePageState(
                state.page,
                state.detail,
                state.scrollY
            ));
        }
    }

    private void restoreRefreshedPageState() {
        history.clear();
        for (Bundle state : refreshHistory) {
            PageState previous = resolveSavedPageState(state);
            if (previous != null) {
                history.add(previous);
            }
        }
        PageState current = resolveSavedPageState(refreshCurrentPage);
        if (current != null) {
            page = current.page;
            selectedDetail = current.detail;
            setPendingScroll(current.scrollY >= 0
                ? current.scrollY
                : savedScrollY);
            return;
        }
        int savedPage = refreshCurrentPage == null
            ? PAGE_SUMMARY
            : refreshCurrentPage.getInt(STATE_PAGE, PAGE_SUMMARY);
        page = fallbackPage(savedPage);
        selectedDetail = null;
        setPendingScroll(savedScrollY);
    }

    private boolean applyDeepLink() {
        boolean hasCharacterTarget = !TextUtils.isEmpty(targetCharacterName);
        boolean hasTermTarget = !TextUtils.isEmpty(targetTermName);
        if (!hasCharacterTarget && !hasTermTarget) {
            return false;
        }
        if (hasTermTarget) {
            SceneManagementDetailData.TermEntry target = findTermTarget(
                targetTermName
            );
            if (target != null) {
                page = PAGE_TERM_DETAIL;
                selectedDetail = target;
                renderPage();
                return true;
            }
        } else {
            Object target = findCharacterTarget(
                targetCharacterName,
                targetCharacterRole
            );
            if (target instanceof SceneManagementDetailData.CharacterEntry) {
                page = PAGE_CHARACTER_DETAIL;
                selectedDetail = target;
                renderPage();
                return true;
            }
            if (target instanceof SceneManagementDetailData.MentionedCharacterEntry) {
                page = PAGE_MENTIONED_CHARACTER_DETAIL;
                selectedDetail = target;
                renderPage();
                return true;
            }
        }
        renderPage();
        status.setVisibility(View.VISIBLE);
        status.setText(R.string.scene_detail_target_unavailable);
        return true;
    }

    private boolean readSavedPageState(Bundle savedInstanceState) {
        if (savedInstanceState == null) {
            return false;
        }
        restoredCurrentPage = savedInstanceState.getBundle(STATE_CURRENT_PAGE);
        if (restoredCurrentPage == null) {
            return false;
        }
        Parcelable[] savedHistory = savedInstanceState.getParcelableArray(
            STATE_HISTORY
        );
        if (savedHistory != null) {
            for (Parcelable state : savedHistory) {
                if (state instanceof Bundle) {
                    restoredHistory.add((Bundle) state);
                }
            }
        }
        return true;
    }

    private void restoreSavedPageState() {
        history.clear();
        for (Bundle state : restoredHistory) {
            PageState previous = resolveSavedPageState(state);
            if (previous != null) {
                history.add(previous);
            }
        }
        PageState current = resolveSavedPageState(restoredCurrentPage);
        if (current != null) {
            page = current.page;
            selectedDetail = current.detail;
            setPendingScroll(current.scrollY >= 0
                ? current.scrollY
                : savedScrollY);
            return;
        }
        int savedPage = restoredCurrentPage == null
            ? PAGE_SUMMARY
            : restoredCurrentPage.getInt(STATE_PAGE, PAGE_SUMMARY);
        page = fallbackPage(savedPage);
        selectedDetail = null;
        setPendingScroll(savedScrollY);
        restoredTargetUnavailable = isDetailPage(savedPage);
    }

    private PageState resolveSavedPageState(Bundle state) {
        if (state == null || !state.containsKey(STATE_PAGE)) {
            return null;
        }
        int savedPage = state.getInt(STATE_PAGE, PAGE_SUMMARY);
        if (savedPage == PAGE_CHARACTERS) {
            String section = state.getString(STATE_DETAIL_ROLE, "");
            return new PageState(
                savedPage,
                isCharacterSection(section) ? section : null,
                state.getInt(STATE_PAGE_SCROLL_Y, -1)
            );
        }
        if (savedPage == PAGE_SUMMARY
            || savedPage == PAGE_SOURCE
            || savedPage == PAGE_TERMS) {
            return new PageState(
                savedPage,
                null,
                state.getInt(STATE_PAGE_SCROLL_Y, -1)
            );
        }
        String kind = state.getString(STATE_DETAIL_KIND, "");
        String name = state.getString(STATE_DETAIL_NAME, "");
        if (savedPage == PAGE_CHARACTER_DETAIL
            && DETAIL_CHARACTER.equals(kind)) {
            Object detail = findCharacterTarget(
                name,
                state.getString(STATE_DETAIL_ROLE, "")
            );
            return detail instanceof SceneManagementDetailData.CharacterEntry
                ? new PageState(
                    savedPage,
                    detail,
                    state.getInt(STATE_PAGE_SCROLL_Y, -1)
                )
                : null;
        }
        if (savedPage == PAGE_MENTIONED_CHARACTER_DETAIL
            && DETAIL_MENTIONED_CHARACTER.equals(kind)) {
            Object detail = findCharacterTarget(name, "mentioned");
            return detail instanceof SceneManagementDetailData.MentionedCharacterEntry
                ? new PageState(
                    savedPage,
                    detail,
                    state.getInt(STATE_PAGE_SCROLL_Y, -1)
                )
                : null;
        }
        if (savedPage == PAGE_TERM_DETAIL && DETAIL_TERM.equals(kind)) {
            SceneManagementDetailData.TermEntry detail = findTermTarget(
                state.getString(STATE_DETAIL_TERM, "")
            );
            return detail == null
                ? null
                : new PageState(
                    savedPage,
                    detail,
                    state.getInt(STATE_PAGE_SCROLL_Y, -1)
                );
        }
        return null;
    }

    private int fallbackPage(int savedPage) {
        if (savedPage == PAGE_CHARACTER_DETAIL
            || savedPage == PAGE_MENTIONED_CHARACTER_DETAIL) {
            return PAGE_CHARACTERS;
        }
        if (savedPage == PAGE_TERM_DETAIL) {
            return PAGE_TERMS;
        }
        return savedPage == PAGE_SUMMARY
            || savedPage == PAGE_SOURCE
            || savedPage == PAGE_CHARACTERS
            || savedPage == PAGE_TERMS
            ? savedPage
            : PAGE_SUMMARY;
    }

    private boolean isDetailPage(int value) {
        return value == PAGE_CHARACTER_DETAIL
            || value == PAGE_MENTIONED_CHARACTER_DETAIL
            || value == PAGE_TERM_DETAIL;
    }

    private Bundle encodePageState(int value, Object detail, int scrollY) {
        Bundle state = new Bundle();
        state.putInt(STATE_PAGE, value);
        if (scrollY >= 0) {
            state.putInt(STATE_PAGE_SCROLL_Y, scrollY);
        }
        if (value == PAGE_CHARACTERS && detail instanceof String
            && isCharacterSection((String) detail)) {
            state.putString(STATE_DETAIL_ROLE, (String) detail);
        } else if (detail instanceof SceneManagementDetailData.CharacterEntry) {
            SceneManagementDetailData.CharacterEntry entry =
                (SceneManagementDetailData.CharacterEntry) detail;
            state.putString(STATE_DETAIL_KIND, DETAIL_CHARACTER);
            state.putString(STATE_DETAIL_ROLE, entry.role);
            state.putString(STATE_DETAIL_NAME, entry.name);
        } else if (detail instanceof SceneManagementDetailData.MentionedCharacterEntry) {
            SceneManagementDetailData.MentionedCharacterEntry entry =
                (SceneManagementDetailData.MentionedCharacterEntry) detail;
            state.putString(STATE_DETAIL_KIND, DETAIL_MENTIONED_CHARACTER);
            state.putString(STATE_DETAIL_ROLE, "mentioned");
            state.putString(STATE_DETAIL_NAME, entry.name);
        } else if (detail instanceof SceneManagementDetailData.TermEntry) {
            SceneManagementDetailData.TermEntry entry =
                (SceneManagementDetailData.TermEntry) detail;
            state.putString(STATE_DETAIL_KIND, DETAIL_TERM);
            state.putString(STATE_DETAIL_TERM, entry.term);
        }
        return state;
    }

    private boolean isCharacterSection(String value) {
        return CHARACTER_SECTION_MC.equals(value)
            || CHARACTER_SECTION_HIGH.equals(value)
            || CHARACTER_SECTION_LOW.equals(value)
            || CHARACTER_SECTION_MENTIONED.equals(value);
    }

    private Object findCharacterTarget(String name, String role) {
        String exact = name == null ? "" : name.trim();
        if (exact.isEmpty()) {
            return null;
        }
        String normalizedRole = role == null ? "" : role.trim();
        if ("mentioned".equals(normalizedRole)) {
            for (SceneManagementDetailData.MentionedCharacterEntry entry
                : sceneData.mentionedCharacters) {
                if (exact.equals(entry.name)) {
                    return entry;
                }
            }
            return null;
        }
        if (normalizedRole.isEmpty() || "mc".equals(normalizedRole)) {
            if (sceneData.mainCharacter != null
                && exact.equals(sceneData.mainCharacter.name)) {
                return sceneData.mainCharacter;
            }
            if ("mc".equals(normalizedRole)) {
                return null;
            }
        }
        String expectedRole = "primary".equals(normalizedRole)
            ? "high_weight"
            : "secondary".equals(normalizedRole)
                ? "low_weight"
                : normalizedRole;
        for (SceneManagementDetailData.CharacterEntry entry
            : sceneData.highWeightCharacters) {
            if (exact.equals(entry.name)
                && (expectedRole.isEmpty() || expectedRole.equals(entry.role))) {
                return entry;
            }
        }
        for (SceneManagementDetailData.CharacterEntry entry
            : sceneData.lowWeightCharacters) {
            if (exact.equals(entry.name)
                && (expectedRole.isEmpty() || expectedRole.equals(entry.role))) {
                return entry;
            }
        }
        if (normalizedRole.isEmpty()) {
            for (SceneManagementDetailData.MentionedCharacterEntry entry
                : sceneData.mentionedCharacters) {
                if (exact.equals(entry.name)) {
                    return entry;
                }
            }
        }
        return null;
    }

    private SceneManagementDetailData.TermEntry findTermTarget(String name) {
        String exact = name == null ? "" : name.trim();
        if (exact.isEmpty()) {
            return null;
        }
        for (SceneManagementDetailData.TermEntry entry : sceneData.terms) {
            if (exact.equals(entry.term)) {
                return entry;
            }
        }
        return null;
    }

    private void renderPage() {
        if (sceneData == null) {
            return;
        }
        renderGeneration++;
        if (page == PAGE_CHARACTER_DETAIL
            && !(selectedDetail instanceof SceneManagementDetailData.CharacterEntry)) {
            page = PAGE_CHARACTERS;
            selectedDetail = null;
        } else if (page == PAGE_MENTIONED_CHARACTER_DETAIL
            && !(selectedDetail instanceof SceneManagementDetailData.MentionedCharacterEntry)) {
            page = PAGE_CHARACTERS;
            selectedDetail = null;
        } else if (page == PAGE_TERM_DETAIL
            && !(selectedDetail instanceof SceneManagementDetailData.TermEntry)) {
            page = PAGE_TERMS;
            selectedDetail = null;
        }
        actionViews.clear();
        content.removeAllViews();
        toolbar.setTitle(
            page == PAGE_SUMMARY
                ? getString(R.string.scene_detail_title)
                : page == PAGE_SOURCE
                    ? getString(R.string.scene_detail_source_title)
                    : page == PAGE_CHARACTERS
                        ? getString(R.string.scene_detail_characters_title)
                        : page == PAGE_TERMS
                            ? getString(R.string.scene_detail_terms_title)
                            : page == PAGE_CHARACTER_DETAIL
                                || page == PAGE_MENTIONED_CHARACTER_DETAIL
                                ? getString(R.string.scene_detail_character_title)
                                : getString(R.string.scene_detail_term_title)
        );
        if (page == PAGE_SUMMARY) {
            renderSummaryPage();
        } else if (page == PAGE_SOURCE) {
            renderSourcePage();
        } else if (page == PAGE_CHARACTERS) {
            renderCharactersPage();
        } else if (page == PAGE_TERMS) {
            renderTermsPage();
        } else if (page == PAGE_CHARACTER_DETAIL) {
            renderCharacterDetailPage(
                (SceneManagementDetailData.CharacterEntry) selectedDetail
            );
        } else if (page == PAGE_MENTIONED_CHARACTER_DETAIL) {
            renderMentionedCharacterDetailPage(
                (SceneManagementDetailData.MentionedCharacterEntry) selectedDetail
            );
        } else if (page == PAGE_TERM_DETAIL) {
            renderTermDetailPage(
                (SceneManagementDetailData.TermEntry) selectedDetail
            );
        }
        updateActionState();
        restoreScrollIfNeeded();
    }

    private void renderSummaryPage() {
        addMetadata(sceneData);
        addSummaryCard(
            getString(R.string.scene_detail_source_title),
            sceneData.sourceItems.size(),
            getString(R.string.scene_detail_summary_source_hint),
            () -> openPage(PAGE_SOURCE, null)
        );
        addSummaryCard(
            getString(R.string.scene_detail_mc_count),
            sceneData.mainCharacter == null ? 0 : 1,
            getString(R.string.scene_detail_summary_character_hint),
            () -> openPage(PAGE_CHARACTERS, CHARACTER_SECTION_MC)
        );
        addSummaryCard(
            getString(R.string.scene_detail_high_count),
            sceneData.highWeightCharacters.size(),
            getString(R.string.scene_detail_summary_character_hint),
            () -> openPage(PAGE_CHARACTERS, CHARACTER_SECTION_HIGH)
        );
        addSummaryCard(
            getString(R.string.scene_detail_low_count),
            sceneData.lowWeightCharacters.size(),
            getString(R.string.scene_detail_summary_character_hint),
            () -> openPage(PAGE_CHARACTERS, CHARACTER_SECTION_LOW)
        );
        addSummaryCard(
            getString(R.string.scene_detail_mentioned_count),
            sceneData.mentionedCharacters.size(),
            getString(R.string.scene_detail_summary_character_hint),
            () -> openPage(PAGE_CHARACTERS, CHARACTER_SECTION_MENTIONED)
        );
        addSummaryCard(
            getString(R.string.scene_detail_terms_title),
            sceneData.terms.size(),
            getString(R.string.scene_detail_summary_term_hint),
            () -> openPage(PAGE_TERMS, null)
        );
        if (manualSummaries.length() > 0) {
            MaterialCardView annotations = cardColumn();
            addText(
                annotations,
                getString(R.string.scene_annotation_summaries),
                16,
                true,
                false
            );
            java.util.Iterator<String> languages = manualSummaries.keys();
            while (languages.hasNext()) {
                String language = languages.next();
                addText(
                    annotations,
                    languageName(language),
                    14,
                    true,
                    false
                );
                JSONObject record = manualSummaries.optJSONObject(language);
                addText(
                    annotations,
                    record == null ? "" : record.optString("text", ""),
                    14, false, false);
            }
            content.addView(annotations);
        }
        renderSceneActions();
        addText(content, getString(
            R.string.scene_detail_protect_applied,
            sceneData.protectedTokens.size()
        ), 12, false, true);
    }

    private void renderSceneActions() {
        MaterialCardView card = cardColumn();
        LinearLayout column = (LinearLayout) card.getTag();
        MaterialButton edit = new MaterialButton(this);
        edit.setText(R.string.scene_annotation_edit);
        edit.setAllCaps(false);
        edit.setOnClickListener(view -> {
            if (!canStartPendingMove()) return;
            startActivity(new Intent(this, SceneContextActivity.class)
            .putExtra(SceneContextActivity.EXTRA_MANAGEMENT_SCENE, sceneName));
        });
        registerAction(edit);
        addText(
            card,
            getString(R.string.scene_detail_actions_title),
            16,
            true,
            false
        );
        column.addView(edit);
        addText(
            card,
            getString(
                R.string.scene_detail_language_count,
                sceneData.languages.size()
            ),
            12,
            false,
            true
        );
        if (sceneData.languages.isEmpty()) {
            addText(
                card,
                getString(R.string.scene_detail_no_languages),
                13,
                false,
                true
            );
        } else {
            for (String language : sceneData.languages) {
                MaterialButton button = new MaterialButton(this);
                button.setAllCaps(false);
                button.setText(getString(
                    R.string.scene_detail_move_language_pending,
                    languageName(language)
                ));
                applyDangerButton(button);
                button.setOnClickListener(
                    view -> moveLanguageToPending(language)
                );
                registerAction(button);
                column.addView(button, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                ));
            }
        }
        MaterialButton sceneButton = new MaterialButton(this);
        sceneButton.setAllCaps(false);
        sceneButton.setText(R.string.scene_detail_move_scene_pending);
        applyDangerButton(sceneButton);
        sceneButton.setOnClickListener(view -> moveSceneToPending());
        registerAction(sceneButton);
        column.addView(sceneButton, new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        ));
        content.addView(card);
    }

    private void registerAction(View action) {
        actionViews.add(action);
    }

    private void updateActionState() {
        boolean enabled = resumed
            && !operationBusy
            && !loadInFlight
            && sceneData != null
            && isActivityAlive();
        for (View action : actionViews) {
            action.setEnabled(enabled);
        }
    }

    private void moveLanguageToPending(String language) {
        if (!canStartPendingMove()
            || sceneData == null
            || language == null
            || !sceneData.languages.contains(language)) {
            return;
        }
        final String canonicalId;
        try {
            canonicalId = SceneStore.languageCanonicalId(sceneName, language);
        } catch (Exception failure) {
            showOperationFailure(failure);
            return;
        }
        operationBusy = true;
        reloadAfterPendingMove = false;
        updateActionState();
        pendingMoveController.confirmMove(
            "language",
            canonicalId,
            sceneName + " · " + languageName(language),
            this::handleLanguageMoved,
            this::finishPendingMove
        );
    }

    private void moveSceneToPending() {
        if (!canStartPendingMove() || !SceneStore.isValidSceneName(sceneName)) {
            return;
        }
        operationBusy = true;
        reloadAfterPendingMove = false;
        updateActionState();
        pendingMoveController.confirmMove(
            "scene",
            sceneName,
            sceneName,
            this::handleSceneMoved,
            this::finishPendingMove
        );
    }

    private boolean canStartPendingMove() {
        return resumed
            && !operationBusy
            && !loadInFlight
            && pendingMoveController != null
            && sceneData != null
            && isActivityAlive();
    }

    private void handleLanguageMoved() {
        if (!isActivityAlive()) {
            return;
        }
        page = PAGE_SUMMARY;
        selectedDetail = null;
        history.clear();
        setPendingScroll(-1);
        if (scrollView != null) {
            scrollView.scrollTo(0, 0);
        }
        reloadAfterPendingMove = true;
    }

    private void handleSceneMoved() {
        if (!isActivityAlive()) {
            return;
        }
        setResult(RESULT_OK);
        finish();
    }

    private void finishPendingMove() {
        operationBusy = false;
        updateActionState();
        if (reloadAfterPendingMove) {
            reloadAfterPendingMove = false;
            if (resumed) {
                loadAsync();
            }
        }
    }

    private void showOperationFailure(Throwable failure) {
        operationBusy = false;
        reloadAfterPendingMove = false;
        updateActionState();
        if (!isActivityAlive()) {
            return;
        }
        android.widget.Toast.makeText(
            this,
            getString(R.string.scene_operation_failed, safeMessage(failure)),
            android.widget.Toast.LENGTH_LONG
        ).show();
    }

    private void openPage(int nextPage, Object detail) {
        history.add(new PageState(page, selectedDetail, currentScrollY()));
        page = nextPage;
        selectedDetail = detail;
        setPendingScroll(-1);
        if (scrollView != null) {
            scrollView.scrollTo(0, 0);
        }
        renderPage();
    }

    private void addSummaryCard(
        String title,
        int count,
        String hint,
        Runnable action
    ) {
        MaterialCardView card = cardColumn();
        LinearLayout column = (LinearLayout) card.getTag();
        addText(column, title + " · " + count, 15, true, false);
        addText(column, hint, 12, false, true);
        card.setClickable(true);
        card.setFocusable(true);
        card.setContentDescription(title + " · " + count);
        card.setOnClickListener(view -> action.run());
        content.addView(card);
    }

    private void addMetadata(SceneManagementDetailData.SceneData data) {
        MaterialCardView card = cardColumn();
        addText(card, data.sceneName, 20, true, false);
        addText(card, getString(
            R.string.scene_detail_metadata,
            emptyFallback(data.gameVersion),
            languageLabel(data.rawLanguage),
            languageLabel(data.targetLanguage)
        ), 12, false, true);
        content.addView(card);
    }

    private void renderCharactersPage() {
        String expandedSection = selectedDetail instanceof String
            ? (String) selectedDetail
            : "";
        addText(content, getString(
            R.string.scene_detail_characters_description
        ), 13, false, true);
        addDisclosure(
            getString(R.string.scene_detail_mc_count),
            sceneData.mainCharacter == null ? 0 : 1,
            sceneData.mainCharacter == null
                ? new ArrayList<>()
                : singletonCharacterRows(sceneData.mainCharacter, characterDictionary),
            CHARACTER_SECTION_MC.equals(expandedSection)
        );
        addDisclosure(
            getString(R.string.scene_detail_high_count),
            sceneData.highWeightCharacters.size(),
            characterRows(sceneData.highWeightCharacters, characterDictionary),
            CHARACTER_SECTION_HIGH.equals(expandedSection)
        );
        addDisclosure(
            getString(R.string.scene_detail_low_count),
            sceneData.lowWeightCharacters.size(),
            characterRows(sceneData.lowWeightCharacters, characterDictionary),
            CHARACTER_SECTION_LOW.equals(expandedSection)
        );
        List<View> mentionedRows = new ArrayList<>();
        for (SceneManagementDetailData.MentionedCharacterEntry entry
            : sceneData.mentionedCharacters) {
            mentionedRows.add(characterRow(entry));
        }
        addDisclosure(
            getString(R.string.scene_detail_mentioned_count),
            sceneData.mentionedCharacters.size(),
            mentionedRows,
            CHARACTER_SECTION_MENTIONED.equals(expandedSection)
        );
    }

    private void renderSourcePage() {
        addText(content, getString(
            R.string.scene_detail_source_description,
            sceneData.sourceItems.size()
        ), 13, false, true);
        for (SceneManagementDetailData.SourceItem item : sceneData.sourceItems) {
            content.addView(sourceRow(item, sceneData));
        }
    }

    private void renderTermsPage() {
        addText(content, getString(
            R.string.scene_detail_terms_description,
            sceneData.terms.size()
        ), 13, false, true);
        for (SceneManagementDetailData.TermEntry entry : sceneData.terms) {
            content.addView(termRow(entry));
        }
    }

    private void renderCharacterDetailPage(
        SceneManagementDetailData.CharacterEntry entry
    ) {
        if (entry == null) {
            return;
        }
        content.addView(characterDetailRow(entry, characterDictionary));
    }

    private void renderMentionedCharacterDetailPage(
        SceneManagementDetailData.MentionedCharacterEntry entry
    ) {
        if (entry == null) {
            return;
        }
        content.addView(mentionedCharacterDetailRow(entry));
    }

    private void renderTermDetailPage(SceneManagementDetailData.TermEntry entry) {
        if (entry == null) {
            return;
        }
        content.addView(termDetailRow(entry));
    }

    private List<View> singletonCharacterRows(
        SceneManagementDetailData.CharacterEntry entry,
        JSONObject characterDictionary
    ) {
        List<SceneManagementDetailData.CharacterEntry> entries =
            new ArrayList<>();
        entries.add(entry);
        return characterRows(entries, characterDictionary);
    }

    private List<View> characterRows(
        List<SceneManagementDetailData.CharacterEntry> entries,
        JSONObject characterDictionary
    ) {
        List<View> rows = new ArrayList<>();
        for (SceneManagementDetailData.CharacterEntry entry : entries) {
            rows.add(characterRow(entry, characterDictionary));
        }
        return rows;
    }

    private View characterRow(
        SceneManagementDetailData.CharacterEntry entry,
        JSONObject characterDictionary
    ) {
        MaterialCardView card = cardColumn();
        addText(
            card,
            displayCharacterName(entry),
            16,
            true,
            false
        );
        addText(card, getString(
            entry.temporary
                ? R.string.scene_detail_temporary_character
                : R.string.scene_detail_dictionary_character,
            entry.name
        ), 12, false, true);
        addField(card, R.string.scene_detail_original_name, entry.name);
        card.setOnClickListener(view -> openPage(
            PAGE_CHARACTER_DETAIL,
            entry
        ));
        return card;
    }

    private View characterDetailRow(
        SceneManagementDetailData.CharacterEntry entry,
        JSONObject characterDictionary
    ) {
        MaterialCardView card = cardColumn();
        addText(
            card,
            displayCharacterName(entry),
            20,
            true,
            false
        );
        addText(card, getString(
            entry.temporary
                ? R.string.scene_detail_temporary_character
                : R.string.scene_detail_dictionary_character,
            entry.name
        ), 12, false, true);
        addField(card, R.string.scene_detail_original_name, entry.name);
        addField(card, R.string.scene_detail_field_simplified_chinese, entry.zhCn);
        addField(card, R.string.scene_detail_field_traditional_chinese, entry.zhTw);
        addField(card, R.string.scene_detail_field_english, entry.en);
        addField(
            card,
            R.string.field_alias,
            TextUtils.join(
                "、",
                SceneManagementDetailData.stringArray(entry.aliases)
            )
        );
        addField(
            card,
            R.string.field_school,
            TextUtils.join(
                "、",
                SceneManagementDetailData.stringArray(entry.school)
            )
        );
        addField(
            card,
            R.string.field_guild,
            TextUtils.join(
                "、",
                SceneManagementDetailData.stringArray(entry.guild)
            )
        );
        addField(
            card,
            R.string.field_origin_world,
            TextUtils.join(
                "、",
                SceneManagementDetailData.stringArray(entry.originWorld)
            )
        );
        addField(
            card,
            R.string.field_relationships,
            TextUtils.join(
                "、",
                displayRelationshipLabels(entry.relationships)
            )
        );
        addField(card, R.string.field_speech_style, entry.speechStyle);
        addField(card, R.string.field_description, entry.description);
        addField(card, R.string.field_info, entry.info);
        return card;
    }

    private View characterRow(
        SceneManagementDetailData.MentionedCharacterEntry entry
    ) {
        MaterialCardView card = cardColumn();
        addText(
            card,
            displayMentionedName(entry),
            16,
            true,
            false
        );
        addText(card, getString(
            entry.temporary
                ? R.string.scene_detail_temporary_character
                : R.string.scene_detail_dictionary_character,
            entry.name
        ), 12, false, true);
        addField(card, R.string.scene_detail_original_name, entry.name);
        addField(card, R.string.scene_detail_field_simplified_chinese, entry.zhCn);
        addField(card, R.string.scene_detail_field_traditional_chinese, entry.zhTw);
        addField(card, R.string.scene_detail_field_english, entry.en);
        card.setOnClickListener(view -> openPage(
            PAGE_MENTIONED_CHARACTER_DETAIL,
            entry
        ));
        return card;
    }

    private View mentionedCharacterDetailRow(
        SceneManagementDetailData.MentionedCharacterEntry entry
    ) {
        MaterialCardView card = cardColumn();
        addText(
            card,
            displayMentionedName(entry),
            20,
            true,
            false
        );
        addText(card, getString(
            entry.temporary
                ? R.string.scene_detail_temporary_character
                : R.string.scene_detail_dictionary_character,
            entry.name
        ), 12, false, true);
        addField(card, R.string.scene_detail_original_name, entry.name);
        addField(card, R.string.scene_detail_field_simplified_chinese, entry.zhCn);
        addField(card, R.string.scene_detail_field_traditional_chinese, entry.zhTw);
        addField(card, R.string.scene_detail_field_english, entry.en);
        return card;
    }

    private View termRow(SceneManagementDetailData.TermEntry entry) {
        MaterialCardView card = cardColumn();
        addText(
            card,
            displayTermName(entry),
            16,
            true,
            false
        );
        addText(card, getString(
            entry.temporary
                ? R.string.scene_detail_temporary_term
                : R.string.scene_detail_dictionary_term,
            entry.term
        ), 12, false, true);
        addField(card, R.string.scene_detail_original_name, entry.term);
        card.setOnClickListener(view -> openPage(
            PAGE_TERM_DETAIL,
            entry
        ));
        return card;
    }

    private View termDetailRow(SceneManagementDetailData.TermEntry entry) {
        MaterialCardView card = cardColumn();
        addText(
            card,
            displayTermName(entry),
            20,
            true,
            false
        );
        addText(card, getString(
            entry.temporary
                ? R.string.scene_detail_temporary_term
                : R.string.scene_detail_dictionary_term,
            entry.term
        ), 12, false, true);
        addField(card, R.string.scene_detail_original_name, entry.term);
        addField(card, R.string.scene_detail_field_simplified_chinese, entry.zhCn);
        addField(card, R.string.scene_detail_field_traditional_chinese, entry.zhTw);
        addField(card, R.string.scene_detail_field_english, entry.en);
        addField(card, R.string.field_description, entry.description);
        return card;
    }

    private View sourceRow(
        SceneManagementDetailData.SourceItem sourceItem,
        SceneManagementDetailData.SceneData data
    ) {
        JSONObject item = sourceItem.source;
        MaterialCardView card = cardColumn();
        String type = item.optString("type", "text");
        addText(card, "#" + sourceItem.orderLabel + " · " + type, 14, true, false);
        addField(
            card,
            R.string.scene_detail_speaker,
            displaySpeaker(item.optString("speaker", ""))
        );
        if ("text".equals(type)) {
            addField(
                card,
                R.string.scene_detail_original_text,
                SceneManagementDetailData.restoreProtectedText(
                    item.optString("text", ""),
                    data.protectedTokens
                )
            );
            addField(
                card,
                R.string.scene_detail_translation,
                SceneManagementDetailData.translationFor(
                    item,
                    data.targetLanguage
                )
            );
        } else if ("choice".equals(type)) {
            addField(card, R.string.scene_detail_merge_label, item.optString("merge_label", ""));
            JSONArray branches = item.optJSONArray("branches");
            if (branches != null) {
                for (int branchIndex = 0; branchIndex < branches.length(); branchIndex++) {
                    JSONObject branch = branches.optJSONObject(branchIndex);
                    if (branch == null) {
                        continue;
                    }
                    addField(
                        card,
                        R.string.scene_detail_branch,
                        branch.optString("target_label", "")
                    );
                    JSONArray options = branch.optJSONArray("options");
                    if (options != null) {
                        for (int optionIndex = 0; optionIndex < options.length(); optionIndex++) {
                            JSONObject option = options.optJSONObject(optionIndex);
                            if (option == null) {
                                continue;
                            }
                            String optionOrder = SceneManagementDetailData.orderLabel(
                                option,
                                optionIndex,
                                data.sequenceByOrder
                            );
                            addField(
                                card,
                                "  " + getString(R.string.scene_detail_option)
                                    + " #" + optionOrder,
                                SceneManagementDetailData.restoreProtectedText(
                                    option.optString("text", ""),
                                    data.protectedTokens
                                )
                            );
                        }
                    }
                }
            }
        } else if ("if".equals(type)) {
            addField(
                card,
                R.string.scene_detail_condition,
                SceneManagementDetailData.restoreProtectedText(
                    item.optString("condition", ""),
                    data.protectedTokens
                )
            );
            addField(card, R.string.scene_detail_branch, item.optString("target_label", ""));
            JSONArray following = item.optJSONArray("following_text");
            if (following != null) {
                addField(
                    card,
                    R.string.scene_detail_following_count,
                    String.valueOf(following.length())
                );
            }
        }
        return card;
    }

    @Override
    protected void onResume() {
        super.onResume();
        resumed = true;
        if (hasResumed && !operationBusy && !loadInFlight) {
            loadAsync();
        }
        hasResumed = true;
        updateActionState();
    }

    @Override
    protected void onPause() {
        rememberScroll();
        resumed = false;
        updateActionState();
        super.onPause();
    }

    @Override
    protected void onSaveInstanceState(Bundle outState) {
        rememberScroll();
        outState.putInt(STATE_SCROLL_Y, savedScrollY);
        if (sceneData == null) {
            if (restoredPageState && restoredCurrentPage != null) {
                outState.putBundle(STATE_CURRENT_PAGE, restoredCurrentPage);
                Parcelable[] savedHistory = new Parcelable[restoredHistory.size()];
                for (int index = 0; index < restoredHistory.size(); index++) {
                    savedHistory[index] = restoredHistory.get(index);
                }
                outState.putParcelableArray(STATE_HISTORY, savedHistory);
            }
            super.onSaveInstanceState(outState);
            return;
        }
        outState.putBundle(
            STATE_CURRENT_PAGE,
            encodePageState(page, selectedDetail, currentScrollY())
        );
        Parcelable[] savedHistory = new Parcelable[history.size()];
        for (int index = 0; index < history.size(); index++) {
            PageState state = history.get(index);
            savedHistory[index] = encodePageState(
                state.page,
                state.detail,
                state.scrollY
            );
        }
        outState.putParcelableArray(STATE_HISTORY, savedHistory);
        super.onSaveInstanceState(outState);
    }

    private void addDisclosure(String title, int count, List<View> rows) {
        addDisclosure(title, count, rows, false);
    }

    private void addDisclosure(
        String title,
        int count,
        List<View> rows,
        boolean defaultExpanded
    ) {
        MaterialCardView card = cardColumn();
        LinearLayout column = (LinearLayout) card.getTag();
        LinearLayout header = new LinearLayout(this);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);
        header.setMinimumHeight(dp(48));
        TextView titleView = new TextView(this);
        titleView.setTextAppearance(this, R.style.Widget_HET_SectionTitle);
        titleView.setText(getString(
            R.string.scene_detail_section_count,
            title,
            count
        ));
        header.addView(titleView, new LinearLayout.LayoutParams(
            0,
            ViewGroup.LayoutParams.WRAP_CONTENT,
            1f
        ));
        TextView arrow = new TextView(this);
        arrow.setTextAppearance(this, R.style.Widget_HET_SectionArrow);
        header.addView(arrow, new LinearLayout.LayoutParams(
            dp(32),
            ViewGroup.LayoutParams.WRAP_CONTENT
        ));
        header.setContentDescription(getString(
            R.string.scene_detail_expand_section,
            title
        ));

        LinearLayout body = new LinearLayout(this);
        body.setOrientation(LinearLayout.VERTICAL);
        body.setPadding(dp(12), 0, dp(12), dp(8));
        body.setVisibility(defaultExpanded ? View.VISIBLE : View.GONE);
        for (View row : rows) {
            body.addView(row);
        }
        column.setPadding(0, 0, 0, 0);
        column.addView(header, new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        ));
        column.addView(body, new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        ));
        arrow.setText(defaultExpanded
            ? R.string.array_indicator_expanded
            : R.string.array_indicator_collapsed);
        header.setContentDescription(getString(
            defaultExpanded
                ? R.string.scene_detail_collapse_section
                : R.string.scene_detail_expand_section,
            title
        ));

        header.setOnClickListener(view -> {
            boolean expanded = body.getVisibility() == View.VISIBLE;
            body.setVisibility(expanded ? View.GONE : View.VISIBLE);
            arrow.setText(expanded
                ? R.string.array_indicator_collapsed
                : R.string.array_indicator_expanded);
            header.setContentDescription(getString(
                expanded
                    ? R.string.scene_detail_expand_section
                    : R.string.scene_detail_collapse_section,
                title
            ));
        });
        content.addView(card);
    }

    private MaterialCardView cardColumn() {
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
        params.bottomMargin = dp(12);
        card.setLayoutParams(params);
        return card;
    }

    private void addField(View parent, int labelRes, String value) {
        addField(parent, getString(labelRes), value);
    }

    private String languageName(String value) {
        String code = value == null
            ? ""
            : value.trim().toLowerCase(Locale.ROOT).replace('_', '-');
        if ("zh-cn".equals(code)) {
            return getString(R.string.settings_option_zh_cn);
        }
        if ("zh-tw".equals(code)) {
            return getString(R.string.settings_option_zh_tw);
        }
        if ("en".equals(code)) {
            return getString(R.string.settings_option_en);
        }
        Locale locale = Locale.forLanguageTag(code);
        String name = locale.getDisplayName(interfaceLocale());
        return TextUtils.isEmpty(name) || name.equalsIgnoreCase(code)
            ? getString(R.string.context_detail_other_language)
            : name;
    }

    private String languageLabel(String value) {
        return value == null || value.trim().isEmpty()
            ? emptyFallback(value)
            : languageName(value);
    }

    private void addField(View parent, String label, String value) {
        if (value == null || value.trim().isEmpty()) {
            return;
        }
        addText(parent, label + "：" + value, 13, false, false);
    }

    private void addText(
        View parent,
        String value,
        int size,
        boolean bold,
        boolean secondary
    ) {
        LinearLayout column = parent instanceof MaterialCardView
            ? (LinearLayout) parent.getTag()
            : (LinearLayout) parent;
        TextView text = new TextView(this);
        text.setText(value == null ? "" : value);
        text.setTextSize(size);
        text.setAlpha(secondary ? 0.72f : 1.0f);
        if (bold) {
            text.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        }
        text.setPadding(0, dp(3), 0, dp(3));
        column.addView(text);
    }

    private void applyDangerButton(MaterialButton button) {
        button.setBackgroundTintList(ColorStateList.valueOf(
            ContextCompat.getColor(this, R.color.het_error_container)
        ));
        button.setTextColor(ContextCompat.getColor(this, R.color.het_error));
    }

    private void rememberScroll() {
        if (scrollView != null) {
            savedScrollY = scrollView.getScrollY();
        }
    }

    private int currentScrollY() {
        return scrollView == null ? Math.max(0, savedScrollY) : scrollView.getScrollY();
    }

    private void setPendingScroll(int scrollY) {
        pendingScrollY = scrollY;
        restoreScrollPending = scrollY >= 0;
    }

    private void restoreScrollIfNeeded() {
        if (scrollView == null || !restoreScrollPending) {
            return;
        }
        restoreScrollPending = false;
        final int target = Math.max(0, pendingScrollY);
        pendingScrollY = -1;
        final long expectedGeneration = renderGeneration;
        final int expectedPage = page;
        final Object expectedDetail = selectedDetail;
        scrollView.post(() -> {
            if (isActivityAlive()
                && expectedGeneration == renderGeneration
                && expectedPage == page
                && expectedDetail == selectedDetail) {
                scrollView.scrollTo(0, target);
            }
        });
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    private String displayCharacterName(
        SceneManagementDetailData.CharacterEntry entry
    ) {
        return entry != null && entry.isMainCharacter()
            ? getString(R.string.scene_detail_mc_count)
            : SceneManagementDetailData.displayCharacterName(entry, interfaceLocale());
    }

    private String displayMentionedName(
        SceneManagementDetailData.MentionedCharacterEntry entry
    ) {
        return entry != null && "mc".equals(entry.name)
            ? getString(R.string.scene_detail_mc_count)
            : SceneManagementDetailData.displayMentionedName(entry, interfaceLocale());
    }

    private String displayTermName(SceneManagementDetailData.TermEntry entry) {
        return SceneManagementDetailData.displayTermName(entry, interfaceLocale());
    }

    private String displaySpeaker(String speaker) {
        String value = speaker == null ? "" : speaker.trim();
        if (value.isEmpty()) {
            return getString(R.string.scene_detail_narrator);
        }
        return displayRelationshipTarget(value);
    }

    private List<String> displayRelationshipLabels(JSONArray values) {
        List<String> result = new ArrayList<>();
        if (values == null) {
            return result;
        }
        for (int index = 0; index < values.length(); index++) {
            JSONObject relation = values.optJSONObject(index);
            if (relation == null) {
                continue;
            }
            String target = displayRelationshipTarget(
                relation.optString("target", "")
            );
            String type = relation.optString("type", "").trim();
            String label = type.isEmpty() ? target : target + " · " + type;
            if (!label.trim().isEmpty()) {
                result.add(label);
            }
        }
        return result;
    }

    private String displayRelationshipTarget(String target) {
        String value = target == null ? "" : target.trim();
        if (value.isEmpty()) {
            return "";
        }
        if ("mc".equals(value)) {
            return getString(R.string.scene_detail_mc_count);
        }
        String sceneLabel = findSceneCharacterLabel(value);
        if (!sceneLabel.isEmpty()) {
            return sceneLabel;
        }
        return SceneManagementDetailData.relationshipTargetLabel(
            value,
            characterDictionary,
            interfaceLocale(),
            getString(R.string.scene_detail_mc_count)
        );
    }

    private String findSceneCharacterLabel(
        String identity
    ) {
        if (sceneData == null || identity == null || identity.trim().isEmpty()) {
            return "";
        }
        String exact = identity.trim();
        if (sceneData.mainCharacter != null
            && exact.equals(sceneData.mainCharacter.name)) {
            return displayCharacterName(sceneData.mainCharacter);
        }
        for (SceneManagementDetailData.CharacterEntry entry
            : sceneData.highWeightCharacters) {
            if (exact.equals(entry.name)) {
                return displayCharacterName(entry);
            }
        }
        for (SceneManagementDetailData.CharacterEntry entry
            : sceneData.lowWeightCharacters) {
            if (exact.equals(entry.name)) {
                return displayCharacterName(entry);
            }
        }
        for (SceneManagementDetailData.MentionedCharacterEntry entry
            : sceneData.mentionedCharacters) {
            if (exact.equals(entry.name)) {
                return SceneManagementDetailData.displayMentionedName(
                    entry,
                    interfaceLocale()
                );
            }
        }
        return "";
    }

    private Locale interfaceLocale() {
        android.os.LocaleList locales = getResources()
            .getConfiguration()
            .getLocales();
        return locales.isEmpty() ? Locale.getDefault() : locales.get(0);
    }

    private static String emptyFallback(String value) {
        return value == null || value.trim().isEmpty() ? "—" : value;
    }

    private static String safeMessage(Throwable throwable) {
        String message = throwable == null ? null : throwable.getMessage();
        return TextUtils.isEmpty(message)
            ? throwable == null
                ? "unknown"
                : throwable.getClass().getSimpleName()
            : message;
    }

    private boolean isActivityAlive() {
        return !isFinishing() && !isDestroyed();
    }

    @Override
    protected void onDestroy() {
        if (pendingMoveController != null) {
            pendingMoveController.close();
            pendingMoveController = null;
        }
        ioExecutor.shutdownNow();
        super.onDestroy();
    }
}
