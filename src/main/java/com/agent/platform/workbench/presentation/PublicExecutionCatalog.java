package com.agent.platform.workbench.presentation;

import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

@Component
public class PublicExecutionCatalog {

    private static final Map<String, Definition> DEFINITIONS = Map.of(
            "GENERAL_AGENT", new Definition("General Agent", List.of(
                    "理解目标并加载相关上下文", "按需调用只读工具或知识库", "整理证据并生成回答")),
            "ORDERCARE_CASE", new Definition("OrderCare Agent", List.of(
                    "读取订单、扣减、库存与死信事实", "检索适用 SOP 并形成诊断",
                    "在需要副作用时申请人工确认", "验证最终业务状态")),
            "INCIDENT_INVESTIGATION", new Definition("Incident Commander", List.of(
                    "确认事故范围与调查边界", "调度领域 Specialist 收集证据",
                    "执行确定性冲突检查", "由 Reviewer 汇总结论")),
            "INCIDENT_RECOVERY_PLAN", new Definition("Recovery Planner", List.of(
                    "加载已确认的事故证据", "生成受控恢复建议",
                    "校验建议与证据引用", "等待人工处置决策"))
    );

    public Definition definition(String targetId) {
        return DEFINITIONS.getOrDefault(targetId, new Definition("Agent", List.of(
                "理解任务", "执行允许的操作", "返回结果")));
    }

    public record Definition(String label, List<String> standardProcess) {
        public Definition {
            standardProcess = standardProcess == null ? List.of() : List.copyOf(standardProcess);
        }
    }
}
