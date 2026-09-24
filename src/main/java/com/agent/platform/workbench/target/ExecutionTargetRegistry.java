package com.agent.platform.workbench.target;

import com.agent.platform.workbench.security.AuthenticatedPrincipal;
import org.springframework.stereotype.Component;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

@Component
public class ExecutionTargetRegistry {

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
        EnumMap<ExecutionTargetId, ExecutionTargetDefinition> definitions = new EnumMap<>(ExecutionTargetId.class);
        definitions.put(ExecutionTargetId.PROCUREMENT_SOURCING, new ExecutionTargetDefinition(
                ExecutionTargetId.PROCUREMENT_SOURCING,
                "复杂采购的供应商寻源、证据推荐，并可在人工审批后创建受控 RFQ",
                Set.of("PROCUREMENT_REQUIREMENT_UNDERSTANDING", "SUPPLIER_SOURCING", "SUPPLIER_EVALUATION",
                        "APPROVAL_BOUND_RFQ_CREATION"),
                Set.of("productDescription"), TargetRiskLevel.HIGH, TargetCostClass.LOW,
                "procurement-sourcing-rfq-v1", true));
        return Map.copyOf(definitions);
    }
}
