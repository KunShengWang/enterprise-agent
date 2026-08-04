package com.agent.platform.ordercare.incident.model;

/**
 * 事故调查（Incident）的状态机。
 *
 * <p>一次调查的典型主流程：
 * <pre>
 * CREATED → PLANNING → INVESTIGATING → CHECKING_CONSISTENCY → REVIEWING → ASSESSED
 *                                （CLARIFYING 可循环，结论不明时向用户请求澄清）
 * </pre>
 * 异常分支会落到 PARTIAL / MANUAL_REVIEW / FAILED / CANCELLED 等终态。</p>
 */
public enum IncidentStatus {
    /** 已创建：事故记录刚落库，尚未开始调查。 */
    CREATED,
    /** 规划中：Commander（指挥官）生成调查方案与任务计划。 */
    PLANNING,
    /** 调查中：多个 Specialist（专家）并行执行任务、收集证据。 */
    INVESTIGATING,
    /** 一致性检查中：校验已收集证据是否一致，检测冲突。 */
    CHECKING_CONSISTENCY,
    /** 评审中：Reviewer（评审者）审查证据与冲突，生成结论。 */
    REVIEWING,
    /** 澄清中：结论不明确，向用户请求补充信息后重新评审。 */
    CLARIFYING,
    /** 已评估：调查完成，已产出最终评估结果（终态）。 */
    ASSESSED,
    /** 部分完成：存在证据缺口，只完成部分调查（终态）。 */
    PARTIAL,
    /** 人工复核：自动调查无法定论，需要人工介入（终态）。 */
    MANUAL_REVIEW,
    /** 已失败：调查执行失败（终态）。 */
    FAILED,
    /** 已取消：调查被主动取消（终态）。 */
    CANCELLED;

    /** 是否为终态：终态之后不会再发生状态转换。 */
    public boolean terminal() {
        return this == ASSESSED
                || this == PARTIAL
                || this == MANUAL_REVIEW
                || this == FAILED
                || this == CANCELLED;
    }
}
