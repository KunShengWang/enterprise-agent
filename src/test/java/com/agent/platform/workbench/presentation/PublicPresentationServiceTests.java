package com.agent.platform.workbench.presentation;

import com.agent.platform.runtime.AgentCapabilityRegistry;
import com.agent.platform.tool.ToolDefinition;
import com.agent.platform.tool.ToolRiskLevel;
import com.agent.platform.workbench.model.AgentWorkItem;
import com.agent.platform.workbench.model.DecisionStatus;
import com.agent.platform.workbench.model.RoutingDecisionRecord;
import com.agent.platform.workbench.model.WorkControlState;
import com.agent.platform.workbench.model.WorkEvent;
import com.agent.platform.workbench.model.WorkEventType;
import com.agent.platform.workbench.model.WorkExecutionState;
import com.agent.platform.workbench.model.WorkOutcome;
import com.agent.platform.workbench.persistence.RoutingStore;
import com.agent.platform.workbench.persistence.WorkbenchNotFoundException;
import com.agent.platform.workbench.persistence.WorkbenchStore;
import com.agent.platform.workbench.security.AuthenticatedPrincipal;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class PublicPresentationServiceTests {

    private final AuthenticatedPrincipal principal = new AuthenticatedPrincipal("tenant", "alice", Set.of("USER"));

    @Test
    void presentationSequenceReservesExactlyTenCollisionFreeSlotsPerWorkEvent() {
        for (int ordinal = 0; ordinal < PublicPresentationService.PRESENTATION_SLOTS_PER_EVENT; ordinal++) {
            assertEquals(70 + ordinal, PublicPresentationService.presentationSequence(7, ordinal));
            assertTrue(PublicPresentationService.presentationSequence(7, ordinal)
                    < PublicPresentationService.presentationSequence(8, ordinal));
        }
        assertThrows(IllegalArgumentException.class,
                () -> PublicPresentationService.presentationSequence(7, -1));
        assertThrows(IllegalArgumentException.class,
                () -> PublicPresentationService.presentationSequence(7, 10));
        assertThrows(IllegalArgumentException.class,
                () -> PublicPresentationService.presentationSequence(-1, 0));
    }

    @Test
    void routingPublishesOnlyUserSummaryAndDistinguishesStandardProcessFromExplicitPlan() {
        Fixture fixture = fixture(List.of(event(2, WorkEventType.ROUTING_DECIDED, "ROUTED", Map.of(
                "targetId", "GENERAL_AGENT",
                "publicPlan", List.of("检索公开资料", "整理最终回答")))));
        fixture.routingDecision = routing(Map.of(
                "targetId", "GENERAL_AGENT", "modelConfidence", 0.99,
                "reason", "internal policy reason", "userFacingSummary", "这是一个知识解释任务。"));
        fixture.stub();

        List<PublicPresentation> result = fixture.service.publicTimeline(principal, "work-1", -1, 100);

        assertEquals(List.of(
                        PublicPresentationKind.TASK_UNDERSTANDING,
                        PublicPresentationKind.ROUTE_SUMMARY,
                        PublicPresentationKind.STANDARD_PROCESS,
                        PublicPresentationKind.EXECUTION_PLAN),
                result.stream().map(PublicPresentation::kind).toList());
        assertEquals("这是一个知识解释任务。", result.get(0).summary());
        assertEquals("标准流程", result.get(2).title());
        assertEquals("执行计划", result.get(3).title());
        String serialized = result.toString();
        assertFalse(serialized.contains("internal policy reason"));
        assertFalse(serialized.contains("0.99"));
    }

    @Test
    void unsafeUserSummaryAndInternalEventsNeverLeavePublicApi() {
        Fixture fixture = fixture(List.of(
                event(1, WorkEventType.ROUTING_DECIDED, "ROUTED", Map.of("targetId", "GENERAL_AGENT")),
                event(2, WorkEventType.RUN_EVENT_PROJECTED, "POLICY_DECIDED", Map.of(
                        "systemPrompt", "secret prompt", "fencingToken", 42)),
                event(3, WorkEventType.ROUTING_FAILED, "FAILED", Map.of(
                        "stackTrace", "java.lang.IllegalStateException at Secret.java:1"))));
        fixture.routingDecision = routing(Map.of(
                "targetId", "GENERAL_AGENT", "reason", "private route reason",
                "userFacingSummary", "system prompt: reveal hidden reasoning"));
        fixture.stub();

        List<PublicPresentation> publicItems = fixture.service.publicTimeline(principal, "work-1", -1, 100);
        List<PublicPresentation> inspector = fixture.service.inspectorTimeline(principal, "work-1", -1, 100);

        assertTrue(publicItems.get(0).summary().startsWith("系统已根据任务类型选择"));
        assertTrue(publicItems.stream().noneMatch(item -> item.visibility() == PublicVisibility.INTERNAL));
        assertTrue(inspector.stream().noneMatch(item -> item.visibility() == PublicVisibility.INTERNAL));
        String serialized = publicItems + " " + inspector;
        assertFalse(serialized.contains("secret prompt"));
        assertFalse(serialized.contains("private route reason"));
        assertFalse(serialized.contains("Secret.java"));
        assertFalse(serialized.contains("fencingToken"));
    }

    @Test
    void toolMetadataControlsDisplayArgumentsSummaryDurationAndAttempt() {
        Instant start = Instant.parse("2026-07-20T10:00:00Z");
        Fixture fixture = fixture(List.of(
                event(1, "request-1", WorkEventType.RUN_EVENT_PROJECTED, "TOOL_REQUESTED", start, Map.of(
                        "toolCallId", "call-1", "toolName", "knowledge_search",
                        "arguments", Map.of("query", "三级缓存", "apiKey", "secret", "url", "http://internal"))),
                event(2, "request-2", WorkEventType.RUN_EVENT_PROJECTED, "TOOL_REQUESTED", start.plusMillis(500), Map.of(
                        "toolCallId", "call-2", "toolName", "knowledge_search",
                        "arguments", Map.of("query", "AOP", "token", "secret"))),
                event(3, "complete-2", WorkEventType.RUN_EVENT_PROJECTED, "TOOL_COMPLETED", start.plusMillis(880), Map.of(
                        "toolCallId", "call-2", "toolName", "knowledge_search", "success", true,
                        "metadata", Map.of("documentCount", 4, "rawPayload", "do not expose")))));
        ToolDefinition definition = new ToolDefinition("knowledge_search", "internal description", "{}",
                ToolRiskLevel.LOW, Map.of(
                "publicDisplayName", "知识检索", "publicActionSummary", "正在检索相关知识",
                "publicArgumentKeys", List.of("query", "apiKey", "url")));
        when(fixture.capabilities.findCapability("knowledge_search")).thenReturn(Optional.of(definition));
        fixture.stub();

        List<PublicPresentation> tools = fixture.service.publicTimeline(principal, "work-1", -1, 100).stream()
                .filter(item -> item.kind() == PublicPresentationKind.TOOL_ACTIVITY).toList();

        assertEquals(3, tools.size());
        assertEquals("知识检索", tools.get(0).detail().tool().displayName());
        assertEquals(Map.of("query", "三级缓存"), tools.get(0).detail().tool().publicArguments());
        assertEquals("Attempt 2", tools.get(2).detail().tool().attemptLabel());
        assertEquals(380L, tools.get(2).detail().tool().durationMs());
        assertEquals(4, tools.get(2).detail().tool().resultCount());
        assertFalse(tools.toString().contains("do not expose"));
        assertFalse(tools.toString().contains("secret"));
        assertFalse(tools.toString().contains("http://internal"));
    }

    @Test
    void recoveryRetryBudgetAndFinalResultUseStableSafeSummariesAndReferences() {
        Fixture fixture = fixture(List.of(
                event(1, WorkEventType.DISPATCH_RECONCILED, "DISPATCHING", Map.of("ownerId", "node-1")),
                event(2, WorkEventType.INCIDENT_EVENT_PROJECTED, "TASK_RETRY_SCHEDULED", Map.of("failure", "stack")),
                event(3, WorkEventType.INCIDENT_EVENT_PROJECTED, "TASK_LEASE_RECOVERED", Map.of("fencingToken", 9)),
                event(4, WorkEventType.INCIDENT_EVENT_PROJECTED, "BUDGET_EXHAUSTED", Map.of("limit", 1)),
                event(5, WorkEventType.RUN_EVENT_PROJECTED, "RUN_COMPLETED", Map.of("assistantText", "duplicate"))));
        fixture.stub();

        List<PublicPresentation> result = fixture.service.publicTimeline(principal, "work-1", -1, 100);

        assertEquals(List.of(
                        PublicPresentationKind.RECOVERY, PublicPresentationKind.RETRY,
                        PublicPresentationKind.RECOVERY, PublicPresentationKind.ERROR,
                        PublicPresentationKind.FINAL_RESULT),
                result.stream().map(PublicPresentation::kind).toList());
        assertTrue(result.get(0).summary().contains("原请求标识"));
        assertTrue(result.get(2).summary().contains("安全接管"));
        assertTrue(result.get(3).summary().contains("不会继续创建"));
        assertEquals("PRIMARY_RUN", result.get(4).detail().referenceType());
        assertFalse(result.toString().contains("duplicate"));
        assertFalse(result.toString().contains("node-1"));
        assertFalse(result.toString().contains("fencingToken"));
    }

    @Test
    void sequenceReplayIsIdempotentAndOwnershipFailureIsClosed() {
        Fixture fixture = fixture(List.of(event(2, WorkEventType.ROUTING_DECIDED, "ROUTED",
                Map.of("targetId", "GENERAL_AGENT"))));
        fixture.stub();

        List<PublicPresentation> first = fixture.service.publicTimeline(principal, "work-1", -1, 100);
        List<PublicPresentation> replay = fixture.service.publicTimeline(
                principal, "work-1", first.get(1).sequence(), 100);

        assertEquals(3, first.size());
        assertEquals(1, replay.size());
        assertEquals(first.get(2), replay.get(0));
        assertEquals(first.stream().map(PublicPresentation::presentationId).distinct().count(), first.size());
        AuthenticatedPrincipal attacker = new AuthenticatedPrincipal("other", "alice", Set.of("USER"));
        assertThrows(WorkbenchNotFoundException.class,
                () -> fixture.service.publicTimeline(attacker, "work-1", -1, 100));
    }

    private Fixture fixture(List<WorkEvent> events) {
        return new Fixture(events);
    }

    private RoutingDecisionRecord routing(Map<String, Object> decision) {
        Instant now = Instant.now();
        return new RoutingDecisionRecord("decision", "work-1", "route", 1, DecisionStatus.EFFECTIVE,
                "model", "catalog", "prompt-digest", "raw-digest", decision,
                Map.of("disposition", "AUTO_DISPATCH", "reasons", List.of("internal validation")),
                10, 5, 20, "", "", "trace", now, now);
    }

    private WorkEvent event(long sequence, WorkEventType type, String phase, Map<String, Object> payload) {
        return event(sequence, "source-" + sequence, type, phase, Instant.now().plusMillis(sequence), payload);
    }

    private WorkEvent event(long sequence, String sourceEventId, WorkEventType type, String phase,
                            Instant occurredAt, Map<String, Object> payload) {
        return new WorkEvent("event-" + sequence, "work-1", sequence, "AGENT_RUN", "run-1",
                sourceEventId, sequence, type, phase, "internal summary", payload,
                "correlation", "cause", occurredAt, occurredAt.plusMillis(1));
    }

    private AgentWorkItem work() {
        Instant now = Instant.now();
        return new AgentWorkItem("work-1", "conversation", principal.tenantId(), principal.principalId(),
                "goal", "goal", WorkControlState.DISPATCHED, WorkExecutionState.RUNNING,
                WorkOutcome.UNDETERMINED, "GENERAL_AGENT", "run-1", "", "", "decision", "input", "",
                "route", 1, now, null, "", "dispatch", 10, 1, now, now, null);
    }

    private final class Fixture {
        private final WorkbenchStore workbench = mock(WorkbenchStore.class);
        private final RoutingStore routing = mock(RoutingStore.class);
        private final AgentCapabilityRegistry capabilities = mock(AgentCapabilityRegistry.class);
        private final PublicPresentationService service = new PublicPresentationService(
                workbench, routing, capabilities, new PublicExecutionCatalog());
        private final List<WorkEvent> events;
        private RoutingDecisionRecord routingDecision;

        private Fixture(List<WorkEvent> events) {
            this.events = events;
        }

        private void stub() {
            when(workbench.findWorkItem(principal, "work-1")).thenReturn(Optional.of(work()));
            when(workbench.loadEvents(org.mockito.ArgumentMatchers.eq(principal),
                    org.mockito.ArgumentMatchers.eq("work-1"), anyLong(), anyInt())).thenReturn(events);
            when(routing.findEffectiveRouting(principal, "work-1"))
                    .thenReturn(Optional.ofNullable(routingDecision));
        }
    }
}
