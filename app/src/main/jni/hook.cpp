#include <cstdint>
#include "housamo.hpp"
#include "dobby.h"

#define Offset_InitBase 0x211FCD8
#define Offset_InitText 0x2128B8C

using RawFuncPtr = void (*)(void* self, void* pageData);

// 原函数指针
static RawFuncPtr RawInitBase = nullptr; // 所有内容
static RawFuncPtr RawInitText = nullptr; // Text专用

static void HookInitBase(void* self, void* pageData) {

    if (pageData == nullptr) {
        LOGE("pageData is nullptr!"); 
        if (RawInitBase) {
            RawInitBase(self, pageData);
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
    }
}

bool install_hook(uintptr_t il2cpp_base) {
    // 计算目标函数地址
    void* targetBase = (void*)(il2cpp_base + Offset_InitBase);
    void* targetText = (void*)(il2cpp_base + Offset_InitText);

    // 安装钩子
    if (DobbyHook(targetBase, (void*)HookInitBase, (void**)&RawInitBase) != 0) {
        LOGE("Failed to hook InitBase");
        return false;
    }
    // DobbyHook(targetText, (void*)HookInitText, (void**)&RawInitText);

    return true;
}

