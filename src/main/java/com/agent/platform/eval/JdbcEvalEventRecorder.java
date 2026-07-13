package com.agent.platform.eval;

import com.agent.platform.agent.AgentRunStatus;
import com.agent.platform.runtime.AgentRunRecord;
import com.agent.platform.runtime.AgentRunStore;
import com.agent.platform.storage.JdbcAgentStoreSupport;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;

import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.UUID;

@Primary
@Component
@ConditionalOnProperty(prefix = "enterprise-agent.storage", name = "mode", havingValue = "jdbc", matchIfMissing = true)
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
                AgentRunStatus.valueOf(run.state().name()),
                run.usedTools(),
                run.usedRag(),
                run.blockedByGuardrail(),
                run.updatedAt()
        );
    }
}
