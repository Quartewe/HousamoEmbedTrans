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
#define RVA_InitBase         0x211FCD8  // AdvCommand.InitFromPageData 基类
#define RVA_InitText         0x2128B8C  // AdvCommandText.InitFromPageData 覆盖版
#define RVA_PageTextChange   0x216CE80  // AdvMessageWindow.PageTextChange
#define RVA_AddSelection     0x216F7B8  // AdvSelectionManager.AddSelection
#define RVA_ShowSelection    0x216FBE0  // AdvSelectionManager.Show

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
