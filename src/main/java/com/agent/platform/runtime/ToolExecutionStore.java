package com.agent.platform.runtime;

import com.agent.platform.tool.ToolCallRequest;
import com.agent.platform.tool.ToolCallResult;

import java.util.List;
import java.util.Optional;

public interface ToolExecutionStore {

    /**
     * "工具调用 claim 就是分布式幂等锁——同一个 toolCallId 全局只执行一次，已经执行过的直接返回缓存结果；如果同时多个请求抢执行权，数据库行锁保证只有一个赢
     */
    ToolExecutionClaim claim(String runId, ToolCallRequest request);

    void markSucceeded(String toolCallId, ToolCallResult result);

    void markFailed(String toolCallId, ToolCallResult result);

    void markManualReview(String toolCallId, String reason);

    Optional<ToolExecutionRecord> findToolExecution(String toolCallId);

    List<ToolExecutionRecord> findByRun(String runId);
}
