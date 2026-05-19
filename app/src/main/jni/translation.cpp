#include "housamo.hpp"
#include <thread>
#include <chrono>
#include <map>
#include <mutex>
#include <queue>
#include <string>

extern std::map<std::string, std::string> s_cache;
extern std::mutex s_cache_mutex;

static std::queue<std::string> s_queue;
static std::thread* s_worker = nullptr;
static bool s_running = false;

static std::string ai_translate(const std::string& jp) {
    // TODO: 接入真实 AI API
    return "[待翻译] " + jp;
}

static void worker_loop() {
    s_running = true;
    LOGI("[Worker] started");
    while (s_running) {
        std::string text;
        {
            std::lock_guard<std::mutex> lk(s_cache_mutex);
            if (!s_queue.empty()) { text = s_queue.front(); s_queue.pop(); }
        }
        if (text.empty()) { std::this_thread::sleep_for(std::chrono::milliseconds(200)); continue; }

        std::string result = ai_translate(text);
        {
            std::lock_guard<std::mutex> lk(s_cache_mutex);
            s_cache[text] = result;
        }
        LOGI("[Worker] cached: %s", text.substr(0, 40).c_str());
    }
    LOGI("[Worker] stopped");
}

void transl_worker_start() {
    if (s_worker) return;
    s_worker = new std::thread(worker_loop);
}

void transl_worker_stop() {
    s_running = false;
    if (s_worker) { s_worker->join(); delete s_worker; s_worker = nullptr; }
}
