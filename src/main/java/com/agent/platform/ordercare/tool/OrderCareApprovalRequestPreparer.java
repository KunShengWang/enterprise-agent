package com.agent.platform.ordercare.tool;

import com.agent.platform.guardrail.ToolPolicyContext;
import com.agent.platform.ordercare.application.OrderCareProposalBinding;
import com.agent.platform.ordercare.application.OrderCareProposalBindingStore;
import com.agent.platform.ordercare.client.FlowOrderClient;
import com.agent.platform.ordercare.model.OrderCareRecoveryProposal;
import com.agent.platform.runtime.ApprovalToolCallRequestPreparer;
import com.agent.platform.tool.ToolCallRequest;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/** 审批卡只使用服务端保存的不可变 preview，不采信模型复述。 */
@Component
public class OrderCareApprovalRequestPreparer implements ApprovalToolCallRequestPreparer {

    private final OrderCareProposalBindingStore bindingStore;
    private final FlowOrderClient flowOrderClient;

    public OrderCareApprovalRequestPreparer(OrderCareProposalBindingStore bindingStore,
                                            FlowOrderClient flowOrderClient) {
        this.bindingStore = bindingStore;
        this.flowOrderClient = flowOrderClient;
    }

    @Override
    public boolean supports(String toolName) {
        return OrderCareToolCatalog.RECOVERY_EXECUTE.equals(toolName);
    }

    @Override
    public ToolCallRequest prepare(String approvalId,
                                   ToolCallRequest request,
                                   ToolPolicyContext context) {
        // 把参数字符串化，如："prop-2239556c-1101-3459-bb4d-954b8351b2be"
        String proposalId = stringArgument(request.arguments(), "proposalId");
        // 检查这个 proposalId 是不是当前这次 Agent 运行自己创建的
        OrderCareProposalBinding binding = bindingStore.requireForRun(proposalId, context.runId());
        OrderCareRecoveryProposal current = flowOrderClient.getProposal(proposalId, request.requestId());
        ensureSameImmutablePreview(binding.immutablePreview(), current);
        if (!"ACTIVE".equals(current.proposalStatus()) || !Boolean.TRUE.equals(current.canExecute())) {
            throw new IllegalArgumentException("Proposal 当前不可审批执行：" + current.proposalStatus());
        }

        Map<String, Object> trusted = new LinkedHashMap<>();
        trusted.put("proposalId", current.proposalId());
        trusted.put("proposalVersion", current.proposalVersion());
        trusted.put("stateFingerprint", current.stateFingerprint());
        trusted.put("effectsDigest", current.effectsDigest());
        trusted.put("warningsDigest", current.warningsDigest());
        trusted.put("previewDigest", current.previewDigest());
        trusted.put("expiresAt", current.expiresAt());
        trusted.put("immutablePreviewSnapshotRef", binding.previewToolExecutionId());
        trusted.put("caseKey", current.caseKey());
        trusted.put("actionRequestId", current.actionRequestId());
        trusted.put("effects", current.effects());
        trusted.put("warnings", current.warnings());
        trusted.put("suggestedReason", safe(current.suggestedReason()));
        trusted.put("approvalId", approvalId);
        return new ToolCallRequest(request.toolName(), request.requestId(), trusted);
    }

    private void ensureSameImmutablePreview(OrderCareRecoveryProposal stored,
                                            OrderCareRecoveryProposal current) {
        if (stored == null || current == null
                || !Objects.equals(stored.proposalId(), current.proposalId())
                || !Objects.equals(stored.actionRequestId(), current.actionRequestId())
                || !Objects.equals(stored.proposalVersion(), current.proposalVersion())
                || !Objects.equals(stored.stateFingerprint(), current.stateFingerprint())
                || !Objects.equals(stored.previewDigest(), current.previewDigest())) {
            throw new IllegalArgumentException("FlowOrder Proposal 与当前 Run 的不可变 preview 不一致");
        }
    }

    private String stringArgument(Map<String, Object> arguments, String name) {
        Object value = arguments == null ? null : arguments.get(name);
        String normalized = value == null ? "" : String.valueOf(value).trim();
        if (normalized.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return normalized;
    }

    private String safe(String value) {
        return value == null ? "" : value;
    }
}
