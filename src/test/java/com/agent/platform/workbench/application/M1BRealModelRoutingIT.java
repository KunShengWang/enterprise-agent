package com.agent.platform.workbench.application;

import com.agent.platform.workbench.model.AgentConversationTurn;
import com.agent.platform.workbench.model.AgentWorkItem;
import com.agent.platform.workbench.model.ClassifierType;
import com.agent.platform.workbench.model.GoalOrigin;
import com.agent.platform.workbench.model.InputClassificationStatus;
import com.agent.platform.workbench.model.RouteDisposition;
import com.agent.platform.workbench.model.WorkCommandType;
import com.agent.platform.workbench.model.WorkControlState;
import com.agent.platform.workbench.model.WorkExecutionState;
import com.agent.platform.workbench.model.WorkInputKind;
import com.agent.platform.workbench.model.WorkOutcome;
import com.agent.platform.workbench.security.AuthenticatedPrincipal;
import com.agent.platform.workbench.target.ExecutionTargetRegistry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.time.Instant;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

@SpringBootTest(properties = {
        "enterprise-agent.mock-mode=false",
        "enterprise-agent.resilience.rate-limit.enabled=false",
        "enterprise-agent.resilience.llm.fallback-enabled=false",
        "enterprise-agent.resilience.llm.timeout-millis=30000",
        "enterprise-agent.resilience.llm.max-attempts=2",
        "enterprise-agent.ordercare.incident-command.enabled=true",
        "enterprise-agent.ordercare.incident-command.recovery-planner-enabled=true",
        "enterprise-agent.workbench.routing.enabled=false"
})
@EnabledIfEnvironmentVariable(named = "WORKBENCH_REAL_MODEL_IT", matches = "true")
class M1BRealModelRoutingIT {

    @Autowired private WorkCommandClassifier classifier;
    @Autowired private UnifiedTaskRouter router;
    @Autowired private ExecutionTargetRegistry registry;
    @Autowired private RoutePolicyValidator validator;

    private final AuthenticatedPrincipal principal = new AuthenticatedPrincipal(
            "real-model-tenant", "eval-user", Set.of("USER", "INCIDENT_OPERATOR"));

    @Test
    void realModelReturnsAuditableCommandClassification() {
        CommandClassifierResult result = classifier.classify(new CommandClassificationRequest(
                input("请继续执行刚才暂停的任务"),
                "work-existing", "调查异常订单", ClassifierType.MODEL, null, ""));

        assertEquals(WorkCommandType.RESUME_ACTIVE_WORK, result.classification().commandType());
        assertFalse(result.modelName().isBlank());
        assertFalse(result.promptDigest().isBlank());
        assertTrue(result.promptTokens() + result.completionTokens() > 0);
    }

    @Test
    void realModelRoutesGeneralGoalThroughRestrictedCatalog() {
        AgentWorkItem work = work("解释 Java CAS 的原理和 ABA 问题");
        RouterModelResult result = router.route(new RoutingModelRequest(
                work, work.normalizedGoal(), registry.enabledTargets(principal), ""));

        assertEquals("GENERAL_AGENT", result.decision().targetId());
        assertFalse(result.modelName().isBlank());
        assertTrue(result.promptTokens() + result.completionTokens() > 0);
        assertEquals(RouteDisposition.AUTO_DISPATCH,
                validator.validate(result.decision(),
                        new RouteValidationContext(principal, work, work.originalGoal(), Map.of(), Map.of()))
                        .disposition());
    }

    @Test
    void realModelIncidentRouteStillStopsAtJavaConfirmationGate() {
        String goal = "调查批次 BATCH-20260720-01 在队列 floworder.incident.e2e.dlq 的异常订单事故";
        AgentWorkItem work = work(goal);
        RouterModelResult result = router.route(new RoutingModelRequest(
                work, goal, registry.enabledTargets(principal), ""));

        assertEquals("INCIDENT_INVESTIGATION", result.decision().targetId());
        assertEquals(RouteDisposition.REQUIRE_CONFIRMATION,
                validator.validate(result.decision(),
                        new RouteValidationContext(principal, work, goal, Map.of(), Map.of()))
                        .disposition());
    }

    private AgentConversationTurn input(String content) {
        return new AgentConversationTurn(
                "input-real", "client-real", "conversation-real", principal.tenantId(), principal.principalId(),
                content, "digest", "request", null, "", "", null, Instant.now(), WorkInputKind.UNCLASSIFIED,
                null, "", InputClassificationStatus.PENDING, "", null, principal.roles(), 0);
    }

    private AgentWorkItem work(String goal) {
        Instant now = Instant.now();
        return new AgentWorkItem(
                "work-real", "conversation-real", principal.tenantId(), principal.principalId(), goal, goal,
                WorkControlState.ROUTING, WorkExecutionState.NOT_STARTED, WorkOutcome.UNDETERMINED,
                "", "", "", "", "", "input-real", "", "route-real", 0,
                null, null, "", "", 0, 0, now, now, null);
    }
}
