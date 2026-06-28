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

std::atomic<bool> stop_catch{false};

void SetStopCatch(bool stop) {
    stop_catch.store(stop, std::memory_order_release);

    NotifyPageRecStopChanged();
    NotifySceneBuilderStopChanged();
}

namespace {

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

struct PageParseResult {
    bool ok = false;
    int label_index = -1;
    int page_index = -1;
    int page_no = -1;

    ParseStats stats;

    std::vector<ItemScore> speaker_character;
    std::vector<ItemScore> show_character;
    std::vector<ItemScore> text_character;
    std::vector<ItemScore> game_terms;

    std::vector<ProtectedToken> protect;
    std::vector<SceneItem> scene_items;
};

static std::mutex caught_mutex;
static std::unordered_set<uintptr_t> caught_scenarios;

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

static bool ReadTranslatableText(
    void* cmd_item,
    PageParseResult& result,
    std::string token_prefix,
    int& protect_index,
    std::string* out) {
    if (out == nullptr) {
        return false;
    }

    const auto& columns = g_runtime_config.layout.text_columns;
    std::string zh_cn = ReadRowStringColumn(cmd_item, columns.zh_cn);
    if (!zh_cn.empty()) {
        LOGW("[ScenarioCatcher] zh_cn already exists; stop current capture");
        SetStopCatch(true);
        return false;
    }

    std::string raw_text = ReadRowStringColumn(cmd_item, columns.raw);
    if (raw_text.empty()) {
        return false;
    }

    // 读当前显示角色
    for (auto& ac_hit : AcScan(ReadRowStringColumn(cmd_item, 1))) {
        if (ac_hit.kind == MatchKind::character) {
            AddItemScore(result.show_character, ac_hit.canonical, ac_hit.score);
        } else if (ac_hit.kind == MatchKind::term) {
            AddItemScore(result.game_terms, ac_hit.canonical, ac_hit.score);
        }
    }

    for (auto& ac_hit : AcScan(raw_text)) {
        if (ac_hit.kind == MatchKind::character) {
            AddItemScore(result.text_character, ac_hit.canonical, ac_hit.score);
        } else if (ac_hit.kind == MatchKind::term) {
            AddItemScore(result.game_terms, ac_hit.canonical, ac_hit.score);
        }
    }

    *out = CatchTextLabel(
        raw_text,
        true,
        token_prefix,
        result.protect,
        protect_index);
    return !out->empty();
}

static void FlushChoiceBlock(ChoiceBlock& choice, PageParseResult& result) {
    if (choice.brenches.empty()) {
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
            if (!ReadTranslatableText(cmd_item, result, token_prefix, protect_index, &text)) {
                if (stop_catch.load()) return result;
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
            if (!ReadTranslatableText(cmd_item, result, token_prefix, protect_index, &text)) {
                if (stop_catch.load()) return result;
                continue;
            }

            if (pending_choice.brenches.empty()) {
                pending_choice.options_seq = job.label + ":"
                    + std::to_string(page_no) + ":"
                    + std::to_string(i);
            }

            BrenchItem branch;
            branch.option.order.label_index = result.label_index;
            branch.option.order.page_no = page_no;
            branch.option.order.cmd_index = i;
            branch.option.order.sub_index = static_cast<int>(pending_choice.brenches.size());
            branch.option.speaker = "mc";
            branch.option.text = std::move(text);
            branch.target_label = ReadSelectionJumpLabel(cmd_item);

            pending_choice.brenches.emplace_back(std::move(branch));
            ++result.stats.selection;
            continue;
        }

        if (type == "Jump" || type == "JumpRandom" || type == "JumpSubroutine") {
            std::string target = ReadJumpLabel(cmd_item);
            if (!target.empty()) {
                ++result.stats.jump;
            }
            continue;
        }
    }

    FlushChoiceBlock(pending_choice, result);

    result.ok = true;
    return result;
}

static void PageWorkerLoop(
    BlockingQueue<PageParseJob>* jobs,
    BlockingQueue<PageParseResult>* results) {
    PageParseJob job;

    while (jobs->Pop(&job)) {
        if (stop_catch.load()) {
            PageParseResult result;
            result.label_index = job.label_index;
            result.page_index = job.page_index;
            result.page_no = job.page_no;
            results->Push(std::move(result));
            continue;
        }

        results->Push(ParsePageJob(job));
    }
}

static bool ParseScenarioToResult(const RuntimeScenario& scenario, ScenarioParseResult* out_result) {
    if (out_result == nullptr) {
        return false;
    }

    ScenarioParseResult f_result;
    f_result.scene = scenario.result.scene;
    f_result.entry_label = scenario.result.entry_label;

    ParseStats total_stats;

    BlockingQueue<PageParseJob> job_queue;
    BlockingQueue<PageParseResult> result_queue;

    // 启动线程
    int worker_count = static_cast<int>(std::thread::hardware_concurrency());
    if (worker_count <= 0) {
        worker_count = 2;
    }
    worker_count = std::min(worker_count, 4);
    std::vector<std::thread> workers;
    workers.reserve(worker_count);

    for (int i = 0; i < worker_count; ++i) {
        workers.emplace_back(PageWorkerLoop, &job_queue, &result_queue);
    }

    size_t submitted_jobs = 0;

    for (size_t label_index = 0; label_index < scenario.label_order.size(); ++label_index) {
        const std::string& label_name = scenario.label_order[label_index];
        auto it = scenario.labels.find(label_name);
        if (it == scenario.labels.end()) {
            continue;
        }

        const RuntimeLabelNode& label = it->second;
        ListView pages = ReadList(
            read_ptr(label.label_data, g_runtime_config.layout.scenario_label_data.page_data_list),
            8192);
        if (!pages.ok) {
            LOGW("[ScenarioCatcher] page list invalid label=%s", label.label.c_str());
            continue;
        }

        ++total_stats.labels;

        for (int page_index = 0; page_index < pages.size; ++page_index) {
            if (stop_catch.load()) {
                job_queue.Close();
                result_queue.Close();

                for (auto& worker : workers) {
                    if (worker.joinable()) worker.join();
                }

                return false;
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

            if (!job_queue.Push(std::move(job))) {
                LOGE("[ScenarioCatcher] failed to push page job label=%s pageIndex=%d",
                     label.label.c_str(),
                     page_index);
            } else {
                ++submitted_jobs;
            }
        }
    }

    // 关闭队列，等待线程结束
    job_queue.Close();
    for (auto& worker : workers) {
        if (worker.joinable()) worker.join();
    }
    result_queue.Close();

    // 收集结果
    std::vector<PageParseResult> page_results;
    page_results.reserve(submitted_jobs);
    PageParseResult page_result;
    while (result_queue.Pop(&page_result)) {
        page_results.push_back(std::move(page_result));
    }

    // 排序
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

    for (auto& result : page_results) {
        if (!result.ok) {
            continue;
        }

        MergeStats(total_stats, result.stats);

        MergeScores(f_result.speaker_character, result.speaker_character);
        MergeScores(f_result.text_character, result.text_character);
        MergeScores(f_result.show_character, result.show_character);
        MergeScores(f_result.game_terms, result.game_terms);

        MoveAppend(f_result.protect, result.protect);
        MoveAppend(f_result.scene_items, result.scene_items);
    }

    LOGI("[ScenarioCatcher] parsed scene=%s labels=%d/%zu pages=%d text=%d selection=%d jump=%d items=%zu",
         scenario.result.scene.c_str(),
         total_stats.labels,
         scenario.labels.size(),
         total_stats.pages,
         total_stats.text,
         total_stats.selection,
         total_stats.jump,
         f_result.scene_items.size());

    *out_result = std::move(f_result);
    return true;
}

} // namespace

bool CatchScenario(void* scenario_data, const std::string& entry_label) {
    if (stop_catch.load()) {
        return false;
    }

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
        return false;
    }

    BuildLabelOrder(&scenario);
    LOGI("[ScenarioCatcher] scenario=%s entry=%s labels=%zu order=%zu",
         scenario.result.scene.c_str(),
         scenario.result.entry_label.c_str(),
         scenario.labels.size(),
         scenario.label_order.size());

    ScenarioParseResult result;
    if (!ParseScenarioToResult(scenario, &result)) {
        LOGE("[ScenarioCatcher] failed to parse scene entry=%s", entry_label.c_str());
        return false;
    }

    StartSceneBuilder();
    SubmitScenarioParseResult(std::move(result));
    return true;
}
