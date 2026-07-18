package com.agent.platform.ordercare.incident.application;

import com.agent.platform.ordercare.incident.model.DelegationPlan;
import com.agent.platform.ordercare.incident.model.EvidenceSubtype;
import com.agent.platform.ordercare.incident.model.IncidentAgentRole;
import com.agent.platform.ordercare.incident.model.IncidentSnapshot;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

@Component
public class SafeDelegationPlanFactory {

    public DelegationPlan create(IncidentSnapshot snapshot) {
        String alertType = snapshot.alertType() == null ? "" : snapshot.alertType().toUpperCase(Locale.ROOT);
        List<DelegationPlan.DelegatedTask> tasks = new ArrayList<>();
        if (alertType.contains("DLQ") || alertType.contains("MQ")) {
            tasks.add(task("mq", IncidentAgentRole.MQ_ANALYST,
                    "核对冻结范围内的持久化死信事实和队列运行态",
                    List.of(EvidenceSubtype.DEAD_LETTER_SET, EvidenceSubtype.QUEUE_RUNTIME_STATUS)));
        }
        tasks.add(task("orders", IncidentAgentRole.ORDER_ANALYST,
                "核对冻结范围内订单终态和 requestId 集合",
                List.of(EvidenceSubtype.ORDER_STATUS_SET)));
        if (tasks.size() < 3 && (alertType.contains("STOCK") || alertType.contains("INVENTORY")
                || alertType.contains("DLQ"))) {
            tasks.add(task("inventory", IncidentAgentRole.INVENTORY_ANALYST,
                    "核对冻结范围内扣减释放状态和库存不变量",
                    List.of(EvidenceSubtype.INVENTORY_DEDUCT_SET, EvidenceSubtype.INVENTORY_INVARIANT)));
        }
        return new DelegationPlan(
                "delegation-plan-v1", snapshot.incidentId(),
                "由 alertType 生成的有界只读安全降级调查计划",
                List.copyOf(tasks.subList(0, Math.min(3, tasks.size())))
        );
    }

    private DelegationPlan.DelegatedTask task(String key,
                                              IncidentAgentRole role,
                                              String objective,
                                              List<EvidenceSubtype> subtypes) {
        return new DelegationPlan.DelegatedTask(key, role, objective, 100, List.of(), subtypes);
    }
}
