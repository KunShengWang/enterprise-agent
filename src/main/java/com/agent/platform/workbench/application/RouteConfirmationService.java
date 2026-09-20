package com.agent.platform.workbench.application;

import com.agent.platform.common.BusinessRetirementPolicy;

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
        return workbenchStore.findWorkItem(principal, workItemId)
                .filter(work -> !BusinessRetirementPolicy.retiredTarget(work.activeExecutionTarget())).flatMap(work ->
                routingStore.findEffectiveRouting(principal, workItemId)
                        .map(decision -> store.ensurePreview(
                                principal, work, decision, properties.getPreviewTtlSeconds())));
    }
    public AgentWorkItem confirm(AuthenticatedPrincipal principal, String workItemId,
                                 String previewId, int previewVersion,
                                 String validatedInputDigest, String scopeDigest) {
        RoutePreview preview = store.findPreview(principal, workItemId)
                .orElseThrow(() -> new IllegalArgumentException("route preview not found"));
        BusinessRetirementPolicy.requireTarget(preview.targetId());
        workbenchStore.findWorkItem(principal, workItemId).ifPresent(work ->
                BusinessRetirementPolicy.requireTarget(work.activeExecutionTarget()));
        if (!preview.previewId().equals(previewId)
                || preview.previewVersion() != previewVersion
                || !preview.validatedInputDigest().equals(validatedInputDigest)
                || !preview.scopeDigest().equals(scopeDigest)) {
            throw new IllegalArgumentException("confirmation is not bound to the immutable preview");
        }
        java.util.Map<String, Object> validated = map(preview.payload().get("validatedInput"));
        String snapshotId = text(validated.get("scopeSnapshotId"));
        if (!snapshotId.isBlank()) {
            BusinessRetirementPolicy.requireTarget("INCIDENT_INVESTIGATION");
        }
        AgentWorkItem confirmed = store.confirmPreview(principal, workItemId, previewId, previewVersion,
                validatedInputDigest, scopeDigest);
        return confirmed;
    }
    public AgentWorkItem reject(AuthenticatedPrincipal principal, String workItemId, String previewId) {
        return store.rejectPreview(principal, workItemId, previewId);
    }

    @SuppressWarnings("unchecked")
    private java.util.Map<String, Object> map(Object value) {
        return value instanceof java.util.Map<?, ?> raw
                ? (java.util.Map<String, Object>) raw : java.util.Map.of();
    }

    private String text(Object value) {
        return value == null ? "" : String.valueOf(value).trim();
    }
}
