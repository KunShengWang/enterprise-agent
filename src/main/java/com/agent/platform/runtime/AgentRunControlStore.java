package com.agent.platform.runtime;

import java.time.Duration;

/**
 * 多实例共享的会话执行租约与取消信号持久化边界。
 */
public interface AgentRunControlStore {

    /**
     * 获取 session 租赁，防止同一 session 被多个 run 并发执行
     */
    void acquireSessionLease(String sessionId, String runId, String leaseOwnerId, Duration leaseDuration);

    /**
     * 更新 session 租约
     */
    boolean renewSessionLease(String sessionId, String leaseOwnerId, Duration leaseDuration);

    /**
     * 释放 session 租约
     */
    void releaseSessionLease(String sessionId, String leaseOwnerId);

    boolean requestCancellation(String runId);

    /**
     * 当前 agent 请求暂停，数据库持久化暂停标志
     */
    boolean requestPause(String runId);

    boolean pauseRequested(String runId);

    boolean clearPauseRequest(String runId);

    /**
     * 查看是否有 agent 的取消请求
     */
    boolean cancellationRequested(String runId);
}
