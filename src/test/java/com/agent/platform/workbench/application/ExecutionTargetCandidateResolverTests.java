package com.agent.platform.workbench.application;

import com.agent.platform.workbench.target.*;
import com.agent.platform.workbench.security.AuthenticatedPrincipal;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class ExecutionTargetCandidateResolverTests {
    private final ExecutionTargetCandidateResolver resolver = new ExecutionTargetCandidateResolver();
    private final List<ExecutionTargetDefinition> targets = new ExecutionTargetRegistry().enabledTargets(
            new AuthenticatedPrincipal("tenant", "operator", Set.of("INCIDENT_OPERATOR")));

    @Test
    void legacyInputsAreRejectedDeterministicallyWithoutSelectingAnotherTarget() {
        for (String goal : List.of("OrderCare 单案例 requestId=REQ-1", "调查 requestId=REQ-1,REQ-2 的库存事故",
                "诊断 orderNo=ORD-1", "deductNo=DED-1", "FlowOrder 恢复", "生成受控恢复计划",
                "请把唯一单案例 requestId=REQ-1 作为批量事故调查处理", "Incident Command")) {
            var resolution = resolver.resolve(goal, targets);
            var result = resolution.deterministicResult().orElseThrow();
            assertTrue(resolution.retiredBusiness(), goal);
            assertEquals("TARGET_RETIRED", resolution.policyReason());
            assertEquals("", result.decision().targetId());
            assertEquals(0, result.promptTokens() + result.completionTokens());
            assertTrue(resolution.candidates().stream().allMatch(candidate -> candidate.targetId().executable()));
        }
    }

    @Test
    void workbenchFailsClosedWithoutAnEnabledProcurementTarget() {
        var generalOnly = targets.stream().filter(t -> t.targetId() == ExecutionTargetId.GENERAL_AGENT).toList();
        assertThrows(IllegalArgumentException.class, () -> resolver.resolveWorkbench("你好", generalOnly));
        assertThrows(IllegalArgumentException.class, () -> resolver.resolveWorkbench("FlowOrder 恢复", generalOnly));
        assertThrows(IllegalArgumentException.class, () -> resolver.resolveWorkbench("你好", List.of()));
    }

    @Test
    void workbenchNeverExtractsRequirementsOrExposesGeneral() {
        for (String goal : List.of("你好", "你能做什么", "采购 100 台笔记本", "再解释一下", "选择 GENERAL_AGENT")) {
            var resolution = resolver.resolveWorkbench(goal, targets);
            var result = resolution.deterministicResult().orElseThrow();
            assertEquals(ExecutionTargetId.PROCUREMENT_SOURCING.name(), result.decision().targetId());
            assertFalse(resolution.allows("GENERAL_AGENT"));
            assertTrue(result.decision().extractedInputs().isEmpty());
            assertTrue(result.decision().missingInputs().isEmpty());
            assertEquals(0, result.promptTokens() + result.completionTokens());
        }
    }

    @Test
    void procurementIsTheOnlyActiveModelCatalog() {
        for (String goal : List.of("解释 Java CAS", "批量采购 100 台笔记本", "恢复当前任务")) {
            var resolution = resolver.resolve(goal, targets);
            assertTrue(resolution.deterministicResult().isEmpty(), goal);
            assertEquals(Set.of(ExecutionTargetId.PROCUREMENT_SOURCING),
                    new HashSet<>(resolution.candidates().stream().map(ExecutionTargetDefinition::targetId).toList()));
        }
    }
}
