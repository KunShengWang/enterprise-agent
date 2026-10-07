package com.agent.platform.runtime;

import com.agent.platform.config.AgentProperties;
import com.agent.platform.tool.*;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ContextBudgetRecoveryTests {
    final ObjectMapper json = new ObjectMapper();
    final AgentProperties properties = new AgentProperties();
    final ToolExecutionStore store = mock(ToolExecutionStore.class);
    final ToolResultProjector projector = new ToolResultProjector(properties);
    final ContextAssembler assembler = new ContextAssembler(new ConservativeTokenEstimator(), store, projector);
    final ContextPolicy policy = new ContextPolicy(true,32000,2000,.75,2);
    final AgentExecutionProfile profile = new AgentExecutionProfile("test","HITL and authorization required",
            Set.of("arbitrary-read"),AgentRunLimits.from(properties),false,policy);
    final ToolDefinition read = new ToolDefinition("arbitrary-read","read","{}",ToolRiskLevel.LOW,
            Map.of("provider","mcp","effect","READ_ONLY","mcpTrustedProcurement",true));

    @Test void largeSuccessfulReadRecoversWithOriginalEvidenceAndTraceUnchanged() {
        String content=json.writeValueAsString(Map.of("record",Map.of("id","item","gpu",24,"price",1500),
                "evidenceRef","critical-ref","notes","ordinary verbose data ".repeat(10000)));
        var pair=pair(content,true,ToolExecutionState.SUCCEEDED,Map.of());
        var original=store.findToolExecution("c").orElseThrow();
        var input=new ArrayList<>(pair);input.add(0,message("user",1,AgentMessageType.USER,"exact current requirement",Map.of()));
        var context=assemble(input,3000);
        assertEquals(true,context.metadata().get("recovery_triggered"));
        assertTrue(((Number)context.metadata().get("tokens_before_recovery")).longValue()>3000);
        assertTrue(context.estimatedTokens()<=3000);
        assertEquals(1,context.metadata().get("recovery_passes"));
        assertEquals(1,context.metadata().get("tool_results_reprojected"));
        var result=context.messages().stream().filter(AgentMessage::isToolResult).findFirst().orElseThrow();
        assertEquals("critical-ref",json.readTree(result.content()).path("evidenceRef").asText());
        assertEquals(24,json.readTree(result.content()).path("record").path("gpu").asInt());
        assertEquals(true,result.metadata().get("truncated"));
        assertEquals("tool-execution:c",result.metadata().get("rawReference"));
        assertEquals(original,store.findToolExecution("c").orElseThrow());
        assertEquals(content,pair.get(1).content());
        verify(store,never()).markSucceeded(anyString(),any());verify(store,never()).markFailed(anyString(),any());
    }

    @Test void canonicalCurrentRequestGovernanceAndLongEvidenceReferencesArePreserved() {
        String ref="evidence-"+"x".repeat(900);
        var pair=pair(json.writeValueAsString(Map.of("evidenceRef",ref,"notes","verbose ".repeat(20000))),true,ToolExecutionState.SUCCEEDED,Map.of());
        var canonical=message("case",0,AgentMessageType.CANONICAL_CONTEXT,"budget=Y approval=A caseVersion=7",Map.of("contextKind","business"));
        var governance=message("policy",0,AgentMessageType.SYSTEM,"approval required; never authorize yourself",Map.of());
        var user=message("user",1,AgentMessageType.USER,"current requirement GPU 48",Map.of());
        var input=new ArrayList<>(List.of(canonical,governance,user));input.addAll(pair);
        var view=assemble(input,4000);
        assertTrue(view.estimatedTokens()<=4000);assertTrue(view.messages().containsAll(List.of(canonical,governance,user)));
        assertTrue(view.messages().stream().anyMatch(m->m.content().contains(ref)));
        assertEquals("HITL and authorization required",profile.systemPrompt());
    }

    @Test void overflowEvictsOldProseAfterProjectionButKeepsRecentRequestsAndToolPairs() {
        var pair=pair(json.writeValueAsString(Map.of("notes","padding ".repeat(20000))),true,ToolExecutionState.SUCCEEDED,Map.of());
        // Timeline's first-level excerpt fits the trigger; reloading the complete store result exceeds it.
        var excerpt=new AgentMessage("result","s","r",5,AgentMessageType.TOOL_RESULT,"short excerpt","c","arbitrary-read",Map.of(),Map.of("success",true),0,Instant.now());
        var old=message("old",1,AgentMessageType.ASSISTANT_TEXT,"old non-authoritative explanation ".repeat(25),Map.of());
        var recent=message("recent",2,AgentMessageType.USER,"recent",Map.of());
        var current=message("current",3,AgentMessageType.USER,"exact latest",Map.of());
        var view=assemble(List.of(old,recent,current,pair.get(0),excerpt),400);
        assertEquals(true,view.metadata().get("recovery_triggered"));
        assertEquals(1,view.metadata().get("history_items_evicted"));
        assertTrue(view.estimatedTokens()<=400);
        assertFalse(view.messages().contains(old));assertTrue(view.messages().containsAll(List.of(recent,current)));
        assertEquals(1,view.messages().stream().filter(AgentMessage::isToolCall).count());
        assertEquals(1,view.messages().stream().filter(AgentMessage::isToolResult).count());
    }

    @Test void irreducibleTierAOverflowsOnceWithoutDroppingAuthority() {
        var canonical=message("case",0,AgentMessageType.CANONICAL_CONTEXT,"authority ".repeat(10000),Map.of());
        var current=message("u",1,AgentMessageType.USER,"current",Map.of());
        var view=assemble(List.of(canonical,current),100);
        assertEquals(List.of(canonical,current),view.messages());
        assertEquals(true,view.metadata().get("budgetExceeded"));assertEquals(1,view.metadata().get("recovery_passes"));
        assertTrue(view.estimatedTokens()>100);
    }

    @Test void normalContextIsUnchangedAndDoesNotRunRecovery() {
        var pair=pair("{\"record\":{\"id\":\"a\"},\"notes\":\"small\"}",true,ToolExecutionState.SUCCEEDED,Map.of());
        var context=assemble(pair,10000);
        var expected=projector.projectForContext("c",store.findToolExecution("c").orElseThrow().result());
        assertEquals(expected.content(),context.messages().get(1).content());
        assertEquals(false,context.metadata().get("recovery_triggered"));assertEquals(0,context.metadata().get("recovery_passes"));
    }

    @Test void failureWriteUnknownAndManualReviewEnvelopesAreNotRecovered() {
        String large=json.writeValueAsString(Map.of("notes","unsafe ".repeat(20000)));
        for (var effect:List.of("EXTERNAL_SIDE_EFFECT","INTERNAL_WRITE","DESTRUCTIVE")) {
            var pair=pair(large,true,ToolExecutionState.SUCCEEDED,Map.of());
            var tool=new ToolDefinition("arbitrary-read","write","{}",ToolRiskLevel.HIGH,Map.of("effect",effect));
            var view=assembler.assemble(new AgentContextView(pair,0,0,false),profile,null,List.of(tool),1000);
            assertEquals(0,view.metadata().get("tool_results_reprojected"));assertTrue(view.estimatedTokens()>1000);
        }
        for (var state:List.of(ToolExecutionState.FAILED,ToolExecutionState.MANUAL_REVIEW,ToolExecutionState.RUNNING)) {
            var pair=pair(large,false,state,Map.of("manualReview",true));
            var view=assemble(pair,1000);assertEquals(0,view.metadata().get("tool_results_reprojected"));
            assertTrue(view.estimatedTokens()>1000);
        }
    }

    @Test void criticalDecisionSubtreesStayWholeEvenWhenHugeAndUnknownTextFailsSafe() {
        for(String key:List.of("record","evidence","approval","hardConstraints","recordsTable")) {
            String large=json.writeValueAsString(Map.of(key,"critical ".repeat(20000)));
            var view=assemble(pair(large,true,ToolExecutionState.SUCCEEDED,Map.of()),500);
            assertEquals(0,view.metadata().get("tool_results_reprojected"));assertTrue(view.estimatedTokens()>500);
        }
        var text="prose evidence ref in unknown format ".repeat(5000)+"CRITICAL-TAIL";
        assertTrue(assemble(pair(text,true,ToolExecutionState.SUCCEEDED,Map.of()),500).messages().get(1).content().endsWith("CRITICAL-TAIL"));
    }

    @Test void unpersistedAndFailedResultsCannotBePromotedToRecoverableReads() {
        String content=json.writeValueAsString(Map.of("notes","large ".repeat(20000)));
        var failed=pair(content,false,ToolExecutionState.FAILED,Map.of());
        assertEquals(0,assemble(failed,500).metadata().get("tool_results_reprojected"));
        var unknown=pair(content,true,ToolExecutionState.SUCCEEDED,Map.of());
        when(store.findToolExecution("c")).thenReturn(Optional.empty());
        assertEquals(0,assemble(unknown,500).metadata().get("tool_results_reprojected"));
    }

    @Test void inconsistentTerminalStateAndRawManualReviewCannotBeRecovered() {
        String content=json.writeValueAsString(Map.of("notes","unsafe ".repeat(20000)));
        var inconsistent=pair(content,true,ToolExecutionState.FAILED,Map.of());
        var failedView=assemble(inconsistent,500);
        assertEquals(0,failedView.metadata().get("tool_results_reprojected"));
        assertTrue(failedView.estimatedTokens()>500);

        var pair=pair(content,true,ToolExecutionState.SUCCEEDED,Map.of("manualReview",true));
        var result=pair.get(1);
        // The persisted result remains authoritative even when the timeline lacks its review marker.
        var timeline=new AgentMessage(result.messageId(),result.sessionId(),result.runId(),result.sequence(),
                result.type(),result.content(),result.toolCallId(),result.toolName(),result.arguments(),
                Map.of("success",true),0,result.createdAt());
        var reviewView=assemble(List.of(pair.get(0),timeline),500);
        assertEquals(0,reviewView.metadata().get("tool_results_reprojected"));
        assertTrue(reviewView.estimatedTokens()>500);
    }

    AgentContextView assemble(List<AgentMessage> m,long budget) {
        return assembler.assemble(new AgentContextView(m,0,0,false),profile,null,List.of(read),budget);
    }
    List<AgentMessage> pair(String content,boolean success,ToolExecutionState state,Map<String,Object> extra) {
        var raw=new ToolCallResult("arbitrary-read",success,content,"",extra);
        when(store.findToolExecution("c")).thenReturn(Optional.of(new ToolExecutionRecord("c","r","arbitrary-read",state,
                new ToolCallRequest("arbitrary-read","c",Map.of()),raw,1,"",Instant.now(),Instant.now())));
        Map<String,Object> md=new HashMap<>(extra);md.put("success",success);
        return List.of(new AgentMessage("call","s","r",4,AgentMessageType.ASSISTANT_TOOL_CALL,"","c","arbitrary-read",Map.of(),Map.of(),0,Instant.now()),
                new AgentMessage("result","s","r",5,AgentMessageType.TOOL_RESULT,content,"c","arbitrary-read",Map.of(),md,0,Instant.now()));
    }
    AgentMessage message(String id,long seq,AgentMessageType type,String text,Map<String,Object> md) {
        return new AgentMessage(id,"s","old",seq,type,text,"","",Map.of(),md,0,Instant.now());
    }
}
