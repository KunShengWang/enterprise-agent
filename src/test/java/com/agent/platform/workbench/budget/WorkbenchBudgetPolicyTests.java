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
        for (ExecutionTargetId target : new ExecutionTargetId[]{ExecutionTargetId.PROCUREMENT_SOURCING}) {
            assertEquals(true, properties.targetLimit(target).fitsWithin(properties.workItemLimit()));
        }
    }

    @Test
    void invalidChildPolicyFailsClosed() {
        WorkbenchBudgetProperties properties = new WorkbenchBudgetProperties();
        properties.getProcurementSourcing().setMaxTokens(properties.getWorkItem().getMaxTokens() + 1);
        assertThrows(IllegalStateException.class, properties::validateHierarchy);
    }

    @Test
    void historicalTargetsHaveNoCurrentBudgetPolicy() {
        var properties = new WorkbenchBudgetProperties();
        for (var target : new ExecutionTargetId[]{ExecutionTargetId.GENERAL_AGENT, ExecutionTargetId.ORDERCARE_CASE,
                ExecutionTargetId.INCIDENT_INVESTIGATION, ExecutionTargetId.INCIDENT_RECOVERY_PLAN}) {
            assertEquals(target, ExecutionTargetId.valueOf(target.name()));
            assertThrows(com.agent.platform.common.RetiredBusinessException.class, () -> properties.targetLimit(target));
        }
    }

    @Test
    void disabledBudgetNeverAllowsGeneralDegradedMode() {
        WorkbenchBudgetProperties properties = new WorkbenchBudgetProperties();
        properties.setEnabled(false);
        DefaultWorkItemBudgetService service = new DefaultWorkItemBudgetService(
                mock(HierarchicalBudgetStore.class), properties, mock(LlmCostCalculator.class),
                mock(AgentRunStore.class));
        AuthenticatedPrincipal principal = new AuthenticatedPrincipal("tenant", "alice", Set.of("USER"));

        assertThrows(BudgetExceededException.class, () -> service.reserveTarget(principal, "work-1",
                ExecutionTargetId.GENERAL_AGENT, "dispatch-1"));
        assertThrows(BudgetExceededException.class, () -> service.reserveTarget(principal, "work-1",
                ExecutionTargetId.INCIDENT_INVESTIGATION, "dispatch-2"));
    }
}
