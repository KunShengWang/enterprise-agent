package com.agent.platform.web;

import com.agent.platform.agent.AgentRequest;
import com.agent.platform.common.BusinessRetirementPolicy;
import com.agent.platform.common.RetiredBusinessException;
import com.agent.platform.config.AgentScenarioProfileResolver;
import com.agent.platform.procurement.application.ProcurementCaseService;
import com.agent.platform.procurement.config.ProcurementSourcingExecutionProfileFactory;
import com.agent.platform.workbench.security.WorkbenchPrincipalProvider;
import com.agent.platform.workbench.security.AuthenticatedPrincipal;
import com.agent.platform.workbench.security.ConversationAccessPolicy;
import com.agent.platform.workbench.persistence.WorkbenchAccessDeniedException;
import java.util.Set;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/** HTTP business execution boundary. Internal Runtime retains its general profile support. */
@Component
public class PublicProcurementRunPolicy {
    private static final String PROFILE = ProcurementSourcingExecutionProfileFactory.PROFILE_NAME;
    private final WorkbenchPrincipalProvider principals;
    private final ProcurementCaseService cases;
    private final AgentScenarioProfileResolver profiles;
    private final ConversationAccessPolicy conversations;

    public PublicProcurementRunPolicy(WorkbenchPrincipalProvider principals, ProcurementCaseService cases,
                                      AgentScenarioProfileResolver profiles, ConversationAccessPolicy conversations) {
        this.principals = principals;
        this.cases = cases;
        this.profiles = profiles;
        this.conversations = conversations;
    }

    /** No persistence here: validate and capture trusted identity before rate limiting/offloading. */
    public AgentRequest authorize(AgentRequest request) {
        BusinessRetirementPolicy.requireScenario(request.scenarioId());
        BusinessRetirementPolicy.requireTarget(String.valueOf(request.metadata().get("executionTarget")));
        BusinessRetirementPolicy.requireTarget(String.valueOf(request.metadata().get("targetId")));
        if (BusinessRetirementPolicy.retiredInput(request.question())) throw new RetiredBusinessException();
        if (!request.scenarioId().isBlank() && !PROFILE.equals(request.scenarioId())) {
            throw new IllegalArgumentException("public runs only support scenarioId=" + PROFILE);
        }
        var profile = profiles.resolve(PROFILE).orElseThrow(
                () -> new IllegalStateException("procurement profile is unavailable"));
        if (!PROFILE.equals(profile.name())) throw new IllegalStateException("invalid procurement profile");
        var principal = principals.current();
        // Runtime session keys are global. Keep direct API sessions separate from legacy/workbench sessions.
        String owner = principal.tenantId().length() + ":" + principal.tenantId() + principal.principalId();
        String prefix = "procurement-api-" + digest(owner) + "-";
        String supplied = request.conversationId() == null ? "" : request.conversationId().trim();
        if (supplied.startsWith(ConversationAccessPolicy.DIRECT_PREFIX) && !supplied.startsWith(prefix))
            throw new WorkbenchAccessDeniedException("conversation belongs to another identity");
        String conversation = supplied.startsWith(prefix) ? supplied
                : prefix + (supplied.isBlank() ? UUID.randomUUID() : digest(supplied));
        // Client metadata is never execution authority, including dispatch IDs, budgets and Case state.
        return new AgentRequest(conversation, principal.principalId(), request.question(), Map.of(
                "tenantId", principal.tenantId(), "authenticatedRoles", principal.roles(),
                "executionTarget", "PROCUREMENT_SOURCING", "publicConversationAlias", supplied), PROFILE);
    }

    /** Called on boundedElastic only after admission; failure prevents executor invocation. */
    public AgentRequest initializeCase(AgentRequest authorized) {
        @SuppressWarnings("unchecked")
        var principal = new AuthenticatedPrincipal((String) authorized.metadata().get("tenantId"),
                authorized.userId(), (Set<String>) authorized.metadata().get("authenticatedRoles"));
        conversations.claimDirect(principal, (String) authorized.metadata().get("publicConversationAlias"),
                authorized.conversationId());
        var value = cases.ensureCase((String) authorized.metadata().get("tenantId"),
                authorized.conversationId(), authorized.userId());
        var metadata = new LinkedHashMap<>(authorized.metadata());
        metadata.remove("publicConversationAlias");
        metadata.put("procurementCaseId", value.caseId());
        metadata.put("procurementCaseVersion", value.version());
        return new AgentRequest(authorized.conversationId(), authorized.userId(), authorized.question(),
                metadata, PROFILE);
    }

    public java.util.function.BooleanSupplier readPermission(String conversationId) {
        var principal = principals.current();
        return () -> conversations.canRead(principal, conversationId);
    }

    public java.util.function.Consumer<com.agent.platform.runtime.AgentRunRecord> resumePermission() {
        var principal = principals.current();
        return run -> {
            com.agent.platform.common.PublicBusinessRunPolicy.requireResumable(run);
            if (!principal.principalId().equals(run.userId())
                    || !conversations.canRead(principal, run.conversationId())) {
                throw new WorkbenchAccessDeniedException("run is not owned by the authenticated identity");
            }
        };
    }

    public String rateLimitKey(AgentRequest authorized) {
        String tenant = (String) authorized.metadata().get("tenantId");
        return "procurement-api:" + tenant.length() + ":" + tenant + authorized.userId();
    }

    private static UUID digest(String value) {
        return UUID.nameUUIDFromBytes(value.getBytes(StandardCharsets.UTF_8));
    }
}
