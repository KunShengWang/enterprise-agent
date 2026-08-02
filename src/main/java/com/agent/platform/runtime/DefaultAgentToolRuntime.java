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
import org.springframework.beans.factory.annotation.Autowired;

import java.time.Instant;
import java.util.Locale;
import java.util.Map;
import java.util.List;
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
    private final List<ApprovalToolCallRequestPreparer> approvalRequestPreparers;
    private final List<UncertainToolExecutionResolver> uncertainExecutionResolvers;

    @Autowired
    public DefaultAgentToolRuntime(GuardrailService guardrailService,
                                   ApprovalService approvalService,
                                   ToolExecutionStore toolExecutionStore,
                                   AgentCapabilityExecutor capabilityExecutor,
                                   AgentProperties properties,
                                   List<ApprovalToolCallRequestPreparer> approvalRequestPreparers,
                                   List<UncertainToolExecutionResolver> uncertainExecutionResolvers) {
        this.guardrailService = guardrailService;
        this.approvalService = approvalService;
        this.toolExecutionStore = toolExecutionStore;
        this.capabilityExecutor = capabilityExecutor;
        this.properties = properties;
        this.approvalRequestPreparers = approvalRequestPreparers == null
                ? List.of() : List.copyOf(approvalRequestPreparers);
        this.uncertainExecutionResolvers = uncertainExecutionResolvers == null
                ? List.of() : List.copyOf(uncertainExecutionResolvers);
    }

    DefaultAgentToolRuntime(GuardrailService guardrailService,
                            ApprovalService approvalService,
                            ToolExecutionStore toolExecutionStore,
                            AgentCapabilityExecutor capabilityExecutor,
                            AgentProperties properties,
                            List<ApprovalToolCallRequestPreparer> approvalRequestPreparers) {
        this(guardrailService, approvalService, toolExecutionStore, capabilityExecutor,
                properties, approvalRequestPreparers, List.of());
    }

    DefaultAgentToolRuntime(GuardrailService guardrailService,
                            ApprovalService approvalService,
                            ToolExecutionStore toolExecutionStore,
                            AgentCapabilityExecutor capabilityExecutor,
                            AgentProperties properties) {
        this(guardrailService, approvalService, toolExecutionStore, capabilityExecutor,
                properties, List.of(), List.of());
    }

    @Override
    public AgentToolRuntimeResult execute(String runId,
                                          String sessionId,
                                          String userId,
                                          Map<String, Object> attributes,// 刚开始请求传来的元数据
                                          AgentToolCall toolCall,
                                          ToolDefinition definition) {
        ToolCallRequest request = new ToolCallRequest(toolCall.toolName(), toolCall.toolCallId(), toolCall.arguments());
        // 把当前 Agent Run、会话、用户、租户、角色和请求属性封装成统一的工具策略上下文，供后续 Guardrail 权限判断和审批请求绑定使用
        ToolPolicyContext policyContext = ToolPolicyContext.from(runId, sessionId, userId, attributes);
        // 工具调用审查
        GuardrailDecision policy = guardrailService.checkToolCall(definition, request, policyContext);
        if (policy.action() == GuardrailAction.BLOCK) {
            return denied(request, policy);
        }
        if (policy.action() == GuardrailAction.REQUIRE_APPROVAL) {
            String approvalId = UUID.randomUUID().toString();
            ToolCallRequest approvalBoundRequest = prepareApprovalRequest(
                    approvalId,
                    request,
                    policyContext
            );
            // TODO 不能像 codex 一样在执行的过程中让用户批准吗？
            approvalService.requestApproval(new ApprovalRequest(
                    approvalId,
                    runId,
                    sessionId,
                    approvalBoundRequest,
                    policy.reason(),
                    Instant.now()
            ));
            return new AgentToolRuntimeResult(
                    AgentToolExecutionStatus.WAITING_APPROVAL,
                    approvalBoundRequest,
                    null,
                    policy.action(),
                    policy.reason(),
                    approvalId,
                    false
            );
        }
        return executeClaimed(runId, request, policy.action(), policy.reason());
    }

    private ToolCallRequest prepareApprovalRequest(String approvalId,
                                                   ToolCallRequest request,
                                                   ToolPolicyContext context) {
        ToolCallRequest prepared = request;
        for (ApprovalToolCallRequestPreparer preparer : approvalRequestPreparers) {
            if (preparer.supports(prepared.toolName())) {
                prepared = preparer.prepare(approvalId, prepared, context);
            }
        }
        return prepared;
    }

    /**
     * 工具的执行：
     * 1、判断工具是否审批通过
     * 2、工具执行护栏检查
     * 3、同一个 toolCallId 全局只执行一次，已经执行过的直接返回缓存结果
     * 4、拿到工具执行权，执行工具
     * 5、判断工具执行是成功、失败还是进入人工审批
     */
    @Override
    public AgentToolRuntimeResult executeApproved(ApprovalRecord approval,
                                                  ToolDefinition definition,
                                                  ToolPolicyContext context) {
        // 工具的执行必须是审批通过
        if (approval == null || approval.status() != ApprovalStatus.APPROVED) {
            throw new IllegalArgumentException("approved approval record is required");
        }
        // 工具调用的参数
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
            // 没拿到执行权，但是之前执行成功过，直接拿缓存结果
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
            // 能走到 !claim.claimed() 的失败场景只有一种：跨 run 冲突——另一个 run 占了这个 toolCallId。这种情况不能重试，只能人工判断
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
        ToolCallResult result = executeWithRetry(request);// 有最大重试次数的执行工具
        try {
            if (result.success()) {
                toolExecutionStore.markSucceeded(request.requestId(), result);
                return completed(request, result, policyAction, policyReason, false);
            }
            // 人工审批
            if (manualReview(result)) {
                toolExecutionStore.markManualReview(request.requestId(), result.errorMessage());
                return new AgentToolRuntimeResult(
                        AgentToolExecutionStatus.MANUAL_REVIEW,
                        request,
                        result,
                        policyAction,
                        "tool outcome requires manual review",
                        "",
                        false
                );
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

    @Override
    public ToolExecutionRecord reconcileUncertain(ToolExecutionRecord execution) {
        if (execution == null || execution.state() != ToolExecutionState.RUNNING) {
            return execution;
        }
        for (UncertainToolExecutionResolver resolver : uncertainExecutionResolvers) {
            if (!resolver.supports(execution)) continue;
            try {
                ToolCallResult result = resolver.resolve(execution);
                if (result == null) return execution;
                if (result.success()) {
                    toolExecutionStore.markSucceeded(execution.toolCallId(), result);
                } else if (manualReview(result)) {
                    toolExecutionStore.markManualReview(execution.toolCallId(), result.errorMessage());
                } else {
                    toolExecutionStore.markFailed(execution.toolCallId(), result);
                }
            } catch (RuntimeException exception) {
                markManualReviewBestEffort(
                        execution.toolCallId(),
                        "uncertain tool reconciliation failed: " + exception.getClass().getSimpleName()
                );
            }
            return toolExecutionStore.findToolExecution(execution.toolCallId()).orElse(execution);
        }
        return execution;
    }

    private ToolCallResult executeWithRetry(ToolCallRequest request) {
        // 工具的执行有最大重试次数
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
            if (lastResult.success() // ① 成功了 → 不用重试
                    || !retryable(lastResult) // ② 失败了但不可重试（如权限不足）→ 重试也没用
                    || attempt >= maxAttempts // ③ 重试次数用完了 → 不能再试了
            ) {
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

    private boolean manualReview(ToolCallResult result) {
        return Boolean.TRUE.equals(result.metadata().get("manualReview"));
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
