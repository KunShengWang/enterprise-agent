package com.agent.platform.workbench.dispatch;

import com.agent.platform.ordercare.incident.application.IncidentInvestigationLauncher;
import com.agent.platform.ordercare.incident.model.IncidentInvestigationRequest;
import com.agent.platform.workbench.model.WorkLinkType;
import com.agent.platform.workbench.target.ExecutionTargetId;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Optional;

@Component
public class IncidentInvestigationExecutionAdapter implements ExecutionAdapter {

    private final IncidentInvestigationLauncher launcher;

    public IncidentInvestigationExecutionAdapter(IncidentInvestigationLauncher launcher) {
        this.launcher = launcher;
    }

    @Override public ExecutionTargetId targetId() { return ExecutionTargetId.INCIDENT_INVESTIGATION; }

    @Override
    public DispatchResult dispatch(DispatchRequest request) {
        // 在真正启动 Incident 调查之前，先查一下"这个分发请求是不是已经有对应的 Incident 了"
        Optional<DispatchResult> existing = reconcile(request);
        if (existing.isPresent()) return existing.get();// 已有 → 直接复用，不再启动

        var payload = request.validatedInput().typedPayload();
        String batchId = text(payload.get("batchId"));
        if (batchId.isBlank()) batchId = "WB-" + request.validatedInput().inputDigest().substring(0, 16);
        List<String> requestIds = values(payload.get("requestIds"));
        if (requestIds.isEmpty()) {
            throw new IllegalArgumentException(
                    "validated incident investigation requires explicit requestIds; batchId resolution is unavailable");
        }
        var started = launcher.startForDispatch(
                request.dispatchRequestId(),
                new IncidentInvestigationRequest(
                        batchId,
                        "ORDER_STATE_INCONSISTENCY",
                        request.requestedAt(),
                        request.goalText(),
                        requestIds,
                        queues(payload),
                        request.workItemId(),
                        text(payload.get("scopeSnapshotId")),
                        text(payload.get("candidateFingerprint")),
                        scopeProvenance(payload)));
        return new DispatchResult(
                request.dispatchRequestId(), WorkLinkType.INCIDENT, started.incidentId(), true);
    }

    @Override
    public Optional<DispatchResult> reconcile(DispatchRequest request) {
        return launcher.findByDispatchRequestId(request.dispatchRequestId())
                .map(response -> new DispatchResult(
                        request.dispatchRequestId(), WorkLinkType.INCIDENT, response.incidentId(), false));
    }

    private List<String> queues(java.util.Map<String, Object> payload) {
        List<String> result = values(payload.get("queueNames"));
        return result.isEmpty() ? values(payload.get("queueName")) : result;
    }

    private List<String> values(Object value) {
        if (value instanceof List<?> list) return list.stream().map(String::valueOf).map(String::trim).filter(v -> !v.isBlank()).toList();
        String single = text(value);
        return single.isBlank() ? List.of() : List.of(single);
    }

    private java.util.Map<String, Object> scopeProvenance(java.util.Map<String, Object> payload) {
        if (text(payload.get("scopeSnapshotId")).isBlank()) return java.util.Map.of();
        java.util.Map<String, Object> result = new java.util.LinkedHashMap<>();
        for (String key : List.of("criteriaDigest", "candidateCount", "truncated", "timeStart",
                "timeEnd", "timezone", "defaultTimezoneUsed", "anomalyTypes", "sourceHealth")) {
            Object value = payload.get(key);
            if (value != null) result.put(key, value);
        }
        result.put("resolutionSource", "SERVER_RESOLVED_FROM_SCOPE_DISCOVERY");
        return java.util.Map.copyOf(result);
    }

    private String text(Object value) { return value == null ? "" : String.valueOf(value).trim(); }
}
