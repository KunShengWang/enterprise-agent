package com.agent.platform.config;

import com.agent.platform.workbench.budget.BudgetLimit;
import com.agent.platform.workbench.target.ExecutionTargetId;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "enterprise-agent.workbench.budget")
public class WorkbenchBudgetProperties {

    private boolean enabled = true;
    private final Limit workItem = new Limit(48, 220_000, 24, 900_000, 48);
    private final Limit routerAttempt = new Limit(1, 12_000, 0, 45_000, 2);
    private final Limit procurementSourcing = new Limit(10, 48_000, 8, 240_000, 12);

    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }
    public Limit getWorkItem() { return workItem; }
    public Limit getRouterAttempt() { return routerAttempt; }
    public Limit getProcurementSourcing() { return procurementSourcing; }

    public BudgetLimit workItemLimit() { return workItem.toLimit(); }
    public BudgetLimit routerAttemptLimit() { return routerAttempt.toLimit(); }

    public BudgetLimit targetLimit(ExecutionTargetId targetId) {
        return switch (targetId) {
            case GENERAL_AGENT, ORDERCARE_CASE, INCIDENT_INVESTIGATION, INCIDENT_RECOVERY_PLAN -> throw new com.agent.platform.common.RetiredBusinessException();
            case PROCUREMENT_SOURCING -> procurementSourcing.toLimit();
        };
    }

    public void validateHierarchy() {
        BudgetLimit root = workItemLimit();
        requireFits(routerAttemptLimit(), root, "routerAttempt");
        for (ExecutionTargetId target : new ExecutionTargetId[]{ExecutionTargetId.PROCUREMENT_SOURCING}) {
            requireFits(targetLimit(target), root, target.name());
        }
    }

    private void requireFits(BudgetLimit child, BudgetLimit parent, String name) {
        if (!child.fitsWithin(parent)) {
            throw new IllegalStateException("budget policy exceeds parent limit: " + name);
        }
    }

    public static class Limit {
        private int maxModelCalls;
        private long maxTokens;
        private int maxToolCalls;
        private long maxDurationMillis;
        private double maxEstimatedCost;

        public Limit() { }
        public Limit(int modelCalls, long tokens, int tools, long duration, double cost) {
            maxModelCalls = modelCalls; maxTokens = tokens; maxToolCalls = tools;
            maxDurationMillis = duration; maxEstimatedCost = cost;
        }
        public int getMaxModelCalls() { return maxModelCalls; }
        public void setMaxModelCalls(int value) { maxModelCalls = value; }
        public long getMaxTokens() { return maxTokens; }
        public void setMaxTokens(long value) { maxTokens = value; }
        public int getMaxToolCalls() { return maxToolCalls; }
        public void setMaxToolCalls(int value) { maxToolCalls = value; }
        public long getMaxDurationMillis() { return maxDurationMillis; }
        public void setMaxDurationMillis(long value) { maxDurationMillis = value; }
        public double getMaxEstimatedCost() { return maxEstimatedCost; }
        public void setMaxEstimatedCost(double value) { maxEstimatedCost = value; }
        public BudgetLimit toLimit() {
            return new BudgetLimit(maxModelCalls, maxTokens, maxToolCalls,
                    maxDurationMillis, maxEstimatedCost);
        }
    }
}
