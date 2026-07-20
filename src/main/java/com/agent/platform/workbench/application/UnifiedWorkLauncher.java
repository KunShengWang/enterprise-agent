package com.agent.platform.workbench.application;

import com.agent.platform.workbench.dispatch.DispatchCoordinator;
import com.agent.platform.workbench.model.AgentWorkItem;
import com.agent.platform.workbench.model.WorkControlState;
import com.agent.platform.workbench.persistence.WorkbenchStore;
import com.agent.platform.workbench.security.AuthenticatedPrincipal;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

@Service
public class UnifiedWorkLauncher {
    private static final Logger log = LoggerFactory.getLogger(UnifiedWorkLauncher.class);
    private final RoutingCoordinator routing;
    private final DispatchCoordinator dispatch;
    private final WorkbenchStore workbench;

    public UnifiedWorkLauncher(RoutingCoordinator routing,
                               DispatchCoordinator dispatch,
                               WorkbenchStore workbench) {
        this.routing = routing;
        this.dispatch = dispatch;
        this.workbench = workbench;
    }

    @Async("workbenchExecutor")
    public void routeAndDispatch(AuthenticatedPrincipal principal, String workItemId, String routingRequestId) {
        try {
            routing.route(principal, workItemId, routingRequestId);
            dispatchIfReady(principal, workItemId);
        }
        catch (RuntimeException exception) {
            log.warn("unified work launch failed: workItemId={}, code={}",
                    workItemId, exception.getClass().getSimpleName());
        }
    }

    @Async("workbenchExecutor")
    public void dispatchIfReady(AuthenticatedPrincipal principal, String workItemId) {
        try {
            AgentWorkItem current = workbench.findWorkItem(principal, workItemId).orElseThrow();
            if (current.controlState() == WorkControlState.READY_TO_DISPATCH) {
                dispatch.dispatch(principal, workItemId);
            }
        }
        catch (RuntimeException exception) {
            log.warn("unified work dispatch failed: workItemId={}, code={}",
                    workItemId, exception.getClass().getSimpleName());
        }
    }
}
