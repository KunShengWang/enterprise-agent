package com.agent.platform.procurement.erp;

import com.agent.platform.approval.*;
import com.agent.platform.config.AgentProperties;
import com.agent.platform.guardrail.*;
import com.agent.platform.procurement.model.*;
import com.agent.platform.procurement.persistence.ProcurementCaseStore;
import com.agent.platform.procurement.tool.*;
import com.agent.platform.runtime.*;
import com.agent.platform.tool.*;
import tools.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Real approval preparation, Tool Runtime and RFQ handler; in-memory test governance stores. */
final class ErpRfqControlledTestSupport {
    record Result(String rfqId, String key, ProcurementRfqGateway gateway) {}
    static Result approveAndExecute(java.util.function.Function<ErpRfqApprovalGuard,ProcurementRfqGateway> factory,ObjectMapper json){
        var cases=mock(ProcurementCaseStore.class);var executions=mock(ToolExecutionStore.class);
        var state=new ProcurementCaseState("AI Workstation","V2-WS-001",20,new BigDecimal("300000"),"CNY",21,Map.of(),Map.of(),Set.of(),List.of(),"SOURCING");
        var current=new ProcurementCase("erp-case","tenant","conversation","buyer",ProcurementCaseStatus.SOURCING,state,Instant.now(),Instant.now(),1,"patch-1");
        when(cases.findByTenantUserAndConversationId("tenant","buyer","conversation")).thenReturn(Optional.of(current));
        var finalized=new ToolCallResult(ProcurementToolCatalog.RECOMMENDATION_FINALIZE,true,json.writeValueAsString(Map.of(
                "caseId","erp-case","caseVersion",1,"source","verified-provider-snapshot","recommendation",Map.of(
                "recommendedSupplier",Map.of("supplierId","V2-SUP-001"),"selectedOffer",Map.of("supplierId","V2-SUP-001")))),"",Map.of());
        var record=ToolExecutionRecord.running("erp-run",new ToolCallRequest(ProcurementToolCatalog.RECOMMENDATION_FINALIZE,"finalize-1",Map.of()))
                .withResult(ToolExecutionState.SUCCEEDED,finalized,"");
        when(executions.findByRun("erp-run")).thenReturn(List.of(record));when(executions.findToolExecution("finalize-1")).thenReturn(Optional.of(record));
        when(executions.claim(eq("erp-run"),any())).thenAnswer(i->{
            ToolCallRequest r=i.getArgument(1);
            when(executions.findToolExecution(r.requestId())).thenReturn(Optional.of(ToolExecutionRecord.running("erp-run",r)));
            return ToolExecutionClaim.acquired();
        });
        var approvalStore=mock(ApprovalStore.class);var runs=mock(AgentRunStore.class);
        when(runs.find("erp-run")).thenReturn(Optional.of(AgentRunRecord.create("erp-run","trace","conversation",
                new com.agent.platform.agent.AgentRequest("conversation","buyer","RFQ",Map.of("tenantId","tenant")))));
        var preparer=new ProcurementRfqApprovalPreparer(executions,cases,json);
        var gateway=factory.apply(new ErpRfqApprovalGuard(approvalStore,runs,executions,preparer,json));
        var observed=spy(gateway);
        var handler=new ProcurementRfqToolHandler(observed,cases,executions,json);
        var guardrail=mock(GuardrailService.class);
        when(guardrail.checkToolCall(any(),any(),any())).thenReturn(GuardrailDecision.requireApproval(GuardrailStage.TOOL,"RFQ requires approval"));
        var approvals=mock(ApprovalService.class);var pending=new AtomicReference<ApprovalRequest>();
        when(approvals.requestApproval(any())).thenAnswer(i->{pending.set(i.getArgument(0));return null;});
        // Context is the server-owned trusted context, never passed from the model.
        AgentCapabilityExecutor executor=request->handler.execute(request,new ToolExecutionContext("erp-run","conversation","buyer","tenant",Set.of("USER"),Map.of()));
        var runtime=new DefaultAgentToolRuntime(guardrail,approvals,executions,executor,new AgentProperties(),List.of(preparer),List.of(handler));
        var definition=new ProcurementToolCatalog().definitions().stream().filter(d->d.name().equals(ProcurementToolCatalog.CREATE_RFQ)).findFirst().orElseThrow();
        var waiting=runtime.execute("erp-run","conversation","buyer",Map.of("tenantId","tenant","authenticatedRoles",Set.of("USER")),
                new AgentToolCall("rfq-"+UUID.randomUUID(),definition.name(),Map.of("supplierId","forged"),"create RFQ"),definition);
        assertEquals(AgentToolExecutionStatus.WAITING_APPROVAL,waiting.status());verify(observed,never()).create(any());
        assertEquals("V2-SUP-001",pending.get().toolCallRequest().arguments().get("supplierId"));
        var request=pending.get();
        var requested=new ApprovalRecord(request.approvalId(),"erp-run","conversation",request.toolCallRequest(),"test",ApprovalStatus.REQUESTED,"","",Instant.now(),null);
        var create=new ProcurementRfqGateway.CreateRequest("rfq:"+request.approvalId(),"V2-SUP-001","AI Workstation","V2-WS-001",20,"CNY",21,Map.of());
        // Direct gateway and handler invocations cannot manufacture an approval.
        assertRejected(gateway,create);
        when(approvalStore.find(request.approvalId())).thenReturn(Optional.of(requested));
        assertRejected(gateway,create);
        assertFalse(handler.execute(request.toolCallRequest(),new ToolExecutionContext("erp-run","conversation","buyer","tenant",Set.of(),Map.of())).success());
        assertTrue(gateway.findByIdempotencyKey(create.idempotencyKey()).isEmpty());
        clearInvocations(observed);
        var context=new ToolPolicyContext("erp-run","conversation","buyer","tenant",Set.of("USER"),Map.of());
        assertThrows(IllegalArgumentException.class,()->runtime.executeApproved(requested,definition,context));verify(observed,never()).create(any());
        // A stale Case is rejected by the unchanged handler before the new Gateway signs anything.
        var changed=new ProcurementCase(current.caseId(),current.tenantId(),current.conversationId(),current.userId(),current.status(),state,current.createdAt(),Instant.now(),2,"patch-2");
        when(cases.findByTenantUserAndConversationId("tenant","buyer","conversation")).thenReturn(Optional.of(changed));
        var stale=handler.execute(request.toolCallRequest(),new ToolExecutionContext("erp-run","conversation","buyer","tenant",Set.of("USER"),Map.of()));
        assertFalse(stale.success());verify(observed,never()).create(any());
        when(cases.findByTenantUserAndConversationId("tenant","buyer","conversation")).thenReturn(Optional.of(current));
        // Exercise the same JSON round trip as persisted governance records (Long -> Integer for small values).
        var approved=json.readValue(json.writeValueAsString(new ApprovalRecord(request.approvalId(),"erp-run","conversation",request.toolCallRequest(),"test",ApprovalStatus.APPROVED,"integration-reviewer","test approval",Instant.now(),Instant.now())),ApprovalRecord.class);
        assertInstanceOf(Integer.class,approved.toolCallRequest().arguments().get("caseVersion"));
        when(approvalStore.find(request.approvalId())).thenReturn(Optional.of(approved));
        // An approved record alone is insufficient: no execution claim yet.
        assertRejected(gateway,create);
        when(executions.findToolExecution(request.toolCallRequest().requestId())).thenReturn(Optional.of(ToolExecutionRecord.running("erp-run",request.toolCallRequest())));
        when(cases.findByTenantUserAndConversationId("tenant","buyer","conversation")).thenReturn(Optional.of(changed));
        assertRejected(gateway,create);
        assertTrue(gateway.findByIdempotencyKey(create.idempotencyKey()).isEmpty());
        when(cases.findByTenantUserAndConversationId("tenant","buyer","conversation")).thenReturn(Optional.of(current));
        var expired=new ApprovalRecord(approved.approvalId(),approved.runId(),approved.conversationId(),approved.toolCallRequest(),"test",ApprovalStatus.APPROVED,"reviewer","",Instant.now().minusSeconds(100),Instant.now().minusSeconds(1),Instant.now().minusSeconds(50));
        when(approvalStore.find(request.approvalId())).thenReturn(Optional.of(expired));
        assertRejected(gateway,create);
        when(approvalStore.find(request.approvalId())).thenReturn(Optional.of(approved));
        var tampered=new ProcurementRfqGateway.CreateRequest(create.idempotencyKey(),create.supplierId(),create.productCategory(),create.productDescription(),21,"CNY",21,Map.of());
        assertRejected(gateway,tampered);
        assertTrue(gateway.findByIdempotencyKey(create.idempotencyKey()).isEmpty());
        clearInvocations(observed);
        var result=runtime.executeApproved(approved,definition,context);
        assertEquals(AgentToolExecutionStatus.COMPLETED,result.status(),String.valueOf(result));verify(observed,times(1)).create(any());
        assertRejected(gateway,tampered);
        // A definitive rejection must not turn into success by reconciling a pre-existing receipt.
        when(approvalStore.find(request.approvalId())).thenReturn(Optional.of(expired));
        clearInvocations(observed);
        var refused=handler.execute(request.toolCallRequest(),new ToolExecutionContext("erp-run","conversation","buyer","tenant",Set.of(),Map.of()));
        assertFalse(refused.success());assertEquals("RFQ_REQUEST_REJECTED",refused.metadata().get("errorType"));
        verify(observed,never()).findByIdempotencyKey(anyString());
        when(approvalStore.find(request.approvalId())).thenReturn(Optional.of(approved));
        return new Result(json.readTree(result.result().content()).path("rfqId").asText(),create.idempotencyKey(),gateway);
    }
    private static void assertRejected(ProcurementRfqGateway gateway,ProcurementRfqGateway.CreateRequest request) {
        assertThrows(ProcurementRfqGateway.RejectedRequestException.class,()->gateway.create(request));
    }
}
