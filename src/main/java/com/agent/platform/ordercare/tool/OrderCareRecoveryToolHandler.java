package com.agent.platform.ordercare.tool;

import com.agent.platform.approval.ApprovalRecord;
import com.agent.platform.approval.ApprovalService;
import com.agent.platform.approval.ApprovalStatus;
import com.agent.platform.ordercare.application.OrderCareProposalBinding;
import com.agent.platform.ordercare.application.OrderCareProposalBindingStore;
import com.agent.platform.ordercare.application.RecoveryConvergenceChecker;
import com.agent.platform.ordercare.client.FlowOrderApiException;
import com.agent.platform.ordercare.client.FlowOrderClient;
import com.agent.platform.ordercare.model.OrderCareConvergenceResult;
import com.agent.platform.ordercare.model.OrderCareProposalCreateCommand;
import com.agent.platform.ordercare.model.OrderCareProposalExecuteCommand;
import com.agent.platform.ordercare.model.OrderCareRecoveryProposal;
import com.agent.platform.runtime.ToolExecutionRecord;
import com.agent.platform.runtime.ToolExecutionStore;
import com.agent.platform.tool.ToolCallRequest;
import com.agent.platform.tool.ToolCallResult;
import com.agent.platform.tool.ToolHandler;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

@Component
public class OrderCareRecoveryToolHandler implements ToolHandler {

    private final FlowOrderClient flowOrderClient;
    private final ToolExecutionStore toolExecutionStore;
    private final OrderCareProposalBindingStore bindingStore;
    private final ApprovalService approvalService;
    private final RecoveryConvergenceChecker convergenceChecker;
    private final ObjectMapper objectMapper;

    public OrderCareRecoveryToolHandler(FlowOrderClient flowOrderClient,
                                        ToolExecutionStore toolExecutionStore,
                                        OrderCareProposalBindingStore bindingStore,
                                        ApprovalService approvalService,
                                        RecoveryConvergenceChecker convergenceChecker,
                                        ObjectMapper objectMapper) {
        this.flowOrderClient = flowOrderClient;
        this.toolExecutionStore = toolExecutionStore;
        this.bindingStore = bindingStore;
        this.approvalService = approvalService;
        this.convergenceChecker = convergenceChecker;
        this.objectMapper = objectMapper;
    }

    @Override
    public boolean supports(String toolName) {
        return OrderCareToolCatalog.RECOVERY_PREVIEW.equals(toolName)
                || OrderCareToolCatalog.RECOVERY_EXECUTE.equals(toolName);
    }

    @Override
    public ToolCallResult execute(ToolCallRequest request) {
        if (OrderCareToolCatalog.RECOVERY_PREVIEW.equals(request.toolName())) {
            return preview(request);
        }
        return executeApprovedProposal(request);
    }

    private ToolCallResult preview(ToolCallRequest request) {
        try {
            String runId = currentRunId(request.requestId());
            String identifierType = requiredString(request, "identifierType");
            String identifierValue = requiredString(request, "identifierValue");
            String proposalId = stableProposalId(request.requestId());
            OrderCareRecoveryProposal proposal = flowOrderClient.createProposal(
                    new OrderCareProposalCreateCommand(
                            proposalId,
                            identifierType,
                            identifierValue,
                            "REPLAY",
                            optionalString(request, "suggestedReason")
                    ),
                    request.requestId()
            );
            bindingStore.bind(new OrderCareProposalBinding(
                    proposal.proposalId(),
                    proposal.actionRequestId(),
                    proposal.caseKey(),
                    request.requestId(),
                    runId,
                    proposal,
                    Instant.now()
            ));
            return success(
                    request,
                    proposal,
                    Map.of(
                            "readOnly", true,
                            "proposalId", proposal.proposalId(),
                            "proposalStatus", proposal.proposalStatus(),
                            "previewDigest", proposal.previewDigest(),
                            "canExecute", Boolean.TRUE.equals(proposal.canExecute())
                    )
            );
        } catch (FlowOrderApiException exception) {
            return flowFailure(request, exception, true);
        } catch (RuntimeException exception) {
            return localFailure(request, exception, true);
        }
    }

    private ToolCallResult executeApprovedProposal(ToolCallRequest request) {
        try {
            String runId = currentRunId(request.requestId());
            String proposalId = requiredString(request, "proposalId");
            bindingStore.requireForRun(proposalId, runId);
            String approvalId = requiredString(request, "approvalId");
            ApprovalRecord approval = approvalService.find(approvalId)
                    .filter(item -> item.status() == ApprovalStatus.APPROVED)
                    .orElseThrow(() -> new IllegalArgumentException("approved ApprovalRecord not found"));
            if (!runId.equals(approval.runId())) {
                throw new IllegalArgumentException("Approval 不属于当前 Run");
            }
            OrderCareRecoveryProposal execution = flowOrderClient.executeProposal(
                    new OrderCareProposalExecuteCommand(
                            proposalId,
                            integerArgument(request, "proposalVersion"),
                            requiredString(request, "stateFingerprint"),
                            requiredString(request, "effectsDigest"),
                            requiredString(request, "warningsDigest"),
                            requiredString(request, "previewDigest"),
                            approval.approvalId(),
                            approval.reviewer(),
                            approval.decisionReason()
                    ),
                    request.requestId()
            );
            OrderCareConvergenceResult convergence = convergenceChecker.await(
                    proposalId,
                    request.requestId()
            );
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("execution", execution);
            payload.put("convergence", convergence);
            return success(
                    request,
                    payload,
                    Map.of(
                            "readOnly", false,
                            "sideEffect", true,
                            "retryable", false,
                            "approvalId", approvalId,
                            "proposalId", proposalId,
                            "actionStatus", convergence.actionStatus(),
                            "caseOutcome", convergence.caseOutcome(),
                            "convergenceStatus", convergence.status()
                    )
            );
        } catch (FlowOrderApiException exception) {
            return flowFailure(request, exception, false);
        } catch (RuntimeException exception) {
            return localFailure(request, exception, false);
        }
    }

    private ToolCallResult success(ToolCallRequest request,
                                   Object content,
                                   Map<String, Object> details) {
        Map<String, Object> metadata = baseMetadata();
        metadata.putAll(details);
        return new ToolCallResult(
                request.toolName(),
                true,
                objectMapper.writeValueAsString(content),
                "",
                metadata
        );
    }

    private ToolCallResult flowFailure(ToolCallRequest request,
                                       FlowOrderApiException exception,
                                       boolean readOnly) {
        Map<String, Object> metadata = baseMetadata();
        metadata.put("readOnly", readOnly);
        metadata.put("statusCode", exception.statusCode());
        metadata.put("retryable", readOnly && exception.retryable());
        metadata.put("outcome", exception.outcomeUnknown() ? "UNKNOWN" : "REJECTED");
        metadata.put("manualReview", exception.outcomeUnknown());
        return new ToolCallResult(
                request.toolName(),
                false,
                "",
                "FlowOrder recovery call failed: " + exception.getMessage(),
                metadata
        );
    }

    private ToolCallResult localFailure(ToolCallRequest request,
                                        RuntimeException exception,
                                        boolean readOnly) {
        Map<String, Object> metadata = baseMetadata();
        metadata.put("readOnly", readOnly);
        metadata.put("retryable", false);
        metadata.put("outcome", "REJECTED");
        return new ToolCallResult(
                request.toolName(),
                false,
                "",
                "OrderCare recovery coordination failed: " + exception.getClass().getSimpleName(),
                metadata
        );
    }

    private Map<String, Object> baseMetadata() {
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("provider", "floworder");
        metadata.put("domain", "ordercare");
        metadata.put("contractVersion", "floworder-recovery-proposal-v1");
        return metadata;
    }

    private String currentRunId(String toolExecutionId) {
        ToolExecutionRecord record = toolExecutionStore.findToolExecution(toolExecutionId)
                .orElseThrow(() -> new IllegalStateException("tool execution context not found"));
        if (record.runId() == null || record.runId().isBlank()) {
            throw new IllegalStateException("tool execution runId not found");
        }
        return record.runId();
    }

    private String stableProposalId(String toolExecutionId) {
        UUID value = UUID.nameUUIDFromBytes(
                ("ordercare-proposal:" + toolExecutionId).getBytes(StandardCharsets.UTF_8)
        );
        return "prop-" + value;
    }

    private String requiredString(ToolCallRequest request, String name) {
        String value = optionalString(request, name);
        if (value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return value;
    }

    private String optionalString(ToolCallRequest request, String name) {
        Object value = request.arguments().get(name);
        return value == null ? "" : String.valueOf(value).trim();
    }

    private Integer integerArgument(ToolCallRequest request, String name) {
        Object value = request.arguments().get(name);
        if (value instanceof Number number) {
            return number.intValue();
        }
        try {
            return Integer.valueOf(String.valueOf(value));
        } catch (RuntimeException exception) {
            throw new IllegalArgumentException(name + " must be an integer", exception);
        }
    }
}
