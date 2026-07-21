package com.agent.platform.ordercare.incident.application;

import com.agent.platform.ordercare.incident.model.DelegationPlan;
import com.agent.platform.ordercare.incident.model.EvidenceSubtype;
import com.agent.platform.ordercare.incident.model.IncidentAgentRole;
import com.agent.platform.ordercare.incident.model.IncidentSnapshot;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
public class SafeDelegationPlanFactory {

    public DelegationPlan create(IncidentSnapshot snapshot) {
        List<DelegationPlan.DelegatedTask> tasks = List.of(
                task("orders", IncidentAgentRole.ORDER_ANALYST,
                        "核对冻结范围内订单终态和 requestId 集合",
                        List.of(EvidenceSubtype.ORDER_STATUS_SET)),
                task("inventory", IncidentAgentRole.INVENTORY_ANALYST,
                        "核对冻结范围内扣减释放状态和库存不变量",
                        List.of(EvidenceSubtype.INVENTORY_DEDUCT_SET, EvidenceSubtype.INVENTORY_INVARIANT)),
                task("mq", IncidentAgentRole.MQ_ANALYST,
                        "核对冻结范围内的持久化死信事实和队列运行态",
                        List.of(EvidenceSubtype.DEAD_LETTER_SET, EvidenceSubtype.QUEUE_RUNTIME_STATUS)));
        return new DelegationPlan(
                "delegation-plan-v1", snapshot.incidentId(),
                "覆盖订单、库存和消息链路的有界只读安全降级调查计划",
                tasks
        );
    }

    private DelegationPlan.DelegatedTask task(String key,
                                              IncidentAgentRole role,
                                              String objective,
                                              List<EvidenceSubtype> subtypes) {
        return new DelegationPlan.DelegatedTask(key, role, objective, 100, List.of(), subtypes);
    }
}
