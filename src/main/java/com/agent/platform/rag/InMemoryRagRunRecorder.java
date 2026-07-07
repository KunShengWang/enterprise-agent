package com.agent.platform.rag;

import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Component
public class InMemoryRagRunRecorder implements RagRunRecorder {

    private static final int MAX_RECORDS = 200;

    private final List<RagRunRecord> records = Collections.synchronizedList(new ArrayList<>());

    @Override
    public void record(RagResult result) {
        if (result == null || "empty".equals(result.retrievalMode())) {
            return;
        }
        synchronized (records) {
            records.add(0, RagRunRecord.from(result));
            while (records.size() > MAX_RECORDS) {
                records.remove(records.size() - 1);
            }
        }
    }

    @Override
    public List<RagRunRecord> recent(int limit) {
        synchronized (records) {
            return records.stream()
                    .limit(Math.max(1, limit))
                    .toList();
        }
    }

    @Override
    public RagRunStats stats(int limit) {
        List<RagRunRecord> snapshot = recent(limit);
        int totalRuns = snapshot.size();
        int hitRuns = (int) snapshot.stream().filter(RagRunRecord::enoughEvidence).count();
        double averageDurationMs = snapshot.stream().mapToLong(RagRunRecord::durationMs).average().orElse(0);
        double averageRetrievedDocuments = snapshot.stream().mapToInt(RagRunRecord::retrievedDocuments).average().orElse(0);
        Map<String, Long> runsByMode = snapshot.stream()
                .collect(Collectors.groupingBy(RagRunRecord::retrievalMode, Collectors.counting()));
        return new RagRunStats(
                totalRuns,
                hitRuns,
                totalRuns == 0 ? 0 : (double) hitRuns / totalRuns,
                averageDurationMs,
                averageRetrievedDocuments,
                runsByMode
        );
    }

    @Override
    public void clear() {
        records.clear();
    }
}
