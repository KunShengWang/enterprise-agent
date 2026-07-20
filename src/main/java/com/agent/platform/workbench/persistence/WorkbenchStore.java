package com.agent.platform.workbench.persistence;

import com.agent.platform.workbench.application.CreateWorkItemCommand;
import com.agent.platform.workbench.application.CreatePersistedInputWorkItemCommand;
import com.agent.platform.workbench.application.WorkItemCreationResult;
import com.agent.platform.workbench.model.AgentConversationTurn;
import com.agent.platform.workbench.model.AgentWorkItem;
import com.agent.platform.workbench.model.ConversationWorkState;
import com.agent.platform.workbench.model.WorkEvent;
import com.agent.platform.workbench.model.WorkEventDraft;
import com.agent.platform.workbench.model.WorkLink;
import com.agent.platform.workbench.model.WorkRelation;
import com.agent.platform.workbench.security.AuthenticatedPrincipal;

import java.util.List;
import java.util.Optional;

public interface WorkbenchStore {

    WorkItemCreationResult createWorkItem(AuthenticatedPrincipal principal, CreateWorkItemCommand command);

    WorkItemCreationResult createWorkItemFromPersistedInput(AuthenticatedPrincipal principal,
                                                             CreatePersistedInputWorkItemCommand command);

    Optional<AgentConversationTurn> findInput(AuthenticatedPrincipal principal, String inputId);

    Optional<AgentConversationTurn> findInputByClientId(AuthenticatedPrincipal principal, String clientInputId);

    List<AgentConversationTurn> listInputs(AuthenticatedPrincipal principal, String conversationId, int limit);

    Optional<AgentWorkItem> findWorkItem(AuthenticatedPrincipal principal, String workItemId);

    List<AgentWorkItem> listWorkItems(AuthenticatedPrincipal principal, String conversationId, int limit);

    Optional<ConversationWorkState> findConversationState(AuthenticatedPrincipal principal, String conversationId);

    ConversationWorkState switchFocus(AuthenticatedPrincipal principal,
                                      String conversationId,
                                      String workItemId,
                                      long expectedVersion);

    WorkEvent appendLocalEvent(AuthenticatedPrincipal principal, String workItemId, WorkEventDraft event);

    AgentWorkItem abandon(AuthenticatedPrincipal principal,
                          String workItemId,
                          long expectedVersion,
                          String causationId);

    List<WorkEvent> loadEvents(AuthenticatedPrincipal principal,
                               String workItemId,
                               long afterSequence,
                               int limit);

    List<WorkRelation> listRelations(AuthenticatedPrincipal principal, String workItemId);

    WorkLink createLink(AuthenticatedPrincipal principal, WorkLink link);

    List<WorkLink> listLinks(AuthenticatedPrincipal principal, String workItemId);
}
