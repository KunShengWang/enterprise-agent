package com.agent.platform.ordercare.incident.application;

import com.agent.platform.agent.AgentRequest;
import com.agent.platform.ordercare.incident.model.AgentTaskRecord;
import com.agent.platform.ordercare.incident.model.AgentTaskStatus;
import com.agent.platform.ordercare.incident.model.ConflictSeverity;
import com.agent.platform.ordercare.incident.model.DelegationPlan;
import com.agent.platform.ordercare.incident.model.EvidenceConflict;
import com.agent.platform.ordercare.incident.model.EvidenceConsistencyResult;
import com.agent.platform.ordercare.incident.model.EvidenceGap;
import com.agent.platform.ordercare.incident.model.EvidenceRecord;
import com.agent.platform.ordercare.incident.model.EvidenceSubtype;
import com.agent.platform.ordercare.incident.model.EvidenceTrust;
import com.agent.platform.ordercare.incident.model.IncidentAggregate;
import com.agent.platform.ordercare.incident.model.IncidentAgentRole;
import com.agent.platform.ordercare.incident.model.IncidentAssessment;
import com.agent.platform.ordercare.incident.model.IncidentInvestigationRequest;
import com.agent.platform.ordercare.incident.model.IncidentInvestigationResult;
import com.agent.platform.ordercare.incident.model.IncidentOutcome;
import com.agent.platform.ordercare.incident.model.IncidentRecord;
import com.agent.platform.ordercare.incident.model.IncidentSnapshot;
import com.agent.platform.ordercare.incident.model.IncidentStatus;
import com.agent.platform.ordercare.incident.model.ReviewerAssessmentDraft;
import com.agent.platform.ordercare.incident.model.TaskEventActorType;
import com.agent.platform.ordercare.incident.model.TaskEventCategory;
import com.agent.platform.ordercare.incident.model.TaskEventRecord;
import com.agent.platform.ordercare.incident.model.TaskEventType;
import com.agent.platform.ordercare.incident.persistence.AgentTaskStore;
import com.agent.platform.ordercare.incident.persistence.EvidenceStore;
import com.agent.platform.ordercare.incident.persistence.IncidentStore;
import com.agent.platform.ordercare.incident.persistence.TaskEventStore;
import com.agent.platform.ordercare.incident.config.IncidentCommandProperties;
import com.agent.platform.runtime.AgentContinuationRuntime;
import com.agent.platform.runtime.AgentFollowUpInput;
import com.agent.platform.runtime.AgentRunState;
import com.agent.platform.runtime.AgentRunStore;
import com.agent.platform.runtime.AgentRuntime;
import com.agent.platform.runtime.AgentRuntimeResult;
import com.agent.platform.runtime.AgentExecutionProfile;
import com.agent.platform.runtime.ToolExecutionStore;
import com.agent.platform.workbench.budget.IncidentBudgetGate;
import com.agent.platform.workbench.budget.IncidentBudgetReservation;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import tools.jackson.databind.ObjectMapper;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.Optional;
import java.util.stream.Collectors;

@Service
public class IncidentInvestigationOrchestrator {

    private static final Logger LOGGER = LoggerFactory.getLogger(IncidentInvestigationOrchestrator.class);
    private static final String SCENARIO_ID = "ordercare-incident-command-v1";

    private final IncidentStore incidentStore;
    private final AgentTaskStore taskStore;
    private final EvidenceStore evidenceStore;
    private final TaskEventStore eventStore;
    private final IncidentSnapshotFactory snapshotFactory;
    private final DelegationPlanValidator planValidator;
    private final SafeDelegationPlanFactory fallbackPlanFactory;
    private final IncidentExecutionProfileFactory profileFactory;
    private final IncidentTaskScheduler taskScheduler;
    private final EvidenceConsistencyChecker consistencyChecker;
    private final EvidenceTrustAssessor trustAssessor;
    private final IncidentAssessmentAssembler assessmentAssembler;
    private final ReviewerAssessmentDraftParser reviewerDraftParser;
    private final AgentRuntime agentRuntime;
    private final AgentContinuationRuntime continuationRuntime;
    private final ObjectMapper objectMapper;
    private final IncidentBudgetGate budgets;
    private final IncidentCommandProperties properties;
    private final ToolExecutionStore toolExecutionStore;
    private final AgentRunStore agentRunStore;
    private final IncidentEvidenceProjector evidenceProjector;
    private final IncidentSubAgentTaskService subAgentTaskService;
    private final IncidentReviewerAgentService reviewerAgentService;

    public IncidentInvestigationOrchestrator(IncidentStore incidentStore,
                                             AgentTaskStore taskStore,
                                             EvidenceStore evidenceStore,
                                             TaskEventStore eventStore,
                                             IncidentSnapshotFactory snapshotFactory,
                                             DelegationPlanValidator planValidator,
                                             SafeDelegationPlanFactory fallbackPlanFactory,
                                             IncidentExecutionProfileFactory profileFactory,
                                             IncidentTaskScheduler taskScheduler,
                                             EvidenceConsistencyChecker consistencyChecker,
                                             EvidenceTrustAssessor trustAssessor,
                                             IncidentAssessmentAssembler assessmentAssembler,
                                             ReviewerAssessmentDraftParser reviewerDraftParser,
                                             AgentRuntime agentRuntime,
                                             AgentContinuationRuntime continuationRuntime,
                                             ObjectMapper objectMapper,
                                             IncidentBudgetGate budgets,
                                             IncidentCommandProperties properties,
                                             ToolExecutionStore toolExecutionStore,
                                             AgentRunStore agentRunStore,
                                             IncidentEvidenceProjector evidenceProjector,
                                             IncidentSubAgentTaskService subAgentTaskService,
                                             IncidentReviewerAgentService reviewerAgentService) {
        this.incidentStore = incidentStore;
        this.taskStore = taskStore;
        this.evidenceStore = evidenceStore;
        this.eventStore = eventStore;
        this.snapshotFactory = snapshotFactory;
        this.planValidator = planValidator;
        this.fallbackPlanFactory = fallbackPlanFactory;
        this.profileFactory = profileFactory;
        this.taskScheduler = taskScheduler;
        this.consistencyChecker = consistencyChecker;
        this.trustAssessor = trustAssessor;
        this.assessmentAssembler = assessmentAssembler;
        this.reviewerDraftParser = reviewerDraftParser;
        this.agentRuntime = agentRuntime;
        this.continuationRuntime = continuationRuntime;
        this.objectMapper = objectMapper;
        this.budgets = budgets;
        this.properties = properties;
        this.toolExecutionStore = toolExecutionStore;
        this.agentRunStore = agentRunStore;
        this.evidenceProjector = evidenceProjector;
        this.subAgentTaskService = subAgentTaskService;
        this.reviewerAgentService = reviewerAgentService;
    }

    public IncidentInvestigationResult investigate(IncidentInvestigationRequest request) {
        IncidentRecord initial = initialize(request);
        return investigate(initial.incidentId(), request);
    }

    public IncidentRecord initialize(IncidentInvestigationRequest request) {
        String incidentId = "inc-" + UUID.randomUUID();
        IncidentSnapshot snapshot = snapshotFactory.create(incidentId, request);
        Instant now = Instant.now();
        IncidentRecord created = incidentStore.create(new IncidentRecord(
                incidentId, null, null, "incident:" + incidentId, SCENARIO_ID,
                IncidentStatus.CREATED, snapshot, Map.of(), Map.of(),
                0, 1, 1, 0, now, now));
        try {
            budgets.initializeIncident(created.incidentId(), request.budgetOwnerWorkItemId());
            return created;
        }
        catch (RuntimeException exception) {
            rejectBeforeExecution(created.incidentId(), exception.getMessage());
            throw exception;
        }
    }

    /**
     * 把一个"分发请求"初始化成一个持久化的 Incident（事故调查）记录，并确保这个分发请求只对应一个 Incident（幂等），同时初始化它的预算
     */
    public IncidentDispatchInitialization initializeForDispatch(String dispatchRequestId,
                                                                IncidentInvestigationRequest request) {
        String incidentId = "inc-" + UUID.randomUUID();
        // 生成快照
        IncidentSnapshot snapshot = snapshotFactory.create(incidentId, request);
        Instant now = Instant.now();
        // 构造 Incident 记录（CREATED 状态）
        IncidentRecord candidate = new IncidentRecord(
                incidentId, null, null, "incident:" + incidentId, SCENARIO_ID,
                IncidentStatus.CREATED, snapshot, Map.of(), Map.of(),
                0, 1, 1, 0, now, now);
        // 幂等落库（关键）
        IncidentRecord persisted = incidentStore.createForDispatch(dispatchRequestId, candidate);
        try {
            // 初始化预算
            budgets.initializeIncident(persisted.incidentId(), request.budgetOwnerWorkItemId());
        }
        catch (RuntimeException exception) {
            rejectBeforeExecution(persisted.incidentId(), exception.getMessage());
            throw exception;
        }
        return new IncidentDispatchInitialization(persisted, persisted.incidentId().equals(incidentId));
    }

    public void rejectBeforeExecution(String incidentId, String reason) {
        IncidentRecord incident = current(incidentId);
        if (incident.status() == IncidentStatus.CREATED) {
            incidentStore.transitionStatus(
                    incidentId, incident.version(), IncidentStatus.FAILED,
                    TaskEventActorType.SYSTEM, "incident-launcher",
                    "incident-launch-rejected:" + incidentId);
        }
    }

    public Optional<IncidentRecord> findByDispatchRequestId(String dispatchRequestId) {
        return incidentStore.findByDispatchRequestId(dispatchRequestId);
    }

    public IncidentInvestigationResult investigate(String incidentId,
                                                    IncidentInvestigationRequest request) {
        IncidentRecord incident = current(incidentId);
        if (incident.status() != IncidentStatus.CREATED) {
            throw new IllegalStateException("incident must be CREATED before execution: " + incidentId);
        }
        IncidentSnapshot snapshot = incident.snapshot();
        try {
            // 状态机的状态转移
            incident = transition(incident, IncidentStatus.PLANNING, "incident-planning");
            PlanningResult planning;
            List<IncidentTaskExecution> executions;
            if (properties.isSubAgentToolsEnabled()) {
                // 新路径：先进入调查态，再让 Commander 通过普通 ToolCall 委派 Specialist。
                incident = transition(incident, IncidentStatus.INVESTIGATING,
                        "incident-investigating-subagent-tools");
                ToolDelegationResult delegated = delegateWithSubAgentTools(incident, request);
                planning = new PlanningResult(delegated.commanderRunId(), delegated.plan());
                incident = current(incidentId);
                incident = incidentStore.updateDetails(
                        incidentId, incident.version(), planning.commanderRunId(), null,
                        objectMapper.convertValue(planning.plan(), Map.class), null, false);
                executions = delegated.executions();
            }
            else {
                // 兼容路径：Commander 输出 delegation-plan-v1，Java 解析后创建和调度任务。
                planning = plan(incident, request);
                incident = incidentStore.updateDetails(
                        incidentId, incident.version(), planning.commanderRunId(), null,
                        objectMapper.convertValue(planning.plan(), Map.class), null, false);
                List<AgentTaskRecord> tasks = createTasks(incident, planning.plan());
                incident = transition(incident, IncidentStatus.INVESTIGATING, "incident-investigating");
                executions = taskScheduler.execute(tasks, snapshot);// ← Specialist 全部返回（工具委派路径）
            }
            List<EvidenceGap> gaps = executions.stream().flatMap(item -> item.gaps().stream()).toList();// ① 收集证据缺口

            incident = current(incidentId);
            incident = transition(incident, IncidentStatus.CHECKING_CONSISTENCY, "incident-checking");// ② 状态机→一致性检查
            List<EvidenceRecord> evidence = evidenceStore.listEvidence(incidentId);// ③ 加载全部证据
            Set<EvidenceSubtype> required = planning.plan().tasks().stream()
                    .flatMap(task -> task.requiredEvidenceSubtypes().stream())
                    .collect(Collectors.toSet());// ④ 必需证据类型（来自计划）
            EvidenceConsistencyResult consistency = consistencyChecker.check(snapshot, evidence, required);// ⑤ 缺失检查 + 跨域冲突检测
            List<EvidenceConflict> conflicts = persistConflicts(incidentId, consistency.conflicts());// ⑥ 持久化 Conflict
            persistTrust(incidentId, trustAssessor.assess(snapshot, evidence, conflicts)); // ⑦ 可信度计算 + 持久化 Trust

            incident = current(incidentId);
            incident = transition(incident, IncidentStatus.REVIEWING, "incident-reviewing");
            ReviewResult review = properties.isSubAgentToolsEnabled()
                    ? reviewThroughCommanderTool(
                    incident, evidence, conflicts, gaps, planning.commanderRunId())
                    : review(incident, evidence, conflicts, gaps);
            incident = incidentStore.updateDetails(
                    incidentId, incident.version(), null, review.reviewerRunId(), null, null, false);

            if (review.draft().clarificationRequest() != null
                    && incident.clarificationCount() < incident.maxClarifications()) {
                ClarificationResult clarified = clarify(
                        incident, review, conflicts, gaps, planning.plan());
                incident = clarified.incident();
                evidence = clarified.evidence();
                conflicts = clarified.conflicts();
                gaps = clarified.gaps();
                review = clarified.review();
            }
            else {
                continuationRuntime.completeWaitingInput(review.reviewerRunId());
                budgets.settleStored(review.budget(), review.reviewerRunId());
                completeWaitingTasks(incidentId);
            }

            ReviewerAssessmentDraft authoritativeDraft = validOrFallbackDraft(
                    snapshot, evidence, conflicts, review.draft());
            IncidentAssessment assessment = assessmentAssembler.assemble(
                    snapshot, evidence, conflicts, gaps, authoritativeDraft);
            incident = current(incidentId);
            incident = incidentStore.updateDetails(
                    incidentId, incident.version(), null, null, null,
                    objectMapper.convertValue(assessment, Map.class), false);
            IncidentStatus terminal = switch (assessment.outcome()) {
                case ASSESSED -> IncidentStatus.ASSESSED;
                case PARTIAL -> IncidentStatus.PARTIAL;
                case MANUAL_REVIEW -> IncidentStatus.MANUAL_REVIEW;
            };
            incident = transition(incident, terminal, "incident-terminal-" + terminal.name().toLowerCase());
            IncidentAggregate aggregate = incidentStore.findAggregate(incidentId, 10_000).orElseThrow();
            budgets.completeIncident(incidentId);
            return new IncidentInvestigationResult(incident, assessment, aggregate);
        }
        catch (RuntimeException exception) {
            failIncident(incidentId, exception);
            throw exception;
        }
    }

    public Optional<IncidentInvestigationResult> resumeAfterRecoveredTasks(String incidentId) {
        IncidentRecord incident = current(incidentId);
        if (incident.status() != IncidentStatus.INVESTIGATING) return Optional.empty();
        List<AgentTaskRecord> tasks = taskStore.listTasks(incidentId);
        if (tasks.isEmpty() || tasks.stream().anyMatch(task -> task.status() == AgentTaskStatus.PENDING
                || task.status() == AgentTaskStatus.CLAIMED || task.status() == AgentTaskStatus.RUNNING
                || task.status() == AgentTaskStatus.RETRY_PENDING)) {
            return Optional.empty();
        }
        DelegationPlan plan = objectMapper.convertValue(incident.delegationPlan(), DelegationPlan.class);
        List<EvidenceGap> gaps = tasks.stream()
                .filter(task -> task.status() == AgentTaskStatus.FAILED
                        || task.status() == AgentTaskStatus.TIMED_OUT
                        || task.status() == AgentTaskStatus.CANCELLED)
                .map(task -> new EvidenceGap("SPECIALIST_RECOVERY_FAILED", "phase3-recovery",
                        task.taskId() + ": " + String.valueOf(task.lastError())))
                .toList();
        try {
            incident = transition(incident, IncidentStatus.CHECKING_CONSISTENCY,
                    "phase3-incident-checking-after-task-takeover");
            List<EvidenceRecord> evidence = evidenceStore.listEvidence(incidentId);
            Set<EvidenceSubtype> required = plan.tasks().stream()
                    .flatMap(task -> task.requiredEvidenceSubtypes().stream()).collect(Collectors.toSet());
            EvidenceConsistencyResult consistency = consistencyChecker.check(incident.snapshot(), evidence, required);
            List<EvidenceConflict> conflicts = persistConflicts(incidentId, consistency.conflicts());
            persistTrust(incidentId, trustAssessor.assess(incident.snapshot(), evidence, conflicts));

            incident = transition(current(incidentId), IncidentStatus.REVIEWING,
                    "phase3-incident-reviewing-after-task-takeover");
            ReviewResult review = review(incident, evidence, conflicts, gaps);
            incident = incidentStore.updateDetails(
                    incidentId, incident.version(), null, review.reviewerRunId(), null, null, false);
            if (review.draft().clarificationRequest() != null
                    && incident.clarificationCount() < incident.maxClarifications()) {
                ClarificationResult clarified = clarify(incident, review, conflicts, gaps, plan);
                incident = clarified.incident();
                evidence = clarified.evidence();
                conflicts = clarified.conflicts();
                gaps = clarified.gaps();
                review = clarified.review();
            } else {
                continuationRuntime.completeWaitingInput(review.reviewerRunId());
                budgets.settleStored(review.budget(), review.reviewerRunId());
                completeWaitingTasks(incidentId);
            }
            ReviewerAssessmentDraft authoritativeDraft = validOrFallbackDraft(
                    incident.snapshot(), evidence, conflicts, review.draft());
            IncidentAssessment assessment = assessmentAssembler.assemble(
                    incident.snapshot(), evidence, conflicts, gaps, authoritativeDraft);
            incident = current(incidentId);
            incident = incidentStore.updateDetails(
                    incidentId, incident.version(), null, null, null,
                    objectMapper.convertValue(assessment, Map.class), false);
            IncidentStatus terminal = switch (assessment.outcome()) {
                case ASSESSED -> IncidentStatus.ASSESSED;
                case PARTIAL -> IncidentStatus.PARTIAL;
                case MANUAL_REVIEW -> IncidentStatus.MANUAL_REVIEW;
            };
            incident = transition(incident, terminal, "phase3-incident-terminal-" + terminal.name().toLowerCase());
            IncidentAggregate aggregate = incidentStore.findAggregate(incidentId, 10_000).orElseThrow();
            budgets.completeIncident(incidentId);
            return Optional.of(new IncidentInvestigationResult(incident, assessment, aggregate));
        } catch (com.agent.platform.ordercare.incident.persistence.IncidentCasConflictException contention) {
            return Optional.empty();
        } catch (RuntimeException exception) {
            failIncident(incidentId, exception);
            throw exception;
        }
    }

    /**
     * 让 Commander（指挥官 Agent）制定事故调查方案
     */
    private ToolDelegationResult delegateWithSubAgentTools(IncidentRecord incident,
                                                           IncidentInvestigationRequest request) {
        boolean mqRequired = !incident.snapshot().businessScope().queueNames().isEmpty();
        // 找出 commander 控制子 agent 的执行配置
        AgentExecutionProfile profile = profileFactory.commanderWithSubAgents(mqRequired);
        IncidentBudgetReservation budget = budgets.reserveIncidentRun(
                incident.incidentId(), "commander-subagent-tools", "COMMANDER", profile);
        String prompt = """
                请通过已提供的 Specialist Tools 对当前服务器绑定的事故快照执行只读调查。
                incidentId=%s
                alertType=%s
                symptom=%s
                requestIdCount=%d
                queueCount=%d
                业务标识和队列范围只用于说明，禁止把它们复制进 Tool arguments；每个 Tool 只填写 objective。
                """.formatted(
                incident.incidentId(), request.alertType(), request.symptom(),
                incident.snapshot().orderScope().requestIds().size(),
                incident.snapshot().businessScope().queueNames().size()).trim();
        AgentRuntimeResult commander = continuationRuntime.runUntilInputCheckpoint(
                new AgentRequest(
                        "incident:" + incident.incidentId() + ":commander",
                        "incident-commander", prompt,
                        Map.of(
                                "incidentId", incident.incidentId(),
                                "snapshotId", incident.snapshot().snapshotId(),
                                "scopeHash", incident.snapshot().scopeHash(),
                                "parentIncidentId", incident.incidentId(),
                                "runRole", "COMMANDER",
                                "delegationDepth", 0),
                        SCENARIO_ID),
                profile, event -> { });
        if (commander.state() != AgentRunState.WAITING_INPUT) {
            budgets.settle(budget, commander);
        }

        // 模型遗漏某个必需角色时仍通过同一幂等领域入口补齐；不会创建第二套任务或 Child Run。
        DelegationPlan plan = fallbackPlanFactory.create(incident.snapshot());
        List<IncidentTaskExecution> executions = new ArrayList<>();
        for (DelegationPlan.DelegatedTask required : plan.tasks()) {
            IncidentSubAgentTaskService.DelegationOutcome outcome = subAgentTaskService.delegate(
                    incident.snapshot(), commander.runId(), required.role(), required.objective());
            List<EvidenceGap> gaps = new ArrayList<>(outcome.execution().gaps());
            AgentTaskRecord task = outcome.execution().task();
            if (task.childRunId() != null && !task.childRunId().isBlank()) {
                gaps.addAll(evidenceProjector.projectGaps(toolExecutionStore.findByRun(task.childRunId())));
            }
            executions.add(subAgentTaskService.executionFromStored(task, List.copyOf(gaps)));
        }
        DelegationPlan persistedPlan = new DelegationPlan(
                "delegation-plan-v1", incident.incidentId(),
                "Commander 通过受约束的 SubAgent Tools 完成只读 Specialist 委派；Java 仅校验并补齐必需角色。",
                plan.tasks());
        return new ToolDelegationResult(commander.runId(), persistedPlan, List.copyOf(executions));
    }

    /**
     * 让 Commander（指挥官 Agent）制定事故调查方案
     */
    private PlanningResult plan(IncidentRecord incident, IncidentInvestigationRequest request) {
        // 根据事故快照里是否涉及 MQ（消息队列），决定需要哪些 Specialist 角色
        boolean mqRequired = !incident.snapshot().businessScope().queueNames().isEmpty();
        String requiredRoles = mqRequired
                ? "ORDER_ANALYST、INVENTORY_ANALYST、MQ_ANALYST"
                : "ORDER_ANALYST、INVENTORY_ANALYST";
        String mqInstruction = mqRequired
                ? "MQ_ANALYST 同时核对持久化死信事实和消息队列运行态。"
                : "没有权威 queueName，不得创建 MQ_ANALYST。";
        // 构造 Commander 的 prompt
        String prompt = """
                生成 delegation-plan-v1 JSON。必须且只能包含 %s。
                %s
                incidentId=%s
                alertType=%s
                symptom=%s
                requestIdCount=%d
                queueCount=%d
                不得输出工具名、预算、服务地址、新范围、恢复或写操作。
                """.formatted(
                requiredRoles, mqInstruction, incident.incidentId(), request.alertType(), request.symptom(),
                incident.snapshot().orderScope().requestIds().size(),
                incident.snapshot().businessScope().queueNames().size()).trim();
        // 获取"指挥官（Commander）Agent 的执行配置"
        AgentExecutionProfile profile = profileFactory.commander();
        // 为"一次 Agent Run"（比如指挥官这次运行）预留预算——在真正调用模型之前，先从该 Incident 的预算账户里占住一块额度，防止超支
        IncidentBudgetReservation budget = budgets.reserveIncidentRun(
                incident.incidentId(), "commander", "COMMANDER", profile);
        AgentRuntimeResult result = agentRuntime.run(
                new AgentRequest(
                        "incident:" + incident.incidentId() + ":commander",
                        "incident-commander", prompt,
                        Map.of("incidentId", incident.incidentId(), "parentIncidentId", incident.incidentId(),
                                "runRole", "COMMANDER"), SCENARIO_ID),
                profile, event -> { });
        budgets.settle(budget, result);
        DelegationPlan plan = null;
        if (result.state() == AgentRunState.COMPLETED) {
            try {
                plan = objectMapper.readValue(json(result.answer()), DelegationPlan.class);
            }
            catch (RuntimeException ignored) {
                plan = null;
            }
        }
        if (!planValidator.validate(plan, incident.snapshot()).valid()) {
            plan = fallbackPlanFactory.create(incident.snapshot());
        }
        return new PlanningResult(result.runId(), plan);
    }

    private List<AgentTaskRecord> createTasks(IncidentRecord incident, DelegationPlan plan) {
        List<AgentTaskRecord> tasks = new ArrayList<>();
        for (DelegationPlan.DelegatedTask delegated : plan.tasks()) {
            Instant now = Instant.now();
            tasks.add(taskStore.create(new AgentTaskRecord(
                    "task-" + UUID.randomUUID(), incident.incidentId(), delegated.clientTaskKey(),
                    "INCIDENT_INVESTIGATION", delegated.role().name(), delegated.objective(),
                    delegated.priority(), delegated.dependencies(), delegated.requiredEvidenceSubtypes(),
                    Map.of("snapshotId", incident.snapshot().snapshotId(),
                            "scopeHash", incident.snapshot().scopeHash()),
                    Map.of(), AgentTaskStatus.PENDING, 0, 2, null, null,
                    incident.snapshot().deadlineAt(), null, null, 0, null, "", 0, now, now)));
        }
        return List.copyOf(tasks);
    }

    private ReviewResult review(IncidentRecord incident,
                                List<EvidenceRecord> evidence,
                                List<EvidenceConflict> conflicts,
                                List<EvidenceGap> gaps) {
        IncidentReviewerAgentService.ReviewAgentOutcome outcome = reviewerAgentService.review(
                incident, evidence, conflicts, gaps);
        return new ReviewResult(outcome.reviewerRunId(), outcome.draft(), outcome.budget());
    }

    private ReviewResult reviewThroughCommanderTool(IncidentRecord incident,
                                                     List<EvidenceRecord> evidence,
                                                     List<EvidenceConflict> conflicts,
                                                     List<EvidenceGap> gaps,
                                                     String commanderRunId) {
        var commanderRun = agentRunStore.find(commanderRunId).orElse(null);
        if (commanderRun == null || commanderRun.state() != AgentRunState.WAITING_INPUT) {
            // 第一阶段没有形成可续跑检查点时，不能伪造第二阶段输入，直接复用 Java Reviewer。
            return review(incident, evidence, conflicts, gaps);
        }
        AgentExecutionProfile commanderProfile = profileFactory.commanderWithSubAgents(
                !incident.snapshot().businessScope().queueNames().isEmpty());
        IncidentBudgetReservation commanderBudget = budgets.reserveIncidentRun(
                incident.incidentId(), "commander-subagent-tools", "COMMANDER", commanderProfile);
        AgentRuntimeResult continued;
        try {
            continued = continuationRuntime.continueWithInput(
                    commanderRunId,
                    new AgentFollowUpInput(
                            "follow-up-task-v1", "REVIEW_READY", "", "", List.of(),
                            "REVIEW_READY：Specialist 已全部汇合，Java 一致性检查已完成。"
                                    + "现在必须且只能调用一次 review_incident_evidence，objective 说明审查已规范化证据和冲突；"
                                    + "不得再次调用任何 Specialist Tool。",
                            1, 2_000,
                            Map.of("incidentId", incident.incidentId(), "stateGate", "REVIEWING")),
                    event -> { });
        }
        catch (RuntimeException exception) {
            // 续跑前置校验、租约或模型调用失败时，不让 Reviewer 阶段拖垮整个 Incident。
            try {
                continuationRuntime.completeWaitingInput(commanderRunId);
            }
            catch (RuntimeException ignored) {
                // best effort：即使 Commander 检查点清理失败，仍允许独立 Reviewer 完成业务审查。
            }
            budgets.settleStored(commanderBudget, commanderRunId);
            return review(incident, evidence, conflicts, gaps);
        }
        if (continued.state() != AgentRunState.WAITING_INPUT) {
            budgets.settle(commanderBudget, continued);
        }

        var reviewerExecution = toolExecutionStore.findByRun(commanderRunId).stream()
                .filter(item -> com.agent.platform.ordercare.incident.tool.IncidentToolCatalog
                        .REVIEW_INCIDENT_EVIDENCE.equals(item.toolName()))
                .filter(item -> item.result() != null)
                .max(java.util.Comparator.comparing(com.agent.platform.runtime.ToolExecutionRecord::updatedAt))
                .orElse(null);
        if (reviewerExecution == null) {
            // 模型未遵守第二阶段调用协议时，仍复用同一个 Reviewer 创建服务完成安全降级。
            return review(incident, evidence, conflicts, gaps);
        }
        if (!reviewerExecution.result().success()) {
            String errorCode = String.valueOf(
                    reviewerExecution.result().metadata().getOrDefault("errorCode", ""));
            String failedReviewerRunId = String.valueOf(
                    reviewerExecution.result().metadata().getOrDefault("reviewerRunId", ""));
            if ("REVIEWER_OUTPUT_INVALID".equals(errorCode)
                    && !failedReviewerRunId.isBlank()
                    && reviewerExecution.result().content() != null
                    && !reviewerExecution.result().content().isBlank()) {
                LOGGER.warn("Commander Reviewer Tool returned an invalid final draft; incidentId={}, reviewerRunId={}, errors={}",
                        incident.incidentId(), failedReviewerRunId,
                        reviewerExecution.result().metadata().getOrDefault("validationErrors", List.of()));
                IncidentBudgetReservation reviewerBudget = budgets.reserveIncidentRun(
                        incident.incidentId(), "reviewer", "REVIEWER", profileFactory.reviewer());
                return new ReviewResult(
                        failedReviewerRunId,
                        reviewerDraftParser.parse(reviewerExecution.result().content()),
                        reviewerBudget);
            }
            return review(incident, evidence, conflicts, gaps);
        }
        ReviewerAssessmentDraft draft = reviewerDraftParser.parse(reviewerExecution.result().content());
        String reviewerRunId = String.valueOf(
                reviewerExecution.result().metadata().getOrDefault("reviewerRunId", ""));
        if (reviewerRunId.isBlank()) {
            return review(incident, evidence, conflicts, gaps);
        }
        IncidentBudgetReservation reviewerBudget = budgets.reserveIncidentRun(
                incident.incidentId(), "reviewer", "REVIEWER", profileFactory.reviewer());
        return new ReviewResult(reviewerRunId, draft, reviewerBudget);
    }

    private ClarificationResult clarify(IncidentRecord incident,
                                        ReviewResult initialReview,
                                        List<EvidenceConflict> conflicts,
                                        List<EvidenceGap> gaps,
                                        DelegationPlan plan) {
        ReviewerAssessmentDraft.ClarificationRequest request = initialReview.draft().clarificationRequest();
        AgentTaskRecord task = taskStore.findTask(request.taskId()).orElse(null);
        EvidenceConflict conflict = conflicts.stream()
                .filter(item -> item.conflictId().equals(request.conflictId()))
                .findFirst().orElse(null);
        if (task == null || conflict == null || task.status() != AgentTaskStatus.WAITING_CLARIFICATION
                || request.question().isBlank()) {
            continuationRuntime.completeWaitingInput(initialReview.reviewerRunId());
            budgets.settleStored(initialReview.budget(), initialReview.reviewerRunId());
            completeWaitingTasks(incident.incidentId());
            return new ClarificationResult(
                    incident, evidenceStore.listEvidence(incident.incidentId()), conflicts, gaps,
                    new ReviewResult(initialReview.reviewerRunId(), fallbackDraft(
                            evidenceStore.listEvidence(incident.incidentId()), conflicts), initialReview.budget()));
        }
        incident = transition(incident, IncidentStatus.CLARIFYING, "incident-clarifying");
        incident = incidentStore.updateDetails(
                incident.incidentId(), incident.version(), null, null, null, null, true);
        eventStore.appendEvent(communicationEvent(
                incident.incidentId(), task, TaskEventType.CLARIFICATION_REQUEST,
                "INCIDENT_REVIEWER", task.role(), request.conflictId(),
                Map.of("question", request.question(), "relatedEvidenceIds", request.relatedEvidenceIds())));

        IncidentTaskExecution clarified = taskScheduler.clarify(task, new AgentFollowUpInput(
                "follow-up-task-v1", "EVIDENCE_CLARIFICATION", task.taskId(), conflict.conflictId(),
                request.relatedEvidenceIds(), request.question(), 1, 1_200, Map.of()));
        completeOtherWaitingTasks(incident.incidentId(), task.taskId());
        List<EvidenceGap> updatedGaps = new ArrayList<>(gaps);
        updatedGaps.addAll(clarified.gaps());
        eventStore.appendEvent(communicationEvent(
                incident.incidentId(), task, TaskEventType.CLARIFICATION_RESPONSE,
                task.role(), "INCIDENT_REVIEWER", request.conflictId(),
                Map.of("successful", clarified.successful(),
                        "newEvidenceIds", clarified.evidence().stream().map(EvidenceRecord::evidenceId).toList())));

        List<EvidenceRecord> updatedEvidence = evidenceStore.listEvidence(incident.incidentId());
        Set<EvidenceSubtype> required = plan.tasks().stream()
                .flatMap(item -> item.requiredEvidenceSubtypes().stream()).collect(Collectors.toSet());
        EvidenceConsistencyResult consistency = consistencyChecker.check(
                incident.snapshot(), updatedEvidence, required);
        List<EvidenceConflict> updatedConflicts = persistConflicts(
                incident.incidentId(), consistency.conflicts());
        persistTrust(incident.incidentId(), trustAssessor.assess(
                incident.snapshot(), updatedEvidence, updatedConflicts));

        String reviewerFollowUp = reviewerPrompt(
                incident.snapshot(), updatedEvidence, updatedConflicts, updatedGaps);
        AgentRuntimeResult finalReview = continuationRuntime.continueWithInput(
                initialReview.reviewerRunId(),
                new AgentFollowUpInput(
                        "follow-up-task-v1", "REVIEW_UPDATED_EVIDENCE", task.taskId(), conflict.conflictId(),
                        request.relatedEvidenceIds(), reviewerFollowUp, 0, 1_500, Map.of()),
                event -> { });
        budgets.settle(initialReview.budget(), finalReview);
        ReviewerAssessmentDraft finalDraft = reviewerDraftParser.parse(finalReview.answer());
        incident = current(incident.incidentId());
        incident = transition(incident, IncidentStatus.REVIEWING, "incident-reviewing-after-clarification");
        return new ClarificationResult(
                incident, updatedEvidence, updatedConflicts, List.copyOf(updatedGaps),
                new ReviewResult(initialReview.reviewerRunId(), finalDraft, initialReview.budget()));
    }

    private List<EvidenceConflict> persistConflicts(String incidentId, List<EvidenceConflict> conflicts) {
        List<EvidenceConflict> persisted = new ArrayList<>();
        for (EvidenceConflict conflict : conflicts) {
            TaskEventRecord event = eventStore.appendEvent(new TaskEventRecord(
                    conflict.conflictId(), incidentId, null, null, 0,
                    TaskEventType.EVIDENCE_CONFLICT_DETECTED, TaskEventCategory.LIFECYCLE,
                    TaskEventActorType.SYSTEM, "evidence-consistency-checker", null, null, 0,
                    incidentId, null, "conflict:" + incidentId + ":" + conflict.metricKey()
                            + ":" + Integer.toHexString(conflict.details().hashCode()),
                    objectMapper.convertValue(conflict, Map.class), Instant.now()));
            persisted.add(new EvidenceConflict(
                    event.eventId(), conflict.conflictType(), conflict.metricKey(), conflict.severity(),
                    conflict.relatedEvidenceIds(), conflict.details(), conflict.status()));
        }
        return List.copyOf(persisted);
    }

    private void persistTrust(String incidentId, List<EvidenceTrust> trust) {
        for (EvidenceTrust assessment : trust) {
            eventStore.appendEvent(new TaskEventRecord(
                    UUID.randomUUID().toString(), incidentId, null, null, 0,
                    TaskEventType.EVIDENCE_TRUST_ASSESSED, TaskEventCategory.LIFECYCLE,
                    TaskEventActorType.SYSTEM, "evidence-trust-assessor", null, null, 0,
                    incidentId, null, "trust:" + incidentId + ":" + assessment.evidenceId()
                            + ":" + assessment.crossValidationLabel()
                            + ":" + assessment.trustScore(),
                    objectMapper.convertValue(assessment, Map.class), Instant.now()));
        }
    }

    private TaskEventRecord communicationEvent(String incidentId,
                                               AgentTaskRecord task,
                                               TaskEventType type,
                                               String sender,
                                               String recipient,
                                               String causationId,
                                               Map<String, Object> payload) {
        return new TaskEventRecord(
                UUID.randomUUID().toString(), incidentId, task.taskId(), task.childRunId(), 0,
                type, TaskEventCategory.COMMUNICATION, TaskEventActorType.ORCHESTRATOR,
                "incident-orchestrator", sender, recipient, 2, task.taskId(), causationId,
                type.name().toLowerCase() + ":" + task.taskId() + ":" + causationId,
                payload, Instant.now());
    }

    private void completeWaitingTasks(String incidentId) {
        taskStore.listTasks(incidentId).stream()
                .filter(task -> task.status() == AgentTaskStatus.WAITING_CLARIFICATION)
                .forEach(taskScheduler::completeWithoutClarification);
    }

    private void completeOtherWaitingTasks(String incidentId, String clarifiedTaskId) {
        taskStore.listTasks(incidentId).stream()
                .filter(task -> task.status() == AgentTaskStatus.WAITING_CLARIFICATION)
                .filter(task -> !task.taskId().equals(clarifiedTaskId))
                .forEach(taskScheduler::completeWithoutClarification);
    }

    private ReviewerAssessmentDraft validOrFallbackDraft(IncidentSnapshot snapshot,
                                                          List<EvidenceRecord> evidence,
                                                          List<EvidenceConflict> conflicts,
                                                          ReviewerAssessmentDraft draft) {
        try {
            assessmentAssembler.assemble(snapshot, evidence, conflicts, List.of(), draft);
            return draft;
        }
        catch (IncidentAssessmentValidationException exception) {
            LOGGER.warn("Reviewer draft rejected; using deterministic fallback; incidentId={}, validationErrors={}",
                    snapshot.incidentId(), exception.validationErrors());
            return fallbackDraft(evidence, conflicts);
        }
    }

    private ReviewerAssessmentDraft fallbackDraft(List<EvidenceRecord> evidence,
                                                  List<EvidenceConflict> conflicts) {
        List<ReviewerAssessmentDraft.ConfirmedFactDraft> facts = evidence.stream()
                .filter(item -> item.status() == com.agent.platform.ordercare.incident.model.EvidenceStatus.ACCEPTED)
                .filter(item -> item.evidenceClass() == com.agent.platform.ordercare.incident.model.EvidenceClass.FACT)
                .map(item -> new ReviewerAssessmentDraft.ConfirmedFactDraft(
                        item.evidenceSubtype(),
                        "已采集并校验 " + item.evidenceSubtype() + " 只读事实",
                        List.of(item.evidenceId())))
                .toList();
        return new ReviewerAssessmentDraft(
                "reviewer-assessment-v1", facts, List.of(), List.of(), null,
                conflicts.stream().map(EvidenceConflict::conflictId).toList());
    }

    private String reviewerPrompt(IncidentSnapshot snapshot,
                                  List<EvidenceRecord> evidence,
                                  List<EvidenceConflict> conflicts,
                                  List<EvidenceGap> gaps) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("schemaVersion", "reviewer-input-v1");
        payload.put("incidentId", snapshot.incidentId());
        payload.put("snapshotId", snapshot.snapshotId());
        payload.put("scopeHash", snapshot.scopeHash());
        payload.put("evidence", evidence);
        payload.put("javaConflicts", conflicts);
        payload.put("evidenceGaps", gaps);
        return """
                只基于下列结构化数据返回 reviewer-assessment-v1 JSON，不要添加 Markdown 代码块，不要增加 schema 名称外层包装。
                必须严格使用以下顶层结构，confirmedFacts、rootCauseCandidates、recommendations 始终是数组，禁止使用 confirmedFact、rootCause、recommendation 单数字段：
                {"schemaVersion":"reviewer-assessment-v1","confirmedFacts":[{"evidenceSubtype":"DEAD_LETTER_SET","statement":"...","evidenceIds":["..."]}],"rootCauseCandidates":[{"hypothesis":"...","supportingEvidenceIds":["..."],"relatedConflictIds":[]}],"recommendations":[{"action":"建议值班人员核对恢复前置条件并进入受控 Proposal 流程","evidenceIds":["..."],"conflictIds":[]}],"clarificationRequest":null,"acknowledgedConflictIds":[]}
                每个 confirmedFact 只能引用与 evidenceSubtype 一致的 ACCEPTED FACT；rootCauseCandidate 和 recommendation 必须引用有效 evidenceId 或 conflictId。
                如果输入存在 ACCEPTED FACT，confirmedFacts 不得为空；不得遗漏 OPEN HIGH conflict；最多提出一次 clarificationRequest。
                输入：
                """
                + objectMapper.writeValueAsString(payload);
    }

    /**
     * 把 Incident 从一个状态（如 PLANNING）安全地迁移到目标状态（如 INVESTIGATING），同时保证并发安全、幂等、合法性校验和审计事件记录。
     */
    private IncidentRecord transition(IncidentRecord incident,
                                      IncidentStatus target,
                                      String key) {
        return incidentStore.transitionStatus(
                incident.incidentId(), incident.version(), target,
                TaskEventActorType.ORCHESTRATOR, "incident-orchestrator",
                key + ":" + incident.incidentId());
    }

    private IncidentRecord current(String incidentId) {
        return incidentStore.find(incidentId).orElseThrow();
    }

    private void failIncident(String incidentId, RuntimeException exception) {
        try {
            IncidentRecord current = current(incidentId);
            if (!current.status().terminal()) {
                incidentStore.transitionStatus(
                        incidentId, current.version(), IncidentStatus.FAILED,
                        TaskEventActorType.SYSTEM, "incident-failure-boundary",
                        "incident-failed:" + incidentId);
            }
        }
        catch (RuntimeException suppressed) {
            exception.addSuppressed(suppressed);
        }
    }

    private String json(String value) {
        String normalized = value == null ? "" : value.trim();
        if (normalized.startsWith("```")) {
            int firstLine = normalized.indexOf('\n');
            int closing = normalized.lastIndexOf("```");
            if (firstLine >= 0 && closing > firstLine) {
                normalized = normalized.substring(firstLine + 1, closing).trim();
            }
        }
        int objectStart = normalized.indexOf('{');
        int objectEnd = normalized.lastIndexOf('}');
        return objectStart >= 0 && objectEnd > objectStart
                ? normalized.substring(objectStart, objectEnd + 1)
                : normalized;
    }

    private record PlanningResult(String commanderRunId, DelegationPlan plan) {}
    private record ToolDelegationResult(String commanderRunId,
                                        DelegationPlan plan,
                                        List<IncidentTaskExecution> executions) {}
    private record ReviewResult(String reviewerRunId,
                                ReviewerAssessmentDraft draft,
                                IncidentBudgetReservation budget) {}
    private record ClarificationResult(
            IncidentRecord incident,
            List<EvidenceRecord> evidence,
            List<EvidenceConflict> conflicts,
            List<EvidenceGap> gaps,
            ReviewResult review) {}
}
