#include "scene/page_rec.hpp"
#include "scene/scene_identity.hpp"
#include "translation/codec/document_codec.hpp"

#include <chrono>
#include <filesystem>
#include <fstream>
#include <mutex>
#include <unordered_map>
#include <unordered_set>
#include <unistd.h>

namespace {
std::mutex labels_mutex;
std::unordered_map<std::string, std::uint64_t> pending_labels;
std::uint64_t revision = 0;
// Managed objects are never queued to another thread.
std::mutex export_mutex;
std::unordered_map<std::string, std::string> exported_json;
std::filesystem::path export_directory;

bool IsCurrent(std::uint64_t epoch) {
    return stop_reason.load(std::memory_order_acquire) != StopReason::user_pause
        && capture_pause_epoch.load(std::memory_order_acquire) == epoch;
}

bool WriteScene(const Scene& scene, std::uint64_t epoch) {
    std::string json, error;
    het::translation::TranslationRequest unused_request;
    // Only serialize: never submit the request to the translation pipeline.
    if (!het::translation::document_codec::EncodeCapturedScene(
            scene, &json, &unused_request, &error)) {
        LOGE("[PageRec] encode failed scene=%s error=%s", scene.scene.c_str(), error.c_str());
        return false;
    }
    auto previous = exported_json.find(scene.scene);
    if (previous != exported_json.end() && previous->second == json) return IsCurrent(epoch);
    if (g_runtime_config.base_dir.empty()) return false;
    if (export_directory.empty()) {
        const auto time = std::chrono::duration_cast<std::chrono::milliseconds>(
            std::chrono::system_clock::now().time_since_epoch()).count();
        export_directory = std::filesystem::path(g_runtime_config.base_dir) / "page_rec"
            / (std::to_string(time) + "-" + std::to_string(getpid()));
    }
    std::error_code ec;
    std::filesystem::create_directories(export_directory, ec);
    if (ec) {
        LOGE("[PageRec] create directory failed: %s", ec.message().c_str());
        return false;
    }
    const auto destination = export_directory / (scene.scene + ".json");
    const auto temporary = export_directory / (scene.scene + ".json.tmp");
    {
        std::ofstream stream(temporary, std::ios::binary | std::ios::trunc);
        stream.write(json.data(), static_cast<std::streamsize>(json.size()));
        stream.close();
        if (!stream) {
            LOGE("[PageRec] write failed: %s", temporary.c_str());
            std::filesystem::remove(temporary, ec);
            return false;
        }
    }
    // Pause and committing an export have one ordering boundary.
    std::lock_guard<std::mutex> transition(capture_transition_mutex);
    if (!IsCurrent(epoch)) {
        std::filesystem::remove(temporary, ec);
        return false;
    }
    std::filesystem::rename(temporary, destination, ec);
    if (ec) {
        LOGE("[PageRec] commit failed: %s", ec.message().c_str());
        return false;
    }
    exported_json[scene.scene] = std::move(json);
    LOGI("[PageRec] exported scene=%s raw_lang=%s path=%s",
         scene.scene.c_str(), scene.raw_lang.c_str(), destination.c_str());
    return true;
}
} // namespace

// No background workers to wake or managed references to clear.
void NotifyPageRecStopChanged() {}

void ClearPageRecOnPause() {
    std::lock_guard<std::mutex> lock(labels_mutex);
    pending_labels.clear();
}

bool CommandExamine(void* page_data, const std::string&) {
    const auto epoch = capture_pause_epoch.load(std::memory_order_acquire);
    if (!g_runtime_config.enable_page_rec_debug || !IsCurrent(epoch) || !valid_ptr(page_data)) return false;
    const auto& layout = g_runtime_config.layout;
    void* label_data = read_ptr(page_data, layout.adv_scenario_page_data.scenario_label_data);
    if (!valid_ptr(label_data)) return false;
    std::string label = read_il2cpp_string(read_ptr(label_data, layout.scenario_label_data.scenario_label));
    if (label.empty()) return false;
    std::lock_guard<std::mutex> lock(labels_mutex);
    if (!IsCurrent(epoch)) return false;
    pending_labels[std::move(label)] = ++revision;
    return true;
}

void ExportPageRecScenarios(
    void* current_scenario, const std::string& entry_label,
    std::uint64_t epoch, const std::function<void*(const std::string&)>& resolve) {
    // Do not recursively enter the exporter or retain its manager.
    static thread_local bool exporting = false;
    if (exporting || !IsCurrent(epoch)) return;
    exporting = true;
    struct Reset { bool& value; ~Reset() { value = false; } } reset{exporting};
    std::lock_guard<std::mutex> export_lock(export_mutex);
    if (!IsCurrent(epoch)) return;
    std::unordered_map<std::string, std::uint64_t> snapshot;
    {
        std::lock_guard<std::mutex> lock(labels_mutex);
        snapshot = pending_labels;
    }
    std::unordered_set<std::string> covered;
    std::unordered_set<std::string> attempted;
    auto capture = [&](void* data, const std::string& entry) {
        if (!valid_ptr(data) || !IsCurrent(epoch)) return;
        const std::string name = read_il2cpp_string(
            read_ptr(data, g_runtime_config.layout.adv_scenario_data.name));
        if (!het::translation::scene_identity::IsValid(name)) return;
        // A failed bucket can contain hundreds of initialized labels. Retry
        // once on the next lookup, not once per label in the same sweep.
        if (!attempted.insert(name).second) return;
        Scene scene;
        std::vector<std::string> labels;
        if (!ParsePageRecScene(data, entry, &scene, &labels) || !WriteScene(scene, epoch)) {
            LOGW("[PageRec] export deferred scene=%s; retry on next lookup", name.c_str());
            return;
        }
        std::lock_guard<std::mutex> lock(labels_mutex);
        for (const auto& label : labels) {
            covered.insert(label);
            auto old = snapshot.find(label);
            auto pending = pending_labels.find(label);
            if (old != snapshot.end() && pending != pending_labels.end()
                && pending->second == old->second) pending_labels.erase(pending);
        }
    };
    capture(current_scenario, entry_label);
    for (const auto& entry : snapshot) {
        if (!IsCurrent(epoch)) break;
        if (covered.count(entry.first)) continue;
        void* scenario = resolve(entry.first);
        if (valid_ptr(scenario)) capture(scenario, entry.first);
        else LOGW("[PageRec] unresolved label=%s; retained for next lookup", entry.first.c_str());
    }
}
