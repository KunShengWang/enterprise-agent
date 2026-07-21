package com.agent.platform.workbench.application;

import com.agent.platform.ordercare.incident.application.IncidentTraceProjector;
import com.agent.platform.ordercare.incident.model.AgentTaskRecord;
import com.agent.platform.ordercare.incident.model.AgentTaskStatus;
import com.agent.platform.ordercare.incident.model.EvidenceRecord;
import com.agent.platform.ordercare.incident.model.IncidentAggregate;
import com.agent.platform.ordercare.incident.model.IncidentTrace;
import com.agent.platform.ordercare.incident.model.TaskEventRecord;
import com.agent.platform.ordercare.incident.model.TaskEventType;
import com.agent.platform.ordercare.incident.persistence.IncidentStore;
import com.agent.platform.ordercare.incident.recovery.model.IncidentRecoveryPlanRecord;
import com.agent.platform.ordercare.incident.recovery.persistence.IncidentRecoveryPlanStore;
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

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Service
public class UnifiedWorkExecutionTreeService {

    private static final Pattern ATTEMPT = Pattern.compile(":ATTEMPT_(\\d+)$");

    private final WorkbenchStore workbench;
    private final IncidentStore incidents;
    private final IncidentTraceProjector incidentTraces;
    private final IncidentRecoveryPlanStore recoveryPlans;
    private final RuntimeTraceProjector runtimeTraces;

    public UnifiedWorkExecutionTreeService(WorkbenchStore workbench,
                                           IncidentStore incidents,
                                           IncidentTraceProjector incidentTraces,
                                           IncidentRecoveryPlanStore recoveryPlans,
                                           RuntimeTraceProjector runtimeTraces) {
        this.workbench = workbench;
        this.incidents = incidents;
        this.incidentTraces = incidentTraces;
        this.recoveryPlans = recoveryPlans;
        this.runtimeTraces = runtimeTraces;
    }

    public UnifiedWorkExecutionTree project(AuthenticatedPrincipal principal, String workItemId) {
        AgentWorkItem workItem = workbench.findWorkItem(principal, workItemId)
                .orElseThrow(() -> new WorkbenchNotFoundException("work item not found"));
        List<WorkLink> links = workbench.listLinks(principal, workItemId);
        List<WorkLink> primary = links.stream()
                .filter(link -> link.relation() == WorkLinkRelation.PRIMARY)
                .toList();
        if (primary.isEmpty()) {
            return empty(workItem);
        }
        if (primary.size() != 1) {
            throw new IllegalStateException("work item has multiple PRIMARY execution links");
        }
        WorkLink link = primary.get(0);
        return switch (link.linkType()) {
            case INCIDENT -> incidentTree(workItem, link.linkedId());
            case RECOVERY_PLAN -> recoveryPlanTree(workItem, link.linkedId());
            case RUN -> runTree(workItem, link.linkedId());
            default -> empty(workItem);
        };
    }

    private UnifiedWorkExecutionTree incidentTree(AgentWorkItem workItem, String incidentId) {
        IncidentAggregate aggregate = incidents.findAggregate(incidentId, 10_000)
                .orElseThrow(() -> new WorkbenchNotFoundException("linked incident not found"));
        IncidentTrace trace = incidentTraces.project(incidentId)
                .orElseThrow(() -> new WorkbenchNotFoundException("linked incident trace not found"));
        List<IncidentRecoveryPlanRecord> plans = recoveryPlans.listByIncident(incidentId);
        List<UnifiedWorkExecutionTree.AgentNode> nodes = incidentNodes(aggregate, trace, plans);
        List<UnifiedWorkExecutionTree.ConflictView> conflicts = conflicts(aggregate.events());
        UnifiedWorkExecutionTree.CoordinatorNode coordinator = new UnifiedWorkExecutionTree.CoordinatorNode(
                trace.syntheticCoordinatorSpan().spanId(),
                "Deterministic Incident Coordinator",
                trace.syntheticCoordinatorSpan().status().name(),
                true,
                0,
                trace.syntheticCoordinatorSpan());
        return tree(workItem, "MULTI_AGENT", incidentId, coordinator, nodes,
                aggregate.evidence(), conflicts, aggregate.incident().assessment(), plans);
    }

    private List<UnifiedWorkExecutionTree.AgentNode> incidentNodes(IncidentAggregate aggregate,
                                                                   IncidentTrace trace,
                                                                   List<IncidentRecoveryPlanRecord> plans) {
        List<UnifiedWorkExecutionTree.AgentNode> nodes = new ArrayList<>();
        Set<String> representedRuns = new HashSet<>();
        Map<String, AgentTaskRecord> tasks = new LinkedHashMap<>();
        aggregate.tasks().forEach(task -> tasks.put(task.taskId(), task));
        for (IncidentTrace.ChildRunTrace child : trace.childRuns()) {
            AgentTaskRecord task = tasks.get(child.taskId());
            int attempt = attempt(child.runRole(), task);
            nodes.add(node(child.runRole(), child.taskId(), child.trace().traceId(), attempt,
                    task == null ? 1 : task.maxAttempts(), authoritativeStatus(child.trace(), task),
                    objective(child.runRole(), task), authoritativeError(child.trace(), task),
                    runtimeWarning(child.trace(), task), child.trace(),
                    evidenceFor(aggregate.evidence(), child.trace().traceId(), child.taskId())));
            representedRuns.add(child.trace().traceId());
        }
        addMissingRoleNode(nodes, representedRuns, "COMMANDER", "",
                aggregate.incident().commanderRunId(), 1, 1, aggregate.incident().status().name(),
                objective("COMMANDER", null), "", aggregate.evidence());
        for (AgentTaskRecord task : aggregate.tasks()) {
            if (hasText(task.firstChildRunId()) && !task.firstChildRunId().equals(task.childRunId())) {
                addMissingRoleNode(nodes, representedRuns, "SPECIALIST:" + task.role() + ":ATTEMPT_1",
                        task.taskId(), task.firstChildRunId(), 1, task.maxAttempts(), "TRACE_UNAVAILABLE",
                        task.objective(), task.lastError(), aggregate.evidence());
            }
            addMissingRoleNode(nodes, representedRuns,
                    "SPECIALIST:" + task.role() + ":ATTEMPT_" + (task.attempt() + 1),
                    task.taskId(), task.childRunId(), task.attempt() + 1, task.maxAttempts(),
                    task.status().name(), task.objective(), task.lastError(), aggregate.evidence(), task);
        }
        addMissingRoleNode(nodes, representedRuns, "REVIEWER", "",
                aggregate.incident().reviewerRunId(), 1, 1, aggregate.incident().status().name(),
                objective("REVIEWER", null), "", aggregate.evidence());
        for (IncidentRecoveryPlanRecord plan : plans) {
            addMissingRoleNode(nodes, representedRuns, "RECOVERY_PLANNER", plan.planId(),
                    plan.plannerRunId(), 1, 1, plan.status().name(),
                    objective("RECOVERY_PLANNER", null), String.join("; ", plan.validationErrors()),
                    aggregate.evidence());
        }
        nodes.sort(Comparator.comparingInt((UnifiedWorkExecutionTree.AgentNode node) -> roleRank(node.role()))
                .thenComparing(UnifiedWorkExecutionTree.AgentNode::taskId)
                .thenComparingInt(UnifiedWorkExecutionTree.AgentNode::attempt));
        return List.copyOf(nodes);
    }

    private void addMissingRoleNode(List<UnifiedWorkExecutionTree.AgentNode> nodes,
                                    Set<String> representedRuns,
                                    String role,
                                    String taskId,
                                    String runId,
                                    int attempt,
                                    int maxAttempts,
                                    String fallbackStatus,
                                    String objective,
                                    String fallbackError,
                                    List<EvidenceRecord> evidence) {
        addMissingRoleNode(nodes, representedRuns, role, taskId, runId, attempt, maxAttempts,
                fallbackStatus, objective, fallbackError, evidence, null);
    }

    private void addMissingRoleNode(List<UnifiedWorkExecutionTree.AgentNode> nodes,
                                    Set<String> representedRuns,
                                    String role,
                                    String taskId,
                                    String runId,
                                    int attempt,
                                    int maxAttempts,
                                    String fallbackStatus,
                                    String objective,
                                    String fallbackError,
                                    List<EvidenceRecord> evidence,
                                    AgentTaskRecord authoritativeTask) {
        if (!hasText(runId)) {
            if (hasText(taskId)) {
                nodes.add(node(role, taskId, "", attempt, maxAttempts, fallbackStatus,
                        objective, fallbackError, null, evidenceFor(evidence, "", taskId)));
            }
            return;
        }
        if (representedRuns.contains(runId)) return;
        TraceRun trace = runtimeTraces.project(runId).orElse(null);
        nodes.add(node(role, taskId, runId, attempt, maxAttempts,
                trace == null ? fallbackStatus : authoritativeStatus(trace, authoritativeTask), objective,
                trace == null ? normalize(fallbackError) : authoritativeError(trace, authoritativeTask),
                trace == null ? "" : runtimeWarning(trace, authoritativeTask), trace,
                evidenceFor(evidence, runId, taskId)));
        representedRuns.add(runId);
    }

    private UnifiedWorkExecutionTree recoveryPlanTree(AgentWorkItem workItem, String planId) {
        IncidentRecoveryPlanRecord plan = recoveryPlans.find(planId)
                .orElseThrow(() -> new WorkbenchNotFoundException("linked recovery plan not found"));
        TraceRun trace = runtimeTraces.project(plan.plannerRunId()).orElse(null);
        IncidentAggregate aggregate = incidents.findAggregate(plan.incidentId(), 10_000).orElse(null);
        Set<String> evidenceIds = plan.items().stream()
                .flatMap(item -> item.evidenceIds().stream()).collect(java.util.stream.Collectors.toSet());
        List<EvidenceRecord> evidence = aggregate == null ? List.of() : aggregate.evidence().stream()
                .filter(item -> evidenceIds.contains(item.evidenceId())).toList();
        Set<String> conflictIds = plan.items().stream()
                .flatMap(item -> item.conflictIds().stream()).collect(java.util.stream.Collectors.toSet());
        List<UnifiedWorkExecutionTree.ConflictView> conflicts = aggregate == null ? List.of()
                : conflicts(aggregate.events()).stream()
                        .filter(item -> conflictIds.contains(item.conflictId())).toList();
        UnifiedWorkExecutionTree.AgentNode planner = node(
                "RECOVERY_PLANNER", plan.planId(), plan.plannerRunId(), 1, 1,
                trace == null ? plan.status().name() : trace.status(),
                objective("RECOVERY_PLANNER", null),
                trace == null ? String.join("; ", plan.validationErrors()) : error(trace, null),
                trace, evidence);
        return tree(workItem, "RECOVERY_PLAN", plan.planId(), null, List.of(planner), evidence,
                conflicts, aggregate == null ? Map.of() : aggregate.incident().assessment(), List.of(plan));
    }

    private UnifiedWorkExecutionTree runTree(AgentWorkItem workItem, String runId) {
        TraceRun trace = runtimeTraces.project(runId).orElse(null);
        UnifiedWorkExecutionTree.AgentNode run = node(
                workItem.activeExecutionTarget(), "", runId, 1, 1,
                trace == null ? workItem.executionState().name() : trace.status(),
                workItem.originalGoal(), trace == null ? "" : trace.failureReason(), trace, List.of());
        return tree(workItem, "SINGLE_AGENT", runId, null, List.of(run),
                List.of(), List.of(), Map.of(), List.of());
    }

    private UnifiedWorkExecutionTree empty(AgentWorkItem workItem) {
        return tree(workItem, "PENDING", "", null, List.of(),
                List.of(), List.of(), Map.of(), List.of());
    }

    private UnifiedWorkExecutionTree tree(AgentWorkItem workItem,
                                          String treeType,
                                          String executionId,
                                          UnifiedWorkExecutionTree.CoordinatorNode coordinator,
                                          List<UnifiedWorkExecutionTree.AgentNode> nodes,
                                          List<EvidenceRecord> evidence,
                                          List<UnifiedWorkExecutionTree.ConflictView> conflicts,
                                          Map<String, Object> assessment,
                                          List<IncidentRecoveryPlanRecord> plans) {
        long modelCalls = nodes.stream().map(UnifiedWorkExecutionTree.AgentNode::metrics)
                .mapToLong(UnifiedWorkExecutionTree.NodeMetrics::modelCalls).sum();
        long toolCalls = nodes.stream().map(UnifiedWorkExecutionTree.AgentNode::metrics)
                .mapToLong(UnifiedWorkExecutionTree.NodeMetrics::toolCalls).sum();
        long promptTokens = nodes.stream().map(UnifiedWorkExecutionTree.AgentNode::metrics)
                .mapToLong(UnifiedWorkExecutionTree.NodeMetrics::promptTokens).sum();
        long completionTokens = nodes.stream().map(UnifiedWorkExecutionTree.AgentNode::metrics)
                .mapToLong(UnifiedWorkExecutionTree.NodeMetrics::completionTokens).sum();
        double cost = nodes.stream().map(UnifiedWorkExecutionTree.AgentNode::metrics)
                .mapToDouble(UnifiedWorkExecutionTree.NodeMetrics::estimatedCost).sum();
        return new UnifiedWorkExecutionTree(
                workItem.workItemId(), workItem.activeExecutionTarget(), treeType, executionId,
                coordinator, nodes, evidence, conflicts, assessment, plans,
                new UnifiedWorkExecutionTree.TreeMetrics(
                        nodes.size(), modelCalls, toolCalls, promptTokens, completionTokens, cost,
                        evidence.size(), conflicts.size(), 0));
    }

    private UnifiedWorkExecutionTree.AgentNode node(String role,
                                                     String taskId,
                                                     String runId,
                                                     int attempt,
                                                     int maxAttempts,
                                                     String status,
                                                     String objective,
                                                     String error,
                                                     TraceRun trace,
                                                     List<EvidenceRecord> evidence) {
        return node(role, taskId, runId, attempt, maxAttempts, status, objective, error, "", trace, evidence);
    }

    private UnifiedWorkExecutionTree.AgentNode node(String role,
                                                     String taskId,
                                                     String runId,
                                                     int attempt,
                                                     int maxAttempts,
                                                     String status,
                                                     String objective,
                                                     String error,
                                                     String runtimeWarning,
                                                     TraceRun trace,
                                                     List<EvidenceRecord> evidence) {
        String identity = hasText(runId) ? runId : normalize(taskId) + ":" + Math.max(1, attempt);
        return new UnifiedWorkExecutionTree.AgentNode(
                role + ":" + identity, role, normalize(taskId), normalize(runId),
                Math.max(1, attempt), Math.max(1, maxAttempts), normalize(status),
                trace == null ? normalize(status) : normalize(trace.status()),
                normalize(objective), normalize(error), normalize(runtimeWarning), trace, evidence, metrics(trace));
    }

    private String authoritativeStatus(TraceRun trace, AgentTaskRecord task) {
        if (isCurrentTaskRun(trace, task)) return task.status().name();
        return trace.status();
    }

    private String authoritativeError(TraceRun trace, AgentTaskRecord task) {
        if (!isCurrentTaskRun(trace, task)) return error(trace, task);
        if (task.status() == AgentTaskStatus.SUCCEEDED) return "";
        return hasText(task.lastError()) ? task.lastError() : normalize(trace.failureReason());
    }

    private String runtimeWarning(TraceRun trace, AgentTaskRecord task) {
        if (!isCurrentTaskRun(trace, task)
                || task.status() != AgentTaskStatus.SUCCEEDED
                || !"FAILED".equalsIgnoreCase(trace.status())) return "";
        if (Boolean.TRUE.equals(task.outputSummary().get("recoveredFromDuplicateToolRequest"))) {
            return "Runtime blocked a duplicate tool request (" + normalize(trace.failureReason())
                    + "); the task recovered from the first persisted read-only result.";
        }
        return "Runtime ended as FAILED (" + normalize(trace.failureReason())
                + "), but the authoritative task completed from persisted evidence.";
    }

    private boolean isCurrentTaskRun(TraceRun trace, AgentTaskRecord task) {
        return trace != null && task != null && hasText(task.childRunId())
                && task.childRunId().equals(trace.traceId());
    }

    private UnifiedWorkExecutionTree.NodeMetrics metrics(TraceRun trace) {
        if (trace == null) return UnifiedWorkExecutionTree.NodeMetrics.empty();
        return new UnifiedWorkExecutionTree.NodeMetrics(
                number(trace.metrics().get("modelCalls")),
                number(trace.metrics().get("toolCalls")),
                trace.estimatedPromptTokens(), trace.estimatedCompletionTokens(),
                trace.estimatedCost(), trace.durationMs());
    }

    private List<EvidenceRecord> evidenceFor(List<EvidenceRecord> evidence, String runId, String taskId) {
        List<EvidenceRecord> exact = evidence.stream()
                .filter(item -> hasText(runId) && runId.equals(item.childRunId())).toList();
        if (!exact.isEmpty()) return exact;
        return evidence.stream().filter(item -> hasText(taskId) && taskId.equals(item.taskId())).toList();
    }

    private List<UnifiedWorkExecutionTree.ConflictView> conflicts(List<TaskEventRecord> events) {
        return events.stream()
                .filter(event -> event.eventType() == TaskEventType.EVIDENCE_CONFLICT_DETECTED)
                .map(this::conflict)
                .toList();
    }

    private UnifiedWorkExecutionTree.ConflictView conflict(TaskEventRecord event) {
        Map<String, Object> payload = event.payload();
        return new UnifiedWorkExecutionTree.ConflictView(
                text(payload, "conflictId", event.eventId()),
                text(payload, "conflictType", "UNKNOWN"),
                text(payload, "severity", "UNKNOWN"),
                text(payload, "metricKey", ""),
                strings(payload.get("relatedEvidenceIds")),
                text(payload, "status", "OPEN"),
                payload);
    }

    private int attempt(String role, AgentTaskRecord task) {
        Matcher matcher = ATTEMPT.matcher(role == null ? "" : role);
        if (matcher.find()) return Integer.parseInt(matcher.group(1));
        return task == null ? 1 : task.attempt() + 1;
    }

    private String objective(String role, AgentTaskRecord task) {
        if (task != null && hasText(task.objective())) return task.objective();
        if ("COMMANDER".equals(role)) return "Freeze scope and create a bounded delegation plan";
        if ("REVIEWER".equals(role)) return "Review evidence and Java conflicts to assemble the final assessment";
        if ("RECOVERY_PLANNER".equals(role)) return "Create evidence-bound recovery proposals without execution authority";
        return "Execute the assigned read-only investigation task";
    }

    private String error(TraceRun trace, AgentTaskRecord task) {
        if (trace != null && hasText(trace.failureReason())) return trace.failureReason();
        return task == null ? "" : normalize(task.lastError());
    }

    private long number(Object value) {
        if (value instanceof Number number) return Math.max(0, number.longValue());
        try { return Math.max(0, Long.parseLong(String.valueOf(value))); }
        catch (RuntimeException ignored) { return 0; }
    }

    private List<String> strings(Object value) {
        if (!(value instanceof List<?> list)) return List.of();
        return list.stream().map(String::valueOf).filter(this::hasText).distinct().toList();
    }

    private String text(Map<String, Object> payload, String key, String fallback) {
        Object value = payload.get(key);
        return value == null || String.valueOf(value).isBlank() ? fallback : String.valueOf(value);
    }

    private int roleRank(String role) {
        if ("COMMANDER".equals(role)) return 0;
        if (role != null && role.startsWith("SPECIALIST:")) return 1;
        if ("REVIEWER".equals(role)) return 2;
        if ("RECOVERY_PLANNER".equals(role)) return 3;
        return 4;
    }

    private boolean hasText(String value) { return value != null && !value.isBlank(); }
    private String normalize(String value) { return value == null ? "" : value.trim(); }
}
