package com.agent.platform.workbench.application;

import com.agent.platform.workbench.model.AgentConversationTurn;
import com.agent.platform.workbench.model.AgentWorkItem;
import com.agent.platform.workbench.model.ConversationWorkState;
import com.agent.platform.workbench.model.RoutePreview;
import com.agent.platform.workbench.model.RoutingDecisionRecord;
import com.agent.platform.workbench.model.WorkEvent;
import com.agent.platform.workbench.model.WorkLink;
import com.agent.platform.workbench.persistence.DispatchStore;
import com.agent.platform.workbench.persistence.RoutingStore;
import com.agent.platform.workbench.persistence.WorkbenchNotFoundException;
import com.agent.platform.workbench.persistence.WorkbenchStore;
import com.agent.platform.workbench.security.AuthenticatedPrincipal;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class UnifiedWorkQueryService {
    private final WorkbenchStore workbench;
    private final RoutingStore routing;
    private final DispatchStore dispatch;
    public UnifiedWorkQueryService(WorkbenchStore workbench, RoutingStore routing, DispatchStore dispatch) {
        this.workbench = workbench; this.routing = routing; this.dispatch = dispatch;
    }
    public List<AgentWorkItem> workItems(AuthenticatedPrincipal principal, String conversationId, int limit) {
        return workbench.listWorkItems(principal, conversationId, limit);
    }
    public List<AgentConversationTurn> inputs(AuthenticatedPrincipal principal, String conversationId, int limit) {
        return workbench.listInputs(principal, conversationId, limit);
    }
    public ConversationWorkState focus(AuthenticatedPrincipal principal, String conversationId) {
        return workbench.findConversationState(principal, conversationId)
                .orElseThrow(() -> new WorkbenchNotFoundException("conversation not found"));
    }
    public UnifiedWorkItemView detail(AuthenticatedPrincipal principal, String workItemId) {
        AgentWorkItem item = workbench.findWorkItem(principal, workItemId)
                .orElseThrow(() -> new WorkbenchNotFoundException("work item not found"));
        RoutingDecisionRecord decision = routing.findEffectiveRouting(principal, workItemId).orElse(null);
        RoutePreview preview = dispatch.findPreview(principal, workItemId).orElse(null);
        return new UnifiedWorkItemView(item,
                workbench.findConversationState(principal, item.conversationId()).orElse(null),
                decision, preview, workbench.listLinks(principal, workItemId),
                workbench.loadEvents(principal, workItemId, -1, 500));
    }
    public record UnifiedWorkItemView(AgentWorkItem workItem,
                                      ConversationWorkState focus,
                                      RoutingDecisionRecord routingDecision,
                                      RoutePreview preview,
                                      List<WorkLink> links,
                                      List<WorkEvent> events) { }
}
