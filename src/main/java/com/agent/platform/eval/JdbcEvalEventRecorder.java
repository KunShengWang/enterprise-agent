package com.agent.platform.eval;

import com.agent.platform.storage.JdbcAgentStoreSupport;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.UUID;

@Primary
@Component
@ConditionalOnProperty(prefix = "enterprise-agent.storage", name = "mode", havingValue = "jdbc", matchIfMissing = true)
public class JdbcEvalEventRecorder implements EvalEventRecorder {

    private static final String CATEGORY = "eval_event";

    private final JdbcAgentStoreSupport store;

    public JdbcEvalEventRecorder(JdbcAgentStoreSupport store) {
        this.store = store;
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
        return store.recent(CATEGORY, AgentRunEvalEvent.class, Integer.MAX_VALUE);
    }
}
