package com.agent.platform.workbench.dispatch;

import com.agent.platform.agent.AgentExecutor;
import com.agent.platform.agent.AgentRequest;
import com.agent.platform.agent.AgentResponse;
import com.agent.platform.agent.AgentRunStatus;
import com.agent.platform.config.AgentScenarioProfileResolver;
import com.agent.platform.runtime.AgentRunStore;
import com.agent.platform.workbench.model.ValidatedExecutionInput;
import com.agent.platform.workbench.security.AuthenticatedPrincipal;
import com.agent.platform.workbench.target.ExecutionTargetId;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ExecutionAdapterUnitTests {

    @Test
    void procurementUsesFrozenProfile() {
        AgentExecutor executor = mock(AgentExecutor.class);
        AgentRunStore runStore = mock(AgentRunStore.class);
        when(runStore.findByDispatchRequestId("dispatch-general")).thenReturn(Optional.empty());
        when(executor.execute(org.mockito.ArgumentMatchers.any())).thenReturn(
                new AgentResponse("run-1", "conversation-1", AgentRunStatus.COMPLETED,
                        "done", "", List.of(), null));

        new ProcurementSourcingExecutionAdapter(executor, runStore, mock(com.agent.platform.procurement.persistence.ProcurementCaseStore.class)).dispatch(request(
                "dispatch-general", ExecutionTargetId.PROCUREMENT_SOURCING, Map.of()));
        ArgumentCaptor<AgentRequest> requests = ArgumentCaptor.forClass(AgentRequest.class);
        verify(executor, org.mockito.Mockito.times(1)).execute(requests.capture());
        AgentRequest general = requests.getAllValues().get(0);
        assertEquals("procurement-sourcing-rfq-v1", general.scenarioId());
        assertEquals("dispatch-general",
                general.metadata().get(AgentRunStore.DISPATCH_REQUEST_METADATA_KEY));
        assertEquals(ExecutionTargetId.PROCUREMENT_SOURCING.name(), general.metadata().get("executionTarget"));
    }

    @Test
    void registryRejectsAnyCatalogOtherThanTheRegisteredAdapters() {
        List<ExecutionAdapter> all = new ArrayList<>();
        for (ExecutionTargetId id : ExecutionTargetId.values()) all.add(fake(id));
        assertEquals(1, new ExecutionAdapterRegistry(all).size());
        assertEquals(1, new ExecutionAdapterRegistry(List.of(fake(ExecutionTargetId.GENERAL_AGENT),
                fake(ExecutionTargetId.PROCUREMENT_SOURCING))).size());
        assertThrows(com.agent.platform.common.RetiredBusinessException.class,
                () -> new ExecutionAdapterRegistry(all).require("ORDERCARE_CASE"));
        assertThrows(IllegalStateException.class,
                () -> new ExecutionAdapterRegistry(all.subList(0, 3)));
        assertThrows(IllegalStateException.class,
                () -> new ExecutionAdapterRegistry(List.of(fake(ExecutionTargetId.GENERAL_AGENT),
                        fake(ExecutionTargetId.GENERAL_AGENT),
                        fake(ExecutionTargetId.ORDERCARE_CASE),
                        fake(ExecutionTargetId.INCIDENT_INVESTIGATION),
                        fake(ExecutionTargetId.INCIDENT_RECOVERY_PLAN))));
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings = {"GENERAL_AGENT", "ORDERCARE_CASE", "INCIDENT_INVESTIGATION", "INCIDENT_RECOVERY_PLAN"})
    void historicalTargetsCannotReachAdaptersEvenWithCompleteOrIncompleteInput(String historicalId) {
        ExecutionTargetId target = ExecutionTargetId.valueOf(historicalId);
        var registry = new ExecutionAdapterRegistry(List.of(fake(ExecutionTargetId.GENERAL_AGENT),
                fake(ExecutionTargetId.PROCUREMENT_SOURCING)));
        assertThrows(com.agent.platform.common.RetiredBusinessException.class, () -> registry.require(target.name()));
        for (Map<String, Object> input : List.of(Map.<String, Object>of(),
                Map.<String, Object>of("batchId", "BATCH-1", "queueNames", List.of("orders.dlq")),
                Map.<String, Object>of("requestIds", List.of("REQ-1"), "incidentId", "incident-1",
                        "batchId", "BATCH-1", "queueNames", List.of("orders.dlq")))) {
            var store = mock(com.agent.platform.workbench.persistence.DispatchStore.class);
            var adapters = mock(ExecutionAdapterRegistry.class);
            var budgets = mock(com.agent.platform.workbench.budget.WorkItemBudgetGate.class);
            var properties = new com.agent.platform.config.WorkbenchDispatchProperties();
            properties.setEnabled(true);
            var request = request("old-dispatch", target, input);
            when(store.claimDispatch(org.mockito.ArgumentMatchers.eq(request.principal()),
                    org.mockito.ArgumentMatchers.eq("work-1"), org.mockito.ArgumentMatchers.any(),
                    org.mockito.ArgumentMatchers.anyInt(), org.mockito.ArgumentMatchers.anyString(),
                    org.mockito.ArgumentMatchers.any())).thenReturn(Optional.of(new DispatchClaim(null, request)));
            var coordinator = new DispatchCoordinator(store, adapters, properties, (claim, result) -> {}, budgets);
            assertThrows(com.agent.platform.common.RetiredBusinessException.class,
                    () -> coordinator.dispatch(request.principal(), "work-1"));
            org.mockito.Mockito.verifyNoInteractions(adapters, budgets);
            verify(store).claimDispatch(org.mockito.ArgumentMatchers.eq(request.principal()),
                    org.mockito.ArgumentMatchers.eq("work-1"), org.mockito.ArgumentMatchers.any(),
                    org.mockito.ArgumentMatchers.anyInt(), org.mockito.ArgumentMatchers.anyString(),
                    org.mockito.ArgumentMatchers.any());
            org.mockito.Mockito.verifyNoMoreInteractions(store);
        }
    }

    private DispatchRequest request(String dispatchRequestId,
                                    ExecutionTargetId target,
                                    Map<String, Object> payload) {
        return new DispatchRequest(
                dispatchRequestId, "work-1", "conversation-1", "goal", target.name(),
                new AuthenticatedPrincipal("tenant-1", "alice", Set.of("USER")),
                new ValidatedExecutionInput(target.name(), Map.of(), payload, "digest-1"),
                Instant.parse("2026-07-19T00:00:00Z"));
    }

    private ExecutionAdapter fake(ExecutionTargetId id) {
        return new ExecutionAdapter() {
            @Override public ExecutionTargetId targetId() { return id; }
            @Override public DispatchResult dispatch(DispatchRequest request) { throw new UnsupportedOperationException(); }
            @Override public Optional<DispatchResult> reconcile(DispatchRequest request) { return Optional.empty(); }
        };
    }
}
