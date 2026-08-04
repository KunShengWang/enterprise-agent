package com.agent.platform.ordercare.incident.application;

import com.agent.platform.ordercare.incident.model.AgentTaskRecord;
import com.agent.platform.ordercare.incident.model.AgentTaskStatus;
import com.agent.platform.ordercare.incident.model.EvidenceGap;
import com.agent.platform.ordercare.incident.model.EvidenceRecord;
import com.agent.platform.ordercare.incident.model.EvidenceSubtype;
import com.agent.platform.ordercare.incident.model.IncidentAgentRole;
import com.agent.platform.ordercare.incident.model.IncidentSnapshot;
import com.agent.platform.ordercare.incident.persistence.AgentTaskStore;
import com.agent.platform.ordercare.incident.persistence.EvidenceStore;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Specialist Tool Handler 和安全补齐逻辑共享的领域任务入口。 */
@Service
public class IncidentSubAgentTaskService {

    private final AgentTaskStore taskStore;
    private final EvidenceStore evidenceStore;
    private final ObjectProvider<IncidentTaskScheduler> schedulerProvider;

    public IncidentSubAgentTaskService(AgentTaskStore taskStore,
                                       EvidenceStore evidenceStore,
                                       ObjectProvider<IncidentTaskScheduler> schedulerProvider) {
        this.taskStore = taskStore;
        this.evidenceStore = evidenceStore;
        this.schedulerProvider = schedulerProvider;
    }

    public DelegationOutcome delegate(IncidentSnapshot snapshot,
                                      String parentRunId,
                                      IncidentAgentRole role,
                                      String objective) {
        AgentTaskRecord requested = newTask(snapshot, parentRunId, role, objective);
        AgentTaskRecord task = taskStore.createOrGet(requested);
        boolean reused = !task.taskId().equals(requested.taskId());
        return new DelegationOutcome(executeOrReuse(task, snapshot), reused);
    }

    public List<AgentTaskRecord> listTasks(String incidentId) {
        return taskStore.listTasks(incidentId);
    }

    public IncidentTaskExecution executionFromStored(AgentTaskRecord task,
                                                      List<EvidenceGap> gaps) {
        List<EvidenceRecord> evidence = evidenceStore.listEvidence(task.incidentId()).stream()
                .filter(item -> task.taskId().equals(item.taskId()))
                .toList();
        boolean successful = !evidence.isEmpty()
                && (task.status() == AgentTaskStatus.SUCCEEDED
                || task.status() == AgentTaskStatus.WAITING_CLARIFICATION);
        return new IncidentTaskExecution(task, evidence, gaps, successful);
    }

    private IncidentTaskExecution executeOrReuse(AgentTaskRecord task, IncidentSnapshot snapshot) {
        if (task.status() == AgentTaskStatus.SUCCEEDED
                || task.status() == AgentTaskStatus.WAITING_CLARIFICATION) {
            return executionFromStored(task, List.of());
        }
        if (task.status() == AgentTaskStatus.CLAIMED || task.status() == AgentTaskStatus.RUNNING) {
            return new IncidentTaskExecution(
                    task, List.of(),
                    List.of(new EvidenceGap("SUB_AGENT_ALREADY_RUNNING", "subagent-tool",
                            "the idempotent specialist task is already running")), false);
        }
        if (task.status().terminal()) {
            return new IncidentTaskExecution(
                    task, List.of(),
                    List.of(new EvidenceGap("SUB_AGENT_TERMINAL_FAILURE", "subagent-tool",
                            task.lastError() == null ? "specialist task already failed" : task.lastError())), false);
        }
        List<IncidentTaskExecution> executions = schedulerProvider.getObject().execute(List.of(task), snapshot);
        return executions.isEmpty()
                ? new IncidentTaskExecution(task, List.of(),
                List.of(new EvidenceGap("SUB_AGENT_NO_RESULT", "subagent-tool", "scheduler returned no result")), false)
                : executions.get(0);
    }

    private AgentTaskRecord newTask(IncidentSnapshot snapshot,
                                    String parentRunId,
                                    IncidentAgentRole role,
                                    String objective) {
        Instant now = Instant.now();
        List<EvidenceSubtype> required = role.allowedEvidenceSubtypes().stream()
                .sorted(Comparator.comparing(Enum::name)).toList();
        return new AgentTaskRecord(
                "task-" + UUID.randomUUID(), snapshot.incidentId(), clientTaskKey(role),
                "INCIDENT_INVESTIGATION", role.name(), normalizeObjective(objective, role),
                100, List.of(), required,
                Map.of(
                        "snapshotId", snapshot.snapshotId(),
                        "scopeHash", snapshot.scopeHash(),
                        "parentRunId", parentRunId,
                        "delegationDepth", 1,
                        "delegatedAsTool", true),
                Map.of(), AgentTaskStatus.PENDING, 0, 2, null, null,
                snapshot.deadlineAt(), null, null, 0, null, "", 0, now, now);
    }

    private String normalizeObjective(String objective, IncidentAgentRole role) {
        String value = objective == null ? "" : objective.trim();
        if (value.isBlank()) {
            value = "在冻结事故范围内收集并核对 " + role.name() + " 的只读权威事实";
        }
        return value.length() <= 500 ? value : value.substring(0, 500);
    }

    private String clientTaskKey(IncidentAgentRole role) {
        return "subagent-" + role.name().toLowerCase(java.util.Locale.ROOT);
    }

    public record DelegationOutcome(IncidentTaskExecution execution, boolean reused) { }
}
