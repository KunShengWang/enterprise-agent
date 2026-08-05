package com.agent.platform.ordercare.incident.application;

import com.agent.platform.agent.AgentRequest;
import com.agent.platform.ordercare.incident.model.EvidenceClass;
import com.agent.platform.ordercare.incident.model.EvidenceConflict;
import com.agent.platform.ordercare.incident.model.EvidenceGap;
import com.agent.platform.ordercare.incident.model.EvidenceRecord;
import com.agent.platform.ordercare.incident.model.EvidenceStatus;
import com.agent.platform.ordercare.incident.model.IncidentRecord;
import com.agent.platform.ordercare.incident.model.ReviewerAssessmentDraft;
import com.agent.platform.runtime.AgentContinuationRuntime;
import com.agent.platform.runtime.AgentExecutionProfile;
import com.agent.platform.runtime.AgentFollowUpInput;
import com.agent.platform.runtime.AgentRunState;
import com.agent.platform.runtime.AgentRunStore;
import com.agent.platform.runtime.AgentRuntimeResult;
import com.agent.platform.workbench.budget.IncidentBudgetGate;
import com.agent.platform.workbench.budget.IncidentBudgetReservation;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import tools.jackson.databind.ObjectMapper;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/** Reviewer Child Run 的唯一创建入口，可被 Java 兼容路径和 Reviewer Tool 共同复用。 */
@Service
public class IncidentReviewerAgentService {

    private static final Logger LOGGER = LoggerFactory.getLogger(IncidentReviewerAgentService.class);
    private static final String SCENARIO_ID = "ordercare-incident-command-v1";
    private static final String REVIEWER_OUTPUT_CONTRACT = """
            reviewer-assessment-v1 的字段结构必须严格如下，所有嵌套对象都禁止增加、删除或改名字段：
            {
              "schemaVersion": "reviewer-assessment-v1",
              "confirmedFacts": [
                {
                  "evidenceSubtype": "ORDER_STATUS_SET",
                  "statement": "基于证据确认的事实陈述",
                  "evidenceIds": ["真实 evidenceId"]
                }
              ],
              "rootCauseCandidates": [
                {
                  "hypothesis": "根因假设",
                  "supportingEvidenceIds": ["真实 evidenceId"],
                  "relatedConflictIds": ["真实 conflictId"]
                }
              ],
              "recommendations": [
                {
                  "action": "只读的后续核查建议",
                  "evidenceIds": ["真实 evidenceId"],
                  "conflictIds": ["真实 conflictId"]
                }
              ],
              "clarificationRequest": null,
              "acknowledgedConflictIds": ["已确认的真实 conflictId"]
            }
            clarificationRequest 非 null 时也只能是：
            {"taskId":"真实 taskId","conflictId":"真实 conflictId","relatedEvidenceIds":["真实 evidenceId"],"question":"需要补充的问题"}
            evidenceSubtype 只能使用 EvidenceSubtype 枚举值：
            ORDER_STATUS_SET、INVENTORY_DEDUCT_SET、INVENTORY_INVARIANT、DEAD_LETTER_SET、QUEUE_RUNTIME_STATUS、SOP_GUIDANCE、ROOT_CAUSE_CANDIDATE、RECOVERY_RECOMMENDATION。
            禁止使用错误字段 evidenceId、confidence、rationale；数组字段即使无值也必须保留并填写 []。
            recommendation.action 只能提出核查、确认、分析、观察或评估等只读建议。
            可以写“评估消息重放的影响”，但禁止要求“重放消息”“更新状态”“删除记录”或“执行恢复”。
            """;

    private final IncidentExecutionProfileFactory profileFactory;
    private final AgentContinuationRuntime continuationRuntime;
    private final ReviewerAssessmentDraftParser draftParser;
    private final IncidentAssessmentAssembler assessmentAssembler;
    private final IncidentBudgetGate budgets;
    private final AgentRunStore runStore;
    private final ObjectMapper objectMapper;
    private final AtomicInteger threadSequence = new AtomicInteger();
    private final ExecutorService executor = new ThreadPoolExecutor(
            2, 2, 30, TimeUnit.SECONDS, new ArrayBlockingQueue<>(8),
            runnable -> {
                Thread thread = new Thread(runnable,
                        "incident-reviewer-" + threadSequence.incrementAndGet());
                thread.setDaemon(true);
                return thread;
            },
            new ThreadPoolExecutor.AbortPolicy());

    public IncidentReviewerAgentService(IncidentExecutionProfileFactory profileFactory,
                                        AgentContinuationRuntime continuationRuntime,
                                        ReviewerAssessmentDraftParser draftParser,
                                        IncidentAssessmentAssembler assessmentAssembler,
                                        IncidentBudgetGate budgets,
                                        AgentRunStore runStore,
                                        ObjectMapper objectMapper) {
        this.profileFactory = profileFactory;
        this.continuationRuntime = continuationRuntime;
        this.draftParser = draftParser;
        this.assessmentAssembler = assessmentAssembler;
        this.budgets = budgets;
        this.runStore = runStore;
        this.objectMapper = objectMapper;
    }

    public ReviewAgentOutcome review(IncidentRecord incident,
                                     List<EvidenceRecord> evidence,
                                     List<EvidenceConflict> conflicts,
                                     List<EvidenceGap> gaps) {
        AgentExecutionProfile profile = profileFactory.reviewer();
        IncidentBudgetReservation budget = budgets.reserveIncidentRun(
                incident.incidentId(), "reviewer", "REVIEWER", profile);
        if (incident.reviewerRunId() != null && !incident.reviewerRunId().isBlank()) {
            var stored = runStore.find(incident.reviewerRunId()).orElse(null);
            if (stored != null && stored.answer() != null && !stored.answer().isBlank()) {
                ReviewerAssessmentDraft reusedDraft = draftParser.parse(stored.answer());
                List<String> reusedErrors = validationErrors(
                        incident, evidence, conflicts, gaps, reusedDraft);
                return new ReviewAgentOutcome(
                        stored.runId(), reusedDraft, budget, true, reusedErrors);
            }
        }

        String prompt = reviewerPrompt(incident, evidence, conflicts, gaps);
        AgentRuntimeResult result = CompletableFuture.supplyAsync(
                () -> continuationRuntime.runUntilInputCheckpoint(
                        new AgentRequest(
                                "incident:" + incident.incidentId() + ":reviewer",
                                "incident-reviewer", prompt,
                                Map.of(
                                        "incidentId", incident.incidentId(),
                                        "parentIncidentId", incident.incidentId(),
                                        "runRole", "REVIEWER",
                                        "internalSubAgent", true,
                                        "delegationDepth", 1),
                                SCENARIO_ID),
                        profile, event -> { }),
                executor).join();
        if (result.state() != AgentRunState.WAITING_INPUT) {
            budgets.settle(budget, result);
        }
        ReviewerAssessmentDraft draft = draftParser.parse(result.answer());
        List<String> validationErrors = validationErrors(incident, evidence, conflicts, gaps, draft);
        if (!validationErrors.isEmpty() && result.state() == AgentRunState.WAITING_INPUT) {
            result = continuationRuntime.continueWithInput(
                    result.runId(),
                    new AgentFollowUpInput(
                            "follow-up-task-v1", "REVIEW_OUTPUT_CORRECTION", "", "", List.of(),
                            correctionPrompt(evidence, validationErrors), 0, 1_800,
                            Map.of("validationErrors", validationErrors)),
                    event -> { });
            if (result.state() != AgentRunState.WAITING_INPUT) {
                budgets.settle(budget, result);
            }
            draft = draftParser.parse(result.answer());
            validationErrors = validationErrors(incident, evidence, conflicts, gaps, draft);
        }
        if (!validationErrors.isEmpty()) {
            LOGGER.warn("reviewer output failed authoritative validation after correction; incidentId={}, runId={}, errors={}",
                    incident.incidentId(), result.runId(), validationErrors);
        }
        return new ReviewAgentOutcome(
                result.runId(), draft, budget, false, validationErrors);
    }

    private String reviewerPrompt(IncidentRecord incident,
                                  List<EvidenceRecord> evidence,
                                  List<EvidenceConflict> conflicts,
                                  List<EvidenceGap> gaps) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("schemaVersion", "reviewer-input-v1");
        payload.put("incidentId", incident.incidentId());
        payload.put("snapshotId", incident.snapshot().snapshotId());
        payload.put("scopeHash", incident.snapshot().scopeHash());
        payload.put("evidence", evidence);
        payload.put("javaConflicts", conflicts);
        payload.put("evidenceGaps", gaps);
        List<Map<String, Object>> acceptedFactIndex = acceptedFactIndex(evidence);
        payload.put("acceptedFactIndex", acceptedFactIndex);
        payload.put("requiredConfirmedFactMinimum", acceptedFactIndex.stream()
                .map(item -> String.valueOf(item.get("evidenceSubtype")))
                .distinct()
                .count());
        return """
                只基于下列结构化数据返回 reviewer-assessment-v1 JSON，不要添加 Markdown 代码块。
                %s
                acceptedFactIndex 中每个不同的 evidenceSubtype 都必须单独生成至少一条 ConfirmedFact。
                一条 ConfirmedFact 的 evidenceIds 只能引用它声明的同一 evidenceSubtype 的 ACCEPTED FACT，
                禁止在同一条 ConfirmedFact 中混合不同 evidenceSubtype。只要 acceptedFactIndex 非空，
                confirmedFacts 就严禁返回空数组。
                rootCauseCandidate 和 recommendation 必须引用有效 evidenceId 或 conflictId。
                不得遗漏 OPEN HIGH conflict，最多提出一次 clarificationRequest。
                输入：
                """.formatted(REVIEWER_OUTPUT_CONTRACT) + objectMapper.writeValueAsString(payload);
    }

    private List<String> validationErrors(IncidentRecord incident,
                                          List<EvidenceRecord> evidence,
                                          List<EvidenceConflict> conflicts,
                                          List<EvidenceGap> gaps,
                                          ReviewerAssessmentDraft draft) {
        try {
            assessmentAssembler.assemble(incident.snapshot(), evidence, conflicts, gaps, draft);
            return List.of();
        }
        catch (IncidentAssessmentValidationException exception) {
            return exception.validationErrors();
        }
    }

    private String correctionPrompt(List<EvidenceRecord> evidence, List<String> validationErrors) {
        return """
                REVIEW_OUTPUT_CORRECTION：上一版 Reviewer JSON 未通过 Java 权威语义校验。
                validationErrors=%s
                acceptedFactIndex=%s
                %s
                请基于时间线中的原始 reviewer-input-v1 重写完整 reviewer-assessment-v1 JSON。
                必须修复所有 validationErrors；只要 acceptedFactIndex 非空，confirmedFacts 就不得为空，
                acceptedFactIndex 中每个不同的 evidenceSubtype 都必须单独生成至少一条 ConfirmedFact；
                每条 ConfirmedFact 只能引用同一 evidenceSubtype 的真实 evidenceId，禁止混合 subtype。
                本次纠错已经消耗唯一的后续输入机会，因此 clarificationRequest 必须为 null。
                只返回 JSON，不要输出 Markdown 或解释。
                """.formatted(
                objectMapper.writeValueAsString(validationErrors),
                objectMapper.writeValueAsString(acceptedFactIndex(evidence)),
                REVIEWER_OUTPUT_CONTRACT).trim();
    }

    private List<Map<String, Object>> acceptedFactIndex(List<EvidenceRecord> evidence) {
        return (evidence == null ? List.<EvidenceRecord>of() : evidence).stream()
                .filter(item -> item.evidenceClass() == EvidenceClass.FACT)
                .filter(item -> item.status() == EvidenceStatus.ACCEPTED)
                .map(item -> Map.<String, Object>of(
                        "evidenceId", item.evidenceId(),
                        "evidenceSubtype", item.evidenceSubtype().name()))
                .toList();
    }

    public record ReviewAgentOutcome(String reviewerRunId,
                                     ReviewerAssessmentDraft draft,
                                     IncidentBudgetReservation budget,
                                     boolean reused,
                                     List<String> validationErrors) {
        public ReviewAgentOutcome {
            validationErrors = validationErrors == null ? List.of() : List.copyOf(validationErrors);
        }

        public ReviewAgentOutcome(String reviewerRunId,
                                  ReviewerAssessmentDraft draft,
                                  IncidentBudgetReservation budget,
                                  boolean reused) {
            this(reviewerRunId, draft, budget, reused, List.of());
        }

        public boolean valid() {
            return validationErrors.isEmpty();
        }
    }
}
