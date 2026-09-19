package com.quarty.housamoembedtrans.ui;

import com.google.android.material.appbar.MaterialToolbar;
import com.google.android.material.button.MaterialButton;

import com.quarty.housamoembedtrans.R;
import com.quarty.housamoembedtrans.context.store.SceneContextStore;
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

import java.text.DateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
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
    /** Display-only fixture time for the in-memory style preview. */
    private static final long PREVIEW_SCENE_UPDATED_AT = 1_725_000_000_000L;

    private final ExecutorService ioExecutor = Executors.newSingleThreadExecutor();
    private final List<View> actionViews = new ArrayList<>();

    private LinearLayout content;
    private LinearLayout pageActions;
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
    private List<JSONObject> sceneContexts = new ArrayList<>();
    private List<JSONObject> sceneGroups = new ArrayList<>();
    private JSONObject sceneAnnotation = new JSONObject();
    private long sceneUpdatedAt;
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
    private boolean stylePreview;
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
        pageActions = findViewById(R.id.page_actions);
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
        stylePreview = isStylePreviewRequest();
        if (stylePreview) {
            JSONObject preview = StylePreview.payloadOf(intent);
            if (preview == null) {
                preview = StylePreview.sample(StylePreview.KIND_SCENE_DETAIL);
            }
            if (preview != null) {
                String previewName = preview.optString("scene", "").trim();
                if (!previewName.isEmpty()) {
                    sceneName = previewName;
                }
                loadPreviewData(preview);
            } else {
                status.setText(R.string.scene_detail_missing);
            }
            return;
        }
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
                java.io.File sceneFile =
                    sceneStore.getValidSceneFileByName(sceneName);
                final long loadedSceneUpdatedAt = sceneFile == null
                    ? 0L
                    : sceneFile.lastModified();
                ConfigStore configStore = new ConfigStore(this);
                final JSONObject annotations = new com.quarty.housamoembedtrans.scene.store.SceneAnnotationStore(
                    getFilesDir()).read(sceneName);
                List<JSONObject> loadedContexts = new ArrayList<>();
                List<JSONObject> loadedGroups = new ArrayList<>();
                try {
                    SceneContextStore contextStore = new SceneContextStore(this);
                    for (JSONObject context : contextStore.listContexts()) {
                        if (context != null) {
                            loadedContexts.add(new JSONObject(context.toString()));
                        }
                    }
                    for (JSONObject group : contextStore.listGroups()) {
                        if (group != null) {
                            loadedGroups.add(new JSONObject(group.toString()));
                        }
                    }
                } catch (Exception ignored) {
                    // Scene detail remains readable when optional Context metadata
                    // is unavailable; the relation line falls back to empty state.
                }
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
                    sceneAnnotation = annotations;
                    sceneContexts = loadedContexts;
                    sceneGroups = loadedGroups;
                    render(data, characters, loadedSceneUpdatedAt);
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
                    clearPageActions();
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

    private void loadPreviewData(JSONObject source) {
        try {
            sceneData = SceneManagementDetailData.readPreview(source);
            characterDictionary = null;
            sceneContexts = previewObjects(source.optJSONArray("contexts"));
            sceneGroups = previewObjects(source.optJSONArray("groups"));
            JSONObject annotation = source.optJSONObject("annotation");
            sceneAnnotation = annotation == null ? new JSONObject() : annotation;
            manualSummaries = new JSONObject();
            sceneUpdatedAt = PREVIEW_SCENE_UPDATED_AT;
            loadInFlight = false;
            render(sceneData, null, sceneUpdatedAt);
        } catch (Exception error) {
            sceneData = null;
            status.setText(getString(
                R.string.scene_detail_load_failed,
                safeMessage(error)
            ));
        }
    }

    private List<JSONObject> previewObjects(JSONArray values) {
        List<JSONObject> result = new ArrayList<>();
        for (int index = 0; values != null && index < values.length(); index++) {
            JSONObject value = values.optJSONObject(index);
            if (value != null) {
                result.add(value);
            }
        }
        return result;
    }

    private void render(
        SceneManagementDetailData.SceneData data,
        JSONObject characterDictionary,
        long updatedAt
    ) {
        if (isFinishing() || isDestroyed()) {
            return;
        }
        status.setVisibility(View.GONE);
        sceneData = data;
        this.characterDictionary = characterDictionary;
        sceneUpdatedAt = updatedAt;
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
        clearPageActions();
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
            renderPrototypeSourcePage();
        } else if (page == PAGE_CHARACTERS) {
            renderPrototypeCharactersPage();
        } else if (page == PAGE_TERMS) {
            renderPrototypeTermsPage();
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
        renderPrototypeSummaryPage();
    }

    /** Compact management-detail surface matching the navigation prototype. */
    private void renderPrototypeSummaryPage() {
        addPrototypeHeading(
            sceneData.sceneName,
            getString(R.string.detail_proto_scene_kind),
            getString(R.string.detail_proto_scene_move),
            this::moveSceneToPending
        );

        LinearLayout metadata = new LinearLayout(this);
        metadata.setOrientation(LinearLayout.VERTICAL);
        LinearLayout firstRow = new LinearLayout(this);
        firstRow.setOrientation(LinearLayout.HORIZONTAL);
        firstRow.setGravity(Gravity.TOP);
        addPrototypeMetaCell(
            firstRow,
            getString(R.string.detail_proto_scene_contexts),
            orderedSceneContextNames(),
            true
        );
        addPrototypeMetaCell(
            firstRow,
            getString(R.string.detail_proto_scene_groups),
            orderedSceneGroupNames(),
            false
        );
        metadata.addView(firstRow, fullWidthParams(8));
        addPrototypeMetaCell(
            metadata,
            getString(R.string.detail_proto_languages),
            sceneLanguageText(sceneLanguages())
        );
        String sceneTime = formatSceneTime(sceneUpdatedAt);
        if (!TextUtils.isEmpty(sceneTime)) {
            addPrototypeMetaCell(
                metadata,
                getString(R.string.detail_proto_updated_at),
                sceneTime
            );
        }
        content.addView(metadata, fullWidthParams(0));

        addPrototypeLanguageActions();
        addPrototypeSummaryCard(
            getString(R.string.detail_proto_source) + " · " + sceneData.sourceItems.size() + " 条",
            sourcePreview(),
            () -> openPage(PAGE_SOURCE, null)
        );
        addPrototypeSummaryCard(
            getString(R.string.detail_proto_main_character) + " · "
                + (sceneData.mainCharacter == null ? 0 : 1) + " 人",
            sceneCharacterPreview(singletonOrEmpty(sceneData.mainCharacter)),
            () -> openPage(PAGE_CHARACTERS, CHARACTER_SECTION_MC)
        );
        addPrototypeSummaryCard(
            getString(R.string.detail_proto_primary_characters) + " · "
                + sceneData.highWeightCharacters.size() + " 人",
            sceneCharacterPreview(sceneData.highWeightCharacters),
            () -> openPage(PAGE_CHARACTERS, CHARACTER_SECTION_HIGH)
        );
        addPrototypeSummaryCard(
            getString(R.string.detail_proto_secondary_characters) + " · "
                + sceneData.lowWeightCharacters.size() + " 人",
            sceneCharacterPreview(sceneData.lowWeightCharacters),
            () -> openPage(PAGE_CHARACTERS, CHARACTER_SECTION_LOW)
        );
        addPrototypeSummaryCard(
            getString(R.string.detail_proto_mentioned_characters) + " · "
                + sceneData.mentionedCharacters.size() + " 人",
            mentionedCharacterPreview(sceneData.mentionedCharacters),
            () -> openPage(PAGE_CHARACTERS, CHARACTER_SECTION_MENTIONED)
        );
        addPrototypeSummaryCard(
            getString(R.string.detail_proto_terms) + " · " + sceneData.terms.size() + " 个",
            termPreview(),
            () -> openPage(PAGE_TERMS, null)
        );

        if (manualSummaries.length() > 0) {
            MaterialCardView summaryCard = prototypeCard(8);
            LinearLayout summaryBody = prototypeCardBody(summaryCard);
            addPrototypeText(
                summaryBody,
                getString(R.string.scene_annotation_summaries),
                R.style.TextAppearance_HET_DetailPrototype_CardTitle,
                0,
                2
            );
            java.util.Iterator<String> languages = manualSummaries.keys();
            while (languages.hasNext()) {
                String language = languages.next();
                JSONObject record = manualSummaries.optJSONObject(language);
                String text = record == null ? "" : record.optString("text", "");
                addPrototypeText(
                    summaryBody,
                    languageName(language) + "：" + trimPreview(text),
                    R.style.TextAppearance_HET_DetailPrototype_Metadata,
                    0,
                    2
                );
            }
            content.addView(summaryCard);
        }

        MaterialButton edit = prototypeButton(
            R.string.detail_proto_edit,
            false
        );
        edit.setOnClickListener(view -> {
            if (stylePreview) {
                startActivity(StylePreview.intentFor(
                    this,
                    StylePreview.KIND_SCENE_EDITOR
                ));
                return;
            }
            if (!canStartPendingMove()) {
                return;
            }
            startActivity(new Intent(this, SceneManagementEditorActivity.class)
                .putExtra(SceneManagementEditorActivity.EXTRA_SCENE_NAME, sceneName));
        });
        registerAction(edit);
        addPageAction(edit, wrapButtonParams(0));
    }

    private void addPrototypeHeading(
        String title,
        String kindLabel,
        String actionLabel,
        Runnable action
    ) {
        LinearLayout heading = new LinearLayout(this);
        heading.setOrientation(LinearLayout.HORIZONTAL);
        heading.setGravity(Gravity.TOP);
        LinearLayout copy = new LinearLayout(this);
        copy.setOrientation(LinearLayout.VERTICAL);
        TextView titleView = prototypeTextView(
            title,
            R.style.TextAppearance_HET_DetailPrototype_Heading
        );
        TextView kindView = prototypeTextView(
            kindLabel,
            R.style.TextAppearance_HET_DetailPrototype_Kind
        );
        copy.addView(titleView);
        copy.addView(kindView, fullWidthParams(2));
        heading.addView(copy, new LinearLayout.LayoutParams(
            0,
            ViewGroup.LayoutParams.WRAP_CONTENT,
            1f
        ));
        if (action != null && actionLabel != null) {
            MaterialButton actionButton = prototypeButton(actionLabel, true);
            actionButton.setOnClickListener(view -> {
                if (!stylePreview) action.run();
            });
            actionButton.setEnabled(!stylePreview);
            if (!stylePreview) registerAction(actionButton);
            LinearLayout.LayoutParams actionParams = wrapButtonParams(0);
            actionParams.setMarginStart(dp(8));
            heading.addView(actionButton, actionParams);
        }
        content.addView(heading, fullWidthParams(10));
    }

    private void addPrototypeMetaCell(
        LinearLayout row,
        String label,
        String value,
        boolean first
    ) {
        MaterialCardView card = prototypeCard(0);
        LinearLayout body = prototypeCardBody(card);
        addPrototypeText(
            body,
            label,
            R.style.TextAppearance_HET_DetailPrototype_MetaLabel,
            0,
            2
        );
        addPrototypeText(
            body,
            emptyFallback(value),
            R.style.TextAppearance_HET_DetailPrototype_MetaValue,
            0,
            0
        );
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
            0,
            ViewGroup.LayoutParams.WRAP_CONTENT,
            1f
        );
        if (!first) {
            params.leftMargin = dp(8);
        }
        row.addView(card, params);
    }

    private void addPrototypeMetaCell(
        LinearLayout parent,
        String label,
        String value
    ) {
        MaterialCardView card = prototypeCard(0);
        LinearLayout body = prototypeCardBody(card);
        addPrototypeText(
            body,
            label,
            R.style.TextAppearance_HET_DetailPrototype_MetaLabel,
            0,
            2
        );
        addPrototypeText(
            body,
            emptyFallback(value),
            R.style.TextAppearance_HET_DetailPrototype_MetaValue,
            0,
            0
        );
        parent.addView(card, fullWidthParams(8));
    }

    private void addPrototypeLanguageActions() {
        List<String> languages = sceneLanguages();
        MaterialCardView card = prototypeCard(8);
        LinearLayout body = prototypeCardBody(card);
        addPrototypeText(
            body,
            getString(R.string.detail_proto_language_actions),
            R.style.TextAppearance_HET_DetailPrototype_CardTitle,
            0,
            4
        );
        LinearLayout actions = new LinearLayout(this);
        actions.setOrientation(LinearLayout.HORIZONTAL);
        actions.setGravity(Gravity.CENTER_VERTICAL);
        actions.setPadding(0, 0, 0, 0);
        boolean hasAction = false;
        for (String language : languages) {
            if (stylePreview) {
                break;
            }
            MaterialButton button = prototypeButton(
                getString(
                    R.string.scene_detail_move_language_pending,
                    languageName(language)
                ),
                true
            );
            button.setOnClickListener(view -> moveLanguageToPending(language));
            registerAction(button);
            LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                dp(34)
            );
            params.rightMargin = dp(6);
            actions.addView(button, params);
            hasAction = true;
        }
        if (!hasAction) {
            addPrototypeText(
                body,
                getString(R.string.detail_proto_no_language_action),
                R.style.TextAppearance_HET_DetailPrototype_Metadata,
                0,
                0
            );
        } else {
            body.addView(actions, fullWidthParams(0));
        }
        content.addView(card);
    }

    private void addPrototypeSummaryCard(
        String title,
        String preview,
        Runnable action
    ) {
        MaterialCardView card = prototypeCard(8);
        card.setClickable(true);
        card.setFocusable(true);
        card.setOnClickListener(view -> action.run());
        LinearLayout body = prototypeCardBody(card);
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout copy = new LinearLayout(this);
        copy.setOrientation(LinearLayout.VERTICAL);
        TextView titleView = prototypeTextView(
            title,
            R.style.TextAppearance_HET_DetailPrototype_CardTitle,
            false
        );
        titleView.setSingleLine(true);
        titleView.setMaxLines(1);
        titleView.setEllipsize(TextUtils.TruncateAt.END);
        titleView.setTextSize(12);
        titleView.setTypeface(Typeface.DEFAULT, Typeface.NORMAL);
        copy.addView(titleView, fullWidthParams(3));
        TextView previewView = prototypeTextView(
            emptyFallback(trimPreview(preview)),
            R.style.TextAppearance_HET_DetailPrototype_CardPreview,
            false
        );
        previewView.setSingleLine(true);
        previewView.setMaxLines(1);
        previewView.setEllipsize(TextUtils.TruncateAt.END);
        previewView.setTextSize(13);
        previewView.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        copy.addView(previewView, fullWidthParams(0));
        row.addView(copy, new LinearLayout.LayoutParams(
            0,
            ViewGroup.LayoutParams.WRAP_CONTENT,
            1f
        ));
        TextView arrow = prototypeTextView(
            "›",
            R.style.TextAppearance_HET_DetailPrototype_MetaLabel,
            false
        );
        arrow.setTextSize(24);
        arrow.setGravity(Gravity.CENTER);
        row.addView(arrow, new LinearLayout.LayoutParams(dp(20), dp(28)));
        body.addView(row, fullWidthParams(0));
        content.addView(card);
    }

    private void renderPrototypeSourcePage() {
        String language = currentTranslationLanguage(sceneLanguages());
        addPrototypeHeading(
            sceneData.sceneName + " · "
                + getString(R.string.detail_proto_scene_source_heading),
            getString(
                R.string.detail_proto_scene_source_description,
                language == null
                    ? getString(R.string.detail_proto_no_language)
                    : languageName(language)
            ),
            null,
            null
        );
        JSONObject source = sceneData == null ? null : sceneData.source;
        JSONArray sceneItems = source == null
            ? null
            : source.optJSONArray("scene_items");
        if (sceneItems == null || sceneItems.length() == 0) {
            addPrototypeText(
                content,
                getString(R.string.detail_proto_scene_no_source),
                R.style.TextAppearance_HET_DetailPrototype_Metadata,
                0,
                0
            );
            return;
        }
        addPrototypeSceneItems(content, sceneItems, language, new int[] {0});
    }

    private void addPrototypeSceneItems(
        LinearLayout parent,
        JSONArray items,
        String language,
        int[] fallbackCounter
    ) {
        for (int index = 0; items != null && index < items.length(); index++) {
            JSONObject item = items.optJSONObject(index);
            if (item == null) {
                continue;
            }
            addPrototypeSceneItem(parent, item, language, fallbackCounter);
        }
    }

    private void addPrototypeSceneItem(
        LinearLayout parent,
        JSONObject item,
        String language,
        int[] fallbackCounter
    ) {
        String orderLabel = SceneManagementDetailData.orderLabel(
            item,
            fallbackCounter[0]++,
            sceneData.sequenceByOrder
        );
        String type = item.optString("type", "text").trim();
        if ("choice".equals(type)) {
            addPrototypeChoiceItem(parent, item, language, orderLabel, fallbackCounter);
        } else if ("if".equals(type)) {
            addPrototypeIfItem(parent, item, language, orderLabel, fallbackCounter);
        } else {
            addPrototypeTextItem(parent, item, language, orderLabel);
        }
    }

    private void addPrototypeTextItem(
        LinearLayout parent,
        JSONObject item,
        String language,
        String orderLabel
    ) {
        MaterialCardView card = prototypeCard(8);
        LinearLayout body = prototypeCardBody(card);
        addPrototypeSceneMetadata(
            body,
            "#" + orderLabel,
            displaySpeaker(item.optString("speaker", ""))
        );

        String translation = SceneManagementDetailData.restoreProtectedText(
            sceneItemTranslation(item, language),
            sceneData.protectedTokens
        );
        addPrototypeText(
            body,
            translation.isEmpty()
                ? getString(R.string.detail_proto_scene_no_translation)
                : translation,
            R.style.TextAppearance_HET_DetailPrototype_Translation,
            0,
            0
        );
        String original = SceneManagementDetailData.restoreProtectedText(
            item.optString("text", ""),
            sceneData.protectedTokens
        );
        if (!original.isEmpty()) {
            addPrototypeText(
                body,
                original,
                R.style.TextAppearance_HET_DetailPrototype_Original,
                3,
                0
            );
        }
        parent.addView(card);
    }

    private void addPrototypeChoiceItem(
        LinearLayout parent,
        JSONObject item,
        String language,
        String orderLabel,
        int[] fallbackCounter
    ) {
        MaterialCardView card = prototypeCard(8);
        LinearLayout body = prototypeCardBody(card);
        addPrototypeSceneMetadata(
            body,
            "#" + orderLabel,
            getString(R.string.detail_proto_scene_choice)
        );

        JSONArray directOptions = item.optJSONArray("options");
        if (directOptions != null && directOptions.length() > 0) {
            LinearLayout optionsContainer = prototypeNestedContainer();
            addPrototypeOptionItems(optionsContainer, directOptions, language);
            if (optionsContainer.getChildCount() > 0) {
                body.addView(optionsContainer, fullWidthParams(0));
            }
        }

        JSONArray branches = item.optJSONArray("branches");
        for (int branchIndex = 0;
             branches != null && branchIndex < branches.length();
             branchIndex++) {
            JSONObject branch = branches.optJSONObject(branchIndex);
            if (branch == null) {
                continue;
            }
            LinearLayout branchContainer = prototypeBranchContainer();
            addPrototypeText(
                branchContainer,
                getString(R.string.detail_proto_scene_branch, branchIndex + 1),
                R.style.TextAppearance_HET_DetailPrototype_Metadata,
                0,
                0
            );
            String target = branch.optString("target_label", "").trim();
            if (!target.isEmpty()) {
                addPrototypeText(
                    branchContainer,
                    getString(R.string.detail_proto_scene_jump_target, target),
                    R.style.TextAppearance_HET_DetailPrototype_Metadata,
                    3,
                    0
                );
            }
            addPrototypeOptionItems(
                branchContainer,
                branch.optJSONArray("options"),
                language
            );
            addPrototypeSceneItems(
                branchContainer,
                branch.optJSONArray("following_text"),
                language,
                fallbackCounter
            );
            LinearLayout.LayoutParams branchParams = fullWidthParams(0);
            if (branchIndex > 0) {
                branchParams.topMargin = dp(6);
            }
            body.addView(branchContainer, branchParams);
        }
        String mergeLabel = item.optString("merge_label", "").trim();
        if (!mergeLabel.isEmpty()) {
            addPrototypeText(
                body,
                getString(R.string.detail_proto_scene_merge_label, mergeLabel),
                R.style.TextAppearance_HET_DetailPrototype_Metadata,
                3,
                0
            );
        }
        parent.addView(card);
    }

    private void addPrototypeIfItem(
        LinearLayout parent,
        JSONObject item,
        String language,
        String orderLabel,
        int[] fallbackCounter
    ) {
        MaterialCardView card = prototypeCard(8);
        LinearLayout body = prototypeCardBody(card);
        addPrototypeSceneMetadata(
            body,
            "#" + orderLabel,
            getString(R.string.detail_proto_scene_if)
        );

        String condition = SceneManagementDetailData.restoreProtectedText(
            item.optString("condition", ""),
            sceneData.protectedTokens
        ).trim();
        if (!condition.isEmpty()) {
            addPrototypeText(
                body,
                getString(R.string.detail_proto_scene_condition, condition),
                R.style.TextAppearance_HET_DetailPrototype_Metadata,
                3,
                0
            );
        }
        String target = item.optString("target_label", "").trim();
        if (!target.isEmpty()) {
            addPrototypeText(
                body,
                getString(R.string.detail_proto_scene_jump_target, target),
                R.style.TextAppearance_HET_DetailPrototype_Metadata,
                3,
                0
            );
        }
        LinearLayout followingContainer = prototypeNestedContainer();
        addPrototypeSceneItems(
            followingContainer,
            item.optJSONArray("following_text"),
            language,
            fallbackCounter
        );
        if (followingContainer.getChildCount() > 0) {
            body.addView(followingContainer, fullWidthParams(0));
        }
        String mergeLabel = item.optString("merge_label", "").trim();
        if (!mergeLabel.isEmpty()) {
            addPrototypeText(
                body,
                getString(R.string.detail_proto_scene_merge_label, mergeLabel),
                R.style.TextAppearance_HET_DetailPrototype_Metadata,
                3,
                0
            );
        }
        parent.addView(card);
    }

    private void addPrototypeOptionItems(
        LinearLayout parent,
        JSONArray options,
        String language
    ) {
        for (int index = 0; options != null && index < options.length(); index++) {
            JSONObject option = options.optJSONObject(index);
            if (option == null) {
                continue;
            }
            String optionText = SceneManagementDetailData.restoreProtectedText(
                option.optString("text", ""),
                sceneData.protectedTokens
            );
            String translation = SceneManagementDetailData.restoreProtectedText(
                sceneItemTranslation(option, language),
                sceneData.protectedTokens
            );
            if (optionText.isEmpty() && translation.isEmpty()) {
                continue;
            }
            String target = option.optString("target_label", "").trim();
            LinearLayout optionContainer = new LinearLayout(this);
            optionContainer.setOrientation(LinearLayout.VERTICAL);
            optionContainer.setPadding(0, dp(3), 0, dp(2));
            if (!optionText.isEmpty()) {
                addPrototypeText(
                    optionContainer,
                    "• " + optionText,
                    R.style.TextAppearance_HET_DetailPrototype_Metadata,
                    0,
                    0
                );
            }
            if (!translation.isEmpty()) {
                addPrototypeText(
                    optionContainer,
                    "↳ " + translation,
                    R.style.TextAppearance_HET_DetailPrototype_Metadata,
                    2,
                    0
                );
            }
            if (!target.isEmpty()) {
                addPrototypeText(
                    optionContainer,
                    getString(R.string.detail_proto_scene_jump_target, target),
                    R.style.TextAppearance_HET_DetailPrototype_Metadata,
                    2,
                    0
                );
            }
            parent.addView(optionContainer, fullWidthParams(0));
        }
    }

    private void addPrototypeSceneMetadata(
        LinearLayout body,
        String orderLabel,
        String label
    ) {
        LinearLayout metadata = new LinearLayout(this);
        metadata.setOrientation(LinearLayout.HORIZONTAL);
        TextView order = prototypeTextView(
            orderLabel,
            R.style.TextAppearance_HET_DetailPrototype_Metadata
        );
        metadata.addView(order, new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        ));
        TextView type = prototypeTextView(
            label,
            R.style.TextAppearance_HET_DetailPrototype_Metadata
        );
        LinearLayout.LayoutParams typeParams = new LinearLayout.LayoutParams(
            0,
            ViewGroup.LayoutParams.WRAP_CONTENT,
            1f
        );
        typeParams.leftMargin = dp(7);
        metadata.addView(type, typeParams);
        body.addView(metadata, fullWidthParams(4));
    }

    private LinearLayout prototypeNestedContainer() {
        LinearLayout nested = new LinearLayout(this);
        nested.setOrientation(LinearLayout.VERTICAL);
        nested.setPadding(dp(8), dp(4), 0, 0);
        return nested;
    }

    private LinearLayout prototypeBranchContainer() {
        LinearLayout branch = new LinearLayout(this);
        branch.setOrientation(LinearLayout.VERTICAL);
        branch.setPadding(dp(8), dp(7), 0, dp(2));
        return branch;
    }

    private void renderPrototypeCharactersPage() {
        addPrototypeHeading(
            sceneData.sceneName + " · "
                + getString(R.string.detail_proto_characters),
            getString(R.string.detail_proto_scene_characters_description),
            null,
            null
        );
        addPrototypeCharacterSection(
            getString(R.string.detail_proto_main_character),
            singletonOrEmpty(sceneData.mainCharacter),
            CHARACTER_SECTION_MC
        );
        addPrototypeCharacterSection(
            getString(R.string.detail_proto_primary_characters),
            sceneData.highWeightCharacters,
            CHARACTER_SECTION_HIGH
        );
        addPrototypeCharacterSection(
            getString(R.string.detail_proto_secondary_characters),
            sceneData.lowWeightCharacters,
            CHARACTER_SECTION_LOW
        );
        addPrototypeMentionedSection();
    }

    private void addPrototypeCharacterSection(
        String label,
        List<SceneManagementDetailData.CharacterEntry> entries,
        String section
    ) {
        addPrototypeText(
            content,
            getString(R.string.detail_proto_scene_character_group_count, label, entries.size()),
            R.style.TextAppearance_HET_DetailPrototype_CardTitle,
            2,
            4
        );
        if (entries.isEmpty()) {
            addPrototypeText(
                content,
                getString(R.string.detail_proto_no_preview),
                R.style.TextAppearance_HET_DetailPrototype_Metadata,
                0,
                6
            );
            return;
        }
        for (SceneManagementDetailData.CharacterEntry entry : entries) {
            addPrototypeEntityCard(
                displayCharacterName(entry),
                entry.temporary
                    ? getString(R.string.scene_detail_temporary_character, entry.name)
                    : getString(R.string.scene_detail_dictionary_character, entry.name),
                () -> openPage(PAGE_CHARACTER_DETAIL, entry)
            );
        }
    }

    private void addPrototypeMentionedSection() {
        String label = getString(R.string.detail_proto_mentioned_characters);
        addPrototypeText(
            content,
            getString(
                R.string.detail_proto_scene_character_group_count,
                label,
                sceneData.mentionedCharacters.size()
            ),
            R.style.TextAppearance_HET_DetailPrototype_CardTitle,
            2,
            4
        );
        if (sceneData.mentionedCharacters.isEmpty()) {
            addPrototypeText(
                content,
                getString(R.string.detail_proto_no_preview),
                R.style.TextAppearance_HET_DetailPrototype_Metadata,
                0,
                6
            );
            return;
        }
        for (SceneManagementDetailData.MentionedCharacterEntry entry
            : sceneData.mentionedCharacters) {
            addPrototypeEntityCard(
                displayMentionedName(entry),
                entry.temporary
                    ? getString(R.string.scene_detail_temporary_character, entry.name)
                    : getString(R.string.scene_detail_dictionary_character, entry.name),
                () -> openPage(PAGE_MENTIONED_CHARACTER_DETAIL, entry)
            );
        }
    }

    private void addPrototypeEntityCard(
        String title,
        String subtitle,
        Runnable action
    ) {
        MaterialCardView card = prototypeCard(6);
        card.setClickable(true);
        card.setFocusable(true);
        card.setOnClickListener(view -> action.run());
        LinearLayout body = prototypeCardBody(card);
        addPrototypeText(
            body,
            title,
            R.style.TextAppearance_HET_DetailPrototype_MetaValue,
            0,
            2,
            false
        );
        addPrototypeText(
            body,
            subtitle,
            R.style.TextAppearance_HET_DetailPrototype_Metadata,
            0,
            0,
            false
        );
        content.addView(card);
    }

    private void renderPrototypeTermsPage() {
        addPrototypeHeading(
            sceneData.sceneName + " · "
                + getString(R.string.detail_proto_terms),
            getString(R.string.detail_proto_scene_terms_description),
            null,
            null
        );
        if (sceneData.terms.isEmpty()) {
            addPrototypeText(
                content,
                getString(R.string.detail_proto_no_preview),
                R.style.TextAppearance_HET_DetailPrototype_Metadata,
                0,
                0
            );
            return;
        }
        for (SceneManagementDetailData.TermEntry entry : sceneData.terms) {
            addPrototypeEntityCard(
                displayTermName(entry),
                entry.temporary
                    ? getString(R.string.scene_detail_temporary_term, entry.term)
                    : getString(R.string.scene_detail_dictionary_term, entry.term),
                () -> openPage(PAGE_TERM_DETAIL, entry)
            );
        }
    }

    private MaterialCardView prototypeCard(int bottomMargin) {
        MaterialCardView card = new MaterialCardView(
            new ContextThemeWrapper(this, R.style.Widget_HET_DetailCard)
        );
        card.setCardElevation(0);
        card.setRadius(dp(14));
        card.setCardBackgroundColor(ContextCompat.getColor(
            this,
            R.color.het_surface_container
        ));
        card.setStrokeWidth(0);
        LinearLayout body = new LinearLayout(this);
        body.setOrientation(LinearLayout.VERTICAL);
        body.setPadding(dp(11), dp(10), dp(11), dp(10));
        card.addView(body);
        card.setTag(body);
        card.setLayoutParams(fullWidthParams(bottomMargin));
        return card;
    }

    private LinearLayout prototypeCardBody(MaterialCardView card) {
        return (LinearLayout) card.getTag();
    }

    private TextView prototypeTextView(String value, int style) {
        return prototypeTextView(value, style, true);
    }

    private TextView prototypeTextView(
        String value,
        int style,
        boolean selectable
    ) {
        TextView text = new TextView(this);
        text.setTextAppearance(this, style);
        text.setText(value == null ? "" : value);
        text.setTextIsSelectable(selectable);
        return text;
    }

    private void addPrototypeText(
        View parent,
        String value,
        int style,
        int topMargin,
        int bottomMargin
    ) {
        addPrototypeText(
            parent,
            value,
            style,
            topMargin,
            bottomMargin,
            true
        );
    }

    private void addPrototypeText(
        View parent,
        String value,
        int style,
        int topMargin,
        int bottomMargin,
        boolean selectable
    ) {
        LinearLayout column = parent instanceof MaterialCardView
            ? prototypeCardBody((MaterialCardView) parent)
            : (LinearLayout) parent;
        TextView text = prototypeTextView(value, style, selectable);
        LinearLayout.LayoutParams params = fullWidthParams(bottomMargin);
        params.topMargin = dp(topMargin);
        column.addView(text, params);
    }

    private MaterialButton prototypeButton(String value, boolean danger) {
        MaterialButton button = new MaterialButton(
            new ContextThemeWrapper(
                this,
                danger
                    ? R.style.Widget_HET_DetailPrototype_Button_Danger
                    : R.style.Widget_HET_DetailPrototype_Button
            )
        );
        button.setText(value);
        button.setAllCaps(false);
        button.setTextSize(13);
        button.setMinWidth(0);
        button.setMinHeight(0);
        button.setMinimumHeight(0);
        button.setInsetTop(0);
        button.setInsetBottom(0);
        button.setPadding(dp(12), 0, dp(12), 0);
        button.setCornerRadius(dp(17));
        if (danger) {
            button.setBackgroundTintList(ColorStateList.valueOf(
                ContextCompat.getColor(this, R.color.het_error)
            ));
            button.setTextColor(ContextCompat.getColor(
                this,
                R.color.het_on_error
            ));
            button.setStrokeWidth(0);
        } else {
            button.setBackgroundTintList(ColorStateList.valueOf(
                ContextCompat.getColor(
                    this,
                    R.color.het_surface_container_high
                )
            ));
            button.setTextColor(ContextCompat.getColor(
                this,
                R.color.het_on_surface
            ));
            button.setStrokeWidth(dp(1));
            button.setStrokeColor(ColorStateList.valueOf(
                ContextCompat.getColor(this, R.color.het_outline_soft)
            ));
        }
        return button;
    }

    private MaterialButton prototypeButton(int labelResource, boolean danger) {
        return prototypeButton(getString(labelResource), danger);
    }

    private LinearLayout.LayoutParams fullWidthParams(int bottomMargin) {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        );
        params.bottomMargin = dp(bottomMargin);
        return params;
    }

    private LinearLayout.LayoutParams wrapButtonParams(int bottomMargin) {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT,
            dp(34)
        );
        params.gravity = Gravity.END;
        params.bottomMargin = dp(bottomMargin);
        return params;
    }

    private List<SceneManagementDetailData.CharacterEntry> singletonOrEmpty(
        SceneManagementDetailData.CharacterEntry entry
    ) {
        List<SceneManagementDetailData.CharacterEntry> result = new ArrayList<>();
        if (entry != null) {
            result.add(entry);
        }
        return result;
    }

    private String sourcePreview() {
        for (SceneManagementDetailData.SourceItem item : sceneData.sourceItems) {
            String value = SceneManagementDetailData.restoreProtectedText(
                item.source.optString("text", ""),
                sceneData.protectedTokens
            ).trim();
            if (!value.isEmpty()) {
                return value;
            }
        }
        return getString(R.string.detail_proto_no_preview);
    }

    private String sceneCharacterPreview(
        List<SceneManagementDetailData.CharacterEntry> entries
    ) {
        List<String> names = new ArrayList<>();
        for (SceneManagementDetailData.CharacterEntry entry : entries) {
            String name = displayCharacterName(entry);
            if (!name.isEmpty()) {
                names.add(name);
            }
        }
        return names.isEmpty()
            ? getString(R.string.detail_proto_no_preview)
            : TextUtils.join("、", names);
    }

    private String mentionedCharacterPreview(
        List<SceneManagementDetailData.MentionedCharacterEntry> entries
    ) {
        List<String> names = new ArrayList<>();
        for (SceneManagementDetailData.MentionedCharacterEntry entry : entries) {
            String name = displayMentionedName(entry);
            if (!name.isEmpty()) {
                names.add(name);
            }
        }
        return names.isEmpty()
            ? getString(R.string.detail_proto_no_preview)
            : TextUtils.join("、", names);
    }

    private String termPreview() {
        List<String> names = new ArrayList<>();
        for (SceneManagementDetailData.TermEntry entry : sceneData.terms) {
            String name = displayTermName(entry);
            if (!name.isEmpty()) {
                names.add(name);
            }
        }
        return names.isEmpty()
            ? getString(R.string.detail_proto_no_preview)
            : TextUtils.join("、", names);
    }

    private String trimPreview(String value) {
        String text = value == null ? "" : value.trim().replace('\n', ' ');
        if (text.length() <= 120) {
            return text;
        }
        return text.substring(0, 117) + "…";
    }

    private List<String> sceneLanguages() {
        Set<String> languages = new LinkedHashSet<>();
        if (sceneData != null && sceneData.languages != null) {
            languages.addAll(sceneData.languages);
        }
        JSONObject source = sceneData == null ? null : sceneData.source;
        addLanguageKeys(source == null ? null : source.optJSONObject("translated"), languages, true);
        addLanguageKeys(source == null ? null : source.optJSONObject("provider"), languages, false);
        addLanguageKeys(source == null ? null : source.optJSONObject("model"), languages, false);
        for (SceneManagementDetailData.SourceItem item
            : sceneData == null ? new ArrayList<SceneManagementDetailData.SourceItem>()
                : sceneData.sourceItems) {
            addLanguageKeys(item.source.optJSONObject("translations"), languages, true);
            addLanguageKeys(item.source.optJSONObject("translated"), languages, true);
        }
        return new ArrayList<>(languages);
    }

    private void addLanguageKeys(
        JSONObject values,
        Set<String> output,
        boolean requireValue
    ) {
        if (values == null) {
            return;
        }
        java.util.Iterator<String> keys = values.keys();
        while (keys.hasNext()) {
            String key = keys.next();
            if (!requireValue || translationValueText(values.opt(key)) != null
                || values.optBoolean(key, false)) {
                output.add(key);
            }
        }
    }

    private String sceneLanguageText(List<String> languages) {
        if (languages == null || languages.isEmpty()) {
            return getString(R.string.detail_proto_no_language);
        }
        List<String> labels = new ArrayList<>();
        for (String language : languages) {
            labels.add(languageName(language));
        }
        return TextUtils.join("、", labels);
    }

    private String currentTranslationLanguage(List<String> languages) {
        if (languages == null || languages.isEmpty()) {
            return null;
        }
        String[] priority = new String[] {"zh-cn", "en", "ja", "ko"};
        for (String preferred : priority) {
            if (languages.contains(preferred)) {
                return preferred;
            }
        }
        return languages.get(0);
    }

    private String sceneItemTranslation(JSONObject item, String language) {
        if (item == null || language == null || language.isEmpty()) {
            return "";
        }
        String value = translationValueText(
            item.optJSONObject("translations") == null
                ? null
                : item.optJSONObject("translations").opt(language)
        );
        if (value == null) {
            value = translationValueText(
                item.optJSONObject("translated") == null
                    ? null
                    : item.optJSONObject("translated").opt(language)
            );
        }
        if (value == null && sceneData != null && sceneData.source != null) {
            JSONObject languages = sceneData.source.optJSONObject("translated");
            JSONObject record = languages == null ? null : languages.optJSONObject(language);
            JSONObject items = record == null ? null : record.optJSONObject("items");
            if (items == null) {
                items = record;
            }
            String itemId = item.optString("id", "");
            if (items != null && !itemId.isEmpty()) {
                value = translationValueText(items.opt(itemId));
            }
        }
        return value == null ? "" : value;
    }

    private String translationValueText(Object value) {
        if (value == null || value == JSONObject.NULL) {
            return null;
        }
        if (value instanceof String) {
            String text = ((String) value).trim();
            return text.isEmpty() ? null : text;
        }
        if (!(value instanceof JSONObject)) {
            return null;
        }
        JSONObject record = (JSONObject) value;
        String[] fields = new String[] {
            "text", "current", "final", "manual", "translation",
            "translatedText", "currentTranslation", "finalTranslation"
        };
        for (String field : fields) {
            String text = translationValueText(record.opt(field));
            if (text != null) {
                return text;
            }
        }
        return null;
    }

    private void registerAction(View action) {
        actionViews.add(action);
    }

    private void addPageAction(View action, LinearLayout.LayoutParams params) {
        params.gravity = Gravity.CENTER_VERTICAL;
        if (pageActions.getChildCount() > 0) {
            params.leftMargin = dp(6);
        }
        pageActions.addView(action, params);
        pageActions.setVisibility(View.VISIBLE);
    }

    private void clearPageActions() {
        pageActions.removeAllViews();
        pageActions.setVisibility(View.GONE);
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

    private String orderedSceneContextNames() {
        List<JSONObject> associated = new ArrayList<>();
        java.util.LinkedHashSet<String> remaining = new java.util.LinkedHashSet<>();
        for (JSONObject context : sceneContexts) {
            String id = context == null ? "" : context.optString("id", "");
            if (!id.isEmpty() && containsScene(context, sceneName)) {
                remaining.add(id);
            }
        }
        JSONArray order = sceneAnnotation == null
            ? null
            : sceneAnnotation.optJSONArray("context_order");
        for (int index = 0; order != null && index < order.length(); index++) {
            String id = order.optString(index, "");
            if (remaining.remove(id)) {
                JSONObject context = findContext(id);
                if (context != null) associated.add(context);
            }
        }
        for (String id : remaining) {
            JSONObject context = findContext(id);
            if (context != null) associated.add(context);
        }
        if (associated.isEmpty()) {
            return getString(R.string.scene_detail_no_contexts);
        }
        List<String> names = new ArrayList<>();
        for (JSONObject context : associated) {
            names.add(context.optString("display_name", context.optString("id", "")));
        }
        return TextUtils.join("、", names);
    }

    private String orderedSceneGroupNames() {
        LinkedHashSet<String> names = new LinkedHashSet<>();
        Set<String> contextIds = new LinkedHashSet<>();
        for (JSONObject context : sceneContexts) {
            String id = context == null ? "" : context.optString("id", "");
            if (!id.isEmpty() && containsScene(context, sceneName)) {
                contextIds.add(id);
            }
        }
        for (JSONObject group : sceneGroups) {
            if (group == null) {
                continue;
            }
            JSONArray members = group.optJSONArray("contexts");
            boolean associated = false;
            for (int index = 0;
                 members != null && index < members.length();
                 index++) {
                JSONObject member = members.optJSONObject(index);
                if (member != null && contextIds.contains(
                    member.optString("context_id", "")
                )) {
                    associated = true;
                    break;
                }
            }
            if (associated) {
                String label = group.optString("display_name", "").trim();
                if (label.isEmpty()) {
                    label = group.optString("id", "").trim();
                }
                if (!label.isEmpty()) {
                    names.add(label);
                }
            }
        }
        return names.isEmpty()
            ? getString(R.string.detail_proto_no_context)
            : TextUtils.join("、", names);
    }

    private JSONObject findContext(String id) {
        for (JSONObject context : sceneContexts) {
            if (context != null && id.equals(context.optString("id", ""))) return context;
        }
        return null;
    }

    private static boolean containsScene(JSONObject context, String scene) {
        JSONArray scenes = context == null ? null : context.optJSONArray("scenes");
        for (int index = 0; scenes != null && index < scenes.length(); index++) {
            JSONObject entry = scenes.optJSONObject(index);
            if (entry != null && scene.equals(entry.optString("scene", ""))) return true;
        }
        return false;
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

    @Override
    protected void onResume() {
        super.onResume();
        resumed = true;
        if (!stylePreview && hasResumed && !operationBusy && !loadInFlight) {
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
        text.setTextSize(size + 2);
        text.setAlpha(secondary ? 0.72f : 1.0f);
        if (bold) {
            text.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        }
        text.setPadding(0, dp(3), 0, dp(3));
        column.addView(text);
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

    private String formatSceneTime(long timestamp) {
        if (timestamp <= 0L) {
            return "";
        }
        return DateFormat.getDateTimeInstance(
            DateFormat.SHORT,
            DateFormat.SHORT,
            interfaceLocale()
        ).format(new Date(timestamp));
    }

    private boolean isStylePreviewRequest() {
        return StylePreview.isEnabled(this)
            && StylePreview.KIND_SCENE_DETAIL.equals(
                StylePreview.kindOf(getIntent())
            );
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
