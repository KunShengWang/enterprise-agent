package com.agent.platform.runtime;

import java.time.Duration;

/**
 * 多实例共享的会话执行租约与取消信号持久化边界。
 */
public interface AgentRunControlStore {

    void acquireSessionLease(String sessionId, String runId, Duration leaseDuration);

    boolean renewSessionLease(String sessionId, String runId, Duration leaseDuration);

    void releaseSessionLease(String sessionId, String runId);

    boolean requestCancellation(String runId);

    boolean cancellationRequested(String runId);
}
