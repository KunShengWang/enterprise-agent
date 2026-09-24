package com.agent.platform.workbench.application;

import com.agent.platform.agent.AgentRequest;
import com.agent.platform.agent.RuntimeAgentExecutor;
import com.agent.platform.config.AgentScenarioProfileResolver;
import com.agent.platform.config.WorkbenchRoutingProperties;
import com.agent.platform.procurement.application.ProcurementCaseService;
import com.agent.platform.procurement.config.ProcurementSourcingExecutionProfileFactory;
import com.agent.platform.procurement.persistence.ProcurementCaseStore;
import com.agent.platform.runtime.*;
import com.agent.platform.workbench.budget.WorkItemBudgetGate;
import com.agent.platform.workbench.dispatch.*;
import com.agent.platform.workbench.model.*;
import com.agent.platform.workbench.persistence.RoutingStore;
import com.agent.platform.workbench.persistence.WorkbenchStore;
import com.agent.platform.workbench.security.AuthenticatedPrincipal;
import com.agent.platform.workbench.target.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import tools.jackson.databind.ObjectMapper;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class ProcurementOnlyWorkbenchRoutingTests {
    private final AuthenticatedPrincipal principal = new AuthenticatedPrincipal("tenant", "user", Set.of("USER"));
    private final RoutingStore routing = mock(RoutingStore.class);
    private final WorkbenchStore workbench = mock(WorkbenchStore.class);
    // A model that would choose General must never get a chance to influence a new workbench Run.
    private final UnifiedTaskRouter router = mock(UnifiedTaskRouter.class);
    private final ProcurementCaseService cases = mock(ProcurementCaseService.class);
    private final RouteContextResolver context = mock(RouteContextResolver.class);
    private final WorkItemBudgetGate budgets = mock(WorkItemBudgetGate.class);
    private final RouteDecisionPostProcessor postProcessor = mock(RouteDecisionPostProcessor.class);
    private final ExecutionTargetRegistry registry = spy(new ExecutionTargetRegistry());
    private final WorkbenchRoutingProperties properties = new WorkbenchRoutingProperties();
    private RouteValidationResult persistedValidation;

    @ParameterizedTest
    @ValueSource(strings = {"你好", "你能做什么", "采购 100 台笔记本，预算 80 万元", "再解释一下为什么", "选择 GENERAL_AGENT 回答"})
    void newWorkPersistsProcurementAndReachesItsActualRuntimeProfile(String goal) {
        AgentWorkItem work = setup(goal);
        when(router.route(any())).thenReturn(new RouterModelResult(
                new ExecutionDecision("GENERAL_AGENT", 1, "general", Map.of(), List.of(), "general"),
                "model", "prompt", "raw", "", 1, 1, 1));
        RoutingDecisionRecord decision = coordinator().route(principal, work.workItemId(), work.routingRequestId()).orElseThrow();
        assertEquals("PROCUREMENT_SOURCING", decision.decision().get("targetId"));
        assertEquals(ExecutionTargetCandidateResolver.WORKBENCH_POLICY_VERSION, decision.modelName());
        assertEquals(RouteDisposition.AUTO_DISPATCH, persistedValidation.disposition());
        assertTrue(persistedValidation.validatedInput().typedPayload().isEmpty());
        verifyNoInteractions(router);
        verify(cases).ensureCase("tenant", "conversation", "user");
        verifyNoMoreInteractions(cases); // No demand extraction/patch at the routing layer.
        verify(budgets).reserveRouter(eq(principal), eq(work), anyString());
        verify(budgets).settleRouter(any(), any());
        verify(postProcessor).afterEffectiveDecision(principal, work, decision);

        AgentRuntime runtime = mock(AgentRuntime.class);
        when(runtime.run(any(), any(AgentExecutionProfile.class), any())).thenReturn(new AgentRuntimeResult(
                "run", "conversation", AgentRunState.COMPLETED, AgentStopReason.COMPLETED, "answer", "", null, List.of()));
        var factory = new ProcurementSourcingExecutionProfileFactory();
        var executor = new RuntimeAgentExecutor(runtime, new AgentScenarioProfileResolver(List.of(factory)));
        var procurement = new ProcurementSourcingExecutionAdapter(executor, mock(AgentRunStore.class), mock(ProcurementCaseStore.class));
        var general = mock(GeneralAgentExecutionAdapter.class);
        when(general.targetId()).thenReturn(ExecutionTargetId.GENERAL_AGENT);
        var adapters = new ExecutionAdapterRegistry(List.of(procurement, general));
        adapters.require((String) decision.decision().get("targetId")).dispatch(new DispatchRequest(
                "dispatch", work.workItemId(), work.conversationId(), goal,
                persistedValidation.validatedInput().targetId(), principal, persistedValidation.validatedInput(), Instant.now()));
        var request = ArgumentCaptor.forClass(AgentRequest.class);
        verify(runtime).run(request.capture(), eq(factory.createProfile()), any());
        assertEquals("procurement-sourcing-rfq-v1", request.getValue().scenarioId());
        assertEquals("user", request.getValue().userId());
        assertEquals("conversation", request.getValue().conversationId());
        assertEquals("tenant", request.getValue().metadata().get("tenantId"));
        assertEquals(Set.of("USER"), request.getValue().metadata().get("authenticatedRoles"));
        verify(general, never()).dispatch(any());
        verifyNoMoreInteractions(runtime);

        // A repeated claim reuses the effective result without creating another Case or dispatch.
        when(routing.claimRouting(eq(principal), eq(work.workItemId()), eq(work.routingRequestId()),
                any(), anyInt(), anyLong(), anyString(), anyString(), any())).thenReturn(Optional.empty());
        when(routing.findEffectiveRouting(principal, work.workItemId())).thenReturn(Optional.of(decision));
        assertEquals(decision, coordinator().route(principal, work.workItemId(), work.routingRequestId()).orElseThrow());
        verify(cases, times(1)).ensureCase("tenant", "conversation", "user");
        verify(postProcessor, times(1)).afterEffectiveDecision(principal, work, decision);
    }

    @Test
    void previousCompletedProcurementContextDoesNotSendFollowUpToGeneral() {
        AgentWorkItem work = setup("再解释一下为什么");
        var previous = mock(AgentWorkItem.class);
        when(previous.workItemId()).thenReturn("previous");
        when(previous.activeExecutionTarget()).thenReturn("PROCUREMENT_SOURCING");
        when(previous.controlState()).thenReturn(WorkControlState.CLOSED);
        when(previous.executionState()).thenReturn(WorkExecutionState.COMPLETED);
        when(workbench.findWorkItem(principal, "previous")).thenReturn(Optional.of(previous));
        var focus = new ConversationWorkState("conversation", "tenant", "user", "previous", 3, Instant.now());
        when(workbench.findConversationState(principal, "conversation")).thenReturn(Optional.of(focus));
        var input = mock(AgentConversationTurn.class);
        when(input.inputId()).thenReturn("input");
        when(input.conversationId()).thenReturn("conversation");
        when(input.content()).thenReturn(work.originalGoal());
        when(routing.persistUnclassifiedInput(principal, "input", "client", "conversation", work.originalGoal()))
                .thenReturn(input);
        var started = mock(WorkCommandDecision.class);
        when(started.decisionStatus()).thenReturn(DecisionStatus.STARTED);
        when(started.commandDecisionId()).thenReturn("command");
        when(routing.beginCommandAttempt(eq(principal), eq("input"), eq(ClassifierType.MODEL), anyString())).thenReturn(started);
        WorkCommandClassifier classifier = mock(WorkCommandClassifier.class);
        when(classifier.classify(any())).thenReturn(new CommandClassifierResult(
                new WorkCommandClassification(WorkCommandType.ADD_INPUT_TO_ACTIVE_WORK, 1, "follow-up", "previous", ""),
                ClassifierType.MODEL, "fixture", "", "", "", 0, 0, 0, "trace"));
        when(routing.completeCommandAttempt(eq(principal), eq("command"), any())).thenAnswer(call -> {
            CommandClassifierResult normalized = call.getArgument(2);
            assertEquals(WorkCommandType.NORMAL_GOAL, normalized.classification().commandType());
            var effective = mock(WorkCommandDecision.class);
            when(effective.decision()).thenReturn(Map.of("commandType", normalized.classification().commandType().name()));
            return effective;
        });
        when(workbench.createWorkItemFromPersistedInput(eq(principal), any())).thenAnswer(call -> {
            CreatePersistedInputWorkItemCommand command = call.getArgument(1);
            assertEquals(work.originalGoal(), command.goalText());
            assertEquals(3, command.expectedFocusVersion());
            return new WorkItemCreationResult(input, work, null, focus, null, false);
        });
        var accepted = new UnifiedWorkIntakeService(routing, workbench, classifier).accept(principal,
                new UnifiedWorkInputRequest("input", "client", "conversation", work.originalGoal(), ClassifierType.MODEL, null, ""));
        assertFalse(accepted.commandOnly());
        assertEquals("PROCUREMENT_SOURCING", coordinator().route(principal, accepted.workItem().workItemId(), work.routingRequestId())
                .orElseThrow().decision().get("targetId"));
        verifyNoInteractions(router);
    }

    @Test
    void disabledProcurementNeverFallsBackToGeneralOrCreatesCase() {
        AgentWorkItem work = setup("你好");
        doReturn(List.of(registry.enabledTargets(principal).stream()
                .filter(t -> t.targetId() == ExecutionTargetId.GENERAL_AGENT).findFirst().orElseThrow()))
                .when(registry).enabledTargets(principal);
        assertTrue(coordinator().route(principal, work.workItemId(), work.routingRequestId()).isEmpty());
        verify(routing, never()).completeRouting(any(), any(), any(), any());
        verifyNoInteractions(router, cases, postProcessor);
    }

    @Test
    void policyValidationStillRejectsUnavailableTargetBeforeCaseCreation() {
        AgentWorkItem work = setup("你好");
        doReturn(Optional.empty()).when(registry).findEnabled(principal, "PROCUREMENT_SOURCING");
        coordinator().route(principal, work.workItemId(), work.routingRequestId()).orElseThrow();
        assertEquals(RouteDisposition.REJECT, persistedValidation.disposition());
        assertEquals("TARGET_DISABLED", persistedValidation.failureCode());
        verifyNoInteractions(router, cases);
    }

    private RoutingCoordinator coordinator() {
        return new RoutingCoordinator(routing, workbench, router,
                new RoutePolicyValidator(registry, properties, new ObjectMapper(), cases),
                context, registry, properties, (attempt, result) -> {}, postProcessor, budgets,
                new ExecutionTargetCandidateResolver());
    }

    private AgentWorkItem setup(String goal) {
        properties.setEnabled(true);
        Instant now = Instant.now();
        AgentWorkItem work = new AgentWorkItem("work", "conversation", "tenant", "user", goal, goal,
                WorkControlState.ROUTING, WorkExecutionState.NOT_STARTED, WorkOutcome.UNDETERMINED,
                "", "", "", "", "", "input", "", "route", 0, null, null, "", "", 0, 0, now, now, null);
        when(workbench.findWorkItem(principal, work.workItemId())).thenReturn(Optional.of(work));
        when(context.resolve(principal, work)).thenReturn(new ResolvedRouteContext("", Map.of(), Map.of()));
        RoutingAttempt attempt = new RoutingAttempt("decision", work.workItemId(), work.routingRequestId(), 1, "trace");
        when(routing.claimRouting(eq(principal), eq(work.workItemId()), eq(work.routingRequestId()),
                any(), anyInt(), anyLong(), anyString(), anyString(), any())).thenReturn(Optional.of(attempt));
        when(routing.completeRouting(eq(principal), eq(attempt), any(), any())).thenAnswer(call -> {
            RouterModelResult result = call.getArgument(2);
            persistedValidation = call.getArgument(3);
            return new RoutingDecisionRecord("decision", work.workItemId(), work.routingRequestId(), 1,
                    DecisionStatus.EFFECTIVE, result.modelName(), properties.getCatalogVersion(),
                    result.promptDigest(), result.rawOutputDigest(), Map.of("targetId", result.decision().targetId()),
                    Map.of("disposition", persistedValidation.disposition().name()),
                    result.promptTokens(), result.completionTokens(), result.latencyMs(),
                    persistedValidation.failureCode(), "", "trace", now, now);
        });
        return work;
    }
}
