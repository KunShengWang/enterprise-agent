package com.agent.platform.eval;

import com.agent.platform.agent.AgentRunStatus;
import com.agent.platform.runtime.AgentRunRecord;
import com.agent.platform.runtime.AgentRunStore;
import com.agent.platform.storage.JdbcAgentStoreSupport;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;

import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.UUID;

@Primary
@Component
public class JdbcEvalEventRecorder implements EvalEventRecorder {

    private static final String CATEGORY = "eval_event";

    private final JdbcAgentStoreSupport store;
    private final AgentRunStore runStore;

    public JdbcEvalEventRecorder(JdbcAgentStoreSupport store, AgentRunStore runStore) {
        this.store = store;
        this.runStore = runStore;
    }

    @Override
    public void record(AgentRunEvalEvent event) {
        if (event == null || event.createdAt() == null) {
            return;
        }
        String key = event.createdAt().toEpochMilli() + "-" + UUID.randomUUID();
        store.save(CATEGORY, key, event, event.createdAt(), event.createdAt());
    }

    @Override
    public List<AgentRunEvalEvent> snapshot() {
        LinkedHashMap<String, AgentRunEvalEvent> byTraceId = new LinkedHashMap<>();
        runStore.recent(10_000).stream()
                .map(this::fromRuntimeRun)
                .forEach(event -> byTraceId.put(event.traceId(), event));
        store.recent(CATEGORY, AgentRunEvalEvent.class, 10_000)
                .forEach(event -> byTraceId.putIfAbsent(event.traceId(), event));
        return byTraceId.values().stream()
                .sorted(Comparator.comparing(AgentRunEvalEvent::createdAt).reversed())
                .toList();
    }

    private AgentRunEvalEvent fromRuntimeRun(AgentRunRecord run) {
        return new AgentRunEvalEvent(
                run.runId(),
                run.conversationId(),
                switch (run.state()) {
                    case CREATED, RUNNING -> AgentRunStatus.RUNNING;
                    case PAUSE_REQUESTED, PAUSED -> AgentRunStatus.PAUSED;
                    case WAITING_APPROVAL -> AgentRunStatus.WAITING_APPROVAL;
                    case COMPLETED -> AgentRunStatus.COMPLETED;
                    case NEEDS_CLARIFICATION -> AgentRunStatus.NEEDS_CLARIFICATION;
                    case BLOCKED -> AgentRunStatus.BLOCKED;
                    case FAILED -> AgentRunStatus.FAILED;
                    case REJECTED -> AgentRunStatus.REJECTED;
                    case MANUAL_REVIEW -> AgentRunStatus.MANUAL_REVIEW;
                },
                run.usedTools(),
                run.usedRag(),
                run.blockedByGuardrail(),
                run.updatedAt()
        );
    }
}
