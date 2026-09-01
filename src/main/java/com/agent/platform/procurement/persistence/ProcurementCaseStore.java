package com.agent.platform.procurement.persistence;

import com.agent.platform.procurement.model.ProcurementCase;

import java.util.Optional;

public interface ProcurementCaseStore {
    Optional<ProcurementCase> findByTenantAndConversationId(String tenantId, String conversationId);
    default Optional<ProcurementCase> findByTenantUserAndConversationId(String tenantId, String userId, String conversationId) {
        return findByTenantAndConversationId(tenantId, conversationId)
                .filter(value -> value.userId().equals(userId));
    }
    ProcurementCase save(ProcurementCase procurementCase);

    /** 仅在当前版本仍为 expectedVersion 时更新，避免采购需求被并发覆盖。 */
    default boolean saveIfVersion(ProcurementCase procurementCase, long expectedVersion) {
        synchronized (this) {
            Optional<ProcurementCase> current = findByTenantUserAndConversationId(
                    procurementCase.tenantId(), procurementCase.userId(), procurementCase.conversationId());
            if (current.isEmpty() || current.get().version() != expectedVersion) return false;
            save(procurementCase);
            return true;
        }
    }

    /** 创建空 Case 时避免并发请求互相覆盖已存在的权威状态。 */
    default boolean createIfAbsent(ProcurementCase procurementCase) {
        synchronized (this) {
            if (findByTenantUserAndConversationId(procurementCase.tenantId(), procurementCase.userId(), procurementCase.conversationId()).isPresent()) {
                return false;
            }
            save(procurementCase);
            return true;
        }
    }
}
