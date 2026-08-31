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
}
