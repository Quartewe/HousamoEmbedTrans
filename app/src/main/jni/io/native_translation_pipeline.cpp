#include "native_translation_pipeline.hpp"

#include "housamo.hpp"
#include "translation_pipeline_internal.hpp"

#include <atomic>
#include <cstdint>
#include <condition_variable>
#include <deque>
#include <memory>
#include <mutex>
#include <thread>
#include <unordered_map>
#include <unordered_set>
#include <utility>

namespace {

using het::translation::SceneCommitResult;
using het::translation::SceneInspection;
using het::translation::TranslationRequest;
using het::translation::TranslationResult;
namespace document_codec = het::translation::document_codec;
namespace scene_store = het::translation::scene_store;
namespace translation_dispatcher = het::translation::translation_dispatcher;

bool IsPaused() {
    return stop_reason.load(std::memory_order_acquire)
        == StopReason::user_pause;
}

class NativeTranslationPipeline {
public:
#ifdef HET_NATIVE_TRANSLATION_PIPELINE_TEST
    std::uint64_t WaitReturnCountForTest() const noexcept {
        return scene_wait_return_count_.load(std::memory_order_acquire);
    }

    bool IsIdleWaitingForTest() const noexcept {
        return scene_idle_waiting_.load(std::memory_order_acquire);
    }

    bool IsPauseWaitingForTest() const noexcept {
        return scene_pause_waiting_.load(std::memory_order_acquire);
    }
#endif

    void NotifyStopChanged() {
        std::lock_guard<std::mutex> lock(scene_mutex_);
        scene_cv_.notify_all();
    }

    void ClearOnPause() {
        std::lock_guard<std::mutex> lock(scene_mutex_);
        scene_queue_.clear();
        scene_cv_.notify_all();
    }

    SceneFileStatus GetFileStatusForTarget(
        const std::string& scene_name,
        const std::string& target_lang) const {
        if (scene_name.empty() || target_lang.empty()) {
            return SceneFileStatus::not_found;
        }
        std::string scene_json;
        std::string error;
        if (!scene_store::Read(scene_name, &scene_json, &error)) {
            LOGW(
                "[NativeTranslationPipeline] Scene status read failed scene=%s reason=%s",
                scene_name.c_str(),
                error.c_str());
            return SceneFileStatus::not_found;
        }
        const SceneInspection inspection = document_codec::InspectScene(
            scene_json,
            scene_name,
            target_lang,
            &error);
        if (inspection == SceneInspection::complete) {
            return SceneFileStatus::complete;
        }
        if (inspection == SceneInspection::invalid) {
            LOGW(
                "[NativeTranslationPipeline] Scene status invalid scene=%s reason=%s",
                scene_name.c_str(),
                error.c_str());
        }
        return inspection == SceneInspection::pending
            ? SceneFileStatus::pending
            : SceneFileStatus::not_found;
    }

    SceneFileStatus GetFileStatus(const std::string& scene_name) const {
        return GetFileStatusForTarget(scene_name, g_runtime_config.target_lang);
    }

    bool SubmitExisting(const std::string& scene_name) {
        if (scene_name.empty()) {
            return false;
        }

        std::string scene_json;
        std::string error;
        if (!scene_store::Read(scene_name, &scene_json, &error)) {
            LOGE(
                "[NativeTranslationPipeline] Could not read existing scene=%s reason=%s",
                scene_name.c_str(),
                error.c_str());
            return false;
        }
        TranslationRequest request;
        const bool rebuilt = document_codec::BuildRequestFromExistingScene(
                scene_json,
                g_runtime_config.target_lang,
                &request,
                &error);
        if (!rebuilt || request.scene_name != scene_name) {
            if (rebuilt && error.empty()) {
                error = "existing scene identity does not match requested scene";
            }
            LOGE(
                "[NativeTranslationPipeline] Existing scene is not submit-ready scene=%s reason=%s",
                scene_name.c_str(),
                error.c_str());
            return false;
        }
        if (g_runtime_config.parse_only_debug) {
            LOGI(
                "[NativeTranslationPipeline] Parse-only mode validated existing scene=%s",
                scene_name.c_str());
            return true;
        }
        const bool submitted = translation_dispatcher::Submit(
            std::make_shared<const TranslationRequest>(std::move(request)));
        if (!submitted) {
            LOGE(
                "[NativeTranslationPipeline] Dispatcher rejected existing scene=%s",
                scene_name.c_str());
        }
        return submitted;
    }

    struct CapturedWork {
        std::shared_ptr<const Scene> scene;
        het::scene_sync::SceneProductionLease production_lease;
    };

    void SubmitCaptured(
        std::shared_ptr<const Scene> scene,
        het::scene_sync::SceneProductionLease production_lease) {
        const std::uint64_t captured_epoch = capture_pause_epoch.load(
            std::memory_order_acquire);
        if (!scene
            || scene->scene.empty()
            || !production_lease.allowed()
            || IsPaused()) {
            return;
        }
        StartSceneWorker();
        {
            std::lock_guard<std::mutex> lock(scene_mutex_);
            // Pause clearing and queue insertion share this mutex.  The
            // epoch closes the old admission generation even when a stale
            // producer is delayed until after pause and resume.
            if (IsPaused()
                || captured_epoch
                    != capture_pause_epoch.load(std::memory_order_acquire)) {
                return;
            }
            scene_queue_.push_back(CapturedWork{
                std::move(scene),
                std::move(production_lease)
            });
        }
        scene_cv_.notify_one();
    }

    bool ApplyResult(
        const std::string& request_id,
        const std::string& scene_name,
        const std::string& target_lang,
        const std::string& result_json) {
        if (request_id.empty() || scene_name.empty() || target_lang.empty()
            || result_json.empty()) {
            LOGE("[NativeTranslationPipeline] final result callback has incomplete identity or payload");
            return false;
        }
        {
            std::lock_guard<std::mutex> lock(receipt_mutex_);
            auto receipt = completion_receipts_.find(request_id);
            if (receipt != completion_receipts_.end()) {
                if (receipt->second.scene != scene_name
                    || receipt->second.target_lang != target_lang) {
                    LOGW(
                        "[NativeTranslationPipeline] completion receipt identity mismatch requestId=%s",
                        request_id.c_str());
                    return false;
                }
                // The Scene may have been moved/deleted after the first
                // commit.  A matching waiting-ACK receipt is still enough to
                // accept the duplicate without reconstructing the request.
                return true;
            }
        }
        // Terminal Scene writeback is another Scene production mutation.  It
        // uses the same policy hold/active lease as capture, so FULL_SYNC
        // either waits for an already admitted write or leaves this callback
        // pending when the hold has won the admission race.  Rejections are
        // deliberately not reported as capture rejections.
        het::scene_sync::SceneProductionLease mutation_lease =
            EnterSceneProduction(scene_name);
        if (!mutation_lease.allowed()) {
            LOGI(
                "[NativeTranslationPipeline] deferring terminal Scene apply "
                "requestId=%s scene=%s reason=%d",
                request_id.c_str(),
                scene_name.c_str(),
                static_cast<int>(mutation_lease.reason()));
            return false;
        }
        std::shared_ptr<const TranslationRequest> request =
            translation_dispatcher::PeekPendingRequest(request_id);
        if (request && (request->scene_name != scene_name
                || request->target_lang != target_lang)) {
            LOGW(
                "[NativeTranslationPipeline] completion identity mismatch requestId=%s expected=%s/%s actual=%s/%s",
                request_id.c_str(),
                request->scene_name.c_str(),
                request->target_lang.c_str(),
                scene_name.c_str(),
                target_lang.c_str());
            return false;
        }
        if (!request) {
            std::string scene_json;
            std::string rebuild_error;
            TranslationRequest rebuilt_request;
            if (!scene_store::Read(scene_name, &scene_json, &rebuild_error)
                || !document_codec::BuildRequestFromExistingScene(
                    scene_json,
                    target_lang,
                    &rebuilt_request,
                    &rebuild_error,
                    true)) {
                LOGW(
                    "[NativeTranslationPipeline] cannot reconstruct completion requestId=%s scene=%s reason=%s",
                    request_id.c_str(),
                    scene_name.c_str(),
                    rebuild_error.c_str());
                return false;
            }
            request = std::make_shared<const TranslationRequest>(
                std::move(rebuilt_request)
            );
            if (!request || request->scene_name != scene_name
                || request->target_lang != target_lang) {
                LOGW(
                    "[NativeTranslationPipeline] reconstructed completion identity mismatch requestId=%s",
                    request_id.c_str());
                return false;
            }
        }
        TranslationResult result;
        std::string error;
        if (!document_codec::DecodeTranslationResult(result_json, *request, &result, &error)) {
            LOGE("[NativeTranslationPipeline] rejected final result requestId=%s scene=%s reason=%s", request_id.c_str(), request->scene_name.c_str(), error.c_str());
            return false;
        }
        if (GetFileStatusForTarget(scene_name, target_lang)
            == SceneFileStatus::complete) {
            std::lock_guard<std::mutex> lock(receipt_mutex_);
            // Java preflight is authoritative for the current durable
            // terminal kind.  A rerun may replace an old opposite receipt in
            // this process, so discard that stale receipt before recording
            // the newly accepted completion.
            failure_receipts_.erase(request_id);
            completion_receipts_[request_id] = CompletionReceipt{
                scene_name,
                target_lang
            };
            // completion_waiting_ack receipt; native ACK owns its removal.
            translation_dispatcher::TakePendingRequest(request_id);
            LOGI(
                "[NativeTranslationPipeline] completion already applied requestId=%s scene=%s target=%s",
                request_id.c_str(),
                scene_name.c_str(),
                target_lang.c_str());
            return true;
        }
        if (!CommitTranslationResult(*request, result, &error)) {
            LOGE("[NativeTranslationPipeline] final writeback failed requestId=%s scene=%s path=%s reason=%s", request_id.c_str(), request->scene_name.c_str(), scene_store::PathForLog(request->scene_name).c_str(), error.c_str());
            return false;
        }
        {
            std::lock_guard<std::mutex> lock(receipt_mutex_);
            failure_receipts_.erase(request_id);
            completion_receipts_[request_id] = CompletionReceipt{
                scene_name,
                target_lang
            };
        }
        // completion_waiting_ack receipt is durable on HET and process-local
        // here until Java persists the matching ACK.
        translation_dispatcher::TakePendingRequest(request_id);
        LOGI("[NativeTranslationPipeline] final result committed requestId=%s scene=%s target=%s translations=%zu", request_id.c_str(), request->scene_name.c_str(), result.target_lang.c_str(), result.translations.size());
        return true;
    }

    bool HandleFailure(
        const std::string& request_id,
        const std::string& error_type,
        const std::string& message) {
        if (request_id.empty()) {
            LOGE("[NativeTranslationPipeline] failure callback has empty requestId type=%s message=%s", error_type.c_str(), message.c_str());
            return false;
        }
        {
            std::lock_guard<std::mutex> lock(receipt_mutex_);
            if (failure_receipts_.find(request_id)
                != failure_receipts_.end()) {
                return true;
            }
            // A new durable failed outcome supersedes an old completion
            // receipt after HET preflight has accepted the current kind.
            completion_receipts_.erase(request_id);
        }
        const std::shared_ptr<const TranslationRequest> request =
            translation_dispatcher::PeekPendingRequest(request_id);
        LOGE(
            "[NativeTranslationPipeline] translation failure accepted requestId=%s scene=%s type=%s message=%s",
            request_id.c_str(),
            request ? request->scene_name.c_str() : "<replayed>",
            error_type.c_str(),
            message.c_str());
        {
            std::lock_guard<std::mutex> lock(receipt_mutex_);
            failure_receipts_.insert(request_id);
        }
        // This process-local entry is the native failed_waiting_ack receipt;
        // it is removed only by a matching native ACK.
        translation_dispatcher::TakePendingRequest(request_id);
        return true;
    }

    bool Acknowledge(
        const std::string& request_id,
        const std::string& terminal_kind) {
        if (request_id.empty()) {
            return false;
        }
        std::lock_guard<std::mutex> lock(receipt_mutex_);
        if (terminal_kind == "completed") {
            // The Java side already gates ACKs with the durable JobStore
            // terminal identity.  Native must still reject an unknown
            // request: an ACK is valid only for the receipt produced by the
            // matching terminal callback in this process.
            return completion_receipts_.erase(request_id) > 0U;
        }
        if (terminal_kind == "failed") {
            return failure_receipts_.erase(request_id) > 0U;
        }
        return false;
    }

private:
    bool CommitTranslationResult(
        const TranslationRequest& request,
        const TranslationResult& result,
        std::string* error) const {
        std::string scene_json;
        if (!scene_store::Read(request.scene_name, &scene_json, error)) {
            return false;
        }

        std::string translated_scene_json;
        if (!document_codec::ApplyTranslationToScene(
                scene_json,
                request,
                result,
                &translated_scene_json,
                error)) {
            return false;
        }

        return scene_store::Commit(
            request.scene_name,
            translated_scene_json,
            true,
            error) == SceneCommitResult::committed;
    }

    void StartSceneWorker() {
        std::call_once(scene_start_once_, [this] {
            std::thread(&NativeTranslationPipeline::SceneWorker, this).detach();
            LOGI("[NativeTranslationPipeline] Scene worker started");
        });
    }

    CapturedWork WaitForScene() {
        std::unique_lock<std::mutex> lock(scene_mutex_);
        for (;;) {
            if (IsPaused()) {
                scene_queue_.clear();
#ifdef HET_NATIVE_TRANSLATION_PIPELINE_TEST
                scene_pause_waiting_.store(true, std::memory_order_release);
#endif
                scene_cv_.wait(lock, []() { return !IsPaused(); });
#ifdef HET_NATIVE_TRANSLATION_PIPELINE_TEST
                scene_pause_waiting_.store(false, std::memory_order_release);
                scene_wait_return_count_.fetch_add(
                    1U,
                    std::memory_order_release);
#endif
                continue;
            }
            if (scene_queue_.empty()) {
#ifdef HET_NATIVE_TRANSLATION_PIPELINE_TEST
                scene_idle_waiting_.store(true, std::memory_order_release);
#endif
                scene_cv_.wait(
                    lock,
                    [this]() {
                        return IsPaused() || !scene_queue_.empty();
                    });
#ifdef HET_NATIVE_TRANSLATION_PIPELINE_TEST
                scene_idle_waiting_.store(false, std::memory_order_release);
                scene_wait_return_count_.fetch_add(
                    1U,
                    std::memory_order_release);
#endif
                continue;
            }
            CapturedWork work = std::move(scene_queue_.front());
            scene_queue_.pop_front();
            return work;
        }
    }

    void ProcessCaptured(CapturedWork work) {
        const std::shared_ptr<const Scene>& scene = work.scene;
        if (!scene || IsPaused()) {
            return;
        }
        std::string scene_json;
        TranslationRequest request;
        std::string error;
        if (!document_codec::EncodeCapturedScene(
                *scene,
                &scene_json,
                &request,
                &error)) {
            LOGE(
                "[NativeTranslationPipeline] Could not encode captured scene=%s reason=%s",
                scene->scene.c_str(),
                error.c_str());
            return;
        }

        const SceneCommitResult committed = scene_store::Commit(
            scene->scene,
            scene_json,
            g_runtime_config.overwrite_existing,
            &error);
        if (committed == SceneCommitResult::failed) {
            LOGE(
                "[NativeTranslationPipeline] Could not commit scene=%s path=%s reason=%s",
                scene->scene.c_str(),
                scene_store::PathForLog(scene->scene).c_str(),
                error.c_str());
            return;
        }

        if (committed == SceneCommitResult::already_exists) {
            if (!scene_store::Read(scene->scene, &scene_json, &error)
                || !document_codec::BuildRequestFromExistingScene(
                    scene_json,
                    scene->target_lang,
                    &request,
                    &error)
                || request.scene_name != scene->scene) {
                if (error.empty()) {
                    error = "existing scene identity does not match requested scene";
                }
                LOGE(
                    "[NativeTranslationPipeline] Existing scene could not replace captured submission scene=%s reason=%s",
                    scene->scene.c_str(),
                    error.c_str());
                return;
            }
        } else {
            LOGI(
                "[NativeTranslationPipeline] Scene committed path=%s bytes=%zu",
                scene_store::PathForLog(scene->scene).c_str(),
                scene_json.size());
        }

        if (g_runtime_config.parse_only_debug || IsPaused()) {
            return;
        }
        if (!translation_dispatcher::Submit(
                std::make_shared<const TranslationRequest>(
                    std::move(request)))) {
            LOGE(
                "[NativeTranslationPipeline] Dispatcher rejected scene=%s",
                scene->scene.c_str());
        }
    }

    void SceneWorker() {
        for (;;) {
            ProcessCaptured(WaitForScene());
        }
    }

    std::once_flag scene_start_once_;
    std::mutex scene_mutex_;
    std::condition_variable scene_cv_;
    std::deque<CapturedWork> scene_queue_;
    std::mutex receipt_mutex_;
    struct CompletionReceipt {
        std::string scene;
        std::string target_lang;
    };
    std::unordered_map<std::string, CompletionReceipt> completion_receipts_;
    std::unordered_set<std::string> failure_receipts_;
#ifdef HET_NATIVE_TRANSLATION_PIPELINE_TEST
    std::atomic<std::uint64_t> scene_wait_return_count_{0U};
    std::atomic<bool> scene_idle_waiting_{false};
    std::atomic<bool> scene_pause_waiting_{false};
#endif
};

NativeTranslationPipeline& GetPipeline() {
    static auto* pipeline = new NativeTranslationPipeline();
    return *pipeline;
}

#ifdef HET_NATIVE_TRANSLATION_PIPELINE_TEST
std::uint64_t NativeTranslationPipelineWaitReturnCountForTest() noexcept {
    return GetPipeline().WaitReturnCountForTest();
}

bool IsNativeTranslationPipelineIdleWaitingForTest() noexcept {
    return GetPipeline().IsIdleWaitingForTest();
}

bool IsNativeTranslationPipelinePauseWaitingForTest() noexcept {
    return GetPipeline().IsPauseWaitingForTest();
}
#endif

}  // namespace

void NotifyNativeTranslationPipelineStopChanged() {
    GetPipeline().NotifyStopChanged();
}

void ClearNativeTranslationPipelineOnPause() {
    GetPipeline().ClearOnPause();
}

SceneFileStatus GetSceneFileStatus(const std::string& scene_name) {
    return GetPipeline().GetFileStatus(scene_name);
}

bool SubmitExistingScene(const std::string& scene_name) {
    return GetPipeline().SubmitExisting(scene_name);
}

void SubmitCapturedScene(
    std::shared_ptr<const Scene> scene,
    het::scene_sync::SceneProductionLease production_lease) {
    GetPipeline().SubmitCaptured(
        std::move(scene),
        std::move(production_lease));
}

bool HandleCompletedTranslation(
    const std::string& request_id,
    const std::string& scene_name,
    const std::string& target_lang,
    const std::string& result_json) {
    return GetPipeline().ApplyResult(
        request_id,
        scene_name,
        target_lang,
        result_json);
}

bool HandleTranslationFailure(
    const std::string& request_id,
    const std::string& error_type,
    const std::string& message) {
    return GetPipeline().HandleFailure(request_id, error_type, message);
}

bool AcknowledgeTranslationTerminal(
    const std::string& request_id,
    const std::string& terminal_kind) {
    return GetPipeline().Acknowledge(request_id, terminal_kind);
}
