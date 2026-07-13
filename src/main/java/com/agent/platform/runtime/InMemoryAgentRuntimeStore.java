package com.agent.platform.runtime;

import com.agent.platform.tool.ToolCallRequest;
import com.agent.platform.tool.ToolCallResult;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.function.UnaryOperator;

@Deprecated(forRemoval = true)
public class InMemoryAgentRuntimeStore implements AgentRunStore, ToolExecutionStore {

    private final ConcurrentMap<String, AgentRunRecord> runs = new ConcurrentHashMap<>();

    private final ConcurrentMap<String, ToolExecutionRecord> toolExecutions = new ConcurrentHashMap<>();

    @Override
    public AgentRunRecord create(AgentRunRecord record) {
        AgentRunRecord previous = runs.putIfAbsent(record.runId(), record);
        if (previous != null) {
            throw new IllegalStateException("agent run already exists: " + record.runId());
        }
        return record;
    }

    @Override
    public Optional<AgentRunRecord> find(String runId) {
        return Optional.ofNullable(runId == null ? null : runs.get(runId));
    }

    @Override
    public List<AgentRunRecord> recent(int limit) {
        return runs.values().stream()
                .sorted(Comparator.comparing(AgentRunRecord::updatedAt).reversed())
                .limit(Math.max(1, limit))
                .toList();
    }

    @Override
    public AgentRunRecord update(String runId, UnaryOperator<AgentRunRecord> updater) {
        AgentRunRecord updated = runs.compute(runId, (ignored, current) -> {
            if (current == null) {
                throw new IllegalArgumentException("agent run not found: " + runId);
            }
            AgentRunRecord next = updater.apply(current);
            return next.withVersion(current.version() + 1, java.time.Instant.now());
        });
        return updated;
    }

    @Override
    public Optional<AgentRunRecord> claimForResume(String runId) {
        final AgentRunRecord[] claimed = new AgentRunRecord[1];
        runs.compute(runId, (ignored, current) -> {
            if (current == null || current.state() != AgentRunState.WAITING_APPROVAL) {
                return current;
            }
            AgentRunRecord next = current.claimedForResume()
                    .withVersion(current.version() + 1, java.time.Instant.now());
            claimed[0] = next;
            return next;
        });
        return Optional.ofNullable(claimed[0]);
    }

    @Override
    public synchronized ToolExecutionClaim claim(String runId, ToolCallRequest request) {
        ToolExecutionRecord current = toolExecutions.get(request.requestId());
        if (current == null) {
            toolExecutions.put(request.requestId(), ToolExecutionRecord.running(runId, request));
            return ToolExecutionClaim.acquired();
        }
        if (current.state() == ToolExecutionState.SUCCEEDED) {
            return ToolExecutionClaim.existing(current, "toolCallId already succeeded");
        }
        if (current.state() == ToolExecutionState.FAILED) {
            toolExecutions.put(request.requestId(), current.retrying());
            return ToolExecutionClaim.acquired();
        }
        return ToolExecutionClaim.existing(current, "toolCallId has uncertain or in-progress result");
    }

    @Override
    public synchronized void markSucceeded(String toolCallId, ToolCallResult result) {
        updateTool(toolCallId, ToolExecutionState.SUCCEEDED, result, "");
    }

    @Override
    public synchronized void markFailed(String toolCallId, ToolCallResult result) {
        updateTool(toolCallId, ToolExecutionState.FAILED, result, result == null ? "tool failed" : result.errorMessage());
    }

    @Override
    public synchronized void markManualReview(String toolCallId, String reason) {
        ToolExecutionRecord current = toolExecutions.get(toolCallId);
        if (current != null && current.state() != ToolExecutionState.SUCCEEDED) {
            toolExecutions.put(toolCallId, current.withResult(ToolExecutionState.MANUAL_REVIEW, current.result(), reason));
        }
    }

    @Override
    public Optional<ToolExecutionRecord> findToolExecution(String toolCallId) {
        return Optional.ofNullable(toolCallId == null ? null : toolExecutions.get(toolCallId));
    }

    @Override
    public List<ToolExecutionRecord> findByRun(String runId) {
        return toolExecutions.values().stream()
                .filter(record -> record.runId().equals(runId))
                .sorted(Comparator.comparing(ToolExecutionRecord::updatedAt))
                .toList();
    }

    private void updateTool(String toolCallId,
                            ToolExecutionState state,
                            ToolCallResult result,
                            String error) {
        ToolExecutionRecord current = toolExecutions.get(toolCallId);
        if (current == null) {
            throw new IllegalArgumentException("tool execution not found: " + toolCallId);
        }
        toolExecutions.put(toolCallId, current.withResult(state, result, error));
    }
}
