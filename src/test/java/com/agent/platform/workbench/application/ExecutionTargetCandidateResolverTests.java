package com.agent.platform.workbench.application;

import com.agent.platform.ordercare.incident.config.IncidentCommandProperties;
import com.agent.platform.workbench.target.ExecutionTargetId;
import com.agent.platform.workbench.target.ExecutionTargetRegistry;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ExecutionTargetCandidateResolverTests {

    private final ExecutionTargetCandidateResolver resolver = new ExecutionTargetCandidateResolver();
    private final List<com.agent.platform.workbench.target.ExecutionTargetDefinition> targets = targets();

    @Test
    void completeOrderCareRequestIsResolvedDeterministicallyWithoutModel() {
        String goal = """
                请处理一个唯一的 OrderCare 单案例。
                案例标识：requestId=ORDERCARE-M05-REQUEST。
                请先查询该案例的订单、库存扣减和死信事实，并检索 OrderCare SOP。
                如果 FlowOrder 返回 diagnosisCode=REPLAY_CANDIDATE、recoveryEligible=true 且 hardRisks 为空，
                则创建恢复预演，申请人工审批；审批通过后执行恢复，并验证扣减记录、库存和死信是否最终收敛。
                """;

        var resolution = resolver.resolve(goal, targets);
        var result = resolution.deterministicResult().orElseThrow();

        assertEquals(List.of(ExecutionTargetId.ORDERCARE_CASE), resolution.candidates().stream()
                .map(candidate -> candidate.targetId()).toList());
        assertEquals(ExecutionTargetId.ORDERCARE_CASE.name(), result.decision().targetId());
        assertEquals("ORDERCARE-M05-REQUEST", result.decision().extractedInputs().get("requestId"));
        assertEquals(0, result.promptTokens() + result.completionTokens());
        assertFalse(resolution.requiresClarification());
    }

    @Test
    void multipleRequestIdsNarrowCandidatesToIncidentInvestigation() {
        var resolution = resolver.resolve(
                "调查 requestId=REQ-001,REQ-002 的批量库存未释放事故", targets);

        assertTrue(resolution.deterministicResult().isEmpty());
        assertEquals(List.of(ExecutionTargetId.INCIDENT_INVESTIGATION), resolution.candidates().stream()
                .map(candidate -> candidate.targetId()).toList());
    }

    @Test
    void explicitSingleRequestIncidentStillUsesIncidentCandidate() {
        var resolution = resolver.resolve(
                "调查 requestId=REQ-001 在队列 floworder.dlq 的一致性事故", targets);

        assertTrue(resolution.deterministicResult().isEmpty());
        assertEquals(List.of(ExecutionTargetId.INCIDENT_INVESTIGATION), resolution.candidates().stream()
                .map(candidate -> candidate.targetId()).toList());
    }

    @Test
    void incidentRecoveryPlanIntentIsNotCollapsedIntoInvestigation() {
        var resolution = resolver.resolve(
                "基于刚才已经完成的事故调查生成受控恢复计划", targets);

        assertTrue(resolution.deterministicResult().isEmpty());
        assertEquals(List.of(ExecutionTargetId.INCIDENT_RECOVERY_PLAN), resolution.candidates().stream()
                .map(candidate -> candidate.targetId()).toList());
    }

    @Test
    void explicitSingleCaseAndBatchScopeFailClosedForClarification() {
        var resolution = resolver.resolve(
                "请把唯一单案例 requestId=REQ-001 作为批量事故调查处理", targets);

        assertTrue(resolution.requiresClarification());
        assertEquals("", resolution.deterministicResult().orElseThrow().decision().targetId());
        assertEquals(List.of("executionScope"),
                resolution.deterministicResult().orElseThrow().decision().missingInputs());
    }

    @Test
    void unrelatedGoalKeepsEnabledCatalogForModelRouting() {
        var resolution = resolver.resolve("解释 Java CAS", targets);

        assertTrue(resolution.deterministicResult().isEmpty());
        assertEquals(4, resolution.candidates().size());
        assertEquals("AMBIGUOUS_MODEL_ROUTING", resolution.policyReason());
    }

    private List<com.agent.platform.workbench.target.ExecutionTargetDefinition> targets() {
        IncidentCommandProperties properties = new IncidentCommandProperties();
        properties.setEnabled(true);
        properties.setRecoveryPlannerEnabled(true);
        return new ExecutionTargetRegistry(properties).enabledTargets(
                new com.agent.platform.workbench.security.AuthenticatedPrincipal(
                        "tenant", "operator", java.util.Set.of("INCIDENT_OPERATOR")));
    }
}
