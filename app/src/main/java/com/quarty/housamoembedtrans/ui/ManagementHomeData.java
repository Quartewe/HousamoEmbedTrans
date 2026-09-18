package com.quarty.housamoembedtrans.ui;

import com.quarty.housamoembedtrans.context.model.GroupContextEntry;
import com.quarty.housamoembedtrans.context.store.SceneContextStore;
import com.quarty.housamoembedtrans.scene.store.SceneStore;
import com.quarty.housamoembedtrans.storage.config.ConfigStore;

import android.content.Context;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Locale;
import java.util.Set;

/**
 * Immutable, display-sized data for the first-level management page.
 *
 * <p>The page reads each store on its IO executor and keeps only the fields
 * needed by the list and structure views.  In particular, it does not retain
 * a large Scene document or use the import coordinator's recovery snapshot.</p>
 */
public final class ManagementHomeData {

    public static final int NODE_GROUP = 1;
    public static final int NODE_CONTEXT = 2;
    public static final int NODE_SCENE = 3;
    public static final int NODE_UNCATEGORIZED = 4;

    private ManagementHomeData() {
        throw new AssertionError("No instances");
    }

    /** Reads all display stores once and builds the corresponding tree. */
    public static Snapshot load(Context context) throws Exception {
        Context safeContext = context.getApplicationContext() != null
            ? context.getApplicationContext()
            : context;

        SceneStore sceneStore = new SceneStore(safeContext);
        List<SceneStore.SceneInfo> sceneInfos = sceneStore.listSceneInfos();
        List<SceneItem> scenes = new ArrayList<>();
        Map<String, SceneItem> scenesByName = new LinkedHashMap<>();
        for (SceneStore.SceneInfo info : sceneInfos) {
            if (info == null || isEmpty(info.sceneName)) {
                continue;
            }
            long updatedAt = 0L;
            try {
                java.io.File sceneFile = sceneStore.getValidSceneFileByName(
                    info.sceneName
                );
                if (sceneFile != null) {
                    updatedAt = sceneFile.lastModified();
                }
            } catch (RuntimeException ignored) {
                // A metadata read must not hide a Scene already accepted by
                // listSceneInfos().
            }
            SceneItem scene = new SceneItem(
                info.sceneName,
                info.languages == null ? Collections.emptyList() : info.languages,
                updatedAt
            );
            scenes.add(scene);
            scenesByName.put(scene.name, scene);
        }

        SceneContextStore contextStore = new SceneContextStore(safeContext);
        List<JSONObject> contextDocuments = contextStore.listContexts();
        List<ContextItem> contexts = new ArrayList<>();
        Map<String, ContextItem> contextsById = new LinkedHashMap<>();
        for (JSONObject document : contextDocuments) {
            ContextItem contextItem = parseContext(document, scenesByName);
            if (contextItem == null) {
                continue;
            }
            contexts.add(contextItem);
            contextsById.put(contextItem.id, contextItem);
        }

        Map<String, StringBuilder> contextNamesByScene = new HashMap<>();
        Map<String, Long> updatedAtByScene = new HashMap<>();
        for (ContextItem contextItem : contexts) {
            for (SceneRef sceneRef : contextItem.scenes) {
                StringBuilder names = contextNamesByScene.get(sceneRef.sceneName);
                if (names == null) {
                    names = new StringBuilder();
                    contextNamesByScene.put(sceneRef.sceneName, names);
                }
                if (names.length() > 0) names.append('\n');
                names.append(contextItem.displayName);
                Long previous = updatedAtByScene.get(sceneRef.sceneName);
                if (sceneRef.updatedAt > (previous == null ? 0L : previous)) {
                    updatedAtByScene.put(sceneRef.sceneName, sceneRef.updatedAt);
                }
            }
        }
        for (int index = 0; index < scenes.size(); index++) {
            SceneItem scene = scenes.get(index);
            StringBuilder names = contextNamesByScene.get(scene.name);
            Long relationUpdatedAt = updatedAtByScene.get(scene.name);
            SceneItem enriched = scene.withUpdatedAt(
                relationUpdatedAt == null ? 0L : relationUpdatedAt
            ).withRelatedContexts(
                names == null ? "" : names.toString()
            );
            scenes.set(index, enriched);
            scenesByName.put(enriched.name, enriched);
        }

        List<JSONObject> groupDocuments = contextStore.listGroups();
        List<GroupItem> groups = new ArrayList<>();
        for (JSONObject document : groupDocuments) {
            GroupItem group = parseGroup(document);
            if (group != null) {
                groups.add(group);
            }
        }

        Map<String, Integer> groupCountByContext = new HashMap<>();
        for (GroupItem group : groups) {
            Set<String> countedInGroup = new HashSet<>();
            for (GroupContextRef reference : group.contexts) {
                if (contextsById.containsKey(reference.contextId)
                    && countedInGroup.add(reference.contextId)) {
                    Integer count = groupCountByContext.get(reference.contextId);
                    groupCountByContext.put(
                        reference.contextId,
                        count == null ? 1 : count + 1
                    );
                }
            }
        }
        for (int index = 0; index < contexts.size(); index++) {
            ContextItem contextItem = contexts.get(index);
            Integer groupCount = groupCountByContext.get(contextItem.id);
            contexts.set(index, contextItem.withGroupCount(
                groupCount == null ? 0 : groupCount
            ));
        }
        Map<String, StringBuilder> groupsByContext = new HashMap<>();
        for (GroupItem group : groups) {
            for (GroupContextRef reference : group.contexts) {
                StringBuilder names = groupsByContext.get(reference.contextId);
                if (names == null) {
                    names = new StringBuilder();
                    groupsByContext.put(reference.contextId, names);
                }
                if (names.length() > 0) {
                    names.append('\n');
                }
                names.append(group.displayName);
            }
        }
        for (int index = 0; index < contexts.size(); index++) {
            ContextItem contextItem = contexts.get(index);
            StringBuilder groupNames = groupsByContext.get(contextItem.id);
            contexts.set(index, contextItem.withRelatedGroups(
                groupNames == null ? "" : groupNames.toString()
            ));
        }
        Map<String, ContextItem> enrichedContextsById = new LinkedHashMap<>();
        for (ContextItem contextItem : contexts) {
            enrichedContextsById.put(contextItem.id, contextItem);
        }
        for (int index = 0; index < groups.size(); index++) {
            GroupItem group = groups.get(index);
            StringBuilder contextNames = new StringBuilder();
            for (GroupContextRef reference : group.contexts) {
                ContextItem contextItem = enrichedContextsById.get(reference.contextId);
                if (contextItem == null) {
                    continue;
                }
                if (contextNames.length() > 0) {
                    contextNames.append('\n');
                }
                contextNames.append(contextItem.displayName);
            }
            groups.set(index, group.withRelatedContexts(contextNames.toString()));
        }
        contextsById = enrichedContextsById;

        ConfigStore configStore = new ConfigStore(safeContext);
        Locale uiLocale = safeContext.getResources().getConfiguration().locale;
        List<DictionaryItem> characters = parseDictionary(
            configStore.loadJson(ConfigStore.CHARDICT_FILE_NAME).json,
            uiLocale
        );
        List<DictionaryItem> terms = parseDictionary(
            configStore.loadJson(ConfigStore.GAMETERMS_FILE_NAME).json,
            uiLocale
        );

        List<TreeNode> tree = buildTree(
            scenes,
            contexts,
            groups,
            contextsById,
            scenesByName
        );
        return new Snapshot(
            scenes,
            contexts,
            groups,
            characters,
            terms,
            tree,
            contextStore.getActiveContextId(),
            contextStore.getActiveGroupId()
        );
    }

    public static final class Snapshot {
        public final List<SceneItem> scenes;
        public final List<ContextItem> contexts;
        public final List<GroupItem> groups;
        public final List<DictionaryItem> characters;
        public final List<DictionaryItem> terms;
        public final List<TreeNode> treeRoots;
        public final String activeContextId;
        public final String activeGroupId;

        private Snapshot(
            List<SceneItem> scenes,
            List<ContextItem> contexts,
            List<GroupItem> groups,
            List<DictionaryItem> characters,
            List<DictionaryItem> terms,
            List<TreeNode> treeRoots,
            String activeContextId,
            String activeGroupId
        ) {
            this.scenes = immutableList(scenes);
            this.contexts = immutableList(contexts);
            this.groups = immutableList(groups);
            this.characters = immutableList(characters);
            this.terms = immutableList(terms);
            this.treeRoots = immutableList(treeRoots);
            this.activeContextId = activeContextId;
            this.activeGroupId = activeGroupId;
        }
    }

    /**
     * Builds the same display snapshot from the in-memory style-preview
     * payload.  The preview path intentionally never opens a store; it only
     * projects the small list/tree fields that the home page renders.
     */
    public static Snapshot fromPreview(JSONObject payload) {
        try {
            JSONObject source = payload == null ? new JSONObject() : payload;
            List<SceneItem> scenes = new ArrayList<>();
            Map<String, SceneItem> scenesByName = new LinkedHashMap<>();
            JSONArray sceneRows = source.optJSONArray("scenes");
            if (sceneRows != null) {
                for (int index = 0; index < sceneRows.length(); index++) {
                    JSONObject row = sceneRows.optJSONObject(index);
                    String id = previewId(row);
                    if (isEmpty(id) || scenesByName.containsKey(id)) {
                        continue;
                    }
                    String displayName = firstNonEmpty(
                        row == null ? "" : row.optString("display_name", ""),
                        id
                    );
                    List<String> languages = previewStrings(row, "languages");
                    SceneItem scene = new SceneItem(
                        displayName,
                        languages,
                        row == null ? 0L : row.optLong("updated_at", 0L)
                    );
                    scenes.add(scene);
                    scenesByName.put(displayName, scene);
                }
            }

            List<ContextItem> contexts = new ArrayList<>();
            Map<String, ContextItem> contextsById = new LinkedHashMap<>();
            JSONArray contextRows = source.optJSONArray("contexts");
            if (contextRows != null) {
                for (int index = 0; index < contextRows.length(); index++) {
                    ContextItem context = parseContext(
                        contextRows.optJSONObject(index),
                        scenesByName
                    );
                    if (context != null) {
                        contexts.add(context);
                        contextsById.put(context.id, context);
                    }
                }
            }

            Map<String, Long> updatedAtByScene = new HashMap<>();
            for (ContextItem context : contexts) {
                for (SceneRef sceneRef : context.scenes) {
                    Long previous = updatedAtByScene.get(sceneRef.sceneName);
                    if (sceneRef.updatedAt > (previous == null ? 0L : previous)) {
                        updatedAtByScene.put(sceneRef.sceneName, sceneRef.updatedAt);
                    }
                }
            }
            for (int index = 0; index < scenes.size(); index++) {
                SceneItem scene = scenes.get(index);
                Long relationUpdatedAt = updatedAtByScene.get(scene.name);
                SceneItem enriched = scene.withUpdatedAt(
                    relationUpdatedAt == null ? 0L : relationUpdatedAt
                );
                scenes.set(index, enriched);
                scenesByName.put(enriched.name, enriched);
            }

            List<GroupItem> groups = new ArrayList<>();
            JSONArray groupRows = source.optJSONArray("groups");
            if (groupRows != null) {
                for (int index = 0; index < groupRows.length(); index++) {
                    GroupItem group = parseGroup(groupRows.optJSONObject(index));
                    if (group != null) {
                        groups.add(group);
                    }
                }
            }

            Map<String, Integer> groupCountByContext = new HashMap<>();
            Map<String, StringBuilder> groupsByContext = new HashMap<>();
            for (GroupItem group : groups) {
                Set<String> counted = new HashSet<>();
                for (GroupContextRef reference : group.contexts) {
                    if (!contextsById.containsKey(reference.contextId)) {
                        continue;
                    }
                    if (counted.add(reference.contextId)) {
                        Integer count = groupCountByContext.get(reference.contextId);
                        groupCountByContext.put(
                            reference.contextId,
                            count == null ? 1 : count + 1
                        );
                    }
                    StringBuilder names = groupsByContext.get(reference.contextId);
                    if (names == null) {
                        names = new StringBuilder();
                        groupsByContext.put(reference.contextId, names);
                    }
                    if (names.length() > 0) {
                        names.append('\n');
                    }
                    names.append(group.displayName);
                }
            }
            for (int index = 0; index < contexts.size(); index++) {
                ContextItem context = contexts.get(index);
                Integer count = groupCountByContext.get(context.id);
                ContextItem enriched = context.withGroupCount(
                    count == null ? 0 : count
                );
                StringBuilder groupNames = groupsByContext.get(context.id);
                contexts.set(index, enriched.withRelatedGroups(
                    groupNames == null ? "" : groupNames.toString()
                ));
            }
            contextsById.clear();
            for (ContextItem context : contexts) {
                contextsById.put(context.id, context);
            }

            for (int index = 0; index < groups.size(); index++) {
                GroupItem group = groups.get(index);
                StringBuilder names = new StringBuilder();
                for (GroupContextRef reference : group.contexts) {
                    ContextItem context = contextsById.get(reference.contextId);
                    if (context == null) {
                        continue;
                    }
                    if (names.length() > 0) {
                        names.append('\n');
                    }
                    names.append(context.displayName);
                }
                groups.set(index, group.withRelatedContexts(names.toString()));
            }

            Locale locale = Locale.getDefault();
            List<DictionaryItem> characters = parseDictionary(
                previewDictionary(source.optJSONArray("characters")),
                locale
            );
            List<DictionaryItem> terms = parseDictionary(
                previewDictionary(source.optJSONArray("terms")),
                locale
            );
            List<TreeNode> tree = buildTree(
                scenes,
                contexts,
                groups,
                contextsById,
                scenesByName
            );
            return new Snapshot(
                scenes,
                contexts,
                groups,
                characters,
                terms,
                tree,
                source.optString("active_context_id", ""),
                source.optString("active_group_id", "")
            );
        } catch (Exception error) {
            throw new IllegalArgumentException(
                "invalid management style preview payload",
                error
            );
        }
    }

    private static String previewId(JSONObject row) {
        if (row == null) {
            return "";
        }
        return firstNonEmpty(
            row.optString("id", ""),
            row.optString("key", ""),
            row.optString("name", "")
        );
    }

    private static List<String> previewStrings(JSONObject row, String key) {
        if (row == null) {
            return Collections.emptyList();
        }
        JSONArray values = row.optJSONArray(key);
        if (values == null) {
            return Collections.emptyList();
        }
        List<String> output = new ArrayList<>();
        for (int index = 0; index < values.length(); index++) {
            String value = trimmed(values.optString(index, ""));
            if (!isEmpty(value)) {
                output.add(value);
            }
        }
        return output;
    }

    private static JSONObject previewDictionary(JSONArray rows)
        throws Exception {
        JSONObject dictionary = new JSONObject();
        if (rows == null) {
            return dictionary;
        }
        for (int index = 0; index < rows.length(); index++) {
            JSONObject row = rows.optJSONObject(index);
            String id = previewId(row);
            if (row == null || isEmpty(id)) {
                continue;
            }
            JSONObject record = new JSONObject(row.toString());
            String display = firstNonEmpty(
                record.optString("display_name", ""),
                record.optString("name", ""),
                record.optString("key", "")
            );
            if (!isEmpty(display) && isEmpty(record.optString("zh-cn", ""))) {
                record.put("zh-cn", display);
            }
            dictionary.put(id, record);
        }
        return dictionary;
    }

    public static final class SceneItem {
        public final String name;
        public final int languageCount;
        public final List<String> languages;
        public final long updatedAt;
        public final String contextNames;
        public final String searchText;

        private SceneItem(String name, List<String> languages) {
            this(name, languages, 0L, "");
        }

        private SceneItem(
            String name,
            List<String> languages,
            long updatedAt
        ) {
            this(name, languages, updatedAt, "");
        }

        private SceneItem(
            String name,
            List<String> languages,
            String contextNames
        ) {
            this(name, languages, 0L, contextNames);
        }

        private SceneItem(
            String name,
            List<String> languages,
            long updatedAt,
            String contextNames
        ) {
            this.name = name;
            this.languages = immutableList(languages);
            this.languageCount = this.languages.size();
            this.updatedAt = updatedAt;
            this.contextNames = contextNames == null ? "" : contextNames;
            StringBuilder search = new StringBuilder(name);
            for (String language : this.languages) {
                appendSearchField(search, language);
            }
            appendSearchField(search, this.contextNames);
            this.searchText = search.toString();
        }

        private SceneItem withRelatedContexts(String names) {
            return new SceneItem(name, languages, updatedAt, names);
        }

        private SceneItem withUpdatedAt(long timestamp) {
            if (timestamp <= updatedAt) {
                return this;
            }
            return new SceneItem(name, languages, timestamp, contextNames);
        }
    }

    public static final class SceneRef {
        public final String entryId;
        public final String sceneName;
        public final long updatedAt;

        private SceneRef(String entryId, String sceneName, long updatedAt) {
            this.entryId = entryId;
            this.sceneName = sceneName;
            this.updatedAt = updatedAt;
        }
    }

    public static final class ContextItem {
        public final String id;
        public final String displayName;
        public final List<SceneRef> scenes;
        public final int groupCount;
        public final String summary;
        public final List<String> summaryLanguages;
        public final long updatedAt;
        public final String searchText;

        private ContextItem(
            String id,
            String displayName,
            List<SceneRef> scenes,
            int groupCount,
            String summary,
            List<String> summaryLanguages,
            long updatedAt,
            String searchText
        ) {
            this.id = id;
            this.displayName = displayName;
            this.scenes = immutableList(scenes);
            this.groupCount = groupCount;
            this.summary = summary == null ? "" : summary;
            this.summaryLanguages = immutableList(summaryLanguages);
            this.updatedAt = updatedAt;
            this.searchText = searchText == null ? displayName : searchText;
        }

        private ContextItem withGroupCount(int count) {
            return new ContextItem(
                id,
                displayName,
                scenes,
                count,
                summary,
                summaryLanguages,
                updatedAt,
                searchText
            );
        }

        private ContextItem withRelatedGroups(String groupNames) {
            StringBuilder search = new StringBuilder(searchText);
            appendSearchField(search, groupNames);
            return new ContextItem(
                id,
                displayName,
                scenes,
                groupCount,
                summary,
                summaryLanguages,
                updatedAt,
                search.toString()
            );
        }
    }

    public static final class GroupContextRef {
        public final String entryId;
        public final String contextId;

        private GroupContextRef(String entryId, String contextId) {
            this.entryId = entryId;
            this.contextId = contextId;
        }
    }

    public static final class GroupItem {
        public final String id;
        public final String displayName;
        public final List<GroupContextRef> contexts;
        public final String summary;
        public final List<String> summaryLanguages;
        public final long updatedAt;
        public final String searchText;

        private GroupItem(
            String id,
            String displayName,
            List<GroupContextRef> contexts,
            String summary,
            List<String> summaryLanguages,
            long updatedAt,
            String searchText
        ) {
            this.id = id;
            this.displayName = displayName;
            this.contexts = immutableList(contexts);
            this.summary = summary == null ? "" : summary;
            this.summaryLanguages = immutableList(summaryLanguages);
            this.updatedAt = updatedAt;
            this.searchText = searchText == null ? displayName : searchText;
        }

        private GroupItem withRelatedContexts(String contextNames) {
            StringBuilder search = new StringBuilder(searchText);
            appendSearchField(search, contextNames);
            return new GroupItem(
                id,
                displayName,
                contexts,
                summary,
                summaryLanguages,
                updatedAt,
                search.toString()
            );
        }
    }

    public static final class DictionaryItem {
        public final String key;
        public final String subtitle;
        public final String searchText;
        public final int aliasCount;

        private DictionaryItem(
            String key,
            String subtitle,
            String searchText,
            int aliasCount
        ) {
            this.key = key;
            this.subtitle = subtitle;
            this.searchText = searchText;
            this.aliasCount = aliasCount;
        }
    }

    /** One immutable node in the group → context → scene display tree. */
    public static final class TreeNode {
        public final int kind;
        public final String key;
        public final String label;
        public final String targetId;
        public final int count;
        public final List<TreeNode> children;
        public final String summary;
        public final long updatedAt;
        public final String searchText;

        private TreeNode(
            int kind,
            String key,
            String label,
            String targetId,
            int count,
            List<TreeNode> children,
            String summary,
            long updatedAt,
            String searchText
        ) {
            this.kind = kind;
            this.key = key;
            this.label = label;
            this.targetId = targetId;
            this.count = count;
            this.children = immutableList(children);
            this.summary = summary == null ? "" : summary;
            this.updatedAt = updatedAt;
            this.searchText = searchText == null ? label : searchText;
        }
    }

    private static ContextItem parseContext(
        JSONObject document,
        Map<String, SceneItem> scenesByName
    ) {
        if (document == null) {
            return null;
        }
        String id = trimmed(document.optString("id", ""));
        String displayName = trimmed(document.optString("display_name", ""));
        if (isEmpty(id) || isEmpty(displayName)) {
            return null;
        }
        List<SceneRef> sceneRefs = new ArrayList<>();
        JSONArray sceneEntries = document.optJSONArray("scenes");
        if (sceneEntries != null) {
            for (int index = 0; index < sceneEntries.length(); index++) {
                JSONObject entry = sceneEntries.optJSONObject(index);
                if (entry == null) {
                    continue;
                }
                String sceneName = trimmed(entry.optString("scene", ""));
                if (isEmpty(sceneName) || !scenesByName.containsKey(sceneName)) {
                    continue;
                }
                String entryId = trimmed(entry.optString("entry_id", ""));
                if (isEmpty(entryId)) {
                    entryId = Integer.toString(index);
                }
                sceneRefs.add(new SceneRef(
                    entryId,
                    sceneName,
                    entry.optLong("updated_at", 0L)
                ));
            }
        }
        JSONObject summaryObject = document.optJSONObject("summary");
        String summary = summaryText(summaryObject);
        StringBuilder searchText = new StringBuilder(id)
            .append('\n').append(displayName);
        appendSearchField(
            searchText,
            summaryText(document.optJSONObject("summary"))
        );
        appendSearchField(
            searchText,
            flattenJson(document.optJSONObject("manual_descriptions"))
        );
        for (SceneRef sceneRef : sceneRefs) {
            appendSearchField(searchText, sceneRef.sceneName);
        }
        return new ContextItem(
            id,
            displayName,
            sceneRefs,
            0,
            summary,
            summaryLanguages(summaryObject),
            document.optLong("updated_at", 0L),
            searchText.toString()
        );
    }

    private static GroupItem parseGroup(JSONObject document) {
        if (document == null) {
            return null;
        }
        String id = trimmed(document.optString("id", ""));
        String displayName = trimmed(document.optString("display_name", ""));
        if (isEmpty(id) || isEmpty(displayName)) {
            return null;
        }
        List<GroupContextRef> refs = new ArrayList<>();
        JSONArray entries = document.optJSONArray("contexts");
        if (entries != null) {
            for (int index = 0; index < entries.length(); index++) {
                JSONObject entry = entries.optJSONObject(index);
                if (entry == null) {
                    continue;
                }
                String contextId = trimmed(entry.optString(
                    GroupContextEntry.CONTEXT_ID,
                    ""
                ));
                if (isEmpty(contextId)) {
                    continue;
                }
                String entryId = trimmed(entry.optString(
                    GroupContextEntry.ENTRY_ID,
                    ""
                ));
                if (isEmpty(entryId)) {
                    entryId = Integer.toString(index);
                }
                refs.add(new GroupContextRef(entryId, contextId));
            }
        }
        JSONObject summaryObject = document.optJSONObject("summary");
        String summary = summaryText(summaryObject);
        StringBuilder searchText = new StringBuilder(id)
            .append('\n').append(displayName);
        appendSearchField(searchText, summary);
        appendSearchField(
            searchText,
            flattenJson(document.optJSONObject("manual_descriptions"))
        );
        for (GroupContextRef reference : refs) {
            appendSearchField(searchText, reference.contextId);
        }
        return new GroupItem(
            id,
            displayName,
            refs,
            summary,
            summaryLanguages(summaryObject),
            document.optLong("updated_at", 0L),
            searchText.toString()
        );
    }

    private static List<DictionaryItem> parseDictionary(
        JSONObject dictionary,
        Locale uiLocale
    ) {
        if (dictionary == null) {
            return Collections.emptyList();
        }
        List<String> keys = new ArrayList<>();
        java.util.Iterator<String> iterator = dictionary.keys();
        while (iterator.hasNext()) {
            keys.add(iterator.next());
        }
        Collections.sort(keys);
        List<DictionaryItem> result = new ArrayList<>();
        for (String key : keys) {
            JSONObject record = dictionary.optJSONObject(key);
            if (record == null || isEmpty(key)) {
                continue;
            }
            String subtitle = firstNonEmpty(
                localizedValue(record, uiLocale),
                record.optString("zh-cn", ""),
                record.optString("en", ""),
                record.optString("zh-tw", ""),
                record.optString("info", ""),
                record.optString("description", "")
            );
            StringBuilder searchText = new StringBuilder(key);
            appendSearchField(searchText, subtitle);
            appendSearchField(searchText, record.optString("en", ""));
            appendSearchField(searchText, record.optString("zh-tw", ""));
            appendSearchField(searchText, record.optString("zh-cn", ""));
            appendSearchField(searchText, record.optString("info", ""));
            appendSearchField(searchText, record.optString("description", ""));
            JSONArray aliases = record.optJSONArray("alias");
            if (aliases != null) {
                for (int aliasIndex = 0; aliasIndex < aliases.length(); aliasIndex++) {
                    JSONObject alias = aliases.optJSONObject(aliasIndex);
                    if (alias == null) {
                        continue;
                    }
                    appendSearchField(searchText, alias.optString("name", ""));
                    appendSearchField(searchText, alias.optString("called", ""));
                    appendSearchField(searchText, alias.optString("en", ""));
                    appendSearchField(searchText, alias.optString("zh-tw", ""));
                    appendSearchField(searchText, alias.optString("zh-cn", ""));
                }
            }
            int aliasCount = aliases == null ? 0 : aliases.length();
            result.add(new DictionaryItem(
                key,
                subtitle,
                searchText.toString(),
                aliasCount
            ));
        }
        return result;
    }

    private static String localizedValue(JSONObject record, Locale locale) {
        if (record == null) {
            return "";
        }
        Locale safeLocale = locale == null ? Locale.getDefault() : locale;
        String language = safeLocale.getLanguage();
        String country = safeLocale.getCountry();
        boolean traditionalChinese = "zh".equals(language)
            && ("TW".equalsIgnoreCase(country)
                || "HK".equalsIgnoreCase(country)
                || "MO".equalsIgnoreCase(country));
        if (traditionalChinese) {
            return trimmed(record.optString("zh-tw", ""));
        }
        if ("zh".equals(language)) {
            return trimmed(record.optString("zh-cn", ""));
        }
        if ("en".equals(language)) {
            return trimmed(record.optString("en", ""));
        }
        return "";
    }

    private static List<TreeNode> buildTree(
        List<SceneItem> scenes,
        List<ContextItem> contexts,
        List<GroupItem> groups,
        Map<String, ContextItem> contextsById,
        Map<String, SceneItem> scenesByName
    ) {
        List<TreeNode> roots = new ArrayList<>();
        Set<String> groupedContextIds = new HashSet<>();
        Set<String> categorizedSceneNames = new HashSet<>();

        for (GroupItem group : groups) {
            List<TreeNode> contextNodes = new ArrayList<>();
            for (GroupContextRef reference : group.contexts) {
                ContextItem context = contextsById.get(reference.contextId);
                if (context == null) {
                    continue;
                }
                groupedContextIds.add(context.id);
                String contextKey = "group/" + group.id
                    + "/context-entry/" + reference.entryId
                    + "/context/" + context.id;
                List<TreeNode> sceneNodes = sceneNodes(
                    context,
                    contextKey,
                    scenesByName,
                    categorizedSceneNames
                );
                contextNodes.add(new TreeNode(
                    NODE_CONTEXT,
                    contextKey,
                    context.displayName,
                    context.id,
                    sceneNodes.size(),
                    sceneNodes,
                    context.summary,
                    context.updatedAt,
                    context.searchText
                ));
            }
            String groupKey = "group/" + group.id;
            roots.add(new TreeNode(
                NODE_GROUP,
                groupKey,
                group.displayName,
                group.id,
                contextNodes.size(),
                contextNodes,
                group.summary,
                group.updatedAt,
                group.searchText
            ));
        }

        List<TreeNode> uncategorized = new ArrayList<>();
        for (ContextItem context : contexts) {
            if (groupedContextIds.contains(context.id)) {
                continue;
            }
            String contextKey = "uncategorized/context/" + context.id;
            List<TreeNode> sceneNodes = sceneNodes(
                context,
                contextKey,
                scenesByName,
                categorizedSceneNames
            );
            uncategorized.add(new TreeNode(
                NODE_CONTEXT,
                contextKey,
                context.displayName,
                context.id,
                sceneNodes.size(),
                sceneNodes,
                context.summary,
                context.updatedAt,
                context.searchText
            ));
        }
        for (SceneItem scene : scenes) {
            if (categorizedSceneNames.contains(scene.name)) {
                continue;
            }
            uncategorized.add(new TreeNode(
                NODE_SCENE,
                "uncategorized/scene/" + scene.name,
                scene.name,
                scene.name,
                scene.languageCount,
                Collections.emptyList(),
                "",
                0L,
                scene.searchText
            ));
        }
        if (!uncategorized.isEmpty()) {
            roots.add(new TreeNode(
                NODE_UNCATEGORIZED,
                "uncategorized",
                "",
                null,
                uncategorized.size(),
                uncategorized,
                "",
                0L,
                "uncategorized"
            ));
        }
        return roots;
    }

    private static List<TreeNode> sceneNodes(
        ContextItem context,
        String contextKey,
        Map<String, SceneItem> scenesByName,
        Set<String> categorizedSceneNames
    ) {
        List<TreeNode> result = new ArrayList<>();
        for (SceneRef sceneRef : context.scenes) {
            SceneItem scene = scenesByName.get(sceneRef.sceneName);
            if (scene == null) {
                continue;
            }
            categorizedSceneNames.add(scene.name);
            String sceneKey = contextKey + "/scene-entry/"
                + sceneRef.entryId + "/scene/" + scene.name;
            result.add(new TreeNode(
                NODE_SCENE,
                sceneKey,
                scene.name,
                scene.name,
                scene.languageCount,
                Collections.emptyList(),
                "",
                0L,
                scene.searchText
            ));
        }
        return result;
    }

    private static String firstNonEmpty(String... values) {
        for (String value : values) {
            String trimmed = trimmed(value);
            if (!isEmpty(trimmed)) {
                return trimmed;
            }
        }
        return "";
    }

    /** Reads existing persisted summary text without introducing a new field. */
    private static String summaryText(JSONObject summary) {
        return flattenJson(summary);
    }

    private static List<String> summaryLanguages(JSONObject summary) {
        if (summary == null) return Collections.emptyList();
        List<String> result = new ArrayList<>();
        java.util.Iterator<String> keys = summary.keys();
        while (keys.hasNext()) {
            String key = keys.next();
            if (isSummaryMetadata(key)) continue;
            if (hasSummaryValue(summary.opt(key))) result.add(key);
        }
        Collections.sort(result, String.CASE_INSENSITIVE_ORDER);
        return result;
    }

    private static boolean hasSummaryValue(Object value) {
        StringBuilder text = new StringBuilder();
        appendJsonText(text, value);
        return text.length() > 0;
    }

    private static String flattenJson(JSONObject object) {
        if (object == null) {
            return "";
        }
        StringBuilder output = new StringBuilder();
        java.util.Iterator<String> keys = object.keys();
        while (keys.hasNext()) {
            String key = keys.next();
            if (isSummaryMetadata(key)) {
                continue;
            }
            Object value = object.opt(key);
            appendJsonText(output, value);
        }
        return output.toString().trim();
    }

    private static void appendJsonText(StringBuilder output, Object value) {
        if (value == null || value == JSONObject.NULL) {
            return;
        }
        if (value instanceof JSONObject) {
            JSONObject object = (JSONObject) value;
            String text = trimmed(object.optString("text", ""));
            if (!isEmpty(text)) {
                appendSearchField(output, text);
            }
            java.util.Iterator<String> keys = object.keys();
            while (keys.hasNext()) {
                String key = keys.next();
                if ("text".equals(key) || isSummaryMetadata(key)) {
                    continue;
                }
                appendJsonText(output, object.opt(key));
            }
            return;
        }
        if (value instanceof JSONArray) {
            JSONArray array = (JSONArray) value;
            for (int index = 0; index < array.length(); index++) {
                appendJsonText(output, array.opt(index));
            }
            return;
        }
        appendSearchField(output, String.valueOf(value));
    }

    private static boolean isSummaryMetadata(String key) {
        return "updated_at".equals(key)
            || "created_at".equals(key)
            || "source".equals(key)
            || "request_id".equals(key)
            || "status".equals(key);
    }

    private static void appendSearchField(StringBuilder output, String value) {
        String trimmed = trimmed(value);
        if (!isEmpty(trimmed)) {
            output.append('\n').append(trimmed);
        }
    }

    private static String trimmed(String value) {
        return value == null ? "" : value.trim();
    }

    private static boolean isEmpty(String value) {
        return value == null || value.trim().isEmpty();
    }

    private static <T> List<T> immutableList(List<T> values) {
        return Collections.unmodifiableList(new ArrayList<>(values));
    }
}
