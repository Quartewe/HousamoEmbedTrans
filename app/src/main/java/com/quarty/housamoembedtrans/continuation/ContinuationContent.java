package com.quarty.housamoembedtrans.continuation;

import android.content.Context;
import com.quarty.housamoembedtrans.scene.store.SceneStore;
import com.quarty.housamoembedtrans.storage.config.ConfigStore;
import com.quarty.housamoembedtrans.storage.config.StoryContinuationSettings;
import com.quarty.housamoembedtrans.storage.json.AtomicJsonFileIo;
import com.quarty.housamoembedtrans.translation.request.SceneTranslationRequestBuilder;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import com.quarty.housamoembedtrans.util.JobValidator;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.Iterator;
import java.util.UUID;

/** Business payload and local commit of one story continuation. The service owns task state. */
public final class ContinuationContent {
    private ContinuationContent() {}
    private static final AtomicJsonFileIo IO = AtomicJsonFileIo.android();
    private static final Object RECORD_WRITES = new Object();

    public static final class OutputError extends Exception {
        public final String code;
        public OutputError(String code, String message) { super(message); this.code = code; }
    }

    public static String fingerprint(JSONObject scene) throws Exception {
        // Strip mutable language data from the nested story tree, preserving branch and option order.
        JSONArray items = new JSONArray(scene.getJSONArray("scene_items").toString());
        stripTranslations(items);
        JSONObject source = new JSONObject().put("scene_items", items)
            .put("protect", scene.getJSONArray("protect"));
        byte[] digest = MessageDigest.getInstance("SHA-256").digest(
            SceneTranslationRequestBuilder.toCanonicalBytes(source));
        StringBuilder hex = new StringBuilder(64);
        for (byte b : digest) {
            hex.append(Character.forDigit((b & 255) >>> 4, 16));
            hex.append(Character.forDigit(b & 15, 16));
        }
        return hex.toString();
    }

    private static void stripTranslations(Object value) throws JSONException {
        if (value instanceof JSONObject) {
            JSONObject object = (JSONObject)value;
            object.remove("translations");
            Iterator<String> keys = object.keys();
            while (keys.hasNext()) stripTranslations(object.get(keys.next()));
        } else if (value instanceof JSONArray) {
            JSONArray array = (JSONArray)value;
            for (int i = 0; i < array.length(); ++i) stripTranslations(array.get(i));
        }
    }

    private static StoryPrompt latestScene;

    private static StoryPrompt snapshot(Context context, JSONObject node) throws Exception {
        byte[] bytes = new SceneStore(context).readRawSceneSnapshot(node.getString("scene")).bytes;
        synchronized (ContinuationContent.class) {
            if (latestScene == null || !Arrays.equals(latestScene.bytes, bytes)) {
                latestScene = new StoryPrompt(bytes);
            }
            return latestScene;
        }
    }

    public static boolean matchesCurrentSource(Context c, JSONObject node) throws Exception {
        try {
            return snapshot(c,node).hash.equals(node.getString("source_hash"));
        } catch (SceneStore.RawSceneFailure e) {
            if (e.getCause() instanceof java.io.FileNotFoundException) return false;
            throw e;
        }
    }

    private static StoryPrompt current(Context c, JSONObject node) throws Exception {
        StoryPrompt snapshot = snapshot(c,node);
        if (!snapshot.hash.equals(node.getString("source_hash")))
            throw new IOException("当前原剧本与 HET 中的 Scene 不一致");
        return snapshot;
    }

    public static void prepareNode(Context context, JSONObject node) throws Exception {
        StoryPrompt snapshot = current(context, node);
        if (!node.has("route") || !node.has("screen")) return;

        JSONObject config = new ConfigStore(context).load().config;
        StoryContinuationSettings settings = StoryContinuationSettings.read(config);
        if (settings.enabled) {
            String language = config.getJSONObject("UserSettings").getString("TargetLanguage");
            snapshot.local(context, node, language, settings.allowCharacter, settings.allowBackground);
        }
    }

    private static File file(Context c, String scene) {
        return new File(new File(c.getFilesDir(), "continuations"), SceneStore.fileNameForScene(scene));
    }

    private static JSONObject document(Context c, String scene) throws Exception {
        File target = file(c, scene);
        if (!IO.exists(target)) return new JSONObject().put("scene",scene).put("records",new JSONArray());
        JSONObject doc = new JSONObject(new String(IO.read(target),StandardCharsets.UTF_8));
        if (!scene.equals(doc.getString("scene"))) throw new IOException("续写记录所属 Scene 不符");
        doc.getJSONArray("records");
        return doc;
    }

    public static JSONArray all(Context c, String scene) throws Exception {
        synchronized (RECORD_WRITES) { return document(c,scene).getJSONArray("records"); }
    }

    static boolean same(JSONObject a, JSONObject b) throws Exception {
        return Arrays.equals(SceneTranslationRequestBuilder.toCanonicalBytes(a),
            SceneTranslationRequestBuilder.toCanonicalBytes(b));
    }

    static JSONObject byId(JSONArray records,String id) throws JSONException {
        for (int i=0;i<records.length();i++) {
            JSONObject r=records.getJSONObject(i);
            if (id.equals(r.getString("id"))) return r;
        }
        return null;
    }

    static JSONObject project(JSONObject obj,String... keys) throws JSONException {
        JSONObject copy = new JSONObject();
        for (String key : keys) if (obj.has(key)) copy.put(key,obj.get(key));
        return copy;
    }

    public static JSONObject prepare(Context context, JSONObject submission, JSONObject generation,
            String targetLanguage) throws Exception {
        StoryPrompt snapshot = current(context, submission);
        String input = submission.getString("input");
        if (!hasText(input)) throw new IOException("请输入主角原话");

        StoryPrompt.Local local = snapshot.local(context, submission, targetLanguage,
            generation.getBoolean("allow_character"), generation.getBoolean("allow_background"));
        JSONObject resources = local.resources();
        return project(submission, "scene", "source", "source_hash", "input")
            .put("generation", generation)
            .put("created_at", System.currentTimeMillis())
            .put("record_id", UUID.randomUUID().toString())
            .put("target_lang", targetLanguage)
            .put("resources", resources)
            .put("messages", snapshot.messages(local, input, generation, resources));
    }

    public static boolean hasText(String s) {
        return s.codePoints().anyMatch(cp -> !Character.isWhitespace(cp) && !Character.isSpaceChar(cp));
    }

    public static JSONObject finish(JSONObject prepared,String modelJson) throws Exception {
        try {
            JSONObject out = JobValidator.parseJsonObject(modelJson,"continuation");
            if ("error".equals(out.getString("status"))) {
                StorySteps.keys(out,"status","error");
                JSONObject error = out.getJSONObject("error");
                StorySteps.keys(error,"code","message");
                String code = error.getString("code");
                if (!Arrays.asList("refused","incomplete","request_failed","invalid_output").contains(code))
                    throw new JSONException("未知错误码");
                Object message = error.get("message");
                if (!(message instanceof String) || !hasText((String) message))
                    throw new JSONException("错误结果缺少原因");
                throw new OutputError(code,(String) message);
            }
            if (!"ok".equals(out.getString("status"))) throw new JSONException("未知续写状态");
            StorySteps.keys(out,"status","steps");
            JSONArray steps = out.getJSONArray("steps");
            StorySteps.validate(steps,prepared.getJSONObject("generation"),prepared.getJSONObject("resources"));
            JSONObject record = project(prepared,"source","source_hash","input","created_at","generation")
                .put("id",prepared.getString("record_id")).put("steps",steps);
            return new JSONObject().put("scene",prepared.getString("scene")).put("record",record);
        } catch (JSONException | JobValidator.ValidationException e) { throw new OutputError("invalid_output",e.getMessage()); }
    }

    public static void validateStored(JSONObject prepared,JSONObject result) throws Exception {
        JSONObject record = result.getJSONObject("record");
        JSONObject rebuilt = finish(prepared,new JSONObject().put("status","ok")
            .put("steps",record.getJSONArray("steps")).toString());
        if (!same(rebuilt.getJSONObject("record"),record) || !rebuilt.getString("scene").equals(result.getString("scene")))
            throw new IOException("持久续写结果与请求不符");
    }

    public static void commit(Context c,JSONObject result) throws Exception {
        String scene = result.getString("scene");
        JSONObject record = result.getJSONObject("record");
        synchronized (RECORD_WRITES) {
            JSONObject doc = document(c,scene);
            JSONArray rows = doc.getJSONArray("records");
            JSONObject existing = byId(rows,record.getString("id"));
            if (existing != null) {
                if (!same(existing,record)) throw new IOException("续写记录 ID 冲突");
                return;
            }
            rows.put(record);
            IO.write(file(c,scene),doc.toString().getBytes(StandardCharsets.UTF_8));
        }
    }
}
