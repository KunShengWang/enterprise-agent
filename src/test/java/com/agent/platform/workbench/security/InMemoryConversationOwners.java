package com.agent.platform.workbench.security;

import com.agent.platform.workbench.persistence.WorkbenchAccessDeniedException;
import java.util.HashMap;
import java.util.Map;

/** Test persistence substitute; production uses a database unique key and transaction. */
public class InMemoryConversationOwners implements ConversationOwnershipStore {
    private final Map<String, String> owners = new HashMap<>();
    public synchronized boolean check(AuthenticatedPrincipal p, String id, boolean claim) {
        String owner = p.tenantId().length() + ":" + p.tenantId() + p.principalId();
        String existing = owners.get(id);
        if (existing != null && !existing.equals(owner)) throw new WorkbenchAccessDeniedException("foreign conversation");
        if (claim) owners.put(id, owner);
        return existing != null || claim;
    }
}
