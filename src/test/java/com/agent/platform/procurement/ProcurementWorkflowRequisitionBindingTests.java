package com.agent.platform.procurement;

import com.agent.platform.agent.AgentRequest;
import com.agent.platform.approval.*;
import com.agent.platform.guardrail.ToolPolicyContext;
import com.agent.platform.mcp.McpToolGateway;
import com.agent.platform.procurement.erp.*;
import com.agent.platform.procurement.model.ProcurementCase;
import com.agent.platform.procurement.tool.*;
import com.agent.platform.procurement.workflow.WorkflowFinalizeBinding;
import com.agent.platform.runtime.*;
import com.agent.platform.tool.*;
import org.junit.jupiter.api.Test;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ProcurementWorkflowRequisitionBindingTests {
    private ProcurementWorkflowTests.Fixture fixture() {
        var f=new ProcurementWorkflowTests.Fixture();f.requisitionId="V2-REQ-001";return f;
    }
    private ToolPolicyContext context(String run) { return new ToolPolicyContext(run,"s","u","t",Set.of(),Map.of()); }
    private void editIdentity(ProcurementWorkflowTests.Fixture f, java.util.function.Consumer<Map<String,Object>> edit) {
        f.runs.update("run1", r->{
            var md=new LinkedHashMap<>(r.request().metadata());
            var identity=new LinkedHashMap<String,Object>(f.json.convertValue(md.get(WorkflowFinalizeBinding.REQUISITION),Map.class));
            edit.accept(identity);md.put(WorkflowFinalizeBinding.REQUISITION,identity);
            return r.withRequest(new AgentRequest("s","u",r.request().question(),md));
        });
    }
    @Test void omittedModelIdentityIsBoundAndSurvivesApprovedResumeWithoutRfqRead() {
        var f=fixture();var r=f.run();assertEquals(AgentRunState.WAITING_APPROVAL,r.state(),r.answer());
        assertEquals("V2-REQ-001",f.approvalStore.find(r.approvalId()).orElseThrow().toolCallRequest().arguments().get("requisitionId"));
        assertTrue(f.executions.findByRun(r.runId()).stream().noneMatch(e->e.toolName().endsWith("get_requisition")));
        f.approvals.decide(r.approvalId(),true,"reviewer","yes");
        assertEquals(AgentRunState.COMPLETED,f.workflow.resume(r.runId(),AgentEventListener.NOOP).state());
        assertEquals("V2-REQ-001",f.writtenRequest.requisitionId());
        f.workflow.resume(r.runId(),AgentEventListener.NOOP);assertEquals(1,f.writes);
    }
    @Test void wrongModelIdentityCannotOverrideServer() {
        var f=fixture();var r=f.run();
        var canonical=f.preparer.prepare("fixed",new ToolCallRequest(ProcurementToolCatalog.CREATE_RFQ,"proposal",Map.of("requisitionId","V2-REQ-999")),context(r.runId()));
        assertEquals("V2-REQ-001",canonical.arguments().get("requisitionId"));
    }
    @Test void missingIdentityFailsBeforeApproval() {
        var f=fixture();f.beforeRfq=v->editIdentity(v,m->m.remove("canonicalRequisitionId"));
        assertEquals(AgentRunState.FAILED,f.run().state());assertTrue(f.approvalStore.recent(20).isEmpty());assertEquals(0,f.writes);
    }
    @Test void missingEntireBindingDoesNotFallBackToCurrentRunLedger() {
        var f=fixture();f.beforeRfq=v->v.runs.update("run1",r->{var md=new LinkedHashMap<>(r.request().metadata());md.remove(WorkflowFinalizeBinding.REQUISITION);return r.withRequest(new AgentRequest("s","u",r.request().question(),md));});
        assertEquals(AgentRunState.FAILED,f.run().state());assertTrue(f.approvalStore.recent(20).isEmpty());assertEquals(0,f.writes);
    }
    @Test void recommendationIdentityMismatchFailsBeforeApproval() {
        var f=fixture();f.beforeRfq=v->editIdentity(v,m->m.put("canonicalRequisitionId","V2-REQ-002"));
        assertEquals(AgentRunState.FAILED,f.run().state());assertTrue(f.approvalStore.recent(20).isEmpty());assertEquals(0,f.writes);
    }
    @Test void tamperedApprovalIdentityCannotWrite() {
        var f=fixture();var r=f.run();f.approvals.decide(r.approvalId(),true,"reviewer","yes");
        var a=f.approvalStore.find(r.approvalId()).orElseThrow();var args=new LinkedHashMap<>(a.toolCallRequest().arguments());args.put("requisitionId","V2-REQ-999");
        f.approvalStore.save(new ApprovalRecord(a.approvalId(),a.runId(),a.conversationId(),new ToolCallRequest(a.toolCallRequest().toolName(),a.toolCallRequest().requestId(),args),a.reason(),a.status(),a.reviewer(),a.decisionReason(),a.createdAt(),a.expiresAt(),a.decidedAt()));
        assertEquals(AgentRunState.FAILED,f.workflow.resume(r.runId(),AgentEventListener.NOOP).state());assertEquals(0,f.writes);
    }
    @Test void staleCaseVersionCannotResumeLinkedApproval() {
        var f=fixture();var r=f.run();var c=f.current();
        assertTrue(f.cases.saveIfVersion(new ProcurementCase(c.caseId(),c.tenantId(),c.conversationId(),c.userId(),c.status(),c.state(),c.createdAt(),Instant.now(),c.version()+1,"external"),c.version()));
        f.approvals.decide(r.approvalId(),true,"reviewer","yes");assertEquals(AgentRunState.FAILED,f.workflow.resume(r.runId(),AgentEventListener.NOOP).state());assertEquals(0,f.writes);
    }
    @Test void changedIdentityAfterApprovalCannotResume() {
        var f=fixture();var r=f.run();f.approvals.decide(r.approvalId(),true,"reviewer","yes");editIdentity(f,m->m.put("canonicalRequisitionId","V2-REQ-002"));
        assertEquals(AgentRunState.FAILED,f.workflow.resume(r.runId(),AgentEventListener.NOOP).state());assertEquals(0,f.writes);
    }
    @Test void requisitionParticipatesInExecutionIdentityEvenWithSameOtherIdentityFields() {
        var f=fixture();var r=f.run();var intent=new ToolCallRequest(ProcurementToolCatalog.CREATE_RFQ,"intent",Map.of());
        String first=(String)f.preparer.prepare("fixed",intent,context(r.runId())).arguments().get("idempotencyKey");
        // Keep Case, approval id and recommendation id constant in this isolated digest unit check.
        var binding=mock(WorkflowFinalizeBinding.class);when(binding.find(r.runId())).thenReturn(f.binding.find(r.runId()));
        when(binding.permits(eq(r.runId()),anyString())).thenReturn(true);when(binding.requisitionId(r.runId())).thenReturn("V2-REQ-002");f.preparer.setWorkflowBinding(binding);
        String second=(String)f.preparer.prepare("fixed",intent,context(r.runId())).arguments().get("idempotencyKey");
        assertNotEquals(first,second);
    }
    @Test void realApprovalGatewayAndAdapterPreserveDocumentAndItemLinkage() {
        var document=new AtomicReference<tools.jackson.databind.JsonNode>();
        var posts=new java.util.concurrent.atomic.AtomicInteger();
        var f=new ProcurementWorkflowTests.Fixture(v->WorkflowErpLinkTestSupport.gateway(v.approvalStore,v.runs,v.executions,v.preparer,v.json,document,posts));f.requisitionId="V2-REQ-001";
        var r=f.run();assertEquals(AgentRunState.WAITING_APPROVAL,r.state(),r.answer());assertNull(document.get());
        f.approvals.decide(r.approvalId(),true,"reviewer","yes");var resumed=f.workflow.resume(r.runId(),AgentEventListener.NOOP);
        assertEquals(AgentRunState.COMPLETED,resumed.state(),resumed.answer());
        assertEquals("V2-REQ-001",document.get().path("items").get(0).path("material_request").asText());
        assertEquals("MR-LINE-001",document.get().path("items").get(0).path("material_request_item").asText());
        f.workflow.resume(r.runId(),AgentEventListener.NOOP);assertEquals(1,posts.get());
    }
}
