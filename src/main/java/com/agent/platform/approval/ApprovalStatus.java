package com.agent.platform.approval;

/**
 * 审批状态
 */
public enum ApprovalStatus {
    REQUESTED,// 已请求
    APPROVED,// 得到正式认可的
    REJECTED,// 已拒绝
    EXPIRED// 已到期
}
