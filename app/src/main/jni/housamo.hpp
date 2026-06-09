#pragma once

#include <string>
#include <vector>
#include <unordered_map>
#include <variant>
#include <android/log.h>
#include <cstdint>
#include <cstddef>
#include <atomic>

// 日志宏
#define LOG_TAG "HousamoTrans"
#define LOGD(...) __android_log_print(ANDROID_LOG_DEBUG, LOG_TAG, __VA_ARGS__)
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO,  LOG_TAG, __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN,  LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

// 全局变量和结构体定义
extern std::atomic<bool> stop_catch;

// 子结构体
struct Il2CppStringLayoutConfig {
    size_t length = 0;
    size_t chars = 0;
};

struct Il2CppArrayLayoutConfig {
    size_t length = 0;
    size_t first_element = 0;
    int pointer_size = 0;
};

struct Il2CppListLayoutConfig {
    size_t items = 0;
    size_t size = 0;
};

struct AdvScenarioPageDataLayoutConfig {
    size_t command_list = 0;
    size_t text_data_list = 0;
    size_t scenario_label_data = 0;
    size_t page_no = 0;
    size_t message_window_name = 0;
};

struct ScenarioLabelDataLayoutConfig {
    size_t page_data_list = 0;
    size_t scenario_label = 0;
    size_t next = 0;
    size_t command_list = 0;
    size_t scenario_label_command = 0;
};

struct AdvCommandLayoutConfig {
    size_t row_data = 0;
    size_t type = 0;
};

struct StringGridRowLayoutConfig {
    size_t row_index = 0;
    size_t strings = 0;
};

struct AdvCommandCharacterLayoutConfig {
    size_t character_info = 0;
    size_t name_text = 0;
};

struct AdvCommandSelectionLayoutConfig {
    size_t jump_label = 0;
};

struct AdvCommandJumpLayoutConfig {
    size_t jump_label = 0;
    size_t expression_parser = 0;
    int condition_column = 0;
};

struct TextColumnsConfig {
    int raw = 0;
    int en = 0;
    int zh_tw = 0;
    int zh_cn = 0;
};

// Java层传入的配置结构体
struct RvaConfig {
    uintptr_t init_base = 0;
    uintptr_t init_text = 0;
    uintptr_t page_text_change = 0;
    uintptr_t add_selection = 0;
    uintptr_t show_selection = 0;
};

struct LayoutConfig {
    Il2CppStringLayoutConfig il2cpp_string;
    Il2CppArrayLayoutConfig il2cpp_array;
    Il2CppListLayoutConfig il2cpp_list;
    AdvScenarioPageDataLayoutConfig adv_scenario_page_data;
    ScenarioLabelDataLayoutConfig scenario_label_data;
    AdvCommandLayoutConfig adv_command;
    StringGridRowLayoutConfig string_grid_row;
    AdvCommandCharacterLayoutConfig adv_command_character;
    AdvCommandSelectionLayoutConfig adv_command_selection;
    AdvCommandJumpLayoutConfig adv_command_jump;
    TextColumnsConfig text_columns;
};

struct RuntimeConfig {
    RvaConfig rva;
    LayoutConfig layout;
};

extern RuntimeConfig g_runtime_config;

// 预处理文本结构体
struct CharacterInfo {
    std::string name;
    std::string description;
};

struct TextItem {
    uint64_t seq;
    std::string speaker;
    std::vector<std::string> text;
};

struct OptionItem {
    uint64_t seq;
    std::string text;
    std::string jump_label;
};

struct BrenchItem {
    OptionItem option;
    std::vector<TextItem> following_text;
};

struct ChoiceBlock {
    std::vector<uint64_t> options_seq;
    std::vector<BrenchItem> brenches;
};

using SceneItem = std::variant<TextItem, ChoiceBlock>;

struct Scene {
    std::string scene;
    std::string raw_lang = "ja";
    std::string target_lang;
    std::vector<std::string> character;
    std::unordered_map<std::string, CharacterInfo> character_info;
    std::vector<SceneItem> scene_items;
};

//函数声明
void SetStopCatch(bool stop);
bool CommandExamine(void* pageData, const std::string& source);
bool make_runtime_config(const RvaConfig& java_rva, const LayoutConfig& java_layout, RuntimeConfig* out);
bool valid_rva_config(const RvaConfig& config);
bool valid_layout_config(const LayoutConfig& config);
bool install_hook(uintptr_t il2cpp_base, RvaConfig config);
bool valid_ptr(void* ptr);
void* read_ptr(void* base, size_t offset);
int read_int(void* base, size_t offset);
std::string read_il2cpp_string(void* strptr);
