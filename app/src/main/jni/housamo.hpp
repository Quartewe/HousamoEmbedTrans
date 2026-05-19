#pragma once
#include <cstdint>
#include <string>
#include <dlfcn.h>
#include <unistd.h>
#include <cstdio>
#include <cstring>
#include <android/log.h>

#define TAG "HousamoTrans"
#define LOGD(...) __android_log_print(ANDROID_LOG_DEBUG, TAG, __VA_ARGS__)
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO,  TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

// ═══════════ Il2CppDumper 导出 RVA (arm64-v8a) ═══════════
#define RVA_PAGE_TEXT_CHANGE 0x216CE80
#define RVA_DO_COMMAND       0x2128D00
#define RVA_ADD_SELECTION    0x216F7B8
#define RVA_SELECTION_SHOW   0x216FBE0

// ═══════════ IL2CPP API 类型 ═══════════
struct Il2CppObject { void* klass; void* monitor; };
struct Il2CppString : Il2CppObject { int32_t length; uint16_t chars[1]; };

typedef Il2CppString* (*il2cpp_string_new_t)(const char* str);

// ═══════════ 全局状态 ═══════════
extern uintptr_t g_il2cpp_base;
extern il2cpp_string_new_t g_il2cpp_string_new;

// ═══════════ 函数声明 ═══════════
void housamo_init();
void il2cpp_hooks_install();
void transl_worker_start();
void transl_worker_stop();
