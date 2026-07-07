package com.agent.platform.eval;

import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.ConcurrentMap;

@Component
public class InMemoryEvalReportRecorder implements EvalReportRecorder {

    private static final int MAX_REPORTS = 100;

    private final ConcurrentMap<String, EvalReport> reports = new ConcurrentHashMap<>();

    private final ConcurrentLinkedDeque<String> recentRunIds = new ConcurrentLinkedDeque<>();

    @Override
    public void record(EvalReport report) {
        if (report == null || report.runId() == null || report.runId().isBlank()) {
            return;
        }
        reports.put(report.runId(), report);
        recentRunIds.addFirst(report.runId());
        while (recentRunIds.size() > MAX_REPORTS) {
            String removed = recentRunIds.pollLast();
            if (removed != null) {
                reports.remove(removed);
            }
        }
    }

    @Override
    public Optional<EvalReport> find(String runId) {
        if (runId == null || runId.isBlank()) {
            return Optional.empty();
        }
        return Optional.ofNullable(reports.get(runId.trim()));
    }

    @Override
    public List<EvalReport> recent(int limit) {
        List<EvalReport> result = new ArrayList<>();
        for (String runId : recentRunIds) {
            EvalReport report = reports.get(runId);
            if (report != null) {
                result.add(report);
            }
            if (result.size() >= Math.max(1, limit)) {
                break;
            }
        }
        return result;
    }

    @Override
    public void clear() {
        reports.clear();
        recentRunIds.clear();
    }
}
