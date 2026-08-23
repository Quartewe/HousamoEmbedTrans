#pragma once

#include <string>
#include <vector>
#include <condition_variable>
#include <unordered_map>
#include <unordered_set>
#include <variant>
#include <android/log.h>
#include <cstdint>
#include <cstddef>
#include <functional>
#include <atomic>
#include <mutex>
#include <memory>
#include <utility>

#include "scene_production_policy.hpp"

// 日志宏
#define LOG_TAG "HousamoTrans"
#define LOGD(...) __android_log_print(ANDROID_LOG_DEBUG, LOG_TAG, __VA_ARGS__)
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO,  LOG_TAG, __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN,  LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

// 全局变量和结构体定义
enum class StopReason {
    none,
    user_pause
};

extern std::atomic<StopReason> stop_reason;
// Monotonic capture admission generation.  A producer that began before a
// pause must not be able to enqueue after that pause has been cleared and the
// capture has resumed.
extern std::atomic<std::uint64_t> capture_pause_epoch;
// Serializes pause/resume transitions with captured-scene admission and the
// native formal Scene commit.  All participants acquire this before the
// scene queue mutex, so pause cannot clear a new generation behind an old
// producer or let an old worker commit after the boundary.
extern std::mutex capture_transition_mutex;

bool BeginSceneSyncHold();
bool ReplaceBlockedScenes(const std::vector<std::string>& scene_names);
void ResetSceneProductionPolicy();
het::scene_sync::SceneProductionLease EnterSceneProduction(
    const std::string& scene_name);
void ReportSceneProductionRejected(
    const std::string& scene_name,
    het::scene_sync::RejectReason reason);

enum class SceneFileStatus {
    not_found, // 文件不存在
    complete, // 文件完整
    pending // 文件存在但未完成
};

// AC机
enum class MatchKind {
    character,
    term
};

enum class AcHitSource {
    direct,
    alias
};

struct ACInit {
    std::vector<std::string> char_patterns;
    std::vector<std::string> char_canonicals;
    std::vector<std::string> char_called;
    std::vector<std::string> term_list;
};

struct AcHit {
    std::string matched_text;
    std::string canonical;
    std::string called;
    MatchKind kind;
    AcHitSource source = AcHitSource::direct;
    float score = 0.0f;
};

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

struct AdvScenarioDataLayoutConfig {
    size_t name = 0;
    size_t jump_data_list = 0;
    size_t scenario_labels = 0;
};

struct Il2CppDictionaryLayoutConfig {
    size_t entries = 0;
    size_t count = 0;
};

struct DictionaryEntryLayoutConfig {
    size_t hash_code = 0;
    size_t key = 0;
    size_t value = 0;
    size_t size = 0;
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
    uintptr_t find_scenario_data = 0;
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
    AdvScenarioDataLayoutConfig adv_scenario_data;
    Il2CppDictionaryLayoutConfig il2cpp_dictionary;
    DictionaryEntryLayoutConfig dictionary_entry;
    AdvCommandLayoutConfig adv_command;
    StringGridRowLayoutConfig string_grid_row;
    AdvCommandCharacterLayoutConfig adv_command_character;
    AdvCommandSelectionLayoutConfig adv_command_selection;
    AdvCommandJumpLayoutConfig adv_command_jump;
    TextColumnsConfig text_columns;
};

struct CharacterWeightConfig {
    float high_relevance = 4.0f;
    float mid_relevance = 3.0f;
    float density_high = 1.5f;
    float text_low_score = 3.0f;
    float text_mentioned_score = 1.0f;
    int related_num = 1;
    int low_term_score = 3;
};

struct RuntimeConfig {
    std::string game_version;
    RvaConfig rva;
    LayoutConfig layout;
    CharacterWeightConfig character_weight;
    int scene_worker_count = 4;
    std::string target_lang;
    std::string base_dir;
    bool overwrite_existing = false;
    bool enable_page_rec_debug = false;
    bool parse_only_debug = false;
};

extern RuntimeConfig g_runtime_config;

// 预处理文本结构体
struct OrderKey {
    int label_index = -1;   // ScenarioLabelData.Next 排序后的 label 序号
    int page_no = -1;      // 游戏里的 pageNo，主要用于调试
    int cmd_index = -1;    // CommandList 里的真实下标
    int sub_index = 0;     // 同一个命令内的子项，普通 Text 用 0，选项可用 0/1/2

    bool operator==(const OrderKey& other) const noexcept {
        return label_index == other.label_index
            && page_no == other.page_no
            && cmd_index == other.cmd_index
            && sub_index == other.sub_index;
    }
};

struct OrderKeyHash {
    std::size_t operator()(const OrderKey& key) const noexcept {
        std::size_t seed = 0;

        auto combine = [&seed](int value) {
            seed ^= std::hash<int>{}(value)
                + static_cast<std::size_t>(0x9e3779b9U)
                + (seed << 6U)
                + (seed >> 2U);
        };

        combine(key.label_index);
        combine(key.page_no);
        combine(key.cmd_index);
        combine(key.sub_index);
        return seed;
    }
};

struct ProtectedToken {
    OrderKey order;
    std::string label;      // __HET__PH_0__
    std::string origin;   // <param=teamLeaderCharaName>
};

struct TextItem {
    OrderKey order;
    std::string speaker;
    std::string text;
};

struct JumpItem {
    std::string target;
    std::string condition;
};

struct SceneItem;

struct ChoiceBranch {
    std::string target_label;
    std::vector<TextItem> options;
    std::vector<SceneItem> following_text;
};

struct ScenarioLabelNode {
    std::string label;
    std::string next_label;
    int page_count = 0;
};

struct ScenarioResult {
    std::string scene;
    std::string entry_label;

    std::unordered_map<std::string, ScenarioLabelNode> labels;
    std::vector<std::string> label_order;
};

struct IfBlock {
    OrderKey order;
    std::string condition;
    std::string target_label;
    std::string merge_label;
    std::vector<SceneItem> following_text;
};

struct ChoiceBlock {
    OrderKey order;
    std::string merge_label;
    std::vector<ChoiceBranch> branches;
};

struct SceneItem {
    using Value = std::variant<TextItem, ChoiceBlock, IfBlock>;

    Value value;

    SceneItem() = default;
    SceneItem(TextItem item) : value(std::move(item)) {}
    SceneItem(ChoiceBlock item) : value(std::move(item)) {}
    SceneItem(IfBlock item) : value(std::move(item)) {}
};

struct I18N {
    std::string en;
    std::string zh_tw;
    std::string zh_cn;
};

struct Relationship {
    std::string target;
    std::string type;
};

struct AliasItem {
    std::string name;
    I18N i18n;
    std::string called;
};

struct CharacterItem {
    std::string name;
    std::vector<AliasItem> aliases;
    I18N i18n;
    std::vector<std::string> school;
    std::vector<std::string> guild;
    std::vector<std::string> origin_world;
    std::vector<Relationship> relationships;
    std::string info;
    std::string description;
    std::string speech_style;
};

struct RelationshipHit {
    std::string source;   // 查询角色
    std::string target;   // 命中的相关角色
    std::string type;     // relationship.type
    bool reverse = false; // false: source -> target, true: target -> source
};

struct MentionedCharacter {
    std::string name;
    I18N i18n;
};

struct Character {
    CharacterItem mc;
    std::vector<CharacterItem> high_weight;
    std::vector<CharacterItem> low_weight;
};

struct GameTerm {
    std::string term;
    I18N i18n;
    std::string description;
};

struct ItemScore {
    std::string item;
    float score = 0.0f;
};

struct AliasScore {
    std::string pattern;
    std::string canonical;
    std::string called;
    float score = 0.0f;
};

struct ScenarioParseResult {
    std::string scene;        // scenarioData.name
    std::string entry_label;  // FindScenarioData(label) 的参数
    std::vector<ItemScore> speaker_character;
    std::vector<ItemScore> show_character;
    std::vector<ItemScore> text_character;
    std::vector<AliasScore> aliases;
    std::vector<ItemScore> game_terms;

    std::vector<ProtectedToken> protect;
    std::vector<SceneItem> scene_items;
};

struct Scene {
    std::string scene;
    std::string raw_lang = "ja";
    std::string target_lang;
    Character character;
    std::vector<MentionedCharacter> mentioned_characters;
    std::vector<GameTerm> game_terms;
    std::vector<ProtectedToken> protect;
    std::vector<SceneItem> scene_items;
};

// 启动类
bool install_hook(uintptr_t il2cpp_base, const RuntimeConfig& config);
void StartJsonManager(std::string chardict_json, std::string gameterms_json);
void StartAcInit(ACInit ac_init);
void StartSceneBuilder();

// submit 类
// bool SubmitToQuestRewriter(const std::string& entry_label);
void SubmitScenarioParseResult(
    ScenarioParseResult result,
    het::scene_sync::SceneProductionLease production_lease,
    std::uint64_t captured_epoch);

// 检验类
bool IsJsonManagerReady();
void NotifyPageRecStopChanged();
void ClearPageRecOnPause();
void SetCapturePaused(bool paused);
void ResumeCapture();
bool IsAcReady();

// 解析类
int RelatedNum(const std::string& name, const std::unordered_set<std::string>& related_characters);
bool FindAliasItem(
    const std::string& canonical,
    const std::string& alias_name,
    const std::string& called,
    std::vector<AliasItem>* out);
bool CommandExamine(void* pageData, const std::string& source);
bool CatchScenario(
    void* scenario_data,
    const std::string& entry_label,
    het::scene_sync::SceneProductionLease production_lease,
    std::uint64_t captured_epoch);
std::vector<AcHit> AcScan(const std::string& text);
bool FindCharacterItem(const std::string& name, CharacterItem* out);
bool FindGameTerm(const std::string& term, GameTerm* out);
bool make_runtime_config(
    const std::string& game_version,
    const RvaConfig& java_rva,
    const LayoutConfig& java_layout,
    const CharacterWeightConfig& character_weight,
    int scene_worker_count,
    const std::string& target_lang,
    bool enable_page_rec_debug,
    bool parse_only_debug,
    bool overwrite_existing,
    const std::string& base_dir,
    RuntimeConfig* out);

// 工具类
bool valid_rva_config(const RvaConfig& config);
bool valid_layout_config(const LayoutConfig& config);
bool valid_ptr(void* ptr);
void* read_ptr(void* base, size_t offset);
int read_int(void* base, size_t offset);
std::string read_il2cpp_string(void* strptr);
