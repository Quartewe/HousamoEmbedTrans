#include <cstdint>
#include "housamo.hpp"
#include "shadowhook.h"

using RawFuncPtr = void (*)(void* self, void* pageData);

// 原函数指针
static RawFuncPtr RawInitBase = nullptr; // 所有内容
static RawFuncPtr RawInitText = nullptr; // Text专用

// ShadowHook stub 指针（用于 unhook）
static void* StubInitBase = nullptr;
static void* StubInitText = nullptr;

static void HookInitBase(void* self, void* pageData) {
    if (pageData == nullptr) {
        LOGE("pageData is nullptr!"); 
        if (RawInitBase) {
            RawInitBase(self, pageData);
            return;
        }
    };

    // pageData结构体解析
    // void* scenario_label_data =read_ptr(pageData, 0x20);
    int page_no = read_int(pageData, 0x38);
    void* command_list = read_ptr(pageData, 0x10);

    // CommandList解析
    int list_size = read_int(command_list, 0x18);
    void* cmd_array = read_ptr(command_list, 0x10);

    LOGI("[InitBase] ---- Page No: %d ---- \n[InitBase] - Command List Size: %d", page_no, list_size);
    for (int i = 0; i < list_size; i++) {
        void* cmd_item = read_ptr(cmd_array,0x20 + i * 0x8);
        void* cmd_type = read_ptr(cmd_item, 0x20);
        std::string type_str = read_il2cpp_string(cmd_type);
        LOGI("[InitBase] Command %d: Type = %s", i, type_str.c_str());
    }

    if (RawInitBase) {
        // 调用原函数
        RawInitBase(self, pageData);
        return;
    }
}

// static void HookInitText(void* self, void* pageData) {
//     if (pageData == nullptr) {
//         LOGE("pageData is nullptr!"); 
//         if (RawInitText) {
//             RawInitText(self, pageData);
//             return;
//         }
//     };

//     if (RawInitText) {
//         // 调用原函数
//         RawInitText(self, pageData);
//         return;
//     }
// }

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

    // StubInitText = shadowhook_hook_func_addr(
    //     targetText,
    //     (void*)HookInitText,
    //     (void**)&RawInitText
    // );

    return true;
}

