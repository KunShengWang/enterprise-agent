package com.agent.platform.workbench.application;

import com.agent.platform.workbench.persistence.DispatchStore;
import com.agent.platform.workbench.persistence.RoutingStore;
import com.agent.platform.workbench.persistence.WorkbenchStore;
import com.agent.platform.workbench.security.AuthenticatedPrincipal;
import com.agent.platform.workbench.security.WorkbenchPrincipalProvider;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;

import java.time.Instant;
import java.util.Set;

import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class WorkbenchSchemaInitializerTests {

    @Test
    void initializesSchemaOwnersBeforeBackgroundScannersCanRun() {
        WorkbenchStore workbench = mock(WorkbenchStore.class);
        RoutingStore routing = mock(RoutingStore.class);
        DispatchStore dispatch = mock(DispatchStore.class);
        WorkbenchPrincipalProvider principals = mock(WorkbenchPrincipalProvider.class);
        AuthenticatedPrincipal principal = new AuthenticatedPrincipal("tenant", "alice", Set.of("USER"));
        when(principals.current()).thenReturn(principal);

        new WorkbenchSchemaInitializer(workbench, routing, dispatch, principals)
                .afterSingletonsInstantiated();

        InOrder order = inOrder(workbench, routing, dispatch);
        order.verify(workbench).findConversationState(principal, "__schema_initialization__");
        order.verify(routing).findStaleRouting(Instant.EPOCH, 1);
        order.verify(dispatch).findStaleDispatch(Instant.EPOCH, 1);
    }
}
