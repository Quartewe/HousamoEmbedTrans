#include "native_translation_pipeline.hpp"
#include "housamo.hpp"

#include <jni.h>

#include <atomic>
#include <cstddef>
#include <string>

namespace {

constexpr size_t kMaxCallbackPayloadBytes = 32U * 1024U * 1024U;
constexpr size_t kMaxRequestIdBytes = 512U;
constexpr size_t kMaxErrorFieldBytes = 4096U;

bool ClearException(JNIEnv* env, const char* operation) {
    if (!env || !env->ExceptionCheck()) {
        return false;
    }
    LOGE("[TranslationCallback] JNI exception during %s", operation);
    env->ExceptionClear();
    return true;
}

bool ReadString(JNIEnv* env, jstring value, size_t max_bytes, std::string* output) {
    if (!env || !value || !output) {
        return false;
    }
    const jsize size = env->GetStringUTFLength(value);
    if (ClearException(env, "GetStringUTFLength") || size <= 0 || static_cast<size_t>(size) > max_bytes) {
        return false;
    }
    const char* chars = env->GetStringUTFChars(value, nullptr);
    if (!chars) {
        ClearException(env, "GetStringUTFChars");
        return false;
    }
    output->assign(chars, static_cast<size_t>(size));
    env->ReleaseStringUTFChars(value, chars);
    ClearException(env, "ReleaseStringUTFChars");
    return true;
}

bool ReadByteArray(JNIEnv* env, jbyteArray value, std::string* output) {
    if (!env || !value || !output) {
        return false;
    }
    const jsize size = env->GetArrayLength(value);
    if (ClearException(env, "GetArrayLength") || size <= 0 || static_cast<size_t>(size) > kMaxCallbackPayloadBytes) {
        return false;
    }
    output->resize(static_cast<size_t>(size));
    env->GetByteArrayRegion(value, 0, size, reinterpret_cast<jbyte*>(output->data()));
    if (ClearException(env, "GetByteArrayRegion")) {
        output->clear();
        return false;
    }
    return true;
}

}  // namespace

extern "C" JNIEXPORT jboolean JNICALL
Java_com_quarty_housamoembedtrans_MainHook_nativeApplySceneResult(JNIEnv* env, jclass, jstring request_id, jstring scene_name, jstring target_lang, jbyteArray result_json) {
    std::string request;
    if (!ReadString(env, request_id, kMaxRequestIdBytes, &request)) {
        LOGE("[TranslationCallback] final result has invalid requestId");
        return JNI_FALSE;
    }
    std::string scene;
    if (!ReadString(env, scene_name, kMaxRequestIdBytes, &scene)) {
        LOGE("[TranslationCallback] final result has invalid Scene name requestId=%s", request.c_str());
        return JNI_FALSE;
    }
    std::string target;
    if (!ReadString(env, target_lang, kMaxRequestIdBytes, &target)) {
        LOGE("[TranslationCallback] final result has invalid target language requestId=%s", request.c_str());
        return JNI_FALSE;
    }
    std::string result;
    if (!ReadByteArray(env, result_json, &result)) {
        LOGE("[TranslationCallback] final result payload is missing, invalid, or exceeds 32 MiB requestId=%s", request.c_str());
        return JNI_FALSE;
    }
    return HandleCompletedTranslation(request, scene, target, result)
        ? JNI_TRUE
        : JNI_FALSE;
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_quarty_housamoembedtrans_MainHook_nativeOnTranslationFailed(JNIEnv* env, jclass, jstring request_id, jstring error_type, jstring message) {
    std::string request;
    if (!ReadString(env, request_id, kMaxRequestIdBytes, &request)) {
        LOGE("[TranslationCallback] failure has invalid requestId");
        return JNI_FALSE;
    }
    std::string type;
    if (!ReadString(env, error_type, kMaxErrorFieldBytes, &type)) {
        type = "unknown";
    }
    std::string detail;
    if (!ReadString(env, message, kMaxErrorFieldBytes, &detail)) {
        detail = "missing failure message";
    }
    return HandleTranslationFailure(request, type, detail)
        ? JNI_TRUE
        : JNI_FALSE;
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_quarty_housamoembedtrans_MainHook_nativeAcknowledgeTranslationTerminal(JNIEnv* env, jclass, jstring request_id, jstring terminal_kind) {
    std::string request;
    std::string kind;
    if (!ReadString(env, request_id, kMaxRequestIdBytes, &request)
        || !ReadString(env, terminal_kind, kMaxErrorFieldBytes, &kind)) {
        return JNI_FALSE;
    }
    return AcknowledgeTranslationTerminal(request, kind)
        ? JNI_TRUE
        : JNI_FALSE;
}

extern "C" JNIEXPORT void JNICALL
Java_com_quarty_housamoembedtrans_MainHook_nativeApplyQuestPatch(JNIEnv* env, jclass, jstring request_id, jbyteArray) {
    static std::atomic<bool> ignored_logged{false};
    std::string request;
    if (!ReadString(env, request_id, kMaxRequestIdBytes, &request)) {
        LOGE("[TranslationCallback] ignored Quest patch with invalid requestId");
        return;
    }
    if (!ignored_logged.exchange(true, std::memory_order_acq_rel)) {
        LOGI("[TranslationCallback] Quest patch callback is ignored; only final Scene result is persisted requestId=%s", request.c_str());
    }
}
