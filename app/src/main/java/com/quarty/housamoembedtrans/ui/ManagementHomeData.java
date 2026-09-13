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
            SceneItem scene = new SceneItem(
                info.sceneName,
                info.languages == null ? 0 : info.languages.size()
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

    public static final class SceneItem {
        public final String name;
        public final int languageCount;

        private SceneItem(String name, int languageCount) {
            this.name = name;
            this.languageCount = languageCount;
        }
    }

    public static final class SceneRef {
        public final String entryId;
        public final String sceneName;

        private SceneRef(String entryId, String sceneName) {
            this.entryId = entryId;
            this.sceneName = sceneName;
        }
    }

    public static final class ContextItem {
        public final String id;
        public final String displayName;
        public final List<SceneRef> scenes;
        public final int groupCount;

        private ContextItem(
            String id,
            String displayName,
            List<SceneRef> scenes,
            int groupCount
        ) {
            this.id = id;
            this.displayName = displayName;
            this.scenes = immutableList(scenes);
            this.groupCount = groupCount;
        }

        private ContextItem withGroupCount(int count) {
            return new ContextItem(id, displayName, scenes, count);
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

        private GroupItem(
            String id,
            String displayName,
            List<GroupContextRef> contexts
        ) {
            this.id = id;
            this.displayName = displayName;
            this.contexts = immutableList(contexts);
        }
    }

    public static final class DictionaryItem {
        public final String key;
        public final String subtitle;
        public final String searchText;

        private DictionaryItem(String key, String subtitle, String searchText) {
            this.key = key;
            this.subtitle = subtitle;
            this.searchText = searchText;
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

        private TreeNode(
            int kind,
            String key,
            String label,
            String targetId,
            int count,
            List<TreeNode> children
        ) {
            this.kind = kind;
            this.key = key;
            this.label = label;
            this.targetId = targetId;
            this.count = count;
            this.children = immutableList(children);
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
                sceneRefs.add(new SceneRef(entryId, sceneName));
            }
        }
        return new ContextItem(id, displayName, sceneRefs, 0);
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
        return new GroupItem(id, displayName, refs);
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
            result.add(new DictionaryItem(key, subtitle, searchText.toString()));
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
                    sceneNodes
                ));
            }
            String groupKey = "group/" + group.id;
            roots.add(new TreeNode(
                NODE_GROUP,
                groupKey,
                group.displayName,
                group.id,
                contextNodes.size(),
                contextNodes
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
                sceneNodes
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
                Collections.emptyList()
            ));
        }
        if (!uncategorized.isEmpty()) {
            roots.add(new TreeNode(
                NODE_UNCATEGORIZED,
                "uncategorized",
                "",
                null,
                uncategorized.size(),
                uncategorized
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
                Collections.emptyList()
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
