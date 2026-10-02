package com.agent.platform.procurement.erp;

import com.agent.platform.approval.*;
import com.agent.platform.guardrail.ToolPolicyContext;
import com.agent.platform.procurement.tool.*;
import com.agent.platform.runtime.*;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.*;

/** Revalidates persisted governance state before the ERP gateway can issue a write capability. */
@Component
public final class ErpRfqApprovalGuard {
    private final ApprovalStore approvals;
    private final AgentRunStore runs;
    private final ToolExecutionStore executions;
    private final ProcurementRfqApprovalPreparer preparer;
    private final ObjectMapper json;

    public ErpRfqApprovalGuard(ApprovalStore approvals, AgentRunStore runs, ToolExecutionStore executions,
                               ProcurementRfqApprovalPreparer preparer, ObjectMapper json) {
        this.approvals=approvals; this.runs=runs; this.executions=executions; this.preparer=preparer; this.json=json;
    }

    public Instant requireApproved(ProcurementRfqGateway.CreateRequest request) {
        try {
            if (!request.idempotencyKey().startsWith("rfq:")) reject();
            String id=request.idempotencyKey().substring(4);
            var approval=approvals.find(id).orElseThrow();
            if (!id.equals(approval.approvalId()) || approval.status()!=ApprovalStatus.APPROVED
                    || approval.decidedAt()==null || !approval.expiresAt().isAfter(Instant.now())) reject();
            var run=runs.find(approval.runId()).orElseThrow();
            if (!run.runId().equals(approval.runId()) || !run.conversationId().equals(approval.conversationId())) reject();
            var bound=approval.toolCallRequest();
            var claimed=executions.findToolExecution(bound.requestId()).orElseThrow();
            if (!approval.runId().equals(claimed.runId())
                    || !Set.of(ToolExecutionState.RUNNING,ToolExecutionState.SUCCEEDED).contains(claimed.state())
                    || !storedJson(bound).equals(storedJson(claimed.request()))) reject();
            var context=new ToolPolicyContext(run.runId(),run.conversationId(),run.userId(),
                    Objects.toString(run.request().metadata().get("tenantId"),""),Set.of(),Map.of());
            // Reuse the existing server-owned reconstruction, including current Case version and Finalize binding.
            var canonical=preparer.prepare(id,bound,context);
            if (!storedJson(bound).equals(storedJson(canonical))) reject();
            var a=json.valueToTree(canonical.arguments());
            var expected=new ProcurementRfqGateway.CreateRequest(a.path("idempotencyKey").asText(),
                    a.path("supplierId").asText(),a.path("productCategory").asText(),a.path("productDescription").asText(),
                    a.path("quantity").asInt(),a.path("currency").asText(),a.path("requiredDeliveryDays").asInt(),
                    json.convertValue(a.get("hardConstraints"),new tools.jackson.core.type.TypeReference<Map<String,String>>(){}));
            if (!expected.equals(request)) reject();
            return approval.expiresAt();
        } catch (RuntimeException ex) { throw new ProcurementRfqGateway.RejectedRequestException(); }
    }
    private tools.jackson.databind.JsonNode storedJson(Object value) {
        // JSON storage restores small integral values as Integer even when the preparer used Long.
        return json.readTree(json.writeValueAsString(value));
    }
    private static void reject() { throw new ProcurementRfqGateway.RejectedRequestException(); }
}
