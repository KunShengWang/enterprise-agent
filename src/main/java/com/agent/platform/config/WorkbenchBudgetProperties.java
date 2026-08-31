package com.agent.platform.config;

import com.agent.platform.workbench.budget.BudgetLimit;
import com.agent.platform.workbench.target.ExecutionTargetId;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "enterprise-agent.workbench.budget")
public class WorkbenchBudgetProperties {

    private boolean enabled = true;
    private boolean allowLowRiskDegradedMode = true;
    private final Limit workItem = new Limit(48, 220_000, 24, 900_000, 48);
    private final Limit routerAttempt = new Limit(1, 12_000, 0, 45_000, 2);
    private final Limit general = new Limit(12, 48_000, 10, 240_000, 12);
    private final Limit orderCare = new Limit(16, 72_000, 14, 360_000, 18);
    private final Limit incident = new Limit(32, 170_000, 8, 720_000, 36);
    private final Limit recoveryPlan = new Limit(6, 36_000, 20, 300_000, 10);
    private final Limit incidentAggregate = new Limit(32, 170_000, 8, 720_000, 36);
    private final Limit recoveryPlanAggregate = new Limit(6, 36_000, 20, 300_000, 10);
    private final Limit procurementSourcing = new Limit(10, 48_000, 8, 240_000, 12);

    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }
    public boolean isAllowLowRiskDegradedMode() { return allowLowRiskDegradedMode; }
    public void setAllowLowRiskDegradedMode(boolean value) { allowLowRiskDegradedMode = value; }
    public Limit getWorkItem() { return workItem; }
    public Limit getRouterAttempt() { return routerAttempt; }
    public Limit getGeneral() { return general; }
    public Limit getOrderCare() { return orderCare; }
    public Limit getIncident() { return incident; }
    public Limit getRecoveryPlan() { return recoveryPlan; }
    public Limit getIncidentAggregate() { return incidentAggregate; }
    public Limit getRecoveryPlanAggregate() { return recoveryPlanAggregate; }
    public Limit getProcurementSourcing() { return procurementSourcing; }

    public BudgetLimit workItemLimit() { return workItem.toLimit(); }
    public BudgetLimit routerAttemptLimit() { return routerAttempt.toLimit(); }
    public BudgetLimit incidentAggregateLimit() { return incidentAggregate.toLimit(); }
    public BudgetLimit recoveryPlanAggregateLimit() { return recoveryPlanAggregate.toLimit(); }

    public BudgetLimit targetLimit(ExecutionTargetId targetId) {
        return switch (targetId) {
            case GENERAL_AGENT -> general.toLimit();
            case ORDERCARE_CASE -> orderCare.toLimit();
            case INCIDENT_INVESTIGATION -> incident.toLimit();
            case INCIDENT_RECOVERY_PLAN -> recoveryPlan.toLimit();
            case PROCUREMENT_SOURCING -> procurementSourcing.toLimit();
        };
    }

    public void validateHierarchy() {
        BudgetLimit root = workItemLimit();
        requireFits(routerAttemptLimit(), root, "routerAttempt");
        for (ExecutionTargetId target : ExecutionTargetId.values()) {
            requireFits(targetLimit(target), root, target.name());
        }
        requireFits(incidentAggregateLimit(), targetLimit(ExecutionTargetId.INCIDENT_INVESTIGATION),
                "incidentAggregate");
        requireFits(recoveryPlanAggregateLimit(), targetLimit(ExecutionTargetId.INCIDENT_RECOVERY_PLAN),
                "recoveryPlanAggregate");
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
