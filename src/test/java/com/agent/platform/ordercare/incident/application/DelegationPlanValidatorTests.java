package com.agent.platform.ordercare.incident.application;

import com.agent.platform.ordercare.incident.model.DelegationPlan;
import com.agent.platform.ordercare.incident.model.EvidenceSubtype;
import com.agent.platform.ordercare.incident.model.IncidentAgentRole;
import com.agent.platform.ordercare.incident.model.IncidentSnapshot;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DelegationPlanValidatorTests {

    @Test
    void acceptsBoundedReadOnlyPlanAndRejectsRoleSubtypeMismatchOrWriteIntent() {
        IncidentSnapshot snapshot = snapshot();
        DelegationPlan valid = completePlan();
        DelegationPlan invalid = new DelegationPlan(
                "delegation-plan-v1", "inc-1", "invalid",
                List.of(new DelegationPlan.DelegatedTask(
                        "orders", IncidentAgentRole.ORDER_ANALYST, "执行死信重放", 100,
                        List.of(), List.of(EvidenceSubtype.DEAD_LETTER_SET))));

        DelegationPlanValidator validator = new DelegationPlanValidator();
        assertTrue(validator.validate(valid, snapshot).valid());
        assertFalse(validator.validate(invalid, snapshot).valid());
    }

    @Test
    void rejectsCommanderPlanThatOmitsRequiredDomainSpecialists() {
        DelegationPlan incomplete = new DelegationPlan(
                "delegation-plan-v1", "inc-1", "incomplete",
                List.of(new DelegationPlan.DelegatedTask(
                        "orders", IncidentAgentRole.ORDER_ANALYST, "核对订单终态集合", 100,
                        List.of(), List.of(EvidenceSubtype.ORDER_STATUS_SET))));

        assertFalse(new DelegationPlanValidator().validate(incomplete, snapshot()).valid());
    }

    @Test
    void safeFallbackAlwaysRestoresCompleteDomainCoverage() {
        DelegationPlan fallback = new SafeDelegationPlanFactory().create(snapshot());

        assertTrue(new DelegationPlanValidator().validate(fallback, snapshot()).valid());
        assertEquals(List.of(
                        IncidentAgentRole.ORDER_ANALYST,
                        IncidentAgentRole.INVENTORY_ANALYST,
                        IncidentAgentRole.MQ_ANALYST),
                fallback.tasks().stream().map(DelegationPlan.DelegatedTask::role).toList());
    }

    private DelegationPlan completePlan() {
        return new DelegationPlan("delegation-plan-v1", "inc-1", "read-only investigation", List.of(
                new DelegationPlan.DelegatedTask("orders", IncidentAgentRole.ORDER_ANALYST,
                        "核对订单终态集合", 100, List.of(), List.of(EvidenceSubtype.ORDER_STATUS_SET)),
                new DelegationPlan.DelegatedTask("inventory", IncidentAgentRole.INVENTORY_ANALYST,
                        "核对库存扣减和释放事实", 100, List.of(),
                        List.of(EvidenceSubtype.INVENTORY_DEDUCT_SET, EvidenceSubtype.INVENTORY_INVARIANT)),
                new DelegationPlan.DelegatedTask("mq", IncidentAgentRole.MQ_ANALYST,
                        "核对持久化死信和队列运行态", 100, List.of(),
                        List.of(EvidenceSubtype.DEAD_LETTER_SET, EvidenceSubtype.QUEUE_RUNTIME_STATUS))));
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
