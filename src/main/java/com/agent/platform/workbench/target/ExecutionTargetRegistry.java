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
                "通用解释、知识问答和低风险只读协助",
                Set.of("EXPLAIN", "KNOWLEDGE", "LOW_RISK_ASSISTANCE"),
                Set.of(), TargetRiskLevel.LOW, TargetCostClass.LOW,
                "general-safe-v1", true));
        definitions.put(ExecutionTargetId.ORDERCARE_CASE, new ExecutionTargetDefinition(
                ExecutionTargetId.ORDERCARE_CASE,
                "由 requestId、orderNo 或 deductNo 标识的单个有界 FlowOrder 案例",
                Set.of("ORDER_DIAGNOSIS", "ORDER_RECOVERY_REQUEST"),
                Set.of("oneOf:requestId,orderNo,deductNo"), TargetRiskLevel.MEDIUM, TargetCostClass.MEDIUM,
                "ordercare-floworder-v1", true));
        definitions.put(ExecutionTargetId.INCIDENT_INVESTIGATION, new ExecutionTargetDefinition(
                ExecutionTargetId.INCIDENT_INVESTIGATION,
                "基于明确标识或可发现的有界业务条件执行只读多 Agent 事故调查",
                Set.of("INCIDENT_INVESTIGATION"),
                Set.of("oneOf:requestIds,timeExpression,orderNo", "anomalyType"),
                TargetRiskLevel.HIGH, TargetCostClass.HIGH,
                "ordercare-incident-command-v1", incidentProperties.isEnabled() && incidentRole));
        definitions.put(ExecutionTargetId.INCIDENT_RECOVERY_PLAN, new ExecutionTargetDefinition(
                ExecutionTargetId.INCIDENT_RECOVERY_PLAN,
                "针对一个当前用户可访问且状态为 ASSESSED 的事故制定受控恢复计划",
                Set.of("INCIDENT_RECOVERY_PLAN"),
                Set.of("incidentId"), TargetRiskLevel.HIGH, TargetCostClass.HIGH,
                "ordercare-incident-recovery-v1",
                incidentProperties.isEnabled() && incidentProperties.isRecoveryPlannerEnabled() && incidentRole));
        return Map.copyOf(definitions);
    }
}
