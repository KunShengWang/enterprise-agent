package com.agent.platform.eval;

import com.agent.platform.storage.JdbcAgentStoreSupport;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

@Primary
@Component
@ConditionalOnProperty(prefix = "enterprise-agent.storage", name = "mode", havingValue = "jdbc", matchIfMissing = true)
public class JdbcEvalReportRecorder implements EvalReportRecorder {

    private static final String CATEGORY = "eval_report";

    private final JdbcAgentStoreSupport store;

    public JdbcEvalReportRecorder(JdbcAgentStoreSupport store) {
        this.store = store;
    }

    @Override
    public void record(EvalReport report) {
        if (report == null || report.runId() == null || report.runId().isBlank()) {
            return;
        }
        store.save(CATEGORY, report.runId(), report, report.createdAt(), Instant.now());
    }

    @Override
    public Optional<EvalReport> find(String runId) {
        return store.find(CATEGORY, runId, EvalReport.class);
    }

    @Override
    public List<EvalReport> recent(int limit) {
        return store.recent(CATEGORY, EvalReport.class, limit);
    }

    @Override
    public void clear() {
        store.clear(CATEGORY);
    }
}
