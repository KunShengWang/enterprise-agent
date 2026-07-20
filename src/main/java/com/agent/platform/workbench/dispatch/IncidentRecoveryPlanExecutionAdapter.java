package com.agent.platform.workbench.dispatch;

import com.agent.platform.ordercare.incident.recovery.application.IncidentRecoveryPlanLauncher;
import com.agent.platform.ordercare.incident.recovery.model.RecoveryPlanStartRequest;
import com.agent.platform.ordercare.incident.recovery.persistence.IncidentRecoveryPlanStore;
import com.agent.platform.workbench.model.WorkLinkType;
import com.agent.platform.workbench.target.ExecutionTargetId;
import org.springframework.stereotype.Component;

import java.util.Optional;

@Component
public class IncidentRecoveryPlanExecutionAdapter implements ExecutionAdapter {

    private final IncidentRecoveryPlanLauncher launcher;
    private final IncidentRecoveryPlanStore store;

    public IncidentRecoveryPlanExecutionAdapter(IncidentRecoveryPlanLauncher launcher,
                                                IncidentRecoveryPlanStore store) {
        this.launcher = launcher;
        this.store = store;
    }

    @Override public ExecutionTargetId targetId() { return ExecutionTargetId.INCIDENT_RECOVERY_PLAN; }

    @Override
    public DispatchResult dispatch(DispatchRequest request) {
        Optional<DispatchResult> existing = reconcile(request);
        if (existing.isPresent()) return existing.get();
        String incidentId = String.valueOf(request.validatedInput().typedPayload().getOrDefault("incidentId", "")).trim();
        if (incidentId.isBlank()) throw new IllegalArgumentException("validated incidentId is required");
        var started = launcher.start(
                incidentId, new RecoveryPlanStartRequest(request.dispatchRequestId(), request.goalText()));
        return new DispatchResult(
                request.dispatchRequestId(), WorkLinkType.RECOVERY_PLAN, started.planId(), started.newlyCreated());
    }

    @Override
    public Optional<DispatchResult> reconcile(DispatchRequest request) {
        String incidentId = String.valueOf(request.validatedInput().typedPayload().getOrDefault("incidentId", "")).trim();
        if (incidentId.isBlank()) return Optional.empty();
        return store.findByRequestKey(incidentId, request.dispatchRequestId())
                .map(plan -> new DispatchResult(
                        request.dispatchRequestId(), WorkLinkType.RECOVERY_PLAN, plan.planId(), false));
    }
}
