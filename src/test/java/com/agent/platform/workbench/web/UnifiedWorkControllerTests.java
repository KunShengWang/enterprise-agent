package com.agent.platform.workbench.web;

import com.agent.platform.workbench.application.ConversationFocusService;
import com.agent.platform.workbench.application.RouteConfirmationService;
import com.agent.platform.workbench.application.UnifiedWorkInputRequest;
import com.agent.platform.workbench.application.UnifiedWorkIntakeResult;
import com.agent.platform.workbench.application.UnifiedWorkIntakeService;
import com.agent.platform.workbench.application.UnifiedWorkLauncher;
import com.agent.platform.workbench.application.UnifiedWorkQueryService;
import com.agent.platform.workbench.model.AgentConversationTurn;
import com.agent.platform.workbench.model.AgentWorkItem;
import com.agent.platform.workbench.model.WorkControlState;
import com.agent.platform.workbench.model.WorkCommandDecision;
import com.agent.platform.workbench.model.WorkCommandType;
import com.agent.platform.workbench.model.WorkExecutionState;
import com.agent.platform.workbench.model.WorkOutcome;
import com.agent.platform.workbench.persistence.RoutingStore;
import com.agent.platform.workbench.persistence.WorkbenchStore;
import com.agent.platform.workbench.security.AuthenticatedPrincipal;
import com.agent.platform.workbench.security.WorkbenchPrincipalProvider;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;

import java.time.Instant;
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
    private final UnifiedWorkController controller = new UnifiedWorkController(
            principals, intake, launcher, queries, confirmations, focus, workbench, routing, eventStream);

    @Test
    void requestMetadataCannotOverrideTrustedIdentityOrExecutionProfile() {
        assertThrows(IllegalArgumentException.class, () -> controller.submit(
                "conversation-1", "client-1",
                new UnifiedWorkController.UnifiedInputBody("hello", Map.of("tenantId", "attacker"))));
        assertThrows(IllegalArgumentException.class, () -> controller.submit(
                "conversation-1", "client-2",
                new UnifiedWorkController.UnifiedInputBody("hello", Map.of("executionProfile", "admin"))));
    }

    @Test
    void unsupportedCommandIsAuditedAndCannotChangeUnderlyingExecution() {
        AgentWorkItem work = work();
        when(workbench.findWorkItem(principal, work.workItemId())).thenReturn(Optional.of(work));
        when(routing.persistUnclassifiedInput(eq(principal), anyString(), eq("button-1"),
                eq(work.conversationId()), anyString())).thenReturn(mock(AgentConversationTurn.class));

        var response = controller.unsupportedCommand(work.workItemId(), "pause",
                new UnifiedWorkController.WorkCommandBody(work.version(), "button-1"));

        assertEquals(409, response.getStatusCode().value());
        assertEquals("UNSUPPORTED_FOR_TARGET", response.getBody().code());
        assertEquals(false, response.getBody().data().underlyingExecutionChanged());
        verify(routing).persistUnclassifiedInput(eq(principal), anyString(), eq("button-1"),
                eq(work.conversationId()), anyString());
    }

    @Test
    void naturalLanguageCommandIsRecordedButReturnsStructuredUnsupportedError() {
        AgentConversationTurn input = mock(AgentConversationTurn.class);
        WorkCommandDecision decision = mock(WorkCommandDecision.class);
        when(decision.commandType()).thenReturn(WorkCommandType.RESUME_ACTIVE_WORK);
        when(decision.focusedWorkItemId()).thenReturn("");
        when(intake.accept(eq(principal), any(UnifiedWorkInputRequest.class)))
                .thenReturn(new UnifiedWorkIntakeResult(input, decision, null, true));

        var response = controller.submit("conversation-1", "client-command-1",
                new UnifiedWorkController.UnifiedInputBody("继续刚才的任务", Map.of()));

        assertEquals(409, response.getStatusCode().value());
        assertEquals("UNSUPPORTED_FOR_TARGET", response.getBody().code());
    }

    @Test
    void eventStreamUsesMonotonicMaximumOfQueryAndLastEventIdCursors() {
        when(eventStream.stream(eq(principal), eq("work-1"), any(UnifiedWorkStreamCursor.class)))
                .thenReturn(Flux.empty());

        controller.eventStream("work-1", 10, 9, "w:12;r:7");

        verify(eventStream).stream(principal, "work-1", new UnifiedWorkStreamCursor(12, 9));
    }

    private AgentWorkItem work() {
        Instant now = Instant.now();
        return new AgentWorkItem("work-1", "conversation-1", principal.tenantId(), principal.principalId(),
                "goal", "goal", WorkControlState.DISPATCHED, WorkExecutionState.RUNNING,
                WorkOutcome.UNDETERMINED, "INCIDENT_INVESTIGATION", "", "incident-1", "", "decision-1",
                "input-1", "", "routing-1", 1, now, null, "", "dispatch-1", 2, 3, now, now, null);
    }
}
