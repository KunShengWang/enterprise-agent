package com.agent.platform.runtime;

import com.agent.platform.approval.ApprovalService;
import com.agent.platform.config.AgentProperties;
import com.agent.platform.guardrail.*;
import com.agent.platform.tool.*;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ToolRetryPolicyTests {
    ToolCallResult result(ToolExecutionOutcome outcome) {
        return new ToolCallResult("item",outcome==ToolExecutionOutcome.SUCCESS,"{}","translated diagnostic",
                Map.of("_harnessOutcome",outcome.name()));
    }
    ToolDefinition definition(ToolRuntimeContract.Effect effect) {
        return new ToolDefinition("item","item","{}",ToolRiskLevel.LOW,Map.of("effect",effect.name()));
    }
    @Test void onlyTransientSafeEffectsCanRetry() {
        for(var outcome:ToolExecutionOutcome.values())for(var effect:ToolRuntimeContract.Effect.values()){
            var decision=ToolRetryPolicy.decide(result(outcome),definition(effect),1,2,0);
            assertEquals(outcome==ToolExecutionOutcome.RETRYABLE_ERROR && effect==ToolRuntimeContract.Effect.READ_ONLY,
                    decision.retry(),outcome+" "+effect);
        }
    }
    @Test void explicitReplaySafeInternalWriteOnly() {
        for(var effect:ToolRuntimeContract.Effect.values()){
            var d=new ToolDefinition("item","","{}",ToolRiskLevel.LOW,Map.of("effect",effect.name(),"retry","safe"));
            assertEquals(effect==ToolRuntimeContract.Effect.READ_ONLY || effect==ToolRuntimeContract.Effect.INTERNAL_WRITE,
                    ToolRetryPolicy.decide(result(ToolExecutionOutcome.RETRYABLE_ERROR),d,1,2,0).retry());
        }
    }
    @Test void budgetDeadlineAndExecutorVetoAreRespected() {
        var r=result(ToolExecutionOutcome.RETRYABLE_ERROR);var d=definition(ToolRuntimeContract.Effect.READ_ONLY);
        assertEquals("RETRY_BUDGET_EXHAUSTED",ToolRetryPolicy.decide(r,d,2,2,0).reason());
        var expired=new ToolDefinition("item","","{}",ToolRiskLevel.LOW,Map.of("readOnly",true,"_harnessDeadlineEpochMillis",0));
        assertEquals("RUN_DEADLINE_EXHAUSTED",ToolRetryPolicy.decide(r,expired,1,2,0).reason());
        var veto=new ToolCallResult("item",false,"","",Map.of("_harnessOutcome","RETRYABLE_ERROR","retryable",false));
        assertEquals("EXECUTOR_RETRY_VETO",ToolRetryPolicy.decide(veto,d,1,2,0).reason());
    }
    @Test void structuredTransientRecoversSameRequestAndPreservesErrorMetrics() {
        var executor=mock(AgentCapabilityExecutor.class);
        when(executor.execute(any())).thenReturn(new ToolCallResult("item",false,"","injected MCP 503",Map.of("errorType","ERP_UNAVAILABLE")),result(ToolExecutionOutcome.SUCCESS));
        var response=execute(executor,definition(ToolRuntimeContract.Effect.READ_ONLY));
        assertTrue(response.result().success());var metadata=response.result().metadata();
        assertEquals(2,metadata.get("attempts"));assertEquals(1L,metadata.get("_harnessToolErrors"));
        assertEquals(1L,metadata.get("_harnessRecoveredRetryableErrors"));
        var history=(List<Map<String,Object>>)metadata.get("_harnessAttempts");
        assertEquals("RETRYABLE_ERROR",history.get(0).get("outcome"));assertEquals(true,history.get(0).get("retryDecision"));
        assertEquals("SUCCESS",history.get(history.size()-1).get("outcome"));assertEquals(0L,history.get(0).get("backoffMillis"));
        var captor=org.mockito.ArgumentCaptor.forClass(ToolCallRequest.class);verify(executor,times(2)).execute(captor.capture());
        assertSame(captor.getAllValues().get(0),captor.getAllValues().get(1));
        assertEquals(Map.of("id","SKU","caseVersion",3),captor.getValue().arguments());
    }
    @Test void exhaustionHasTwoErrorsAndNoThirdAttempt() {
        var executor=mock(AgentCapabilityExecutor.class);when(executor.execute(any())).thenReturn(result(ToolExecutionOutcome.RETRYABLE_ERROR));
        var response=execute(executor,definition(ToolRuntimeContract.Effect.READ_ONLY));
        assertEquals(AgentToolExecutionStatus.FAILED,response.status());assertEquals(2L,response.result().metadata().get("_harnessToolErrors"));
        assertEquals(0L,response.result().metadata().get("_harnessRecoveredRetryableErrors"));verify(executor,times(2)).execute(any());
    }
    @Test void nonRetryableOutcomesExecuteOnce() {
        for(var outcome:List.of(ToolExecutionOutcome.VALIDATION_ERROR,ToolExecutionOutcome.BUSINESS_ERROR,
                ToolExecutionOutcome.POLICY_DENIED,ToolExecutionOutcome.APPROVAL_REQUIRED,ToolExecutionOutcome.FATAL_ERROR)){
            var executor=mock(AgentCapabilityExecutor.class);when(executor.execute(any())).thenReturn(result(outcome));
            assertEquals(1,execute(executor,definition(ToolRuntimeContract.Effect.READ_ONLY)).result().metadata().get("attempts"));
            verify(executor,times(1)).execute(any());
        }
    }
    @Test void ambiguousExternalAndDestructiveWritesNeverReplay() {
        for(var effect:List.of(ToolRuntimeContract.Effect.EXTERNAL_SIDE_EFFECT,ToolRuntimeContract.Effect.DESTRUCTIVE,ToolRuntimeContract.Effect.INTERNAL_WRITE)){
            var executor=mock(AgentCapabilityExecutor.class);when(executor.execute(any())).thenReturn(result(ToolExecutionOutcome.RETRYABLE_ERROR));
            execute(executor,definition(effect));verify(executor,times(1)).execute(any());
        }
    }
    @Test void unexpectedExceptionIsFatalAndValidationIsNotRetried() {
        for(var ex:List.of(new IllegalStateException("unexpected"),new IllegalArgumentException("invalid args"))){
            var executor=mock(AgentCapabilityExecutor.class);when(executor.execute(any())).thenThrow(ex);
            assertEquals(ex instanceof IllegalArgumentException?ToolExecutionOutcome.VALIDATION_ERROR:ToolExecutionOutcome.FATAL_ERROR,
                    execute(executor,definition(ToolRuntimeContract.Effect.READ_ONLY)).outcome());
            verify(executor,times(1)).execute(any());
        }
    }
    @Test void typedTransientExceptionRecoversWithoutMessageGuessing() {
        var executor=mock(AgentCapabilityExecutor.class);when(executor.execute(any()))
                .thenThrow(new RuntimeException(new java.sql.SQLTransientConnectionException("arbitrary"))).thenReturn(result(ToolExecutionOutcome.SUCCESS));
        assertTrue(execute(executor,definition(ToolRuntimeContract.Effect.READ_ONLY)).result().success());verify(executor,times(2)).execute(any());
    }
    AgentToolRuntimeResult execute(AgentCapabilityExecutor executor,ToolDefinition definition){
        var guard=mock(GuardrailService.class);var store=mock(ToolExecutionStore.class);var properties=new AgentProperties();
        properties.setMaxToolExecutionAttempts(2);properties.setToolRetryBackoffMillis(0);
        when(guard.checkToolCall(any(),any(),any())).thenReturn(GuardrailDecision.allow(GuardrailStage.TOOL,"allowed"));
        when(store.claim(any(),any())).thenReturn(ToolExecutionClaim.acquired());
        return new DefaultAgentToolRuntime(guard,mock(ApprovalService.class),store,executor,properties)
                .execute("r","s","u",Map.of(),new AgentToolCall("same-call","item",Map.of("id","SKU","caseVersion",3),""),definition);
    }
}
