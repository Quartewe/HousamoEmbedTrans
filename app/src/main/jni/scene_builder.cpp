#include "housamo.hpp"
#include <mutex>
#include <string>
#include <deque>
#include <atomic>
#include <thread>
#include <unordered_set>

struct CharCount {
    std::string character;
    double count = 0.0;
};

struct SceneDelta {
    ProcessPageResult result;
    std::vector<std::pair<std::string, double>> character_scores;
};

struct SceneBuildState {
    std::unordered_map<std::string, std::vector<ProcessPageResult>> labels;
    std::vector<std::string> label_order;
    std::vector<CharCount> characters;
    std::unordered_map<std::string, size_t> character_index;
};

class SceneBuilder {
    public:
        void Start() {
            std::call_once(start_once_, [this] {
                constexpr int worker_count = 2;

                for (int i = 0; i < worker_count; ++i) {
                    std::thread(&SceneBuilder::WorkerLoop, this).detach();
                }

                LOGI("[SceneBuilder] started workers=%d", worker_count);
            });
        }

        void Submit(ProcessPageResult result) {
            {
                std::lock_guard<std::mutex> lock(queue_mutex_);
                queue_.push_back(std::move(result));
            }
            queue_cv_.notify_one();
        };

        void NotifyStopChanged() {
            queue_cv_.notify_all();
        }

    private:
        bool TakeResult(ProcessPageResult* out) {
            std::unique_lock<std::mutex> lock(queue_mutex_);

            queue_cv_.wait(lock, [this] {
                return stop_catch.load() || !queue_.empty();
            });

            if (stop_catch.load()) {
                queue_.clear();

                queue_cv_.wait(lock, [] {
                    return !stop_catch.load();
                });

                return false;
            }

            *out = std::move(queue_.front());
            queue_.pop_front();
            return true;
        }

        SceneDelta BuildDelta(ProcessPageResult result) {
            SceneDelta delta;

            for (const auto& name : result.characters) {
                if (!name.empty() && name != "mc") {
                    delta.character_scores.emplace_back(name, 10.0);
                }
            }

            for (const auto& hit : result.ac_hits) {
                if (hit.kind == MatchKind::character && !hit.text.empty()) {
                    delta.character_scores.emplace_back(hit.text, hit.score);
                }
            }

            delta.result = std::move(result);
            return delta;
        }

        void MergeDelta(SceneDelta delta) {
            std::lock_guard<std::mutex> lock(state_mutex_);

            for (const auto& [name, score] : delta.character_scores) {
                AddCharacterScoreLock(name, score);
            }

            AddPageResultLock(std::move(delta.result));
        }

        void AddCharacterScoreLock(const std::string& name, double score) {
            // 调用前要持锁
            if (name.empty() || name == "mc") return;

            auto [it, inserted] = state_.character_index.try_emplace(
                name,
                state_.characters.size()
            );

            if (inserted) {
                CharCount item;
                item.character = name;
                item.count = score;
                state_.characters.push_back(std::move(item));
                return;
            }

            state_.characters[it->second].count += score;
        }

        void AddPageResultLock(ProcessPageResult result) {
            // 调用前要持锁
            const std::string& label = result.current_label;
            if (label.empty()) return;

            auto [it, inserted] = state_.labels.try_emplace(label);
            if (inserted) {
                state_.label_order.push_back(label);
            }

            it->second.push_back(std::move(result));
        }

        void WorkerLoop() {
            while (true) {
                ProcessPageResult result;

                if (!TakeResult(&result)) {
                    continue;
                }

                SceneDelta delta = BuildDelta(std::move(result));
                MergeDelta(std::move(delta));
            }
        }

    private:
        std::mutex queue_mutex_;
        std::condition_variable queue_cv_;
        std::deque<ProcessPageResult> queue_;
        std::once_flag start_once_;

        std::mutex state_mutex_;
        SceneBuildState state_;
};

static SceneBuilder g_scene_builder;

void NotifySceneBuilderStopChanged() {
    g_scene_builder.NotifyStopChanged();
}

void SubmitPageResult(ProcessPageResult result) {
    g_scene_builder.Submit(std::move(result));
}

void StartSceneBuilder() {
    g_scene_builder.Start();
}
