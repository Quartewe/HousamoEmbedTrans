package com.quarty.housamoembedtrans.ui;

import com.quarty.housamoembedtrans.R;
import com.quarty.housamoembedtrans.context.store.SceneContextStore;
import com.quarty.housamoembedtrans.scene.store.SceneStore;
import com.quarty.housamoembedtrans.storage.config.ConfigStore;

import android.content.Intent;
import android.content.res.ColorStateList;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.text.TextUtils;
import android.view.ContextThemeWrapper;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.activity.OnBackPressedCallback;
import androidx.core.content.ContextCompat;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.widget.NestedScrollView;

import com.google.android.material.appbar.MaterialToolbar;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.card.MaterialCardView;

import org.json.JSONArray;
import org.json.JSONObject;

import java.text.DateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Read-only detail surface for one persisted Context or Group document. */
public final class ContextManagementDetailActivity extends AppCompatActivity {
    public static final String EXTRA_KIND =
        "com.quarty.housamoembedtrans.ui.EXTRA_CONTEXT_GROUP_KIND";
    public static final String EXTRA_ID =
        "com.quarty.housamoembedtrans.ui.EXTRA_CONTEXT_GROUP_ID";

    private static final String KIND_CONTEXT = "context";
    private static final String KIND_GROUP = "group";
    private static final String STATE_EXPANDED = "context_detail.expanded";
    private static final String STATE_SCROLL_Y = "context_detail.scroll_y";
    private static final String STATE_SCENE_LIST_SCROLL_Y =
        "context_detail.scene_list_scroll_y";
    private static final String STATE_LAYER = "context_detail.layer";
    private static final String LAYER_DETAIL = "detail";
    private static final String LAYER_GROUP_SCENES = "group_scenes";
    private static final String LAYER_SUMMARY = "summary";
    private static final String LAYER_RELATIONS = "relations";
    private static final String LAYER_SCENES = "scenes";
    private static final String LAYER_MEMBERS = "members";
    private static final String LAYER_CHARACTERS = "characters";
    private static final String LAYER_TERMS = "terms";
    private static final String LAYER_SUMMARIES = "summaries";
    private static final String LAYER_MANUAL = "manual";

    private final ExecutorService ioExecutor = Executors.newSingleThreadExecutor();
    private final Set<String> expandedSections = new LinkedHashSet<>();

    private NestedScrollView scrollView;
    private MaterialToolbar toolbar;
    private TextView status;
    private LinearLayout content;
    private LinearLayout pageActions;
    private String kind;
    private String objectId;
    private LoadedData loadedData;
    private long loadGeneration;
    private boolean lifecycleStarted;
    private boolean destroyed;
    private boolean editingAllowed;
    private boolean stylePreview;
    private PendingProcessMoveController pendingMoveController;
    private boolean pendingMoveBusy;
    private MaterialButton editAction;
    private MaterialButton moveAction;
    private boolean showingGroupSceneList;
    private String detailLayer = LAYER_SUMMARY;
    private SceneContextStore.ManualClosureState closureState;
    private int savedScrollY = -1;
    private int savedSceneListScrollY = -1;

    private static final class GroupLink {
        final String id;
        final String displayName;

        GroupLink(String id, String displayName) {
            this.id = id;
            this.displayName = displayName;
        }
    }

    private static final class ContextMember {
        final String id;
        final String displayName;
        final boolean available;

        ContextMember(String id, String displayName, boolean available) {
            this.id = id;
            this.displayName = displayName;
            this.available = available;
        }
    }

    private static final class SceneLink {
        final String sceneName;
        final boolean available;

        SceneLink(String sceneName, boolean available) {
            this.sceneName = sceneName;
            this.available = available;
        }
    }

    private static final class AggregateCharacter {
        final Object entry;
        final String sceneName;

        AggregateCharacter(Object entry, String sceneName) {
            this.entry = entry;
            this.sceneName = sceneName;
        }
    }

    private static final class AggregateTerm {
        final SceneManagementDetailData.TermEntry entry;
        final String sceneName;

        AggregateTerm(
            SceneManagementDetailData.TermEntry entry,
            String sceneName
        ) {
            this.entry = entry;
            this.sceneName = sceneName;
        }
    }

    private static final class AggregateData {
        final List<AggregateCharacter> primaryCharacters;
        final List<AggregateCharacter> secondaryCharacters;
        final List<AggregateTerm> terms;
        final int missingSceneCount;

        AggregateData(
            List<AggregateCharacter> primaryCharacters,
            List<AggregateCharacter> secondaryCharacters,
            List<AggregateTerm> terms,
            int missingSceneCount
        ) {
            this.primaryCharacters = primaryCharacters;
            this.secondaryCharacters = secondaryCharacters;
            this.terms = terms;
            this.missingSceneCount = missingSceneCount;
        }
    }

    private static final class SceneLoadResult {
        final List<SceneLink> sceneLinks;
        final AggregateData aggregate;

        SceneLoadResult(
            List<SceneLink> sceneLinks,
            AggregateData aggregate
        ) {
            this.sceneLinks = sceneLinks;
            this.aggregate = aggregate;
        }
    }

    private static final class LoadedData {
        final JSONObject document;
        final List<GroupLink> groups;
        final List<ContextMember> members;
        final List<SceneLink> scenes;
        final AggregateData aggregate;
        final String activeContextId;
        final String activeGroupId;
        final SceneContextStore.ManualClosureState closureState;

        LoadedData(
            JSONObject document,
            List<GroupLink> groups,
            List<ContextMember> members,
            List<SceneLink> scenes,
            AggregateData aggregate,
            String activeContextId,
            String activeGroupId,
            SceneContextStore.ManualClosureState closureState
        ) {
            this.document = document;
            this.groups = groups;
            this.members = members;
            this.scenes = scenes;
            this.aggregate = aggregate;
            this.activeContextId = activeContextId;
            this.activeGroupId = activeGroupId;
            this.closureState = closureState;
        }
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_context_management_detail);
        SystemBarInsets.apply(findViewById(R.id.root_context_management_detail));

        String restoredLayer = LAYER_SUMMARY;

        toolbar = findViewById(R.id.toolbar_context_management_detail);
        toolbar.setNavigationOnClickListener(
            view -> getOnBackPressedDispatcher().onBackPressed()
        );
        scrollView = findViewById(R.id.scroll_context_management_detail);
        status = findViewById(R.id.tv_context_management_detail_status);
        content = findViewById(R.id.container_context_management_detail);
        pageActions = findViewById(R.id.page_actions);

        if (savedInstanceState != null) {
            String[] expanded = savedInstanceState.getStringArray(STATE_EXPANDED);
            if (expanded != null) {
                Collections.addAll(expandedSections, expanded);
            }
            savedScrollY = savedInstanceState.getInt(STATE_SCROLL_Y, -1);
            savedSceneListScrollY = savedInstanceState.getInt(
                STATE_SCENE_LIST_SCROLL_Y,
                -1
            );
            restoredLayer = savedInstanceState.getString(
                STATE_LAYER,
                LAYER_SUMMARY
            );
        }

        Intent intent = getIntent();
        kind = intent == null ? null : intent.getStringExtra(EXTRA_KIND);
        objectId = intent == null ? null : intent.getStringExtra(EXTRA_ID);
        stylePreview = isStylePreviewRequest();
        showingGroupSceneList = KIND_GROUP.equals(kind)
            && LAYER_GROUP_SCENES.equals(restoredLayer);
        detailLayer = showingGroupSceneList
            ? LAYER_SUMMARY
            : normalizeDetailLayer(restoredLayer);
        getOnBackPressedDispatcher().addCallback(
            this,
            new OnBackPressedCallback(true) {
                @Override
                public void handleOnBackPressed() {
                    if (showingGroupSceneList) {
                        showGroupDetailLayer();
                    } else if (!LAYER_SUMMARY.equals(detailLayer)) {
                        detailLayer = LAYER_SUMMARY;
                        renderDocument();
                    } else {
                        finish();
                    }
                }
            }
        );
        if (stylePreview) {
            loadPreviewData(intent);
            return;
        }
        if (!isSupportedRequest()) {
            showFailure(getString(R.string.context_detail_unavailable));
            return;
        }
        updateToolbarTitle();
        status.setText(R.string.context_detail_loading);
    }

    @Override
    protected void onStart() {
        super.onStart();
        lifecycleStarted = true;
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (isSupportedRequest() && !stylePreview) {
            loadAsync();
        }
    }

    @Override
    protected void onPause() {
        rememberScroll();
        super.onPause();
    }

    @Override
    protected void onStop() {
        lifecycleStarted = false;
        loadGeneration++;
        super.onStop();
    }

    @Override
    protected void onSaveInstanceState(Bundle outState) {
        rememberScroll();
        outState.putStringArray(
            STATE_EXPANDED,
            expandedSections.toArray(new String[0])
        );
        outState.putString(
            STATE_LAYER,
            showingGroupSceneList ? LAYER_GROUP_SCENES : detailLayer
        );
        outState.putInt(STATE_SCROLL_Y, savedScrollY);
        outState.putInt(STATE_SCENE_LIST_SCROLL_Y, savedSceneListScrollY);
        super.onSaveInstanceState(outState);
    }

    @Override
    protected void onDestroy() {
        if (pendingMoveController != null) pendingMoveController.close();
        destroyed = true;
        loadGeneration++;
        ioExecutor.shutdownNow();
        super.onDestroy();
    }

    private void loadPreviewData(Intent intent) {
        JSONObject document = StylePreview.payloadOf(intent);
        if (document == null) {
            document = StylePreview.sample(
                isContext()
                    ? StylePreview.KIND_CONTEXT_DETAIL
                    : StylePreview.KIND_GROUP_DETAIL
            );
        }
        if (document == null) {
            showFailure(getString(R.string.context_detail_missing));
            return;
        }
        List<GroupLink> groups = new ArrayList<>();
        JSONArray groupEntries = document.optJSONArray("groups");
        for (int index = 0;
             groupEntries != null && index < groupEntries.length();
             index++) {
            JSONObject entry = groupEntries.optJSONObject(index);
            if (entry == null) {
                continue;
            }
            String id = entry.optString("id", "").trim();
            if (!id.isEmpty()) {
                groups.add(new GroupLink(
                    id,
                    firstNonEmpty(
                        entry.optString("display_name", ""),
                        id
                    )
                ));
            }
        }
        List<ContextMember> members = new ArrayList<>();
        JSONArray contextEntries = document.optJSONArray("contexts");
        for (int index = 0;
             contextEntries != null && index < contextEntries.length();
             index++) {
            JSONObject entry = contextEntries.optJSONObject(index);
            if (entry == null) {
                continue;
            }
            String id = firstNonEmpty(
                entry.optString("context_id", ""),
                entry.optString("id", "")
            ).trim();
            if (!id.isEmpty()) {
                members.add(new ContextMember(
                    id,
                    firstNonEmpty(entry.optString("display_name", ""), id),
                    true
                ));
            }
        }
        List<SceneLink> scenes = new ArrayList<>();
        JSONArray sceneEntries = document.optJSONArray("scenes");
        for (int index = 0;
             sceneEntries != null && index < sceneEntries.length();
             index++) {
            JSONObject entry = sceneEntries.optJSONObject(index);
            if (entry == null) {
                continue;
            }
            String scene = entry.optString("scene", "").trim();
            if (!scene.isEmpty()) {
                scenes.add(new SceneLink(scene, true));
            }
        }
        loadedData = new LoadedData(
            document,
            groups,
            members,
            scenes,
            new AggregateData(
                new ArrayList<AggregateCharacter>(),
                new ArrayList<AggregateCharacter>(),
                new ArrayList<AggregateTerm>(),
                0
            ),
            "",
            "",
            null
        );
        closureState = null;
        editingAllowed = false;
        renderDocument();
    }

    private void loadAsync() {
        final long request = ++loadGeneration;
        loadedData = null;
        closureState = null;
        editingAllowed = false;
        content.removeAllViews();
        clearPageActions();
        status.setVisibility(View.VISIBLE);
        status.setText(R.string.context_detail_loading);

        ioExecutor.execute(() -> {
            try {
                SceneContextStore store = new SceneContextStore(this);
                JSONObject document = isContext()
                    ? store.getContext(objectId)
                    : store.getGroup(objectId);
                List<GroupLink> groups = isContext()
                    ? findGroups(store, objectId)
                    : Collections.emptyList();
                Map<String, JSONObject> contextsById = new LinkedHashMap<>();
                List<ContextMember> members = isContext()
                    ? Collections.emptyList()
                    : readMembers(
                        store,
                        document.optJSONArray("contexts"),
                        contextsById
                    );
                List<SceneLink> requestedScenes = isContext()
                    ? readContextScenes(document)
                    : readGroupScenes(contextsById);
                ConfigStore configStore = new ConfigStore(this);
                JSONObject characterDictionary = configStore.loadJson(
                    ConfigStore.CHARDICT_FILE_NAME
                ).json;
                JSONObject termDictionary = configStore.loadJson(
                    ConfigStore.GAMETERMS_FILE_NAME
                ).json;
                SceneLoadResult sceneLoad = readSceneData(
                    new SceneStore(this),
                    requestedScenes,
                    characterDictionary,
                    termDictionary
                );
                SceneContextStore.ManualClosureState loadedClosure = null;
                try {
                    loadedClosure = store.getManualClosureState(
                        isContext() ? KIND_CONTEXT : KIND_GROUP,
                        objectId
                    );
                } catch (Exception ignored) {
                    // Detail remains readable when a legacy sidecar is unavailable.
                }
                LoadedData data = new LoadedData(
                    document,
                    groups,
                    members,
                    sceneLoad.sceneLinks,
                    sceneLoad.aggregate,
                    store.getActiveContextId(),
                    store.getActiveGroupId(),
                    loadedClosure
                );
                runOnUiThread(() -> {
                    if (!isCurrentRequest(request)) {
                        return;
                    }
                    loadedData = data;
                    editingAllowed = true;
                    renderDocument();
                });
            } catch (Exception error) {
                runOnUiThread(() -> {
                    if (!isCurrentRequest(request)) {
                        return;
                    }
                    showFailure(getString(
                        R.string.context_detail_load_failed
                    ));
                });
            }
        });
    }

    private List<GroupLink> findGroups(
        SceneContextStore store,
        String contextId
    ) throws SceneContextStore.StorageException {
        List<GroupLink> result = new ArrayList<>();
        for (JSONObject group : store.listGroups()) {
            if (group == null) {
                continue;
            }
            JSONArray entries = group.optJSONArray("contexts");
            if (entries == null) {
                continue;
            }
            for (int index = 0; index < entries.length(); index++) {
                JSONObject entry = entries.optJSONObject(index);
                if (entry != null && contextId.equals(
                    entry.optString("context_id", "")
                )) {
                    String groupId = group.optString("id", "");
                    String displayName = group.optString("display_name", "");
                    if (!groupId.isEmpty() && !displayName.isEmpty()) {
                        result.add(new GroupLink(groupId, displayName));
                    }
                    break;
                }
            }
        }
        return result;
    }

    private List<ContextMember> readMembers(
        SceneContextStore store,
        JSONArray entries,
        Map<String, JSONObject> contextsById
    ) {
        List<ContextMember> result = new ArrayList<>();
        if (entries == null) {
            return result;
        }
        for (int index = 0; index < entries.length(); index++) {
            JSONObject entry = entries.optJSONObject(index);
            String contextId = entry == null
                ? ""
                : entry.optString("context_id", "").trim();
            if (contextId.isEmpty()) {
                result.add(new ContextMember(
                    contextId,
                    getString(R.string.context_detail_unavailable_context),
                    false
                ));
                continue;
            }
            try {
                JSONObject context = store.getContext(contextId);
                contextsById.put(contextId, context);
                String displayName = context.optString("display_name", "").trim();
                result.add(new ContextMember(
                    contextId,
                    displayName.isEmpty()
                        ? getString(R.string.context_detail_unavailable_context)
                        : displayName,
                    !displayName.isEmpty()
                ));
            } catch (Exception ignored) {
                result.add(new ContextMember(
                    contextId,
                    getString(R.string.context_detail_unavailable_context),
                    false
                ));
            }
        }
        return result;
    }

    private List<SceneLink> readContextScenes(JSONObject document) {
        List<SceneLink> result = new ArrayList<>();
        JSONArray scenes = document == null
            ? null
            : document.optJSONArray("scenes");
        if (scenes == null) {
            return result;
        }
        for (int index = 0; index < scenes.length(); index++) {
            JSONObject entry = scenes.optJSONObject(index);
            String sceneName = entry == null
                ? ""
                : entry.optString("scene", "").trim();
            if (!sceneName.isEmpty()) {
                result.add(new SceneLink(sceneName, false));
            }
        }
        return result;
    }

    private List<SceneLink> readGroupScenes(
        Map<String, JSONObject> contextsById
    ) {
        List<SceneLink> result = new ArrayList<>();
        Set<String> seenScenes = new LinkedHashSet<>();
        for (JSONObject context : contextsById.values()) {
            JSONArray scenes = context == null
                ? null
                : context.optJSONArray("scenes");
            if (scenes == null) {
                continue;
            }
            for (int index = 0; index < scenes.length(); index++) {
                JSONObject entry = scenes.optJSONObject(index);
                String sceneName = entry == null
                    ? ""
                    : entry.optString("scene", "").trim();
                if (!sceneName.isEmpty() && seenScenes.add(sceneName)) {
                    result.add(new SceneLink(sceneName, false));
                }
            }
        }
        return result;
    }

    private SceneLoadResult readSceneData(
        SceneStore sceneStore,
        List<SceneLink> requestedScenes,
        JSONObject characterDictionary,
        JSONObject termDictionary
    ) {
        List<SceneLink> sceneLinks = new ArrayList<>();
        Map<String, SceneManagementDetailData.SceneData> scenesByName =
            new LinkedHashMap<>();
        Map<String, Boolean> availabilityByName = new LinkedHashMap<>();
        int missingSceneCount = 0;
        for (SceneLink requested : requestedScenes) {
            String sceneName = requested.sceneName;
            Boolean available = availabilityByName.get(sceneName);
            if (available == null) {
                try {
                    SceneStore.ValidatedScene scene =
                        sceneStore.readValidSceneByName(sceneName);
                    if (scene == null) {
                        availabilityByName.put(sceneName, false);
                        missingSceneCount++;
                    } else {
                        SceneManagementDetailData.SceneData data =
                            SceneManagementDetailData.read(
                                scene,
                                characterDictionary,
                                termDictionary
                            );
                        scenesByName.put(sceneName, data);
                        availabilityByName.put(sceneName, true);
                    }
                } catch (Exception ignored) {
                    availabilityByName.put(sceneName, false);
                    missingSceneCount++;
                }
                available = availabilityByName.get(sceneName);
            }
            sceneLinks.add(new SceneLink(sceneName, Boolean.TRUE.equals(available)));
        }
        AggregateData aggregate = aggregateSceneData(
            sceneLinks,
            scenesByName,
            characterDictionary,
            termDictionary,
            missingSceneCount
        );
        return new SceneLoadResult(sceneLinks, aggregate);
    }

    private AggregateData aggregateSceneData(
        List<SceneLink> sceneLinks,
        Map<String, SceneManagementDetailData.SceneData> scenesByName,
        JSONObject characterDictionary,
        JSONObject termDictionary,
        int missingSceneCount
    ) {
        LinkedHashMap<String, AggregateCharacter> primary =
            new LinkedHashMap<>();
        LinkedHashMap<String, AggregateCharacter> secondary =
            new LinkedHashMap<>();
        LinkedHashMap<String, AggregateTerm> terms = new LinkedHashMap<>();
        Set<String> visitedScenes = new LinkedHashSet<>();

        for (SceneLink scene : sceneLinks) {
            if (!scene.available || !visitedScenes.add(scene.sceneName)) {
                continue;
            }
            SceneManagementDetailData.SceneData data =
                scenesByName.get(scene.sceneName);
            if (data == null) {
                continue;
            }
            for (SceneManagementDetailData.CharacterEntry entry
                : data.highWeightCharacters) {
                String identity = dictionaryIdentity(
                    characterDictionary,
                    entry.name
                );
                if (!identity.isEmpty()) {
                    primary.putIfAbsent(
                        identity,
                        new AggregateCharacter(entry, scene.sceneName)
                    );
                }
            }
        }

        visitedScenes.clear();
        for (SceneLink scene : sceneLinks) {
            if (!scene.available || !visitedScenes.add(scene.sceneName)) {
                continue;
            }
            SceneManagementDetailData.SceneData data =
                scenesByName.get(scene.sceneName);
            if (data == null) {
                continue;
            }
            for (SceneManagementDetailData.CharacterEntry entry
                : data.lowWeightCharacters) {
                addSecondaryCharacter(
                    secondary,
                    primary,
                    characterDictionary,
                    entry,
                    scene.sceneName
                );
            }
            for (SceneManagementDetailData.MentionedCharacterEntry entry
                : data.mentionedCharacters) {
                addSecondaryCharacter(
                    secondary,
                    primary,
                    characterDictionary,
                    entry,
                    scene.sceneName
                );
            }
            for (SceneManagementDetailData.TermEntry entry : data.terms) {
                String identity = dictionaryIdentity(termDictionary, entry.term);
                if (!identity.isEmpty()) {
                    terms.putIfAbsent(
                        identity,
                        new AggregateTerm(entry, scene.sceneName)
                    );
                }
            }
        }

        return new AggregateData(
            new ArrayList<>(primary.values()),
            new ArrayList<>(secondary.values()),
            new ArrayList<>(terms.values()),
            missingSceneCount
        );
    }

    private void addSecondaryCharacter(
        Map<String, AggregateCharacter> secondary,
        Map<String, AggregateCharacter> primary,
        JSONObject characterDictionary,
        Object entry,
        String sceneName
    ) {
        String name = entry instanceof SceneManagementDetailData.CharacterEntry
            ? ((SceneManagementDetailData.CharacterEntry) entry).name
            : ((SceneManagementDetailData.MentionedCharacterEntry) entry).name;
        String identity = dictionaryIdentity(characterDictionary, name);
        if (!identity.isEmpty()
            && !primary.containsKey(identity)
            && !secondary.containsKey(identity)) {
            secondary.put(identity, new AggregateCharacter(entry, sceneName));
        }
    }

    private String dictionaryIdentity(JSONObject dictionary, String value) {
        String exact = value == null ? "" : value.trim();
        if (exact.isEmpty() || dictionary == null) {
            return exact;
        }
        JSONObject direct = dictionary.optJSONObject(exact);
        if (direct != null) {
            return exact;
        }
        Iterator<String> keys = dictionary.keys();
        while (keys.hasNext()) {
            String key = keys.next();
            if (exact.equals(key)) {
                return key;
            }
            JSONObject record = dictionary.optJSONObject(key);
            if (record != null && exact.equals(record.optString("key", ""))) {
                return key;
            }
        }
        return exact;
    }

    private boolean isCurrentRequest(long request) {
        return !destroyed
            && lifecycleStarted
            && !isFinishing()
            && !isDestroyed()
            && request == loadGeneration;
    }

    private void renderDocument() {
        clearPageActions();
        if (loadedData == null || loadedData.document == null) {
            showFailure(getString(R.string.context_detail_missing));
            return;
        }
        if (showingGroupSceneList) {
            renderGroupSceneList();
            return;
        }
        updateToolbarTitle();
        status.setVisibility(View.GONE);
        content.removeAllViews();
        if (LAYER_SUMMARY.equals(detailLayer)) {
            renderPrototypeSummary();
        } else {
            renderPrototypeLayer();
        }
        restoreScrollIfNeeded();
    }

    private String normalizeDetailLayer(String layer) {
        if (LAYER_RELATIONS.equals(layer)
            || LAYER_SCENES.equals(layer)
            || LAYER_MEMBERS.equals(layer)
            || LAYER_CHARACTERS.equals(layer)
            || LAYER_TERMS.equals(layer)
            || LAYER_SUMMARIES.equals(layer)
            || LAYER_MANUAL.equals(layer)) {
            return layer;
        }
        return LAYER_SUMMARY;
    }

    private void renderPrototypeSummary() {
        String displayName = loadedData.document.optString("display_name", "");
        addPrototypeHeading(
            displayName,
            getString(isContext()
                ? R.string.detail_proto_context_kind
                : R.string.detail_proto_group_kind)
        );

        LinearLayout metadata = new LinearLayout(this);
        metadata.setOrientation(LinearLayout.VERTICAL);
        LinearLayout firstRow = new LinearLayout(this);
        firstRow.setOrientation(LinearLayout.HORIZONTAL);
        firstRow.setGravity(Gravity.TOP);
        if (isContext()) {
            addPrototypeMetaCell(
                firstRow,
                getString(R.string.detail_proto_context_groups),
                groupPreview(),
                true
            );
            addPrototypeMetaCell(
                firstRow,
                getString(R.string.detail_proto_context_scenes),
                String.valueOf(loadedData.scenes.size()),
                false
            );
        } else {
            addPrototypeMetaCell(
                firstRow,
                getString(R.string.detail_proto_group_contexts),
                String.valueOf(loadedData.members.size()),
                true
            );
            addPrototypeMetaCell(
                firstRow,
                getString(R.string.detail_proto_group_scenes),
                String.valueOf(loadedData.scenes.size()),
                false
            );
        }
        metadata.addView(firstRow, fullWidthParams(8));
        addPrototypeMetaCell(
            metadata,
            getString(R.string.detail_proto_context_summary_languages),
            summaryLanguageText()
        );
        String updatedTime = formatTimeIfPresent(
            loadedData.document.opt("updated_at")
        );
        if (!TextUtils.isEmpty(updatedTime)) {
            addPrototypeMetaCell(
                metadata,
                getString(R.string.detail_proto_updated_at),
                updatedTime
            );
        }
        content.addView(metadata, fullWidthParams(0));

        if (loadedData.aggregate.missingSceneCount > 0) {
            addNotice(getString(
                R.string.context_detail_missing_scenes,
                loadedData.aggregate.missingSceneCount
            ));
        }
        if (hasUnavailableMembers()) {
            addNotice(getString(R.string.context_detail_partial_members));
        }
        addPrototypeClosureCard();

        addPrototypeSummaryCard(
            getString(R.string.detail_proto_context_summary),
            summaryPreview(),
            () -> openDetailLayer(LAYER_SUMMARIES)
        );
        if (isContext()) {
            addPrototypeSummaryCard(
                getString(R.string.detail_proto_context_scenes)
                    + " · " + loadedData.scenes.size(),
                scenePreview(),
                () -> openDetailLayer(LAYER_SCENES)
            );
            addPrototypeSummaryCard(
                getString(R.string.detail_proto_context_primary)
                    + " · " + loadedData.aggregate.primaryCharacters.size(),
                aggregateCharacterPreview(loadedData.aggregate.primaryCharacters),
                () -> openDetailLayer(LAYER_CHARACTERS)
            );
            addPrototypeSummaryCard(
                getString(R.string.detail_proto_context_secondary)
                    + " · " + loadedData.aggregate.secondaryCharacters.size(),
                aggregateCharacterPreview(loadedData.aggregate.secondaryCharacters),
                () -> openDetailLayer(LAYER_CHARACTERS)
            );
            addPrototypeSummaryCard(
                getString(R.string.detail_proto_context_terms)
                    + " · " + loadedData.aggregate.terms.size(),
                aggregateTermPreview(),
                () -> openDetailLayer(LAYER_TERMS)
            );
            addPrototypeSummaryCard(
                getString(R.string.context_detail_manual_descriptions),
                manualDescriptionPreview(),
                () -> openDetailLayer(LAYER_MANUAL)
            );
        } else {
            addPrototypeSummaryCard(
                getString(R.string.detail_proto_group_contexts)
                    + " · " + loadedData.members.size(),
                memberPreview(),
                () -> openDetailLayer(LAYER_MEMBERS)
            );
            addPrototypeSummaryCard(
                getString(R.string.detail_proto_group_scenes)
                    + " · " + loadedData.scenes.size(),
                scenePreview(),
                () -> openDetailLayer(LAYER_SCENES)
            );
            addPrototypeSummaryCard(
                getString(R.string.detail_proto_context_primary)
                    + " · " + loadedData.aggregate.primaryCharacters.size(),
                aggregateCharacterPreview(loadedData.aggregate.primaryCharacters),
                () -> openDetailLayer(LAYER_CHARACTERS)
            );
            addPrototypeSummaryCard(
                getString(R.string.detail_proto_context_secondary)
                    + " · " + loadedData.aggregate.secondaryCharacters.size(),
                aggregateCharacterPreview(loadedData.aggregate.secondaryCharacters),
                () -> openDetailLayer(LAYER_CHARACTERS)
            );
            addPrototypeSummaryCard(
                getString(R.string.detail_proto_context_terms)
                    + " · " + loadedData.aggregate.terms.size(),
                aggregateTermPreview(),
                () -> openDetailLayer(LAYER_TERMS)
            );
        }

        setPendingMoveBusy(pendingMoveBusy);
    }

    private void addPrototypeHeading(String title, String kindLabel) {
        LinearLayout heading = new LinearLayout(this);
        heading.setOrientation(LinearLayout.HORIZONTAL);
        heading.setGravity(Gravity.TOP);
        LinearLayout copy = new LinearLayout(this);
        copy.setOrientation(LinearLayout.VERTICAL);
        addPrototypeText(
            copy,
            title,
            R.style.TextAppearance_HET_DetailPrototype_Heading,
            0,
            2
        );
        addPrototypeText(
            copy,
            kindLabel,
            R.style.TextAppearance_HET_DetailPrototype_Kind,
            0,
            0
        );
        heading.addView(copy, new LinearLayout.LayoutParams(
            0,
            ViewGroup.LayoutParams.WRAP_CONTENT,
            1f
        ));
        MaterialButton edit = prototypeButton(
            getString(R.string.detail_proto_context_edit,
                getString(isContext()
                    ? R.string.context_detail_context_title
                    : R.string.context_detail_group_title)),
            false
        );
        edit.setOnClickListener(view -> openEditor());
        editAction = edit;
        addPageAction(edit, wrapButtonParams(0));
        if (!stylePreview) {
            MaterialButton move = prototypeButton(
                getString(R.string.detail_proto_context_move),
                true
            );
            move.setOnClickListener(view -> moveCurrentToPending());
            moveAction = move;
            addPageAction(move, wrapButtonParams(0));
        } else {
            moveAction = null;
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

    private void addPrototypeClosureCard() {
        MaterialCardView card = prototypeCard(8);
        LinearLayout body = prototypeCardBody(card);
        addPrototypeText(
            body,
            getString(R.string.detail_proto_context_manual_closure),
            R.style.TextAppearance_HET_DetailPrototype_CardTitle,
            0,
            3
        );
        String statusText;
        if (closureState == null) {
            statusText = getString(R.string.detail_proto_context_closure_unknown);
        } else if (closureState.isOpen()) {
            statusText = getString(R.string.detail_proto_context_closure_open);
        } else if (closureState.isClosed()) {
            statusText = getString(R.string.detail_proto_context_closure_closed);
        } else {
            statusText = getString(R.string.detail_proto_context_closure_none);
        }
        addPrototypeText(
            body,
            statusText,
            R.style.TextAppearance_HET_DetailPrototype_CardPreview,
            0,
            5
        );
        MaterialButton manage = prototypeButton(
            getString(R.string.detail_proto_context_closure_manage),
            false
        );
        manage.setOnClickListener(view -> openEditor());
        body.addView(manage, fullWidthButtonParams(0));
        content.addView(card);
    }

    private void renderPrototypeLayer() {
        String label;
        if (LAYER_SCENES.equals(detailLayer)) {
            label = getString(R.string.detail_proto_scene_kind);
        } else if (LAYER_MEMBERS.equals(detailLayer)) {
            label = getString(R.string.detail_proto_context_kind);
        } else if (LAYER_CHARACTERS.equals(detailLayer)) {
            label = getString(R.string.detail_proto_characters);
        } else if (LAYER_TERMS.equals(detailLayer)) {
            label = getString(R.string.detail_proto_context_terms);
        } else if (LAYER_SUMMARIES.equals(detailLayer)) {
            label = getString(R.string.detail_proto_context_summary);
        } else if (LAYER_MANUAL.equals(detailLayer)) {
            label = getString(R.string.context_detail_manual_descriptions);
        } else {
            label = getString(R.string.context_detail_relations);
        }
        addPrototypeHeading(
            loadedData.document.optString("display_name", "") + " · " + label,
            getString(R.string.detail_proto_context_layer_description)
        );
        if (loadedData.aggregate.missingSceneCount > 0) {
            addNotice(getString(
                R.string.context_detail_missing_scenes,
                loadedData.aggregate.missingSceneCount
            ));
        }
        if (LAYER_SCENES.equals(detailLayer)) {
            renderPrototypeScenes(content);
        } else if (LAYER_MEMBERS.equals(detailLayer)) {
            renderPrototypeMembers(content);
        } else if (LAYER_CHARACTERS.equals(detailLayer)) {
            renderPrototypeCharacters(content);
        } else if (LAYER_TERMS.equals(detailLayer)) {
            renderPrototypeTerms(content);
        } else if (LAYER_SUMMARIES.equals(detailLayer)) {
            renderPrototypeSummaries(content);
        } else if (LAYER_MANUAL.equals(detailLayer)) {
            renderPrototypeManual(content);
        } else {
            renderPrototypeRelations(content);
        }
    }

    private void renderPrototypeScenes(LinearLayout parent) {
        if (loadedData.scenes.isEmpty()) {
            addPrototypeEmpty(parent);
            return;
        }
        for (SceneLink scene : loadedData.scenes) {
            addPrototypeLinkRow(
                parent,
                scene.sceneName,
                getString(scene.available
                    ? R.string.context_detail_scenes
                    : R.string.context_detail_unavailable_scene),
                scene.available ? () -> openScene(scene.sceneName) : null,
                scene.available
            );
        }
    }

    private void renderPrototypeMembers(LinearLayout parent) {
        if (loadedData.members.isEmpty()) {
            addPrototypeEmpty(parent);
            return;
        }
        for (ContextMember member : loadedData.members) {
            addPrototypeLinkRow(
                parent,
                member.displayName,
                getString(R.string.context_detail_members),
                member.available
                    ? () -> openDetail(KIND_CONTEXT, member.id)
                    : null,
                member.available
            );
        }
    }

    private void renderPrototypeRelations(LinearLayout parent) {
        addPrototypeSectionHeading(parent, getString(
            R.string.context_detail_scenes_count,
            loadedData.scenes.size()
        ));
        renderPrototypeScenes(parent);
        if (isContext()) {
            addPrototypeSectionHeading(parent, getString(
                R.string.context_detail_groups_count,
                loadedData.groups.size()
            ));
            if (loadedData.groups.isEmpty()) {
                addPrototypeEmpty(parent);
            } else {
                for (GroupLink group : loadedData.groups) {
                    addPrototypeLinkRow(
                        parent,
                        group.displayName,
                        getString(R.string.context_detail_groups),
                        () -> openDetail(KIND_GROUP, group.id),
                        true
                    );
                }
            }
        } else {
            addPrototypeSectionHeading(parent, getString(
                R.string.context_detail_members_count,
                loadedData.members.size()
            ));
            renderPrototypeMembers(parent);
        }
    }

    private void renderPrototypeCharacters(LinearLayout parent) {
        addPrototypeSectionHeading(parent, getString(
            R.string.context_detail_primary_characters_count,
            loadedData.aggregate.primaryCharacters.size()
        ));
        renderPrototypeAggregateCharacters(
            parent,
            loadedData.aggregate.primaryCharacters
        );
        addPrototypeSectionHeading(parent, getString(
            R.string.context_detail_secondary_characters_count,
            loadedData.aggregate.secondaryCharacters.size()
        ));
        renderPrototypeAggregateCharacters(
            parent,
            loadedData.aggregate.secondaryCharacters
        );
    }

    private void renderPrototypeAggregateCharacters(
        LinearLayout parent,
        List<AggregateCharacter> entries
    ) {
        if (entries.isEmpty()) {
            addPrototypeEmpty(parent);
            return;
        }
        for (AggregateCharacter aggregate : entries) {
            String title;
            String role;
            String sourceName;
            if (aggregate.entry instanceof SceneManagementDetailData.CharacterEntry) {
                SceneManagementDetailData.CharacterEntry entry =
                    (SceneManagementDetailData.CharacterEntry) aggregate.entry;
                title = displayCharacterName(entry);
                role = entry.role;
                sourceName = entry.name;
            } else {
                SceneManagementDetailData.MentionedCharacterEntry entry =
                    (SceneManagementDetailData.MentionedCharacterEntry)
                        aggregate.entry;
                title = displayMentionedName(entry);
                role = "mentioned";
                sourceName = entry.name;
            }
            addPrototypeLinkRow(
                parent,
                title,
                aggregate.sceneName,
                () -> openSceneCharacter(aggregate.sceneName, role, sourceName),
                true
            );
        }
    }

    private void renderPrototypeTerms(LinearLayout parent) {
        if (loadedData.aggregate.terms.isEmpty()) {
            addPrototypeEmpty(parent);
            return;
        }
        for (AggregateTerm aggregate : loadedData.aggregate.terms) {
            addPrototypeLinkRow(
                parent,
                SceneManagementDetailData.displayTermName(aggregate.entry),
                aggregate.sceneName,
                () -> openSceneTerm(aggregate.sceneName, aggregate.entry.term),
                true
            );
        }
    }

    private void renderPrototypeSummaries(LinearLayout parent) {
        JSONObject summaries = loadedData.document.optJSONObject("summary");
        if (languageKeys(summaries).isEmpty()) {
            addPrototypeEmpty(parent);
            return;
        }
        MaterialCardView card = prototypeCard(0);
        LinearLayout body = prototypeCardBody(card);
        renderSummaries(body, summaries);
        parent.addView(card);
    }

    private void renderPrototypeManual(LinearLayout parent) {
        JSONObject descriptions = loadedData.document.optJSONObject(
            "manual_descriptions"
        );
        if (languageKeys(descriptions).isEmpty()) {
            addPrototypeEmpty(parent);
            return;
        }
        MaterialCardView card = prototypeCard(0);
        LinearLayout body = prototypeCardBody(card);
        renderManualDescriptions(body, descriptions);
        parent.addView(card);
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

    private void addPrototypeLinkRow(
        LinearLayout parent,
        String title,
        String subtitle,
        Runnable action,
        boolean enabled
    ) {
        MaterialCardView card = prototypeCard(6);
        card.setEnabled(enabled);
        card.setAlpha(enabled ? 1.0f : 0.65f);
        LinearLayout body = prototypeCardBody(card);
        addPrototypeText(
            body,
            title,
            R.style.TextAppearance_HET_DetailPrototype_CardTitle,
            0,
            2,
            false
        );
        addPrototypeText(
            body,
            subtitle,
            R.style.TextAppearance_HET_DetailPrototype_CardPreview,
            0,
            0,
            false
        );
        if (action != null) {
            card.setClickable(true);
            card.setOnClickListener(view -> action.run());
        }
        parent.addView(card);
    }

    private void addPrototypeSectionHeading(LinearLayout parent, String text) {
        addPrototypeText(
            parent,
            text,
            R.style.TextAppearance_HET_DetailPrototype_CardTitle,
            2,
            4
        );
    }

    private void addPrototypeEmpty(LinearLayout parent) {
        addPrototypeText(
            parent,
            getString(R.string.detail_proto_context_no_preview),
            R.style.TextAppearance_HET_DetailPrototype_Metadata,
            0,
            6
        );
    }

    private void openDetailLayer(String nextLayer) {
        if (nextLayer == null || loadedData == null) {
            return;
        }
        rememberScroll();
        detailLayer = normalizeDetailLayer(nextLayer);
        if (scrollView != null) {
            scrollView.scrollTo(0, 0);
        }
        renderDocument();
    }

    private void moveCurrentToPending() {
        if (stylePreview
            || !editingAllowed
            || loadedData == null
            || pendingMoveBusy) {
            return;
        }
        if (pendingMoveController == null) {
            pendingMoveController = new PendingProcessMoveController(this);
        }
        setPendingMoveBusy(true);
        pendingMoveController.confirmMove(
            isContext() ? KIND_CONTEXT : KIND_GROUP,
            objectId,
            objectId,
            () -> {
                setResult(RESULT_OK);
                finish();
            },
            () -> setPendingMoveBusy(false)
        );
    }

    private String groupPreview() {
        if (loadedData.groups.isEmpty()) {
            return getString(R.string.detail_proto_context_no_preview);
        }
        List<String> names = new ArrayList<>();
        for (GroupLink group : loadedData.groups) {
            if (group != null && !group.displayName.isEmpty()) {
                names.add(group.displayName);
            }
        }
        return names.isEmpty()
            ? getString(R.string.detail_proto_context_no_preview)
            : TextUtils.join("、", names);
    }

    private String memberPreview() {
        if (loadedData.members.isEmpty()) {
            return getString(R.string.detail_proto_context_no_preview);
        }
        List<String> names = new ArrayList<>();
        for (ContextMember member : loadedData.members) {
            if (member != null && !member.displayName.isEmpty()) {
                names.add(member.displayName);
            }
        }
        return names.isEmpty()
            ? getString(R.string.detail_proto_context_no_preview)
            : TextUtils.join("、", names);
    }

    private String scenePreview() {
        if (loadedData.scenes.isEmpty()) {
            return getString(R.string.detail_proto_context_no_preview);
        }
        List<String> names = new ArrayList<>();
        for (SceneLink scene : loadedData.scenes) {
            if (scene != null && !scene.sceneName.isEmpty()) {
                names.add(scene.sceneName);
            }
        }
        return names.isEmpty()
            ? getString(R.string.detail_proto_context_no_preview)
            : TextUtils.join("、", names);
    }

    private String aggregateCharacterPreview(List<AggregateCharacter> entries) {
        if (entries == null || entries.isEmpty()) {
            return getString(R.string.detail_proto_context_no_preview);
        }
        List<String> names = new ArrayList<>();
        for (AggregateCharacter aggregate : entries) {
            if (aggregate == null || aggregate.entry == null) {
                continue;
            }
            String name;
            if (aggregate.entry instanceof SceneManagementDetailData.CharacterEntry) {
                name = displayCharacterName(
                    (SceneManagementDetailData.CharacterEntry) aggregate.entry
                );
            } else {
                name = displayMentionedName(
                    (SceneManagementDetailData.MentionedCharacterEntry)
                        aggregate.entry
                );
            }
            if (!name.isEmpty()) {
                names.add(name);
            }
            if (names.size() >= 5) {
                break;
            }
        }
        return names.isEmpty()
            ? getString(R.string.detail_proto_context_no_preview)
            : TextUtils.join("、", names);
    }

    private String aggregateTermPreview() {
        if (loadedData.aggregate.terms.isEmpty()) {
            return getString(R.string.detail_proto_context_no_preview);
        }
        List<String> names = new ArrayList<>();
        for (AggregateTerm aggregate : loadedData.aggregate.terms) {
            if (aggregate != null && aggregate.entry != null) {
                names.add(SceneManagementDetailData.displayTermName(
                    aggregate.entry
                ));
            }
            if (names.size() >= 5) {
                break;
            }
        }
        return names.isEmpty()
            ? getString(R.string.detail_proto_context_no_preview)
            : TextUtils.join("、", names);
    }

    private String summaryPreview() {
        JSONObject summaries = loadedData.document.optJSONObject("summary");
        for (String language : languageKeys(summaries)) {
            JSONObject record = summaries.optJSONObject(language);
            String text = summaryRecordText(record);
            if (!text.isEmpty()) {
                return text;
            }
        }
        return getString(R.string.detail_proto_context_no_preview);
    }

    private String manualDescriptionPreview() {
        JSONObject descriptions = loadedData.document.optJSONObject(
            "manual_descriptions"
        );
        for (String language : languageKeys(descriptions)) {
            JSONObject record = descriptions.optJSONObject(language);
            String text = record == null ? "" : record.optString("text", "");
            if (!text.trim().isEmpty()) {
                return text;
            }
        }
        return getString(R.string.detail_proto_context_no_preview);
    }

    private String summaryLanguageText() {
        Set<String> languages = new LinkedHashSet<>();
        languages.addAll(languageKeys(
            loadedData.document.optJSONObject("summary")
        ));
        if (languages.isEmpty()) {
            return getString(R.string.detail_proto_context_no_preview);
        }
        List<String> labels = new ArrayList<>();
        for (String language : languages) {
            labels.add(languageName(language));
        }
        return TextUtils.join("、", labels);
    }

    private String summaryRecordText(JSONObject record) {
        if (record == null) {
            return "";
        }
        String[] fields = new String[] {"final", "current", "manual"};
        for (String field : fields) {
            JSONObject nested = record.optJSONObject(field);
            if (nested != null) {
                String text = nested.optString("text", "").trim();
                if (!text.isEmpty()) {
                    return text;
                }
            }
        }
        return record.optString("text", "").trim();
    }

    private String trimPreview(String value) {
        String text = value == null ? "" : value.trim().replace('\n', ' ');
        if (text.length() <= 120) {
            return text;
        }
        return text.substring(0, 117) + "…";
    }

    private String emptyFallback(String value) {
        return value == null || value.trim().isEmpty()
            ? getString(R.string.context_detail_empty_value)
            : value;
    }

    private String firstNonEmpty(String... values) {
        if (values == null) {
            return "";
        }
        for (String value : values) {
            if (value != null && !value.trim().isEmpty()) {
                return value;
            }
        }
        return "";
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

    private LinearLayout.LayoutParams fullWidthButtonParams(int bottomMargin) {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            dp(34)
        );
        params.bottomMargin = dp(bottomMargin);
        return params;
    }

    private void renderGroupSceneList() {
        updateToolbarTitle();
        status.setVisibility(View.GONE);
        content.removeAllViews();
        addText(
            content,
            getString(
                R.string.context_detail_group_scenes_count,
                loadedData.scenes.size()
            ),
            16,
            true
        );
        addText(
            content,
            getString(R.string.context_detail_group_scenes_hint),
            12,
            false
        );
        if (loadedData.aggregate.missingSceneCount > 0) {
            addNotice(
                getString(
                    R.string.context_detail_missing_scenes,
                    loadedData.aggregate.missingSceneCount
                )
            );
        }
        if (hasUnavailableMembers()) {
            addNotice(getString(R.string.context_detail_partial_members));
        }
        if (loadedData.scenes.isEmpty()) {
            addText(
                content,
                getString(R.string.context_detail_group_scenes_empty),
                14,
                false
            );
        } else {
            for (SceneLink scene : loadedData.scenes) {
                addLinkRow(
                    content,
                    scene.sceneName,
                    getString(scene.available
                        ? R.string.context_detail_scenes
                        : R.string.context_detail_unavailable_scene),
                    scene.available
                        ? () -> openScene(scene.sceneName)
                        : null,
                    scene.available
                );
            }
        }
        restoreScrollIfNeeded();
    }

    private void showGroupDetailLayer() {
        if (!showingGroupSceneList) {
            finish();
            return;
        }
        rememberScroll();
        showingGroupSceneList = false;
        renderDocument();
    }

    private void updateToolbarTitle() {
        if (toolbar == null) {
            return;
        }
        if (showingGroupSceneList && !isContext()) {
            toolbar.setTitle(R.string.context_detail_group_scenes_title);
        } else {
            toolbar.setTitle(isContext()
                ? R.string.context_detail_context_title
                : R.string.context_detail_group_title);
        }
    }

    private boolean hasUnavailableMembers() {
        if (isContext()) {
            return false;
        }
        for (ContextMember member : loadedData.members) {
            if (!member.available) {
                return true;
            }
        }
        return false;
    }

    private void addEditButton(boolean enabled) {
        if (stylePreview) {
            return;
        }
        MaterialButton button = new MaterialButton(this);
        editAction = button;
        button.setText(isContext()
            ? R.string.context_detail_edit_context
            : R.string.context_detail_edit_group);
        button.setAllCaps(false);
        button.setEnabled(enabled && !pendingMoveBusy);
        button.setOnClickListener(view -> openEditor());
        addPageAction(button, wrapButtonParams(0));
        moveAction = new MaterialButton(this);
        moveAction.setText(R.string.pending_process_move);
        moveAction.setAllCaps(false);
        applyDangerButton(moveAction);
        moveAction.setEnabled(enabled && !pendingMoveBusy);
        moveAction.setOnClickListener(view -> {
            if (!editingAllowed || loadedData == null || pendingMoveBusy) return;
            if (pendingMoveController == null) {
                pendingMoveController = new PendingProcessMoveController(this);
            }
            setPendingMoveBusy(true);
            pendingMoveController.confirmMove(
                isContext() ? "context" : "group", objectId, objectId,
                () -> { setResult(RESULT_OK); finish(); },
                () -> setPendingMoveBusy(false));
        });
        addPageAction(moveAction, wrapButtonParams(0));
    }

    private void setPendingMoveBusy(boolean busy) {
        pendingMoveBusy = busy;
        boolean enabled = !busy
            && loadedData != null
            && (editingAllowed || stylePreview);
        if (editAction != null) editAction.setEnabled(enabled);
        if (moveAction != null) moveAction.setEnabled(enabled);
    }

    private void openEditor() {
        if (stylePreview) {
            startActivity(StylePreview.intentFor(
                this,
                isContext()
                    ? StylePreview.KIND_CONTEXT_EDITOR
                    : StylePreview.KIND_GROUP_EDITOR
            ));
            return;
        }
        if (loadedData == null || !editingAllowed || !isSupportedRequest() || pendingMoveBusy) {
            return;
        }
        Intent intent = new Intent(this, ContextGroupEditorActivity.class);
        intent.putExtra(ContextGroupEditorActivity.EXTRA_KIND, kind);
        intent.putExtra(ContextGroupEditorActivity.EXTRA_ID, objectId);
        startActivity(intent);
    }

    private String displayCharacterName(
        SceneManagementDetailData.CharacterEntry entry
    ) {
        return entry != null && entry.isMainCharacter()
            ? getString(R.string.scene_detail_mc_count)
            : SceneManagementDetailData.displayCharacterName(entry);
    }

    private String displayMentionedName(
        SceneManagementDetailData.MentionedCharacterEntry entry
    ) {
        return entry != null && "mc".equals(entry.name)
            ? getString(R.string.scene_detail_mc_count)
            : SceneManagementDetailData.displayMentionedName(entry);
    }

    private void renderSummaries(LinearLayout body, JSONObject summaries) {
        List<String> languages = languageKeys(summaries);
        if (languages.isEmpty()) {
            addText(body, getString(R.string.context_detail_no_summary), 14, false);
            return;
        }
        for (String language : languages) {
            JSONObject languageSummary = summaries.optJSONObject(language);
            addText(body, languageName(language), 16, true);
            addSummaryRecord(
                body,
                languageSummary == null
                    ? null
                    : languageSummary.optJSONObject("final"),
                R.string.context_detail_summary_final
            );
            addSummaryRecord(
                body,
                languageSummary == null
                    ? null
                    : languageSummary.optJSONObject("current"),
                R.string.context_detail_summary_current
            );
            addSummaryRecord(
                body,
                languageSummary == null
                    ? null
                    : languageSummary.optJSONObject("manual"),
                R.string.context_detail_summary_manual
            );
        }
    }

    private void renderManualDescriptions(LinearLayout body, JSONObject descriptions) {
        List<String> languages = languageKeys(descriptions);
        if (languages.isEmpty()) {
            addText(
                body,
                getString(R.string.context_detail_no_manual_description),
                14,
                false
            );
            return;
        }
        for (String language : languages) {
            JSONObject description = descriptions.optJSONObject(language);
            addText(body, languageName(language), 16, true);
            if (description == null) {
                continue;
            }
            addField(
                body,
                getString(R.string.context_detail_manual_descriptions),
                description.optString("text", "")
            );
            addText(
                body,
                getString(
                    R.string.context_detail_updated_at,
                    formatTime(description.opt("updated_at"))
                ),
                12,
                false
            );
        }
    }

    private void addSummaryRecord(
        LinearLayout body,
        JSONObject record,
        int labelResource
    ) {
        if (record == null) {
            return;
        }
        addField(body, getString(labelResource), record.optString("text", ""));
        addText(
            body,
            getString(
                R.string.context_detail_updated_at,
                formatTime(record.opt("updated_at"))
            ),
            12,
            false
        );
    }

    private void addLinkRow(
        LinearLayout parent,
        String title,
        String subtitle,
        Runnable action,
        boolean enabled
    ) {
        MaterialCardView card = cardColumn();
        addText(card, title, 16, true, false);
        addText(card, subtitle, 12, false, false);
        card.setEnabled(enabled);
        card.setAlpha(enabled ? 1.0f : 0.65f);
        if (action != null) {
            card.setOnClickListener(view -> action.run());
        }
        parent.addView(card);
    }

    private void addNotice(String text) {
        TextView notice = new TextView(this);
        notice.setTextAppearance(
            this,
            com.google.android.material.R.style.TextAppearance_MaterialComponents_Body2
        );
        notice.setText(text);
        notice.setTextSize(14);
        notice.setTextColor(ContextCompat.getColor(this, R.color.het_warning));
        GradientDrawable background = new GradientDrawable();
        background.setColor(ContextCompat.getColor(
            this,
            R.color.het_warning_container
        ));
        background.setCornerRadius(dp(10));
        notice.setBackground(background);
        notice.setPadding(dp(12), dp(10), dp(12), dp(10));
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        );
        params.bottomMargin = dp(8);
        content.addView(notice, params);
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
        column.setPadding(dp(16), dp(12), dp(16), dp(8));
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

    private void applyDangerButton(MaterialButton button) {
        button.setBackgroundTintList(ColorStateList.valueOf(
            ContextCompat.getColor(this, R.color.het_error_container)
        ));
        button.setTextColor(ContextCompat.getColor(this, R.color.het_error));
    }

    private void addField(View parent, String label, String rawValue) {
        LinearLayout column = parent instanceof MaterialCardView
            ? (LinearLayout) parent.getTag()
            : (LinearLayout) parent;
        TextView labelView = new TextView(this);
        labelView.setTextAppearance(this, R.style.Widget_HET_FieldLabel);
        labelView.setText(label);
        column.addView(labelView);
        TextView valueView = new TextView(this);
        valueView.setTextAppearance(
            this,
            com.google.android.material.R.style.TextAppearance_MaterialComponents_Body1
        );
        valueView.setText(TextUtils.isEmpty(rawValue)
            ? getString(R.string.context_detail_empty_value)
            : rawValue);
        valueView.setTextIsSelectable(true);
        valueView.setPadding(0, 0, 0, dp(8));
        column.addView(valueView);
    }

    private void addText(
        View parent,
        String text,
        int sizeSp,
        boolean bold
    ) {
        addText(parent, text, sizeSp, bold, true);
    }

    private void addText(
        View parent,
        String text,
        int sizeSp,
        boolean bold,
        boolean selectable
    ) {
        LinearLayout column = parent instanceof MaterialCardView
            ? (LinearLayout) parent.getTag()
            : (LinearLayout) parent;
        TextView value = new TextView(this);
        value.setTextAppearance(
            this,
            com.google.android.material.R.style.TextAppearance_MaterialComponents_Body2
        );
        value.setText(text);
        value.setTextSize(sizeSp + 2);
        value.setTypeface(value.getTypeface(), bold
            ? android.graphics.Typeface.BOLD
            : android.graphics.Typeface.NORMAL);
        value.setTextIsSelectable(selectable);
        value.setPadding(0, dp(2), 0, dp(6));
        column.addView(value);
    }

    private List<String> languageKeys(JSONObject values) {
        if (values == null) {
            return Collections.emptyList();
        }
        List<String> result = new ArrayList<>();
        java.util.Iterator<String> iterator = values.keys();
        while (iterator.hasNext()) {
            String key = iterator.next();
            if (values.optJSONObject(key) != null) {
                result.add(key);
            }
        }
        Collections.sort(result);
        return result;
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
        Locale locale = Locale.forLanguageTag(code);
        String name = locale.getDisplayName(Locale.getDefault());
        return TextUtils.isEmpty(name) || name.equalsIgnoreCase(code)
            ? getString(R.string.context_detail_other_language)
            : name;
    }

    private String formatTime(Object value) {
        String formatted = formatTimeIfPresent(value);
        return TextUtils.isEmpty(formatted)
            ? getString(R.string.context_detail_empty_value)
            : formatted;
    }

    private String formatTimeIfPresent(Object value) {
        long millis = 0L;
        if (value instanceof Number) {
            millis = ((Number) value).longValue();
        } else if (value != null) {
            try {
                millis = Long.parseLong(value.toString());
            } catch (NumberFormatException ignored) {
                return "";
            }
        }
        if (millis <= 0L) {
            return "";
        }
        if (millis < 100_000_000_000L
            && millis <= Long.MAX_VALUE / 1000L) {
            millis *= 1000L;
        }
        return DateFormat.getDateTimeInstance(
            DateFormat.SHORT,
            DateFormat.SHORT,
            Locale.getDefault()
        ).format(new Date(millis));
    }

    private void openScene(String sceneName) {
        if (stylePreview) {
            startActivity(StylePreview.intentFor(
                this,
                StylePreview.KIND_SCENE_DETAIL
            ));
            return;
        }
        startActivity(new Intent(this, SceneManagementDetailActivity.class)
            .putExtra(SceneManagementDetailActivity.EXTRA_SCENE_NAME, sceneName));
    }

    private void openSceneCharacter(
        String sceneName,
        String role,
        String name
    ) {
        if (stylePreview) {
            Intent preview = StylePreview.intentFor(
                this,
                StylePreview.KIND_SCENE_DETAIL
            );
            preview.putExtra(SceneManagementDetailActivity.EXTRA_CHARACTER_ROLE, role);
            preview.putExtra(SceneManagementDetailActivity.EXTRA_CHARACTER_NAME, name);
            startActivity(preview);
            return;
        }
        startActivity(new Intent(this, SceneManagementDetailActivity.class)
            .putExtra(SceneManagementDetailActivity.EXTRA_SCENE_NAME, sceneName)
            .putExtra(SceneManagementDetailActivity.EXTRA_CHARACTER_ROLE, role)
            .putExtra(SceneManagementDetailActivity.EXTRA_CHARACTER_NAME, name));
    }

    private void openSceneTerm(String sceneName, String term) {
        if (stylePreview) {
            Intent preview = StylePreview.intentFor(
                this,
                StylePreview.KIND_SCENE_DETAIL
            );
            preview.putExtra(SceneManagementDetailActivity.EXTRA_TERM_NAME, term);
            startActivity(preview);
            return;
        }
        startActivity(new Intent(this, SceneManagementDetailActivity.class)
            .putExtra(SceneManagementDetailActivity.EXTRA_SCENE_NAME, sceneName)
            .putExtra(SceneManagementDetailActivity.EXTRA_TERM_NAME, term));
    }

    private void openDetail(String nextKind, String nextId) {
        if (stylePreview) {
            startActivity(StylePreview.intentFor(
                this,
                KIND_CONTEXT.equals(nextKind)
                    ? StylePreview.KIND_CONTEXT_DETAIL
                    : StylePreview.KIND_GROUP_DETAIL
            ));
            return;
        }
        startActivity(new Intent(this, ContextManagementDetailActivity.class)
            .putExtra(EXTRA_KIND, nextKind)
            .putExtra(EXTRA_ID, nextId));
    }

    private void showFailure(String message) {
        editingAllowed = false;
        clearPageActions();
        if (status != null) {
            status.setVisibility(View.VISIBLE);
            status.setText(message);
        }
        if (content != null) {
            content.removeAllViews();
            if (!showingGroupSceneList) {
                addEditButton(false);
            }
        }
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
        editAction = null;
        moveAction = null;
        pageActions.removeAllViews();
        pageActions.setVisibility(View.GONE);
    }

    private void rememberScroll() {
        if (scrollView != null && loadedData != null) {
            if (showingGroupSceneList) {
                savedSceneListScrollY = scrollView.getScrollY();
            } else {
                savedScrollY = scrollView.getScrollY();
            }
        }
    }

    private void restoreScrollIfNeeded() {
        final int target = showingGroupSceneList
            ? savedSceneListScrollY
            : savedScrollY;
        final long generation = loadGeneration;
        final boolean listLayer = showingGroupSceneList;
        scrollView.post(() -> {
            if (!destroyed
                && generation == loadGeneration
                && listLayer == showingGroupSceneList) {
                scrollView.scrollTo(0, Math.max(0, target));
            }
        });
    }

    private boolean isSupportedRequest() {
        return (KIND_CONTEXT.equals(kind) || KIND_GROUP.equals(kind))
            && !TextUtils.isEmpty(objectId);
    }

    private boolean isContext() {
        return KIND_CONTEXT.equals(kind);
    }

    private boolean isStylePreviewRequest() {
        if (!StylePreview.isEnabled(this)) {
            return false;
        }
        String previewKind = StylePreview.kindOf(getIntent());
        return (isContext()
            && StylePreview.KIND_CONTEXT_DETAIL.equals(previewKind))
            || (!isContext()
                && KIND_GROUP.equals(kind)
                && StylePreview.KIND_GROUP_DETAIL.equals(previewKind));
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

}
