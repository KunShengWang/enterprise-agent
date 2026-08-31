package com.agent.platform.workbench.dispatch;

import com.agent.platform.agent.AgentExecutor;
import com.agent.platform.procurement.config.ProcurementSourcingExecutionProfileFactory;
import com.agent.platform.procurement.model.ProcurementCase;
import com.agent.platform.procurement.persistence.ProcurementCaseStore;
import com.agent.platform.runtime.AgentRunStore;
import com.agent.platform.workbench.target.ExecutionTargetId;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;

@Component
public class ProcurementSourcingExecutionAdapter extends AbstractAgentRunExecutionAdapter {
    private final ProcurementCaseStore caseStore;

    public ProcurementSourcingExecutionAdapter(AgentExecutor executor, AgentRunStore runStore,
                                               ProcurementCaseStore caseStore) {
        super(executor, runStore); this.caseStore = caseStore;
    }

    @Override public ExecutionTargetId targetId() { return ExecutionTargetId.PROCUREMENT_SOURCING; }
    @Override protected String scenarioId() { return ProcurementSourcingExecutionProfileFactory.PROFILE_NAME; }

    @Override
    protected Map<String, Object> additionalMetadata(DispatchRequest request) {
        ProcurementCase value = caseStore.findByTenantUserAndConversationId(request.principal().tenantId(),
                request.principal().principalId(), request.conversationId()).orElse(null);
        return value == null ? Map.of() : Map.of("procurementCaseId", value.caseId(),
                "procurementCaseVersion", value.version(),
                "procurementCaseStateDigest", digest(value.state().toString()),
                "procurementCaseState", value.state());
    }

    private String digest(String value) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(value.getBytes(StandardCharsets.UTF_8))); }
        catch (Exception exception) { throw new IllegalStateException("failed to digest procurement CaseState", exception); }
    }
}
