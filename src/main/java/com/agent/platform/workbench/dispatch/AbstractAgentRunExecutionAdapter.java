package com.agent.platform.workbench.dispatch;

import com.agent.platform.agent.AgentExecutor;
import com.agent.platform.agent.AgentRequest;
import com.agent.platform.agent.AgentResponse;
import com.agent.platform.runtime.AgentRunRecord;
import com.agent.platform.runtime.AgentRunStore;
import com.agent.platform.workbench.model.WorkLinkType;
import com.agent.platform.workbench.target.ExecutionTargetId;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

abstract class AbstractAgentRunExecutionAdapter implements ExecutionAdapter {

    private final AgentExecutor executor;
    private final AgentRunStore runStore;

    AbstractAgentRunExecutionAdapter(AgentExecutor executor, AgentRunStore runStore) {
        this.executor = executor;
        this.runStore = runStore;
    }

    protected abstract String scenarioId();

    @Override
    public DispatchResult dispatch(DispatchRequest request) {
        // 先对账（幂等）
        Optional<DispatchResult> existing = reconcile(request);
        if (existing.isPresent()) return existing.get();// 已经派发过 → 直接复用
        // 组装元数据
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put(AgentRunStore.DISPATCH_REQUEST_METADATA_KEY, request.dispatchRequestId());
        metadata.put("workItemId", request.workItemId());
        metadata.put("executionTarget", targetId().name());
        metadata.put("validatedInputDigest", request.validatedInput().inputDigest());
        metadata.put("validatedInput", request.validatedInput().typedPayload());
        metadata.putAll(additionalMetadata(request));
        try {
            // 启动 Agent Run
            AgentResponse response = executor.execute(new AgentRequest(
                    request.conversationId(), request.principal().principalId(), request.goalText(),
                    Map.copyOf(metadata), scenarioId()));
            return new DispatchResult(request.dispatchRequestId(), WorkLinkType.RUN, response.runId(), true);
        }
        catch (RuntimeException exception) {
            return reconcile(request).orElseThrow(() -> exception);
        }
    }

    protected Map<String, Object> additionalMetadata(DispatchRequest request) {
        return Map.of();
    }

    @Override
    public Optional<DispatchResult> reconcile(DispatchRequest request) {
        return runStore.findByDispatchRequestId(request.dispatchRequestId())
                .map(AgentRunRecord::runId)
                .map(runId -> new DispatchResult(
                        request.dispatchRequestId(), WorkLinkType.RUN, runId, false));
    }
}
