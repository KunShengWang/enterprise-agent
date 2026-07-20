package com.agent.platform.workbench.web;

import com.agent.platform.workbench.application.ConversationFocusService;
import com.agent.platform.workbench.application.RouteConfirmationService;
import com.agent.platform.workbench.application.UnifiedWorkInputRequest;
import com.agent.platform.workbench.application.UnifiedWorkExecutionTreeService;
import com.agent.platform.workbench.application.UnifiedWorkIntakeResult;
import com.agent.platform.workbench.application.UnifiedWorkIntakeService;
import com.agent.platform.workbench.application.UnifiedWorkLauncher;
import com.agent.platform.workbench.application.WorkCommandHandler;
import com.agent.platform.workbench.application.WorkCommandResult;
import com.agent.platform.workbench.application.WorkItemBudgetQueryService;
import com.agent.platform.workbench.application.UnifiedWorkQueryService;
import com.agent.platform.workbench.model.AgentConversationTurn;
import com.agent.platform.workbench.model.AgentWorkItem;
import com.agent.platform.workbench.model.WorkControlState;
import com.agent.platform.workbench.model.WorkCommandDecision;
import com.agent.platform.workbench.model.WorkCommandType;
import com.agent.platform.workbench.model.WorkCommandExecutionStatus;
import com.agent.platform.workbench.model.WorkExecutionState;
import com.agent.platform.workbench.model.WorkOutcome;
import com.agent.platform.workbench.budget.BudgetAccount;
import com.agent.platform.workbench.presentation.PublicPresentationService;
import com.agent.platform.workbench.presentation.PublicPresentationStreamService;
import com.agent.platform.workbench.persistence.RoutingStore;
import com.agent.platform.workbench.persistence.WorkbenchStore;
import com.agent.platform.workbench.security.AuthenticatedPrincipal;
import com.agent.platform.workbench.security.WorkbenchPrincipalProvider;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class UnifiedWorkControllerTests {
    private final AuthenticatedPrincipal principal = new AuthenticatedPrincipal(
            "tenant-test", "alice", Set.of("USER"));
    private final WorkbenchPrincipalProvider principals = () -> principal;
    private final UnifiedWorkIntakeService intake = mock(UnifiedWorkIntakeService.class);
    private final UnifiedWorkLauncher launcher = mock(UnifiedWorkLauncher.class);
    private final UnifiedWorkQueryService queries = mock(UnifiedWorkQueryService.class);
    private final RouteConfirmationService confirmations = mock(RouteConfirmationService.class);
    private final ConversationFocusService focus = mock(ConversationFocusService.class);
    private final WorkbenchStore workbench = mock(WorkbenchStore.class);
    private final RoutingStore routing = mock(RoutingStore.class);
    private final UnifiedWorkEventStreamService eventStream = mock(UnifiedWorkEventStreamService.class);
    private final UnifiedWorkExecutionTreeService executionTrees = mock(UnifiedWorkExecutionTreeService.class);
    private final WorkCommandHandler commandHandler = mock(WorkCommandHandler.class);
    private final WorkItemBudgetQueryService budgetQueries = mock(WorkItemBudgetQueryService.class);
    private final PublicPresentationService presentations = mock(PublicPresentationService.class);
    private final PublicPresentationStreamService presentationStream = mock(PublicPresentationStreamService.class);
    private final UnifiedWorkController controller = new UnifiedWorkController(
            principals, intake, launcher, queries, confirmations, focus, workbench, routing,
            eventStream, executionTrees, commandHandler, budgetQueries, presentations, presentationStream);

    @Test
    void requestMetadataCannotOverrideTrustedIdentityOrExecutionProfile() {
        assertThrows(IllegalArgumentException.class, () -> controller.submit(
                "conversation-1", "client-1",
                new UnifiedWorkController.UnifiedInputBody("hello", Map.of("tenantId", "attacker"))).block());
        assertThrows(IllegalArgumentException.class, () -> controller.submit(
                "conversation-1", "client-2",
                new UnifiedWorkController.UnifiedInputBody("hello", Map.of("executionProfile", "admin"))).block());
    }

    @Test
    void unsupportedCommandIsAuditedAndCannotChangeUnderlyingExecution() {
        AgentWorkItem work = work();
        when(workbench.findWorkItem(principal, work.workItemId())).thenReturn(Optional.of(work));
        AgentConversationTurn input = mock(AgentConversationTurn.class);
        WorkCommandDecision decision = mock(WorkCommandDecision.class);
        when(intake.accept(eq(principal), any(UnifiedWorkInputRequest.class)))
                .thenReturn(new UnifiedWorkIntakeResult(input, decision, null, true));
        when(commandHandler.handle(eq(principal), any())).thenReturn(commandResult(work,
                WorkCommandType.PAUSE_ACTIVE_WORK, "UNSUPPORTED_FOR_TARGET", false));

        var response = controller.command(work.workItemId(), "pause",
                new UnifiedWorkController.WorkCommandBody(work.version(), "button-1")).block();

        assertEquals(409, response.getStatusCode().value());
        assertEquals("UNSUPPORTED_FOR_TARGET", response.getBody().code());
        assertEquals(false, response.getBody().data().underlyingExecutionChanged());
        verify(intake).accept(eq(principal), any(UnifiedWorkInputRequest.class));
    }

    @Test
    void naturalLanguageCommandIsRecordedButReturnsStructuredUnsupportedError() {
        AgentConversationTurn input = mock(AgentConversationTurn.class);
        WorkCommandDecision decision = mock(WorkCommandDecision.class);
        when(decision.commandType()).thenReturn(WorkCommandType.RESUME_ACTIVE_WORK);
        when(decision.focusedWorkItemId()).thenReturn("");
        when(intake.accept(eq(principal), any(UnifiedWorkInputRequest.class)))
                .thenReturn(new UnifiedWorkIntakeResult(input, decision, null, true));
        when(commandHandler.handle(eq(principal), any())).thenReturn(commandResult(null,
                WorkCommandType.RESUME_ACTIVE_WORK, "FOCUS_NOT_FOUND", false));

        var response = controller.submit("conversation-1", "client-command-1",
                new UnifiedWorkController.UnifiedInputBody("继续刚才的任务", Map.of())).block();

        assertEquals(409, response.getStatusCode().value());
        assertEquals("FOCUS_NOT_FOUND", response.getBody().code());
    }

    @Test
    void eventStreamUsesMonotonicMaximumOfQueryAndLastEventIdCursors() {
        when(eventStream.stream(eq(principal), eq("work-1"), any(UnifiedWorkStreamCursor.class)))
                .thenReturn(Flux.empty());

        controller.eventStream("work-1", 10, 9, "w:12;r:7");

        verify(eventStream).stream(principal, "work-1", new UnifiedWorkStreamCursor(12, 9));
    }

    @Test
    void executionTreeEndpointUsesAuthenticatedWorkItemProjection() {
        var tree = mock(com.agent.platform.workbench.model.UnifiedWorkExecutionTree.class);
        when(executionTrees.project(principal, "work-1")).thenReturn(tree);

        var response = controller.executionTree("work-1");

        assertEquals(tree, response.data());
        verify(executionTrees).project(principal, "work-1");
    }

    @Test
    void budgetEndpointUsesAuthenticatedWorkItemBudgetQuery() {
        BudgetAccount account = mock(BudgetAccount.class);
        when(budgetQueries.require(principal, "work-1")).thenReturn(account);

        var response = controller.budget("work-1");

        assertEquals(account, response.data());
        verify(budgetQueries).require(principal, "work-1");
    }

    @Test
    void publicPresentationEndpointsUseAuthenticatedPrincipalAndRequestedCursor() {
        when(presentations.publicTimeline(principal, "work-1", 20, 25)).thenReturn(List.of());
        when(presentations.inspectorTimeline(principal, "work-1", 30, 50)).thenReturn(List.of());

        controller.presentations("work-1", 20, 25);
        controller.inspectorPresentations("work-1", 30, 50);

        verify(presentations).publicTimeline(principal, "work-1", 20, 25);
        verify(presentations).inspectorTimeline(principal, "work-1", 30, 50);
    }

    @Test
    void publicPresentationStreamUsesAuthenticatedPrincipalAndResumeCursor() {
        when(presentationStream.resolveCursor(20, "p:30")).thenReturn(30L);
        when(presentationStream.stream(principal, "work-1", 30)).thenReturn(Flux.empty());

        controller.presentationStream("work-1", 20, "p:30");

        verify(presentationStream).resolveCursor(20, "p:30");
        verify(presentationStream).stream(principal, "work-1", 30);
    }

    private AgentWorkItem work() {
        Instant now = Instant.now();
        return new AgentWorkItem("work-1", "conversation-1", principal.tenantId(), principal.principalId(),
                "goal", "goal", WorkControlState.DISPATCHED, WorkExecutionState.RUNNING,
                WorkOutcome.UNDETERMINED, "INCIDENT_INVESTIGATION", "", "incident-1", "", "decision-1",
                "input-1", "", "routing-1", 1, now, null, "", "dispatch-1", 2, 3, now, now, null);
    }

    private WorkCommandResult commandResult(AgentWorkItem work,
                                            WorkCommandType command,
                                            String code,
                                            boolean success) {
        return new WorkCommandResult(success, code, code, "wcmd-1", "input-1", command,
                work == null ? "" : work.activeExecutionTarget(), work == null ? "" : work.workItemId(),
                false, work == null ? "" : work.activeRunId(),
                success ? WorkCommandExecutionStatus.SUCCEEDED : WorkCommandExecutionStatus.REJECTED, work);
    }
}
