package com.agent.platform.workbench.application;

import com.agent.platform.workbench.model.ConversationWorkState;
import com.agent.platform.workbench.persistence.WorkbenchStore;
import com.agent.platform.workbench.security.AuthenticatedPrincipal;
import org.springframework.stereotype.Service;

import java.util.Optional;

@Service
public class ConversationFocusService {

    private final WorkbenchStore store;

    public ConversationFocusService(WorkbenchStore store) {
        this.store = store;
    }

    public Optional<ConversationWorkState> find(AuthenticatedPrincipal principal, String conversationId) {
        return store.findConversationState(principal, conversationId);
    }

    public ConversationWorkState switchFocus(AuthenticatedPrincipal principal,
                                             String conversationId,
                                             String workItemId,
                                             long expectedVersion) {
        return store.switchFocus(principal, conversationId, workItemId, expectedVersion);
    }
}
