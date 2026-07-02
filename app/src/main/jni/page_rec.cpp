#include "housamo.hpp"
#include <deque>
#include <thread>
#include <unordered_set>
#include <utility>
#include <mutex>

struct PageKey {
    uintptr_t page_data = 0;
    uintptr_t command_list = 0;
    int page_no = -1;

    bool operator==(const PageKey& other) const {
        return page_data == other.page_data
            && command_list == other.command_list
            && page_no == other.page_no;
    }
};
struct PageKeyHash {
    std::size_t operator()(const PageKey& key) const noexcept {
        std::size_t h1 = std::hash<uintptr_t>{}(key.page_data);
        std::size_t h2 = std::hash<uintptr_t>{}(key.command_list);
        std::size_t h3 = std::hash<int>{}(key.page_no);

        return h1 ^ (h2 << 1) ^ (h3 << 2);
    }
};

struct PageEvent {
    void* page_data = nullptr;
    void* command_list = nullptr;
    int page_no = -1;
    std::string source;
};

struct PageJob {
    uint64_t seq = 0;
    PageKey key;
    void* page_data = nullptr;
    void* command_list = nullptr;
    int page_no = -1;
    std::string source;
};

struct SelectionParseItem {
    TextItem option;
    std::string target_label;
};

using TextGroup = std::vector<TextItem>;
using SelectionGroup = std::vector<SelectionParseItem>;

struct ProcessPageResult {
    uint64_t page_seq = 0;
    OrderKey order;
    std::string current_label;
    std::vector<JumpItem> exit_labels;
    std::vector<std::string> characters;
    std::vector<ProtectedToken> protect;
    std::vector<AcHit> ac_hits;

    using PageItem = std::variant<
        std::monostate,
        TextGroup,
        SelectionGroup
    >;
    PageItem items;
};

static constexpr int kPageWorkerCount = 2;
static std::once_flag page_recorder_once;

static std::mutex event_mutex;
static std::mutex page_mutex;

static std::deque<PageEvent> event_queue;
static std::deque<PageJob> page_queue;

static std::condition_variable event_cv;
static std::condition_variable page_cv;

static std::atomic<uint64_t> next_seq{0};
static std::atomic<int> protect_label{0}; 
static std::unordered_set<PageKey, PageKeyHash> seen_pages;

static bool IsCapturePaused() {
    return stop_reason.load(std::memory_order_acquire) == StopReason::existing_scene;
}

class PageParser {
public:
    explicit PageParser(ProcessPageResult& result) : result_(result) {}

    bool Parse(const PageJob& job) {
        if (!valid_ptr(job.command_list)) {
            return false;
        }

        int select_seq = 0; // 用于同一个Selection内的选项区分

        const auto& layout = g_runtime_config.layout;
        const auto& list = layout.il2cpp_list;
        const auto& array = layout.il2cpp_array;
        const auto& cmd = layout.adv_command;
        const auto& page = layout.adv_scenario_page_data;
        const auto& scenario = layout.scenario_label_data;

        result_.page_seq = job.seq;
        result_.order.label_index = -1;
        result_.order.page_no = job.page_no;

        void* label_data = read_ptr(job.page_data, page.scenario_label_data);
        void* label_ptr = read_ptr(label_data, scenario.scenario_label);
        result_.current_label = read_il2cpp_string(label_ptr);

        int list_size = read_int(job.command_list, list.size);
        if (list_size <= 0 || list_size > 4096) {
            return false;
        }

        void* cmd_array = read_ptr(job.command_list, list.items);
        if (!valid_ptr(cmd_array)) {
            return false;
        }

        TextGroup texts;
        SelectionGroup selections;
        std::string current_speaker;

        for (int i = 0; i < list_size; i++) {
            void* cmd_item = read_ptr(cmd_array, array.first_element + i * array.pointer_size);
            if (!valid_ptr(cmd_item)) continue;

            void* cmd_type_ptr = read_ptr(cmd_item, cmd.type);
            std::string cmd_type = read_il2cpp_string(cmd_type_ptr);

            if (cmd_type == "Character") {
                current_speaker = GetNameItem(cmd_item);
                continue;
            } else if (cmd_type == "CharacterOff") { // characteroff 控制当前说话角色
                current_speaker.clear();
                continue;
            } else if (cmd_type == "Text") {
                TextItem text_item;
                std::string raw_text = GetTextItem(cmd_item);
                if (raw_text.empty()) continue;

                text_item.order.label_index = -1;
                text_item.order.page_no = job.page_no;
                text_item.order.cmd_index = i;
                text_item.order.sub_index = 0;

                text_item.speaker = current_speaker;
                AddUniqueName(current_speaker);
                text_item.text = std::move(raw_text);

                texts.push_back(std::move(text_item));
                continue;
            } else if (cmd_type == "Selection") {
                SelectionParseItem item = GetSelectItem(cmd_item);
                if (item.option.text.empty() || item.target_label.empty()) continue;

                item.option.order.label_index = -1;
                item.option.order.page_no = job.page_no;
                item.option.order.cmd_index = i;
                item.option.order.sub_index = ++select_seq;

                selections.push_back(std::move(item));
                continue;
            } else if (cmd_type == "Jump") {
                JumpItem item = GetJumpItem(cmd_item);
                result_.exit_labels.push_back(std::move(item));
                continue;
            }
        }

        if (!selections.empty()) {
            result_.items = std::move(selections);
        } else if (!texts.empty()) {
            result_.items = std::move(texts);
        } else {
            result_.items = std::monostate{};
        }

        return true;
    }

private:
    static bool IsTag(const std::string& raw, size_t i) {
        if (raw[i] != '<') return false;
        if (i + 1 >= raw.size()) return false;

        unsigned char next = static_cast<unsigned char>(raw[i + 1]);

        return next == '/'
            || (next >= 'A' && next <= 'Z')
            || (next >= 'a' && next <= 'z');
    }

    std::string CatchTextLabel(const std::string& raw, bool replace_mode) {
        if (raw.empty()) {
            return "";
        }

        std::string out;
        out.reserve(raw.size());

        bool have_start = false;
        size_t label_start = 0;

        for(size_t i = 0; i < raw.size(); ++i) {
            char c = raw[i];

            if (!have_start) {
                if (IsTag(raw, i)) {
                    have_start = true;
                    label_start = i;
                } else {
                    out.push_back(c);
                }
                continue;
            }

            if (c == '>' && have_start) {
                std::string tag = raw.substr(label_start, i - label_start + 1);

                if (replace_mode) {
                    ProtectedToken token;
                    token.label = "__HET__PT_" + std::to_string(protect_label.fetch_add(1)) + "__";
                    token.origin = std::move(tag);

                    out += token.label;
                    result_.protect.push_back(std::move(token));
                }

                have_start = false;
            }
        }

        if (have_start) {
            // 处理不完整标签的情况，直接把剩余部分加入输出
            out += raw.substr(label_start);
        }

        return out;
    }

    void AddUniqueName(const std::string& name) {
        if (name.empty()) return;
        if (name == "mc") return;

        for (const auto& existing : result_.characters) {
            if (existing == name) return;
        }

        result_.characters.push_back(name);
    }

    std::string ReadRowStringColumn(void* cmd_item, const int& column_index = -1) {
        if (column_index < 0) {
            return "";
        }

        const auto& layout = g_runtime_config.layout;
        const auto& cmd = layout.adv_command;
        const auto& row = layout.string_grid_row;
        const auto& array = layout.il2cpp_array;

        void* rowData = read_ptr(cmd_item, cmd.row_data);
        void* stringsPtr = read_ptr(rowData, row.strings);

        int text_len = read_int(stringsPtr, array.length);

        if (text_len <= column_index || text_len > 4096) {
            return "";
        }

        void* rawTextPtr = read_ptr(stringsPtr, array.first_element + column_index * array.pointer_size);
        if (!valid_ptr(rawTextPtr)) {
            return "";
        }

        std::string raw_text = read_il2cpp_string(rawTextPtr);
        if (raw_text.empty()) {
            return "";
        }

        return raw_text;
    }

    JumpItem GetJumpItem(void* cmd_item) {
        const auto& layout = g_runtime_config.layout;
        const auto& jump = layout.adv_command_jump;

        JumpItem item;
        void* jumpLabelPtr = read_ptr(cmd_item, jump.jump_label);
        if (!valid_ptr(jumpLabelPtr)) {
            return item;
        }

        item.target = read_il2cpp_string(jumpLabelPtr);
        item.condition = ReadRowStringColumn(cmd_item, jump.condition_column);

        return item;
    }

    std::string GetTextItem(void* cmd_item) {
        const auto& column = g_runtime_config.layout.text_columns;

        std::string cn_text = ReadRowStringColumn(cmd_item, column.zh_cn);
        if (!cn_text.empty()) {
            LOGW("[ProcessPageJob] Already has translation");
            return "";
        }

        std::string raw_text = ReadRowStringColumn(cmd_item, column.raw);
        std::string out = CatchTextLabel(raw_text, true);

        auto ac_hits = AcScan(CatchTextLabel(raw_text, false));
        result_.ac_hits.insert(result_.ac_hits.end(), ac_hits.begin(), ac_hits.end());

        return out;
    }

    std::string GetNameItem(void* cmd_item) {
        const auto& layout = g_runtime_config.layout;
        const auto& character = layout.adv_command_character;

        void* charInfo = read_ptr(cmd_item, character.character_info);
        if (!valid_ptr(charInfo)) {
            return "";
        }

        void* nameText = read_ptr(charInfo, character.name_text);
        if (!valid_ptr(nameText)) {
            return "";
        }

        return read_il2cpp_string(nameText);
    }

    SelectionParseItem GetSelectItem(void* cmd_item) {
        const auto& layout = g_runtime_config.layout;
        const auto& selection = layout.adv_command_selection;
        const auto& column = layout.text_columns;
        SelectionParseItem item;

        void* jumpLabelPtr = read_ptr(cmd_item, selection.jump_label);

        std::string cn_text = ReadRowStringColumn(cmd_item, column.zh_cn);
        if (!cn_text.empty()) {
            LOGW("[ProcessPageJob] Already has translation");
            return item;
        }

        std::string raw_text = ReadRowStringColumn(cmd_item, column.raw);
        std::string out = CatchTextLabel(raw_text, true);

        auto ac_hits = AcScan(std::move(CatchTextLabel(raw_text, false)));
        result_.ac_hits.insert(result_.ac_hits.end(), ac_hits.begin(), ac_hits.end());

        item.option.text = out;
        item.option.speaker = "mc"; // 选项通常没有说话人，这里用一个固定值占位
        item.target_label = read_il2cpp_string(jumpLabelPtr);

        return item;
    }

private:
    ProcessPageResult& result_;
};

void NotifyPageRecStopChanged() {
    event_cv.notify_all();
    page_cv.notify_all();
}

static bool ProcessPageJob(const PageJob& job, ProcessPageResult* out = nullptr) {
    if (out == nullptr) {
        return false;
    }
    PageParser parser(*out);
    return parser.Parse(job);
}

static PageKey MakePageKey(void* pageData, void* commandList, int pageNo) {
    // 构造PageKey, 用于去重和识别唯一页面
    PageKey key;
    key.page_data = reinterpret_cast<uintptr_t>(pageData);
    key.command_list = reinterpret_cast<uintptr_t>(commandList);
    key.page_no = pageNo;
    return key;
};

static void PageEventWorker() {
    // 这个线程专门处理来自Hook的页面事件，进行去重和初步处理
    while (true) {
        PageEvent event;
        {
            std::unique_lock<std::mutex> lock(event_mutex);

            event_cv.wait(lock, [] {
                return IsCapturePaused() || !event_queue.empty();
            });

            if (IsCapturePaused()) {
                event_queue.clear();
                event_cv.wait(lock, [] {
                    return !IsCapturePaused();
                });
                continue;
            }

            event = event_queue.front();
            event_queue.pop_front();
        }
        PageKey key = MakePageKey(event.page_data, event.command_list, event.page_no);
        PageJob job;

        {
            std::lock_guard<std::mutex> lock(page_mutex);
            auto res = seen_pages.insert(key);
            if (!res.second) continue;

            job.seq = next_seq.fetch_add(1);
            job.key = key;
            job.page_data = event.page_data;
            job.command_list = event.command_list;
            job.page_no = event.page_no;
            job.source = event.source;

            page_queue.push_back(job);
        }

        // 通知PageWorker有新任务
        page_cv.notify_one();
    }
}

static void PageWorker() {
    // 这个线程专门处理PageJob，进行文本提取和翻译等后续工作
    while (true) {
        PageJob job;
        ProcessPageResult out;

        {
            std::unique_lock<std::mutex> lock(page_mutex);

            page_cv.wait(lock, [] {
                return IsCapturePaused() || !page_queue.empty();
            });

            if (IsCapturePaused()) {
                page_queue.clear();
                page_cv.wait(lock, [] {
                    return !IsCapturePaused();
                });
                continue;
            }

            job = page_queue.front();
            page_queue.pop_front();
        }

        if (ProcessPageJob(job, &out)) {
            LOGI("[PageRecDebug] parsed seq=%llu label=%s pageNo=%d source=%s",
                 static_cast<unsigned long long>(out.page_seq),
                 out.current_label.c_str(),
                 out.order.page_no,
                 job.source.c_str());
        } else {
            LOGE("[PageWorker] failed to parse page job seq=%llu pageNo=%d", static_cast<unsigned long long>(job.seq), job.page_no);
        }
    }
};

static void StartPageRecorder() {
    // 确保PageEventWorker只启动一次
    std::call_once(page_recorder_once, [] {
        std::thread(PageEventWorker).detach();
        for (int i = 0; i < kPageWorkerCount; ++i) {
            std::thread(PageWorker).detach();
        }
        LOGI("[PageRec] worker started page_workers=%d", kPageWorkerCount);
    });
}

bool CommandExamine(void* pageData, const std::string& source) {
    if (IsCapturePaused()) {
        return false;
    }

    // 这个函数由Hook调用，每次页面事件发生时被调用
    StartPageRecorder();

    if (!valid_ptr(pageData)) {
        LOGE("[CommandExamine] pageData is nullptr!"); 
        return false;
    };

    const auto& layout = g_runtime_config.layout;
    const auto& page_data = layout.adv_scenario_page_data;

    void* command_list = read_ptr(pageData, page_data.command_list);
    int page_no = read_int(pageData, page_data.page_no);

    if (!valid_ptr(command_list)) {
        LOGE("[CommandExamine] command_list is nullptr!");
        return false;
    }

    PageEvent event;
    event.page_data = pageData;
    event.command_list = command_list;
    event.page_no = page_no;
    event.source = source;

    {
        std::lock_guard<std::mutex> lock(event_mutex);
        event_queue.push_back(event);
    }
    
    // 通知PageEventWorker有新事件
    event_cv.notify_one();

    return true;
}
