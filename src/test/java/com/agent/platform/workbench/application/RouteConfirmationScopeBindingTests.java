package com.agent.platform.workbench.application;

import com.agent.platform.config.WorkbenchDispatchProperties;
import com.agent.platform.ordercare.incident.scope.persistence.IncidentScopeDiscoveryStore;
import com.agent.platform.workbench.model.AgentWorkItem;
import com.agent.platform.workbench.model.RoutePreview;
import com.agent.platform.workbench.model.RoutePreviewStatus;
import com.agent.platform.workbench.persistence.DispatchStore;
import com.agent.platform.workbench.persistence.RoutingStore;
import com.agent.platform.workbench.persistence.WorkbenchStore;
import com.agent.platform.workbench.security.AuthenticatedPrincipal;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class RouteConfirmationScopeBindingTests {

    @Test
    void confirmsSnapshotVersionAndFingerprintBeforeRouteDispatch() {
        DispatchStore dispatch = mock(DispatchStore.class);
        WorkbenchStore workbench = mock(WorkbenchStore.class);
        IncidentScopeDiscoveryStore scopes = mock(IncidentScopeDiscoveryStore.class);
        AuthenticatedPrincipal principal = new AuthenticatedPrincipal(
                "tenant-1", "alice", Set.of("INCIDENT_OPERATOR"));
        RoutePreview preview = new RoutePreview("preview-1", "work-1", "decision-1",
                "INCIDENT_INVESTIGATION", 1, "input-digest", "scope-digest",
                Map.of("validatedInput", Map.of(
                        "scopeSnapshotId", "scope-1",
                        "scopeSnapshotVersion", 3,
                        "candidateFingerprint", "fingerprint-1")),
                RoutePreviewStatus.ACTIVE, Instant.now().plusSeconds(60), "", null, Instant.now());
        AgentWorkItem updated = mock(AgentWorkItem.class);
        when(dispatch.findPreview(principal, "work-1")).thenReturn(Optional.of(preview));
        when(dispatch.confirmPreview(principal, "work-1", "preview-1", 1,
                "input-digest", "scope-digest")).thenReturn(updated);
        RouteConfirmationService service = new RouteConfirmationService(dispatch, mock(RoutingStore.class),
                workbench, new WorkbenchDispatchProperties(), scopes);

        service.confirm(principal, "work-1", "preview-1", 1, "input-digest", "scope-digest");

        verify(scopes).confirm(principal, "scope-1", 3, "fingerprint-1");
        verify(dispatch).confirmPreview(principal, "work-1", "preview-1", 1,
                "input-digest", "scope-digest");
        verify(workbench).appendLocalEvent(org.mockito.ArgumentMatchers.eq(principal),
                org.mockito.ArgumentMatchers.eq("work-1"), org.mockito.ArgumentMatchers.any());
    }
}
