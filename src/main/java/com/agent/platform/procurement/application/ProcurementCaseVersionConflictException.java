package com.agent.platform.procurement.application;

/** 当前采购 Case 已被其他请求更新，调用方必须重新读取并重新规划。 */
public final class ProcurementCaseVersionConflictException extends RuntimeException {
    public ProcurementCaseVersionConflictException(String message) {
        super("CASE_VERSION_CONFLICT: " + message);
    }
}
