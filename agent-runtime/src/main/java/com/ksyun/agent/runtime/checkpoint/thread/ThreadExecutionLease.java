package com.ksyun.agent.runtime.checkpoint.thread;

/**
 * 线程执行租约，实现 AutoCloseable。
 * <p>
 * close 必须幂等。
 * 同一 Lease 重复 close 不得抛异常。
 * 不得允许错误 Lease 释放其他请求的占用。
 * 不得记录 Session ID。
 */
public class ThreadExecutionLease implements AutoCloseable {

    private final ThreadExecutionCoordinator.LeaseKey key;
    private final ThreadExecutionCoordinator coordinator;
    private volatile boolean closed = false;

    ThreadExecutionLease(ThreadExecutionCoordinator.LeaseKey key,
                          ThreadExecutionCoordinator coordinator) {
        this.key = key;
        this.coordinator = coordinator;
    }

    /**
     * 释放租约，幂等。
     */
    @Override
    public void close() {
        if (!closed) {
            closed = true;
            coordinator.release(key, this);
        }
    }

    /**
     * 租约是否已关闭。
     */
    public boolean isClosed() {
        return closed;
    }
}
