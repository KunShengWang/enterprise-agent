package com.agent.platform.procurement;

import com.agent.platform.runtime.*;
import com.agent.platform.tool.*;
import com.agent.platform.guardrail.*;
import org.junit.jupiter.api.Test;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ProcurementWorkflowRetryTests {
    @Test void transientItemReadRecoversAndWorkflowContinues() {
        var calls=new AtomicInteger();var f=fixture(calls,false);f.intent="ANALYZE";
        var result=f.run();assertEquals(AgentRunState.COMPLETED,result.state(),result.answer());
        assertTrue(result.answer().contains("COMPLETE"));assertEquals(2,calls.get());assertEquals(2,f.stages.size());
        var receipt=f.executions.findToolExecution("run1-item").orElseThrow().result();
        assertEquals(2,receipt.metadata().get("attempts"));assertEquals(1L,receipt.metadata().get("_harnessToolErrors"));
        assertEquals(1L,receipt.metadata().get("_harnessRecoveredRetryableErrors"));
    }
    @Test void exhaustionNeverBecomesUserClarification() { exhausted(true); }
    @Test void infrastructureFailureTakesPriorityOverMissingQuantity() { exhausted(false); }
    void exhausted(boolean quantity){
        var calls=new AtomicInteger();var f=fixture(calls,true);f.quantity=quantity;
        var result=f.run();assertEquals(2,calls.get());assertEquals(AgentRunState.FAILED,result.state(),result.answer());
        assertEquals(AgentStopReason.TOOL_ERROR,result.stopReason());assertTrue(result.answer().contains("WORKFLOW_ERROR"));
        assertTrue(result.answer().contains("DATA_UNAVAILABLE_DUE_TO_TOOL_FAILURE"));assertTrue(result.answer().contains("run1-item"));
        assertFalse(result.answer().contains("NEEDS_USER_INPUT"));assertNull(f.current());assertEquals(1,f.stages.size());
        assertNotNull(f.runs.find("run1").orElseThrow().request().metadata().get("workflowDataUnavailable"));
        assertEquals(2L,f.executions.findToolExecution("run1-item").orElseThrow().result().metadata().get("_harnessToolErrors"));
    }
    @Test void genuinelyMissingQuantityRemainsClarificationAfterSuccessfulRead(){
        var f=fixture(new AtomicInteger(),false);f.quantity=false;
        assertTrue(f.run().answer().contains("NEEDS_USER_INPUT"));assertEquals(1,f.stages.size());
    }
    @Test void suppliedItemWithoutReceiptIsNotUserMissing(){
        var f=new ProcurementWorkflowTests.Fixture();f.itemReceipt=(fixture,id)->{};
        var result=f.run();assertEquals(AgentRunState.FAILED,result.state());assertTrue(result.answer().contains("canonical item unavailable"));
    }
    @Test void successfulDifferentArgumentsCannotEraseUnavailableRead(){
        var f=fixture(new AtomicInteger(),true);var original=f.itemReceipt;
        f.itemReceipt=(fixture,id)->{original.accept(fixture,id);fixture.receipt(id,"unrelated","mcp.erpnext.procurement_get_item",Map.of("record",Map.of("id","OTHER")));};
        assertTrue(f.run().answer().contains("DATA_UNAVAILABLE_DUE_TO_TOOL_FAILURE"));
    }
    ProcurementWorkflowTests.Fixture fixture(AtomicInteger calls,boolean permanent){
        var f=new ProcurementWorkflowTests.Fixture();f.properties.setMaxToolExecutionAttempts(2);f.properties.setToolRetryBackoffMillis(0);
        var guard=mock(GuardrailService.class);when(guard.checkToolCall(any(),any(),any())).thenReturn(GuardrailDecision.allow(GuardrailStage.TOOL,"read"));
        AgentCapabilityExecutor executor=request->{
            int attempt=calls.incrementAndGet();
            return attempt==1 || permanent
                    ?new ToolCallResult(request.toolName(),false,"{\"error\":{\"code\":\"ERP_UNAVAILABLE\"}}","injected MCP 503",Map.of("errorType","ERP_UNAVAILABLE"))
                    :new ToolCallResult(request.toolName(),true,f.json.writeValueAsString(Map.of("record",Map.of("id","SKU","category","Workstation","active",true))),"",Map.of());
        };
        var runtime=new DefaultAgentToolRuntime(guard,f.approvals,f.executions,executor,f.properties,List.of(),List.of());
        f.itemReceipt=(fixture,id)->runtime.execute(id,"s","u",Map.of(),
                new AgentToolCall(id+"-item","mcp.erpnext.procurement_get_item",Map.of("id","SKU"),""),
                new ToolDefinition("mcp.erpnext.procurement_get_item","","{}",ToolRiskLevel.LOW,Map.of("readOnly",true)));
        return f;
    }
}
