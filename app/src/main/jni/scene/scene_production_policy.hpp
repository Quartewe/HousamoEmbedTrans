#pragma once

#include <atomic>
#include <condition_variable>
#include <cstdint>
#include <memory>
#include <mutex>
#include <string>
#include <unordered_set>
#include <vector>

namespace het::scene_sync {

struct SceneProductionPolicy {
    bool sync_worker_hold = false;
    // Close new admission, but let a previously admitted capture batch drain.
    bool sync_worker_drain = false;
    std::uint64_t epoch = 0;
    std::unordered_set<std::string> blocked_scenes;
};

enum class RejectReason : int {
    none = 0,
    sync_worker_hold = 1,
    scene_blocked = 2,
    invalid_scene_name = 3,
};

/** Move-only scope covering one Scene production path or capture batch. */
class SceneProductionLease {
public:
    SceneProductionLease() = default;
    SceneProductionLease(
        class SceneProductionPolicyStore* owner,
        bool allowed,
        RejectReason reason,
        std::uint64_t epoch = 0
    )
        : owner_(owner), allowed_(allowed), reason_(reason), epoch_(epoch) {}

    SceneProductionLease(const SceneProductionLease&) = delete;
    SceneProductionLease& operator=(const SceneProductionLease&) = delete;

    SceneProductionLease(SceneProductionLease&& other) noexcept
        : owner_(other.owner_),
          allowed_(other.allowed_),
          reason_(other.reason_),
          epoch_(other.epoch_) {
        other.owner_ = nullptr;
        other.allowed_ = false;
    }

    SceneProductionLease& operator=(SceneProductionLease&& other) noexcept {
        if (this != &other) {
            Release();
            owner_ = other.owner_;
            allowed_ = other.allowed_;
            reason_ = other.reason_;
            epoch_ = other.epoch_;
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
    friend class SceneProductionPolicyStore;

    class SceneProductionPolicyStore* owner_ = nullptr;
    bool allowed_ = false;
    RejectReason reason_ = RejectReason::none;
    std::uint64_t epoch_ = 0;
};

/**
 * Native control-plane policy. Readers use immutable atomic snapshots; lease
 * release and control writes share writer_mutex_ to finish a pending drain.
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

    /** Drains admitted batches and Scene work before publishing hold=true. */
    bool BeginSyncDrain();

    /** Replaces the complete blocked set and clears sync_worker_hold. */
    bool ReplaceBlockedScenes(const std::vector<std::string>& scene_names);

    /** Explicit fail-open reset used when the controlling connection dies. */
    void FailOpen();

    /** Two-check production admission and active-count scope creation. */
    SceneProductionLease TryEnter(
        const std::string& scene_name,
        const SceneProductionLease* batch = nullptr);

    /** Covers the producer until it has enqueued every Scene in this sweep. */
    SceneProductionLease TryEnterBatch();

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
    SceneProductionLease TryEnterInternal(
        const std::string* scene_name,
        const SceneProductionLease* batch);

    std::shared_ptr<const SceneProductionPolicy> policy_;
    // Prepared at drain admission; the last lease publishes it without allocation.
    std::shared_ptr<const SceneProductionPolicy> drained_policy_;
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
