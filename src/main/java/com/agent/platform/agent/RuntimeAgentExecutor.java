package com.agent.platform.agent;

import com.agent.platform.runtime.AgentEvent;
import com.agent.platform.runtime.AgentEventType;
import com.agent.platform.runtime.AgentRunState;
import com.agent.platform.runtime.AgentRuntime;
import com.agent.platform.runtime.AgentRuntimeResult;
import com.agent.platform.trace.TraceEvent;
import com.agent.platform.trace.TraceSummary;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Locale;

/**
 * 将统一 AgentRuntime 适配为原有同步 API。
 */
@Primary
@Service
public class RuntimeAgentExecutor implements AgentExecutor {

    private final AgentRuntime runtime;

    public RuntimeAgentExecutor(AgentRuntime runtime) {
        this.runtime = runtime;
    }

    @Override
    public AgentResponse execute(AgentRequest request) {
        return toResponse(runtime.run(request));
    }

    @Override
    public AgentResponse resume(String runId) {
        return toResponse(runtime.resume(runId));
    }

    private AgentResponse toResponse(AgentRuntimeResult result) {
        List<AgentStep> steps = result.events().stream()
                .filter(event -> event.type() != AgentEventType.HEARTBEAT)
                .map(this::toStep)
                .toList();
        List<TraceEvent> traceEvents = result.events().stream()
                .map(event -> new TraceEvent(event.createdAt(), event.type().name().toLowerCase(Locale.ROOT), event.content()))
                .toList();
        return new AgentResponse(
                result.runId(),
                result.sessionId(),
                toStatus(result.state()),
                result.answer(),
                result.approvalId(),
                steps,
                new TraceSummary(result.runId(), result.sessionId(), traceEvents)
        );
    }

    private AgentStep toStep(AgentEvent event) {
        return new AgentStep(
                event.type().name().toLowerCase(Locale.ROOT).replace('_', '.'),
                eventStatus(event),
                event.content()
        );
    }

    private String eventStatus(AgentEvent event) {
        return switch (event.type()) {
            case RUN_FAILED -> "FAILED";
            case RUN_CANCELLED -> "CANCELLED";
            case APPROVAL_REQUIRED -> "WAITING_APPROVAL";
            case POLICY_DECIDED -> String.valueOf(event.payload().getOrDefault("action", "COMPLETED"));
            default -> "COMPLETED";
        };
    }

    private AgentRunStatus toStatus(AgentRunState state) {
        return switch (state) {
            case CREATED, RUNNING -> AgentRunStatus.RUNNING;
            case WAITING_APPROVAL -> AgentRunStatus.WAITING_APPROVAL;
            case COMPLETED -> AgentRunStatus.COMPLETED;
            case NEEDS_CLARIFICATION -> AgentRunStatus.NEEDS_CLARIFICATION;
            case BLOCKED -> AgentRunStatus.BLOCKED;
            case FAILED -> AgentRunStatus.FAILED;
            case REJECTED -> AgentRunStatus.REJECTED;
            case MANUAL_REVIEW -> AgentRunStatus.MANUAL_REVIEW;
        };
    }
}
