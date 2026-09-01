package com.agent.platform.procurement.persistence;

import com.agent.platform.procurement.model.ProcurementCase;

import java.util.Optional;

public interface ProcurementCaseStore {
    Optional<ProcurementCase> findByTenantUserAndConversationId(String tenantId, String userId, String conversationId);

    /** 创建空 Case 时避免并发请求互相覆盖已存在的权威状态。 */
    boolean createIfAbsent(ProcurementCase procurementCase);

    /** 仅在当前版本仍为 expectedVersion 时更新，避免采购需求被并发覆盖。 */
    boolean saveIfVersion(ProcurementCase procurementCase, long expectedVersion);
}
