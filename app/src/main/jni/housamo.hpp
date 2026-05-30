#pragma once

#include <string>
#include <vector>
#include <unordered_map>
#include <variant>
#include <android/log.h>
#include <cstdint>
#include <cstddef>
// #include <nlohmann/json.hpp>

// using json = nlohmann::json;

// 日志宏
#define LOG_TAG "HousamoTrans"
#define LOGD(...) __android_log_print(ANDROID_LOG_DEBUG, LOG_TAG, __VA_ARGS__)
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO,  LOG_TAG, __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN,  LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

//函数声明
bool install_hook(uintptr_t il2cpp_base);
bool valid_ptr(void* ptr);
void* read_ptr(void* base, size_t offset);
int read_int(void* base, size_t offset);
std::string read_il2cpp_string(void* strptr);

// 预处理文本结构体
struct CharacterInfo {
    std::string name;
    std::string description;
};

struct TextItem {
    int page_no;
    std::string speaker;
    std::string type;
    std::vector<std::string> text;
    // 所有文本对象
};

struct JumpData{
    TextItem option;
    TextItem following_text;
    // 选项和选项对应后续的文本
};

using SceneItem = std::variant<TextItem, JumpData>;

struct Scene {
    std::string scene;
    std::string raw_lang = "ja";
    std::string target_lang;
    std::vector<std::string> character;
    std::unordered_map<std::string, CharacterInfo> character_info;
    std::vector<SceneItem> scene_items;
};