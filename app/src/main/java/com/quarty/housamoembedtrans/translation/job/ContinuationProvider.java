package com.quarty.housamoembedtrans.translation.job;

import android.content.Context;
import android.content.SharedPreferences;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.Base64;

import com.quarty.housamoembedtrans.continuation.ContinuationContent;
import com.quarty.housamoembedtrans.continuation.ContinuationWriting;
import com.quarty.housamoembedtrans.logging.Log;
import com.quarty.housamoembedtrans.provider.TranslationApiClient;
import com.quarty.housamoembedtrans.provider.TranslationConfig;
import com.quarty.housamoembedtrans.storage.config.ConfigStore;
import com.quarty.housamoembedtrans.translation.request.TranslationRequestFactory;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.KeyStore;
import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

/** Private, installation-bound provider configuration for an admitted continuation. */
public final class ContinuationProvider {
    private static final String KEY_ALIAS = "het.continuation.provider.v1";

    private ContinuationProvider() {}

    private static SharedPreferences profiles(Context context) {
        return context.getSharedPreferences("continuation_call_profiles", Context.MODE_PRIVATE);
    }

    /** Caller holds ROOT_ACCESS_LOCK across freezing and durable request admission. */
    public static void freeze(Context context, ConfigStore.TranslationConfigSnapshot snapshot,
                              String requestId, File jobRoot)
        throws Exception {
        JSONObject settings = snapshot.config.getJSONObject("UserSettings");
        JSONObject execution = new JSONObject()
            .put("TranslationApi", settings.getJSONObject("TranslationApi"))
            .put("Api", settings.optJSONObject("Api"))
            .put("EnableApiBodyLogging", settings.optBoolean("EnableApiBodyLogging", false))
            .put("DebugOmitThinkingParameters", settings.optBoolean("DebugOmitThinkingParameters", true));
        JSONObject secret = new JSONObject().put("settings", execution).put("key", snapshot.apiKey);
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.ENCRYPT_MODE, key(true));
        cipher.updateAAD(requestId.getBytes(StandardCharsets.UTF_8));
        JSONObject sealed = new JSONObject().put("iv", Base64.encodeToString(cipher.getIV(), Base64.NO_WRAP))
            .put("data", Base64.encodeToString(cipher.doFinal(secret.toString().getBytes(StandardCharsets.UTF_8)), Base64.NO_WRAP));
        SharedPreferences storage = profiles(context);
        SharedPreferences.Editor edit = storage.edit();
        // A process can die between saving this profile and publishing request.json.
        for (String previous : storage.getAll().keySet()) {
            if (!new File(jobRoot, previous).isDirectory()) edit.remove(previous);
        }
        if (!edit.putString(requestId, sealed.toString()).commit()) {
            throw new IOException("无法保存本次续写的模型配置");
        }
    }

    public static void release(Context context, String requestId) {
        try {
            SharedPreferences store = profiles(context);
            if (store.contains(requestId)) store.edit().remove(requestId).apply();
        } catch (RuntimeException error) {
            // Terminal persistence already succeeded; startup retries profile cleanup.
            Log.w("HET-Continuation", "Could not release completed call profile " + requestId, error);
        }
    }

    private static synchronized SecretKey key(boolean create) throws Exception {
        KeyStore store = KeyStore.getInstance("AndroidKeyStore");
        store.load(null);
        SecretKey existing = (SecretKey) store.getKey(KEY_ALIAS, null);
        if (existing != null) return existing;
        if (!create) throw new IllegalStateException("无法读取本次续写的模型配置密钥");
        KeyGenerator generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore");
        generator.init(new KeyGenParameterSpec.Builder(KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build());
        return generator.generateKey();
    }

    static TranslationConfig open(Context context, JSONObject request, String requestId) throws Exception {
        String value = profiles(context).getString(requestId, null);
        if (value == null) throw new IOException("找不到本次续写的模型配置");
        JSONObject sealed = new JSONObject(value);
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.DECRYPT_MODE, key(false),
            new GCMParameterSpec(128, Base64.decode(sealed.getString("iv"), Base64.NO_WRAP)));
        cipher.updateAAD(requestId.getBytes(StandardCharsets.UTF_8));
        byte[] bytes = cipher.doFinal(Base64.decode(sealed.getString("data"), Base64.NO_WRAP));
        JSONObject secret = new JSONObject(new String(bytes, StandardCharsets.UTF_8));
        return TranslationConfig.fromUserSettings(secret.getJSONObject("settings"), secret.getString("key"),
            request.getJSONArray("messages").getJSONObject(0).getString("content"));
    }

    static String call(TranslationConfig config, JSONObject request, TranslationApiClient.AttemptHandle handle)
        throws Exception {
        JSONArray messages = request.getJSONArray("messages");
        return callRound(config, messages.getJSONObject(0).getString("content"),
            messages.getJSONObject(1).getString("content"), handle);
    }

    static String writeProse(TranslationConfig config, JSONObject request, JSONObject pipeline,
                             String plan, TranslationApiClient.AttemptHandle handle) throws Exception {
        String input = ContinuationWriting.writingInput(plan, pipeline.getString("following"),
            request.getString("input"), request.getJSONObject("generation").getInt("dialogue_target"));
        String prose = callRound(config, pipeline.getString("writing_system"), input, handle);
        if (!ContinuationContent.hasText(prose)) {
            throw new ContinuationContent.OutputError("invalid_output", "续写第二轮没有返回短文正文");
        }
        return prose;
    }

    private static String callRound(TranslationConfig config, String system, String input,
                                    TranslationApiClient.AttemptHandle handle) throws Exception {
        if (handle.isCanceled() || Thread.currentThread().isInterrupted()) {
            throw new InterruptedException("续写调用已停止");
        }
        JSONObject body = TranslationRequestFactory.buildMessageBody(config, system, input);
        StringBuilder response = new StringBuilder();
        boolean[] completed = {false};
        TranslationApiClient.streamTranslationAttempt(config, body.toString(), new TranslationApiClient.StreamListener() {
            @Override public boolean requiresStreamTerminator() { return true; }
            @Override public void onAttemptStarted(int attemptNumber) {}
            @Override public void onTextDelta(String delta) throws Exception {
                if ((long) response.length() + delta.length() > TranslationJobStore.MAX_RESULT_BYTES) {
                    throw new ContinuationContent.OutputError("invalid_output", "续写结果超过持久任务的大小限制");
                }
                response.append(delta);
            }
            @Override public void onStreamCompleted(String stopReason) { completed[0] = true; }
        }, 1, handle);
        if (!completed[0]) throw new TranslationApiClient.IncompleteResponseException("续写响应提前结束");
        return response.toString();
    }
}
