#include "translation_pipeline_internal.hpp"

#if !defined(HET_TRANSLATION_DISPATCHER_TEST)
#include "jni_bridge.hpp"
#endif

#include <algorithm>
#include <chrono>
#include <condition_variable>
#include <cstdint>
#include <limits>
#include <mutex>
#include <queue>
#include <thread>
#include <unordered_map>
#include <utility>
#include <vector>

namespace het::translation::translation_dispatcher {
namespace {

using Clock = std::chrono::steady_clock;

bool IsPaused() {
    return stop_reason.load(std::memory_order_acquire)
        == StopReason::user_pause;
}

class SubmissionBridge {
public:
    virtual ~SubmissionBridge() = default;

    virtual bool ResolveRequestId(
        const std::string& request_json,
        std::string* response) = 0;

    virtual bool Submit(
        const std::string& request_id,
        const std::string& request_json,
        std::string* response) = 0;
};

#if !defined(HET_TRANSLATION_DISPATCHER_TEST)

bool CheckAndClearJavaException(JNIEnv* env, const char* operation) {
    if (!env || !env->ExceptionCheck()) {
        return false;
    }
    LOGE("[TranslationDispatcher] Java exception during %s", operation);
    env->ExceptionClear();
    return true;
}

class AttachedEnv {
public:
    bool Attach() {
        if (!g_java_bridge.jvm) {
            return false;
        }
        const jint status = g_java_bridge.jvm->GetEnv(
            reinterpret_cast<void**>(&env_),
            JNI_VERSION_1_6);
        if (status == JNI_OK) {
            return env_ != nullptr;
        }
        if (status != JNI_EDETACHED
            || g_java_bridge.jvm->AttachCurrentThread(&env_, nullptr)
                != JNI_OK) {
            env_ = nullptr;
            return false;
        }
        attached_here_ = true;
        return true;
    }

    ~AttachedEnv() {
        if (attached_here_ && g_java_bridge.jvm) {
            g_java_bridge.jvm->DetachCurrentThread();
        }
    }

    JNIEnv* get() const {
        return env_;
    }

private:
    JNIEnv* env_ = nullptr;
    bool attached_here_ = false;
};

jbyteArray NewByteArray(JNIEnv* env, const std::string& bytes) {
    if (!env || bytes.empty()
        || bytes.size()
            > static_cast<size_t>(std::numeric_limits<jsize>::max())) {
        return nullptr;
    }
    jbyteArray array = env->NewByteArray(static_cast<jsize>(bytes.size()));
    if (!array) {
        if (!CheckAndClearJavaException(env, "NewByteArray")) {
            LOGE("[TranslationDispatcher] NewByteArray returned null");
        }
        return nullptr;
    }
    if (CheckAndClearJavaException(env, "NewByteArray")) {
        env->DeleteLocalRef(array);
        return nullptr;
    }
    env->SetByteArrayRegion(
        array,
        0,
        static_cast<jsize>(bytes.size()),
        reinterpret_cast<const jbyte*>(bytes.data()));
    if (CheckAndClearJavaException(env, "SetByteArrayRegion")) {
        env->DeleteLocalRef(array);
        return nullptr;
    }
    return array;
}

bool ReadByteArray(
    JNIEnv* env,
    jbyteArray array,
    std::string* output) {
    if (!env || !array || !output) {
        return false;
    }
    const jsize size = env->GetArrayLength(array);
    if (CheckAndClearJavaException(env, "GetArrayLength") || size <= 0) {
        return false;
    }
    output->resize(static_cast<size_t>(size));
    env->GetByteArrayRegion(
        array,
        0,
        size,
        reinterpret_cast<jbyte*>(output->data()));
    if (CheckAndClearJavaException(env, "GetByteArrayRegion")) {
        output->clear();
        return false;
    }
    return true;
}

bool CallJavaResolveRequestId(
    const std::string& request_json,
    std::string* response) {
    if (!response || request_json.empty()
        || !g_java_bridge.main_hook_class
        || !g_java_bridge.resolve_request_id_method) {
        return false;
    }
    response->clear();
    AttachedEnv attached;
    if (!attached.Attach()) {
        LOGE("[TranslationDispatcher] Could not attach worker to JVM");
        return false;
    }
    JNIEnv* env = attached.get();
    jbyteArray request_array = NewByteArray(env, request_json);
    if (!request_array) {
        return false;
    }

    auto result = static_cast<jbyteArray>(
        env->CallStaticObjectMethod(
            g_java_bridge.main_hook_class,
            g_java_bridge.resolve_request_id_method,
            request_array));
    const bool call_failed = CheckAndClearJavaException(
        env,
        "MainHook.resolveTranslationRequestId");
    env->DeleteLocalRef(request_array);
    if (call_failed || !result) {
        if (result) {
            env->DeleteLocalRef(result);
        }
        return false;
    }
    const bool success = ReadByteArray(env, result, response);
    env->DeleteLocalRef(result);
    return success;
}

bool CallJavaSubmit(
    const std::string& request_id,
    const std::string& request_json,
    std::string* response) {
    if (!response || request_id.empty() || request_json.empty()
        || !g_java_bridge.main_hook_class
        || !g_java_bridge.submit_translation_method) {
        return false;
    }
    response->clear();
    AttachedEnv attached;
    if (!attached.Attach()) {
        LOGE("[TranslationDispatcher] Could not attach worker to JVM");
        return false;
    }
    JNIEnv* env = attached.get();
    jstring request_id_string = env->NewStringUTF(request_id.c_str());
    if (!request_id_string) {
        if (!CheckAndClearJavaException(env, "NewStringUTF(requestId)")) {
            LOGE("[TranslationDispatcher] NewStringUTF returned null");
        }
        return false;
    }
    if (CheckAndClearJavaException(env, "NewStringUTF(requestId)")) {
        env->DeleteLocalRef(request_id_string);
        return false;
    }
    jbyteArray request_array = NewByteArray(env, request_json);
    if (!request_array) {
        env->DeleteLocalRef(request_id_string);
        return false;
    }

    auto result = static_cast<jbyteArray>(
        env->CallStaticObjectMethod(
            g_java_bridge.main_hook_class,
            g_java_bridge.submit_translation_method,
            request_id_string,
            request_array));
    const bool call_failed = CheckAndClearJavaException(
        env,
        "MainHook.submitTranslation");
    env->DeleteLocalRef(request_array);
    env->DeleteLocalRef(request_id_string);
    if (call_failed || !result) {
        if (result) {
            env->DeleteLocalRef(result);
        }
        return false;
    }
    const bool success = ReadByteArray(env, result, response);
    env->DeleteLocalRef(result);
    return success;
}

class JniSubmissionBridge final : public SubmissionBridge {
public:
    bool ResolveRequestId(
        const std::string& request_json,
        std::string* response) override {
        return CallJavaResolveRequestId(request_json, response);
    }

    bool Submit(
        const std::string& request_id,
        const std::string& request_json,
        std::string* response) override {
        return CallJavaSubmit(request_id, request_json, response);
    }
};

#endif

struct PendingSubmission {
    std::shared_ptr<const TranslationRequest> request;
    std::string request_id;
    uint32_t retry_count = 0;
    Clock::time_point next_attempt_at = Clock::now();
    uint64_t queue_sequence = 0;
};

struct PendingEarlier {
    bool operator()(
        const std::shared_ptr<PendingSubmission>& left,
        const std::shared_ptr<PendingSubmission>& right) const {
        if (left->next_attempt_at != right->next_attempt_at) {
            return left->next_attempt_at > right->next_attempt_at;
        }
        return left->queue_sequence > right->queue_sequence;
    }
};

class Dispatcher {
public:
    explicit Dispatcher(SubmissionBridge& bridge, bool start_worker = true)
        : bridge_(bridge), start_worker_(start_worker) {}

    bool Submit(std::shared_ptr<const TranslationRequest> request) {
        const std::uint64_t captured_epoch = capture_pause_epoch.load(
            std::memory_order_acquire);
        if (!request || request->payload_json.empty()) {
            return false;
        }
        if (IsPaused()) {
            return false;
        }
        if (start_worker_) {
            Start();
        }
        auto pending = std::make_shared<PendingSubmission>();
        pending->request = std::move(request);
        pending->next_attempt_at = Clock::now();
        {
            std::lock_guard<std::mutex> lock(queue_mutex_);
            if (IsPaused()
                || captured_epoch
                    != capture_pause_epoch.load(std::memory_order_acquire)) {
                LOGI(
                    "[TranslationDispatcher] Dropped stale/paused submission scene=%s",
                    pending->request->scene_name.c_str());
                return false;
            }
            pending->queue_sequence = next_queue_sequence_++;
            queue_.push(std::move(pending));
        }
        queue_cv_.notify_one();
        return true;
    }

    void ClearOnPause() {
        {
            std::lock_guard<std::mutex> lock(queue_mutex_);
            ClearQueuedLocked();
        }
        // pending_requests_ contains requests whose submission has already
        // been accepted by the Java bridge.  They must survive a capture
        // pause so terminal redelivery can still TakePending()/write back the
        // accepted result; only unsubmitted queue entries are discarded.
        queue_cv_.notify_all();
    }

    std::shared_ptr<const TranslationRequest> TakePending(
        const std::string& request_id) {
        if (request_id.empty()) {
            return nullptr;
        }
        std::lock_guard<std::mutex> lock(pending_mutex_);
        const auto found = pending_requests_.find(request_id);
        if (found == pending_requests_.end()) {
            return nullptr;
        }
        auto request = std::move(found->second);
        pending_requests_.erase(found);
        return request;
    }

    std::shared_ptr<const TranslationRequest> PeekPending(
        const std::string& request_id) {
        if (request_id.empty()) {
            return nullptr;
        }
        std::lock_guard<std::mutex> lock(pending_mutex_);
        const auto found = pending_requests_.find(request_id);
        return found == pending_requests_.end() ? nullptr : found->second;
    }

#if defined(HET_TRANSLATION_DISPATCHER_TEST)
    bool ProcessReadyForTest() {
        std::shared_ptr<PendingSubmission> pending;
        {
            std::lock_guard<std::mutex> lock(queue_mutex_);
            if (IsPaused()) {
                ClearQueuedLocked();
                return false;
            }
            if (queue_.empty()
                || queue_.top()->next_attempt_at > Clock::now()) {
                return false;
            }
            pending = queue_.top();
            queue_.pop();
        }
        Process(pending);
        return true;
    }

    size_t QueuedCountForTest() const {
        std::lock_guard<std::mutex> lock(queue_mutex_);
        return queue_.size();
    }

    size_t PendingCountForTest() const {
        std::lock_guard<std::mutex> lock(pending_mutex_);
        return pending_requests_.size();
    }
#endif

private:
    void Start() {
        std::call_once(start_once_, [this] {
            std::thread(&Dispatcher::Worker, this).detach();
            LOGI("[TranslationDispatcher] Started");
        });
    }

    void ClearQueuedLocked() {
        while (!queue_.empty()) {
            queue_.pop();
        }
    }

    std::shared_ptr<PendingSubmission> WaitForReadySubmission() {
        std::unique_lock<std::mutex> lock(queue_mutex_);
        for (;;) {
            if (IsPaused()) {
                ClearQueuedLocked();
                queue_cv_.wait_for(lock, std::chrono::milliseconds(250));
                continue;
            }
            if (queue_.empty()) {
                queue_cv_.wait(lock);
                continue;
            }
            const Clock::time_point now = Clock::now();
            const Clock::time_point ready_at = queue_.top()->next_attempt_at;
            if (ready_at > now) {
                queue_cv_.wait_until(
                    lock,
                    std::min(
                        ready_at,
                        now + std::chrono::milliseconds(250)));
                continue;
            }
            auto pending = queue_.top();
            queue_.pop();
            return pending;
        }
    }

    void ScheduleRetry(const std::shared_ptr<PendingSubmission>& pending) {
        if (!pending) {
            return;
        }
        const std::uint64_t captured_epoch = capture_pause_epoch.load(
            std::memory_order_acquire);
        if (IsPaused()) {
            return;
        }
        static constexpr int kRetrySeconds[] = {1, 2, 5, 10, 30};
        const size_t index = std::min<size_t>(
            pending->retry_count,
            (sizeof(kRetrySeconds) / sizeof(kRetrySeconds[0])) - 1U);
        const int delay_seconds = kRetrySeconds[index];
        if (pending->retry_count < std::numeric_limits<uint32_t>::max()) {
            ++pending->retry_count;
        }
        pending->next_attempt_at = Clock::now()
            + std::chrono::seconds(delay_seconds);
        {
            std::lock_guard<std::mutex> lock(queue_mutex_);
            if (IsPaused()
                || captured_epoch
                    != capture_pause_epoch.load(std::memory_order_acquire)) {
                return;
            }
            pending->queue_sequence = next_queue_sequence_++;
            queue_.push(pending);
        }
        LOGW(
            "[TranslationDispatcher] Retrying scene=%s in %d seconds attempt=%u",
            pending->request->scene_name.c_str(),
            delay_seconds,
            pending->retry_count);
        queue_cv_.notify_one();
    }

    void RegisterPending(
        const std::string& request_id,
        const std::shared_ptr<const TranslationRequest>& request) {
        std::lock_guard<std::mutex> lock(pending_mutex_);
        pending_requests_[request_id] = request;
    }

    void RemovePendingIfSame(
        const std::string& request_id,
        const std::shared_ptr<const TranslationRequest>& expected) {
        std::lock_guard<std::mutex> lock(pending_mutex_);
        const auto found = pending_requests_.find(request_id);
        if (found != pending_requests_.end() && found->second == expected) {
            pending_requests_.erase(found);
        }
    }

    void Process(const std::shared_ptr<PendingSubmission>& pending) {
        if (!pending || !pending->request || IsPaused()) {
            return;
        }
        const TranslationRequest& request = *pending->request;
        if (pending->request_id.empty()) {
            std::string resolution_json;
            if (!bridge_.ResolveRequestId(
                    request.payload_json,
                    &resolution_json)) {
                LOGE(
                    "[TranslationDispatcher] Request ID resolution JNI bridge invocation failed permanently scene=%s",
                    request.scene_name.c_str());
                return;
            }

            const RequestIdResolutionResponse resolution =
                document_codec::DecodeRequestIdResolutionResponse(
                    resolution_json);
            if (resolution.kind == BridgeResponseKind::retryable_failure) {
                LOGW(
                    "[TranslationDispatcher] Request ID resolution retryable failure scene=%s type=%s message=%s",
                    request.scene_name.c_str(),
                    resolution.error_type.c_str(),
                    resolution.error_message.c_str());
                ScheduleRetry(pending);
                return;
            }
            if (resolution.kind != BridgeResponseKind::success) {
                LOGE(
                    "[TranslationDispatcher] Request ID resolution rejected scene=%s type=%s message=%s status=%d",
                    request.scene_name.c_str(),
                    resolution.error_type.c_str(),
                    resolution.error_message.c_str(),
                    resolution.error_status);
                return;
            }
            pending->request_id = resolution.request_id;
        }
        if (IsPaused()) {
            return;
        }

        RegisterPending(pending->request_id, pending->request);
        // Once the request has crossed the HET admission bridge it remains in
        // pending_requests_ across capture pause.  The terminal callback path
        // owns its eventual removal/receipt, so pause must not erase it here.
        std::string submission_json;
        if (!bridge_.Submit(
                pending->request_id,
                request.payload_json,
                &submission_json)) {
            RemovePendingIfSame(pending->request_id, pending->request);
            LOGE(
                "[TranslationDispatcher] Submit JNI bridge invocation failed permanently scene=%s requestId=%s",
                request.scene_name.c_str(),
                pending->request_id.c_str());
            return;
        }

        const SubmissionResponse submitted =
            document_codec::DecodeSubmissionResponse(
                submission_json,
                pending->request_id);
        if (submitted.kind == BridgeResponseKind::success) {
            LOGI(
                "[TranslationDispatcher] Submission accepted requestId=%s scene=%s created=%s",
                pending->request_id.c_str(),
                request.scene_name.c_str(),
                submitted.created ? "true" : "false");
            return;
        }

        RemovePendingIfSame(pending->request_id, pending->request);
        if (submitted.kind == BridgeResponseKind::retryable_failure) {
            LOGW(
                "[TranslationDispatcher] Submit retryable failure scene=%s type=%s message=%s",
                request.scene_name.c_str(),
                submitted.error_type.c_str(),
                submitted.error_message.c_str());
            ScheduleRetry(pending);
            return;
        }
        LOGE(
            "[TranslationDispatcher] Submit rejected scene=%s type=%s message=%s status=%d",
            request.scene_name.c_str(),
            submitted.error_type.c_str(),
            submitted.error_message.c_str(),
            submitted.error_status);
    }

    void Worker() {
        for (;;) {
            Process(WaitForReadySubmission());
        }
    }

    SubmissionBridge& bridge_;
    const bool start_worker_;
    std::once_flag start_once_;
    mutable std::mutex queue_mutex_;
    std::condition_variable queue_cv_;
    std::priority_queue<
        std::shared_ptr<PendingSubmission>,
        std::vector<std::shared_ptr<PendingSubmission>>,
        PendingEarlier> queue_;
    uint64_t next_queue_sequence_ = 0;

    mutable std::mutex pending_mutex_;
    std::unordered_map<
        std::string,
        std::shared_ptr<const TranslationRequest>> pending_requests_;
};

#if !defined(HET_TRANSLATION_DISPATCHER_TEST)

SubmissionBridge& GetJniSubmissionBridge() {
    static auto* bridge = new JniSubmissionBridge();
    return *bridge;
}

Dispatcher& GetDispatcher() {
    static auto* dispatcher = new Dispatcher(GetJniSubmissionBridge());
    return *dispatcher;
}

#endif

}  // namespace

#if !defined(HET_TRANSLATION_DISPATCHER_TEST)

bool Submit(std::shared_ptr<const TranslationRequest> request) {
    return GetDispatcher().Submit(std::move(request));
}

std::shared_ptr<const TranslationRequest> TakePendingRequest(
    const std::string& request_id) {
    return GetDispatcher().TakePending(request_id);
}

std::shared_ptr<const TranslationRequest> PeekPendingRequest(
    const std::string& request_id) {
    return GetDispatcher().PeekPending(request_id);
}

void ClearOnPause() {
    GetDispatcher().ClearOnPause();
}

#else

void ClearOnPause() {
    // Test harnesses own their Dispatcher instance; production pause control
    // is wired to the process-wide instance above.
}

#endif

}  // namespace het::translation::translation_dispatcher
