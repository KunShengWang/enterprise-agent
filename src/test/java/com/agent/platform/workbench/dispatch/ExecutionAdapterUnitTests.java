package com.agent.platform.workbench.dispatch;

import com.agent.platform.agent.AgentExecutor;
import com.agent.platform.agent.AgentRequest;
import com.agent.platform.agent.AgentResponse;
import com.agent.platform.agent.AgentRunStatus;
import com.agent.platform.ordercare.incident.application.IncidentInvestigationLauncher;
import com.agent.platform.ordercare.incident.model.IncidentInvestigationRequest;
import com.agent.platform.ordercare.incident.model.IncidentStartResponse;
import com.agent.platform.ordercare.incident.model.IncidentStatus;
import com.agent.platform.ordercare.incident.recovery.application.IncidentRecoveryPlanLauncher;
import com.agent.platform.ordercare.incident.recovery.model.RecoveryPlanStartRequest;
import com.agent.platform.ordercare.incident.recovery.model.RecoveryPlanStartResponse;
import com.agent.platform.ordercare.incident.recovery.model.RecoveryPlanStatus;
import com.agent.platform.ordercare.incident.recovery.persistence.IncidentRecoveryPlanStore;
import com.agent.platform.ordercare.config.AgentScenarioProfileResolver;
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
    void generalAndOrderCareUseTheirFrozenProfilesAndProtectedDispatchMetadata() {
        AgentExecutor executor = mock(AgentExecutor.class);
        AgentRunStore runStore = mock(AgentRunStore.class);
        when(runStore.findByDispatchRequestId("dispatch-general")).thenReturn(Optional.empty());
        when(runStore.findByDispatchRequestId("dispatch-ordercare")).thenReturn(Optional.empty());
        when(executor.execute(org.mockito.ArgumentMatchers.any())).thenReturn(
                new AgentResponse("run-1", "conversation-1", AgentRunStatus.COMPLETED,
                        "done", "", List.of(), null));

        new GeneralAgentExecutionAdapter(executor, runStore).dispatch(request(
                "dispatch-general", ExecutionTargetId.GENERAL_AGENT, Map.of()));
        new OrderCareExecutionAdapter(executor, runStore).dispatch(request(
                "dispatch-ordercare", ExecutionTargetId.ORDERCARE_CASE,
                Map.of("requestId", "ORDERCARE-M05-REQUEST")));

        ArgumentCaptor<AgentRequest> requests = ArgumentCaptor.forClass(AgentRequest.class);
        verify(executor, org.mockito.Mockito.times(2)).execute(requests.capture());
        AgentRequest general = requests.getAllValues().get(0);
        AgentRequest orderCare = requests.getAllValues().get(1);
        assertEquals(AgentScenarioProfileResolver.GENERAL_AGENT_V1, general.scenarioId());
        assertEquals("ordercare-floworder-v1", orderCare.scenarioId());
        assertEquals("dispatch-general",
                general.metadata().get(AgentRunStore.DISPATCH_REQUEST_METADATA_KEY));
        assertEquals("dispatch-ordercare",
                orderCare.metadata().get(AgentRunStore.DISPATCH_REQUEST_METADATA_KEY));
        assertEquals(ExecutionTargetId.GENERAL_AGENT.name(), general.metadata().get("executionTarget"));
        assertEquals(ExecutionTargetId.ORDERCARE_CASE.name(), orderCare.metadata().get("executionTarget"));
    }

    @Test
    void registryRejectsAnyCatalogOtherThanTheRegisteredAdapters() {
        List<ExecutionAdapter> all = new ArrayList<>();
        for (ExecutionTargetId id : ExecutionTargetId.values()) all.add(fake(id));
        assertEquals(5, new ExecutionAdapterRegistry(all).size());
        assertThrows(IllegalStateException.class,
                () -> new ExecutionAdapterRegistry(all.subList(0, 3)));
        assertThrows(IllegalStateException.class,
                () -> new ExecutionAdapterRegistry(List.of(fake(ExecutionTargetId.GENERAL_AGENT),
                        fake(ExecutionTargetId.GENERAL_AGENT),
                        fake(ExecutionTargetId.ORDERCARE_CASE),
                        fake(ExecutionTargetId.INCIDENT_INVESTIGATION),
                        fake(ExecutionTargetId.INCIDENT_RECOVERY_PLAN))));
    }

    @Test
    void incidentAdapterPreservesStableRequestedAtAndValidatedScope() {
        IncidentInvestigationLauncher launcher = mock(IncidentInvestigationLauncher.class);
        Instant requestedAt = Instant.parse("2026-07-19T00:00:00Z");
        when(launcher.findByDispatchRequestId("dispatch-incident")).thenReturn(Optional.empty());
        when(launcher.startForDispatch(org.mockito.ArgumentMatchers.eq("dispatch-incident"),
                org.mockito.ArgumentMatchers.any())).thenReturn(
                new IncidentStartResponse("incident-1", IncidentStatus.CREATED, requestedAt));
        DispatchRequest request = new DispatchRequest(
                "dispatch-incident", "work-1", "conversation-1", "investigate",
                ExecutionTargetId.INCIDENT_INVESTIGATION.name(),
                new AuthenticatedPrincipal("tenant-1", "alice", Set.of("INCIDENT_OPERATOR")),
                new ValidatedExecutionInput(ExecutionTargetId.INCIDENT_INVESTIGATION.name(), Map.of(),
                        Map.of("batchId", "BATCH-1", "requestIds", List.of("REQ-1"),
                                "queueNames", List.of("orders.dlq")), "digest-1"),
                requestedAt);

        DispatchResult result = new IncidentInvestigationExecutionAdapter(launcher).dispatch(request);

        ArgumentCaptor<IncidentInvestigationRequest> captured =
                ArgumentCaptor.forClass(IncidentInvestigationRequest.class);
        verify(launcher).startForDispatch(org.mockito.ArgumentMatchers.eq("dispatch-incident"), captured.capture());
        assertEquals("incident-1", result.linkedId());
        assertEquals(requestedAt, captured.getValue().detectedAt());
        assertEquals(List.of("REQ-1"), captured.getValue().candidateRequestIds());
        assertEquals(List.of("orders.dlq"), captured.getValue().queueNames());
    }

    @Test
    void incidentAdapterRejectsUnresolvedBatchBeforeCreatingIncident() {
        IncidentInvestigationLauncher launcher = mock(IncidentInvestigationLauncher.class);
        DispatchRequest request = new DispatchRequest(
                "dispatch-batch", "work-1", "conversation-1", "investigate batch",
                ExecutionTargetId.INCIDENT_INVESTIGATION.name(),
                new AuthenticatedPrincipal("tenant-1", "alice", Set.of("INCIDENT_OPERATOR")),
                new ValidatedExecutionInput(ExecutionTargetId.INCIDENT_INVESTIGATION.name(), Map.of(),
                        Map.of("batchId", "BATCH-1", "queueNames", List.of("orders.dlq")), "digest-1"),
                Instant.parse("2026-07-19T00:00:00Z"));

        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
                () -> new IncidentInvestigationExecutionAdapter(launcher).dispatch(request));

        assertEquals("validated incident investigation requires explicit requestIds; batchId resolution is unavailable",
                failure.getMessage());
    }

    @Test
    void recoveryPlanAdapterUsesDispatchRequestAsExistingPlannerIdempotencyKey() {
        IncidentRecoveryPlanLauncher launcher = mock(IncidentRecoveryPlanLauncher.class);
        IncidentRecoveryPlanStore store = mock(IncidentRecoveryPlanStore.class);
        when(store.findByRequestKey("incident-1", "dispatch-plan")).thenReturn(Optional.empty());
        when(launcher.start(org.mockito.ArgumentMatchers.eq("incident-1"),
                org.mockito.ArgumentMatchers.any())).thenReturn(
                new RecoveryPlanStartResponse("plan-1", "incident-1",
                        RecoveryPlanStatus.PLANNING, Instant.parse("2026-07-19T00:00:00Z"), true));

        DispatchResult result = new IncidentRecoveryPlanExecutionAdapter(launcher, store).dispatch(
                request("dispatch-plan", ExecutionTargetId.INCIDENT_RECOVERY_PLAN,
                        Map.of("incidentId", "incident-1")));

        ArgumentCaptor<RecoveryPlanStartRequest> captured =
                ArgumentCaptor.forClass(RecoveryPlanStartRequest.class);
        verify(launcher).start(org.mockito.ArgumentMatchers.eq("incident-1"), captured.capture());
        assertEquals("dispatch-plan", captured.getValue().requestKey());
        assertEquals("plan-1", result.linkedId());
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
