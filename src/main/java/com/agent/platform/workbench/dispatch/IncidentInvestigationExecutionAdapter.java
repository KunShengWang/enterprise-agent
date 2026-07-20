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
        Optional<DispatchResult> existing = reconcile(request);
        if (existing.isPresent()) return existing.get();
        var payload = request.validatedInput().typedPayload();
        String batchId = text(payload.get("batchId"));
        if (batchId.isBlank()) batchId = "WB-" + request.validatedInput().inputDigest().substring(0, 16);
        var started = launcher.startForDispatch(
                request.dispatchRequestId(),
                new IncidentInvestigationRequest(
                        batchId,
                        "ORDER_STATE_INCONSISTENCY",
                        request.requestedAt(),
                        request.goalText(),
                        values(payload.get("requestIds")),
                        queues(payload),
                        request.workItemId()));
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

    private String text(Object value) { return value == null ? "" : String.valueOf(value).trim(); }
}
