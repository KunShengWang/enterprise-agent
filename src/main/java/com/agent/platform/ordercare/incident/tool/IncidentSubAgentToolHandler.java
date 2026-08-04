package com.agent.platform.ordercare.incident.tool;

import com.agent.platform.ordercare.incident.application.IncidentSubAgentTaskService;
import com.agent.platform.ordercare.incident.application.IncidentTaskExecution;
import com.agent.platform.ordercare.incident.model.EvidenceGap;
import com.agent.platform.ordercare.incident.model.EvidenceRecord;
import com.agent.platform.ordercare.incident.model.IncidentAgentRole;
import com.agent.platform.ordercare.incident.model.IncidentRecord;
import com.agent.platform.ordercare.incident.model.IncidentStatus;
import com.agent.platform.ordercare.incident.persistence.IncidentStore;
import com.agent.platform.tool.ContextualToolHandler;
import com.agent.platform.tool.ToolCallRequest;
import com.agent.platform.tool.ToolCallResult;
import com.agent.platform.tool.ToolExecutionContext;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 将事故 Specialist 注册为普通 Tool，同时保留领域任务、Child Run 和证据持久化链路。 */
@Component
public class IncidentSubAgentToolHandler implements ContextualToolHandler {

    private final IncidentStore incidentStore;
    private final IncidentSubAgentTaskService taskService;
    private final ObjectMapper objectMapper;

    public IncidentSubAgentToolHandler(IncidentStore incidentStore,
                                       IncidentSubAgentTaskService taskService,
                                       ObjectMapper objectMapper) {
        this.incidentStore = incidentStore;
        this.taskService = taskService;
        this.objectMapper = objectMapper;
    }

    @Override
    public boolean supports(String toolName) {
        return role(toolName) != null;
    }

    @Override
    public ToolCallResult execute(ToolCallRequest request, ToolExecutionContext context) {
        IncidentAgentRole role = request == null ? null : role(request.toolName());
        if (role == null) {
            return failure(request, "unsupported incident sub-agent tool", false, "UNSUPPORTED_TOOL");
        }
        if (context == null || context.runId().isBlank()
                || !"COMMANDER".equals(context.attribute("runRole"))) {
            return failure(request, "trusted Commander execution context is required", false,
                    "UNTRUSTED_PARENT_CONTEXT");
        }
        if (context.intAttribute("delegationDepth", 0) >= 1) {
            return failure(request, "maximum delegation depth exceeded", false, "DELEGATION_DEPTH_EXCEEDED");
        }

        IncidentRecord incident = incidentStore.find(context.attribute("incidentId")).orElse(null);
        if (incident == null) {
            return failure(request, "incident not found in trusted execution context", false,
                    "INCIDENT_NOT_FOUND");
        }
        if (incident.status() != IncidentStatus.PLANNING
                && incident.status() != IncidentStatus.INVESTIGATING) {
            return failure(request, "incident does not accept specialist delegation in state "
                    + incident.status(), false, "DELEGATION_STATE_REJECTED");
        }
        if (role == IncidentAgentRole.MQ_ANALYST
                && incident.snapshot().businessScope().queueNames().isEmpty()) {
            return failure(request, "MQ Analyst requires a server-authorized queue scope", false,
                    "MQ_SCOPE_NOT_AUTHORIZED");
        }

        IncidentSubAgentTaskService.DelegationOutcome outcome = taskService.delegate(
                incident.snapshot(), context.runId(), role, objective(request));
        return result(request, outcome.execution(), outcome.reused());
    }

    private ToolCallResult result(ToolCallRequest request,
                                  IncidentTaskExecution execution,
                                  boolean reused) {
        var task = execution.task();
        LinkedHashMap<String, Object> payload = new LinkedHashMap<>();
        payload.put("schemaVersion", "incident-subagent-result-v1");
        payload.put("taskId", task.taskId());
        payload.put("role", task.role());
        payload.put("status", task.status().name());
        payload.put("successful", execution.successful());
        payload.put("evidenceIds", execution.evidence().stream().map(EvidenceRecord::evidenceId).toList());
        payload.put("evidenceSubtypes", execution.evidence().stream()
                .map(EvidenceRecord::evidenceSubtype).distinct().toList());
        payload.put("gaps", execution.gaps());

        LinkedHashMap<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("provider", "floworder-incident-subagent");
        metadata.put("executionKind", "SUB_AGENT");
        metadata.put("readOnly", true);
        metadata.put("retryable", !execution.successful() && !task.status().terminal());
        metadata.put("incidentId", task.incidentId());
        metadata.put("taskId", task.taskId());
        metadata.put("role", task.role());
        metadata.put("childRunId", task.childRunId() == null ? "" : task.childRunId());
        metadata.put("reusedTask", reused);
        return new ToolCallResult(
                request.toolName(), execution.successful(), objectMapper.writeValueAsString(payload),
                execution.successful() ? "" : firstGap(execution.gaps()), Map.copyOf(metadata));
    }

    private String objective(ToolCallRequest request) {
        if (request == null || request.arguments() == null) return "";
        Object value = request.arguments().get("objective");
        return value == null ? "" : String.valueOf(value).trim();
    }

    private IncidentAgentRole role(String toolName) {
        if (IncidentToolCatalog.DELEGATE_ORDER_ANALYST.equals(toolName)) return IncidentAgentRole.ORDER_ANALYST;
        if (IncidentToolCatalog.DELEGATE_INVENTORY_ANALYST.equals(toolName)) return IncidentAgentRole.INVENTORY_ANALYST;
        if (IncidentToolCatalog.DELEGATE_MQ_ANALYST.equals(toolName)) return IncidentAgentRole.MQ_ANALYST;
        return null;
    }

    private ToolCallResult failure(ToolCallRequest request,
                                   String message,
                                   boolean retryable,
                                   String errorCode) {
        return new ToolCallResult(
                request == null ? "" : request.toolName(), false, "", message,
                Map.of("provider", "floworder-incident-subagent", "executionKind", "SUB_AGENT",
                        "readOnly", true, "retryable", retryable, "errorCode", errorCode));
    }

    private String firstGap(List<EvidenceGap> gaps) {
        return gaps == null || gaps.isEmpty() ? "incident sub-agent did not complete" : gaps.get(0).message();
    }
}
