package com.agent.platform.procurement.tool;

import com.agent.platform.guardrail.ToolPolicyContext;
import com.agent.platform.procurement.model.ProcurementCase;
import com.agent.platform.procurement.persistence.ProcurementCaseStore;
import com.agent.platform.runtime.ApprovalToolCallRequestPreparer;
import com.agent.platform.runtime.ToolExecutionRecord;
import com.agent.platform.runtime.ToolExecutionState;
import com.agent.platform.runtime.ToolExecutionStore;
import com.agent.platform.tool.ToolCallRequest;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 在 ApprovalRecord 创建前，用当前 Case 和本 Run 已验证的 Finalize 结果重建 RFQ exact action。
 * 模型请求只表达意图，不提供任何被信任的 RFQ 业务字段。
 */
@Component
public class ProcurementRfqApprovalPreparer implements ApprovalToolCallRequestPreparer {
    private final ToolExecutionStore toolExecutionStore;
    private final ProcurementCaseStore caseStore;
    private final ObjectMapper objectMapper;

    public ProcurementRfqApprovalPreparer(ToolExecutionStore toolExecutionStore,
                                          ProcurementCaseStore caseStore,
                                          ObjectMapper objectMapper) {
        this.toolExecutionStore = toolExecutionStore;
        this.caseStore = caseStore;
        this.objectMapper = objectMapper;
    }

    @Override
    public boolean supports(String toolName) {
        return ProcurementToolCatalog.CREATE_RFQ.equals(toolName);
    }

    @Override
    public ToolCallRequest prepare(String approvalId,
                                   ToolCallRequest request,
                                   ToolPolicyContext context) {
        if (approvalId == null || approvalId.isBlank()) {
            throw new IllegalArgumentException("approvalId is required");
        }
        ToolPolicyContext trusted = requireContext(context);
        if (request == null || !supports(request.toolName())) {
            throw new IllegalArgumentException("unsupported procurement RFQ tool");
        }
        ProcurementCase current = caseStore.findByTenantUserAndConversationId(
                        trusted.tenantId(), trusted.userId(), trusted.sessionId())
                .orElseThrow(() -> new IllegalArgumentException("procurement Case not found"));
        ToolExecutionRecord finalizeExecution = latestSuccessfulFinalize(trusted.runId());
        FinalizeSnapshot finalized = readFinalize(finalizeExecution);
        if (!current.caseId().equals(finalized.caseId()) || current.version() != finalized.caseVersion()) {
            throw new IllegalArgumentException("verified recommendation is stale for the current procurement Case");
        }

        var state = current.state();
        if (state.productCategory().isBlank() || state.productDescription().isBlank()
                || state.quantity() == null || state.quantity() <= 0
                || state.requiredDeliveryDays() == null || state.requiredDeliveryDays() <= 0
                || state.currency().isBlank()) {
            throw new IllegalArgumentException("current procurement Case is not ready for RFQ");
        }
        Map<String, Object> authoritative = new LinkedHashMap<>();
        authoritative.put("caseId", current.caseId());
        authoritative.put("caseVersion", current.version());
        authoritative.put("supplierId", finalized.supplierId());
        authoritative.put("productCategory", state.productCategory());
        authoritative.put("productDescription", state.productDescription());
        authoritative.put("quantity", state.quantity());
        authoritative.put("currency", state.currency());
        authoritative.put("requiredDeliveryDays", state.requiredDeliveryDays());
        authoritative.put("hardConstraints", state.hardConstraints());
        authoritative.put("sourceRecommendationToolCallId", finalizeExecution.toolCallId());
        authoritative.put("idempotencyKey", "rfq:" + approvalId.trim());
        return new ToolCallRequest(request.toolName(), request.requestId(), authoritative);
    }

    private ToolPolicyContext requireContext(ToolPolicyContext context) {
        if (context == null || context.runId().isBlank() || context.sessionId().isBlank()
                || context.userId().isBlank() || context.tenantId().isBlank()) {
            throw new IllegalArgumentException("trusted runId, tenantId, userId and conversationId are required");
        }
        return context;
    }

    private ToolExecutionRecord latestSuccessfulFinalize(String runId) {
        List<ToolExecutionRecord> executions = toolExecutionStore.findByRun(runId);
        return executions.stream()
                .filter(record -> record != null
                        && record.state() == ToolExecutionState.SUCCEEDED
                        && record.result() != null
                        && record.result().success()
                        && ProcurementToolCatalog.RECOMMENDATION_FINALIZE.equals(record.toolName()))
                .max(Comparator.comparing(ToolExecutionRecord::updatedAt)
                        .thenComparing(ToolExecutionRecord::createdAt))
                .orElseThrow(() -> new IllegalArgumentException(
                        "successful recommendation_finalize is required before RFQ"));
    }

    private FinalizeSnapshot readFinalize(ToolExecutionRecord execution) {
        try {
            JsonNode root = objectMapper.readTree(execution.result().content());
            if (root == null || !root.isObject()
                    || !"verified-provider-snapshot".equals(text(root, "source"))) {
                throw new IllegalArgumentException("Finalize result is not a verified provider snapshot");
            }
            String caseId = requiredText(root, "caseId");
            long caseVersion = requiredNonNegativeLong(root, "caseVersion");
            JsonNode recommendation = requiredObject(root, "recommendation");
            JsonNode recommendedSupplier = requiredObject(recommendation, "recommendedSupplier");
            JsonNode selectedOffer = requiredObject(recommendation, "selectedOffer");
            String recommendedSupplierId = requiredText(recommendedSupplier, "supplierId");
            String selectedOfferSupplierId = requiredText(selectedOffer, "supplierId");
            if (!recommendedSupplierId.equals(selectedOfferSupplierId)) {
                throw new IllegalArgumentException("Finalize supplier sources do not match");
            }
            return new FinalizeSnapshot(caseId, caseVersion, recommendedSupplierId, execution.toolCallId());
        }
        catch (IllegalArgumentException exception) {
            throw exception;
        }
        catch (Exception exception) {
            throw new IllegalArgumentException("Finalize result is invalid", exception);
        }
    }

    private JsonNode requiredObject(JsonNode root, String field) {
        JsonNode value = root.get(field);
        if (value == null || !value.isObject()) {
            throw new IllegalArgumentException("Finalize field must be an object: " + field);
        }
        return value;
    }

    private String requiredText(JsonNode root, String field) {
        String value = text(root, field);
        if (value.isBlank()) throw new IllegalArgumentException("Finalize field is required: " + field);
        return value;
    }

    private String text(JsonNode root, String field) {
        JsonNode value = root.get(field);
        return value != null && value.isTextual() ? value.asText().trim() : "";
    }

    private long requiredNonNegativeLong(JsonNode root, String field) {
        JsonNode value = root.get(field);
        if (value == null || !value.isIntegralNumber() || value.asLong() < 0) {
            throw new IllegalArgumentException("Finalize field must be a non-negative integer: " + field);
        }
        return value.asLong();
    }

    private record FinalizeSnapshot(String caseId, long caseVersion, String supplierId, String sourceToolCallId) {
    }
}
