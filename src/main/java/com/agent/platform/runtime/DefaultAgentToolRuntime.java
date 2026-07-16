package com.agent.platform.runtime;

import com.agent.platform.approval.ApprovalRecord;
import com.agent.platform.approval.ApprovalRequest;
import com.agent.platform.approval.ApprovalService;
import com.agent.platform.approval.ApprovalStatus;
import com.agent.platform.config.AgentProperties;
import com.agent.platform.guardrail.GuardrailAction;
import com.agent.platform.guardrail.GuardrailDecision;
import com.agent.platform.guardrail.GuardrailService;
import com.agent.platform.guardrail.ToolPolicyContext;
import com.agent.platform.tool.ToolCallRequest;
import com.agent.platform.tool.ToolCallResult;
import com.agent.platform.tool.ToolDefinition;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * 工具权限、审批、执行权抢占、有限重试和副作用结果落库的唯一入口。
 */
@Service
public class DefaultAgentToolRuntime implements AgentToolRuntime {

    private final GuardrailService guardrailService;
    private final ApprovalService approvalService;
    private final ToolExecutionStore toolExecutionStore;
    private final AgentCapabilityExecutor capabilityExecutor;
    private final AgentProperties properties;

    public DefaultAgentToolRuntime(GuardrailService guardrailService,
                                   ApprovalService approvalService,
                                   ToolExecutionStore toolExecutionStore,
                                   AgentCapabilityExecutor capabilityExecutor,
                                   AgentProperties properties) {
        this.guardrailService = guardrailService;
        this.approvalService = approvalService;
        this.toolExecutionStore = toolExecutionStore;
        this.capabilityExecutor = capabilityExecutor;
        this.properties = properties;
    }

    @Override
    public AgentToolRuntimeResult execute(String runId,
                                          String sessionId,
                                          String userId,
                                          Map<String, Object> attributes,// 刚开始请求传来的元数据
                                          AgentToolCall toolCall,
                                          ToolDefinition definition) {
        ToolCallRequest request = new ToolCallRequest(toolCall.toolName(), toolCall.toolCallId(), toolCall.arguments());
        ToolPolicyContext policyContext = ToolPolicyContext.from(runId, sessionId, userId, attributes);
        // 工具调用审查
        GuardrailDecision policy = guardrailService.checkToolCall(definition, request, policyContext);
        if (policy.action() == GuardrailAction.BLOCK) {
            return denied(request, policy);
        }
        if (policy.action() == GuardrailAction.REQUIRE_APPROVAL) {
            String approvalId = UUID.randomUUID().toString();
            // TODO 不能像 codex 一样在执行的过程中让用户批准吗？
            approvalService.requestApproval(new ApprovalRequest(
                    approvalId,
                    runId,
                    sessionId,
                    request,
                    policy.reason(),
                    Instant.now()
            ));
            return new AgentToolRuntimeResult(
                    AgentToolExecutionStatus.WAITING_APPROVAL,
                    request,
                    null,
                    policy.action(),
                    policy.reason(),
                    approvalId,
                    false
            );
        }
        return executeClaimed(runId, request, policy.action(), policy.reason());
    }

    @Override
    public AgentToolRuntimeResult executeApproved(ApprovalRecord approval,
                                                  ToolDefinition definition,
                                                  ToolPolicyContext context) {
        if (approval == null || approval.status() != ApprovalStatus.APPROVED) {
            throw new IllegalArgumentException("approved approval record is required");
        }
        ToolCallRequest request = approval.toolCallRequest();
        if (request == null || definition == null || !definition.name().equals(request.toolName())) {
            throw new IllegalArgumentException("approval tool does not match capability definition");
        }
        GuardrailDecision currentPolicy = guardrailService.checkToolCall(definition, request, context);
        if (currentPolicy.action() == GuardrailAction.BLOCK) {
            return denied(request, currentPolicy);
        }
        return executeClaimed(
                approval.runId(),
                request,
                GuardrailAction.ALLOW,
                "human approval satisfied: " + approval.approvalId()
        );
    }

    private AgentToolRuntimeResult executeClaimed(String runId,
                                                  ToolCallRequest request,
                                                  GuardrailAction policyAction,
                                                  String policyReason) {
        // "工具调用 claim 就是分布式幂等锁——同一个 toolCallId 全局只执行一次，已经执行过的直接返回缓存结果；如果同时多个请求抢执行权，数据库行锁保证只有一个赢
        ToolExecutionClaim claim = toolExecutionStore.claim(runId, request);
        // 没拿到执行权
        if (!claim.claimed()) {
            if (claim.state() == ToolExecutionState.SUCCEEDED && claim.cachedResult() != null) {
                return new AgentToolRuntimeResult(
                        AgentToolExecutionStatus.COMPLETED,
                        request,
                        claim.cachedResult(),
                        policyAction,
                        policyReason,
                        "",
                        true
                );
            }
            return new AgentToolRuntimeResult(
                    AgentToolExecutionStatus.MANUAL_REVIEW,
                    request,
                    claim.cachedResult(),
                    policyAction,
                    claim.reason(),
                    "",
                    true
            );
        }
        // 拿到执行权 → 真正执行
        ToolCallResult result = executeWithRetry(request);
        try {
            if (result.success()) {
                toolExecutionStore.markSucceeded(request.requestId(), result);
                return completed(request, result, policyAction, policyReason, false);
            }
            toolExecutionStore.markFailed(request.requestId(), result);
            return new AgentToolRuntimeResult(
                    AgentToolExecutionStatus.FAILED,
                    request,
                    result,
                    policyAction,
                    policyReason,
                    "",
                    false
            );
        }
        catch (RuntimeException persistenceFailure) {
            markManualReviewBestEffort(request.requestId(), "tool result persistence failed");
            return new AgentToolRuntimeResult(
                    AgentToolExecutionStatus.MANUAL_REVIEW,
                    request,
                    result,
                    policyAction,
                    "tool executed but result persistence is uncertain",
                    "",
                    false
            );
        }
    }

    private ToolCallResult executeWithRetry(ToolCallRequest request) {
        int maxAttempts = Math.max(1, properties.getMaxToolExecutionAttempts());
        ToolCallResult lastResult = null;
        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            try {
                // 工具执行
                lastResult = capabilityExecutor.execute(request);
            }
            catch (RuntimeException exception) {
                lastResult = new ToolCallResult(
                        request.toolName(),
                        false,
                        "",
                        "capability execution failed: " + exception.getClass().getSimpleName(),
                        Map.of("attempt", attempt, "retryable", true)
                );
            }
            if (lastResult.success() || !retryable(lastResult) || attempt >= maxAttempts) {
                return withAttemptMetadata(lastResult, attempt);
            }
            sleepBackoff(attempt);
        }
        return lastResult == null
                ? new ToolCallResult(request.toolName(), false, "", "capability returned no result", Map.of())
                : lastResult;
    }

    private AgentToolRuntimeResult denied(ToolCallRequest request, GuardrailDecision policy) {
        ToolCallResult result = new ToolCallResult(
                request.toolName(),
                false,
                "",
                "permission denied: " + policy.reason(),
                Map.of("policyAction", policy.action().name())
        );
        return new AgentToolRuntimeResult(
                AgentToolExecutionStatus.DENIED,
                request,
                result,
                policy.action(),
                policy.reason(),
                "",
                false
        );
    }

    private AgentToolRuntimeResult completed(ToolCallRequest request,
                                             ToolCallResult result,
                                             GuardrailAction policyAction,
                                             String policyReason,
                                             boolean reused) {
        return new AgentToolRuntimeResult(
                AgentToolExecutionStatus.COMPLETED,
                request,
                result,
                policyAction,
                policyReason,
                "",
                reused
        );
    }

    private ToolCallResult withAttemptMetadata(ToolCallResult result, int attempt) {
        java.util.LinkedHashMap<String, Object> metadata = new java.util.LinkedHashMap<>(result.metadata());
        metadata.put("attempts", attempt);
        return new ToolCallResult(
                result.toolName(),
                result.success(),
                result.content(),
                result.errorMessage(),
                metadata
        );
    }

    private boolean retryable(ToolCallResult result) {
        Object explicit = result.metadata().get("retryable");
        if (explicit instanceof Boolean bool) {
            return bool;
        }
        String error = result.errorMessage() == null ? "" : result.errorMessage().toLowerCase(Locale.ROOT);
        return error.contains("timeout")
                || error.contains("temporar")
                || error.contains("unavailable")
                || error.contains("connection")
                || error.contains("ioexception");
    }

    private void sleepBackoff(int attempt) {
        long delay = Math.max(0, properties.getToolRetryBackoffMillis()) * attempt;
        if (delay <= 0) {
            return;
        }
        try {
            Thread.sleep(delay);
        }
        catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
        }
    }

    private void markManualReviewBestEffort(String toolCallId, String reason) {
        try {
            toolExecutionStore.markManualReview(toolCallId, reason);
        }
        catch (RuntimeException ignored) {
            // The caller already receives MANUAL_REVIEW; preserve the original uncertain state.
        }
    }
}
