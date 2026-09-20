package com.agent.platform.workbench.application;

import com.agent.platform.config.WorkbenchDispatchProperties;
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
    void retiredPreviewIsReadableButCannotConfirmScopeOrStartDispatch() {
        DispatchStore dispatch = mock(DispatchStore.class);
        WorkbenchStore workbench = mock(WorkbenchStore.class);
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
                workbench, new WorkbenchDispatchProperties());

        org.junit.jupiter.api.Assertions.assertEquals(preview, service.preview(principal, "work-1").orElseThrow());
        org.junit.jupiter.api.Assertions.assertThrows(com.agent.platform.common.RetiredBusinessException.class,
                () -> service.confirm(principal, "work-1", "preview-1", 1, "input-digest", "scope-digest"));
        org.mockito.Mockito.verifyNoInteractions(workbench);
        verify(dispatch, org.mockito.Mockito.never()).confirmPreview(principal, "work-1", "preview-1", 1,
                "input-digest", "scope-digest");
    }
    @Test
    void genericPreviewStillRequiresMatchingIdVersionAndBothDigests() {
        DispatchStore dispatch = mock(DispatchStore.class);
        WorkbenchStore workbench = mock(WorkbenchStore.class);
        var principal = new AuthenticatedPrincipal("tenant", "user", Set.of("USER"));
        var preview = new RoutePreview("p", "w", "d", "GENERAL_AGENT", 3, "input", "scope", Map.of(),
                RoutePreviewStatus.ACTIVE, Instant.now().plusSeconds(60), "", null, Instant.now());
        when(dispatch.findPreview(principal, "w")).thenReturn(Optional.of(preview));
        var service = new RouteConfirmationService(dispatch, mock(RoutingStore.class), workbench, new WorkbenchDispatchProperties());
        for (String[] values : java.util.List.of(new String[]{"wrong", "3", "input", "scope"},
                new String[]{"p", "2", "input", "scope"}, new String[]{"p", "3", "tampered", "scope"},
                new String[]{"p", "3", "input", "tampered"})) {
            org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class,
                    () -> service.confirm(principal, "w", values[0], Integer.parseInt(values[1]), values[2], values[3]));
        }
        verify(dispatch, org.mockito.Mockito.never()).confirmPreview(org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyInt(),
                org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyString());
        service.confirm(principal, "w", "p", 3, "input", "scope");
        verify(dispatch).confirmPreview(principal, "w", "p", 3, "input", "scope");
    }

    @Test
    void oldScopePayloadCannotBeConfirmedEvenWithAnActiveTargetLabel() {
        DispatchStore dispatch = mock(DispatchStore.class);
        var principal = new AuthenticatedPrincipal("tenant", "user", Set.of("USER"));
        var preview = new RoutePreview("p", "w", "d", "PROCUREMENT_SOURCING", 1, "input", "scope",
                Map.of("validatedInput", Map.of("scopeSnapshotId", "old-scope")),
                RoutePreviewStatus.ACTIVE, Instant.now().plusSeconds(60), "", null, Instant.now());
        when(dispatch.findPreview(principal, "w")).thenReturn(Optional.of(preview));
        var service = new RouteConfirmationService(dispatch, mock(RoutingStore.class), mock(WorkbenchStore.class), new WorkbenchDispatchProperties());
        org.junit.jupiter.api.Assertions.assertThrows(com.agent.platform.common.RetiredBusinessException.class,
                () -> service.confirm(principal, "w", "p", 1, "input", "scope"));
        verify(dispatch, org.mockito.Mockito.never()).confirmPreview(principal, "w", "p", 1, "input", "scope");
    }

}
