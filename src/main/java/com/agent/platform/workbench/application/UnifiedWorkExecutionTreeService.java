package com.agent.platform.workbench.application;

import com.agent.platform.trace.RuntimeTraceProjector;
import com.agent.platform.trace.TraceRun;
import com.agent.platform.workbench.model.AgentWorkItem;
import com.agent.platform.workbench.model.UnifiedWorkExecutionTree;
import com.agent.platform.workbench.model.WorkLink;
import com.agent.platform.workbench.model.WorkLinkRelation;
import com.agent.platform.workbench.model.WorkLinkType;
import com.agent.platform.workbench.persistence.WorkbenchNotFoundException;
import com.agent.platform.workbench.persistence.WorkbenchStore;
import com.agent.platform.workbench.security.AuthenticatedPrincipal;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;

@Service
public class UnifiedWorkExecutionTreeService {
    private final WorkbenchStore workbench;
    private final RuntimeTraceProjector runtimeTraces;
    private final List<WorkExecutionTreeContributor> contributors;

    public UnifiedWorkExecutionTreeService(WorkbenchStore workbench,
                                           RuntimeTraceProjector runtimeTraces,
                                           List<WorkExecutionTreeContributor> contributors) {
        this.workbench = workbench;
        this.runtimeTraces = runtimeTraces;
        this.contributors = List.copyOf(contributors);
    }

    public UnifiedWorkExecutionTree project(AuthenticatedPrincipal principal, String workItemId) {
        AgentWorkItem work = workbench.findWorkItem(principal, workItemId)
                .orElseThrow(() -> new WorkbenchNotFoundException("work item not found"));
        List<WorkLink> primary = workbench.listLinks(principal, workItemId).stream()
                .filter(link -> link.relation() == WorkLinkRelation.PRIMARY).toList();
        if (primary.isEmpty()) return com.agent.platform.common.BusinessRetirementPolicy.retiredTarget(work.activeExecutionTarget())
                ? retired(work, "") : empty(work);
        if (primary.size() != 1) {
            throw new IllegalStateException("work item has multiple PRIMARY execution links");
        }
        WorkLink link = primary.get(0);
        if (link.linkType() == WorkLinkType.RUN) return runTree(work, link.linkedId());
        List<WorkExecutionTreeContributor> matching = contributors.stream()
                .filter(contributor -> contributor.supports(link.linkType())).toList();
        if (matching.size() > 1) {
            throw new IllegalStateException("multiple execution tree contributors for " + link.linkType());
        }
        if (!matching.isEmpty()) return matching.get(0).project(work, link);
        return link.linkType() == WorkLinkType.INCIDENT || link.linkType() == WorkLinkType.RECOVERY_PLAN
                ? retired(work, link.linkedId()) : empty(work);
    }

    private UnifiedWorkExecutionTree runTree(AgentWorkItem work, String runId) {
        TraceRun trace = runtimeTraces.project(runId).orElse(null);
        String status = trace == null ? work.executionState().name() : trace.status();
        String role = work.activeExecutionTarget();
        String identity = runId == null || runId.isBlank() ? ":1" : runId;
        var metrics = trace == null ? UnifiedWorkExecutionTree.NodeMetrics.empty()
                : new UnifiedWorkExecutionTree.NodeMetrics(
                        number(trace.metrics().get("modelCalls")), number(trace.metrics().get("toolCalls")),
                        trace.estimatedPromptTokens(), trace.estimatedCompletionTokens(),
                        trace.estimatedCost(), trace.durationMs());
        var node = new UnifiedWorkExecutionTree.AgentNode(
                role + ":" + identity, role, "", normalize(runId), 1, 1,
                normalize(status), normalize(status), normalize(work.originalGoal()),
                trace == null ? "" : normalize(trace.failureReason()), "", trace, List.of(), metrics);
        return new UnifiedWorkExecutionTree(work.workItemId(), role, "SINGLE_AGENT", runId,
                null, List.of(node), List.of(), List.of(), Map.of(), List.of(),
                new UnifiedWorkExecutionTree.TreeMetrics(1, metrics.modelCalls(), metrics.toolCalls(),
                        metrics.promptTokens(), metrics.completionTokens(), metrics.estimatedCost(), 0, 0, 0));
    }

    private UnifiedWorkExecutionTree retired(AgentWorkItem work, String executionId) {
        return new UnifiedWorkExecutionTree(work.workItemId(), work.activeExecutionTarget(), "RETIRED", executionId,
                null, List.of(), List.of(), List.of(), Map.of(
                        "readOnly", true, "message", "业务已退役、不能继续执行；仅保留已物化历史。",
                        "historicalExecutionState", work.executionState().name()), List.of(),
                UnifiedWorkExecutionTree.TreeMetrics.empty());
    }

    private UnifiedWorkExecutionTree empty(AgentWorkItem work) {
        return new UnifiedWorkExecutionTree(work.workItemId(), work.activeExecutionTarget(), "PENDING", "",
                null, List.of(), List.of(), List.of(), Map.of(), List.of(),
                UnifiedWorkExecutionTree.TreeMetrics.empty());
    }

    private long number(Object value) {
        if (value instanceof Number number) return Math.max(0, number.longValue());
        try { return Math.max(0, Long.parseLong(String.valueOf(value))); }
        catch (RuntimeException ignored) { return 0; }
    }

    private String normalize(String value) { return value == null ? "" : value.trim(); }
}
