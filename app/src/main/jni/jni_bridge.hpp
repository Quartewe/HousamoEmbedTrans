#pragma once

#include <jni.h>

struct JavaBridge {
    JavaVM* jvm = nullptr;
    jclass main_hook_class = nullptr;
    jmethodID request_api_method = nullptr;
    jmethodID store_scene_method = nullptr;
};

extern JavaBridge g_java_bridge;

bool InitJniBridge(JNIEnv* env, jclass main_hook_class);
