#include <condition_variable>
#include <unordered_set>
#include "housamo.hpp"
#include <cinttypes>
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

static std::once_flag page_recorder_once;

static std::mutex event_mutex;
static std::mutex page_mutex;

static std::deque<PageEvent> event_queue;
static std::deque<PageJob> page_queue;

static std::condition_variable event_cv;
static std::condition_variable page_cv;

static std::atomic<uint64_t> next_seq{0};
static std::unordered_set<PageKey, PageKeyHash> seen_pages;

static bool ParsePageJob(const PageJob job) {
    int list_size = read_int(job.command_list, 0x18);
    if (list_size <= 0 || list_size > 4096) {
        LOGE("[ParsePageJob] Invalid command list size: %d", list_size);
        return false;
    }
    void* cmd_array = read_ptr(job.command_list, 0x10);
    if (!valid_ptr(cmd_array)) { return false; }
    LOGI("[ParsePageJob] ---- Page No: %d ---- \n[ParsePageJob] - Command List Size: %d", job.page_no, list_size);
    for (int i = 0; i < list_size; i++) {
        void* cmd_item = read_ptr(cmd_array,0x20 + i * 0x8);
        void* cmd_type = read_ptr(cmd_item, 0x20);
        std::string type_str = read_il2cpp_string(cmd_type);
        LOGI("[ParsePageJob] Command %d: Type = %s", i, type_str.c_str());
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

            event_cv.wait(lock, [] {return !event_queue.empty(); });

            event = event_queue.front();
            event_queue.pop_front();
        }
        PageKey key = MakePageKey(event.page_data, event.command_list, event.page_no);
        PageJob job;

        {
            std::lock_guard<std::mutex> lock(page_mutex);
            auto res = seen_pages.insert(key);
            if (!res.second) {
                LOGD(
                    "[PageEventWorker] duplicated page pageNo=%d",
                    event.page_no
                );
                continue;
            }

            job.seq = next_seq.fetch_add(1);
            job.key = key;
            job.page_data = event.page_data;
            job.command_list = event.command_list;
            job.page_no = event.page_no;
            job.source = event.source;

            page_queue.push_back(job);
        }
        LOGI("[PageEventWorker] enqueue seq=%llu pageNo=%d source=%s", static_cast<unsigned long long>(job.seq), job.page_no, job.source.c_str());

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
                return !page_queue.empty();
            });

            job = page_queue.front();
            page_queue.pop_front();
        }

        LOGI(
            "[PageWorker] processing seq=%llu pageNo=%d source=%s",
            static_cast<unsigned long long>(job.seq),
            job.page_no,
            job.source.c_str()
        );

        if (!ParsePageJob(job)) {
            LOGE("[PageWorker] failed to parse page job seq=%llu pageNo=%d", static_cast<unsigned long long>(job.seq), job.page_no);
        } else {
            LOGI("[PageWorker] successfully parsed page job seq=%llu pageNo=%d", static_cast<unsigned long long>(job.seq), job.page_no);
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
    // 这个函数由Hook调用，每次页面事件发生时被调用
    StartPageRecorder();

    if (!valid_ptr(pageData)) {
        LOGE("[CommandExamine] pageData is nullptr!"); 
        return false;
    };

    int page_no = read_int(pageData, 0x38);
    void* command_list = read_ptr(pageData, 0x10);

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