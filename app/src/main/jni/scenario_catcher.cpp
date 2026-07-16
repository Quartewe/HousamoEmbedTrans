#include "housamo.hpp"

#include <algorithm>
#include <mutex>
#include <string>
#include <unordered_map>
#include <unordered_set>
#include <utility>
#include <atomic>
#include <condition_variable>
#include <deque>
#include <thread>

std::atomic<StopReason> stop_reason{StopReason::none};

void ResumeCapture() {
    stop_reason.store(StopReason::none, std::memory_order_release);
    NotifyPageRecStopChanged();
    // NotifySceneBuilderStopChanged();
}

namespace {

enum class ScenarioParseStatus {
    ok,
    skipped_official_translation,
    failed,
};

struct ScenarioParseOutput {
    ScenarioParseStatus status = ScenarioParseStatus::failed;
    ScenarioParseResult result;
};

struct ListView {
    bool ok = false;
    int size = 0;
    void* items = nullptr;
};

struct RuntimeLabelNode {
    std::string label;
    std::string next_label;
    void* label_data = nullptr;
    int page_count = 0;
};

struct RuntimeScenario {
    ScenarioResult result;
    std::unordered_map<std::string, RuntimeLabelNode> labels;
    std::vector<std::string> label_order;
};

struct ParseStats {
    int labels = 0;
    int pages = 0;
    int text = 0;
    int selection = 0;
    int jump = 0;
};

struct PageParseJob {
    int label_index = -1;
    int page_index = -1;
    int page_no = -1;
    std::string label;
    void* page_data = nullptr;
};

struct OrderedJump {
    int page_index = -1;
    OrderKey order;

    std::string command_type;
    std::string target_label;
    std::string condition;
};

struct PageParseResult {
    bool ok = false;
    bool official_translation = false;
    int label_index = -1;
    int page_index = -1;
    int page_no = -1;

    ParseStats stats;

    std::vector<ItemScore> speaker_character;
    std::vector<ItemScore> show_character;
    std::vector<ItemScore> text_character;
    std::vector<AliasScore> aliases;
    std::vector<ItemScore> game_terms;

    std::vector<ProtectedToken> protect;
    std::vector<OrderedJump> jumps;
    std::vector<SceneItem> scene_items;
};

static std::mutex caught_mutex;
static std::unordered_set<uintptr_t> caught_scenarios;

static void ForgetCaughtScenario(uintptr_t scenario_key) {
    std::lock_guard<std::mutex> lock(caught_mutex);
    caught_scenarios.erase(scenario_key);
}

template <typename T>
class BlockingQueue {
public:
    bool Push(T value) {
        {
            std::lock_guard<std::mutex> lock(mutex_);
            if (closed_) return false;
            queue_.push_back(std::move(value));
        }
        cv_.notify_one();
        return true;
    }

    bool Pop(T* out) {
        std::unique_lock<std::mutex> lock(mutex_);

        cv_.wait(lock, [this] {
            return closed_ || !queue_.empty();
        });

        if (queue_.empty()) {
            return false;
        }

        *out = std::move(queue_.front());
        queue_.pop_front();
        return true;
    }

    void Close() {
        {
            std::lock_guard<std::mutex> lock(mutex_);
            closed_ = true;
        }
        cv_.notify_all();
    }

private:
    std::mutex mutex_;
    std::condition_variable cv_;
    std::deque<T> queue_;
    bool closed_ = false;
};

static void AddItemScore(std::vector<ItemScore>& item, const std::string& name, float score) {
    if (name.empty() || name == "mc") {
        return;
    }

    for (auto& existing : item) {
        if (existing.item == name) {
            existing.score += score;
            return;
        }
    }
    item.push_back({name, score});
}

static void AddAlias(
    std::vector<AliasScore>& aliases,
    const std::string& pattern,
    const std::string& canonical,
    const std::string& called,
    float score) {
    if (pattern.empty() || canonical.empty()) {
        return;
    }

    for (auto& existing : aliases) {
        if (existing.pattern == pattern) {
            existing.score += score;
            return;
        }
    }
    aliases.push_back({pattern, canonical, called, score});
}

static void MergeStats(ParseStats& dst, const ParseStats& src) {
    dst.pages += src.pages;
    dst.text += src.text;
    dst.selection += src.selection;
    dst.jump += src.jump;
}

static void MergeScores(std::vector<ItemScore>& dst, const std::vector<ItemScore>& src) {
    for (const auto& item : src) {
        AddItemScore(dst, item.item, item.score);
    }
}

static void MergeAliases(std::vector<AliasScore>& dst, const std::vector<AliasScore>& src) {
    for (const auto& alias : src) {
        AddAlias(dst, alias.pattern, alias.canonical, alias.called, alias.score);
    }
}

template <typename T>
static void MoveAppend(std::vector<T>& dst, std::vector<T>& src) {
    dst.reserve(dst.size() + src.size());
    for (auto& item : src) {
        dst.push_back(std::move(item));
    }
}

static ListView ReadList(void* list_ptr, int max_size) {
    ListView view;
    if (!valid_ptr(list_ptr)) {
        return view;
    }

    const auto& list = g_runtime_config.layout.il2cpp_list;
    view.size = read_int(list_ptr, list.size);
    view.items = read_ptr(list_ptr, list.items);
    view.ok = view.size >= 0 && view.size <= max_size && valid_ptr(view.items);
    return view;
}

static void* ListElement(const ListView& list, int index) {
    if (!list.ok || index < 0 || index >= list.size) {
        return nullptr;
    }

    const auto& array = g_runtime_config.layout.il2cpp_array;
    return read_ptr(list.items, array.first_element + index * array.pointer_size);
}

static std::string ReadLabelName(void* label_data) {
    const auto& label_layout = g_runtime_config.layout.scenario_label_data;
    void* label_ptr = read_ptr(label_data, label_layout.scenario_label);
    return read_il2cpp_string(label_ptr);
}

static std::string ReadRowStringColumn(void* cmd_item, int column_index) {
    if (column_index < 0) {
        return {};
    }

    const auto& layout = g_runtime_config.layout;
    const auto& cmd = layout.adv_command;
    const auto& row = layout.string_grid_row;
    const auto& array = layout.il2cpp_array;

    void* row_data = read_ptr(cmd_item, cmd.row_data);
    void* strings = read_ptr(row_data, row.strings);
    int len = read_int(strings, array.length);

    if (len <= column_index || len > 4096) {
        return {};
    }

    void* text_ptr = read_ptr(strings, array.first_element + column_index * array.pointer_size);
    return read_il2cpp_string(text_ptr);
}

static bool IsTagStart(const std::string& raw, size_t index) {
    if (index >= raw.size() || raw[index] != '<') return false;
    if (index + 1 >= raw.size()) return false;

    unsigned char next = static_cast<unsigned char>(raw[index + 1]);
    return next == '/'
        || (next >= 'A' && next <= 'Z')
        || (next >= 'a' && next <= 'z');
}

static std::string CatchTextLabel(
    const std::string& raw,
    bool replace_mode,
    const std::string& token_prefix,
    std::vector<ProtectedToken>& protect,
    int& protect_index) {
    if (raw.empty()) {
        return {};
    }

    std::string out;
    out.reserve(raw.size());

    bool have_start = false;
    size_t label_start = 0;

    for (size_t i = 0; i < raw.size(); ++i) {
        char c = raw[i];

        if (!have_start) {
            if (IsTagStart(raw, i)) {
                have_start = true;
                label_start = i;
            } else {
                out.push_back(c);
            }
            continue;
        }

        if (c == '>') {
            if (replace_mode) {
                ProtectedToken token;
                token.label = token_prefix + std::to_string(protect_index++) + "__";
                token.origin = raw.substr(label_start, i - label_start + 1);
                out += token.label;
                protect.push_back(std::move(token));
            }
            have_start = false;
        }
    }

    if (have_start) {
        out += raw.substr(label_start);
    }

    return out;
}

static std::string ReadCharacterName(void* cmd_item) {
    const auto& character = g_runtime_config.layout.adv_command_character;
    void* info = read_ptr(cmd_item, character.character_info);
    void* name_text = read_ptr(info, character.name_text);
    return read_il2cpp_string(name_text);
}

static std::string ReadJumpLabel(void* cmd_item) {
    const auto& jump = g_runtime_config.layout.adv_command_jump;
    void* label_ptr = read_ptr(cmd_item, jump.jump_label);
    return read_il2cpp_string(label_ptr);
}

static std::string ReadSelectionJumpLabel(void* cmd_item) {
    const auto& selection = g_runtime_config.layout.adv_command_selection;
    void* label_ptr = read_ptr(cmd_item, selection.jump_label);
    return read_il2cpp_string(label_ptr);
}

enum class TextStatus {
    ok,
    empty,
    official_translation,
};

static TextStatus ReadTranslatableText(
    void* cmd_item,
    PageParseResult& result,
    std::string token_prefix,
    int& protect_index,
    const std::string& current_speaker,
    std::string* out) {
    if (out == nullptr) {
        return TextStatus::empty;
    }
    const auto& columns = g_runtime_config.layout.text_columns;
    int target_lang = 0;

    if (g_runtime_config.target_lang == "en") {
        target_lang = columns.en;
    } else if (g_runtime_config.target_lang == "zh-tw") {
        target_lang = columns.zh_tw;
    } else if (g_runtime_config.target_lang == "zh-cn") {
        target_lang = columns.zh_cn;
    } else {
        LOGW("[ScenarioCatcher] target lang [%s] has no official translation", g_runtime_config.target_lang.c_str());
    }

    if (target_lang != 0) {
        std::string target_lang_text = ReadRowStringColumn(cmd_item, target_lang);
        if (!target_lang_text.empty()) {
            LOGW("[ScenarioCatcher] %s already exists; skip current scenario", g_runtime_config.target_lang.c_str());
            return TextStatus::official_translation;
        }
    }


    std::string raw_text = ReadRowStringColumn(cmd_item, columns.raw);
    if (raw_text.empty()) {
        return TextStatus::empty;
    }

    // 读当前显示角色
    for (const AcHit& ac_hit : AcScan(ReadRowStringColumn(cmd_item, 1))) {
        if (ac_hit.kind == MatchKind::character) {
            AddItemScore(result.show_character, ac_hit.canonical, ac_hit.score);
        } else if (ac_hit.kind == MatchKind::term) {
            AddItemScore(result.game_terms, ac_hit.canonical, ac_hit.score);
        }
    }

    for (const AcHit& ac_hit : AcScan(raw_text)) {
        bool valid_alias = ac_hit.called.empty() || ac_hit.called == current_speaker;

        if (ac_hit.kind == MatchKind::term) {
            AddItemScore(result.game_terms, ac_hit.canonical, ac_hit.score);
            continue;
        } else if (ac_hit.kind == MatchKind::character) {
            if (ac_hit.source == AcHitSource::alias) {
                if (valid_alias) {
                    AddAlias(result.aliases, ac_hit.matched_text, ac_hit.canonical, ac_hit.called, ac_hit.score);
                    AddItemScore(result.text_character, ac_hit.canonical, ac_hit.score);
                }
                continue;
            }
            AddItemScore(result.text_character, ac_hit.canonical, ac_hit.score);
        }
    }

    *out = CatchTextLabel(
        raw_text,
        true,
        token_prefix,
        result.protect,
        protect_index);
    if (out->empty()) {
        return TextStatus::empty;
    }
    return TextStatus::ok;
}

static void FlushChoiceBlock(ChoiceBlock& choice, PageParseResult& result) {
    if (choice.branches.empty()) {
        return;
    }

    result.scene_items.emplace_back(std::move(choice));
    choice = ChoiceBlock{};
}

static bool ParseScenarioLabels(
    void* scenario_data,
    const std::string& entry_label,
    RuntimeScenario* out) {
        
    if (!valid_ptr(scenario_data) || out == nullptr) {
        return false;
    }

    const auto& layout = g_runtime_config.layout;
    const auto& scenario = layout.adv_scenario_data;
    const auto& dict = layout.il2cpp_dictionary;
    const auto& entry = layout.dictionary_entry;
    const auto& array = layout.il2cpp_array;
    const auto& label_layout = layout.scenario_label_data;

    out->result.entry_label = entry_label;
    out->result.scene = read_il2cpp_string(read_ptr(scenario_data, scenario.name));

    void* label_dict = read_ptr(scenario_data, scenario.scenario_labels);
    void* entries = read_ptr(label_dict, dict.entries);
    int dict_count = read_int(label_dict, dict.count);
    int entry_capacity = read_int(entries, array.length);

    if (dict_count <= 0 || dict_count > 8192
        || entry_capacity <= 0 || entry_capacity > 8192
        || !valid_ptr(entries)) {
        LOGE("[ScenarioCatcher] invalid scenarioLabels dict count=%d capacity=%d",
             dict_count,
             entry_capacity);
        return false;
    }

    int limit = std::min(dict_count, entry_capacity);
    uintptr_t first_entry = reinterpret_cast<uintptr_t>(entries) + array.first_element;

    for (int i = 0; i < limit; ++i) {
        void* entry_ptr = reinterpret_cast<void*>(first_entry + i * entry.size);
        int hash_code = read_int(entry_ptr, entry.hash_code);
        if (hash_code < 0) {
            continue;
        }

        void* key_ptr = read_ptr(entry_ptr, entry.key);
        void* label_data = read_ptr(entry_ptr, entry.value);
        if (!valid_ptr(label_data)) {
            continue;
        }

        std::string key = read_il2cpp_string(key_ptr);
        std::string value_label = ReadLabelName(label_data);
        std::string label = !value_label.empty() ? value_label : key;
        if (label.empty()) {
            continue;
        }

        void* next_data = read_ptr(label_data, label_layout.next);
        std::string next_label = ReadLabelName(next_data);

        ListView pages = ReadList(read_ptr(label_data, label_layout.page_data_list), 8192);

        RuntimeLabelNode runtime_node;
        runtime_node.label = label;
        runtime_node.next_label = next_label;
        runtime_node.label_data = label_data;
        runtime_node.page_count = pages.ok ? pages.size : 0;
        out->labels[label] = runtime_node;

        ScenarioLabelNode public_node;
        public_node.label = label;
        public_node.next_label = next_label;
        public_node.page_count = runtime_node.page_count;
        out->result.labels[label] = std::move(public_node);
    }

    return !out->labels.empty();
}

static void BuildLabelOrder(RuntimeScenario* scenario) {
    if (scenario == nullptr || scenario->labels.empty()) {
        return;
    }

    std::string start = scenario->result.scene;
    if (scenario->labels.find(start) == scenario->labels.end()) {
        start = scenario->result.entry_label;
    }

    if (scenario->labels.find(start) == scenario->labels.end()) {
        start = scenario->labels.begin()->first;
        LOGW("[ScenarioCatcher] neither scenario name nor entry label found, fallback=%s",
             start.c_str());
    }

    std::unordered_set<std::string> visited;
    std::string current = start;

    while (!current.empty() && scenario->label_order.size() < scenario->labels.size()) {
        if (!visited.insert(current).second) {
            LOGW("[ScenarioCatcher] label Next loop at %s", current.c_str());
            break;
        }

        auto it = scenario->labels.find(current);
        if (it == scenario->labels.end()) {
            LOGW("[ScenarioCatcher] missing Next target %s", current.c_str());
            break;
        }

        scenario->label_order.push_back(current);
        current = it->second.next_label;
    }

    std::vector<std::string> unvisited;
    for (const auto& pair : scenario->labels) {
        if (visited.find(pair.first) == visited.end()) {
            unvisited.push_back(pair.first);
        }
    }

    std::sort(unvisited.begin(), unvisited.end());
    for (const auto& label : unvisited) {
        LOGW("[ScenarioCatcher] append unvisited label=%s", label.c_str());
        scenario->label_order.push_back(label);
    }

    scenario->result.label_order = scenario->label_order;
}

static PageParseResult ParsePageJob(const PageParseJob& job) {
    PageParseResult result;
    result.label_index = job.label_index;
    result.page_index = job.page_index;
    result.page_no = job.page_no;

    void* page_data = job.page_data;
    int protect_index = 0;
    std::string token_prefix =
        "__HET__PT_" + std::to_string(job.label_index) + "_"
                     + std::to_string(job.page_index) + "_";

    if (!valid_ptr(page_data)) {
        return result;
    }

    const auto& layout = g_runtime_config.layout;
    const auto& page = layout.adv_scenario_page_data;
    const auto& cmd = layout.adv_command;

    int page_no = read_int(page_data, page.page_no);
    ListView commands = ReadList(read_ptr(page_data, page.command_list), 4096);
    if (!commands.ok) {
        return result;
    }

    ++result.stats.pages;
    std::string current_speaker;
    ChoiceBlock pending_choice;

    for (int i = 0; i < commands.size; ++i) {
        void* cmd_item = ListElement(commands, i);
        if (!valid_ptr(cmd_item)) {
            continue;
        }

        std::string type = read_il2cpp_string(read_ptr(cmd_item, cmd.type));

        if (type != "Selection") {
            FlushChoiceBlock(pending_choice, result);
        }

        if (type == "Character") {
            current_speaker = ReadCharacterName(cmd_item);
            AddItemScore(result.show_character, current_speaker, 2.0f);
            continue;
        }

        if (type == "CharacterOff") {
            current_speaker.clear();
            continue;
        }

        if (type == "Text") {
            std::string text;

            TextStatus status = ReadTranslatableText(cmd_item, result, token_prefix, protect_index, current_speaker, &text);
            if (status == TextStatus::official_translation) {
                result.official_translation = true;
                return result;
            }
            if (status != TextStatus::ok) {
                continue;
            }

            TextItem item;
            item.order.label_index = result.label_index;
            item.order.page_no = page_no;
            item.order.cmd_index = i;
            item.order.sub_index = 0;
            item.speaker = current_speaker;
            AddItemScore(result.speaker_character, current_speaker, 10.0f);
            item.text = std::move(text);
            
            result.scene_items.emplace_back(std::move(item));
            ++result.stats.text;
            continue;
        }

        if (type == "Selection") {
            std::string text;

            TextStatus status = ReadTranslatableText(cmd_item, result, token_prefix, protect_index, current_speaker, &text);
            if (status == TextStatus::official_translation) {
                result.official_translation = true;
                return result;
            }
            if (status != TextStatus::ok) {
                continue;
            }

            if (pending_choice.branches.empty()) {
                pending_choice.order.label_index = result.label_index;
                pending_choice.order.page_no = page_no;
                pending_choice.order.cmd_index = i;
                pending_choice.order.sub_index = 0;
            }

            BranchItem branch;
            branch.option.order.label_index = result.label_index;
            branch.option.order.page_no = page_no;
            branch.option.order.cmd_index = i;
            branch.option.order.sub_index = static_cast<int>(pending_choice.branches.size());
            branch.option.speaker = "mc";
            branch.option.text = std::move(text);
            branch.target_label = ReadSelectionJumpLabel(cmd_item);

            pending_choice.branches.emplace_back(std::move(branch));
            ++result.stats.selection;
            continue;
        }

        if (type == "Jump" || type == "JumpRandom" || type == "JumpSubroutine") {
            std::string target = ReadJumpLabel(cmd_item);
            if (!target.empty()) {
                OrderedJump jump;
                jump.page_index = result.page_index;
                jump.order = {result.label_index, page_no, i, 0};
                jump.command_type = type;
                jump.target_label = target;
                jump.condition = ReadRowStringColumn(cmd_item, g_runtime_config.layout.adv_command_jump.condition_column);

                if (jump.command_type == "Jump" && !jump.condition.empty()) {
                    IfBlock if_block;
                    if_block.order = jump.order;
                    if_block.condition = jump.condition;
                    if_block.target_label = jump.target_label;
                    result.scene_items.emplace_back(std::move(if_block));
                }

                result.jumps.emplace_back(std::move(jump));

                ++result.stats.jump;
            }
            continue;
        }
    }

    FlushChoiceBlock(pending_choice, result);

    result.ok = true;
    return result;
}

struct LabelBlock {
    std::string label;
    std::string next_label;
    std::vector<OrderedJump> jumps;
    std::vector<SceneItem> scene_items;

    std::string continuation;
    bool has_terminal_jump = false;
    bool consumed = false;
};

static constexpr const char* kVirtualExit = "\x1fHET_VIRTUAL_EXIT";

static bool OrderedJumpLess(const OrderedJump& a, const OrderedJump& b) {
    if (a.page_index != b.page_index) {
        return a.page_index < b.page_index;
    }
    if (a.order.page_no != b.order.page_no) {
        return a.order.page_no < b.order.page_no;
    }
    if (a.order.cmd_index != b.order.cmd_index) {
        return a.order.cmd_index < b.order.cmd_index;
    }
    return a.order.sub_index < b.order.sub_index;
}

static bool InitializeContinuation(LabelBlock* block) {
    if (block == nullptr) {
        return false;
    }

    std::sort(block->jumps.begin(), block->jumps.end(), OrderedJumpLess);
    for (const OrderedJump& jump : block->jumps) {
        if (jump.command_type != "Jump") {
            LOGW("[ScenarioCatcher] unsupported jump kind label=%s type=%s target=%s",
                 block->label.c_str(),
                 jump.command_type.c_str(),
                 jump.target_label.c_str());
            return false;
        }
    }

    for (auto it = block->jumps.rbegin(); it != block->jumps.rend(); ++it) {
        if (it->command_type == "Jump"
            && it->condition.empty()
            && !it->target_label.empty()) {
            block->continuation = it->target_label;
            block->has_terminal_jump = true;
            return true;
        }
    }

    block->continuation = block->next_label;
    return true;
}

static bool BuildContinuationChain(
    const std::string& start,
    const std::vector<LabelBlock>& blocks,
    const std::unordered_map<std::string, size_t>& label_indices,
    std::vector<std::string>* out) {
    if (start.empty() || out == nullptr) {
        return false;
    }

    std::unordered_set<std::string> visited;
    std::string current = start;

    for (size_t step = 0; step <= blocks.size() + 1; ++step) {
        if (current.empty()) {
            out->emplace_back(kVirtualExit);
            return true;
        }

        if (!visited.insert(current).second) {
            LOGW("[ScenarioCatcher] continuation loop while resolving branch at label=%s",
                 current.c_str());
            return false;
        }

        out->push_back(current);

        auto it = label_indices.find(current);
        if (it == label_indices.end()) {
            out->emplace_back(kVirtualExit);
            return true;
        }

        current = blocks[it->second].continuation;
    }

    return false;
}

static bool FindChoiceMerge(
    const ChoiceBlock& choice,
    const std::vector<LabelBlock>& blocks,
    const std::unordered_map<std::string, size_t>& label_indices,
    std::string* merge_label) {
    if (choice.branches.empty() || merge_label == nullptr) {
        return false;
    }

    std::vector<std::vector<std::string>> chains;
    chains.reserve(choice.branches.size());

    for (const BranchItem& branch : choice.branches) {
        if (branch.target_label.empty()) {
            return false;
        }

        std::vector<std::string> chain;
        chain.reserve(blocks.size() + 1);
        if (!BuildContinuationChain(branch.target_label, blocks, label_indices, &chain)) {
            return false;
        }
        chains.push_back(std::move(chain));
    }

    if (chains.size() == 1) {
        *merge_label = kVirtualExit;
        return true;
    }

    std::vector<std::unordered_set<std::string>> reachable;
    reachable.reserve(chains.size() - 1);
    for (size_t i = 1; i < chains.size(); ++i) {
        reachable.emplace_back(chains[i].begin(), chains[i].end());
    }

    for (const std::string& candidate : chains.front()) {
        bool common = true;
        for (const auto& labels : reachable) {
            if (labels.find(candidate) == labels.end()) {
                common = false;
                break;
            }
        }

        if (common) {
            *merge_label = candidate;
            return true;
        }
    }

    return false;
}

static bool FindIfMerge(
    const std::string& true_start,
    const std::string& false_start,
    const std::vector<LabelBlock>& blocks,
    const std::unordered_map<std::string, size_t>& label_indices,
    std::string* merge_label) {
    if (true_start.empty() || false_start.empty() || merge_label == nullptr) {
        return false;
    }

    std::vector<std::string> true_chain;
    true_chain.reserve(blocks.size() + 1);
    if (!BuildContinuationChain(true_start, blocks, label_indices, &true_chain)) {
        return false;
    }

    std::vector<std::string> false_chain;
    false_chain.reserve(blocks.size() + 1);
    if (false_start == kVirtualExit) {
        false_chain.emplace_back(kVirtualExit);
    } else if (!BuildContinuationChain(false_start, blocks, label_indices, &false_chain)) {
        return false;
    }

    const std::unordered_set<std::string> false_reachable(
        false_chain.begin(),
        false_chain.end());

    for (const std::string& candidate : true_chain) {
        if (false_reachable.find(candidate) != false_reachable.end()) {
            *merge_label = candidate;
            return true;
        }
    }

    return false;
}

static bool CollectBranchPath(
    const std::string& start,
    const std::string& merge_label,
    const std::vector<LabelBlock>& blocks,
    const std::unordered_map<std::string, size_t>& label_indices,
    std::vector<size_t>* path) {
    if (start.empty() || merge_label.empty() || path == nullptr) {
        return false;
    }

    std::unordered_set<std::string> visited;
    std::string current = start;

    for (size_t step = 0; step <= blocks.size(); ++step) {
        if (current == merge_label) {
            return true;
        }

        if (current.empty()) {
            return merge_label == kVirtualExit;
        }

        if (!visited.insert(current).second) {
            return false;
        }

        auto it = label_indices.find(current);
        if (it == label_indices.end()) {
            return merge_label == kVirtualExit;
        }

        path->push_back(it->second);
        current = blocks[it->second].continuation;
    }

    return false;
}

static bool ResolveChoice(
    size_t owner_index,
    const std::string& owner_label,
    ChoiceBlock* choice,
    std::vector<LabelBlock>* blocks,
    const std::unordered_map<std::string, size_t>& label_indices,
    std::string* merge_label) {
    if (choice == nullptr || blocks == nullptr || merge_label == nullptr) {
        return false;
    }

    if (!FindChoiceMerge(*choice, *blocks, label_indices, merge_label)) {
        LOGW("[ScenarioCatcher] no common merge for choice label=%s page=%d cmd=%d",
             owner_label.c_str(),
             choice->order.page_no,
             choice->order.cmd_index);
        return false;
    }

    std::vector<std::vector<size_t>> branch_paths;
    branch_paths.reserve(choice->branches.size());
    std::unordered_set<size_t> claimed;

    for (const BranchItem& branch : choice->branches) {
        std::vector<size_t> path;
        if (!CollectBranchPath(
                branch.target_label,
                *merge_label,
                *blocks,
                label_indices,
                &path)) {
            LOGW("[ScenarioCatcher] invalid branch path owner=%s target=%s merge=%s",
                 owner_label.c_str(),
                 branch.target_label.c_str(),
                 merge_label->c_str());
            return false;
        }

        for (size_t index : path) {
            if (index <= owner_index || (*blocks)[index].consumed || !claimed.insert(index).second) {
                LOGW("[ScenarioCatcher] branch path is not a tree owner=%s target=%s label=%s",
                     owner_label.c_str(),
                     branch.target_label.c_str(),
                     (*blocks)[index].label.c_str());
                return false;
            }
        }

        branch_paths.push_back(std::move(path));
    }

    for (size_t branch_index = 0; branch_index < choice->branches.size(); ++branch_index) {
        BranchItem& branch = choice->branches[branch_index];
        branch.merge_label = *merge_label == kVirtualExit ? "" : *merge_label;
        for (size_t label_index : branch_paths[branch_index]) {
            LabelBlock& block = (*blocks)[label_index];
            MoveAppend(branch.following_text, block.scene_items);
            block.consumed = true;
        }
    }

    LOGD("[ScenarioCatcher] choice resolved label=%s merge=%s branches=%zu",
         owner_label.c_str(),
         *merge_label == kVirtualExit ? "<exit>" : merge_label->c_str(),
         choice->branches.size());

    return true;
}

static bool ResolveIfBlock(
    size_t owner_index,
    const std::string& owner_label,
    const std::string& false_start,
    IfBlock* if_block,
    std::vector<LabelBlock>* blocks,
    const std::unordered_map<std::string, size_t>& label_indices) {
    if (if_block == nullptr || blocks == nullptr || if_block->target_label.empty()) {
        return false;
    }

    std::string merge_label;
    if (!FindIfMerge(
            if_block->target_label,
            false_start,
            *blocks,
            label_indices,
            &merge_label)) {
        LOGW("[ScenarioCatcher] no common merge for if owner=%s target=%s false=%s condition=%s",
             owner_label.c_str(),
             if_block->target_label.c_str(),
             false_start == kVirtualExit ? "<exit>" : false_start.c_str(),
             if_block->condition.c_str());
        return false;
    }

    std::vector<size_t> path;
    if (!CollectBranchPath(
            if_block->target_label,
            merge_label,
            *blocks,
            label_indices,
            &path)) {
        LOGW("[ScenarioCatcher] invalid if path owner=%s target=%s merge=%s condition=%s",
             owner_label.c_str(),
             if_block->target_label.c_str(),
             merge_label.c_str(),
             if_block->condition.c_str());
        return false;
    }

    for (size_t index : path) {
        if (index <= owner_index || (*blocks)[index].consumed) {
            LOGW("[ScenarioCatcher] if path is not a tree owner=%s target=%s label=%s",
                 owner_label.c_str(),
                 if_block->target_label.c_str(),
                 (*blocks)[index].label.c_str());
            return false;
        }
    }

    for (size_t index : path) {
        LabelBlock& block = (*blocks)[index];
        MoveAppend(if_block->following_text, block.scene_items);
        block.consumed = true;
    }

    if_block->merge_label = merge_label == kVirtualExit ? "" : merge_label;

    LOGD("[ScenarioCatcher] if resolved label=%s target=%s merge=%s items=%zu",
         owner_label.c_str(),
         if_block->target_label.c_str(),
         merge_label == kVirtualExit ? "<exit>" : merge_label.c_str(),
         if_block->following_text.size());

    return true;
}

static std::string MergeIfConditions(
    const std::string& first,
    const std::string& second) {
    if (first.empty() || first == second) {
        return second.empty() ? first : second;
    }
    if (second.empty()) {
        return first;
    }

    return "(" + first + ")||(" + second + ")";
}

static void CoalesceParallelIfEdges(LabelBlock* block) {
    if (block == nullptr || block->scene_items.size() < 2) {
        return;
    }

    std::vector<SceneItem> normalized;
    normalized.reserve(block->scene_items.size());

    std::unordered_map<std::string, size_t> target_indices;
    target_indices.reserve(block->scene_items.size());

    for (SceneItem& item : block->scene_items) {
        IfBlock* current = std::get_if<IfBlock>(&item.value);
        if (current == nullptr || current->target_label.empty()) {
            normalized.emplace_back(std::move(item));
            continue;
        }

        auto [target_it, inserted] = target_indices.emplace(
            current->target_label,
            normalized.size());
        if (inserted) {
            normalized.emplace_back(std::move(item));
            continue;
        }

        IfBlock* existing = std::get_if<IfBlock>(
            &normalized[target_it->second].value);
        if (existing == nullptr) {
            target_it->second = normalized.size();
            normalized.emplace_back(std::move(item));
            continue;
        }

        existing->condition = MergeIfConditions(
            existing->condition,
            current->condition);

        LOGD("[ScenarioCatcher] coalesced if owner=%s target=%s condition=%s",
             block->label.c_str(),
             existing->target_label.c_str(),
             existing->condition.c_str());
    }

    block->scene_items = std::move(normalized);
}

static bool AssembleLabelBlocks(
    std::vector<LabelBlock>* blocks,
    const std::unordered_map<std::string, size_t>& label_indices,
    std::vector<SceneItem>* out) {
    if (blocks == nullptr || out == nullptr) {
        return false;
    }

    for (LabelBlock& block : *blocks) {
        CoalesceParallelIfEdges(&block);
    }

    for (size_t reverse_index = blocks->size(); reverse_index > 0; --reverse_index) {
        const size_t label_index = reverse_index - 1;
        LabelBlock& block = (*blocks)[label_index];
        bool continuation_from_choice = false;

        for (size_t item_index = block.scene_items.size(); item_index > 0; --item_index) {
            SceneItem& item = block.scene_items[item_index - 1];
            ChoiceBlock* choice = std::get_if<ChoiceBlock>(&item.value);
            if (choice != nullptr) {
                std::string merge_label;
                if (!ResolveChoice(
                        label_index,
                        block.label,
                        choice,
                        blocks,
                        label_indices,
                        &merge_label)) {
                    return false;
                }

                if (!block.has_terminal_jump && !continuation_from_choice) {
                    block.continuation = merge_label == kVirtualExit ? "" : merge_label;
                    continuation_from_choice = true;
                }
                continue;
            }

            IfBlock* if_block = std::get_if<IfBlock>(&item.value);
            if (if_block != nullptr) {
                const std::string false_start = block.continuation.empty()
                    ? std::string(kVirtualExit)
                    : block.continuation;
                if (!ResolveIfBlock(
                        label_index,
                        block.label,
                        false_start,
                        if_block,
                        blocks,
                        label_indices)) {
                    return false;
                }
            }
        }
    }

    size_t consumed_count = 0;
    for (LabelBlock& block : *blocks) {
        if (block.consumed) {
            ++consumed_count;
            continue;
        }
        MoveAppend(*out, block.scene_items);
    }

    LOGI("[ScenarioCatcher] assembled label tree consumed=%zu roots=%zu items=%zu",
         consumed_count,
         blocks->size() - consumed_count,
         out->size());
    return true;
}

class ScenarioParseRunner {
public:
    explicit ScenarioParseRunner(const RuntimeScenario& scenario)
        : scenario_(scenario) {}

    ScenarioParseOutput Run() {
        result_.scene = scenario_.result.scene;
        result_.entry_label = scenario_.result.entry_label;

        StartWorkers();
        size_t submitted_jobs = SubmitJobs();

        job_queue_.Close();
        JoinWorkers();

        result_queue_.Close();
        CollectResults(submitted_jobs);

        if (Reason() == AbortReason::official_translation) {
            LOGI("[ScenarioCatcher] skipped official translation scene=%s entry=%s",
                 scenario_.result.scene.c_str(),
                 scenario_.result.entry_label.c_str());
            return {ScenarioParseStatus::skipped_official_translation, {}};
        }

        if (Reason() != AbortReason::none) {
            LOGE("[ScenarioCatcher] parse aborted scene=%s reason=%d",
                 scenario_.result.scene.c_str(),
                 static_cast<int>(Reason()));
            return {ScenarioParseStatus::failed, {}};
        }

        LOGI("[ScenarioCatcher] parsed scene=%s labels=%d/%zu pages=%d text=%d selection=%d jump=%d items=%zu",
             scenario_.result.scene.c_str(),
             total_stats_.labels,
             scenario_.labels.size(),
             total_stats_.pages,
             total_stats_.text,
             total_stats_.selection,
             total_stats_.jump,
             result_.scene_items.size());

        return {ScenarioParseStatus::ok, std::move(result_)};
    }

private:
    enum class AbortReason : int {
        none = 0,
        official_translation = 1,
        paused_existing_scene = 2,
        page_parse_failed = 3,
        branch_assembly_failed = 4,
    };

    int WorkerCount() const {
        int count = static_cast<int>(std::thread::hardware_concurrency());
        if (count <= 0) {
            count = 2;
        }
        return std::min(count, 4);
    }

    void StartWorkers() {
        int count = WorkerCount();
        workers_.reserve(count);

        for (int i = 0; i < count; ++i) {
            workers_.emplace_back(&ScenarioParseRunner::WorkerLoop, this);
        }
    }

    void JoinWorkers() {
        for (auto& worker : workers_) {
            if (worker.joinable()) {
                worker.join();
            }
        }
    }

    bool IsCapturePaused() const {
        return stop_reason.load(std::memory_order_acquire) == StopReason::user_pause;
    }

    void Abort(AbortReason reason) {
        int expected = static_cast<int>(AbortReason::none);
        abort_reason_.compare_exchange_strong(
            expected,
            static_cast<int>(reason),
            std::memory_order_acq_rel
        );
    }

    bool ShouldAbort() const {
        return abort_reason_.load(std::memory_order_acquire) !=
            static_cast<int>(AbortReason::none);
    }

    AbortReason Reason() const {
        return static_cast<AbortReason>(
            abort_reason_.load(std::memory_order_acquire)
        );
    }

    size_t SubmitJobs() {
        size_t submitted_jobs = 0;

        for (size_t label_index = 0; label_index < scenario_.label_order.size(); ++label_index) {
            if (ShouldAbort()) {
                break;
            }

            if (IsCapturePaused()) {
                Abort(AbortReason::paused_existing_scene);
                break;
            }

            const std::string& label_name = scenario_.label_order[label_index];
            auto it = scenario_.labels.find(label_name);
            if (it == scenario_.labels.end()) {
                continue;
            }

            const RuntimeLabelNode& label = it->second;
            ListView pages = ReadList(
                read_ptr(label.label_data, g_runtime_config.layout.scenario_label_data.page_data_list),
                8192);
            if (!pages.ok) {
                LOGW("[ScenarioCatcher] page list invalid label=%s", label.label.c_str());
                Abort(AbortReason::page_parse_failed);
                break;
            }

            ++total_stats_.labels;

            for (int page_index = 0; page_index < pages.size; ++page_index) {
                if (ShouldAbort()) {
                    break;
                }

                if (IsCapturePaused()) {
                    Abort(AbortReason::paused_existing_scene);
                    break;
                }

                void* page_data = ListElement(pages, page_index);
                PageParseJob job;
                job.label_index = static_cast<int>(label_index);
                job.page_index = page_index;
                job.page_no = read_int(
                    page_data,
                    g_runtime_config.layout.adv_scenario_page_data.page_no
                );
                job.label = label.label;
                job.page_data = page_data;

                if (!job_queue_.Push(std::move(job))) {
                    LOGE("[ScenarioCatcher] failed to push page job label=%s pageIndex=%d",
                         label.label.c_str(),
                         page_index);
                    Abort(AbortReason::page_parse_failed);
                    break;
                } else {
                    ++submitted_jobs;
                }
            }
        }

        return submitted_jobs;
    }

    void WorkerLoop() {
        PageParseJob job;

        while (job_queue_.Pop(&job)) {
            if (ShouldAbort()) {
                continue;
            }

            if (IsCapturePaused()) {
                Abort(AbortReason::paused_existing_scene);
                continue;
            }

            PageParseResult result = ParsePageJob(job);
            if (result.official_translation) {
                Abort(AbortReason::official_translation);
            } else if (!result.ok) {
                Abort(AbortReason::page_parse_failed);
            }

            result_queue_.Push(std::move(result));
        }
    }

    void CollectResults(size_t submitted_jobs) {
        std::vector<PageParseResult> page_results;
        page_results.reserve(submitted_jobs);

        PageParseResult page_result;
        while (result_queue_.Pop(&page_result)) {
            page_results.push_back(std::move(page_result));
        }

        std::sort(page_results.begin(), page_results.end(),
            [](const PageParseResult& a, const PageParseResult& b) {
                if (a.label_index != b.label_index) {
                    return a.label_index < b.label_index;
                }

                if (a.page_index != b.page_index) {
                    return a.page_index < b.page_index;
                }

                return a.page_no < b.page_no;
            });

        if (ShouldAbort()) {
            return;
        }

        std::vector<LabelBlock> label_blocks;
        label_blocks.reserve(scenario_.label_order.size());

        std::unordered_map<std::string, size_t> label_indices;
        label_indices.reserve(scenario_.label_order.size());

        for (const std::string& label_name : scenario_.label_order) {
            auto runtime_it = scenario_.labels.find(label_name);
            if (runtime_it == scenario_.labels.end()) {
                LOGE("[ScenarioCatcher] ordered label missing from runtime map label=%s",
                     label_name.c_str());
                Abort(AbortReason::branch_assembly_failed);
                return;
            }

            const RuntimeLabelNode& runtime = runtime_it->second;
            LabelBlock block;
            block.label = runtime.label;
            block.next_label = runtime.next_label;

            const size_t index = label_blocks.size();
            if (!label_indices.emplace(block.label, index).second) {
                LOGE("[ScenarioCatcher] duplicate label in order label=%s", block.label.c_str());
                Abort(AbortReason::branch_assembly_failed);
                return;
            }

            label_blocks.push_back(std::move(block));
        }

        for (auto& result : page_results) {
            if (!result.ok) {
                continue;
            }

            if (result.label_index < 0
                || static_cast<size_t>(result.label_index) >= label_blocks.size()) {
                LOGE("[ScenarioCatcher] page result label index out of range index=%d size=%zu",
                     result.label_index,
                     label_blocks.size());
                Abort(AbortReason::branch_assembly_failed);
                return;
            }

            MergeStats(total_stats_, result.stats);

            MergeScores(result_.speaker_character, result.speaker_character);
            MergeScores(result_.text_character, result.text_character);
            MergeScores(result_.show_character, result.show_character);
            MergeScores(result_.game_terms, result.game_terms);

            MergeAliases(result_.aliases, result.aliases);

            MoveAppend(result_.protect, result.protect);
            LabelBlock& block = label_blocks[static_cast<size_t>(result.label_index)];
            MoveAppend(block.jumps, result.jumps);
            MoveAppend(block.scene_items, result.scene_items);
        }

        for (LabelBlock& block : label_blocks) {
            if (!InitializeContinuation(&block)) {
                Abort(AbortReason::branch_assembly_failed);
                return;
            }
        }

        if (!AssembleLabelBlocks(&label_blocks, label_indices, &result_.scene_items)) {
            Abort(AbortReason::branch_assembly_failed);
        }
    }

private:
    const RuntimeScenario& scenario_;

    std::atomic<int> abort_reason_{
        static_cast<int>(AbortReason::none)
    };

    BlockingQueue<PageParseJob> job_queue_;
    BlockingQueue<PageParseResult> result_queue_;
    std::vector<std::thread> workers_;

    ScenarioParseResult result_;
    ParseStats total_stats_;
};

static ScenarioParseOutput ParseScenarioToResult(const RuntimeScenario& scenario) {
    ScenarioParseRunner runner(scenario);
    return runner.Run();
}

} // namespace

bool CatchScenario(void* scenario_data, const std::string& entry_label) {
    uintptr_t scenario_key = reinterpret_cast<uintptr_t>(scenario_data);

    {
        std::lock_guard<std::mutex> lock(caught_mutex);
        if (!caught_scenarios.insert(scenario_key).second) {
            LOGD("[ScenarioCatcher] scenario already caught entry=%s data=%p",
                 entry_label.c_str(),
                 scenario_data);
            return true;
        }
    }

    RuntimeScenario scenario;
    if (!ParseScenarioLabels(scenario_data, entry_label, &scenario)) {
        LOGE("[ScenarioCatcher] failed to parse labels entry=%s data=%p",
             entry_label.c_str(),
             scenario_data);
        ForgetCaughtScenario(scenario_key);
        return false;
    }

    BuildLabelOrder(&scenario);
    LOGI("[ScenarioCatcher] scenario=%s entry=%s labels=%zu order=%zu",
         scenario.result.scene.c_str(),
         scenario.result.entry_label.c_str(),
         scenario.labels.size(),
         scenario.label_order.size());

    ScenarioParseOutput output = ParseScenarioToResult(scenario);
    if (output.status == ScenarioParseStatus::skipped_official_translation) {
        LOGI("[ScenarioCatcher] scenario skipped because official translation exists entry=%s",
             entry_label.c_str());
        return true;
    }

    if (output.status != ScenarioParseStatus::ok) {
        LOGE("[ScenarioCatcher] failed to parse scene entry=%s", entry_label.c_str());
        ForgetCaughtScenario(scenario_key);
        return false;
    }

    StartSceneBuilder();
    SubmitScenarioParseResult(std::move(output.result));
    return true;
}
