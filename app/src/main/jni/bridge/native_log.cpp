#include "native_log.hpp"
#include <android/log.h>
#include <atomic>
#include <cstdarg>
#include <cstdio>
#include <cstdlib>

namespace {
std::atomic<JavaVM*> log_vm{nullptr};
jclass log_class = nullptr;
jmethodID persist_method = nullptr;
}

void InitializeNativeLog(JavaVM* vm) {
    JNIEnv* env = nullptr;
    if (vm->GetEnv(reinterpret_cast<void**>(&env), JNI_VERSION_1_6) != JNI_OK) return;
    jclass local = env->FindClass("com/quarty/housamoembedtrans/logging/Log");
    if (local) {
        persist_method = env->GetStaticMethodID(local, "persistNative", "(I[B)V");
        if (persist_method) log_class = static_cast<jclass>(env->NewGlobalRef(local));
        env->DeleteLocalRef(local);
    }
    if (env->ExceptionCheck()) env->ExceptionClear();
    if (log_class && persist_method) {
        // Immutable global reference lives as long as the native workers/process.
        log_vm.store(vm, std::memory_order_release);
    } else {
        __android_log_print(ANDROID_LOG_ERROR, "HousamoTrans", "Native file logging initialization failed");
    }
}

int NativeLogPrint(int priority, const char* tag, const char* format, ...) {
    va_list args, sizing;
    va_start(args, format);
    va_copy(sizing, args);
    int length = vsnprintf(nullptr, 0, format, sizing);
    va_end(sizing);
    char* text = length >= 0 ? static_cast<char*>(malloc(static_cast<size_t>(length) + 1)) : nullptr;
    if (!text) {
        int result = __android_log_vprint(priority, tag, format, args);
        va_end(args);
        return result;
    }
    vsnprintf(text, static_cast<size_t>(length) + 1, format, args);
    va_end(args);
    int result = __android_log_write(priority, tag, text);
    JavaVM* vm = log_vm.load(std::memory_order_acquire);
    JNIEnv* env = nullptr;
    bool attached = false;
    if (vm) {
        jint status = vm->GetEnv(reinterpret_cast<void**>(&env), JNI_VERSION_1_6);
        if (status == JNI_EDETACHED) {
            attached = vm->AttachCurrentThread(&env, nullptr) == JNI_OK;
            if (!attached) env = nullptr;
        } else if (status != JNI_OK) {
            env = nullptr;
        }
    }
    if (env) {
        // Error-path logs must not consume or replace the caller's exception.
        jthrowable pending = env->ExceptionOccurred();
        if (pending) env->ExceptionClear();
        jbyteArray bytes = env->NewByteArray(length);
        if (bytes) {
            env->SetByteArrayRegion(bytes, 0, length, reinterpret_cast<const jbyte*>(text));
            if (!env->ExceptionCheck()) env->CallStaticVoidMethod(log_class, persist_method, priority, bytes);
            env->DeleteLocalRef(bytes);
        }
        if (env->ExceptionCheck()) {
            env->ExceptionClear();
            __android_log_write(ANDROID_LOG_ERROR, tag, "Could not enqueue native file log");
        }
        if (pending) {
            env->Throw(pending);
            env->DeleteLocalRef(pending);
        }
    }
    if (attached) vm->DetachCurrentThread();
    free(text);
    return result;
}
