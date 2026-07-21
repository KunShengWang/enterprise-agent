package com.agent.platform.ordercare.incident.application;

import com.agent.platform.ordercare.incident.model.DelegationPlan;
import com.agent.platform.ordercare.incident.model.IncidentAgentRole;
import com.agent.platform.ordercare.incident.model.IncidentSnapshot;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

@Component
public class DelegationPlanValidator {

    private static final Set<IncidentAgentRole> REQUIRED_INVESTIGATION_ROLES = Set.of(
            IncidentAgentRole.ORDER_ANALYST,
            IncidentAgentRole.INVENTORY_ANALYST,
            IncidentAgentRole.MQ_ANALYST);

    private static final Set<String> FORBIDDEN_INTENTS = Set.of(
            "recover", "replay", "execute", "update", "delete", "write", "approve",
            "恢复", "重放", "执行", "更新", "删除", "审批", "修改");

    public ValidationResult validate(DelegationPlan plan, IncidentSnapshot snapshot) {
        List<String> errors = new ArrayList<>();
        if (plan == null) {
            return new ValidationResult(false, List.of("delegation plan is missing"));
        }
        if (!"delegation-plan-v1".equals(plan.schemaVersion())) {
            errors.add("unsupported schemaVersion");
        }
        if (snapshot == null || !snapshot.incidentId().equals(plan.incidentId())) {
            errors.add("incidentId does not match immutable snapshot");
        }
        if (plan.tasks().size() != REQUIRED_INVESTIGATION_ROLES.size()) {
            errors.add("investigation must contain exactly three domain specialist tasks");
        }
        if (snapshot != null && snapshot.deadlineAt() != null && !Instant.now().isBefore(snapshot.deadlineAt())) {
            errors.add("incident deadline is exhausted");
        }
        Set<IncidentAgentRole> roles = new HashSet<>();
        Set<String> keys = new HashSet<>();
        for (DelegationPlan.DelegatedTask task : plan.tasks()) {
            if (task.role() == null || !roles.add(task.role())) {
                errors.add("role must be present and unique");
            }
            if (task.clientTaskKey().isBlank() || !keys.add(task.clientTaskKey())) {
                errors.add("clientTaskKey must be present and unique");
            }
            if (task.objective().isBlank() || task.objective().length() > 500) {
                errors.add("objective must contain 1..500 characters");
            }
            if (!task.dependencies().isEmpty()) {
                errors.add("Phase 1 dependencies must be empty");
            }
            if (task.role() != null
                    && (!task.role().allowedEvidenceSubtypes().containsAll(task.requiredEvidenceSubtypes())
                    || task.requiredEvidenceSubtypes().isEmpty())) {
                errors.add("requiredEvidenceSubtypes do not match role " + task.role());
            }
            String normalizedObjective = task.objective().toLowerCase(Locale.ROOT);
            if (FORBIDDEN_INTENTS.stream().anyMatch(normalizedObjective::contains)) {
                errors.add("write or recovery intent is forbidden in Phase 1");
            }
        }
        if (!roles.equals(REQUIRED_INVESTIGATION_ROLES)) {
            errors.add("investigation must cover ORDER_ANALYST, INVENTORY_ANALYST and MQ_ANALYST");
        }
        return new ValidationResult(errors.isEmpty(), List.copyOf(errors));
    }

    public record ValidationResult(boolean valid, List<String> errors) {
        public ValidationResult {
            errors = errors == null ? List.of() : List.copyOf(errors);
        }
    }
}
