package com.agent.platform.tool;

import com.agent.platform.storage.JdbcAgentStoreSupport;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Repository;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

@Primary
@Repository
public class JdbcToolRunRecorder implements ToolRunRecorder {

    private static final String CATEGORY = "tool_run";
    private static final int STATS_WINDOW = 10_000;

    private final JdbcAgentStoreSupport store;

    public JdbcToolRunRecorder(JdbcAgentStoreSupport store) {
        this.store = store;
    }

    @Override
    public void record(ToolCallRecord record) {
        if (record == null) {
            return;
        }
        String requestId = record.requestId() == null || record.requestId().isBlank()
                ? "anonymous"
                : record.requestId();
        // 同一请求可能连续调用多个工具，运行记录必须追加而不是按 requestId 覆盖。
        String key = requestId + ":" + record.occurredAt().toEpochMilli() + ":" + UUID.randomUUID();
        store.save(CATEGORY, key, record, record.occurredAt(), record.occurredAt());
    }

    @Override
    public List<ToolCallRecord> recent(int limit) {
        return store.recent(CATEGORY, ToolCallRecord.class, limit);
    }

    @Override
    public ToolRunStats stats() {
        List<ToolCallRecord> records = recent(STATS_WINDOW);
        long total = records.size();
        long success = records.stream().filter(ToolCallRecord::success).count();
        Map<String, Long> callsByTool = records.stream().collect(Collectors.groupingBy(
                ToolCallRecord::toolName,
                LinkedHashMap::new,
                Collectors.counting()
        ));
        return new ToolRunStats(
                total,
                success,
                total - success,
                total == 0 ? 0 : (double) success / total,
                callsByTool
        );
    }
}
