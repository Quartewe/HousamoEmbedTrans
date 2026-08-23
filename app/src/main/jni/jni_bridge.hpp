#pragma once

#include <jni.h>

struct JavaBridge {
    JavaVM* jvm = nullptr;
    jclass main_hook_class = nullptr;

    jmethodID resolve_request_id_method = nullptr;
    jmethodID submit_translation_method = nullptr;
    jmethodID report_scene_rejected_method = nullptr;
};

extern JavaBridge g_java_bridge;

bool InitJniBridge(JNIEnv* env, jclass main_hook_class);
