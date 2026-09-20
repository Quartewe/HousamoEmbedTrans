package com.quarty.housamoembedtrans.ui;

import android.content.Context;
import android.content.Intent;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * In-memory handoff for the read-only style preview pages.
 *
 * <p>The preview flag is deliberately carried on the Activity intent.  A real
 * page can therefore keep its normal renderer while selecting an in-memory
 * object before it constructs a durable store or a service client.  This
 * class never reads or writes an application file.</p>
 */
public final class StylePreview {
    public static final String EXTRA_STYLE_PREVIEW =
        "com.quarty.housamoembedtrans.extra.STYLE_PREVIEW";
    public static final String EXTRA_STYLE_PREVIEW_KIND =
        "com.quarty.housamoembedtrans.extra.STYLE_PREVIEW_KIND";
    public static final String EXTRA_STYLE_PREVIEW_JSON =
        "com.quarty.housamoembedtrans.extra.STYLE_PREVIEW_JSON";

    public static final String KIND_HOME = "home";
    public static final String KIND_TASKS = "tasks";
    public static final String KIND_HELD_ARRANGEMENT = "held-arrangement";
    public static final String KIND_MANAGEMENT_HOME = "management-home";
    public static final String KIND_SCENE_SYNC = "scene-sync";
    public static final String KIND_MANAGEMENT_EXPORT = "management-export";
    public static final String KIND_SCENE_DETAIL = "scene-detail";
    public static final String KIND_SCENE_EDITOR = "scene-editor";
    public static final String KIND_CONTEXT_DETAIL = "context-detail";
    public static final String KIND_GROUP_DETAIL = "group-detail";
    public static final String KIND_CONTEXT_EDITOR = "context-editor";
    public static final String KIND_GROUP_EDITOR = "group-editor";
    public static final String KIND_CHARACTER_DETAIL = "character-detail";
    public static final String KIND_TERM_DETAIL = "term-detail";
    public static final String KIND_CHARACTER_EDITOR = "character-editor";
    public static final String KIND_TERM_EDITOR = "term-editor";
    public static final String KIND_MANAGEMENT_IMPORT = "management-import";

    public static final String SAMPLE_SCENE_NAME = "preview_style_scene";
    public static final String SAMPLE_CONTEXT_ID = "preview_context";
    public static final String SAMPLE_GROUP_ID = "preview_group";
    public static final String SAMPLE_CHARACTER_NAME = "preview_character";
    public static final String SAMPLE_TERM_NAME = "preview_term";

    private static final List<String> KINDS = Collections.unmodifiableList(
        Arrays.asList(
            KIND_HOME,
            KIND_TASKS,
            KIND_HELD_ARRANGEMENT,
            KIND_MANAGEMENT_HOME,
            KIND_SCENE_SYNC,
            KIND_MANAGEMENT_EXPORT,
            KIND_SCENE_DETAIL,
            KIND_SCENE_EDITOR,
            KIND_CONTEXT_DETAIL,
            KIND_GROUP_DETAIL,
            KIND_CONTEXT_EDITOR,
            KIND_GROUP_EDITOR,
            KIND_CHARACTER_DETAIL,
            KIND_TERM_DETAIL,
            KIND_CHARACTER_EDITOR,
            KIND_TERM_EDITOR,
            KIND_MANAGEMENT_IMPORT
        )
    );

    private StylePreview() {
    }

    public static boolean isEnabled(Intent intent) {
        return intent != null && intent.getBooleanExtra(
            EXTRA_STYLE_PREVIEW,
            false
        );
    }

    public static boolean isEnabled(android.app.Activity activity) {
        return activity != null && isEnabled(activity.getIntent());
    }

    public static String kindOf(Intent intent) {
        if (!isEnabled(intent)) {
            return "";
        }
        String kind = intent.getStringExtra(EXTRA_STYLE_PREVIEW_KIND);
        return kind == null ? "" : kind;
    }

    /** Returns a defensive in-memory copy of the payload in an Activity intent. */
    public static JSONObject payloadOf(Intent intent) {
        if (!isEnabled(intent)) {
            return null;
        }
        String encoded = intent.getStringExtra(EXTRA_STYLE_PREVIEW_JSON);
        if (encoded == null || encoded.trim().isEmpty()) {
            return null;
        }
        try {
            return new JSONObject(encoded);
        } catch (JSONException error) {
            return null;
        }
    }

    public static List<String> kinds() {
        return KINDS;
    }

    /** Adds a fresh sample payload to a target Activity intent. */
    public static Intent putPreview(Intent intent, String kind) {
        if (intent == null) {
            throw new IllegalArgumentException("intent is required");
        }
        String safeKind = kind == null ? "" : kind;
        intent.putExtra(EXTRA_STYLE_PREVIEW, true);
        intent.putExtra(EXTRA_STYLE_PREVIEW_KIND, safeKind);
        intent.putExtra(EXTRA_STYLE_PREVIEW_JSON, sample(safeKind).toString());
        return intent;
    }

    /** Builds the real page intent used by the selection surface. */
    public static Intent intentFor(Context context, String kind) {
        if (context == null) {
            throw new IllegalArgumentException("context is required");
        }
        if (KIND_HOME.equals(kind)) {
            return putPreview(new Intent(context, HomeActivity.class), kind);
        }
        if (KIND_TASKS.equals(kind)) {
            return putPreview(new Intent(context, TranslationQueueActivity.class), kind);
        }
        if (KIND_MANAGEMENT_HOME.equals(kind)
            || KIND_MANAGEMENT_EXPORT.equals(kind)) {
            return putPreview(new Intent(context, ManagementHomeActivity.class), kind);
        }
        if (KIND_SCENE_SYNC.equals(kind)) {
            return putPreview(new Intent(context, SceneFilesActivity.class), kind);
        }
        if (KIND_SCENE_DETAIL.equals(kind)) {
            return putPreview(new Intent(context, SceneManagementDetailActivity.class)
                .putExtra(SceneManagementDetailActivity.EXTRA_SCENE_NAME, SAMPLE_SCENE_NAME), kind);
        }
        if (KIND_SCENE_EDITOR.equals(kind)) {
            return putPreview(new Intent(context, SceneManagementEditorActivity.class)
                .putExtra(SceneManagementEditorActivity.EXTRA_SCENE_NAME, SAMPLE_SCENE_NAME), kind);
        }
        if (KIND_CONTEXT_DETAIL.equals(kind) || KIND_GROUP_DETAIL.equals(kind)) {
            boolean contextKind = KIND_CONTEXT_DETAIL.equals(kind);
            return putPreview(new Intent(context, ContextManagementDetailActivity.class)
                .putExtra(ContextManagementDetailActivity.EXTRA_KIND, contextKind ? "context" : "group")
                .putExtra(ContextManagementDetailActivity.EXTRA_ID, contextKind ? SAMPLE_CONTEXT_ID : SAMPLE_GROUP_ID), kind);
        }
        if (KIND_CONTEXT_EDITOR.equals(kind) || KIND_GROUP_EDITOR.equals(kind)) {
            boolean contextKind = KIND_CONTEXT_EDITOR.equals(kind);
            return putPreview(new Intent(context, ContextGroupEditorActivity.class)
                .putExtra(ContextGroupEditorActivity.EXTRA_KIND, contextKind ? "context" : "group")
                .putExtra(ContextGroupEditorActivity.EXTRA_ID, contextKind ? SAMPLE_CONTEXT_ID : SAMPLE_GROUP_ID), kind);
        }
        if (KIND_CHARACTER_DETAIL.equals(kind) || KIND_TERM_DETAIL.equals(kind)) {
            boolean characterKind = KIND_CHARACTER_DETAIL.equals(kind);
            return putPreview(new Intent(context, DictionaryManagementDetailActivity.class)
                .putExtra(DictionaryManagementDetailActivity.EXTRA_KIND, characterKind ? "character" : "term")
                .putExtra(DictionaryManagementDetailActivity.EXTRA_KEY, characterKind ? SAMPLE_CHARACTER_NAME : SAMPLE_TERM_NAME), kind);
        }
        if (KIND_CHARACTER_EDITOR.equals(kind)) {
            return putPreview(new Intent(context, CharacterDictionaryActivity.class)
                .putExtra(CharacterDictionaryActivity.EXTRA_CHARACTER_NAME, SAMPLE_CHARACTER_NAME), kind);
        }
        if (KIND_TERM_EDITOR.equals(kind)) {
            return putPreview(new Intent(context, GameTermsActivity.class)
                .putExtra(GameTermsActivity.EXTRA_TERM_NAME, SAMPLE_TERM_NAME), kind);
        }
        if (KIND_MANAGEMENT_IMPORT.equals(kind)) {
            return putPreview(new Intent(context, ManagementImportActivity.class), kind);
        }
        throw new IllegalArgumentException("unknown style preview kind: " + kind);
    }

    /** Returns a new sample object for the requested real page. */
    public static JSONObject sample(String kind) {
        try {
            if (KIND_HOME.equals(kind)) return new JSONObject()
                .put("app_version", "1.0")
                .put("latest_app_version", "1.1")
                .put("resource_version", "1")
                .put("resource_update_available", true)
                .put("game_version", "5.0.0")
                .put("target_language", "zh-cn")
                .put("runtime", sampleSceneSync().getJSONObject("runtime"));
            if (KIND_TASKS.equals(kind)) return sampleTasks();
            if (KIND_MANAGEMENT_HOME.equals(kind)
                || KIND_MANAGEMENT_EXPORT.equals(kind)) return sampleManagementHome();
            if (KIND_SCENE_SYNC.equals(kind)) return sampleSceneSync();
            if (KIND_SCENE_DETAIL.equals(kind)
                || KIND_SCENE_EDITOR.equals(kind)) return sampleScene();
            if (KIND_CONTEXT_DETAIL.equals(kind)
                || KIND_CONTEXT_EDITOR.equals(kind)) return sampleContext();
            if (KIND_GROUP_DETAIL.equals(kind)
                || KIND_GROUP_EDITOR.equals(kind)) return sampleGroup();
            if (KIND_CHARACTER_DETAIL.equals(kind)
                || KIND_CHARACTER_EDITOR.equals(kind)) return sampleCharacter();
            if (KIND_TERM_DETAIL.equals(kind)
                || KIND_TERM_EDITOR.equals(kind)) return sampleTerm();
            if (KIND_MANAGEMENT_IMPORT.equals(kind)) return sampleImport();
        } catch (JSONException error) {
            throw new IllegalStateException("could not create style preview", error);
        }
        throw new IllegalArgumentException("unknown style preview kind: " + kind);
    }

    private static JSONObject sampleTasks() throws JSONException {
        JSONArray tasks = new JSONArray()
            .put(new JSONObject()
                .put("request_id", "preview-queued")
                .put("kind", "translation")
                .put("scene", SAMPLE_SCENE_NAME)
                .put("target_language", "zh-cn")
                .put("status", "queued")
                .put("reason", "等待恢复顺序")
                .put("created_at", 1_725_000_000_000L))
            .put(new JSONObject()
                .put("request_id", "preview-running")
                .put("kind", "translation")
                .put("scene", "preview_running_scene")
                .put("target_language", "zh-cn")
                .put("status", "running")
                .put("reason", "翻译服务正在处理")
                .put("created_at", 1_725_000_100_000L))
            .put(new JSONObject()
                .put("request_id", "preview-failed")
                .put("kind", "summary")
                .put("scene", SAMPLE_CONTEXT_ID)
                .put("target_language", "zh-cn")
                .put("status", "failed")
                .put("reason", "需要重新尝试")
                .put("created_at", 1_725_000_200_000L))
            .put(new JSONObject()
                .put("request_id", "preview-completed")
                .put("kind", "translation")
                .put("scene", "preview_completed_scene")
                .put("target_language", "zh-cn")
                .put("status", "completed")
                .put("reason", "已完成")
                .put("created_at", 1_725_000_300_000L));
        return new JSONObject()
            .put("version", 1)
            .put("tasks", tasks)
            .put("waiting", new JSONArray()
                .put(new JSONObject()
                    .put("kind", "scene")
                    .put("canonical_id", SAMPLE_SCENE_NAME)
                    .put("reason", "等待管理处理")));
    }

    private static JSONObject sampleManagementHome() throws JSONException {
        return new JSONObject()
            .put("version", 1)
            .put("scenes", new JSONArray()
                .put(new JSONObject()
                    .put("id", SAMPLE_SCENE_NAME)
                    .put("display_name", "样式核对 · 夏日街道")
                    .put("languages", new JSONArray().put("zh-cn").put("en")))
                .put(new JSONObject().put("id", "preview_empty_scene").put("display_name", "空数据示例")))
            .put("contexts", new JSONArray()
                .put(new JSONObject()
                    .put("id", SAMPLE_CONTEXT_ID)
                    .put("display_name", "样式核对上下文")
                    .put("scenes", new JSONArray()
                        .put(new JSONObject()
                            .put("entry_id", "preview_scene_entry")
                            .put("scene", "样式核对 · 夏日街道")))
                    .put("summary", new JSONObject()
                        .put("zh-cn", new JSONObject()
                            .put("text", "上下文包含一段示例剧情。")))))
            .put("groups", new JSONArray()
                .put(new JSONObject()
                    .put("id", SAMPLE_GROUP_ID)
                    .put("display_name", "样式核对分组")
                    .put("contexts", new JSONArray()
                        .put(new JSONObject().put("context_id", SAMPLE_CONTEXT_ID)))
                    .put("summary", new JSONObject()
                        .put("zh-cn", new JSONObject()
                            .put("text", "分组中的示例上下文摘要。")))))
            .put("characters", new JSONArray()
                .put(sampleCharacter()
                    .put("id", SAMPLE_CHARACTER_NAME)
                    .put("display_name", "星野明")))
            .put("terms", new JSONArray()
                .put(sampleTerm()
                    .put("id", SAMPLE_TERM_NAME)
                    .put("display_name", "天穹学院")))
            .put("pending_count", 2)
            .put("translation_task_count", 3)
            .put("summary_task_count", 1);
    }

    private static JSONObject sampleScene() throws JSONException {
        JSONObject character = new JSONObject()
            .put("mc", new JSONObject()
                .put("name", "mc")
                .put("i18n", new JSONObject().put("zh_cn", "主角")))
            .put("high_weight", new JSONArray()
                .put(new JSONObject()
                    .put("name", SAMPLE_CHARACTER_NAME)
                    .put("i18n", new JSONObject()
                        .put("en", "Hoshino Akira")
                        .put("zh_cn", "星野明"))
                    .put("description", "谨慎而可靠的同行者。")))
            .put("low_weight", new JSONArray()
                .put(new JSONObject()
                    .put("name", "preview_guide")
                    .put("i18n", new JSONObject().put("zh_cn", "向导"))));
        JSONArray items = new JSONArray()
            .put(new JSONObject()
                .put("type", "text")
                .put("speaker", SAMPLE_CHARACTER_NAME)
                .put("text", "今日は静かな通りですね。")
                .put("translations", new JSONObject().put("zh-cn", "今天的街道真安静。"))
                .put("order", new JSONObject().put("label_index", 0).put("page_no", 0).put("cmd_index", 0).put("sub_index", 0)))
            .put(new JSONObject()
                .put("type", "text")
                .put("speaker", "mc")
                .put("text", "先看看前面发生了什么。")
                .put("translations", new JSONObject().put("zh-cn", "先看看前面发生了什么。"))
                .put("order", new JSONObject().put("label_index", 0).put("page_no", 0).put("cmd_index", 1).put("sub_index", 0)))
            .put(new JSONObject()
                .put("type", "choice")
                .put("options", new JSONArray()
                    .put(new JSONObject().put("text", "走近看看").put("jump_label", "preview_next"))
                    .put(new JSONObject().put("text", "暂时离开").put("jump_label", "preview_leave")))
                .put("order", new JSONObject().put("label_index", 0).put("page_no", 0).put("cmd_index", 2).put("sub_index", 0)));
        return new JSONObject()
            .put("scene", SAMPLE_SCENE_NAME)
            .put("game_version", "style-preview")
            .put("raw_lang", "ja")
            .put("target_lang", "zh-cn")
            .put("character", character)
            .put("mentioned_characters", new JSONArray()
                .put(new JSONObject()
                    .put("name", "preview_mentioned")
                    .put("i18n", new JSONObject().put("zh_cn", "路人"))))
            .put("game_terms", new JSONArray()
                .put(new JSONObject()
                    .put("term", SAMPLE_TERM_NAME)
                    .put("description", "一所连接诸界的学校。")
                    .put("i18n", new JSONObject().put("zh_cn", "天穹学院"))))
            .put("scene_items", items)
            .put("seq_to_order", new JSONArray()
                .put(new JSONObject().put("order", new JSONObject().put("label_index", 0).put("page_no", 0).put("cmd_index", 0).put("sub_index", 0)).put("seq", 1))
                .put(new JSONObject().put("order", new JSONObject().put("label_index", 0).put("page_no", 0).put("cmd_index", 1).put("sub_index", 0)).put("seq", 2))
                .put(new JSONObject().put("order", new JSONObject().put("label_index", 0).put("page_no", 0).put("cmd_index", 2).put("sub_index", 0)).put("seq", 3)))
            .put("protect", new JSONArray()
                .put(new JSONObject()
                    .put("label", "<name>")
                    .put("origin", "星野明")
                    .put("order", 1)))
            .put("summary", new JSONObject().put("zh-cn", "角色在安静的街道上确认接下来的行动。"))
            .put("annotation", new JSONObject()
                .put("version", 1)
                .put("scene", SAMPLE_SCENE_NAME)
                .put("manual_summaries", new JSONObject()
                    .put("zh-cn", new JSONObject()
                        .put("text", "人工补充：保持角色的克制语气。")))
                .put("context_order", new JSONArray().put(SAMPLE_CONTEXT_ID)))
            .put("contexts", new JSONArray()
                .put(new JSONObject()
                    .put("id", SAMPLE_CONTEXT_ID)
                    .put("display_name", "样式核对上下文")
                    .put("scenes", new JSONArray())))
            .put("groups", new JSONArray()
                .put(new JSONObject()
                    .put("id", SAMPLE_GROUP_ID)
                    .put("display_name", "样式核对分组")
                    .put("contexts", new JSONArray()
                        .put(new JSONObject().put("context_id", SAMPLE_CONTEXT_ID)))));
    }

    private static JSONObject sampleContext() throws JSONException {
        return new JSONObject()
            .put("version", 1)
            .put("id", SAMPLE_CONTEXT_ID)
            .put("storage_name", "preview_context")
            .put("display_name", "样式核对上下文")
            .put("revision", 2)
            .put("created_at", 1_724_000_000_000L)
            .put("updated_at", 1_725_000_000_000L)
            .put("retention", new JSONObject()
                .put("inherit_defaults", false)
                .put("recent_percent", 40)
                .put("recent_limit", 12))
            .put("manual_descriptions", new JSONObject()
                .put("zh-cn", new JSONObject()
                    .put("text", "用于检查上下文详情中的描述和摘要区域。")
                    .put("updated_at", 1_725_000_000_000L)))
            .put("summary", new JSONObject()
                .put("zh-cn", new JSONObject()
                    .put("final", new JSONObject()
                        .put("text", "上下文包含一段示例剧情。")
                        .put("source_hash", "preview-source")
                        .put("updated_at", 1_725_000_000_000L))
                    .put("manual", new JSONObject()
                        .put("text", "人工补充：保留角色语气。")
                        .put("updated_at", 1_725_000_000_000L))))
            .put("scenes", new JSONArray()
                .put(new JSONObject()
                    .put("entry_id", "preview_scene_entry")
                    .put("scene", SAMPLE_SCENE_NAME)
                    .put("scene_file", SAMPLE_SCENE_NAME + ".json")
                    .put("created_at", 1_724_000_000_000L)
                    .put("updated_at", 1_725_000_000_000L)
                    .put("summaries", new JSONObject()
                        .put("zh-cn", new JSONObject()
                            .put("text", "街道上的短对话。")
                            .put("updated_at", 1_725_000_000_000L)))))
            .put("groups", new JSONArray()
                .put(new JSONObject()
                    .put("id", SAMPLE_GROUP_ID)
                    .put("display_name", "样式核对分组")));
    }

    private static JSONObject sampleGroup() throws JSONException {
        return new JSONObject()
            .put("version", 1)
            .put("id", SAMPLE_GROUP_ID)
            .put("storage_name", "preview_group")
            .put("display_name", "样式核对分组")
            .put("revision", 1)
            .put("created_at", 1_724_000_000_000L)
            .put("updated_at", 1_725_000_000_000L)
            .put("contexts", new JSONArray()
                .put(new JSONObject()
                    .put("context_entry_id", "preview_group_context_entry")
                    .put("context_id", SAMPLE_CONTEXT_ID)
                    .put("display_name", "样式核对上下文")))
            .put("scenes", new JSONArray()
                .put(new JSONObject().put("scene", SAMPLE_SCENE_NAME)))
            .put("summary", new JSONObject()
                .put("zh-cn", new JSONObject()
                    .put("current", new JSONObject()
                        .put("text", "分组中的示例上下文摘要。")
                        .put("source_hash", "preview-group-source")
                        .put("updated_at", 1_725_000_000_000L)
                        .put("cutoff", "preview_context"))));
    }

    private static JSONObject sampleCharacter() throws JSONException {
        return new JSONObject()
            .put("key", SAMPLE_CHARACTER_NAME)
            .put("en", "Hoshino Akira")
            .put("zh-tw", "星野明")
            .put("zh-cn", "星野明")
            .put("info", "Preview character record")
            .put("description", "谨慎而可靠的同行者。")
            .put("speech_style", "语气克制，偶尔会用简短的确认句。")
            .put("alias", new JSONArray()
                .put(new JSONObject()
                    .put("name", "明")
                    .put("en", "Akira")
                    .put("zh-tw", "明")
                    .put("zh-cn", "明")
                    .put("called", "星野"))
                .put(new JSONObject()
                    .put("name", "星野")
                    .put("en", "Hoshino")
                    .put("zh-tw", "星野")
                    .put("zh-cn", "星野")))
            .put("school", new JSONArray().put("天穹学院"))
            .put("guild", new JSONArray().put("调查社"))
            .put("origin_world", new JSONArray().put("东京"))
            .put("relationships", new JSONArray()
                .put(new JSONObject().put("target", "mc").put("type", "同行者")));
    }

    private static JSONObject sampleTerm() throws JSONException {
        return new JSONObject()
            .put("key", SAMPLE_TERM_NAME)
            .put("en", "Sky Academy")
            .put("zh-tw", "天穹學院")
            .put("zh-cn", "天穹学院")
            .put("description", "一所连接诸界的学校。");
    }

    private static JSONObject sampleImport() throws JSONException {
        JSONObject character = sampleCharacter().put("id", SAMPLE_CHARACTER_NAME);
        JSONObject term = sampleTerm().put("id", SAMPLE_TERM_NAME);
        JSONArray items = new JSONArray()
            .put(new JSONObject().put("kind", "scene").put("payload", sampleScene()))
            .put(new JSONObject().put("kind", "context").put("payload", sampleContext()))
            .put(new JSONObject().put("kind", "group").put("payload", sampleGroup()))
            .put(new JSONObject().put("kind", "character").put("payload", character))
            .put(new JSONObject().put("kind", "term").put("payload", term));
        JSONObject document = new JSONObject()
            .put("version", 1)
            .put("format", "het-management")
            .put("type", "bundle")
            .put("items", items);
        return new JSONObject()
            .put("version", 1)
            .put("documents", new JSONArray()
                .put(new JSONObject()
                    .put("source_name", "style-preview-management.json")
                    .put("root", document)))
            .put("snapshot", new JSONObject()
                .put("scenes", new JSONObject())
                .put("contexts", new JSONObject())
                .put("groups", new JSONObject())
                .put("characters", new JSONObject())
                .put("terms", new JSONObject())
                .put("active_jobs", new JSONObject()));
    }

    private static JSONObject sampleSceneSync() throws JSONException {
        return new JSONObject()
            .put("version", 1)
            .put("scenes", new JSONArray()
                .put(new JSONObject()
                    .put("name", SAMPLE_SCENE_NAME)
                    .put("languages", new JSONArray().put("zh-cn").put("en"))))
            .put("selected_scene", SAMPLE_SCENE_NAME)
            .put("selected_language", "zh-cn")
            .put("runtime", new JSONObject()
                .put("service_available", true)
                .put("game_port_available", true)
                .put("phase", "IDLE")
                .put("active_api_jobs", 1)
                .put("pending_conflict_count", 1)
                .put("last_action", "MANUAL_REFRESH")
                .put("last_outcome", "NEEDS_ATTENTION")
                .put("scene_summaries", new JSONArray()
                    .put(new JSONObject()
                        .put("scene", SAMPLE_SCENE_NAME)
                        .put("direction", "GAME_TO_HET")
                        .put("status", "NEEDS_ATTENTION"))))
            .put("last_result", "样例刷新完成；保留 1 个待处理冲突。")
            .put("read_only", true);
    }
}
