package com.agent.platform.procurement;

import com.agent.platform.procurement.agent.*;
import com.agent.platform.procurement.model.*;
import com.agent.platform.runtime.*;
import com.agent.platform.tool.*;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static com.agent.platform.procurement.tool.ProcurementToolCatalog.*;

class RootExecutionSummaryTests {
    final ObjectMapper json=new ObjectMapper();
    ProcurementCase current(ProcurementMultiAgentTests.Fixture f){return f.cases.findByTenantUserAndConversationId("tenant","buyer","conversation").orElseThrow();}
    ProcurementExecutionSummaries summaries(ProcurementMultiAgentTests.Fixture f){return new ProcurementExecutionSummaries(f.runs,f.executions,f.cases,json);}
    String child(ProcurementMultiAgentTests.Fixture f,String agent){return f.runs.recent(100).stream().filter(r->agent.equals(r.executionProfile().name())).findFirst().orElseThrow().runId();}
    void patch(ProcurementMultiAgentTests.Fixture f,String child,String id,boolean success){
        var c=current(f);var inputs=new HashSet<>(c.appliedInputIds());inputs.add(id);
        if(success)assertTrue(f.cases.saveIfVersion(new ProcurementCase(c.caseId(),c.tenantId(),c.conversationId(),c.userId(),c.status(),c.state(),c.createdAt(),Instant.now(),c.version()+1,id,inputs),c.version()));
        var request=new ToolCallRequest(CASE_PATCH,id,Map.of());f.executions.claim(child,request);
        var result=new ToolCallResult(CASE_PATCH,success,json.writeValueAsString(Map.of("caseId",c.caseId(),"caseVersion",c.version()+1,"state",c.state())),"",Map.of());
        if(success)f.executions.markSucceeded(id,result);else f.executions.markFailed(id,result);
    }
    void combination(boolean a,boolean b){
        var f=new ProcurementMultiAgentTests().new Fixture();var root=f.start();var s=summaries(f);
        String first=child(f,AgentRegistry.SOURCING),second=child(f,AgentRegistry.RFQ);
        if(a)patch(f,first,"a",true);if(b)patch(f,second,"b",true);
        var sa=s.child(first,current(f));var sb=s.child(second,current(f));var global=s.root(root.runId());
        assertEquals(a,sa.caseMutated());assertEquals(b,sb.caseMutated());
        assertEquals(a||b,global.caseMutated());assertEquals((a?1:0)+(b?1:0),global.caseMutationCount());
        assertEquals(current(f).version(),global.currentCaseVersion());assertEquals("ROOT_GLOBAL",global.scope());assertEquals("CHILD_LOCAL",sb.scope());
    }
    @Test void falsePlusFalseIsFalse(){combination(false,false);}
    @Test void truePlusFalseStaysTrue(){combination(true,false);}
    @Test void falsePlusTrueIsTrue(){combination(false,true);}
    @Test void truePlusTrueCountsTwo(){combination(true,true);}
    @Test void failedMutationNeverCounts(){
        var f=new ProcurementMultiAgentTests().new Fixture();var root=f.start();patch(f,child(f,AgentRegistry.SOURCING),"failed",false);
        assertFalse(summaries(f).root(root.runId()).caseMutated());assertEquals(0,summaries(f).root(root.runId()).caseMutationCount());
    }
    @Test void childProseAndSeededCaseCannotClaimThisRootMutated(){
        var f=new ProcurementMultiAgentTests().new Fixture();var root=f.start();
        var call=new ToolCallRequest("prose","untrusted-claim",Map.of());f.executions.claim(child(f,AgentRegistry.SOURCING),call);
        f.executions.markSucceeded(call.requestId(),new ToolCallResult(call.toolName(),true,"{\"caseMutated\":true,\"findings\":[\"已修改 Case\"]}","",Map.of()));
        assertFalse(summaries(f).root(root.runId()).caseMutated());assertEquals(1,summaries(f).root(root.runId()).currentCaseVersion());
    }
    @Test void currentVersionComesFromStoreNotChildReceiptMaximum(){
        var f=new ProcurementMultiAgentTests().new Fixture();var root=f.start();String id=child(f,AgentRegistry.SOURCING);patch(f,id,"patch-2",true);
        var c=current(f);assertTrue(f.cases.saveIfVersion(new ProcurementCase(c.caseId(),c.tenantId(),c.conversationId(),c.userId(),c.status(),c.state(),c.createdAt(),Instant.now(),7,"external-update",c.appliedInputIds()),c.version()));
        assertEquals(2,summaries(f).child(id,current(f)).resultingCaseVersion());assertEquals(7,summaries(f).root(root.runId()).currentCaseVersion());
    }
    @Test void proposalIsNotRfqAndCommittedFactsSurviveReloadResume(){
        var f=new ProcurementMultiAgentTests().new Fixture();var waiting=f.start();
        assertEquals(AgentRunState.WAITING_APPROVAL,waiting.state());assertFalse(summaries(f).root(waiting.runId()).rfqCreated());
        // Supply a durable historical success matching the already-existing fixture Case, without changing approval state/version.
        var c=current(f);var request=new ToolCallRequest(CASE_PATCH,c.lastAppliedInputId(),Map.of());
        f.executions.claim(child(f,AgentRegistry.SOURCING),request);
        f.executions.markSucceeded(request.requestId(),new ToolCallResult(CASE_PATCH,true,
                json.writeValueAsString(Map.of("caseId",c.caseId(),"caseVersion",c.version(),"state",c.state())),"",Map.of()));
        assertTrue(summaries(f).root(waiting.runId()).caseMutated());
        for(var r:f.runs.recent(100))f.runs.update(r.runId(),old->json.readValue(json.writeValueAsString(r),AgentRunRecord.class));
        for(var t:f.executions.all())f.executions.records.put(t.toolCallId(),json.readValue(json.writeValueAsString(t),ToolExecutionRecord.class));
        f.approvals.decide(waiting.approvalId(),true,"reviewer","approved");var completed=f.runtime.resume(waiting.runId());
        assertEquals(AgentRunState.COMPLETED,completed.state());assertEquals(1,f.writes.get());
        var summary=summaries(f).root(waiting.runId());assertTrue(summary.caseMutated());assertTrue(summary.rfqCreated());
        assertEquals(1,summary.caseMutationCount());assertEquals(1,summary.rfqCreationCount());
        assertEquals(json.valueToTree(summary),json.valueToTree(completed.serverFacts().get("rootExecutionSummary")));
        assertEquals(json.valueToTree(summary),json.valueToTree(f.runtime.resume(waiting.runId()).serverFacts().get("rootExecutionSummary")));
    }
    @Test void rejectedApprovalNeverCreatesRootRfq(){
        var f=new ProcurementMultiAgentTests().new Fixture();var waiting=f.start();
        assertFalse(json.valueToTree(waiting.serverFacts()).path("rootExecutionSummary").path("rfqCreated").asBoolean());
        f.approvals.decide(waiting.approvalId(),false,"reviewer","reject");var completed=f.runtime.resume(waiting.runId());
        assertFalse(summaries(f).root(completed.runId()).rfqCreated());assertEquals(0,f.writes.get());
    }
    @Test void childResultSeparatesScopesAndMetadataSurvivesSerialization(){
        var f=new ProcurementMultiAgentTests().new Fixture();var waiting=f.start();
        for(var e:f.executions.all())if(AgentRegistry.AGENT_TOOLS.contains(e.toolName()) && e.result()!=null){
            var body=json.readTree(e.result().content());assertFalse(body.has("executionFacts"));
            assertEquals("CHILD_LOCAL",body.path("childExecutionSummary").path("scope").asText());
            assertEquals("ROOT_GLOBAL",body.path("rootExecutionSummary").path("scope").asText());
            assertFalse(body.path("rootExecutionSummary").has("rawExecutionRecords"));
        }
        var restored=json.readValue(json.writeValueAsString(waiting),AgentRuntimeResult.class);
        assertEquals(json.readTree(json.writeValueAsString(waiting.serverFacts())),json.readTree(json.writeValueAsString(restored.serverFacts())));
        assertTrue(waiting.events().stream().anyMatch(e->e.payload().containsKey("serverFacts")));
    }
    @Test void receiptsAreBoundedAndNotDoubleCountedAcrossRepeatedReads(){
        var f=new ProcurementMultiAgentTests().new Fixture();var root=f.start();String child=child(f,AgentRegistry.SOURCING);
        for(int i=0;i<10;i++)patch(f,child,"p"+i,true);
        var s=summaries(f);var first=s.root(root.runId());assertEquals(first,s.root(root.runId()));
        assertEquals(10,first.caseMutationCount());assertEquals(8,first.receiptRefs().size());assertEquals(2,first.omittedReceiptRefs());
    }
}
