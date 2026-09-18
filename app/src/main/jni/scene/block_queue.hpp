#include <deque>

template <typename T>
class BlockingQueue {
public:
    bool Push(T value) {
        {
            std::lock_guard<std::mutex> lock(mutex_);
            if (closed_) return false;
            queue_.push_back(std::move(value));
        }
        cv_.notify_one();
        return true;
    }

    bool Pop(T* out) {
        std::unique_lock<std::mutex> lock(mutex_);

        cv_.wait(lock, [this] {
            return closed_ || !queue_.empty();
        });

        if (queue_.empty()) {
            return false;
        }

        *out = std::move(queue_.front());
        queue_.pop_front();
        return true;
    }

    void Close() {
        {
            std::lock_guard<std::mutex> lock(mutex_);
            closed_ = true;
        }
        cv_.notify_all();
    }

private:
    std::mutex mutex_;
    std::condition_variable cv_;
    std::deque<T> queue_;
    bool closed_ = false;
};
