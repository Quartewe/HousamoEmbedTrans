#include "bootstrap_contract.h"

#include <jni.h>
#include <android/log.h>
#include <pthread.h>
#include <cstdio>
#include <unistd.h>

extern "C" {
__attribute__((visibility("default"))) HetRootRequest het_root_request{};
__attribute__((visibility("default"))) int het_root_state = HET_ROOT_IDLE;
__attribute__((visibility("default"))) char het_root_error[256]{};
}

namespace {
void fail(const char* stage) {
    std::snprintf(het_root_error, sizeof(het_root_error), "%s", stage);
    __android_log_print(ANDROID_LOG_ERROR, "HET.Root", "%s", stage);
    __atomic_store_n(&het_root_state, HET_ROOT_FAILED, __ATOMIC_RELEASE);
}

bool check(JNIEnv* env, const void* value, const char* stage) {
    if (!env->ExceptionCheck() && value) return true;
    if (env->ExceptionCheck()) {
        env->ExceptionDescribe();
        env->ExceptionClear();
    }
    fail(stage);
    return false;
}

void loadJava(JNIEnv* env) {
    // JNI from this attached worker has no application Java caller. Resolve the
    // platform Application here; RootEntry only uses public Android APIs.
    jclass activityThread = env->FindClass("android/app/ActivityThread");
    if (!check(env, activityThread, "Find ActivityThread")) return;
    jmethodID current = env->GetStaticMethodID(activityThread, "currentApplication",
                                              "()Landroid/app/Application;");
    if (!check(env, current, "Find currentApplication")) return;
    jobject application = nullptr;
    for (int attempt = 0; attempt < 100; ++attempt) {
        application = env->CallStaticObjectMethod(activityThread, current);
        if (env->ExceptionCheck()) {
            check(env, nullptr, "Read current Application");
            return;
        }
        if (application) break;
        usleep(100000);
    }
    if (!check(env, application, "Application not ready within 10 seconds")) return;

    jclass loaders = env->FindClass("java/lang/ClassLoader");
    if (!check(env, loaders, "Find ClassLoader")) return;
    jmethodID systemLoader = env->GetStaticMethodID(loaders, "getSystemClassLoader",
                                                   "()Ljava/lang/ClassLoader;");
    if (!check(env, systemLoader, "Find system ClassLoader")) return;
    jobject parent = env->CallStaticObjectMethod(loaders, systemLoader);
    if (!check(env, parent, "Get system ClassLoader")) return;
    jclass paths = env->FindClass("dalvik/system/PathClassLoader");
    if (!check(env, paths, "Find PathClassLoader")) return;
    jmethodID constructor = env->GetMethodID(paths, "<init>",
        "(Ljava/lang/String;Ljava/lang/String;Ljava/lang/ClassLoader;)V");
    if (!check(env, constructor, "Find PathClassLoader constructor")) return;
    jstring apk = env->NewStringUTF(het_root_request.apk_path);
    if (!check(env, apk, "Create APK path")) return;
    jstring libraries = env->NewStringUTF(het_root_request.library_path);
    if (!check(env, libraries, "Create native library path")) return;
    jobject loader = env->NewObject(paths, constructor, apk, libraries, parent);
    if (!check(env, loader, "Create HET ClassLoader")) return;
    jmethodID loadClass = env->GetMethodID(loaders, "loadClass",
                                         "(Ljava/lang/String;)Ljava/lang/Class;");
    if (!check(env, loadClass, "Find loadClass")) return;
    jstring name = env->NewStringUTF("com.quarty.housamoembedtrans.RootEntry");
    if (!check(env, name, "Create entry name")) return;
    auto entry = static_cast<jclass>(env->CallObjectMethod(loader, loadClass, name));
    if (!check(env, entry, "Load HET RootEntry")) return;
    jmethodID start = env->GetStaticMethodID(entry, "start",
        "(Landroid/app/Application;Ljava/lang/String;)I");
    if (!check(env, start, "Find RootEntry.start")) return;
    int state = env->CallStaticIntMethod(entry, start, application, apk);
    if (env->ExceptionCheck()) {
        check(env, nullptr, "HET Java startup failed; inspect game log and restart");
        return;
    }
    __atomic_store_n(&het_root_state, state, __ATOMIC_RELEASE);
}

void* worker(void* value) {
    auto* vm = static_cast<JavaVM*>(value);
    JNIEnv* env = nullptr;
    if (vm->AttachCurrentThread(&env, nullptr) != JNI_OK) {
        fail("Attach bootstrap worker to JavaVM");
        return nullptr;
    }
    if (env->PushLocalFrame(32) < 0) {
        check(env, nullptr, "Allocate JNI local frame");
    } else {
        loadJava(env);
        env->PopLocalFrame(nullptr);
    }
    vm->DetachCurrentThread();
    return nullptr;
}
}

extern "C" JNIEXPORT jint JNICALL JNI_OnLoad(JavaVM* vm, void*) {
    int expected = HET_ROOT_IDLE;
    if (!__atomic_compare_exchange_n(&het_root_state, &expected, HET_ROOT_STARTING,
                                    false, __ATOMIC_ACQ_REL, __ATOMIC_ACQUIRE)) {
        return JNI_VERSION_1_6;
    }
    pthread_t thread;
    int error = pthread_create(&thread, nullptr, worker, vm);
    if (error != 0) {
        fail("Create bootstrap worker");
        return JNI_ERR;
    }
    pthread_detach(thread);
    return JNI_VERSION_1_6;
}
