package com.agent.platform.runtime;

import com.agent.platform.config.AgentProperties;
import com.agent.platform.memory.*;
import com.agent.platform.tool.*;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class ContextEngineeringTests {
    final ObjectMapper json = new ObjectMapper();
    final AgentProperties properties = new AgentProperties();
    final ToolExecutionStore store = mock(ToolExecutionStore.class);
    final ToolResultProjector projector = new ToolResultProjector(properties);
    final ContextAssembler assembler = new ContextAssembler(new ConservativeTokenEstimator(), store, projector);
    final AgentExecutionProfile profile = new AgentExecutionProfile("test", "fixed instructions", Set.of("lookup"),
            AgentRunLimits.from(properties), false, new ContextPolicy(true,32000,2000,0.75,2));

    @Test void tablePreservesEveryRecordAndCriticalTailIncludingProvenance() {
        List<Map<String,Object>> records=new ArrayList<>();
        for(int i=0;i<300;i++) records.add(Map.of("id","supplier-"+i,"price",i==299?1:900,
                "description","same product ".repeat(200),"source",Map.of("backend","ERPNext","version","v1","entityId","supplier-"+i)));
        String raw=json.writeValueAsString(Map.of("records",records,"nextOffset",300));
        var result=projector.projectForContext("c",new ToolCallResult("lookup",true,raw,"",Map.of()));
        var tree=json.readTree(result.content());
        assertEquals(300,tree.path("recordsTable").path("count").asInt());
        assertEquals(300,tree.path("nextOffset").asInt());
        assertTrue(result.content().contains("supplier-299"));
        assertEquals(records,json.readValue(expandTable(result.content()),List.class));
        assertTrue(result.content().length()<raw.length()/4);
        List<AgentMessage> pair=pair("c",1,raw);
        var context=assemble(pair,8000);
        assertTrue(context.estimatedTokens()<=8000);
        assertEquals(false,context.metadata().get("budgetExceeded"));
    }

    @Test void evidenceIdsAndUniqueFactsSurviveWhileOnlyEqualSubtreesAreFactored() {
        Map<String,Object> offer=Map.of("supplierId","S-tail","sourceDigest","digest","specifications",Map.of("gpu",24),"unitPrice",100);
        Map<String,Object> evidence=Map.of("evidenceId","E-tail","supplierId","S-tail","source","ERPNext","sourceDigest","digest","fact","spec {\"gpu\":24}");
        String raw=json.writeValueAsString(Map.of("offers",List.of(offer),"evaluations",List.of(Map.of("offer",offer,"eligible",false,"failures",List.of("inactive"))),"evidence",List.of(evidence)));
        String projected=projector.projectForContext("c",new ToolCallResult("search",true,raw,"",Map.of())).content();
        var node=json.readTree(projected);
        assertEquals("$.offers[0]",node.path("evaluations").get(0).path("offerRef").asText());
        assertEquals(24,node.path("offers").get(0).path("specifications").path("gpu").asInt());
        for(String value:List.of("E-tail","ERPNext","digest","inactive"))assertTrue(projected.contains(value));
        String unique=raw.replace("spec {", "unique note with unmatched trailing content {")+" ";
        assertTrue(projector.projectForContext("c",new ToolCallResult("search",true,unique,"",Map.of())).content().contains("unique note"));
    }

    @Test void identicalVersionFactsReplaceWholePairsButChangedVersionsRemain() {
        List<AgentMessage> source=new ArrayList<>();
        source.addAll(pair("a",1,"{\"record\":{\"id\":\"S\",\"source\":{\"version\":\"v1\",\"retrievedAt\":\"t1\"}}}"));
        source.addAll(pair("b",3,"{\"record\":{\"id\":\"S\",\"source\":{\"version\":\"v1\",\"retrievedAt\":\"t2\"}}}"));
        source.addAll(pair("c",5,"{\"record\":{\"id\":\"S\",\"source\":{\"version\":\"v2\",\"retrievedAt\":\"t3\"}}}"));
        var view=assemble(source,10000);
        assertEquals(4,view.messages().size());assertEquals(1,view.metadata().get("duplicateToolPairsRemoved"));
        assertEquals(List.of("b","b","c","c"),view.messages().stream().map(AgentMessage::toolCallId).toList());
        verify(store,never()).markSucceeded(anyString(),any()); verify(store,never()).markFailed(anyString(),any());
    }

    @Test void authoritativeStateIsFreshAndNeverRecoveredFromConversationSummary() {
        AgentTimelineStore timeline=mock(AgentTimelineStore.class);
        var summarizer=mock(ConversationSummarizer.class);var memory=mock(MemoryService.class);
        when(timeline.loadMessages(anyString(),anyInt())).thenReturn(List.of(message("old",1,AgentMessageType.CONTEXT_SUMMARY,"budget=1 quantity=1",Map.of()),
                message("u",2,AgentMessageType.USER,"buy requested SKU",Map.of())));
        AtomicReference<String> state=new AtomicReference<>("{\"budget\":300000,\"quantity\":20,\"hardConstraints\":{\"gpu\":24}}");
        AgentCanonicalContextProvider provider=(tenant,user,session,p)->Optional.of(new AgentCanonicalContextProvider.CanonicalContext("case",state.get(),Map.of("source","CaseStore")));
        var manager=new DefaultAgentContextManager(timeline,new ConservativeTokenEstimator(),memory,summarizer,properties,List.of(provider));
        var first=assembler.assemble(manager.project("s","u","t","buy",10000,profile),profile,null,List.of(),10000);
        assertTrue(first.messages().get(0).content().contains("300000"));
        assertTrue(first.messages().stream().noneMatch(m->m.type()==AgentMessageType.CONTEXT_SUMMARY));
        state.set("{\"budget\":250000,\"quantity\":12,\"hardConstraints\":{\"gpu\":48}}");
        var second=assembler.assemble(manager.project("s","u","t","buy",10000,profile),profile,null,List.of(),10000);
        assertTrue(second.messages().get(0).content().contains("250000"));assertTrue(second.messages().get(0).content().contains("48"));
        verifyNoInteractions(summarizer,memory);
    }

    @Test void approvalReferenceAndBusinessStateRemainEvenWhenBudgetCannotFit() {
        AgentRunRecord run=mock(AgentRunRecord.class);
        when(run.runtimeState()).thenReturn(Map.of("status","PAUSED","stopReason","WAITING_APPROVAL","pendingInterruption",Map.of("approvalId","approval-1")));
        when(run.runId()).thenReturn("r");when(run.conversationId()).thenReturn("s");
        var canonical=message("case",0,AgentMessageType.CANONICAL_CONTEXT,"budget=300000 quantity=20 gpu=24",Map.of("contextKind","procurement_case_state"));
        var view=assembler.assemble(new AgentContextView(List.of(canonical),0,0,false),profile,run,List.of(),1);
        assertTrue(view.estimatedTokens()>1);assertEquals(true,view.metadata().get("budgetExceeded"));
        assertTrue(view.messages().stream().anyMatch(m->m.content().contains("approval-1")));
        assertTrue(view.messages().stream().anyMatch(m->m.content().equals(canonical.content())));
    }

    @Test void oldConversationWindowIsBudgetDrivenAndKeepsCurrentRequestVerbatim() {
        List<AgentMessage> messages=List.of(message("old",1,AgentMessageType.USER,"old topic ".repeat(2000),Map.of()),
                message("new",2,AgentMessageType.USER,"last clarification",Map.of()),message("current",3,AgentMessageType.USER,"exact current question",Map.of()));
        assertEquals(3,assemble(messages,100000).messages().size());
        var compact=assemble(messages,1000);
        assertEquals(2,compact.messages().size());assertEquals("exact current question",compact.messages().get(1).content());
        assertEquals(1,compact.metadata().get("conversationMessagesRemoved"));
    }

    @Test void nonJsonResultsStayWholeAndReportBudgetOverflowInsteadOfDroppingTail() {
        String raw="important ".repeat(10000)+"CRITICAL-TAIL";
        var view=assemble(pair("c",1,raw),1000);
        assertTrue(view.messages().get(1).content().endsWith("CRITICAL-TAIL"));
        assertEquals(true,view.metadata().get("budgetExceeded"));
    }

    @Test void contextMetricsAreEstimatesAndDoNotModifyToolDefinitions() {
        ToolDefinition tool=new ToolDefinition("lookup","unchanged description","{}",ToolRiskLevel.LOW,Map.of());
        String before=json.writeValueAsString(tool);
        var view=assembler.assemble(new AgentContextView(pair("c",1,"{\"ok\":true}"),0,0,false),profile,null,List.of(tool),10000);
        assertEquals(before,json.writeValueAsString(tool));
        assertTrue(view.metadata().get("tokenAccounting").toString().startsWith("estimated"));
        assertTrue(view.metadata().containsKey("estimatedComposition"));
    }

    @Test void manualReviewOrRunningExecutionNeverReplacesTimelineSafetyEnvelope() {
        for (var state : List.of(ToolExecutionState.MANUAL_REVIEW, ToolExecutionState.RUNNING)) {
            var messages = pair("review", 1, "manual review required; execution outcome unknown");
            var unsafeResult = new ToolCallResult("lookup",true,"successful underlying result","",Map.of());
            when(store.findToolExecution("review")).thenReturn(Optional.of(new ToolExecutionRecord(
                    "review","r","lookup",state,new ToolCallRequest("lookup","review",Map.of()),
                    unsafeResult,1,"",Instant.now(),Instant.now())));
            assertEquals(messages,assemble(messages,10000).messages());
        }
    }

    @Test void unknownOrWriteToolResultsAreNotDeduplicated() {
        List<AgentMessage> source = new ArrayList<>(pair("a",1,"{\"ok\":true}"));
        source.addAll(pair("b",3,"{\"ok\":true}"));
        var view = assembler.assemble(new AgentContextView(source,0,0,false),profile,null,List.of(),10000);
        assertEquals(4,view.messages().size());
    }

    private AgentContextView assemble(List<AgentMessage> messages,long budget) {
        var readTool=new ToolDefinition("lookup","lookup","{}",ToolRiskLevel.LOW,Map.of("readOnly",true,"sideEffect",false));
        return assembler.assemble(new AgentContextView(messages,0,0,false),profile,null,List.of(readTool),budget);
    }
    private List<AgentMessage> pair(String id,long seq,String content) {
        var raw=new ToolCallResult("lookup",true,content,"",Map.of());
        when(store.findToolExecution(id)).thenReturn(Optional.of(new ToolExecutionRecord(id,"r","lookup",ToolExecutionState.SUCCEEDED,
                new ToolCallRequest("lookup",id,Map.of()),raw,1,"",Instant.now(),Instant.now())));
        return List.of(new AgentMessage("call-"+id,"s","r",seq,AgentMessageType.ASSISTANT_TOOL_CALL,"",id,"lookup",Map.of(),Map.of(),0,Instant.now()),
                new AgentMessage("result-"+id,"s","r",seq+1,AgentMessageType.TOOL_RESULT,content,id,"lookup",Map.of(),Map.of("success",true),0,Instant.now()));
    }
    private AgentMessage message(String id,long seq,AgentMessageType type,String content,Map<String,Object> metadata) {
        return new AgentMessage(id,"s","old-run",seq,type,content,"","",Map.of(),metadata,0,Instant.now());
    }
    @SuppressWarnings("unchecked")
    private String expandTable(String projection) {
        Map<String,Object> root=json.readValue(projection,Map.class);Map<String,Object> table=(Map<String,Object>)root.get("recordsTable");
        List<String> columns=(List<String>)table.get("columns");List<Map<String,Object>> rows=new ArrayList<>();
        for(List<Object> values:(List<List<Object>>)table.get("rows")) {
            Map<String,Object> flat=new TreeMap<>((Map<String,Object>)table.get("common"));
            for(int i=0;i<columns.size();i++)flat.put(columns.get(i),values.get(i));
            Map<String,Object> row=new LinkedHashMap<>();
            flat.forEach((key,value)->{String[] path=key.split("\\.");Map<String,Object> node=row;
                for(int i=0;i<path.length-1;i++)node=(Map<String,Object>)node.computeIfAbsent(path[i],k->new LinkedHashMap<>());
                node.put(path[path.length-1],value);});rows.add(row);
        }
        return json.writeValueAsString(rows);
    }
}
