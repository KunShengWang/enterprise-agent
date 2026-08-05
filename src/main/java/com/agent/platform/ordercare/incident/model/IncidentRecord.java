package com.agent.platform.ordercare.incident.model;

import java.time.Instant;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 一次事故调查的"主档案/快照记录"——IncidentRecord 是事故调查的核心聚合根，承载这次调查的全部状态
 */
public record IncidentRecord(
        String incidentId,            // ① 事故 ID（"inc-xxx"）
        String commanderRunId,        // ② 指挥官 Run 的 ID（谁规划的）
        String reviewerRunId,         // ③ 评审者 Run 的 ID（谁评审的）
        String conversationId,        // ④ 所属会话
        String scenarioId,            // ⑤ 场景 ID（"incident-scenario-v1"）
        IncidentStatus status,        // ⑥ 状态机状态（CREATED→...→ASSESSED）
        IncidentSnapshot snapshot,    // ⑦ 事故快照（不可变事实：涉及的请求、队列、范围）
        Map<String, Object> delegationPlan,  // ⑧ Commander 的规划结果
        Map<String, Object> assessment,      // ⑨ 最终评估结果
        int clarificationCount,       // ⑩ 已澄清次数
        int maxClarifications,        // ⑪ 最大允许澄清次数（预算）
        long nextEventSequence,       // ⑫ 事件序号（审计流用）
        long version,                 // ⑬ 乐观锁版本
        Instant createdAt,            // ⑭ 创建时间
        Instant updatedAt             // ⑮ 更新时间
) {
    public IncidentRecord {
        delegationPlan = immutableMap(delegationPlan);
        assessment = immutableMap(assessment);
    }

    private static Map<String, Object> immutableMap(Map<String, Object> value) {
        return value == null
                ? Map.of()
                : Collections.unmodifiableMap(new LinkedHashMap<>(value));
    }
}
