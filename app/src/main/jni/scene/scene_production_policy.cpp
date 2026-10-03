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
    next->sync_worker_drain = false;
    ++next->epoch;
    drained_policy_.reset();
    std::atomic_store_explicit(
        &policy_,
        std::shared_ptr<const SceneProductionPolicy>(std::move(next)),
        std::memory_order_release
    );
    return true;
}

bool SceneProductionPolicyStore::BeginSyncDrain() {
    std::lock_guard<std::mutex> lock(writer_mutex_);
    auto current = Load();
    // Never weaken a startup/explicit hold or replace an already pending drain.
    if (current->sync_worker_hold || current->sync_worker_drain) return true;
    auto held = std::make_shared<SceneProductionPolicy>(*current);
    held->sync_worker_hold = true;
    if (active_count_.load(std::memory_order_acquire) == 0) {
        std::atomic_store_explicit(
            &policy_, std::shared_ptr<const SceneProductionPolicy>(std::move(held)),
            std::memory_order_release);
    } else {
        auto draining = std::make_shared<SceneProductionPolicy>(*current);
        draining->sync_worker_drain = true;
        drained_policy_ = std::move(held);
        std::atomic_store_explicit(
            &policy_, std::shared_ptr<const SceneProductionPolicy>(std::move(draining)),
            std::memory_order_release);
    }
    return true;
}

bool SceneProductionPolicyStore::ReplaceBlockedScenes(
    const std::vector<std::string>& scene_names
) {
    std::lock_guard<std::mutex> lock(writer_mutex_);
    const auto next_epoch = Load()->epoch + 1;
    drained_policy_.reset();
    auto fail_open = [this, next_epoch]() {
        // A failed complete replacement is a control-plane failure, not a
        // valid policy.  Scene Sync deliberately fails open: retaining the
        // previous list (or the temporary hold) could strand every future
        // Scene production behind one failed one-shot Binder request.
        auto next = std::make_shared<SceneProductionPolicy>();
        next->epoch = next_epoch;
        std::atomic_store_explicit(
            &policy_,
            std::shared_ptr<const SceneProductionPolicy>(std::move(next)),
            std::memory_order_release
        );
        return false;
    };
    if (scene_names.size() > 65536U) {
        // A malformed update must clear the complete policy and leave
        // production fail-open; callers may retry with a valid replacement.
        return fail_open();
    }

    auto next = std::make_shared<SceneProductionPolicy>();
    next->epoch = next_epoch;
    next->sync_worker_hold = false;
    for (const std::string& scene_name : scene_names) {
        if (!translation::scene_identity::IsValid(scene_name)
            || !next->blocked_scenes.insert(scene_name).second) {
            // Invalid or duplicate input is a failed control-plane update.
            // Clear the complete policy and leave production fail-open;
            // callers may retry with a valid complete replacement.
            return fail_open();
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
    auto next = std::make_shared<SceneProductionPolicy>();
    next->epoch = Load()->epoch + 1;
    drained_policy_.reset();
    std::atomic_store_explicit(
        &policy_,
        std::shared_ptr<const SceneProductionPolicy>(std::move(next)),
        std::memory_order_release
    );
}

SceneProductionLease SceneProductionPolicyStore::TryEnter(
    const std::string& scene_name,
    const SceneProductionLease* batch
) {
    if (!translation::scene_identity::IsValid(scene_name)) {
        return SceneProductionLease(
            this,
            false,
            RejectReason::invalid_scene_name
        );
    }

    return TryEnterInternal(&scene_name, batch);
}

SceneProductionLease SceneProductionPolicyStore::TryEnterBatch() {
    return TryEnterInternal(nullptr, nullptr);
}

SceneProductionLease SceneProductionPolicyStore::TryEnterInternal(
    const std::string* scene_name,
    const SceneProductionLease* batch
) {
    auto check = [this, scene_name, batch](const SceneProductionPolicy& policy) {
        const bool owns_batch = batch != nullptr && batch->owner_ == this
            && batch->allowed_ && batch->epoch_ == policy.epoch;
        if ((batch != nullptr && !owns_batch) || policy.sync_worker_hold
            || (policy.sync_worker_drain && !owns_batch)) {
            return RejectReason::sync_worker_hold;
        }
        if (scene_name != nullptr
            && policy.blocked_scenes.count(*scene_name) != 0) {
            return RejectReason::scene_blocked;
        }
        return RejectReason::none;
    };
    auto current = Load();
    RejectReason reason = check(*current);
    if (reason != RejectReason::none) return SceneProductionLease(this, false, reason);

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
    reason = check(*current);
    if (reason != RejectReason::none) {
        Leave();
        return SceneProductionLease(this, false, reason);
    }

    return SceneProductionLease(this, true, RejectReason::none, current->epoch);
}

void SceneProductionPolicyStore::WaitForActiveZero() {
    std::unique_lock<std::mutex> lock(active_mutex_);
    active_cv_.wait(lock, [this]() {
        return active_count_.load(std::memory_order_acquire) == 0
            && !Load()->sync_worker_drain;
    });
}

void SceneProductionPolicyStore::Leave() {
    std::unique_lock<std::mutex> writer_lock(writer_mutex_);
    const int previous = active_count_.fetch_sub(1, std::memory_order_acq_rel);
    if (previous <= 0) {
        assert(false && "SceneProductionLease released more than once");
        // Undo this invalid decrement without overwriting a concurrent
        // TryEnter increment that may have happened after fetch_sub.
        active_count_.fetch_add(1, std::memory_order_acq_rel);
        return;
    }
    if (previous == 1) {
        if (drained_policy_) {
            // The producer has finished and every queued/in-progress Scene
            // has prepared its request (or exited on failure/pause). No API
            // execution or Binder retry is part of this boundary.
            std::atomic_store_explicit(
                &policy_, std::move(drained_policy_), std::memory_order_release);
        }
        writer_lock.unlock();
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

bool BeginSceneSyncDrain() {
    return het::scene_sync::g_scene_production_policy.BeginSyncDrain();
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
    const std::string& scene_name,
    const het::scene_sync::SceneProductionLease* batch
) {
    return het::scene_sync::g_scene_production_policy.TryEnter(scene_name, batch);
}
