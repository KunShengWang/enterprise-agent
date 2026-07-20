package com.agent.platform.workbench.application;

import com.agent.platform.config.WorkbenchDispatchProperties;
import com.agent.platform.workbench.model.AgentWorkItem;
import com.agent.platform.workbench.model.RoutingDecisionRecord;
import com.agent.platform.workbench.model.WorkControlState;
import com.agent.platform.workbench.persistence.DispatchStore;
import com.agent.platform.workbench.security.AuthenticatedPrincipal;
import org.springframework.stereotype.Service;

@Service
public class DispatchPreparationService implements RouteDecisionPostProcessor {

    private final DispatchStore store;
    private final WorkbenchDispatchProperties properties;

    public DispatchPreparationService(DispatchStore store, WorkbenchDispatchProperties properties) {
        this.store = store;
        this.properties = properties;
    }

    @Override
    public void afterEffectiveDecision(AuthenticatedPrincipal principal,
                                       AgentWorkItem workItem,
                                       RoutingDecisionRecord decision) {
        if (workItem.controlState() == WorkControlState.WAITING_CONFIRMATION) {
            store.ensurePreview(principal, workItem, decision, properties.getPreviewTtlSeconds());
        }
    }
}
