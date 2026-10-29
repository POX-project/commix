#pragma once

#include <vector>
#include <queue>
#include <thread>
#include <mutex>
#include <condition_variable>
#include <functional>
#include <atomic>
#include <future>
#include <memory>

namespace commix {

/**
 * High-performance, persistent thread pool using std::thread and lock-free/mutex queues.
 * Eliminates thread creation overhead on hot image/scope execution paths.
 */
class ThreadPool {
public:
    static ThreadPool& instance() noexcept {
        static ThreadPool s_instance(8);
        return s_instance;
    }

    explicit ThreadPool(size_t threadCount = 8) : m_stop(false) {
        m_threads.reserve(threadCount);
        for (size_t i = 0; i < threadCount; ++i) {
            m_threads.emplace_back([this]() {
                workerLoop();
            });
        }
    }

    ~ThreadPool() {
        shutdown();
    }

    ThreadPool(const ThreadPool&) = delete;
    ThreadPool& operator=(const ThreadPool&) = delete;

    template <typename F>
    bool enqueue(F&& f) {
        {
            std::unique_lock<std::mutex> lock(m_mutex);
            if (m_stop.load(std::memory_order_relaxed)) return false;
            m_tasks.emplace(std::forward<F>(f));
        }
        m_cv.notify_one();
        return true;
    }

    void shutdown() noexcept {
        bool expected = false;
        if (m_stop.compare_exchange_strong(expected, true)) {
            m_cv.notify_all();
            for (auto& th : m_threads) {
                if (th.joinable()) {
                    th.join();
                }
            }
        }
    }

private:
    void workerLoop() {
        while (!m_stop.load(std::memory_order_relaxed)) {
            std::function<void()> task;
            {
                std::unique_lock<std::mutex> lock(m_mutex);
                m_cv.wait(lock, [this]() {
                    return m_stop.load(std::memory_order_relaxed) || !m_tasks.empty();
                });

                if (m_stop.load(std::memory_order_relaxed) && m_tasks.empty()) {
                    break;
                }

                if (!m_tasks.empty()) {
                    task = std::move(m_tasks.front());
                    m_tasks.pop();
                }
            }
            if (task) {
                task();
            }
        }
    }

    std::vector<std::thread> m_threads;
    std::queue<std::function<void()>> m_tasks;
    std::mutex m_mutex;
    std::condition_variable m_cv;
    std::atomic<bool> m_stop;
};

} // namespace commix
