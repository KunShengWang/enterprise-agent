package com.agent.platform.tool;


import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.stream.Collectors;

@Deprecated(forRemoval = true)
public class InMemoryToolRunRecorder implements ToolRunRecorder {

    private final List<ToolCallRecord> records = new CopyOnWriteArrayList<>();

    @Override
    public void record(ToolCallRecord record) {
        records.add(record);
    }

    @Override
    public List<ToolCallRecord> recent(int limit) {
        int effectiveLimit = Math.max(1, limit);
        return records.stream()
                .sorted(Comparator.comparing(ToolCallRecord::occurredAt).reversed())
                .limit(effectiveLimit)
                .toList();
    }

    @Override
    public ToolRunStats stats() {
        long total = records.size();
        long success = records.stream().filter(ToolCallRecord::success).count();
        long failed = total - success;
        double successRate = total == 0 ? 0.0 : success * 1.0 / total;
        Map<String, Long> callsByTool = records.stream()
                .collect(Collectors.groupingBy(
                        ToolCallRecord::toolName,
                        LinkedHashMap::new,
                        Collectors.counting()
                ));
        return new ToolRunStats(total, success, failed, successRate, callsByTool);
    }

    public List<ToolCallRecord> all() {
        return new ArrayList<>(records);
    }
}
