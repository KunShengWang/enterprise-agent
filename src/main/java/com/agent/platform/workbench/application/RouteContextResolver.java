package com.agent.platform.workbench.application;

import com.agent.platform.workbench.model.AgentWorkItem;
import com.agent.platform.workbench.model.WorkLinkType;
import com.agent.platform.workbench.persistence.WorkbenchStore;
import com.agent.platform.workbench.security.AuthenticatedPrincipal;
import org.springframework.stereotype.Service;

import java.util.Map;

@Service
public class RouteContextResolver {

    private final WorkbenchStore store;

    public RouteContextResolver(WorkbenchStore store) {
        this.store = store;
    }

    public ResolvedRouteContext resolve(AuthenticatedPrincipal principal, AgentWorkItem workItem) {
        if (workItem.parentWorkItemId() == null || workItem.parentWorkItemId().isBlank()) {
            return new ResolvedRouteContext("", Map.of(), Map.of());
        }
        AgentWorkItem parent = store.findWorkItem(principal, workItem.parentWorkItemId()).orElse(null);
        if (parent == null) return new ResolvedRouteContext("", Map.of(), Map.of());
        String incidentId = parent.activeIncidentId();
        if (incidentId == null || incidentId.isBlank()) {
            incidentId = store.listLinks(principal, parent.workItemId()).stream()
                    .filter(link -> link.linkType() == WorkLinkType.INCIDENT)
                    .map(link -> link.linkedId()).findFirst().orElse("");
        }
        Map<String, String> trusted = incidentId.isBlank() ? Map.of() : Map.of("incidentId", incidentId);
        return new ResolvedRouteContext(
                "parentWorkItemId=" + parent.workItemId() + "; parentOutcome=" + parent.outcome(),
                trusted,
                Map.of());
    }
}
