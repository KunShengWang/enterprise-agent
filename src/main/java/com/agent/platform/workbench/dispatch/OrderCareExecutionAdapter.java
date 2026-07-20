package com.agent.platform.workbench.dispatch;

import com.agent.platform.agent.AgentExecutor;
import com.agent.platform.runtime.AgentRunStore;
import com.agent.platform.workbench.target.ExecutionTargetId;
import org.springframework.stereotype.Component;

@Component
public class OrderCareExecutionAdapter extends AbstractAgentRunExecutionAdapter {
    public OrderCareExecutionAdapter(AgentExecutor executor, AgentRunStore runStore) {
        super(executor, runStore);
    }
    @Override public ExecutionTargetId targetId() { return ExecutionTargetId.ORDERCARE_CASE; }
    @Override protected String scenarioId() { return "ordercare-floworder-v1"; }
}
