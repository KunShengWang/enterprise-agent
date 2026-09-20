package com.agent.platform.workbench.application;

import com.agent.platform.workbench.model.AgentWorkItem;
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
        return new ResolvedRouteContext(
                "parentWorkItemId=" + parent.workItemId() + "; parentOutcome=" + parent.outcome(),
                Map.of(),
                Map.of());
    }
}
