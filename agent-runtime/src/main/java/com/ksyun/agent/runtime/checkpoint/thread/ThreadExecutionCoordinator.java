package com.ksyun.agent.runtime.checkpoint.thread;

import com.ksyun.agent.core.exception.AgentErrorCode;
import com.ksyun.agent.core.exception.AgentFrameworkException;

import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 线程执行协调器，线程安全。
 * <p>
 * 隔离 Key 为 userId+threadId。
 * 同一 Key 同一时刻只能有一个 Lease。
 * 不同 threadId 允许并发。
 * 不同用户相同 threadId 允许并发。
 * Key 已占用时抛 THREAD_BUSY。
 * Lease 关闭后释放。
 * close 必须幂等。
 * <p>
 * 不得使用 static Map、ThreadLocal、全局 ReentrantLock 阻塞全部线程。
 * 可使用 ConcurrentHashMap 和原子占用标记。
 * 释放后应清理无用 Key。
 * 不得让 Map 随已结束线程无限增长。
 * 本批采用立即失败，不实现排队。
 * 不得实现分布式锁。
 * 不得记录 Session ID。
 */
public class ThreadExecutionCoordinator {

    /** 内部不可变复合 Key，不得与 ThreadCheckpointKey 混淆用途 */
    record LeaseKey(String userId, String threadId) {
        LeaseKey {
            userId = userId.trim();
            threadId = threadId.trim();
        }
    }

    private final ConcurrentHashMap<LeaseKey, ThreadExecutionLease> activeLeases = new ConcurrentHashMap<>();

    /**
     * 尝试获取线程执行租约。
     * <p>
     * Key 已占用时抛 THREAD_BUSY。
     *
     * @param userId   用户 ID
     * @param threadId 线程 ID
     * @return 执行租约
     */
    public ThreadExecutionLease acquire(String userId, String threadId) {
        Objects.requireNonNull(userId, "userId must not be null");
        Objects.requireNonNull(threadId, "threadId must not be null");

        if (userId.isBlank() || threadId.isBlank()) {
            throw new AgentFrameworkException(AgentErrorCode.INVALID_ARGUMENT,
                    "userId and threadId must not be blank");
        }

        LeaseKey key = new LeaseKey(userId, threadId);
        ThreadExecutionLease lease = new ThreadExecutionLease(key, this);

        ThreadExecutionLease existing = activeLeases.putIfAbsent(key, lease);
        if (existing != null) {
            throw new AgentFrameworkException(AgentErrorCode.THREAD_BUSY,
                    "Thread is already in use: userId=" + userId + ", threadId=" + threadId);
        }

        return lease;
    }

    /**
     * 释放租约。只在 lease 匹配时才移除。
     * <p>
     * 不得允许错误 Lease 释放其他请求的占用。
     */
    void release(LeaseKey key, ThreadExecutionLease lease) {
        // 只移除匹配的 lease，防止错误 lease 释放其他请求的占用
        activeLeases.remove(key, lease);
    }

    /**
     * 检查指定线程是否正在执行。
     *
     * @param userId   用户 ID
     * @param threadId 线程 ID
     * @return 是否有活跃 Lease
     */
    public boolean isBusy(String userId, String threadId) {
        LeaseKey key = new LeaseKey(userId, threadId);
        return activeLeases.containsKey(key);
    }
}
