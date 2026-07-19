package com.agent.platform.workbench.application;

import com.agent.platform.workbench.model.GoalOrigin;
import com.agent.platform.workbench.model.NormalGoalEnvelope;
import com.agent.platform.workbench.model.WorkRelationType;
import com.agent.platform.workbench.persistence.WorkbenchStore;
import com.agent.platform.workbench.security.AuthenticatedPrincipal;
import org.junit.jupiter.api.Test;

import java.lang.reflect.RecordComponent;
import java.util.Arrays;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class WorkbenchModelAndServiceTests {

    @Test
    void directAndDerivedGoalEnvelopesEnforceFrozenSemantics() {
        new NormalGoalEnvelope(
                "input-1", "goal", GoalOrigin.DIRECT_NORMAL_GOAL, "", "", null);
        new NormalGoalEnvelope(
                "input-2", "goal", GoalOrigin.DERIVED_FROM_START_NEW_WORK,
                "command-1", "parent-1", WorkRelationType.FOLLOW_UP_OF);

        assertThrows(IllegalArgumentException.class, () -> new NormalGoalEnvelope(
                "input-3", "goal", GoalOrigin.DERIVED_FROM_START_NEW_WORK, "", "", null));
        assertThrows(IllegalArgumentException.class, () -> new NormalGoalEnvelope(
                "input-4", "goal", GoalOrigin.DIRECT_NORMAL_GOAL, "command", "", null));
        assertThrows(IllegalArgumentException.class, () -> new NormalGoalEnvelope(
                "input-5", "goal", GoalOrigin.DIRECT_NORMAL_GOAL, "", "parent", null));
    }

    @Test
    void submitCommandCannotCarryTrustedIdentityOrServerGeneratedRoutingId() {
        Set<String> componentNames = Arrays.stream(SubmitWorkInputCommand.class.getRecordComponents())
                .map(RecordComponent::getName)
                .collect(java.util.stream.Collectors.toSet());

        assertFalse(componentNames.contains("tenantId"));
        assertFalse(componentNames.contains("ownerPrincipalId"));
        assertFalse(componentNames.contains("routingRequestId"));
        assertFalse(componentNames.contains("workItemId"));
    }

    @Test
    void workInputServiceBuildsServerSideInputEnvelopeAndPreservesPrincipalBoundary() {
        WorkbenchStore store = mock(WorkbenchStore.class);
        WorkInputService service = new WorkInputService(new WorkItemService(store));
        AuthenticatedPrincipal principal = new AuthenticatedPrincipal("tenant-a", "alice", Set.of("USER"));

        service.submit(principal, SubmitWorkInputCommand.direct(
                "client-1", "conversation-1", "explain circuit breaker", 0));

        verify(store).createWorkItem(any(AuthenticatedPrincipal.class), any(CreateWorkItemCommand.class));
        assertTrue(principal.roles().contains("USER"));
    }
}
