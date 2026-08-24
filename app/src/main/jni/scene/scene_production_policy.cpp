#include "scene_production_policy.hpp"

#include "scene_identity.hpp"

#include <algorithm>
#include <cassert>

namespace het::scene_sync {

SceneProductionPolicyStore::SceneProductionPolicyStore()
    : policy_(std::make_shared<const SceneProductionPolicy>()) {}

std::shared_ptr<const SceneProductionPolicy>
SceneProductionPolicyStore::Load() const {
    return std::atomic_load_explicit(&policy_, std::memory_order_acquire);
}

bool SceneProductionPolicyStore::BeginSyncHold() {
    std::lock_guard<std::mutex> lock(writer_mutex_);
    auto current = Load();
    auto next = std::make_shared<SceneProductionPolicy>(*current);
    next->sync_worker_hold = true;
    std::atomic_store_explicit(
        &policy_,
        std::shared_ptr<const SceneProductionPolicy>(std::move(next)),
        std::memory_order_release
    );
    return true;
}

bool SceneProductionPolicyStore::ReplaceBlockedScenes(
    const std::vector<std::string>& scene_names
) {
    std::lock_guard<std::mutex> lock(writer_mutex_);
    if (scene_names.size() > 65536U) {
        // A malformed update must not leave a previous hold/list active.
        auto fail_open = std::make_shared<const SceneProductionPolicy>();
        std::atomic_store_explicit(
            &policy_,
            std::move(fail_open),
            std::memory_order_release
        );
        return false;
    }

    auto next = std::make_shared<SceneProductionPolicy>();
    next->sync_worker_hold = false;
    for (const std::string& scene_name : scene_names) {
        if (!translation::scene_identity::IsValid(scene_name)
            || !next->blocked_scenes.insert(scene_name).second) {
            // Treat any invalid/duplicate replacement as a failed control
            // plane update and fail open rather than retaining stale blocks.
            auto fail_open = std::make_shared<const SceneProductionPolicy>();
            std::atomic_store_explicit(
                &policy_,
                std::move(fail_open),
                std::memory_order_release
            );
            return false;
        }
    }
    std::atomic_store_explicit(
        &policy_,
        std::shared_ptr<const SceneProductionPolicy>(std::move(next)),
        std::memory_order_release
    );
    return true;
}

void SceneProductionPolicyStore::FailOpen() {
    std::lock_guard<std::mutex> lock(writer_mutex_);
    auto next = std::make_shared<const SceneProductionPolicy>();
    std::atomic_store_explicit(
        &policy_,
        std::move(next),
        std::memory_order_release
    );
}

SceneProductionLease SceneProductionPolicyStore::TryEnter(
    const std::string& scene_name
) {
    if (!translation::scene_identity::IsValid(scene_name)) {
        return SceneProductionLease(
            this,
            false,
            RejectReason::invalid_scene_name
        );
    }

    auto current = Load();
    if (current->sync_worker_hold) {
        return SceneProductionLease(this, false, RejectReason::sync_worker_hold);
    }
    if (current->blocked_scenes.find(scene_name)
        != current->blocked_scenes.end()) {
        return SceneProductionLease(this, false, RejectReason::scene_blocked);
    }

    active_count_.fetch_add(1, std::memory_order_acq_rel);

#if defined(HET_SCENE_PRODUCTION_POLICY_TEST)
    AdmissionProbe probe = admission_probe_.load(std::memory_order_acquire);
    if (probe != nullptr) {
        probe(this);
    }
#endif

    // The second gate closes the race where export/policy replacement lands
    // after the first read but before this production scope is counted.
    current = Load();
    if (current->sync_worker_hold) {
        Leave();
        return SceneProductionLease(this, false, RejectReason::sync_worker_hold);
    }
    if (current->blocked_scenes.find(scene_name)
        != current->blocked_scenes.end()) {
        Leave();
        return SceneProductionLease(this, false, RejectReason::scene_blocked);
    }

    return SceneProductionLease(this, true, RejectReason::none);
}

void SceneProductionPolicyStore::WaitForActiveZero() {
    std::unique_lock<std::mutex> lock(active_mutex_);
    active_cv_.wait(lock, [this]() {
        return active_count_.load(std::memory_order_acquire) == 0;
    });
}

void SceneProductionPolicyStore::Leave() {
    const int previous = active_count_.fetch_sub(1, std::memory_order_acq_rel);
    if (previous <= 0) {
        assert(false && "SceneProductionLease released more than once");
        // Undo this invalid decrement without overwriting a concurrent
        // TryEnter increment that may have happened after fetch_sub.
        active_count_.fetch_add(1, std::memory_order_acq_rel);
        return;
    }
    if (previous == 1) {
        std::lock_guard<std::mutex> lock(active_mutex_);
        active_cv_.notify_all();
    }
}

void SceneProductionLease::Release() {
    if (owner_ != nullptr && allowed_) {
        owner_->Leave();
    }
    owner_ = nullptr;
    allowed_ = false;
}

SceneProductionPolicyStore g_scene_production_policy;

}  // namespace het::scene_sync

bool BeginSceneSyncHold() {
    return het::scene_sync::g_scene_production_policy.BeginSyncHold();
}

bool ReplaceBlockedScenes(const std::vector<std::string>& scene_names) {
    return het::scene_sync::g_scene_production_policy.ReplaceBlockedScenes(
        scene_names
    );
}

void ResetSceneProductionPolicy() {
    het::scene_sync::g_scene_production_policy.FailOpen();
}

het::scene_sync::SceneProductionLease EnterSceneProduction(
    const std::string& scene_name
) {
    return het::scene_sync::g_scene_production_policy.TryEnter(scene_name);
}
