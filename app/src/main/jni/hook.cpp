#include <cstdint>
#include "housamo.hpp"
#include "shadowhook.h"

using RawFuncPtr = void (*)(void* self, void* pageData, void* method);

// 原函数指针
static RawFuncPtr RawInitBase = nullptr; // 所有内容
static RawFuncPtr RawInitText = nullptr; // Text专用

// ShadowHook stub 指针（用于 unhook）
static void* StubInitBase = nullptr;
static void* StubInitText = nullptr;

static void HookInitBase(void* self, void* pageData, void* method) {
    if (pageData == nullptr) {
        LOGE("[InitBase] pageData is nullptr!"); 
        if (RawInitBase) {
            RawInitBase(self, pageData, method);
        }
        return;
    };
    CommandExamine(pageData, "base");
    if (RawInitBase) {
        // 调用原函数
        RawInitBase(self, pageData, method);
    }
    return;
}

static void HookInitText(void* self, void* pageData, void* method) {
    if (pageData == nullptr) {
        LOGE("[InitText] pageData is nullptr!"); 
        if (RawInitText) {
            RawInitText(self, pageData, method);
            return;
        }
    };
    CommandExamine(pageData, "text");
    if (RawInitText) {
        // 调用原函数
        RawInitText(self, pageData, method);
        return;
    }
}

bool install_hook(uintptr_t il2cpp_base, RvaConfig config) {
    // 计算目标函数地址
    void* targetBase = (void*)(il2cpp_base + config.init_base);
    void* targetText = (void*)(il2cpp_base + config.init_text);

    // 使用 ShadowHook 安装钩子
    StubInitBase = shadowhook_hook_func_addr(
        targetBase,
        (void*)HookInitBase,
        (void**)&RawInitBase
    );
    if (StubInitBase == nullptr) {
        int err = shadowhook_get_errno();
        LOGE("shadowhook InitBase failed: %d %s", err, shadowhook_to_errmsg(err));
        return false;
    }
    LOGI("shadowhook InitBase success stub=%p", StubInitBase);

    StubInitText = shadowhook_hook_func_addr(
        targetText,
        (void*)HookInitText,
        (void**)&RawInitText
    );
    if (StubInitText == nullptr) {
        int err = shadowhook_get_errno();
        LOGE("shadowhook InitText failed: %d %s", err, shadowhook_to_errmsg(err));
        return false;
    }
    LOGI("shadowhook InitText success stub=%p", StubInitText);

    return true;
}

