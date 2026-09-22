package com.agent.platform.common;

import com.agent.platform.agent.AgentRequest;
import com.agent.platform.approval.*;
import com.agent.platform.config.*;
import com.agent.platform.guardrail.GuardrailService;
import com.agent.platform.runtime.*;
import com.agent.platform.tool.*;
import com.agent.platform.workbench.application.*;
import com.agent.platform.workbench.dispatch.*;
import com.agent.platform.workbench.model.*;
import com.agent.platform.workbench.persistence.*;
import com.agent.platform.workbench.security.AuthenticatedPrincipal;
import com.agent.platform.workbench.target.*;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class BusinessRetirementBoundaryTests {
    private final AuthenticatedPrincipal principal = new AuthenticatedPrincipal("tenant", "user", Set.of("USER"));

    @Test
    void historicalEnumIdsRemainJsonReadableButNotExecutable() {
        ObjectMapper mapper = new ObjectMapper();
        for (String id : List.of("ORDERCARE_CASE", "INCIDENT_INVESTIGATION", "INCIDENT_RECOVERY_PLAN")) {
            ExecutionTargetId target = mapper.readValue("\"" + id + "\"", ExecutionTargetId.class);
            assertEquals(id, target.name());
            assertFalse(target.executable());
            assertTrue(new ExecutionTargetRegistry().findEnabled(principal, id).isEmpty());
        }
        assertEquals(Set.of(ExecutionTargetId.GENERAL_AGENT, ExecutionTargetId.PROCUREMENT_SOURCING),
                ExecutionTargetId.executableTargets());
    }

    @Test
    void modelCannotReinterpretLegacyInputAsProcurementOrGeneral() {
        var validator = new RoutePolicyValidator(new ExecutionTargetRegistry(), new WorkbenchRoutingProperties(), new ObjectMapper());
        for (String target : List.of("GENERAL_AGENT", "PROCUREMENT_SOURCING", "ORDERCARE_CASE", "INCIDENT_INVESTIGATION", "INCIDENT_RECOVERY_PLAN")) {
            var decision = new ExecutionDecision(target, 1, "model override", Map.of(), List.of(), "");
            var result = validator.validate(decision, new RouteValidationContext(principal, mock(AgentWorkItem.class),
                    "调查批次 BATCH-20260720-01 在队列 floworder.incident.e2e.dlq 的异常订单事故，候选 requestId 为 IC-HAPPY-REQ-001", Map.of(), Map.of()));
            assertEquals(RouteDisposition.REJECT, result.disposition());
            assertEquals("TARGET_RETIRED", result.failureCode());
            assertNull(result.validatedInput());
        }
    }

    @Test
    void rawIntakeIsRejectedBeforeModelCanRewriteTheGoal() {
        RoutingStore routing = mock(RoutingStore.class);
        WorkbenchStore workbench = mock(WorkbenchStore.class);
        WorkCommandClassifier classifier = mock(WorkCommandClassifier.class);
        var service = new UnifiedWorkIntakeService(routing, workbench, classifier);
        assertThrows(RetiredBusinessException.class, () -> service.accept(principal,
                new UnifiedWorkInputRequest("i", "c", "conversation", "调查批次 BATCH-20260720-01 在队列 floworder.incident.e2e.dlq 的异常订单事故，候选 requestId 为 IC-HAPPY-REQ-001", ClassifierType.MODEL, null, "")));
        verifyNoInteractions(routing, workbench, classifier);
    }

    @Test
    void directRuntimeRejectsLegacyScenarioAndRestoredRunBeforeMutation() {
        AgentRunStore runs = mock(AgentRunStore.class);
        var runtime = new DefaultAgentRuntime(new AgentProperties(), null, runs, null, null, null,
                null, null, null, List.of(), null, null, null, null, null, null);
        AgentExecutionProfile profile = mock(AgentExecutionProfile.class);
        when(profile.name()).thenReturn("general-safe-v1");
        assertThrows(RetiredBusinessException.class, () -> runtime.run(
                new AgentRequest("c", "u", "inspect", Map.of(), "ordercare-floworder-v1"), profile, AgentEventListener.NOOP));
        verifyNoInteractions(runs);
        AgentRunRecord historical = mock(AgentRunRecord.class);
        when(historical.request()).thenReturn(new AgentRequest("c", "u", "inspect", Map.of("executionTarget", "ORDERCARE_CASE")));
        when(runs.find("old-run")).thenReturn(Optional.of(historical));
        assertThrows(RetiredBusinessException.class, () -> runtime.resume("old-run", AgentEventListener.NOOP));
        assertThrows(RetiredBusinessException.class, () -> runtime.completeWaitingInput("old-run"));
        assertThrows(RetiredBusinessException.class, () -> runtime.continueWithInput("old-run", mock(AgentFollowUpInput.class), AgentEventListener.NOOP));
        verify(runs, times(3)).find("old-run");
        verifyNoMoreInteractions(runs);
    }

    @Test
    void oldApprovalRemainsReadableWithoutExpiryMutationAndCannotExecuteAsRfq() {
        ApprovalStore store = mock(ApprovalStore.class);
        var service = new LocalApprovalService(store, new AgentProperties());
        var request = new ToolCallRequest("floworder_recovery_execute", "call", Map.of("proposalId", "old"));
        var record = new ApprovalRecord("approval", "run", "conversation", request, "old approval",
                ApprovalStatus.REQUESTED, "", "", Instant.EPOCH, Instant.EPOCH.plusSeconds(1), null);
        when(store.find("approval")).thenReturn(Optional.of(record));
        assertSame(record, service.find("approval").orElseThrow());
        assertThrows(RetiredBusinessException.class, () -> service.decide("approval", true, "user", "approve"));
        assertThrows(RetiredBusinessException.class, () -> service.requestApproval(
                new ApprovalRequest("new", "run", "conversation", request, "", Instant.now())));
        verify(store, times(2)).find("approval");
        verifyNoMoreInteractions(store);
    }

    @Test
    void toolRuntimeBlocksCallsApprovalsAndReconciliationBeforeClaimOrProvider() {
        GuardrailService guardrails = mock(GuardrailService.class);
        ApprovalService approvals = mock(ApprovalService.class);
        ToolExecutionStore executions = mock(ToolExecutionStore.class);
        AgentCapabilityExecutor executor = mock(AgentCapabilityExecutor.class);
        var runtime = new DefaultAgentToolRuntime(guardrails, approvals, executions, executor, new AgentProperties(), List.of(), List.of());
        for (String name : List.of("floworder_recovery_execute", "mcp.floworder.query_order", "delegate_order_analyst")) {
            assertThrows(RetiredBusinessException.class, () -> runtime.execute("r", "s", "u", Map.of(),
                    new AgentToolCall("call", name, Map.of(), "{}"), null));
            ApprovalRecord approval = mock(ApprovalRecord.class);
            when(approval.status()).thenReturn(ApprovalStatus.APPROVED);
            when(approval.toolCallRequest()).thenReturn(new ToolCallRequest(name, "call", Map.of()));
            assertThrows(RetiredBusinessException.class, () -> runtime.executeApproved(approval, null, null));
            ToolExecutionRecord record = mock(ToolExecutionRecord.class);
            when(record.toolName()).thenReturn(name);
            when(record.state()).thenReturn(ToolExecutionState.RUNNING);
            assertThrows(RetiredBusinessException.class, () -> runtime.reconcileUncertain(record));
        }
        verifyNoInteractions(guardrails, approvals, executions, executor);
    }

    @Test
    void dispatchAndRoutingRecoverySkipOldRowsAndStillVisitProcurement() {
        AgentWorkItem old = work("old", "INCIDENT_INVESTIGATION");
        AgentWorkItem procurement = work("procurement", "PROCUREMENT_SOURCING");
        DispatchStore dispatchStore = mock(DispatchStore.class);
        DispatchCoordinator dispatcher = mock(DispatchCoordinator.class);
        WorkbenchDispatchProperties dispatchProperties = new WorkbenchDispatchProperties();
        dispatchProperties.setEnabled(true);
        when(dispatchStore.findStaleDispatch(any(), anyInt())).thenReturn(List.of(
                new DispatchRecoveryCandidate(old, principal), new DispatchRecoveryCandidate(procurement, principal)));
        new DispatchReconciler(dispatchStore, dispatcher, dispatchProperties).reconcileStaleDispatches();
        verify(dispatcher).dispatch(principal, "procurement");
        verifyNoMoreInteractions(dispatcher);
        RoutingStore routingStore = mock(RoutingStore.class);
        RoutingCoordinator router = mock(RoutingCoordinator.class);
        WorkbenchRoutingProperties routingProperties = new WorkbenchRoutingProperties();
        routingProperties.setEnabled(true);
        when(routingStore.findStaleRouting(any(), anyInt())).thenReturn(List.of(
                new RoutingRecoveryCandidate(old, principal), new RoutingRecoveryCandidate(procurement, principal)));
        new RoutingRecoveryScanner(routingStore, router, routingProperties).reconcileStaleRoutingWorkItems();
        verify(router).route(principal, "procurement", "route-procurement");
        verifyNoMoreInteractions(router);
    }

    @Test
    void directOldWorkCommandCannotReachTheRuntime() {
        AgentRuntime runtime = mock(AgentRuntime.class);
        AgentRunStore runs = mock(AgentRunStore.class);
        var result = new AgentRunWorkCommandAdapter(runtime, runs).execute(principal,
                work("old", "ORDERCARE_CASE"), WorkCommandType.RESUME_ACTIVE_WORK);
        assertFalse(result.accepted());
        assertEquals("TARGET_RETIRED", result.code());
        verifyNoInteractions(runtime, runs);
    }


    @Test
    void activeRoutingStillChecksProtectedFieldsAndIdentifierSources() {
        var validator = new RoutePolicyValidator(new ExecutionTargetRegistry(), new WorkbenchRoutingProperties(), new ObjectMapper());
        var context = new RouteValidationContext(principal, mock(AgentWorkItem.class), "explain queueName=queue-1", Map.of(), Map.of());
        var accepted = validator.validate(new ExecutionDecision("GENERAL_AGENT", 1, "", Map.of("queueName", "queue-1"), List.of(), ""), context);
        assertEquals(RouteDisposition.AUTO_DISPATCH, accepted.disposition());
        assertEquals(IdentifierSource.EXPLICIT_USER_INPUT, accepted.validatedInput().identifiers().get("queueName").source());
        var inferred = validator.validate(new ExecutionDecision("GENERAL_AGENT", 1, "", Map.of("queueName", "invented"), List.of(), ""), context);
        assertEquals(RouteDisposition.REQUIRE_CLARIFICATION, inferred.disposition());
        var collision = validator.validate(new ExecutionDecision("GENERAL_AGENT", 1, "", Map.of("queueName", "queue-1", "batchId", "queue-1"), List.of(), ""), context);
        assertEquals(RouteDisposition.REQUIRE_CLARIFICATION, collision.disposition());
        for (String field : List.of("scenarioId", "executionProfile", "toolName", "approvedBy", "roles", "tenantId", "url", "sql")) {
            var forbidden = validator.validate(new ExecutionDecision("PROCUREMENT_SOURCING", 1, "", Map.of(field, "injected"), List.of(), ""), context);
            assertEquals(RouteDisposition.REJECT, forbidden.disposition(), field);
            assertEquals("POLICY_REJECTED", forbidden.failureCode(), field);
        }
    }

    @Test
    void versionedRoutingEvalKeepsLegacyCasesAsDeterministicRejections() {
        var cases = new com.agent.platform.workbench.eval.WorkbenchRoutingEvalSuite().cases().stream()
                .filter(c -> c.expectedTarget() != null && !c.expectedTarget().executable()).toList();
        assertFalse(cases.isEmpty());
        assertTrue(cases.stream().allMatch(c -> c.expectedDisposition() == RouteDisposition.REJECT));
        var model = mock(UnifiedTaskRouter.class);
        var classifier = mock(WorkCommandClassifier.class);
        var registry = new ExecutionTargetRegistry();
        var runner = new com.agent.platform.workbench.eval.WorkbenchRoutingEvalRunner(classifier, model, registry,
                new RoutePolicyValidator(registry, new WorkbenchRoutingProperties(), new ObjectMapper()), new ExecutionTargetCandidateResolver());
        var report = runner.run(principal, cases);
        assertEquals(cases.size(), report.passedCases(), report.results().stream().filter(r -> !r.passed()).toList().toString());
        assertEquals(cases.size(), report.routeTargetCorrect());
        verifyNoInteractions(model, classifier);
    }


    @Test
    void oldWorkCommandIsRejectedBeforeClaimingOrUpdatingHistory() {
        var input = mock(AgentConversationTurn.class);
        when(input.tenantId()).thenReturn("tenant");
        when(input.ownerPrincipalId()).thenReturn("user");
        when(input.inputId()).thenReturn("input");
        when(input.conversationId()).thenReturn("conversation");
        var decision = mock(WorkCommandDecision.class);
        when(decision.tenantId()).thenReturn("tenant");
        when(decision.ownerPrincipalId()).thenReturn("user");
        when(decision.inputId()).thenReturn("input");
        when(decision.commandType()).thenReturn(WorkCommandType.RESUME_ACTIVE_WORK);
        var work = work("old", "ORDERCARE_CASE");
        when(work.conversationId()).thenReturn("conversation");
        var workbench = mock(WorkbenchStore.class);
        var commands = mock(WorkCommandExecutionStore.class);
        var adapter = mock(AgentRunWorkCommandAdapter.class);
        when(workbench.findWorkItem(principal, "old")).thenReturn(Optional.of(work));
        var handler = new WorkCommandHandler(workbench, commands, new ExecutionCommandCapabilityRegistry(), adapter);
        var result = handler.handle(principal, new WorkCommandRequest(input, decision, "old", null));
        assertEquals("TARGET_RETIRED", result.code());
        verify(commands).findByInput(principal, "input");
        verifyNoMoreInteractions(commands);
        verifyNoInteractions(adapter);
    }

    @Test
    void claimedOldDispatchIsRejectedBeforeBudgetReconciliationOrAdapter() {
        var store = mock(DispatchStore.class);
        var registry = mock(ExecutionAdapterRegistry.class);
        var budgets = mock(com.agent.platform.workbench.budget.WorkItemBudgetGate.class);
        var properties = new WorkbenchDispatchProperties();
        properties.setEnabled(true);
        var request = new DispatchRequest("d", "old", "c", "goal", "ORDERCARE_CASE", principal,
                new ValidatedExecutionInput("ORDERCARE_CASE", Map.of(), Map.of(), "digest"), Instant.now());
        when(store.claimDispatch(eq(principal), eq("old"), any(), anyInt(), anyString(), any()))
                .thenReturn(Optional.of(new DispatchClaim(null, request)));
        var coordinator = new DispatchCoordinator(store, registry, properties, (claim, result) -> {}, budgets);
        assertThrows(RetiredBusinessException.class, () -> coordinator.dispatch(principal, "old"));
        verifyNoInteractions(registry, budgets);
        verify(store).claimDispatch(eq(principal), eq("old"), any(), anyInt(), anyString(), any());
        verifyNoMoreInteractions(store);
    }

    @Test
    void directLocalToolCallCannotReachALegacyHandler() {
        ToolRegistry registry = mock(ToolRegistry.class);
        ToolRunRecorder recorder = mock(ToolRunRecorder.class);
        var executor = new LocalToolExecutor(registry, null, recorder, null, null, null);
        var result = executor.execute(new ToolCallRequest("floworder_case_inspect", "call", Map.of()));
        assertFalse(result.success());
        assertEquals("TARGET_RETIRED", result.metadata().get("errorCode"));
        verifyNoInteractions(registry);
        verify(recorder).record(any());
    }

    private AgentWorkItem work(String id, String target) {
        AgentWorkItem work = mock(AgentWorkItem.class);
        when(work.workItemId()).thenReturn(id);
        when(work.activeExecutionTarget()).thenReturn(target);
        when(work.originalGoal()).thenReturn("采购笔记本");
        when(work.routingRequestId()).thenReturn("route-" + id);
        return work;
    }
}
