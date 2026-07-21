package com.agent.platform.workbench.target;

import com.agent.platform.ordercare.incident.config.IncidentCommandProperties;
import com.agent.platform.workbench.security.AuthenticatedPrincipal;
import org.springframework.stereotype.Component;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

@Component
public class ExecutionTargetRegistry {

    private final IncidentCommandProperties incidentProperties;

    public ExecutionTargetRegistry(IncidentCommandProperties incidentProperties) {
        this.incidentProperties = incidentProperties;
    }

    public List<ExecutionTargetDefinition> enabledTargets(AuthenticatedPrincipal principal) {
        if (principal == null) {
            throw new IllegalArgumentException("authenticated principal is required");
        }
        Map<ExecutionTargetId, ExecutionTargetDefinition> catalog = catalog(principal);
        return catalog.values().stream().filter(ExecutionTargetDefinition::enabled).toList();
    }

    public Optional<ExecutionTargetDefinition> findEnabled(AuthenticatedPrincipal principal, String targetId) {
        try {
            ExecutionTargetId id = ExecutionTargetId.valueOf(targetId == null ? "" : targetId.trim());
            return Optional.ofNullable(catalog(principal).get(id)).filter(ExecutionTargetDefinition::enabled);
        }
        catch (IllegalArgumentException ignored) {
            return Optional.empty();
        }
    }

    private Map<ExecutionTargetId, ExecutionTargetDefinition> catalog(AuthenticatedPrincipal principal) {
        boolean incidentRole = principal.roles().contains("INCIDENT_OPERATOR")
                || principal.roles().contains("ADMIN");
        EnumMap<ExecutionTargetId, ExecutionTargetDefinition> definitions = new EnumMap<>(ExecutionTargetId.class);
        definitions.put(ExecutionTargetId.GENERAL_AGENT, new ExecutionTargetDefinition(
                ExecutionTargetId.GENERAL_AGENT,
                "General explanation, knowledge and low-risk read-only assistance",
                Set.of("EXPLAIN", "KNOWLEDGE", "LOW_RISK_ASSISTANCE"),
                Set.of(), TargetRiskLevel.LOW, TargetCostClass.LOW,
                "general-safe-v1", true));
        definitions.put(ExecutionTargetId.ORDERCARE_CASE, new ExecutionTargetDefinition(
                ExecutionTargetId.ORDERCARE_CASE,
                "One bounded FlowOrder case identified by requestId, orderNo or deductNo",
                Set.of("ORDER_DIAGNOSIS", "ORDER_RECOVERY_REQUEST"),
                Set.of("oneOf:requestId,orderNo,deductNo"), TargetRiskLevel.MEDIUM, TargetCostClass.MEDIUM,
                "ordercare-floworder-v1", true));
        definitions.put(ExecutionTargetId.INCIDENT_INVESTIGATION, new ExecutionTargetDefinition(
                ExecutionTargetId.INCIDENT_INVESTIGATION,
                "Read-only multi-agent investigation over explicit bounded requestIds",
                Set.of("INCIDENT_INVESTIGATION"),
                Set.of("requestIds", "queueNames"), TargetRiskLevel.HIGH, TargetCostClass.HIGH,
                "ordercare-incident-command-v1", incidentProperties.isEnabled() && incidentRole));
        definitions.put(ExecutionTargetId.INCIDENT_RECOVERY_PLAN, new ExecutionTargetDefinition(
                ExecutionTargetId.INCIDENT_RECOVERY_PLAN,
                "Controlled recovery planning for one accessible ASSESSED incident",
                Set.of("INCIDENT_RECOVERY_PLAN"),
                Set.of("incidentId"), TargetRiskLevel.HIGH, TargetCostClass.HIGH,
                "ordercare-incident-recovery-v1",
                incidentProperties.isEnabled() && incidentProperties.isRecoveryPlannerEnabled() && incidentRole));
        return Map.copyOf(definitions);
    }
}
