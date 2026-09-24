package com.agent.platform.workbench.security;

/** Global Timeline identity admission, separate from tenant-scoped business aggregates. */
public interface ConversationOwnershipStore {
    /** Claim atomically, or check existing ownership. Unknown reads return false; foreign/ambiguous IDs throw. */
    boolean check(AuthenticatedPrincipal principal, String conversationId, boolean claim);
}
