package com.agent.platform.ordercare.incident.application;

import com.agent.platform.agent.AgentRequest;
import com.agent.platform.ordercare.incident.model.EvidenceConflict;
import com.agent.platform.ordercare.incident.model.EvidenceGap;
import com.agent.platform.ordercare.incident.model.EvidenceRecord;
import com.agent.platform.ordercare.incident.model.IncidentRecord;
import com.agent.platform.ordercare.incident.model.ReviewerAssessmentDraft;
import com.agent.platform.runtime.AgentContinuationRuntime;
import com.agent.platform.runtime.AgentExecutionProfile;
import com.agent.platform.runtime.AgentRunState;
import com.agent.platform.runtime.AgentRunStore;
import com.agent.platform.runtime.AgentRuntimeResult;
import com.agent.platform.workbench.budget.IncidentBudgetGate;
import com.agent.platform.workbench.budget.IncidentBudgetReservation;
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

    private static final String SCENARIO_ID = "ordercare-incident-command-v1";

    private final IncidentExecutionProfileFactory profileFactory;
    private final AgentContinuationRuntime continuationRuntime;
    private final ReviewerAssessmentDraftParser draftParser;
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
                                        IncidentBudgetGate budgets,
                                        AgentRunStore runStore,
                                        ObjectMapper objectMapper) {
        this.profileFactory = profileFactory;
        this.continuationRuntime = continuationRuntime;
        this.draftParser = draftParser;
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
                return new ReviewAgentOutcome(
                        stored.runId(), draftParser.parse(stored.answer()), budget, true);
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
        return new ReviewAgentOutcome(
                result.runId(), draftParser.parse(result.answer()), budget, false);
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
        return """
                只基于下列结构化数据返回 reviewer-assessment-v1 JSON，不要添加 Markdown 代码块。
                顶层结构必须为：
                {"schemaVersion":"reviewer-assessment-v1","confirmedFacts":[],"rootCauseCandidates":[],"recommendations":[],"clarificationRequest":null,"acknowledgedConflictIds":[]}
                confirmedFact 只能引用同 evidenceSubtype 的 ACCEPTED FACT；rootCauseCandidate 和 recommendation
                必须引用有效 evidenceId 或 conflictId。不得遗漏 OPEN HIGH conflict，最多提出一次 clarificationRequest。
                输入：
                """ + objectMapper.writeValueAsString(payload);
    }

    public record ReviewAgentOutcome(String reviewerRunId,
                                     ReviewerAssessmentDraft draft,
                                     IncidentBudgetReservation budget,
                                     boolean reused) { }
}
