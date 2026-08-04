package com.agent.platform.ordercare.incident.tool;

import com.agent.platform.ordercare.incident.application.IncidentEvidenceProjector;
import com.agent.platform.ordercare.incident.application.IncidentReviewerAgentService;
import com.agent.platform.ordercare.incident.model.AgentTaskStatus;
import com.agent.platform.ordercare.incident.model.EvidenceConflict;
import com.agent.platform.ordercare.incident.model.EvidenceGap;
import com.agent.platform.ordercare.incident.model.IncidentRecord;
import com.agent.platform.ordercare.incident.model.IncidentStatus;
import com.agent.platform.ordercare.incident.model.TaskEventType;
import com.agent.platform.ordercare.incident.persistence.AgentTaskStore;
import com.agent.platform.ordercare.incident.persistence.EvidenceStore;
import com.agent.platform.ordercare.incident.persistence.IncidentStore;
import com.agent.platform.ordercare.incident.persistence.TaskEventStore;
import com.agent.platform.runtime.ToolExecutionStore;
import com.agent.platform.tool.ContextualToolHandler;
import com.agent.platform.tool.ToolCallRequest;
import com.agent.platform.tool.ToolCallResult;
import com.agent.platform.tool.ToolExecutionContext;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 只有证据汇合和 Java 一致性检查完成后才允许调用 Reviewer Child Agent。 */
@Component
public class IncidentReviewerToolHandler implements ContextualToolHandler {

    private final IncidentStore incidentStore;
    private final AgentTaskStore taskStore;
    private final EvidenceStore evidenceStore;
    private final TaskEventStore eventStore;
    private final ToolExecutionStore toolExecutionStore;
    private final IncidentEvidenceProjector evidenceProjector;
    private final IncidentReviewerAgentService reviewerService;
    private final ObjectMapper objectMapper;

    public IncidentReviewerToolHandler(IncidentStore incidentStore,
                                       AgentTaskStore taskStore,
                                       EvidenceStore evidenceStore,
                                       TaskEventStore eventStore,
                                       ToolExecutionStore toolExecutionStore,
                                       IncidentEvidenceProjector evidenceProjector,
                                       IncidentReviewerAgentService reviewerService,
                                       ObjectMapper objectMapper) {
        this.incidentStore = incidentStore;
        this.taskStore = taskStore;
        this.evidenceStore = evidenceStore;
        this.eventStore = eventStore;
        this.toolExecutionStore = toolExecutionStore;
        this.evidenceProjector = evidenceProjector;
        this.reviewerService = reviewerService;
        this.objectMapper = objectMapper;
    }

    @Override
    public boolean supports(String toolName) {
        return IncidentToolCatalog.REVIEW_INCIDENT_EVIDENCE.equals(toolName);
    }

    @Override
    public ToolCallResult execute(ToolCallRequest request, ToolExecutionContext context) {
        if (context == null || context.runId().isBlank()
                || !"COMMANDER".equals(context.attribute("runRole"))) {
            return failure(request, "trusted Commander execution context is required",
                    "UNTRUSTED_PARENT_CONTEXT");
        }
        if (context.intAttribute("delegationDepth", 0) >= 1) {
            return failure(request, "maximum delegation depth exceeded", "DELEGATION_DEPTH_EXCEEDED");
        }
        IncidentRecord incident = incidentStore.find(context.attribute("incidentId")).orElse(null);
        if (incident == null) {
            return failure(request, "incident not found in trusted execution context", "INCIDENT_NOT_FOUND");
        }
        if (incident.status() != IncidentStatus.REVIEWING) {
            return failure(request,
                    "Reviewer is gated until Java consistency checking reaches REVIEWING; current="
                            + incident.status(),
                    "REVIEW_STATE_GATE_REJECTED");
        }

        var tasks = taskStore.listTasks(incident.incidentId());
        boolean active = tasks.stream().anyMatch(task -> task.status() == AgentTaskStatus.PENDING
                || task.status() == AgentTaskStatus.CLAIMED
                || task.status() == AgentTaskStatus.RUNNING
                || task.status() == AgentTaskStatus.RETRY_PENDING);
        if (tasks.isEmpty() || active) {
            return failure(request, "all required Specialist tasks must reach a stable result first",
                    "SPECIALISTS_NOT_JOINED");
        }

        List<EvidenceConflict> conflicts = eventStore.loadEventsAfter(
                        incident.incidentId(), 0, 10_000).stream()
                .filter(event -> event.eventType() == TaskEventType.EVIDENCE_CONFLICT_DETECTED)
                .map(event -> objectMapper.convertValue(event.payload(), EvidenceConflict.class))
                .toList();
        List<EvidenceGap> gaps = new ArrayList<>();
        tasks.stream()
                .filter(task -> task.status() == AgentTaskStatus.FAILED
                        || task.status() == AgentTaskStatus.TIMED_OUT
                        || task.status() == AgentTaskStatus.CANCELLED)
                .forEach(task -> gaps.add(new EvidenceGap(
                        "SPECIALIST_FAILED", task.role(),
                        task.lastError() == null ? "specialist did not complete" : task.lastError())));
        tasks.stream()
                .filter(task -> task.childRunId() != null && !task.childRunId().isBlank())
                .forEach(task -> gaps.addAll(evidenceProjector.projectGaps(
                        toolExecutionStore.findByRun(task.childRunId()))));

        try {
            IncidentReviewerAgentService.ReviewAgentOutcome outcome = reviewerService.review(
                    incident, evidenceStore.listEvidence(incident.incidentId()), conflicts, List.copyOf(gaps));
            LinkedHashMap<String, Object> metadata = new LinkedHashMap<>();
            metadata.put("provider", "floworder-incident-subagent");
            metadata.put("executionKind", "SUB_AGENT");
            metadata.put("readOnly", true);
            metadata.put("retryable", false);
            metadata.put("incidentId", incident.incidentId());
            metadata.put("reviewerRunId", outcome.reviewerRunId());
            metadata.put("reusedReviewer", outcome.reused());
            metadata.put("stateGate", IncidentStatus.REVIEWING.name());
            return new ToolCallResult(
                    request.toolName(), true,
                    objectMapper.writeValueAsString(outcome.draft()), "", Map.copyOf(metadata));
        }
        catch (RuntimeException exception) {
            return new ToolCallResult(
                    request.toolName(), false, "",
                    "reviewer sub-agent failed: " + exception.getClass().getSimpleName(),
                    Map.of(
                            "provider", "floworder-incident-subagent",
                            "executionKind", "SUB_AGENT",
                            "readOnly", true,
                            // Reviewer 失败由 Orchestrator 使用同一 Reviewer 服务确定性降级，
                            // 禁止通用 ToolRuntime 在同一个 ToolCall 内再次启动 Child Run。
                            "retryable", false,
                            "errorCode", "REVIEWER_SUB_AGENT_FAILED"));
        }
    }

    private ToolCallResult failure(ToolCallRequest request, String message, String errorCode) {
        return new ToolCallResult(
                request == null ? "" : request.toolName(), false, "", message,
                Map.of(
                        "provider", "floworder-incident-subagent",
                        "executionKind", "SUB_AGENT",
                        "readOnly", true,
                        "retryable", false,
                        "errorCode", errorCode));
    }
}
