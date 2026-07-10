package com.agent.platform.runtime;

import com.agent.platform.tool.ToolCallRequest;
import com.agent.platform.tool.ToolCallResult;

import java.util.List;
import java.util.Optional;

public interface ToolExecutionStore {

    ToolExecutionClaim claim(String runId, ToolCallRequest request);

    void markSucceeded(String toolCallId, ToolCallResult result);

    void markFailed(String toolCallId, ToolCallResult result);

    void markManualReview(String toolCallId, String reason);

    Optional<ToolExecutionRecord> findToolExecution(String toolCallId);

    List<ToolExecutionRecord> findByRun(String runId);
}
