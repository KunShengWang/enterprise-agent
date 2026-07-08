package com.agent.platform.agentops;

import com.agent.platform.eval.EvalReport;
import com.agent.platform.rag.RagRunRecord;
import com.agent.platform.tool.ToolCallRecord;
import com.agent.platform.trace.TraceRun;

import java.time.Instant;
import java.util.List;

public record AgentOpsEvidence(
        Instant generatedAt,
        int limit,
        List<TraceRun> recentTraces,
        List<RagRunRecord> recentRagRuns,
        List<ToolCallRecord> recentToolCalls,
        List<EvalReport> recentEvalReports
) {

    public AgentOpsEvidence {
        generatedAt = generatedAt == null ? Instant.now() : generatedAt;
        limit = Math.max(1, limit);
        recentTraces = recentTraces == null ? List.of() : List.copyOf(recentTraces);
        recentRagRuns = recentRagRuns == null ? List.of() : List.copyOf(recentRagRuns);
        recentToolCalls = recentToolCalls == null ? List.of() : List.copyOf(recentToolCalls);
        recentEvalReports = recentEvalReports == null ? List.of() : List.copyOf(recentEvalReports);
    }
}
