package com.agent.platform.workbench.budget;

import com.agent.platform.config.WorkbenchBudgetProperties;
import com.agent.platform.llm.LlmCostCalculator;
import com.agent.platform.runtime.AgentRunStore;
import com.agent.platform.workbench.model.AgentWorkItem;
import com.agent.platform.workbench.security.AuthenticatedPrincipal;
import com.agent.platform.workbench.target.ExecutionTargetId;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;

class WorkbenchBudgetPolicyTests {

    @Test
    void defaultHierarchyIsValidAndEveryTargetFitsTheWorkItem() {
        WorkbenchBudgetProperties properties = new WorkbenchBudgetProperties();
        properties.validateHierarchy();
        for (ExecutionTargetId target : ExecutionTargetId.values()) {
            assertEquals(true, properties.targetLimit(target).fitsWithin(properties.workItemLimit()));
        }
    }

    @Test
    void invalidChildPolicyFailsClosed() {
        WorkbenchBudgetProperties properties = new WorkbenchBudgetProperties();
        properties.getIncidentAggregate().setMaxTokens(properties.getIncident().getMaxTokens() + 1);
        assertThrows(IllegalStateException.class, properties::validateHierarchy);
    }

    @Test
    void disabledBudgetOnlyAllowsExplicitGeneralDegradedMode() {
        WorkbenchBudgetProperties properties = new WorkbenchBudgetProperties();
        properties.setEnabled(false);
        DefaultWorkItemBudgetService service = new DefaultWorkItemBudgetService(
                mock(HierarchicalBudgetStore.class), properties, mock(LlmCostCalculator.class),
                mock(AgentRunStore.class));
        AuthenticatedPrincipal principal = new AuthenticatedPrincipal("tenant", "alice", Set.of("USER"));

        assertEquals(false, service.reserveTarget(principal, "work-1",
                ExecutionTargetId.GENERAL_AGENT, "dispatch-1").enforced());
        assertThrows(BudgetExceededException.class, () -> service.reserveTarget(principal, "work-1",
                ExecutionTargetId.INCIDENT_INVESTIGATION, "dispatch-2"));
    }
}
