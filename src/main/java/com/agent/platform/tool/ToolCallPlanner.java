package com.agent.platform.tool;

import com.agent.platform.agent.AgentRequest;
import com.agent.platform.memory.ConversationMemory;
import com.agent.platform.router.IntentRoute;

import java.util.List;

public interface ToolCallPlanner {

    ToolCallPlan plan(AgentRequest request,
                      ConversationMemory memory,
                      IntentRoute route,
                      List<ToolDefinition> availableTools,
                      List<ToolCallResult> previousResults);
}
