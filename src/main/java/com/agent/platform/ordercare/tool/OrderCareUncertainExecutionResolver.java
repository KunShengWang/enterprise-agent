package com.agent.platform.ordercare.tool;

import com.agent.platform.ordercare.application.OrderCareProposalBinding;
import com.agent.platform.ordercare.application.OrderCareProposalBindingStore;
import com.agent.platform.ordercare.application.RecoveryOutcomeReconciler;
import com.agent.platform.ordercare.model.OrderCareProposalExecuteCommand;
import com.agent.platform.ordercare.model.OrderCareRecoveryReconciliationResult;
import com.agent.platform.runtime.ToolExecutionRecord;
import com.agent.platform.runtime.UncertainToolExecutionResolver;
import com.agent.platform.tool.ToolCallRequest;
import com.agent.platform.tool.ToolCallResult;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

import java.util.LinkedHashMap;
import java.util.Map;

/** Runtime 重启后用 FlowOrder Action 事实恢复原工具执行，不再次向模型询问参数。 */
@Component
public class OrderCareUncertainExecutionResolver implements UncertainToolExecutionResolver {

    private final OrderCareProposalBindingStore bindingStore;
    private final RecoveryOutcomeReconciler outcomeReconciler;
    private final ObjectMapper objectMapper;

    public OrderCareUncertainExecutionResolver(OrderCareProposalBindingStore bindingStore,
                                               RecoveryOutcomeReconciler outcomeReconciler,
                                               ObjectMapper objectMapper) {
        this.bindingStore = bindingStore;
        this.outcomeReconciler = outcomeReconciler;
        this.objectMapper = objectMapper;
    }

    @Override
    public boolean supports(ToolExecutionRecord execution) {
        return execution != null
                && OrderCareToolCatalog.RECOVERY_EXECUTE.equals(execution.toolName())
                && execution.request() != null;
    }

    @Override
    public ToolCallResult resolve(ToolExecutionRecord execution) {
        ToolCallRequest request = execution.request();
        String proposalId = requiredString(request, "proposalId");
        OrderCareProposalBinding binding = bindingStore.requireForRun(proposalId, execution.runId());
        OrderCareProposalExecuteCommand command = new OrderCareProposalExecuteCommand(
                proposalId,
                integerArgument(request, "proposalVersion"),
                requiredString(request, "stateFingerprint"),
                requiredString(request, "effectsDigest"),
                requiredString(request, "warningsDigest"),
                requiredString(request, "previewDigest"),
                requiredString(request, "approvalId"),
                requiredString(request, "approvedBy"),
                optionalString(request, "approvalComment"),
                execution.toolCallId()
        );
        OrderCareRecoveryReconciliationResult reconciliation = outcomeReconciler.reconcile(
                binding.immutablePreview(),
                command,
                execution.toolCallId(),
                execution.toolCallId(),
                true
        );
        boolean resolved = "RESOLVED".equals(reconciliation.status());
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("provider", "floworder");
        metadata.put("domain", "ordercare");
        metadata.put("contractVersion", "floworder-recovery-action-v1");
        metadata.put("readOnly", false);
        metadata.put("sideEffect", true);
        metadata.put("retryable", false);
        metadata.put("manualReview", !resolved);
        metadata.put("outcome", reconciliation.status());
        metadata.put("reconciled", resolved);
        metadata.put("recoveredAfterCrash", true);
        metadata.put("proposalId", binding.proposalId());
        metadata.put("actionRequestId", binding.actionRequestId());
        metadata.put("approvalId", requiredString(request, "approvalId"));
        metadata.put("reconciliationAttempts", reconciliation.attempts());
        metadata.put("executeReissuedWithSameId", reconciliation.executeReissuedWithSameId());
        return new ToolCallResult(
                request.toolName(),
                resolved,
                resolved ? objectMapper.writeValueAsString(reconciliation) : "",
                resolved ? "" : "OrderCare crash recovery could not prove the side effect outcome",
                metadata
        );
    }

    private String requiredString(ToolCallRequest request, String name) {
        String value = optionalString(request, name);
        if (value.isBlank()) throw new IllegalArgumentException(name + " is required");
        return value;
    }

    private String optionalString(ToolCallRequest request, String name) {
        Object value = request.arguments().get(name);
        return value == null ? "" : String.valueOf(value).trim();
    }

    private int integerArgument(ToolCallRequest request, String name) {
        Object value = request.arguments().get(name);
        if (value instanceof Number number) return number.intValue();
        try {
            return Integer.parseInt(String.valueOf(value));
        } catch (RuntimeException exception) {
            throw new IllegalArgumentException(name + " must be an integer", exception);
        }
    }
}
