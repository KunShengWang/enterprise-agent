package com.agent.platform.workbench.model;

import com.agent.platform.ordercare.incident.model.EvidenceRecord;
import com.agent.platform.ordercare.incident.recovery.model.IncidentRecoveryPlanRecord;
import com.agent.platform.trace.TraceRun;
import com.agent.platform.trace.TraceSpan;

import java.util.List;
import java.util.Map;

public record UnifiedWorkExecutionTree(
        String workItemId,
        String executionTarget,
        String treeType,
        String executionId,
        CoordinatorNode coordinator,
        List<AgentNode> agents,
        List<EvidenceRecord> evidence,
        List<ConflictView> conflicts,
        Map<String, Object> assessment,
        List<IncidentRecoveryPlanRecord> recoveryPlans,
        TreeMetrics metrics
) {
    public UnifiedWorkExecutionTree {
        agents = agents == null ? List.of() : List.copyOf(agents);
        evidence = evidence == null ? List.of() : List.copyOf(evidence);
        conflicts = conflicts == null ? List.of() : List.copyOf(conflicts);
        assessment = assessment == null ? Map.of() : Map.copyOf(assessment);
        recoveryPlans = recoveryPlans == null ? List.of() : List.copyOf(recoveryPlans);
        metrics = metrics == null ? TreeMetrics.empty() : metrics;
    }

    public record CoordinatorNode(
            String nodeId,
            String label,
            String status,
            boolean synthetic,
            int modelCalls,
            TraceSpan span
    ) { }

    public record AgentNode(
            String nodeId,
            String role,
            String taskId,
            String runId,
            int attempt,
            int maxAttempts,
            String status,
            String objective,
            String error,
            TraceRun trace,
            List<EvidenceRecord> evidence,
            NodeMetrics metrics
    ) {
        public AgentNode {
            evidence = evidence == null ? List.of() : List.copyOf(evidence);
            metrics = metrics == null ? NodeMetrics.empty() : metrics;
        }
    }

    public record ConflictView(
            String conflictId,
            String conflictType,
            String severity,
            String metricKey,
            List<String> evidenceIds,
            String status,
            Map<String, Object> details
    ) {
        public ConflictView {
            evidenceIds = evidenceIds == null ? List.of() : List.copyOf(evidenceIds);
            details = details == null ? Map.of() : Map.copyOf(details);
        }
    }

    public record NodeMetrics(
            long modelCalls,
            long toolCalls,
            long promptTokens,
            long completionTokens,
            double estimatedCost,
            long durationMs
    ) {
        public static NodeMetrics empty() { return new NodeMetrics(0, 0, 0, 0, 0, 0); }
    }

    public record TreeMetrics(
            int agentNodes,
            long modelCalls,
            long toolCalls,
            long promptTokens,
            long completionTokens,
            double estimatedCost,
            long evidenceCount,
            long conflictCount,
            int syntheticCoordinatorModelCalls
    ) {
        public static TreeMetrics empty() {
            return new TreeMetrics(0, 0, 0, 0, 0, 0, 0, 0, 0);
        }
    }
}
