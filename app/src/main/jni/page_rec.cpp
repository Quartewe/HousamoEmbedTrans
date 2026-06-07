#include <condition_variable>
#include <unordered_set>
#include "housamo.hpp"
#include <atomic>
#include <string>
#include <deque>
#include <thread>
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

std::atomic<bool> stop_catch{false};
static std::once_flag page_recorder_once;

static std::mutex event_mutex;
static std::mutex page_mutex;

static std::deque<PageEvent> event_queue;
static std::deque<PageJob> page_queue;

static std::condition_variable event_cv;
static std::condition_variable page_cv;

static std::atomic<uint64_t> next_seq{0};
static std::unordered_set<PageKey, PageKeyHash> seen_pages;

void SetStopCatch(bool stop) {
    stop_catch.store(stop);

    event_cv.notify_all();
    page_cv.notify_all();
}

static std::string GetTextItem(void* cmd_item) {
    const auto& layout = g_runtime_config.layout;
    const auto& cmd = layout.adv_command;
    const auto& row = layout.string_grid_row;
    const auto& array = layout.il2cpp_array;
    const auto& column = layout.text_columns;

    void* rowData = read_ptr(cmd_item, cmd.row_data);
    void* stringsPtr = read_ptr(rowData, row.strings);

    int text_len = read_int(stringsPtr, array.length);

    if (text_len <= column.raw || text_len > 4096) {
        return "";
    }
    
    void* zhTextPtr = read_ptr(stringsPtr, array.first_element + column.zh_cn * array.pointer_size);
    std::string zh_text = read_il2cpp_string(zhTextPtr);

    void* rawTextPtr = read_ptr(stringsPtr, array.first_element + column.raw * array.pointer_size);
    if (!valid_ptr(rawTextPtr)) {
        return "";
    }
    std::string raw_text = read_il2cpp_string(rawTextPtr);
    
    if (!zh_text.empty() && !raw_text.empty()) {
        SetStopCatch(true);
        LOGI("[PageTextCandidate] Already have translation");
        return "";
    }

    return raw_text;
}

static std::string GetNameItem(void* cmd_item) {
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

static bool ProcessPageJob(const PageJob& job) {
    if (!valid_ptr(job.command_list)) {
        return false;
    }

    const auto& layout = g_runtime_config.layout;
    const auto& list = layout.il2cpp_list;
    const auto& array = layout.il2cpp_array;
    const auto& cmd = layout.adv_command;

    int list_size = read_int(job.command_list, list.size);
    if (list_size <= 0 || list_size > 4096) {
        return false;
    }

    void* cmd_array = read_ptr(job.command_list, list.items);
    if (!valid_ptr(cmd_array)) {
        return false;
    }

    bool have_text = false;

    for (int i = 0; i < list_size; i++) {
        void* cmd_item = read_ptr(cmd_array,array.first_element + i * array.pointer_size);
        if (!valid_ptr(cmd_item)) continue;
        void* cmd_type_ptr = read_ptr(cmd_item, cmd.type);
        std::string cmd_type = read_il2cpp_string(cmd_type_ptr);
        
        if (cmd_type == "Text") {
            continue;
        } else if (cmd_type == "Selection") {
            continue;
        } else if (cmd_type == "Jump") {
            continue;
        } else if (cmd_type == "Character") {
            continue;
        }
    }

    return true;
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
                return stop_catch.load() || !event_queue.empty();
            });

            if (stop_catch.load()) {
                event_queue.clear();
                event_cv.wait(lock, [] {
                    return !stop_catch.load();
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
        {
            std::unique_lock<std::mutex> lock(page_mutex);

            page_cv.wait(lock, [] {
                return stop_catch.load() || !page_queue.empty();
            });

            if (stop_catch.load()) {
                page_queue.clear();
                page_cv.wait(lock, [] {
                    return !stop_catch.load();
                });
                continue;
            }

            job = page_queue.front();
            page_queue.pop_front();
        }

        if (!ProcessPageJob(job)) {
            LOGE("[PageWorker] failed to parse page job seq=%llu pageNo=%d", static_cast<unsigned long long>(job.seq), job.page_no);
        }
    }
};

static void StartPageRecorder() {
    // 确保PageEventWorker只启动一次
    std::call_once(page_recorder_once, [] {
        std::thread(PageEventWorker).detach();
        std::thread(PageWorker).detach();
        LOGI("[PageRec] worker started");
    });
}

bool CommandExamine(void* pageData, const std::string& source) {
    if (stop_catch.load()) {
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
