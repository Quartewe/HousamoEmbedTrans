#pragma once

#include <atomic>
#include <condition_variable>
#include <memory>
#include <mutex>
#include <string>
#include <unordered_set>
#include <vector>

namespace het::scene_sync {

struct SceneProductionPolicy {
    bool sync_worker_hold = false;
    std::unordered_set<std::string> blocked_scenes;
};

enum class RejectReason : int {
    none = 0,
    sync_worker_hold = 1,
    scene_blocked = 2,
    invalid_scene_name = 3,
};

/** Move-only scope covering one Scene production path. */
class SceneProductionLease {
public:
    SceneProductionLease() = default;
    SceneProductionLease(
        class SceneProductionPolicyStore* owner,
        bool allowed,
        RejectReason reason
    )
        : owner_(owner), allowed_(allowed), reason_(reason) {}

    SceneProductionLease(const SceneProductionLease&) = delete;
    SceneProductionLease& operator=(const SceneProductionLease&) = delete;

    SceneProductionLease(SceneProductionLease&& other) noexcept
        : owner_(other.owner_),
          allowed_(other.allowed_),
          reason_(other.reason_) {
        other.owner_ = nullptr;
        other.allowed_ = false;
    }

    SceneProductionLease& operator=(SceneProductionLease&& other) noexcept {
        if (this != &other) {
            Release();
            owner_ = other.owner_;
            allowed_ = other.allowed_;
            reason_ = other.reason_;
            other.owner_ = nullptr;
            other.allowed_ = false;
        }
        return *this;
    }

    ~SceneProductionLease() {
        Release();
    }

    bool allowed() const {
        return allowed_ && owner_ != nullptr;
    }

    RejectReason reason() const {
        return reason_;
    }

    void Release();

private:
    class SceneProductionPolicyStore* owner_ = nullptr;
    bool allowed_ = false;
    RejectReason reason_ = RejectReason::none;
};

/**
 * Native control-plane policy.  Readers use atomic shared ownership and never
 * acquire writer_mutex_; writers publish complete immutable snapshots.
 */
class SceneProductionPolicyStore {
public:
#if defined(HET_SCENE_PRODUCTION_POLICY_TEST)
    using AdmissionProbe = void (*)(SceneProductionPolicyStore*);
#endif

    SceneProductionPolicyStore();

    std::shared_ptr<const SceneProductionPolicy> Load() const;

    /** Publishes hold=true while preserving the current blocked set. */
    bool BeginSyncHold();

    /** Replaces the complete blocked set and clears sync_worker_hold. */
    bool ReplaceBlockedScenes(const std::vector<std::string>& scene_names);

    /** Fail-open reset used by Binder death and policy update failures. */
    void FailOpen();

    /** Two-check production admission and active-count scope creation. */
    SceneProductionLease TryEnter(const std::string& scene_name);

    /** Test/diagnostic seam invoked after the first gate and active increment. */
#if defined(HET_SCENE_PRODUCTION_POLICY_TEST)
    void SetAdmissionProbe(AdmissionProbe probe) {
        admission_probe_.store(probe, std::memory_order_release);
    }
#endif

    void WaitForActiveZero();

    int ActiveCount() const {
        return active_count_.load(std::memory_order_acquire);
    }

private:
    friend class SceneProductionLease;

    void Leave();

    std::shared_ptr<const SceneProductionPolicy> policy_;
    mutable std::mutex writer_mutex_;
    std::atomic<int> active_count_{0};
#if defined(HET_SCENE_PRODUCTION_POLICY_TEST)
    std::atomic<AdmissionProbe> admission_probe_{nullptr};
#endif
    mutable std::mutex active_mutex_;
    mutable std::condition_variable active_cv_;
};

extern SceneProductionPolicyStore g_scene_production_policy;

}  // namespace het::scene_sync
