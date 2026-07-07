package com.agent.platform.workflow;

import com.agent.platform.router.IntentRoute;

public interface WorkflowPlanner {

    WorkflowExecutionPlan plan(String traceId, String conversationId, IntentRoute route);

    WorkflowNode mapStepName(String stepName);

    boolean retryable(WorkflowNode node);

    boolean resumable(WorkflowNode node);
}
