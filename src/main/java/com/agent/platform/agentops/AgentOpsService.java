package com.agent.platform.agentops;

import com.agent.platform.eval.EvalReport;
import com.agent.platform.eval.EvalReportRecorder;
import com.agent.platform.rag.RagCacheOperations;
import com.agent.platform.rag.RagCacheStats;
import com.agent.platform.rag.RagRunRecorder;
import com.agent.platform.rag.RagRunStats;
import com.agent.platform.tool.ToolRunRecorder;
import com.agent.platform.tool.ToolRunStats;
import com.agent.platform.trace.TraceRecorder;
import com.agent.platform.trace.TraceRunStats;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
public class AgentOpsService {

    private final TraceRecorder traceRecorder;

    private final RagRunRecorder ragRunRecorder;

    private final ToolRunRecorder toolRunRecorder;

    private final EvalReportRecorder evalReportRecorder;

    private final ObjectProvider<RagCacheOperations> ragCacheOperationsProvider;

    public AgentOpsService(TraceRecorder traceRecorder,
                           RagRunRecorder ragRunRecorder,
                           ToolRunRecorder toolRunRecorder,
                           EvalReportRecorder evalReportRecorder,
                           ObjectProvider<RagCacheOperations> ragCacheOperationsProvider) {
        this.traceRecorder = traceRecorder;
        this.ragRunRecorder = ragRunRecorder;
        this.toolRunRecorder = toolRunRecorder;
        this.evalReportRecorder = evalReportRecorder;
        this.ragCacheOperationsProvider = ragCacheOperationsProvider;
    }

    public AgentOpsSummary summary(int limit) {
        int effectiveLimit = Math.max(1, limit);
        TraceRunStats traceStats = traceRecorder.stats(effectiveLimit);
        RagRunStats ragStats = ragRunRecorder.stats(effectiveLimit);
        ToolRunStats toolStats = toolRunRecorder.stats();
        RagCacheStats cacheStats = cacheStats();
        AgentOpsEvalSnapshot latestEval = evalReportRecorder.recent(1).stream()
                .findFirst()
                .map(AgentOpsEvalSnapshot::from)
                .orElseGet(AgentOpsEvalSnapshot::empty);
        return new AgentOpsSummary(
                Instant.now(),
                effectiveLimit,
                traceStats,
                ragStats,
                cacheStats,
                toolStats,
                latestEval,
                metricMeanings(),
                endpoints(),
                risks(traceStats, ragStats, toolStats, latestEval, cacheStats)
        );
    }

    public AgentOpsEvidence evidence(int limit) {
        int effectiveLimit = Math.max(1, limit);
        return new AgentOpsEvidence(
                Instant.now(),
                effectiveLimit,
                traceRecorder.recentRuns(effectiveLimit),
                ragRunRecorder.recent(effectiveLimit),
                toolRunRecorder.recent(effectiveLimit),
                evalReportRecorder.recent(effectiveLimit)
        );
    }

    private RagCacheStats cacheStats() {
        RagCacheOperations operations = ragCacheOperationsProvider.getIfAvailable();
        if (operations == null) {
            return new RagCacheStats(false, 0, 0, 0, 0, 0, 0);
        }
        return operations.cacheStats();
    }

    private Map<String, String> metricMeanings() {
        Map<String, String> meanings = new LinkedHashMap<>();
        meanings.put("traceStats.totalRuns", "最近 limit 次 Agent Run 数量。");
        meanings.put("traceStats.averageDurationMs", "最近 limit 次 Agent Run 平均耗时。");
        meanings.put("traceStats.estimatedPromptTokens", "最近 limit 次 Agent Run 累计 prompt tokens，优先使用模型返回 usage，缺失时退化为估算。");
        meanings.put("traceStats.estimatedCompletionTokens", "最近 limit 次 Agent Run 累计 completion tokens，优先使用模型返回 usage，缺失时退化为估算。");
        meanings.put("traceStats.estimatedCost", "基于 token 的成本估算，方便做成本趋势观测。");
        meanings.put("ragStats.hitRate", "RAG 运行命中率，表示检索结果是否达到 enoughEvidence。");
        meanings.put("ragStats.averageRetrievedDocuments", "平均召回 chunk 数，用于观察 TopK、阈值和召回稳定性。");
        meanings.put("ragCacheStats.hitRate", "RAG 缓存命中率，用于说明重复问题的缓存收益。");
        meanings.put("toolStats.successRate", "工具调用成功率，用于观察工具可靠性和重试/降级效果。");
        meanings.put("latestEval.passRate", "最近一次 Agent Eval 的整体通过率。");
        meanings.put("latestEval.ragUsageAccuracy", "评测集中该走 RAG 的问题是否真的走了 RAG。");
        meanings.put("latestEval.toolCallSuccessRate", "评测集中需要工具的问题是否正确完成工具调用。");
        meanings.put("latestEval.groundednessRate", "回答是否基于 RAG 或工具证据。");
        meanings.put("latestEval.hallucinationRiskRate", "评测集中未 grounded 的比例，可作为幻觉风险代理指标。");
        meanings.put("latestEval.adversarialPassRate", "对抗样本通过率，用于观察 Guardrail 鲁棒性。");
        return meanings;
    }

    private Map<String, String> endpoints() {
        Map<String, String> endpoints = new LinkedHashMap<>();
        endpoints.put("agentopsSummary", "GET /api/agent/ops/summary?limit=100");
        endpoints.put("agentopsEvidence", "GET /api/agent/ops/evidence?limit=20");
        endpoints.put("traceStats", "GET /api/agent/traces/stats?limit=100");
        endpoints.put("traceReplay", "GET /api/agent/traces/{traceId}/replay");
        endpoints.put("ragEval", "POST /api/agent/rag/eval");
        endpoints.put("ragRunStats", "GET /api/agent/rag/runs/stats?limit=100");
        endpoints.put("ragCacheStats", "GET /api/agent/rag/cache/stats");
        endpoints.put("toolRunStats", "GET /api/agent/tools/runs/stats");
        endpoints.put("agentEval", "POST /api/agent/evals/regression");
        endpoints.put("adversarialEval", "POST /api/agent/evals/adversarial");
        return endpoints;
    }

    private List<String> risks(TraceRunStats traceStats,
                               RagRunStats ragStats,
                               ToolRunStats toolStats,
                               AgentOpsEvalSnapshot latestEval,
                               RagCacheStats cacheStats) {
        List<String> risks = new ArrayList<>();
        if (traceStats.totalRuns() == 0) {
            risks.add("还没有 Agent Run 样本，Trace/Token/耗时指标暂时没有解释价值。");
        }
        if (traceStats.failedRuns() > 0 || traceStats.blockedRuns() > 0) {
            risks.add("最近窗口存在失败或阻断的 Agent Run，需要结合 trace replay 排查。");
        }
        if (traceStats.estimatedPromptTokens() + traceStats.estimatedCompletionTokens() == 0) {
            risks.add("当前窗口没有 token usage，可能尚未完成真实模型调用或模型未返回 usage。");
        }
        if (ragStats.totalRuns() == 0) {
            risks.add("还没有 RAG Run 样本，RAG 命中率需要先通过对话或 /api/agent/rag/search 产生数据。");
        }
        if (ragStats.totalRuns() > 0 && ragStats.hitRate() < 0.6) {
            risks.add("RAG 命中率低于 0.6，优先检查切块、TopK、相似度阈值和 query rewrite。");
        }
        if (toolStats.totalCalls() > 0 && toolStats.successRate() < 0.9) {
            risks.add("工具调用成功率低于 0.9，需要检查工具参数、重试和降级。");
        }
        if (!latestEval.available()) {
            risks.add("还没有 Eval 报告，无法证明回归评测、groundedness 和对抗测试结果。");
        }
        if (latestEval.available() && latestEval.hallucinationRiskRate() > 0.2) {
            risks.add("最近 Eval 的幻觉风险超过 0.2，应优先优化证据过滤和 Prompt 约束。");
        }
        if (cacheStats.enabled() && cacheStats.hits() + cacheStats.misses() > 0 && cacheStats.hitRate() < 0.3) {
            risks.add("RAG 缓存命中率较低，说明问题重复度低或缓存 key 设计需要优化。");
        }
        return risks;
    }
}
