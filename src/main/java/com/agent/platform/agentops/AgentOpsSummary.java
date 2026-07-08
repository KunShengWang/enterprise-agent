package com.agent.platform.agentops;

import com.agent.platform.rag.RagCacheStats;
import com.agent.platform.rag.RagRunStats;
import com.agent.platform.tool.ToolRunStats;
import com.agent.platform.trace.TraceRunStats;

import java.time.Instant;
import java.util.List;
import java.util.Map;

public record AgentOpsSummary(
        Instant generatedAt,
        int limit,
        TraceRunStats traceStats,
        RagRunStats ragStats,
        RagCacheStats ragCacheStats,
        ToolRunStats toolStats,
        AgentOpsEvalSnapshot latestEval,
        Map<String, String> metricMeanings,
        Map<String, String> endpoints,
        List<String> risks
) {

    public AgentOpsSummary {
        generatedAt = generatedAt == null ? Instant.now() : generatedAt;
        limit = Math.max(1, limit);
        latestEval = latestEval == null ? AgentOpsEvalSnapshot.empty() : latestEval;
        metricMeanings = metricMeanings == null ? Map.of() : Map.copyOf(metricMeanings);
        endpoints = endpoints == null ? Map.of() : Map.copyOf(endpoints);
        risks = risks == null ? List.of() : List.copyOf(risks);
    }
}
