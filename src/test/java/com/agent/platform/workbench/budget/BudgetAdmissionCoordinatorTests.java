package com.agent.platform.workbench.budget;

import com.agent.platform.config.WorkbenchDispatchProperties;
import com.agent.platform.config.WorkbenchRoutingProperties;
import com.agent.platform.workbench.application.NoopRoutingFailureInjector;
import com.agent.platform.workbench.application.RouteContextResolver;
import com.agent.platform.workbench.application.RouteDecisionPostProcessor;
import com.agent.platform.workbench.application.RoutePolicyValidator;
import com.agent.platform.workbench.application.RoutingCoordinator;
import com.agent.platform.workbench.application.UnifiedTaskRouter;
import com.agent.platform.workbench.dispatch.DispatchClaim;
import com.agent.platform.workbench.dispatch.DispatchCoordinator;
import com.agent.platform.workbench.dispatch.DispatchFailureInjector;
import com.agent.platform.workbench.dispatch.DispatchRequest;
import com.agent.platform.workbench.dispatch.ExecutionAdapter;
import com.agent.platform.workbench.dispatch.ExecutionAdapterRegistry;
import com.agent.platform.workbench.model.AgentWorkItem;
import com.agent.platform.workbench.model.DispatchAttempt;
import com.agent.platform.workbench.model.DispatchAttemptStatus;
import com.agent.platform.workbench.model.RoutingAttempt;
import com.agent.platform.workbench.model.ValidatedExecutionInput;
import com.agent.platform.workbench.persistence.DispatchStore;
import com.agent.platform.workbench.persistence.RoutingStore;
import com.agent.platform.workbench.persistence.WorkbenchStore;
import com.agent.platform.workbench.security.AuthenticatedPrincipal;
import com.agent.platform.workbench.target.ExecutionTargetRegistry;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class BudgetAdmissionCoordinatorTests {

    @Test
    void routerBudgetDenialStopsBeforeModelInvocation() {
        RoutingStore routing = mock(RoutingStore.class);
        WorkbenchStore workbench = mock(WorkbenchStore.class);
        UnifiedTaskRouter router = mock(UnifiedTaskRouter.class);
        WorkItemBudgetGate budgets = mock(WorkItemBudgetGate.class);
        AuthenticatedPrincipal principal = principal();
        AgentWorkItem work = mock(AgentWorkItem.class);
        when(work.workItemId()).thenReturn("work-1");
        when(work.routingRequestId()).thenReturn("routing-1");
        when(workbench.findWorkItem(principal, "work-1")).thenReturn(Optional.of(work));
        RoutingAttempt attempt = new RoutingAttempt("decision-1", "work-1", "routing-1", 1, "trace-1");
        when(routing.claimRouting(any(), anyString(), anyString(), any(), anyInt(), anyLong(), anyString(),
                anyString(), any()))
                .thenReturn(Optional.of(attempt));
        when(budgets.reserveRouter(principal, work, "router:decision-1"))
                .thenThrow(new BudgetExceededException("BUDGET_EXHAUSTED", "no remaining budget"));
        WorkbenchRoutingProperties properties = new WorkbenchRoutingProperties();
        properties.setEnabled(true);
        RoutingCoordinator coordinator = new RoutingCoordinator(
                routing, workbench, router, mock(RoutePolicyValidator.class),
                mock(RouteContextResolver.class), mock(ExecutionTargetRegistry.class), properties,
                new NoopRoutingFailureInjector(), mock(RouteDecisionPostProcessor.class), budgets);

        assertEquals(Optional.empty(), coordinator.route(principal, "work-1", "routing-1"));
        verify(router, never()).route(any());
        verify(routing).failRouting(any(), any(), org.mockito.ArgumentMatchers.eq("BUDGET_EXHAUSTED"),
                anyString(), any(), anyLong(), org.mockito.ArgumentMatchers.eq(1));
    }

    @Test
    void dispatchBudgetDenialStopsBeforeTargetAdapter() {
        DispatchStore store = mock(DispatchStore.class);
        ExecutionAdapterRegistry adapters = mock(ExecutionAdapterRegistry.class);
        ExecutionAdapter adapter = mock(ExecutionAdapter.class);
        WorkItemBudgetGate budgets = mock(WorkItemBudgetGate.class);
        AuthenticatedPrincipal principal = principal();
        DispatchAttempt attempt = new DispatchAttempt("attempt-1", "work-1", "dispatch-1", 1,
                false, "INCIDENT_INVESTIGATION", DispatchAttemptStatus.STARTED, "", "", Instant.now(), null);
        DispatchRequest request = new DispatchRequest("dispatch-1", "work-1", "conversation-1", "goal",
                "INCIDENT_INVESTIGATION", principal,
                new ValidatedExecutionInput("INCIDENT_INVESTIGATION", Map.of(), Map.of(), "digest"), Instant.now());
        when(store.claimDispatch(any(), anyString(), any(), anyInt(), anyString(), any()))
                .thenReturn(Optional.of(new DispatchClaim(attempt, request)));
        when(adapters.require("INCIDENT_INVESTIGATION")).thenReturn(adapter);
        when(budgets.reserveTarget(principal, "work-1",
                com.agent.platform.workbench.target.ExecutionTargetId.INCIDENT_INVESTIGATION,
                "dispatch:dispatch-1")).thenThrow(
                new BudgetExceededException("BUDGET_EXHAUSTED", "no remaining budget"));
        WorkbenchDispatchProperties properties = new WorkbenchDispatchProperties();
        properties.setEnabled(true);
        DispatchCoordinator coordinator = new DispatchCoordinator(
                store, adapters, properties, mock(DispatchFailureInjector.class), budgets);

        assertEquals(Optional.empty(), coordinator.dispatch(principal, "work-1"));
        verify(adapter, never()).dispatch(any());
        verify(store).failDispatch(any(), any(), org.mockito.ArgumentMatchers.eq("BUDGET_EXHAUSTED"),
                anyString(), anyLong(), org.mockito.ArgumentMatchers.eq(1));
    }

    private AuthenticatedPrincipal principal() {
        return new AuthenticatedPrincipal("tenant", "alice", Set.of("USER", "INCIDENT_OPERATOR"));
    }
}
