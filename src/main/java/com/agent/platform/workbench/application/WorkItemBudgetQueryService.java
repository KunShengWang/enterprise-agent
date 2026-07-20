package com.agent.platform.workbench.application;

import com.agent.platform.workbench.budget.BudgetAccount;
import com.agent.platform.workbench.budget.HierarchicalBudgetStore;
import com.agent.platform.workbench.persistence.WorkbenchNotFoundException;
import com.agent.platform.workbench.persistence.WorkbenchStore;
import com.agent.platform.workbench.security.AuthenticatedPrincipal;
import org.springframework.stereotype.Service;

@Service
public class WorkItemBudgetQueryService {

    private final WorkbenchStore workbench;
    private final HierarchicalBudgetStore budgets;

    public WorkItemBudgetQueryService(WorkbenchStore workbench, HierarchicalBudgetStore budgets) {
        this.workbench = workbench;
        this.budgets = budgets;
    }

    public BudgetAccount require(AuthenticatedPrincipal principal, String workItemId) {
        workbench.findWorkItem(principal, workItemId)
                .orElseThrow(() -> new WorkbenchNotFoundException("work item not found"));
        return budgets.findAccount("WORK_ITEM", workItemId)
                .orElseThrow(() -> new WorkbenchNotFoundException("work item budget not initialized"));
    }
}
