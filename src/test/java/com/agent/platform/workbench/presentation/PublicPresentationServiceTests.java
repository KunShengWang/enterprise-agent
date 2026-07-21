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
    void modelFailurePublishesOnlySafeActionableDiagnostics() {
        Fixture fixture = fixture(List.of(
                event(4, WorkEventType.RUN_EVENT_PROJECTED, "MODEL_FAILED", Map.of(
                        "errorType", "MODEL_PROTOCOL_ERROR", "providerBody", "sensitive")),
                event(5, WorkEventType.RUN_EVENT_PROJECTED, "RUN_FAILED", Map.of(
                        "stopReason", "MODEL_ERROR", "traceId", "trace-1"))));
        fixture.stub();

        PublicPresentation failure = fixture.service.publicTimeline(principal, "work-1", -1, 100).stream()
                .filter(item -> item.kind() == PublicPresentationKind.ERROR)
                .filter(item -> "模型调用失败".equals(item.title()))
                .findFirst().orElseThrow();

        assertEquals("系统未能获得模型响应，本次任务没有形成最终答案。", failure.summary());
        assertEquals("MODEL_PROTOCOL_ERROR", failure.detail().attributes().get("safeErrorCode"));
        assertEquals("true", failure.detail().attributes().get("retryable"));
        assertEquals("correlation", failure.detail().attributes().get("correlationId"));
        assertEquals("trace-1", failure.detail().attributes().get("traceId"));
        assertFalse(failure.toString().contains("sensitive"));
    }

    @Test
    void routeConfirmationIsNotPublishedAsToolApproval() {
        Fixture fixture = fixture(List.of(event(2, WorkEventType.ROUTE_CONFIRMATION_REQUIRED,
                "WAITING_CONFIRMATION", Map.of("previewId", "preview-1"))));
        fixture.stub();

        List<PublicPresentation> timeline = fixture.service.publicTimeline(principal, "work-1", -1, 100);

        PublicPresentation confirmation = timeline.stream()
                .filter(item -> item.kind() == PublicPresentationKind.CONFIRMATION_REQUIRED)
                .findFirst().orElseThrow();
        assertEquals("ROUTE_PREVIEW", confirmation.detail().referenceType());
        assertEquals("preview-1", confirmation.detail().referenceId());
        assertFalse(timeline.stream().anyMatch(item -> item.kind() == PublicPresentationKind.APPROVAL_REQUIRED));
    }

    @Test
    void scopeDiscoveryPublishesSafeBusinessProgressWithoutRawFacts() {
        Fixture fixture = fixture(List.of(
                event(1, WorkEventType.SCOPE_DISCOVERY_STARTED, "SCOPE_DISCOVERY_STARTED",
                        Map.of("timeExpression", "昨晚", "sql", "select secret")),
                event(2, WorkEventType.ORDER_CANDIDATES_DISCOVERED, "ORDER_CANDIDATES_DISCOVERED",
                        Map.of("snapshotId", "scope-1", "candidateCount", 18)),
                event(3, WorkEventType.RESOURCE_ENRICHMENT_COMPLETED, "RESOURCE_ENRICHMENT_COMPLETED",
                        Map.of("snapshotId", "scope-1", "sourceHealth", Map.of("resource", "AVAILABLE"))),
                event(4, WorkEventType.DEAD_LETTERS_RESOLVED, "DEAD_LETTERS_RESOLVED",
                        Map.of("snapshotId", "scope-1", "deadLetterCount", 9)),
                event(5, WorkEventType.QUEUES_RESOLVED, "QUEUES_RESOLVED",
                        Map.of("snapshotId", "scope-1", "queueCount", 1)),
                event(6, WorkEventType.SCOPE_DISCOVERY_COMPLETED, "SCOPE_DISCOVERY_COMPLETED",
                        Map.of("snapshotId", "scope-1", "candidateCount", 11, "rawPayload", "private")),
                event(7, WorkEventType.SCOPE_CONFIRMATION_REQUIRED, "SCOPE_CONFIRMATION_REQUIRED",
                        Map.of("snapshotId", "scope-1", "candidateFingerprint", "fingerprint"))));
        fixture.stub();

        List<PublicPresentation> result = fixture.service.publicTimeline(principal, "work-1", -1, 100);

        assertEquals(7, result.size());
        assertEquals("已发现候选订单", result.get(1).title());
        assertTrue(result.get(1).summary().contains("18"));
        assertEquals("INCIDENT_SCOPE_SNAPSHOT", result.get(6).detail().referenceType());
        assertFalse(result.toString().contains("select secret"));
        assertFalse(result.toString().contains("private"));
    }

    @Test
    void clarificationPublishesConcreteMissingInputsWithoutInternalReason() {
        Fixture fixture = fixture(List.of(event(3, WorkEventType.CLARIFICATION_REQUIRED, "WAITING_INPUT",
                Map.of("reasons", List.of("missing required inputs: queueNames,requestIds")))));
        fixture.routingDecision = routing(Map.of(
                "targetId", "INCIDENT_INVESTIGATION",
                "missingInputs", List.of("queueNames", "requestIds"),
                "reason", "internal router reason",
                "userFacingSummary", "需要补充事故范围。"));
        fixture.stub();

        PublicPresentation clarification = fixture.service.publicTimeline(principal, "work-1", -1, 100)
                .stream().filter(item -> item.kind() == PublicPresentationKind.WAITING_FOR_USER)
                .findFirst().orElseThrow();

        assertEquals("请在下方输入框补充以下信息，提交后系统会继续当前任务。", clarification.summary());
        assertEquals(List.of(
                "消息队列名称（queueNames），可填写一个或多个实际队列名",
                "一个或多个请求 ID（requestIds）"), clarification.steps());
        assertEquals("ADD_INPUT", clarification.detail().attributes().get("inputMode"));
        assertFalse(clarification.toString().contains("internal router reason"));
    }

    @Test
    void rejectedWorkCommandUsesItsOwnSafeCodeAndDoesNotPretendRunFailed() {
        Fixture fixture = fixture(List.of(event(6, WorkEventType.WORK_COMMAND_REJECTED, "REJECTED",
                Map.of("command", "ADD_INPUT_TO_ACTIVE_WORK", "code", "UNSUPPORTED_FOR_TARGET"))));
        fixture.stub();

        PublicPresentation rejected = fixture.service.publicTimeline(principal, "work-1", -1, 100)
                .stream().filter(item -> item.kind() == PublicPresentationKind.ERROR).findFirst().orElseThrow();

        assertEquals("指令未执行", rejected.title());
        assertEquals("UNSUPPORTED_FOR_TARGET", rejected.detail().attributes().get("safeErrorCode"));
        assertEquals("false", rejected.detail().attributes().get("retryable"));
        assertFalse(rejected.toString().contains("RUN_FAILED"));
    }

    @Test
    void incidentPublishesSafeDomainNarrativeWithoutRawPayloadOrReasoning() {
        Fixture fixture = fixture(List.of(
                event(1, WorkEventType.INCIDENT_EVENT_PROJECTED, "INCIDENT_STATE_CHANGED",
                        Map.of("targetStatus", "PLANNING", "internalReason", "hidden")),
                event(2, WorkEventType.INCIDENT_EVENT_PROJECTED, "TASK_ASSIGNMENT",
                        Map.of("recipientRole", "ORDER_ANALYST", "taskId", "task-order")),
                event(3, WorkEventType.INCIDENT_EVENT_PROJECTED, "EVIDENCE_SUBMITTED",
                        Map.of("senderRole", "ORDER_ANALYST", "taskId", "task-order",
                                "evidenceIds", List.of("evidence-1", "evidence-2"), "rawOutput", "secret")),
                event(4, WorkEventType.INCIDENT_EVENT_PROJECTED, "INCIDENT_STATE_CHANGED",
                        Map.of("targetStatus", "CHECKING_CONSISTENCY")),
                event(5, WorkEventType.INCIDENT_EVENT_PROJECTED, "INCIDENT_STATE_CHANGED",
                        Map.of("targetStatus", "REVIEWING")),
                event(6, WorkEventType.INCIDENT_EVENT_PROJECTED, "INCIDENT_STATE_CHANGED",
                        Map.of("targetStatus", "ASSESSED", "prompt", "private"))));
        fixture.stub();

        List<PublicPresentation> timeline = fixture.service.publicTimeline(principal, "work-1", -1, 100);

        assertEquals(List.of(
                        "已启动只读 Multi-Agent 调查",
                        "已派发 Order Specialist",
                        "Order Specialist 已完成取证",
                        "Specialist 已完成取证",
                        "Reviewer 正在汇总证据",
                        "已生成事故 Assessment"),
                timeline.stream().map(PublicPresentation::title).toList());
        assertEquals("2", timeline.get(2).detail().attributes().get("evidenceCount"));
        assertEquals("evidence-1, evidence-2", timeline.get(2).detail().attributes().get("evidenceIds"));
        assertEquals("Order", timeline.get(2).detail().attributes().get("role"));
        assertEquals("run-1", timeline.get(2).detail().attributes().get("incidentId"));
        assertTrue(timeline.get(5).summary().contains("未执行任何恢复操作"));
        assertFalse(timeline.toString().contains("hidden"));
        assertFalse(timeline.toString().contains("secret"));
        assertFalse(timeline.toString().contains("private"));
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
