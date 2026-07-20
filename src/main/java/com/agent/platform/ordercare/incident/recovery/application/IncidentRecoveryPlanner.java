package com.agent.platform.ordercare.incident.recovery.application;

import com.agent.platform.agent.AgentRequest;
import com.agent.platform.approval.ApprovalRequest;
import com.agent.platform.approval.ApprovalService;
import com.agent.platform.ordercare.application.OrderCareProposalBinding;
import com.agent.platform.ordercare.application.OrderCareProposalBindingStore;
import com.agent.platform.ordercare.client.FlowOrderApiException;
import com.agent.platform.ordercare.client.FlowOrderClient;
import com.agent.platform.ordercare.incident.application.IncidentExecutionProfileFactory;
import com.agent.platform.ordercare.incident.config.IncidentCommandProperties;
import com.agent.platform.ordercare.incident.model.EvidenceRecord;
import com.agent.platform.ordercare.incident.model.IncidentAggregate;
import com.agent.platform.ordercare.incident.model.IncidentAssessment;
import com.agent.platform.ordercare.incident.model.TaskEventActorType;
import com.agent.platform.ordercare.incident.model.TaskEventCategory;
import com.agent.platform.ordercare.incident.model.TaskEventRecord;
import com.agent.platform.ordercare.incident.model.TaskEventType;
import com.agent.platform.ordercare.incident.persistence.IncidentStore;
import com.agent.platform.ordercare.incident.persistence.TaskEventStore;
import com.agent.platform.ordercare.incident.recovery.model.IncidentRecoveryPlanItem;
import com.agent.platform.ordercare.incident.recovery.model.IncidentRecoveryPlanRecord;
import com.agent.platform.ordercare.incident.recovery.model.RecoveryPlanDraft;
import com.agent.platform.ordercare.incident.recovery.model.RecoveryPlanItemStatus;
import com.agent.platform.ordercare.incident.recovery.model.RecoveryPlanOutcome;
import com.agent.platform.ordercare.incident.recovery.model.RecoveryPlanStartRequest;
import com.agent.platform.ordercare.incident.recovery.model.RecoveryPlanStartResponse;
import com.agent.platform.ordercare.incident.recovery.model.RecoveryPlanStatus;
import com.agent.platform.ordercare.incident.recovery.model.RecoveryPlanValidationResult;
import com.agent.platform.ordercare.incident.recovery.persistence.IncidentRecoveryPlanStore;
import com.agent.platform.ordercare.model.OrderCareProposalCreateCommand;
import com.agent.platform.ordercare.model.OrderCareRecoveryProposal;
import com.agent.platform.ordercare.tool.OrderCareToolCatalog;
import com.agent.platform.runtime.AgentRunState;
import com.agent.platform.runtime.AgentRuntime;
import com.agent.platform.runtime.AgentRuntimeResult;
import com.agent.platform.runtime.AgentExecutionProfile;
import com.agent.platform.workbench.budget.IncidentBudgetGate;
import com.agent.platform.workbench.budget.IncidentBudgetReservation;
import com.agent.platform.tool.ToolCallRequest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.HashSet;
import java.util.UUID;

@Service
public class IncidentRecoveryPlanner {

    private static final String SCENARIO_ID = "ordercare-incident-command-recovery-v1";
    private static final Logger log = LoggerFactory.getLogger(IncidentRecoveryPlanner.class);

    private final IncidentCommandProperties properties;
    private final IncidentStore incidentStore;
    private final IncidentRecoveryPlanStore planStore;
    private final TaskEventStore eventStore;
    private final IncidentExecutionProfileFactory profileFactory;
    private final IncidentRecoveryPlanValidator validator;
    private final RecoveryPlanDigest digest;
    private final AgentRuntime agentRuntime;
    private final FlowOrderClient flowOrderClient;
    private final OrderCareProposalBindingStore bindingStore;
    private final ApprovalService approvalService;
    private final ObjectMapper objectMapper;
    private final IncidentBudgetGate budgets;

    @Autowired
    public IncidentRecoveryPlanner(IncidentCommandProperties properties,
                                   IncidentStore incidentStore,
                                   IncidentRecoveryPlanStore planStore,
                                   TaskEventStore eventStore,
                                   IncidentExecutionProfileFactory profileFactory,
                                   IncidentRecoveryPlanValidator validator,
                                   RecoveryPlanDigest digest,
                                   AgentRuntime agentRuntime,
                                   FlowOrderClient flowOrderClient,
                                   OrderCareProposalBindingStore bindingStore,
                                   ApprovalService approvalService,
                                   ObjectMapper objectMapper,
                                   IncidentBudgetGate budgets) {
        this.properties = properties;
        this.incidentStore = incidentStore;
        this.planStore = planStore;
        this.eventStore = eventStore;
        this.profileFactory = profileFactory;
        this.validator = validator;
        this.digest = digest;
        this.agentRuntime = agentRuntime;
        this.flowOrderClient = flowOrderClient;
        this.bindingStore = bindingStore;
        this.approvalService = approvalService;
        this.objectMapper = objectMapper;
        this.budgets = budgets;
    }

    public IncidentRecoveryPlanner(IncidentCommandProperties properties,
                                   IncidentStore incidentStore,
                                   IncidentRecoveryPlanStore planStore,
                                   TaskEventStore eventStore,
                                   IncidentExecutionProfileFactory profileFactory,
                                   IncidentRecoveryPlanValidator validator,
                                   RecoveryPlanDigest digest,
                                   AgentRuntime agentRuntime,
                                   FlowOrderClient flowOrderClient,
                                   OrderCareProposalBindingStore bindingStore,
                                   ApprovalService approvalService,
                                   ObjectMapper objectMapper) {
        this(properties, incidentStore, planStore, eventStore, profileFactory, validator, digest,
                agentRuntime, flowOrderClient, bindingStore, approvalService, objectMapper,
                IncidentBudgetGate.NOOP);
    }

    public RecoveryPlanStartResponse initialize(String incidentId, RecoveryPlanStartRequest request) {
        requireEnabled();
        IncidentAggregate aggregate = requireIncident(incidentId);
        IncidentAssessment assessment = assessment(aggregate);
        RecoveryPlanValidationResult eligibility = validator.validateEligibility(aggregate, assessment);
        if (!eligibility.valid()) {
            throw new IllegalStateException("incident is not eligible for recovery planning: "
                    + String.join("; ", eligibility.errors()));
        }
        String requestKey = requireText(request == null ? null : request.requestKey(), "requestKey");
        String assessmentDigest = digest.sha256(assessment);
        var existing = planStore.findByRequestKey(incidentId, requestKey);
        if (existing.isPresent()) {
            IncidentRecoveryPlanRecord current = existing.get();
            if (!assessmentDigest.equals(current.assessmentDigest())) {
                throw new IllegalArgumentException("requestKey is bound to another assessment version");
            }
            budgets.initializeRecoveryPlan(current.planId(), request.budgetOwnerWorkItemId());
            return response(current, false);
        }
        Instant now = Instant.now();
        IncidentRecoveryPlanRecord created = planStore.create(new IncidentRecoveryPlanRecord(
                "rplan-" + UUID.randomUUID(), incidentId, requestKey, "", assessmentDigest,
                RecoveryPlanStatus.CREATED, RecoveryPlanOutcome.NOT_STARTED, null,
                List.of(), List.of(), 0, now, now));
        emit(created, "RECOVERY_PLAN_CREATED", Map.of("requestKey", requestKey));
        try {
            budgets.initializeRecoveryPlan(created.planId(), request.budgetOwnerWorkItemId());
        }
        catch (RuntimeException exception) {
            fail(created, List.of("budget admission failed: " + safe(exception.getMessage())));
            throw exception;
        }
        return response(created, true);
    }

    public IncidentRecoveryPlanRecord plan(String planId, String objective) {
        IncidentRecoveryPlanRecord plan = requirePlan(planId);
        if (plan.status() != RecoveryPlanStatus.CREATED) {
            return plan;
        }
        IncidentAggregate aggregate = requireIncident(plan.incidentId());
        IncidentAssessment assessment = assessment(aggregate);
        if (!plan.assessmentDigest().equals(digest.sha256(assessment))) {
            return fail(plan, List.of("authoritative IncidentAssessment changed before planning"));
        }
        plan = transition(plan, RecoveryPlanStatus.PLANNING, RecoveryPlanOutcome.NOT_STARTED,
                plan.plannerRunId(), plan.draft(), plan.items(), plan.validationErrors());
        AgentExecutionProfile profile = profileFactory.recoveryPlanner();
        IncidentBudgetReservation budget = budgets.reserveRecoveryPlanRun(
                plan.planId(), "recovery-planner", "RECOVERY_PLANNER", profile);
        AgentRuntimeResult result = agentRuntime.run(
                new AgentRequest(
                        "incident:" + plan.incidentId() + ":recovery-planner",
                        "incident-recovery-planner",
                        plannerPrompt(plan, aggregate, assessment, objective),
                        Map.of("incidentId", plan.incidentId(), "recoveryPlanId", plan.planId(),
                                "runRole", "RECOVERY_PLANNER"),
                        SCENARIO_ID),
                profile,
                event -> { });
        budgets.settle(budget, result);
        RecoveryPlanDraft draft = parseDraft(result);
        RecoveryPlanValidationResult validation = validator.validate(aggregate, assessment, draft);
        if (!validation.valid()) {
            IncidentRecoveryPlanRecord failed = new IncidentRecoveryPlanRecord(
                    plan.planId(), plan.incidentId(), plan.requestKey(), result.runId(),
                    plan.assessmentDigest(), RecoveryPlanStatus.FAILED, RecoveryPlanOutcome.MANUAL_REVIEW,
                    draft, List.of(), validation.errors(), plan.version() + 1,
                    plan.createdAt(), Instant.now());
            failed = planStore.update(failed, plan.version());
            emit(failed, "RECOVERY_PLAN_REJECTED", Map.of("errors", validation.errors()));
            budgets.completeRecoveryPlan(failed.planId());
            return failed;
        }
        plan = transition(plan, RecoveryPlanStatus.PREVIEWING, RecoveryPlanOutcome.NOT_STARTED,
                result.runId(), draft, List.of(), List.of());
        List<IncidentRecoveryPlanItem> items = preview(plan, draft);
        RecoveryPlanStatus status = items.stream().anyMatch(item -> item.status() == RecoveryPlanItemStatus.WAITING_APPROVAL)
                ? RecoveryPlanStatus.WAITING_APPROVAL : RecoveryPlanStatus.COMPLETED;
        RecoveryPlanOutcome outcome = status == RecoveryPlanStatus.WAITING_APPROVAL
                ? RecoveryPlanOutcome.READY : RecoveryPlanOutcome.REJECTED;
        plan = transition(plan, status, outcome, result.runId(), draft, items, List.of());
        emit(plan, "RECOVERY_PLAN_PREVIEWED", Map.of(
                "itemCount", items.size(),
                "approvableCount", items.stream().filter(item -> item.status() == RecoveryPlanItemStatus.WAITING_APPROVAL).count()));
        budgets.completeRecoveryPlan(plan.planId());
        return plan;
    }

    public IncidentRecoveryPlanRecord failBeforePlanning(String planId, RuntimeException exception) {
        IncidentRecoveryPlanRecord plan = requirePlan(planId);
        if (plan.status().terminal()) {
            return plan;
        }
        return fail(plan, List.of(exception.getClass().getSimpleName() + ": " + safe(exception.getMessage())));
    }

    private List<IncidentRecoveryPlanItem> preview(IncidentRecoveryPlanRecord plan, RecoveryPlanDraft draft) {
        List<IncidentRecoveryPlanItem> items = new ArrayList<>();
        for (RecoveryPlanDraft.ProposalRequest request : draft.proposalRequests()) {
            String itemId = stableId("ritem", plan.planId() + ":" + request.clientItemKey());
            String proposalId = stableId("prop", plan.planId() + ":" + request.clientItemKey());
            Instant now = Instant.now();
            try {
                IncidentBudgetReservation toolBudget = budgets.reserveRecoveryPlanTool(
                        plan.planId(), "proposal:" + itemId, "FLOWORDER_PROPOSAL_PREVIEW");
                long startedNanos = System.nanoTime();
                OrderCareRecoveryProposal proposal;
                try {
                    proposal = flowOrderClient.createProposal(
                            new OrderCareProposalCreateCommand(
                                    proposalId, request.identifierType(), request.identifierValue(),
                                    request.actionType(), request.suggestedReason()),
                            traceId(plan, itemId, "preview"));
                }
                finally {
                    budgets.settleDeterministicTool(
                            toolBudget, (System.nanoTime() - startedNanos) / 1_000_000);
                }
                if (!"ACTIVE".equals(proposal.proposalStatus()) || !Boolean.TRUE.equals(proposal.canExecute())) {
                    items.add(item(request, itemId, RecoveryPlanItemStatus.INELIGIBLE, proposal,
                            "", "NOT_REQUESTED", proposal.actionStatus(), proposal.caseOutcome(),
                            null, "FlowOrder Proposal is not executable", now));
                    continue;
                }
                String previewRef = "incident-recovery-preview:" + itemId;
                bindingStore.bind(new OrderCareProposalBinding(
                        proposal.proposalId(), proposal.actionRequestId(), proposal.caseKey(),
                        previewRef, plan.plannerRunId(), proposal, now));
                String approvalId = stableId("approval", plan.planId() + ":" + request.clientItemKey());
                ToolCallRequest approvedCall = approvalToolCall(plan, itemId, approvalId, proposal);
                if (approvalService.find(approvalId).isEmpty()) {
                    approvalService.requestApproval(new ApprovalRequest(
                            approvalId, plan.plannerRunId(), "incident:" + plan.incidentId(), approvedCall,
                            "批准 Incident Recovery Plan 中的不可变 Proposal；每项独立执行和收敛",
                            now));
                }
                items.add(item(request, itemId, RecoveryPlanItemStatus.WAITING_APPROVAL, proposal,
                        approvalId, "REQUESTED", proposal.actionStatus(), proposal.caseOutcome(),
                        null, "", now));
            } catch (FlowOrderApiException exception) {
                items.add(item(request, itemId, RecoveryPlanItemStatus.FAILED, null,
                        "", "NOT_REQUESTED", "NOT_STARTED", "NOT_CONVERGED", null,
                        "FlowOrder preview failed: " + exception.getMessage(), now));
            } catch (RuntimeException exception) {
                items.add(item(request, itemId, RecoveryPlanItemStatus.FAILED, null,
                        "", "NOT_REQUESTED", "NOT_STARTED", "NOT_CONVERGED", null,
                        "Recovery preview coordination failed: " + exception.getClass().getSimpleName(), now));
            }
        }
        return List.copyOf(items);
    }

    private IncidentRecoveryPlanItem item(RecoveryPlanDraft.ProposalRequest request,
                                          String itemId,
                                          RecoveryPlanItemStatus status,
                                          OrderCareRecoveryProposal proposal,
                                          String approvalId,
                                          String approvalStatus,
                                          String actionStatus,
                                          String caseOutcome,
                                          com.agent.platform.ordercare.model.OrderCareConvergenceResult convergence,
                                          String error,
                                          Instant now) {
        return new IncidentRecoveryPlanItem(
                itemId, request.clientItemKey(), request.identifierType(), request.identifierValue(),
                request.actionType(), request.suggestedReason(), request.evidenceIds(), request.conflictIds(),
                status, proposal, approvalId, approvalStatus, actionStatus, caseOutcome,
                convergence, error, "", 0, null, null, 0, now);
    }

    private ToolCallRequest approvalToolCall(IncidentRecoveryPlanRecord plan,
                                             String itemId,
                                             String approvalId,
                                             OrderCareRecoveryProposal proposal) {
        Map<String, Object> trusted = new LinkedHashMap<>();
        trusted.put("proposalId", proposal.proposalId());
        trusted.put("proposalVersion", proposal.proposalVersion());
        trusted.put("stateFingerprint", proposal.stateFingerprint());
        trusted.put("effectsDigest", proposal.effectsDigest());
        trusted.put("warningsDigest", proposal.warningsDigest());
        trusted.put("previewDigest", proposal.previewDigest());
        trusted.put("expiresAt", proposal.expiresAt());
        trusted.put("caseKey", proposal.caseKey());
        trusted.put("actionRequestId", proposal.actionRequestId());
        trusted.put("effects", proposal.effects());
        trusted.put("warnings", proposal.warnings());
        trusted.put("suggestedReason", safe(proposal.suggestedReason()));
        trusted.put("approvalId", approvalId);
        trusted.put("incidentId", plan.incidentId());
        trusted.put("recoveryPlanId", plan.planId());
        trusted.put("recoveryPlanItemId", itemId);
        trusted.put("assessmentDigest", plan.assessmentDigest());
        return new ToolCallRequest(
                OrderCareToolCatalog.RECOVERY_EXECUTE,
                "incident-recovery-execute:" + itemId,
                trusted);
    }

    private String plannerPrompt(IncidentRecoveryPlanRecord plan,
                                 IncidentAggregate aggregate,
                                 IncidentAssessment assessment,
                                 String objective) {
        Map<String, Object> input = new LinkedHashMap<>();
        input.put("schemaVersion", "incident-recovery-planner-input-v1");
        input.put("planId", plan.planId());
        input.put("incidentId", plan.incidentId());
        input.put("assessmentDigest", plan.assessmentDigest());
        input.put("objective", safe(objective));
        input.put("allowedIdentifierType", "REQUEST_ID");
        input.put("allowedActionType", "REPLAY");
        input.put("maxItems", properties.getMaxRecoveryPlanItems());
        input.put("snapshotRequestIds", aggregate.incident().snapshot().orderScope().requestIds());
        input.put("assessment", assessment);
        Set<String> authoritativeEvidenceIds = assessmentEvidenceIds(assessment);
        input.put("evidence", aggregate.evidence().stream()
                .filter(evidence -> authoritativeEvidenceIds.contains(evidence.evidenceId()))
                .map(this::plannerEvidence)
                .toList());
        return """
                只返回 incident-recovery-plan-v1 JSON，不得调用工具或执行恢复。
                输出格式：
                {"schemaVersion":"incident-recovery-plan-v1","summary":"...","proposalRequests":[{"clientItemKey":"...","identifierType":"REQUEST_ID","identifierValue":"...","actionType":"REPLAY","suggestedReason":"...","evidenceIds":["..."],"conflictIds":[]}]}
                每个目标必须来自 snapshotRequestIds，并引用能够证明该 requestId 存在可恢复死信的 FACT evidenceId。
                evidence 数组已经由 Java 限定为权威 Assessment 的引用闭包，不得引用数组之外的标识符。
                输入：
                """ + objectMapper.writeValueAsString(input);
    }

    private Set<String> assessmentEvidenceIds(IncidentAssessment assessment) {
        Set<String> result = new HashSet<>();
        assessment.confirmedFacts().forEach(item -> result.addAll(item.evidenceIds()));
        assessment.rootCauseCandidates().forEach(item -> result.addAll(item.supportingEvidenceIds()));
        assessment.recommendations().forEach(item -> result.addAll(item.evidenceIds()));
        assessment.conflicts().forEach(item -> result.addAll(item.evidenceIds()));
        return Set.copyOf(result);
    }

    private Map<String, Object> plannerEvidence(EvidenceRecord evidence) {
        return Map.of(
                "evidenceId", evidence.evidenceId(),
                "evidenceClass", evidence.evidenceClass(),
                "evidenceSubtype", evidence.evidenceSubtype(),
                "status", evidence.status(),
                "facts", evidence.facts());
    }

    private RecoveryPlanDraft parseDraft(AgentRuntimeResult result) {
        if (result == null || result.state() != AgentRunState.COMPLETED) {
            return null;
        }
        try {
            return objectMapper.readValue(json(result.answer()), RecoveryPlanDraft.class);
        } catch (RuntimeException exception) {
            return null;
        }
    }

    private IncidentRecoveryPlanRecord transition(IncidentRecoveryPlanRecord current,
                                                  RecoveryPlanStatus status,
                                                  RecoveryPlanOutcome outcome,
                                                  String plannerRunId,
                                                  RecoveryPlanDraft draft,
                                                  List<IncidentRecoveryPlanItem> items,
                                                  List<String> validationErrors) {
        IncidentRecoveryPlanRecord next = new IncidentRecoveryPlanRecord(
                current.planId(), current.incidentId(), current.requestKey(), plannerRunId,
                current.assessmentDigest(), status, outcome, draft, items, validationErrors,
                current.version() + 1, current.createdAt(), Instant.now());
        IncidentRecoveryPlanRecord updated = planStore.update(next, current.version());
        emit(updated, "RECOVERY_PLAN_STATE_CHANGED", Map.of(
                "from", current.status().name(), "to", status.name(), "outcome", outcome.name()));
        return updated;
    }

    private IncidentRecoveryPlanRecord fail(IncidentRecoveryPlanRecord plan, List<String> errors) {
        IncidentRecoveryPlanRecord failed = transition(
                plan, RecoveryPlanStatus.FAILED, RecoveryPlanOutcome.MANUAL_REVIEW,
                plan.plannerRunId(), plan.draft(), plan.items(), errors);
        emit(failed, "RECOVERY_PLAN_FAILED", Map.of("errors", errors));
        budgets.completeRecoveryPlan(failed.planId());
        return failed;
    }

    private void emit(IncidentRecoveryPlanRecord plan, String eventName, Map<String, Object> details) {
        Map<String, Object> payload = new LinkedHashMap<>(details);
        payload.put("recoveryEvent", eventName);
        payload.put("planId", plan.planId());
        payload.put("planStatus", plan.status().name());
        payload.put("planOutcome", plan.outcome().name());
        try {
            eventStore.appendEvent(new TaskEventRecord(
                    UUID.randomUUID().toString(), plan.incidentId(), null, plan.plannerRunId(), 0,
                    TaskEventType.RECOVERY_PLAN_CHANGED, TaskEventCategory.LIFECYCLE,
                    TaskEventActorType.ORCHESTRATOR, "incident-recovery-planner",
                    null, null, 0, plan.planId(), null,
                    "recovery-plan-event:" + plan.planId() + ":" + plan.version() + ":" + eventName,
                    payload, Instant.now()));
        } catch (RuntimeException exception) {
            // RecoveryPlan JSON is authoritative; the incident event is a rebuildable SSE/Trace projection.
            log.warn("recovery plan event projection failed: planId={}, event={}",
                    plan.planId(), eventName, exception);
        }
    }

    private IncidentAggregate requireIncident(String incidentId) {
        return incidentStore.findAggregate(incidentId, 10_000)
                .orElseThrow(() -> new IllegalArgumentException("incident not found: " + incidentId));
    }

    private IncidentRecoveryPlanRecord requirePlan(String planId) {
        return planStore.find(planId)
                .orElseThrow(() -> new IllegalArgumentException("recovery plan not found: " + planId));
    }

    private IncidentAssessment assessment(IncidentAggregate aggregate) {
        if (aggregate.incident().assessment().isEmpty()) {
            throw new IllegalStateException("incident has no authoritative assessment");
        }
        return objectMapper.convertValue(aggregate.incident().assessment(), IncidentAssessment.class);
    }

    private RecoveryPlanStartResponse response(IncidentRecoveryPlanRecord plan, boolean newlyCreated) {
        return new RecoveryPlanStartResponse(
                plan.planId(), plan.incidentId(), plan.status(), plan.createdAt(), newlyCreated);
    }

    private void requireEnabled() {
        if (!properties.isEnabled() || !properties.isRecoveryPlannerEnabled()) {
            throw new IllegalStateException("Incident Recovery Planner is disabled");
        }
    }

    private String traceId(IncidentRecoveryPlanRecord plan, String itemId, String operation) {
        return plan.planId() + ":" + itemId + ":" + operation;
    }

    private String stableId(String prefix, String source) {
        UUID id = UUID.nameUUIDFromBytes(source.getBytes(StandardCharsets.UTF_8));
        return prefix + "-" + id;
    }

    private String json(String value) {
        String normalized = safe(value);
        if (normalized.startsWith("```")) {
            int firstLine = normalized.indexOf('\n');
            int closing = normalized.lastIndexOf("```");
            if (firstLine >= 0 && closing > firstLine) {
                normalized = normalized.substring(firstLine + 1, closing).trim();
            }
        }
        int start = normalized.indexOf('{');
        int end = normalized.lastIndexOf('}');
        return start >= 0 && end > start ? normalized.substring(start, end + 1) : normalized;
    }

    private String requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        return value.trim();
    }

    private String safe(String value) {
        return value == null ? "" : value.trim();
    }
}
