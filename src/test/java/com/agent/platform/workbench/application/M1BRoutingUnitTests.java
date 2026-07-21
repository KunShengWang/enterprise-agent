package com.agent.platform.workbench.application;

import com.agent.platform.config.WorkbenchRoutingProperties;
import com.agent.platform.llm.LlmService;
import com.agent.platform.llm.LlmUsage;
import com.agent.platform.ordercare.incident.config.IncidentCommandProperties;
import com.agent.platform.prompt.PromptRequest;
import com.agent.platform.workbench.model.AgentConversationTurn;
import com.agent.platform.workbench.model.AgentWorkItem;
import com.agent.platform.workbench.model.ClassifierType;
import com.agent.platform.workbench.model.ExecutionDecision;
import com.agent.platform.workbench.model.GoalOrigin;
import com.agent.platform.workbench.model.InputClassificationStatus;
import com.agent.platform.workbench.model.RouteDisposition;
import com.agent.platform.workbench.model.WorkCommandType;
import com.agent.platform.workbench.model.WorkControlState;
import com.agent.platform.workbench.model.WorkExecutionState;
import com.agent.platform.workbench.model.WorkInputKind;
import com.agent.platform.workbench.model.WorkOutcome;
import com.agent.platform.workbench.security.AuthenticatedPrincipal;
import com.agent.platform.workbench.target.ExecutionTargetId;
import com.agent.platform.workbench.target.ExecutionTargetRegistry;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;
import tools.jackson.databind.ObjectMapper;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class M1BRoutingUnitTests {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void deterministicCommandDoesNotCallModelAndDoesNotChooseTarget() {
        FakeLlm llm = new FakeLlm("unused", new LlmUsage(0, 0, 0, 0, 0, "", "test"));
        DefaultWorkCommandClassifier classifier = new DefaultWorkCommandClassifier(llm, objectMapper);

        CommandClassifierResult result = classifier.classify(new CommandClassificationRequest(
                input("继续刚才任务"), "work-1", "调查订单", ClassifierType.DETERMINISTIC_BUTTON,
                WorkCommandType.RESUME_ACTIVE_WORK, ""));

        assertEquals(WorkCommandType.RESUME_ACTIVE_WORK, result.classification().commandType());
        assertEquals(0, llm.calls);
        assertEquals(0, result.promptTokens());
    }

    @Test
    void modelClassifierOnlyProducesWorkCommandSemantics() {
        FakeLlm llm = new FakeLlm("""
                {"commandType":"START_NEW_WORK","modelConfidence":0.91,"reason":"new goal",
                 "targetWorkItemId":"","derivedGoalText":"解释 Java CAS"}
                """, usage("deepseek-chat", 31, 12));
        DefaultWorkCommandClassifier classifier = new DefaultWorkCommandClassifier(llm, objectMapper);

        CommandClassifierResult result = classifier.classify(new CommandClassificationRequest(
                input("先放着，另开任务解释 Java CAS"), "work-1", "调查订单", ClassifierType.MODEL, null, ""));

        assertEquals(WorkCommandType.START_NEW_WORK, result.classification().commandType());
        assertEquals("解释 Java CAS", result.classification().derivedGoalText());
        assertEquals(43, result.promptTokens() + result.completionTokens());
        assertFalse(result.promptDigest().isBlank());
    }

    @Test
    void registryContainsOnlyFourTargetsAndGeneralProfileIsRestricted() {
        IncidentCommandProperties incident = new IncidentCommandProperties();
        incident.setEnabled(true);
        incident.setRecoveryPlannerEnabled(true);
        ExecutionTargetRegistry registry = new ExecutionTargetRegistry(incident);

        var targets = registry.enabledTargets(principal());

        assertEquals(4, targets.size());
        var general = registry.findEnabled(principal(), ExecutionTargetId.GENERAL_AGENT.name()).orElseThrow();
        assertEquals("general-safe-v1", general.executionProfileId());
        assertFalse(general.supportedIntents().stream().anyMatch(value -> value.contains("INCIDENT")));
        var investigation = registry.findEnabled(
                principal(), ExecutionTargetId.INCIDENT_INVESTIGATION.name()).orElseThrow();
        assertTrue(investigation.requiredInputs().contains("oneOf:requestIds,timeExpression,orderNo"));
        assertFalse(investigation.requiredInputs().contains("oneOf:batchId,requestIds"));
    }

    @Test
    void incidentAlwaysRequiresConfirmationAndModelInferredIdentifiersCannotPass() {
        IncidentCommandProperties incident = new IncidentCommandProperties();
        incident.setEnabled(true);
        incident.setRecoveryPlannerEnabled(true);
        WorkbenchRoutingProperties properties = new WorkbenchRoutingProperties();
        RoutePolicyValidator validator = new RoutePolicyValidator(
                new ExecutionTargetRegistry(incident), properties, objectMapper);
        AgentWorkItem work = work("调查 REQ-1，队列 q.incident");
        ExecutionDecision valid = new ExecutionDecision(
                "INCIDENT_INVESTIGATION", .99, "incident",
                Map.of("requestIds", List.of("REQ-1"), "queueName", "q.incident"), List.of(), "preview");

        var accepted = validator.validate(valid,
                new RouteValidationContext(principal(), work, work.originalGoal(), Map.of(), Map.of()));
        assertEquals(RouteDisposition.REQUIRE_CONFIRMATION, accepted.disposition());

        ExecutionDecision invented = new ExecutionDecision(
                "INCIDENT_INVESTIGATION", .99, "incident",
                Map.of("requestIds", List.of("REQ-INVENTED"), "queueName", "q.incident"), List.of(), "preview");
        var clarified = validator.validate(invented,
                new RouteValidationContext(principal(), work, work.originalGoal(), Map.of(), Map.of()));
        assertEquals(RouteDisposition.REQUIRE_CLARIFICATION, clarified.disposition());

        ExecutionDecision unresolvedBatch = new ExecutionDecision(
                "INCIDENT_INVESTIGATION", .99, "incident",
                Map.of("batchId", "BATCH-1", "queueName", "q.incident"), List.of(), "preview");
        var batchClarification = validator.validate(unresolvedBatch,
                new RouteValidationContext(principal(), work,
                        "调查 BATCH-1，队列 q.incident", Map.of(), Map.of()));
        assertEquals(RouteDisposition.REQUIRE_CLARIFICATION, batchClarification.disposition());
        assertTrue(batchClarification.reasons().get(0).contains("requestIds"));
    }

    @Test
    void routerRejectsFallbackAndPreservesObservableUsage() {
        FakeLlm llm = new FakeLlm("{}", new LlmUsage(55, 9, 64, 0, 0, "deepseek-chat", "fallback"));
        LlmUnifiedTaskRouter router = new LlmUnifiedTaskRouter(llm, objectMapper);
        IncidentCommandProperties incident = new IncidentCommandProperties();
        var registry = new ExecutionTargetRegistry(incident);

        RouterInvocationException failure = assertThrows(RouterInvocationException.class,
                () -> router.route(new RoutingModelRequest(
                        work("解释 CAS"), "解释 CAS", registry.enabledTargets(principal()), "")));

        assertEquals("MODEL_FALLBACK", failure.failureCode());
        assertEquals(64, failure.observation().promptTokens() + failure.observation().completionTokens());
        assertFalse(failure.observation().promptDigest().isBlank());
    }

    @Test
    void invalidStructuredOutputPreservesTokensAndGetsDistinctFailureCode() {
        FakeLlm llm = new FakeLlm("not-json", usage("deepseek-chat", 44, 7));
        LlmUnifiedTaskRouter router = new LlmUnifiedTaskRouter(llm, objectMapper);
        IncidentCommandProperties incident = new IncidentCommandProperties();

        RouterInvocationException failure = assertThrows(RouterInvocationException.class,
                () -> router.route(new RoutingModelRequest(
                        work("解释 CAS"), "解释 CAS",
                        new ExecutionTargetRegistry(incident).enabledTargets(principal()), "")));

        assertEquals("STRUCTURED_OUTPUT_INVALID", failure.failureCode());
        assertEquals(51, failure.observation().promptTokens() + failure.observation().completionTokens());
        assertFalse(failure.observation().rawOutputDigest().isBlank());
    }

    private AgentConversationTurn input(String content) {
        return new AgentConversationTurn(
                "input-1", "client-1", "conversation-1", "tenant-1", "alice", content,
                "content-digest", "request-digest", GoalOrigin.DIRECT_NORMAL_GOAL, "", "", null,
                Instant.now(), WorkInputKind.UNCLASSIFIED, null, "", InputClassificationStatus.PENDING,
                "", null, Set.of("INCIDENT_OPERATOR"), 0);
    }

    private AgentWorkItem work(String goal) {
        Instant now = Instant.now();
        return new AgentWorkItem(
                "work-1", "conversation-1", "tenant-1", "alice", goal, goal,
                WorkControlState.ROUTING, WorkExecutionState.NOT_STARTED, WorkOutcome.UNDETERMINED,
                "", "", "", "", "", "input-1", "", "route-1", 0,
                null, null, "", "", 0, 0, now, now, null);
    }

    private AuthenticatedPrincipal principal() {
        return new AuthenticatedPrincipal("tenant-1", "alice", Set.of("INCIDENT_OPERATOR"));
    }

    private LlmUsage usage(String model, long prompt, long completion) {
        return new LlmUsage(prompt, completion, prompt + completion, 0, 0, model, "provider");
    }

    private static final class FakeLlm implements LlmService {
        private final String response;
        private final LlmUsage usage;
        private int calls;

        private FakeLlm(String response, LlmUsage usage) {
            this.response = response;
            this.usage = usage;
        }

        @Override public String complete(PromptRequest promptRequest) { calls++; return response; }
        @Override public Flux<String> stream(PromptRequest promptRequest) { return Flux.just(response); }
        @Override public Optional<LlmUsage> lastUsage() { return Optional.of(usage); }
    }
}
