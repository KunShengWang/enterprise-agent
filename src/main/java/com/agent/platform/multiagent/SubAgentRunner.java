package com.agent.platform.multiagent;

import com.agent.platform.agent.AgentRequest;
import com.agent.platform.runtime.AgentEventDraft;
import com.agent.platform.runtime.AgentEventListener;
import com.agent.platform.runtime.AgentEventType;
import com.agent.platform.runtime.AgentExecutionProfile;
import com.agent.platform.runtime.AgentRunState;
import com.agent.platform.runtime.AgentRuntime;
import com.agent.platform.runtime.AgentRuntimeResult;
import com.agent.platform.runtime.AgentStopReason;
import com.agent.platform.runtime.AgentTimelineStore;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.Map;

@Component
public class SubAgentRunner {

    private final AgentRuntime agentRuntime;
    private final AgentTimelineStore timelineStore;

    public SubAgentRunner(AgentRuntime agentRuntime, AgentTimelineStore timelineStore) {
        this.agentRuntime = agentRuntime;
        this.timelineStore = timelineStore;
    }

    public SubAgentExecutionResult run(String coordinatorRunId,
                                       String coordinatorSessionId,
                                       String coordinatorUserId,
                                       String taskId,
                                       MultiAgentRole role,
                                       String instruction,
                                       AgentExecutionProfile profile) {
        appendEvent(coordinatorSessionId, coordinatorUserId, coordinatorRunId, AgentEventType.SUB_AGENT_STARTED,
                "sub-agent started", Map.of("taskId", taskId, "role", role.name(), "profile", profile.name()));
        String childSessionId = coordinatorSessionId + ":sub:" + taskId;
        String isolatedUserId = "subagent:" + coordinatorRunId + ":" + taskId;
        AgentRuntimeResult result = agentRuntime.run(
                new AgentRequest(
                        childSessionId,
                        isolatedUserId,
                        instruction,
                        Map.of(
                                "parentRunId", coordinatorRunId,
                                "subAgentRole", role.name(),
                                "internalSubAgent", true
                        )
                ),
                profile,
                AgentEventListener.NOOP
        );
        appendEvent(coordinatorSessionId, coordinatorUserId, coordinatorRunId, AgentEventType.SUB_AGENT_COMPLETED,
                "sub-agent completed",
                Map.of(
                        "taskId", taskId,
                        "role", role.name(),
                        "childRunId", result.runId(),
                        "childSessionId", result.sessionId(),
                        "state", result.state().name(),
                        "stopReason", result.stopReason().name()
                ));
        String answer = result.answer() == null || result.answer().isBlank()
                ? "子 Agent 未返回有效摘要。"
                : result.answer();
        return new SubAgentExecutionResult(
                taskId,
                role,
                result.runId(),
                result.sessionId(),
                answer,
                message(role, taskId, answer, Map.of(
                        "childRunId", result.runId(),
                        "childSessionId", result.sessionId(),
                        "state", result.state().name(),
                        "stopReason", result.stopReason().name(),
                        "turns", result.budget().turns(),
                        "modelCalls", result.budget().modelCalls(),
                        "toolCalls", result.budget().toolCalls(),
                        "fullContextShared", false
                ))
        );
    }

    public SubAgentExecutionResult timeout(MultiAgentTask task) {
        String answer = "子 Agent 超过 60 秒执行预算，结果未纳入 Reviewer 证据。";
        return new SubAgentExecutionResult(
                task.taskId(),
                task.role(),
                "",
                "",
                answer,
                message(task.role(), task.taskId(), answer, Map.of(
                        "state", AgentRunState.FAILED.name(),
                        "stopReason", AgentStopReason.TIMEOUT.name(),
                        "fullContextShared", false
                ))
        );
    }

    public SubAgentExecutionResult failure(MultiAgentTask task, Throwable failure) {
        String answer = "子 Agent 执行失败，结果未纳入 Reviewer 事实证据。";
        String errorType = failure == null ? "UNKNOWN" : failure.getClass().getSimpleName();
        return new SubAgentExecutionResult(
                task.taskId(),
                task.role(),
                "",
                "",
                answer,
                message(task.role(), task.taskId(), answer, Map.of(
                        "state", AgentRunState.FAILED.name(),
                        "stopReason", AgentStopReason.INTERNAL_ERROR.name(),
                        "errorType", errorType,
                        "fullContextShared", false
                ))
        );
    }

    private void appendEvent(String sessionId,
                             String userId,
                             String runId,
                             AgentEventType type,
                             String content,
                             Map<String, Object> payload) {
        timelineStore.appendEvent(sessionId, userId, runId, new AgentEventDraft(type, content, payload));
    }

    private MultiAgentMessage message(MultiAgentRole role,
                                      String taskId,
                                      String content,
                                      Map<String, Object> metadata) {
        return new MultiAgentMessage(role, taskId, content, Instant.now(), metadata);
    }
}
