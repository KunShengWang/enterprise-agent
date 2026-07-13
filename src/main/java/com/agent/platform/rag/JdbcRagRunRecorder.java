package com.agent.platform.rag;

import com.agent.platform.storage.JdbcAgentStoreSupport;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Primary
@Repository
public class JdbcRagRunRecorder implements RagRunRecorder {

    private static final String CATEGORY = "rag_run";

    private final JdbcAgentStoreSupport store;

    public JdbcRagRunRecorder(JdbcAgentStoreSupport store) {
        this.store = store;
    }

    @Override
    public void record(RagResult result) {
        if (result == null || "empty".equals(result.retrievalMode())) {
            return;
        }
        RagRunRecord record = RagRunRecord.from(result);
        store.save(CATEGORY, record.ragRunId(), record, record.createdAt(), record.createdAt());
    }

    @Override
    public List<RagRunRecord> recent(int limit) {
        return store.recent(CATEGORY, RagRunRecord.class, limit);
    }

    @Override
    public RagRunStats stats(int limit) {
        List<RagRunRecord> records = recent(limit);
        int hitRuns = (int) records.stream().filter(RagRunRecord::enoughEvidence).count();
        Map<String, Long> runsByMode = records.stream().collect(
                Collectors.groupingBy(RagRunRecord::retrievalMode, Collectors.counting())
        );
        return new RagRunStats(
                records.size(),
                hitRuns,
                records.isEmpty() ? 0 : (double) hitRuns / records.size(),
                records.stream().mapToLong(RagRunRecord::durationMs).average().orElse(0),
                records.stream().mapToInt(RagRunRecord::retrievedDocuments).average().orElse(0),
                runsByMode
        );
    }

    @Override
    public void clear() {
        store.clear(CATEGORY);
    }
}
