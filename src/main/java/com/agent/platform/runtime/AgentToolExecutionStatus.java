package com.agent.platform.runtime;

public enum AgentToolExecutionStatus {
    COMPLETED,// 完成
    DENIED,// 已拒绝
    WAITING_APPROVAL,// 等待审批
    FAILED,// 失败的
    MANUAL_REVIEW// 人工审核
}
