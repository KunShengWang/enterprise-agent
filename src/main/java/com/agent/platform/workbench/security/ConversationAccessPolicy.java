package com.agent.platform.workbench.security;

import com.agent.platform.workbench.persistence.WorkbenchAccessDeniedException;
import org.springframework.stereotype.Component;

@Component
public class ConversationAccessPolicy {
    public static final String DIRECT_PREFIX = "procurement-api-";
    private final ConversationOwnershipStore owners;

    public ConversationAccessPolicy(ConversationOwnershipStore owners) { this.owners = owners; }

    public void claimWorkbench(AuthenticatedPrincipal principal, String conversationId) {
        String id = requiredId(conversationId);
        if (id.startsWith(DIRECT_PREFIX)) throw new WorkbenchAccessDeniedException("reserved direct API conversation");
        owners.check(principal, id, true);
    }

    public void claimDirect(AuthenticatedPrincipal principal, String alias, String effectiveId) {
        // Never turn somebody else's existing alias/session into a new session for the caller.
        if (alias != null && !alias.isBlank()) owners.check(principal, requiredId(alias), false);
        owners.check(principal, requiredId(effectiveId), true);
    }

    public boolean canRead(AuthenticatedPrincipal principal, String conversationId) {
        return owners.check(principal, requiredId(conversationId), false);
    }

    private static String requiredId(String id) {
        if (id == null || id.isBlank() || id.trim().length() > 256)
            throw new IllegalArgumentException("conversationId must contain 1 to 256 characters");
        return id.trim();
    }
}
