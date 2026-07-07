package com.agent.platform.multiagent;

import java.time.Instant;
import java.util.List;
import java.util.Map;

public record MultiAgentRunResponse(
        String runId,
        String conversationId,
        String question,
        String finalAnswer,
        List<MultiAgentTask> tasks,
        List<MultiAgentMessage> messages,
        Instant startedAt,
        Instant finishedAt,
        long durationMs,
        Map<String, Object> metrics
) {

    public MultiAgentRunResponse {
        tasks = tasks == null ? List.of() : List.copyOf(tasks);
        messages = messages == null ? List.of() : List.copyOf(messages);
        metrics = metrics == null ? Map.of() : Map.copyOf(metrics);
    }
}
