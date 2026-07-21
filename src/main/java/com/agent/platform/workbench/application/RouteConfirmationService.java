package com.agent.platform.workbench.application;

import com.agent.platform.workbench.model.AgentWorkItem;
import com.agent.platform.workbench.model.RoutePreview;
import com.agent.platform.workbench.model.WorkEventDraft;
import com.agent.platform.workbench.model.WorkEventType;
import com.agent.platform.ordercare.incident.scope.persistence.IncidentScopeDiscoveryStore;
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
    private final IncidentScopeDiscoveryStore scopeStore;
    public RouteConfirmationService(DispatchStore store,
                                    RoutingStore routingStore,
                                    WorkbenchStore workbenchStore,
                                    WorkbenchDispatchProperties properties,
                                    IncidentScopeDiscoveryStore scopeStore) {
        this.store = store;
        this.routingStore = routingStore;
        this.workbenchStore = workbenchStore;
        this.properties = properties;
        this.scopeStore = scopeStore;
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
        RoutePreview preview = store.findPreview(principal, workItemId)
                .orElseThrow(() -> new IllegalArgumentException("route preview not found"));
        if (!preview.previewId().equals(previewId)
                || preview.previewVersion() != previewVersion
                || !preview.validatedInputDigest().equals(validatedInputDigest)
                || !preview.scopeDigest().equals(scopeDigest)) {
            throw new IllegalArgumentException("confirmation is not bound to the immutable preview");
        }
        java.util.Map<String, Object> validated = map(preview.payload().get("validatedInput"));
        String snapshotId = text(validated.get("scopeSnapshotId"));
        if (!snapshotId.isBlank()) {
            long snapshotVersion = number(validated.get("scopeSnapshotVersion"));
            String fingerprint = text(validated.get("candidateFingerprint"));
            scopeStore.confirm(principal, snapshotId, snapshotVersion, fingerprint);
        }
        AgentWorkItem confirmed = store.confirmPreview(principal, workItemId, previewId, previewVersion,
                validatedInputDigest, scopeDigest);
        if (!snapshotId.isBlank()) {
            workbenchStore.appendLocalEvent(principal, workItemId, new WorkEventDraft(
                    "scope-confirmed:" + snapshotId, WorkEventType.SCOPE_CONFIRMED,
                    "SCOPE_CONFIRMED", "Incident scope snapshot confirmed",
                    java.util.Map.of("snapshotId", snapshotId,
                            "candidateFingerprint", text(validated.get("candidateFingerprint"))), previewId));
        }
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

    private long number(Object value) {
        return value instanceof Number number ? number.longValue() : Long.parseLong(text(value));
    }

    private String text(Object value) {
        return value == null ? "" : String.valueOf(value).trim();
    }
}
