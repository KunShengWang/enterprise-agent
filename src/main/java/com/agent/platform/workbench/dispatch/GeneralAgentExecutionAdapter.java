package com.agent.platform.workbench.dispatch;

import com.agent.platform.agent.AgentExecutor;
import com.agent.platform.runtime.AgentRunStore;
import com.agent.platform.workbench.target.ExecutionTargetId;
import org.springframework.stereotype.Component;

@Component
public class GeneralAgentExecutionAdapter extends AbstractAgentRunExecutionAdapter {
    public GeneralAgentExecutionAdapter(AgentExecutor executor, AgentRunStore runStore) {
        super(executor, runStore);
    }
    @Override public ExecutionTargetId targetId() { return ExecutionTargetId.GENERAL_AGENT; }
    @Override protected String scenarioId() { return ""; }
}
