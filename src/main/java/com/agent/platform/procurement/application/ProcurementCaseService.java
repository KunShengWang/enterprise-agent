package com.agent.platform.procurement.application;

import com.agent.platform.procurement.model.ProcurementCase;
import com.agent.platform.procurement.model.ProcurementCaseState;
import com.agent.platform.procurement.model.ProcurementCaseStatus;
import com.agent.platform.procurement.persistence.ProcurementCaseStore;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.UUID;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.LinkedHashSet;
import java.util.Set;

@Service
public class ProcurementCaseService {
    private final ProcurementCaseStore store;
    private final ProcurementCaseParser parser;

    public ProcurementCaseService(ProcurementCaseStore store, ProcurementCaseParser parser) {
        this.store = store; this.parser = parser;
    }

    public ProcurementCase upsert(String tenantId, String conversationId, String userId, String message) {
        return upsert(tenantId, conversationId, userId, message, "");
    }

    public ProcurementCase upsert(String tenantId, String conversationId, String userId, String message, String sourceInputId) {
        if (tenantId == null || tenantId.isBlank() || conversationId == null || conversationId.isBlank() || userId == null || userId.isBlank()) {
            throw new IllegalArgumentException("tenantId, conversationId and userId are required");
        }
        Instant now = Instant.now();
        String inputId = sourceInputId == null || sourceInputId.isBlank() ? digest(message) : sourceInputId.trim();
        ProcurementCase existing = store.findByTenantUserAndConversationId(tenantId.trim(), userId.trim(), conversationId.trim()).orElse(null);
        if (existing != null && existing.appliedInputIds().contains(inputId)) return existing;
        ProcurementCaseState state = parser.merge(existing == null ? ProcurementCaseState.empty() : existing.state(), message);
        ProcurementCaseStatus status = state.missingFields().isEmpty()
                ? ProcurementCaseStatus.SOURCING : ProcurementCaseStatus.CLARIFICATION_REQUIRED;
        return store.save(existing == null
                ? new ProcurementCase(UUID.randomUUID().toString(), tenantId, conversationId, userId, status, state, now, now, 1, inputId)
                : new ProcurementCase(existing.caseId(), existing.tenantId(), existing.conversationId(), existing.userId(), status, state,
                existing.createdAt(), now, existing.version() + 1, inputId, appliedInputs(existing, inputId)));
    }

    public ProcurementCase upsert(String conversationId, String userId, String message) {
        return upsert("default-tenant", conversationId, userId, message);
    }

    private String digest(String value) {
        try { return "message-" + HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(String.valueOf(value).getBytes(StandardCharsets.UTF_8))); }
        catch (Exception exception) { throw new IllegalStateException("failed to derive procurement input id", exception); }
    }

    private Set<String> appliedInputs(ProcurementCase existing, String inputId) {
        LinkedHashSet<String> result = new LinkedHashSet<>(existing.appliedInputIds());
        result.add(inputId);
        while (result.size() > 128) result.remove(result.iterator().next());
        return Set.copyOf(result);
    }
}
