package com.agent.platform.procurement.application;

import com.agent.platform.procurement.model.ProcurementCase;
import com.agent.platform.procurement.model.ProcurementCasePatch;
import com.agent.platform.procurement.model.ProcurementCaseState;
import com.agent.platform.procurement.model.ProcurementCaseStatus;
import com.agent.platform.procurement.persistence.ProcurementCaseStore;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.UUID;
import java.util.LinkedHashSet;
import java.util.Set;

@Service
public class ProcurementCaseService {
    private final ProcurementCaseStore store;
    private final ProcurementCasePatchMerger patchMerger;

    @Autowired
    public ProcurementCaseService(ProcurementCaseStore store,
                                  ProcurementCasePatchMerger patchMerger) {
        this.store = store;
        this.patchMerger = patchMerger;
    }

    /** 确保同一 tenant/user/conversation 只存在一个采购 Case；不解析用户自然语言。 */
    public ProcurementCase ensureCase(String tenantId, String conversationId, String userId) {
        String tenant = required(tenantId, "tenantId");
        String conversation = required(conversationId, "conversationId");
        String user = required(userId, "userId");
        return store.findByTenantUserAndConversationId(tenant, user, conversation).orElseGet(() -> {
            Instant now = Instant.now();
            ProcurementCase created = new ProcurementCase(UUID.randomUUID().toString(), tenant, conversation, user,
                    ProcurementCaseStatus.CLARIFICATION_REQUIRED, ProcurementCaseState.empty(), now, now, 0, "");
            if (store.createIfAbsent(created)) return created;
            return store.findByTenantUserAndConversationId(tenant, user, conversation)
                    .orElseThrow(() -> new IllegalStateException("procurement Case disappeared during creation"));
        });
    }

    /**
     * 校验 Agent Patch，加载最新 Case，以 CAS 方式合并并持久化 authoritative CaseState。
     * sourceInputId 是 Runtime 的幂等标识，不来自模型 JSON。
     */
    public ProcurementCase applyPatch(String tenantId, String conversationId, String userId,
                                      ProcurementCasePatch patch, String sourceInputId) {
        String tenant = required(tenantId, "tenantId");
        String conversation = required(conversationId, "conversationId");
        String user = required(userId, "userId");
        String inputId = required(sourceInputId, "sourceInputId");
        patchMerger.validate(patch);
        ProcurementCase current = ensureCase(tenant, conversation, user);
        if (current.appliedInputIds().contains(inputId)) return current;

        ProcurementCaseState nextState = patchMerger.merge(current.state(), patch);
        ProcurementCase next = new ProcurementCase(current.caseId(), current.tenantId(), current.conversationId(),
                current.userId(), statusOf(nextState), nextState, current.createdAt(), Instant.now(),
                current.version() + 1, inputId, appliedInputs(current, inputId));
        if (!store.saveIfVersion(next, current.version())) {
            throw new ProcurementCaseVersionConflictException(
                    "procurement Case was updated before this Patch could be applied");
        }
        return next;
    }

    private Set<String> appliedInputs(ProcurementCase existing, String inputId) {
        LinkedHashSet<String> result = new LinkedHashSet<>(existing.appliedInputIds());
        result.add(inputId);
        while (result.size() > 128) result.remove(result.iterator().next());
        return Set.copyOf(result);
    }

    private ProcurementCaseStatus statusOf(ProcurementCaseState state) {
        return state.missingFields().isEmpty()
                ? ProcurementCaseStatus.SOURCING : ProcurementCaseStatus.CLARIFICATION_REQUIRED;
    }

    private String required(String value, String name) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(name + " is required");
        return value.trim();
    }
}
