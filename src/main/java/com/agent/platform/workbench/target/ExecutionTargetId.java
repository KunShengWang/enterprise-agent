package com.agent.platform.workbench.target;

/**
 * 表示 WorkItem 由哪一类"执行目标/执行器"来实际执行
 */
public enum ExecutionTargetId {
    GENERAL_AGENT,             // 历史 ID：仅保留读取，不再注册或执行
    ORDERCARE_CASE,            // 历史 ID：只供读取与 TARGET_RETIRED 拒绝，不可执行
    INCIDENT_INVESTIGATION,    // 历史 ID：只供读取与退役拒绝，不可执行
    INCIDENT_RECOVERY_PLAN,    // 历史 ID：只供读取与退役拒绝，不可执行
    PROCUREMENT_SOURCING;      // 复杂/非标采购供应商寻源与决策

    /** Historical enum values are deliberately retained; only procurement can execute. */
    public boolean executable() { return this == PROCUREMENT_SOURCING; }

    public static java.util.Set<ExecutionTargetId> executableTargets() {
        return java.util.Set.of(PROCUREMENT_SOURCING);
    }
}

