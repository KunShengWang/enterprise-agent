package com.agent.platform.workbench.application;

import com.agent.platform.workbench.persistence.DispatchStore;
import com.agent.platform.workbench.persistence.RoutingStore;
import com.agent.platform.workbench.persistence.WorkbenchStore;
import com.agent.platform.workbench.security.WorkbenchPrincipalProvider;
import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.time.Instant;

@Component
@ConditionalOnProperty(prefix = "enterprise-agent.workbench.web", name = "enabled", havingValue = "true")
public class WorkbenchSchemaInitializer implements SmartInitializingSingleton {

    private final WorkbenchStore workbenchStore;
    private final RoutingStore routingStore;
    private final DispatchStore dispatchStore;
    private final WorkbenchPrincipalProvider principalProvider;

    public WorkbenchSchemaInitializer(WorkbenchStore workbenchStore,
                                      RoutingStore routingStore,
                                      DispatchStore dispatchStore,
                                      WorkbenchPrincipalProvider principalProvider) {
        this.workbenchStore = workbenchStore;
        this.routingStore = routingStore;
        this.dispatchStore = dispatchStore;
        this.principalProvider = principalProvider;
    }

    @Override
    public void afterSingletonsInstantiated() {
        workbenchStore.findConversationState(principalProvider.current(), "__schema_initialization__");
        routingStore.findStaleRouting(Instant.EPOCH, 1);
        dispatchStore.findStaleDispatch(Instant.EPOCH, 1);
    }
}
