package com.quarty.housamoembedtrans.ui;

import com.quarty.housamoembedtrans.R;
import com.quarty.housamoembedtrans.context.store.SceneContextStore;
import com.quarty.housamoembedtrans.scene.store.SceneStore;
import com.quarty.housamoembedtrans.storage.config.ConfigStore;

import android.content.Intent;
import android.content.res.ColorStateList;
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

    private final ExecutorService ioExecutor = Executors.newSingleThreadExecutor();
    private final Set<String> expandedSections = new LinkedHashSet<>();

    private NestedScrollView scrollView;
    private MaterialToolbar toolbar;
    private TextView status;
    private LinearLayout content;
    private String kind;
    private String objectId;
    private LoadedData loadedData;
    private long loadGeneration;
    private boolean lifecycleStarted;
    private boolean destroyed;
    private boolean editingAllowed;
    private PendingProcessMoveController pendingMoveController;
    private boolean pendingMoveBusy;
    private MaterialButton editAction;
    private MaterialButton moveAction;
    private boolean showingGroupSceneList;
    private int savedScrollY = -1;
    private int savedSceneListScrollY = -1;

    private interface BodyRenderer {
        void render(LinearLayout body);
    }

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

        LoadedData(
            JSONObject document,
            List<GroupLink> groups,
            List<ContextMember> members,
            List<SceneLink> scenes,
            AggregateData aggregate,
            String activeContextId,
            String activeGroupId
        ) {
            this.document = document;
            this.groups = groups;
            this.members = members;
            this.scenes = scenes;
            this.aggregate = aggregate;
            this.activeContextId = activeContextId;
            this.activeGroupId = activeGroupId;
        }
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_context_management_detail);
        SystemBarInsets.apply(findViewById(R.id.root_context_management_detail));

        String restoredLayer = LAYER_DETAIL;

        toolbar = findViewById(R.id.toolbar_context_management_detail);
        toolbar.setNavigationOnClickListener(
            view -> getOnBackPressedDispatcher().onBackPressed()
        );
        scrollView = findViewById(R.id.scroll_context_management_detail);
        status = findViewById(R.id.tv_context_management_detail_status);
        content = findViewById(R.id.container_context_management_detail);

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
                LAYER_DETAIL
            );
        }

        Intent intent = getIntent();
        kind = intent == null ? null : intent.getStringExtra(EXTRA_KIND);
        objectId = intent == null ? null : intent.getStringExtra(EXTRA_ID);
        showingGroupSceneList = KIND_GROUP.equals(kind)
            && LAYER_GROUP_SCENES.equals(restoredLayer);
        getOnBackPressedDispatcher().addCallback(
            this,
            new OnBackPressedCallback(true) {
                @Override
                public void handleOnBackPressed() {
                    if (showingGroupSceneList) {
                        showGroupDetailLayer();
                    } else {
                        finish();
                    }
                }
            }
        );
        if (!isSupportedRequest()) {
            showFailure(getString(R.string.context_detail_unavailable));
            return;
        }
        updateToolbarTitle();
        status.setText(R.string.context_detail_loading);
        if (!showingGroupSceneList) {
            addEditButton(false);
        }
    }

    @Override
    protected void onStart() {
        super.onStart();
        lifecycleStarted = true;
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (isSupportedRequest()) {
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
            showingGroupSceneList ? LAYER_GROUP_SCENES : LAYER_DETAIL
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

    private void loadAsync() {
        final long request = ++loadGeneration;
        loadedData = null;
        editingAllowed = false;
        content.removeAllViews();
        status.setVisibility(View.VISIBLE);
        status.setText(R.string.context_detail_loading);
        if (!showingGroupSceneList) {
            addEditButton(false);
        }

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
                LoadedData data = new LoadedData(
                    document,
                    groups,
                    members,
                    sceneLoad.sceneLinks,
                    sceneLoad.aggregate,
                    store.getActiveContextId(),
                    store.getActiveGroupId()
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
        addEditButton(editingAllowed);
        addOverview();
        if (loadedData.aggregate.missingSceneCount > 0) {
            addNotice(
                getString(
                    R.string.context_detail_missing_scenes,
                    loadedData.aggregate.missingSceneCount
                )
            );
        }
        if (isContext()) {
            addDisclosure(
                "relations",
                getString(R.string.context_detail_relations),
                getString(
                    R.string.context_detail_relations_count,
                    loadedData.scenes.size(),
                    loadedData.groups.size()
                ),
                false,
                this::renderContextRelations
            );
        } else {
            addDisclosure(
                "members",
                getString(R.string.context_detail_members),
                getString(
                    R.string.context_detail_members_count,
                    loadedData.members.size()
                ),
                false,
                this::renderGroupMembers
            );
        }
        if (hasUnavailableMembers()) {
            addNotice(getString(R.string.context_detail_partial_members));
        }
        if (!isContext()) {
            addLinkRow(
                content,
                getString(R.string.context_detail_group_scenes_entry),
                getString(
                    R.string.context_detail_group_scenes_count,
                    loadedData.scenes.size()
                ),
                this::showGroupSceneList,
                true
            );
        }
        addDisclosure(
            "aggregate-primary",
            getString(R.string.context_detail_primary_characters),
            getString(
                R.string.context_detail_primary_characters_count,
                loadedData.aggregate.primaryCharacters.size()
            ),
            false,
            this::renderPrimaryCharacters
        );
        addDisclosure(
            "aggregate-secondary",
            getString(R.string.context_detail_secondary_characters),
            getString(
                R.string.context_detail_secondary_characters_count,
                loadedData.aggregate.secondaryCharacters.size()
            ),
            false,
            this::renderSecondaryCharacters
        );
        addDisclosure(
            "aggregate-terms",
            getString(R.string.context_detail_terms),
            getString(
                R.string.context_detail_terms_count,
                loadedData.aggregate.terms.size()
            ),
            false,
            this::renderAggregateTerms
        );
        addDisclosure(
            "summaries",
            getString(R.string.context_detail_summaries),
            getString(
                R.string.context_detail_summary_count,
                languageObjectCount(loadedData.document.optJSONObject("summary"))
            ),
            false,
            body -> renderSummaries(body, loadedData.document.optJSONObject("summary"))
        );
        if (isContext()) {
            addDisclosure(
                "manual",
                getString(R.string.context_detail_manual_descriptions),
                getString(
                    R.string.context_detail_manual_description_count,
                    languageObjectCount(
                        loadedData.document.optJSONObject("manual_descriptions")
                    )
                ),
                false,
                body -> renderManualDescriptions(
                    body,
                    loadedData.document.optJSONObject("manual_descriptions")
                )
            );
        }
        restoreScrollIfNeeded();
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

    private void showGroupSceneList() {
        if (loadedData == null || isContext()) {
            return;
        }
        rememberScroll();
        showingGroupSceneList = true;
        renderDocument();
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

    private void addOverview() {
        JSONObject document = loadedData.document;
        MaterialCardView card = cardColumn();
        addText(
            card,
            document.optString("display_name", ""),
            20,
            true
        );
        addText(
            card,
            getString(
                R.string.context_detail_record,
                getString(isContext()
                    ? R.string.context_detail_context_title
                    : R.string.context_detail_group_title)
            ),
            12,
            false
        );
        boolean active = isContext()
            ? objectId.equals(loadedData.activeContextId)
            : objectId.equals(loadedData.activeGroupId);
        if (active) {
            addToneText(
                card,
                getString(R.string.context_detail_active),
                R.color.het_good_container,
                R.color.het_good
            );
        }
        content.addView(card);
    }

    private void addEditButton(boolean enabled) {
        MaterialButton button = new MaterialButton(this);
        editAction = button;
        button.setText(isContext()
            ? R.string.context_detail_edit_context
            : R.string.context_detail_edit_group);
        button.setAllCaps(false);
        button.setEnabled(enabled && !pendingMoveBusy);
        button.setOnClickListener(view -> openEditor());
        content.addView(button, new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        ));
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
        content.addView(moveAction, new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
    }

    private void setPendingMoveBusy(boolean busy) {
        pendingMoveBusy = busy;
        boolean enabled = !busy && editingAllowed && loadedData != null;
        if (editAction != null) editAction.setEnabled(enabled);
        if (moveAction != null) moveAction.setEnabled(enabled);
    }

    private void openEditor() {
        if (loadedData == null || !editingAllowed || !isSupportedRequest() || pendingMoveBusy) {
            return;
        }
        Intent intent = new Intent(this, SceneContextActivity.class);
        intent.putExtra(
            isContext()
                ? SceneContextActivity.EXTRA_MANAGEMENT_CONTEXT_ID
                : SceneContextActivity.EXTRA_MANAGEMENT_GROUP_ID,
            objectId
        );
        startActivity(intent);
    }

    private void addDisclosure(
        String key,
        String title,
        String count,
        boolean defaultExpanded,
        BodyRenderer renderer
    ) {
        MaterialCardView card = cardColumn();
        LinearLayout column = (LinearLayout) card.getTag();
        LinearLayout header = new LinearLayout(this);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);
        header.setPadding(dp(16), dp(8), dp(12), dp(8));
        TextView titleView = new TextView(this);
        titleView.setTextAppearance(this, R.style.Widget_HET_SectionTitle);
        titleView.setText(title);
        header.addView(titleView, new LinearLayout.LayoutParams(
            0,
            ViewGroup.LayoutParams.WRAP_CONTENT,
            1
        ));
        TextView countView = new TextView(this);
        countView.setTextAppearance(this, R.style.Widget_HET_SectionArrow);
        countView.setText(count);
        header.addView(countView);
        TextView arrow = new TextView(this);
        arrow.setTextAppearance(this, R.style.Widget_HET_SectionArrow);
        header.addView(arrow, new LinearLayout.LayoutParams(
            dp(32),
            ViewGroup.LayoutParams.WRAP_CONTENT
        ));

        LinearLayout body = new LinearLayout(this);
        body.setOrientation(LinearLayout.VERTICAL);
        body.setPadding(dp(16), 0, dp(16), dp(12));
        renderer.render(body);
        boolean expanded = expandedSections.contains(key) || defaultExpanded;
        body.setVisibility(expanded ? View.VISIBLE : View.GONE);
        arrow.setText(expanded
            ? R.string.array_indicator_expanded
            : R.string.array_indicator_collapsed);
        header.setContentDescription(getString(
            expanded
                ? R.string.context_detail_collapse
                : R.string.context_detail_expand,
            title
        ));
        header.setOnClickListener(view -> {
            boolean next = body.getVisibility() != View.VISIBLE;
            body.setVisibility(next ? View.VISIBLE : View.GONE);
            arrow.setText(next
                ? R.string.array_indicator_expanded
                : R.string.array_indicator_collapsed);
            header.setContentDescription(getString(
                next
                    ? R.string.context_detail_collapse
                    : R.string.context_detail_expand,
                title
            ));
            if (next) {
                expandedSections.add(key);
            } else {
                expandedSections.remove(key);
            }
        });
        column.setPadding(0, 0, 0, 0);
        column.addView(header);
        column.addView(body);
        content.addView(card);
    }

    private void renderContextRelations(LinearLayout body) {
        addSectionHeading(body, getString(
            R.string.context_detail_scenes_count,
            loadedData.scenes.size()
        ));
        if (loadedData.scenes.isEmpty()) {
            addText(body, getString(R.string.context_detail_no_entries), 14, false);
        } else {
            for (SceneLink scene : loadedData.scenes) {
                addLinkRow(
                    body,
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

        addSectionHeading(body, getString(
            R.string.context_detail_groups_count,
            loadedData.groups.size()
        ));
        if (loadedData.groups.isEmpty()) {
            addText(body, getString(R.string.context_detail_no_entries), 14, false);
        } else {
            for (GroupLink group : loadedData.groups) {
                addLinkRow(
                    body,
                    group.displayName,
                    getString(R.string.context_detail_groups),
                    () -> openDetail(KIND_GROUP, group.id),
                    true
                );
            }
        }
    }

    private void renderGroupMembers(LinearLayout body) {
        if (loadedData.members.isEmpty()) {
            addText(body, getString(R.string.context_detail_no_entries), 14, false);
            return;
        }
        for (ContextMember member : loadedData.members) {
            addLinkRow(
                body,
                member.displayName,
                getString(R.string.context_detail_members),
                member.available
                    ? () -> openDetail(KIND_CONTEXT, member.id)
                    : null,
                member.available
            );
        }
    }

    private void renderPrimaryCharacters(LinearLayout body) {
        addAggregateHint(body);
        renderAggregateCharacters(body, loadedData.aggregate.primaryCharacters);
    }

    private void renderSecondaryCharacters(LinearLayout body) {
        addAggregateHint(body);
        renderAggregateCharacters(body, loadedData.aggregate.secondaryCharacters);
    }

    private void renderAggregateCharacters(
        LinearLayout body,
        List<AggregateCharacter> entries
    ) {
        if (entries.isEmpty()) {
            addText(body, getString(R.string.context_detail_no_entries), 14, false);
            return;
        }
        for (AggregateCharacter aggregate : entries) {
            String title;
            String subtitle;
            String role;
            if (aggregate.entry instanceof SceneManagementDetailData.CharacterEntry) {
                SceneManagementDetailData.CharacterEntry entry =
                    (SceneManagementDetailData.CharacterEntry) aggregate.entry;
                title = displayCharacterName(entry);
                subtitle = getString(
                    entry.temporary
                        ? R.string.scene_detail_temporary_character
                        : R.string.scene_detail_dictionary_character,
                    entry.name
                );
                role = entry.role;
            } else {
                SceneManagementDetailData.MentionedCharacterEntry entry =
                    (SceneManagementDetailData.MentionedCharacterEntry)
                        aggregate.entry;
                title = displayMentionedName(entry);
                subtitle = getString(
                    entry.temporary
                        ? R.string.scene_detail_temporary_character
                        : R.string.scene_detail_dictionary_character,
                    entry.name
                );
                role = "mentioned";
            }
            addLinkRow(
                body,
                title,
                subtitle + " · " + aggregate.sceneName,
                () -> openSceneCharacter(
                    aggregate.sceneName,
                    role,
                    aggregate.entry instanceof SceneManagementDetailData.CharacterEntry
                        ? ((SceneManagementDetailData.CharacterEntry)
                            aggregate.entry).name
                        : ((SceneManagementDetailData.MentionedCharacterEntry)
                            aggregate.entry).name
                ),
                true
            );
        }
    }

    private void renderAggregateTerms(LinearLayout body) {
        addAggregateHint(body);
        if (loadedData.aggregate.terms.isEmpty()) {
            addText(body, getString(R.string.context_detail_no_entries), 14, false);
            return;
        }
        for (AggregateTerm aggregate : loadedData.aggregate.terms) {
            SceneManagementDetailData.TermEntry entry = aggregate.entry;
            addLinkRow(
                body,
                SceneManagementDetailData.displayTermName(entry),
                getString(
                    entry.temporary
                        ? R.string.scene_detail_temporary_term
                        : R.string.scene_detail_dictionary_term,
                    entry.term
                ) + " · " + aggregate.sceneName,
                () -> openSceneTerm(aggregate.sceneName, entry.term),
                true
            );
        }
    }

    private void addAggregateHint(LinearLayout body) {
        int available = 0;
        for (SceneLink scene : loadedData.scenes) {
            if (scene.available) {
                available++;
            }
        }
        addText(
            body,
            getString(R.string.context_detail_aggregate_scene_hint, available),
            12,
            false
        );
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
        addText(card, title, 16, true);
        addText(card, subtitle, 12, false);
        card.setEnabled(enabled);
        card.setAlpha(enabled ? 1.0f : 0.65f);
        if (action != null) {
            card.setOnClickListener(view -> action.run());
        }
        parent.addView(card);
    }

    private void addSectionHeading(LinearLayout parent, String text) {
        TextView heading = new TextView(this);
        heading.setTextAppearance(this, R.style.Widget_HET_SectionTitle);
        heading.setText(text);
        heading.setPadding(0, dp(8), 0, dp(4));
        parent.addView(heading);
    }

    private void addNotice(String text) {
        TextView notice = new TextView(this);
        notice.setTextAppearance(
            this,
            com.google.android.material.R.style.TextAppearance_MaterialComponents_Body2
        );
        notice.setText(text);
        notice.setTextSize(12);
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

    private void addToneText(
        View parent,
        String text,
        int backgroundColor,
        int foregroundColor
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
        value.setTextSize(12);
        value.setTypeface(value.getTypeface(), android.graphics.Typeface.BOLD);
        value.setTextColor(ContextCompat.getColor(this, foregroundColor));
        GradientDrawable background = new GradientDrawable();
        background.setColor(ContextCompat.getColor(this, backgroundColor));
        background.setCornerRadius(dp(10));
        value.setBackground(background);
        value.setPadding(dp(10), dp(6), dp(10), dp(6));
        column.addView(value);
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
        LinearLayout column = parent instanceof MaterialCardView
            ? (LinearLayout) parent.getTag()
            : (LinearLayout) parent;
        TextView value = new TextView(this);
        value.setTextAppearance(
            this,
            com.google.android.material.R.style.TextAppearance_MaterialComponents_Body2
        );
        value.setText(text);
        value.setTextSize(sizeSp);
        value.setTypeface(value.getTypeface(), bold
            ? android.graphics.Typeface.BOLD
            : android.graphics.Typeface.NORMAL);
        value.setTextIsSelectable(true);
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

    private int languageObjectCount(JSONObject values) {
        return languageKeys(values).size();
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
        long millis = 0L;
        if (value instanceof Number) {
            millis = ((Number) value).longValue();
        } else if (value != null) {
            try {
                millis = Long.parseLong(value.toString());
            } catch (NumberFormatException ignored) {
                // Keep the localized empty marker below.
            }
        }
        if (millis <= 0L) {
            return getString(R.string.context_detail_empty_value);
        }
        return DateFormat.getDateTimeInstance(
            DateFormat.SHORT,
            DateFormat.SHORT,
            Locale.getDefault()
        ).format(new Date(millis));
    }

    private void openScene(String sceneName) {
        startActivity(new Intent(this, SceneManagementDetailActivity.class)
            .putExtra(SceneManagementDetailActivity.EXTRA_SCENE_NAME, sceneName));
    }

    private void openSceneCharacter(
        String sceneName,
        String role,
        String name
    ) {
        startActivity(new Intent(this, SceneManagementDetailActivity.class)
            .putExtra(SceneManagementDetailActivity.EXTRA_SCENE_NAME, sceneName)
            .putExtra(SceneManagementDetailActivity.EXTRA_CHARACTER_ROLE, role)
            .putExtra(SceneManagementDetailActivity.EXTRA_CHARACTER_NAME, name));
    }

    private void openSceneTerm(String sceneName, String term) {
        startActivity(new Intent(this, SceneManagementDetailActivity.class)
            .putExtra(SceneManagementDetailActivity.EXTRA_SCENE_NAME, sceneName)
            .putExtra(SceneManagementDetailActivity.EXTRA_TERM_NAME, term));
    }

    private void openDetail(String nextKind, String nextId) {
        startActivity(new Intent(this, ContextManagementDetailActivity.class)
            .putExtra(EXTRA_KIND, nextKind)
            .putExtra(EXTRA_ID, nextId));
    }

    private void showFailure(String message) {
        editingAllowed = false;
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

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

}
