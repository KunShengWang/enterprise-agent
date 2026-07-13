package com.agent.platform.workflow;

public enum WorkflowRunStatus {
    RUNNING,
    WAITING_APPROVAL,// 等待批准
    COMPLETED,
    BLOCKED,
    FAILED,
    REJECTED,
    MANUAL_REVIEW,// 人工审批
    INTERRUPTED,// 中断
    RESUMABLE
}
