package com.agent.platform.workbench.application;

import com.agent.platform.ordercare.incident.scope.application.IncidentScopeDiscoveryCoordinator;
import com.agent.platform.ordercare.config.OrderCareProperties;
import com.agent.platform.ordercare.incident.scope.model.IncidentScopeAnomalyType;
import com.agent.platform.ordercare.incident.scope.model.IncidentScopeCandidate;
import com.agent.platform.ordercare.incident.scope.model.IncidentScopeCriteria;
import com.agent.platform.ordercare.incident.scope.model.IncidentScopeRelationQuality;
import com.agent.platform.ordercare.incident.scope.model.IncidentScopeSnapshot;
import com.agent.platform.ordercare.incident.scope.model.IncidentScopeSnapshotStatus;
import com.agent.platform.ordercare.incident.scope.persistence.IncidentScopeDiscoveryStore;
import com.agent.platform.workbench.model.AgentWorkItem;
import com.agent.platform.workbench.model.ExecutionDecision;
import com.agent.platform.workbench.model.IdentifierSource;
import com.agent.platform.workbench.model.RouteDisposition;
import com.agent.platform.workbench.model.WorkControlState;
import com.agent.platform.workbench.model.WorkExecutionState;
import com.agent.platform.workbench.model.WorkOutcome;
import com.agent.platform.workbench.persistence.WorkbenchStore;
import com.agent.platform.workbench.security.AuthenticatedPrincipal;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class IncidentScopeRoutePreflightTests {

    private final IncidentScopeDiscoveryCoordinator coordinator = mock(IncidentScopeDiscoveryCoordinator.class);
    private final IncidentScopeDiscoveryStore scopeStore = mock(IncidentScopeDiscoveryStore.class);
    private final WorkbenchStore workbenchStore = mock(WorkbenchStore.class);
    private final DefaultIncidentScopeRoutePreflight preflight = new DefaultIncidentScopeRoutePreflight(
            coordinator, scopeStore, workbenchStore, new ObjectMapper(), new OrderCareProperties());

    @Test
    void explicitRequestIdsContinueThroughOriginalValidationPath() {
        ExecutionDecision decision = decision(Map.of("requestIds", List.of("REQ-1")));

        Optional<?> result = preflight.resolve(principal(), work("调查 REQ-1"), decision, context());

        assertThat(result).isEmpty();
        verify(coordinator, never()).discover(any(), any());
    }

    @Test
    void fuzzyBusinessConditionsDiscoverAndFreezeServerResolvedScope() {
        IncidentScopeSnapshot ready = snapshot(IncidentScopeSnapshotStatus.CANDIDATES_READY, 2);
        IncidentScopeSnapshot waiting = snapshot(IncidentScopeSnapshotStatus.WAITING_CONFIRMATION, 3);
        when(coordinator.discover(any(), any())).thenReturn(ready);
        when(scopeStore.markWaitingConfirmation(principal(), ready.snapshotId(), ready.version()))
                .thenReturn(waiting);

        var result = preflight.resolve(principal(),
                work("调查昨晚订单超时但库存未释放的问题"), decision(Map.of()), context()).orElseThrow();

        assertThat(result.disposition()).isEqualTo(RouteDisposition.REQUIRE_CONFIRMATION);
        assertThat(result.validatedInput().typedPayload()).containsEntry("scopeSnapshotId", "scope-1");
        assertThat(result.validatedInput().typedPayload()).containsEntry("requestIds", List.of("REQ-1"));
        assertThat(result.validatedInput().identifiers().values())
                .allMatch(value -> value.source() == IdentifierSource.SERVER_RESOLVED_FROM_SCOPE_DISCOVERY);
        verify(workbenchStore, org.mockito.Mockito.atLeast(7)).appendLocalEvent(any(), any(), any());
    }

    @Test
    void missingBusinessAnchorRequiresClarificationWithoutDiscovery() {
        var result = preflight.resolve(principal(), work("调查订单问题"), decision(Map.of()), context())
                .orElseThrow();

        assertThat(result.disposition()).isEqualTo(RouteDisposition.REQUIRE_CLARIFICATION);
        assertThat(result.reasons().get(0)).contains("时间");
        verify(coordinator, never()).discover(any(), any());
    }

    private ExecutionDecision decision(Map<String, Object> inputs) {
        return new ExecutionDecision("INCIDENT_INVESTIGATION", .9, "incident", inputs,
                List.of("requestIds", "queueNames"), "只读调查");
    }

    private IncidentScopeSnapshot snapshot(IncidentScopeSnapshotStatus status, long version) {
        Instant now = Instant.parse("2026-07-21T04:00:00Z");
        IncidentScopeCandidate candidate = new IncidentScopeCandidate(
                "REQ-1", "ORDER-1", "DEDUCT-1", List.of("DL-1"), List.of("orders.dlq"),
                2, 1, 1, "UNRELEASED",
                List.of(IncidentScopeAnomalyType.ORDER_TIMEOUT_INVENTORY_UNRELEASED),
                List.of("ORDER_TIMEOUT_INVENTORY_UNRELEASED"), IncidentScopeRelationQuality.STRONG,
                "COMPLETE", List.of(), List.of());
        IncidentScopeCriteria criteria = new IncidentScopeCriteria("昨晚", now.minusSeconds(3600), now,
                "Asia/Shanghai", false,
                List.of(IncidentScopeAnomalyType.ORDER_TIMEOUT_INVENTORY_UNRELEASED),
                List.of(), List.of(), List.of());
        return new IncidentScopeSnapshot("scope-1", "tenant-1", "alice", "conversation-1",
                "work-1", "input-1", "scope:route-1", criteria, "criteria-digest",
                List.of(candidate), Map.of("order", "AVAILABLE", "resource", "AVAILABLE"),
                "fingerprint-1", 1, false, status, version, "", null, 1,
                now.plusSeconds(600), null, "", "", now, now);
    }

    private AgentWorkItem work(String goal) {
        Instant now = Instant.now();
        return new AgentWorkItem("work-1", "conversation-1", "tenant-1", "alice", goal, goal,
                WorkControlState.ROUTING, WorkExecutionState.NOT_STARTED, WorkOutcome.UNDETERMINED,
                "", "", "", "", "", "input-1", "", "route-1", 0,
                null, null, "", "", 0, 0, now, now, null);
    }

    private AuthenticatedPrincipal principal() {
        return new AuthenticatedPrincipal("tenant-1", "alice", Set.of("INCIDENT_OPERATOR"));
    }

    private ResolvedRouteContext context() {
        return new ResolvedRouteContext("", Map.of(), Map.of());
    }
}
