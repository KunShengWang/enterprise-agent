package com.agent.platform.ordercare.incident.application;

import com.agent.platform.ordercare.incident.model.DelegationPlan;
import com.agent.platform.ordercare.incident.model.EvidenceSubtype;
import com.agent.platform.ordercare.incident.model.IncidentAgentRole;
import com.agent.platform.ordercare.incident.model.IncidentSnapshot;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DelegationPlanValidatorTests {

    @Test
    void acceptsBoundedReadOnlyPlanAndRejectsRoleSubtypeMismatchOrWriteIntent() {
        IncidentSnapshot snapshot = snapshot();
        DelegationPlan valid = new DelegationPlan(
                "delegation-plan-v1", "inc-1", "read-only investigation",
                List.of(new DelegationPlan.DelegatedTask(
                        "orders", IncidentAgentRole.ORDER_ANALYST, "核对订单终态集合", 100,
                        List.of(), List.of(EvidenceSubtype.ORDER_STATUS_SET))));
        DelegationPlan invalid = new DelegationPlan(
                "delegation-plan-v1", "inc-1", "invalid",
                List.of(new DelegationPlan.DelegatedTask(
                        "orders", IncidentAgentRole.ORDER_ANALYST, "执行死信重放", 100,
                        List.of(), List.of(EvidenceSubtype.DEAD_LETTER_SET))));

        DelegationPlanValidator validator = new DelegationPlanValidator();
        assertTrue(validator.validate(valid, snapshot).valid());
        assertFalse(validator.validate(invalid, snapshot).valid());
    }

    private IncidentSnapshot snapshot() {
        Instant now = Instant.now();
        return new IncidentSnapshot(
                "snap", "inc-1", "alert", "DLQ", "tenant",
                new IncidentSnapshot.IncidentOrderScope(List.of("REQ-1")),
                new IncidentSnapshot.IncidentBusinessScope(List.of("floworder.order.state.dlq")),
                new IncidentSnapshot.IncidentTimeWindow(now.minusSeconds(60), now),
                now, now, now.plusSeconds(60), "scope");
    }
}
