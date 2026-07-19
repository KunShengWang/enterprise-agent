package com.agent.platform.workbench.application;

import com.agent.platform.workbench.model.AgentConversationTurn;
import com.agent.platform.workbench.model.AgentWorkItem;
import com.agent.platform.workbench.model.WorkEvent;
import com.agent.platform.workbench.model.WorkLink;
import com.agent.platform.workbench.model.WorkRelation;
import com.agent.platform.workbench.persistence.WorkbenchStore;
import com.agent.platform.workbench.security.AuthenticatedPrincipal;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Optional;

@Service
public class WorkItemService {

    private final WorkbenchStore store;

    public WorkItemService(WorkbenchStore store) {
        this.store = store;
    }

    public WorkItemCreationResult create(AuthenticatedPrincipal principal, CreateWorkItemCommand command) {
        return store.createWorkItem(principal, command);
    }

    public Optional<AgentConversationTurn> findInput(AuthenticatedPrincipal principal, String inputId) {
        return store.findInput(principal, inputId);
    }

    public Optional<AgentWorkItem> find(AuthenticatedPrincipal principal, String workItemId) {
        return store.findWorkItem(principal, workItemId);
    }

    public AgentWorkItem abandon(AuthenticatedPrincipal principal,
                                 String workItemId,
                                 long expectedVersion,
                                 String causationId) {
        return store.abandon(principal, workItemId, expectedVersion, causationId);
    }

    public List<WorkEvent> events(AuthenticatedPrincipal principal,
                                  String workItemId,
                                  long afterSequence,
                                  int limit) {
        return store.loadEvents(principal, workItemId, afterSequence, limit);
    }

    public List<WorkRelation> relations(AuthenticatedPrincipal principal, String workItemId) {
        return store.listRelations(principal, workItemId);
    }

    public WorkLink link(AuthenticatedPrincipal principal, WorkLink link) {
        return store.createLink(principal, link);
    }

    public List<WorkLink> links(AuthenticatedPrincipal principal, String workItemId) {
        return store.listLinks(principal, workItemId);
    }
}
