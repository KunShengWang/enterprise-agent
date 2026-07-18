package com.agent.platform.runtime;

public enum AgentRunState {
    /** 已创建：run 记录已写入，尚未开始执行 */
    CREATED,
    /** 运行中：Agent 正在执行主链路 */
    RUNNING,
    /** 已请求暂停：Runtime 将在下一个安全检查点落盘后停止 */
    PAUSE_REQUESTED,
    /** 已暂停：保留原 Run、Checkpoint 和剩余预算，可继续恢复 */
    PAUSED,
    /** 等待审批：高风险工具触发 HITL，暂停等待人工确认 */
    WAITING_APPROVAL,
    /** 等待定向输入：结果和预算已持久化，当前没有线程或模型连接在等待 */
    WAITING_INPUT,
    /** 已完成：正常执行结束，返回了最终回答 */
    COMPLETED,
    /** 需要澄清：用户问题模糊，Agent 要求补充信息 */
    NEEDS_CLARIFICATION,
    /** 被拦截：Guardrail 判定 BLOCK，回答被阻止输出 */
    BLOCKED,
    /** 执行失败：Agent 内部抛出未捕获异常 */
    FAILED,
    /** 审批拒绝：人工审批不通过，高风险操作被驳回 */
    REJECTED,
    /** 人工复核：输出被脱敏处理，需管理员查看原始内容 */
    MANUAL_REVIEW
}
