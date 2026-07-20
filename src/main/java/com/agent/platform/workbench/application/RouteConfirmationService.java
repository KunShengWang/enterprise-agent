package com.agent.platform.workbench.application;

import com.agent.platform.workbench.model.AgentWorkItem;
import com.agent.platform.workbench.model.RoutePreview;
import com.agent.platform.workbench.persistence.DispatchStore;
import com.agent.platform.workbench.persistence.RoutingStore;
import com.agent.platform.workbench.persistence.WorkbenchStore;
import com.agent.platform.config.WorkbenchDispatchProperties;
import com.agent.platform.workbench.security.AuthenticatedPrincipal;
import org.springframework.stereotype.Service;

import java.util.Optional;

@Service
public class RouteConfirmationService {
    private final DispatchStore store;
    private final RoutingStore routingStore;
    private final WorkbenchStore workbenchStore;
    private final WorkbenchDispatchProperties properties;
    public RouteConfirmationService(DispatchStore store,
                                    RoutingStore routingStore,
                                    WorkbenchStore workbenchStore,
                                    WorkbenchDispatchProperties properties) {
        this.store = store;
        this.routingStore = routingStore;
        this.workbenchStore = workbenchStore;
        this.properties = properties;
    }
    public Optional<RoutePreview> preview(AuthenticatedPrincipal principal, String workItemId) {
        Optional<RoutePreview> existing = store.findPreview(principal, workItemId);
        if (existing.isPresent()) return existing;
        return workbenchStore.findWorkItem(principal, workItemId).flatMap(work ->
                routingStore.findEffectiveRouting(principal, workItemId)
                        .map(decision -> store.ensurePreview(
                                principal, work, decision, properties.getPreviewTtlSeconds())));
    }
    public AgentWorkItem confirm(AuthenticatedPrincipal principal, String workItemId,
                                 String previewId, int previewVersion,
                                 String validatedInputDigest, String scopeDigest) {
        return store.confirmPreview(principal, workItemId, previewId, previewVersion,
                validatedInputDigest, scopeDigest);
    }
    public AgentWorkItem reject(AuthenticatedPrincipal principal, String workItemId, String previewId) {
        return store.rejectPreview(principal, workItemId, previewId);
    }
}
